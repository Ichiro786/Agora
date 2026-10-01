package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.viewmodel.AttachmentImportProcessor
import com.newoether.agora.viewmodel.BranchReplacementTransitionCoordinator
import com.newoether.agora.viewmodel.ChatClient
import com.newoether.agora.viewmodel.ChatClients
import com.newoether.agora.viewmodel.ComposerDraftController
import com.newoether.agora.viewmodel.ComposerDraftPersistence
import com.newoether.agora.viewmodel.ConversationComposerController
import com.newoether.agora.viewmodel.ConversationComposerSubmissionController
import com.newoether.agora.viewmodel.ConversationComposerSubmissionSnapshot
import com.newoether.agora.viewmodel.ConversationRenderStore
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ConversationUiStateAssembler
import com.newoether.agora.viewmodel.ConversationWorkspaceDraft
import com.newoether.agora.viewmodel.GenerationStopAdapter
import com.newoether.agora.viewmodel.MessageGenerationController
import com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
import com.newoether.agora.viewmodel.NewChatWorkspaceSnapshot
import com.newoether.agora.viewmodel.resolveValidModel
import com.newoether.agora.viewmodel.validChatModels
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One signed-in browser connection as a [ChatClient] of the process-scoped chat runtime.
 *
 * Send, Stop and queueing go through the same runtime owners the phone uses, so they work while
 * the app is in the background. The browser's open conversation, composer draft and New Chat
 * workspace belong to this session only (application-ui.md section 36): drafts stay in memory
 * and a New Chat send never touches the phone's persisted New Chat workspace. An existing
 * conversation's model is read from the conversation itself, which the phone shares.
 *
 * The session lives exactly as long as its sync connection: [start] attaches it, [close]
 * detaches it and releases the retained composer owner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WebUiChatSession(
    private val generation: MessageGenerationController,
    private val generationStop: GenerationStopAdapter,
    private val clients: ChatClients,
    conversations: ConversationRepository,
    registry: ConversationStateRegistry,
    executionCoordinator: ConversationExecutionCoordinator,
    settings: SettingsRepository,
    private val transfers: ConversationSettingsTransferCoordinator,
    attachmentProcessor: AttachmentImportProcessor,
    private val scope: CoroutineScope,
) : ChatClient {
    /** Where this session shows; [browserSeq] is the browser's last open request it follows. */
    data class OpenTarget(
        val conversationId: String?,
        val browserSeq: Long,
        /** True when the runtime, not the browser, moved the session (for example New Chat send). */
        val movedByServer: Boolean,
    )

    /** The composer owner the browser shows and that owner's submission phase. */
    data class ComposerState(
        val conversationId: String?,
        val snapshot: ConversationComposerSubmissionSnapshot,
    )

    private val target = MutableStateFlow(OpenTarget(null, browserSeq = 0L, movedByServer = false))
    val openTarget: StateFlow<OpenTarget> = target.asStateFlow()
    // Updated together with [target] so a send right after an open captures the new target.
    private val openId = MutableStateFlow<String?>(null)

    private val newChatEntryId = AtomicLong(0L)
    private val _snackbars = MutableSharedFlow<String>(extraBufferCapacity = SNACKBAR_BUFFER)
    val snackbars: SharedFlow<String> = _snackbars.asSharedFlow()

    // The canonical render owner keeps this client's store equal to what the phone's store holds
    // for the same conversation; Stop snapshots in-flight rows from any showing client's store.
    private val ui = ConversationUiStateAssembler(
        conversations = conversations,
        registry = registry,
        executionCoordinator = executionCoordinator,
        currentConversationId = openId,
        scope = scope,
    )
    private val drafts = ComposerDraftController(
        persistence = SessionDraftPersistence(),
        conversations = conversations,
    )
    private val composers = ConversationComposerController(
        scope = scope,
        drafts = drafts,
        processor = attachmentProcessor,
    )

    /** Model chosen on this browser's New Chat page; null follows the default model. */
    private val newChatModelId = MutableStateFlow<String?>(null)
    private val activeModel: StateFlow<String> = combine(
        openId.flatMapLatest { id ->
            if (id == null) newChatModelId else conversations.observeConversation(id).map { it?.modelId }
        },
        settings.selectedModel,
        settings.validChatModels(scope),
    ) { referenced, fallback, valid ->
        if (valid == null) "" else resolveValidModel(referenced, fallback, valid)
    }.stateIn(scope, SharingStarted.Eagerly, "")

    private val submission = ConversationComposerSubmissionController(
        scope = scope,
        composers = composers,
        drafts = drafts,
        captureTarget = { ownerId ->
            val current = openId.value
            generation.captureForegroundSendTarget(
                ownerId = ownerId,
                currentId = current,
                isNewChatMode = current == null,
                newChatEntryId = newChatEntryId.get(),
                modelId = activeModel.value,
                captureNewChatWorkspace = {
                    NewChatWorkspaceSnapshot(
                        persisted = null,
                        modelId = newChatModelId.value,
                        systemPromptId = null,
                        conversationSettings = null,
                        sessionLocal = true,
                    )
                },
            )
        },
        prepare = { sendTarget, composer ->
            generation.prepareForegroundSend(sendTarget, composer, this@WebUiChatSession)
        },
        send = { admission, text, attachments, onAccepted ->
            generation.sendMessage(admission, text, attachments, onAccepted, this@WebUiChatSession)
        },
        // The in-memory draft store cannot fail to clear, so no retry surface is needed.
    )

    private val ownerMutex = Mutex()
    private var retainedOwner: String? = null
    private val ownerState = MutableStateFlow<Pair<String, StateFlow<ConversationComposerSubmissionSnapshot>>?>(null)
    val composerState: Flow<ComposerState> = ownerState.filterNotNull().flatMapLatest { (owner, state) ->
        state.map { ComposerState(owner.takeUnless { it == NEW_CHAT_WORKSPACE_ID }, it) }
    }

    /** Attaches to the runtime and admits the initial New Chat composer. */
    suspend fun start() {
        clients.attach(this)
        ui.start()
        ownerMutex.withLock { retainOwnerLocked(NEW_CHAT_WORKSPACE_ID) }
    }

    /** The browser opened [conversationId] (null is a fresh New Chat page) with request [seq]. */
    suspend fun open(conversationId: String?, seq: Long) = ownerMutex.withLock {
        if (conversationId == null) {
            newChatEntryId.incrementAndGet()
        } else if (conversationId == target.value.conversationId) {
            // Already shown, for example after the browser followed a runtime move.
            return@withLock
        }
        target.value = OpenTarget(conversationId, seq, movedByServer = false)
        openId.value = conversationId
        retainOwnerLocked(conversationId ?: NEW_CHAT_WORKSPACE_ID)
    }

    /** Sends [text] from the browser composer into whatever this session shows. */
    suspend fun send(text: String) = ownerMutex.withLock {
        val owner = retainedOwner ?: return@withLock
        composers.updateText(owner, text)
        submission.submit(owner, text, emptyList())
    }

    fun stop() = generationStop.stop(openId.value, this)

    suspend fun close() = withContext(NonCancellable) {
        clients.detach(this@WebUiChatSession)
        ownerMutex.withLock {
            retainedOwner?.let { releaseOwner(it) }
            retainedOwner = null
        }
    }

    /** Moves the composer retain to [ownerId]; the previous owner is released afterwards. */
    private suspend fun retainOwnerLocked(ownerId: String) {
        val previous = retainedOwner
        if (previous == ownerId) return
        composers.loadSelected(ownerId)
        retainedOwner = ownerId
        ownerState.value = ownerId to submission.observeState(ownerId)
        previous?.let { releaseOwner(it) }
    }

    private suspend fun releaseOwner(ownerId: String) {
        submission.releaseState(ownerId)
        composers.releaseSelected(ownerId)
    }

    private suspend fun moveByServer(conversationId: String?) {
        if (conversationId == null) newChatEntryId.incrementAndGet()
        target.value = OpenTarget(conversationId, target.value.browserSeq, movedByServer = true)
        openId.value = conversationId
        retainOwnerLocked(conversationId ?: NEW_CHAT_WORKSPACE_ID)
    }

    // -- ChatClient

    override val openConversationId: String? get() = openId.value
    override val renderStore: ConversationRenderStore get() = ui.renderStore
    override fun isConversationVisible(conversationId: String): Boolean = openId.value == conversationId
    override val branchTransitions = BranchReplacementTransitionCoordinator()

    override suspend fun awaitProjectedPath(conversationId: String, messageId: String) {
        combine(ui.messages, openId) { path, open ->
            open != conversationId || path.any { it.id == messageId }
        }.first { projectedOrClosed -> projectedOrClosed }
    }

    // The browser's message list owns bottom following for its own sends.
    override fun requestScrollToBottomAfter(conversationId: String, messageId: String, attachedOnly: Boolean) = Unit

    // Send haptics belong to the phone.
    override fun onSendAccepted(conversationId: String, messageId: String) = Unit

    override suspend fun applyCommittedNewConversationState(conversationId: String) {
        transfers.complete(conversationId)
    }

    override suspend fun publishAcceptedNewConversation(
        conversationId: String,
        modelId: String,
        entryId: Long,
    ): Boolean = ownerMutex.withLock {
        if (target.value.conversationId != null || newChatEntryId.get() != entryId) return@withLock false
        moveByServer(conversationId)
        true
    }

    // The browser has no tree-mutation cover; it settles from the synced path.
    override suspend fun beginTreeMutation(conversationId: String, scrollToTarget: Boolean): Long? = null
    override fun settleTreeMutation(requestId: Long?, targetMessageId: String?) = Unit
    override fun failTreeMutation(requestId: Long?) = Unit

    override fun showSnackbar(message: String) {
        _snackbars.tryEmit(message)
    }

    override fun openConversation(conversationId: String) {
        scope.launch { ownerMutex.withLock { moveByServer(conversationId) } }
    }

    // The browser offers no share action.
    override fun showShareText(text: String) = Unit

    override fun settleDeletedConversation(conversationId: String) {
        scope.launch {
            ownerMutex.withLock {
                if (target.value.conversationId == conversationId) moveByServer(null)
            }
        }
    }

    override fun isSubmissionFrozen(conversationId: String): Boolean = submission.isFrozen(conversationId)

    override fun onGenerationActivityChanged(conversationId: String, active: Boolean) =
        if (active) ui.markActive(conversationId) else ui.markIdle(conversationId)

    private companion object {
        const val SNACKBAR_BUFFER = 8
    }
}

/** Browser drafts: kept for the connection only, never written to the phone's Room drafts. */
private class SessionDraftPersistence : ComposerDraftPersistence {
    private val drafts = ConcurrentHashMap<String, ConversationWorkspaceDraft>()

    override suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft =
        drafts[ownerId] ?: ConversationWorkspaceDraft(text = "", attachmentsJson = null)

    override suspend fun updateDraft(ownerId: String, text: String, attachmentsJson: String?) {
        drafts[ownerId] = ConversationWorkspaceDraft(text, attachmentsJson)
    }

    override suspend fun clearAcceptedDraft(ownerId: String) {
        drafts.remove(ownerId)
    }
}
