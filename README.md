# `:desktop` — Consolidated Desktop Backends & Multi-Layered SourceSet Architecture

> **Root Repository**: [**GenericUI (`../README.md`)**](../README.md) | **Related Guides**: [Platform Backends (`../docs/backends.md`)](../docs/backends.md) · [SourceSet Hierarchy (`../docs/sourcesets.md`)](../docs/sourcesets.md) · [`:system` (`../system/README.md`)](../system/README.md) · [`:apple` (`../docs/apple.md`)](../docs/apple.md) · [`:jvm` (`../docs/jvm.md`)](../docs/jvm.md)

## Overview
The **`:desktop`** module (`desktop/build.gradle.kts`) consolidates all 5 GenericUI desktop backends (`Windows`, `MacOS`, `Gtk`, `Kde`, and `Swing`) into a single multiplatform KMP project built atop `:common`, `:system`, `:apple` (`macosMain`), and `:jvm` (`jvmMain`).

---

## SourceSet Hierarchy inside `:desktop`

```text
:common
  └── :system
        └── :desktop (commonMain)             — All 5 desktop targets (Swing + Win32, macOS, GTK4, KDE)
              ├── jvmMain                      — JVM AWT/Swing desktop backend (+ :jvm)
              └── nativeDesktopMain            — Native C/C++/ObjC desktop targets (Win32, macOS, GTK4, KDE)
                    ├── mingwMain              — Windows (`mingwX64`: Win32 HWND + ComCtl32 v6 + Direct2D)
                    ├── macosMain              — macOS (`macosArm64`: AppKit NSView + Metal + :apple)
                    └── linuxMain              — Linux (`linuxX64`, `linuxArm64`: shared POSIX/XDG/cURL/style + GTK4 + KDE)
```

| Source Set | Targets | Responsibility | Documentation |
|-----------|---------|----------------|---------------|
| **`commonMain`** (`desktopCommon`) | `jvm`, `mingwX64`, `macosArm64`, `linuxX64`, `linuxArm64` | Pure-Kotlin desktop infrastructure (`DesktopLazyViewportEngine`, `DesktopFileBrowserHelper`, dialogs, timers, notifications, system tray contracts) | [`docs/common.md`](docs/common.md) · [`docs/shared.md`](docs/shared.md) |
| **`nativeDesktopMain`** (`nativeDesktopCommon`) | `mingwX64`, `macosArm64`, `linuxX64`, `linuxArm64` | Shared retained-widget base, layout, and styling controllers (`DesktopBaseNativeImpl`, `DesktopBaseContainerNativeImpl`, `DesktopNativeWidget`, `DesktopContainerLayoutController`, `DesktopStyledWidgetDelegate`, `DesktopButtonNativeImpl`, `DesktopStyledComponentNativeImpl`, `DesktopStyledContainerNativeImpl`, `DesktopStyledButtonNativeImpl`, `DesktopMultiWindowDockCoordinator`, `DesktopLibJpegImageCodecImpl`) | [`docs/nativeCommon.md`](docs/nativeCommon.md) |
| **`linuxMain`** (`linuxCommon` + `linux/style` + `Gtk` + `Kde`) | `linuxX64`, `linuxArm64` | Shared Linux POSIX/XDG/ELF layer (`LinuxXdgConfigDirectoryImpl`, `LinuxXdgCacheDirectoryImpl`, `LinuxXdgPreferenceStoreImpl`, `LinuxResourceResolverImpl`, `LinuxCurlHttpClientNativeImpl`, `LinuxSqliteDatabaseNativeImpl`), toolkit-agnostic `linux/style` helpers (`LinuxStyledSurfaceHelper`, `LinuxBackdropFilterController`), plus strictly isolated **`Gtk`** (`libgenericui-gtk.so`) and **`Kde`** (`libgenericui-kde.so`) UI backends | [`docs/linuxCommon.md`](docs/linuxCommon.md) · [`docs/gtk.md`](docs/gtk.md) · [`docs/kde.md`](docs/kde.md) |
| **`mingwMain`** (**`Windows`**) | `mingwX64` | Real retained child `HWND` tree (`ComCtl32 v6` activated via `shell32.dll` resource `124` before `InitCommonControlsEx`), `Win32ControlMessageRouter`, `Win32LayeredStageHostHwnd` compositor, `WindowsButtonNativeImpl` (`"Button"` / `WC_BUTTON` with `NM_CUSTOMDRAW`), `Win32Direct2dBackdropBlurEngine`, `WindowsCurCodec`, `WindowsResourceResolverImpl` (`RT_RCDATA`), `serviceworker.exe` + `WindowsHeadlessTrayLoop` | [`docs/windows.md`](docs/windows.md) |
| **`macosMain`** (**`MacOS`**) | `macosArm64` | Flipped AppKit `NSView`/`NSControl`/`NSPanel`/`NSPopover`/`NSMenu` retained tree (`MacosWindowRootView` + 4-mode `MacosHostWindow`), `MacosButtonNativeImpl` (native `NSButton`), `MacosSurfaceLayerSupport` (`NSVisualEffectView` blur + `:apple` `AppleStyledSurfaceHelper`, reserving `AppleBackdropFilterController` for non-blur `requiresFullFilterPipeline` ops and offscreen `JestElectricRunner` capture), `MacosCursorTranslator`, `MacosResourceResolverImpl` (Mach-O symbols, zero loose `gui/`/`app/`), `Contents/MacOS/serviceworker` + `Accessory` tray | [`docs/macos.md`](docs/macos.md) |
| **`jvmMain`** (**`Swing`**) | `jvm` | Java Swing / AWT `JFrame`/`JComponent`, `SwingHardwareBackdropBlurRenderer` (`VolatileImage` ping-pong + `:jvm` `JvmBackdropCapturePlanner`), `SwingControlTintRenderUtil` (`:jvm` `JvmControlTintPixelUtil` where Java L&Fs ignore button tinting), `SemanticSurfaceStyleResolver`, `:jvm` `JniLibraryLoader` (`META-INF/native/`), `GeneratedSwingServiceWorkerMain` | [`docs/swing.md`](docs/swing.md) |

---

## Retained Native Control Architecture, Strictly `Button`-Only `BootstrapType`, & 60+Hz Hardware `backdrop-filter`

All four native desktop backends mount **real interactive native OS child widgets directly** inside nested container widgets driven by `DesktopContainerLayoutController` + `BrowserLayoutEngine` — completely eliminating monolithic single-canvas window painting and offscreen bitmap button stampers:

1. **`BootstrapType.NONE` Default & Non-`Button` Controls — 100% Unmodified Native OS Controls**:
   - When `(type == null || type == BootstrapType.NONE) && !outline` on `Button`, and for ALL non-`Button` controls (`DropdownButton`, `SplitDropdownButton`, `ProgressBar`, `Spinner`, `StyledSlider`, `Composite`, `CheckBox`, `RadioButton`, `Switch`, `Select`, `ComboBox`, `DatePicker`, `TimePicker`, `ColorPicker`, `SearchField`, `NumberField`, `TextField`, etc., which **MUST NOT support `BootstrapType`**), every desktop backend mounts genuine unmodified OS widgets matching the active `LIGHT` / `DARK` theme (Win32 `ComCtl32 v6` / `UxTheme` `Explorer` / `DarkMode_Explorer`, AppKit `NSView`/`NSControl`, GTK4/Libadwaita `GtkWidget*`, KDE `KF6` + `Breeze` alien `QWidget*`, Swing L&F). Form subclasses stamp `data-control-variant` so native factories select specialized controls cleanly.
2. **`BootstrapType != BootstrapType.NONE` or `outline == true` on `Button` Subclasses — Native Control Tinting vs. Swing Fallback**:
   - **Among control components, ONLY `Button` and subclasses of `Button` (`Button`, `IconButton`, `ToggleButton`, `FloatingActionButton`, `StyledButton`) support `BootstrapType`.**
   - All 4 native desktop backends (`MacOS`, `Windows`, `Gtk`, `Kde`) retain their genuine OS push button (`NSButton`, Win32 `"Button"` / `WC_BUTTON`, `GtkButton`, KDE `QPushButton` with `Breeze`) across both default (`(type == null || type == BootstrapType.NONE) && !outline`) and semantic Bootstrap modes, updating appearance in place via `DesktopButtonNativeImpl` (`onButtonModeChanged()`): macOS configures `NSButton` `bezelColor`, `hasDestructiveAction`, `bordered`, `showsBorderOnlyWhileMouseInside`, and `contentTintColor`; GTK applies Libadwaita/GTK CSS classes (`suggested-action`, `destructive-action`, `flat`, `gui-btn-*`) and a theme-derived `GtkCssProvider` to `GtkButton`; KDE applies `setFlat` and per-widget `QPalette` styled by `Breeze` on `QPushButton`; and Windows returns `CDRF_DODEFAULT` in `NM_CUSTOMDRAW` when unmodified while using `NM_CUSTOMDRAW` (`CDDS_PREPAINT` -> `CDRF_SKIPDEFAULT`) for Bootstrap variants where UxTheme `BP_PUSHBUTTON` lacks a color-tint API.
   - On `Swing`, `Button` continues using `SwingControlTintRenderUtil` / `:jvm` `JvmControlTintPixelUtil` bevel-preserving sprite tinting (`outline = true/false`) where standard Java Look-and-Feels ignore button background tinting.
   - Non-control Bootstrap semantic surfaces (`Badge`, `Alert`, `Banner`, `Toast`, `Card`, `ListGroup`, `ListGroupItem`, `Jumbotron`, `Well`) mount dedicated custom semantic widgets styled via `:system`'s `SemanticSurfaceStyleResolver` and `SemanticSurfacePaintSpec`.
3. **Typography vs. Aesthetic Style Restraints**:
   - **Text elements** (`Label`, `Text`, `Heading`, `Blockquote`, `CodeBlock`, `MarkdownView`) honor typography and color styles.
   - **Non-`Button` controls & standard containers** (`DropdownButton`, `SplitDropdownButton`, `CheckBox`, `TextField`, `Select`, `ProgressBar`, `Spinner`, `Container`, etc.) MUST NOT support `BootstrapType` and ignore aesthetic overrides (`backgroundColor`, `borderRadius`, `boxShadow`, `backdropFilter`, `filter`); use `Button` subclasses or Bootstrap non-control semantic components for semantic styling.
   - **`StyledComponent`, `StyledContainer`, `StyledButton`, `Composite`, & `StyledSlider` (and [`:extensions:ui:alien`](../extensions/docs/alien.md))**: Mount dedicated custom widgets powered by `:system`'s `StyledWidgetVisualController`, `DesktopStyledWidgetDelegate`, and shared platform helpers honoring full `ComponentStyle` (`style.scss`-first rules, consumer-compiled `GenericUiGeneratedThemeStyleSheet`, VBlank-synced `Animator` CSS `@keyframes` via `SystemCssAnimationController`, `position: absolute` proximal `position: relative` / `Window` containing-block parity, CSS `transform`, `z-index`, `pointer-events: none`, gradients via `GradientStopNormalizer`, and zero component/app fast-paths; `Composite` and `StyledSlider` MUST NOT support `BootstrapType`), including **60+Hz hardware/CPU `backdrop-filter`** with all 10 `CssFilter` ops (`FastBackdropBlurKernel`, `CssFilterColorMatrix`):
     - **Windows**: `Win32Direct2dBackdropBlurEngine` (`D3D11` + `D2D1` `CLSID_D2D1GaussianBlur` / `CLSID_D2D1ColorMatrix` + general per-node opacity/transform inheritance).
     - **macOS**: `MacosSurfaceLayerSupport` + `:apple` (`AppleStyledSurfaceHelper`) mounts native `NSVisualEffectView` (`blendingMode = WithinWindow`, without `CALayer.backgroundFilters`) for `backdrop-filter: blur(...)`, reserving `AppleBackdropFilterController` solely for non-blur W3C filter operations (`requiresFullFilterPipeline`) and offscreen `JestElectricRunner` capture.
     - **Linux GTK4**: Toolkit-agnostic `linux/style` (`LinuxStyledSurfaceHelper`, `LinuxBackdropFilterController`) + GTK4 `GtkOverlay`, `GskTransform`, and GSK/Cairo effects.
     - **Linux KDE**: Toolkit-agnostic `linux/style` (`LinuxStyledSurfaceHelper`, `LinuxBackdropFilterController`) + `KWindowEffects::enableBlurBehind` (`KF6` + `Breeze` on `Qt6`) and `FastBackdropBlurKernel` CPU fallback.
     - **Swing**: `SwingHardwareBackdropBlurRenderer` (`:jvm` `JvmBackdropCapturePlanner` + `JRootPane` root walk + GPU `VolatileImage` bilinear ping-pong `1/2 -> 1/4 -> 1/8` + `FastBackdropBlurKernel` fallback).

---

## Embedded Native Resources & `ServiceWorker` Architecture Across Desktop

- **Embedded Prefix-Free Resources**: `ResourceID.nativeResourceKey` eliminates loose asset directories beside native executables. Windows (`WindowsResourceResolverImpl` reads Win32 `RT_RCDATA`), macOS (`MacosResourceResolverImpl` reads Mach-O `__TEXT,__const` `genericui_res_lookup_*` symbols via `dlsym` inside the signed binary & `Contents/Frameworks/lib<group>.dylib`), Linux (`LinuxResourceResolverImpl` reads ELF `.rodata` `genericui_res_lookup_*` symbols via `dlsym`), and Swing (`SwingResourceResolverImpl` + `:jvm` `JniLibraryLoader` extracting `META-INF/native/<os>-<arch>/` from fat JARs).
- **`ServiceWorker`, Tray, Autostart, & `openWindowOnLaunch = false`**:
  - Generated main entry points call `ServiceWorkerExtension.configure(...)` and `ServiceWorkerExtension.prepareDesktopMainLaunch(args)`; when `openWindowOnLaunch == false` (without `--open-window`/`--settings-only`), the launcher ensures the singleton background worker is spawned and returns without opening a window.
  - **Native targets** emit a secondary `serviceworker` binary (`serviceworker.exe` with `CreateMutexW` + Startup `.lnk` + `WindowsHeadlessTrayLoop`; `Contents/MacOS/serviceworker` with `flock` + `LaunchAgents` plist + `NSApplicationActivationPolicyAccessory` + unwrapped embedded tray icon; Linux `serviceworker` with `fcntl`/`flock` + `.desktop` autostart + `org.kde.StatusNotifierItem` / `KStatusNotifierItem` D-Bus tray), while **Swing** packages `GeneratedSwingServiceWorkerMain` inside the same fat JAR guarded by `FileChannel.tryLock()`.

---

## Strict User CSS Class Isolation, `CssCustomCursor`, `.scss`, & `TitleBar`

- **User CSS Classes**: `addClass`/`removeClass`/`hasClass`/`getClasses()` remain strictly isolated in `styleSnapshot.customClasses`, separate from `ComponentBootstrap` enums.
- **`CssCustomCursor`**: Converts PNG/SVG/raster cursors + hotspots to `.cur` / `HCURSOR` (`WindowsCurCodec`), Retina `NSImage`/`NSCursor` (`MacosCursorTranslator`), `GdkCursor`, `QCursor`, and AWT `Toolkit.createCustomCursor`.
- **`PrecompiledStyleSheet`, `GenericUiGeneratedThemeStyleSheet`, & `ThemeMode.AUTO`**: Prebuilt libraries (`:common`, `:extensions:*`) package only raw `theme.scss` templates; the consumer app's `:gradle-plugin` build compiles `.scss` into `Resource.style.*` and consumer-specific `GenericUiGeneratedThemeStyleSheet` rules, paired with VBlank-synced `SystemCssAnimationController` (`Animator`) `@keyframes` and live OS dark/light theme tracking across all 5 desktop backends.
- **Window Chrome**: Reports `TitleCapabilities(isTitleBarRequired = true, supportsCustomComponents = true, canEmbedComponentsInTitleBar = true, titleBarRendersAsNavbar = false)` with single-placement `TitleBar` (`mountedInTitleBar = true`).

---

## Bare-Metal Native Async `IFileSystem`, `Database`, & `Network` Across Desktop Backends

| Backend | `FileSystem` & `UserDirectory` | Native `Database` (`DatabaseQuerySqlTranslator`) | Async `Network`, `HttpClient`, `WebSocket`, & `IApplication.isOffline` |
| :--- | :--- | :--- | :--- |
| [**`Kde`** & **`Gtk`** (`linuxMain`)](docs/linuxCommon.md) | `LinuxPosixFileSystemNativeImpl` + `$XDG_CONFIG_HOME` / `$XDG_CACHE_HOME` (`LinuxXdgConfigDirectoryImpl`, `LinuxXdgCacheDirectoryImpl`) | **`libsqlite3`** (`LinuxSqliteDatabaseNativeImpl`) | POSIX sockets (`LinuxPosixTcpSocketNativeImpl`, `LinuxPosixUdpSocketNativeImpl`), `libcurl` (`LinuxCurlHttpClientNativeImpl`), `LinuxRfc6455WebSocketNativeImpl`, `GNetworkMonitor` (`LinuxGioNetworkMonitorNativeImpl`) |
| [**`Windows`** (`mingwMain`)](docs/windows.md) | Win32 `CreateFileW` / `ReadFile` / `WriteFile` + `%APPDATA%` / `%LOCALAPPDATA%` | **`libsqlite3`** (`WindowsDatabaseNativeImpl`) | WinSock2 (`SOCK_STREAM` / `SOCK_DGRAM` + Schannel TLS), `WinHTTP` (`WinHttpOpenRequest`, `WinHttpWebSocket*`), COM `INetworkListManager` |
| [**`MacOS`** (`macosMain`)](docs/macos.md) | `:apple` `NSFileManager` / `dispatch_io_t` + `~/Library/Application Support` / `~/Library/Caches` | **`:apple` `libsqlite3`** (`AppleDatabaseNativeImpl`) | `:apple` `Network.framework` (`NWConnection` & BSD UDP), `NSURLSession`, `NSURLSessionWebSocketTask`, `NWPathMonitor` |
| [**`Swing`** (`jvmMain`)](docs/swing.md) | `:jvm` `JvmFileSystemBase` + `SwingUserDirectoryNativeImpl` | **`org.xerial:sqlite-jdbc`** (`SwingDatabaseNativeImpl`) | `:jvm` TCP/UDP sockets, `JvmHttpUrlConnectionClient`, `JvmRfc6455WebSocketNativeImpl`, `java.net.NetworkInterface` |
