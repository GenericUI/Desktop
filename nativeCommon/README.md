# `:desktop:nativeCommon` — Native C-Interop Desktop Foundation

## Overview
`:desktop:nativeCommon` provides the shared Kotlin/Native widget, container, window, styling, docking, lazy viewport, user directory, and libjpeg implementation for the four native cinterop desktop backends (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, and `:desktop:qt`). It targets `mingwX64()`, `macosX64()`, `macosArm64()`, and `linuxX64()`, and depends on `:common`, `:systemCommon`, and `:desktop:common`.

---

## Key Packages & Classes

- **`com.nexustools.genericui.desktop.core` & `nativecommon` (`DesktopBaseNativeImpl`, `DesktopRenderableNativeImpl`, `DesktopControlTintRenderUtil`)**:
  - **`BootstrapType.NONE` Default & Offscreen Sprite Tinting (`DesktopControlTintRenderUtil`)**: When `styleSnapshot.type == BootstrapType.NONE` (the default), native desktop backends render the 100% unmodified genuine OS system control. When `BootstrapType != BootstrapType.NONE`, each backend renders the native OS control with **no text** into an offscreen ARGB buffer, calls `DesktopControlTintRenderUtil.tintNativeControlArgbBuffer` (`ThemeConfig` / `BootstrapColorPalette`) to color-tint the native control pixels while preserving native 3D bevels, borders, and hover/pressed/disabled states, blits the tinted sprite, and overlays contrasting text and icons on top.
  - **System Backend Typography vs. Aesthetic Style Restraints**: Typography properties (`fontFamily`, `fontSize`, `fontWeight`, `fontStyle`, `color`, `lineHeight`, `letterSpacing`, `textAlign`, etc.) are honored on text elements (`Label`, `Text`, `Heading`, `Badge`, `Blockquote`, `CodeBlock`, `MarkdownView`). Aesthetic skinning and font overrides on `ComponentStyle` are **silently ignored** on standard controls/containers (which can only be tinted via `BootstrapType`), while `StyledContainer` and `StyledButton` honor full `ComponentStyle` aesthetics.
  - **Strict User CSS Class Isolation**: `addClass`, `removeClass`, `hasClass`, `getClasses()`, and `getClass()` read and mutate exclusively `styleSnapshot.customClasses`. `ComponentBootstrap` (`applyComponentBootstrap`) enum utilities are evaluated directly by the native layout/rendering engines.
  - **`CssCustomCursor`**: `setCustomCursor(cursor: CssCursorValue?)` stores custom cursors for translation to `.cur`/`HCURSOR`, `NSCursor`, `GdkCursor`, or `QCursor`.
- **`com.nexustools.genericui.desktop`**:
  - `DesktopBaseContainerNativeImpl`, `DesktopNativeWidget`, `DesktopWindowNativeImpl`, `DesktopApplicationNativeImpl` (managing `IApplication.addStyleSheet`/`removeStyleSheet` and `ThemeMode.AUTO`/`DARK`/`LIGHT`).
- **`com.nexustools.genericui.desktop.container` / `dock`**:
  - `DesktopMultiWindowDockCoordinator`, `DesktopDockWorkspaceNativeImpl`, `DesktopLazyListNativeImpl`, `DesktopLazyGridNativeImpl`, `DesktopTreeViewNativeImpl`.
- **`com.nexustools.genericui.desktop.system` & `image`**:
  - `DesktopConfigDirectoryHelper`, `DesktopUserDirectoryNativeImpl`, `DesktopLibJpegImageCodecImpl` (`cinterop/libjpeg.def`).

See [`../docs/nativeCommon.md`](../docs/nativeCommon.md) for detailed architecture documentation.
