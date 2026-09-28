package com.newoether.agora.ui.chat.bottombar

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Non-expanded composer geometry: concentric controls and a 14 dp corner inset for text and icon. */
class ComposerConcentricInsetTest {
    @Test fun controlsAreConcentricWithTheOuterCorners() {
        assertEquals(28.dp, CHAT_BOTTOM_BAR_OUTER_RADIUS)
        assertEquals(44.dp, COMPOSER_CONTROL_HEIGHT)
        assertEquals(6.dp, COMPOSER_CONTROLS_INSET)
        assertEquals(CHAT_BOTTOM_BAR_OUTER_RADIUS, COMPOSER_CONTROL_HEIGHT / 2 + COMPOSER_CONTROLS_INSET)
    }

    @Test fun textAndExpandIconSitAtTheCornerInset() {
        assertEquals(14.dp, COMPOSER_CORNER_CONTENT_INSET)
        val layout = source("ChatComposerLayout.kt")
        assertTrue(layout.contains("padding(start = COMPOSER_HOST_SIDE_PADDING, end = COMPOSER_HOST_SIDE_PADDING, top = COMPOSER_HOST_TOP_PADDING, bottom = COMPOSER_CONTROLS_INSET)"))
        assertTrue(layout.contains("start = COMPOSER_CORNER_CONTENT_INSET - COMPOSER_HOST_SIDE_PADDING,"))
        assertTrue(layout.contains("top = COMPOSER_CORNER_CONTENT_INSET - COMPOSER_HOST_TOP_PADDING,"))
        assertTrue(layout.contains("Modifier.offset(x = COMPOSER_HOST_SIDE_PADDING - EXPAND_BUTTON_EDGE_INSET, y = EXPAND_BUTTON_EDGE_INSET - COMPOSER_HOST_TOP_PADDING).size(COMPOSER_EXPAND_BUTTON_SIZE)"))
        assertTrue(layout.contains("EXPAND_BUTTON_EDGE_INSET = COMPOSER_CORNER_CONTENT_INSET - (COMPOSER_EXPAND_BUTTON_SIZE - COMPOSER_EXPAND_ICON_SIZE) / 2"))
        assertTrue(layout.contains("start = COMPOSER_CONTROLS_INSET - COMPOSER_HOST_SIDE_PADDING, end = COMPOSER_CONTROLS_INSET - COMPOSER_HOST_SIDE_PADDING"))
        assertTrue(source("ComposerSendButton.kt").contains("Modifier.size(COMPOSER_CONTROL_HEIGHT)"))
        assertTrue(source("ChatBottomBarComponents.kt").contains(".height(COMPOSER_CONTROL_HEIGHT)"))
        // Capsule children: 32 dp buttons 6 dp from its ends, so 16 + 6 = 22 = capsule radius.
        assertTrue(source("ChatBottomBarComponents.kt").contains(".padding(horizontal = COMPOSER_CONTROL_HEIGHT / 2 - 16.dp)"))
    }

    private fun source(name: String): File = listOf("src/main/java", "app/src/main/java")
        .map { File(it, "com/newoether/agora/ui/chat/bottombar/$name") }
        .first { it.isFile }

    private fun File.contains(text: String): Boolean = readText().contains(text)
}
