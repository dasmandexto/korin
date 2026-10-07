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
        var isRunning = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ipPortPattern = Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):(\\d{4,5})")
    private var scrollAttempts = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.i(TAG, "Accessibility Service підключено")
        TerminalManager.log("🟢 Служба доступності KORIN успішно активна в системі")
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = arrayOf("com.android.settings")
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isTriggered) return

        val rootNode = rootInActiveWindow ?: return

        // 1. Обробка діалогів підтвердження ("Завжди дозволяти в цій мережі")
        handleConfirmationDialog(rootNode)

        // 2. Варіант А: Перевірка та ввімкнення USB Debugging
        handleUsbDebuggingToggle(rootNode)

        // 3. Варіант Б: Пошук та активація Wireless Debugging (з авто-скролом)
        handleWirelessDebugging(rootNode)

        // 4. Зчитування IP:Port
        findAndSaveIpPort(rootNode)
    }

    private fun handleConfirmationDialog(root: AccessibilityNodeInfo) {
        val alwaysAllowNodes = findNodesByKeywords(root, listOf(
            "always allow", "завжди дозволяти", "всегда разрешать"
        ))
        for (node in alwaysAllowNodes) {
            if (node.isCheckable && !node.isChecked) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                TerminalManager.log("[Accessibility] Встановлено прапорець 'Завжди дозволяти в цій мережі'")
            }
        }

        val allowButtonNodes = findNodesByKeywords(root, listOf(
            "allow", "дозволити", "разрешить", "ok"
        ))
        for (node in allowButtonNodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                TerminalManager.log("[Accessibility] Натиснуто кнопку підтвердження (Allow)")
            }
        }
    }

    private fun handleUsbDebuggingToggle(root: AccessibilityNodeInfo) {
        val usbKeywords = listOf(
            "usb debugging",
            "налагодження по usb",
            "отладка по usb"
        )
        val targetNodes = findNodesByKeywords(root, usbKeywords)
        for (textNode in targetNodes) {
            val text = (textNode.text?.toString() ?: "").lowercase()
            if (text.contains("wireless") || text.contains("бездротов") || text.contains("беспроводн") || text.contains("wi-fi")) {
                continue
            }
            val switchNode = findSwitchInHierarchy(textNode)
            if (switchNode != null && switchNode.isCheckable && !switchNode.isChecked) {
                switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                TerminalManager.log("[Accessibility] [USB] Увімкнено тумблер USB Debugging")
            }
        }
    }

    private fun handleWirelessDebugging(root: AccessibilityNodeInfo) {
        val targetKeywords = listOf(
            "wireless debugging",
            "бездротове налагодження",
            "беспроводная отладка",
            "налагодження по wi-fi",
            "отладка по wi-fi",
            "wireless debug"
        )

        val targetNodes = findNodesByKeywords(root, targetKeywords)

        if (targetNodes.isNotEmpty()) {
            val textNode = targetNodes.first()
            val switchNode = findSwitchInHierarchy(textNode)

            if (switchNode != null) {
                if (switchNode.isCheckable && !switchNode.isChecked) {
                    switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    TerminalManager.log("[Accessibility] ⚡ Увімкнено тумблер Wireless Debugging")
                } else if (switchNode.isChecked) {
                    // Вже увімкнено, спробуємо клікнути рядок щоб відкрити деталі з портом
                    textNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } else {
                // Якщо поруч нема тумблера, клікаємо на сам рядок (відкриття підменю)
                textNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                TerminalManager.log("[Accessibility] Відкрито сторінку Wireless Debugging")
            }
        } else {
            // Якщо на поточному екрані не знайдено — гортаємо список вниз (до 6 разів)
            if (scrollAttempts < 6) {
                val scrollable = findFirstScrollableNode(root)
                if (scrollable != null) {
                    scrollAttempts++
                    TerminalManager.log("[Accessibility] Гортаємо список вниз ($scrollAttempts/6)...")
                    scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
            }
        }

        // Перевіряємо головний перемикач на підсторінці Wireless Debugging (якщо зайшли в середину)
        val mainSwitches = findMainSubpageSwitch(root)
        for (sw in mainSwitches) {
            if (sw.isCheckable && !sw.isChecked) {
                sw.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                TerminalManager.log("[Accessibility] Увімкнено головний перемикач на сторінці Wireless Debugging")
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
                    scrollAttempts = 0
                    TerminalManager.log("🎉 Знайдено Wireless ADB адресу: $fullAddress")

                    handler.post {
                        Toast.makeText(applicationContext, "ADB готовий: $fullAddress", Toast.LENGTH_LONG).show()
                    }

                    // Витягуємо порт і підключаємо SSH тунель
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
        scrollAttempts = 0
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 300)
        }, 500)
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = findFirstScrollableNode(node.getChild(i))
            if (child != null) return child
        }
        return null
    }

    private fun findMainSubpageSwitch(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val keywords = listOf("use wireless debugging", "використовувати бездротове", "использовать беспроводную")
        val nodes = findNodesByKeywords(root, keywords)
        for (n in nodes) {
            val sw = findSwitchInHierarchy(n)
            if (sw != null) result.add(sw)
        }
        return result
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

    private fun findNodesByKeywords(root: AccessibilityNodeInfo?, keywords: List<String>): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return result

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
            for (kw in keywords) {
                if (text.contains(kw)) {
                    result.add(node)
                    break
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
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
