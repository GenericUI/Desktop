package com.nexustools.genericui.swing

import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.surface.styled.StyledComponent
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.style.css.CompiledStyleRule
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssStyleSheet
import com.nexustools.genericui.style.css.PrecompiledStyleSheet
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class SwingCursorHideAndRestoreTest {

    @Test
    fun cssCursorNoneClassHidesCursorAndRemovingClassRestoresDefaultCursor() {
        Application.current?.dispose()
        val prevFactory = GenericUI.activeFactory
        val factory = SwingNativeFactory()
        GenericUI.initialize(factory)

        val sheet = CssStyleSheet.parse(
            """
            .cursor-hidden {
                cursor: none;
            }
            """.trimIndent()
        )
        val precompiled = object : PrecompiledStyleSheet {
            override val name: String = "CursorHideTest"
            override val sourcePath: String = "test/cursor.css"
            override val rawCss: String = ""
            override val compiledRules: List<CompiledStyleRule> = emptyList()
            override val styleSheet: CssStyleSheet = sheet
        }

        val target = StyledComponent()
        val root = Container().apply { addChild(target) }
        val app = object : Application(initialContent = root) {}
        try {
            app.addStyleSheet(precompiled)
            app.mainWindow.show(factory)
            app.applyActiveStyleSheetsToRootContent()
            val impl = target.ensureNativeImpl() as SwingBaseNativeImpl
            assertSame(Cursor.getDefaultCursor(), impl.nativeWidget.cursor)

            target.addClass("cursor-hidden")
            app.applyActiveStyleSheetsToRootContent()
            if (!GraphicsEnvironment.isHeadless()) {
                assertEquals(Cursor.CUSTOM_CURSOR, impl.nativeWidget.cursor.type)
                assertEquals("none", impl.nativeWidget.cursor.name)
                assertNotEquals(Cursor.getDefaultCursor(), impl.nativeWidget.cursor)
            } else {
                assertNotNull(impl.nativeWidget.cursor)
            }

            target.removeClass("cursor-hidden")
            app.applyActiveStyleSheetsToRootContent()
            assertSame(Cursor.getDefaultCursor(), impl.nativeWidget.cursor)
        } finally {
            app.dispose()
            prevFactory?.let { GenericUI.initialize(it) }
        }
    }

    @Test
    fun applyComputedStyleAndComponentStyleRestoreDefaultCursorWhenCleared() {
        val impl = SwingStyledComponentImpl()

        // Directly applying ComputedStyle(cursor: none) then clearing it with ComputedStyle()
        impl.applyComputedStyle(ComputedStyle(cursor = "none", customCursor = CssCursor.NONE))
        if (!GraphicsEnvironment.isHeadless()) {
            assertEquals(Cursor.CUSTOM_CURSOR, impl.nativeWidget.cursor.type)
            assertEquals("none", impl.nativeWidget.cursor.name)
        }

        impl.applyComputedStyle(ComputedStyle())
        assertSame(Cursor.getDefaultCursor(), impl.nativeWidget.cursor)

        // Reverting via explicit "default" keyword string without customCursor
        impl.applyComputedStyle(ComputedStyle(cursor = "none"))
        if (!GraphicsEnvironment.isHeadless()) {
            assertEquals(Cursor.CUSTOM_CURSOR, impl.nativeWidget.cursor.type)
        }
        impl.applyComputedStyle(ComputedStyle(cursor = "default"))
        assertSame(Cursor.getDefaultCursor(), impl.nativeWidget.cursor)

        // Programmatic setCustomCursor is preserved across unrelated ComputedStyle updates without prior style cursor
        impl.setCustomCursor(CssCursor.POINTER)
        assertEquals(Cursor.HAND_CURSOR, impl.nativeWidget.cursor.type)
        impl.applyComputedStyle(ComputedStyle())
        assertEquals(Cursor.HAND_CURSOR, impl.nativeWidget.cursor.type)

        // Clearing programmatic cursor restores default cursor
        impl.setCustomCursor(null)
        assertSame(Cursor.getDefaultCursor(), impl.nativeWidget.cursor)

        // Inline ComponentStyle cursor cleared back to null restores default cursor
        impl.applyComponentStyle(ComponentStyle().apply { setCursor(CssCursor.NONE) })
        if (!GraphicsEnvironment.isHeadless()) {
            assertEquals(Cursor.CUSTOM_CURSOR, impl.nativeWidget.cursor.type)
        }
        impl.applyComponentStyle(ComponentStyle())
        assertSame(Cursor.getDefaultCursor(), impl.nativeWidget.cursor)
    }
}
