package com.example.autotranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.savedstate.*
import com.example.autotranslator.data.ServiceLocator
import com.example.autotranslator.ui.theme.*
import kotlinx.coroutines.*

class OverlayTranslationService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        const val ACTION_START = "com.example.autotranslator.START"
        const val ACTION_STOP = "com.example.autotranslator.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: ComposeView? = null
    private var panelView: ComposeView? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val translatedTextState = mutableStateOf("Ready to translate...")
    private val isPanelVisibleState = mutableStateOf(false)
    private val bubbleSizeState = mutableIntStateOf(64)
    private val opacityState = mutableIntStateOf(85)
    private val fontScaleState = mutableIntStateOf(16)

    private val textReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ScreenAccessibilityService.EXTRA_TEXT) ?: return
            if (isPanelVisibleState.value) {
                translateText(text)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        loadSettings()
        registerTextReceiver()

        createNotificationChannel()

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        showFloatingBubble()
        showTranslationPanel()
    }

    private fun loadSettings() {
        val settings = ServiceLocator.provideAppSettings(this)
        scope.launch {
            settings.getBubbleSize().collect { bubbleSizeState.intValue = it }
        }
        scope.launch {
            settings.getOpacity().collect { opacityState.intValue = it }
        }
        scope.launch {
            settings.getFontScale().collect { fontScaleState.intValue = it }
        }
    }

    private fun registerTextReceiver() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            textReceiver,
            IntentFilter(ScreenAccessibilityService.ACTION_TEXT_EXTRACTED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (resultCode != 0 && data != null) {
                    startForeground(1, createNotification())
                    // Here you would also initialize MediaProjection if needed
                } else {
                    // Fallback or error handling
                }
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun showFloatingBubble() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        bubbleView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayTranslationService)
            setViewTreeViewModelStoreOwner(this@OverlayTranslationService)
            setViewTreeSavedStateRegistryOwner(this@OverlayTranslationService)
            
            setContent {
                AutoTranslatorTheme {
                    val bubbleSize by remember { bubbleSizeState }
                    var offsetX by remember { mutableFloatStateOf(params.x.toFloat()) }
                    var offsetY by remember { mutableFloatStateOf(params.y.toFloat()) }

                    Box(
                        modifier = Modifier
                            .size(bubbleSize.dp)
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .background(SurfaceContainer)
                            .border(2.dp, Primary, CircleShape)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    offsetX += dragAmount.x
                                    offsetY += dragAmount.y
                                    params.x = offsetX.toInt()
                                    params.y = offsetY.toInt()
                                    windowManager.updateViewLayout(bubbleView, params)
                                }
                            }
                            .clickable {
                                isPanelVisibleState.value = !isPanelVisibleState.value
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Translate",
                            tint = Primary,
                            modifier = Modifier.size((bubbleSize * 0.5).dp)
                        )
                    }
                }
            }
        }

        windowManager.addView(bubbleView, params)
    }

    private var translationJob: Job? = null

    private fun translateText(text: String) {
        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            val repository = ServiceLocator.provideTranslationRepository(this@OverlayTranslationService)
            val result = repository.translateText(text)
            withContext(Dispatchers.Main) {
                translatedTextState.value = result.getOrElse { it.message ?: "Translation failed" }
            }
        }
    }

    private fun showTranslationPanel() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        panelView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayTranslationService)
            setViewTreeViewModelStoreOwner(this@OverlayTranslationService)
            setViewTreeSavedStateRegistryOwner(this@OverlayTranslationService)

            setContent {
                AutoTranslatorTheme {
                    val isVisible by remember { isPanelVisibleState }
                    val translatedText by remember { translatedTextState }
                    val opacity by remember { opacityState }
                    val fontScale by remember { fontScaleState }

                    if (isVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .shadow(16.dp, RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp))
                                .background(Surface.copy(alpha = opacity / 100f))
                                .border(1.dp, OutlineVariant, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "LIVE TRANSLATION",
                                        style = Typography.labelSmall,
                                        color = Secondary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = { isPanelVisibleState.value = false },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close",
                                            tint = OnSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = translatedText,
                                    style = Typography.bodyLarge.copy(fontSize = fontScale.sp),
                                    color = OnSurface,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        windowManager.addView(panelView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "overlay_channel",
                "Overlay Translation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "overlay_channel")
            .setContentTitle("Auto Translator Running")
            .setContentText("Overlay controls are active.")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        bubbleView?.let { windowManager.removeView(it) }
        panelView?.let { windowManager.removeView(it) }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(textReceiver)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
