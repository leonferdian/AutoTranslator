package com.example.autotranslator

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.autotranslator.data.ServiceLocator
import com.example.autotranslator.service.OverlayTranslationService
import com.example.autotranslator.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutoTranslatorTheme {
                DashboardScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { ServiceLocator.provideAppSettings(context) }

    val apiKey by settings.getApiKey().collectAsState(initial = "")
    val targetLanguage by settings.getTargetLanguage().collectAsState(initial = "Thai (ไทย)")
    val bubbleSize by settings.getBubbleSize().collectAsState(initial = 64)
    val opacity by settings.getOpacity().collectAsState(initial = 85)
    val fontScale by settings.getFontScale().collectAsState(initial = 16)

    var isServiceRunning by remember { mutableStateOf(false) }

    val mediaProjectionManager = remember { context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, OverlayTranslationService::class.java).apply {
                action = OverlayTranslationService.ACTION_START
                putExtra(OverlayTranslationService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayTranslationService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            isServiceRunning = true
        }
    }

    Scaffold(
        topBar = { DashboardTopBar() },
        bottomBar = { DashboardBottomNav() },
        containerColor = Background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            
            // API Setup Card
            item {
                ApiSetupCard(
                    apiKey = apiKey,
                    onApiKeyChange = { scope.launch { settings.setApiKey(it) } },
                    targetLanguage = targetLanguage
                )
            }

            // Engine Tiles
            item {
                EngineSection()
            }

            // Privileges
            item {
                PrivilegesCard(context)
            }

            // HUD Tuning
            item {
                HudTuningCard(
                    bubbleSize = bubbleSize,
                    onBubbleSizeChange = { scope.launch { settings.setBubbleSize(it) } },
                    opacity = opacity,
                    onOpacityChange = { scope.launch { settings.setOpacity(it) } },
                    fontScale = fontScale,
                    onFontScaleChange = { scope.launch { settings.setFontScale(it) } }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            if (!Settings.canDrawOverlays(context)) {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                context.startActivity(intent)
                            } else {
                                projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                            }
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = OnPrimary)
                        Spacer(Modifier.width(8.dp))
                        // Changed text to reflect it starts screen capture
                        Text("Start Live", style = Typography.headlineSmall, color = OnPrimary)
                    }

                    OutlinedButton(
                        onClick = {
                            val serviceIntent = Intent(context, OverlayTranslationService::class.java).apply {
                                action = OverlayTranslationService.ACTION_STOP
                            }
                            context.startService(serviceIntent)
                            isServiceRunning = false
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OutlineVariant)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, tint = OnSurface)
                        Spacer(Modifier.width(8.dp))
                        // Changed text to reflect it stops the service
                        Text("Stop Live", style = Typography.headlineSmall, color = OnSurface)
                    }
                }
            }
            
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun DashboardTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Gemini Live", style = Typography.headlineSmall, color = OnSurface)
                Text("Dashboard", style = Typography.labelSmall, color = Secondary)
            }
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SurfaceContainerLow)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Tertiary))
                    Spacer(Modifier.width(6.dp))
                    Text("Live Active", style = Typography.labelSmall, color = Tertiary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(Primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = OnPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun ApiSetupCard(apiKey: String, onApiKeyChange: (String) -> Unit, targetLanguage: String) {
    var isVisible by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clip(CircleShape).background(SurfaceContainerHigh).padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Gemini 1.5 Flash", style = Typography.labelMedium, color = Primary, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("v2-exp", style = Typography.labelSmall, color = OnSurfaceVariant)
            }
            
            Spacer(Modifier.height(16.dp))
            
            Text("GEMINI API CREDENTIALS", style = Typography.labelSmall, color = OnSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Enter API Key", style = Typography.labelMedium) },
                visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { isVisible = !isVisible }) {
                        Icon(if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceContainer,
                    unfocusedContainerColor = SurfaceContainer,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Primary
                ),
                shape = RoundedCornerShape(8.dp),
                textStyle = Typography.labelMedium
            )

            Spacer(Modifier.height(16.dp))
            
            Text("TRANSLATION ROUTING", style = Typography.labelSmall, color = OnSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceContainer)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("SOURCE STREAM", style = Typography.labelSmall, color = Outline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Secondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Auto-Detect", style = Typography.headlineSmall, color = OnSurface)
                    }
                }
                Icon(Icons.Default.SyncAlt, contentDescription = null, tint = OnSurfaceVariant, modifier = Modifier.size(16.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("TARGET OVERLAY", style = Typography.labelSmall, color = Outline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(targetLanguage, style = Typography.headlineSmall, color = Primary)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Primary)
                    }
                }
            }
        }
    }
}

@Composable
fun EngineSection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column {
                Text("Active Engines", style = Typography.headlineSmall, color = OnSurface)
                Text("Simultaneous capture pipelines", style = Typography.bodySmall, color = OnSurfaceVariant)
            }
            Text("3 Engines Ready", style = Typography.labelSmall, color = Tertiary, modifier = Modifier.padding(bottom = 4.dp))
        }
        
        EngineTile(
            title = "Screen Chat Reader",
            subtitle = "Accessibility Service Hook",
            description = "Real-time text extraction & inline overlay translation for system chats.",
            icon = Icons.Default.ChatBubbleOutline,
            iconColor = Primary,
            status = "Hooked: 60 FPS"
        )

        EngineTile(
            title = "Game OCR Capture",
            subtitle = "MediaProjection Engine",
            description = "High-speed GPU frame buffer grab for RPG dialogues & live video.",
            icon = Icons.Default.ScreenshotMonitor,
            iconColor = Secondary,
            status = "MediaProjection Ready"
        )
        
        EngineTile(
            title = "Live Audio & Voice",
            subtitle = "Internal Audio + Mic Subtitles",
            description = "Dual-stream audio capture translating voice chat into synchronized subtitles.",
            icon = Icons.Default.Hearing,
            iconColor = Tertiary,
            status = "Stereo Loopback"
        )
    }
}

@Composable
fun EngineTile(title: String, subtitle: String, description: String, icon: ImageVector, iconColor: Color, status: String) {
    var checked by remember { mutableStateOf(true) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, style = Typography.headlineSmall, color = OnSurface)
                        Text(subtitle, style = Typography.labelSmall, color = iconColor)
                    }
                }
                Switch(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = OnPrimary, checkedTrackColor = Primary)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(description, style = Typography.bodySmall, color = OnSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Tertiary))
                    Spacer(Modifier.width(6.dp))
                    Text(status, style = Typography.labelSmall, color = Tertiary)
                }
                Text("Vulkan / OpenGL ES", style = Typography.labelSmall, color = Outline)
            }
        }
    }
}

@Composable
fun PrivilegesCard(context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("System Privileges", style = Typography.headlineSmall, color = OnSurface)
                    Text("Foreground services requirement", style = Typography.bodySmall, color = OnSurfaceVariant)
                }
                Box(
                    modifier = Modifier.clip(CircleShape).background(SurfaceContainerHigh).padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Tertiary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("3 of 4 Active", style = Typography.labelSmall, color = OnSurface, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = 0.75f,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = Primary,
                trackColor = SurfaceContainerHighest
            )
            Spacer(Modifier.height(16.dp))
            
            PrivilegeItem("Draw Over Other Apps", "SYSTEM_ALERT_WINDOW", Icons.Default.Layers, true)
            PrivilegeItem("Accessibility Service Access", "CHAT_DOM_INSPECTOR", Icons.Default.AccessibilityNew, true)
            PrivilegeItem("Screen Capture Engine", "MEDIA_PROJECTION", Icons.Default.ScreenShare, true)
            PrivilegeItem("Audio Capture Access", "INTERNAL_LOOPBACK", Icons.Default.Mic, false, onGrant = {
                // Handle mic permission
            })
        }
    }
}

@Composable
fun PrivilegeItem(title: String, subtitle: String, icon: ImageVector, active: Boolean, onGrant: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceContainer).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (active) Tertiary else Secondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = Typography.bodyMedium, color = OnSurface)
                Text(subtitle, style = Typography.labelSmall, color = Outline)
            }
        }
        if (active) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(CircleShape).background(SurfaceContainerHigh).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Tertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Active", style = Typography.labelSmall, color = Tertiary)
            }
        } else {
            Button(
                onClick = { onGrant?.invoke() },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SecondaryContainer)
            ) {
                Text("Grant", style = Typography.labelSmall, color = OnSecondaryContainer, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun HudTuningCard(bubbleSize: Int, onBubbleSizeChange: (Int) -> Unit, opacity: Int, onOpacityChange: (Int) -> Unit, fontScale: Int, onFontScaleChange: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Floating HUD Tuning", style = Typography.headlineSmall, color = OnSurface)
                    Text("Live overlay presentation", style = Typography.bodySmall, color = OnSurfaceVariant)
                }
                Text("Interactive Preview", style = Typography.labelSmall, color = Primary, modifier = Modifier.padding(bottom = 4.dp))
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Preview
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceContainerLowest),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .wrapContentSize()
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(SurfaceContainerHigh.copy(alpha = opacity / 100f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(bubbleSize.dp / 2).clip(CircleShape).background(Primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Translate, contentDescription = null, tint = OnPrimary, modifier = Modifier.size((bubbleSize / 4).dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Ready to translate...", style = Typography.bodyMedium.copy(fontSize = fontScale.sp), color = OnSurface)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            
            TuningSlider("FLOATING BUBBLE SIZE", bubbleSize, 48, 80, "dp", Primary, onBubbleSizeChange)
            TuningSlider("GLASSMORPHISM OPACITY", opacity, 20, 100, "%", Secondary, onOpacityChange)
            TuningSlider("SUBTITLE TYPOGRAPHY SCALE", fontScale, 12, 24, "sp", Tertiary, onFontScaleChange)
        }
    }
}

@Composable
fun TuningSlider(label: String, value: Int, min: Int, max: Int, unit: String, color: Color, onValueChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = Typography.labelSmall, color = OnSurfaceVariant)
            Text("$value $unit", style = Typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = SurfaceContainerHighest)
        )
    }
}

@Composable
fun DashboardBottomNav() {
    NavigationBar(
        containerColor = SurfaceContainerLowest,
        tonalElevation = 8.dp
    ) {
        NavigationBarItem(
            selected = true,
            onClick = {},
            icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
            label = { Text("Dashboard", style = Typography.labelSmall) },
            colors = NavigationBarItemDefaults.colors(selectedIconColor = Primary, selectedTextColor = Primary, unselectedIconColor = OnSurfaceVariant, unselectedTextColor = OnSurfaceVariant, indicatorColor = SurfaceContainerHigh)
        )
        NavigationBarItem(
            selected = false,
            onClick = {},
            icon = { Icon(Icons.Default.PictureInPictureAlt, contentDescription = null) },
            label = { Text("Overlay", style = Typography.labelSmall) },
            colors = NavigationBarItemDefaults.colors(unselectedIconColor = OnSurfaceVariant, unselectedTextColor = OnSurfaceVariant)
        )
        NavigationBarItem(
            selected = false,
            onClick = {},
            icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
            label = { Text("Voice", style = Typography.labelSmall) },
            colors = NavigationBarItemDefaults.colors(unselectedIconColor = OnSurfaceVariant, unselectedTextColor = OnSurfaceVariant)
        )
        NavigationBarItem(
            selected = false,
            onClick = {},
            icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
            label = { Text("Logs", style = Typography.labelSmall) },
            colors = NavigationBarItemDefaults.colors(unselectedIconColor = OnSurfaceVariant, unselectedTextColor = OnSurfaceVariant)
        )
    }
}
