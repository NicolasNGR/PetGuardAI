package com.petguard.ai.ui.previews

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.petguard.ai.ui.components.PetBottomBar
import com.petguard.ai.ui.screens.CamerasScreen
import com.petguard.ai.ui.screens.HomeScreen
import com.petguard.ai.ui.screens.LoginScreen
import com.petguard.ai.ui.screens.ProfileScreen
import com.petguard.ai.ui.screens.TimelineScreen
import com.petguard.ai.ui.theme.PetGuardTheme
import com.petguard.ai.ui.viewmodel.PetGuardPage
import com.petguard.ai.ui.viewmodel.PetGuardViewModel

@Preview(name = "Login · Escuro", showBackground = true, showSystemUi = true)
@Composable
fun LoginDarkPreview() = LoginPreview(light = false)

@Preview(name = "Login · Claro", showBackground = true, showSystemUi = true)
@Composable
fun LoginLightPreview() = LoginPreview(light = true)

@Preview(name = "Início", showBackground = true, showSystemUi = true)
@Composable
fun HomePreview() {
    val vm = remember { PetGuardViewModel() }
    PagePreview(PetGuardPage.Home) { HomeScreen(vm) }
}

@Preview(name = "Câmeras", showBackground = true, showSystemUi = true)
@Composable
fun CamerasPreview() {
    val vm = remember { PetGuardViewModel() }
    PagePreview(PetGuardPage.Cameras) { CamerasScreen(vm) }
}

@Preview(name = "Linha do Tempo", showBackground = true, showSystemUi = true)
@Composable
fun TimelinePreview() {
    val vm = remember { PetGuardViewModel() }
    PagePreview(PetGuardPage.Timeline) { TimelineScreen(vm) }
}

@Preview(name = "Perfil", showBackground = true, showSystemUi = true)
@Composable
fun ProfilePreview() {
    val vm = remember { PetGuardViewModel() }
    PagePreview(PetGuardPage.Profile) { ProfileScreen(vm) }
}

@Preview(name = "Paleta para daltonismo", showBackground = true, showSystemUi = true)
@Composable
fun ProfileColorAccessiblePreview() {
    val vm = remember { PetGuardViewModel() }
    PetGuardTheme(light = false, contrast = true) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { ProfileScreen(vm) }
    }
}

@Composable
private fun LoginPreview(light: Boolean) {
    PetGuardTheme(light = light, contrast = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            LoginScreen(remember { PetGuardViewModel() })
        }
    }
}

@Composable
private fun PagePreview(page: PetGuardPage, content: @Composable () -> Unit) {
    PetGuardTheme(light = false, contrast = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize()) { content() }
                PetBottomBar(selected = page, light = false, onSelect = {})
            }
        }
    }
}
