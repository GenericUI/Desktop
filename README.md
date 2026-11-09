# `:desktop` — Desktop Backends & Multi-Layered Architecture

## Overview
The `:desktop` module hierarchy provides native and JVM desktop backends for GenericUI (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, `:desktop:qt`, and `:desktop:swing`), built on top of `:common`, `:systemCommon`, `:desktop:common`, `:desktop:nativeCommon`, and `:desktop:linuxCommon`.

---

## Module Hierarchy

```text
:common
  └── :systemCommon
        └── :desktop:common               (All 5 desktop targets: JVM Swing + Native Win32, macOS, GTK, Qt)
              ├── :desktop:swing          (JVM AWT/Swing desktop backend)
              └── :desktop:nativeCommon   (Native C-interop desktop targets: Win32, macOS, GTK, Qt)
                    ├── :desktop:windows  (mingwX64 — Win32 + UxTheme + Direct2D + WindowsCurCodec)
                    ├── :desktop:macos    (macosX64, macosArm64 — AppKit / Cocoa + MacosCursorTranslator)
                    └── :desktop:linuxCommon (linuxX64 — XDG config/cache/preferences)
                          ├── :desktop:gtk   (linuxX64 — GTK4 / Cairo / Pango)
                          └── :desktop:qt    (linuxX64 — Qt6 Widgets / QPainter)
```

| Submodule | Targets | Responsibility | Documentation |
|-----------|---------|----------------|---------------|
| [`:desktop:common`](common/README.md) | `jvm`, `mingwX64`, `macosX64`, `macosArm64`, `linuxX64` | Pure-Kotlin desktop infrastructure (`DesktopLazyViewportEngine`, `DesktopFileBrowserHelper`, dialogs, timers, notifications, system tray) | [docs/common.md](docs/common.md) |
| [`:desktop:nativeCommon`](nativeCommon/README.md) | `mingwX64`, `macosX64`, `macosArm64`, `linuxX64` | Native C-interop widget/container/window base (`DesktopBaseNativeImpl`, `DesktopRenderableNativeImpl`, `DesktopControlTintRenderUtil`, `ComponentStyle`/`ComponentBootstrap` execution, `customClasses` isolation, `resolveSemanticBootstrapColor`, `DesktopMultiWindowDockCoordinator`, `DesktopLibJpegImageCodecImpl`) | [docs/nativeCommon.md](docs/nativeCommon.md) |
| [`:desktop:linuxCommon`](linuxCommon/README.md) | `linuxX64` | Linux XDG Base Directory Specification (`LinuxXdgConfigDirectoryImpl`, `LinuxXdgCacheDirectoryImpl`, `LinuxXdgPreferenceStoreImpl`) | [docs/linuxCommon.md](docs/linuxCommon.md) |
| [`:desktop:windows`](windows/README.md) | `mingwX64` | Win32 `HWND` controls + **`UxTheme`** (`Explorer::BUTTON` / `DarkMode_Explorer::BUTTON`), 36 `Windows*NativeImpl` + 12 `Win32*RenderUtil` classes, `WindowsCurCodec` (`.cur` binary synthesis), `WindowsLauncher`, `WindowsResourceResolverImpl` | [docs/windows.md](docs/windows.md) |
| [`:desktop:macos`](macos/README.md) | `macosX64`, `macosArm64` | AppKit `NSWindow`/`NSView`, `Macos*NativeImpl` + `MacosControlRenderUtil`, `MacosCursorTranslator` (`NSImage` + `NSCursor`), `MacosLauncher`, `MacosResourceResolverImpl` | [docs/macos.md](docs/macos.md) |
| [`:desktop:gtk`](gtk/README.md) | `linuxX64` | GTK4 / Cairo / Pango widgets, `Gtk*NativeImpl` + `GtkControlRenderUtil`, `GdkCursor` translation, `GtkLauncher`, `GtkResourceResolverImpl` | [docs/gtk.md](docs/gtk.md) |
| [`:desktop:qt`](qt/README.md) | `linuxX64` | Qt6 Widgets / `QMainWindow`, `Qt*NativeImpl` + `QtControlRenderUtil`, `QCursor` translation, `QtLauncher`, `QtResourceResolverImpl` | [docs/qt.md](docs/qt.md) |
| [`:desktop:swing`](swing/README.md) | `jvm` | Java Swing / AWT `JFrame`/`JComponent`, `Toolkit.createCustomCursor`, `SwingLauncher`, `SwingResourceResolverImpl` | [docs/swing.md](docs/swing.md) |

---

## Native System Control Rendering (`BootstrapType.NONE`), Empty-Text Offscreen Sprite + `ThemeConfig` Tinting (`BootstrapType != NONE`), & System Style Restraints

1. **`BootstrapType.NONE` (`BootstrapType.None`) Default — 100% Unmodified Native OS Controls**:
   - Desktop applications **never** apply Bootstrap CSS styling to native controls.
   - When `component.getBootstrap().type == BootstrapType.NONE` (the default), each desktop backend renders the **100% unmodified genuine OS system control** for the active `LIGHT` / `DARK` theme:
     - **Windows (`:desktop:windows`)**: Uses Win32 **`UxTheme`** (`uxtheme.dll` / `comctl32.dll` / `user32.dll`) via `Win32DwmUxThemeBridge` (`SetWindowTheme(hwnd, "Explorer", null)` / `OpenThemeData(hwnd, "Explorer::BUTTON")` in `LIGHT` mode and `SetWindowTheme(hwnd, "DarkMode_Explorer", null)` / `OpenThemeData(hwnd, "DarkMode_Explorer::BUTTON")` in `DARK` mode, painting `BP_PUSHBUTTON`, `BP_CHECKBOX`, `BP_RADIOBUTTON`, and `PBS_NORMAL`..`PBS_DEFAULTED` states via `DrawThemeBackground`).
     - **macOS (`:desktop:macos`)**: Renders native AppKit `NSControl` widgets matching `NSApp.effectiveAppearance`.
     - **GTK4 (`:desktop:gtk`) & Qt6 (`:desktop:qt`)**: Render native GTK4 Adwaita and Qt6 `QStyle` widgets matching the desktop color scheme.
     - **Swing (`:desktop:swing`)**: Renders native Look-and-Feel `JComponent` peers.
2. **Semantic `BootstrapType != NONE` Offscreen Sprite + `ThemeConfig` Tint + Contrasting Text Overlay**:
   - When `BootstrapType != BootstrapType.NONE` (`PRIMARY`, `SECONDARY`, `SUCCESS`, `DANGER`, `WARNING`, `INFO`, `LIGHT`, `DARK`), each desktop backend renders the native OS control **with NO text** into an offscreen ARGB pixel buffer, color-tints the native control pixels via `DesktopControlTintRenderUtil.tintNativeControlArgbBuffer` and `ThemeConfig` / `BootstrapColorPalette` while preserving native 3D bevels, borders, specular highlights, and hover/pressed/disabled states, blits the tinted native control, and renders the contrasting text and icon on top afterward.
3. **System Backend Typography vs. Aesthetic Style Restraints**:
   - **Text / Typography Elements (`Label`, `Text`, `Heading`, `Badge`, `Blockquote`, `CodeBlock`, `MarkdownView`)**: Honor typography and text-color properties (`color` / `textColor`, `fontFamily`, `fontSize`, `fontWeight`, `fontStyle`, `lineHeight`, `letterSpacing`, `textAlign`, `textDecoration`, `textTransform`, `textOverflow`, `whiteSpace`, `wordBreak`, plus `bootstrap.textColorType`, `bootstrap.fontSize`, `bootstrap.fontWeight`, `bootstrap.textAlignment`).
   - **Buttons, Inputs, Controls, & Standard Containers (`Button`, `IconButton`, `ToggleButton`, `CheckBox`, `RadioButton`, `Switch`, `Select`, `DropdownButton`, `TextField`, `TextArea`, `RangeSlider`, `ProgressBar`, `Spinner`, `TabContainer`, `Tab`, `Container`, `Card`, etc.)**: Aesthetic skinning and typography overrides on `ComponentStyle` (`backgroundColor`, `background`, `color`, `accentColor`, `borderColor`, `borderWidth`, `borderStyle`, `borderRadius`, `boxShadow`, `textShadow`, `filter`, `backdropFilter`, `fontFamily`, `fontSize`, `fontWeight`, `fontStyle`) are **silently ignored** on desktop backends. Standard components can **ONLY** be tinted via `BootstrapType`.
   - **`StyledContainer` & `StyledButton` (and [`:extensions:alien`](../extensions/docs/alien.md))**: Honor full `ComponentStyle` aesthetic and typography properties across all backends.
4. **Modular Per-Component `*NativeImpl` + Type-Grouped `*RenderUtil` Architecture**:
   - All native desktop backends implement `DesktopRenderableNativeImpl` per component and delegate to type-grouped `*RenderUtil` classes (12 `Win32*RenderUtil` + 36 `Windows*NativeImpl` classes on `:desktop:windows`, `MacosControlRenderUtil` + `Macos*NativeImpl` on `:desktop:macos`, `GtkControlRenderUtil` + `Gtk*NativeImpl` on `:desktop:gtk`, and `QtControlRenderUtil` + `Qt*NativeImpl` on `:desktop:qt`).

---

## Strict User CSS Class Isolation (`customClasses`)

In `DesktopBaseNativeImpl` and `SwingBaseNativeImpl`, `addClass`, `removeClass`, `hasClass`, `getClasses()`, and `getClass()` operate **exclusively** on user-added custom classes (`styleSnapshot.customClasses`). `ComponentBootstrap` (`getBootstrap()`) enum utilities are executed directly by the native desktop layout and rendering engines and never pollute `getClasses()`.

---

## Custom Cursor Translation (`CssCustomCursor`)

- **Windows (`:desktop:windows`)**: `WindowsCurCodec` (at runtime) and `WindowsCurResourceGenerator` (at build time) convert any PNG `ResourceID`, SVG `ResourceID`/markup, or `ImageBuffer` `CssCustomCursor` into a valid **Windows `.cur` binary** (`ICONDIR` with `idType = 2`, `ICONDIRENTRY` with `wXHotSpot` and `wYHotSpot`, 32-bit BGRA DIB + 1-bpp AND mask) and create a cached Win32 `HCURSOR` for `WM_SETCURSOR`.
- **macOS (`:desktop:macos`)**: `MacosCursorTranslator` converts any PNG, SVG, or `ImageBuffer` `CssCustomCursor` into a Retina-aware `@1x`/`@2x` `NSImage` + `NSCursor(image: nsImage, hotSpot: NSMakePoint(hotspotX, hotspotY))`.
- **Linux GTK (`:desktop:gtk`), Linux Qt (`:desktop:qt`), & JVM Swing (`:desktop:swing`)**: Translate `CssCustomCursor` to `GdkCursor`, `QCursor`, and AWT `Toolkit.createCustomCursor`.

---

## Precompiled `.scss` Stylesheets & `IApplication` Theming (`ThemeMode.AUTO`)

- **Build-Time `.scss` Precompilation**: `.scss` files in `src/commonMain/resources/**` generate `ResourceType.STYLE` (`R.style.<name>` / `Resource.style.<name>`) and precompile via `SassKotlinPrecompiler` into `PrecompiledStyleSheet` (`CompiledStyleRule`) objects attached via `IApplication.addStyleSheet(...)` / `removeStyleSheet(...)` (automatically wired in generated launchers when `styles { globalStyleSheet = "..." }` is set).
- **8-Target `ThemeMode.AUTO` System Theme Detection**: `IApplication` defaults to `ThemeMode.AUTO` (`setTheme`, `getTheme`, `getResolvedTheme`) and detects live OS dark/light theme changes across Windows (`AppsUseLightTheme` + `WM_SETTINGCHANGE` + `DWMWA_USE_IMMERSIVE_DARK_MODE`), macOS (`NSApp.effectiveAppearance`), GTK (`gtk-application-prefer-dark-theme`), Qt (`QStyleHints::colorScheme`), and Swing.

---

## Window Chrome & Single-Placement Custom `TitleBar`

All 5 desktop backends report `TitleCapabilities(isTitleBarRequired = true, supportsCustomComponents = true, canEmbedComponentsInTitleBar = true, titleBarRendersAsNavbar = false)`. Custom components added to `TitleBar` (`leadingComponents`, `centerComponents`, `trailingComponents`) are marked with `mountedInTitleBar = true` and rendered **strictly once** inside the window titlebar chrome, never duplicated in the window body.

---

## Bare-Metal Native Async `IFileSystem`, `Database`, & `Network` Across Desktop Backends

| Backend | `FileSystem` & `UserDirectory` (`FileSystemAvailability.Always`) | Native `Database` (`DatabaseQuerySqlTranslator`) | Async `Network` (`TcpClient`, `UdpClient`), `HttpClient`, `WebSocket`, & `IApplication.isOffline` |
| :--- | :--- | :--- | :--- |
| [`:desktop:qt`](qt/README.md) | `QFile` / `QDir` + `$XDG_CONFIG_HOME/<appName>` & `$XDG_CACHE_HOME/<appName>` (`QtConfigDirectoryImpl`, `QtCacheDirectoryImpl`) | **`QtSql`** (`QSqlDatabase` `"QSQLITE"`, `QSqlQuery`) | `QTcpSocket` / `QSslSocket`, `QUdpSocket`, `QNetworkAccessManager`, `QWebSocket`, `QNetworkInformation::reachability()` |
| [`:desktop:gtk`](gtk/README.md) | GIO `GFile` / POSIX + `$XDG_CONFIG_HOME/<appName>` & `$XDG_CACHE_HOME/<appName>` (`GtkConfigDirectoryImpl`, `GtkCacheDirectoryImpl`) | **`libsqlite3`** (`sqlite3_open_v2`, `sqlite3_prepare_v2`) | GIO `GSocketClient` / `GTlsClientConnection`, `GSocket` (`G_SOCKET_TYPE_DATAGRAM`), `libsoup-3.0` (`SoupSession`, `SoupWebsocketConnection`), `GNetworkMonitor` |
| [`:desktop:windows`](windows/README.md) | Win32 `CreateFileW` / `ReadFile` / `WriteFile` + `%APPDATA%\<appName>` & `%LOCALAPPDATA%\<appName>\Cache` (`WindowsConfigDirectoryImpl`, `WindowsCacheDirectoryImpl`) | **`libsqlite3`** (`sqlite3_open_v2`, `sqlite3_prepare_v2`) | WinSock2 (`ws2_32.dll` `SOCK_STREAM` / `SOCK_DGRAM` + Schannel TLS), `WinHTTP` (`WinHttpOpenRequest`, `WinHttpWebSocket*`), COM `INetworkListManager` |
| [`:desktop:macos`](macos/README.md) | `NSFileManager` / `dispatch_io_t` + `~/Library/Application Support/<appName>` & `~/Library/Caches/<appName>` (`MacosConfigDirectoryImpl`, `MacosCacheDirectoryImpl`) | **`libsqlite3`** (`sqlite3_open_v2`, `sqlite3_prepare_v2`) | `Network.framework` (`NWConnection` TCP/TLS & BSD `SOCK_DGRAM`), `NSURLSession` (`NSURLSessionDataTask`, `NSURLSessionWebSocketTask`), `NWPathMonitor` |
| [`:desktop:swing`](swing/README.md) | JVM NIO.2 `AsynchronousFileChannel` / `Files` on `Dispatchers.IO` + `SwingUserDirectoryNativeImpl` | **`org.xerial:sqlite-jdbc`** (`org.sqlite.JDBC` / `PreparedStatement`) | JVM NIO `AsynchronousSocketChannel` / `SSLSocket`, `DatagramChannel`, `java.net.http.HttpClient`, `java.net.http.WebSocket`, `java.net.NetworkInterface` |
