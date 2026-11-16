package com.petguard.ai.data

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.petguard.ai.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Firebase Auth fornece o UID; Supabase armazena o perfil, pets e eventos sob RLS por UID. */
class SupabasePetGuardRepository : PetGuardRepository {
    override suspend fun currentUserEmail(): RepositoryResult<String?> = RepositoryResult.Success(currentFirebaseUser()?.email)
    override suspend fun signIn(email: String, password: String) = RepositoryResult.Failure("Autenticação indisponível nesta fonte de dados.")
    override suspend fun signInWithGoogleIdToken(idToken: String) = RepositoryResult.Failure("Autenticação indisponível nesta fonte de dados.")
    override suspend fun createAccount(name: String, email: String, password: String) = RepositoryResult.Failure("Autenticação indisponível nesta fonte de dados.")
    override suspend fun sendPasswordReset(email: String) = RepositoryResult.Failure("Autenticação indisponível nesta fonte de dados.")
    override suspend fun signOut() = RepositoryResult.Success(Unit)

    override suspend fun loadContent(): RepositoryResult<PetGuardContent> = cloudCall { fetchContent() }

    override suspend fun savePreferences(preferences: PetPreferences) =
        mutate { it.copy(preferences = preferences) }.mapToUnit()

    override suspend fun addCamera(name: String, url: String): RepositoryResult<List<CameraDevice>> =
        mutate { content ->
            val camera = CameraDevice("camera-" + java.util.UUID.randomUUID(), name, url, connected = false)
            content.copy(cameras = content.cameras + camera)
        }.map { it.cameras }

    override suspend fun setCameraConnected(id: String, connected: Boolean): RepositoryResult<List<CameraDevice>> =
        mutate { content -> content.copy(cameras = content.cameras.map { if (it.id == id) it.copy(connected = connected) else it }) }
            .map { it.cameras }

    override suspend fun renameCamera(id: String, name: String): RepositoryResult<List<CameraDevice>> =
        mutate { content -> content.copy(cameras = content.cameras.map { if (it.id == id) it.copy(name = name) else it }) }
            .map { it.cameras }

    override suspend fun removeCamera(id: String): RepositoryResult<List<CameraDevice>> =
        mutate { content -> content.copy(cameras = content.cameras.filterNot { it.id == id }) }.map { it.cameras }

    override suspend fun updatePet(pet: PetProfile): RepositoryResult<PetProfile> = cloudCall {
        val user = currentFirebaseUser() ?: error("Entre novamente para sincronizar seus dados.")
        val rows = requestTable("pets", "user_id", "POST", "?on_conflict=id,user_id", pet.toSupabaseJson(user.uid), "resolution=merge-duplicates,return=representation")
            .arrayOrEmpty()
        rows.optJSONObject(0)?.toPet() ?: error("N\u00e3o foi poss\u00edvel atualizar esse pet.")
    }

    override suspend fun addPet(name: String, species: String): RepositoryResult<List<PetProfile>> = cloudCall {
        val user = currentFirebaseUser() ?: error("Entre novamente para sincronizar seus dados.")
        val pet = PetProfile("pet-" + java.util.UUID.randomUUID(), name, species, "", "")
        requestTable("pets", "user_id", "POST", "?on_conflict=id,user_id", pet.toSupabaseJson(user.uid), "resolution=merge-duplicates,return=minimal")
        val updatedPets = fetchPets()
        runCatching { saveContent(fetchContent().copy(selectedPetId = pet.id)) }
        updatedPets
    }

    override suspend fun deletePet(id: String): RepositoryResult<Unit> = cloudCall {
        val current = fetchContent()
        val removed = requestTable("pets", "user_id", "DELETE", "?id=eq.${encode(id)}&select=id", null, "return=representation")
            .arrayOrEmpty()
        if (removed.length() == 0) error("N\u00e3o foi poss\u00edvel localizar esse pet na sua conta.")
        val remainingPets = fetchPets()
        val remainingEvents = fetchEvents()
        val nextSelectedId = if (current.selectedPetId == id) remainingPets.firstOrNull()?.id.orEmpty() else current.selectedPetId
        runCatching { saveContent(current.copy(pets = remainingPets, events = remainingEvents, selectedPetId = nextSelectedId)) }
        Unit
    }

    override suspend fun selectPet(id: String): RepositoryResult<PetProfile> = cloudCall {
        val content = fetchContent()
        val selected = content.pets.firstOrNull { it.id == id } ?: error("Não foi possível localizar esse pet.")
        saveContent(content.copy(selectedPetId = id))
        selected
    }

    override suspend fun updateTutorName(name: String): RepositoryResult<String> =
        mutate { content -> content.copy(tutor = content.tutor.copy(name = name)) }.map { it.tutor.name }

    override suspend fun addEvent(event: PetEvent): RepositoryResult<Unit> = cloudCall {
        val user = currentFirebaseUser() ?: error("Entre novamente para sincronizar seus dados.")
        val json = JSONObject()
            .put("id", event.id)
            .put("user_id", user.uid)
            .put("pet_id", if (event.petId.isBlank()) JSONObject.NULL else event.petId)
            .put("date", event.date)
            .put("category", event.category.name)
            .put("title", event.title)
            .put("detail", event.detail)
            .put("time_label", event.timeLabel)
        requestTable("events", "user_id", "POST", "?on_conflict=id,user_id", json, "resolution=merge-duplicates,return=minimal")
        Unit
    }

    override suspend fun clearApiEvents(): RepositoryResult<Unit> = cloudCall {
        requestTable("events", "user_id", "DELETE", "?id=like.api-alert-%25", null, "return=minimal")
        Unit
    }

    private suspend fun mutate(transform: (PetGuardContent) -> PetGuardContent): RepositoryResult<PetGuardContent> = cloudCall {
        val updated = transform(fetchContent())
        saveContent(updated)
        updated
    }

    private suspend fun fetchContent(): PetGuardContent {
        val row = request("GET", "?select=payload", null).arrayOrEmpty().optJSONObject(0)
        val base = row?.optJSONObject("payload")?.toContent(currentFirebaseUser()?.email.orEmpty()) ?: emptyContent()
        return base.copy(pets = fetchPets(), events = fetchEvents())
    }

    private suspend fun fetchPets(): List<PetProfile> = requestTable("pets", "user_id", "GET", "?select=*&order=created_at.asc", null)
        .arrayOrEmpty().let { rows -> buildList { for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { add(it.toPet()) } } }

    private suspend fun fetchEvents(): List<PetEvent> = requestTable("events", "user_id", "GET", "?select=*&order=date.desc,time_label.desc&limit=300", null)
        .arrayOrEmpty().let { rows -> buildList {
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { row ->
                val category = runCatching { EventCategory.valueOf(row.optString("category")) }.getOrDefault(EventCategory.ACTIVITY)
                add(PetEvent(row.optString("id"), row.optString("date"), category, row.optString("title"), row.optString("detail"), row.optString("time_label"), row.optString("pet_id")))
            }
        } }

    private fun PetProfile.toSupabaseJson(userId: String) = JSONObject()
        .put("id", id).put("user_id", userId).put("name", name).put("species", species)
        .put("breed", breed).put("age_label", ageLabel).put("porte", porte)
        .put("peso_kg", pesoKg ?: JSONObject.NULL)

    private fun JSONObject.toPet() = PetProfile(
        optString("id"), optString("name"), optString("species"), optString("breed"),
        optString("age_label"), optString("porte", "medio"), if (isNull("peso_kg")) null else optDouble("peso_kg")
    )

    private suspend fun saveContent(content: PetGuardContent) {
        val user = currentFirebaseUser() ?: error("Entre novamente para sincronizar seus dados.")
        val json = JSONObject().put("firebase_uid", user.uid).put("payload", content.toJson())
        request("POST", "?on_conflict=firebase_uid", json, "resolution=merge-duplicates,return=minimal")
    }

    private suspend fun requestTable(table: String, ownerColumn: String, method: String, query: String, body: JSONObject?, prefer: String? = null): String =
        request(method, query, body, prefer, table, ownerColumn)

    private suspend fun request(method: String, query: String, body: JSONObject?, prefer: String? = null, table: String = "petguard_user_data", ownerColumn: String = "firebase_uid"): String =
        withContext(Dispatchers.IO) {
            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
            val publishableKey = BuildConfig.SUPABASE_ANON_KEY
            if (baseUrl.isBlank() || publishableKey.isBlank()) {
                error("A chave Publishable do Supabase ainda não foi configurada no app.")
            }
            val user = currentFirebaseUser() ?: error("Entre novamente para sincronizar seus dados.")
            val token = firebaseAccessToken(user)
            val suffix = if (query.startsWith("?")) query else "?" + query
            val userFilter = if (method == "GET" || method == "PATCH" || method == "DELETE") "&$ownerColumn=eq." + encode(user.uid) else ""
            val connection = (URL(baseUrl + "/rest/v1/" + table + suffix + userFilter))
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("apikey", publishableKey)
                connection.setRequestProperty("Authorization", "Bearer " + token)
                connection.setRequestProperty("Accept", "application/json")
                if (prefer != null) connection.setRequestProperty("Prefer", prefer)
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write("[" + body.toString() + "]") }
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val message = when (status) {
                        401, 403 -> "Acesso ao Supabase recusado. Confira a integração Firebase e a política RLS por UID da tabela."
                        404 -> "A tabela de sincronização ainda não foi criada no Supabase."
                        else -> "Falha ao sincronizar com o Supabase (HTTP " + status + ")."
                    }
                    error(message)
                }
                val stream = if (status == HttpURLConnection.HTTP_NO_CONTENT) null else connection.inputStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } finally {
                connection.disconnect()
            }
        }

    /** O token Firebase identifica o UID; a política RLS do Supabase limita cada linha a esse UID. */
    private suspend fun firebaseAccessToken(user: com.google.firebase.auth.FirebaseUser): String {
        return user.getIdToken(false).awaitResult().token
            ?.takeIf { it.isNotBlank() }
            ?: error("Não foi possível validar a sessão da conta.")
    }

    private fun currentFirebaseUser() = FirebaseAuth.getInstance().currentUser

    private fun emptyContent() = PetGuardContent(
        tutor = TutorProfile(
            currentFirebaseUser()?.displayName?.takeIf(String::isNotBlank) ?: "Tutor PetGuard",
            currentFirebaseUser()?.email.orEmpty()
        ),
        pets = emptyList(),
        selectedPetId = "",
        cameras = emptyList(),
        events = emptyList(),
        preferences = PetPreferences()
    )

    private fun PetGuardContent.toJson() = JSONObject()
        .put("tutor_name", tutor.name)
        .put("selected_pet_id", selectedPetId)
        .put("pets", JSONArray().apply { pets.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("species", it.species).put("breed", it.breed).put("age_label", it.ageLabel).put("porte", it.porte).put("peso_kg", it.pesoKg ?: JSONObject.NULL)) } })
        .put("cameras", JSONArray().apply { cameras.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("connected", it.connected)) } })
        .put("events", JSONArray().apply { events.forEach { put(JSONObject().put("id", it.id).put("date", it.date).put("category", it.category.name).put("title", it.title).put("detail", it.detail).put("time_label", it.timeLabel).put("pet_id", it.petId)) } })
        .put("preferences", JSONObject().put("light_theme", preferences.lightTheme).put("color_accessible_palette", preferences.colorAccessiblePalette).put("yolo_enabled", preferences.yoloEnabled).put("notifications_enabled", preferences.notificationsEnabled))

    private fun JSONObject.toContent(email: String): PetGuardContent {
        val petArray = optJSONArray("pets") ?: JSONArray()
        val pets = buildList {
            for (i in 0 until petArray.length()) petArray.optJSONObject(i)?.let { pet ->
                add(PetProfile(pet.optString("id"), pet.optString("name"), pet.optString("species"), pet.optString("breed"), pet.optString("age_label"), pet.optString("porte", "medio"), if (pet.isNull("peso_kg")) null else pet.optDouble("peso_kg")))
            }
        }
        val cameraArray = optJSONArray("cameras") ?: JSONArray()
        val cameras = buildList {
            for (i in 0 until cameraArray.length()) cameraArray.optJSONObject(i)?.let { camera ->
                add(CameraDevice(camera.optString("id"), camera.optString("name"), camera.optString("url"), camera.optBoolean("connected")))
            }
        }
        val eventArray = optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (i in 0 until eventArray.length()) eventArray.optJSONObject(i)?.let { event ->
                val category = runCatching { EventCategory.valueOf(event.optString("category")) }.getOrDefault(EventCategory.ACTIVITY)
                add(PetEvent(event.optString("id"), event.optString("date"), category, event.optString("title"), event.optString("detail"), event.optString("time_label"), event.optString("pet_id")))
            }
        }
        val pref = optJSONObject("preferences") ?: JSONObject()
        return PetGuardContent(
            tutor = TutorProfile(optString("tutor_name", "Tutor PetGuard"), email),
            pets = pets,
            selectedPetId = optString("selected_pet_id"),
            cameras = cameras,
            events = events,
            preferences = PetPreferences(
                pref.optBoolean("light_theme"),
                pref.optBoolean("color_accessible_palette"),
                pref.optBoolean("yolo_enabled", true),
                pref.optBoolean("notifications_enabled", true)
            )
        )
    }

    private fun String.arrayOrEmpty(): JSONArray = if (isBlank()) JSONArray() else JSONArray(this)
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private suspend fun <T> cloudCall(block: suspend () -> T): RepositoryResult<T> = try {
        RepositoryResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        RepositoryResult.Failure(error.message?.takeIf(String::isNotBlank) ?: "Não foi possível sincronizar os dados agora.")
    }

    private fun <T, R> RepositoryResult<T>.map(transform: (T) -> R): RepositoryResult<R> = when (this) {
        is RepositoryResult.Success -> RepositoryResult.Success(transform(value))
        is RepositoryResult.Failure -> this
    }

    private fun RepositoryResult<*>.mapToUnit(): RepositoryResult<Unit> = when (this) {
        is RepositoryResult.Success -> RepositoryResult.Success(Unit)
        is RepositoryResult.Failure -> this
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(task.exception ?: IllegalStateException("Falha na autenticação"))
        }
    }
}
