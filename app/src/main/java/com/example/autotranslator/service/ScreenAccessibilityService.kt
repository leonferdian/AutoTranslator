package com.example.autotranslator.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class ScreenAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_TEXT_EXTRACTED = "com.example.autotranslator.TEXT_EXTRACTED"
        const val EXTRA_TEXT = "extra_text"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            val texts = extractScreenText()
            if (texts.isNotEmpty()) {
                val fullText = texts.joinToString("\n")
                broadcastText(fullText)
            }
        }
    }

    private fun broadcastText(text: String) {
        val intent = Intent(ACTION_TEXT_EXTRACTED).apply {
            putExtra(EXTRA_TEXT, text)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onInterrupt() {}

    private fun extractScreenText(): List<String> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val textList = mutableListOf<String>()
        traverseNode(rootNode, textList)
        return textList
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, textList: MutableList<String>) {
        if (node == null) return

        if (!node.text.isNullOrBlank()) {
            textList.add(node.text.toString())
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), textList)
        }
    }
}
