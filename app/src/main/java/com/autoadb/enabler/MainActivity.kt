package com.autoadb.enabler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var terminalOutput: TextView
    private lateinit var terminalScrollView: ScrollView
    private lateinit var commandInput: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Обробка переданих інтентом налаштувань
        handleIncomingIntent(intent)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1117"))
            setPadding(30, 40, 30, 30)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Заголовок
        val header = TextView(this).apply {
            text = "⚡ KORIN: ADB Persistence & SSH Tunnel"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        rootLayout.addView(header)

        // Блок поточного статусу
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8E9AA8"))
            setPadding(0, 10, 0, 15)
        }
        rootLayout.addView(statusText)
        updateStatusText()

        // Горизонтальний ряд кнопок швидких дій
        val buttonsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnWireless = createActionButton("▶ Wireless ADB", "#2563EB") {
            TerminalManager.log("[Action] Запуск активації Wireless ADB...")
            AdbEnablerAccessibilityService.isTriggered = true
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        val btnUsb = createActionButton("▶ USB ADB", "#059669") {
            TerminalManager.log("[Action] Примусова активація USB Debugging...")
            try {
                Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
                TerminalManager.log("[USB] adb_enabled успішно виставлено в 1")
            } catch (e: Exception) {
                TerminalManager.log("[USB] Помилка: ${e.message} (потрібен дозвіл WRITE_SECURE_SETTINGS)")
            }
            updateStatusText()
        }

        val btnSsh = createActionButton("▶ SSH Connect", "#D97706") {
            val addr = AdbEnablerAccessibilityService.lastCapturedAddress
            val port = addr?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 5555
            TerminalManager.log("[Action] Спроба підключення SSH тунелю на локальний порт $port...")
            SshTunnelService.startTunnel(this, port)
        }

        val btnClear = createActionButton("🗑 Очистити", "#374151") {
            TerminalManager.clearLogs()
            terminalOutput.text = ""
        }

        buttonsRow.addView(btnWireless)
        buttonsRow.addView(btnUsb)
        buttonsRow.addView(btnSsh)
        buttonsRow.addView(btnClear)
        buttonsScroll.addView(buttonsRow)
        rootLayout.addView(buttonsScroll)

        // Вбудований Термінал
        val terminalFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050505"))
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            ).apply {
                setMargins(0, 20, 0, 20)
            }
        }

        terminalScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        terminalOutput = TextView(this).apply {
            setTextColor(Color.parseColor("#00FF66"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        terminalScrollView.addView(terminalOutput)
        terminalFrame.addView(terminalScrollView)
        rootLayout.addView(terminalFrame)

        // Блок вводу команди
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        commandInput = EditText(this).apply {
            hint = "Введіть команду (наприклад, getprop, ip a, ps)..."
            setHintTextColor(Color.parseColor("#4B5563"))
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#1F2937"))
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
            )
        }

        val btnRun = Button(this).apply {
            text = "Run"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981"))
            setOnClickListener {
                val cmd = commandInput.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    TerminalManager.executeCommand(cmd)
                    commandInput.setText("")
                }
            }
        }

        inputRow.addView(commandInput)
        inputRow.addView(btnRun)
        rootLayout.addView(inputRow)

        setContentView(rootLayout)

        // Підписка на потоковий вивід логів
        TerminalManager.addListener { text ->
            terminalOutput.append(text)
            terminalScrollView.post {
                terminalScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }

        TerminalManager.log("⚡ KORIN ініціалізовано. Термінал готовий до роботи.")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
        updateStatusText()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val prefs = getSharedPreferences("korin_config", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var updated = false

        val host = intent.getStringExtra("ssh_host")
        if (!host.isNullOrBlank()) {
            editor.putString("ssh_host", host)
            TerminalManager.log("[Intent] Оновлено ssh_host -> $host")
            updated = true
        }

        if (intent.hasExtra("ssh_port")) {
            val port = intent.getIntExtra("ssh_port", 22)
            editor.putInt("ssh_port", port)
            TerminalManager.log("[Intent] Оновлено ssh_port -> $port")
            updated = true
        }

        val user = intent.getStringExtra("ssh_user")
        if (!user.isNullOrBlank()) {
            editor.putString("ssh_user", user)
            TerminalManager.log("[Intent] Оновлено ssh_user -> $user")
            updated = true
        }

        val pass = intent.getStringExtra("ssh_pass")
        if (!pass.isNullOrBlank()) {
            editor.putString("ssh_pass", pass)
            TerminalManager.log("[Intent] Оновлено ssh_pass -> [ЗБЕРЕЖЕНО]")
            updated = true
        }

        if (intent.hasExtra("remote_port")) {
            val rPort = intent.getIntExtra("remote_port", 5555)
            editor.putInt("remote_port", rPort)
            TerminalManager.log("[Intent] Оновлено remote_port -> $rPort")
            updated = true
        }

        val webhook = intent.getStringExtra("webhook_url")
        if (!webhook.isNullOrBlank()) {
            editor.putString("webhook_url", webhook)
            TerminalManager.log("[Intent] Оновлено webhook_url -> $webhook")
            updated = true
        }

        if (updated) {
            editor.apply()
            TerminalManager.log("[Intent] ✅ Нові налаштування збережено.")
        }
    }

    private fun updateStatusText() {
        val prefs = getSharedPreferences("korin_config", Context.MODE_PRIVATE)
        val host = prefs.getString("ssh_host", "не вказано")
        val rPort = prefs.getInt("remote_port", 5555)
        val lastAddr = AdbEnablerAccessibilityService.lastCapturedAddress ?: "не активовано"

        val isUsbOn = try {
            Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }

        statusText.text = "USB ADB: ${if (isUsbOn) "🟢 ON" else "🔴 OFF"} | Wireless ADB: $lastAddr\nSSH Домен: $host (порт $rPort)"
    }

    private fun createActionButton(label: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(colorHex))
            setPadding(25, 10, 25, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 15, 0)
            }
            setOnClickListener { onClick() }
        }
    }
}
