package com.autoadb.enabler

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.regex.Pattern

class AdbEnablerAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "AdbAutoEnabler"
        @Volatile
        var isTriggered = false
        var lastCapturedAddress: String? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ipPortPattern = Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):(\\d{4,5})")

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service підключено")
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = arrayOf("com.android.settings")
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isTriggered) return

        val rootNode = rootInActiveWindow ?: return

        // 1. Обробка діалогів підтвердження ("Завжди дозволяти в цій мережі")
        handleConfirmationDialog(rootNode)

        // 2. Варіант А: Перевірка та ввімкнення USB Debugging, якщо раптом вимкнений
        handleUsbDebuggingToggle(rootNode)

        // 3. Варіант Б: Пошук та активація Wireless Debugging
        handleWirelessDebuggingToggle(rootNode)

        // 4. Зчитування IP:Port
        findAndSaveIpPort(rootNode)
    }

    private fun handleConfirmationDialog(root: AccessibilityNodeInfo) {
        val alwaysAllowNodes = findNodesByTexts(root, listOf(
            "always allow", "завжди дозволяти", "всегда разрешать"
        ))
        for (node in alwaysAllowNodes) {
            if (node.isCheckable && !node.isChecked) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Встановлено прапорець 'Завжди дозволяти'")
            }
        }

        val allowButtonNodes = findNodesByTexts(root, listOf(
            "allow", "дозволити", "разрешить", "ok"
        ))
        for (node in allowButtonNodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Натиснуто кнопку підтвердження")
            }
        }
    }

    private fun handleUsbDebuggingToggle(root: AccessibilityNodeInfo) {
        val usbKeywords = listOf(
            "usb debugging",
            "налагодження по usb",
            "отладка по usb"
        )
        val targetNodes = findNodesByTexts(root, usbKeywords)
        for (textNode in targetNodes) {
            // Переконуємось, що це саме USB налагодження, а не Wireless
            val text = textNode.text?.toString()?.lowercase() ?: ""
            if (text.contains("wireless") || text.contains("бездротов") || text.contains("беспроводн")) {
                continue
            }
            val switchNode = findSwitchInHierarchy(textNode)
            if (switchNode != null && switchNode.isCheckable && !switchNode.isChecked) {
                switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "[Варіант А] Увімкнено перемикач USB Debugging")
            }
        }
    }

    private fun handleWirelessDebuggingToggle(root: AccessibilityNodeInfo) {
        val targetKeywords = listOf(
            "wireless debugging",
            "бездротове налагодження",
            "беспроводная отладка"
        )

        val targetNodes = findNodesByTexts(root, targetKeywords)

        for (textNode in targetNodes) {
            val switchNode = findSwitchInHierarchy(textNode)
            if (switchNode != null) {
                if (switchNode.isCheckable && !switchNode.isChecked) {
                    switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "[Варіант Б] Увімкнено тумблер Wireless Debugging")
                }
            } else if (textNode.isClickable) {
                textNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    private fun findAndSaveIpPort(root: AccessibilityNodeInfo) {
        val allTexts = mutableListOf<String>()
        extractAllTexts(root, allTexts)

        for (text in allTexts) {
            val matcher = ipPortPattern.matcher(text)
            if (matcher.find()) {
                val fullAddress = matcher.group(0)
                if (fullAddress != null && fullAddress != lastCapturedAddress) {
                    lastCapturedAddress = fullAddress
                    Log.i(TAG, "Знайдено Wireless ADB адресу: $fullAddress")
                    TerminalManager.log("⚡ Знайдено Wireless ADB: $fullAddress")

                    handler.post {
                        Toast.makeText(applicationContext, "ADB готовий: $fullAddress", Toast.LENGTH_LONG).show()
                    }

                    // Витягуємо локальний порт і запускаємо SSH тунель
                    val port = matcher.group(2)?.toIntOrNull() ?: 0
                    if (port > 0) {
                        SshTunnelService.startTunnel(applicationContext, port)
                    }

                    finishAutomation()
                    break
                }
            }
        }
    }

    private fun finishAutomation() {
        isTriggered = false
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 300)
        }, 500)
    }

    private fun findSwitchInHierarchy(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("Switch") == true || node.isCheckable) {
            return node
        }
        val parent = node.parent ?: return null
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            if (child.className?.toString()?.contains("Switch") == true || child.isCheckable) {
                return child
            }
        }
        val grandParent = parent.parent ?: return null
        for (i in 0 until grandParent.childCount) {
            val child = grandParent.getChild(i) ?: continue
            if (child.className?.toString()?.contains("Switch") == true || child.isCheckable) {
                return child
            }
        }
        return null
    }

    private fun findNodesByTexts(root: AccessibilityNodeInfo, keywords: List<String>): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        for (kw in keywords) {
            val found = root.findAccessibilityNodeInfosByText(kw)
            if (found != null) {
                result.addAll(found)
            }
        }
        return result
    }

    private fun extractAllTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) list.add(text)
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) list.add(desc)

        for (i in 0 until node.childCount) {
            extractAllTexts(node.getChild(i), list)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Службу перервано")
    }
}
