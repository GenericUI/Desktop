# `:desktop:nativeCommon` — Native C-Interop Desktop Foundation

## Overview
`:desktop:nativeCommon` provides the shared Kotlin/Native widget, container, window, styling, docking, lazy viewport, user directory, and libjpeg implementation for the four native cinterop desktop backends (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, and `:desktop:qt`). It targets `mingwX64()`, `macosX64()`, `macosArm64()`, and `linuxX64()`, and depends on `:common`, `:systemCommon`, and `:desktop:common`.

---

## Key Packages & Classes

- **`com.nexustools.genericui.desktop.core` (`DesktopBaseNativeImpl`)**:
  - **`BootstrapType.NONE` Default & Semantic `BootstrapType` Tinting**: When `styleSnapshot.type == BootstrapType.NONE` (the default) and no inline `ComponentStyle` background/border is set, native desktop backends render the genuine OS system control. When a semantic `BootstrapType` (`DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, `LINK`) is set, `resolveSemanticBootstrapColor(darkTheme)` and `resolveSemanticBootstrapForeground(darkTheme)` provide the semantic palette colors for tinting the native control (supporting `outline = true/false`).
  - **Strict User CSS Class Isolation**: `addClass`, `removeClass`, `hasClass`, `getClasses()`, and `getClass()` read and mutate exclusively `styleSnapshot.customClasses`. `ComponentBootstrap` (`applyComponentBootstrap`) enum utilities are evaluated directly by the native layout/rendering engines.
  - **`ComponentStyle`, `accentColor`, & `CssCustomCursor`**: `applyComponentStyle(style: ComponentStyle)` applies `backgroundColor`, `backgroundImage` gradients, `color`, `accentColor` (`getEffectiveAccentColor`), `borderColor`, `borderRadius`, `opacity`, `zIndex`, `visibility`, and `cursor` (`setCustomCursor(cursor: CssCursorValue?)`), while silently ignoring unsupported CSS properties.
- **`com.nexustools.genericui.desktop`**:
  - `DesktopBaseContainerNativeImpl`, `DesktopNativeWidget`, `DesktopWindowNativeImpl`, `DesktopApplicationNativeImpl` (managing `IApplication.addStyleSheet`/`removeStyleSheet` and `ThemeMode.AUTO`/`DARK`/`LIGHT`).
- **`com.nexustools.genericui.desktop.container` / `dock`**:
  - `DesktopMultiWindowDockCoordinator`, `DesktopDockWorkspaceNativeImpl`, `DesktopLazyListNativeImpl`, `DesktopLazyGridNativeImpl`, `DesktopTreeViewNativeImpl`.
- **`com.nexustools.genericui.desktop.system` & `image`**:
  - `DesktopConfigDirectoryHelper`, `DesktopUserDirectoryNativeImpl`, `DesktopLibJpegImageCodecImpl` (`cinterop/libjpeg.def`).

See [`../docs/nativeCommon.md`](../docs/nativeCommon.md) for detailed architecture documentation.
