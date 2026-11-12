package com.petguard.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petguard.ai.data.EventCategory
import com.petguard.ai.ui.components.CameraConnectionCard
import com.petguard.ai.ui.components.PetAlertCard
import com.petguard.ai.ui.components.PetStatusPill
import com.petguard.ai.ui.theme.petSignalColors
import com.petguard.ai.ui.viewmodel.PetGuardPage
import com.petguard.ai.ui.viewmodel.PetGuardViewModel

@Composable
fun HomeScreen(vm: PetGuardViewModel, onConnectCamera: () -> Unit = { vm.navigate(PetGuardPage.Cameras) }) {
    val signals = petSignalColors()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Pets, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp)); Text("PETGUARD", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Text(" AI", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(20.dp)) {
                Text(if (vm.cameras.any { it.connected }) "Câmera preparada" else "Câmera desconectada",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
            }
        }
        Column(Modifier.padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(12.dp)); Text("Olá, Tutor", color = MaterialTheme.colorScheme.onSurface, fontSize = 21.sp)
            Text("Acompanhe ${vm.pet.name} e a rotina dele.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f), fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            when {
                vm.isLoading -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(18.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Atualizando informações…", Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurface) }
                }
                vm.contentError != null -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(vm.contentError.orEmpty(), color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = vm::loadContent) { Text("Tentar novamente") }
                    }
                }
                vm.latestAttentionEvent() != null -> {
                    val event = vm.latestAttentionEvent()!!
                    PetAlertCard("ATENÇÃO NA ROTINA", event.title, event.detail, event.timeLabel, signals.attention)
                }
                else -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text("Nenhum alerta recente para ${vm.pet.name}.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(18.dp))
            if (vm.cameras.any { it.connected }) {
                val connectedCamera = vm.cameras.last { it.connected }
                Card(onClick = onConnectCamera, modifier = Modifier.fillMaxWidth().heightIn(min = 210.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column {
                        if (vm.cameraPrivacyAccepted) {
                            CameraStreamPreview(connectedCamera.url, Modifier.fillMaxWidth().height(210.dp))
                            Text("${connectedCamera.name} · Toque para abrir Câmeras", Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        } else {
                            CameraPrivacyGate(Modifier.fillMaxWidth().heightIn(min = 210.dp), vm::acceptCameraPrivacyNotice)
                        }
                    }
                }
            } else {
                CameraConnectionCard(onConnect = onConnectCamera, modifier = Modifier.heightIn(min = 210.dp), compact = true)
            }
            Spacer(Modifier.height(14.dp))
            PetStatusPill(Icons.Default.SmartToy, "Atividade", if (vm.yoloEnabled) "Aguardando análise" else "Desativada", signals.info)
            Spacer(Modifier.height(8.dp))
            val hydration = vm.eventsForSelectedPet().firstOrNull { it.category == EventCategory.ATTENTION }
            PetStatusPill(Icons.Default.LocalDrink, "Hidratação", if (hydration == null) "Sem alertas" else "Atenção", if (hydration == null) signals.healthy else signals.attention)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InitialPetSetupDialog(onSave: (String, String) -> Unit, onLater: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var species by remember { mutableStateOf("Cachorro") }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Cadastre seu pet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Personalize o acompanhamento do seu animal. Se quiser mudar depois, vá em Perfil > Meus Pets.")
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Nome") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = species == "Cachorro", onClick = { species = "Cachorro" }, label = { Text("Cachorro") })
                    FilterChip(selected = species == "Gato", onClick = { species = "Gato" }, label = { Text("Gato") })
                }
            }
        },
        confirmButton = { Button(enabled = name.trim().length >= 2, onClick = { onSave(name, species) }) { Text("Salvar pet") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Agora não") } }
    )
}