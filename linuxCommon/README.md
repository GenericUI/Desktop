# `:desktop:linuxCommon` — Linux XDG Desktop Common Runtime

> **Parent Submodule**: [`:desktop` (`../README.md`)](../README.md) | **Root Repository**: [**GenericUI (`../../README.md`)**](../../README.md)  
> **Sibling Desktop Modules**: [`:desktop:common`](../common/README.md) · [`:desktop:nativeCommon`](../nativeCommon/README.md) · [`:desktop:gtk`](../docs/gtk.md) · [`:desktop:qt`](../docs/qt.md) · [`:desktop:windows`](../docs/windows.md) · [`:desktop:macos`](../docs/macos.md) · [`:desktop:swing`](../docs/swing.md)

The **`:desktop:linuxCommon`** module (`com.nexustools.genericui.linux.*`) is the Linux-specific native desktop module inherited by both **`:desktop:gtk`** (GTK4) and **`:desktop:qt`** (Qt6) on `linuxX64` and `linuxArm64`. It backs the **`linuxCommon`** variant scope (`AppTarget.LINUX_COMMON`, specificity rank `4`) in the sourceSet hierarchy (`common` $\rightarrow$ `systemCommon` $\rightarrow$ `desktopCommon` $\rightarrow$ `nativeDesktopCommon` $\rightarrow$ `linuxCommon` $\rightarrow$ `gtk` / `qt`).

---

## Core Responsibilities & Classes

`:desktop:linuxCommon` centralizes Linux XDG Base Directory Specification resolution (`$XDG_CONFIG_HOME`, `$XDG_CACHE_HOME`) and binary JSON (`.bson`) preference persistence so `:desktop:gtk` and `:desktop:qt` use identical configuration and cache storage on Linux workstations:

| Package | Class | Responsibility |
| :--- | :--- | :--- |
| `com.nexustools.genericui.linux` | `LinuxXdgConfigDirectoryImpl` | Implements `UserDirectoryNativeImpl` for `ConfigDirectory` on Linux, resolving `$XDG_CONFIG_HOME/<appName>` (or defaulting to `$HOME/.config/<appName>`). |
| `com.nexustools.genericui.linux` | `LinuxXdgCacheDirectoryImpl` | Implements `UserDirectoryNativeImpl` for `CacheDirectory` on Linux, resolving `$XDG_CACHE_HOME/<appName>` (or defaulting to `$HOME/.cache/<appName>`). |
| `com.nexustools.genericui.linux` | `LinuxXdgPreferenceStoreImpl` | Implements `PreferenceStoreNativeImpl` for Linux desktop targets, storing each preference key in its own binary JSON file (`~/.config/<appName>/<key>.bson`) encoded and decoded via `BinaryJson`. Subclassed directly by `GtkPreferenceStoreImpl` and `QtPreferenceStoreImpl`. |

---

## Detailed Documentation

See [**`../docs/linuxCommon.md`**](../docs/linuxCommon.md) for architecture details and code examples.
