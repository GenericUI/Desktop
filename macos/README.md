# `:desktop:macos` — Native macOS AppKit Backend (`macosX64`, `macosArm64`)

## Overview
`:desktop:macos` is the native macOS AppKit / Cocoa backend for GenericUI (`macosX64` and `macosArm64`).

### Key Highlights
- **Genuine AppKit System Controls (`BootstrapType.NONE` Default)**: When `getBootstrap().type == BootstrapType.NONE` (the default), controls render as untinted native macOS Aqua / Dark Aqua `NSControl` widgets with zero Bootstrap CSS styling.
- **Semantic `BootstrapType` Tinting**: Setting `DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, or `LINK` tints the native AppKit control with the semantic color (`outline = true/false`). Unsupported CSS properties and transitions are silently ignored.
- **Strict User CSS Class Isolation**: `getClasses()`, `getClass()`, and `hasClass()` return only user-added `customClasses`.
- **Retina `NSCursor` Synthesis (`MacosCursorTranslator`)**: Translates any PNG `ResourceID`, SVG `ResourceID`/markup, or `ImageBuffer` `CssCustomCursor` into an `NSImage` + `NSCursor(image: nsImage, hotSpot: NSMakePoint(hotspotX, hotspotY))`.
- **Live System Theme Detection (`ThemeMode.AUTO`)**: Tracks `NSApp.effectiveAppearance` (`NSAppearanceNameDarkAqua` vs. `NSAppearanceNameAqua`).

See [`../docs/macos.md`](../docs/macos.md) for full documentation.
