package com.nexustools.genericui.macos

/**
 * Aggregated CoreGraphics paint-timing telemetry for `:desktop:macos` visual performance benchmarks.
 */
data class MacosFrameMetricsSnapshot(
    val totalPaints: Long,
    val stageOnlyPaints: Long,
    val fullWindowPaints: Long,
    val sampleCount: Int,
    val p50PaintUs: Long,
    val p95PaintUs: Long,
    val maxPaintUs: Long,
    val avgPaintUs: Long
)
