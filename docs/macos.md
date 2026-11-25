# macOS Native Backend (`:desktop` `macosMain` / `BackendTarget.MacOS`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md) | **Related**: [`../../docs/apple.md`](../../docs/apple.md) · [`nativeCommon.md`](nativeCommon.md) · [`windows.md`](windows.md)

## Overview
The **macOS** backend (`macosArm64`) resides in `macosMain` of `:desktop` atop **`:apple`**, using Kotlin/Native Objective-C / C-Interop with AppKit (`NSApplication`, `NSWindow`, `NSView`, `NSCursor`, `NSToolbar`, `NSMenu`), CoreGraphics, CoreImage, **Metal**, and **MoltenVK**.

---

## Single-`commonMain` Architecture & `MacosLauncher`

`GenericUiGenerateLaunchersTask` generates the `macosArm64Main` launcher invoking `MacosLauncher`:

```kotlin
// Auto-generated in build/generated/genericui/launchers/macosArm64Main
fun main(args: Array<String>) {
    val descriptor = GeneratedAppDescriptor.create()
    GenericUI.defaultThemeConfig = descriptor.themeConfig
    // When @MainServiceWorker targets MacOS:
    ServiceWorkerExtension.configure(/* generated ServiceWorkerOptions */)
    if (!ServiceWorkerExtension.prepareDesktopMainLaunch(args)) {
        return
    }
    MacosLauncher.launch(args, descriptor)
}
```

When `styles { globalStyleSheet = "..." }` is set, startup attaches the precompiled `PrecompiledStyleSheet` via `app.addStyleSheet(...)`.

---

## Pure Retained AppKit `NSView` Hierarchy, Shared `:apple` Helpers, & 60+Hz `backdrop-filter`

Every mounted component creates a real flipped AppKit `NSView` / `NSControl` (`NSPanel`, `NSPopover`, `NSMenu` for popups/menus/dialogs) inside `MacosWindowRootView` and the 4-mode `MacosHostWindow` hierarchy — completely replacing legacy single-canvas painting and offscreen control sprite rendering with retained native AppKit subviews:

1. **`MacosSurfaceLayerSupport` + Native `NSVisualEffectView` & `:apple` Helpers (`StyledComponent`, `StyledButton`, `StyledContainer`, & `Composite`)**:
   - Powered by `StyledWidgetVisualController`, `DesktopStyledWidgetDelegate`, `:apple`'s `AppleStyledSurfaceHelper`, and `MacosSurfaceLayerSupport`, using native `NSVisualEffectView` (`blendingMode = WithinWindow`) for `backdrop-filter: blur(...)` without `CALayer.backgroundFilters`, reserving `AppleBackdropFilterController` solely for non-blur W3C filter operations (`requiresFullFilterPipeline`) and offscreen `JestElectricRunner` capture (`MacosNativeWindowHost`).
2. **Uniform CSS Cascade, `:system` `Animator` `@keyframes`, & `DesktopContainerLayoutController`**:
   - Opacity inheritance (`.hidden` fading overlays and wave transitions driven via `style.scss` + `SystemCssAnimationController` / `Animator`), CSS `transform` (`translate(...)`), `z-index` subview ordering, `pointer-events: none` hit-test exclusion, and `DesktopContainerLayoutController` + `BrowserLayoutEngine` proximal `position: relative` ancestor (else `Window`) containing-block bounds resolve uniformly with **zero** component/class/id/app-specific fast-paths or custom `SeekBarNativeImpl`.

---

## Native AppKit Controls, Native `NSButton` Tinting, & Style Restraints

1. **`BootstrapType.NONE` Default & Non-`Button` Controls**: Mounts 100% unmodified AppKit controls (`NSButton`, `NSTextField`, `NSSlider`, `NSSwitch`, `NSPopUpButton`, `NSDatePicker`, `NSColorWell`, `NSSearchField`, etc.) matching `NSApp.effectiveAppearance`. Form subclasses (`ComboBox`, `DatePicker`, `TimePicker`, `ColorPicker`, `SearchField`, `NumberField`, `SplitDropdownButton`) stamp `data-control-variant` for specialized AppKit widget selection; **`DropdownButton`, `SplitDropdownButton`, `ProgressBar`, `Spinner`, `StyledSlider`, `Composite`, and all other non-`Button` controls MUST NOT support `BootstrapType`**.
2. **`Button` Genuine `NSButton` Native Tinting (`MacosButtonNativeImpl`)**: Among controls, **ONLY `Button` and its subclasses (`Button`, `IconButton`, `ToggleButton`, `FloatingActionButton`, `StyledButton`)** support `BootstrapType`. `MacosButtonNativeImpl` always mounts a genuine native `NSButton`, leaving it unmodified when `(type == null || type == BootstrapType.NONE) && !outline` and updating native AppKit styling in place via `bezelColor`, `hasDestructiveAction`, `bordered`, `showsBorderOnlyWhileMouseInside`, and `contentTintColor` when `BootstrapType != NONE` or `outline == true` (with template `NSImage` tinting).
3. **Bootstrap Semantic Surfaces & Typography vs. Aesthetic Style Restraints**: Bootstrap non-control surfaces (`Badge`, `Alert`, `Banner`, `Toast`, `Card`, `ListGroup`, `ListGroupItem`, `Jumbotron`, `Well`) mount dedicated custom AppKit views styled via `:system`'s `SemanticSurfaceStyleResolver` (`SemanticSurfacePaintSpec`). Text elements honor typography/color; non-`Button` controls and standard containers ignore both `BootstrapType` and aesthetic CSS properties; `StyledComponent` / `StyledContainer` / `StyledButton` / `Composite` (`StyledSlider`, `SeekBar`, `ExpandingVolumeSlider`) / `:extensions:ui:alien` honor full CSS/SCSS styling (`Composite` and `StyledSlider` do NOT support `BootstrapType`, and direct Kotlin component styles are forbidden beyond runtime-dynamic `%` values).
4. **Strict User CSS Class Isolation**: `styleSnapshot.customClasses` stays separate from `ComponentBootstrap` enums.

---

## Embedded Mach-O Resources (`MacosResourceResolverImpl`) & Clean `.app` Bundle Packaging

- **`MacosResourceResolverImpl`** ([`MacosResourceResolverImpl.kt`](../src/macosMain/kotlin/com/nexustools/genericui/macos/MacosResourceResolverImpl.kt)): Resolves prefix-free `ResourceID.nativeResourceKey` symbols (`genericui_res_lookup_<group>` / `genericui_res_lookup_app`) embedded in Mach-O `__TEXT,__const` sections via `dlsym` (`RTLD_DEFAULT` or `dlopen` on `Contents/Frameworks/lib<group>.dylib`), plus `lookupEmbeddedAppBytes(key)` for embedded app assets, falling back to `NSBundle.mainBundle` and `Resources.activeTable`.
- **Zero Loose `gui/` or `app/` Directories**: During `.app` assembly (`GenericUiMultiplatformConfigurator`), `gui/`, `app/`, `res/`, and `Assets.xcassets` are removed from `Contents/Resources/`, leaving **only `icon.icns`** (and optional `vulkan/icd.d/MoltenVK_icd.json`), while signed executables live in `Contents/MacOS/` and signed libraries/frameworks live in `Contents/Frameworks/`.

---

## `ServiceWorker`, `Accessory` Activation Policy, Unwrapped Tray Icon, & Squircle Handling

- **Secondary `serviceworker` Helper Binary & Native XPC / `AF_UNIX` IPC**: Built and code-signed inside `.app/Contents/Helpers/<AppName> Service Worker.app/Contents/MacOS/serviceworker`; guarded by POSIX `flock(LOCK_EX | LOCK_NB)`, `~/Library/LaunchAgents/<appId>.serviceworker.plist` autostart with `MachServices` (`<appId>.serviceworker`), and authenticated native IPC via `libxpc` Mach service (`<appId>.serviceworker`) when running under a `launchd` LaunchAgent or POSIX `AF_UNIX` `SOCK_STREAM` (plus `getpeereid` UID verification) for direct non-`launchd` launches, advertised via `serviceworker-endpoint.json` (`transport`, `address`, 32-byte hex `token`).
- **`NSApplicationActivationPolicyAccessory` Headless Tray Loop**: `MacosNativeWindowHost.syncActivationPolicyForCurrentState()` dynamically switches AppKit between `NSApplicationActivationPolicyRegular` and `NSApplicationActivationPolicyAccessory` whenever `!hasVisibleMainWindow && MacosSystemTrayNativeImpl.hasActiveTray()`, keeping menu-bar tray operation alive without a Dock icon when `openWindowOnLaunch = false` or the last window closes.
- **Unwrapped Menu-Bar Tray Icon vs. Dock Squircle Wrapping**:
  - `MacosSystemTrayNativeImpl` loads raw unwrapped template/tray icon bytes (`tray_icon_svg` / `tray_icon_png`) directly from signed Mach-O symbols via `MacosResourceResolverImpl.lookupEmbeddedAppBytes(...)` (no loose `gui/`/`app/` files required).
  - Dock icons in `MacosNativeWindowHost` skip double squircle wrapping when an asset is already pre-wrapped via `!MacosNativeWindowHost.isAlreadyWrappedAppleSquircleIcon(...)`.

---

## Custom Cursors (`MacosCursorTranslator`), Appearance, & Menu Bar

- **`MacosCursorTranslator`**: Produces Retina-aware `@1x`/`@2x` `NSImage` + `NSCursor(image:hotSpot:)`.
- **`ThemeMode.AUTO`**: Tracks `NSApp.effectiveAppearance` (`NSAppearanceNameDarkAqua` vs. `NSAppearanceNameAqua`).
- **Window Chrome**: `TitleCapabilities(isTitleBarRequired = true, supportsCustomComponents = true, canEmbedComponentsInTitleBar = true, titleBarRendersAsNavbar = false)`, single-placement `TitleBar` (`mountedInTitleBar = true`), and automatic global `NSApplication.mainMenu`.
