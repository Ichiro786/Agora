package com.newoether.agora.ui.chat.interaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.message.ChatMarkdownCodeBlock
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.ShellConfirmationController

private val ContentMaxWidth = 840.dp
internal val ScrollableContentMaxHeight = 200.dp
private const val AppearDurationMs = 180

/** The bar grows out of the composer, so it scales from its bottom edge rather than its centre. */
private val BottomOrigin = TransformOrigin(0.5f, 1f)

/**
 * Bottom bar that answers the requests in [interactions] without covering the conversation.
 *
 * Requests stack, so the bar is a pager: one card per request (a set of questions asked together
 * is one card with a page per question), swiped left and right, with the
 * oldest first. Answering removes that card and the next one takes its place. Nothing here can be
 * dismissed by tapping elsewhere; both kinds of request are answered only by their own buttons,
 * which keeps the shell confirmation a real security gate.
 *
 * [onHeightChanged] reports the measured height in pixels so the host can lift whatever sits above
 * the composer, and reports zero when there is nothing to answer.
 */
@Composable
internal fun UserInteractionBar(
    interactions: List<UserInteraction>,
    autoWrapCodeBlocks: Boolean,
    onAnswerQuestion: (Long, List<String>, String?) -> Unit,
    onSkipQuestion: (Long) -> Unit,
    onShellDecision: (Long, Boolean, Boolean) -> Unit,
    onHeightChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val visible = interactions.isNotEmpty()
    // The exit animation still needs cards to draw, so the last non-empty list stays around.
    var shown by remember { mutableStateOf(interactions) }
    if (visible) shown = interactions
    // The measured height never reaches zero on its own, because the bar leaves by scaling rather
    // than shrinking. Reporting zero here is what lets the host drop its lifted controls back down.
    LaunchedEffect(visible) { if (!visible) onHeightChanged(0f) }
    val spec = if (motionPolicy.allowContinuousMotion) tween<Float>(AppearDurationMs) else snap()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(spec) + scaleIn(spec, initialScale = 0.9f, transformOrigin = BottomOrigin),
        exit = fadeOut(spec) + scaleOut(spec, targetScale = 0.9f, transformOrigin = BottomOrigin),
        modifier = modifier.fillMaxWidth(),
    ) {
        UserInteractionPager(
            interactions = shown,
            autoWrapCodeBlocks = autoWrapCodeBlocks,
            onAnswerQuestion = onAnswerQuestion,
            onSkipQuestion = onSkipQuestion,
            onShellDecision = onShellDecision,
            onHeightChanged = onHeightChanged,
        )
    }
}

@Composable
private fun UserInteractionPager(
    interactions: List<UserInteraction>,
    autoWrapCodeBlocks: Boolean,
    onAnswerQuestion: (Long, List<String>, String?) -> Unit,
    onSkipQuestion: (Long) -> Unit,
    onShellDecision: (Long, Boolean, Boolean) -> Unit,
    onHeightChanged: (Float) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { interactions.size })
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height.toFloat()) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.widthIn(max = ContentMaxWidth),
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 8.dp,
            verticalAlignment = Alignment.Bottom,
            key = { index -> interactions.getOrNull(index)?.key ?: index },
        ) { page ->
            when (val interaction = interactions.getOrNull(page)) {
                is UserInteraction.Question -> InteractionCard {
                    QuestionCardContent(
                        requests = interaction.requests,
                        onAnswer = onAnswerQuestion,
                        onSkip = onSkipQuestion,
                    )
                }
                is UserInteraction.ShellCommand -> InteractionCard {
                    ShellCardContent(
                        pending = interaction.pending,
                        autoWrapCodeBlocks = autoWrapCodeBlocks,
                        onDecision = { allow, alwaysAllow ->
                            onShellDecision(interaction.pending.id, allow, alwaysAllow)
                        },
                    )
                }
                null -> Box(modifier = Modifier.fillMaxWidth())
            }
        }
        if (interactions.size > 1) {
            Spacer(Modifier.height(8.dp))
            PageIndicator(pageCount = interactions.size, currentPage = pagerState.currentPage)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InteractionCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) { content() }
    }
}

@Composable
private fun ShellCardContent(
    pending: ShellConfirmationController.PendingShellCommand,
    autoWrapCodeBlocks: Boolean,
    onDecision: (Boolean, Boolean) -> Unit,
) {
    var alwaysAllow by remember(pending.id) { mutableStateOf(false) }
    CardHeader(
        icon = { tint ->
            Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp), tint = tint)
        },
        title = stringResource(R.string.shell_confirm_title, pending.server),
    )
    Spacer(Modifier.height(10.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = ScrollableContentMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        ChatMarkdownCodeBlock(code = pending.summary, autoWrap = autoWrapCodeBlocks)
    }
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { alwaysAllow = !alwaysAllow },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = alwaysAllow, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.shell_confirm_always),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onDecision(false, false) },
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text(stringResource(R.string.shell_confirm_deny)) }
        Spacer(Modifier.width(4.dp))
        Button(onClick = { onDecision(true, alwaysAllow) }) {
            Text(stringResource(R.string.shell_confirm_allow))
        }
    }
}

@Composable
internal fun CardHeader(
    icon: @Composable (Color) -> Unit,
    title: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon(MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(pageCount) { index ->
            val active = index == currentPage
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (active) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
            )
        }
    }
}
