package com.nexustools.genericui.windows

/**
 * Defines the structural and visual kind of a Windows titlebar or panel header bar.
 *
 * All titlebar kinds share the same underlying 32-bit ARGB DIB renderer, theme palette resolver,
 * and component-embedding pipeline ([Win32NativeTitleBarRenderer.renderEmbeddedTitleBarComponents]),
 * while adapting their metrics, caption button geometry, font sizing, and Win32/DWM/UxTheme part IDs:
 *
 * - [STANDARD_WINDOW]: Normal top-level window titlebar (`SM_CYCAPTION`, `WP_CAPTION`, `WP_CLOSEBUTTON`,
 *   `DWMWCP_ROUND`, `DWMSBT_MAINWINDOW` Mica, `46px` wide caption buttons with `10x10` glyphs,
 *   window icon + Minimize/Maximize/Close buttons).
 * - [TOOL_WINDOW]: Native Win32 tool window titlebar (`WS_EX_TOOLWINDOW`, `SM_CYSMCAPTION`,
 *   `WP_SMALLCAPTION`, `WP_SMALLCLOSEBUTTON`, `DWMWCP_ROUNDSMALL`, `DWMSBT_TRANSIENTWINDOW` Acrylic,
 *   compact `24..28px` height, `28px` wide tool close button with an `8x8` close glyph, smaller tool
 *   caption font, no taskbar button, and no Minimize/Maximize buttons). Used by `PanelWindow` and
 *   floating `DockWindow` tool windows.
 * - [DIALOG_WINDOW]: Modal or modeless dialog titlebar (`32px` height, `36px` close button,
 *   no Minimize or Maximize buttons by default).
 * - [DOCKED_PANEL_HEADER]: In-window collapsible dock panel header bar (`26px` compact height,
 *   leading collapse/expand chevron indicator, no window caption buttons, full support for embedded
 *   `TitleBar` `leadingComponents`, `centerComponents`, `trailingComponents`, and `DesktopDockPanelAction`s).
 */
enum class Win32TitleBarKind(
    val isTopLevelNonClientFrame: Boolean,
    val usesSmallCaptionMetrics: Boolean,
    val defaultMinHeightPx: Int,
    val defaultMaxHeightPx: Int,
    val defaultFallbackHeightPx: Int,
    val defaultCaptionButtonWidthPx: Int,
    val defaultGlyphSizePx: Int,
    val defaultComponentHeightPx: Int,
    val defaultShowWindowIcon: Boolean,
    val defaultShowMinimizeButton: Boolean,
    val defaultShowMaximizeButton: Boolean,
    val defaultShowCloseButton: Boolean,
    val allowMinimizeMaximizeButtons: Boolean,
    val uxThemeCaptionPartId: Int,
    val uxThemeCloseButtonPartId: Int,
    val win11CornerPreference: Int,
    val win11BackdropType: Int
) {
    /**
     * Standard top-level application window titlebar (`Window` / `WindowsWindowNativeImpl`).
     */
    STANDARD_WINDOW(
        isTopLevelNonClientFrame = true,
        usesSmallCaptionMetrics = false,
        defaultMinHeightPx = 32,
        defaultMaxHeightPx = 40,
        defaultFallbackHeightPx = 34,
        defaultCaptionButtonWidthPx = 46,
        defaultGlyphSizePx = 10,
        defaultComponentHeightPx = 24,
        defaultShowWindowIcon = true,
        defaultShowMinimizeButton = true,
        defaultShowMaximizeButton = true,
        defaultShowCloseButton = true,
        allowMinimizeMaximizeButtons = true,
        uxThemeCaptionPartId = 1,      // WP_CAPTION
        uxThemeCloseButtonPartId = 18, // WP_CLOSEBUTTON
        win11CornerPreference = 2,     // DWMWCP_ROUND
        win11BackdropType = 2          // DWMSBT_MAINWINDOW (Mica)
    ),

    /**
     * Native Win32 tool window titlebar (`WS_EX_TOOLWINDOW`, `SM_CYSMCAPTION`, `WP_SMALLCAPTION`)
     * used for `PanelWindow` (`DesktopDockPanelNativeImpl`), `WindowsDockWindowNativeImpl`, and
     * tool-modal windows.
     */
    TOOL_WINDOW(
        isTopLevelNonClientFrame = true,
        usesSmallCaptionMetrics = true,
        defaultMinHeightPx = 24,
        defaultMaxHeightPx = 28,
        defaultFallbackHeightPx = 26,
        defaultCaptionButtonWidthPx = 28,
        defaultGlyphSizePx = 8,
        defaultComponentHeightPx = 20,
        defaultShowWindowIcon = false,
        defaultShowMinimizeButton = false,
        defaultShowMaximizeButton = false,
        defaultShowCloseButton = true,
        allowMinimizeMaximizeButtons = false,
        uxThemeCaptionPartId = 2,      // WP_SMALLCAPTION
        uxThemeCloseButtonPartId = 19, // WP_SMALLCLOSEBUTTON
        win11CornerPreference = 3,     // DWMWCP_ROUNDSMALL
        win11BackdropType = 3          // DWMSBT_TRANSIENTWINDOW (Acrylic)
    ),

    /**
     * Dialog / modal window titlebar (`DialogWindow` / `DesktopDialogWindowNativeImpl`).
     */
    DIALOG_WINDOW(
        isTopLevelNonClientFrame = false,
        usesSmallCaptionMetrics = false,
        defaultMinHeightPx = 30,
        defaultMaxHeightPx = 36,
        defaultFallbackHeightPx = 32,
        defaultCaptionButtonWidthPx = 36,
        defaultGlyphSizePx = 10,
        defaultComponentHeightPx = 22,
        defaultShowWindowIcon = false,
        defaultShowMinimizeButton = false,
        defaultShowMaximizeButton = false,
        defaultShowCloseButton = true,
        allowMinimizeMaximizeButtons = false,
        uxThemeCaptionPartId = 1,      // WP_CAPTION
        uxThemeCloseButtonPartId = 18, // WP_CLOSEBUTTON
        win11CornerPreference = 2,     // DWMWCP_ROUND
        win11BackdropType = 3          // DWMSBT_TRANSIENTWINDOW
    ),

    /**
     * In-window docked collapsible panel header bar (`DockPanel` header in single-window mode).
     */
    DOCKED_PANEL_HEADER(
        isTopLevelNonClientFrame = false,
        usesSmallCaptionMetrics = true,
        defaultMinHeightPx = 24,
        defaultMaxHeightPx = 28,
        defaultFallbackHeightPx = 26,
        defaultCaptionButtonWidthPx = 24,
        defaultGlyphSizePx = 8,
        defaultComponentHeightPx = 20,
        defaultShowWindowIcon = false,
        defaultShowMinimizeButton = false,
        defaultShowMaximizeButton = false,
        defaultShowCloseButton = false,
        allowMinimizeMaximizeButtons = false,
        uxThemeCaptionPartId = 2,      // WP_SMALLCAPTION
        uxThemeCloseButtonPartId = 19, // WP_SMALLCLOSEBUTTON
        win11CornerPreference = 3,     // DWMWCP_ROUNDSMALL
        win11BackdropType = 1          // DWMSBT_NONE
    );
}
