from fastapi import FastAPI, File, UploadFile, HTTPException, Body, Query, Header, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, StreamingResponse, Response
from contextlib import asynccontextmanager, contextmanager
from ultralytics import YOLO
import cv2
import numpy as np
import math
import datetime
import sqlite3
import os
import time
import threading
import socket
import base64
import hashlib
import hmac
import secrets
from collections import defaultdict, Counter
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token
import cachecontrol
import requests

try:
    from zeroconf import IPVersion, ServiceInfo, Zeroconf
except ImportError:
    IPVersion = ServiceInfo = Zeroconf = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    global model
    model = YOLO("yolov8n.pt")
    start_camera_discovery()
    try:
        yield
    finally:
        stop_camera_capture()
        stop_camera_discovery()


app = FastAPI(
    title="PetGuard AI Backend",
    description="API Completa com ROI, Re-ID Visual HSV, Controle YOLO e Métricas de Atividade Física",
    version="5.0.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

DB_PATH = "petguard_local.db"
FIREBASE_PROJECT_ID = os.getenv("FIREBASE_PROJECT_ID", "petguard-ai-133cd").strip()
STREAM_TOKEN_TTL_SECONDS = max(60, min(3600, int(os.getenv("PETGUARD_STREAM_TOKEN_TTL_SECONDS", "1800"))))
_stream_signing_secret = os.getenv("PETGUARD_STREAM_SIGNING_SECRET", "").encode("utf-8") or secrets.token_bytes(32)
_google_session = cachecontrol.CacheControl(requests.Session())
_google_auth_request = google_requests.Request(session=_google_session)

history_classes = defaultdict(list)
alert_lock = threading.Lock()
camera_control_lock = threading.Lock()
camera_state_lock = threading.Lock()
yolo_inference_lock = threading.Lock()
pet_assignment_lock = threading.Lock()
camera_stop_event = threading.Event()
camera_stop_event.set()

camera_thread = None
latest_camera_jpeg = None
latest_raw_frame = None  # Frame limpo sem bounding boxes para snapshot de ROI e Re-ID
latest_annotated_frame = None
camera_status = "desconectada"
yolo_enabled = True

discovery_server = None
discovery_info = None
discovery_ready = False

latest_pet_detections = []
latest_track_boxes = {}       # {track_id: [x1, y1, x2, y2]}
track_species_votes = defaultdict(Counter)
track_species_locks = {}
track_species_lock = threading.Lock()
pet_assignments = {}          # {track_id: {"pet_id": ..., "pet_name": ..., "pet_species": ..., "pet_size": ...}}
pet_visual_signatures = {}    # {pet_id: {"pet_name": ..., "pet_species": ..., "pet_size": ..., "hist": np.ndarray}}

water_interaction_sessions = {}
food_interaction_sessions = {}
PET_MONITOR_CHECK_AT = {}
pet_presence_states = {}

HYDRATION_ALERT_AFTER_SECONDS = max(10, int(os.getenv("PETGUARD_HYDRATION_ALERT_SECONDS", "28800")))
FEEDING_ALERT_AFTER_SECONDS = max(10, int(os.getenv("PETGUARD_FEEDING_ALERT_SECONDS", "43200")))
_demo_routine_seconds = os.getenv("PETGUARD_DEMO_ROUTINE_SECONDS", "").strip()
try:
    DEMO_ROUTINE_SECONDS = max(1, int(_demo_routine_seconds)) if _demo_routine_seconds else None
except ValueError:
    DEMO_ROUTINE_SECONDS = None
PRESENCE_LOST_AFTER_SECONDS = max(5, int(os.getenv("PETGUARD_PRESENCE_LOST_SECONDS", "120")))
REID_SIMILARITY_THRESHOLD = float(os.getenv("PETGUARD_REID_THRESHOLD", "0.68"))

# Armazenamento de posições anteriores e sessões na memória do processo
posicoes_anteriores = {}  # {track_id: (x, y)}
live_prev_positions = {}  # {track_id: (x, y)}
sessoes_interacao = {}    # {track_id: {"tipo": "ALIMENTACAO", "inicio": dt, "ultima_vista": dt}}


def require_firebase_user(authorization: str | None = Header(default=None)):
    """Valida um ID token Firebase válido; contas não precisam de liberação manual."""
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Autenticação necessária.", headers={"WWW-Authenticate": "Bearer"})
    token = authorization[7:].strip()
    if not token or len(token) > 8192:
        raise HTTPException(status_code=401, detail="Sessão inválida.", headers={"WWW-Authenticate": "Bearer"})
    if os.getenv("DEV_SKIP_AUTH") == "1" and token == "dev-local-token":
        return {"sub": "dev-local-user"}
    try:
        claims = id_token.verify_firebase_token(token, _google_auth_request, audience=FIREBASE_PROJECT_ID)
    except Exception:
        raise HTTPException(status_code=401, detail="Sessão inválida ou expirada.", headers={"WWW-Authenticate": "Bearer"})
    if not claims.get("sub"):
        raise HTTPException(status_code=401, detail="Sessão Firebase inválida.", headers={"WWW-Authenticate": "Bearer"})
    return claims


def require_camera_owner(user: dict = Depends(require_firebase_user)):
    """Vincula esta API ao primeiro UID Firebase autorizado a iniciar a câmera."""
    uid = str(user["sub"])
    with camera_control_lock:
        with get_db() as conn:
            row = conn.execute("SELECT firebase_uid FROM api_camera_owner WHERE owner_id = 1").fetchone()
            if row is None:
                conn.execute("INSERT INTO api_camera_owner (owner_id, firebase_uid) VALUES (1, ?)", (uid,))
            elif not hmac.compare_digest(str(row["firebase_uid"]), uid):
                raise HTTPException(status_code=403, detail="Esta câmera está vinculada a outra conta PetGuard.")
    return user


def _verify_stream_owner(stream_token: str):
    uid = _verify_stream_token(stream_token)
    with get_db() as conn:
        row = conn.execute("SELECT firebase_uid FROM api_camera_owner WHERE owner_id = 1").fetchone()
    if row is None or not hmac.compare_digest(str(row["firebase_uid"]), uid):
        raise HTTPException(status_code=403, detail="Esta câmera está vinculada a outra conta PetGuard.")


def _encode_stream_token(payload: str) -> str:
    encoded = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii").rstrip("=")
    signature = hmac.new(_stream_signing_secret, encoded.encode("ascii"), hashlib.sha256).digest()
    signature_encoded = base64.urlsafe_b64encode(signature).decode("ascii").rstrip("=")
    return encoded + "." + signature_encoded


def _issue_stream_token(uid: str) -> str:
    expires_at = int(time.time()) + STREAM_TOKEN_TTL_SECONDS
    nonce = secrets.token_urlsafe(12)
    return _encode_stream_token(f"{uid}|{expires_at}|{nonce}")


def _verify_stream_token(token: str) -> str:
    try:
        encoded, signature_encoded = token.split(".", 1)
        supplied_signature = base64.urlsafe_b64decode(signature_encoded + "=" * (-len(signature_encoded) % 4))
        expected_signature = hmac.new(_stream_signing_secret, encoded.encode("ascii"), hashlib.sha256).digest()
        if not hmac.compare_digest(supplied_signature, expected_signature):
            raise ValueError("signature")
        payload = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)).decode("utf-8")
        uid, expires_at, _nonce = payload.split("|", 2)
        if not uid or int(expires_at) < int(time.time()):
            raise ValueError("expired")
        return uid
    except Exception:
        raise HTTPException(status_code=401, detail="Sessão da câmera expirada. Reabra a câmera pelo app.")


@contextmanager
def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db():
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS api_camera_owner (
                owner_id INTEGER PRIMARY KEY CHECK (owner_id = 1),
                firebase_uid TEXT NOT NULL UNIQUE
            )
        """)
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS zonas_roi (
                tipo TEXT PRIMARY KEY,
                x1 REAL, y1 REAL, x2 REAL, y2 REAL
            )
        """)
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS configuracao_potes (
                tipo_pote TEXT PRIMARY KEY,
                cor_predominante TEXT
            )
        """)
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS registro_eventos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT,
                pet_id INTEGER,
                especie TEXT,
                tipo_evento TEXT,
                duracao_segundos INTEGER,
                deslocamento_pixels REAL
            )
        """)
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS alertas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT,
                categoria TEXT,
                nivel_prioridade TEXT,
                mensagem TEXT,
                lido INTEGER DEFAULT 0,
                pet_id TEXT,
                pet_nome TEXT
            )
        """)
        alert_columns = {row[1] for row in cursor.execute("PRAGMA table_info(alertas)").fetchall()}
        if "pet_id" not in alert_columns:
            cursor.execute("ALTER TABLE alertas ADD COLUMN pet_id TEXT")
        if "pet_nome" not in alert_columns:
            cursor.execute("ALTER TABLE alertas ADD COLUMN pet_nome TEXT")

        cursor.execute("""
        CREATE TABLE IF NOT EXISTS estado_hidratacao (
                pet_id TEXT PRIMARY KEY,
                pet_nome TEXT NOT NULL,
                pet_porte TEXT DEFAULT 'medio',
                pet_peso REAL,
                monitoramento_desde TEXT NOT NULL,
                ultima_interacao_agua TEXT,
                ultimo_aviso TEXT
            )
        """)
        hyd_columns = {row[1] for row in cursor.execute("PRAGMA table_info(estado_hidratacao)").fetchall()}
        if "pet_porte" not in hyd_columns:
            cursor.execute("ALTER TABLE estado_hidratacao ADD COLUMN pet_porte TEXT DEFAULT 'medio'")
        if "pet_peso" not in hyd_columns:
            cursor.execute("ALTER TABLE estado_hidratacao ADD COLUMN pet_peso REAL")

        cursor.execute("""
            CREATE TABLE IF NOT EXISTS estado_alimentacao (
                pet_id TEXT PRIMARY KEY,
                pet_nome TEXT NOT NULL,
                pet_porte TEXT DEFAULT 'medio',
                pet_peso REAL,
                monitoramento_desde TEXT NOT NULL,
                ultima_interacao_comida TEXT,
                ultimo_aviso TEXT
            )
        """)
        feed_columns = {row[1] for row in cursor.execute("PRAGMA table_info(estado_alimentacao)").fetchall()}
        if "pet_porte" not in feed_columns:
            cursor.execute("ALTER TABLE estado_alimentacao ADD COLUMN pet_porte TEXT DEFAULT 'medio'")
        if "pet_peso" not in feed_columns:
            cursor.execute("ALTER TABLE estado_alimentacao ADD COLUMN pet_peso REAL")

        conn.commit()


# ==============================================================================
# RE-IDENTIFICAÇÃO VISUAL POR HISTOGRAMA HSV (SOLUÇÃO 1)
# ==============================================================================

def extract_pet_color_signature(frame: np.ndarray, box: list | tuple) -> np.ndarray | None:
    """
    Recorta os 60% centrais da bounding box do animal (descartando 20% das bordas
    para evitar interferência de piso/cobertor) e calcula um histograma HSV normalizado.
    """
    if frame is None or box is None or len(box) < 4:
        return None
    h, w = frame.shape[:2]
    x1, y1, x2, y2 = [float(v) for v in box[:4]]
    bw = x2 - x1
    bh = y2 - y1
    if bw < 12 or bh < 12:
        return None

    cx1 = max(0, int(x1 + bw * 0.20))
    cy1 = max(0, int(y1 + bh * 0.20))
    cx2 = min(w, int(x2 - bw * 0.20))
    cy2 = min(h, int(y2 - bh * 0.20))

    if cx2 <= cx1 or cy2 <= cy1:
        return None

    crop = frame[cy1:cy2, cx1:cx2]
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1, 2], None, [16, 16, 8], [0, 180, 0, 256, 0, 256])
    cv2.normalize(hist, hist, alpha=0, beta=1, norm_type=cv2.NORM_MINMAX)
    return hist


def match_pet_by_visual_signature(
    frame: np.ndarray,
    box: list | tuple,
    expected_species: str,
    already_used_pet_ids: set[str],
) -> dict | None:
    """
    Compara o histograma atual com as assinaturas salvas da mesma espécie.
    Retorna o dicionário do pet se atingir o limiar de similaridade.
    """
    if frame is None or not pet_visual_signatures:
        return None
    candidate_hist = extract_pet_color_signature(frame, box)
    if candidate_hist is None:
        return None

    best_score = -1.0
    best_pet = None
    for pet_id, sig in pet_visual_signatures.items():
        if pet_id in already_used_pet_ids:
            continue
        if sig.get("pet_species") != expected_species:
            continue
        score = float(cv2.compareHist(candidate_hist, sig["hist"], cv2.HISTCMP_CORREL))
        if score > best_score:
            best_score = score
            best_pet = {
                "pet_id": pet_id,
                "pet_name": sig["pet_name"],
                "pet_species": sig["pet_species"],
                "pet_size": sig.get("pet_size", "medio"),
                "pet_weight": sig.get("pet_weight"),
            }

    if best_pet is not None and best_score >= REID_SIMILARITY_THRESHOLD:
        return best_pet
    return None


def classify_activity(displacement: float) -> str:
    if displacement < 2.0:
        return "Repouso / Inativo"
    if displacement < 15.0:
        return "Atividade Moderada"
    return "Movimentação Intensa"


# ==============================================================================
# ALERTAS E MONITORAMENTO DE ROTINA
# ==============================================================================

def create_pet_alert(category, message, pet_id, pet_name, priority="informativo"):
    if not pet_id or not pet_name:
        return
    with get_db() as conn:
        conn.execute(
            "INSERT INTO alertas (timestamp, categoria, nivel_prioridade, mensagem, pet_id, pet_nome) VALUES (?, ?, ?, ?, ?, ?)",
            (datetime.datetime.now().astimezone().isoformat(timespec="seconds"), category, priority, message, pet_id, pet_name),
        )


def load_roi_zones():
    with get_db() as conn:
        return {
            row["tipo"]: {"x1": row["x1"], "y1": row["y1"], "x2": row["x2"], "y2": row["y2"]}
            for row in conn.execute("SELECT tipo, x1, y1, x2, y2 FROM zonas_roi").fetchall()
        }


def normalize_legacy_zone(zone):
    if max(zone.values()) > 1.0:
        return {key: max(0.0, min(1.0, value / 640.0)) for key, value in zone.items()}
    return zone


def point_inside_normalized_zone(point, zone, frame_width, frame_height):
    x1 = zone["x1"] * frame_width
    x2 = zone["x2"] * frame_width
    y1 = zone["y1"] * frame_height
    y2 = zone["y2"] * frame_height
    return x1 <= point[0] <= x2 and y1 <= point[1] <= y2


def box_intersects_normalized_zone(box, zone, frame_width, frame_height):
    """Treat a pet as visiting a bowl zone when its bounding box overlaps it."""
    box_x1, box_y1, box_x2, box_y2 = (float(value) for value in box[:4])
    zone_x1 = zone["x1"] * frame_width
    zone_x2 = zone["x2"] * frame_width
    zone_y1 = zone["y1"] * frame_height
    zone_y2 = zone["y2"] * frame_height
    return box_x1 <= zone_x2 and box_x2 >= zone_x1 and box_y1 <= zone_y2 and box_y2 >= zone_y1


def get_pet_alert_thresholds(pet_size: str, pet_weight: float | None):
    """Return water and food alert thresholds adjusted for the pet's size/weight."""
    if DEMO_ROUTINE_SECONDS is not None:
        return DEMO_ROUTINE_SECONDS, DEMO_ROUTINE_SECONDS
    size = (pet_size or "medio").strip().casefold()
    if pet_weight is not None:
        factor = 0.75 if pet_weight < 10 else 1.25 if pet_weight > 25 else 1.0
    elif size == "pequeno":
        factor = 0.75
    elif size == "grande":
        factor = 1.25
    else:
        factor = 1.0
    return (
        max(1, int(HYDRATION_ALERT_AFTER_SECONDS * factor)),
        max(1, int(FEEDING_ALERT_AFTER_SECONDS * factor)),
    )


def ensure_hydration_monitoring(pet_id, pet_name, now, pet_size="medio", pet_weight=None):
    with get_db() as conn:
        conn.execute(
            "INSERT OR IGNORE INTO estado_hidratacao (pet_id, pet_nome, pet_porte, pet_peso, monitoramento_desde) VALUES (?, ?, ?, ?, ?)",
            (pet_id, pet_name, pet_size, pet_weight, now.isoformat(timespec="seconds")),
        )
        conn.execute("UPDATE estado_hidratacao SET pet_nome = ?, pet_porte = ?, pet_peso = ? WHERE pet_id = ?", (pet_name, pet_size, pet_weight, pet_id))


def record_water_interaction(pet_id, pet_name, now, pet_size="medio", pet_weight=None):
    with get_db() as conn:
        conn.execute(
            "INSERT OR IGNORE INTO estado_hidratacao (pet_id, pet_nome, pet_porte, pet_peso, monitoramento_desde) VALUES (?, ?, ?, ?, ?)",
            (pet_id, pet_name, pet_size, pet_weight, now.isoformat(timespec="seconds")),
        )
        conn.execute(
            "UPDATE estado_hidratacao SET pet_nome = ?, pet_porte = ?, pet_peso = ?, ultima_interacao_agua = ?, ultimo_aviso = NULL WHERE pet_id = ?",
            (pet_name, pet_size, pet_weight, now.isoformat(timespec="seconds"), pet_id),
        )


def ensure_feeding_monitoring(pet_id, pet_name, now, pet_size="medio", pet_weight=None):
    with get_db() as conn:
        conn.execute(
            "INSERT OR IGNORE INTO estado_alimentacao (pet_id, pet_nome, pet_porte, pet_peso, monitoramento_desde) VALUES (?, ?, ?, ?, ?)",
            (pet_id, pet_name, pet_size, pet_weight, now.isoformat(timespec="seconds")),
        )
        conn.execute("UPDATE estado_alimentacao SET pet_nome = ?, pet_porte = ?, pet_peso = ? WHERE pet_id = ?", (pet_name, pet_size, pet_weight, pet_id))


def record_food_interaction(pet_id, pet_name, now, pet_size="medio", pet_weight=None):
    with get_db() as conn:
        conn.execute(
            "INSERT OR IGNORE INTO estado_alimentacao (pet_id, pet_nome, pet_porte, pet_peso, monitoramento_desde) VALUES (?, ?, ?, ?, ?)",
            (pet_id, pet_name, pet_size, pet_weight, now.isoformat(timespec="seconds")),
        )
        conn.execute(
            "UPDATE estado_alimentacao SET pet_nome = ?, pet_porte = ?, pet_peso = ?, ultima_interacao_comida = ?, ultimo_aviso = NULL WHERE pet_id = ?",
            (pet_name, pet_size, pet_weight, now.isoformat(timespec="seconds"), pet_id),
        )


def _format_duration(seconds):
    if seconds < 60:
        return f"{seconds} s"
    if seconds < 3600:
        return f"{max(1, seconds // 60)} min"
    hours = seconds / 3600
    return f"{int(hours)} h" if hours.is_integer() else f"{hours:.1f} h"


def check_pet_routine_alerts(now):
    if camera_stop_event.is_set():
        return
    zones = load_roi_zones()
    with camera_state_lock:
        visible_pet_ids = {item["pet_id"] for item in latest_pet_detections if item.get("pet_id")}
    with pet_assignment_lock:
        assigned_pets = {
            value["pet_id"]: (value["pet_name"], value.get("pet_size", "medio"), value.get("pet_weight"))
            for value in pet_assignments.values()
        }
    for pet_id, (pet_name, pet_size, pet_weight) in assigned_pets.items():
        if pet_id not in visible_pet_ids:
            continue
        now_monotonic = time.monotonic()
        last_check = PET_MONITOR_CHECK_AT.get(pet_id, 0.0)
        if now_monotonic - last_check < 5:
            continue
        PET_MONITOR_CHECK_AT[pet_id] = now_monotonic
        hydration_threshold, feeding_threshold = get_pet_alert_thresholds(pet_size, pet_weight)
        checks = (
            ("agua", "estado_hidratacao", "ultima_interacao_agua", hydration_threshold, "HIDRATACAO"),
            ("comida", "estado_alimentacao", "ultima_interacao_comida", feeding_threshold, "ALIMENTACAO"),
        )
        for zone_name, table, interaction_column, threshold, category in checks:
            if zone_name not in zones:
                continue
            if table == "estado_hidratacao":
                ensure_hydration_monitoring(pet_id, pet_name, now, pet_size, pet_weight)
            else:
                ensure_feeding_monitoring(pet_id, pet_name, now, pet_size, pet_weight)
            with get_db() as conn:
                state = conn.execute(
                    f"SELECT monitoramento_desde, {interaction_column}, ultimo_aviso FROM {table} WHERE pet_id = ?",
                    (pet_id,),
                ).fetchone()
            reference = state[interaction_column] or state["monitoramento_desde"]
            elapsed = (now - datetime.datetime.fromisoformat(reference)).total_seconds()
            if elapsed < threshold or state["ultimo_aviso"]:
                continue
            elapsed_label = _format_duration(threshold)
            if category == "HIDRATACAO":
                message = f"A câmera não registrou {pet_name} (porte {pet_size}{f', {pet_weight:g} kg' if pet_weight is not None else ''}) na zona de água há {elapsed_label}. Confira se há água disponível."
            else:
                message = f"A câmera não registrou {pet_name} (porte {pet_size}{f', {pet_weight:g} kg' if pet_weight is not None else ''}) na zona de ração há {elapsed_label}. Confirme se ele comeu; se realmente não comeu, procure orientação veterinária."
            create_pet_alert(category, message, pet_id, pet_name, "atencao")
            with get_db() as conn:
                conn.execute(f"UPDATE {table} SET ultimo_aviso = ? WHERE pet_id = ?", (now.isoformat(timespec="seconds"), pet_id))


def update_pet_presence_alerts(detections):
    now = time.monotonic()
    visible_pets = {
        item["pet_id"]: item["pet_name"]
        for item in detections
        if item.get("pet_id") and item.get("pet_name")
    }
    for pet_id, pet_name in visible_pets.items():
        state = pet_presence_states.get(pet_id)
        if state is None:
            pet_presence_states[pet_id] = {"visible": True, "last_seen": now, "pet_name": pet_name}
            create_pet_alert("PRESENCA", f"{pet_name} foi detectado pela câmera.", pet_id, pet_name)
        elif not state["visible"]:
            state.update(visible=True, last_seen=now, pet_name=pet_name)
            create_pet_alert("PRESENCA", f"{pet_name} voltou a aparecer na câmera.", pet_id, pet_name)
        else:
            state.update(last_seen=now, pet_name=pet_name)

    for pet_id, state in pet_presence_states.items():
        if state["visible"] and pet_id not in visible_pets and now - state["last_seen"] >= PRESENCE_LOST_AFTER_SECONDS:
            state["visible"] = False
            pet_name = state.get("pet_name", "Seu pet")
            create_pet_alert(
                "PRESENCA",
                f"{pet_name} não aparece na câmera há {_format_duration(PRESENCE_LOST_AFTER_SECONDS)}.",
                pet_id,
                pet_name,
            )


def stabilize_track_species(track_id: int, candidate: str) -> str:
    """Vote per track and permanently lock dog/cat after three matching detections."""
    with track_species_lock:
        locked = track_species_locks.get(track_id)
        if locked:
            return locked
        votes = track_species_votes[track_id]
        votes[candidate] += 1
        if votes[candidate] >= 3:
            track_species_locks[track_id] = candidate
            return candidate
        best_count = max(votes.values())
        leaders = [species for species, count in votes.items() if count == best_count]
        return candidate if candidate in leaders else leaders[0]


def draw_detection_labels(frame: np.ndarray, overlay_items: list[dict]) -> np.ndarray:
    """Draw stable species/pet labels over the latest tracked boxes."""
    for item in overlay_items:
        x1, y1, x2, y2 = (int(round(value)) for value in item["box"])
        color = (40, 190, 90) if item.get("pet_name") else (0, 170, 220)
        cv2.rectangle(frame, (x1, y1), (x2, y2), color, 2, cv2.LINE_AA)
        label_name = item.get("pet_name") or ("Cachorro" if item["species"] == "dog" else "Gato")
        confidence = int(round(float(item.get("confidence", 0.0)) * 100))
        label = f"{label_name} {confidence}%"
        (text_width, text_height), baseline = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.55, 2)
        label_top = max(0, y1 - text_height - baseline - 6)
        cv2.rectangle(frame, (x1, label_top), (x1 + text_width + 8, label_top + text_height + baseline + 6), color, -1)
        cv2.putText(frame, label, (x1 + 4, label_top + text_height + 2), cv2.FONT_HERSHEY_SIMPLEX,
                    0.55, (255, 255, 255), 2, cv2.LINE_AA)
    return frame


def update_live_pet_detections(result, frame_shape, raw_frame: np.ndarray | None = None):
    global latest_pet_detections, latest_track_boxes
    boxes = getattr(result, "boxes", None)
    detections = []
    current_boxes = {}
    now = datetime.datetime.now().astimezone()

    if boxes is not None and boxes.cls is not None and boxes.id is not None:
        frame_height, frame_width = frame_shape[:2]
        zones = {key: normalize_legacy_zone(value) for key, value in load_roi_zones().items()}
        box_values = boxes.xyxy.cpu().tolist()
        classes = boxes.cls.cpu().tolist()
        confidences = boxes.conf.cpu().tolist()
        track_ids = boxes.id.int().cpu().tolist()

        # Coleta quais pet_ids já estão ativamente vinculados a track_ids visíveis neste frame
        active_track_set = {int(tid) for tid in track_ids}
        with pet_assignment_lock:
            already_used_pet_ids = {
                v["pet_id"]
                for tid, v in pet_assignments.items()
                if tid in active_track_set and v.get("pet_id")
            }

        for box, class_id, confidence, track_id in zip(box_values, classes, confidences, track_ids):
            detected_species = model.names[int(class_id)]
            if detected_species not in ("dog", "cat"):
                continue

            tid = int(track_id)
            species = stabilize_track_species(tid, detected_species)
            current_boxes[tid] = [float(v) for v in box[:4]]
            center = get_center(box)

            # Cálculo de deslocamento e atividade em tempo real para a tela Início
            displacement = 0.0
            if tid in live_prev_positions:
                prev_x, prev_y = live_prev_positions[tid]
                displacement = math.hypot(center[0] - prev_x, center[1] - prev_y)
            live_prev_positions[tid] = center
            activity_label = classify_activity(displacement)

            interaction = next(
                (
                    "ALIMENTACAO" if kind == "comida" else "HIDRATACAO"
                    for kind, zone in zones.items()
                    if box_intersects_normalized_zone(box, zone, frame_width, frame_height)
                ),
                None,
            )

            expected_species = "cachorro" if species == "dog" else "gato"
            with pet_assignment_lock:
                assignment = pet_assignments.get(tid, {}).copy()
                if assignment and assignment.get("pet_species") != expected_species:
                    assignment = {}

                # Re-ID automático via assinatura HSV caso seja um novo track_id sem dono
                if not assignment and raw_frame is not None:
                    matched = match_pet_by_visual_signature(raw_frame, box, expected_species, already_used_pet_ids)
                    if matched:
                        pet_assignments[tid] = matched
                        assignment = matched.copy()
                        already_used_pet_ids.add(matched["pet_id"])

            detection = {
                "track_id": tid,
                "species": species,
                "confidence": round(float(confidence), 3),
                "interaction": interaction,
                "activity": activity_label,
                "nivel_atividade": activity_label,
                "displacement_pixels": round(displacement, 2),
                "pet_id": assignment.get("pet_id", ""),
                "pet_name": assignment.get("pet_name", ""),
                "pet_size": assignment.get("pet_size", ""),
                "pet_weight": assignment.get("pet_weight"),
            }
            detections.append(detection)

            if interaction in ("HIDRATACAO", "ALIMENTACAO") and detection["pet_id"]:
                sessions = water_interaction_sessions if interaction == "HIDRATACAO" else food_interaction_sessions
                session = sessions.get(tid)
                if session is None or session["pet_id"] != detection["pet_id"]:
                    sessions[tid] = {
                        "pet_id": detection["pet_id"],
                        "pet_name": detection["pet_name"],
                        "pet_size": detection.get("pet_size") or "medio",
                        "pet_weight": detection.get("pet_weight"),
                        "started_at": now,
                        "last_seen": now,
                        "recorded": False,
                    }
                else:
                    session["last_seen"] = now
                session = sessions[tid]
                duration = (now - session["started_at"]).total_seconds()
                if duration >= 2 and not session["recorded"]:
                    recorder = record_water_interaction if interaction == "HIDRATACAO" else record_food_interaction
                    category_label = "zona de água" if interaction == "HIDRATACAO" else "zona de ração"
                    recorder(detection["pet_id"], detection["pet_name"], now, session["pet_size"], session.get("pet_weight"))
                    create_pet_alert(
                        interaction,
                        f"A câmera registrou {detection['pet_name']} na {category_label}. Isso confirma presença na área, não ingestão.",
                        detection["pet_id"],
                        detection["pet_name"],
                    )
                    session["recorded"] = True

    active_tracks = {item["track_id"] for item in detections}
    for old_tid in list(live_prev_positions.keys()):
        if old_tid not in active_tracks:
            live_prev_positions.pop(old_tid, None)

    for sessions, interaction_name, recorder in (
        (water_interaction_sessions, "HIDRATACAO", record_water_interaction),
        (food_interaction_sessions, "ALIMENTACAO", record_food_interaction),
    ):
        for track_id, session in list(sessions.items()):
            if track_id in active_tracks and next((d["interaction"] for d in detections if d["track_id"] == track_id), None) == interaction_name:
                continue
            # Keep the session through a brief detector miss, then close it after a 1 s grace period.
            if (now - session["last_seen"]).total_seconds() < 1:
                continue
            duration = (session["last_seen"] - session["started_at"]).total_seconds()
            if duration >= 2 and not session["recorded"]:
                recorder(session["pet_id"], session["pet_name"], now, session.get("pet_size", "medio"), session.get("pet_weight"))
                category_label = "zona de água" if interaction_name == "HIDRATACAO" else "zona de ração"
                create_pet_alert(
                    interaction_name,
                    f"A câmera registrou {session['pet_name']} na {category_label}. Isso confirma presença na área, não ingestão.",
                    session["pet_id"],
                    session["pet_name"],
                )
            del sessions[track_id]

    with camera_state_lock:
        latest_pet_detections = detections
        latest_track_boxes = current_boxes
    update_pet_presence_alerts(detections)
    check_pet_routine_alerts(now)
    return [
        {"box": current_boxes[item["track_id"]], "species": item["species"],
         "pet_name": item["pet_name"], "confidence": item["confidence"]}
        for item in detections if item["track_id"] in current_boxes
    ]


def get_center(box):
    return ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2)


def ponto_dentro_da_roi(ponto, roi):
    px, py = ponto
    return roi["x1"] <= px <= roi["x2"] and roi["y1"] <= py <= roi["y2"]


# ==============================================================================
# ENDPOINTS DA API
# ==============================================================================

@app.post("/configurar-rois")
def configurar_rois(payload: dict = Body(...), user: dict = Depends(require_camera_owner)):
    """Salva zonas normalizadas (0..1) sobre o quadro da câmera."""
    if not isinstance(payload, dict) or any(kind not in ("comida", "agua") for kind in payload):
        raise HTTPException(status_code=422, detail="Use apenas as zonas 'comida' e 'agua'.")
    validated = {}
    for kind, coords in payload.items():
        if coords is None:
            validated[kind] = None
            continue
        try:
            values = {key: float(coords[key]) for key in ("x1", "y1", "x2", "y2")}
        except (KeyError, TypeError, ValueError):
            raise HTTPException(status_code=422, detail=f"Coordenadas inválidas para a zona {kind}.")
        if max(values.values()) > 1.0:
            values = {key: value / 640.0 for key, value in values.items()}
        if not all(0.0 <= value <= 1.0 for value in values.values()) or values["x1"] >= values["x2"] or values["y1"] >= values["y2"]:
            raise HTTPException(status_code=422, detail=f"A zona {kind} precisa ser um retângulo dentro da imagem.")
        validated[kind] = values
    with get_db() as conn:
        cursor = conn.cursor()
        for kind, coords in validated.items():
            if coords is None:
                cursor.execute("DELETE FROM zonas_roi WHERE tipo = ?", (kind,))
            else:
                cursor.execute(
                    "INSERT OR REPLACE INTO zonas_roi (tipo, x1, y1, x2, y2) VALUES (?, ?, ?, ?, ?)",
                    (kind, coords["x1"], coords["y1"], coords["x2"], coords["y2"]),
                )
        if "agua" in validated:
            conn.execute(
                "UPDATE estado_hidratacao SET monitoramento_desde = ?, ultima_interacao_agua = NULL, ultimo_aviso = NULL",
                (datetime.datetime.now().astimezone().isoformat(timespec="seconds"),),
            )
        if "comida" in validated:
            conn.execute(
                "UPDATE estado_alimentacao SET monitoramento_desde = ?, ultima_interacao_comida = NULL, ultimo_aviso = NULL",
                (datetime.datetime.now().astimezone().isoformat(timespec="seconds"),),
            )
        conn.commit()
    return {"zonas": load_roi_zones()}


@app.get("/configurar-rois")
def obter_rois(user: dict = Depends(require_camera_owner)):
    return {"zonas": {key: normalize_legacy_zone(value) for key, value in load_roi_zones().items()}}


@app.get("/detections")
def obter_deteccoes_ao_vivo(user: dict = Depends(require_camera_owner)):
    with camera_state_lock:
        detections = [item.copy() for item in latest_pet_detections]
        status = camera_status
        is_yolo_active = yolo_enabled
    return {"camera": status, "yolo_enabled": is_yolo_active, "detections": detections}


@app.post("/detections/assign")
def associar_deteccao_a_pet(payload: dict = Body(...), user: dict = Depends(require_camera_owner)):
    try:
        track_id = int(payload["track_id"])
    except (KeyError, TypeError, ValueError):
        raise HTTPException(status_code=422, detail="Identificador de rastreamento inválido.")
    pet_id = str(payload.get("pet_id", "")).strip()[:80]
    pet_name = " ".join(str(payload.get("pet_name", "")).split())[:60]
    forget_pet_signature = payload.get("forget_pet_signature") is True
    pet_species = str(payload.get("pet_species", "")).strip().casefold()
    pet_size = str(payload.get("pet_size") or payload.get("porte") or "medio").strip().casefold()
    if pet_size == "médio":
        pet_size = "medio"
    if pet_size not in ("pequeno", "medio", "grande"):
        pet_size = "medio"
    raw_weight = payload.get("pet_weight", payload.get("pesoKg"))
    try:
        pet_weight = None if raw_weight is None or str(raw_weight).strip() == "" else float(raw_weight)
    except (TypeError, ValueError):
        raise HTTPException(status_code=422, detail="O peso do pet deve ser um número em kg.")
    if pet_weight is not None and (not math.isfinite(pet_weight) or pet_weight <= 0 or pet_weight > 200):
        raise HTTPException(status_code=422, detail="Informe um peso válido entre 0 e 200 kg.")

    with camera_state_lock:
        current_detection = next((item for item in latest_pet_detections if item["track_id"] == track_id), None)
        current_box = latest_track_boxes.get(track_id)
        frame_snapshot = latest_raw_frame.copy() if latest_raw_frame is not None else None

    if pet_id and current_detection is None:
        raise HTTPException(status_code=409, detail="Essa detecção não está mais ativa. Selecione o animal novamente.")
    expected_species = {"dog": "cachorro", "cat": "gato"}.get(current_detection["species"]) if current_detection else None
    if pet_id and pet_species != expected_species:
        raise HTTPException(status_code=422, detail="Associe a detecção a um pet da mesma espécie.")

    old_pet_id = ""
    with pet_assignment_lock:
        previously_assigned = any(value.get("pet_id") == pet_id for value in pet_assignments.values()) if pet_id else False
        removed_assignment = pet_assignments.get(track_id)
        old_pet_id = removed_assignment.get("pet_id", "") if removed_assignment else ""
        if not pet_id or not pet_name:
            pet_assignments.pop(track_id, None)
        else:
            for other_track_id, assignment in list(pet_assignments.items()):
                if other_track_id != track_id and assignment.get("pet_id") == pet_id:
                    pet_assignments.pop(other_track_id, None)
                    water_interaction_sessions.pop(other_track_id, None)
                    food_interaction_sessions.pop(other_track_id, None)
            pet_assignments[track_id] = {
                "pet_id": pet_id,
                "pet_name": pet_name,
                "pet_species": pet_species,
                "pet_size": pet_size,
                "pet_weight": pet_weight,
            }
            # Captura e salva a assinatura visual HSV do pet para Re-ID automático futuro
            if frame_snapshot is not None and current_box is not None:
                hist = extract_pet_color_signature(frame_snapshot, current_box)
                if hist is not None:
                    pet_visual_signatures[pet_id] = {
                        "pet_name": pet_name,
                        "pet_species": pet_species,
                        "pet_size": pet_size,
                        "pet_weight": pet_weight,
                        "hist": hist,
                    }
        if forget_pet_signature and old_pet_id and old_pet_id != pet_id:
            for other_track_id, assignment in list(pet_assignments.items()):
                if assignment.get("pet_id") == old_pet_id:
                    pet_assignments.pop(other_track_id, None)
                    water_interaction_sessions.pop(other_track_id, None)
                    food_interaction_sessions.pop(other_track_id, None)
            pet_visual_signatures.pop(old_pet_id, None)

    if old_pet_id and old_pet_id != pet_id:
        with pet_assignment_lock:
            old_still_assigned = any(value.get("pet_id") == old_pet_id for value in pet_assignments.values())
            if forget_pet_signature and not old_still_assigned:
                pet_visual_signatures.pop(old_pet_id, None)
        if not old_still_assigned:
            pet_presence_states.pop(old_pet_id, None)

    if pet_id and pet_name:
        now = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        with get_db() as conn:
            conn.execute(
                "INSERT INTO estado_hidratacao (pet_id, pet_nome, pet_porte, pet_peso, monitoramento_desde, ultima_interacao_agua, ultimo_aviso) "
                "VALUES (?, ?, ?, ?, ?, NULL, NULL) ON CONFLICT(pet_id) DO UPDATE SET "
                "pet_nome = excluded.pet_nome, pet_porte = excluded.pet_porte, pet_peso = excluded.pet_peso, "
                "monitoramento_desde = CASE WHEN ? THEN excluded.monitoramento_desde ELSE estado_hidratacao.monitoramento_desde END, "
                "ultima_interacao_agua = CASE WHEN ? THEN NULL ELSE estado_hidratacao.ultima_interacao_agua END, "
                "ultimo_aviso = CASE WHEN ? THEN NULL ELSE estado_hidratacao.ultimo_aviso END",
                (pet_id, pet_name, pet_size, pet_weight, now, int(not previously_assigned), int(not previously_assigned), int(not previously_assigned)),
            )
            conn.execute(
                "INSERT INTO estado_alimentacao (pet_id, pet_nome, pet_porte, pet_peso, monitoramento_desde, ultima_interacao_comida, ultimo_aviso) "
                "VALUES (?, ?, ?, ?, ?, NULL, NULL) ON CONFLICT(pet_id) DO UPDATE SET "
                "pet_nome = excluded.pet_nome, pet_porte = excluded.pet_porte, pet_peso = excluded.pet_peso, "
                "monitoramento_desde = CASE WHEN ? THEN excluded.monitoramento_desde ELSE estado_alimentacao.monitoramento_desde END, "
                "ultima_interacao_comida = CASE WHEN ? THEN NULL ELSE estado_alimentacao.ultima_interacao_comida END, "
                "ultimo_aviso = CASE WHEN ? THEN NULL ELSE estado_alimentacao.ultimo_aviso END",
                (pet_id, pet_name, pet_size, pet_weight, now, int(not previously_assigned), int(not previously_assigned), int(not previously_assigned)),
            )
        PET_MONITOR_CHECK_AT.pop(pet_id, None)
    elif removed_assignment:
        old_pet_id = removed_assignment.get("pet_id", "")
        with pet_assignment_lock:
            still_assigned = any(value.get("pet_id") == old_pet_id for value in pet_assignments.values())
        if not still_assigned:
            pet_presence_states.pop(old_pet_id, None)

    return {"track_id": track_id, "pet_id": pet_id, "pet_name": pet_name, "pet_size": pet_size, "pet_weight": pet_weight, "reid_saved": pet_id in pet_visual_signatures}


@app.get("/pets/{pet_id}/metas")
def get_pet_daily_goals(pet_id: str, user: dict = Depends(require_camera_owner)):
    with get_db() as conn:
        row = conn.execute(
            "SELECT pet_porte, pet_peso FROM estado_hidratacao WHERE pet_id = ?",
            (pet_id[:80],),
        ).fetchone()
        if row is None:
            row = conn.execute(
                "SELECT pet_porte, pet_peso FROM estado_alimentacao WHERE pet_id = ?",
                (pet_id[:80],),
            ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Pet não encontrado. Associe uma detecção primeiro.")

    pet_size = (row["pet_porte"] or "medio").casefold()
    default_weights = {"pequeno": 6.0, "medio": 16.0, "médio": 16.0, "grande": 30.0}
    pet_weight = row["pet_peso"]
    reference_weight = float(pet_weight if pet_weight is not None else default_weights.get(pet_size, 16.0))
    hydration_ml = round(reference_weight * 55)
    calories_kcal = round(140 * (reference_weight ** 0.75))
    food_grams = round(calories_kcal / 3.5)
    return {
        "pet_id": pet_id,
        "pet_size": "medio" if pet_size == "médio" else pet_size,
        "pet_weight": pet_weight,
        "peso_referencia_kg": reference_weight,
        "meta_hidrica_ml_dia": hydration_ml,
        "meta_calorica_kcal_dia": calories_kcal,
        "densidade_energetica_kcal_g": 3.5,
        "meta_racao_gramas_dia": food_grams,
    }


@app.post("/analisar-frame")
async def analisar_frame(file: UploadFile = File(...), user: dict = Depends(require_camera_owner)):
    global posicoes_anteriores, sessoes_interacao

    contents = await file.read(8 * 1024 * 1024 + 1)
    if len(contents) > 8 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="A imagem excede o limite de 8 MB.")
    nparr = np.frombuffer(contents, np.uint8)
    frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

    if frame is None:
        raise HTTPException(status_code=400, detail="Imagem inválida.")

    results = model.track(frame, persist=True, classes=[15, 16, 45], conf=0.38, verbose=False)
    if results:
        update_live_pet_detections(results[0], frame.shape, raw_frame=frame)

    agora = datetime.datetime.now()
    pets = []

    rois = {}
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT tipo, x1, y1, x2, y2 FROM zonas_roi")
        for row in cursor.fetchall():
            rois[row["tipo"]] = normalize_legacy_zone({"x1": row["x1"], "y1": row["y1"], "x2": row["x2"], "y2": row["y2"]})

    if results[0].boxes is not None and results[0].boxes.id is not None:
        boxes = results[0].boxes.xyxy.cpu().numpy()
        clss = results[0].boxes.cls.cpu().numpy()
        confs = results[0].boxes.conf.cpu().numpy()
        track_ids = results[0].boxes.id.int().cpu().numpy()

        for box, cls_id, conf, track_id in zip(boxes, clss, confs, track_ids):
            class_name = model.names[int(cls_id)]

            if class_name in ["dog", "cat"]:
                history_classes[track_id].append(class_name)
                if len(history_classes[track_id]) > 30:
                    history_classes[track_id] = history_classes[track_id][-30:]
                especie = Counter(history_classes[track_id]).most_common(1)[0][0]

                centro_atual = get_center(box)

                deslocamento = 0.0
                if track_id in posicoes_anteriores:
                    prev_x, prev_y = posicoes_anteriores[track_id]
                    deslocamento = math.hypot(centro_atual[0] - prev_x, centro_atual[1] - prev_y)
                posicoes_anteriores[track_id] = centro_atual

                nivel_atividade = classify_activity(deslocamento)

                interagindo_tipo = None
                for tipo_zona, coords_roi in rois.items():
                    if box_intersects_normalized_zone(box, coords_roi, frame.shape[1], frame.shape[0]):
                        interagindo_tipo = "ALIMENTACAO" if tipo_zona == "comida" else "HIDRATACAO"
                        break

                if interagindo_tipo:
                    if track_id not in sessoes_interacao:
                        sessoes_interacao[track_id] = {
                            "tipo": interagindo_tipo,
                            "inicio": agora,
                            "ultima_vista": agora,
                        }
                    else:
                        sessoes_interacao[track_id]["ultima_vista"] = agora
                else:
                    if track_id in sessoes_interacao:
                        sessao = sessoes_interacao[track_id]
                        tempo_ausente = (agora - sessao["ultima_vista"]).total_seconds()
                        if tempo_ausente > 60:
                            duracao_total = int((sessao["ultima_vista"] - sessao["inicio"]).total_seconds())
                            if duracao_total >= 5:
                                with get_db() as conn:
                                    cursor = conn.cursor()
                                    cursor.execute(
                                        "INSERT INTO registro_eventos (timestamp, pet_id, especie, tipo_evento, duracao_segundos, deslocamento_pixels) VALUES (?, ?, ?, ?, ?, ?)",
                                        (sessao["inicio"].strftime("%Y-%m-%d %H:%M:%S"), int(track_id), especie, sessao["tipo"], duracao_total, 0.0),
                                    )
                                    conn.commit()
                            del sessoes_interacao[track_id]

                pets.append({
                    "id": int(track_id),
                    "especie": especie,
                    "nivel_atividade": nivel_atividade,
                    "deslocamento_pixels": round(deslocamento, 2),
                    "interagindo": interagindo_tipo if interagindo_tipo else "Nenhum",
                })

    return {
        "timestamp": agora.strftime("%Y-%m-%d %H:%M:%S"),
        "pets_detectados": pets,
        "rois_ativas": list(rois.keys()),
    }


@app.get("/health")
def health():
    """Confirma que a API terminou de carregar o modelo YOLO."""
    return {"status": "online", "yolo_enabled": yolo_enabled, "camera": camera_status}


@app.get("/alertas")
def listar_alertas(after_id: int = Query(0, ge=0), limit: int = Query(100, ge=1, le=500), user: dict = Depends(require_camera_owner)):
    """Lista alertas novos para o app; não entrega quadros nem imagens armazenadas."""
    with get_db() as conn:
        rows = conn.execute(
            "SELECT id, timestamp, categoria, nivel_prioridade, mensagem, lido, pet_id, pet_nome "
            "FROM alertas WHERE id > ? ORDER BY id ASC LIMIT ?",
            (after_id, limit),
        ).fetchall()
    return {"alertas": [dict(row) for row in rows]}


@app.post("/admin/reset-demo")
def reset_demo_data(user: dict = Depends(require_camera_owner)):
    """Limpa alertas e estados de teste antes da apresentação oficial."""
    with get_db() as conn:
        conn.execute("DELETE FROM alertas")
        conn.execute("DELETE FROM registro_eventos")
        conn.execute("DELETE FROM estado_hidratacao")
        conn.execute("DELETE FROM estado_alimentacao")
    with pet_assignment_lock:
        pet_assignments.clear()
        pet_visual_signatures.clear()
    pet_presence_states.clear()
    water_interaction_sessions.clear()
    food_interaction_sessions.clear()
    PET_MONITOR_CHECK_AT.clear()
    return {"status": "ok", "message": "Dados de teste limpos com sucesso."}


@app.post("/camera/yolo")
def set_yolo_state(payload: dict = Body(...), user: dict = Depends(require_camera_owner)):
    """Ativa ou pausa a inferência YOLO mantendo a transmissão de vídeo da câmera ativa."""
    global yolo_enabled, latest_pet_detections
    if "enabled" not in payload or not isinstance(payload["enabled"], bool):
        raise HTTPException(status_code=422, detail="Informe {'enabled': true} ou {'enabled': false}.")
    yolo_enabled = payload["enabled"]
    if not yolo_enabled:
        with camera_state_lock:
            latest_pet_detections = []
    return {"yolo_enabled": yolo_enabled}


@app.get("/camera/yolo")
def get_yolo_state(user: dict = Depends(require_camera_owner)):
    return {"yolo_enabled": yolo_enabled}


@app.get("/camera/snapshot")
def get_camera_snapshot(user: dict = Depends(require_camera_owner)):
    """Retorna o frame atual do stream, incluindo os rótulos das detecções ativas."""
    with camera_state_lock:
        annotated = latest_annotated_frame.copy() if latest_annotated_frame is not None else None
        fallback_jpeg = latest_camera_jpeg

    if annotated is not None:
        ok, encoded = cv2.imencode(".jpg", annotated, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
        if ok:
            return Response(
                content=encoded.tobytes(),
                media_type="image/jpeg",
                headers={"Cache-Control": "no-store, max-age=0"},
            )

    if fallback_jpeg:
        return Response(
            content=fallback_jpeg,
            media_type="image/jpeg",
            headers={"Cache-Control": "no-store, max-age=0"},
        )

    placeholder = _placeholder_camera_frame()
    return Response(
        content=placeholder,
        media_type="image/jpeg",
        headers={"Cache-Control": "no-store, max-age=0"},
    )


@app.post("/camera/session")
def create_camera_stream_session(user: dict = Depends(require_camera_owner)):
    return {"stream_token": _issue_stream_token(user["sub"]), "expires_in": STREAM_TOKEN_TTL_SECONDS}


@app.get("/camera", response_class=HTMLResponse)
def camera_page(stream_token: str = Query(..., min_length=20, max_length=2048)):
    """Página para WebView; a URL usa uma autorização temporária emitida após login Firebase."""
    _verify_stream_owner(stream_token)
    safe_token = stream_token.replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    status_label = "análise YOLO ativa" if yolo_enabled else "análise YOLO pausada"
    html = f"""
    <!doctype html>
    <html lang="pt-BR">
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta charset="utf-8">
        <style>
          * {{ box-sizing: border-box; }}
          body {{ margin: 0; background: #111116; color: #fff;
                 font: 14px Arial, sans-serif; }}
          .frame {{ position: relative; width: 100%; aspect-ratio: 4 / 3;
                   background: #24242c; overflow: hidden; }}
          img {{ width: 100%; height: 100%; object-fit: contain; }}
          .status {{ padding: 10px 12px; color: #d2d2dc; }}
          .error {{ color: #ff8a80; }}
          .dot {{ color: #49dc8a; }}
        </style>
      </head>
      <body>
        <div class="frame">
          <img src="/video_feed?stream_token=__STREAM_TOKEN__" alt="Transmissão da câmera"
               onerror="document.getElementById('status').textContent='Não foi possível receber o vídeo. Verifique a câmera e a API.'; document.getElementById('status').className='status error'">
        </div>
        <div id="status" class="status"><span class="dot">●</span> Câmera de monitoramento · {status_label}</div>
      </body>
    </html>
    """
    response = HTMLResponse(html.replace("__STREAM_TOKEN__", safe_token))
    response.headers["Cache-Control"] = "no-store, max-age=0"
    response.headers["Referrer-Policy"] = "no-referrer"
    return response


def _local_ipv4_address():
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("192.0.2.1", 80))
        address = probe.getsockname()[0]
        if not address.startswith("127."):
            return address
    except OSError:
        pass
    finally:
        probe.close()
    for address in socket.gethostbyname_ex(socket.gethostname())[2]:
        if not address.startswith("127."):
            return address
    raise RuntimeError("Nenhum endereço IPv4 local disponível")


def start_camera_discovery():
    global discovery_server, discovery_info, discovery_ready
    if Zeroconf is None:
        discovery_ready = False
        return
    try:
        address = _local_ipv4_address()
        discovery_server = Zeroconf(ip_version=IPVersion.V4Only)
        discovery_info = ServiceInfo(
            "_petguard-camera._tcp.local.",
            "PetGuard AI._petguard-camera._tcp.local.",
            addresses=[socket.inet_aton(address)],
            port=8001,
            properties={b"path": b"/camera", b"app": b"PetGuard AI"},
            server="petguard-ai.local.",
        )
        discovery_server.register_service(discovery_info)
        discovery_ready = True
    except Exception:
        discovery_ready = False
        if discovery_server is not None:
            discovery_server.close()
        discovery_server = None
        discovery_info = None


def stop_camera_discovery():
    global discovery_server, discovery_info, discovery_ready
    if discovery_server is not None:
        try:
            if discovery_info is not None:
                discovery_server.unregister_service(discovery_info)
        except Exception:
            pass
        discovery_server.close()
    discovery_server = None
    discovery_info = None
    discovery_ready = False


def _placeholder_camera_frame():
    frame = np.zeros((480, 640, 3), dtype=np.uint8)
    cv2.putText(frame, "Camera indisponivel", (150, 230), cv2.FONT_HERSHEY_SIMPLEX, 1, (210, 210, 220), 2)
    ok, encoded = cv2.imencode(".jpg", frame)
    return encoded.tobytes() if ok else b""


def _camera_capture_worker():
    global latest_camera_jpeg, latest_raw_frame, latest_annotated_frame, camera_status
    backend = cv2.CAP_DSHOW if os.name == "nt" else cv2.CAP_ANY
    frame_index = 0
    last_overlay_items = []
    while not camera_stop_event.is_set():
        capture = cv2.VideoCapture(0, backend)
        if not capture.isOpened():
            capture.release()
            with camera_state_lock:
                camera_status = "aguardando_camera"
                latest_camera_jpeg = _placeholder_camera_frame()
                latest_raw_frame = None
                latest_annotated_frame = None
            camera_stop_event.wait(3.0)
            continue
        capture.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        capture.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        with camera_state_lock:
            camera_status = "conectada"
        try:
            while not camera_stop_event.is_set():
                ok, frame = capture.read()
                if not ok:
                    with camera_state_lock:
                        camera_status = "reconectando"
                    break
                raw_copy = frame.copy()
                if yolo_enabled:
                    if frame_index % 3 == 0:
                        try:
                            with yolo_inference_lock:
                                results = model.track(frame, persist=True, classes=[15, 16, 45], conf=0.38, verbose=False)
                            if results:
                                last_overlay_items = update_live_pet_detections(results[0], frame.shape, raw_frame=raw_copy)
                        except Exception:
                            pass
                    frame = draw_detection_labels(frame, last_overlay_items)
                else:
                    last_overlay_items = []

                height, width = frame.shape[:2]
                if width > 640:
                    output_height = max(1, int(round(height * 640 / width)))
                    frame = cv2.resize(frame, (640, output_height), interpolation=cv2.INTER_AREA)

                encoded_ok, encoded = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
                if encoded_ok:
                    with camera_state_lock:
                        latest_camera_jpeg = encoded.tobytes()
                        latest_raw_frame = raw_copy
                        latest_annotated_frame = frame.copy()
                frame_index += 1
                camera_stop_event.wait(0.04)
        finally:
            capture.release()
    with camera_state_lock:
        camera_status = "desconectada"
        latest_camera_jpeg = None
        latest_raw_frame = None
        latest_annotated_frame = None


def start_camera_capture():
    global camera_thread, camera_status
    with camera_control_lock:
        if camera_thread is not None and camera_thread.is_alive() and not camera_stop_event.is_set():
            return
        if camera_thread is not None and camera_thread.is_alive():
            camera_thread.join(timeout=5)
        with get_db() as conn:
            conn.execute(
                "UPDATE estado_hidratacao SET monitoramento_desde = ?, ultima_interacao_agua = NULL, ultimo_aviso = NULL",
                (datetime.datetime.now().astimezone().isoformat(timespec="seconds"),),
            )
            conn.execute(
                "UPDATE estado_alimentacao SET monitoramento_desde = ?, ultima_interacao_comida = NULL, ultimo_aviso = NULL",
                (datetime.datetime.now().astimezone().isoformat(timespec="seconds"),),
            )
        PET_MONITOR_CHECK_AT.clear()
        pet_presence_states.clear()
        food_interaction_sessions.clear()
        water_interaction_sessions.clear()
        live_prev_positions.clear()
        camera_stop_event.clear()
        with camera_state_lock:
            camera_status = "iniciando"
        camera_thread = threading.Thread(target=_camera_capture_worker, name="petguard-camera", daemon=True)
        camera_thread.start()


def stop_camera_capture():
    global camera_thread, camera_status, latest_camera_jpeg, latest_raw_frame, latest_annotated_frame
    with camera_control_lock:
        camera_stop_event.set()
        if camera_thread is not None and camera_thread.is_alive():
            camera_thread.join(timeout=5)
        camera_thread = None
        with camera_state_lock:
            camera_status = "desconectada"
            latest_camera_jpeg = None
            latest_raw_frame = None
            latest_annotated_frame = None
        latest_pet_detections.clear()
        latest_track_boxes.clear()
    with pet_assignment_lock:
        pet_assignments.clear()
    with track_species_lock:
        track_species_votes.clear()
        track_species_locks.clear()
    water_interaction_sessions.clear()
    food_interaction_sessions.clear()
    PET_MONITOR_CHECK_AT.clear()
    pet_presence_states.clear()
    live_prev_positions.clear()


@app.post("/camera/connect")
def connect_camera(user: dict = Depends(require_camera_owner)):
    start_camera_capture()
    return {"camera": "conectando"}


@app.post("/camera/disconnect")
def disconnect_camera(user: dict = Depends(require_camera_owner)):
    stop_camera_capture()
    return {"camera": "desconectada"}


def generate_camera_frames():
    """Entrega quadros do capturador persistente; fechar o vídeo não interrompe o YOLO."""
    start_camera_capture()
    while not camera_stop_event.is_set():
        with camera_state_lock:
            frame = latest_camera_jpeg
        if frame:
            yield b"--frame\r\nContent-Type: image/jpeg\r\n\r\n" + frame + b"\r\n"
        camera_stop_event.wait(0.04)


@app.get("/video_feed")
def video_feed(stream_token: str = Query(..., min_length=20, max_length=2048)):
    _verify_stream_owner(stream_token)
    return StreamingResponse(
        generate_camera_frames(),
        media_type="multipart/x-mixed-replace; boundary=frame",
        headers={"Cache-Control": "no-cache, no-store, must-revalidate", "Referrer-Policy": "no-referrer"},
    )
