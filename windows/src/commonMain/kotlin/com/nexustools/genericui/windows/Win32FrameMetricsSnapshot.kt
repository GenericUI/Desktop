package com.nexustools.genericui.windows

/**
 * Snapshot of Win32 paint timing and frame breakdown metrics for verification and diagnostics.
 */
data class Win32FrameMetricsSnapshot(
    val totalPaints: Int,
    val videoStageOnlyPaints: Int,
    val fullWindowPaints: Int,
    val lastPaintUs: Long,
    val p50PaintUs: Long,
    val p95PaintUs: Long,
    val maxPaintUs: Long,
    val avgPaintUs: Long,
    val lastSlowPaintBreakdown: String = ""
)
