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

- `SwingStyledComponentImpl` (`StyledComponent`), `SwingStyledContainerImpl` (`StyledContainer`, plus `Composite` / `StyledSlider` / `SeekBar` with zero custom `SeekBarNativeImpl`), and `SwingStyledButtonImpl` (`StyledButton`) honor full CSS/SCSS styling (`style.scss` + consumer-compiled `GenericUiGeneratedThemeStyleSheet`) via `:system`'s `StyledWidgetVisualController`, `SystemCssAnimationController` (`Animator`) `@keyframes`, `BrowserLayoutEngine` proximal `position: relative` / `Window` containing-block parity + `transform` / `z-index` / `pointer-events: none`, and CSS `backdropFilter` (all 10 `CssFilter` ops via `FastBackdropBlurKernel` & `CssFilterColorMatrix`).
- [`SwingHardwareBackdropBlurRenderer`](../src/jvmMain/kotlin/com/nexustools/genericui/swing/SwingHardwareBackdropBlurRenderer.kt) shares capture planning with Android via `:jvm`'s `JvmBackdropCapturePlanner` (`JvmBackdropCapturePlan`), walks up the Swing hierarchy to the nearest backdrop root (`JRootPane` or outermost `Container`), executes GPU-accelerated Java2D `VolatileImage` bilinear downsample/upscale ping-pong passes (`1/2 -> 1/4 -> 1/8` via `tryPaintHardwareVolatilePingPong`), and falls back transparently to cached `BufferedImage` + `FastBackdropBlurKernel` in headless JVMs or when CSS pixel color ops (`hasPixelOps`) are active.

---

## Native Swing Control Rendering, Tinting, & Style Restraints

1. **`BootstrapType.NONE` Default & Non-`Button` Controls**: `SwingBaseNativeImpl` renders 100% unmodified Swing Look-and-Feel `JComponent` peers (`JButton`, `JTextField`, `JCheckBox`, `JComboBox`, `JSlider`) for the active `LIGHT` / `DARK` theme (selecting specialized pickers/inputs via `data-control-variant`). Among control components, **`DropdownButton`, `SplitDropdownButton`, `ProgressBar`, `Spinner`, `StyledSlider`, `Composite`, and all other non-`Button` controls MUST NOT support `BootstrapType`**.
2. **`BootstrapType != NONE` on `Button` Subclasses**: Among controls, **ONLY `Button` and its subclasses (`Button`, `IconButton`, `ToggleButton`, `FloatingActionButton`, `StyledButton`)** support `BootstrapType` (plus Bootstrap-specific non-control semantic surfaces `Badge`, `Alert`, `Banner`, `Toast`, `Card`, `ListGroup`, `ListGroupItem`, `Jumbotron`, `Well` styled via `:system`'s `SemanticSurfaceStyleResolver`). `SwingControlTintRenderUtil` (`:jvm`'s `JvmControlTintPixelUtil`) renders empty-text Look-and-Feel button sprites, tints pixels using `ThemeConfig` / `BootstrapColorPalette`, blits, and overlays contrasting text and icons (`outline = true/false`).
3. **Typography vs. Aesthetic Style Restraints**: Text elements honor typography/color; non-`Button` controls and standard containers ignore both `BootstrapType` and aesthetic CSS overrides; Bootstrap non-control classes support `BootstrapType`; `StyledComponent` / `StyledContainer` / `StyledButton` / `Composite` (`StyledSlider`, `SeekBar`, `ExpandingVolumeSlider`) / `:extensions:ui:alien` honor full CSS/SCSS styling (`Composite` and `StyledSlider` do NOT support `BootstrapType`, and direct Kotlin component styles are forbidden beyond runtime-dynamic `%` values).
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
- **File Lock, Autostart, & Headless Tray Loop**: Uses `FileChannel.tryLock()` single-instance file locking (`DesktopOsHelpers`), writes OS-appropriate autostart shortcuts (`Startup/*.lnk`, `LaunchAgents/*.plist`, `.desktop`), serves authenticated IPC via JDK 16+ JEP 380 native `AF_UNIX` (`StandardProtocolFamily.UNIX` / `UnixDomainSocketAddress`) across macOS, Linux, and Windows 10/11 (`serviceworker-endpoint.json` with `transport`, `address`, and 32-byte hex `token`), and keeps `SwingApplicationImpl.runEventLoop` parked on `headlessLoopCondition.await()` while `windows.isEmpty() && SwingSystemTrayImpl.hasActiveTray()` (`openWindowOnLaunch = false`).
