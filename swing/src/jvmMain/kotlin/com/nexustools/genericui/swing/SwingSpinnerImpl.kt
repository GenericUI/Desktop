package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.feedback.SpinnerNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.theme.SystemThemeDetector
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JProgressBar

/**
 * Java Swing indeterminate `JProgressBar` implementation of [SpinnerNativeImpl].
 */
class SwingSpinnerImpl : SwingBaseNativeImpl(JProgressBar().apply { isIndeterminate = true }, "spinner-border"), SpinnerNativeImpl<JComponent> {
    override val nativeWidget: JProgressBar get() = super.nativeWidget as JProgressBar

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        val bType = getType() ?: BootstrapType.NONE
        if (bType != BootstrapType.NONE) {
            val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
            val tint = BootstrapColorPalette.resolveColor(bType, isDark, BootstrapColorPalette.activeThemeConfig)
            nativeWidget.foreground = Color(tint.r, tint.g, tint.b, tint.a)
        }
    }

    override fun setGrowStyle(grow: Boolean) {
        nativeWidget.putClientProperty("growStyle", grow)
    }

    override fun setLabel(label: String?) {
        nativeWidget.string = label ?: ""
        nativeWidget.isStringPainted = !label.isNullOrEmpty()
    }
}
