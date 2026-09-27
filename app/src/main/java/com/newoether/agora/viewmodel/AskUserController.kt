package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates `ask_user` requests between the tool loop, which asks, and the interaction bar,
 * which answers.
 *
 * Requests stack: parallel conversations and repeated tool calls can all be waiting, so the state
 * is an ordered list instead of a single slot and every answer carries its request id.
 *
 * A request is either blocking or not, and that choice belongs to the caller:
 *
 * - blocking: the tool call suspends until the answer arrives. There is no timeout, because a
 *   question the user has not seen yet must not decide itself; stopping the generation is what ends
 *   an unwanted wait.
 * - non-blocking: the tool call returns at once and the model keeps working. The answer is
 *   published through [deferredAnswers] and reaches the model as a queued user message, the same
 *   path a message typed during a generation takes.
 */
class AskUserController {
    data class Request(
        val id: Long,
        val conversationId: String?,
        val question: String,
        val options: List<String>,
        val allowMultiple: Boolean,
        /** False means the asking tool call already returned and the answer travels as a message. */
        val blocking: Boolean,
    )

    /**
     * [choices] are the options the user picked, [text] is what they typed instead of, or next to,
     * an option. [answered] is false when the user skipped the request or it was no longer waiting.
     */
    data class Answer(
        val choices: List<String>,
        val text: String? = null,
        val answered: Boolean,
    ) {
        companion object {
            val Unanswered = Answer(emptyList(), null, answered = false)
        }
    }

    /** An answer to a non-blocking request, on its way to [conversationId] as a user message. */
    data class DeferredAnswer(val conversationId: String, val text: String)

    private val _requests = MutableStateFlow<List<Request>>(emptyList())

    /** Every request still waiting for an answer, oldest first. */
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()

    private val _deferredAnswers = MutableSharedFlow<DeferredAnswer>(extraBufferCapacity = 32)

    /**
     * Answers to non-blocking requests. The chat runtime collects this and sends each one through
     * the queue, so an answer given while the model is still working lands in the next turn.
     */
    val deferredAnswers: SharedFlow<DeferredAnswer> = _deferredAnswers.asSharedFlow()

    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Answer>>()
    private val nextRequestId = AtomicLong(1)

    /**
     * Identifies this process's request numbering. Notification actions carry it so an action built
     * by an earlier process can never answer a request created after a restart.
     */
    val notificationSessionId: String = java.util.UUID.randomUUID().toString()

    /** Looks up a request that is still waiting, for callers that only kept its id. */
    fun requestById(id: Long): Request? = _requests.value.firstOrNull { it.id == id }

    fun open(
        conversationId: String?,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
        blocking: Boolean,
    ): Request {
        val request = Request(
            id = nextRequestId.getAndIncrement(),
            conversationId = conversationId,
            question = question,
            options = options,
            allowMultiple = allowMultiple,
            blocking = blocking,
        )
        // Only a blocking request has a caller to resume.
        if (blocking) waiters[request.id] = CompletableDeferred()
        _requests.update { it + request }
        return request
    }

    /**
     * Suspends until [request] is answered or skipped. The wait is unbounded; cancelling the
     * generation cancels this coroutine, and the request then leaves the bar.
     */
    suspend fun awaitAnswer(request: Request): Answer {
        // The waiter outlives the request on purpose: one tool call can be waiting on several
        // questions, and an answer given before its turn to be collected must not be lost.
        val waiter = waiters[request.id] ?: return Answer.Unanswered
        return try {
            waiter.await()
        } finally {
            forget(request.id)
        }
    }

    /**
     * Gives up on [id]: its card leaves the bar and its answer, if any, is dropped. Callers use this
     * when their wait ends for their own reasons, such as the generation being stopped, so nothing
     * is left on screen that no longer has anyone to return to. It is idempotent.
     */
    fun abandon(id: Long) {
        forget(id)
    }

    /**
     * Answers a request with the options [choices] and the typed [text], either of which may be
     * empty. An id that is no longer waiting is ignored, so one request cannot be answered twice and
     * a stale answer cannot decide a newer request.
     */
    fun submit(id: Long, choices: List<String>, text: String? = null) {
        val request = _requests.value.firstOrNull { it.id == id } ?: return
        val answer = Answer(choices, text?.takeIf { it.isNotBlank() }, answered = true)
        if (request.blocking) {
            // The card leaves now, while the answer stays until its caller collects it.
            dropRequest(id)
            waiters[id]?.complete(answer)
            return
        }
        dropRequest(id)
        // A non-blocking request without a conversation has nowhere to deliver the answer; the tool
        // refuses that combination, so this is only a guard.
        val conversationId = request.conversationId ?: return
        _deferredAnswers.tryEmit(DeferredAnswer(conversationId, deferredAnswerText(request, answer)))
    }

    /** The user declined to answer. A blocking caller resumes with [Answer.Unanswered]. */
    fun dismiss(id: Long) {
        val request = _requests.value.firstOrNull { it.id == id } ?: return
        dropRequest(id)
        if (request.blocking) waiters[id]?.complete(Answer.Unanswered)
    }

    private fun forget(id: Long) {
        waiters.remove(id)
        dropRequest(id)
    }

    private fun dropRequest(id: Long) {
        _requests.update { requests -> requests.filterNot { it.id == id } }
    }

    companion object {
        /**
         * What a non-blocking answer says as a user message. It repeats the question because the
         * message arrives one or more turns after the model asked, where the question is no longer
         * the last thing said.
         */
        fun deferredAnswerText(request: Request, answer: Answer): String = buildString {
            append(request.question)
            append("\n")
            append(answerBody(answer))
        }

        /** The user's answer on its own: picked options first, then whatever they typed. */
        fun answerBody(answer: Answer): String = listOfNotNull(
            answer.choices.takeIf { it.isNotEmpty() }?.joinToString(", "),
            answer.text?.takeIf { it.isNotBlank() },
        ).joinToString("\n").ifBlank { "" }
    }
}
