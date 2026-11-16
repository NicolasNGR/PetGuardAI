package com.petguard.ai.ui.screens

import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petguard.ai.R
import com.petguard.ai.ui.theme.petSecondaryTextColor
import com.petguard.ai.ui.viewmodel.AuthUiState
import com.petguard.ai.ui.viewmodel.PetGuardViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(vm: PetGuardViewModel) {
    var revealPassword by remember { mutableStateOf(false) }
    var creatingAccount by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mutedTextColor = petSecondaryTextColor()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(42.dp))
        Image(painterResource(R.drawable.petguard_logo), contentDescription = "Logo PetGuard AI", modifier = Modifier.size(width = 160.dp, height = 128.dp), contentScale = ContentScale.Fit)
        if (vm.localSessionMode) {
            Text("Modo local de demonstração: os dados não serão sincronizados.", Modifier.padding(top = 8.dp),
                color = mutedTextColor, style = MaterialTheme.typography.bodySmall)
        } else if (!vm.supabaseSyncConfigured) {
            Text("Login na nuvem disponível; configure SUPABASE_ANON_KEY para sincronizar pets e eventos.", Modifier.padding(top = 8.dp),
                color = mutedTextColor, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(40.dp))
        Column(Modifier.fillMaxWidth()) {
            if (creatingAccount) {
                Text("Nome do tutor", color = mutedTextColor, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
                OutlinedTextField(value = vm.signUpName, onValueChange = vm::onSignUpNameChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Seu nome", color = mutedTextColor) }, shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, cursorColor = MaterialTheme.colorScheme.primary))
                Spacer(Modifier.height(15.dp))
            }
            Text("E-mail do tutor", color = mutedTextColor, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            OutlinedTextField(value = vm.email, onValueChange = vm::onEmailChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("seu@email.com", color = mutedTextColor) }, leadingIcon = { Icon(Icons.Default.Email, null, tint = mutedTextColor) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, cursorColor = MaterialTheme.colorScheme.primary))
            Spacer(Modifier.height(15.dp))
            Text("Senha", color = mutedTextColor, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            OutlinedTextField(value = vm.password, onValueChange = vm::onPasswordChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Mínimo de 8 caracteres", color = mutedTextColor) },
                visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { revealPassword = !revealPassword }) { Icon(if (revealPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Alternar visibilidade", tint = mutedTextColor) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, cursorColor = MaterialTheme.colorScheme.primary))
            if (!creatingAccount) Text("Esqueceu a senha?", Modifier.align(Alignment.End).padding(top = 10.dp).clickable(onClick = vm::requestPasswordReset), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            if (creatingAccount) {
                Spacer(Modifier.height(15.dp))
                Text("Confirme a senha", color = mutedTextColor, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
                OutlinedTextField(value = vm.passwordConfirmation, onValueChange = vm::onPasswordConfirmationChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, cursorColor = MaterialTheme.colorScheme.primary))
            }
            (vm.authState as? AuthUiState.Error)?.let { Text(it.message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Spacer(Modifier.height(22.dp))
            Button(onClick = { if (creatingAccount) vm.createAccount() else vm.signIn() }, enabled = vm.authState != AuthUiState.Loading, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(11.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                if (vm.authState == AuthUiState.Loading) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Text(if (creatingAccount) "CRIAR CONTA" else "ENTRAR", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (creatingAccount) "Já tem uma conta?" else "Ainda não tem uma conta?", color = mutedTextColor, fontSize = 12.sp)
            TextButton(onClick = { creatingAccount = !creatingAccount; vm.clearAuthError() }) {
                Text(if (creatingAccount) "Entrar" else "Criar conta")
            }
        }
        if (!creatingAccount) {
            Text("Ou entrar com", color = mutedTextColor, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    vm.beginGoogleSignIn()
                    scope.launch {
                        try {
                            val clientIdResource = context.resources.getIdentifier(
                                "default_web_client_id", "string", context.packageName
                            )
                            if (clientIdResource == 0) {
                                vm.failGoogleSignIn("Ative o provedor Google no Firebase e baixe novamente o google-services.json.")
                                return@launch
                            }
                            val activity = context.findActivity()
                            if (activity == null) {
                                vm.failGoogleSignIn("Não foi possível abrir o login Google nesta tela. Tente novamente.")
                                return@launch
                            }
                            val googleIdOption = GetGoogleIdOption.Builder()
                                .setServerClientId(context.getString(clientIdResource))
                                .setFilterByAuthorizedAccounts(false)
                                .build()
                            val request = GetCredentialRequest.Builder()
                                .addCredentialOption(googleIdOption)
                                .build()
                            val result = CredentialManager.create(context).getCredential(activity, request)
                            val credential = result.credential
                            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                                vm.signInWithGoogleIdToken(googleCredential.idToken)
                            } else {
                                vm.failGoogleSignIn("Não foi possível obter uma credencial Google válida.")
                            }
                        } catch (cancelled: GetCredentialCancellationException) {
                            vm.cancelGoogleSignIn()
                        } catch (cancelled: CancellationException) {
                            vm.cancelGoogleSignIn()
                            throw cancelled
                        } catch (_: GetCredentialException) {
                            vm.failGoogleSignIn("Não foi possível entrar com Google. Tente novamente.")
                        } catch (_: Exception) {
                            vm.failGoogleSignIn("Não foi possível entrar com Google. Verifique a configuração e tente novamente.")
                        }
                    }
                },
                enabled = vm.authState != AuthUiState.Loading,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text("G", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text("CONTINUAR COM GOOGLE", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(36.dp)); Text("© 2026 PETGUARD AI · CUIDADO INTELIGENTE", Modifier.padding(top = 36.dp, bottom = 16.dp), color = mutedTextColor, fontSize = 10.sp)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

