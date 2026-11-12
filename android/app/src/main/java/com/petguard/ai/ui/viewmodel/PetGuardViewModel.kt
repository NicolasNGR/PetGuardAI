package com.petguard.ai.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.petguard.ai.data.AuthValidator
import com.petguard.ai.data.CameraDevice
import com.petguard.ai.data.CameraAlert
import com.petguard.ai.data.CameraZone
import com.petguard.ai.data.LivePetDetection
import com.petguard.ai.data.EventCategory
import com.petguard.ai.data.FirebaseAuthPetGuardRepository
import com.petguard.ai.data.PetGuardAlertsApi
import com.petguard.ai.data.PetEvent
import com.petguard.ai.data.PetGuardContent
import com.petguard.ai.data.PetGuardRepository
import com.petguard.ai.data.PetPreferences
import com.petguard.ai.data.PetProfile
import com.petguard.ai.data.RepositoryResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

enum class PetGuardPage { Home, Cameras, Timeline, Profile }

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    data object Success : AuthUiState
    data class Error(val message: String) : AuthUiState
}

class PetGuardViewModel(
    private val repository: PetGuardRepository = FirebaseAuthPetGuardRepository()
) : ViewModel() {
    var email by mutableStateOf(""); private set
    var password by mutableStateOf(""); private set
    var passwordConfirmation by mutableStateOf(""); private set
    var signUpName by mutableStateOf(""); private set
    var authState by mutableStateOf<AuthUiState>(AuthUiState.Idle); private set
    var page by mutableStateOf<PetGuardPage?>(null); private set
    var lightTheme by mutableStateOf(false); private set
    var highContrast by mutableStateOf(false); private set
    var yoloEnabled by mutableStateOf(true); private set
    var notificationsEnabled by mutableStateOf(true); private set
    var selectedDate by mutableStateOf(LocalDate.now().toString()); private set
    var tutorEmail by mutableStateOf(""); private set
    var tutorName by mutableStateOf("Tutor PetGuard"); private set
    var pets by mutableStateOf(emptyList<PetProfile>()); private set
    var selectedPetId by mutableStateOf(""); private set
    val pet: PetProfile get() = pets.firstOrNull { it.id == selectedPetId }
        ?: pets.firstOrNull()
        ?: PetProfile("", "seu pet", "", "", "")
    var events by mutableStateOf<List<PetEvent>>(emptyList()); private set
    var cameras by mutableStateOf<List<CameraDevice>>(emptyList()); private set
    var isLoading by mutableStateOf(false); private set
    var contentError by mutableStateOf<String?>(null); private set
    var actionMessage by mutableStateOf<String?>(null); private set
    var alertStatus by mutableStateOf("Aguardando conexão com a API YOLO")
        private set
    var cameraPrivacyAccepted by mutableStateOf(false)
        private set
    var cameraStreamUrl by mutableStateOf<String?>(null)
        private set
    var pendingAlertNotifications by mutableStateOf<List<CameraAlert>>(emptyList())
        private set
    var liveDetections by mutableStateOf<List<LivePetDetection>>(emptyList())
        private set
    var cameraZones by mutableStateOf<Map<String, CameraZone>>(emptyMap())
        private set
    var zonesLoading by mutableStateOf(false)
        private set
    var zonesSaving by mutableStateOf(false)
        private set
    private var alertPollingJob: Job? = null
    private var sharedSyncJob: Job? = null
    private var lastProcessedAlertId = 0L
    private var hasLoadedAlertBaseline = false
    private val assignmentRequestsInFlight = mutableSetOf<Int>()

    init { loadContent(); restoreSession() }

    fun onEmailChange(value: String) { email = AuthValidator.sanitizeEmail(value); authState = AuthUiState.Idle }
    fun onPasswordChange(value: String) { password = AuthValidator.sanitizePassword(value); authState = AuthUiState.Idle }
    fun onPasswordConfirmationChange(value: String) { passwordConfirmation = AuthValidator.sanitizePassword(value); authState = AuthUiState.Idle }
    fun onSignUpNameChange(value: String) { signUpName = value.filter { !it.isISOControl() }.take(60); authState = AuthUiState.Idle }
    fun clearAuthError() { authState = AuthUiState.Idle }

    fun signIn() {
        val error = AuthValidator.validateEmail(email) ?: AuthValidator.validatePassword(password)
        if (error != null) {
            authState = AuthUiState.Error(error)
            return
        }
        viewModelScope.launch {
            authState = AuthUiState.Loading
            when (val result = safely { repository.signIn(email.trim(), password) }) {
                is RepositoryResult.Success -> {
                    tutorEmail = result.value
                    authState = AuthUiState.Success
                    password = ""
                    page = PetGuardPage.Home
                    loadContent()
                    startSharedSync()
                }
                is RepositoryResult.Failure -> authState = AuthUiState.Error(result.message)
            }
        }
    }

    fun beginGoogleSignIn() {
        if (authState != AuthUiState.Loading) authState = AuthUiState.Loading
    }

    fun signInWithGoogleIdToken(idToken: String) {
        viewModelScope.launch {
            authState = AuthUiState.Loading
            when (val result = safely { repository.signInWithGoogleIdToken(idToken) }) {
                is RepositoryResult.Success -> {
                    tutorEmail = result.value
                    authState = AuthUiState.Success
                    page = PetGuardPage.Home
                    loadContent()
                    startSharedSync()
                }
                is RepositoryResult.Failure -> authState = AuthUiState.Error(result.message)
            }
        }
    }

    fun failGoogleSignIn(message: String) {
        authState = AuthUiState.Error(message)
    }

    fun cancelGoogleSignIn() {
        authState = AuthUiState.Idle
    }

    fun createAccount() {
        val safeName = signUpName.trim()
        val error = when {
            safeName.length < 2 -> "Digite seu nome (pelo menos 2 caracteres)."
            AuthValidator.validateEmail(email) != null -> AuthValidator.validateEmail(email)
            AuthValidator.validatePassword(password) != null -> AuthValidator.validatePassword(password)
            password != passwordConfirmation -> "As senhas não coincidem."
            else -> null
        }
        if (error != null) {
            authState = AuthUiState.Error(error)
            return
        }
        viewModelScope.launch {
            authState = AuthUiState.Loading
            when (val result = safely { repository.createAccount(safeName, email.trim(), password) }) {
                is RepositoryResult.Success -> {
                    tutorEmail = result.value
                    tutorName = safeName
                    authState = AuthUiState.Success
                    password = ""
                    passwordConfirmation = ""
                    page = PetGuardPage.Home
                    loadContent()
                    startSharedSync()
                }
                is RepositoryResult.Failure -> authState = AuthUiState.Error(result.message)
            }
        }
    }

    private fun restoreSession() {
        viewModelScope.launch {
            when (val result = safely { repository.currentUserEmail() }) {
                is RepositoryResult.Success -> result.value?.let { email ->
                    tutorEmail = email
                    page = PetGuardPage.Home
                    loadContent()
                    startSharedSync()
                }
                is RepositoryResult.Failure -> Unit
            }
        }
    }

    fun requestPasswordReset() {
        val error = AuthValidator.validateEmail(email)
        if (error != null) {
            actionMessage = "Informe um e-mail válido para recuperar a senha."
            return
        }
        viewModelScope.launch {
            actionMessage = null
            when (val result = safely { repository.sendPasswordReset(email.trim()) }) {
                is RepositoryResult.Success -> actionMessage = "Se a conta existir, você receberá as instruções de recuperação."
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }

    fun loadContent() {
        viewModelScope.launch {
            isLoading = true
            contentError = null
            when (val result = safely { repository.loadContent() }) {
                is RepositoryResult.Success -> applyContent(result.value)
                is RepositoryResult.Failure -> contentError = result.message
            }
            isLoading = false
        }
    }

    private fun applyContent(content: PetGuardContent) {
        tutorName = content.tutor.name
        if (content.tutor.email.isNotBlank()) tutorEmail = content.tutor.email
        pets = content.pets
        selectedPetId = content.selectedPetId.takeIf { id -> pets.any { it.id == id } } ?: pets.firstOrNull()?.id.orEmpty()
        cameras = content.cameras
        events = content.events
        lightTheme = content.preferences.lightTheme
        highContrast = content.preferences.colorAccessiblePalette
        yoloEnabled = content.preferences.yoloEnabled
        notificationsEnabled = content.preferences.notificationsEnabled
    }

    fun startAlertPolling() {
        if (alertPollingJob?.isActive == true) return
        alertPollingJob = viewModelScope.launch {
            while (isActive) {
                val camera = cameras.lastOrNull { it.connected && it.url.isNotBlank() }
                if (camera == null || !yoloEnabled) {
                    alertStatus = if (yoloEnabled) "Conecte a câmera para receber alertas" else "Análise YOLO desativada"
                    delay(5_000)
                    continue
                }

                try {
                    val incoming = PetGuardAlertsApi.fetch(camera.url, lastProcessedAlertId)
                    val detections = PetGuardAlertsApi.fetchDetections(camera.url)
                    liveDetections = detections
                    autoAssignUnambiguousDetections(camera, detections)
                    if (incoming.isNotEmpty()) {
                        val activityEvents = incoming.map { alert ->
                            val datePart = alert.timestamp.substringBefore('T').substringBefore(' ')
                            val timePart = alert.timestamp.substringAfter('T').takeIf { it != alert.timestamp }
                                ?.take(5) ?: alert.timestamp.substringAfter(' ').take(5)
                            PetEvent(
                                id = "api-alert-${alert.id}",
                                date = datePart,
                                category = when (alert.category.uppercase()) {
                                    "HIDRATACAO", "ALIMENTACAO" -> if (alert.priority.equals("atencao", ignoreCase = true)) EventCategory.ATTENTION else EventCategory.ACTIVITY
                                    else -> EventCategory.ACTIVITY
                                },
                                title = alert.petName.takeIf { it.isNotBlank() }?.let { "Alerta · $it" } ?: "Alerta da câmera",
                                detail = alert.message,
                                timeLabel = timePart.ifBlank { "agora" },
                                petId = alert.petId
                            )
                        }
                        events = (activityEvents + events).distinctBy(PetEvent::id).take(300)
                        activityEvents.forEach { event ->
                            when (val saved = safely { repository.addEvent(event) }) {
                                is RepositoryResult.Success -> Unit
                                is RepositoryResult.Failure -> actionMessage = saved.message
                            }
                        }
                        incoming.lastOrNull()?.timestamp?.let { timestamp ->
                            val eventDate = timestamp.substringBefore('T').substringBefore(' ')
                            if (runCatching { LocalDate.parse(eventDate) }.isSuccess) selectedDate = eventDate
                        }
                        // Alertas anteriores à primeira consulta entram no histórico sem gerar notificações atrasadas.
                        if (hasLoadedAlertBaseline) {
                            pendingAlertNotifications = (pendingAlertNotifications + incoming).distinctBy(CameraAlert::id)
                        }
                        lastProcessedAlertId = maxOf(lastProcessedAlertId, incoming.maxOf(CameraAlert::id))
                    }
                    hasLoadedAlertBaseline = true
                    alertStatus = "Alertas do YOLO sincronizados"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    alertStatus = "Sem conexão com a API de alertas"
                }
                delay(5_000)
            }
        }
    }

    fun stopAlertPolling() {
        alertPollingJob?.cancel()
        alertPollingJob = null
        liveDetections = emptyList()
        alertStatus = "Monitoramento em pausa"
    }

    fun refreshCameraZones() {
        val camera = cameras.lastOrNull { it.connected && it.url.isNotBlank() } ?: run {
            actionMessage = "Conecte uma câmera antes de configurar os potes."
            return
        }
        viewModelScope.launch {
            zonesLoading = true
            try {
                cameraZones = PetGuardAlertsApi.fetchZones(camera.url)
                actionMessage = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionMessage = "Não foi possível carregar as zonas da câmera. Confira se a API está acessível."
            } finally { zonesLoading = false }
        }
    }

    fun saveCameraZones(zones: Map<String, CameraZone>) {
        val camera = cameras.lastOrNull { it.connected && it.url.isNotBlank() } ?: run {
            actionMessage = "Conecte uma câmera antes de salvar os potes."
            return
        }
        viewModelScope.launch {
            zonesSaving = true
            try {
                PetGuardAlertsApi.saveZones(camera.url, zones)
                cameraZones = zones
                actionMessage = "Zonas dos potes salvas na câmera."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionMessage = "Não foi possível salvar as zonas. Tente novamente."
            } finally { zonesSaving = false }
        }
    }

    fun assignDetectionToPet(detection: LivePetDetection, petId: String?) {
        val camera = cameras.lastOrNull { it.connected && it.url.isNotBlank() } ?: return
        val assignedPet = pets.firstOrNull { it.id == petId }
        requestDetectionAssignment(camera, detection, assignedPet, notifyUser = true)
    }

    fun startSharedSync() {
        if (sharedSyncJob?.isActive == true) return
        sharedSyncJob = viewModelScope.launch {
            while (isActive && page != null) {
                delay(12_000)
                if (page != null) {
                    when (val result = safely { repository.loadContent() }) {
                        is RepositoryResult.Success -> applyContent(result.value)
                        is RepositoryResult.Failure -> {
                            if (contentError == null) contentError = result.message
                        }
                    }
                }
            }
        }
    }

    fun stopSharedSync() {
        sharedSyncJob?.cancel()
        sharedSyncJob = null
    }

    private fun autoAssignUnambiguousDetections(camera: CameraDevice, detections: List<LivePetDetection>) {
        if (pets.isEmpty()) return
        detections.asSequence()
            .filter { it.petId.isBlank() }
            .forEach { detection ->
                val speciesName = if (detection.species == "dog") "cachorro" else "gato"
                val pet = pets.filter { it.species.equals(speciesName, ignoreCase = true) }.singleOrNull()
                    ?: return@forEach
                requestDetectionAssignment(camera, detection, pet, notifyUser = false)
            }
    }

    private fun requestDetectionAssignment(
        camera: CameraDevice,
        detection: LivePetDetection,
        assignedPet: PetProfile?,
        notifyUser: Boolean
    ) {
        if (!assignmentRequestsInFlight.add(detection.trackId)) return
        viewModelScope.launch {
            try {
                PetGuardAlertsApi.assignDetection(camera.url, detection, assignedPet)
                liveDetections = liveDetections.map { current ->
                    if (current.trackId != detection.trackId) current
                    else current.copy(petId = assignedPet?.id.orEmpty(), petName = assignedPet?.name.orEmpty())
                }
                if (notifyUser) {
                    actionMessage = if (assignedPet == null) "Identificação removida desta detecção."
                    else "Detecção associada a ${assignedPet.name}."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (notifyUser) actionMessage = "Não foi possível associar o pet. Confira a conexão com a câmera."
            } finally {
                assignmentRequestsInFlight.remove(detection.trackId)
            }
        }
    }

    fun clearPendingAlertNotifications() {
        pendingAlertNotifications = emptyList()
    }



    fun navigate(destination: PetGuardPage) { page = destination; actionMessage = null }
    fun signOut() {
        page = null
        cameraPrivacyAccepted = false
        cameraStreamUrl = null
        sharedSyncJob?.cancel()
        sharedSyncJob = null
        password = ""
        passwordConfirmation = ""
        authState = AuthUiState.Idle
        actionMessage = null
        viewModelScope.launch {
            when (val result = safely { repository.signOut() }) {
                is RepositoryResult.Success -> { tutorEmail = ""; tutorName = "Tutor PetGuard" }
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }
    fun onLightThemeChange(value: Boolean) = savePreferences(lightTheme = value)
    fun onHighContrastChange(value: Boolean) = savePreferences(highContrast = value)
    fun setYolo(value: Boolean) = savePreferences(yoloEnabled = value)
    fun setNotifications(value: Boolean) = savePreferences(notificationsEnabled = value)

    private fun savePreferences(
        lightTheme: Boolean = this.lightTheme,
        highContrast: Boolean = this.highContrast,
        yoloEnabled: Boolean = this.yoloEnabled,
        notificationsEnabled: Boolean = this.notificationsEnabled
    ) {
        val updated = PetPreferences(lightTheme, highContrast, yoloEnabled, notificationsEnabled)
        this.lightTheme = lightTheme
        this.highContrast = highContrast
        this.yoloEnabled = yoloEnabled
        this.notificationsEnabled = notificationsEnabled
        viewModelScope.launch {
            when (val result = safely { repository.savePreferences(updated) }) {
                is RepositoryResult.Success -> Unit
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }

    fun selectDate(value: String) {
        selectedDate = runCatching { LocalDate.parse(value).toString() }.getOrDefault(selectedDate)
    }
    fun selectDateMillis(value: Long) {
        selectedDate = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().toString()
    }
    val selectedDateMillis: Long
        get() = runCatching { LocalDate.parse(selectedDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrDefault(Instant.now().toEpochMilli())
    fun eventsForSelectedDay(): List<PetEvent> = events.filter { it.date == selectedDate }
    fun eventsForSelectedPet(): List<PetEvent> = events.filter { it.petId == selectedPetId }

    fun addCamera(name: String, url: String) {
        val safeName = name.trim().take(40)
        val safeUrl = url.trim().take(200)
        if (safeName.length < 2) { actionMessage = "Dê um nome para a câmera."; return }
        val parsedUri = runCatching { Uri.parse(safeUrl) }.getOrNull()
        if (safeUrl.isNotEmpty() && (parsedUri == null || parsedUri.scheme !in setOf("http", "https") || parsedUri.host.isNullOrBlank() || parsedUri.userInfo != null)) {
            actionMessage = "Use um endereço HTTP ou HTTPS válido, sem usuário ou senha na URL."
            return
        }
        viewModelScope.launch {
            when (val result = safely { repository.addCamera(safeName, safeUrl) }) {
                is RepositoryResult.Success -> { cameras = result.value; actionMessage = "Câmera adicionada." }
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }

    fun addDiscoveredCamera(name: String, url: String) {
        val safeName = name.trim().filter { !it.isISOControl() }.take(40).ifBlank { "Notebook PetGuard" }
        val parsedUri = runCatching { Uri.parse(url.trim()) }.getOrNull()
        if (parsedUri?.scheme != "http" || parsedUri.host.isNullOrBlank() || parsedUri.userInfo != null) {
            actionMessage = "O endereço encontrado para a câmera é inválido."
            return
        }
        viewModelScope.launch {
            val existing = cameras.firstOrNull { it.url.equals(url.trim(), ignoreCase = true) }
            val addResult = if (existing != null) {
                RepositoryResult.Success(cameras)
            } else safely { repository.addCamera(safeName, url.trim()) }
            when (addResult) {
                is RepositoryResult.Failure -> actionMessage = addResult.message
                is RepositoryResult.Success -> {
                    val camera = addResult.value.firstOrNull { it.url.equals(url.trim(), ignoreCase = true) }
                    if (camera == null) {
                        actionMessage = "Não foi possível adicionar o notebook encontrado."
                        return@launch
                    }
                    when (val connected = safely { repository.setCameraConnected(camera.id, true) }) {
                        is RepositoryResult.Success -> {
                            cameras = connected.value
                            cameraPrivacyAccepted = false
                            cameraStreamUrl = null
                            actionMessage = "Notebook encontrado. Confirme para iniciar a câmera."
                        }
                        is RepositoryResult.Failure -> actionMessage = connected.message
                    }
                }
            }
        }
    }

    fun setCameraConnected(id: String, connected: Boolean) {
        viewModelScope.launch {
            when (val result = safely { repository.setCameraConnected(id, connected) }) {
                is RepositoryResult.Success -> {
                    cameras = result.value
                    val camera = result.value.firstOrNull { it.id == id }
                    if (connected) {
                        cameraPrivacyAccepted = false
                        cameraStreamUrl = null
                        actionMessage = "Câmera selecionada. Confirme no app para iniciar o monitoramento."
                    } else {
                        cameraPrivacyAccepted = false
                        cameraStreamUrl = null
                        val apiAvailable = camera?.url?.isNotBlank() == true && try {
                            PetGuardAlertsApi.setCameraConnected(camera.url, false)
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        actionMessage = if (apiAvailable) "Câmera desconectada." else
                            "Câmera desconectada no app; não foi possível confirmar no notebook."
                    }
                }
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }

    fun renameCamera(id: String, name: String) {
        val safeName = name.trim().filter { !it.isISOControl() }.take(40)
        if (safeName.length < 2) {
            actionMessage = "O nome da câmera precisa ter pelo menos 2 caracteres."
            return
        }
        viewModelScope.launch {
            when (val result = safely { repository.renameCamera(id, safeName) }) {
                is RepositoryResult.Success -> {
                    cameras = result.value
                    actionMessage = "Nome da câmera atualizado."
                }
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }

    fun forgetCamera(id: String) {
        val camera = cameras.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            var stoppedOnNotebook = true
            if (camera.connected && camera.url.isNotBlank()) {
                stoppedOnNotebook = try {
                    PetGuardAlertsApi.setCameraConnected(camera.url, false)
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
            when (val result = safely { repository.removeCamera(id) }) {
                is RepositoryResult.Success -> {
                    cameras = result.value
                    if (camera.connected) {
                        cameraPrivacyAccepted = false
                        cameraStreamUrl = null
                    }
                    actionMessage = if (stoppedOnNotebook) {
                        "${camera.name} foi removida das câmeras salvas."
                    } else {
                        "${camera.name} foi removida. Não foi possível avisar o notebook para parar a câmera."
                    }
                }
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }

    fun updatePet(name: String, species: String) {
        if (pets.isEmpty()) { actionMessage = "Adicione seu primeiro pet antes de editar."; return }
        val safeName = name.trim().filter { !it.isISOControl() }.take(40)
        if (safeName.length < 2) { actionMessage = "O nome do pet precisa ter pelo menos 2 caracteres."; return }
        if (species !in setOf("Cachorro", "Gato")) { actionMessage = "Escolha cachorro ou gato."; return }
        viewModelScope.launch {
            when (val result = safely { repository.updatePet(pet.copy(name = safeName, species = species)) }) {
                is RepositoryResult.Success -> {
                    pets = pets.map { if (it.id == result.value.id) result.value else it }
                    actionMessage = "Perfil do pet atualizado."
                }
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }

    fun addPet(name: String, species: String) {
        val safeName = name.trim().filter { !it.isISOControl() }.take(40)
        if (safeName.length < 2) { actionMessage = "O nome do pet precisa ter pelo menos 2 caracteres."; return }
        if (species !in setOf("Cachorro", "Gato")) { actionMessage = "Escolha cachorro ou gato."; return }
        viewModelScope.launch {
            when (val result = safely { repository.addPet(safeName, species) }) {
                is RepositoryResult.Success -> {
                    pets = result.value
                    pets.lastOrNull()?.let { addedPet -> selectedPetId = addedPet.id }
                    actionMessage = "$safeName foi adicionado ao perfil."
                }
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }

    fun selectPet(id: String) {
        viewModelScope.launch {
            when (val result = safely { repository.selectPet(id) }) {
                is RepositoryResult.Success -> { selectedPetId = result.value.id; actionMessage = "Pet selecionado: ${result.value.name}." }
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }

    fun updateTutorName(name: String) {
        val safeName = name.trim().filter { !it.isISOControl() }.take(60)
        if (safeName.length < 2) { actionMessage = "O nome precisa ter pelo menos 2 caracteres."; return }
        viewModelScope.launch {
            when (val result = safely { repository.updateTutorName(safeName) }) {
                is RepositoryResult.Success -> { tutorName = result.value; actionMessage = "Nome do perfil atualizado." }
                is RepositoryResult.Failure -> actionMessage = result.message
            }
        }
    }

    fun dismissMessage() { actionMessage = null }
    fun showMessage(message: String) { actionMessage = message }
    fun acceptCameraPrivacyNotice() {
        val camera = cameras.lastOrNull { it.connected } ?: return
        if (camera.url.isBlank()) {
            actionMessage = "Essa câmera não tem um endereço de transmissão válido."
            return
        }
        viewModelScope.launch {
            actionMessage = "Conectando à câmera do notebook…"
            try {
                val authorizedStreamUrl = PetGuardAlertsApi.createCameraStreamUrl(camera.url)
                PetGuardAlertsApi.setCameraConnected(camera.url, true)
                cameraStreamUrl = authorizedStreamUrl
                cameraPrivacyAccepted = true
                actionMessage = "Câmera conectada; monitoramento iniciado."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cameraPrivacyAccepted = false
                cameraStreamUrl = null
                actionMessage = "Não foi possível iniciar a câmera. Confira se a API do notebook está aberta e tente novamente."
            }
        }
    }
    fun latestAttentionEvent(): PetEvent? = events.firstOrNull { it.petId == selectedPetId && it.category == EventCategory.ATTENTION }

    private suspend fun <T> safely(action: suspend () -> RepositoryResult<T>): RepositoryResult<T> = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        RepositoryResult.Failure("Não foi possível concluir a operação agora. Tente novamente.")
    }
}
