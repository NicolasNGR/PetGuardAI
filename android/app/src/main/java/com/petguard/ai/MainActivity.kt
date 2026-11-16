package com.petguard.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.petguard.ai.data.PetGuardSessionStore
import com.petguard.ai.ui.navigation.PetGuardApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PetGuardSessionStore.initialize(applicationContext)
        enableEdgeToEdge()
        setContent { PetGuardApp() }
    }
}
