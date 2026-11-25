package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.geometry.Rect

/**
 * Hit-test bounds for a laid-out Linux titlebar button.
 */
data class LinuxTitleBarButtonHit(
    val kind: LinuxTitleButtonKind,
    val side: LinuxTitleButtonSide,
    val bounds: Rect
)
