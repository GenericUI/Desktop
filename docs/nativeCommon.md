# `:desktop` Native Desktop Common Layer (`nativeDesktopMain` / `nativeDesktopCommon`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md) | **Related**: [`common.md`](common.md) · [`linuxCommon.md`](linuxCommon.md) · [`windows.md`](windows.md) · [`macos.md`](macos.md) · [`gtk.md`](gtk.md) · [`kde.md`](kde.md)

## Overview
The `nativeDesktopMain` source set of `:desktop` (`nativeDesktopCommon` variant scope) provides the Kotlin/Native retained widget, container, layout, styling, button, docking, lazy viewport, user directory, and `libjpeg` foundation shared across the four native C/ObjC desktop backends (`Windows`, `MacOS`, `Gtk`, and `Kde`), targeting `mingwX64()`, `macosArm64()`, `linuxX64()`, and `linuxArm64()`.

> `Swing` (`jvmMain`) runs on the JVM atop `:desktop` `commonMain` and `:jvm` rather than `nativeDesktopMain`.

---

## Pure Retained Native Widgets, `DesktopContainerLayoutController`, Strictly `Button`-Only `BootstrapType`, & 60+Hz `backdrop-filter`

Every native desktop backend mounts **real interactive native OS child widgets directly** inside nested container widgets — monolithic single-canvas window painters and offscreen control sprite buffers are completely removed.

`DesktopBaseNativeImpl`, `DesktopBaseContainerNativeImpl`, `DesktopNativeWidget` (`com.nexustools.genericui.desktop.core`), `DesktopContainerLayoutController` (`com.nexustools.genericui.desktop.layout`), `DesktopStyledWidgetDelegate` (`com.nexustools.genericui.desktop.style`), and `DesktopButtonNativeImpl`, `DesktopStyledComponentNativeImpl`, `DesktopStyledContainerNativeImpl`, `DesktopStyledButtonNativeImpl` (`com.nexustools.genericui.desktop.widget`) coordinate the shared native desktop layer:

1. **`BootstrapType.NONE` Default & Non-`Button` Controls — 100% Unmodified Native OS Controls**:
   - `BootstrapStyleSnapshot` defaults to `type = BootstrapType.NONE`. All non-`Button` standard controls render genuine unmodified OS child widgets (`Win32 ComCtl32 v6` `HWND`s, AppKit `NSView`/`NSControl`, GTK4/Libadwaita `GtkWidget*`, KDE `KF6` + `Breeze` alien `QWidget*`). **`DropdownButton`, `SplitDropdownButton`, `ProgressBar`, `Spinner`, `StyledSlider`, `Composite`, and ALL other non-`Button` controls MUST NOT support `BootstrapType`.** Form subclasses (`ComboBox`, `DatePicker`, `TimePicker`, `ColorPicker`, `SearchField`, `NumberField`, `SplitDropdownButton`) stamp `data-control-variant` so backends instantiate specialized native OS controls cleanly.
2. **`Button` Native Control Mode & In-Place Toolkit Styling (`DesktopButtonNativeImpl`)**:
   - **Among control components, ONLY `Button` and its subclasses (`Button`, `IconButton`, `ToggleButton`, `FloatingActionButton`, `StyledButton`) support `BootstrapType`.**
   - `DesktopButtonNativeImpl` evaluates `isNativeDefaultButtonMode()` (`(type == null || type == BootstrapType.NONE) && !outline`): all 4 native desktop backends always retain their genuine OS push button (`NSButton`, Win32 `"Button"` / `WC_BUTTON`, `GtkButton`, KDE `QPushButton` with `Breeze`), and on `onButtonModeChanged()` update native styling in place (`NSButton` `bezelColor`/`hasDestructiveAction`/`bordered`/`showsBorderOnlyWhileMouseInside`/`contentTintColor` on macOS; Libadwaita/GTK CSS classes + theme-derived `GtkCssProvider` on GTK; `setFlat` + per-widget `QPalette` styled by `Breeze` on KDE; `NM_CUSTOMDRAW` returning `CDRF_DODEFAULT` when unmodified and `CDDS_PREPAINT` -> `CDRF_SKIPDEFAULT` for Bootstrap variants on Windows `"Button"` where UxTheme `BP_PUSHBUTTON` lacks a color-tint API).
   - Non-control Bootstrap semantic surfaces (`Badge`, `Alert`, `Banner`, `Toast`, `Card`, `ListGroup`, `ListGroupItem`, `Jumbotron`, `Well`) mount dedicated custom native widgets styled via `:system`'s `SemanticSurfaceStyleResolver` and `SemanticSurfacePaintSpec`.
3. **Typography vs. Aesthetic Style Restraints, `Composite`, & `style.scss`-First Discipline**:
   - **Text elements (`Label`, `Text`, `Heading`, `Blockquote`, `CodeBlock`, `MarkdownView`)** honor `ComponentStyle` typography and text color.
   - **Non-`Button` controls & standard containers** (`DropdownButton`, `SplitDropdownButton`, `ProgressBar`, `Spinner`, `CheckBox`, `Select`, `TextField`, `Container`, etc.) MUST NOT support `BootstrapType` and ignore aesthetic CSS skinning overrides.
   - **`StyledComponent`, `StyledContainer`, `StyledButton`, `Composite`, & `StyledSlider` (and [`:extensions:ui:alien`](../../extensions/docs/alien.md))**: Mount dedicated custom widgets controlled by `StyledWidgetVisualController` (`:system`), `DesktopStyledWidgetDelegate`, and shared backend surface/blur helpers (`AppleStyledSurfaceHelper` + macOS `NSVisualEffectView` blur, reserving `AppleBackdropFilterController` for non-blur `requiresFullFilterPipeline` ops and offscreen `JestElectricRunner` capture; `LinuxStyledSurfaceHelper`/`LinuxBackdropFilterController`; `Win32Direct2dBackdropBlurEngine`), supporting full W3C CSS (`style.scss` + consumer-compiled `GenericUiGeneratedThemeStyleSheet`), `GradientStopNormalizer` (`NormalizedLinearGradient`/`NormalizedRadialGradient`), all 10 `CssFilter` / `backdrop-filter` ops (`FastBackdropBlurKernel`, `CssFilterColorMatrix`), VBlank-synced `SystemCssAnimationController` (`Animator`) `@keyframes`, and `DesktopContainerLayoutController` + `BrowserLayoutEngine` proximal `position: relative` / `Window` containing-block parity with `transform`, `z-index`, and `pointer-events: none` (`Composite` and `StyledSlider` MUST NOT support `BootstrapType`, and direct Kotlin component styles are forbidden beyond runtime-dynamic `%` values).
4. **Strict User CSS Class Isolation (`customClasses`)**:
   - `setClass`, `getClass()`, `getClasses()`, `addClass`, `removeClass`, and `hasClass` operate strictly on `styleSnapshot.customClasses`. `ComponentBootstrap` enums never pollute `getClasses()`.
5. **`CssCustomCursor` Translation**:
   - `setCustomCursor(cursor: CssCursorValue?)` stores `CssCustomCursor` (`ResourceID`, SVG, or `ImageBuffer` + `hotspotX`/`hotspotY`) for translation by `WindowsCurCodec`, `MacosCursorTranslator`, GTK4 `GdkCursor`, or KDE `QCursor`.

---

## Key Classes

| Class | Package | Description |
|-------|---------|-------------|
| `DesktopBaseNativeImpl` | `com.nexustools.genericui.desktop.core` | Base retained native widget implementation managing `ComponentStyle`, `ComponentBootstrap`, `customClasses` isolation, `CssCustomCursor`, bounds, visibility, focus, and events |
| `DesktopBaseContainerNativeImpl` | `com.nexustools.genericui.desktop.core` | Base native container implementation with real child widget mount/unmount management and layout invalidation |
| `DesktopNativeWidget` | `com.nexustools.genericui.desktop.core` | Common contract for retained native desktop widgets with parent/window handle lifecycle |
| `DesktopContainerLayoutController` | `com.nexustools.genericui.desktop.layout` | Synchronizes nested native child widget bounds, CSS transforms, and stacking order from `BrowserLayoutEngine` across all 4 native backends |
| `DesktopStyledWidgetDelegate` | `com.nexustools.genericui.desktop.style` | Coordinates `StyledWidgetVisualController`, CSS backgrounds/gradients, box shadows, transforms, and 10-op `CssFilter` / `backdrop-filter` metadata |
| `DesktopButtonNativeImpl` | `com.nexustools.genericui.desktop.widget` | Base native `Button` managing `isNativeDefaultButtonMode()` (`BootstrapType.NONE && !outline`) and notifying retained OS button peers (`onButtonModeChanged()`) to update native button styling in place |
| `DesktopStyledComponentNativeImpl` | `com.nexustools.genericui.desktop.widget` | Pure stylesheet-driven non-container visual leaf (`StyledComponentNativeImpl`) honoring full `ComponentStyle` including `backdropFilter` |
| `DesktopStyledContainerNativeImpl` | `com.nexustools.genericui.desktop.widget` | Custom-styled container bridge honoring full `ComponentStyle` including `backdropFilter` (also backs `Composite` / `StyledSlider`) |
| `DesktopStyledButtonNativeImpl` | `com.nexustools.genericui.desktop.widget` | Custom-styled button bridge honoring full `ComponentStyle` and stateful hover/active/focus/disabled specs including `backdropFilter` |
| `DesktopWindowNativeImpl` | `com.nexustools.genericui.desktop.shell` | Base desktop window managing `TitleBar`, `MenuBar`, `StatusBar`, `TitleCapabilities`, window state, and single-placement custom titlebar filtering (`mountedInTitleBar`) |
| `DesktopApplicationNativeImpl` | `com.nexustools.genericui.desktop.shell` | Desktop application lifecycle manager, `PrecompiledStyleSheet` registry (`addStyleSheet` / `removeStyleSheet`), and `ThemeMode` (`AUTO`, `DARK`, `LIGHT`) coordination |
| `DesktopMultiWindowDockCoordinator` | `com.nexustools.genericui.desktop.dock` | Coordinates cross-window tear-off, floating dock windows, and dock target drop zones |
| `DesktopDockWorkspaceNativeImpl` | `com.nexustools.genericui.desktop.dock` | Split/tabbed docking workspace implementation for native desktop targets |
| `DesktopLazyListNativeImpl` / `DesktopTreeViewNativeImpl` | `com.nexustools.genericui.desktop.lazy` | Virtualized `LazyList` and hierarchical `TreeView` implementations powered by `DesktopLazyViewportEngine` |
| `DesktopConfigDirectoryHelper` & `DesktopUserDirectoryNativeImpl` | `com.nexustools.genericui.desktop.system` | OS configuration/cache/user directory resolution |
| `DesktopLibJpegImageCodecImpl` | `com.nexustools.genericui.desktop.image` | Hardware-accelerated `libjpeg` (`cinterop/libjpeg.def`) JPEG encoder/decoder for `mingwX64`, `macosArm64`, `linuxX64`, and `linuxArm64` |
