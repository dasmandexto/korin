package com.autoadb.enabler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("korin_config", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        var updated = false

        if (intent.hasExtra("ssh_host")) {
            val host = intent.getStringExtra("ssh_host") ?: ""
            editor.putString("ssh_host", host)
            TerminalManager.log("[Config] Оновлено ssh_host: $host")
            updated = true
        }

        if (intent.hasExtra("ssh_port")) {
            val port = intent.getIntExtra("ssh_port", 22)
            editor.putInt("ssh_port", port)
            TerminalManager.log("[Config] Оновлено ssh_port: $port")
            updated = true
        }

        if (intent.hasExtra("ssh_user")) {
            val user = intent.getStringExtra("ssh_user") ?: "root"
            editor.putString("ssh_user", user)
            TerminalManager.log("[Config] Оновлено ssh_user: $user")
            updated = true
        }

        if (intent.hasExtra("ssh_pass")) {
            val pass = intent.getStringExtra("ssh_pass") ?: ""
            editor.putString("ssh_pass", pass)
            TerminalManager.log("[Config] Оновлено ssh_pass: [ВСТАНОВЛЕНО]")
            updated = true
        }

        if (intent.hasExtra("remote_port")) {
            val remotePort = intent.getIntExtra("remote_port", 5555)
            editor.putInt("remote_port", remotePort)
            TerminalManager.log("[Config] Оновлено remote_port: $remotePort")
            updated = true
        }

        if (intent.hasExtra("webhook_url")) {
            val webhook = intent.getStringExtra("webhook_url") ?: ""
            editor.putString("webhook_url", webhook)
            TerminalManager.log("[Config] Оновлено webhook_url: $webhook")
            updated = true
        }

        if (updated) {
            editor.apply()
            TerminalManager.log("[Config] ✅ Конфігурацію успішно збережено в пам'ять")

            // Якщо порт уже відомий — спробуємо підключитися зараз
            val lastAddr = AdbEnablerAccessibilityService.lastCapturedAddress
            if (!lastAddr.isNullOrBlank()) {
                val parts = lastAddr.split(":")
                if (parts.size == 2) {
                    val localPort = parts[1].toIntOrNull() ?: 0
                    if (localPort > 0) {
                        TerminalManager.log("[Config] Запуск тунелю з поточним портом $localPort...")
                        SshTunnelService.startTunnel(context, localPort)
                    }
                }
            }
        }
    }
}
