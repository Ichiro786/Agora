package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One chat client attached to [ChatRuntime]: the phone UI today, each WebUI session later.
 *
 * A client owns what it shows. The runtime never selects a conversation for a client; it only
 * fans conversation-scoped changes out to the clients that currently show that conversation.
 */
internal interface ChatClient {
    /** Conversation this client currently shows, or null when it shows none (for example New Chat). */
    val openConversationId: String?

    /** Render snapshot of [openConversationId]. The runtime writes it only while that conversation is open. */
    val renderStore: ConversationRenderStore

    /** True when the user can currently see [conversationId] on this client. */
    fun isConversationVisible(conversationId: String): Boolean
}

/** Room projection fences opened on each client render store for one accepted input. */
internal class ChatClientRoomFences internal constructor(
    internal val byStore: Map<ConversationRenderStore, RoomMessageProjectionFence>,
)

/**
 * The clients attached to the runtime.
 *
 * A conversation counts as open (or visible) when any attached client has it open (or visible).
 * Conversation-scoped graph changes go to every client that has that conversation open at the
 * moment of the write.
 */
internal class ChatClients {
    private val attached = CopyOnWriteArrayList<ChatClient>()

    fun attach(client: ChatClient) {
        attached.addIfAbsent(client)
    }

    fun detach(client: ChatClient) {
        attached.remove(client)
    }

    fun isConversationOpen(conversationId: String): Boolean =
        attached.any { it.openConversationId == conversationId }

    fun isConversationVisible(conversationId: String): Boolean =
        attached.any { it.isConversationVisible(conversationId) }

    fun commitGraph(
        conversationId: String,
        committedMessages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
        streamingMessage: ChatMessage?,
        fences: ChatClientRoomFences? = null,
    ) {
        val stores = storesShowing(conversationId)
        stores.forEach { store ->
            store.commitGraph(
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
                roomProjectionFence = fences?.byStore?.get(store),
            )
        }
        // A client that left the conversation after its fence opened must still release it.
        fences?.byStore?.forEach { (store, fence) ->
            if (store !in stores) store.releaseRoomMessageProjectionFence(fence)
        }
    }

    fun replaceGraph(
        conversationId: String,
        allMessages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
    ) {
        storesShowing(conversationId).forEach { store ->
            store.replaceGraph(allMessages = allMessages, selectedChildren = selectedChildren)
        }
    }

    /** Opens a fence on every client showing [conversationId]; null when no client shows it. */
    fun beginRoomProjectionFences(conversationId: String): ChatClientRoomFences? =
        storesShowing(conversationId)
            .associateWith { it.beginRoomMessageProjectionFence() }
            .takeIf { it.isNotEmpty() }
            ?.let(::ChatClientRoomFences)

    fun releaseRoomProjectionFences(fences: ChatClientRoomFences) {
        fences.byStore.forEach { (store, fence) -> store.releaseRoomMessageProjectionFence(fence) }
    }

    private fun storesShowing(conversationId: String): List<ConversationRenderStore> =
        attached.filter { it.openConversationId == conversationId }.map { it.renderStore }
}
