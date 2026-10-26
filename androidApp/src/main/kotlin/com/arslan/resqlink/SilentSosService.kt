package com.arslan.resqlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*

class SilentSosService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSilentForeground()
        dispatchSilentPoliceAlert()
        return START_STICKY
    }

    private fun startSilentForeground() {
        val channelId = "silent_sos_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Sync",
                NotificationManager.IMPORTANCE_MIN // Low priority channel prevents heads-up alerts
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Sync")
            .setContentText("Syncing background state...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(999, notification)
    }

    private fun dispatchSilentPoliceAlert() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    val lat = location?.latitude ?: 0.0
                    val lng = location?.longitude ?: 0.0

                    serviceScope.launch {
                        // 1. Send HTTP POST alert to Police API endpoint
                        sendApiPayloadToPolice(lat, lng)

                        // 2. Send direct emergency SMS fallback
                        sendSilentSmsToPolice(lat, lng)
                    }
                }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private suspend fun sendApiPayloadToPolice(lat: Double, lng: Double) {
        // HTTP API dispatch payload (e.g. via Ktor, Retrofit, or OkHttp)
        // {"alert_type": "SILENT_DURESS", "lat": lat, "lng": lng, "timestamp": System.currentTimeMillis()}
    }

    private fun sendSilentSmsToPolice(lat: Double, lng: Double) {
        val policeSmsNumber = "911" // Emergency SMS dispatch destination
        val message = "EMERGENCY SILENT SOS: Armed Robbery/Duress in progress. Live Location: https://maps.google.com/?q=$lat,$lng"

        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(policeSmsNumber, null, message, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}