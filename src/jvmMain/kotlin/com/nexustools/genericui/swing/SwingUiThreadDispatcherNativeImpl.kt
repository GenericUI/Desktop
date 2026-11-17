package com.nexustools.genericui.swing

import com.nexustools.genericui.jvm.JvmUiThreadDispatcherBase
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

/**
 * Java Swing implementation of [com.nexustools.genericui.threading.UiThreadDispatcherNativeImpl]
 * extending [JvmUiThreadDispatcherBase].
 */
class SwingUiThreadDispatcherNativeImpl : JvmUiThreadDispatcherBase(
    logicThreadName = LOGIC_THREAD_NAME
) {
    override fun isOnToolkitMainThread(): Boolean = SwingUtilities.isEventDispatchThread()

    override fun canPostToToolkitUiThread(): Boolean = !GraphicsEnvironment.isHeadless()

    override fun postToToolkitUiThread(task: () -> Unit) {
        if (!GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(task)
        }
    }

    override fun runOnToolkitUiThreadSync(action: () -> Unit): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        SwingUtilities.invokeAndWait(action)
        return true
    }

    companion object {
        const val LOGIC_THREAD_NAME: String = "GenericUI-Swing-Logic"
    }
}
