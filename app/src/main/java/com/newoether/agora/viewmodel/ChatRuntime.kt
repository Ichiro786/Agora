package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.tool.AskUserToolProvider
import com.newoether.agora.tool.AutomationToolProvider
import com.newoether.agora.tool.McpToolProvider
import com.newoether.agora.util.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-scoped chat runtime shared by every client of this process (the phone UI and, later,
 * the WebUI). It lives for the whole process on the app scope, so chat work it owns keeps running
 * when no Activity or ViewModel exists.
 *
 * It owns the foreground generation core and RAG indexing. Per-client state (open conversation,
 * scroll, composer drafts, new-chat workspace) stays with each client.
 */
class ChatRuntime(
    application: Application,
    appContext: Context,
    conversations: ConversationRepository,
    settings: SettingsRepository,
    memoryManager: MemoryManager,
    skillManager: SkillManager,
    sandboxFactory: SandboxManagerFactory?,
    automationToolProvider: AutomationToolProvider,
    mcpToolProvider: McpToolProvider,
    askUser: AskUserController,
    shellConfirmation: ShellConfirmationController,
    scope: CoroutineScope,
) {
    // replay=0: events raised while no client is collecting are dropped rather than replayed
    // stale to the next client. The 1-slot buffer keeps tryEmit lossless for slow collectors.
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Clients currently attached to this runtime (see [ChatClient]). */
    internal val clients = ChatClients()

    /** Runtime-wide messages every connected client may show (for example RAG cache prompts). */
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /** Embedding subsystem: model CRUD + RAG cache + single-message indexing + key resolution. */
    val ragManager = RagManager(
        conversations = conversations,
        settings = settings,
        appContext = appContext,
        scope = scope,
    ) { _snackbarEvents.emit(it) }

    internal val generationManager: GenerationManager by lazy {
        GenerationManager(
            app = application,
            conversations = conversations,
            memoryManager = memoryManager,
            skillManager = skillManager,
            context = appContext,
            sandboxFactory = sandboxFactory,
            additionalToolProviders = listOf(
                automationToolProvider,
                mcpToolProvider,
                AskUserToolProvider(askUser),
            ),
            customProviders = { settings.customProviders.value },
        ).also { gm ->
            // Gate lives in RagManager.indexMessageForRag (autoCacheEnabled + active model).
            gm.onMessagePersisted = { messageId, text -> ragManager.indexMessageForRag(messageId, text) }
            gm.onConfirmShellCommand = shellConfirmation::confirm
        }
    }
}
