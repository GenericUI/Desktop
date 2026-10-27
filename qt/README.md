# `:desktop:qt` — Native Linux Qt6 Backend (`linuxX64`)

## Overview
`:desktop:qt` is the native Linux Qt6 backend for GenericUI (`linuxX64`).

### Key Highlights
- **Genuine Qt6 System Widgets (`BootstrapType.NONE` Default)**: When `getBootstrap().type == BootstrapType.NONE` (the default), widgets render as untinted native Qt6 `QStyle` / `QPalette` controls with zero Bootstrap CSS styling.
- **Semantic `BootstrapType` Tinting**: Setting a semantic `BootstrapType` (`DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, `LINK`) tints the native `QWidget` palette with the semantic color (`outline = true/false`). Unsupported CSS properties and transitions are silently ignored.
- **Strict User CSS Class Isolation**: `getClasses()`, `getClass()`, and `hasClass()` expose only user-added `customClasses`.
- **Custom Cursor Translation (`QCursor`)**: Converts any PNG, SVG, or `ImageBuffer` `CssCustomCursor` into a `QPixmap` + `QCursor(pixmap, hotspotX, hotspotY)`.
- **Live System Theme Detection (`ThemeMode.AUTO`)**: Queries `QGuiApplication::styleHints()->colorScheme()` and listens for `colorSchemeChanged`.

See [`../docs/qt.md`](../docs/qt.md) for full documentation.
