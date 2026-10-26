package com.nexustools.genericui.windows

/**
 * Identifies the active Windows visual style and Desktop Window Manager (DWM) generation
 * so the `:desktop:windows` titlebar integrates with DWM or emulates the exact native OS theme.
 */
enum class Win32VisualStyleGeneration {
    /**
     * Windows 11+ (`build >= 22000`) with DWM composition enabled.
     * Supports Mica (`DWMWA_SYSTEMBACKDROP_TYPE = DWMSBT_MAINWINDOW`), rounded corners
     * (`DWMWA_WINDOW_CORNER_PREFERENCE`), dark/light system theme (`DWMWA_USE_IMMERSIVE_DARK_MODE`),
     * `Segoe Fluent Icons` caption glyphs, and Windows 11 Snap Layouts via `HTMAXBUTTON`.
     */
    WIN11_MICA_FLUENT,

    /**
     * Windows 10 (`major == 10, build < 22000`) with DWM composition enabled.
     * Supports dark/light system theme, `DWM\ColorPrevalence` accent-colored titlebars,
     * and `Segoe MDL2 Assets` caption glyphs (`46x32` caption buttons).
     */
    WIN10_FLUENT,

    /**
     * Windows 8 / 8.1 (`major == 6, minor in 2..3`) with DWM composition enabled.
     * Supports native DWM caption buttons (`DwmDefWindowProc` + `DWMWA_CAPTION_BUTTON_BOUNDS`),
     * DWM colorization frame colors, and centered window titles.
     */
    WIN8_AERO,

    /**
     * Windows Vista / Windows 7 (`major == 6, minor in 0..1`) with DWM Aero Glass composition enabled.
     * Supports translucent Aero Glass blur via `DwmExtendFrameIntoClientArea`, native glowing Aero
     * caption buttons via `DwmDefWindowProc`, and glowing caption text.
     */
    VISTA_7_AERO_GLASS,

    /**
     * Windows Basic / Non-DWM visual style (`DwmIsCompositionEnabled == FALSE`, `IsThemeActive == TRUE`),
     * such as Windows 7 Basic or Windows XP Luna, rendered via `uxtheme.dll` (`DrawThemeBackground`).
     */
    UXTHEME_BASIC,

    /**
     * Windows Classic or High-Contrast theme (`IsThemeActive == FALSE` or `SPI_GETHIGHCONTRAST`),
     * rendered via Win32 `DrawCaption` (`COLOR_ACTIVECAPTION` / `COLOR_GRADIENTACTIVECAPTION`) and `DrawFrameControl`.
     */
    WIN32_CLASSIC
}
