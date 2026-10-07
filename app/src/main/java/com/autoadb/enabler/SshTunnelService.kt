package com.autoadb.enabler

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SshTunnelService : Service() {

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private var activeSession: Session? = null

        fun startTunnel(context: Context, localPort: Int) {
            val intent = Intent(context, SshTunnelService::class.java).apply {
                putExtra("local_port", localPort)
            }
            context.startService(intent)
        }

        fun stopTunnel() {
            try {
                activeSession?.disconnect()
                activeSession = null
                TerminalManager.log("[SSH] Тунель зупинено")
            } catch (e: Exception) {
                TerminalManager.log("[SSH] Помилка зупинки: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("korin_config", Context.MODE_PRIVATE)
        val sshHost = prefs.getString("ssh_host", null)
        val sshPort = prefs.getInt("ssh_port", 22)
        val sshUser = prefs.getString("ssh_user", "root")
        val sshPass = prefs.getString("ssh_pass", "")
        val remotePort = prefs.getInt("remote_port", 5555)
        val webhookUrl = prefs.getString("webhook_url", null)

        val localPort = intent?.getIntExtra("local_port", 0) ?: 0

        if (sshHost.isNullOrBlank() && webhookUrl.isNullOrBlank()) {
            TerminalManager.log("[Tunnel] Не налаштовано ssh_host або webhook_url. Очікування конфігурації...")
            return START_NOT_STICKY
        }

        executor.execute {
            // 1. Якщо задано Webhook — відправляємо звіт на вказаний домен
            if (!webhookUrl.isNullOrBlank() && localPort > 0) {
                sendWebhookReport(webhookUrl, localPort)
            }

            // 2. Якщо задано SSH-домен — піднімаємо зворотний SSH тунель
            if (!sshHost.isNullOrBlank() && localPort > 0) {
                establishSshTunnel(sshHost, sshPort, sshUser ?: "root", sshPass ?: "", remotePort, localPort)
            }
        }

        return START_STICKY
    }

    private fun sendWebhookReport(webhookUrl: String, localPort: Int) {
        try {
            TerminalManager.log("[Webhook] Відправка даних на $webhookUrl ...")
            val url = URL(webhookUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val json = """{"status":"active","adb_port":$localPort,"timestamp":${System.currentTimeMillis()}}"""
            OutputStreamWriter(conn.outputStream).use { it.write(json) }

            val code = conn.responseCode
            TerminalManager.log("[Webhook] Відповідь сервера: HTTP $code")
            conn.disconnect()
        } catch (e: Exception) {
            TerminalManager.log("[Webhook] Помилка: ${e.message}")
        }
    }

    private fun establishSshTunnel(
        host: String,
        port: Int,
        user: String,
        pass: String,
        remotePort: Int,
        localPort: Int
    ) {
        try {
            if (activeSession?.isConnected == true) {
                TerminalManager.log("[SSH] Перепідключення існуючої сесії...")
                activeSession?.disconnect()
            }

            TerminalManager.log("[SSH] Підключення до $user@$host:$port ...")
            val jsch = JSch()
            val session = jsch.getSession(user, host, port)
            if (pass.isNotEmpty()) {
                session.setPassword(pass)
            }

            val config = java.util.Properties()
            config["StrictHostKeyChecking"] = "no"
            session.setConfig(config)
            session.connect(10000)

            TerminalManager.log("[SSH] Авторизовано успішно. Прокидання віддаленого порту: $remotePort -> 127.0.0.1:$localPort")
            // Remote port forwarding: віддалений сервер слухає remotePort і пересилає на наш localPort
            session.setPortForwardingR(remotePort, "127.0.0.1", localPort)

            activeSession = session
            TerminalManager.log("[SSH] ✅ УСПІХ! На сервері $host тепер доступно: adb connect 127.0.0.1:$remotePort")

        } catch (e: Exception) {
            TerminalManager.log("[SSH] ❌ Помилка з'єднання: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
