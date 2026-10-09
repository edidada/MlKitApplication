package com.example.mlkitapplication.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动缩放策略的纯逻辑单测：`./gradlew testDebugUnitTest`
 *
 * 模拟调用方的节奏：update() 拿到建议倍率后 echo() 回填，再进入下一帧。
 */
class ZoomPolicyTest {

    private fun newPolicy(
        maxRatio: Float = 8f,
        autoMax: Float = 4f,
    ) = ZoomPolicy(autoMaxRatio = autoMax, maxRatio = maxRatio)

    /** 一帧：推演 + 回填 */
    private fun step(p: ZoomPolicy, t: Long, fillRatio: Float?): ZoomDecision {
        val d = p.update(t, fillRatio)
        p.echo(d.targetRatio)
        return d
    }

    @Test
    fun `码太小应自动放大直至上限`() {
        val p = newPolicy()
        var t = 1000L
        p.reset(t)
        var last = 1f
        var reached = 0f
        repeat(20) {
            val d = step(p, t, fillRatio = 0.18f)
            assertEquals(ZoomMode.TRACKING, d.mode)
            assertTrue("倍率不应回退，实际 $last -> ${d.targetRatio}", d.targetRatio >= last)
            last = d.targetRatio
            reached = last
            t += 33
        }
        assertTrue("最终应明显放大，实际 $reached", reached > 2.5f)
        // 远小于目标占比时由自动上限托住；相对死区会让它在离上限约 0.1x 处停下，
        // 这是刻意的（避免贴着上限反复微动）
        assertTrue("应收敛到上限的死区内，实际 $reached", reached in 3.7f..4.001f)
    }

    @Test
    fun `码太大应自动退开`() {
        val p = newPolicy()
        p.echo(4f)
        val d = step(p, 2000L, fillRatio = 0.95f)
        assertEquals(ZoomMode.TRACKING, d.mode)
        assertTrue("占比超标应退开", d.targetRatio < 4f && d.engaging)
    }

    @Test
    fun `占比正好落在死区里不应动作`() {
        val p = newPolicy()
        p.reset(1000L)
        val d = step(p, 1000L, fillRatio = p.targetFill)
        assertFalse("达标后不该再动", d.engaging)
        assertEquals(1f, d.targetRatio, 0.001f)
    }

    @Test
    fun `短时丢框保持不动`() {
        val p = newPolicy()
        p.reset(1000L)
        step(p, 1000L, fillRatio = 0.2f)
        val d = p.update(1000L + p.lostGraceMs / 2, null)
        assertFalse(d.engaging)
    }

    @Test
    fun `持续丢框应先平滑回落到一倍再开始找码`() {
        val p = newPolicy()
        p.echo(3f)
        var t = 10_000L
        var minSeen = p.ratio
        var prev = p.ratio
        var decayedMonotonically = true
        repeat(40) {
            val d = step(p, t, null)
            if (d.mode == ZoomMode.IDLE && d.targetRatio > prev + 0.001f) decayedMonotonically = false
            prev = d.targetRatio
            minSeen = minOf(minSeen, d.targetRatio)
            t += 100
        }
        assertTrue("回落过程不应反弹", decayedMonotonically)
        assertTrue("应回落到 1x 附近，最低 $minSeen", minSeen <= 1.06f)
    }

    @Test
    fun `长时间没码应进入搜索并逐级放大`() {
        val p = newPolicy()
        p.reset(0L)
        var t = p.searchAfterMs + 10
        val d0 = step(p, t, null)
        assertEquals(ZoomMode.SEARCH, d0.mode)
        t += p.searchStepMs + 1
        val d1 = step(p, t, null)
        assertEquals(ZoomMode.SEARCH, d1.mode)
        assertTrue("搜索应产生放大", d1.targetRatio > 1f)
    }

    @Test
    fun `搜索应一路爬到上限不被回落规则打断`() {
        val p = newPolicy()
        p.reset(0L)
        var t = p.searchAfterMs + 10
        var last = p.ratio
        var maxSeen = last
        val modes = mutableSetOf<ZoomMode>()
        // 100ms/帧，步进由 searchStepMs 自己节流：20 帧足够从 1x 爬到自动上限
        repeat(50) {
            val d = step(p, t, null)
            modes += d.mode
            assertTrue("搜索只能单调爬升，实际 $last -> ${d.targetRatio}", d.targetRatio >= last - 0.001f)
            last = d.targetRatio
            maxSeen = maxOf(maxSeen, last)
            t += 100
        }
        assertEquals(setOf(ZoomMode.SEARCH), modes)
        assertTrue("应爬到自动上限附近，实际 $maxSeen", maxSeen >= p.autoMaxRatio - 0.05f)
    }

    @Test
    fun `搜索到顶后应回落一倍再开始下一轮`() {
        val p = newPolicy()
        p.reset(0L)
        var t = p.searchAfterMs + 10
        var wentUpToCap = false
        var cameBackLow = false
        var climbedAgain = false
        repeat(160) {
            val d = step(p, t, null)
            assertEquals("循环搜索期间应一直是 SEARCH，实际 ${d.mode}", ZoomMode.SEARCH, d.mode)
            if (d.targetRatio >= p.autoMaxRatio - 0.05f) wentUpToCap = true
            if (wentUpToCap && d.targetRatio <= 1.05f) cameBackLow = true
            if (cameBackLow && d.targetRatio > 1.6f) climbedAgain = true
            t += 100
        }
        assertTrue("应先爬到顶", wentUpToCap)
        assertTrue("到顶后应退回 1x", cameBackLow)
        assertTrue("退回 1x 后应重新开始放大", climbedAgain)
    }

    @Test
    fun `搜索中一旦检出码就交回跟踪规则`() {
        val p = newPolicy()
        p.reset(0L)
        var t = p.searchAfterMs + 10
        repeat(30) {
            step(p, t, null)
            t += 100
        }
        assertTrue("搜索应该已经放大了，实际 ${p.ratio}", p.ratio > 1.5f)
        val d = step(p, t, fillRatio = 0.1f)
        assertEquals(ZoomMode.TRACKING, d.mode)
        assertTrue("检出小码应继续放大", d.targetRatio > p.ratio - 0.001f)
        // 之后一直丢框：此时才是"从跟踪中丢失"，应先平滑回落到 1x
        var t2 = t + p.lostGraceMs + 1
        var minSeen = p.ratio
        repeat(60) {
            val lost = step(p, t2, null)
            minSeen = minOf(minSeen, lost.targetRatio)
            t2 += 100
        }
        assertTrue("跟踪丢失后应回到 1x，最低 $minSeen", minSeen <= 1.06f)
    }

    @Test
    fun `手动缩放期间策略冻结`() {
        val p = newPolicy()
        p.reset(0L)
        p.onManualZoom(1000L, 6f)
        val d = p.update(1200L, fillRatio = 0.1f)
        assertEquals(ZoomMode.MANUAL, d.mode)
        assertFalse(d.engaging)
        assertEquals(6f, d.targetRatio, 0.001f)
        // 冻结期过后策略重新接管
        val after = p.update(1000L + p.manualFreezeMs + 1, fillRatio = 0.1f)
        assertEquals(ZoomMode.TRACKING, after.mode)
        assertTrue(after.engaging)
    }

    @Test
    fun `自动倍率不超过上限`() {
        val p = newPolicy(autoMax = 3.5f)
        p.reset(0L)
        var t = 1000L
        repeat(60) {
            val d = step(p, t, fillRatio = 0.01f)
            assertTrue("auto 上限 3.5，实际 ${d.targetRatio}", d.targetRatio <= 3.5f + 0.001f)
            t += 33
        }
    }

    @Test
    fun `解码成功后短暂锁定倍率`() {
        val p = newPolicy()
        p.reset(0L)
        step(p, 1000L, fillRatio = 0.3f)
        p.onSuccess(2000L)
        val d = p.update(2100L, fillRatio = 0.05f)
        assertEquals(ZoomMode.LOCKED, d.mode)
        assertFalse(d.engaging)
    }
}
