# `:desktop:windows` — Native Windows Win32 Backend

> **Parent Submodule**: [`:desktop` (`../README.md`)](../README.md) | **Root Repository**: [**GenericUI (`../../README.md`)**](../../README.md)  
> **Root Docs**: [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)  
> **Sibling Desktop Backends**: [`:desktop:shared`](./shared.md) · [`:desktop:macos`](./macos.md) · [`:desktop:gtk`](./gtk.md) · [`:desktop:qt`](./qt.md) · [`:desktop:swing`](./swing.md)

The **`:desktop:windows`** module (`com.nexustools.genericui.windows.*`) is the native Windows (`mingwX64`) backend for **GenericUI**. Built directly on the Win32 C API (`user32.dll`, `gdi32.dll`, `comctl32.dll`, `comdlg32.dll`, `ole32.dll`, `dwmapi.dll`, `windowscodecs.dll`, `shell32.dll`) and [`:desktop:shared`](./shared.md), it compiles to a standalone native `.exe` without a JVM or Electron runtime.

> [!IMPORTANT]
> **Zero VLC or Media Code in `:desktop:windows`**
> `:desktop:windows` contains **NO** VLC (`libvlc`) or media playback code whatsoever. All Windows VLC video/audio decoding (`D3D11VA` / `DXVA2` / `NVDEC`), Vulkan (`#version 450` SPIR-V) & OpenGL ES (`#version 300 es` via Google ANGLE) HDR tone-mapping, and media playback clocks reside strictly in **`:extensions:media:desktop:windows`** and **`:extensions:media:desktop:shared`** ([`../../extensions/docs/media.md`](../../extensions/docs/media.md)).

---

## Core Architecture & Native Capabilities

1. **Native Win32 `HWND` Windowing & Controls (`WindowsNativeFactory`, `Win32NativeWindowHost`, `Win32HwndHandle`)**:
   - Registers custom and standard Win32 window classes (`CreateWindowExW`, `WndProc` message dispatch, Per-Monitor v2 DPI awareness `DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2`, and DWM dark/light immersive titlebars `DWMWA_USE_IMMERSIVE_DARK_MODE`).
   - Translates `TitleBar` instructions (`window.setTitleBar(titleBar)`) into native Win32 window captions, `HMENU` menu bars, and header action buttons.
   - Supports live onscreen automated UI testing via `Win32NativeWindowHost.visualTestMode = true` paired with [`:tests:desktop:windows` (`WindowsVisualAppRunnerNativeImpl`)](../../tests/docs/platform-test-runners.md).
2. **COM Shell File & Folder Dialogs (`WindowsFileBrowserDialogNativeImpl`)**:
   - Uses Vista+ COM `IFileOpenDialog` (`CLSID_FileOpenDialog`) and `IFileSaveDialog` (`CLSID_FileSaveDialog`) with `COMDLG_FILTERSPEC` filter arrays formatted by `DesktopFileBrowserHelper`.
   - Enables native folder picking via `FOS_PICKFOLDERS` when `FileBrowserMode.SELECT_FOLDER` is requested, and multi-selection via `FOS_ALLOWMULTISELECT` for `FileBrowserMode.OPEN_MULTIPLE_FILES`.
3. **Hardware-Accelerated 2D & 3D Canvases**:
   - **`Canvas2D` (`:extensions:canvas2d:desktop:windows`)**: Hardware-accelerated Direct2D (`ID2D1HwndRenderTarget` / `ID2D1DeviceContext`) & DirectWrite vector/text rendering synchronized to VBlank (`DwmFlush` / high-precision QPC timer) with `deltaMs` callbacks.
   - **`GLCanvas` (`:extensions:canvas3d:desktop:windows` + `:extensions:gpu`)**: OpenGL ES 3.0 / 3.1 contexts powered by **Google ANGLE** (`libEGL.dll`, `libGLESv2.dll`, `d3dcompiler_47.dll`), translating OpenGL ES `#version 300 es` shaders directly to Direct3D 11 (`angleBackendRenderer = "d3d11"`), bypassing buggy consumer Windows OpenGL drivers.
   - **`VKCanvas` (`:extensions:canvas3d:desktop:windows` + `:extensions:gpu`)**: Native Vulkan 1.2+ rendering via `VK_KHR_win32_surface` (`vkCreateWin32SurfaceKHR` bound to the widget's `HWND` and `HINSTANCE`).
4. **Native Windows Imaging Component Codec (`WindowsWicImageCodecNativeImpl`)**:
   - Integrates Windows Imaging Component (`IWICImagingFactory`, `IWICBitmapDecoder`, `IWICFormatConverter`) with `:common`'s `ImageLoader` alongside `libjpeg`/`libpng`/`libwebp` for zero-copy `ImageBuffer` decoding and encoding.
5. **Windows `.rc` $\rightarrow$ `.res` Icon & Version Resource Embedding**:
   - The GenericUI Gradle plugin automatically generates a Windows resource script (`.rc`), compiles it via `windres` into a `.res` object, and links the application `.ico` icon (`windowsIcon` / `icons(windows = "icon.ico")`) and `VS_VERSION_INFO` metadata directly into the output `.exe`.

---

## Key Classes Reference

| Class / Object | Responsibility |
| :--- | :--- |
| `WindowsNativeFactory` | `NativeBackendFactory` implementation creating Win32-backed peers and registering `WindowsWicImageCodecNativeImpl`. |
| `Win32HwndHandle` | Extends `DesktopNativeWidget` with native Win32 `hwnd: Long`, `hMenu: Long`, `windowStyleFlags: Int`, and `exStyleFlags: Int`. |
| `Win32NativeWindowHost` | Manages live Win32 `HWND` creation, message loop pumping (`PeekMessageW` / `GetMessageW` / `DispatchMessageW`), DPI scaling, and visual test banner rendering (`visualTestBannerText`). |
| `Win32WinMain` | Entry-point helper initializing COM (`CoInitializeEx`), Per-Monitor v2 DPI awareness, and `WindowsNativeFactory`. |
| `WindowsApplicationNativeImpl` | Top-level Win32 application lifecycle, system theme detection, and message loop execution. |
| `WindowsWindowNativeImpl` | Primary Win32 `HWND` window implementation supporting `TitleBar` instructions, menus, maximize/fullscreen, and VBlank callbacks. |
| `WindowsDockWindowNativeImpl` | Floating tool/utility window (`WS_POPUP` / `WS_OVERLAPPEDWINDOW`) used in GIMP Multi-Window Mode (`StudioWindowMode.MULTI_WINDOW_MODE`). |
| `WindowsFileBrowserDialogNativeImpl` | Native `IFileOpenDialog` / `IFileSaveDialog` implementation supporting files, multi-file selection, save dialogs, and `FOS_PICKFOLDERS` folder selection. |
| `WindowsWicImageCodecNativeImpl` | Hardware-accelerated WIC (`windowscodecs.dll`) implementation of `:common`'s `ImageCodecNativeImpl`. |
| `WindowsSystemTrayNativeImpl` | Win32 Notification Area (`Shell_NotifyIconW` / `NOTIFYICONDATAW`) tray icon and context menu peer. |
| `WindowsNotificationManagerNativeImpl` | Native Windows toast and balloon notification dispatcher. |
| `WindowsNetworkMonitorNativeImpl` | Win32 Network List Manager / `WinInet` connectivity state observer. |
| `WindowsPreferenceStoreImpl` | Native Windows `PreferenceStoreNativeImpl` storing per-key `.bson` binary JSON files (`BinaryJson`) inside `%APPDATA%\<appName>\<key>.bson`. |

---

## SourceSet Hierarchy (`windows` / `windowsMain` / `windowsTest`)

When `windows()` is enabled in `genericui { backends { ... } }`, the Gradle plugin configures the `mingwX64` target and links the **`windows`** sourceSet tier into the hierarchy:

```
commonMain / commonTest
 └── systemCommonMain / systemCommonTest
      └── desktopCommonMain / desktopCommonTest
           └── desktopNativeCommonMain / desktopNativeCommonTest (:desktop:shared)
                └── windowsMain / windowsTest (:desktop:windows)
```

> [!TIP]
> You can place Windows-specific Kotlin code in `src/windowsMain/kotlin` (or `src/windows/kotlin`) and Windows-specific tests in `src/windowsTest/kotlin`. Both inherit all APIs from `desktopNativeCommonMain`, `desktopCommonMain`, `systemCommonMain`, and `commonMain`.

---

## Gradle Configuration, Tasks, & `.rc`/`.res` Icon Embedding

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.nexustools.genericui")
}

genericui {
    backends {
        windows()
    }

    application {
        mainClass = "com.example.app.MainKt"
        appName = "HAEditor Studio"
        appId = "com.nexustools.haeditor"
        version = "2.4.0"
        companyName = "NexusTools"
        copyright = "Copyright (c) 2026 NexusTools"

        // Automatically compiled into a Win32 .rc -> .res object and linked into the .exe:
        windowsIcon("src/commonMain/resources/icons/app-icon.ico")
    }

    graphics {
        // Stages libEGL.dll, libGLESv2.dll, and d3dcompiler_47.dll alongside the Windows executable:
        bundleAngleOnWindows = true
        angleBackendRenderer = "d3d11"
        bundleShaderc = true
    }

    extensions {
        canvas2d() // Direct2D hardware-accelerated 2D canvas
        canvas3d() // Google ANGLE GLCanvas + Vulkan VK_KHR_win32_surface VKCanvas
    }
}
```

### Windows Gradle Tasks

| Task Name | Description |
| :--- | :--- |
| `./gradlew windowsDevelopment` | Compiles the debug `mingwX64` executable, embeds `.rc`/`.res` icon & version resources, stages Google ANGLE DLLs (`libEGL.dll`, `libGLESv2.dll`, `d3dcompiler_47.dll`), and launches the app immediately. |
| `./gradlew windowsProduction` | Compiles the optimized release `mingwX64` executable (`-opt`), links `.res` resources, and packages a self-contained Windows distribution directory. |
| `./gradlew windowsVisualTest` | Runs the `JestElectricRunner` UI test suite using `WindowsVisualAppRunnerNativeImpl`, opening a real onscreen Win32 window and visibly executing UI clicks, typing, and menu actions. |
| `./gradlew mingwX64Test` | Runs standard unit and headless/visual tests on `mingwX64`. |

---

## Code Examples

### 1. Bootstrapping a Native Win32 Application (`Win32WinMain` & `WindowsNativeFactory`)

```kotlin
package com.example.app

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.action.TitleBar
import com.nexustools.genericui.component.container.Container
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.bootstrap.SpacingScale
import com.nexustools.genericui.windows.Win32WinMain

class StudioWin32App : Application(title = "Nexus Studio (Win32 Native)", width = 1280, height = 800) {
    override fun onCreate() {
        // TitleBar is an instruction descriptor applied to the Win32 HWND caption & HMENU
        mainWindow.setTitleBar(
            TitleBar(title = "Nexus Studio", subtitle = "Direct2D + ANGLE + Vulkan")
        )

        val root = Container().apply {
            setFlexDirection(FlexDirection.COLUMN)
            setPaddingAll(SpacingScale.P_4)
            addChild(
                Button("Open Native Win32 Folder Picker").apply {
                    id = "open-folder-btn"
                    setType(BootstrapType.PRIMARY)
                }
            )
        }
        mainWindow.setContent(root)
    }
}

fun main(args: Array<String>) {
    Win32WinMain.launch(args) {
        StudioWin32App()
    }
}
```

### 2. Native `IFileOpenDialog` with `FOS_PICKFOLDERS` & Multi-Format Filters

```kotlin
import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserDialog
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.component.dialog.SelectedFile
import com.nexustools.genericui.enums.dialog.FileBrowserMode

// 1. Open a native Win32 folder picker (IFileOpenDialog with FOS_PICKFOLDERS):
fun pickWorkspaceDirectory(dialog: FileBrowserDialog) {
    dialog.show(
        request = FileBrowserRequest(
            title = "Select Workspace Folder",
            mode = FileBrowserMode.SELECT_FOLDER
        ),
        callback = object : FileBrowserCallback {
            override fun onFilesSelected(files: List<SelectedFile>, activeFilter: FileTypeFilter?) {
                val folder = files.first()
                println("Selected Win32 Directory: ${folder.pathOrUri}")
            }

            override fun onCancelled() {
                println("Folder selection cancelled.")
            }
        }
    )
}
```

### 3. Direct2D `Canvas2D`, Google ANGLE `GLCanvas`, and Win32 Vulkan `VKCanvas`

```kotlin
import com.nexustools.genericui.ext.canvas2d.Canvas2D
import com.nexustools.genericui.ext.canvas3d.GLCanvas
import com.nexustools.genericui.ext.canvas3d.VKCanvas

// Direct2D hardware-accelerated 2D canvas
val direct2dCanvas = Canvas2D(width = 800, height = 600).apply {
    registerVBlankRender { deltaMs ->
        // Synchronized to monitor VBlank via DwmFlush / QPC timer
    }
}

// Google ANGLE (OpenGL ES 3.1 -> Direct3D 11) 3D canvas
val angleGlCanvas = GLCanvas(width = 1280, height = 720, preferES31 = true).apply {
    registerVBlankRender { deltaMs ->
        glContext?.clearColorBuffer(0.06f, 0.08f, 0.12f, 1.0f)
    }
}

// Native Vulkan 1.2+ (VK_KHR_win32_surface) 3D canvas
val win32VulkanCanvas = VKCanvas(width = 1280, height = 720).apply {
    registerVBlankRender { deltaMs ->
        // Record Vulkan command buffer for HWND swapchain image
    }
}
```

---

## Related Documentation

- **Parent Desktop Overview**: [`../README.md`](../README.md)
- **Sibling Desktop Docs**: [`:desktop:shared`](./shared.md) · [`:desktop:macos`](./macos.md) · [`:desktop:gtk`](./gtk.md) · [`:desktop:qt`](./qt.md) · [`:desktop:swing`](./swing.md)
- **Visual Win32 UI Testing**: [Platform Test Runners (`WindowsVisualAppRunnerNativeImpl`)](../../tests/docs/platform-test-runners.md)
- **Media & VLC Extension (`:extensions:media`)**: [`../../extensions/docs/media.md`](../../extensions/docs/media.md)
- **Root GenericUI Documentation**: [Root README](../../README.md) · [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)
- **Sibling Submodules**: [`:mobile`](../../mobile/README.md) · [`:web`](../../web/README.md) · [`:tests`](../../tests/README.md) · [`:NJS`](../../lib/NJS/README.md)
