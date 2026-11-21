# Linux GTK4 Native Backend (`:desktop` `linuxMain` / `BackendTarget.Gtk`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md) | **Shared Linux Layer**: [`linuxCommon.md`](linuxCommon.md) · **Related**: [`qt.md`](qt.md) · [`nativeCommon.md`](nativeCommon.md)

## Overview
The **Linux GTK4** backend (`linuxX64`, `linuxArm64`) lives in `linuxMain` of `:desktop`, sharing POSIX I/O, XDG directories/preferences, `libcurl` HTTP, POSIX sockets/WebSockets, `libsqlite3`, D-Bus notifications, and embedded ELF resources (`LinuxResourceResolverImpl`) with `Qt` ([`linuxCommon.md`](linuxCommon.md)). UI and windowing bind dynamically/via C-Interop to GTK4 / Libadwaita, GDK, Pango, and Cairo.

---

## Single-`commonMain` Architecture & `GtkLauncher`

`GenericUiGenerateLaunchersTask` generates the `linuxMain` entry point calling `GtkLauncher`:

```kotlin
// Auto-generated in build/generated/genericui/launchers/linuxX64Main
fun main(args: Array<String>) {
    val descriptor = GeneratedAppDescriptor.create()
    GenericUI.defaultThemeConfig = descriptor.themeConfig
    // When @MainServiceWorker targets Gtk:
    ServiceWorkerExtension.configure(/* generated ServiceWorkerOptions */)
    if (!ServiceWorkerExtension.prepareDesktopMainLaunch(args)) {
        return
    }
    GtkLauncher.launch(args, descriptor)
}
```

When `styles { globalStyleSheet = "..." }` is set, startup automatically registers the precompiled `PrecompiledStyleSheet` via `app.addStyleSheet(...)`.

---

## 60+Hz Hardware `backdrop-filter` (`Gtk4DynamicBindings` + Cairo GSK Blur)

- `GtkStyledContainerNativeImpl` (`StyledContainer`) and `GtkStyledButtonNativeImpl` (`StyledButton`) honor full `ComponentStyle` including `backdropFilter` (`TextBubble` strips `backdropFilter` via `withoutBackdropFilter()`).
- `GtkControlRenderUtil` calls `Gtk4DynamicBindings.cairoApplyBackdropFilterRegion(...)`. `Gtk4DynamicBindings` resolves GTK4 GSK GPU blur symbols (`hasHardwareGskBlur()`, `applyGskBlurNodeToCairoSurface`, `gtk_snapshot_push_blur`, `gsk_blur_node_new`, `gsk_cairo_node_new`, `gsk_render_node_draw`) and applies `FastBackdropBlurKernel` onto Cairo `CAIRO_FORMAT_ARGB32`/`RGB24` surfaces.

---

## Native GTK4 Control Rendering, Offscreen Tinting, & Style Restraints

1. **`BootstrapType.NONE` Default**: Renders 100% unmodified GTK4 / Adwaita `GtkWidget` peers matching the active `LIGHT` / `DARK` desktop color scheme.
2. **`BootstrapType != NONE`**: `GtkControlRenderUtil` renders the native `GtkWidget` **with NO text** into an offscreen Cairo `CAIRO_FORMAT_ARGB32` surface, tints pixels via `DesktopControlTintRenderUtil.tintNativeControlArgbBuffer` + `ThemeConfig`, blits the tinted sprite, and renders contrasting `PangoLayout` text and icons on top.
3. **Typography vs. Aesthetic Style Restraints**: Text elements honor typography/color; standard controls/containers ignore aesthetic overrides (tint via `BootstrapType` only); `StyledContainer` / `StyledButton` / `:extensions:ui:alien` honor full `ComponentStyle`.
4. **Strict User CSS Class Isolation**: `styleSnapshot.customClasses` remains isolated from `ComponentBootstrap` enums.
5. **Custom Cursors & Theming**: `CssCustomCursor` -> `GdkTexture` -> `gdk_cursor_new_from_texture`; `ThemeMode.AUTO` tracks `GtkSettings` / `org.freedesktop.portal.Settings` `color-scheme`.

---

## Window Chrome & Embedded ELF Resources (`LinuxResourceResolverImpl`)

- **Window Chrome**: `GtkWindowImpl` reports desktop `TitleCapabilities` (`isTitleBarRequired = true`, `supportsCustomComponents = true`, `canEmbedComponentsInTitleBar = true`, `titleBarRendersAsNavbar = false`) with single-placement custom `TitleBar` components mounted inside `GtkHeaderBar` (`mountedInTitleBar = true`).
- **Embedded Prefix-Free Resources**: Uses shared [`LinuxResourceResolverImpl`](../src/linuxMain/kotlin/com/nexustools/genericui/linux/LinuxResourceResolverImpl.kt) (ELF `.rodata` `genericui_res_lookup_<group>` / `genericui_res_lookup_app` symbols via `dlsym` inside the executable or `lib<group>.so` + `$exeDir` / XDG / `Resources.activeTable` fallbacks — no `.gresource` bundle required).

---

## `ServiceWorker`, `StatusNotifierItem` Tray, & XDG Autostart

- **Secondary `serviceworker` Executable**: Generated via `ServiceWorkerExtensionContributor` (`GeneratedGtkServiceWorkerMain.kt`) and staged beside the main executable.
- **Singleton Lock, Autostart, & `openWindowOnLaunch = false`**: Uses POSIX `fcntl` `F_SETLK` / `flock` locking (`DesktopOsHelpers`), XDG `$XDG_CONFIG_HOME/autostart/<appId>-serviceworker.desktop` autostart files, loopback `127.0.0.1:0` authenticated IPC, and `GtkNativeWindowHost.runEventLoop()` kept alive while `GtkSystemTrayNativeImpl.hasActiveTray()` exposes `org.kde.StatusNotifierItem` D-Bus tray icons.
