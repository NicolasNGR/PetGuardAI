package com.petguard.ai.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.petguard.ai.data.CameraAlert

object PetGuardNotificationManager {
    private const val CHANNEL_ID = "petguard_alerts"

    fun show(context: Context, alert: CameraAlert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Alertas do PetGuard", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Avisos gerados pela análise da câmera do pet."
                }
            )
        }

        try {
            NotificationManagerCompat.from(context).notify(
                alert.id.toInt(),
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(
                        alert.petName.takeIf { it.isNotBlank() }?.let { petName ->
                            val category = when (alert.category.uppercase()) {
                                "HIDRATACAO" -> "Água"
                                "ALIMENTACAO" -> "Alimentação"
                                "PRESENCA" -> "Câmera"
                                else -> "Aviso"
                            }
                            "$petName · $category"
                        } ?: "PetGuard AI"
                    )
                    .setContentText(alert.message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: SecurityException) {
            // A permissão pode ser revogada entre a checagem e o envio.
        }
    }
}
