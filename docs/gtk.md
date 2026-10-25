# `:desktop:gtk` — Native Linux GTK4 Backend

> **Parent Submodule**: [`:desktop` (`../README.md`)](../README.md) | **Root Repository**: [**GenericUI (`../../README.md`)**](../../README.md)  
> **Root Docs**: [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)  
> **Sibling Desktop Backends**: [`:desktop:shared`](./shared.md) · [`:desktop:windows`](./windows.md) · [`:desktop:macos`](./macos.md) · [`:desktop:qt`](./qt.md) · [`:desktop:swing`](./swing.md)

The **`:desktop:gtk`** module (`com.nexustools.genericui.gtk.*`) is the native Linux GTK4 (`linuxX64` & `linuxArm64`) backend for **GenericUI**. Built on **GTK4** (`libgtk-4`), **GDK4** (Wayland & X11), **GIO**, **Cairo**, **Pango**, **GdkPixbuf**, and [`:desktop:shared`](./shared.md), it produces native GNOME / GTK desktop applications with first-class Wayland and X11 support.

> [!IMPORTANT]
> **Zero VLC or Media Code in `:desktop:gtk`**
> `:desktop:gtk` contains **NO** VLC (`libvlc`) or media playback code whatsoever. All Linux GTK VLC video/audio decoding (`VAAPI` / `VDPAU` / `NVDEC`), Vulkan (`#version 450` SPIR-V) & OpenGL ES (`#version 300 es`) HDR tone-mapping, and media playback clocks reside strictly in **`:extensions:media:desktop:gtk`** and **`:extensions:media:desktop:shared`** ([`../../extensions/docs/media.md`](../../extensions/docs/media.md)).

---

## Core Architecture & Native Capabilities

1. **Native GTK4 Widget Peers (`GtkNativeFactory`, `GtkWindowNativeImpl`, `GtkWidgetHandle`)**:
   - Maps GenericUI components directly to GTK4 `GtkWidget` instances (`GtkApplicationWindow`, `GtkHeaderBar`, `GtkBox`, `GtkPaned`, `GtkNotebook`, `GtkButton`, `GtkEntry`, `GtkDropDown`, `GtkCheckButton`, `GtkScale`, `GtkProgressBar`, `GtkPopoverMenu`).
   - Translates `TitleBar` instructions (`window.setTitleBar(titleBar)`) into a native GNOME **`GtkHeaderBar`** with title/subtitle labels, `GMenuModel` popover/menu bar, and start/end action buttons.
   - Bridges GenericUI's Dual-Mode Bootstrap 5 + SASS/CSS styling to GTK4's native `GtkCssProvider` and `gtk_widget_add_css_class`.
2. **Native `GtkFileDialog` File & Folder Picker (`GtkFileBrowserDialogNativeImpl`)**:
   - Uses GTK4's modern `GtkFileDialog` (and XDG Desktop Portal integration on sandboxed Wayland sessions):
     - `gtk_file_dialog_open` (`FileBrowserMode.OPEN_SINGLE_FILE`)
     - `gtk_file_dialog_open_multiple` (`FileBrowserMode.OPEN_MULTIPLE_FILES`)
     - `gtk_file_dialog_save` (`FileBrowserMode.SAVE_FILE`)
     - `gtk_file_dialog_select_folder` (`FileBrowserMode.SELECT_FOLDER`)
   - Attaches `GtkFileFilter` instances built from `DesktopFileBrowserHelper.toGtkFilterPatterns(request)`.
3. **Hardware-Accelerated 2D & 3D Canvases (`Cairo`, `EGL/GLES3`, `Vulkan`)**:
   - **`Canvas2D` (`:extensions:canvas2d:desktop:gtk`)**: Hardware-accelerated **Cairo** (`cairo_t`) + **Pango** 2D drawing area (`GtkDrawingArea`) synchronized to GTK4's frame clock (`gtk_widget_add_tick_callback` / `GdkFrameClock`) providing high-precision `deltaMs` VBlank callbacks.
   - **`GLCanvas` (`:extensions:canvas3d:desktop:gtk` + `:extensions:gpu`)**: Native `GtkGLArea` backed by **EGL / OpenGL ES 3.0+**.
   - **`VKCanvas` (`:extensions:canvas3d:desktop:gtk` + `:extensions:gpu`)**: Native Vulkan 1.2+ surface created for the active GDK display server (`VK_KHR_wayland_surface` on Wayland or `VK_KHR_xlib_surface` / `VK_KHR_xcb_surface` on X11).
4. **Native `GdkPixbuf` Image Codec (`GtkPixbufImageCodecNativeImpl`)**:
   - Integrates `GdkPixbuf` (`libgdk_pixbuf-2.0`) with `:common`'s `ImageLoader` for fast native image decoding and encoding.

---

## Key Classes Reference

| Class / Object | Responsibility |
| :--- | :--- |
| `GtkNativeFactory` | `NativeBackendFactory` creating GTK4-backed `NativeImpl` peers and registering `GtkPixbufImageCodecNativeImpl`. |
| `GtkWidgetHandle` | Extends `DesktopNativeWidget` with native `gtkWidgetPtr: Long`, `cssProviderPtr: Long`, and GDK surface metadata. |
| `GtkApplicationNativeImpl` | Manages `GtkApplication` (`g_application_run`), Wayland/X11 display connection, and global `GtkCssProvider` themes. |
| `GtkWindowNativeImpl` | Wraps `GtkApplicationWindow` and `GtkHeaderBar`, applying `TitleBar` instructions, menus, and `GdkFrameClock` VBlank ticks. |
| `GtkDockWindowNativeImpl` | Floating GTK4 utility window (`GtkWindow`) managed by `DesktopMultiWindowDockCoordinator` in Multi-Window Mode. |
| `GtkFileBrowserDialogNativeImpl` | Native `GtkFileDialog` + `GtkFileFilter` file and folder browser implementation. |
| `GtkPixbufImageCodecNativeImpl` | Native `GdkPixbuf` implementation of `:common`'s `ImageCodecNativeImpl`. |
| `GtkSystemTrayNativeImpl` | Linux `StatusNotifierItem` (SNI / AppIndicator DBus) system tray icon and menu peer. |
| `GtkNotificationManagerNativeImpl` | GIO `GNotification` / `org.freedesktop.Notifications` desktop notification dispatcher. |
| `GtkNetworkMonitorNativeImpl` | GIO `GNetworkMonitor` connectivity state observer. |
| `GtkPreferenceStoreImpl` | Native Linux GTK `PreferenceStoreNativeImpl` storing per-key `.bson` binary JSON files (`BinaryJson`) inside `$XDG_CONFIG_HOME/<appName>/<key>.bson` (or `~/.config/<appName>/<key>.bson`). |

---

## SourceSet Hierarchy (`linuxCommon` / `linuxMain` $\rightarrow$ `gtk` / `gtkMain`)

When `gtk()` is enabled in `genericui { backends { ... } }`, the Gradle plugin wires **both** the shared Linux desktop sourceSet (`linuxCommon` / `linuxMain` / `linuxTest`, shared with `:desktop:qt`) and the GTK-specific leaf sourceSet (`gtk` / `gtkMain` / `gtkTest`):

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
        gtk() // Enables :desktop:gtk on linuxX64 and linuxArm64
    }

    application {
        mainClass = "com.example.app.MainKt"
        appName = "Nexus Studio"
        appId = "com.example.studio"
        linuxIcon("src/commonMain/resources/icons/icon.png")
    }

    extensions {
        canvas2d() // Cairo + Pango 2D canvas
        canvas3d() // GtkGLArea (EGL/GLES3) + Wayland/X11 Vulkan VKCanvas
    }
}
```

### Linux GTK4 Gradle Tasks

| Task Name | Description |
| :--- | :--- |
| `./gradlew linuxDevelopmentGtk` | Compiles the debug Linux GTK4 binary (`linuxX64` or `linuxArm64`) and launches the application against the active Wayland or X11 display. |
| `./gradlew linuxProductionGtk` | Compiles the optimized release Linux GTK4 executable, bundles application icons and desktop entry metadata (`.desktop`), and outputs a production distribution. |
| `./gradlew gtkTest` | Executes the `JestElectricRunner` UI test suite on GTK4 via `GtkJestElectricRunnerNativeImpl`. |

---

## Code Examples

### 1. Configuring a GTK4 Application with `TitleBar` (`GtkHeaderBar`) & `GtkFileDialog`

```kotlin
package com.example.gtkapp

import com.nexustools.genericui.component.action.ActionItem
import com.nexustools.genericui.component.action.TitleBar
import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserDialog
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.component.dialog.SelectedFile
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.enums.dialog.FileBrowserMode
import com.nexustools.genericui.gtk.GtkNativeFactory

class LinuxGtkApp : Application(title = "Nexus GTK4 Studio", width = 1280, height = 800) {
    private val fileDialog = FileBrowserDialog()

    override fun onCreate() {
        // Renders as a native GTK4 GtkHeaderBar with action buttons:
        mainWindow.setTitleBar(
            TitleBar(
                title = "Nexus GTK4 Studio",
                subtitle = "Wayland / X11 Native",
                actions = listOf(
                    ActionItem(id = "open.project", label = "Open Folder") {
                        openProjectFolder()
                    }
                )
            )
        )
    }

    private fun openProjectFolder() {
        fileDialog.show(
            request = FileBrowserRequest(
                title = "Select Project Directory",
                mode = FileBrowserMode.SELECT_FOLDER
            ),
            callback = object : FileBrowserCallback {
                override fun onFilesSelected(files: List<SelectedFile>, activeFilter: FileTypeFilter?) {
                    println("Selected GTK4 folder: ${files.first().pathOrUri}")
                }

                override fun onCancelled() {}
            }
        )
    }
}

fun main() {
    GenericUI.initialize(GtkNativeFactory())
    LinuxGtkApp().run(GenericUI.requireFactory())
}
```

### 2. Cairo `Canvas2D`, EGL `GLCanvas`, and Wayland/X11 `VKCanvas`

```kotlin
import com.nexustools.genericui.ext.canvas2d.Canvas2D
import com.nexustools.genericui.ext.canvas3d.GLCanvas
import com.nexustools.genericui.ext.canvas3d.VKCanvas

// Cairo + Pango 2D canvas driven by GTK4 GdkFrameClock
val cairoCanvas = Canvas2D(width = 800, height = 600).apply {
    registerVBlankRender { deltaMs ->
        clear("#16181D")
    }
}

// GtkGLArea EGL / OpenGL ES 3.0+ canvas
val eglCanvas = GLCanvas(width = 800, height = 600, preferES31 = true).apply {
    registerVBlankRender { deltaMs ->
        glContext?.clearColorBuffer(0.1f, 0.12f, 0.16f, 1.0f)
    }
}

// Wayland (VK_KHR_wayland_surface) or X11 (VK_KHR_xlib_surface) Vulkan canvas
val vkCanvas = VKCanvas(width = 800, height = 600).apply {
    registerVBlankRender { deltaMs ->
        // Record Vulkan command buffer
    }
}
```

---

## Related Documentation

- **Parent Desktop Overview**: [`../README.md`](../README.md)
- **Sibling Desktop Docs**: [`:desktop:shared`](./shared.md) · [`:desktop:windows`](./windows.md) · [`:desktop:macos`](./macos.md) · [`:desktop:qt`](./qt.md) · [`:desktop:swing`](./swing.md)
- **Media & VLC Extension (`:extensions:media`)**: [`../../extensions/docs/media.md`](../../extensions/docs/media.md)
- **Root GenericUI Documentation**: [Root README](../../README.md) · [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)
- **Sibling Submodules**: [`:mobile`](../../mobile/README.md) · [`:web`](../../web/README.md) · [`:tests`](../../tests/README.md) · [`:NJS`](../../lib/NJS/README.md)
