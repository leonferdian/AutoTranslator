package com.example.autotranslator.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class ScreenAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_TEXT_EXTRACTED = "com.example.autotranslator.TEXT_EXTRACTED"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_BOUNDS = "extra_bounds"
        
        const val ACTION_TAKE_SCREENSHOT = "com.example.autotranslator.TAKE_SCREENSHOT"
        const val ACTION_SCREENSHOT_RESULT = "com.example.autotranslator.SCREENSHOT_RESULT"
        const val EXTRA_BITMAP = "extra_bitmap"
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TAKE_SCREENSHOT) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(screenshotResult.hardwareBuffer, screenshotResult.colorSpace)
                            if (bitmap != null) {
                                // Copy hardware bitmap to software bitmap so ML Kit can process it
                                val swBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                bitmap.recycle()
                                
                                val resultIntent = Intent(ACTION_SCREENSHOT_RESULT).apply {
                                    putExtra(EXTRA_BITMAP, swBitmap)
                                }
                                LocalBroadcastManager.getInstance(this@ScreenAccessibilityService).sendBroadcast(resultIntent)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e("ScreenAccessibility", "Screenshot failed with code: $errorCode")
                        }
                    })
                } else {
                    Log.e("ScreenAccessibility", "Screenshot API requires Android 11 (R) or higher")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            commandReceiver, 
            IntentFilter(ACTION_TAKE_SCREENSHOT)
        )
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

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(commandReceiver)
        super.onDestroy()
    }

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
