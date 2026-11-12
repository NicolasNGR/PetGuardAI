package com.petguard.ai.data

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Firebase Auth identity with Supabase as the shared account data store. */
class FirebaseAuthPetGuardRepository(
    private val appData: PetGuardRepository = SupabasePetGuardRepository()
) : PetGuardRepository by appData {

    override suspend fun loadContent(): RepositoryResult<PetGuardContent> {
        val data = appData.loadContent()
        if (data !is RepositoryResult.Success) return data
        val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        val content = if (user == null) data.value else data.value.copy(
            tutor = data.value.tutor.copy(
                name = user.displayName?.takeIf { it.isNotBlank() } ?: data.value.tutor.name,
                email = user.email.orEmpty()
            )
        )
        return RepositoryResult.Success(content)
    }

    override suspend fun currentUserEmail(): RepositoryResult<String?> = firebaseCall {
        FirebaseAuth.getInstance().currentUser?.email
    }

    override suspend fun signIn(email: String, password: String): RepositoryResult<String> = firebaseCall {
        val result = FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).awaitResult()
        result.user?.email ?: email
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): RepositoryResult<String> = firebaseCall {
        require(idToken.isNotBlank()) { "Google não retornou um token de autenticação válido." }
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = FirebaseAuth.getInstance().signInWithCredential(credential).awaitResult()
        result.user?.email ?: throw IllegalStateException("O Google não retornou um e-mail para esta conta.")
    }

    override suspend fun createAccount(name: String, email: String, password: String): RepositoryResult<String> = firebaseCall {
        val result = FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password).awaitResult()
        val user = result.user
        if (user != null && name.isNotBlank()) {
            try {
                val profile = UserProfileChangeRequest.Builder().setDisplayName(name).build()
                user.updateProfile(profile).awaitResult()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A profile-name update failure does not invalidate a successfully created Firebase account.
            }
        }
        user?.email ?: email
    }

    override suspend fun sendPasswordReset(email: String): RepositoryResult<Unit> = try {
        FirebaseAuth.getInstance().sendPasswordResetEmail(email).awaitResult()
        RepositoryResult.Success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (error.authErrorCode() == "ERROR_USER_NOT_FOUND") RepositoryResult.Success(Unit)
        else RepositoryResult.Failure(error.toUserMessage())
    }

    override suspend fun signOut(): RepositoryResult<Unit> = try {
        FirebaseAuth.getInstance().signOut()
        RepositoryResult.Success(Unit)
    } catch (error: Exception) {
        RepositoryResult.Failure(error.toUserMessage())
    }

    override suspend fun updateTutorName(name: String): RepositoryResult<String> = firebaseCall {
        val user = FirebaseAuth.getInstance().currentUser ?: throw IllegalStateException("No signed-in Firebase user")
        when (val saved = appData.updateTutorName(name)) {
            is RepositoryResult.Success -> Unit
            is RepositoryResult.Failure -> error(saved.message)
        }
        val profile = UserProfileChangeRequest.Builder().setDisplayName(name).build()
        user.updateProfile(profile).awaitResult()
        name
    }

    private suspend fun <T> firebaseCall(action: suspend () -> T): RepositoryResult<T> = try {
        RepositoryResult.Success(action())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        RepositoryResult.Failure(error.toUserMessage())
    }

    private fun Exception.authErrorCode(): String? = (this as? FirebaseAuthException)?.errorCode

    private fun Exception.toUserMessage(): String {
        if (this is IllegalStateException && message.orEmpty().contains("FirebaseApp", ignoreCase = true)) {
            return "Firebase não configurado. Adicione o google-services.json do projeto e ative E-mail/senha no Firebase Console."
        }
        return when (authErrorCode()) {
            "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" ->
                "E-mail ou senha incorretos."
            "ERROR_INVALID_EMAIL" -> "Digite um e-mail válido."
            "ERROR_WEAK_PASSWORD" -> "A senha não atende aos requisitos do Firebase."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "Já existe uma conta com esse e-mail."
            "ERROR_OPERATION_NOT_ALLOWED" -> "Ative o provedor de autenticação correspondente no Firebase Console."
            "ERROR_TOO_MANY_REQUESTS" -> "Muitas tentativas. Aguarde um pouco e tente novamente."
            "ERROR_NETWORK_REQUEST_FAILED" -> "Sem conexão com a internet. Verifique a rede e tente novamente."
            "ERROR_USER_DISABLED" -> "Esta conta está desativada. Entre em contato com o suporte."
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> "Este e-mail já está cadastrado por outro método de acesso. Entre usando o método original."
            else -> "Não foi possível autenticar agora. Verifique a conexão e tente novamente."
        }
    }
}

internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase request failed"))
        }
    }
}
