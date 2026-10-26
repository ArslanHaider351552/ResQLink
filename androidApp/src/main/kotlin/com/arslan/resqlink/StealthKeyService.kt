package com.arslan.resqlink

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class StealthKeyService : AccessibilityService() {

    private var volumeDownPressCount = 0
    private var lastPressTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not required for hardware key filtering
    }

    override fun onInterrupt() {
        // Called if system interrupts accessibility features
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastPressTime < 1200) {
                volumeDownPressCount++
            } else {
                volumeDownPressCount = 1
            }
            lastPressTime = currentTime

            if (volumeDownPressCount >= 3) {
                volumeDownPressCount = 0
                dispatchStealthSos()
            }
        }
        return super.onKeyEvent(event)
    }

    private fun dispatchStealthSos() {
        // 1. Silent pocket haptic feedback (50ms pulse)
        triggerSilentVibration(this)

        // 2. Display "SOS SENT" Toast pop-up on main thread
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                applicationContext,
                "🚨 SOS SENT! Emergency contacts & police dispatched.",
                Toast.LENGTH_LONG
            ).show()
        }

        // 3. Start SilentSosService in background (fetches GPS & sends SMS/API)
        val sosIntent = Intent(this, SilentSosService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(sosIntent)
        } else {
            startService(sosIntent)
        }

        // 4. Open MainActivity directly to the Active Tracker view
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_STEALTH_TRIGGERED", true)
        }
        startActivity(mainIntent)
    }

    private fun triggerSilentVibration(context: Context) {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }
}