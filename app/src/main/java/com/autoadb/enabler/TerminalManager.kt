package com.autoadb.enabler

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object TerminalManager {
    private const val TAG = "KORIN_Terminal"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(String) -> Unit>()
    private val logBuffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        if (logBuffer.isNotEmpty()) {
            listener(logBuffer.toString())
        }
    }

    @Synchronized
    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun log(message: String) {
        val time = timeFormat.format(Date())
        val formatted = "[$time] $message\n"
        logBuffer.append(formatted)
        Log.i(TAG, message)

        mainHandler.post {
            for (listener in listeners) {
                listener(formatted)
            }
        }
    }

    @Synchronized
    fun clearLogs() {
        logBuffer.setLength(0)
    }

    fun executeCommand(command: String) {
        if (command.isBlank()) return
        log("$ $command")

        executor.execute {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    log(line ?: "")
                }
                while (errorReader.readLine().also { line = it } != null) {
                    log("[ERR] ${line ?: ""}")
                }

                val exitCode = process.waitFor()
                log("[Exit code: $exitCode]")
            } catch (e: Exception) {
                log("[Exception: ${e.message}]")
            }
        }
    }
}
