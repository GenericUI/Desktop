# `:desktop:swing` — Java/JVM AWT & Swing Backend

> **Parent Submodule**: [`:desktop` (`../README.md`)](../README.md) | **Root Repository**: [**GenericUI (`../../README.md`)**](../../README.md)  
> **Root Docs**: [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)  
> **Sibling Desktop Backends**: [`:desktop:shared`](./shared.md) · [`:desktop:windows`](./windows.md) · [`:desktop:macos`](./macos.md) · [`:desktop:gtk`](./gtk.md) · [`:desktop:qt`](./qt.md)

The **`:desktop:swing`** module (`com.nexustools.genericui.swing.*`) is the pure Java/JVM desktop backend for **GenericUI**. Built on **AWT / Swing** (`JFrame`, `JDialog`, `JMenuBar`, `JComponent`, `JFileChooser`, `JScrollPane`, `Graphics2D`, `javax.imageio.ImageIO`), it runs on any JVM (Windows, macOS, Linux) and supports optional **LWJGL 3 `JAWT`** native OpenGL ES (`useLwjglGL = true`) and Vulkan (`useVulkan = true`) hardware surfaces when `:extensions:gpu` or `:extensions:canvas3d` are enabled.

> [!IMPORTANT]
> **Zero VLC or Media Code in `:desktop:swing`**
> `:desktop:swing` contains **NO** VLC (`libvlcj` / `libvlc`) or media playback code whatsoever. All JVM/Swing video and audio playback (`uk.co.caprica:vlcj` `libvlcj` integration, hardware-accelerated frame rendering, HDR tone-mapping, and audio analysis) resides strictly inside **`:extensions:media:desktop:swing`** ([`../../extensions/docs/media.md`](../../extensions/docs/media.md)).

---

## Core Architecture & Capabilities

1. **JVM AWT/Swing Widget Peers (`SwingNativeFactory`, `SwingBaseNativeImpl`, `SwingBaseContainerNativeImpl`)**:
   - Maps GenericUI components directly to Swing `JComponent` peers (`JFrame`, `JButton`, `JTextField`, `JComboBox`, `JCheckBox`, `JSlider`, `JProgressBar`, `JSplitPane`, `JTabbedPane`, `JTable`, `JPopupMenu`).
   - Translates `TitleBar` instructions (`window.setTitleBar(titleBar)`) into the `JFrame` title, native `JMenuBar` (`JMenu` / `JMenuItem` with `KeyStroke` accelerators), and header action toolbar (`SwingNavbarImpl`).
   - Applies GenericUI's Dual-Mode Bootstrap 5 enums (`BootstrapType`, `BootstrapSize`, `SpacingScale`, `BorderRadiusStyle`, `ShadowElevation`) and compiled SASS/CSS `ComputedStyle` rules via custom `Graphics2D` anti-aliased painting and `Border` rendering.
2. **True Lazy Viewport Recycling (`SwingLazyListImpl`, `SwingLazyGridImpl`, `SwingTreeViewImpl`)**:
   - Uses `JScrollPane` + `JViewport` `ChangeListener` scroll tracking to dynamically mount and bind (`onBind`) only the `JComponent` cells inside the visible `[firstVisible .. lastVisible]` window while unmounting and recycling offscreen cells into the `ViewHolderPool`.
3. **Native File & Folder Picker (`SwingFileBrowserDialogImpl`)**:
   - Wraps `javax.swing.JFileChooser` supporting:
     - `JFileChooser.FILES_ONLY` (`OPEN_SINGLE_FILE`, `OPEN_MULTIPLE_FILES` with `isMultiSelectionEnabled = true`, and `SAVE_FILE`)
     - `JFileChooser.DIRECTORIES_ONLY` (`FileBrowserMode.SELECT_FOLDER`)
   - Translates `FileTypeFilter` lists into `javax.swing.filechooser.FileNameExtensionFilter` descriptors.
4. **Java2D `Canvas2D` & Optional LWJGL 3 `GLCanvas` / `VKCanvas`**:
   - **`Canvas2D` (`:extensions:canvas2d:desktop:swing`)**: Hardware-accelerated **Java2D** (`Graphics2D` with `RenderingHints.VALUE_ANTIALIAS_ON` and `BufferedImage` backbuffers) driven by a high-precision VBlank Swing timer providing `deltaMs`.
   - **`GLCanvas` & `VKCanvas` (`:extensions:canvas3d:desktop:swing` + `:extensions:gpu:desktop:swing`)**: When `useLwjglGL = true` and/or `useVulkan = true` are configured in `genericui { backends { swing { ... } } }`, the Gradle plugin automatically injects **LWJGL 3** (`lwjgl`, `lwjgl-opengles`, `lwjgl-egl`, `lwjgl-vulkan`, `lwjgl-jawt`) so `GLCanvas` and `VKCanvas` render directly to native `JAWT` surfaces inside the Swing `JFrame`.
5. **Native `javax.imageio.ImageIO` Codec (`SwingImageIoCodecImpl`)**:
   - Uses `ImageIO` and `BufferedImage` alongside `:common`'s `libjpeg`/`libpng`/`libwebp` codecs for fast JVM image decoding and encoding.

---

## Key Classes Reference

| Class / Object | Responsibility |
| :--- | :--- |
| `SwingNativeFactory` | `NativeBackendFactory` creating Swing `JComponent` peers and registering `SwingImageIoCodecImpl`. |
| `SwingBaseNativeImpl<W>` | Base `NativeImpl<W>` for Swing components, managing `BootstrapStyleSnapshot`, `ComputedStyle`, `Graphics2D` styling, and EDT-safe event dispatch. |
| `SwingBaseContainerNativeImpl<W>` | Container peer managing `JPanel` layout managers (`BoxLayout`, Flex-inspired layout, `BorderLayout`) and child `JComponent` attachment. |
| `SwingApplicationImpl` | Manages Swing Look-and-Feel initialization, Event Dispatch Thread (`SwingUtilities.invokeLater`), and application lifecycle. |
| `SwingWindowImpl` | Wraps `JFrame`, applying `TitleBar` instructions, `JMenuBar`, window icon (`ImageIO`), and VBlank timer scheduling. |
| `SwingDockWorkspaceImpl` & `SwingDockWindowImpl` | Supports Single-Window split docking and floating `JDialog` utility windows for Multi-Window Mode (`StudioWindowMode.MULTI_WINDOW_MODE`). |
| `SwingFileBrowserDialogImpl` | `JFileChooser` implementation supporting file selection, multi-selection, save dialogs, and `DIRECTORIES_ONLY` folder selection. |
| `SwingLazyListImpl`, `SwingLazyGridImpl`, `SwingTreeViewImpl` | Viewport-windowed lazy list, grid, and tree components backed by `JScrollPane`. |
| `SwingImageIoCodecImpl` | `javax.imageio.ImageIO` + `BufferedImage` implementation of `:common`'s `ImageCodecNativeImpl`. |
| `SwingSystemTrayImpl` | `java.awt.SystemTray` and `TrayIcon` implementation. |

---

## SourceSet Hierarchy (`desktopCommon` $\rightarrow$ `swing` / `swingMain` / `swingTest`)

Notice how `:desktop:swing` sits under **`desktopCommon`** (sharing desktop concepts with native desktop backends) while branching into its own JVM-specific **`swing`** (`swingMain` / `swingTest`) sourceSet rather than `desktopNativeCommon`:

```
commonMain / commonTest
 └── systemCommonMain / systemCommonTest
      └── desktopCommonMain / desktopCommonTest
           ├── swingMain / swingTest (:desktop:swing — JVM AWT/Swing + Optional LWJGL 3)
           └── desktopNativeCommonMain / desktopNativeCommonTest (:desktop:shared — C/C++/ObjC Native)
                ├── windowsMain / windowsTest
                ├── macMain / macTest
                └── linuxMain / linuxTest (gtkMain, qtMain)
```

---

## Gradle Configuration, LWJGL 3 Injection, & Tasks

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.nexustools.genericui")
}

genericui {
    backends {
        swing {
            // Optional: Inject LWJGL 3 OpenGL ES and Vulkan native dependencies for :extensions:gpu / :extensions:canvas3d
            useLwjglGL = true
            useVulkan = true
            lwjglVersion = "3.3.6"
            targetAllDesktopClassifiers() // Includes natives-windows, natives-macos, natives-macos-arm64, natives-linux, etc.
        }
    }

    application {
        mainClass = "com.example.app.MainKt"
        appName = "Nexus Studio (Swing)"
        appId = "com.example.studio"
        version = "1.0.0"
        commonIcon("src/commonMain/resources/icons/icon.png")
    }

    extensions {
        canvas2d() // Java2D Graphics2D canvas
        canvas3d() // LWJGL 3 JAWT GLCanvas & VKCanvas
        // For video/audio playback on Swing (via libvlcj), enable :extensions:media:
        // media()
    }
}
```

### Swing Gradle Tasks

| Task Name | Description |
| :--- | :--- |
| `./gradlew swingDevelopment` | Compiles the `swingMain` JVM target, wires LWJGL 3 native classifiers (if enabled), and launches the Swing application on the JVM. |
| `./gradlew swingProduction` | Builds a distributable JVM application package / fat JAR with all required runtime dependencies. |
| `./gradlew swingTest` | Runs the `JestElectricRunner` JUnit 4 test suite (`SwingJUnitRunnerNativeImpl` & `JestElectricSwingTestBase`) with Swing EDT synchronization. |

---

## Code Examples

### 1. Launching a GenericUI Application on `:desktop:swing`

```kotlin
package com.example.swingapp

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.action.TitleBar
import com.nexustools.genericui.component.container.Container
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.SpacingScale
import com.nexustools.genericui.swing.SwingNativeFactory

class SwingStudioApp : Application(title = "Nexus Studio (JVM Swing)", width = 1280, height = 800) {
    override fun onCreate() {
        mainWindow.setTitleBar(
            TitleBar(title = "Nexus Studio", subtitle = "Java2D + LWJGL 3 GPU")
        )

        val content = Container().apply {
            setPaddingAll(SpacingScale.P_4)
            addChild(
                Button("Run GPU Filter").apply {
                    id = "run-filter-btn"
                    setType(BootstrapType.SUCCESS)
                }
            )
        }
        mainWindow.setContent(content)
    }
}

fun main() {
    GenericUI.initialize(SwingNativeFactory())
    SwingStudioApp().run(GenericUI.requireFactory())
}
```

### 2. Testing a Swing UI with `JestElectricSwingTestBase`

See [Platform Test Runners (`../../tests/docs/platform-test-runners.md`)](../../tests/docs/platform-test-runners.md) for full details on `SwingJUnitRunnerNativeImpl`:

```kotlin
import com.nexustools.genericui.tests.swing.JestElectricSwingTestBase
import org.junit.Test

class SwingStudioAppTest : JestElectricSwingTestBase() {
    @Test
    fun testButtonInteractionOnSwingEdt() = describeSwing("Swing Studio UI Suite") {
        it("mounts JButton peer and dispatches click on the Swing EDT") {
            val app = launchApp { SwingStudioApp() }
            app.findById("run-filter-btn")
                .assertExists()
                .assertIsVisible()
                .performClick()
        }
    }
}
```

---

## Related Documentation

- **Parent Desktop Overview**: [`../README.md`](../README.md)
- **Sibling Desktop Docs**: [`:desktop:shared`](./shared.md) · [`:desktop:windows`](./windows.md) · [`:desktop:macos`](./macos.md) · [`:desktop:gtk`](./gtk.md) · [`:desktop:qt`](./qt.md)
- **Media & VLC Extension (`:extensions:media` — `libvlcj` on Swing)**: [`../../extensions/docs/media.md`](../../extensions/docs/media.md)
- **Root GenericUI Documentation**: [Root README](../../README.md) · [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)
- **Sibling Submodules**: [`:mobile`](../../mobile/README.md) · [`:web`](../../web/README.md) · [`:tests`](../../tests/README.md) · [`:NJS`](../../lib/NJS/README.md)
