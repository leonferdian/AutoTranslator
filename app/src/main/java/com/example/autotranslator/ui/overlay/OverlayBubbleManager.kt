package com.example.autotranslator.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.*
import com.example.autotranslator.ui.theme.AutoTranslatorTheme

class OverlayBubbleManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val viewModelStoreOwner: ViewModelStoreOwner,
    private val savedStateRegistryOwner: SavedStateRegistryOwner
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val activeBubbles = mutableStateMapOf<Int, BubbleData>()
    private var overlayView: ComposeView? = null

    data class BubbleData(val text: String, val rect: Rect)

    init {
        setupOverlayView()
    }

    private fun setupOverlayView() {
        overlayView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            
            setContent {
                AutoTranslatorTheme {
                    val density = LocalDensity.current
                    // A single transparent Box covering the entire screen
                    Box(modifier = Modifier.fillMaxSize()) {
                        activeBubbles.forEach { (_, bubble) ->
                            val xPos = with(density) { bubble.rect.left.toDp() }
                            val yPos = with(density) { bubble.rect.bottom.toDp() }

                            Box(
                                modifier = Modifier
                                    .offset(x = xPos, y = yPos + 2.dp)
                                    .background(Color(0xD9000000), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFFFBC02D), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = bubble.text,
                                    color = Color(0xFFFBC02D),
                                    fontSize = 14.sp,
                                    lineHeight = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_TOUCHABLE ensures users can click "through" the overlay to interact with the screen below
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e("BubbleManager", "Failed to add overlay view", e)
        }
    }

    fun updateBubble(id: Int, text: String, rect: Rect) {
        activeBubbles[id] = BubbleData(text, rect)
    }

    fun removeBubblesNotIn(activeIds: Set<Int>) {
        activeBubbles.keys.retainAll(activeIds)
    }

    fun clearAll() {
        activeBubbles.clear()
    }

    fun destroy() {
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e("BubbleManager", "Failed to remove overlay view", e)
        }
    }
}
