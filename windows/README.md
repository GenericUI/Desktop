# `:desktop:windows` — Native Windows Backend (`mingwX64`)

## Overview
`:desktop:windows` is the native Windows (`mingwX64`) backend for GenericUI, built on Win32 (`HWND`), **`UxTheme`** (`uxtheme.dll`), Desktop Window Manager (`dwmapi.dll`), and Direct2D.

### Key Highlights
- **Genuine Win32 `UxTheme` Controls (`BootstrapType.NONE` Default)**: When `getBootstrap().type == BootstrapType.NONE` (the default) and no inline `ComponentStyle` background/border is set, controls render via `Win32DwmUxThemeBridge` using `SetWindowTheme` (`"Explorer"` in `LIGHT` mode, `"DarkMode_Explorer"` in `DARK` mode), `OpenThemeData(hwnd, "Explorer::BUTTON" | "DarkMode_Explorer::BUTTON")`, and `DrawThemeBackground`.
- **Semantic `BootstrapType` Tinting**: Setting `DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, or `LINK` emulates `BootstrapType` by tinting the native Win32 control with the semantic color (`outline = true/false`). Unsupported CSS properties and transitions are silently ignored.
- **Strict User CSS Class Isolation**: `getClasses()`, `getClass()`, `hasClass()`, `addClass()`, and `removeClass()` expose only user-added custom classes (`customClasses`).
- **Windows `.cur` Custom Cursor Synthesis (`WindowsCurCodec`)**: Converts any PNG `ResourceID`, SVG `ResourceID`/markup, or `ImageBuffer` `CssCustomCursor` into a valid Windows `.cur` binary (`ICONDIR` `idType = 2`, `ICONDIRENTRY` `wXHotSpot`/`wYHotSpot`, 32-bit BGRA DIB + 1-bpp AND mask) and Win32 `HCURSOR` for `WM_SETCURSOR`.
- **Live System Theme Detection (`ThemeMode.AUTO`)**: Reads `AppsUseLightTheme` from the Windows Registry, handles `WM_SETTINGCHANGE`, and updates `DWMWA_USE_IMMERSIVE_DARK_MODE`.

See [`../docs/windows.md`](../docs/windows.md) for full documentation.
