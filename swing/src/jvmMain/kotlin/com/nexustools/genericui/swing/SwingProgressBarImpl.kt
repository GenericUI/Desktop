package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.feedback.ProgressBarNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.theme.SystemThemeDetector
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JProgressBar

/**
 * Java Swing `JProgressBar` implementation of [ProgressBarNativeImpl].
 */
class SwingProgressBarImpl : SwingBaseNativeImpl(JProgressBar(0, 100), "progress"), ProgressBarNativeImpl<JComponent> {
    override val nativeWidget: JProgressBar get() = super.nativeWidget as JProgressBar

    private var progressVal: Float = 0.0f
    private var indeterminateFlag: Boolean = false
    private var busyFlag: Boolean = false

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        val bType = getType() ?: BootstrapType.NONE
        if (bType != BootstrapType.NONE) {
            val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
            val tint = BootstrapColorPalette.resolveColor(bType, isDark, BootstrapColorPalette.activeThemeConfig)
            nativeWidget.foreground = Color(tint.r, tint.g, tint.b, tint.a)
        }
    }

    override fun setProgress(progress: Float) {
        progressVal = progress.coerceIn(0.0f, 1.0f)
        nativeWidget.value = (progressVal * 100.0f).toInt()
    }

    override fun getProgress(): Float = progressVal

    override fun setIndeterminate(indeterminate: Boolean) {
        indeterminateFlag = indeterminate
        nativeWidget.isIndeterminate = indeterminateFlag || busyFlag
    }

    override fun isIndeterminate(): Boolean = indeterminateFlag

    override fun setBusy(busy: Boolean) {
        busyFlag = busy
        nativeWidget.isIndeterminate = indeterminateFlag || busyFlag
    }

    override fun isBusy(): Boolean = busyFlag

    override fun setLabel(label: String?) {
        nativeWidget.string = label ?: ""
        nativeWidget.isStringPainted = !label.isNullOrEmpty()
    }
}
