package com.example.autotranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.savedstate.*
import com.example.autotranslator.data.ServiceLocator
import com.example.autotranslator.ui.overlay.OverlayBubbleManager
import com.example.autotranslator.ui.theme.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class OverlayTranslationService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        const val ACTION_START = "com.example.autotranslator.START"
        const val ACTION_STOP = "com.example.autotranslator.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    private lateinit var windowManager: WindowManager
    private var triggerView: ComposeView? = null
    private lateinit var bubbleManager: OverlayBubbleManager

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val isTranslationEnabledState = mutableStateOf(false)
    private val sourceLanguageState = mutableStateOf("Auto-Detect")
    private val targetLanguageState = mutableStateOf("Thai (ไทย)")
    private val translationEngineState = mutableStateOf("Gemini")

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var ocrJob: Job? = null

    private val translationCache = mutableMapOf<String, String>()

    private val textReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isTranslationEnabledState.value) {
                bubbleManager.clearAll()
                return
            }

            val texts = intent?.getStringArrayListExtra(ScreenAccessibilityService.EXTRA_TEXT) ?: return
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(ScreenAccessibilityService.EXTRA_BOUNDS, Rect::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(ScreenAccessibilityService.EXTRA_BOUNDS)
            } ?: return

            processExtractedNodes(texts, bounds as List<Rect>)
        }
    }

    private fun processExtractedNodes(texts: List<String>, bounds: List<Rect>) {
        val activeIds = mutableSetOf<Int>()
        texts.forEachIndexed { index, text ->
            val rect = bounds[index]
            val id = (text + rect.top.toString()).hashCode()
            activeIds.add(id)
            translateAndShowBubble(id, text, rect)
        }
        bubbleManager.removeBubblesNotIn(activeIds)
    }

    private fun translateAndShowBubble(id: Int, text: String, rect: Rect) {
        val cached = translationCache[text]
        if (cached != null) {
            bubbleManager.updateBubble(id, cached, rect)
            return
        }

        scope.launch(Dispatchers.IO) {
            val repository = ServiceLocator.provideTranslationRepository(this@OverlayTranslationService)
            val result = repository.translateText(text)
            withContext(Dispatchers.Main) {
                result.onSuccess { translated ->
                    translationCache[text] = translated
                    bubbleManager.updateBubble(id, translated, rect)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubbleManager = OverlayBubbleManager(this, this, this, this)
        
        loadSettings()
        registerTextReceiver()
        createNotificationChannel()

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        showSideTrigger()
    }

    private fun loadSettings() {
        val settings = ServiceLocator.provideAppSettings(this)
        scope.launch {
            settings.getTargetLanguage().collect { targetLanguageState.value = it }
        }
        scope.launch {
            settings.getSourceLanguage().collect { sourceLanguageState.value = it }
        }
        scope.launch {
            settings.getTranslationEngine().collect { translationEngineState.value = it }
        }
    }

    private fun registerTextReceiver() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            textReceiver,
            IntentFilter(ScreenAccessibilityService.ACTION_TEXT_EXTRACTED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            startForeground(1, notification)
        }

        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode != 0 && data != null) {
                    initMediaProjection(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpManager.getMediaProjection(resultCode, data) ?: return
        mediaProjection = mp
        
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay("ScreenCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, 
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)

        startOcrLoop()
    }

    private fun startOcrLoop() {
        ocrJob?.cancel()
        ocrJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (isTranslationEnabledState.value && mediaProjection != null) {
                    captureAndProcessFrame()
                }
                delay(2000)
            }
        }
    }

    private fun captureAndProcessFrame() {
        val image = try { imageReader?.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { visionText ->
                    val activeIds = mutableSetOf<Int>()
                    visionText.textBlocks.forEach { block ->
                        val rect = block.boundingBox ?: return@forEach
                        val id = (block.text + rect.top.toString()).hashCode()
                        activeIds.add(id)
                        translateAndShowBubble(id, block.text, rect)
                    }
                    bubbleManager.removeBubblesNotIn(activeIds)
                }
            bitmap.recycle()
        } catch (e: Exception) { } finally { image.close() }
    }

    private fun showSideTrigger() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = 20
        }

        triggerView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayTranslationService)
            setViewTreeViewModelStoreOwner(this@OverlayTranslationService)
            setViewTreeSavedStateRegistryOwner(this@OverlayTranslationService)
            setContent {
                AutoTranslatorTheme {
                    val isEnabled by remember { isTranslationEnabledState }
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .background(if (isEnabled) Color(0xCC00C853) else Color(0xCC1E1E1E))
                            .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                            .clickable {
                                isTranslationEnabledState.value = !isEnabled
                                if (!isTranslationEnabledState.value) bubbleManager.clearAll()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Translate, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        windowManager.addView(triggerView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("overlay_channel", "Overlay Translation Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "overlay_channel")
            .setContentTitle("Auto Translator Active")
            .setSmallIcon(R.drawable.ic_menu_manage)
            .build()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        triggerView?.let { windowManager.removeView(it) }
        bubbleManager.clearAll()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(textReceiver)
        ocrJob?.cancel()
        recognizer.close()
        mediaProjection?.stop()
        virtualDisplay?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
