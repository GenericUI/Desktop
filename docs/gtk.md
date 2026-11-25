# Linux GTK4 Native Backend (`:desktop` `linuxMain` / `BackendTarget.Gtk`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md) | **Shared Linux Layer**: [`linuxCommon.md`](linuxCommon.md) · **Related**: [`kde.md`](kde.md) · [`nativeCommon.md`](nativeCommon.md)

## Overview
The **Linux GTK4** backend (`linuxX64`, `linuxArm64`, `libgenericui-gtk.so`) lives in `linuxMain` of `:desktop`, sharing POSIX I/O, XDG directories/preferences, `libcurl` HTTP, POSIX sockets/WebSockets, `libsqlite3`, D-Bus notifications, embedded ELF resources (`LinuxResourceResolverImpl`), and toolkit-agnostic `linux/style/` helpers with `Kde` ([`linuxCommon.md`](linuxCommon.md)) while keeping `Gtk` and `Kde` symbols strictly isolated across `.so` boundaries. UI and windowing bind dynamically/via C-Interop to GTK4 / Libadwaita, GDK, `Gsk`, Pango, and Cairo using a real retained `GtkWidget*` hierarchy.

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

## Pure Retained `GtkWidget*` Tree, `GtkCustomLayout`, & 60+Hz `backdrop-filter`

- Every mounted component creates a real child `GtkWidget*` / Libadwaita widget directly inside nested parent containers (zero monolithic window canvas painter or offscreen bitmap control stamping). `GtkCustomLayout` paired with `DesktopContainerLayoutController` and `BrowserLayoutEngine` drives bounds, `GskTransform` CSS transforms, `GtkOverlay` / `GtkPopover` layering, `z-index` ordering, and `pointer-events: none` click-through.
- `GtkStyledComponentNativeImpl` (`StyledComponent`), `GtkStyledContainerNativeImpl` (`StyledContainer`, plus `Composite` / `StyledSlider` / `SeekBar` with zero custom `SeekBarNativeImpl`), and `GtkStyledButtonNativeImpl` (`StyledButton`) mount custom `GtkWidget` subclasses powered by `StyledWidgetVisualController`, `DesktopStyledWidgetDelegate`, and toolkit-agnostic `LinuxStyledSurfaceHelper` + `LinuxBackdropFilterController` (`style.scss` + consumer-compiled `GenericUiGeneratedThemeStyleSheet`, `SystemCssAnimationController` `Animator` `@keyframes`, `GradientStopNormalizer`, and all 10 `CssFilter` ops via GTK4 `Gtk4DynamicBindings` GSK GPU blur / Cairo `FastBackdropBlurKernel` fallback).

---

## Native GTK4 Controls, Native `GtkButton` CSS Styling, & Style Restraints

1. **`BootstrapType.NONE` Default & Non-`Button` Controls**: Mounts 100% unmodified GTK4 / Libadwaita `GtkWidget*` peers matching the active `LIGHT` / `DARK` desktop color scheme. Form subclasses (`ComboBox`, `DatePicker`, `TimePicker`, `ColorPicker`, `SearchField`, `NumberField`, `SplitDropdownButton`) stamp `data-control-variant` for specialized GTK4/Adwaita widget selection; **`DropdownButton`, `SplitDropdownButton`, `ProgressBar`, `Spinner`, `StyledSlider`, `Composite`, and all other non-`Button` controls MUST NOT support `BootstrapType`**.
2. **`Button` Genuine `GtkButton` CSS Styling (`GtkButtonNativeImpl`)**: Among controls, **ONLY `Button` and its subclasses (`Button`, `IconButton`, `ToggleButton`, `FloatingActionButton`, `StyledButton`)** support `BootstrapType`. `GtkButtonNativeImpl` always mounts a genuine native `GtkButton`, leaving it unmodified when `(type == null || type == BootstrapType.NONE) && !outline` and applying Libadwaita/GTK CSS classes (`suggested-action`, `destructive-action`, `flat`, `pill`, `gui-btn-*`) backed by a theme-derived `GtkCssProvider` in place when `BootstrapType != NONE` or `outline == true`.
3. **Bootstrap Semantic Surfaces & Typography vs. Aesthetic Style Restraints**: Bootstrap non-control surfaces (`Badge`, `Alert`, `Banner`, `Toast`, `Card`, `ListGroup`, `ListGroupItem`, `Jumbotron`, `Well`) mount custom semantic widgets styled via `:system`'s `SemanticSurfaceStyleResolver` (`SemanticSurfacePaintSpec`). Text elements honor typography/color; non-`Button` controls and standard containers ignore both `BootstrapType` and aesthetic overrides; `StyledComponent` / `StyledContainer` / `StyledButton` / `Composite` (`StyledSlider`, `SeekBar`, `ExpandingVolumeSlider`) / `:extensions:ui:alien` honor full CSS/SCSS styling (`Composite` and `StyledSlider` do NOT support `BootstrapType`, and direct Kotlin component styles are forbidden beyond runtime-dynamic `%` values).
4. **Strict User CSS Class Isolation**: `styleSnapshot.customClasses` remains isolated from `ComponentBootstrap` enums.
5. **Custom Cursors & Theming**: `CssCustomCursor` -> `GdkTexture` -> `gdk_cursor_new_from_texture`; `ThemeMode.AUTO` tracks `GtkSettings` / `org.freedesktop.portal.Settings` `color-scheme`.

---

## Window Chrome & Embedded ELF Resources (`LinuxResourceResolverImpl`)

- **Window Chrome**: `GtkWindowImpl` reports desktop `TitleCapabilities` (`isTitleBarRequired = true`, `supportsCustomComponents = true`, `canEmbedComponentsInTitleBar = true`, `titleBarRendersAsNavbar = false`) with single-placement custom `TitleBar` components mounted inside `GtkHeaderBar` (`mountedInTitleBar = true`).
- **Embedded Prefix-Free Resources**: Uses shared [`LinuxResourceResolverImpl`](../src/linuxMain/kotlin/com/nexustools/genericui/linux/LinuxResourceResolverImpl.kt) (ELF `.rodata` `genericui_res_lookup_<group>` / `genericui_res_lookup_app` symbols via `dlsym` inside the executable or `lib<group>.so` + `$exeDir` / XDG / `Resources.activeTable` fallbacks — no `.gresource` bundle required).

---

## `ServiceWorker`, `StatusNotifierItem` Tray, & XDG Autostart

- **Secondary `serviceworker` Executable**: Generated via `ServiceWorkerExtensionContributor` (`GeneratedGtkServiceWorkerMain.kt`) and staged beside the main executable.
- **Singleton Lock, Autostart, & `openWindowOnLaunch = false`**: Uses POSIX `fcntl` `F_SETLK` / `flock` locking (`DesktopOsHelpers`), XDG `$XDG_CONFIG_HOME/autostart/<appId>-serviceworker.desktop` autostart files, authenticated native IPC over pure kernel-native filesystem `AF_UNIX` `SOCK_STREAM` (`$XDG_RUNTIME_DIR/<appId>/sw.sock` or `/tmp/genericui-<euid>/`, `0700`/`0600` permissions, `SO_PEERCRED` UID verification, `MSG_NOSIGNAL`, stale socket recovery, zero `GDBus`/D-Bus IPC; `serviceworker-endpoint.json` with `transport`, `address`, and 32-byte hex `token`), and `GtkNativeWindowHost.runEventLoop()` kept alive while `GtkSystemTrayNativeImpl.hasActiveTray()` exposes `org.kde.StatusNotifierItem` D-Bus tray icons.
