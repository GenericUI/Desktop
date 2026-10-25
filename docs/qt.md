# `:desktop:qt` — Native Linux Qt6 Backend

> **Parent Submodule**: [`:desktop` (`../README.md`)](../README.md) | **Root Repository**: [**GenericUI (`../../README.md`)**](../../README.md)  
> **Root Docs**: [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)  
> **Sibling Desktop Backends**: [`:desktop:shared`](./shared.md) · [`:desktop:windows`](./windows.md) · [`:desktop:macos`](./macos.md) · [`:desktop:gtk`](./gtk.md) · [`:desktop:swing`](./swing.md)

The **`:desktop:qt`** module (`com.nexustools.genericui.qt.*`) is the native C++ Qt6 (`linuxX64` & `linuxArm64`) backend for **GenericUI**. Built on **Qt6 Widgets & GUI** (`QApplication`, `QMainWindow`, `QMenuBar`, `QToolBar`, `QDockWidget`, `QWidget`, `QFileDialog`, `QPainter`, `QOpenGLWidget`, `QVulkanWindow`, `QImage`) and [`:desktop:shared`](./shared.md), it provides first-class integration for KDE Plasma and Qt-based Linux workstations.

> [!IMPORTANT]
> **Zero VLC or Media Code in `:desktop:qt`**
> `:desktop:qt` contains **NO** VLC (`libvlc`) or media playback code whatsoever. All Linux Qt VLC video/audio decoding (`VAAPI` / `VDPAU` / `NVDEC`), Vulkan (`#version 450` SPIR-V) & OpenGL ES (`#version 300 es`) HDR tone-mapping, and media playback clocks reside strictly in **`:extensions:media:desktop:qt`** and **`:extensions:media:desktop:shared`** ([`../../extensions/docs/media.md`](../../extensions/docs/media.md)).

---

## Core Architecture & Native Capabilities

1. **Native Qt6 Widget Peers (`QtNativeFactory`, `QtWindowNativeImpl`, `QtWidgetHandle`)**:
   - Maps GenericUI widgets directly to native Qt6 controls (`QMainWindow`, `QPushButton`, `QLineEdit`, `QComboBox`, `QCheckBox`, `QSlider`, `QProgressBar`, `QSplitter`, `QTabWidget`, `QTableWidget`).
   - Translates `TitleBar` instructions (`window.setTitleBar(titleBar)`) into a `QMainWindow` window title, native `QMenuBar` (`QMenu` / `QAction`), and `QToolBar` action buttons.
   - Maps GenericUI's Dual-Mode Bootstrap 5 enums and compiled SASS/CSS rules to Qt Style Sheets (`QSS` via `QWidget::setStyleSheet`) and `QPalette`.
2. **Native Docking & Multi-Window Coordinator (`QtDockWindowNativeImpl`)**:
   - Integrates `QMainWindow` and `QDockWidget` with `:desktop:shared`'s `DesktopMultiWindowDockCoordinator` for seamless runtime toggling between Single-Window Mode (`StudioWindowMode.SINGLE_WINDOW_MODE`) and floating Multi-Window Mode (`StudioWindowMode.MULTI_WINDOW_MODE`).
3. **Native `QFileDialog` File & Folder Browser (`QtFileBrowserDialogNativeImpl`)**:
   - Uses `QFileDialog` with filter strings formatted by `DesktopFileBrowserHelper.toQtFilterString(request)` (e.g. `"Images (*.png *.jpg);;All Files (*)"`):
     - `QFileDialog::ExistingFile` (`FileBrowserMode.OPEN_SINGLE_FILE`)
     - `QFileDialog::ExistingFiles` (`FileBrowserMode.OPEN_MULTIPLE_FILES`)
     - `QFileDialog::AnyFile` + `AcceptSave` (`FileBrowserMode.SAVE_FILE`)
     - `QFileDialog::Directory` + `ShowDirsOnly` (`FileBrowserMode.SELECT_FOLDER`)
4. **Hardware-Accelerated 2D & 3D Canvases (`QPainter`, `QOpenGLWidget`, `QVulkanWindow`)**:
   - **`Canvas2D` (`:extensions:canvas2d:desktop:qt`)**: Hardware-accelerated **`QPainter`** vector/raster surface synchronized to display VBlank (`QWindow::requestUpdate` / `QTimer` high-resolution frame clock) with `deltaMs`.
   - **`GLCanvas` (`:extensions:canvas3d:desktop:qt` + `:extensions:gpu`)**: Native **`QOpenGLWidget`** configured with `QSurfaceFormat::OpenGLES` (OpenGL ES 3.0 / 3.1).
   - **`VKCanvas` (`:extensions:canvas3d:desktop:qt` + `:extensions:gpu`)**: Native **`QVulkanWindow`** / `QVulkanInstance` Vulkan 1.2+ surface embedded via `QWidget::createWindowContainer`.
5. **Native `QImage` Image Codec (`QtQImageCodecNativeImpl`)**:
   - Bridges Qt6's `QImage` and `QImageReader` / `QImageWriter` with `:common`'s `ImageLoader` for fast native image decoding and encoding.

---

## Key Classes Reference

| Class / Object | Responsibility |
| :--- | :--- |
| `QtNativeFactory` | `NativeBackendFactory` creating Qt6-backed `NativeImpl` peers and registering `QtQImageCodecNativeImpl`. |
| `QtWidgetHandle` | Extends `DesktopNativeWidget` with native `qWidgetPtr: Long`, `qLayoutPtr: Long`, and active `qStyleSheet: String`. |
| `QtApplicationNativeImpl` | Manages `QApplication`, High-DPI scaling policy, and global `QSS` stylesheet application. |
| `QtWindowNativeImpl` | Wraps `QMainWindow`, applying `TitleBar` instructions to `QMenuBar` and `QToolBar`, and driving VBlank render callbacks. |
| `QtDockWindowNativeImpl` | Floating `QDockWidget` / utility `QWidget` window managed by `DesktopMultiWindowDockCoordinator`. |
| `QtFileBrowserDialogNativeImpl` | Native `QFileDialog` implementation for files, multi-selection, save dialogs, and directory selection. |
| `QtQImageCodecNativeImpl` | Native `QImage` implementation of `:common`'s `ImageCodecNativeImpl`. |
| `QtSystemTrayNativeImpl` | Native `QSystemTrayIcon` and `QMenu` system tray implementation. |
| `QtNotificationManagerNativeImpl` | Desktop notification dispatcher via `QSystemTrayIcon::showMessage` and Freedesktop DBus notifications. |
| `QtNetworkMonitorNativeImpl` | Qt6 `QNetworkInformation` reachability observer. |

---

## SourceSet Hierarchy (`linuxCommon` / `linuxMain` $\rightarrow$ `qt` / `qtMain`)

When `qt()` is enabled in `genericui { backends { ... } }`, the Gradle plugin wires **both** the shared Linux desktop sourceSet (`linuxCommon` / `linuxMain` / `linuxTest`, shared with `:desktop:gtk`) and the Qt-specific leaf sourceSet (`qt` / `qtMain` / `qtTest`):

```
commonMain / commonTest
 └── systemCommonMain / systemCommonTest
      └── desktopCommonMain / desktopCommonTest
           └── desktopNativeCommonMain / desktopNativeCommonTest (:desktop:shared)
                └── linuxMain (or linuxCommonMain) / linuxTest
                     ├── gtkMain / gtkTest (:desktop:gtk)
                     └── qtMain / qtTest (:desktop:qt)
```

---

## Gradle Configuration & Tasks

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.nexustools.genericui")
}

genericui {
    backends {
        qt() // Enables :desktop:qt on linuxX64 and linuxArm64
    }

    application {
        mainClass = "com.example.app.MainKt"
        appName = "Nexus Studio"
        appId = "com.example.studio"
        linuxIcon("src/commonMain/resources/icons/icon.png")
    }

    extensions {
        canvas2d() // QPainter 2D canvas
        canvas3d() // QOpenGLWidget (GLCanvas) + QVulkanWindow (VKCanvas)
    }
}
```

### Linux Qt6 Gradle Tasks

| Task Name | Description |
| :--- | :--- |
| `./gradlew linuxDevelopmentQt` | Compiles the debug Linux Qt6 binary (`linuxX64` or `linuxArm64`) and launches the application immediately. |
| `./gradlew linuxProductionQt` | Compiles the optimized release Linux Qt6 executable, stages application icons and `.desktop` metadata, and packages a production distribution. |
| `./gradlew qtTest` | Executes the `JestElectricRunner` UI test suite on Qt6 via `QtJestElectricRunnerNativeImpl`. |

---

## Code Examples

### 1. Bootstrapping a Qt6 Application with `TitleBar` (`QMenuBar` & `QToolBar`)

```kotlin
package com.example.qtapp

import com.nexustools.genericui.component.action.ActionItem
import com.nexustools.genericui.component.action.Menu
import com.nexustools.genericui.component.action.MenuBar
import com.nexustools.genericui.component.action.MenuItem
import com.nexustools.genericui.component.action.TitleBar
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.qt.QtNativeFactory

class LinuxQtStudioApp : Application(title = "Nexus Qt6 Studio", width = 1366, height = 840) {
    override fun onCreate() {
        val menuBar = MenuBar().apply {
            addMenu(
                Menu("File").apply {
                    addItem(MenuItem(label = "Open Project...", shortcut = "Ctrl+O") {})
                    addItem(MenuItem(label = "Quit", shortcut = "Ctrl+Q") { dispose() })
                }
            )
        }

        // Populates QMainWindow's native QMenuBar and QToolBar:
        mainWindow.setTitleBar(
            TitleBar(
                title = "Nexus Qt6 Studio",
                subtitle = "KDE Plasma / Qt6 Native",
                menuBar = menuBar,
                actions = listOf(
                    ActionItem(id = "render.start", label = "Render Frame") {}
                )
            )
        )
    }
}

fun main() {
    GenericUI.initialize(QtNativeFactory())
    LinuxQtStudioApp().run(GenericUI.requireFactory())
}
```

### 2. `QPainter` `Canvas2D`, `QOpenGLWidget` `GLCanvas`, and `QVulkanWindow` `VKCanvas`

```kotlin
import com.nexustools.genericui.ext.canvas2d.Canvas2D
import com.nexustools.genericui.ext.canvas3d.GLCanvas
import com.nexustools.genericui.ext.canvas3d.VKCanvas

// QPainter 2D vector/raster canvas
val qPainterCanvas = Canvas2D(width = 800, height = 600).apply {
    registerVBlankRender { deltaMs ->
        clear("#202228")
    }
}

// QOpenGLWidget (OpenGL ES 3.0+) 3D canvas
val qOpenGlCanvas = GLCanvas(width = 800, height = 600, preferES31 = true).apply {
    registerVBlankRender { deltaMs ->
        glContext?.clearColorBuffer(0.08f, 0.1f, 0.14f, 1.0f)
    }
}

// QVulkanWindow Vulkan 1.2+ 3D canvas
val qVulkanCanvas = VKCanvas(width = 800, height = 600).apply {
    registerVBlankRender { deltaMs ->
        // Record Vulkan command buffer for QVulkanWindow swapchain image
    }
}
```

---

## Related Documentation

- **Parent Desktop Overview**: [`../README.md`](../README.md)
- **Sibling Desktop Docs**: [`:desktop:shared`](./shared.md) · [`:desktop:windows`](./windows.md) · [`:desktop:macos`](./macos.md) · [`:desktop:gtk`](./gtk.md) · [`:desktop:swing`](./swing.md)
- **Media & VLC Extension (`:extensions:media`)**: [`../../extensions/docs/media.md`](../../extensions/docs/media.md)
- **Root GenericUI Documentation**: [Root README](../../README.md) · [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)
- **Sibling Submodules**: [`:mobile`](../../mobile/README.md) · [`:web`](../../web/README.md) · [`:tests`](../../tests/README.md) · [`:NJS`](../../lib/NJS/README.md)
