# `:desktop:linuxCommon` — Linux XDG Base Directory & Preference Store

## Overview
`:desktop:linuxCommon` is the `linuxX64` KMP module shared by the Linux desktop backends (`:desktop:gtk` and `:desktop:qt`). It depends on `:common`, `:systemCommon`, `:desktop:common`, and `:desktop:nativeCommon`.

---

## Key Classes (`com.nexustools.genericui.linux`)

- **`LinuxXdgConfigDirectoryImpl`**: Resolves `$XDG_CONFIG_HOME/<appId>` (or `~/.config/<appId>`) and creates directories on demand using POSIX `mkdir`.
- **`LinuxXdgCacheDirectoryImpl`**: Resolves `$XDG_CACHE_HOME/<appId>` (or `~/.cache/<appId>`) for application cache storage.
- **`LinuxXdgPreferenceStoreImpl`**: Implements `PreferenceStore` on Linux desktop targets, persisting key-value pairs and `@PreferenceObject` instances in `$XDG_CONFIG_HOME/<appId>/preferences.properties` using `SystemStorageKeyHelper`.
