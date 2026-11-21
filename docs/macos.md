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

## 60+Hz Hardware `backdrop-filter` & Stage HUD `ExpandingVolumeSlider` Wave-Alpha Sync

1. **`MacosMetalBackdropBlurEngine` (`StyledButton` & `StyledContainer`, Excluding `TextBubble`)**:
   - Implemented in [`MacosMetalBackdropBlurEngine.kt`](../src/macosMain/kotlin/com/nexustools/genericui/macos/render/MacosMetalBackdropBlurEngine.kt) and invoked through `MacosGraphicsDrawUtil.applyBackdropFilterRoundedRect` from `MacosControlRenderUtil` (`StyledContainer`, `StyledButton`) and `MacosStageOverlayRenderUtil` (`TextBubble` strips `backdropFilter` via `withoutBackdropFilter()`).
   - Uses **Metal (`MTLCreateSystemDefaultDevice`) + GPU `CIContext.contextWithMTLDevice`** with clamped `CIGaussianBlur` and `CIColorControls` for 60+Hz smooth behind-the-surface compositing, falling back cleanly to `FastBackdropBlurKernel` when Metal initialization is unavailable.
2. **Stage HUD `ExpandingVolumeSlider` Wave-Alpha Synchronization**:
   - `MacosStageOverlayRenderUtil` (`renderExpandingVolumeIconStack` + `resolveVolumeLayerAlpha`) synchronizes `data-volume-layer` layers (`"speaker"`, `"wave-1"` -> `"data-wave-1-opacity"`, `"wave-2"` -> `"data-wave-2-opacity"`, `"wave-3"` -> `"data-wave-3-opacity"`, `"mute-cross"` -> `"data-mute-cross-opacity"`) inside `ROLE_EXPANDING_SLIDER_PILL` (`ExpandingVolumeSlider`) by multiplying `savedOpacity * layerAlpha` against live HUD fade opacity.

---

## Native AppKit Control Rendering & Style Restraints

1. **`BootstrapType.NONE` Default**: Renders 100% unmodified AppKit controls (`NSButton`/`NSButtonCell`, `NSTextField`, `NSSlider`, `NSSwitch`, `NSPopUpButton`) matching `NSApp.effectiveAppearance`.
2. **`BootstrapType != NONE`**: `MacosControlRenderUtil` renders the AppKit control with empty text into an offscreen `CGBitmapContext` ARGB buffer, tints via `DesktopControlTintRenderUtil.tintNativeControlArgbBuffer` and `ThemeConfig`, blits the tinted `CGImage`, and paints contrasting CoreText labels and icons on top.
3. **Typography vs. Aesthetic Style Restraints**: Text elements honor typography/color; standard controls/containers ignore aesthetic CSS properties (tint via `BootstrapType` only); `StyledContainer` / `StyledButton` / `:extensions:ui:alien` honor full `ComponentStyle` including `backdropFilter`.
4. **Strict User CSS Class Isolation**: `styleSnapshot.customClasses` stays separate from `ComponentBootstrap` enums.

---

## Embedded Mach-O Resources (`MacosResourceResolverImpl`) & Clean `.app` Bundle Packaging

- **`MacosResourceResolverImpl`** ([`MacosResourceResolverImpl.kt`](../src/macosMain/kotlin/com/nexustools/genericui/macos/MacosResourceResolverImpl.kt)): Resolves prefix-free `ResourceID.nativeResourceKey` symbols (`genericui_res_lookup_<group>` / `genericui_res_lookup_app`) embedded in Mach-O `__TEXT,__const` sections via `dlsym` (`RTLD_DEFAULT` or `dlopen` on `Contents/Frameworks/lib<group>.dylib`), plus `lookupEmbeddedAppBytes(key)` for embedded app assets, falling back to `NSBundle.mainBundle` and `Resources.activeTable`.
- **Zero Loose `gui/` or `app/` Directories**: During `.app` assembly (`GenericUiMultiplatformConfigurator`), `gui/`, `app/`, `res/`, and `Assets.xcassets` are removed from `Contents/Resources/`, leaving **only `icon.icns`** (and optional `vulkan/icd.d/MoltenVK_icd.json`), while signed executables live in `Contents/MacOS/` and signed libraries/frameworks live in `Contents/Frameworks/`.

---

## `ServiceWorker`, `Accessory` Activation Policy, Unwrapped Tray Icon, & Squircle Handling

- **Secondary `Contents/MacOS/serviceworker` Binary**: Built and code-signed inside `.app/Contents/MacOS/serviceworker`; guarded by POSIX `flock(LOCK_EX | LOCK_NB)`, `~/Library/LaunchAgents/<appId>.serviceworker.plist` autostart (`DesktopOsHelpers`), and authenticated loopback `127.0.0.1:0` IPC.
- **`NSApplicationActivationPolicyAccessory` Headless Tray Loop**: `MacosNativeWindowHost.syncActivationPolicyForCurrentState()` dynamically switches AppKit between `NSApplicationActivationPolicyRegular` and `NSApplicationActivationPolicyAccessory` whenever `!hasVisibleMainWindow && MacosSystemTrayNativeImpl.hasActiveTray()`, keeping menu-bar tray operation alive without a Dock icon when `openWindowOnLaunch = false` or the last window closes.
- **Unwrapped Menu-Bar Tray Icon vs. Dock Squircle Wrapping**:
  - `MacosSystemTrayNativeImpl` loads raw unwrapped template/tray icon bytes (`tray_icon_svg` / `tray_icon_png`) directly from signed Mach-O symbols via `MacosResourceResolverImpl.lookupEmbeddedAppBytes(...)` (no loose `gui/`/`app/` files required).
  - Dock icons in `MacosNativeWindowHost` skip double squircle wrapping when an asset is already pre-wrapped via `!MacosNativeWindowHost.isAlreadyWrappedAppleSquircleIcon(...)`.

---

## Custom Cursors (`MacosCursorTranslator`), Appearance, & Menu Bar

- **`MacosCursorTranslator`**: Produces Retina-aware `@1x`/`@2x` `NSImage` + `NSCursor(image:hotSpot:)`.
- **`ThemeMode.AUTO`**: Tracks `NSApp.effectiveAppearance` (`NSAppearanceNameDarkAqua` vs. `NSAppearanceNameAqua`).
- **Window Chrome**: `TitleCapabilities(isTitleBarRequired = true, supportsCustomComponents = true, canEmbedComponentsInTitleBar = true, titleBarRendersAsNavbar = false)`, single-placement `TitleBar` (`mountedInTitleBar = true`), and automatic global `NSApplication.mainMenu`.
