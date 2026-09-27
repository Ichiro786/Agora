package com.newoether.agora.ui.chat.interaction

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.message.ChatMarkdownCodeBlock
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ShellConfirmationController

private val ContentMaxWidth = 840.dp
internal val ScrollableContentMaxHeight = 200.dp
private const val AppearDurationMs = 180
private const val PageDurationMs = 220
private const val PageFadeOutMs = 160

/** One page of the interaction card. [key] identifies it across list changes. */
private sealed interface DeckPage {
    val key: String

    data class Shell(val pending: ShellConfirmationController.PendingShellCommand) : DeckPage {
        override val key: String get() = "shell:${pending.id}"
    }

    data class Question(val request: AskUserController.Request) : DeckPage {
        override val key: String get() = "question:${request.id}"
    }
}

/** The bar grows out of the composer, so it scales from its bottom edge rather than its centre. */
private val BottomOrigin = TransformOrigin(0.5f, 1f)

/**
 * Bottom bar that answers the requests in [interactions] of [conversationId] without covering the
 * conversation.
 *
 * Everything waiting is one card with one page per request and a "current / total" count in the
 * header: the shell confirmation first, because a command is held until it is decided, then every
 * question in the order it was asked. Back and Next move between pages, and Send on the last page
 * answers every question at once. Nothing here can be dismissed by tapping elsewhere; both kinds
 * of request are answered only by their own buttons, which keeps the shell confirmation a real
 * security gate.
 *
 * The card can be folded into a capsule so the conversation behind it can be read; tapping the
 * capsule opens it again. Folding never answers anything. The host owns the folded set
 * [minimizedIn] per conversation, so switching away and back keeps it.
 *
 * Each conversation gets its own card. Switching to another conversation that is also waiting
 * lets the old card leave first and then brings the new one in, so it never looks like paging
 * inside one card. A leaving card keeps its own requests and folded state until it is gone.
 *
 * [onHeightChanged] reports the measured height in pixels so the host can lift whatever sits above
 * the composer, and reports zero when there is nothing to answer.
 */
@Composable
internal fun UserInteractionBar(
    conversationId: String,
    interactions: List<UserInteraction>,
    autoWrapCodeBlocks: Boolean,
    onSubmitQuestions: (List<Pair<Long, AskUserController.Answer>>) -> Unit,
    onSkipQuestion: (Long) -> Unit,
    onShellDecision: (Long, Boolean, Boolean) -> Unit,
    onHeightChanged: (Float) -> Unit,
    minimizedIn: Set<String>,
    onMinimizedChange: (String, Boolean) -> Unit,
    drafts: QuestionDrafts,
    pageIn: Map<String, String>,
    onPageChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val visible = interactions.isNotEmpty()
    // The measured height never reaches zero on its own, because the bar leaves by scaling rather
    // than shrinking. Reporting zero here is what lets the host drop its lifted controls back down.
    LaunchedEffect(visible) {
        if (!visible) onHeightChanged(0f)
    }
    AnimatedContent(
        targetState = conversationId to interactions,
        // One card per conversation; an empty list is no card at all.
        contentKey = { (owner, waiting) -> owner.takeIf { waiting.isNotEmpty() } },
        transitionSpec = {
            if (!motionPolicy.allowContinuousMotion) {
                EnterTransition.None togetherWith ExitTransition.None using null
            } else {
                // A card replacing another waits until the old one has gone.
                val delay = if (initialState.second.isEmpty()) 0 else AppearDurationMs
                val enterSpec = tween<Float>(AppearDurationMs, delayMillis = delay)
                val exitSpec = tween<Float>(AppearDurationMs)
                (
                    fadeIn(enterSpec) +
                        scaleIn(enterSpec, initialScale = 0.9f, transformOrigin = BottomOrigin)
                    ) togetherWith (
                    fadeOut(exitSpec) +
                        scaleOut(exitSpec, targetScale = 0.9f, transformOrigin = BottomOrigin)
                    ) using null
            }
        },
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier.fillMaxWidth(),
        label = "interaction-card",
    ) { (owner, waiting) ->
        if (waiting.isNotEmpty()) {
            InteractionDeck(
                interactions = waiting,
                autoWrapCodeBlocks = autoWrapCodeBlocks,
                onSubmitQuestions = onSubmitQuestions,
                onSkipQuestion = onSkipQuestion,
                onShellDecision = onShellDecision,
                onHeightChanged = onHeightChanged,
                minimized = owner in minimizedIn,
                onMinimizedChange = { folded -> onMinimizedChange(owner, folded) },
                drafts = drafts,
                pageKey = pageIn[owner],
                onPageChange = { key -> onPageChange(owner, key) },
            )
        }
    }
}

@Composable
private fun InteractionDeck(
    interactions: List<UserInteraction>,
    autoWrapCodeBlocks: Boolean,
    onSubmitQuestions: (List<Pair<Long, AskUserController.Answer>>) -> Unit,
    onSkipQuestion: (Long) -> Unit,
    onShellDecision: (Long, Boolean, Boolean) -> Unit,
    onHeightChanged: (Float) -> Unit,
    minimized: Boolean,
    onMinimizedChange: (Boolean) -> Unit,
    drafts: QuestionDrafts,
    pageKey: String?,
    onPageChange: (String) -> Unit,
) {
    val shell = interactions.firstNotNullOfOrNull { (it as? UserInteraction.ShellCommand)?.pending }
    val questions = interactions.filterIsInstance<UserInteraction.Question>().flatMap { it.requests }
    val pages = listOfNotNull<DeckPage>(shell?.let(DeckPage::Shell)) +
        questions.map(DeckPage::Question)
    val keys = pages.map { it.key }
    if (pages.isEmpty()) return
    // The host remembers the page by key, so a request joining or leaving the card never moves the
    // user off the page they were on; a page that left falls back to the first one.
    val current = pages.firstOrNull { it.key == pageKey } ?: pages.first()
    val motion = LocalAgoraMotionPolicy.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height.toFloat()) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.widthIn(max = ContentMaxWidth).padding(horizontal = 12.dp)) {
            MorphingInteractionCard(
                kind = if (current is DeckPage.Shell) InteractionKind.Approval else InteractionKind.Question,
                minimized = minimized,
                onMinimizedChange = onMinimizedChange,
            ) {
                // Pages change content inside the one card: a short slide in the reading
                // direction with a fade, while the outline follows the new page's height.
                AnimatedContent(
                    targetState = current,
                    contentKey = { it.key },
                    transitionSpec = {
                        if (!motion.allowContinuousMotion) {
                            EnterTransition.None togetherWith ExitTransition.None using null
                        } else {
                            val from = keys.indexOf(initialState.key)
                            val to = keys.indexOf(targetState.key)
                            // A page that left the card (answered) counts as behind the new one.
                            val forward = from < 0 || to >= from
                            val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
                            (
                                // The new page arrives fast and settles; the old one
                                // starts slowly and speeds away.
                                slideInHorizontally(tween(PageDurationMs, easing = LinearOutSlowInEasing)) {
                                    sign * it / 2
                                } + fadeIn(tween(PageDurationMs, easing = LinearOutSlowInEasing))
                                ) togetherWith (
                                slideOutHorizontally(tween(PageDurationMs, easing = FastOutLinearInEasing)) {
                                    -sign * it / 2
                                } + fadeOut(tween(PageFadeOutMs, easing = FastOutLinearInEasing))
                                ) using SizeTransform(clip = false) { _, _ ->
                                tween<IntSize>(PageDurationMs)
                            }
                        }
                    },
                    label = "interaction page",
                ) { shownPage ->
                    // The outgoing page may already be gone from the lists; it then renders
                    // alone, with inert buttons, for the few frames it takes to fade away.
                    val index = keys.indexOf(shownPage.key)
                    val live = index >= 0
                    val position = if (live && pages.size > 1) "${index + 1} / ${pages.size}" else null
                    val back: (() -> Unit)? = if (live && index > 0) ({ onPageChange(keys[index - 1]) }) else null
                    val next: (() -> Unit)? =
                        if (live && index < pages.lastIndex) ({ onPageChange(keys[index + 1]) }) else null
                    Column(modifier = Modifier.fillMaxWidth()) {
                        when (shownPage) {
                            is DeckPage.Shell -> ShellCardContent(
                                pending = shownPage.pending,
                                autoWrapCodeBlocks = autoWrapCodeBlocks,
                                position = position,
                                onNext = next,
                                onDecision = { allow, alwaysAllow ->
                                    if (live) onShellDecision(shownPage.pending.id, allow, alwaysAllow)
                                },
                            )
                            is DeckPage.Question -> {
                                val questionIndex = questions.indexOfFirst { it.id == shownPage.request.id }
                                QuestionCardContent(
                                    requests = if (questionIndex >= 0) questions else listOf(shownPage.request),
                                    drafts = drafts,
                                    index = questionIndex.coerceAtLeast(0),
                                    position = position,
                                    onBack = back,
                                    onNext = next,
                                    onSubmit = { answers -> if (live) onSubmitQuestions(answers) },
                                    onSkip = { id -> if (live) onSkipQuestion(id) },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ShellCardContent(
    pending: ShellConfirmationController.PendingShellCommand,
    autoWrapCodeBlocks: Boolean,
    position: String?,
    onNext: (() -> Unit)?,
    onDecision: (Boolean, Boolean) -> Unit,
) {
    var alwaysAllow by remember(pending.id) { mutableStateOf(false) }
    CardHeader(
        icon = { tint ->
            Icon(InteractionKind.Approval.icon, null, modifier = Modifier.size(18.dp), tint = tint)
        },
        title = stringResource(InteractionKind.Approval.titleRes),
        detail = pending.server,
        position = position,
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
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { alwaysAllow = !alwaysAllow }
            // Inset so the rounded highlight never cuts into the checkbox.
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = alwaysAllow, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.shell_confirm_always),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onNext?.let { TextButton(onClick = it) { Text(stringResource(R.string.ask_user_next)) } }
        Spacer(Modifier.weight(1f))
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
    detail: String? = null,
    position: String? = null,
) {
    // The end padding keeps the header clear of the card's minimize button.
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        detail?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        position?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
