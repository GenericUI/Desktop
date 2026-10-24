package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.layout.DividerNativeImpl
import javax.swing.JComponent
import javax.swing.JSeparator
import javax.swing.SwingConstants

/**
 * Java Swing `JSeparator` implementation of [DividerNativeImpl].
 */
class SwingDividerImpl : SwingBaseNativeImpl(JSeparator(SwingConstants.HORIZONTAL), "hr"), DividerNativeImpl<JComponent> {
    override val nativeWidget: JSeparator = super.nativeWidget as JSeparator
}
