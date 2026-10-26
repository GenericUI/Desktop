# `:desktop:linuxCommon` — Linux XDG Base Directory & Preference Store

## Overview
`:desktop:linuxCommon` is a `linuxX64` Kotlin/Native module providing XDG Base Directory Specification support for both `:desktop:gtk` and `:desktop:qt`.

---

## Key Classes (`com.nexustools.genericui.linux`)

| Class | Description |
|-------|-------------|
| `LinuxXdgConfigDirectoryImpl` | Resolves `$XDG_CONFIG_HOME/<appId>` (defaulting to `~/.config/<appId>`) and ensures directory creation via POSIX `mkdir` |
| `LinuxXdgCacheDirectoryImpl` | Resolves `$XDG_CACHE_HOME/<appId>` (defaulting to `~/.cache/<appId>`) for temporary and cached application data |
| `LinuxXdgPreferenceStoreImpl` | File-backed key-value `PreferenceStore` persisted under the application's XDG configuration directory using `SystemStorageKeyHelper` |

---

## Dependency Graph
```text
:common
  └── :systemCommon
        └── :desktop:common
              └── :desktop:nativeCommon
                    └── :desktop:linuxCommon
                          ├── :desktop:gtk
                          └── :desktop:qt
```
