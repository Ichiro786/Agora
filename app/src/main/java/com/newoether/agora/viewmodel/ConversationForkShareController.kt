package com.newoether.agora.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Adapts a client's fork/share intents for the conversation it shows to typed service outcomes.
 * Every outcome (the forked conversation to open, share text, failure) goes to that origin only.
 */
internal class ConversationForkShareController(
    private val service: ConversationForkShareService,
    private val scope: CoroutineScope,
    private val forkFailureText: (String) -> String,
    private val shareFailureText: (String) -> String,
) {
    fun fork(origin: ChatClient, messageId: String? = null) {
        val conversationId = origin.openConversationId ?: return
        scope.launch {
            when (val result = service.fork(conversationId, messageId)) {
                is ConversationForkShareService.ForkResult.Success ->
                    origin.openConversation(result.conversationId)
                is ConversationForkShareService.ForkResult.Failure ->
                    origin.showSnackbar(forkFailureText(result.reason))
            }
        }
    }

    fun shareConversation(origin: ChatClient) {
        share(origin) { conversationId -> service.shareAll(conversationId) }
    }

    fun shareGeneration(origin: ChatClient, assistantMessageId: String) {
        share(origin) { conversationId -> service.shareRun(conversationId, assistantMessageId) }
    }

    fun shareMessages(origin: ChatClient, messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        share(origin) { conversationId -> service.shareMessages(conversationId, messageIds) }
    }

    private fun share(
        origin: ChatClient,
        load: suspend (conversationId: String) -> ConversationForkShareService.ShareResult,
    ) {
        val conversationId = origin.openConversationId ?: return
        scope.launch {
            when (val result = load(conversationId)) {
                is ConversationForkShareService.ShareResult.Success ->
                    origin.showShareText(result.text)
                is ConversationForkShareService.ShareResult.Failure ->
                    origin.showSnackbar(shareFailureText(result.reason))
            }
        }
    }
}
