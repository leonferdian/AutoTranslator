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
    private val targetLanguageState = mutableStateOf("Thai (ไทย)")
    private val isPinnedState = mutableStateOf(false)
    private val ocrActiveZoneState = mutableStateOf("Lower 60%")

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var ocrJob: Job? = null
    private var tts: TextToSpeech? = null

    private val textReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ScreenAccessibilityService.EXTRA_TEXT) ?: return
            if (isPanelVisibleState.value && text != originalTextState.value) {
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
                    val notification = createNotification()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(1, notification)
                    }
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
        mediaProjection = mpManager.getMediaProjection(resultCode, data)
        
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
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
                if (isPanelVisibleState.value) {
                    captureAndProcessFrame()
                }
                delay(2000) // Process every 2 seconds
            }
        }
    }

    private fun captureAndProcessFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            val fullBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height, Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)
            
            // Crop to lower 60% as per mockup "OCR Active Zone: Lower 60%"
            val cropHeight = (image.height * 0.6).toInt()
            val cropTop = image.height - cropHeight
            val croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, cropTop, image.width, cropHeight)
            
            val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    if (text.isNotBlank()) {
                        scope.launch(Dispatchers.Main) {
                            if (text != originalTextState.value) {
                                translateText(text)
                            }
                        }
                    }
                }
            
            fullBitmap.recycle()
            // croppedBitmap will be recycled by InputImage or we can do it after process?
            // Actually ML Kit doesn't recycle it immediately.
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
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
                    val targetLang by remember { targetLanguageState }
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
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(SurfaceContainerHigh)
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("EN", style = Typography.labelSmall, color = OnSurfaceVariant)
                                        }
                                        Icon(
                                            Icons.Default.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.padding(horizontal = 8.dp).size(14.dp),
                                            tint = OnSurfaceVariant
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Primary.copy(alpha = 0.2f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(targetLang, style = Typography.labelSmall, color = Primary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { isPinnedState.value = !isPinned }, modifier = Modifier.size(32.dp)) {
                                            Icon(
                                                imageVector = Icons.Default.PushPin,
                                                contentDescription = "Pin",
                                                tint = if (isPinned) Primary else OnSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(onClick = { }, modifier = Modifier.size(32.dp)) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInFull,
                                                contentDescription = "Expand",
                                                tint = OnSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { isPanelVisibleState.value = false },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close",
                                                tint = OnSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Source Text
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "“$originalText”",
                                        style = Typography.bodyLarge.copy(
                                            fontSize = fontScale.sp,
                                            fontStyle = FontStyle.Italic
                                        ),
                                        color = OnSurface.copy(alpha = 0.8f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Translated Text
                                Row(verticalAlignment = Alignment.Top) {
                                    Spacer(modifier = Modifier.width(30.dp))
                                    Text(
                                        text = "“$translatedText”",
                                        style = Typography.headlineSmall.copy(
                                            fontSize = (fontScale + 2).sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = OnSurface
                                    )
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                // Action Buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { 
                                            tts?.speak(translatedText, TextToSpeech.QUEUE_FLUSH, null, null)
                                        },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceContainerHigh),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = OnSurface, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Listen (TTS)", style = Typography.labelMedium, color = OnSurface)
                                    }
                                    Button(
                                        onClick = { 
                                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Translated Text", translatedText)
                                            clipboard.setPrimaryClip(clip)
                                        },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceContainerHigh),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = OnSurface, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Copy", style = Typography.labelMedium, color = OnSurface)
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Bottom Controls
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Tertiary))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "OCR Active Zone: $ocrActiveZone",
                                            style = Typography.labelSmall,
                                            color = OnSurfaceVariant
                                        )
                                    }
                                    
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("A-", style = Typography.labelMedium, color = OnSurfaceVariant)
                                        Spacer(Modifier.width(12.dp))
                                        Text("A+", style = Typography.labelMedium, color = Primary, fontWeight = FontWeight.Bold)
                                    }
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
