# `:desktop:gtk` — Native Linux GTK4 Backend (`linuxX64`)

## Overview
`:desktop:gtk` is the native Linux GTK4 / Libadwaita backend for GenericUI (`linuxX64`).

### Key Highlights
- **Genuine GTK4 System Widgets (`BootstrapType.NONE` Default)**: When `getBootstrap().type == BootstrapType.NONE` (the default), widgets render as untinted native GTK4 / Adwaita controls with zero Bootstrap CSS styling.
- **Semantic `BootstrapType` Tinting**: Setting a semantic `BootstrapType` (`DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, `LINK`) tints the native `GtkWidget` with the semantic color (`outline = true/false`). Unsupported CSS properties and transitions are silently ignored.
- **Strict User CSS Class Isolation**: `getClasses()`, `getClass()`, and `hasClass()` expose only user-added `customClasses`.
- **Custom Cursor Translation (`GdkCursor`)**: Converts any PNG, SVG, or `ImageBuffer` `CssCustomCursor` into a `GdkTexture` + `gdk_cursor_new_from_texture` with `(hotspotX, hotspotY)`.
- **Live System Theme Detection (`ThemeMode.AUTO`)**: Tracks `GtkSettings` (`gtk-application-prefer-dark-theme`) and the FreeDesktop settings portal.

See [`../docs/gtk.md`](../docs/gtk.md) for full documentation.
