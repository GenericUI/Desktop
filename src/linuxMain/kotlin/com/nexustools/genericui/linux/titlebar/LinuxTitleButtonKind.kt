package com.nexustools.genericui.linux.titlebar

/**
 * Supported window titlebar button kinds across GNOME/Yaru, KWin Breeze, Muffin, Xfwm4, and Marco.
 */
enum class LinuxTitleButtonKind {
    MENU,
    MINIMIZE,
    MAXIMIZE_OR_RESTORE,
    CLOSE,
    KEEP_ABOVE,
    KEEP_BELOW,
    ON_ALL_DESKTOPS,
    SHADE,
    CONTEXT_HELP,
    SPACER
}
