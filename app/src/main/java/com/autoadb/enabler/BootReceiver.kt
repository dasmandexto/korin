package com.autoadb.enabler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("AdbAutoEnabler", "Отримано сигнал завантаження: $action")

        if (Intent.ACTION_BOOT_COMPLETED == action ||
            "android.intent.action.QUICKBOOT_POWERON" == action ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED == action
        ) {
            // [ВАРІАНТ А]: Миттєво активуємо USB Debugging у системному реєстрі
            try {
                Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.ADB_ENABLED,
                    1
                )
                Log.i("AdbAutoEnabler", "[Варіант А] USB Debugging гарантовано активовано")
            } catch (e: Exception) {
                Log.w("AdbAutoEnabler", "Помилка встановлення adb_enabled: ${e.message}")
            }

            // [ВАРІАНТ Б]: Готуємося увімкнути Wireless Debugging через Accessibility Service
            AdbEnablerAccessibilityService.isTriggered = true

            // Запускаємо параметри розробника через 2 секунди після завантаження UI
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    }
                    context.startActivity(settingsIntent)
                    Log.i("AdbAutoEnabler", "[Варіант Б] Запущено Intent налаштувань для Wireless ADB")
                } catch (e: Exception) {
                    Log.e("AdbAutoEnabler", "Не вдалося відкрити налаштування: ${e.message}")
                }
            }, 2500)
        }
    }
}
