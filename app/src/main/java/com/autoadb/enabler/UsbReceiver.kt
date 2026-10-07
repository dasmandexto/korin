package com.autoadb.enabler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("AdbAutoEnabler", "USB/Живлення подія: $action")

        try {
            // Гарантуємо, що USB налагодження активоване в системних налаштуваннях (Варіант А)
            val isAdbEnabled = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            )

            if (isAdbEnabled == 0) {
                Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.ADB_ENABLED,
                    1
                )
                Log.i("AdbAutoEnabler", "USB Debugging (adb_enabled) примусово виставлено в 1")
            }
        } catch (e: Exception) {
            Log.w("AdbAutoEnabler", "Не вдалося перевірити/виставити adb_enabled: ${e.message}")
        }
    }
}
