package com.newoether.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where an automatically produced user message came from.
 *
 * Only messages the app sends on the user's behalf carry a source: a Task run prompt, a Loop cycle
 * prompt, or the answers to non-blocking `ask_user` questions. Typed messages, blocking answers and
 * Compact summaries have none. The message text stays the clean readable form; the source is the
 * structured record the chat header and the model-facing XML are built from.
 */
@Immutable
@Serializable
data class MessageSource(
    val kind: Kind,
    /** Question/answer pairs in asking order; only used when [kind] is [Kind.ASK_USER]. */
    val askUser: List<AskUserItem> = emptyList(),
) {
    @Serializable
    enum class Kind {
        @SerialName("task") TASK,
        @SerialName("loop") LOOP,
        @SerialName("ask_user") ASK_USER,
    }

    /** One asked question. A null [answer] means the user left it unanswered. */
    @Immutable
    @Serializable
    data class AskUserItem(
        val question: String,
        val answer: String? = null,
    )

    init {
        require(kind == Kind.ASK_USER || askUser.isEmpty())
    }

    companion object {
        val TASK = MessageSource(Kind.TASK)
        val LOOP = MessageSource(Kind.LOOP)

        private val codec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun encode(source: MessageSource?): String? = source?.let { codec.encodeToString(serializer(), it) }

        /** Unreadable or future-shaped data degrades to an ordinary message rather than failing. */
        fun decode(raw: String?): MessageSource? =
            raw?.takeIf(String::isNotBlank)?.let { runCatching { codec.decodeFromString(serializer(), it) }.getOrNull() }

        /**
         * Backup data is untrusted: only a user row can be automatic input, and anything unreadable
         * imports as a plain message. The kept value is re-encoded so stored JSON is canonical.
         */
        fun sanitizeImported(raw: String?, participant: Participant): String? =
            if (participant == Participant.USER) encode(decode(raw)) else null
    }
}
