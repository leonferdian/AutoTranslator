package com.example.autotranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontStyle
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
    private var bubbleView: ComposeView? = null
    private var panelView: ComposeView? = null
    private var hudView: ComposeView? = null
    private var triggerView: ComposeView? = null
    private lateinit var bubbleManager: OverlayBubbleManager

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val originalTextState = mutableStateOf("")
    private val translatedTextState = mutableStateOf("Ready to translate...")
    private val isPanelVisibleState = mutableStateOf(false)
    private val bubbleSizeState = mutableIntStateOf(64)
    private val opacityState = mutableIntStateOf(85)
    private val fontScaleState = mutableIntStateOf(16)
    private val sourceLanguageState = mutableStateOf("Auto-Detect")
    private val targetLanguageState = mutableStateOf("Thai (ไทย)")
    private val translationEngineState = mutableStateOf("Gemini")
    private val isPinnedState = mutableStateOf(false)
    private val ocrActiveZoneState = mutableStateOf("Lower 60%")

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var ocrJob: Job? = null
    private var tts: TextToSpeech? = null

    private val translationCache = mutableMapOf<String, String>()

    private val textReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val texts = intent?.getStringArrayListExtra(ScreenAccessibilityService.EXTRA_TEXT) ?: return
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(ScreenAccessibilityService.EXTRA_BOUNDS, Rect::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(ScreenAccessibilityService.EXTRA_BOUNDS)
            } ?: return

            if (isPanelVisibleState.value) {
                processExtractedNodes(texts, bounds as List<Rect>)
            } else {
                bubbleManager.clearAll()
            }
        }
    }

    private fun processExtractedNodes(texts: List<String>, bounds: List<Rect>) {
        val activeIds = mutableSetOf<Int>()
        texts.forEachIndexed { index, text ->
            val rect = bounds[index]
            // We use text + top position to identify a unique line, allows scrolling
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

        showFloatingBubble()
        showTranslationPanel()
        showFloatingHud()
        showSideTrigger()
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Initialized
            }
        }
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
        scope.launch {
            settings.getTargetLanguage().collect { targetLanguageState.value = it }
        }
        scope.launch {
            settings.getSourceLanguage().collect { sourceLanguageState.value = it }
        }
        scope.launch {
            settings.getTranslationEngine().collect { 
                translationEngineState.value = it
                bubbleManager = OverlayBubbleManager(
                    this@OverlayTranslationService, 
                    this@OverlayTranslationService, 
                    this@OverlayTranslationService, 
                    this@OverlayTranslationService,
                    engineName = if (it == "Gemini") "English AI" else "Google AI"
                )
            }
        }
    }

    private fun registerTextReceiver() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            textReceiver,
            IntentFilter(ScreenAccessibilityService.ACTION_TEXT_EXTRACTED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy foreground service requirements immediately
        val notification = createNotification()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // On Android 14, we must have a valid MediaProjection to use this type.
                // If we don't have it yet, we start as a generic service and promote later.
                if (intent?.hasExtra(EXTRA_DATA) == true) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    // Fallback type or none if possible. Android 14 is very strict.
                    // We'll use 0 for now or just the projection type if we're sure.
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                }
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback for older APIs or missing types
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
                    try {
                        initMediaProjection(resultCode, data)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        translatedTextState.value = "Capture Error: ${e.message}"
                    }
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
        val mp = mpManager.getMediaProjection(resultCode, data) ?: throw IllegalStateException("Failed to create MediaProjection")
        mediaProjection = mp
        
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                ocrJob?.cancel()
                virtualDisplay?.release()
                virtualDisplay = null
                mediaProjection = null
            }
        }, null)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        startOcrLoop()
    }

    private fun startOcrLoop() {
        ocrJob?.cancel()
        ocrJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (isPanelVisibleState.value && mediaProjection != null) {
                    captureAndProcessFrame()
                }
                delay(2500) // Increased delay to 2.5s for stability
            }
        }
    }

    private fun captureAndProcessFrame() {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            val bitmapWidth = image.width + rowPadding / pixelStride
            if (bitmapWidth <= 0 || image.height <= 0) return

            val fullBitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(buffer)
            
            val inputImage = InputImage.fromBitmap(fullBitmap, 0)
            
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val activeIds = mutableSetOf<Int>()
                    visionText.textBlocks.forEach { block ->
                        val text = block.text
                        val rect = block.boundingBox ?: return@forEach
                        val id = (text + rect.top.toString()).hashCode()
                        activeIds.add(id)
                        translateAndShowBubble(id, text, rect)
                    }
                    bubbleManager.removeBubblesNotIn(activeIds)
                }
            
            fullBitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun showSideTrigger() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = 40
        }

        triggerView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayTranslationService)
            setViewTreeViewModelStoreOwner(this@OverlayTranslationService)
            setViewTreeSavedStateRegistryOwner(this@OverlayTranslationService)

            setContent {
                AutoTranslatorTheme {
                    val engine by remember { translationEngineState }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .shadow(12.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Color(0xCC1E1E1E))
                            .border(2.dp, if (engine == "Gemini") Color.Cyan else Color.Green, CircleShape)
                            .clickable {
                                isPanelVisibleState.value = !isPanelVisibleState.value
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (engine == "Gemini") "G" else "T",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        // Active dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color.Green)
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                        )
                    }
                }
            }
        }
        windowManager.addView(triggerView, params)
    }

    private fun showFloatingHud() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 60 // Closer to bottom like in image
        }

        hudView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayTranslationService)
            setViewTreeViewModelStoreOwner(this@OverlayTranslationService)
            setViewTreeSavedStateRegistryOwner(this@OverlayTranslationService)

            setContent {
                AutoTranslatorTheme {
                    val engine by remember { translationEngineState }
                    val sourceLang by remember { sourceLanguageState }
                    val targetLang by remember { targetLanguageState }
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xAA000000))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$engine AI: ${sourceLang.take(2).uppercase()} → ${targetLang.take(2).uppercase()} | Translating Screen",
                                style = Typography.labelMedium,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.width(16.dp))
                            
                            IconButton(onClick = {}, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = {}, modifier = Modifier.size(28.dp).background(Primary.copy(alpha = 0.2f), CircleShape)) {
                                Icon(Icons.Default.Pause, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        windowManager.addView(hudView, params)
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
        originalTextState.value = text
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
                    val originalText by remember { originalTextState }
                    val translatedText by remember { translatedTextState }
                    val opacity by remember { opacityState }
                    val fontScale by remember { fontScaleState }
                    val sourceLang by remember { sourceLanguageState }
                    val targetLang by remember { targetLanguageState }
                    val engine by remember { translationEngineState }
                    val isPinned by remember { isPinnedState }
                    val ocrActiveZone by remember { ocrActiveZoneState }

                    if (isVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .shadow(16.dp, RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                                .background(Surface.copy(alpha = opacity / 100f))
                                .border(1.dp, OutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                                .padding(20.dp)
                        ) {
                            Column {
                                // Header
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(SurfaceContainerHigh)
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = if (sourceLang == "Auto-Detect") "EN" else sourceLang.take(2).uppercase(),
                                                style = Typography.labelMedium,
                                                color = OnSurfaceVariant,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Icon(
                                            Icons.Default.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.padding(horizontal = 12.dp).size(16.dp),
                                            tint = OnSurfaceVariant
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Primary.copy(alpha = 0.25f))
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = targetLang.uppercase(),
                                                style = Typography.labelMedium,
                                                color = Primary,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                    
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { isPinnedState.value = !isPinned }, modifier = Modifier.size(36.dp)) {
                                            Icon(
                                                imageVector = Icons.Default.PushPin,
                                                contentDescription = "Pin",
                                                tint = if (isPinned) Primary else OnSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        IconButton(onClick = { }, modifier = Modifier.size(36.dp)) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInFull,
                                                contentDescription = "Expand",
                                                tint = OnSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { isPanelVisibleState.value = false },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close",
                                                tint = OnSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                // Source Text
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        Icons.Default.CropFree,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(22.dp).padding(top = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = "“$originalText”",
                                        style = Typography.bodyLarge.copy(
                                            fontSize = fontScale.sp,
                                            fontStyle = FontStyle.Italic,
                                            lineHeight = (fontScale * 1.4).sp
                                        ),
                                        color = OnSurface.copy(alpha = 0.7f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Translated Text
                                Row(verticalAlignment = Alignment.Top) {
                                    Spacer(modifier = Modifier.width(38.dp))
                                    Text(
                                        text = "“$translatedText”",
                                        style = Typography.headlineSmall.copy(
                                            fontSize = (fontScale + 4).sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            lineHeight = ((fontScale + 4) * 1.3).sp
                                        ),
                                        color = OnSurface
                                    )
                                }

                                Spacer(modifier = Modifier.height(28.dp))

                                // Action Buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Button(
                                        onClick = { 
                                            tts?.speak(translatedText, TextToSpeech.QUEUE_FLUSH, null, null)
                                        },
                                        modifier = Modifier.weight(1.2f).height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceContainerHigh),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp)
                                    ) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = OnSurface, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Listen (TTS)", style = Typography.labelLarge, color = OnSurface)
                                    }
                                    Button(
                                        onClick = { 
                                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Translated Text", translatedText)
                                            clipboard.setPrimaryClip(clip)
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceContainerHigh),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = OnSurface, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Copy", style = Typography.labelLarge, color = OnSurface)
                                    }
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                // Bottom Controls
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.WaterDrop, contentDescription = null, tint = OnSurfaceVariant, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Slider(
                                        value = opacity.toFloat(),
                                        onValueChange = { 
                                            // Handle change - maybe through a callback to service to update state
                                            opacityState.intValue = it.toInt()
                                        },
                                        valueRange = 20f..100f,
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(
                                            thumbColor = OnSurfaceVariant,
                                            activeTrackColor = OnSurfaceVariant,
                                            inactiveTrackColor = SurfaceContainerHighest
                                        )
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text("$opacity%", style = Typography.labelSmall, color = OnSurfaceVariant)
                                    Spacer(Modifier.width(24.dp))
                                    
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(SurfaceContainerHigh)
                                            .padding(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = { if (fontScaleState.intValue > 12) fontScaleState.intValue -= 2 },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("A-", style = Typography.labelMedium, color = OnSurfaceVariant)
                                        }
                                        Box(Modifier.width(1.dp).height(16.dp).background(OutlineVariant))
                                        TextButton(
                                            onClick = { if (fontScaleState.intValue < 24) fontScaleState.intValue += 2 },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("A+", style = Typography.labelMedium, color = Primary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Footer
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Tertiary))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "OCR Active Zone: $ocrActiveZone",
                                        style = Typography.labelSmall,
                                        color = OnSurfaceVariant
                                    )
                                }
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
        hudView?.let { windowManager.removeView(it) }
        triggerView?.let { windowManager.removeView(it) }
        bubbleManager.clearAll()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(textReceiver)
        ocrJob?.cancel()
        recognizer.close()
        tts?.stop()
        tts?.shutdown()
        mediaProjection?.stop()
        virtualDisplay?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
