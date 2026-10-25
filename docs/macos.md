# `:desktop:macos` — Native macOS AppKit Backend

> **Parent Submodule**: [`:desktop` (`../README.md`)](../README.md) | **Root Repository**: [**GenericUI (`../../README.md`)**](../../README.md)  
> **Root Docs**: [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)  
> **Sibling Desktop Backends**: [`:desktop:shared`](./shared.md) · [`:desktop:windows`](./windows.md) · [`:desktop:gtk`](./gtk.md) · [`:desktop:qt`](./qt.md) · [`:desktop:swing`](./swing.md)

The **`:desktop:macos`** module (`com.nexustools.genericui.macos.*`) is the native macOS (`macosArm64` Apple Silicon & `macosX64` Intel) backend for **GenericUI**. Built directly on Apple's **AppKit** framework (`NSApplication`, `NSWindow`, `NSView`, `NSControl`, `NSMenu`, `NSToolbar`, `NSOpenPanel`, `NSSavePanel`), **CoreGraphics**, **ImageIO**, and [`:desktop:shared`](./shared.md), it delivers authentic macOS look-and-feel with zero JVM or Electron overhead.

> [!IMPORTANT]
> **Zero VLC or Media Code in `:desktop:macos`**
> `:desktop:macos` contains **NO** VLC (`libvlc`) or media playback code whatsoever. All macOS VLC video/audio decoding (`VideoToolbox` hardware acceleration), `MoltenVK` Vulkan (`#version 450` SPIR-V) & OpenGL ES HDR tone-mapping, and media playback clocks reside strictly in **`:extensions:media:desktop:macos`** and **`:extensions:media:desktop:shared`** ([`../../extensions/docs/media.md`](../../extensions/docs/media.md)).

---

## Core Architecture & Native Capabilities

1. **AppKit `NSWindow`/`NSView` Hierarchy (`MacosNativeFactory`, `MacosWindowNativeImpl`, `MacosViewHandle`)**:
   - Every GenericUI component maps to a native Objective-C AppKit control (`NSButton`, `NSTextField`, `NSPopUpButton`, `NSSlider`, `NSSplitView`, `NSScrollView`) wrapped by `MacosViewHandle` and `DesktopBaseNativeImpl`.
   - Supports Retina (`backingScaleFactor` 2.0x+) coordinate mapping, dark/light `NSAppearance` (`NSAppearanceNameDarkAqua` / `NSAppearanceNameAqua`), and native macOS window traffic lights.
2. **Global macOS System Menu Bar Integration via `TitleBar` (`NSApplication.sharedApplication.mainMenu`)**:
   - In GenericUI, `TitleBar` is a declarative **instruction class** (never a child widget).
   - When `window.setTitleBar(titleBar)` or `fragment.setNavBar(titleBar)` is called on `:desktop:macos`, `MacosWindowNativeImpl` and `MacosApplicationNativeImpl` automatically populate the **top-of-screen global macOS system menu bar** (`NSApplication.sharedApplication.mainMenu` with `NSMenu` and `NSMenuItem` keyboard equivalents like `Cmd+S`, `Cmd+Shift+Z`) as well as the window's unified `NSToolbar` action items.
3. **Native `NSOpenPanel` & `NSSavePanel` File/Folder Browser (`MacosFileBrowserDialogNativeImpl`)**:
   - Uses `NSOpenPanel` for single/multiple file selection and directory selection (`canChooseDirectories = true`, `canChooseFiles = false` when `FileBrowserMode.SELECT_FOLDER` is specified).
   - Uses `NSSavePanel` for `FileBrowserMode.SAVE_FILE`, applying `UTType` (`UniformTypeIdentifiers`) and extension filters extracted by `DesktopFileBrowserHelper.toMacOsAllowedTypes(request)`.
4. **Hardware-Accelerated 2D & 3D Canvases (`CoreGraphics` & `MoltenVK`)**:
   - **`Canvas2D` (`:extensions:canvas2d:desktop:macos`)**: Hardware-accelerated **CoreGraphics** (`CGContextRef`) & **CoreText** 2D vector/raster canvas driven by `CVDisplayLink` / `CADisplayLink` VBlank callbacks with `deltaMs`.
   - **`VKCanvas` (`:extensions:canvas3d:desktop:macos` + `:extensions:gpu`)**: Vulkan 1.2+ rendering translated directly to **Apple Metal** (`CAMetalLayer`) via **Khronos `MoltenVK`** (`VK_EXT_metal_surface` / `VK_MVK_macos_surface`).
   - **`GLCanvas` (`:extensions:canvas3d:desktop:macos` + `:extensions:gpu`)**: OpenGL / ANGLE Metal-backed context synchronized to the window's `NSView`.
5. **Native Apple `ImageIO` Codec (`MacosImageIoCodecNativeImpl`)**:
   - Uses `CGImageSource` and `CGImageDestination` (`ImageIO.framework`) to decode and encode `PNG`, `JPEG`, `WEBP`, `HEIC`, `TIFF`, `GIF`, and `ICO` directly into `:common`'s `ImageBuffer`.
6. **macOS `.app` Bundle Packaging (`Info.plist` & `.icns`)**:
   - The GenericUI Gradle plugin generates a compliant macOS `.app` bundle structure (`Contents/MacOS/`, `Contents/Resources/`, `Contents/Frameworks/`), writes `Contents/Info.plist` (`CFBundleIdentifier`, `CFBundleName`, `CFBundleIconFile`, `NSHighResolutionCapable`), and copies the configured `.icns` icon (`macIcon("icon.icns")`).

---

## Key Classes Reference

| Class / Object | Responsibility |
| :--- | :--- |
| `MacosNativeFactory` | `NativeBackendFactory` creating AppKit-backed `NativeImpl` peers and registering `MacosImageIoCodecNativeImpl`. |
| `MacosViewHandle` | Extends `DesktopNativeWidget` with Objective-C `nsViewPtr: Long`, `nsLayerPtr: Long`, and Retina `backingScaleFactor: Float`. |
| `MacosApplicationNativeImpl` | Manages `NSApplication.sharedApplication`, activation policy (`NSApplicationActivationPolicyRegular`), global `mainMenu`, and Dock icon/badge state. |
| `MacosWindowNativeImpl` | Wraps `NSWindow` and `NSToolbar`; translates `TitleBar` instructions into the global macOS system menu bar and window toolbar items. |
| `MacosDockWindowNativeImpl` | Floating `NSPanel` (`NSWindowStyleMaskUtilityWindow`) for GIMP Multi-Window Mode (`StudioWindowMode.MULTI_WINDOW_MODE`). |
| `MacosFileBrowserDialogNativeImpl` | Native `NSOpenPanel` and `NSSavePanel` implementation supporting files, multi-selection, save sheets, and folder picking (`canChooseDirectories`). |
| `MacosImageIoCodecNativeImpl` | Apple `ImageIO` (`CGImageSource` / `CGImageDestination`) implementation of `:common`'s `ImageCodecNativeImpl`. |
| `MacosSystemTrayNativeImpl` | macOS menu bar extra (`NSStatusBar.systemStatusBar` / `NSStatusItem`) with native `NSMenu`. |
| `MacosNotificationManagerNativeImpl` | macOS `UNUserNotificationCenter` notification dispatcher. |
| `MacosNetworkMonitorNativeImpl` | Apple `NWPathMonitor` (`Network.framework`) reachability observer. |

---

## SourceSet Hierarchy (`mac` / `macMain` / `macosMain` / `macTest`)

When `macos()` is enabled in `genericui { backends { ... } }`, the Gradle plugin configures `macosArm64` and `macosX64` and wires the **`mac`** sourceSet tier (supporting both `src/macMain` and `src/macosMain` directory conventions):

```
commonMain / commonTest
 └── systemCommonMain / systemCommonTest
      └── desktopCommonMain / desktopCommonTest
           └── desktopNativeCommonMain / desktopNativeCommonTest (:desktop:shared)
                └── macMain (or macosMain) / macTest (:desktop:macos)
```

---

## Gradle Configuration, Tasks, & `.app` Bundle Packaging

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.nexustools.genericui")
}

genericui {
    backends {
        macos() // Configures both macosArm64 (Apple Silicon) and macosX64 (Intel)
    }

    application {
        mainClass = "com.example.app.MainKt"
        appName = "HAEditor Studio"
        appId = "com.nexustools.haeditor"
        version = "2.4.0"

        // Packaged into HAEditor Studio.app/Contents/Resources/icon.icns and referenced in Info.plist:
        macIcon("src/commonMain/resources/icons/icon.icns")
    }

    graphics {
        // Bundles libMoltenVK.dylib inside Contents/Frameworks for Vulkan -> Metal acceleration:
        bundleMoltenVkOnApple = true
        bundleShaderc = true
    }

    extensions {
        canvas2d() // CoreGraphics & CoreText 2D canvas
        canvas3d() // MoltenVK VK_EXT_metal_surface VKCanvas
    }
}
```

### macOS Gradle Tasks

| Task Name | Description |
| :--- | :--- |
| `./gradlew macDevelopment` | Compiles the debug macOS binary for the host architecture (`macosArm64` or `macosX64`), assembles a development `.app` bundle with `Info.plist` and `.icns`, and launches the app. |
| `./gradlew macProduction` | Compiles release binaries, stages `libMoltenVK.dylib`, generates `Contents/Info.plist` and `Contents/Resources/*.icns`, and produces a distributable `.app` bundle. |
| `./gradlew macosTest` | Runs the `JestElectricRunner` UI test suite on macOS using `MacosJestElectricRunnerNativeImpl`. |

---

## Code Examples

### 1. Populating the Global macOS System Menu Bar via `TitleBar`

On `:desktop:macos`, passing a `TitleBar` with menus and actions automatically populates the top-of-screen Apple system menu bar (`NSApplication.sharedApplication.mainMenu`) and the window's `NSToolbar`:

```kotlin
import com.nexustools.genericui.component.action.ActionItem
import com.nexustools.genericui.component.action.Menu
import com.nexustools.genericui.component.action.MenuBar
import com.nexustools.genericui.component.action.MenuItem
import com.nexustools.genericui.component.action.TitleBar
import com.nexustools.genericui.component.shell.Application

class MacStudioApp : Application(title = "HAEditor", width = 1440, height = 900) {
    override fun onCreate() {
        val menuBar = MenuBar().apply {
            addMenu(
                Menu("File").apply {
                    addItem(MenuItem(label = "New Canvas...", shortcut = "Cmd+N") { createNewCanvas() })
                    addItem(MenuItem(label = "Open...", shortcut = "Cmd+O") { openCanvasDialog() })
                    addSeparator()
                    addItem(MenuItem(label = "Export...", shortcut = "Cmd+Shift+E") { exportCanvas() })
                }
            )
            addMenu(
                Menu("View").apply {
                    addItem(MenuItem(label = "Toggle Single/Multi Window Mode") { toggleWindowMode() })
                }
            )
        }

        // On macOS, menuBar is mounted into the global system NSMenu bar at the top of the display,
        // and actions are mounted as native NSToolbarItems in the NSWindow title bar:
        mainWindow.setTitleBar(
            TitleBar(
                title = "hero-artwork.hacanvas",
                subtitle = "100% — RGBA/8",
                menuBar = menuBar,
                actions = listOf(
                    ActionItem(id = "zoom.fit", label = "Fit View") { fitCanvasToViewport() },
                    ActionItem(id = "export.quick", label = "Quick Export") { exportCanvas() }
                )
            )
        )
    }

    private fun createNewCanvas() {}
    private fun openCanvasDialog() {}
    private fun exportCanvas() {}
    private fun toggleWindowMode() {}
    private fun fitCanvasToViewport() {}
}
```

### 2. CoreGraphics `Canvas2D` & `MoltenVK` Metal `VKCanvas`

```kotlin
import com.nexustools.genericui.ext.canvas2d.Canvas2D
import com.nexustools.genericui.ext.canvas3d.VKCanvas

// CoreGraphics 2D canvas synchronized to CVDisplayLink / CADisplayLink VBlank
val cgCanvas = Canvas2D(width = 1024, height = 768).apply {
    registerVBlankRender { deltaMs ->
        clear("#1E1E24")
    }
}

// Vulkan 1.2+ canvas backed by MoltenVK (VK_EXT_metal_surface -> CAMetalLayer)
val metalVkCanvas = VKCanvas(width = 1024, height = 768).apply {
    registerVBlankRender { deltaMs ->
        // Executes SPIR-V shaders natively on Apple Silicon / Metal GPU
    }
}
```

---

## Related Documentation

- **Parent Desktop Overview**: [`../README.md`](../README.md)
- **Sibling Desktop Docs**: [`:desktop:shared`](./shared.md) · [`:desktop:windows`](./windows.md) · [`:desktop:gtk`](./gtk.md) · [`:desktop:qt`](./qt.md) · [`:desktop:swing`](./swing.md)
- **Media & VLC Extension (`:extensions:media`)**: [`../../extensions/docs/media.md`](../../extensions/docs/media.md)
- **Root GenericUI Documentation**: [Root README](../../README.md) · [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)
- **Sibling Submodules**: [`:mobile`](../../mobile/README.md) · [`:web`](../../web/README.md) · [`:tests`](../../tests/README.md) · [`:NJS`](../../lib/NJS/README.md)
