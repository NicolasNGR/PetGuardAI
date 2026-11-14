package com.petguard.ai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.petguard.ai.data.PetProfile
import com.petguard.ai.ui.components.PetPreferenceRow
import com.petguard.ai.ui.theme.petSignalColors
import com.petguard.ai.ui.theme.petSecondaryTextColor
import com.petguard.ai.ui.viewmodel.PetGuardPage
import com.petguard.ai.ui.viewmodel.PetGuardViewModel

@Composable
fun ProfileScreen(vm: PetGuardViewModel) {
    val signals = petSignalColors()
    val context = LocalContext.current
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        vm.setNotifications(granted)
        if (!granted) vm.showMessage("Notificações não autorizadas. Você pode ativá-las nas configurações do Android.")
    }
    var showPetManager by remember { mutableStateOf(false) }
    var startAddingPet by remember { mutableStateOf(false) }
    var editPet by remember { mutableStateOf(false) }
    var editTutor by remember { mutableStateOf(false) }
    var confirmResetDemo by remember { mutableStateOf(false) }
    LaunchedEffect(vm.addPetDialogRequested) {
        if (vm.addPetDialogRequested) {
            showPetManager = true
            startAddingPet = true
            vm.consumeAddPetDialogRequest()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        if (vm.isLoading) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        vm.contentError?.let { message ->
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = vm::loadContent) { Text("Tentar novamente") }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(48.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(vm.tutorName, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, maxLines = 1)
                Text(vm.tutorEmail.ifBlank { "Conta PetGuard" }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f), fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = { editTutor = true }) { Icon(Icons.Default.Edit, "Editar nome do tutor", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(24.dp)); Text("Acessibilidade e Usabilidade", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp); Spacer(Modifier.height(12.dp))
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
            Column {
                PetPreferenceRow(Icons.Default.WbSunny, "Modo Claro", vm.lightTheme, vm::onLightThemeChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                PetPreferenceRow(Icons.Default.Visibility, "Paleta para daltonismo", vm.highContrast, vm::onHighContrastChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                PetPreferenceRow(Icons.Default.SmartToy, "Análise YOLO", vm.yoloEnabled, vm::setYolo)
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                PetPreferenceRow(
                    Icons.Default.Notifications,
                    "Notificações do PetGuard",
                    vm.notificationsEnabled && notificationPermissionGranted,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            vm.setNotifications(false)
                        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionGranted) {
                            vm.setNotifications(true)
                        } else {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )

            }
        }
        Spacer(Modifier.height(20.dp)); Text("Gestão do Pet", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp); Spacer(Modifier.height(12.dp))
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
            Column {
                ProfileActionRow(Icons.Default.Pets, "Meus Pets", Icons.Default.Add,
                    onClick = { showPetManager = true }, subtitle = if (vm.pets.isEmpty()) "Adicione seu primeiro pet" else "${vm.pet.name.replaceFirstChar { it.uppercase() }} · ${vm.pet.species} · ${vm.pets.size} pet(s)")
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                ProfileActionRow(Icons.Default.CameraAlt, "Dispositivos Conectados", Icons.Default.Add,
                    onClick = { vm.navigate(PetGuardPage.Cameras) }, subtitle = "${vm.cameras.size} dispositivo(s)")
            }
        }
        TextButton(onClick = { confirmResetDemo = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Limpar histórico de testes", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.height(34.dp)); Text("Conta", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp); Spacer(Modifier.height(12.dp))
        Button(onClick = vm::signOut, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.Logout, null, tint = signals.danger); Text("Terminar sessão", Modifier.padding(start = 12.dp), color = signals.danger)
        }
        Text("VERSÃO 1.0 · PETGUARD AI", Modifier.fillMaxWidth().padding(vertical = 16.dp), color = petSecondaryTextColor(), fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }

    if (showPetManager) PetsDialog(
        pets = vm.pets,
        selectedId = vm.selectedPetId,
        startAdding = startAddingPet,
        onDismiss = { showPetManager = false; startAddingPet = false },
        onSelect = vm::selectPet,
        onEditSelected = { showPetManager = false; editPet = true },
        onAddPet = { name, species, size, weight -> vm.addPet(name, species, size, weight) }
    )
    if (editPet && vm.pets.isNotEmpty()) EditPetDialog(vm.pet, onDismiss = { editPet = false }, onSave = { name, species, size, weight -> vm.updatePet(name, species, size, weight); editPet = false })
    if (editTutor) EditTutorNameDialog(vm.tutorName, onDismiss = { editTutor = false }, onSave = { name -> vm.updateTutorName(name); editTutor = false })
    if (confirmResetDemo) AlertDialog(onDismissRequest = { confirmResetDemo = false }, title = { Text("Limpar histórico de testes?") },
        text = { Text("Alertas e dados de demonstração da câmera serão removidos.") },
        confirmButton = { TextButton(onClick = { vm.resetDemoData(); confirmResetDemo = false }) { Text("Limpar") } },
        dismissButton = { TextButton(onClick = { confirmResetDemo = false }) { Text("Cancelar") } })

}

@Composable
private fun ProfileActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    subtitle: String
) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), fontSize = 10.sp)
        }
        Icon(trailing, null, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PetsDialog(
    pets: List<PetProfile>,
    selectedId: String,
    startAdding: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onEditSelected: () -> Unit,
    onAddPet: (String, String, String, Double?) -> Unit
) {
    var adding by remember(startAdding) { mutableStateOf(startAdding) }
    var name by remember { mutableStateOf("") }
    var species by remember { mutableStateOf("Cachorro") }
    var porte by remember { mutableStateOf("medio") }
    var peso by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (adding) "Adicionar pet" else "Meus Pets", Modifier.weight(1f))
                if (!adding) IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Adicionar pet") }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!adding) {
                    if (pets.isEmpty()) Text("Nenhum pet cadastrado. Adicione seu primeiro pet para começar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    pets.forEach { pet ->
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().selectable(selected = pet.id == selectedId, role = Role.RadioButton) { onSelect(pet.id) }) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (pet.species == "Gato") Icons.Default.Pets else Icons.Default.Pets, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(pet.name.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.onSurface)
                                    Text(pet.species, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                                }
                                RadioButton(selected = pet.id == selectedId, onClick = null)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Nome do pet") }, singleLine = true)
                    Text("Espécie", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = species == "Cachorro", onClick = { species = "Cachorro" }, label = { Text("Cachorro") })
                        FilterChip(selected = species == "Gato", onClick = { species = "Gato" }, label = { Text("Gato") })
                    }
                    Text("Porte", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("pequeno" to "Pequeno", "medio" to "Médio", "grande" to "Grande").forEach { (value, label) ->
                            FilterChip(selected = porte == value, onClick = { porte = value }, label = { Text(label) })
                        }
                    }
                    val pesoError = weightInputError(peso)
                    OutlinedTextField(value = peso, onValueChange = { input -> peso = input.filter { it.isDigit() || it == ',' || it == '.' }.take(6) }, label = { Text("Peso (kg), opcional") }, singleLine = true,
                        isError = pesoError != null, supportingText = { pesoError?.let { Text(it) } })
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!adding && pets.isNotEmpty()) TextButton(onClick = onEditSelected) { Text("Editar selecionado") }
                Button(enabled = !adding || (name.trim().length >= 2 && weightInputError(peso) == null), onClick = {
                    if (adding) { onAddPet(name, species, porte, peso.replace(',', '.').toDoubleOrNull()); onDismiss() } else onDismiss()
                }) { Text(if (adding) "Adicionar" else "Concluir") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (adding) adding = false else onDismiss() }) { Text(if (adding) "Voltar" else "Cancelar") }
        }
    )
}

@Composable
private fun EditPetDialog(pet: PetProfile, onDismiss: () -> Unit, onSave: (String, String, String, Double?) -> Unit) {
    var name by remember(pet.id, pet.name) { mutableStateOf(pet.name) }
    var species by remember(pet.id, pet.species) { mutableStateOf(pet.species) }
    var porte by remember(pet.id, pet.porte) { mutableStateOf(pet.porte) }
    var peso by remember(pet.id, pet.pesoKg) { mutableStateOf(pet.pesoKg?.toString().orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Editar ${pet.name.replaceFirstChar { it.uppercase() }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Nome") }, singleLine = true)
                Text("Espécie", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = species == "Cachorro", onClick = { species = "Cachorro" }, label = { Text("Cachorro") })
                    FilterChip(selected = species == "Gato", onClick = { species = "Gato" }, label = { Text("Gato") })
                }
                Text("Porte", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("pequeno" to "Pequeno", "medio" to "Médio", "grande" to "Grande").forEach { (value, label) ->
                        FilterChip(selected = porte == value, onClick = { porte = value }, label = { Text(label) })
                    }
                }
                val pesoError = weightInputError(peso)
                OutlinedTextField(value = peso, onValueChange = { input -> peso = input.filter { it.isDigit() || it == ',' || it == '.' }.take(6) }, label = { Text("Peso (kg), opcional") }, singleLine = true,
                    isError = pesoError != null, supportingText = { pesoError?.let { Text(it) } })
            }
        },
        confirmButton = { TextButton(enabled = name.trim().length >= 2 && weightInputError(peso) == null, onClick = { onSave(name, species, porte, peso.replace(',', '.').toDoubleOrNull()) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

private fun weightInputError(value: String): String? {
    if (value.isBlank()) return null
    val weight = value.replace(',', '.').toDoubleOrNull() ?: return "Informe um peso válido em kg."
    return when {
        weight <= 0.0 -> "O peso deve ser maior que 0 kg."
        weight > 200.0 -> "O peso máximo é 200 kg."
        else -> null
    }
}

@Composable
private fun EditTutorNameDialog(name: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var editedName by remember(name) { mutableStateOf(name) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Nome do tutor") },
        text = { OutlinedTextField(value = editedName, onValueChange = { editedName = it.take(60) }, label = { Text("Nome") }, singleLine = true) },
        confirmButton = { TextButton(enabled = editedName.trim().length >= 2, onClick = { onSave(editedName) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
