package com.petguard.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petguard.ai.ui.theme.*
import com.petguard.ai.ui.viewmodel.PetGuardPage

@Composable
fun PetBottomBar(selected: PetGuardPage, light: Boolean, onSelect: (PetGuardPage) -> Unit) {
    val pages = listOf(PetGuardPage.Home, PetGuardPage.Cameras, PetGuardPage.Timeline, PetGuardPage.Profile)
    val icons = listOf(Icons.Default.Home, Icons.Default.CameraAlt, Icons.Default.InsertChart, Icons.Default.Person)
    val labels = listOf("Início", "Câmeras", "Status", "Perfil")
    NavigationBar(containerColor = if (light) Color.White else PetInk, tonalElevation = 0.dp) {
        pages.forEachIndexed { index, page ->
            NavigationBarItem(selected = page == selected, onClick = { onSelect(page) },
                icon = { Icon(icons[index], contentDescription = null) }, label = { Text(labels[index], fontSize = 12.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = Color.Transparent,
                    unselectedIconColor = petSecondaryTextColor(), unselectedTextColor = petSecondaryTextColor()))
        }
    }
}

@Composable
fun PetAlertCard(title: String, headline: String, detail: String, time: String, accent: Color, modifier: Modifier = Modifier) {
    val signalIcon = when {
        title.contains("Saudável", ignoreCase = true) -> Icons.Default.CheckCircle
        title.contains("Machucado", ignoreCase = true) -> Icons.Default.Report
        title.contains("Atividade", ignoreCase = true) -> Icons.Default.DirectionsRun
        else -> Icons.Default.Warning
    }
    val highContrast = MaterialTheme.colorScheme.background == Color.Black || MaterialTheme.colorScheme.background == Color.White
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), shadowElevation = 7.dp,
        modifier = modifier.fillMaxWidth(), border = BorderStroke(if (highContrast) 2.dp else 1.dp, accent.copy(alpha = if (highContrast) 1f else .7f))) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(signalIcon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Text(title, Modifier.padding(start = 8.dp).weight(1f), color = accent, fontSize = 13.sp)
                Text(time, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
            }
            Text(headline, Modifier.padding(start = 28.dp, top = 6.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            Text(detail, Modifier.padding(start = 28.dp, top = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f), fontSize = 12.sp)
        }
    }
}

@Composable
fun PetStatusPill(icon: ImageVector, title: String, state: String, stateColor: Color) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Text(title, Modifier.weight(1f).padding(start = 14.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            Text(state, color = stateColor, fontSize = 13.sp)
        }
    }
}

@Composable
fun PetPreferenceRow(icon: ImageVector, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        Text(title, Modifier.weight(1f).padding(start = 14.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.LightGray, uncheckedTrackColor = Color.Gray))
    }
}

@Composable
fun CameraConnectionCard(onConnect: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .75f))) {
        Column(Modifier.padding(if (compact) 16.dp else 22.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.VideocamOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (compact) 36.dp else 48.dp))
            Spacer(Modifier.height(10.dp))
            Text("Conecte uma câmera", color = MaterialTheme.colorScheme.onSurface, fontSize = if (compact) 16.sp else 18.sp)
            Text("A imagem do Thor aparecerá aqui quando a câmera estiver conectada.",
                Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f),
                fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onConnect, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Icon(Icons.Default.AddLink, contentDescription = null)
                Text("Conectar câmera", Modifier.padding(start = 8.dp))
            }
            Text("A análise YOLO estará disponível após a conexão.", Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f), fontSize = 10.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

