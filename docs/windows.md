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

## 60+Hz Hardware `backdrop-filter` & Stage HUD `ExpandingVolumeSlider` Wave-Alpha Sync

1. **`Win32Direct2dBackdropBlurEngine` (`StyledButton` & `StyledContainer`, Excluding `TextBubble`)**:
   - Implemented in [`Win32Direct2dBackdropBlurEngine.kt`](../src/mingwMain/kotlin/com/nexustools/genericui/windows/Win32Direct2dBackdropBlurEngine.kt) and invoked via `Win32NativeWindowHost.applyBackdropFilterRect` from `Win32ContainerChromeRenderUtil` (`StyledContainer`), `Win32ButtonRenderUtil` (`StyledButton`), and `Win32WorkspaceWindowRenderUtil` (stage HUD overlays). (`TextBubble` strips `backdropFilter` via `isTextBubbleComponent()` -> `withoutBackdropFilter()`.)
   - Uses **Direct3D 11 (`d3d11.dll`) + Direct2D 1.1 (`d2d1.dll`, `ID2D1DeviceContext`)** GPU effect graphs (`CLSID_D2D1GaussianBlur` + `CLSID_D2D1ColorMatrix`) to sample and composite underlying window/surface pixels at 60+Hz, falling back cleanly to `FastBackdropBlurKernel` when hardware D2D device creation is unavailable.
2. **Stage HUD `ExpandingVolumeSlider` Wave-Alpha Synchronization**:
   - In `Win32WorkspaceWindowRenderUtil` (`drawExpandingVolumeIconStack` + `resolveVolumeLayerAlpha`), custom data attributes inside `ROLE_EXPANDING_SLIDER_PILL` (`ExpandingVolumeSlider`) — mapping `data-volume-layer` layers (`"speaker"`, `"wave-1"` -> `"data-wave-1-opacity"`, `"wave-2"` -> `"data-wave-2-opacity"`, `"wave-3"` -> `"data-wave-3-opacity"`, `"mute-cross"` -> `"data-mute-cross-opacity"`) — multiply `hudAlpha * resolveVolumeLayerAlpha(...)` so mute cross and animated sound waves fade synchronously with the parent HUD overlay alpha.

---

## Native `UxTheme` Control Rendering, Offscreen Tinting, & Modular `Win32*` / `Windows*` Layout

1. **`BootstrapType.NONE` Default — 100% Unmodified Win32 `UxTheme` Controls**:
   - `Win32DwmUxThemeBridge` applies `SetWindowTheme(hwnd, "Explorer", null)` (`LIGHT`) or `SetWindowTheme(hwnd, "DarkMode_Explorer", null)` (`DARK`) and paints native button parts (`BP_PUSHBUTTON`, `BP_CHECKBOX`, `BP_RADIOBUTTON`, `BP_GROUPBOX`, `BP_COMMANDLINK`) via `OpenThemeData` / `DrawThemeBackground` / `DrawThemeTextEx`.
2. **`BootstrapType != NONE` — Empty-Text Offscreen Sprite + `DesktopControlTintRenderUtil`**:
   - Renders the native `UxTheme` control **with NO text** into an offscreen 32-bit ARGB `CreateDIBSection`, tints via `DesktopControlTintRenderUtil.tintNativeControlArgbBuffer`, blits onto the window DC, and paints contrasting text and icons on top.
3. **Typography vs. Aesthetic Style Restraints**:
   - Text elements honor `ComponentStyle` typography and color; standard controls/containers ignore aesthetic overrides (tint via `BootstrapType` only); `StyledContainer` / `StyledButton` / `:extensions:ui:alien` honor full `ComponentStyle` including `backdropFilter`.
4. **1-Class-Per-File Modular Renderers (`59 Windows*NativeImpl` + `12 Win32*Render*`)**:
   - **12 Render Utilities (`com.nexustools.genericui.windows.render`)**: `Win32RenderContext`, `Win32ControlTintRenderUtil`, `Win32TextRenderUtil`, `Win32ButtonRenderUtil`, `Win32ToggleSelectionRenderUtil`, `Win32InputFieldRenderUtil`, `Win32SliderProgressRenderUtil`, `Win32ContainerChromeRenderUtil`, `Win32NavigationRenderUtil`, `Win32DataDisplayRenderUtil`, `Win32WorkspaceWindowRenderUtil`, and `Win32MediaCanvasRenderUtil`.
   - **59 `Windows*NativeImpl` Classes**: Per-component `Windows<Component>NativeImpl` peers delegating rendering to the `Win32*RenderUtil` classes.
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
  - `DesktopServiceWorkerHost` serves authenticated IPC over `127.0.0.1:0` (`serviceworker-endpoint.json` + 32-byte hex token).
  - **Autostart**: `WindowsShortcutWriter` installs `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\<appName>.lnk`.
  - **Headless Pump**: [`WindowsHeadlessTrayLoop`](../src/mingwMain/kotlin/com/nexustools/genericui/windows/WindowsHeadlessTrayLoop.kt) runs `runHeadlessPumpIfActive()` using a hidden `WS_EX_TOOLWINDOW` / `WS_POPUP` HWND so `WindowsSystemTrayNativeImpl` notifications and context menus stay responsive with zero visible windows.

---

## Custom Cursor Synthesis (`WindowsCurCodec`), DWM Theming, & Window Chrome

- **`WindowsCurCodec` & `WindowsCurResourceGenerator`**: Converts PNG, SVG, or `ImageBuffer` `CssCustomCursor`s + hotspots into `.cur` binaries (`ICONDIR`/`ICONDIRENTRY` + 32-bit BGRA DIB + 1-bpp AND mask) and cached Win32 `HCURSOR`s (`WM_SETCURSOR`).
- **`ThemeMode.AUTO` & DWM**: Tracks `AppsUseLightTheme`, `WM_SETTINGCHANGE` (`"ImmersiveColorSet"`), `WM_THEMECHANGED`, `DWMWA_USE_IMMERSIVE_DARK_MODE`, and Windows 11 Mica (`DWMWA_SYSTEMBACKDROP_TYPE`).
- **Window Chrome**: `TitleCapabilities(isTitleBarRequired = true, supportsCustomComponents = true, canEmbedComponentsInTitleBar = true, titleBarRendersAsNavbar = false)` with single-placement `TitleBar` (`WM_NCCALCSIZE` / `WM_NCHITTEST`, `mountedInTitleBar = true`).
