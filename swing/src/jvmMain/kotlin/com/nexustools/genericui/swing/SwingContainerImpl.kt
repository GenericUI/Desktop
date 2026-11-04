package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing `JPanel` implementation of [ContainerNativeImpl].
 */
open class SwingContainerImpl(
    classPrefix: String = "container",
    panel: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
) : SwingBaseContainerNativeImpl(panel, classPrefix), ContainerNativeImpl<JComponent>
