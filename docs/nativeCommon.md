# `:desktop:nativeCommon` — Native C-Interop Desktop Foundation

## Overview
`:desktop:nativeCommon` provides the Kotlin/Native widget, container, window, application, docking, lazy viewport, user directory, and libjpeg foundation for the four native cinterop desktop backends (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, and `:desktop:qt`). It targets `mingwX64()`, `macosX64()`, `macosArm64()`, and `linuxX64()`.

> `:desktop:swing` runs on the JVM and depends on `:desktop:common` directly rather than `:desktop:nativeCommon`.

---

## Key Classes

| Class | Package | Description |
|-------|---------|-------------|
| `DesktopBaseNativeImpl` | `com.nexustools.genericui.desktop` | Base native widget implementation managing bounds, visibility, focus, enabled state, CSS classes, and native handle lifecycle |
| `DesktopBaseContainerNativeImpl` | `com.nexustools.genericui.desktop` | Base native container implementation with child widget list management and layout invalidation |
| `DesktopNativeWidget` | `com.nexustools.genericui.desktop` | Common contract for native desktop widgets with pointer/handle access |
| `DesktopWindowNativeImpl` | `com.nexustools.genericui.desktop` | Base desktop window managing `TitleBar`, `MenuBar`, `StatusBar`, `TitleCapabilities`, window state, and single-placement custom titlebar filtering (`mountedInTitleBar`) |
| `DesktopApplicationNativeImpl` | `com.nexustools.genericui.desktop` | Desktop application lifecycle manager, open window registry, and main run-loop coordination |
| `DesktopMultiWindowDockCoordinator` | `com.nexustools.genericui.desktop.container` | Coordinates cross-window tear-off, floating dock windows, and dock target drop zones |
| `DesktopDockWorkspaceNativeImpl` | `com.nexustools.genericui.desktop.container` | Split/tabbed docking workspace implementation for native desktop targets |
| `DesktopLazyListNativeImpl` | `com.nexustools.genericui.desktop.container` | Virtualized `LazyList` implementation powered by `DesktopLazyViewportEngine` |
| `DesktopLazyGridNativeImpl` | `com.nexustools.genericui.desktop.container` | Virtualized `LazyGrid` implementation powered by `DesktopLazyViewportEngine` |
| `DesktopTreeViewNativeImpl` | `com.nexustools.genericui.desktop.container` | Hierarchical expandable tree widget implementation |
| `DesktopConfigDirectoryHelper` | `com.nexustools.genericui.desktop.system` | Resolves OS-specific application configuration, cache, and data directories |
| `DesktopUserDirectoryNativeImpl` | `com.nexustools.genericui.desktop.system` | Native `UserDirectory` provider for home, documents, downloads, pictures, music, and videos paths |
| `DesktopLibJpegImageCodecImpl` | `com.nexustools.genericui.desktop.image` | Hardware-accelerated `libjpeg` (`cinterop/libjpeg.def`) JPEG encoder/decoder for `mingwX64`, `linuxX64`, `macosX64`, and `macosArm64` |
