package com.nexustools.genericui.macos

import com.nexustools.genericui.component.feedback.ProgressBar
import com.nexustools.genericui.component.fragment.Fragment
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.lazy.LazyList
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Badge
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MacosLazyListSetItemsReconciliationTest {

    private class CardRefs(
        val card: Container,
        val title: Text,
        val progress: ProgressBar,
        val badge: Badge
    )

    @Test
    fun lazyListSetItemsReconcilesAcrossEmptyCardsUpdatesShrinkAndGrow() {
        val prevFactory = GenericUI.activeFactory
        val factory = MacosNativeFactory()
        val window = Window(title = "LazyList Reconciliation", initialSize = Size(1280, 720))
        try {
            val rootContainer = Container()
            val lazyList = LazyList(estimatedItemExtentPx = 120f, overscanItems = 2)
            rootContainer.addChild(lazyList)

            val fragment = Fragment().apply { addChild(rootContainer) }
            window.setFragment(fragment)
            window.show(factory)

            val lazyImpl = lazyList.ensureNativeImpl() as? DesktopLazyListNativeImpl<*>
            assertNotNull(lazyImpl)
            val lazyWidget = lazyImpl.nativeWidget
            val rootWidget = rootContainer.ensureNativeImpl()?.nativeWidget as DesktopNativeWidget

            fun assertReconciledWindow(expectedCount: Int, forbiddenWidgets: Set<DesktopNativeWidget> = emptySet()) {
                val childImpls = lazyImpl.attachedChildImplsSnapshot()
                val childWidgets = lazyWidget.children
                val firstIdx = lazyImpl.firstActiveIndex
                val lastIdx = lazyImpl.lastActiveIndex
                val expectedIndices = if (expectedCount > 0 && lastIdx >= firstIdx) {
                    (firstIdx..lastIdx).toList()
                } else {
                    emptyList()
                }
                assertEquals(expectedIndices.size, childImpls.size)
                assertEquals(expectedIndices.size, childWidgets.size)

                // Zero duplicates (identity-based) and zero disposed/stale items
                val seenImpls = mutableListOf<Any>()
                val seenWidgets = mutableListOf<DesktopNativeWidget>()
                for (i in childImpls.indices) {
                    val cImpl = childImpls[i]
                    val cWidget = childWidgets[i]
                    assertFalse(cImpl.isDisposed, "Child impl at position $i must not be disposed")
                    assertFalse(cWidget.disposed, "Child widget at position $i must not be disposed")
                    assertTrue(cImpl.nativeWidget === cWidget, "childImpls[$i] widget mismatch")
                    assertFalse(seenImpls.any { it === cImpl }, "Duplicate NativeImpl at position $i")
                    assertFalse(seenWidgets.any { it === cWidget }, "Duplicate DesktopNativeWidget at position $i")
                    assertFalse(forbiddenWidgets.any { it === cWidget }, "Stale widget leaked at position $i")
                    assertEquals(expectedIndices[i].toString(), cWidget.attributes["data-lazy-index"])
                    seenImpls.add(cImpl)
                    seenWidgets.add(cWidget)
                }

                val mat = lazyImpl.materializedItemsSnapshot()
                assertEquals(expectedIndices.toSet(), mat.keys)
                for (idx in expectedIndices) {
                    val mImpl = mat[idx]
                    assertNotNull(mImpl)
                    assertFalse(mImpl.isDisposed)
                }
            }

            // 1. 4 empty-state items
            val emptyContainers = mutableListOf<Container>()
            lazyList.setItems(4, onLoadItem = { idx ->
                Container().apply {
                    addChild(Text("Empty row $idx"))
                    emptyContainers.add(this)
                }
            })
            assertReconciledWindow(4)
            val emptyWidgets = emptyContainers.mapNotNull { it.nativeImpl?.nativeWidget as? DesktopNativeWidget }.toSet()
            assertEquals(4, emptyWidgets.size)

            // 2. Transition to 35 card items; verify none of the 4 empty-state widgets remain
            val activeCards = LinkedHashMap<Int, CardRefs>()
            val cardContainers35 = mutableListOf<Container>()
            lazyList.setItems(35, onLoadItem = { idx ->
                val title = Text("Episode $idx")
                val progress = ProgressBar(progress = 0.1f).apply { type = BootstrapType.PRIMARY }
                val badge = Badge("Queued", type = BootstrapType.SECONDARY)
                val card = Container().apply {
                    addChild(title)
                    addChild(progress)
                    addChild(badge)
                }
                activeCards[idx] = CardRefs(card, title, progress, badge)
                cardContainers35.add(card)
                card
            })
            val cardWidgets35 = cardContainers35.mapNotNull { it.nativeImpl?.nativeWidget as? DesktopNativeWidget }.toSet()
            assertReconciledWindow(35, forbiddenWidgets = emptyWidgets)

            // 3. Live leaf & style updates on a visible card propagate subtreeMutationVersion up ancestors
            val targetIdx = lazyImpl.firstActiveIndex
            val targetRefs = activeCards[targetIdx]!!
            val cardWidget = targetRefs.card.ensureNativeImpl()?.nativeWidget as DesktopNativeWidget
            val titleWidget = targetRefs.title.ensureNativeImpl()?.nativeWidget as DesktopNativeWidget
            val progressWidget = targetRefs.progress.ensureNativeImpl()?.nativeWidget as DesktopNativeWidget
            val badgeWidget = targetRefs.badge.ensureNativeImpl()?.nativeWidget as DesktopNativeWidget

            fun assertBumpsAncestors(actionName: String, block: () -> Unit) {
                val prevCard = cardWidget.subtreeMutationVersion
                val prevLazy = lazyWidget.subtreeMutationVersion
                val prevRoot = rootWidget.subtreeMutationVersion
                block()
                assertTrue(cardWidget.subtreeMutationVersion > prevCard, "$actionName should bump card version")
                assertTrue(lazyWidget.subtreeMutationVersion > prevLazy, "$actionName should bump LazyList version")
                assertTrue(rootWidget.subtreeMutationVersion > prevRoot, "$actionName should bump root ancestor version")
            }

            assertBumpsAncestors("Text.setText") {
                targetRefs.title.text = "Episode $targetIdx (Playing)"
                assertTrue(titleWidget.subtreeMutationVersion > 0L)
            }
            assertBumpsAncestors("ProgressBar.setProgress") {
                targetRefs.progress.progress = 0.72f
                assertTrue(progressWidget.subtreeMutationVersion > 0L)
            }
            assertBumpsAncestors("ProgressBar.setIndeterminate") {
                targetRefs.progress.isIndeterminate = true
            }
            assertBumpsAncestors("ProgressBar.setType") {
                targetRefs.progress.type = BootstrapType.SUCCESS
            }
            assertBumpsAncestors("Badge.setType") {
                targetRefs.badge.type = BootstrapType.SUCCESS
                assertTrue(badgeWidget.subtreeMutationVersion > 0L)
            }
            assertBumpsAncestors("Badge.setBackgroundType") {
                targetRefs.badge.backgroundType = BootstrapType.INFO
            }
            assertBumpsAncestors("Badge.setTextColorType") {
                targetRefs.badge.textColorType = BootstrapType.LIGHT
            }
            assertBumpsAncestors("Badge.setBorderType") {
                targetRefs.badge.borderType = BootstrapType.WARNING
            }
            assertBumpsAncestors("Badge.setPadding") {
                targetRefs.badge.setPadding(EdgeInsets(2, 6, 2, 6))
            }
            assertBumpsAncestors("Badge.setComponentStyle") {
                targetRefs.badge.getStyle().setBackgroundColor(RgbaColor(20, 120, 220))
            }
            assertBumpsAncestors("Container.addClass") {
                targetRefs.card.addClass("active")
            }
            assertBumpsAncestors("Container.removeClass") {
                targetRefs.card.removeClass("active")
            }

            // Scroll downward inside 35 items, verify version bump and clean reconciliation
            val preScrollLazyVer = lazyWidget.subtreeMutationVersion
            val preScrollRootVer = rootWidget.subtreeMutationVersion
            lazyList.onViewportChanged(scrollOffsetPx = 1440f, viewportExtentPx = 840f)
            assertTrue(lazyWidget.subtreeMutationVersion > preScrollLazyVer)
            assertTrue(rootWidget.subtreeMutationVersion > preScrollRootVer)
            assertReconciledWindow(35, forbiddenWidgets = emptyWidgets)

            // 4. Shrink from 35 items -> 2 items
            val containers2 = mutableListOf<Container>()
            lazyList.setItems(2, onLoadItem = { idx ->
                Container().apply {
                    addChild(Text("Compact $idx"))
                    containers2.add(this)
                }
            })
            val widgets2 = containers2.mapNotNull { it.nativeImpl?.nativeWidget as? DesktopNativeWidget }.toSet()
            assertReconciledWindow(2, forbiddenWidgets = emptyWidgets + cardWidgets35)

            // 5. Grow from 2 items -> 25 items
            lazyList.setItems(25, onLoadItem = { idx ->
                Container().apply {
                    addChild(Text("Grown $idx"))
                }
            })
            assertReconciledWindow(25, forbiddenWidgets = emptyWidgets + cardWidgets35 + widgets2)
        } finally {
            window.dispose()
            GenericUI.activeFactory = prevFactory
        }
    }
}
