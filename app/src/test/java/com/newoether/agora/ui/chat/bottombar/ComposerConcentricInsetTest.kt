package com.newoether.agora.ui.chat.bottombar

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Rounded children of the non-expanded composer are concentric with its 28 dp outer corners. */
class ComposerConcentricInsetTest {
    @Test fun childRadiusPlusInsetEqualsOuterRadius() {
        assertEquals(28.dp, CHAT_BOTTOM_BAR_OUTER_RADIUS)
        assertEquals(4.dp, COMPOSER_CONTROLS_INSET)
        assertEquals(8.dp, COMPOSER_EXPAND_BUTTON_INSET)
        assertEquals(CHAT_BOTTOM_BAR_OUTER_RADIUS, COMPOSER_CONTROL_HEIGHT / 2 + COMPOSER_CONTROLS_INSET)
        assertEquals(CHAT_BOTTOM_BAR_OUTER_RADIUS, COMPOSER_EXPAND_BUTTON_SIZE / 2 + COMPOSER_EXPAND_BUTTON_INSET)
    }

    @Test fun layoutUsesTheSharedGeometry() {
        val layout = source("ChatComposerLayout.kt")
        // The host inset is the controls inset on the sides and bottom and the expand inset on top;
        // the controls row adds no side padding and the expand button adds only the end difference.
        assertTrue(layout.contains("padding(start = COMPOSER_CONTROLS_INSET, end = COMPOSER_CONTROLS_INSET, top = COMPOSER_EXPAND_BUTTON_INSET, bottom = COMPOSER_CONTROLS_INSET)"))
        assertTrue(layout.contains("Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp),"))
        assertTrue(layout.contains("padding(end = COMPOSER_EXPAND_BUTTON_INSET - COMPOSER_CONTROLS_INSET).size(COMPOSER_EXPAND_BUTTON_SIZE)"))
        assertTrue(source("ComposerSendButton.kt").contains("Modifier.size(COMPOSER_CONTROL_HEIGHT)"))
        assertTrue(source("ChatBottomBarComponents.kt").contains(".height(COMPOSER_CONTROL_HEIGHT)"))
    }

    private fun source(name: String): File = listOf("src/main/java", "app/src/main/java")
        .map { File(it, "com/newoether/agora/ui/chat/bottombar/$name") }
        .first { it.isFile }

    private fun File.contains(text: String): Boolean = readText().contains(text)
}
