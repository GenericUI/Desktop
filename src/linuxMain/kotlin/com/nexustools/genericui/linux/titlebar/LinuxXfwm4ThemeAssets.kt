package com.nexustools.genericui.linux.titlebar

/**
 * Decoded Xfwm4 window decoration assets including `themerc` properties, corner/title slices,
 * and per-button state sprites (`active`, `inactive`, `prelight`, `pressed`, plus toggled variants).
 */
data class LinuxXfwm4ThemeAssets(
    val themeName: String,
    val properties: Map<String, String> = emptyMap(),
    val topLeftActive: LinuxXfwm4SpriteSet? = null,
    val topLeftInactive: LinuxXfwm4SpriteSet? = null,
    val topRightActive: LinuxXfwm4SpriteSet? = null,
    val topRightInactive: LinuxXfwm4SpriteSet? = null,
    val titleActiveSlices: List<LinuxXfwm4SpriteSet> = emptyList(),
    val titleInactiveSlices: List<LinuxXfwm4SpriteSet> = emptyList(),
    val buttonActive: Map<LinuxTitleButtonKind, LinuxXfwm4SpriteSet> = emptyMap(),
    val buttonInactive: Map<LinuxTitleButtonKind, LinuxXfwm4SpriteSet> = emptyMap(),
    val buttonPrelight: Map<LinuxTitleButtonKind, LinuxXfwm4SpriteSet> = emptyMap(),
    val buttonPressed: Map<LinuxTitleButtonKind, LinuxXfwm4SpriteSet> = emptyMap(),
    val buttonToggledActive: Map<LinuxTitleButtonKind, LinuxXfwm4SpriteSet> = emptyMap(),
    val buttonToggledInactive: Map<LinuxTitleButtonKind, LinuxXfwm4SpriteSet> = emptyMap(),
    val buttonToggledPrelight: Map<LinuxTitleButtonKind, LinuxXfwm4SpriteSet> = emptyMap(),
    val buttonToggledPressed: Map<LinuxTitleButtonKind, LinuxXfwm4SpriteSet> = emptyMap()
) {
    fun resolveButtonSprite(
        kind: LinuxTitleButtonKind,
        isActiveWindow: Boolean,
        state: LinuxTitleButtonState,
        toggled: Boolean = false
    ): LinuxXfwm4SpriteSet? {
        if (toggled) {
            val toggledSprite = when (state) {
                LinuxTitleButtonState.PRESSED ->
                    buttonToggledPressed[kind] ?: buttonToggledPrelight[kind] ?: buttonToggledActive[kind]
                LinuxTitleButtonState.HOVERED ->
                    buttonToggledPrelight[kind] ?: buttonToggledActive[kind]
                LinuxTitleButtonState.NORMAL, LinuxTitleButtonState.DISABLED ->
                    if (isActiveWindow) buttonToggledActive[kind] else (buttonToggledInactive[kind] ?: buttonToggledActive[kind])
            }
            if (toggledSprite != null) return toggledSprite
        }
        return when (state) {
            LinuxTitleButtonState.PRESSED ->
                buttonPressed[kind] ?: buttonPrelight[kind] ?: buttonActive[kind]
            LinuxTitleButtonState.HOVERED ->
                buttonPrelight[kind] ?: buttonActive[kind]
            LinuxTitleButtonState.NORMAL, LinuxTitleButtonState.DISABLED ->
                if (isActiveWindow) buttonActive[kind] else (buttonInactive[kind] ?: buttonActive[kind])
        }
    }
}
