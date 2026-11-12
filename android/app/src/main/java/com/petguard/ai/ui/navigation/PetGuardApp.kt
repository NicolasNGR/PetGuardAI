package com.petguard.ai.ui.navigation

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.petguard.ai.ui.components.PetBottomBar
import com.petguard.ai.ui.screens.*
import com.petguard.ai.ui.theme.PetGuardTheme
import com.petguard.ai.ui.viewmodel.PetGuardPage
import com.petguard.ai.ui.viewmodel.PetGuardViewModel
import com.petguard.ai.notifications.PetGuardNotificationManager

@Composable
fun PetGuardApp(vm: PetGuardViewModel = viewModel()) {
    PetGuardTheme(light = vm.lightTheme, contrast = vm.highContrast) {
        val view = LocalView.current
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, vm.page, vm.cameras, vm.yoloEnabled) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START && vm.page != null) {
                    vm.startSharedSync()
                    if (vm.yoloEnabled && vm.cameras.any { it.connected }) vm.startAlertPolling()
                } else if (event == Lifecycle.Event.ON_STOP) {
                    vm.stopAlertPolling()
                    vm.stopSharedSync()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                vm.page != null && vm.yoloEnabled && vm.cameras.any { it.connected }
            ) vm.startAlertPolling()
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && vm.page != null) {
                vm.startSharedSync()
            }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                vm.stopAlertPolling()
                vm.stopSharedSync()
            }
        }
        LaunchedEffect(vm.pendingAlertNotifications) {
            if (vm.notificationsEnabled) {
                vm.pendingAlertNotifications.forEach { alert -> PetGuardNotificationManager.show(context, alert) }
            }
            vm.clearPendingAlertNotifications()
        }
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = vm.lightTheme
                isAppearanceLightNavigationBars = vm.lightTheme
            }
        }
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(vm.actionMessage) {
            vm.actionMessage?.let { message ->
                snackbarHostState.showSnackbar(message)
                vm.dismissMessage()
            }
        }
        Box(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val current = vm.page
                    if (current == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 480.dp).fillMaxSize()) { LoginScreen(vm) }
                        }
                    } else Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                                when (current) {
                                    PetGuardPage.Home -> HomeScreen(vm)
                                    PetGuardPage.Cameras -> CamerasScreen(vm)
                                    PetGuardPage.Timeline -> TimelineScreen(vm)
                                    PetGuardPage.Profile -> ProfileScreen(vm)
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                                PetBottomBar(current, vm.lightTheme) { vm.navigate(it) }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp))
        }
    }
}
