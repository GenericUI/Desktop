# `:desktop` Native Desktop Common Layer (`nativeDesktopMain` / `nativeDesktopCommon`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md) | **Related**: [`common.md`](common.md) · [`linuxCommon.md`](linuxCommon.md) · [`windows.md`](windows.md) · [`macos.md`](macos.md)

## Overview
The `nativeDesktopMain` source set of `:desktop` (`nativeDesktopCommon` variant scope) provides the Kotlin/Native widget, container, window, application, styling, hardware blur handoff, docking, lazy viewport, user directory, and `libjpeg` foundation shared across the four native C/ObjC desktop backends (`Windows`, `MacOS`, `Gtk`, and `Qt`), targeting `mingwX64()`, `macosArm64()`, `linuxX64()`, and `linuxArm64()`.

> `Swing` (`jvmMain`) runs on the JVM atop `:desktop` `commonMain` and `:jvm` rather than `nativeDesktopMain`.

---

## Styling, `BootstrapType.NONE`, Offscreen Tinting, & 60+Hz `backdrop-filter` (`StyledButton` / `StyledContainer`)

`DesktopBaseNativeImpl` (`com.nexustools.genericui.desktop.core`), `DesktopRenderableNativeImpl`, `DesktopControlTintRenderUtil` (`com.nexustools.genericui.desktop.render`), `DesktopStyledContainerNativeImpl`, and `DesktopStyledButtonNativeImpl` (`com.nexustools.genericui.desktop.widget`) form the shared rendering foundation:

1. **`BootstrapType.NONE` Default — 100% Unmodified Native OS Controls**:
   - `BootstrapStyleSnapshot` defaults to `type = BootstrapType.NONE`. Standard controls render the 100% unmodified genuine OS widget (`UxTheme` on Windows, AppKit on macOS, GTK4 Adwaita on Linux GTK, Qt6 `QStyle` on Linux Qt).
2. **`BootstrapType != BootstrapType.NONE` — Empty-Text Offscreen Sprite + `DesktopControlTintRenderUtil`**:
   - Standard controls render **with NO text** into an offscreen ARGB pixel buffer, call `DesktopControlTintRenderUtil.tintNativeControlArgbBuffer(...)` using `ThemeConfig` / `BootstrapColorPalette` while preserving native bevels and hover/pressed/disabled states (`outline = true/false`), blit the tinted sprite, and overlay contrasting text and icons.
3. **Typography vs. Aesthetic Style Restraints**:
   - **Text elements (`Label`, `Text`, `Heading`, `Badge`, `Blockquote`, `CodeBlock`, `MarkdownView`)** honor `ComponentStyle` typography and text color.
   - **Standard controls & containers** ignore aesthetic skinning overrides on `ComponentStyle`; tint exclusively via `BootstrapType`.
   - **`StyledContainer` & `StyledButton` (and [`:extensions:ui:alien`](../../extensions/docs/alien.md))**: Honor full `ComponentStyle`, including `backdropFilter` routed via `DesktopStyledContainerNativeImpl` / `DesktopStyledButtonNativeImpl` to each backend's 60+Hz blur path (`Win32Direct2dBackdropBlurEngine`, `MacosMetalBackdropBlurEngine`, `Gtk4DynamicBindings` / `cairoApplyBackdropFilterRegion`, `Qt6DynamicBindings` / `QtControlRenderUtil.applyBackdropFilterRegion`), while [`TextBubble`](../../extensions/docs/aichat.md) strips `backdropFilter` via `withoutBackdropFilter()`.
4. **Strict User CSS Class Isolation (`customClasses`)**:
   - `setClass`, `getClass()`, `getClasses()`, `addClass`, `removeClass`, and `hasClass` operate strictly on `styleSnapshot.customClasses`. `ComponentBootstrap` enums drive layout and tinting without polluting `getClasses()`.
5. **`CssCustomCursor` Translation**:
   - `setCustomCursor(cursor: CssCursorValue?)` stores `CssCustomCursor` (`ResourceID`, SVG, or `ImageBuffer` + `hotspotX`/`hotspotY`) for translation by `WindowsCurCodec`, `MacosCursorTranslator`, GTK4 `GdkCursor`, or Qt6 `QCursor`.

---

## Key Classes

| Class | Package | Description |
|-------|---------|-------------|
| `DesktopBaseNativeImpl` | `com.nexustools.genericui.desktop.core` | Base native widget implementation managing `ComponentStyle`, `ComponentBootstrap`, `customClasses` isolation, `CssCustomCursor`, semantic `BootstrapType` tinting, bounds, visibility, focus, and events |
| `DesktopRenderableNativeImpl` | `com.nexustools.genericui.desktop.render` | Per-component rendering contract implemented by backend `Windows*NativeImpl`, `Macos*NativeImpl`, `Gtk*NativeImpl`, and `Qt*NativeImpl` classes |
| `DesktopControlTintRenderUtil` | `com.nexustools.genericui.desktop.render` | Luminance-preserving ARGB offscreen native control pixel tinter (`tintNativeControlArgbBuffer`) used when `BootstrapType != BootstrapType.NONE` |
| `DesktopStyledContainerNativeImpl` | `com.nexustools.genericui.desktop.widget` | Custom-styled container bridge honoring full `ComponentStyle` including `backdropFilter` |
| `DesktopStyledButtonNativeImpl` | `com.nexustools.genericui.desktop.widget` | Custom-styled button bridge honoring full `ComponentStyle` and stateful hover/active/focus/disabled specs including `backdropFilter` |
| `DesktopBaseContainerNativeImpl` | `com.nexustools.genericui.desktop.core` | Base native container implementation with child widget list management and layout invalidation |
| `DesktopNativeWidget` | `com.nexustools.genericui.desktop.core` | Common contract for native desktop widgets with pointer/handle access |
| `DesktopWindowNativeImpl` | `com.nexustools.genericui.desktop.shell` | Base desktop window managing `TitleBar`, `MenuBar`, `StatusBar`, `TitleCapabilities`, window state, and single-placement custom titlebar filtering (`mountedInTitleBar`) |
| `DesktopApplicationNativeImpl` | `com.nexustools.genericui.desktop.shell` | Desktop application lifecycle manager, `PrecompiledStyleSheet` registry (`addStyleSheet` / `removeStyleSheet`), and `ThemeMode` (`AUTO`, `DARK`, `LIGHT`) coordination |
| `DesktopMultiWindowDockCoordinator` | `com.nexustools.genericui.desktop.dock` | Coordinates cross-window tear-off, floating dock windows, and dock target drop zones |
| `DesktopDockWorkspaceNativeImpl` | `com.nexustools.genericui.desktop.dock` | Split/tabbed docking workspace implementation for native desktop targets |
| `DesktopLazyListNativeImpl` / `DesktopTreeViewNativeImpl` | `com.nexustools.genericui.desktop.lazy` | Virtualized `LazyList` and hierarchical `TreeView` implementations powered by `DesktopLazyViewportEngine` |
| `DesktopConfigDirectoryHelper` & `DesktopUserDirectoryNativeImpl` | `com.nexustools.genericui.desktop.system` | OS configuration/cache/user directory resolution |
| `DesktopLibJpegImageCodecImpl` | `com.nexustools.genericui.desktop.image` | Hardware-accelerated `libjpeg` (`cinterop/libjpeg.def`) JPEG encoder/decoder for `mingwX64`, `macosArm64`, `linuxX64`, and `linuxArm64` |
