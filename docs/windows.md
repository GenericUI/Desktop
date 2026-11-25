# Windows Native Backend (`:desktop` `mingwMain` / `BackendTarget.Windows`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md) | **Related**: [`nativeCommon.md`](nativeCommon.md) · [`macos.md`](macos.md) · [`../docs/backends.md`](../../docs/backends.md)

## Overview
The **Windows** backend (`mingwX64`) resides in the `mingwMain` source set of `:desktop`. It compiles directly to a native Windows binary using Kotlin/Native C-Interop against Win32 (`user32`, `gdi32`, `comctl32`, `dwmapi`, `uxtheme`, `shell32`, `ole32`, `winhttp`, `ws2_32`), **Direct3D 11 + Direct2D 1.1**, **Google ANGLE**, and **Vulkan (`VK_KHR_win32_surface`)**, built atop `:common`, `:system`, `commonMain`, and `nativeDesktopMain`.

---

## Single-`commonMain` Architecture & `WindowsLauncher`

Consumer applications write UI and `@MainAppFragment` / `@AppFragment` classes in `src/commonMain/kotlin`. `GenericUiGenerateLaunchersTask` automatically generates a `mingwX64Main` launcher delegating to `WindowsLauncher`:

```kotlin
// Auto-generated in build/generated/genericui/launchers/mingwX64Main
fun main(args: Array<String>) {
    val descriptor = GeneratedAppDescriptor.create()
    GenericUI.defaultThemeConfig = descriptor.themeConfig
    // When @MainServiceWorker targets Windows:
    ServiceWorkerExtension.configure(/* generated ServiceWorkerOptions */)
    if (!ServiceWorkerExtension.prepareDesktopMainLaunch(args)) {
        return
    }
    WindowsLauncher.launch(args, descriptor)
}
```

When `styles { globalStyleSheet = "..." }` is set in `build.gradle.kts`, startup attaches the precompiled `PrecompiledStyleSheet` via `app.addStyleSheet(...)` before mounting the root `FragmentHostFragment`.

---

## Pure Retained Child `HWND` Architecture, `ComCtl32 v6`, & 60+Hz `backdrop-filter`

Every mounted component creates a genuine Win32 child `HWND` parented inside nested container `HWND`s (pure retained OS widgets driven by `DesktopContainerLayoutController`, with no monolithic window canvas painter or offscreen bitmap control stamping):

1. **`ComCtl32 v6` Activation, `Win32ControlMessageRouter`, & `Win32LayeredStageHostHwnd`**:
   - Activates **ComCtl32 v6** visual styles natively via `shell32.dll` manifest resource `124` (`ACTCTXW`) **before** `InitCommonControlsEx`, enabling authentic Windows 11/10 visual controls across standalone and bundled builds.
   - `Win32ControlMessageRouter` routes `WM_COMMAND`, `WM_NOTIFY` (including `NM_CUSTOMDRAW`), `WM_CTLCOLOR*`, `WM_HSCROLL`/`WM_VSCROLL`, focus, and input events directly to per-widget `Windows<Component>NativeImpl` instances.
   - `Win32LayeredStageHostHwnd` provides a double-buffered layered child compositor window solving Win32 heavyweight video/surface and overlay airspace issues cleanly.
2. **`Win32Direct2dBackdropBlurEngine` (`StyledComponent`, `StyledButton`, `StyledContainer`, & `Composite`)**:
   - Powered by `StyledWidgetVisualController`, `DesktopStyledWidgetDelegate`, and `Win32Direct2dBackdropBlurEngine` using **Direct3D 11 (`d3d11.dll`) + Direct2D 1.1 (`d2d1.dll`, `ID2D1DeviceContext`)** GPU effect graphs (`CLSID_D2D1GaussianBlur` + `CLSID_D2D1ColorMatrix`) for all 10 `CssFilter` / `backdrop-filter` operations and CSS gradients (`GradientStopNormalizer`), falling back cleanly to `FastBackdropBlurKernel` when hardware D2D device creation is unavailable.
3. **Uniform CSS Cascade, `:system` `Animator` `@keyframes`, & `DesktopContainerLayoutController`**:
   - Opacity inheritance (including `.hidden` overlay fade-in/out and SVG stack waves driven via `style.scss` + `SystemCssAnimationController` / `Animator`), CSS `transform` (`translate(...)`), `z-index` `SetWindowPos` ordering, `pointer-events: none` hit-test exclusion (`WM_NCHITTEST` `HTTRANSPARENT`), and `DesktopContainerLayoutController` + `BrowserLayoutEngine` proximal `position: relative` ancestor (else `Window`) containing-block bounds resolve uniformly with **zero** component/class/id/app-specific fast-paths.

---

## Native `ComCtl32 v6` Controls, `NM_CUSTOMDRAW` Button Styling, & Style Restraints

1. **`BootstrapType.NONE` Default & Non-`Button` Controls**:
   - Applies `SetWindowTheme(hwnd, "Explorer", null)` (`LIGHT`) or `SetWindowTheme(hwnd, "DarkMode_Explorer", null)` (`DARK`) to genuine child `HWND` controls (`SysDateTimePick32`, `ComboBox`, `msctls_progress32`, `msctls_trackbar32`, etc.). Form subclasses (`ComboBox`, `DatePicker`, `TimePicker`, `ColorPicker`, `SearchField`, `NumberField`, `SplitDropdownButton`) stamp `data-control-variant` for specialized Win32 widget selection. Among control components, **`DropdownButton`, `SplitDropdownButton`, `ProgressBar`, `Spinner`, `StyledSlider`, `Composite`, and all other non-`Button` controls MUST NOT support `BootstrapType`**.
2. **`Button` Genuine `"Button"` (`WC_BUTTON`) + `NM_CUSTOMDRAW` (`WindowsButtonNativeImpl`)**:
   - **ONLY `Button` and its subclasses (`Button`, `IconButton`, `ToggleButton`, `FloatingActionButton`, `StyledButton`)** among control components support `BootstrapType`. `WindowsButtonNativeImpl` always mounts a genuine Win32 `"Button"` (`WC_BUTTON`, `BS_PUSHBUTTON`) child `HWND`: it returns `CDRF_DODEFAULT` in `NM_CUSTOMDRAW` when `(type == null || type == BootstrapType.NONE) && !outline` so UxTheme renders the unmodified system push button, and uses `NM_CUSTOMDRAW` (`CDDS_PREPAINT` -> `CDRF_SKIPDEFAULT`) for Bootstrap variants where UxTheme `BP_PUSHBUTTON` lacks a color-tint API (preserving genuine `WC_BUTTON` focus, input, and UIA semantics with zero window class swapping).
3. **Bootstrap Semantic Surfaces & Typography vs. Aesthetic Style Restraints**:
   - Bootstrap non-control classes (`Badge`, `Alert`, `Banner`, `Toast`, `Card`, `ListGroup`, `ListGroupItem`, `Jumbotron`, `Well`) mount custom Win32 child `HWND`s styled via `:system`'s `SemanticSurfaceStyleResolver` (`SemanticSurfacePaintSpec`). Text elements honor `ComponentStyle` typography and color; non-`Button` controls and standard containers ignore both `BootstrapType` and aesthetic overrides; `StyledComponent` / `StyledContainer` / `StyledButton` / `Composite` (`StyledSlider`, `SeekBar`, `ExpandingVolumeSlider`) / `:extensions:ui:alien` honor full CSS/SCSS styling including `backdropFilter` (`Composite` and `StyledSlider` do NOT support `BootstrapType`, and direct Kotlin component styles are forbidden beyond runtime-dynamic `%` values).
4. **Per-Component `Windows*NativeImpl` Peers (`Composite` -> `WindowsStyledContainerNativeImpl`)**: Each component mounts a dedicated retained `Windows<Component>NativeImpl` child `HWND` (`Composite` / `StyledSlider` / `SeekBar` reuse `WindowsStyledContainerNativeImpl` with zero custom `*NativeImpl`).
5. **Strict User CSS Class Isolation**: `styleSnapshot.customClasses` isolates user classes from `ComponentBootstrap` enums.

---

## Prefix-Free Embedded `RT_RCDATA` Resources (`WindowsResourceResolverImpl`)

`WindowsResourceResolverImpl` resolves `ResourceID.nativeResourceKey` (prefix-free key with `"gui/"`, `"gui/ext/<cat>/<ext>/"`, and `"app/"` stripped) from Win32 `RT_RCDATA` sections via `FindResourceW` / `LoadResource` / `LockResource` / `SizeofResource`:
- Checks `<nativeLibraryGroupId>.dll` (`GetModuleHandleW("$groupId.dll")`) for modular extension resource tables, then the host `.exe` (`GetModuleHandleW(null)`).
- Falls back cleanly to legacy numeric `win32ResourceId` (`2000..65535`) and `DefaultResourceResolverNativeImpl`.

---

## `ServiceWorker`, Startup `.lnk`, & `WindowsHeadlessTrayLoop`

- **Secondary `serviceworker.exe` Executable**: `ServiceWorkerExtensionContributor` emits a separate subsystems-`windows` executable (`serviceworker.exe`, entry `GeneratedWindowsServiceWorkerMain.kt`) beside the main app `.exe`.
- **`openWindowOnLaunch = false`**: `ServiceWorkerExtension.prepareDesktopMainLaunch(args)` spawns `serviceworker.exe` (unless `--open-window` / `--settings-only` is passed) and returns early so no main window opens.
- **Singleton Lock, IPC, Autostart, & Headless Tray**:
  - `DesktopOsHelpers` acquires a Win32 named mutex (`CreateMutexW` `"Local\\..."`).
  - `DesktopServiceWorkerHost` serves authenticated IPC over overlapped Win32 Named Pipes (`\\.\pipe\genericui.<userHash>.<scopeHash>.sw`) with `PIPE_REJECT_REMOTE_CLIENTS` and an explicit current-user SID DACL (`serviceworker-endpoint.json` with `transport`, `address`, and 32-byte hex `token`).
  - **Autostart**: `WindowsShortcutWriter` installs `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\<appName>.lnk`.
  - **Headless Pump**: [`WindowsHeadlessTrayLoop`](../src/mingwMain/kotlin/com/nexustools/genericui/windows/WindowsHeadlessTrayLoop.kt) runs `runHeadlessPumpIfActive()` using a hidden `WS_EX_TOOLWINDOW` / `WS_POPUP` HWND so `WindowsSystemTrayNativeImpl` notifications and context menus stay responsive with zero visible windows.

---

## Custom Cursor Synthesis (`WindowsCurCodec`), DWM Theming, & Window Chrome

- **`WindowsCurCodec` & `WindowsCurResourceGenerator`**: Converts PNG, SVG, or `ImageBuffer` `CssCustomCursor`s + hotspots into `.cur` binaries (`ICONDIR`/`ICONDIRENTRY` + 32-bit BGRA DIB + 1-bpp AND mask) and cached Win32 `HCURSOR`s (`WM_SETCURSOR`).
- **`ThemeMode.AUTO` & DWM**: Tracks `AppsUseLightTheme`, `WM_SETTINGCHANGE` (`"ImmersiveColorSet"`), `WM_THEMECHANGED`, `DWMWA_USE_IMMERSIVE_DARK_MODE`, and Windows 11 Mica (`DWMWA_SYSTEMBACKDROP_TYPE`).
- **Window Chrome**: `TitleCapabilities(isTitleBarRequired = true, supportsCustomComponents = true, canEmbedComponentsInTitleBar = true, titleBarRendersAsNavbar = false)` with single-placement `TitleBar` (`WM_NCCALCSIZE` / `WM_NCHITTEST`, `mountedInTitleBar = true`).
