package com.petguard.ai.data

/**
 * Contrato consumido pelo ViewModel. Implementações de Firebase, API YOLO e Supabase
 * podem substituir a implementação atual sem acoplar a interface a esses serviços.
 */
interface PetGuardRepository {
    suspend fun currentUserEmail(): RepositoryResult<String?>
    suspend fun signIn(email: String, password: String): RepositoryResult<String>
    suspend fun signInWithGoogleIdToken(idToken: String): RepositoryResult<String>
    suspend fun createAccount(name: String, email: String, password: String): RepositoryResult<String>
    suspend fun sendPasswordReset(email: String): RepositoryResult<Unit>
    suspend fun signOut(): RepositoryResult<Unit>
    suspend fun loadContent(): RepositoryResult<PetGuardContent>
    suspend fun addEvent(event: PetEvent): RepositoryResult<Unit>
    suspend fun savePreferences(preferences: PetPreferences): RepositoryResult<Unit>
    suspend fun addCamera(name: String, url: String): RepositoryResult<List<CameraDevice>>
    suspend fun setCameraConnected(id: String, connected: Boolean): RepositoryResult<List<CameraDevice>>
    suspend fun renameCamera(id: String, name: String): RepositoryResult<List<CameraDevice>>
    suspend fun removeCamera(id: String): RepositoryResult<List<CameraDevice>>
    suspend fun updatePet(pet: PetProfile): RepositoryResult<PetProfile>
    suspend fun addPet(name: String, species: String): RepositoryResult<List<PetProfile>>
    suspend fun selectPet(id: String): RepositoryResult<PetProfile>
    suspend fun updateTutorName(name: String): RepositoryResult<String>
}

/** Dados iniciais usados pela interface enquanto os serviços do grupo são conectados. */
class PreviewPetGuardRepository : PetGuardRepository {
    private var preferences = PetPreferences()
    private var tutor = TutorProfile("Tutor PetGuard", "")
    private var pets = listOf(PetProfile("thor", "Thor", "Cachorro", "", ""))
    private var selectedPetId = "thor"
    // Endereço local da webcam usada no teste da apresentação. O notebook deve
    // estar na rede Wi-Fi 192.168.15.x e iniciar a API na porta 8001.
    private var cameras = listOf(
        CameraDevice(
            id = "notebook-webcam",
            name = "Câmera do notebook",
            url = "http://192.168.15.64:8001/camera",
            connected = true
        )
    )

    private val events = emptyList<PetEvent>()

    override suspend fun currentUserEmail() = RepositoryResult.Success<String?>(null)
    override suspend fun signIn(email: String, password: String) = RepositoryResult.Success(email)
    override suspend fun signInWithGoogleIdToken(idToken: String) = RepositoryResult.Failure("Login Google indisponível na prévia.")
    override suspend fun createAccount(name: String, email: String, password: String) = RepositoryResult.Success(email)
    override suspend fun sendPasswordReset(email: String) = RepositoryResult.Success(Unit)
    override suspend fun signOut() = RepositoryResult.Success(Unit)
    override suspend fun loadContent() = RepositoryResult.Success(PetGuardContent(tutor, pets, selectedPetId, cameras, events, preferences))
    override suspend fun addEvent(event: PetEvent): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
    override suspend fun savePreferences(preferences: PetPreferences): RepositoryResult<Unit> {
        this.preferences = preferences
        return RepositoryResult.Success(Unit)
    }
    override suspend fun addCamera(name: String, url: String): RepositoryResult<List<CameraDevice>> {
        cameras = cameras + CameraDevice("camera-${System.currentTimeMillis()}", name, url)
        return RepositoryResult.Success(cameras)
    }
    override suspend fun setCameraConnected(id: String, connected: Boolean): RepositoryResult<List<CameraDevice>> {
        cameras = cameras.map { if (it.id == id) it.copy(connected = connected) else it }
        return RepositoryResult.Success(cameras)
    }
    override suspend fun renameCamera(id: String, name: String): RepositoryResult<List<CameraDevice>> {
        if (cameras.none { it.id == id }) return RepositoryResult.Failure("Não foi possível localizar essa câmera.")
        cameras = cameras.map { if (it.id == id) it.copy(name = name) else it }
        return RepositoryResult.Success(cameras)
    }
    override suspend fun removeCamera(id: String): RepositoryResult<List<CameraDevice>> {
        if (cameras.none { it.id == id }) return RepositoryResult.Failure("Não foi possível localizar essa câmera.")
        cameras = cameras.filterNot { it.id == id }
        return RepositoryResult.Success(cameras)
    }
    override suspend fun updatePet(pet: PetProfile): RepositoryResult<PetProfile> {
        pets = pets.map { if (it.id == pet.id) pet else it }
        return RepositoryResult.Success(pet)
    }
    override suspend fun addPet(name: String, species: String): RepositoryResult<List<PetProfile>> {
        val pet = PetProfile("pet-${System.currentTimeMillis()}", name, species, "", "")
        pets = pets + pet
        selectedPetId = pet.id
        return RepositoryResult.Success(pets)
    }
    override suspend fun selectPet(id: String): RepositoryResult<PetProfile> {
        val pet = pets.firstOrNull { it.id == id } ?: return RepositoryResult.Failure("Não foi possível localizar esse pet.")
        selectedPetId = pet.id
        return RepositoryResult.Success(pet)
    }
    override suspend fun updateTutorName(name: String): RepositoryResult<String> {
        tutor = tutor.copy(name = name)
        return RepositoryResult.Success(tutor.name)
    }
}
