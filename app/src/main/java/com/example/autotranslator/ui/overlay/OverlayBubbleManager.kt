package com.example.autotranslator.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
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
    private val activeBubbles = mutableMapOf<Int, BubbleInstance>()

    data class BubbleInstance(
        val view: ComposeView,
        val textState: MutableState<String>,
        val rect: Rect
    )

    fun updateBubble(id: Int, text: String, rect: Rect) {
        val bubble = activeBubbles[id]
        if (bubble != null) {
            bubble.textState.value = text
            // Update position if it moved significantly
            if (Math.abs(bubble.rect.top - rect.top) > 10 || Math.abs(bubble.rect.left - rect.left) > 10) {
                updateViewLayout(bubble.view, rect)
                activeBubbles[id] = bubble.copy(rect = Rect(rect))
            }
        } else {
            createBubble(id, text, rect)
        }
    }

    private fun createBubble(id: Int, text: String, rect: Rect) {
        val textState = mutableStateOf(text)
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            
            setContent {
                AutoTranslatorTheme {
                    val content by textState
                    Box(
                        modifier = Modifier
                            .background(Color(0x88000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = content,
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }

        val params = createLayoutParams(rect)
        try {
            windowManager.addView(composeView, params)
            activeBubbles[id] = BubbleInstance(composeView, textState, Rect(rect))
        } catch (e: Exception) {}
    }

    private fun updateViewLayout(view: ComposeView, rect: Rect) {
        val params = createLayoutParams(rect)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {}
    }

    private fun createLayoutParams(rect: Rect): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.bottom + 2
        }
    }

    fun removeBubblesNotIn(activeIds: Set<Int>) {
        val toRemove = activeBubbles.keys.filter { it !in activeIds }
        toRemove.forEach { id ->
            activeBubbles.remove(id)?.let {
                try {
                    windowManager.removeView(it.view)
                } catch (e: Exception) {}
            }
        }
    }

    fun clearAll() {
        activeBubbles.values.forEach {
            try {
                windowManager.removeView(it.view)
            } catch (e: Exception) {}
        }
        activeBubbles.clear()
    }
}
