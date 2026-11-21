# Linux Qt6 Native Backend (`:desktop` `linuxMain` / `BackendTarget.Qt`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md) | **Shared Linux Layer**: [`linuxCommon.md`](linuxCommon.md) · **Related**: [`gtk.md`](gtk.md) · [`nativeCommon.md`](nativeCommon.md)

## Overview
The **Linux Qt6** backend (`linuxX64`, `linuxArm64`) lives alongside `Gtk` inside `linuxMain` of `:desktop`, sharing POSIX I/O, XDG directories/preferences, `libcurl` HTTP, POSIX sockets/WebSockets, `libsqlite3`, D-Bus notifications, and embedded ELF resources (`LinuxResourceResolverImpl`) ([`linuxCommon.md`](linuxCommon.md)), while driving Qt6 `QApplication`, `QMainWindow`, `QWidget`, `QStyle`, `QPainter`, `QMenuBar`, `QStatusBar`, `QSystemTrayIcon`, and `QVulkanWindow`.

---

## Single-`commonMain` Architecture & `QtLauncher`

`GenericUiGenerateLaunchersTask` generates the `linuxMain` entry point calling `QtLauncher`:

```kotlin
// Auto-generated in build/generated/genericui/launchers/linuxX64Main
fun main(args: Array<String>) {
    val descriptor = GeneratedAppDescriptor.create()
    GenericUI.defaultThemeConfig = descriptor.themeConfig
    // When @MainServiceWorker targets Qt:
    ServiceWorkerExtension.configure(/* generated ServiceWorkerOptions */)
    if (!ServiceWorkerExtension.prepareDesktopMainLaunch(args)) {
        return
    }
    QtLauncher.launch(args, descriptor)
}
```

When `styles { globalStyleSheet = "..." }` is set, startup automatically registers the precompiled `PrecompiledStyleSheet` via `app.addStyleSheet(...)`.

---

## 60+Hz Hardware `backdrop-filter` (`QtControlRenderUtil` + `Qt6DynamicBindings`)

- `QtStyledContainerNativeImpl` (`StyledContainer`) and `QtStyledButtonNativeImpl` (`StyledButton`) honor full `ComponentStyle` including `backdropFilter` (`TextBubble` strips `backdropFilter` via `withoutBackdropFilter()`).
- `QtControlRenderUtil.applyBackdropFilterRegion` (and `QtWorkspaceLayoutRenderUtil`) samples and filters the `QtRenderContext` `QImage(Format_ARGB32)` surface via `FastBackdropBlurKernel`, while `Qt6DynamicBindings` dynamically resolves Qt6 `QGraphicsBlurEffect` (`hasHardwareBlurEffectSupport()`, `attachWidgetBlurEffect` with `PerformanceHint`).

---

## Native Qt6 Control Rendering, Offscreen Tinting, & Style Restraints

1. **`BootstrapType.NONE` Default**: Renders 100% unmodified genuine Qt6 `QApplication::style()` (`QStyle` / `QPalette`) widgets matching the active `LIGHT` / `DARK` desktop theme.
2. **`BootstrapType != NONE`**: `QtControlRenderUtil` renders the native `QStyle` control (`QStyle::CE_PushButtonBevel`, `PE_PanelButtonCommand`, etc.) **with NO text** into an offscreen `QImage(Format_ARGB32)`, tints pixels via `DesktopControlTintRenderUtil.tintNativeControlArgbBuffer` + `ThemeConfig`, blits the tinted `QImage`, and paints contrasting `QPainter::drawText` labels and icons on top.
3. **Typography vs. Aesthetic Style Restraints**: Text elements honor typography/color; standard controls/containers ignore aesthetic overrides (tint via `BootstrapType` only); `StyledContainer` / `StyledButton` / `:extensions:ui:alien` honor full `ComponentStyle`.
4. **Strict User CSS Class Isolation**: `styleSnapshot.customClasses` remains isolated from `ComponentBootstrap` enums.
5. **Custom Cursors & Theming**: `CssCustomCursor` -> `QImage`/`QPixmap` -> `QCursor(pixmap, hotspotX, hotspotY)`; `ThemeMode.AUTO` queries `QGuiApplication::styleHints()->colorScheme()`.

---

## Window Chrome & Embedded ELF Resources (`LinuxResourceResolverImpl`)

- **Window Chrome**: `QtWindowImpl` reports desktop `TitleCapabilities` (`isTitleBarRequired = true`, `supportsCustomComponents = true`, `canEmbedComponentsInTitleBar = true`, `titleBarRendersAsNavbar = false`) with single-placement custom `TitleBar` components mounted in the `QMainWindow` header (`mountedInTitleBar = true`).
- **Embedded Prefix-Free Resources**: Uses shared [`LinuxResourceResolverImpl`](../src/linuxMain/kotlin/com/nexustools/genericui/linux/LinuxResourceResolverImpl.kt) (ELF `.rodata` `genericui_res_lookup_<group>` / `genericui_res_lookup_app` symbols via `dlsym` inside the executable or `lib<group>.so` + `$exeDir` / XDG / `Resources.activeTable` fallbacks — no external `.rcc` bundle required).

---

## `ServiceWorker`, `QSystemTrayIcon`, & XDG Autostart

- **Secondary `serviceworker` Executable**: Generated via `ServiceWorkerExtensionContributor` (`GeneratedQtServiceWorkerMain.kt`) and staged beside the main executable.
- **Singleton Lock, Autostart, & `openWindowOnLaunch = false`**: Uses POSIX `fcntl` / `flock` locking (`DesktopOsHelpers`), `$XDG_CONFIG_HOME/autostart/<appId>-serviceworker.desktop` autostart files, loopback `127.0.0.1:0` authenticated IPC, and `QtSystemTrayNativeImpl` (`QSystemTrayIcon` + `QApplication.setQuitOnLastWindowClosed(!hasActiveTray())`) so tray services keep running even when no main window is visible.
