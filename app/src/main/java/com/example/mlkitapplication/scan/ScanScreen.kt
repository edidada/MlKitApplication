package com.example.mlkitapplication.scan

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

private val Panel = Color(0xEB0E1416)
private val Hairline = Color(0x33FFFFFF)
private val OnDark = Color(0xFFEFF5F1)
private val OnDarkDim = Color(0x9EE7EFEA)
private val Success = Color(0xFF3BE08C)
private val Warn = Color(0xFFFFB4A8)

/**
 * 扫码主界面：全屏预览 + 取景遮罩 + 自动缩放状态条 + 控制区 + 结果卡片。
 *
 * 对标主流大厂扫码交互：
 *  - 双指捏合手动缩放，此时自动策略让位约 2.5s
 *  - 状态条实时显示自动缩放所处状态（锁定码框 / 找码 / 手动 / 解码锁定 / 待机）
 *  - 连续扫码累积历史；单次模式解出即冻结，点"继续"恢复
 */
@Composable
fun ScanScreen(engine: BarcodeScanEngine, onBack: () -> Unit) {
    val state by engine.uiState.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val feedback = rememberScanFeedback()

    var lastNotifiedId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.lastResult?.id) {
        val r = state.lastResult
        if (r != null && r.id != lastNotifiedId) {
            lastNotifiedId = r.id
            feedback.prepare()
            feedback.success(haptic)
        }
    }

    var manualZoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(state.zoomRatio, state.zoomMode) {
        if (state.zoomMode != ZoomMode.MANUAL) manualZoom = state.zoomRatio
    }

    var showHistory by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                if (engine.scanFromImage(uri) == null) toast(context, "这张图里没找到码")
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        manualZoom = (manualZoom * zoom).coerceIn(1f, 12f)
                        engine.onManualZoom(manualZoom)
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).also { engine.bind(it) } },
            modifier = Modifier.fillMaxSize(),
        )

        ScanOverlay(
            boxes = state.boxes,
            scanning = state.cameraReady && state.lastResult == null,
            modifier = Modifier.fillMaxSize(),
        )

        // ---------------- 顶部栏 ----------------
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton("×", textSize = 22) { onBack() }
            Spacer(Modifier.width(12.dp))
            Text("扫一扫", color = OnDark, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (state.hasTorch && !state.frontCamera) {
                CircleButton("手电", active = state.torchOn) { engine.toggleTorch() }
                Spacer(Modifier.width(8.dp))
            }
            CircleButton("翻转") { engine.toggleLens() }
        }

        // ---------------- 底部区 ----------------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
        ) {
            AnimatedVisibility(
                visible = showHistory && state.history.isNotEmpty(),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                HistoryPanel(
                    history = state.history,
                    onPick = { engine.showResult(it) },
                    onClose = { showHistory = false },
                )
            }

            ResultCard(
                result = state.lastResult,
                onCopy = { r ->
                    clipboard.setText(AnnotatedString(r.rawText))
                    toast(context, "已复制到剪贴板")
                },
                onOpen = { r -> openUrl(context, r.rawText) },
                onClose = { engine.clearResult() },
            )

            Spacer(Modifier.height(10.dp))
            StatusStrip(state)
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TogglePill("自动缩放", state.autoZoomEnabled) { engine.setAutoZoom(!state.autoZoomEnabled) }
                TogglePill("连续扫码", state.continuous) { engine.setContinuous(!state.continuous) }
                TogglePill(
                    "自动补光",
                    state.autoTorch,
                    enabled = state.hasTorch && !state.frontCamera,
                ) { engine.setAutoTorch(!state.autoTorch) }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionChip("相册") {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (state.continuous) {
                    ActionChip("历史 ${state.history.size}") { showHistory = !showHistory }
                } else {
                    ActionChip("继续") { engine.resumeScanning() }
                }
                Spacer(Modifier.weight(1f))
                ZoomStepper(state.zoomRatio) { delta ->
                    manualZoom = (state.zoomRatio + delta).coerceIn(1f, 12f)
                    engine.onManualZoom(manualZoom)
                }
            }

            state.errorText?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Warn, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ------------------------------------------------------------------ 组件

@Composable
private fun StatusStrip(state: ScanUiState) {
    val glow by animateFloatAsState(
        targetValue = if (state.zoomMode == ZoomMode.TRACKING) 1f else 0.35f,
        label = "glow",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0x0DFFFFFF), Color(0x40000000))))
            .padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Success.copy(alpha = glow)))
        Spacer(Modifier.width(8.dp))
        Text(
            state.modeLabel,
            color = if (state.zoomMode == ZoomMode.TRACKING) Success else OnDarkDim,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        MonoChip(state.zoomLabel)
        Spacer(Modifier.width(6.dp))
        MonoChip("${state.analyzeFps}fps")
    }
}

@Composable
private fun MonoChip(text: String) {
    Text(
        text,
        color = OnDark,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Hairline)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun TogglePill(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val fg = when {
        !enabled -> OnDark.copy(alpha = 0.30f)
        on -> Success
        else -> OnDarkDim
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (on) Success.copy(alpha = 0.16f) else Color(0x22FFFFFF))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(7.dp))
        Text(label, color = fg, fontSize = 13.sp)
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = OnDark,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x2EFFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 9.dp),
    )
}

@Composable
private fun CircleButton(
    label: String,
    active: Boolean = false,
    textSize: Int = 13,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (active) Success.copy(alpha = 0.20f) else Color(0x59000000))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Success else OnDark, fontSize = textSize.sp)
    }
}

@Composable
private fun ZoomStepper(ratio: Float, onStep: (Float) -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color(0x2EFFFFFF))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepText("－") { onStep(-0.5f) }
        Text(
            "%.1fx".format(ratio),
            color = OnDark,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        StepText("＋") { onStep(+0.5f) }
    }
}

@Composable
private fun StepText(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = OnDark,
        fontSize = 17.sp,
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

@Composable
private fun ResultCard(
    result: ScanResult?,
    onCopy: (ScanResult) -> Unit,
    onOpen: (ScanResult) -> Unit,
    onClose: () -> Unit,
) {
    AnimatedVisibility(
        visible = result != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val r = result ?: return@AnimatedVisibility
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Success))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${r.formatName} · ${r.valueTypeName} · ${r.source}",
                        color = OnDarkDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "×",
                        color = OnDarkDim,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onClose() }
                            .padding(horizontal = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    r.displayValue,
                    color = OnDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                r.rawBytesHex?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        color = OnDark.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionChip("复制") { onCopy(r) }
                    if (r.isUrl) ActionChip("打开链接") { onOpen(r) }
                }
            }
        }
    }
}

@Composable
private fun HistoryPanel(
    history: List<ScanResult>,
    onPick: (ScanResult) -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("扫码历史", color = OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    "收起",
                    color = OnDarkDim,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onClose() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(186.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(history, key = { it.id }) { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x14FFFFFF))
                            .clickable { onPick(item) }
                            .padding(10.dp)
                    ) {
                        Text(
                            item.displayValue,
                            color = OnDark,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${item.formatName} · ${item.valueTypeName} · ${item.source}",
                            color = OnDarkDim,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 工具

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

private fun openUrl(context: Context, url: String) {
    if (!(url.startsWith("http://", true) || url.startsWith("https://", true))) {
        toast(context, "不是可打开的链接")
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        toast(context, "没有可打开该链接的应用")
    }
}
