package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing `JPanel` implementation of [ContainerNativeImpl].
 */
class SwingContainerImpl(
    classPrefix: String = "container"
) : SwingBaseContainerNativeImpl(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)), classPrefix), ContainerNativeImpl<JComponent>
