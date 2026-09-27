package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.AskUserController

/** What the user has picked and typed for one question, kept while they move between pages. */
@Stable
internal class QuestionDraft(hasOptions: Boolean) {
    var selected by mutableStateOf(emptySet<String>())
    var typed by mutableStateOf("")

    // Typing is one of the choices rather than a second control next to them. An open question has
    // nothing to choose between, so there the field is the answer and is offered straight away.
    var ownAnswer by mutableStateOf(!hasOptions)

    val answered: Boolean get() = selected.isNotEmpty() || (ownAnswer && typed.isNotBlank())
}

/** Drafts for every question on the card, keyed by request id. A new card never inherits one. */
internal class QuestionDrafts {
    private val drafts = HashMap<Long, QuestionDraft>()

    fun of(request: AskUserController.Request): QuestionDraft =
        drafts.getOrPut(request.id) { QuestionDraft(request.options.isNotEmpty()) }

    /** Forgets answered, skipped and withdrawn questions so their ids cannot come back filled. */
    fun retainOnly(requests: List<AskUserController.Request>) {
        val live = requests.mapTo(HashSet()) { it.id }
        drafts.keys.retainAll(live)
    }
}

/**
 * One question page of the interaction card.
 *
 * Every waiting question shares the card: Back and Next move between pages, and Send on the last
 * page answers them all at once, declining any left blank. Skip declines every question on the
 * card. [position] is the "current / total" count across the whole card.
 *
 * Options are optional: a question without them is an open question. Even with options the user can
 * type instead, because the model's list is its guess at what the answers are, and a wrong guess must
 * not force the user to pick one of it.
 */
@Composable
internal fun QuestionCardContent(
    requests: List<AskUserController.Request>,
    drafts: QuestionDrafts,
    index: Int,
    position: String?,
    onBack: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onAnswer: (Long, List<String>, String?) -> Unit,
    onSkip: (Long) -> Unit,
) {
    val request = requests[index.coerceIn(0, requests.lastIndex)]
    CardHeader(
        icon = { tint ->
            Icon(InteractionKind.Question.icon, null, modifier = Modifier.size(18.dp), tint = tint)
        },
        title = stringResource(InteractionKind.Question.titleRes),
        position = position,
    )
    Spacer(Modifier.height(10.dp))
    QuestionPage(request = request, draft = drafts.of(request))
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { requests.forEach { onSkip(it.id) } }) {
            Text(stringResource(R.string.ask_user_skip))
        }
        Spacer(Modifier.weight(1f))
        onBack?.let {
            TextButton(onClick = it) { Text(stringResource(R.string.back)) }
            Spacer(Modifier.width(4.dp))
        }
        if (onNext != null) {
            Button(onClick = onNext) { Text(stringResource(R.string.ask_user_next)) }
        } else {
            Button(
                onClick = {
                    // A question left blank on an earlier page is declined, not invented.
                    requests.forEach { question ->
                        val answer = drafts.of(question)
                        if (answer.answered) {
                            onAnswer(
                                question.id,
                                question.options.filter { it in answer.selected },
                                answer.typed.trim().takeIf { answer.ownAnswer && it.isNotEmpty() },
                            )
                        } else {
                            onSkip(question.id)
                        }
                    }
                },
                enabled = requests.any { drafts.of(it).answered },
            ) { Text(stringResource(R.string.ask_user_send)) }
        }
    }
}

@Composable
private fun QuestionPage(
    request: AskUserController.Request,
    draft: QuestionDraft,
) {
    val hasOptions = request.options.isNotEmpty()
    // The field lives inside the scrolling content, so choosing to type can land it below the
    // visible part; [revealed] marks that choice so the field is scrolled up once it is placed.
    val field = remember { BringIntoViewRequester() }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) {
        if (revealed) {
            field.bringIntoView()
            revealed = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = ScrollableContentMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = request.question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (hasOptions) {
            Spacer(Modifier.height(10.dp))
            request.options.forEach { option ->
                OptionRow(
                    option = option,
                    checked = option in draft.selected,
                    allowMultiple = request.allowMultiple,
                    onToggle = {
                        draft.selected = when {
                            !request.allowMultiple -> setOf(option)
                            option in draft.selected -> draft.selected - option
                            else -> draft.selected + option
                        }
                        // One answer means one choice: picking a listed option puts the typed one
                        // away, and picking the typed one clears the list.
                        if (!request.allowMultiple) draft.ownAnswer = false
                    },
                )
            }
            OptionRow(
                option = stringResource(R.string.ask_user_custom_answer),
                checked = draft.ownAnswer,
                allowMultiple = request.allowMultiple,
                onToggle = {
                    draft.ownAnswer = if (request.allowMultiple) !draft.ownAnswer else true
                    revealed = draft.ownAnswer
                    if (!request.allowMultiple) draft.selected = emptySet()
                },
            )
        }
        if (draft.ownAnswer) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draft.typed,
                onValueChange = { draft.typed = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(field),
                placeholder = if (hasOptions) {
                    null
                } else {
                    { Text(stringResource(R.string.ask_user_custom_answer)) }
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(16.dp),
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun OptionRow(
    option: String,
    checked: Boolean,
    allowMultiple: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            // Inset so the rounded highlight never cuts into the control.
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row owns the click, so the control itself stays non-interactive and the whole row
        // reads as one target for accessibility services.
        if (allowMultiple) {
            Checkbox(checked = checked, onCheckedChange = null)
        } else {
            RadioButton(selected = checked, onClick = null)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = option,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}
