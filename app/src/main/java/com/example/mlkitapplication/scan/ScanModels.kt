package com.example.mlkitapplication.scan

import android.graphics.RectF

/**
 * 一个跟随中的码框（已映射到预览视口坐标，单位：像素）。
 */
data class TrackedBox(
    val rect: RectF,
    val label: String,
    val primary: Boolean,
)

/**
 * 一次解码结果。
 */
data class ScanResult(
    val id: Long,
    val displayValue: String,
    val rawText: String,
    val formatName: String,
    val valueTypeName: String,
    val rawBytesHex: String?,
    val source: String,
    val timestampMs: Long,
) {
    val isUrl: Boolean
        get() = rawText.startsWith("http://", true) || rawText.startsWith("https://", true)
}

/**
 * 相机/扫描状态，UI 只读这一个对象。
 */
data class ScanUiState(
    val cameraReady: Boolean = false,
    val errorText: String? = null,
    val zoomRatio: Float = 1f,
    val zoomMode: ZoomMode = ZoomMode.IDLE,
    val autoZoomEnabled: Boolean = true,
    val continuous: Boolean = true,
    val torchOn: Boolean = false,
    val autoTorch: Boolean = true,
    val hasTorch: Boolean = true,
    val frontCamera: Boolean = false,
    val boxes: List<TrackedBox> = emptyList(),
    val lastResult: ScanResult? = null,
    val history: List<ScanResult> = emptyList(),
    val analyzeFps: Int = 0,
    val dark: Boolean = false,
    val busy: Boolean = false,
) {
    val zoomLabel: String get() = "x%.1f".format(zoomRatio)

    val modeLabel: String
        get() = when (zoomMode) {
            ZoomMode.TRACKING -> "锁定码框 · 自动缩放"
            ZoomMode.SEARCH -> "未见码 · 自动找码"
            ZoomMode.MANUAL -> "手动缩放中 · 策略暂停"
            ZoomMode.LOCKED -> "已解码 · 倍率锁定"
            ZoomMode.IDLE -> if (autoZoomEnabled) "待机 · 自动缩放待命" else "待机 · 自动缩放关闭"
        }
}
