package com.petguard.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petguard.ai.data.EventCategory
import com.petguard.ai.ui.components.PetAlertCard
import com.petguard.ai.ui.theme.petSignalColors
import com.petguard.ai.ui.viewmodel.PetGuardViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(vm: PetGuardViewModel) {
    val signals = petSignalColors()
    val selected = remember(vm.selectedDate) { LocalDate.parse(vm.selectedDate) }
    val dateLabel = remember(selected) { selected.format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale("pt", "BR"))) }
    var showCalendar by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Text("Linha do Tempo", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = { showCalendar = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = "Selecionar data", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Text(dateLabel.replaceFirstChar { it.uppercase(Locale("pt", "BR")) },
            Modifier.fillMaxWidth().padding(bottom = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f),
            style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(selected.minusDays(2), selected.minusDays(1), selected).forEach { date ->
                val dateString = date.toString()
                val isSelected = dateString == vm.selectedDate
                val weekday = if (date == LocalDate.now()) "hoje" else date.format(DateTimeFormatter.ofPattern("EEE", Locale("pt", "BR"))).replace(".", "")
                Surface(Modifier.weight(1f).heightIn(min = 76.dp).selectable(selected = isSelected, role = Role.RadioButton) { vm.selectDate(dateString) },
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(weekday.replaceFirstChar { it.uppercase(Locale("pt", "BR")) },
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                        Text(date.dayOfMonth.toString(), color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        when {
            vm.eventsForSelectedDay().isNotEmpty() -> vm.eventsForSelectedDay().forEach { event ->
                val (label, color) = when (event.category) {
                    EventCategory.ATTENTION -> "Atenção" to signals.attention
                    EventCategory.INJURY -> "Machucado" to signals.danger
                    EventCategory.HEALTHY -> "Saudável" to signals.healthy
                    EventCategory.ACTIVITY -> "Atividade" to signals.healthy
                }
                PetAlertCard(label, event.title, event.detail, event.timeLabel, color)
                Spacer(Modifier.height(14.dp))
            }
            vm.isLoading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(12.dp)); Text("Carregando histórico…", color = MaterialTheme.colorScheme.onSurface) }
            vm.contentError != null -> {
                Text(vm.contentError.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::loadContent) { Text("Tentar novamente") }
            }
            vm.eventsForSelectedDay().isEmpty() -> Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nenhum acontecimento nesta data", color = MaterialTheme.colorScheme.onSurface)
                    Text("Os eventos detectados aparecerão aqui.", Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showCalendar) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = vm.selectedDateMillis)
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let(vm::selectDateMillis)
                    showCalendar = false
                }) { Text("Ir para data") }
            },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("Cancelar") } }
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
}
