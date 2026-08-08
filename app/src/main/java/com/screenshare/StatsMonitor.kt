package com.screenshare

import android.content.Context
import android.os.Debug

/**
 * V4: 性能监控面板数据源。
 *
 * 提供进程 CPU 使用率与内存占用，与 WebRTC 的 FPS/Bitrate/Delay/Loss 组合成
 * 完整性能监控文本：
 *
 *   ScreenShare | FPS 30 | Bitrate 5.2M | Delay 45ms | Loss 0.3% | CPU 35% | Memory 420M
 *
 * CPU 计算：读取 /proc/self/stat 的 utime+stime 差值 / 全局 /proc/stat 总时间差值，
 * 得到进程在采样间隔内的 CPU 占用百分比（近似，跨核最高 100%）。
 */
object StatsMonitor {
    private var lastProcTotal = 0L
    private var lastGlobalTotal = 0L
    private var lastReadMs = 0L

    /** 当前进程 CPU 使用率（0~100，/proc/self/stat 与 /proc/stat 差值法） */
    fun processCpuPercent(): Int {
        val now = System.currentTimeMillis()
        try {
            val procTotal = runCatching { readProcSelfStat() }.getOrNull() ?: return 0
            val globalTotal = runCatching { readProcStat() }.getOrNull() ?: return 0
            if (lastReadMs > 0 && now > lastReadMs) {
                val dProc = procTotal - lastProcTotal
                val dGlobal = globalTotal - lastGlobalTotal
                if (dGlobal > 0 && dProc >= 0) {
                    val pct = dProc * 100.0 / dGlobal
                    lastProcTotal = procTotal
                    lastGlobalTotal = globalTotal
                    lastReadMs = now
                    return pct.toInt().coerceIn(0, 100)
                }
            }
            lastProcTotal = procTotal
            lastGlobalTotal = globalTotal
            lastReadMs = now
        } catch (_: Throwable) {}
        return 0
    }

    /** /proc/self/stat 中第 14、15 字段 (utime, stime)，单位 jiffies */
    private fun readProcSelfStat(): Long {
        val s = java.io.File("/proc/self/stat").readText()
        val parts = s.split(" ")
        // field 14 => index 13, field 15 => index 14
        val utime = parts.getOrNull(13)?.toLongOrNull() ?: 0L
        val stime = parts.getOrNull(14)?.toLongOrNull() ?: 0L
        return utime + stime
    }

    /** /proc/stat 首行 cpu 的 user+nice+system+idle+iowait+irq+softirq+steal 总时钟 */
    private fun readProcStat(): Long {
        val line = java.io.File("/proc/stat").readLines().firstOrNull() ?: return 0
        val parts = line.split(Regex("\\s+")).drop(1)
        return parts.mapNotNull { it.toLongOrNull() }.sum()
    }

    /** 当前进程内存占用（MB，PSS 更贴近真实占用） */
    fun processMemoryMb(context: Context): Int {
        return try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            (mi.totalPss / 1024).coerceAtLeast(0)
        } catch (_: Throwable) { 0 }
    }

    /**
     * 组装完整性能监控面板文本。
     * @param fps 当前帧率（发送/接收）
     * @param bitrateMbps 码率（Mbps 字符串，如 "5.2"）
     * @param delayMs 端到端延迟
     * @param lossPct 丢包率
     */
    fun buildPanel(
        context: Context,
        fps: Int,
        bitrateMbps: String,
        delayMs: Int,
        lossPct: Double
    ): String {
        val cpu = processCpuPercent()
        val mem = processMemoryMb(context)
        val loss = if (lossPct >= 0) "%.1f".format(lossPct) else "--"
        val delay = if (delayMs > 0) "$delayMs" else "--"
        return "ScreenShare | FPS $fps | Bitrate ${if (bitrateMbps.isNotBlank()) "$bitrateMbps M" else "--"} | " +
            "Delay $delay ms | Loss $loss% | CPU $cpu% | Memory ${mem}M"
    }
}