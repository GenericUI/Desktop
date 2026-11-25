@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject

/**
 * Strongly held Objective-C action target bridging AppKit `NSControl` / `NSMenuItem` target-action
 * callbacks into Kotlin handlers. Owners MUST hold a strong reference to this instance because
 * AppKit stores `NSControl.target` weakly.
 */
internal class MacosControlActionTarget(
    private val onTriggered: (NSObject?) -> Unit
) : NSObject() {

    val actionSelector = NSSelectorFromString("onAction:")

    @ObjCAction
    fun onAction(sender: NSObject?) {
        onTriggered(sender)
    }
}
