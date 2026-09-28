package com.newoether.agora.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/*
 * Every dropdown in the app goes through these wrappers so its items share one highlight geometry:
 * each item's ripple is inset by DROPDOWN_ITEM_INSET from the menu's sides, and Material already
 * leaves the same 8dp above the first and below the last item. The item corner is the menu corner
 * minus that inset, so the highlight runs parallel to the menu outline at every corner.
 */

/** Gap between a menu's edge and its items' highlight; equals Material's fixed vertical padding. */
internal val DROPDOWN_ITEM_INSET = 8.dp

/** Material's horizontal item padding (12dp) less the inset, so item text stays where it was. */
private val DROPDOWN_ITEM_CONTENT_PADDING = PaddingValues(horizontal = 12.dp - DROPDOWN_ITEM_INSET)

private val LocalDropdownItemShape = staticCompositionLocalOf<Shape> { RoundedCornerShape(0.dp) }

/** Item highlight shape for a menu drawn with [menuShape]. */
internal fun dropdownItemShape(menuShape: Shape, density: Density): Shape {
    val menuCorner = (menuShape as? CornerBasedShape)?.let { shape ->
        // Corner sizes here are absolute dp values, so the reference size does not matter.
        with(density) { shape.topStart.toPx(REFERENCE_SIZE, density).toDp() }
    } ?: 0.dp
    return RoundedCornerShape((menuCorner - DROPDOWN_ITEM_INSET).coerceAtLeast(0.dp))
}

private val REFERENCE_SIZE = Size(10_000f, 10_000f)

@Composable
private fun ProvideDropdownItemShape(menuShape: Shape, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val itemShape = remember(menuShape, density) { dropdownItemShape(menuShape, density) }
    CompositionLocalProvider(LocalDropdownItemShape provides itemShape, content = content)
}

@Composable
fun AgoraDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
    ) {
        ProvideDropdownItemShape(shape) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedDropdownMenuBoxScope.AgoraExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    matchAnchorWidth: Boolean = true,
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        scrollState = scrollState,
        matchAnchorWidth = matchAnchorWidth,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
    ) {
        ProvideDropdownItemShape(shape) { content() }
    }
}

/**
 * A dropdown item whose highlight is inset from the menu sides and clipped to a corner parallel to
 * the menu's. Use only inside [AgoraDropdownMenu] or [AgoraExposedDropdownMenu].
 */
@Composable
fun AgoraDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = DROPDOWN_ITEM_CONTENT_PADDING,
    interactionSource: MutableInteractionSource? = null,
) {
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        // Padding and clip come before DropdownMenuItem's own clickable, so the ripple is both
        // inset and clipped to the item shape.
        modifier = modifier
            .padding(horizontal = DROPDOWN_ITEM_INSET)
            .clip(LocalDropdownItemShape.current),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
    )
}
