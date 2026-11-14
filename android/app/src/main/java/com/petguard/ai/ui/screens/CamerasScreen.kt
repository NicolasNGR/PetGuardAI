package com.petguard.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import com.petguard.ai.ui.viewmodel.PetGuardViewModel
import com.petguard.ai.data.CameraNetworkDiscovery
import com.petguard.ai.data.CameraDevice
import com.petguard.ai.data.CameraZone
import com.petguard.ai.data.LivePetDetection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CamerasScreen(vm: PetGuardViewModel) {
    var showDiscoveryDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showCameraSettings by remember { mutableStateOf(false) }
    var showZonesDialog by remember { mutableStateOf(false) }
    val selectedCamera = vm.cameras.lastOrNull { it.connected }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Câmeras", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp)
            IconButton(onClick = { showCameraSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Configurações das câmeras", tint = MaterialTheme.colorScheme.onSurface) }
        }
        Card(Modifier.fillMaxWidth().heightIn(min = 220.dp), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            if (selectedCamera?.connected == true && selectedCamera.url.isNotBlank() && vm.cameraPrivacyAccepted) {
                CameraStreamPreview(
                    url = vm.cameraStreamUrl ?: selectedCamera.url,
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                )
            } else if (selectedCamera?.connected == true && !vm.cameraPrivacyAccepted) {
                CameraPrivacyGate(Modifier.fillMaxWidth().heightIn(min = 220.dp), vm::acceptCameraPrivacyNotice)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Text(if (selectedCamera == null) "Prévia da câmera" else selectedCamera.name,
                        Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp)
                    Text(if (selectedCamera == null) "Adicione a webcam do notebook para iniciar a visualização." else "Toque em Conectar para abrir a transmissão do notebook.",
                        Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f), fontSize = 12.sp)
                    if (vm.isLoading) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(Modifier.size(24.dp)) }
                }
            }
        }
        Text(
            vm.alertStatus,
            Modifier.fillMaxWidth().padding(top = 8.dp),
            color = if (vm.alertStatus.startsWith("Sem conexão")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .75f),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dispositivos", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            TextButton(onClick = { showDiscoveryDialog = true }) { Icon(Icons.Default.Add, null); Text("Adicionar câmera") }
        }
        if (vm.cameras.isEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("Nenhuma câmera cadastrada", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                        Text("Adicione o nome e o endereço do dispositivo.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f), fontSize = 11.sp)
                    }
                }
            }
        } else {
            vm.cameras.forEach { camera ->
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(camera.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                            Text(if (camera.url.isBlank()) "Notebook / webcam" else camera.url,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), fontSize = 10.sp, maxLines = 1)
                        }
                        TextButton(onClick = { vm.setCameraConnected(camera.id, !camera.connected) }) {
                            Text(if (camera.connected) "Desconectar" else "Conectar")
                        }
                    }
                }
            }
        }
        if (selectedCamera != null && vm.cameraPrivacyAccepted) {
            Spacer(Modifier.height(14.dp))
            Text("Pets na câmera", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (vm.liveDetections.isEmpty()) {
                Text("Nenhum cachorro ou gato identificado neste momento.", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 12.sp)
            } else {
                vm.liveDetections.forEach { detection ->
                    val speciesName = if (detection.species == "dog") "cachorro" else "gato"
                    val compatiblePets = vm.pets.filter { it.species.equals(speciesName, ignoreCase = true) }
                    DetectionAssignmentCard(detection, compatiblePets.map { it.id to it.name.replaceFirstChar { c -> c.uppercase() } }, speciesName, vm::assignDetectionToPet)
                }
                if (vm.pets.isEmpty()) {
                    Text("Cadastre seu cachorro ou gato na aba Perfil para associar esta detecção.",
                        Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { showZonesDialog = true; vm.refreshCameraZones() }, modifier = Modifier.fillMaxWidth()) {
                Text("Configurar zonas dos potes", maxLines = 1)
            }
            Text("O sistema registra presença na área dos potes; isso não confirma ingestão.",
                Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), fontSize = 11.sp)
        }
        if (vm.contentError != null) {
            Spacer(Modifier.height(12.dp))
            Text(vm.contentError.orEmpty(), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::loadContent) { Text("Tentar novamente") }
        }
        Spacer(Modifier.height(20.dp))
        Text("Quando houver apenas um pet cadastrado de cada espécie, a detecção será associada automaticamente. Se houver mais de um da mesma espécie, escolha o pet no cartão.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f), fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))
    }

    if (showDiscoveryDialog) CameraDiscoveryDialog(
        onDismiss = { showDiscoveryDialog = false },
        onSelect = { name, url ->
            vm.addDiscoveredCamera(name, url)
            showDiscoveryDialog = false
        },
        onManual = { showDiscoveryDialog = false; showManualDialog = true }
    )
    if (showManualDialog) AddCameraDialog(onDismiss = { showManualDialog = false }, onSave = { name, url ->
        vm.addCamera(name, url); showManualDialog = false
    })
    if (showCameraSettings) CameraSettingsDialog(
        cameras = vm.cameras,
        activeCameraId = vm.cameras.lastOrNull { it.connected }?.id.takeIf { vm.cameraPrivacyAccepted },
        onDismiss = { showCameraSettings = false },
        onRename = vm::renameCamera,
        onToggleConnection = vm::setCameraConnected,
        onForget = vm::forgetCamera,
        onAddCamera = { showCameraSettings = false; showDiscoveryDialog = true }
    )
    if (showZonesDialog && selectedCamera != null) CameraZonesDialog(
        cameraUrl = vm.cameraStreamUrl ?: selectedCamera.url,
        zones = vm.cameraZones,
        snapshot = vm.cameraSnapshot,
        loading = vm.zonesLoading,
        saving = vm.zonesSaving,
        onDismiss = { showZonesDialog = false },
        onSave = vm::saveCameraZones
    )
}

@Composable
private fun DetectionAssignmentCard(
    detection: LivePetDetection,
    pets: List<Pair<String, String>>,
    speciesName: String,
    onAssign: (LivePetDetection, String?) -> Unit
) {
    var expanded by remember(detection.trackId) { mutableStateOf(false) }
    val petName = pets.firstOrNull { it.first == detection.petId }?.second
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(if (detection.species == "dog") "Cachorro detectado" else "Gato detectado", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(detection.interaction?.let { if (it == "HIDRATACAO") "Na zona de água" else "Na zona de ração" } ?: "Na câmera · ${(detection.confidence * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
            }
            Box {
                TextButton(enabled = pets.isNotEmpty(), onClick = { expanded = true }) {
                    Text(petName ?: if (pets.isEmpty()) "Cadastre $speciesName" else "Identificar")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    pets.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onAssign(detection, id); expanded = false }) }
                    if (petName != null) DropdownMenuItem(text = { Text("Remover associação") }, onClick = { onAssign(detection, null); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun CameraZonesDialog(
    cameraUrl: String,
    zones: Map<String, CameraZone>,
    snapshot: ByteArray?,
    loading: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Map<String, CameraZone>) -> Unit
) {
    var selectedKind by remember { mutableStateOf("agua") }
    var editedZones by remember(zones) { mutableStateOf(zones.toMap()) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val waterColor = MaterialTheme.colorScheme.tertiary
    val foodColor = MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zonas dos potes") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Escolha água ou ração e arraste na imagem para marcar onde fica o pote.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectedKind == "agua", onClick = { selectedKind = "agua" }, label = { Text("Água") })
                    FilterChip(selected = selectedKind == "comida", onClick = { selectedKind = "comida" }, label = { Text("Ração") })
                }
                Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    val bitmap = remember(snapshot) { snapshot?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                    if (bitmap != null) Image(bitmap, contentDescription = "Imagem atual da câmera", modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize().pointerInput(selectedKind, editedZones) {
                        detectDragGestures(
                            onDragStart = { position ->
                                dragStart = Offset((position.x / size.width).coerceIn(0f, 1f), (position.y / size.height).coerceIn(0f, 1f))
                                dragEnd = dragStart
                            },
                            onDrag = { change, _ ->
                                dragEnd = Offset((change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f))
                                change.consume()
                            },
                            onDragEnd = {
                                val start = dragStart
                                val end = dragEnd
                                if (start != null && end != null) {
                                    val zone = CameraZone(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
                                    if (zone.x2 - zone.x1 > .025f && zone.y2 - zone.y1 > .025f) editedZones = editedZones + (selectedKind to zone)
                                }
                                dragStart = null
                                dragEnd = null
                            }
                        )
                    }) {
                        editedZones.forEach { (kind, zone) ->
                            val color = if (kind == "agua") waterColor else foodColor
                            val topLeft = Offset(zone.x1 * size.width, zone.y1 * size.height)
                            val rectSize = androidx.compose.ui.geometry.Size((zone.x2 - zone.x1) * size.width, (zone.y2 - zone.y1) * size.height)
                            drawRect(color.copy(alpha = .20f), topLeft, rectSize)
                            drawRect(color, topLeft, rectSize, style = Stroke(width = 3.dp.toPx()))
                        }
                        val start = dragStart
                        val end = dragEnd
                        if (start != null && end != null) {
                            drawRect(
                                if (selectedKind == "agua") waterColor else foodColor,
                                Offset(minOf(start.x, end.x) * size.width, minOf(start.y, end.y) * size.height),
                                androidx.compose.ui.geometry.Size(kotlin.math.abs(end.x - start.x) * size.width, kotlin.math.abs(end.y - start.y) * size.height),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { editedZones = editedZones - selectedKind }) {
                    Text("Apagar zona de ${if (selectedKind == "agua") "água" else "ração"}")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && !loading, onClick = { onSave(editedZones) }) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Salvar zonas")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

private data class DiscoveredCamera(val name: String, val url: String)

@Composable
private fun CameraDiscoveryDialog(
    onDismiss: () -> Unit,
    onSelect: (String, String) -> Unit,
    onManual: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val found = remember { mutableStateListOf<DiscoveredCamera>() }
    var scanning by remember { mutableStateOf(true) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanAttempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(scanAttempt) {
        scanning = true
        scanError = null
        launch {
            runCatching { CameraNetworkDiscovery.scan(context) }
                .onSuccess { results ->
                    results.forEach { result ->
                        if (found.none { it.url == result.url }) found.add(DiscoveredCamera(result.name, result.url))
                    }
                    if (found.isNotEmpty()) scanning = false
                }
                .onFailure { scanError = "Falha ao procurar na rede local. Confira a conexão Wi-Fi." }
        }
        delay(15_000)
        if (scanning && found.isEmpty()) {
            scanning = false
            scanError = "Nenhum notebook encontrado. Verifique se a API está aberta e tente buscar novamente."
        }
    }

    DisposableEffect(context, scanAttempt) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val mainHandler = Handler(Looper.getMainLooper())
        val resolving = mutableSetOf<String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { scanning = true }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_petguard-camera._tcp", ignoreCase = true)) return
                synchronized(resolving) {
                    if (!resolving.add(serviceInfo.serviceName)) return
                }
                @Suppress("DEPRECATION")
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        synchronized(resolving) { resolving.remove(info.serviceName) }
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        synchronized(resolving) { resolving.remove(info.serviceName) }
                        val host = info.host?.hostAddress ?: return
                        val address = if (host.contains(':')) "[$host]" else host
                        val camera = DiscoveredCamera(
                            name = info.serviceName.substringBefore("._petguard-camera").ifBlank { "Notebook PetGuard" },
                            url = "http://$address:${info.port}/camera"
                        )
                        mainHandler.post {
                            if (found.none { it.url == camera.url }) found.add(camera)
                            scanning = false
                        }
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                mainHandler.post { found.removeAll { it.name == serviceInfo.serviceName } }
            }
            override fun onDiscoveryStopped(serviceType: String) { mainHandler.post { scanning = false } }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                mainHandler.post { scanning = false; scanError = "Não foi possível procurar dispositivos nesta rede." }
                runCatching { manager.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                mainHandler.post { scanError = "A busca foi interrompida. Tente novamente." }
                runCatching { manager.stopServiceDiscovery(this) }
            }
        }
        try {
            manager.discoverServices("_petguard-camera._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            scanning = false
            scanError = "Não foi possível iniciar a busca nesta rede."
        }
        onDispose { runCatching { manager.stopServiceDiscovery(listener) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar câmera") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Procurando notebooks PetGuard no mesmo Wi-Fi…", style = MaterialTheme.typography.bodyMedium)
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (found.isEmpty() && !scanning) {
                    Text("Confira se a API continua aberta e se o Wi-Fi permite comunicação entre dispositivos.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { found.clear(); scanning = true; scanError = null; scanAttempt++ }) {
                        Text("Buscar novamente")
                    }
                }
                found.forEach { camera ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(camera.name, camera.url) },
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(camera.name, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text("Câmera do notebook", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Conectar", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onManual) { Text("Adicionar por endereço") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun CameraSettingsDialog(
    cameras: List<CameraDevice>,
    activeCameraId: String?,
    onDismiss: () -> Unit,
    onRename: (String, String) -> Unit,
    onToggleConnection: (String, Boolean) -> Unit,
    onForget: (String) -> Unit,
    onAddCamera: () -> Unit
) {
    var cameraToRename by remember { mutableStateOf<CameraDevice?>(null) }
    var cameraToForget by remember { mutableStateOf<CameraDevice?>(null) }
    var editedName by remember(cameraToRename?.id) { mutableStateOf(cameraToRename?.name.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurações das câmeras") },
        text = {
            if (cameras.isEmpty()) {
                Text("Nenhum notebook salvo. Adicione uma câmera para começar.")
            } else {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    cameras.forEach { camera ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(camera.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    when {
                                        camera.connected -> "Conectada • ${camera.url}"
                                        else -> "Salva · desconectada"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Column {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { cameraToRename = camera; editedName = camera.name }) { Text("Renomear") }
                                        TextButton(onClick = { onToggleConnection(camera.id, !camera.connected) }) {
                                            Text(if (camera.connected) "Desconectar" else "Conectar")
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { cameraToForget = camera }) {
                                            Text("Esquecer", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAddCamera) { Text("Adicionar câmera") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )

    cameraToRename?.let { camera ->
        AlertDialog(
            onDismissRequest = { cameraToRename = null },
            title = { Text("Renomear câmera") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it.take(40) },
                    label = { Text("Nome do dispositivo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(camera.id, editedName); cameraToRename = null }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { cameraToRename = null }) { Text("Cancelar") } }
        )
    }

    cameraToForget?.let { camera ->
        AlertDialog(
            onDismissRequest = { cameraToForget = null },
            title = { Text("Esquecer ${camera.name}?") },
            text = { Text("A câmera será removida da lista salva. Se o notebook estiver acessível, o monitoramento também será desconectado.") },
            confirmButton = {
                TextButton(onClick = { onForget(camera.id); cameraToForget = null }) {
                    Text("Esquecer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { cameraToForget = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun AddCameraDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Adicionar câmera") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Nome do dispositivo") }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it.take(200) }, label = { Text("Endereço da câmera") }, singleLine = true)
                Text("Informe o endereço local anunciado pela API do notebook. Celular e notebook precisam estar na mesma rede.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, url) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
fun CameraStreamPreview(url: String, modifier: Modifier = Modifier) {
    var loading by remember(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var reloadKey by remember(url) { mutableIntStateOf(0) }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                            loading = true
                            error = null
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            loading = false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            webError: android.webkit.WebResourceError
                        ) {
                            if (request.isForMainFrame) {
                                loading = false
                                error = "Não foi possível acessar a câmera. Confira se a API está rodando e se celular e notebook estão no mesmo Wi-Fi."
                            }
                        }
                    }
                    tag = "$url#$reloadKey"
                    loadUrl(url)
                }
            },
            update = { webView ->
                if (webView.tag != "$url#$reloadKey") {
                    loading = true
                    error = null
                    webView.tag = "$url#$reloadKey"
                    webView.loadUrl(url)
                }
            }
        )
        if (loading && error == null) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .92f), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Conectando à câmera…", Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
            }
        }
        error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(16.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp))
                    Text("Câmera indisponível", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface)
                    Text(message, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f), fontSize = 11.sp)
                    TextButton(onClick = {
                        error = null
                        loading = true
                        reloadKey++
                    }) { Text("Tentar novamente") }
                }
            }
        }
    }
}

@Composable
fun CameraPrivacyGate(modifier: Modifier = Modifier, onAccepted: () -> Unit) {
    var showQuestion by remember { mutableStateOf(false) }
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            Text("Conectar câmera", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface)
            Button(onClick = { showQuestion = true }, modifier = Modifier.padding(top = 10.dp)) { Text("Continuar") }
        }
    }
    if (showQuestion) AlertDialog(
        onDismissRequest = { showQuestion = false },
        title = { Text("Tudo bem usar a câmera do notebook para acompanhar seu pet?") },
        confirmButton = { TextButton(onClick = { showQuestion = false; onAccepted() }) { Text("Sim") } },
        dismissButton = { TextButton(onClick = { showQuestion = false }) { Text("Não") } }
    )
}
