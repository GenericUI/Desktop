package com.nexustools.genericui.linux.titlebar

/**
 * Recognized Linux window manager / compositor backend for titlebar detection.
 */
enum class LinuxWmType {
    MUTTER_YARU,
    MUTTER_LIBADWAITA,
    KWIN_BREEZE,
    MUFFIN_MINT_Y,
    XFWM4,
    MARCO,
    UNKNOWN_OR_UNSUPPORTED
}
