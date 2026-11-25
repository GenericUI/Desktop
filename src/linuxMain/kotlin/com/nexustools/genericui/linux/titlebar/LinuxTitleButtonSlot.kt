package com.nexustools.genericui.linux.titlebar

/**
 * Declarative button placement entry combining a [LinuxTitleButtonKind] and its [LinuxTitleButtonSide].
 */
data class LinuxTitleButtonSlot(
    val kind: LinuxTitleButtonKind,
    val side: LinuxTitleButtonSide
)
