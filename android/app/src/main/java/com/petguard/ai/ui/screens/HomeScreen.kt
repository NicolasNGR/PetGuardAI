package com.petguard.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petguard.ai.data.EventCategory
import com.petguard.ai.ui.components.PetAlertCard
import com.petguard.ai.ui.components.PetStatusPill
import com.petguard.ai.ui.theme.petSignalColors
import com.petguard.ai.ui.viewmodel.PetGuardPage
import com.petguard.ai.ui.viewmodel.PetGuardViewModel

@Composable
fun HomeScreen(vm: PetGuardViewModel, onConnectCamera: () -> Unit = { vm.navigate(PetGuardPage.Cameras) }) {
    val signals = petSignalColors()
    val goalCameraUrl = vm.cameras.lastOrNull { it.connected && it.url.isNotBlank() }?.url
    LaunchedEffect(vm.focusedPet.id, vm.focusedPet.porte, vm.focusedPet.pesoKg, goalCameraUrl) {
        vm.loadDailyGoals(vm.focusedPet, goalCameraUrl)
    }
    val focusedPet = vm.focusedPet
    val goals = vm.dailyGoals?.takeIf { it.petId == focusedPet.id } ?: vm.estimateDailyGoals(focusedPet)
    val sizeLabel = when (focusedPet.porte.lowercase()) {
        "pequeno" -> "Pequeno"
        "grande" -> "Grande"
        else -> "Médio"
    }
    val weightLabel = focusedPet.pesoKg?.let { "${formatKg(it)} kg" }
        ?: "${formatKg(goals.referenceWeightKg)} kg (estimado)"
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
            Text("Acompanhe ${vm.focusedPet.name.replaceFirstChar { it.uppercase() }} e a rotina dele.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f), fontSize = 13.sp)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 7.dp)) {
                Text("${focusedPet.species.ifBlank { "Pet" }.replaceFirstChar { it.uppercase() }} • $sizeLabel • $weightLabel",
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
            }
            if (vm.pets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.pets.forEach { item ->
                        FilterChip(selected = vm.selectedPetId == item.id, onClick = { vm.selectPet(item.id) },
                            label = { Text(item.name.replaceFirstChar { it.uppercase() }, maxLines = 1) })
                    }
                }
            }
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
                vm.latestHomeAlert() != null -> {
                    val alert = vm.latestHomeAlert()!!
                    PetAlertCard(alert.title, "Alerta da rotina", alert.detail, alert.timeLabel, signals.attention,
                        actionLabel = "Ciente", onAction = { vm.dismissHomeAlert(alert.id) })
                }
                else -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text("Nenhum alerta recente para ${vm.focusedPet.name.replaceFirstChar { it.uppercase() }}.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(18.dp))
            if (vm.cameras.any { it.connected }) {
                val connectedCamera = vm.cameras.last { it.connected }
                Card(onClick = onConnectCamera, modifier = Modifier.fillMaxWidth().heightIn(min = 210.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column {
                        if (vm.cameraPrivacyAccepted && !vm.cameraStreamUrl.isNullOrBlank()) {
                            CameraStreamPreview(vm.cameraStreamUrl!!, Modifier.fillMaxWidth().height(210.dp))
                            Text("${connectedCamera.name} · Toque para abrir Câmeras", Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        } else {
                            CameraWaitingCard(onConnectCamera)
                        }
                    }
                }
            } else {
                CameraWaitingCard(onConnectCamera)
            }
            Spacer(Modifier.height(14.dp))
            PetStatusPill(Icons.Default.SmartToy, "Atividade", if (vm.yoloEnabled) vm.activityForFocusedPet() else "Desativada", signals.info)
            Spacer(Modifier.height(8.dp))
            val hydration = vm.eventsForSelectedPet().firstOrNull { it.category == EventCategory.HIDRATACAO || it.category == EventCategory.ATTENTION }
            PetStatusPill(Icons.Default.LocalDrink, "Hidratação", if (hydration == null) "Sem alertas" else "Atenção", if (hydration == null) signals.healthy else signals.attention)
            Text(
                if (focusedPet.pesoKg != null) "Meta diária: ~${goals.hydrationMlDaily} ml (baseado em ${formatKg(goals.referenceWeightKg)} kg)"
                else "Meta diária: ~${goals.hydrationMlDaily} ml (estimativa porte $sizeLabel)",
                Modifier.padding(start = 12.dp, top = 5.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f), fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            val feeding = vm.latestFeedingEvent()
            PetStatusPill(Icons.Default.Restaurant, "Alimentação", feeding?.timeLabel ?: "Sem alertas", if (feeding == null) signals.healthy else signals.info)
            Text("Meta diária: ~${goals.foodGramsDaily} g de ração", Modifier.padding(start = 12.dp, top = 5.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f), fontSize = 11.sp)
            val careTip = when (focusedPet.porte.lowercase()) {
                "pequeno" -> "Divida a ração em 2-3 porções e renove a água fresca."
                "grande" -> "Evite exercício intenso logo após as refeições."
                else -> "Mantenha água fresca acessível após passeios."
            }
            Text(careTip, Modifier.padding(start = 12.dp, top = 7.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .76f), fontSize = 11.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatKg(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)

@Composable
private fun CameraWaitingCard(onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 125.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text("Câmera em espera", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                Text("Toque em Câmeras para iniciar o monitoramento ao vivo", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
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
