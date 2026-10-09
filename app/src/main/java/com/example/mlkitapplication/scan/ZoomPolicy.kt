package com.example.mlkitapplication.scan

/**
 * 自动缩放的工作状态。
 */
enum class ZoomMode {
    /** 没有任何动作（相机停在当前倍率） */
    IDLE,

    /** 已检出码，按包围盒大小把码"拉"到目标占比 */
    TRACKING,

    /** 长时间没检出码，逐级放大主动找码 */
    SEARCH,

    /** 用户刚捏合过缩放，策略冻结一段时间 */
    MANUAL,

    /** 刚成功解码，短暂锁定倍率做确认 */
    LOCKED,
}

/**
 * 一次策略推演的结果。
 *
 * @param targetRatio 期望相机倍率
 * @param mode        当前状态
 * @param engaging    本次是否产生了有效变化（false 表示无需下发给相机）
 */
data class ZoomDecision(
    val targetRatio: Float,
    val mode: ZoomMode,
    val engaging: Boolean,
)

/**
 * 扫码自动缩放策略 —— 纯 Kotlin，无 Android 依赖，可直接跑 JVM 单测。
 *
 * 对标 2026 年主流大厂扫码体验（离得远自动拉近、贴得太近自动退开、
 * 找不到码时缓慢"搜索"、用户手动缩放后尊重用户意图）的三条规则：
 *
 * 1. **TRACKING**：检出码时，设码的长边占取景短边的比例 `fill`。
 *    数字变焦近似裁剪，码的视觉尺寸与倍率成正比，所以
 *    `desired = current * targetFill / fill`，再做非对称一阶平滑
 *    （放大快、回退慢）+ 相对死区，消除"呼吸"抖动。
 * 2. **SEARCH**：已经在 1x 且持续没检出，说明码太小/太远，
 *    按时间片逐级放大；到上限后保持一段时间再回落 1x，形成循环搜索。
 *    搜索进行中不会被规则 3 的回落打断。
 * 3. **IDLE**：框短时丢失（< `lostGraceMs`）不动作以免闪烁；持续丢失则
 *    缓慢回落到 1x，不把用户"锁死"在高倍率。
 *
 * `MANUAL` / `LOCKED` 期间策略完全不介入，只保持当前倍率。
 *
 * 注意：调用方每帧先用 [echo] 回填相机真实倍率，再调 [update]。
 */
class ZoomPolicy(
    val minRatio: Float = 1f,
    /** 自动缩放允许的倍率上限（再高容易虚焦） */
    val autoMaxRatio: Float = 4f,
    /** 相机真实倍率上限，运行时由引擎回填 */
    var maxRatio: Float = 4f,
    /** 目标：码长边 / 取景框短边 */
    val targetFill: Float = 0.54f,
    /** 相对死区，小于该变化量不动作 */
    val deadbandRatio: Float = 0.04f,
    /** 单帧最大逼近比例（放大方向） */
    val gainIn: Float = 0.34f,
    /** 单帧最大逼近比例（回退方向，刻意比 gainIn 小） */
    val gainOut: Float = 0.12f,
    /** 包围盒丢失容忍时间 */
    val lostGraceMs: Long = 500,
    /** 无检出多久后进入搜索放大 */
    val searchAfterMs: Long = 1500,
    val searchStepMs: Long = 800,
    val searchHoldAtCapMs: Long = 2200,
    val manualFreezeMs: Long = 2500,
    val successFreezeMs: Long = 1000,
    /** 搜索时每级放大倍率 */
    val searchStepRatio: Float = 0.6f,
) {
    /** 相机当前倍率的本地镜像（由 [echo] 更新） */
    var ratio: Float = minRatio
        private set

    var mode: ZoomMode = ZoomMode.IDLE
        private set

    private var smoothedFill = 0f
    private var lastSeenAt = Long.MIN_VALUE / 4
    private var freezeUntil = 0L
    private var freezeMode = ZoomMode.MANUAL
    private var searchTarget = minRatio
    private var lastSearchStepAt = 0L
    private var atCapSince = 0L

    /**
     * 是否正处于"一次搜索战役"中。
     *
     * 关键：搜索期间必须屏蔽 IDLE 的"回落 1x"规则，否则每爬一级就被
     * 拉回去，`searchTarget` 永远停在 1x 附近，循环搜索形同虚设。
     * 只有真正检出过码（TRACKING）或复位/手动缩放时才清掉。
     */
    private var searching = false

    /** 搜索循环中"从上限退回 1x"的那一段 */
    private var searchReturning = false

    init {
        require(minRatio > 0f) { "minRatio must be > 0" }
        require(autoMaxRatio >= minRatio) { "autoMaxRatio must be >= minRatio" }
        require(targetFill > 0f) { "targetFill must be > 0" }
    }

    /** 回填相机真实倍率（zoomState 或刚下发的值）。 */
    fun echo(actualRatio: Float) {
        if (actualRatio.isFinite() && actualRatio >= minRatio) {
            ratio = actualRatio.coerceAtMost(maxRatio)
        }
    }

    /** 用户捏合缩放：尊重用户意图，冻结自动策略。 */
    fun onManualZoom(nowMs: Long, actualRatio: Float) {
        echo(actualRatio)
        freezeUntil = maxOf(freezeUntil, nowMs + manualFreezeMs)
        freezeMode = ZoomMode.MANUAL
        resetSearch()
    }

    /** 成功解码：短暂锁定，避免结果页还在抖动。 */
    fun onSuccess(nowMs: Long) {
        freezeUntil = maxOf(freezeUntil, nowMs + successFreezeMs)
        freezeMode = ZoomMode.LOCKED
    }

    /** 切换摄像头 / 重新开始扫描时复位。 */
    fun reset(nowMs: Long = 0L) {
        smoothedFill = 0f
        lastSeenAt = nowMs
        freezeUntil = 0L
        resetSearch()
        mode = ZoomMode.IDLE
    }

    private fun resetSearch() {
        searching = false
        searchReturning = false
        searchTarget = ratio
        lastSearchStepAt = 0L
        atCapSince = 0L
    }

    private fun autoCap(): Float = minOf(autoMaxRatio, maxRatio)

    private fun clamp(r: Float): Float = r.coerceIn(minRatio, autoCap())

    /**
     * 每帧推演。
     *
     * @param nowMs   单调时间戳
     * @param fillRatio 当前检出码的 长边/取景短边 比例；未检出传 null
     */
    fun update(nowMs: Long, fillRatio: Float?): ZoomDecision {
        if (freezeUntil > nowMs) {
            mode = freezeMode
            return ZoomDecision(ratio, mode, engaging = false)
        }

        val detected = fillRatio != null && fillRatio > 0.001f
        if (detected) {
            val f = fillRatio!!
            // 帧间平滑，抗单帧漏检/抖动
            smoothedFill = if (smoothedFill <= 0f) f else smoothedFill * 0.5f + f * 0.5f
            lastSeenAt = nowMs
            mode = ZoomMode.TRACKING
            resetSearch()

            val desired = clamp(ratio * (targetFill / smoothedFill))
            val gap = desired - ratio
            // 死区判定看"离目标还差多少"，而不是"本帧能动多少"：
            // 后者会在逼近上限时留下一个够不着的死区平台。
            var next = if (kotlin.math.abs(gap) < ratio * deadbandRatio) ratio else ratio + (if (gap > 0) gainIn else gainOut) * gap
            next = clamp(next)
            return ZoomDecision(next, mode, engaging = next != ratio)
        }

        smoothedFill = 0f
        val sinceSeen = nowMs - lastSeenAt

        // 短时丢框：什么都不做，等下一帧
        if (sinceSeen < lostGraceMs) {
            mode = ZoomMode.IDLE
            return ZoomDecision(ratio, mode, engaging = false)
        }

        // 高倍率下丢框：缓慢回落到 1x（搜索战役进行中除外，见 [searching]）
        if (!searching && ratio > minRatio + deadbandRatio) {
            mode = ZoomMode.IDLE
            val next = clamp(ratio - gainOut * (ratio - minRatio) - 0.01f)
            resetSearch()
            return ZoomDecision(next, mode, engaging = next != ratio)
        }

        // 已在 1x 且持续无检出：进入搜索
        mode = ZoomMode.SEARCH
        searching = true

        // 一轮搜到顶仍没找到 → 每帧平滑退回 1x，退到底再开新一轮
        if (searchReturning) {
            val next = clamp(ratio - gainOut * (ratio - minRatio) - 0.01f)
            if (next <= minRatio + deadbandRatio) {
                searchReturning = false
                searchTarget = minRatio
                atCapSince = 0L
                lastSearchStepAt = nowMs
            }
            return ZoomDecision(next, mode, engaging = next != ratio)
        }

        if (sinceSeen < searchAfterMs) {
            return ZoomDecision(ratio, mode, engaging = false)
        }
        if (lastSearchStepAt == 0L) {
            lastSearchStepAt = nowMs
        } else if (nowMs - lastSearchStepAt >= searchStepMs) {
            // 到点了抬一级，之后每帧朝这一级平滑逼近（比"只在步进帧动"更顺）
            lastSearchStepAt = nowMs
            searchTarget = clamp(searchTarget + searchStepRatio)
            if (searchTarget >= autoCap()) {
                if (atCapSince == 0L) {
                    atCapSince = nowMs
                } else if (nowMs - atCapSince >= searchHoldAtCapMs) {
                    searchReturning = true
                }
            }
        }
        if (kotlin.math.abs(searchTarget - ratio) < 0.01f) {
            return ZoomDecision(ratio, mode, engaging = false)
        }
        val gain = if (searchTarget > ratio) gainIn else gainOut
        val next = clamp(ratio + gain * (searchTarget - ratio))
        return ZoomDecision(next, mode, engaging = next != ratio)
    }
}
