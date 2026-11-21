# JVM Desktop Swing Backend (`:desktop` `jvmMain` / `BackendTarget.Swing`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md) | **Related**: [`../../docs/jvm.md`](../../docs/jvm.md) · [`common.md`](common.md) · [`../../docs/backends.md`](../../docs/backends.md)

## Overview
The **JVM Swing** backend (`jvmMain`) in `:desktop` targets Java 11+ atop `:common`, `:system`, [`:jvm`](../../docs/jvm.md), and `:desktop` `commonMain`, wrapping Java Swing (`javax.swing.*`) and AWT (`java.awt.*`).

---

## Single-`commonMain` Architecture & `SwingLauncher`

`GenericUiGenerateLaunchersTask` generates a `jvmMain` entry point (`@file:JvmName("GeneratedSwingMain")`) calling `SwingLauncher`:

```kotlin
// Auto-generated in build/generated/genericui/launchers/jvmMain
@file:JvmName("GeneratedSwingMain")
package com.example.app

import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.swing.SwingLauncher

fun main(args: Array<String>) {
    val descriptor = GeneratedAppDescriptor.create()
    GenericUI.defaultThemeConfig = descriptor.themeConfig
    // When @MainServiceWorker targets Swing:
    com.nexustools.genericui.ext.serviceworker.ServiceWorkerExtension.configure(/* options */)
    if (!com.nexustools.genericui.ext.serviceworker.ServiceWorkerExtension.prepareDesktopMainLaunch(args)) {
        return
    }
    SwingLauncher.launch(args, descriptor)
}
```

When `styles { globalStyleSheet = "..." }` is set, startup attaches the precompiled `PrecompiledStyleSheet` via `app.addStyleSheet(...)`.

---

## 60+Hz Hardware `backdrop-filter` (`SwingHardwareBackdropBlurRenderer`)

- `SwingStyledContainerImpl` (`StyledContainer`) and `SwingStyledButtonImpl` (`StyledButton`) honor full `ComponentStyle` including CSS `backdropFilter` (`TextBubble` strips `backdropFilter` via `withoutBackdropFilter()`).
- [`SwingHardwareBackdropBlurRenderer`](../src/jvmMain/kotlin/com/nexustools/genericui/swing/SwingHardwareBackdropBlurRenderer.kt) walks up the Swing hierarchy to the nearest backdrop root (`JRootPane` or outermost `Container`), paints strictly pre-order preceding sibling branches in Swing's reverse child order (`componentCount - 1 downTo 0`), executes GPU-accelerated Java2D `VolatileImage` bilinear downsample/upscale ping-pong passes (`1/2 -> 1/4 -> 1/8` via `tryPaintHardwareVolatilePingPong`), and falls back transparently to cached `BufferedImage` + `FastBackdropBlurKernel` in headless JVMs or when CSS pixel color ops (`hasPixelOps`) are active.

---

## Native Swing Control Rendering, Tinting, & Style Restraints

1. **`BootstrapType.NONE` Default**: `SwingBaseNativeImpl` renders 100% unmodified Swing Look-and-Feel `JComponent` peers (`JButton`, `JTextField`, `JCheckBox`, `JComboBox`, `JSlider`) for the active `LIGHT` / `DARK` theme.
2. **`BootstrapType != NONE`**: `SwingControlTintRenderUtil` (`:jvm`'s `JvmControlTintPixelUtil`) renders empty-text Look-and-Feel sprites, tints pixels using `ThemeConfig` / `BootstrapColorPalette`, blits, and overlays contrasting text and icons (`outline = true/false`).
3. **Typography vs. Aesthetic Style Restraints**: Text elements honor typography/color; standard controls/containers ignore aesthetic CSS overrides (tint via `BootstrapType` only); `StyledContainer` / `StyledButton` / `:extensions:ui:alien` honor full `ComponentStyle`.
4. **Strict User CSS Class Isolation**: `styleSnapshot.customClasses` isolates user classes from `ComponentBootstrap` enums.
5. **Custom Cursors & Theming**: `CssCustomCursor` -> `BufferedImage` -> `Toolkit.createCustomCursor`; `ThemeMode.AUTO` detects OS theme changes across Windows, macOS, and Linux.

---

## Window Chrome, JAR Classpath Resources, & `:jvm` `JniLibraryLoader`

- **Window Chrome**: `SwingWindowImpl` reports desktop `TitleCapabilities` (`isTitleBarRequired = true`, `supportsCustomComponents = true`, `canEmbedComponentsInTitleBar = true`, `titleBarRendersAsNavbar = false`) with single-placement custom `TitleBar` panels (`mountedInTitleBar = true`).
- **JAR Resources (`SwingResourceResolverImpl`)**: Extends `:jvm` `JvmResourceResolverBase` to load packaged resources from the JVM classpath / fat JAR.
- **Bundled Native Libraries (`META-INF/native/` + `JniLibraryLoader`)**: `GenericUiPackageSwingJarTask` embeds cross-platform JNI/extension native libraries (`.dll`, `.dylib`, `.so`) under `META-INF/native/<os>-<arch>/` inside the Swing fat JAR, and [`:jvm`'s `JniLibraryLoader`](../../docs/jvm.md) extracts them on demand into `<java.io.tmpdir>/genericui-jni/<os>-<arch>/<sha256-16>/` before loading via `System.load(...)`.

---

## `ServiceWorker` (`GeneratedSwingServiceWorkerMain`), Tray, & Autostart

- **`GeneratedSwingServiceWorkerMain` in Same Fat JAR**: Instead of compiling a second native binary, `ServiceWorkerExtensionContributor` emits `GeneratedSwingServiceWorkerMain` (`@file:JvmName("GeneratedSwingServiceWorkerMain")`) into the same Swing JAR and launches `java -cp <jar> ...GeneratedSwingServiceWorkerMain` when needed.
- **File Lock, Autostart, & Headless Tray Loop**: Uses `FileChannel.tryLock()` single-instance file locking (`DesktopOsHelpers`), writes OS-appropriate autostart shortcuts (`Startup/*.lnk`, `LaunchAgents/*.plist`, `.desktop`), serves loopback `127.0.0.1:0` authenticated IPC, and keeps `SwingApplicationImpl.runEventLoop` parked on `headlessLoopCondition.await()` while `windows.isEmpty() && SwingSystemTrayImpl.hasActiveTray()` (`openWindowOnLaunch = false`).
