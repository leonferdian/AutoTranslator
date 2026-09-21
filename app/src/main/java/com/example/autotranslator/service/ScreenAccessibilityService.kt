package com.example.autotranslator.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class ScreenAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_TEXT_EXTRACTED = "com.example.autotranslator.TEXT_EXTRACTED"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_BOUNDS = "extra_bounds"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            
            val nodes = extractNodes()
            if (nodes.isNotEmpty()) {
                broadcastNodes(nodes)
            }
        }
    }

    private fun broadcastNodes(nodes: List<Pair<String, Rect>>) {
        val texts = ArrayList<String>()
        val bounds = ArrayList<Rect>()
        
        nodes.forEach {
            texts.add(it.first)
            bounds.add(it.second)
        }

        val intent = Intent(ACTION_TEXT_EXTRACTED).apply {
            putStringArrayListExtra(EXTRA_TEXT, texts)
            putParcelableArrayListExtra(EXTRA_BOUNDS, bounds)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onInterrupt() {}

    private fun extractNodes(): List<Pair<String, Rect>> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val nodeList = mutableListOf<Pair<String, Rect>>()
        traverseNode(rootNode, nodeList)
        return nodeList
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, nodeList: MutableList<Pair<String, Rect>>) {
        if (node == null) return

        if (!node.text.isNullOrBlank()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            nodeList.add(node.text.toString() to rect)
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), nodeList)
        }
    }
}
