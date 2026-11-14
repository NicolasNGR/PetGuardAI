package com.petguard.ai.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import com.google.firebase.auth.FirebaseAuth
import com.petguard.ai.BuildConfig
import com.petguard.ai.data.awaitResult

/** Cliente mínimo para os metadados de alerta da API local; não baixa nem armazena vídeo. */
object PetGuardAlertsApi {
    suspend fun createCameraStreamUrl(cameraPageUrl: String): String = withContext(Dispatchers.IO) {
        val body = request(cameraPageUrl, "/camera/session", "POST", "{}")
        val token = JSONObject(body).optString("stream_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("A API não autorizou a visualização da câmera.")
        Uri.parse(cameraPageUrl).buildUpon().path("/camera").clearQuery()
            .appendQueryParameter("stream_token", token).build().toString()
    }

    suspend fun setCameraConnected(cameraPageUrl: String, connected: Boolean) = withContext(Dispatchers.IO) {
        request(cameraPageUrl, if (connected) "/camera/connect" else "/camera/disconnect", "POST", "{}")
    }

    suspend fun fetch(cameraPageUrl: String, afterId: Long): List<CameraAlert> = withContext(Dispatchers.IO) {
        val endpointPath = "/alertas?after_id=$afterId"
        val body = request(cameraPageUrl, endpointPath)
        val array = JSONObject(body).optJSONArray("alertas") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optLong("id", -1L)
                val message = item.optString("mensagem").filterNot(Char::isISOControl).take(180)
                if (id >= 0 && message.isNotBlank()) add(CameraAlert(
                    id = id, timestamp = item.optString("timestamp"),
                    category = item.optString("categoria", "DETECCAO"),
                    priority = item.optString("nivel_prioridade", "informativo"),
                    message = message, petId = item.optString("pet_id"), petName = item.optString("pet_nome")
                ))
            }
        }
    }

    suspend fun fetchDetections(cameraPageUrl: String): List<LivePetDetection> = withContext(Dispatchers.IO) {
        val body = request(cameraPageUrl, "/detections")
        val response = JSONObject(body)
        val yoloEnabled = response.optBoolean("yolo_enabled", true)
        val array = response.optJSONArray("detections") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(LivePetDetection(
                    trackId = item.optInt("track_id", -1),
                    species = item.optString("species"),
                    confidence = item.optDouble("confidence", 0.0).toFloat(),
                    interaction = item.optString("interaction").takeUnless { it == "null" || it.isBlank() },
                    petId = item.optString("pet_id"),
                    petName = item.optString("pet_name"),
                    activity = item.optString("activity"),
                    nivelAtividade = item.optString("nivel_atividade"),
                    displacementPixels = item.optDouble("displacement_pixels", 0.0),
                    petSize = item.optString("pet_size", "medio"),
                    yoloEnabled = yoloEnabled
                ))
            }
        }.filter { it.trackId >= 0 && it.species in setOf("dog", "cat") }
    }

    suspend fun assignDetection(cameraPageUrl: String, detection: LivePetDetection, pet: PetProfile?) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("track_id", detection.trackId)
            .put("pet_id", pet?.id.orEmpty())
            .put("pet_name", pet?.name.orEmpty())
            .put("pet_species", pet?.species.orEmpty().lowercase())
            .put("pet_size", pet?.porte ?: "medio")
        pet?.pesoKg?.let { payload.put("pet_weight", it) }
        request(cameraPageUrl, "/detections/assign", "POST", payload.toString())
    }

    suspend fun fetchPetDailyGoals(cameraPageUrl: String, petId: String): PetDailyGoals = withContext(Dispatchers.IO) {
        val path = "/pets/${Uri.encode(petId)}/metas"
        val response = JSONObject(request(cameraPageUrl, path))
        PetDailyGoals(
            petId = response.optString("pet_id", petId),
            referenceWeightKg = response.optDouble("peso_referencia_kg", 16.0),
            hydrationMlDaily = response.optInt("meta_hidrica_ml_dia"),
            caloriesKcalDaily = response.optInt("meta_calorica_kcal_dia"),
            foodGramsDaily = response.optInt("meta_racao_gramas_dia")
        )
    }

    suspend fun setYoloEnabled(cameraPageUrl: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        request(cameraPageUrl, "/camera/yolo", "POST", JSONObject().put("enabled", enabled).toString())
    }

    suspend fun fetchYoloEnabled(cameraPageUrl: String): Boolean = withContext(Dispatchers.IO) {
        JSONObject(request(cameraPageUrl, "/camera/yolo")).optBoolean("yolo_enabled", true)
    }

    suspend fun fetchCameraSnapshot(cameraPageUrl: String): ByteArray = withContext(Dispatchers.IO) {
        requestBytes(cameraPageUrl, "/camera/snapshot")
    }

    suspend fun resetDemo(cameraPageUrl: String) = withContext(Dispatchers.IO) {
        request(cameraPageUrl, "/admin/reset-demo", "POST", "{}")
    }

    suspend fun fetchZones(cameraPageUrl: String): Map<String, CameraZone> = withContext(Dispatchers.IO) {
        val zones = JSONObject(request(cameraPageUrl, "/configurar-rois")).optJSONObject("zonas") ?: JSONObject()
        buildMap {
            listOf("agua", "comida").forEach { kind ->
                val item = zones.optJSONObject(kind) ?: return@forEach
                put(kind, CameraZone(item.optDouble("x1").toFloat(), item.optDouble("y1").toFloat(), item.optDouble("x2").toFloat(), item.optDouble("y2").toFloat()))
            }
        }
    }

    suspend fun saveZones(cameraPageUrl: String, zones: Map<String, CameraZone>) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
        listOf("agua", "comida").forEach { kind ->
            val zone = zones[kind]
            if (zone == null) payload.put(kind, JSONObject.NULL)
            else payload.put(kind, JSONObject().put("x1", zone.x1).put("y1", zone.y1).put("x2", zone.x2).put("y2", zone.y2))
        }
        request(cameraPageUrl, "/configurar-rois", "POST", payload.toString())
    }

    private suspend fun request(baseUrl: String, path: String, method: String = "GET", body: String? = null): String {
        return requestRaw(baseUrl, path, method, body).toString(Charsets.UTF_8)
    }

    private suspend fun requestBytes(baseUrl: String, path: String): ByteArray = requestRaw(baseUrl, path, "GET", null)

    private suspend fun requestRaw(baseUrl: String, path: String, method: String, body: String?): ByteArray {
        val firebaseUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        val localPreviewEnabled = BuildConfig.DEBUG && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()
        val firebaseToken = runCatching { firebaseUser?.getIdToken(false)?.awaitResult()?.token }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        val idToken = firebaseToken
            ?: if (localPreviewEnabled) "dev-local-token"
            else throw IllegalStateException("Entre na sua conta PetGuard para acessar a câmera.")
        val parsedPath = path.substringBefore('?')
        val query = path.substringAfter('?', "")
        val endpointBuilder = Uri.parse(baseUrl).buildUpon().path(parsedPath).clearQuery()
        if (query.isNotBlank()) endpointBuilder.encodedQuery(query)
        val connection = URL(endpointBuilder.build().toString()).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", if (path == "/camera/snapshot") "image/jpeg" else "application/json")
            connection.setRequestProperty("Authorization", "Bearer $idToken")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) {
                val message = when (code) {
                    401 -> "Sessão expirada. Entre novamente no PetGuard."
                    403 -> "Esta câmera está vinculada a outra conta PetGuard."
                    else -> "API da câmera respondeu com erro ($code)."
                }
                throw IllegalStateException(message)
            }
            return response
        } finally { connection.disconnect() }
    }
}
