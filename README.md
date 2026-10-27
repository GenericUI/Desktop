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
| [`:desktop:nativeCommon`](nativeCommon/README.md) | `mingwX64`, `macosX64`, `macosArm64`, `linuxX64` | Native C-interop widget/container/window base (`DesktopBaseNativeImpl`, `ComponentStyle`/`ComponentBootstrap` execution, `customClasses` isolation, `resolveSemanticBootstrapColor`, `DesktopMultiWindowDockCoordinator`, `DesktopLibJpegImageCodecImpl`) | [docs/nativeCommon.md](docs/nativeCommon.md) |
| [`:desktop:linuxCommon`](linuxCommon/README.md) | `linuxX64` | Linux XDG Base Directory Specification (`LinuxXdgConfigDirectoryImpl`, `LinuxXdgCacheDirectoryImpl`, `LinuxXdgPreferenceStoreImpl`) | [docs/linuxCommon.md](docs/linuxCommon.md) |
| [`:desktop:windows`](windows/README.md) | `mingwX64` | Win32 `HWND` controls + **`UxTheme`** (`Explorer::BUTTON` / `DarkMode_Explorer::BUTTON`), `WindowsCurCodec` (`.cur` binary synthesis), `WindowsLauncher`, `WindowsResourceResolverImpl` | [docs/windows.md](docs/windows.md) |
| [`:desktop:macos`](macos/README.md) | `macosX64`, `macosArm64` | AppKit `NSWindow`/`NSView`, `MacosCursorTranslator` (`NSImage` + `NSCursor`), `MacosLauncher`, `MacosResourceResolverImpl` | [docs/macos.md](docs/macos.md) |
| [`:desktop:gtk`](gtk/README.md) | `linuxX64` | GTK4 / Cairo / Pango widgets, `GdkCursor` translation, `GtkLauncher`, `GtkResourceResolverImpl` | [docs/gtk.md](docs/gtk.md) |
| [`:desktop:qt`](qt/README.md) | `linuxX64` | Qt6 Widgets / `QMainWindow`, `QCursor` translation, `QtLauncher`, `QtResourceResolverImpl` | [docs/qt.md](docs/qt.md) |
| [`:desktop:swing`](swing/README.md) | `jvm` | Java Swing / AWT `JFrame`/`JComponent`, `Toolkit.createCustomCursor`, `SwingLauncher`, `SwingResourceResolverImpl` | [docs/swing.md](docs/swing.md) |

---

## Native System Control Rendering (`BootstrapType.NONE` Default), Semantic `BootstrapType` Tinting, & CSS Behavior

1. **`BootstrapType.NONE` (`BootstrapType.None`) Default & Zero Bootstrap CSS Styling on Native Desktop Apps**:
   - Desktop applications **never** apply Bootstrap CSS styling to native controls.
   - When `component.getBootstrap().type == BootstrapType.NONE` (the default) and no inline `ComponentStyle` (`component.getStyle()`) background/border is set, each desktop backend renders the **authentic OS system control** for the active `LIGHT` / `DARK` theme:
     - **Windows (`:desktop:windows`)**: Uses Win32 **`UxTheme`** via `Win32DwmUxThemeBridge` (`SetWindowTheme(hwnd, "Explorer", null)` / `OpenThemeData(hwnd, "Explorer::BUTTON")` in `LIGHT` mode and `SetWindowTheme(hwnd, "DarkMode_Explorer", null)` / `OpenThemeData(hwnd, "DarkMode_Explorer::BUTTON")` in `DARK` mode, painting `BP_PUSHBUTTON`, `BP_CHECKBOX`, `BP_RADIOBUTTON`, and `PBS_NORMAL`..`PBS_DEFAULTED` states via `DrawThemeBackground`).
     - **macOS (`:desktop:macos`)**: Renders native AppKit `NSControl` widgets matching `NSApp.effectiveAppearance`.
     - **GTK4 (`:desktop:gtk`) & Qt6 (`:desktop:qt`)**: Render native GTK4 Adwaita and Qt6 `QStyle` widgets matching the desktop color scheme.
     - **Swing (`:desktop:swing`)**: Renders native Look-and-Feel `JComponent` peers.
2. **Semantic `BootstrapType` Tinting**:
   - When a semantic `BootstrapType` (`DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, `LINK`) is set on `component.getBootstrap().type`, all 5 desktop backends emulate `BootstrapType` by **tinting the native component** with the semantic color (`DesktopBaseNativeImpl.resolveSemanticBootstrapColor(darkTheme)` / `BootstrapColorPalette.resolveColor`, e.g. red for `DANGER`, blue for `PRIMARY`, supporting `outline = true/false`).
   - Standard CSS `accentColor` (`component.getStyle().setAccentColor(...)` / `accent-color`) customizes active indicator fills on checkboxes, radio buttons, sliders, and progress bars.
3. **Silent Ignore of Unsupported CSS Properties**:
   - Unsupported CSS properties and CSS transitions on native desktop backends are **silently ignored** (whereas [`:extensions:alien`](../extensions/docs/alien.md) supports 100% of all CSS properties and transitions).

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
