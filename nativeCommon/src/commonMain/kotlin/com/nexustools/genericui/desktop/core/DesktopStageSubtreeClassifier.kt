package com.nexustools.genericui.desktop.core

/**
 * Pluggable classifier registered by extensions so [DesktopNativeWidget.isInsideIsolatedStageSubtree]
 * can identify extension-specific stage/overlay subtrees without hardcoding extension class names
 * inside `:desktop:nativeCommon`.
 */
fun interface DesktopStageSubtreeClassifier {
    fun isIsolatedStageWidget(widget: DesktopNativeWidget): Boolean
}
