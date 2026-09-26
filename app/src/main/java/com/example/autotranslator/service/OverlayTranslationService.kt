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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
    private val pendingTranslations = mutableSetOf<String>()

    data class TrackedBlock(
        val id: Int,
        var originalText: String,
        var currentRect: Rect,
        var lastSeenTime: Long
    )
    private val trackedBlocks = mutableListOf<TrackedBlock>()
    private var nextBlockId = 1

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

        if (pendingTranslations.contains(text)) {
            return
        }

        pendingTranslations.add(text)
        bubbleManager.updateBubble(id, "...", rect)

        scope.launch(Dispatchers.IO) {
            try {
                val repository = ServiceLocator.provideTranslationRepository(this@OverlayTranslationService)
                val result = repository.translateText(text)
                withContext(Dispatchers.Main) {
                    pendingTranslations.remove(text)
                    result.onSuccess { translated ->
                        val cleanTranslation = translated.replace("[Google]", "").trim()
                        translationCache[text] = cleanTranslation
                        val latestRect = trackedBlocks.find { it.id == id }?.currentRect ?: rect
                        bubbleManager.updateBubble(id, cleanTranslation, latestRect)
                    }.onFailure { e ->
                        Log.e("OverlayService", "Translation failed", e)
                        translationCache[text] = "[Error]"
                        val latestRect = trackedBlocks.find { it.id == id }?.currentRect ?: rect
                        bubbleManager.updateBubble(id, "[Error]", latestRect)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { pendingTranslations.remove(text) }
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

        // Register callback required for Android 14+
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                mediaProjection = null
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
            }
        }, Handler(Looper.getMainLooper()))
        
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
                delay(1000) // Reduced delay for more dynamic/faster subtitle updates
            }
        }
    }

    private fun captureAndProcessFrame() {
        Log.d("OverlayService", "captureAndProcessFrame: Starting capture")
        val image = try { imageReader?.acquireLatestImage() } catch (e: Exception) { 
            Log.e("OverlayService", "captureAndProcessFrame: Failed to acquire image", e)
            null 
        } ?: return
        
        try {
            Log.d("OverlayService", "captureAndProcessFrame: Image acquired, size: ${image.width}x${image.height}")
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            
            // Fix pixel alignment issue: Buffer has row padding, copyPixelsFromBuffer requires exact match
            val bitmapWidth = rowStride / pixelStride
            val paddedBitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            paddedBitmap.copyPixelsFromBuffer(buffer)
            
            // Crop out the padding to get the actual screen image
            val bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
            paddedBitmap.recycle()
            
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnCompleteListener {
                    bitmap.recycle()
                }
                .addOnSuccessListener { visionText ->
                    val now = System.currentTimeMillis()
                    val newActiveIds = mutableSetOf<Int>()
                    
                    val bubbleAvoidanceRects = bubbleManager.getActiveBubbleRects().map {
                        Rect(it.left, it.top, it.right, it.bottom)
                    }

                    visionText.textBlocks.forEach { block ->
                        val rect = block.boundingBox ?: return@forEach
                        val text = block.text

                        val isInsideBubble = bubbleAvoidanceRects.any { it.intersect(rect) || it.contains(rect) }
                        val isGarbageText = text.length < 2 || !text.any { it.isLetterOrDigit() }
                        
                        if (isInsideBubble || text.contains("[Google]") || text == "..." || text == "[Error]" || isGarbageText) {
                            return@forEach
                        }

                        val matchedBlock = trackedBlocks.find { 
                            it.originalText == text || (Rect.intersects(it.currentRect, rect) && 
                            Math.abs(it.currentRect.centerX() - rect.centerX()) < 50 && 
                            Math.abs(it.currentRect.centerY() - rect.centerY()) < 50)
                        }

                        if (matchedBlock != null) {
                            // Only update position if it moved significantly (Deadzone of 15 pixels to prevent jitter)
                            if (Math.abs(matchedBlock.currentRect.left - rect.left) > 15 || 
                                Math.abs(matchedBlock.currentRect.top - rect.top) > 15) {
                                matchedBlock.currentRect = rect
                                bubbleManager.updateBubbleRect(matchedBlock.id, rect)
                            }
                            matchedBlock.originalText = text
                            matchedBlock.lastSeenTime = now
                            newActiveIds.add(matchedBlock.id)
                        } else {
                            val newId = nextBlockId++
                            trackedBlocks.add(TrackedBlock(newId, text, rect, now))
                            newActiveIds.add(newId)
                            
                            translateAndShowBubble(newId, text, rect)
                        }
                    }
                    
                    trackedBlocks.removeAll { now - it.lastSeenTime > 1500 }
                    bubbleManager.removeBubblesNotIn(newActiveIds)
                }
                .addOnFailureListener { e ->
                    Log.e("OverlayService", "captureAndProcessFrame: OCR Failed", e)
                }
        } catch (e: Exception) { 
            Log.e("OverlayService", "captureAndProcessFrame: Error during processing", e)
        } finally { 
            image.close() 
        }
    }

    private fun showSideTrigger() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 150
            y = screenHeight / 2
        }

        triggerView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayTranslationService)
            setViewTreeViewModelStoreOwner(this@OverlayTranslationService)
            setViewTreeSavedStateRegistryOwner(this@OverlayTranslationService)
            setContent {
                AutoTranslatorTheme {
                    val isEnabled by remember { isTranslationEnabledState }
                    var alphaValue by remember { mutableStateOf(1f) }
                    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }

                    LaunchedEffect(lastInteractionTime) {
                        delay(3000)
                        alphaValue = 0.5f
                    }

                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .alpha(alphaValue)
                            .background(if (isEnabled) Color(0xCC00C853) else Color(0xCC1E1E1E))
                            .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        alphaValue = 1f
                                        lastInteractionTime = System.currentTimeMillis()
                                    },
                                    onDragEnd = {
                                        val buttonWidth = size.width
                                        val targetX = if (params.x + buttonWidth / 2 < screenWidth / 2) 0 else screenWidth - buttonWidth
                                        params.x = targetX
                                        try { windowManager.updateViewLayout(triggerView, params) } catch (e: Exception) {}
                                        lastInteractionTime = System.currentTimeMillis()
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        params.x += dragAmount.x.toInt()
                                        params.y += dragAmount.y.toInt()
                                        try { windowManager.updateViewLayout(triggerView, params) } catch (e: Exception) {}
                                        lastInteractionTime = System.currentTimeMillis()
                                        alphaValue = 1f
                                    }
                                )
                            }
                            .clickable {
                                isTranslationEnabledState.value = !isEnabled
                                lastInteractionTime = System.currentTimeMillis()
                                alphaValue = 1f
                                if (!isTranslationEnabledState.value) {
                                    bubbleManager.clearAll()
                                    pendingTranslations.clear()
                                    trackedBlocks.clear()
                                } else {
                                    scope.launch { captureAndProcessFrame() }
                                }
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
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        triggerView?.let { windowManager.removeView(it) }
        bubbleManager.destroy()
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