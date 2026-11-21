# `:desktop` — Consolidated Desktop Backends & Multi-Layered SourceSet Architecture

> **Root Repository**: [**GenericUI (`../README.md`)**](../README.md) | **Related Guides**: [Platform Backends (`../docs/backends.md`)](../docs/backends.md) · [SourceSet Hierarchy (`../docs/sourcesets.md`)](../docs/sourcesets.md) · [`:system` (`../system/README.md`)](../system/README.md) · [`:apple` (`../docs/apple.md`)](../docs/apple.md) · [`:jvm` (`../docs/jvm.md`)](../docs/jvm.md)

## Overview
The **`:desktop`** module (`desktop/build.gradle.kts`) consolidates all 5 GenericUI desktop backends (`Windows`, `MacOS`, `Gtk`, `Qt`, and `Swing`) into a single multiplatform KMP project built atop `:common`, `:system`, `:apple` (`macosMain`), and `:jvm` (`jvmMain`).

---

## SourceSet Hierarchy inside `:desktop`

```text
:common
  └── :system
        └── :desktop (commonMain)             — All 5 desktop targets (Swing + Win32, macOS, GTK4, Qt6)
              ├── jvmMain                      — JVM AWT/Swing desktop backend (+ :jvm)
              └── nativeDesktopMain            — Native C/C++/ObjC desktop targets (Win32, macOS, GTK4, Qt6)
                    ├── mingwMain              — Windows (`mingwX64`: Win32 + UxTheme + Direct2D)
                    ├── macosMain              — macOS (`macosArm64`: AppKit + Metal + :apple)
                    └── linuxMain              — Linux (`linuxX64`, `linuxArm64`: shared POSIX/XDG/cURL + GTK4 + Qt6)
```

| Source Set | Targets | Responsibility | Documentation |
|-----------|---------|----------------|---------------|
| **`commonMain`** (`desktopCommon`) | `jvm`, `mingwX64`, `macosArm64`, `linuxX64`, `linuxArm64` | Pure-Kotlin desktop infrastructure (`DesktopLazyViewportEngine`, `DesktopFileBrowserHelper`, dialogs, timers, notifications, system tray contracts) | [`docs/common.md`](docs/common.md) · [`docs/shared.md`](docs/shared.md) |
| **`nativeDesktopMain`** (`nativeDesktopCommon`) | `mingwX64`, `macosArm64`, `linuxX64`, `linuxArm64` | Native C-interop widget/container/window base (`DesktopBaseNativeImpl`, `DesktopRenderableNativeImpl`, `DesktopControlTintRenderUtil`, `DesktopStyledContainerNativeImpl`, `DesktopStyledButtonNativeImpl`, `DesktopMultiWindowDockCoordinator`, `DesktopLibJpegImageCodecImpl`) | [`docs/nativeCommon.md`](docs/nativeCommon.md) |
| **`linuxMain`** (`linuxCommon` + `Gtk` + `Qt`) | `linuxX64`, `linuxArm64` | Shared Linux POSIX/XDG/ELF resources layer (`LinuxXdgConfigDirectoryImpl`, `LinuxXdgCacheDirectoryImpl`, `LinuxXdgPreferenceStoreImpl`, `LinuxResourceResolverImpl`, `LinuxCurlHttpClientNativeImpl`, `LinuxSqliteDatabaseNativeImpl`) plus **`Gtk`** and **`Qt`** UI backends | [`docs/linuxCommon.md`](docs/linuxCommon.md) · [`docs/gtk.md`](docs/gtk.md) · [`docs/qt.md`](docs/qt.md) |
| **`mingwMain`** (**`Windows`**) | `mingwX64` | Win32 `HWND` controls + `UxTheme`, 59 `Windows*NativeImpl` + 12 `Win32*Render*` classes, `Win32Direct2dBackdropBlurEngine`, `WindowsCurCodec`, `WindowsResourceResolverImpl` (`RT_RCDATA`), `serviceworker.exe` + `WindowsHeadlessTrayLoop` | [`docs/windows.md`](docs/windows.md) |
| **`macosMain`** (**`MacOS`**) | `macosArm64` | AppKit `NSWindow`/`NSView`, `MacosControlRenderUtil`, `MacosMetalBackdropBlurEngine`, `MacosCursorTranslator`, `MacosResourceResolverImpl` (Mach-O symbols, zero loose `gui/`/`app/`), `Contents/MacOS/serviceworker` + `Accessory` tray | [`docs/macos.md`](docs/macos.md) |
| **`jvmMain`** (**`Swing`**) | `jvm` | Java Swing / AWT `JFrame`/`JComponent`, `SwingHardwareBackdropBlurRenderer` (`VolatileImage` ping-pong blur), `SwingControlTintRenderUtil` (`:jvm` `JvmControlTintPixelUtil`), `:jvm` `JniLibraryLoader` (`META-INF/native/`), `GeneratedSwingServiceWorkerMain` | [`docs/swing.md`](docs/swing.md) |

---

## Native System Control Rendering (`BootstrapType.NONE`), Offscreen Tinting, & 60+Hz Hardware `backdrop-filter`

1. **`BootstrapType.NONE` Default — 100% Unmodified Native OS Controls**:
   - When `component.getBootstrap().type == BootstrapType.NONE`, every desktop backend renders genuine unmodified OS widgets matching the active `LIGHT` / `DARK` theme (`Win32 UxTheme` `Explorer` / `DarkMode_Explorer`, AppKit `NSControl`, GTK4 Adwaita, Qt6 `QStyle`, Swing L&F).
2. **`BootstrapType != BootstrapType.NONE` — Empty-Text Offscreen Sprite + `ThemeConfig` Tint + Text Overlay**:
   - Native desktop backends render the control **with NO text** into an offscreen ARGB pixel buffer, tint it via `DesktopControlTintRenderUtil.tintNativeControlArgbBuffer` / `JvmControlTintPixelUtil` using `ThemeConfig`, and overlay contrasting text and icons.
3. **Typography vs. Aesthetic Style Restraints**:
   - **Text elements** (`Label`, `Text`, `Heading`, `Badge`, `Blockquote`, `CodeBlock`, `MarkdownView`) honor typography and color styles.
   - **Standard controls & containers** (`Button`, `CheckBox`, `TextField`, `Select`, `Container`, `Card`, etc.) ignore aesthetic overrides (`backgroundColor`, `borderRadius`, `boxShadow`, `backdropFilter`, `filter`); tint only via `BootstrapType`.
   - **`StyledContainer` & `StyledButton` (and [`:extensions:ui:alien`](../extensions/docs/alien.md))**: Honor full `ComponentStyle`, including **60+Hz hardware-accelerated `backdrop-filter`** (`TextBubble` strips `backdropFilter` via `withoutBackdropFilter()`):
     - **Windows**: `Win32Direct2dBackdropBlurEngine` (`D3D11` + `D2D1` `CLSID_D2D1GaussianBlur` / `CLSID_D2D1ColorMatrix` + stage HUD `ExpandingVolumeSlider` wave-alpha sync).
     - **macOS**: `MacosMetalBackdropBlurEngine` (Metal `CIContext` + clamped `CIGaussianBlur` + stage HUD `ExpandingVolumeSlider` wave-alpha sync).
     - **Linux GTK4**: `Gtk4DynamicBindings` (`GSK` blur APIs) + `cairoApplyBackdropFilterRegion` (`FastBackdropBlurKernel`).
     - **Linux Qt6**: `QtControlRenderUtil.applyBackdropFilterRegion` (`FastBackdropBlurKernel` on `QImage(Format_ARGB32)`) + `Qt6DynamicBindings` (`QGraphicsBlurEffect`).
     - **Swing**: `SwingHardwareBackdropBlurRenderer` (`JRootPane` root walk + GPU `VolatileImage` bilinear ping-pong `1/2 -> 1/4 -> 1/8` + headless `FastBackdropBlurKernel` fallback).

---

## Embedded Native Resources & `ServiceWorker` Architecture Across Desktop

- **Embedded Prefix-Free Resources**: `ResourceID.nativeResourceKey` eliminates loose asset directories beside native executables. Windows (`WindowsResourceResolverImpl` reads Win32 `RT_RCDATA`), macOS (`MacosResourceResolverImpl` reads Mach-O `__TEXT,__const` `genericui_res_lookup_*` symbols via `dlsym` inside the signed binary & `Contents/Frameworks/lib<group>.dylib`), Linux (`LinuxResourceResolverImpl` reads ELF `.rodata` `genericui_res_lookup_*` symbols via `dlsym`), and Swing (`SwingResourceResolverImpl` + `:jvm` `JniLibraryLoader` extracting `META-INF/native/<os>-<arch>/` from fat JARs).
- **`ServiceWorker`, Tray, Autostart, & `openWindowOnLaunch = false`**:
  - Generated main entry points call `ServiceWorkerExtension.configure(...)` and `ServiceWorkerExtension.prepareDesktopMainLaunch(args)`; when `openWindowOnLaunch == false` (without `--open-window`/`--settings-only`), the launcher ensures the singleton background worker is spawned and returns without opening a window.
  - **Native targets** emit a secondary `serviceworker` binary (`serviceworker.exe` with `CreateMutexW` + Startup `.lnk` + `WindowsHeadlessTrayLoop`; `Contents/MacOS/serviceworker` with `flock` + `LaunchAgents` plist + `NSApplicationActivationPolicyAccessory` + unwrapped embedded tray icon; Linux `serviceworker` with `fcntl`/`flock` + `.desktop` autostart + `StatusNotifierItem` / `QSystemTrayIcon`), while **Swing** packages `GeneratedSwingServiceWorkerMain` inside the same fat JAR guarded by `FileChannel.tryLock()`.

---

## Strict User CSS Class Isolation, `CssCustomCursor`, `.scss`, & `TitleBar`

- **User CSS Classes**: `addClass`/`removeClass`/`hasClass`/`getClasses()` remain strictly isolated in `styleSnapshot.customClasses`, separate from `ComponentBootstrap` enums.
- **`CssCustomCursor`**: Converts PNG/SVG/raster cursors + hotspots to `.cur` / `HCURSOR` (`WindowsCurCodec`), Retina `NSImage`/`NSCursor` (`MacosCursorTranslator`), `GdkCursor`, `QCursor`, and AWT `Toolkit.createCustomCursor`.
- **`PrecompiledStyleSheet` & `ThemeMode.AUTO`**: Compiles `.scss` into `Resource.style.*` and tracks live OS dark/light theme changes across all 5 desktop backends.
- **Window Chrome**: Reports `TitleCapabilities(isTitleBarRequired = true, supportsCustomComponents = true, canEmbedComponentsInTitleBar = true, titleBarRendersAsNavbar = false)` with single-placement `TitleBar` (`mountedInTitleBar = true`).

---

## Bare-Metal Native Async `IFileSystem`, `Database`, & `Network` Across Desktop Backends

| Backend | `FileSystem` & `UserDirectory` | Native `Database` (`DatabaseQuerySqlTranslator`) | Async `Network`, `HttpClient`, `WebSocket`, & `IApplication.isOffline` |
| :--- | :--- | :--- | :--- |
| [**`Qt`** & **`Gtk`** (`linuxMain`)](docs/linuxCommon.md) | `LinuxPosixFileSystemNativeImpl` + `$XDG_CONFIG_HOME` / `$XDG_CACHE_HOME` (`LinuxXdgConfigDirectoryImpl`, `LinuxXdgCacheDirectoryImpl`) | **`libsqlite3`** (`LinuxSqliteDatabaseNativeImpl`) | POSIX sockets (`LinuxPosixTcpSocketNativeImpl`, `LinuxPosixUdpSocketNativeImpl`), `libcurl` (`LinuxCurlHttpClientNativeImpl`), `LinuxRfc6455WebSocketNativeImpl`, `GNetworkMonitor` (`LinuxGioNetworkMonitorNativeImpl`) |
| [**`Windows`** (`mingwMain`)](docs/windows.md) | Win32 `CreateFileW` / `ReadFile` / `WriteFile` + `%APPDATA%` / `%LOCALAPPDATA%` | **`libsqlite3`** (`WindowsDatabaseNativeImpl`) | WinSock2 (`SOCK_STREAM` / `SOCK_DGRAM` + Schannel TLS), `WinHTTP` (`WinHttpOpenRequest`, `WinHttpWebSocket*`), COM `INetworkListManager` |
| [**`MacOS`** (`macosMain`)](docs/macos.md) | `:apple` `NSFileManager` / `dispatch_io_t` + `~/Library/Application Support` / `~/Library/Caches` | **`:apple` `libsqlite3`** (`AppleDatabaseNativeImpl`) | `:apple` `Network.framework` (`NWConnection` & BSD UDP), `NSURLSession`, `NSURLSessionWebSocketTask`, `NWPathMonitor` |
| [**`Swing`** (`jvmMain`)](docs/swing.md) | `:jvm` `JvmFileSystemBase` + `SwingUserDirectoryNativeImpl` | **`org.xerial:sqlite-jdbc`** (`SwingDatabaseNativeImpl`) | `:jvm` TCP/UDP sockets, `JvmHttpUrlConnectionClient`, `JvmRfc6455WebSocketNativeImpl`, `java.net.NetworkInterface` |
