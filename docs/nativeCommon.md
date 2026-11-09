# `:desktop:nativeCommon` — Native C-Interop Desktop Foundation

## Overview
`:desktop:nativeCommon` provides the Kotlin/Native widget, container, window, application, styling, docking, lazy viewport, user directory, and libjpeg foundation for the four native cinterop desktop backends (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, and `:desktop:qt`). It targets `mingwX64()`, `macosX64()`, `macosArm64()`, and `linuxX64()`.

> `:desktop:swing` runs on the JVM and depends on `:desktop:common` directly rather than `:desktop:nativeCommon`.

---

## Styling, `BootstrapType.NONE`, Offscreen Native Sprite Tinting (`DesktopControlTintRenderUtil`), & `DesktopRenderableNativeImpl`

`DesktopBaseNativeImpl` (`com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl`), `DesktopRenderableNativeImpl`, and `DesktopControlTintRenderUtil` (`com.nexustools.genericui.desktop.nativecommon.*`) form the shared rendering and styling foundation across `:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, and `:desktop:qt`:

1. **`BootstrapType.NONE` Default — 100% Unmodified Native OS Controls**:
   - `BootstrapStyleSnapshot` defaults to `type = BootstrapType.NONE`. When `type == BootstrapType.NONE`, native desktop widgets render the 100% unmodified genuine OS control (`UxTheme` on Windows, AppKit on macOS, GTK4 on Linux GTK, Qt6 `QStyle` on Linux Qt) with zero Bootstrap CSS styling.
2. **`BootstrapType != BootstrapType.NONE` — Empty-Text Offscreen Sprite + `DesktopControlTintRenderUtil` + Text Overlay**:
   - When `type != BootstrapType.NONE` (`PRIMARY`, `SECONDARY`, `SUCCESS`, `DANGER`, `WARNING`, `INFO`, `LIGHT`, `DARK`), each native desktop backend renders the native OS control **with NO text** into an offscreen ARGB pixel buffer, calls `DesktopControlTintRenderUtil.tintNativeControlArgbBuffer(...)` to color-tint the native control pixels using the resolved `ThemeConfig` / `BootstrapColorPalette` color while preserving native 3D bevels, borders, specular highlights, and hover/pressed/disabled states (`outline = true/false`), blits the tinted sprite, and renders contrasting text and icons on top afterward.
3. **System Backend Typography vs. Aesthetic Style Restraints**:
   - **Text / Typography Elements (`Label`, `Text`, `Heading`, `Badge`, `Blockquote`, `CodeBlock`, `MarkdownView`)**: Honor typography and text-color properties (`color` / `textColor`, `fontFamily`, `fontSize`, `fontWeight`, `fontStyle`, `lineHeight`, `letterSpacing`, `textAlign`, `textDecoration`, `textTransform`, `textOverflow`, `whiteSpace`, `wordBreak`).
   - **Buttons, Inputs, Controls, & Standard Containers**: Aesthetic skinning and typography overrides on `ComponentStyle` (`backgroundColor`, `background`, `color`, `accentColor`, `borderColor`, `borderWidth`, `borderStyle`, `borderRadius`, `boxShadow`, `fontFamily`, `fontSize`, `fontWeight`, `fontStyle`) are **silently ignored**. Standard components can **ONLY** be tinted via `BootstrapType`.
   - **`StyledContainer` & `StyledButton` (and `:extensions:alien`)**: Honor full `ComponentStyle` aesthetic and typography properties.
4. **Strict User CSS Class Isolation (`customClasses`)**:
   - `setClass`, `getClass()`, `getClasses()`, `addClass`, `removeClass`, and `hasClass` operate strictly on `styleSnapshot.customClasses`.
   - `applyComponentBootstrap(bootstrap: ComponentBootstrap)` updates `styleSnapshot` directly so enum utilities (`BootstrapWidth`, `SpacingScale`, `FlexDirection`, `FlexWrap`, etc.) drive native layout without appearing in `getClasses()`.
5. **`CssCustomCursor` Translation**:
   - `setCustomCursor(cursor: CssCursorValue?)` stores `CssCustomCursor` (PNG `ResourceID`, SVG `ResourceID`/markup, or `ImageBuffer` + `hotspotX`/`hotspotY` + `fallback`) for translation by `WindowsCurCodec` (`.cur` / `HCURSOR`), `MacosCursorTranslator` (`NSImage` / `NSCursor`), `GdkCursor`, or `QCursor`.

---

## Key Classes

| Class | Package | Description |
|-------|---------|-------------|
| `DesktopBaseNativeImpl` | `com.nexustools.genericui.desktop.core` | Base native widget implementation managing `ComponentStyle`, `ComponentBootstrap`, `customClasses` isolation, `CssCustomCursor`, semantic `BootstrapType` tinting, bounds, visibility, focus, and events |
| `DesktopRenderableNativeImpl` | `com.nexustools.genericui.desktop.nativecommon` | Per-component rendering contract (`renderNativeComponent(...)`) implemented by `Windows*NativeImpl`, `Macos*NativeImpl`, `Gtk*NativeImpl`, and `Qt*NativeImpl` classes |
| `DesktopControlTintRenderUtil` | `com.nexustools.genericui.desktop.nativecommon` | Luminance-preserving ARGB offscreen native control pixel tinter (`tintNativeControlArgbBuffer`) used whenever `BootstrapType != BootstrapType.NONE` |
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
