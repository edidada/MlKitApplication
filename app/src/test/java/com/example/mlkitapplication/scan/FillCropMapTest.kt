package com.example.mlkitapplication.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览坐标映射的纯数学单测（不依赖 android.graphics）。
 */
class FillCropMapTest {

    private val eps = 0.5f

    @Test
    fun `竖屏 90 度旋转时图像宽高互换`() {
        // 传感器 1280x960，旋转 90 -> 转正后 960x1280；视口 1080x2400
        val g = FrameGeometry(rotatedW = 960f, rotatedH = 1280f, mirrored = false)
        val box = BoxF(0f, 0f, 480f, 480f) // 转正后坐标系里左上半区的一个码
        val mapped = fillCropBox(box, g, 1080, 2400)
        assertNotNull(mapped)
        mapped!!
        val scale = 2400f / 1280f
        // 水平方向溢出被居中裁掉：offX = (1080 - 960*scale)/2 < 0
        val offX = (1080 - 960f * scale) / 2f
        assertTrue(offX < 0f)
        assertEquals(0f * scale + offX, mapped.left, eps)
        assertEquals(480f * scale + offX, mapped.right, eps)
        assertEquals(0f * scale + (2400 - 1280f * scale) / 2f, mapped.top, eps)
    }

    @Test
    fun `长边缩放只由统一缩放系数决定`() {
        val g = FrameGeometry(960f, 1280f, false)
        val wide = BoxF(100f, 600f, 700f, 700f)
        val mapped = fillCropBox(wide, g, 1080, 2400)!!
        val scale = maxOf(1080 / 960f, 2400 / 1280f)
        assertEquals((wide.right - wide.left) * scale, mapped.right - mapped.left, eps)
        assertEquals((wide.bottom - wide.top) * scale, mapped.bottom - mapped.top, eps)
    }

    @Test
    fun `前置摄像头做水平镜像`() {
        val back = fillCropBox(BoxF(0f, 0f, 100f, 100f), FrameGeometry(960f, 1280f, false), 1080, 2400)!!
        val front = fillCropBox(BoxF(0f, 0f, 100f, 100f), FrameGeometry(960f, 1280f, true), 1080, 2400)!!
        // 镜像后左右应关于视口中轴对称（尺寸不变）
        assertEquals(back.right - back.left, front.right - front.left, eps)
        assertEquals(1080f - back.right, front.left, eps)
        assertEquals(1080f - back.left, front.right, eps)
    }

    @Test
    fun `退化输入返回空`() {
        assertNull(fillCropBox(BoxF(0f, 0f, 10f, 10f), FrameGeometry(0f, 100f, false), 1080, 2400))
        assertNull(fillCropBox(BoxF(0f, 0f, 10f, 10f), FrameGeometry(960f, 1280f, false), 0, 2400))
        // 零面积矩形无意义
        assertNull(fillCropBox(BoxF(50f, 50f, 50f, 80f), FrameGeometry(960f, 1280f, false), 1080, 2400))
    }

    @Test
    fun `视口比图像更宽时按宽度铺满`() {
        val g = FrameGeometry(960f, 480f, false)
        val mapped = fillCropBox(BoxF(0f, 0f, 960f, 480f), g, 1920, 600)!!
        val scale = maxOf(1920 / 960f, 600 / 480f)
        assertEquals(1920f / 960f, scale, 0.001f)
        assertEquals(1920f, mapped.right - mapped.left, eps)
        // 垂直方向溢出被裁掉
        assertTrue(mapped.bottom - mapped.top > 600f)
    }
}
