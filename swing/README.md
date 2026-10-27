# `:desktop:swing` — JVM Desktop Swing / AWT Backend (`jvmMain`)

## Overview
`:desktop:swing` is the cross-platform JVM Swing / AWT backend for GenericUI (`jvmMain`).

### Key Highlights
- **Genuine Swing Look-and-Feel Controls (`BootstrapType.NONE` Default)**: When `getBootstrap().type == BootstrapType.NONE` (the default), components render as untinted Look-and-Feel `JComponent` peers with zero Bootstrap CSS styling.
- **Semantic `BootstrapType` Tinting**: Setting `DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, or `LINK` tints the `JComponent` with the semantic color (`outline = true/false`). Unsupported CSS properties and transitions are silently ignored.
- **Strict User CSS Class Isolation**: `getClasses()`, `getClass()`, and `hasClass()` return only user-added `customClasses`.
- **Custom Cursor Translation (`Toolkit.createCustomCursor`)**: Converts any PNG, SVG, or `ImageBuffer` `CssCustomCursor` into a `BufferedImage` and `java.awt.Cursor` with `(hotspotX, hotspotY)`.
- **Live System Theme Detection (`ThemeMode.AUTO`)**: Detects host OS dark/light theme across Windows, macOS, and Linux.

See [`../docs/swing.md`](../docs/swing.md) for full documentation.
