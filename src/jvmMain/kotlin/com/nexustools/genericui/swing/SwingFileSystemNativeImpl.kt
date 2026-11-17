package com.nexustools.genericui.swing

import com.nexustools.genericui.jvm.JvmFileSystemBase
import com.nexustools.genericui.jvm.JvmStreamFileReadableNativeImpl
import com.nexustools.genericui.jvm.JvmStreamFileWritableNativeImpl
import java.io.File
import javax.swing.SwingUtilities

/**
 * Java Swing implementation of [com.nexustools.genericui.nativeimpl.io.FileSystemNativeImpl]
 * backed by [JvmFileSystemBase], [SwingFileReadableNativeImpl], and [SwingFileWritableNativeImpl].
 */
class SwingFileSystemNativeImpl : JvmFileSystemBase(
    isOnMainThread = { SwingUtilities.isEventDispatchThread() }
) {
    override fun createDiskReadStream(file: File): JvmStreamFileReadableNativeImpl =
        SwingFileReadableNativeImpl(file)

    override fun createDiskWriteStream(file: File, append: Boolean): JvmStreamFileWritableNativeImpl =
        SwingFileWritableNativeImpl(file, append = append)
}
