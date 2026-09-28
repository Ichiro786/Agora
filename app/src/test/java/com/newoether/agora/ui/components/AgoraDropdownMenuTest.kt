package com.newoether.agora.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgoraDropdownMenuTest {
    private val density = Density(1f)

    private fun cornerOf(menuCorner: Int): Float {
        val shape = dropdownItemShape(RoundedCornerShape(menuCorner.dp), density) as RoundedCornerShape
        return shape.topStart.toPx(Size(100f, 48f), density)
    }

    @Test
    fun itemCornerIsMenuCornerMinusInset() {
        assertEquals(4f, cornerOf(12), 0.001f)
        assertEquals(8f, cornerOf(16), 0.001f)
    }

    @Test
    fun smallOrNonRoundedMenusGiveSquareItems() {
        assertEquals(0f, cornerOf(4), 0.001f)
        val square = dropdownItemShape(RectangleShape, density) as RoundedCornerShape
        assertEquals(0f, square.topStart.toPx(Size(100f, 48f), density), 0.001f)
    }

    @Test
    fun appUsesOnlyTheSharedDropdownWrappers() {
        val root = File("src/main/java")
        val raw = Regex("""(?<![\w.])(DropdownMenu|DropdownMenuItem|ExposedDropdownMenu)\(""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AgoraDropdownMenu.kt" }
            .filter { raw.containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()
        assertTrue("Use AgoraDropdownMenu / AgoraDropdownMenuItem in: $offenders", offenders.isEmpty())
    }
}
