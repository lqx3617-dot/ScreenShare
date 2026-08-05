package com.screenshare

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateMapperTest {

    @Test
    fun fit_竖屏视频在瘦高区域水平方向留黑边() {
        // renderer 400x800(1:2)，视频 1080x1920(9:16) → 内容高711.11，水平居中
        val r = CoordinateMapper.contentRect(400f, 800f, 1080, 1920, crop = false)
        val contentW = 1080f * (400f / 1080f) // 400
        val contentH = 1920f * (400f / 1080f) // 711.11
        assertEquals((400f - contentW) / 2f, r[0], 0.001f)
        assertEquals((800f - contentH) / 2f, r[1], 0.001f)
        assertEquals((400f + contentW) / 2f, r[2], 0.001f)
        assertEquals((800f + contentH) / 2f, r[3], 0.001f)
    }

    @Test
    fun fit_横视频在竖屏区域垂直方向留黑边() {
        // renderer 400x800，视频 1920x1080（横屏）→ 内容宽400 高225，垂直居中
        val r = CoordinateMapper.contentRect(400f, 800f, 1920, 1080, crop = false)
        val contentH = 225f
        assertEquals(0f, r[0], 0.001f)
        assertEquals((800f - contentH) / 2f, r[1], 0.001f)
        assertEquals(400f, r[2], 0.001f)
        assertEquals((800f + contentH) / 2f, r[3], 0.001f)
    }

    @Test
    fun crop_铺满模式内容覆盖整个渲染区域() {
        val r = CoordinateMapper.contentRect(400f, 800f, 1920, 1080, crop = true)
        assertArrayEquals(floatArrayOf(0f, 0f, 400f, 800f), r, 0.001f)
    }

    @Test
    fun crop_边缘归一化为0或1() {
        val n = CoordinateMapper.normalizeTouch(400f, 800f, 400f, 800f, 1920, 1080, crop = true)!!
        assertEquals(1f, n[0], 0.001f)
        assertEquals(1f, n[1], 0.001f)
    }

    @Test
    fun normalize_fit_内容区内触摸归一化正确() {
        // 横视频：内容矩形 top=287.5, height=225 → 触摸(200, 400) 中心点
        val n = CoordinateMapper.normalizeTouch(200f, 400f, 400f, 800f, 1920, 1080, crop = false)!!
        assertEquals(0.5f, n[0], 0.001f)
        assertEquals(0.5f, n[1], 0.001f)
    }

    @Test
    fun normalize_fit_黑边区域返回null() {
        val n = CoordinateMapper.normalizeTouch(200f, 100f, 400f, 800f, 1920, 1080, crop = false)
        assertNull(n)
    }

    @Test
    fun normalize_越界坐标返回null() {
        val n = CoordinateMapper.normalizeTouch(-5f, 10f, 400f, 800f, 1920, 1080, crop = true)
        assertNull(n)
    }

    @Test
    fun toScreenPx_按真实屏幕分辨率还原() {
        val (x, y) = CoordinateMapper.toScreenPx(0.5f, 0.25f, 1080, 2400)
        assertEquals(540, x)
        assertEquals(600, y)
    }

    @Test
    fun toScreenPx_越界归一化值被钳制() {
        val (x, y) = CoordinateMapper.toScreenPx(-0.1f, 1.2f, 1080, 2400)
        assertEquals(0, x)
        assertEquals(2399, y)
    }

    @Test
    fun 归一化与还原往返一致() {
        for (i in 0..20) {
            val nx = i / 20f
            val ny = (20 - i) / 20f
            val (x, y) = CoordinateMapper.toScreenPx(nx, ny, 1080, 2400)
            assertTrue(Math.abs(x / 1080f - nx) <= 2f / 1080f)
            assertTrue(Math.abs(y / 2400f - ny) <= 2f / 2400f)
        }
    }
}
