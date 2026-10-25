# GenericUI — Desktop Submodule (`:desktop`)

> **Main Repository**: [**GenericUI/GenericUI**](https://github.com/GenericUI/GenericUI)

This repository is the **Desktop Backend Submodule (`:desktop`)** of the [**GenericUI**](https://github.com/GenericUI/GenericUI) Kotlin Multiplatform Native-First UI framework. It provides the shared desktop runtime, the VLC-powered desktop media/HDR engine, and the five desktop platform backends (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, `:desktop:qt`, and `:desktop:swing`).

---

## Submodule Structure

| Gradle Module | Directory | Target Platforms | Description |
| :--- | :--- | :--- | :--- |
| **`:desktop:shared`** | `shared/` | `mingwX64`, `macosX64`, `macosArm64`, `linuxX64`, `linuxArm64` | Shared desktop `NativeImpl` base classes (`DesktopBaseNativeImpl`), multi-window dock coordination (`DesktopMultiWindowDockCoordinator` for GIMP Single-Window vs Multi-Window modes), lazy viewport virtualization (`DesktopLazyViewportEngine`), native file/folder dialog helpers (`DesktopFileBrowserHelper`), and the VLC media/HDR/clock pipeline (`com.nexustools.genericui.desktop.vlc.*`). |
| **`:desktop:windows`** | `windows/` | `mingwX64` | Native Win32 (`user32.dll`, `gdi32.dll`, `comdlg32.dll`, `dwmapi.dll`) window host (`Win32NativeWindowHost`), VBlank-synchronized rendering, multi-window floating dock windows, native `IFileOpenDialog`/`IFileSaveDialog` file & folder pickers, Google ANGLE (`libEGL.dll` / `libGLESv2.dll`) OpenGL ES surfaces, and Vulkan (`VK_KHR_win32_surface`) surfaces. |
| **`:desktop:macos`** | `macos/` | `macosX64`, `macosArm64` | Native macOS AppKit (`NSWindow`, `NSView`, system top `NSMenu` bar), `NSOpenPanel`/`NSSavePanel` file & folder pickers, CoreGraphics 2D rendering, and MoltenVK (`VK_EXT_metal_surface`) GPU integration. |
| **`:desktop:gtk`** | `gtk/` | `linuxX64`, `linuxArm64` | Native Linux GTK4 (`GtkWindow`, `GtkWidget`, `GtkFileDialog`), Cairo 2D rendering, EGL/OpenGL ES, and Wayland/X11 Vulkan surfaces. |
| **`:desktop:qt`** | `qt/` | `linuxX64`, `linuxArm64` | Native Qt6 (`QMainWindow`, `QWidget`, `QDockWidget`, `QFileDialog`), `QPainter` 2D rendering, and `QVulkanWindow` GPU surfaces. |
| **`:desktop:swing`** | `swing/` | `jvm` | Optional Java Swing/AWT backend (`JFrame`, `JFileChooser`, `JScrollPane` lazy viewport) with runtime-detected LWJGL OpenGL ES and Vulkan support. |

---

## Usage via the GenericUI Gradle Plugin

Enable desktop backends in your project's `build.gradle.kts` via the [GenericUI Gradle Plugin](https://github.com/GenericUI/GenericUI):

```kotlin
plugins {
    id("com.nexustools.genericui")
}

genericui {
    backends {
        allDesktopExceptSwing() // enables windows, macos, gtk, and qt
        // or enable individual backends:
        // windows()
        // macos()
        // gtk()
        // qt()
        // swing { useLwjglGL = true; useVulkan = true }
    }
}
```

---

## Related GenericUI Repositories

- **Main Repository**: [https://github.com/GenericUI/GenericUI](https://github.com/GenericUI/GenericUI)
- **Mobile Backends (`:mobile`)**: [https://github.com/GenericUI/Mobile](https://github.com/GenericUI/Mobile)
- **Web Backend (`:web`)**: [https://github.com/GenericUI/Web](https://github.com/GenericUI/Web)
- **Extensions (`:extensions`)**: [https://github.com/GenericUI/Extensions](https://github.com/GenericUI/Extensions)
- **UI Testing Suite (`:tests` / `JestElectricRunner`)**: [https://github.com/GenericUI/Tests](https://github.com/GenericUI/Tests)
