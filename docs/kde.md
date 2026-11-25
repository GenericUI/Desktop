# Desktop KDE Backend (`:desktop` `linuxMain` / `BackendTarget.Kde`)

The **KDE backend** (`BackendTarget.Kde`, `AppTarget.KDE`, `KdeLauncher`) integrates GenericUI applications into Linux KDE Plasma desktops using real retained **KDE Frameworks 6 (`KF6`) + `Breeze` on `Qt6`** lightweight alien `QWidget*` child hierarchies inside `libgenericui-kde.so`.

## Architecture & Pure Retained Widget Hierarchy
- **Alien `QWidget*` Native Control Tree**: Every mounted component creates a real child `QWidget*` parented into nested container widgets (never a single monolithic window canvas or label-pixmap bitmap painter). Layout geometry, CSS positioning, transforms, and `z-index` stacking order are synchronized directly through `DesktopContainerLayoutController` and `BrowserLayoutEngine`.
- **`KF6` + `Breeze` Widget Mapping**:
  - `Breeze` `QStyle` + `KColorScheme` drive native Plasma palette, metrics, focus rings, and light/dark theme integration (with graceful fallback when optional `KF6` libraries are absent in stripped CI environments).
  - Standard and specialized (`data-control-variant`) widgets bind `QPushButton` (`Breeze` default push button), `KBusyIndicatorWidget`, `KPasswordLineEdit`, `KDateComboBox`, `KTimeComboBox`, `KColorButton`, `KMessageWidget`, `KUrlLabel`, `KCollapsibleGroupBox`, `KSeparator`, `KActionMenu`, `KMessageBox`, and `KStatusNotifierItem` D-Bus system tray integration.
- **Native `QPushButton` Semantic Styling & Semantic Surfaces**:
  - `KdeButtonNativeImpl` always wraps a genuine `QPushButton` styled by `Breeze`, keeping `flat = false` and the default `Breeze` palette when `(type == null || type == BootstrapType.NONE) && !outline` and applying `setFlat` (for outline/link variants) plus a per-widget `QPalette` (`Button` / `ButtonText` resolved from `BootstrapColorPalette`) when `BootstrapType != NONE` or `outline == true`.
  - Non-control Bootstrap semantic containers (`Card`, `Alert`, `Banner`, `Toast`, `ListGroup`, `ListGroupItem`, `Jumbotron`, `Well`, `Badge`) mount dedicated custom widgets styled via `SemanticSurfaceStyleResolver` and `SemanticSurfacePaintSpec`.
- **Styled Primitives & Full 10-Op `backdrop-filter`**: `StyledComponent`, `StyledContainer` (including `Composite` / `StyledSlider`), and `StyledButton` mount dedicated custom `QWidget` implementations driven by `StyledWidgetVisualController`, `DesktopStyledWidgetDelegate`, `LinuxStyledSurfaceHelper`, and `LinuxBackdropFilterController` (sharing `CssFilter`, `FastBackdropBlurKernel`, `CssFilterColorMatrix`, and `GradientStopNormalizer` with zero `gtk` imports), alongside `KWindowEffects::enableBlurBehind` window/surface compositing when supported by KWin.

## Dynamic Binding & VTable Bridge
- **`KdeDynamicBindings`**: Resolves `Qt6` and optional `KF6` C++ mangled symbols dynamically at runtime via `dlopen`/`dlsym` inside `libgenericui-kde.so`, enabling graceful Plasma integration without hard-failing builds or headless CI images lacking optional KF6 packages.
- **`KdeVTableBridge`**: Clones live ELF C++ `QWidget` vtables at runtime so Kotlin Native callbacks intercept `paintEvent`, `resizeEvent`, `mousePressEvent`, `keyPressEvent`, and virtual overrides directly, paired with `QMetaObject::connect` for reactive signal delivery without Qt `moc` codegen or polling timers.

## Toolkit Isolation, Packaging, & Verification
- **Strict `.so` Isolation**: `libgenericui-kde.so` and `libgenericui-gtk.so` are compiled and loaded independently via `LinuxResourceResolverImpl` (`ELF .rodata` lookup) and `GenericUiVisualTestTask` / `:gradle-plugin` shared library packaging. Neither backend ever cross-imports symbols from the other; any shared Linux logic lives in toolkit-agnostic `linuxMain` / `linux/style/` helpers.
- **Autostart & Background Lifecycle**: Supports XDG `.desktop` autostart registration (`AppLifecycle`), D-Bus `KStatusNotifierItem` tray hosts, and 2-process `:extensions:system:serviceworker` workers communicating over pure kernel-native filesystem `AF_UNIX` `SOCK_STREAM` (`$XDG_RUNTIME_DIR/<appId>/sw.sock` or `/tmp/genericui-<euid>/`, `0700`/`0600` permissions, `SO_PEERCRED` UID verification, `MSG_NOSIGNAL`, stale socket recovery, zero `GDBus`/D-Bus IPC; `serviceworker-endpoint.json` with `transport`, `address`, and 32-byte hex `token`).
- **Real Widget Visual Testing**: `KdeJestElectricRunnerNativeImpl` + `KdeJestElectricBootstrap` capture real retained widget surfaces directly via `QWidget::render` (running headless via `QT_QPA_PLATFORM=offscreen` or live compositor in `testLinuxKde`).

> **Related Docs**: [Linux Shared Layer](linuxCommon.md) · [GTK Backend](gtk.md) · [Native Desktop Common](nativeCommon.md) · [Backends Overview](../../docs/backends.md)
