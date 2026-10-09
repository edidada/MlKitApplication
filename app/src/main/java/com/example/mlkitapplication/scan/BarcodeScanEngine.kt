package com.example.mlkitapplication.scan

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 转正后的坐标系尺寸 + 是否前置镜像。 */
data class FrameGeometry(val rotatedW: Float, val rotatedH: Float, val mirrored: Boolean)

/** 纯浮点矩形，便于在无 Android 依赖的 JVM 单测里验证映射数学。 */
data class BoxF(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * 把"转正后"图像坐标系的矩形映射到预览视图像素（FILL_CENTER：等比铺满、居中裁溢出）。
 *
 * 长边缩放系数只取决于统一的 [scale]，与裁剪偏移无关 —— 所以即便镜像/偏移判断
 * 有偏差，自动缩放依赖的"占比"输入依然准确。
 */
fun fillCropBox(
    box: BoxF,
    geometry: FrameGeometry,
    viewW: Int,
    viewH: Int,
): BoxF? {
    if (geometry.rotatedW <= 0f || geometry.rotatedH <= 0f || viewW <= 0 || viewH <= 0) return null
    val scale = maxOf(viewW / geometry.rotatedW, viewH / geometry.rotatedH)
    val offX = (viewW - geometry.rotatedW * scale) / 2f
    val offY = (viewH - geometry.rotatedH * scale) / 2f
    var left = box.left * scale + offX
    var right = box.right * scale + offX
    val top = box.top * scale + offY
    val bottom = box.bottom * scale + offY
    if (geometry.mirrored) {
        // 前置摄像头预览做了水平镜像，ML Kit 的结果不做镜像
        val nl = viewW - right
        val nr = viewW - left
        left = nl
        right = nr
    }
    if (right <= left || bottom <= top) return null
    return BoxF(left, top, right, bottom)
}

private fun Rect.toBoxF() = BoxF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

private fun BoxF.toRect() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

fun fillCropMap(box: Rect, geometry: FrameGeometry, viewW: Int, viewH: Int): Rect? =
    fillCropBox(box.toBoxF(), geometry, viewW, viewH)?.toRect()

/**
 * 扫码引擎：CameraX 预览 + 逐帧 ML Kit 解码 + 自动缩放闭环。
 *
 * 自动缩放链路：
 *   ImageAnalysis(YUV) -> InputImage.fromMediaImage(+rotationDegrees)
 *   -> ML Kit BarcodeScanner -> boundingBox（转正后的图像坐标）
 *   -> [fillCropMap] 映射到预览像素 -> 码长边 / 视口短边 = fill
 *   -> [ZoomPolicy] 推演目标倍率 -> CameraControl.setZoomRatio()（限频下发）
 *   -> ZoomState 回填真实倍率闭环。
 */
class BarcodeScanEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    companion object {
        private const val TAG = "BarcodeScanEngine"

        /** 取景框边长 / 视口短边，遮罩与策略共用同一口径 */
        const val REGION_NORM = 0.72f

        /** 目标：主码长边 / 视口短边 */
        private const val TARGET_FILL = 0.54f

        private const val MAX_AUTO_ZOOM = 4f
        private const val ZOOM_APPLY_INTERVAL_MS = 70L
        private const val DEDUP_WINDOW_MS = 2000L
        private const val LUMA_ON = 62f
        private const val LUMA_OFF = 92f

        /** 分析流目标尺寸：够清晰解码小码，又不至于拖帧 */
        private val ANALYSIS_TARGET = Size(1280, 960)
    }

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState
    private val state: ScanUiState get() = _uiState.value

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile private var geometry: FrameGeometry? = null

    private val policy = ZoomPolicy(autoMaxRatio = MAX_AUTO_ZOOM, targetFill = TARGET_FILL)

    private var lastZoomApplyAt = 0L
    private var lastHitText: String? = null
    private var lastHitAt = 0L
    private var fpsWindowStart = 0L
    private var framesInWindow = 0
    private var currentFps = 0
    private var smoothRect: RectF? = null
    private var autoZoomWasActive = false
    private var torchForcedByUser = false
    private var lastLuma = 128f
    private var nextId = 1L
    private var loggedCoordinateAssumption = false

    @Volatile private var lensFacing = CameraSelector.LENS_FACING_BACK
    @Volatile private var paused = false

    // ------------------------------------------------------------ 生命周期

    fun bind(previewView: PreviewView) {
        this.previewView = previewView
        // COMPATIBLE = TextureView：Compose Canvas 遮罩才能稳定地画在预览之上
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cameraProvider = future.get()
                    bindUseCases()
                } catch (t: Throwable) {
                    Log.e(TAG, "camera provider unavailable", t)
                    _uiState.value = state.copy(
                        errorText = "相机不可用：${t.message ?: t.javaClass.simpleName}"
                    )
                }
            },
            mainExecutor
        )
    }

    fun release() {
        paused = true
        runCatching { imageAnalysis?.clearAnalyzer() }
        runCatching { cameraProvider?.unbindAll() }
        analysisExecutor.shutdownNow()
        runCatching { scanner.close() }
    }

    fun pauseScanning() {
        paused = true
        _uiState.value = state.copy(boxes = emptyList())
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val view = previewView ?: return

        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(ANALYSIS_TARGET, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .build()
            .also { it.surfaceProvider = view.surfaceProvider }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, frameAnalyzer) }

        imageAnalysis = analysis

        try {
            provider.unbindAll()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val bound = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            camera = bound
            observeZoom(bound)
            policy.reset(SystemClock.elapsedRealtime())
            smoothRect = null
            geometry = null
            loggedCoordinateAssumption = false
            val zs = bound.cameraInfo.zoomState.value
            policy.maxRatio = zs?.maxZoomRatio?.takeIf { it > 1f } ?: MAX_AUTO_ZOOM
            _uiState.value = state.copy(
                cameraReady = true,
                errorText = null,
                hasTorch = bound.cameraInfo.hasFlashUnit(),
                frontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT,
                torchOn = false,
                zoomRatio = zs?.zoomRatio ?: 1f,
                zoomMode = ZoomMode.IDLE,
                boxes = emptyList(),
            )
            Log.i(
                TAG,
                "bound lens=${if (lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front"} " +
                    "torch=${bound.cameraInfo.hasFlashUnit()} " +
                    "zoom=${zs?.minZoomRatio}..${zs?.maxZoomRatio} " +
                    "analysisTarget=$ANALYSIS_TARGET"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed", t)
            _uiState.value = state.copy(errorText = "打开相机失败：${t.message}")
        }
    }

    private fun observeZoom(cam: Camera) {
        cam.cameraInfo.zoomState.observe(lifecycleOwner) { zs ->
            if (zs == null) return@observe
            zs.maxZoomRatio.takeIf { it > 1f }?.let { policy.maxRatio = it }
            policy.echo(zs.zoomRatio)
            // 策略没在驱动时，界面以相机真实倍率为准
            if (!state.autoZoomEnabled || state.zoomMode == ZoomMode.IDLE || state.zoomMode == ZoomMode.MANUAL) {
                if (kotlin.math.abs(zs.zoomRatio - state.zoomRatio) > 0.01f) {
                    _uiState.value = state.copy(zoomRatio = zs.zoomRatio)
                }
            }
        }
    }

    // ------------------------------------------------------------ 逐帧分析

    private val frameAnalyzer = ImageAnalysis.Analyzer { proxy ->
        var handedOff = false
        try {
            if (paused) {
                proxy.close()
                return@Analyzer
            }
            updateGeometry(proxy)
            if (state.autoTorch && proxy.format == ImageFormat.YUV_420_888) estimateLuma(proxy)

            val media = proxy.image
            if (media == null) {
                proxy.close()
                return@Analyzer
            }
            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { codes -> onBarcodes(codes) }
                .addOnFailureListener { e -> Log.w(TAG, "decode failed: ${e.message}") }
                .addOnCompleteListener { proxy.close() }
            handedOff = true
        } catch (t: Throwable) {
            Log.w(TAG, "frame dropped: ${t.message}")
        } finally {
            if (!handedOff) runCatching { proxy.close() }
        }
    }

    /**
     * ML Kit 用 rotationDegrees 转正后再返回结果，因此包围盒处于"转正后"的坐标系：
     * 90/270 度时宽高互换。
     */
    private fun updateGeometry(proxy: ImageProxy) {
        val rot = proxy.imageInfo.rotationDegrees
        val swap = rot == 90 || rot == 270
        geometry = FrameGeometry(
            rotatedW = (if (swap) proxy.height else proxy.width).toFloat(),
            rotatedH = (if (swap) proxy.width else proxy.height).toFloat(),
            mirrored = lensFacing == CameraSelector.LENS_FACING_FRONT,
        )
    }

    private fun onBarcodes(barcodes: List<Barcode>?) {
        if (paused) return
        val now = SystemClock.elapsedRealtime()
        tickFps(now)

        val view = previewView
        val shortEdge = if (view == null) 0f else minOf(view.width, view.height).toFloat()
        val tracked = if (barcodes.isNullOrEmpty() || shortEdge <= 0f) {
            smoothRect = null
            emptyList()
        } else {
            trackPrimary(barcodes, view!!)
        }

        if (state.autoZoomEnabled) {
            autoZoomWasActive = true
            val fill = tracked.firstOrNull()?.let {
                maxOf(it.rect.width(), it.rect.height()) / shortEdge.coerceAtLeast(1f)
            }
            applyDecision(policy.update(now, fill?.takeIf { it > 0.001f }), now)
        } else if (autoZoomWasActive) {
            autoZoomWasActive = false
            restoreZoom(now)
        }

        val hits = if (barcodes.isNullOrEmpty()) emptyList() else {
            registerHits(barcodes.mapNotNull { toResult(it, "相机") }, now)
        }
        if (hits.isNotEmpty()) {
            policy.onSuccess(now)
            _uiState.value = state.copy(
                lastResult = hits.first(),
                history = mergeHistory(state.history, hits),
            )
            if (!state.continuous) {
                imageAnalysis?.clearAnalyzer()
                paused = true
            }
        }

        _uiState.value = state.copy(boxes = tracked, analyzeFps = currentFps)
    }

    private fun trackPrimary(barcodes: List<Barcode>, view: PreviewView): List<TrackedBox> {
        val g = geometry ?: return emptyList()
        val mapped = barcodes.mapNotNull { b ->
            val src = b.boundingBox ?: rectOfCorners(b.cornerPoints)
            src?.takeIf { it.width() > 0 && it.height() > 0 }?.let { r ->
                checkCoordinateSpace(r, g)
                fillCropMap(r, g, view.width, view.height)
            }
        }
        val biggest = mapped.maxByOrNull { it.width().toLong() * it.height().toLong() }
            ?: return emptyList()

        val prev = smoothRect
        val next = if (prev == null || prev.width() <= 1f || prev.height() <= 1f) {
            RectF(biggest.left.toFloat(), biggest.top.toFloat(), biggest.right.toFloat(), biggest.bottom.toFloat())
        } else {
            RectF(
                prev.left + (biggest.left - prev.left) * 0.55f,
                prev.top + (biggest.top - prev.top) * 0.55f,
                prev.right + (biggest.right - prev.right) * 0.55f,
                prev.bottom + (biggest.bottom - prev.bottom) * 0.55f,
            )
        }
        smoothRect = next
        if (next.width() <= 0f || next.height() <= 0f) return emptyList()
        return listOf(TrackedBox(next, formatName(pickPrimary(barcodes).format), true))
    }

    /** 自检：如果包围盒越界，说明 ML Kit 的返回坐标系假设不成立，打日志并交换宽高重试。 */
    private fun checkCoordinateSpace(box: Rect, g: FrameGeometry) {
        val overflowW = box.right > g.rotatedW + 2
        val overflowH = box.bottom > g.rotatedH + 2
        if ((overflowW || overflowH) && !loggedCoordinateAssumption) {
            loggedCoordinateAssumption = true
            Log.w(
                TAG,
                "bbox $box exceeds assumed rotated space ${g.rotatedW}x${g.rotatedH}; " +
                    "ML Kit may return un-rotated coordinates on this device"
            )
        }
    }

    private fun rectOfCorners(points: Array<Point>?): Rect? {
        if (points.isNullOrEmpty()) return null
        val r = Rect(points[0].x, points[0].y, points[0].x, points[0].y)
        points.forEach { r.union(it.x, it.y) }
        return r
    }

    private fun pickPrimary(barcodes: List<Barcode>): Barcode =
        barcodes.maxByOrNull { b ->
            val r = b.boundingBox
            if (r == null) 0L else r.width().toLong() * r.height()
        } ?: barcodes.first()

    private fun applyDecision(d: ZoomDecision, now: Long) {
        policy.echo(d.targetRatio)
        _uiState.value = state.copy(zoomRatio = d.targetRatio, zoomMode = d.mode)
        if (!d.engaging) return
        val cam = camera ?: return
        if (now - lastZoomApplyAt < ZOOM_APPLY_INTERVAL_MS) return
        lastZoomApplyAt = now
        Log.d(TAG, "auto-zoom -> ${"%.2f".format(d.targetRatio)} (${d.mode})")
        runCatching { cam.cameraControl.setZoomRatio(d.targetRatio) }
            .onFailure { Log.w(TAG, "setZoomRatio failed: ${it.message}") }
    }

    private fun restoreZoom(now: Long) {
        camera?.let { cam -> runCatching { cam.cameraControl.setZoomRatio(1f) } }
        policy.echo(1f)
        policy.reset(now)
        smoothRect = null
        _uiState.value = state.copy(zoomRatio = 1f, zoomMode = ZoomMode.IDLE, boxes = emptyList())
    }

    private fun tickFps(now: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = now
        framesInWindow++
        val dt = now - fpsWindowStart
        if (dt >= 1000L) {
            currentFps = (framesInWindow * 1000L / dt).toInt()
            fpsWindowStart = now
            framesInWindow = 0
        }
    }

    // ------------------------------------------------------------ 亮度 / 手电

    private fun estimateLuma(proxy: ImageProxy) {
        val plane = proxy.planes.firstOrNull() ?: return
        val rowStride = plane.rowStride
        val w = proxy.width
        val h = proxy.height
        if (w <= 0 || h <= 0 || rowStride <= 0) return
        val buf = plane.buffer.duplicate()
        val limit = buf.limit()
        var sum = 0L
        var n = 0
        val stepX = maxOf(1, w / 40)
        val stepY = maxOf(1, h / 40)
        var y = 0
        while (y < h) {
            val rowStart = y * rowStride
            var x = 0
            while (x < w) {
                val idx = rowStart + x
                if (idx in 0 until limit) {
                    sum += buf.get(idx).toInt() and 0xFF
                    n++
                }
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return
        val luma = sum.toFloat() / n
        lastLuma = luma
        val isDark = luma < LUMA_ON
        if (isDark != state.dark) _uiState.value = state.copy(dark = isDark)
        if (!state.autoTorch || state.frontCamera || !state.hasTorch || torchForcedByUser) return
        if (!state.torchOn && isDark) applyTorch(true, user = false)
        else if (state.torchOn && luma > LUMA_OFF) applyTorch(false, user = false)
    }

    private fun applyTorch(on: Boolean, user: Boolean) {
        if (user) torchForcedByUser = true
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            if (state.hasTorch) _uiState.value = state.copy(hasTorch = false)
            return
        }
        runCatching { cam.cameraControl.enableTorch(on) }
            .onFailure { Log.w(TAG, "torch failed: ${it.message}") }
        _uiState.value = state.copy(torchOn = on)
    }

    // ------------------------------------------------------------ 结果

    private fun toResult(b: Barcode, source: String): ScanResult? {
        val text = b.rawValue ?: b.displayValue ?: return null
        return ScanResult(
            id = nextId++,
            displayValue = b.displayValue ?: text,
            rawText = text,
            formatName = formatName(b.format),
            valueTypeName = valueTypeName(b.valueType),
            rawBytesHex = b.rawBytes?.takeIf { it.isNotEmpty() }?.let { hex(it) },
            source = source,
            timestampMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun registerHits(hits: List<ScanResult>, now: Long): List<ScanResult> {
        val fresh = hits.filter { !(it.rawText == lastHitText && now - lastHitAt < DEDUP_WINDOW_MS) }
        if (fresh.isNotEmpty()) {
            lastHitText = fresh.first().rawText
            lastHitAt = now
        }
        return fresh
    }

    private fun mergeHistory(old: List<ScanResult>, added: List<ScanResult>): List<ScanResult> {
        val merged = (added + old).distinctBy { it.rawText }
        return if (merged.size > 30) merged.take(30) else merged
    }

    private fun formatName(fmt: Int): String = when (fmt) {
        Barcode.FORMAT_QR_CODE -> "QR 码"
        Barcode.FORMAT_AZTEC -> "Aztec"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        else -> "条码"
    }

    private fun valueTypeName(t: Int): String = when (t) {
        Barcode.TYPE_URL -> "网址"
        Barcode.TYPE_EMAIL -> "邮件"
        Barcode.TYPE_PHONE -> "电话"
        Barcode.TYPE_SMS -> "短信"
        Barcode.TYPE_WIFI -> "Wi-Fi"
        Barcode.TYPE_GEO -> "地理位置"
        Barcode.TYPE_PRODUCT -> "商品条码"
        Barcode.TYPE_TEXT -> "文本"
        else -> "未知类型"
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format(Locale.US, "%02X", it) }.let {
            if (it.length > 200) it.take(200) + " …" else it
        }

    // ------------------------------------------------------------ 对外控制

    fun setAutoZoom(enabled: Boolean) {
        _uiState.value = state.copy(autoZoomEnabled = enabled)
        if (enabled) {
            autoZoomWasActive = true
            policy.reset(SystemClock.elapsedRealtime())
        } else if (autoZoomWasActive) {
            autoZoomWasActive = false
            restoreZoom(SystemClock.elapsedRealtime())
        }
    }

    fun setContinuous(continuous: Boolean) {
        _uiState.value = state.copy(continuous = continuous)
        if (continuous) resumeScanning()
    }

    fun resumeScanning() {
        imageAnalysis?.setAnalyzer(analysisExecutor, frameAnalyzer)
        paused = false
        policy.reset(SystemClock.elapsedRealtime())
    }

    fun toggleTorch() = applyTorch(!state.torchOn, user = true)

    fun setAutoTorch(enabled: Boolean) {
        _uiState.value = state.copy(autoTorch = enabled, torchOn = if (!enabled) false else state.torchOn)
        torchForcedByUser = false
        if (!enabled && camera?.let { it.cameraInfo.hasFlashUnit() } == true) {
            runCatching { camera?.cameraControl?.enableTorch(false) }
        }
    }

    fun toggleLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindUseCases()
    }

    /** 用户捏合/步进缩放：自动策略让位一段时间。 */
    fun onManualZoom(ratio: Float) {
        val cam = camera ?: return
        val clamped = ratio.coerceIn(1f, policy.maxRatio)
        runCatching { cam.cameraControl.setZoomRatio(clamped) }
            .onFailure { Log.w(TAG, "manual zoom failed: ${it.message}") }
        policy.onManualZoom(SystemClock.elapsedRealtime(), clamped)
        _uiState.value = state.copy(zoomRatio = clamped, zoomMode = ZoomMode.MANUAL)
    }

    fun showResult(result: ScanResult) {
        _uiState.value = state.copy(lastResult = result)
    }

    fun clearResult() {
        _uiState.value = state.copy(lastResult = null)
    }

    /** 相册识码。 */
    suspend fun scanFromImage(uri: Uri): ScanResult? = withContext(Dispatchers.IO) {
        _uiState.value = state.copy(busy = true)
        try {
            val codes = scanner.processAwait(InputImage.fromFilePath(context, uri))
            publishResult(codes.mapNotNull { toResult(it, "相册") }.firstOrNull())
        } catch (t: Throwable) {
            Log.w(TAG, "gallery scan failed", t)
            _uiState.value = state.copy(busy = false, errorText = "相册识码失败：${t.message}")
            null
        }
    }

    /** 直接解码一张本地图片文件（也用于 adb 自动化验证）。 */
    suspend fun scanFromFilePath(path: String): ScanResult? = withContext(Dispatchers.IO) {
        _uiState.value = state.copy(busy = true)
        try {
            val file = File(path)
            val codes = scanner.processAwait(InputImage.fromFilePath(context, Uri.fromFile(file)))
            val result = codes.mapNotNull { toResult(it, "图片") }.firstOrNull()
            Log.i(TAG, "file scan ${file.name} -> ${result?.rawText ?: "no barcode"}")
            publishResult(result)
        } catch (t: Throwable) {
            Log.w(TAG, "file scan failed", t)
            _uiState.value = state.copy(busy = false, errorText = "图片识码失败：${t.message}")
            null
        }
    }

    private fun publishResult(result: ScanResult?): ScanResult? {
        _uiState.value = if (result == null) {
            state.copy(busy = false, errorText = "这张图里没找到码")
        } else {
            state.copy(
                busy = false,
                errorText = null,
                lastResult = result,
                history = mergeHistory(state.history, listOf(result)),
            )
        }
        return result
    }

    private suspend fun BarcodeScanner.processAwait(image: InputImage): List<Barcode> =
        suspendCancellableCoroutine { cont ->
            process(image)
                .addOnSuccessListener { cont.resume(it ?: emptyList()) }
                .addOnFailureListener { cont.resumeWithException(it) }
                .addOnCanceledListener { cont.resumeWithException(IllegalStateException("cancelled")) }
        }
}
