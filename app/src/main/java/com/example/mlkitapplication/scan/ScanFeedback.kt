package com.example.mlkitapplication.scan

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext

/**
 * 解码成功的即时反馈：震动（走 Compose HapticFeedback，无需 VIBRATE 权限）+ 「嘀」声。
 * 与主流扫码 App 的确认手感一致；任一环节不可用都不影响主流程。
 */
class ScanFeedback(private val context: Context) {
    private var tone: ToneGenerator? = null

    fun prepare() {
        if (tone != null) return
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
            .onFailure { Log.w(TAG, "tone unavailable: ${it.message}") }
            .getOrNull()
    }

    fun success(haptic: HapticFeedback) {
        runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 140) }
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
    }

    companion object {
        private const val TAG = "ScanFeedback"
    }
}

/** 声震反馈的生命周期包装：随组合释放资源。 */
@Composable
fun rememberScanFeedback(): ScanFeedback {
    val ctx = LocalContext.current
    val feedback = remember { ScanFeedback(ctx) }
    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }
    return feedback
}
