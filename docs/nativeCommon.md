# `:desktop:nativeCommon` — Native C-Interop Desktop Foundation

## Overview
`:desktop:nativeCommon` provides the Kotlin/Native widget, container, window, application, styling, docking, lazy viewport, user directory, and libjpeg foundation for the four native cinterop desktop backends (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, and `:desktop:qt`). It targets `mingwX64()`, `macosX64()`, `macosArm64()`, and `linuxX64()`.

> `:desktop:swing` runs on the JVM and depends on `:desktop:common` directly rather than `:desktop:nativeCommon`.

---

## Styling, `BootstrapType.NONE`, User Class Isolation, & Custom Cursors in `DesktopBaseNativeImpl`

`DesktopBaseNativeImpl` (`com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl`) is the base `NativeImpl` across `:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, and `:desktop:qt`:

1. **`BootstrapType.NONE` Default & Semantic `BootstrapType` Tinting**:
   - `BootstrapStyleSnapshot` defaults to `type = BootstrapType.NONE`. When `type == BootstrapType.NONE` and no inline `ComponentStyle` background/border is set, native desktop widgets render the authentic OS control (`UxTheme` on Windows, AppKit on macOS, GTK4 on Linux GTK, Qt6 `QStyle` on Linux Qt) with zero Bootstrap CSS styling.
   - When `type.isSemantic` is `true` (`DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, `LINK`), `resolveSemanticBootstrapColor(darkTheme)` and `resolveSemanticBootstrapForeground(darkTheme)` compute the semantic color used for tinting the native control (including `outline = true/false`).
   - `getEffectiveAccentColor(darkTheme)` resolves `ComponentStyle.accentColor` (`accent-color`) $\rightarrow$ `ComputedStyle.accentColor` $\rightarrow$ `backgroundColor` $\rightarrow$ `resolveSemanticBootstrapColor(darkTheme)`.
2. **Strict User CSS Class Isolation (`customClasses`)**:
   - `setClass`, `getClass()`, `getClasses()`, `addClass`, `removeClass`, and `hasClass` operate strictly on `styleSnapshot.customClasses`.
   - `applyComponentBootstrap(bootstrap: ComponentBootstrap)` updates `styleSnapshot` directly so enum utilities (`BootstrapWidth`, `SpacingScale`, `FlexDirection`, etc.) drive native layout without appearing in `getClasses()`.
3. **`CssCustomCursor` & Silent Ignore of Unsupported CSS Properties**:
   - `setCustomCursor(cursor: CssCursorValue?)` stores `CssCustomCursor` (PNG `ResourceID`, SVG `ResourceID`/markup, or `ImageBuffer` + `hotspotX`/`hotspotY` + `fallback`) for translation by `WindowsCurCodec` (`.cur` / `HCURSOR`), `MacosCursorTranslator` (`NSImage` / `NSCursor`), `GdkCursor`, or `QCursor`.
   - Unsupported CSS properties and transitions in `ComponentStyle` or `PrecompiledStyleSheet` are silently ignored on native desktop controls.

---

## Key Classes

| Class | Package | Description |
|-------|---------|-------------|
| `DesktopBaseNativeImpl` | `com.nexustools.genericui.desktop.core` | Base native widget implementation managing `ComponentStyle`, `ComponentBootstrap`, `customClasses` isolation, `CssCustomCursor`, semantic `BootstrapType` tinting, bounds, visibility, focus, and events |
| `DesktopBaseContainerNativeImpl` | `com.nexustools.genericui.desktop` | Base native container implementation with child widget list management and layout invalidation |
| `DesktopNativeWidget` | `com.nexustools.genericui.desktop` | Common contract for native desktop widgets with pointer/handle access |
| `DesktopWindowNativeImpl` | `com.nexustools.genericui.desktop` | Base desktop window managing `TitleBar`, `MenuBar`, `StatusBar`, `TitleCapabilities`, window state, and single-placement custom titlebar filtering (`mountedInTitleBar`) |
| `DesktopApplicationNativeImpl` | `com.nexustools.genericui.desktop` | Desktop application lifecycle manager, `PrecompiledStyleSheet` registry (`addStyleSheet` / `removeStyleSheet`), and `ThemeMode` (`AUTO`, `DARK`, `LIGHT`) coordination |
| `DesktopMultiWindowDockCoordinator` | `com.nexustools.genericui.desktop.dock` | Coordinates cross-window tear-off, floating dock windows, and dock target drop zones |
| `DesktopDockWorkspaceNativeImpl` | `com.nexustools.genericui.desktop.container` | Split/tabbed docking workspace implementation for native desktop targets |
| `DesktopLazyListNativeImpl` | `com.nexustools.genericui.desktop.container` | Virtualized `LazyList` implementation powered by `DesktopLazyViewportEngine` |
| `DesktopLazyGridNativeImpl` | `com.nexustools.genericui.desktop.container` | Virtualized `LazyGrid` implementation powered by `DesktopLazyViewportEngine` |
| `DesktopTreeViewNativeImpl` | `com.nexustools.genericui.desktop.container` | Hierarchical expandable tree widget implementation |
| `DesktopConfigDirectoryHelper` | `com.nexustools.genericui.desktop.system` | Resolves OS-specific application configuration, cache, and data directories |
| `DesktopUserDirectoryNativeImpl` | `com.nexustools.genericui.desktop.system` | Native `UserDirectory` provider for home, documents, downloads, pictures, music, and videos paths |
| `DesktopLibJpegImageCodecImpl` | `com.nexustools.genericui.desktop.image` | Hardware-accelerated `libjpeg` (`cinterop/libjpeg.def`) JPEG encoder/decoder for `mingwX64`, `linuxX64`, `macosX64`, and `macosArm64` |
