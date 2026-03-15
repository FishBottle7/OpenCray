package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmProviderOptionSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.LocalLlmConfigFacade
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.app.facade.mcp.EmptyMcpSettingsFacade
import com.opencray.app.facade.mcp.LocalMcpSettingsFacade
import com.opencray.app.facade.mcp.McpServerSettingsSnapshot
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsSnapshot
import com.opencray.app.facade.personalization.EmptyPersonalizationFacade
import com.opencray.app.facade.personalization.LocalPersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationConfigSnapshot
import com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationPresetSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetScope
import com.opencray.app.facade.personalization.SavePersonalizationConfigRequest
import com.opencray.app.facade.skills.EmptySkillsFacade
import com.opencray.app.facade.skills.InstallSourceSnapshot
import com.opencray.app.facade.skills.InstalledSkillSnapshot
import com.opencray.app.facade.skills.LocalSkillsFacade
import com.opencray.app.facade.skills.SkillInstructionsSnapshot
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.app.facade.skills.SuggestedSkillSnapshot
import com.opencray.app.facade.settings.LocalSettingsFacade
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.facade.settings.SettingsRowSnapshot
import com.opencray.app.facade.settings.SettingsSectionSnapshot
import com.opencray.app.shell.AppShellStateStore
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.opencray.app.R

internal class OpenCrayHostRuntime private constructor(
  private val appContext: Context?,
  private val stateStore: AppShellStateStore,
  private val chatSessionStore: ChatSessionLocalStore,
  private var settingsFacade: SettingsFacade,
  private var llmConfigFacade: LlmConfigFacade,
  private var personalizationFacade: PersonalizationFacade,
  private var mcpSettingsFacade: McpSettingsFacade,
  private var skillsFacade: SkillsFacade,
  private val workspaceSnapshotProvider: () -> Map<String, Any?>,
  private val sessionRuntimeManager: AgentSessionRuntimeManager,
  private val approvalRegistry: AgentTaskApprovalRegistry,
  private var strings: HostRuntimeStrings,
  private val mainThreadPoster: MainThreadPoster,
) {
  private val lock = Any()
  private val shellListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val settingsOverviewListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val skillsListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val chatListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val chatRuntimeListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val pendingApprovalsBySession = linkedMapOf<String, LinkedHashMap<String, PendingApprovalSnapshot>>()
  private val runtimeEventsBySession = linkedMapOf<String, ArrayDeque<OpenCrayAgentRunEvent>>()

  init {
    sessionRuntimeManager.observe(
      object : AgentSessionRuntimeListener {
        override fun onTaskStarted(sessionId: String, task: AgentTask) {
          emitChatSnapshot()
          emitChatRuntimeSnapshot()
        }

        override fun onTaskFinished(sessionId: String, task: AgentTask, result: ExecutionResult) {
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.takeIf(String::isNotBlank)
          synchronized(lock) {
            if (isApprovalRequiredResult(result)) {
              recordPendingApprovalLocked(sessionId = sessionId, task = task, result = result)
            } else {
              clearPendingApprovalLocked(sessionId = sessionId, taskId = task.id)
              approvalRegistry.clear(sessionId = sessionId, taskId = task.id)
            }
            pendingMessageId?.let { messageId ->
              chatSessionStore.replaceMessage(
                sessionId = sessionId,
                messageId = messageId,
                role = ChatTranscriptRole.ASSISTANT,
                text = finalTextFor(result),
              )
            }
          }
          emitChatSnapshot()
          emitChatRuntimeSnapshot()
        }

        override fun onRunEvent(sessionId: String, task: AgentTask, event: OpenCrayAgentRunEvent) {
          synchronized(lock) {
            recordRuntimeEventLocked(sessionId = sessionId, event = event)
          }
          emitChatSnapshot()
          emitChatRuntimeSnapshot()
        }
      },
    )
    ensureActiveSessionResumed()
  }

  fun loadShellSnapshot(): Map<String, Any?> = mapOf(
    "initialTab" to stateStore.load().selectedTab.routeKey,
    "localeTag" to strings.localeTag,
    "hostLabel" to strings.shellHostLabel,
    "hostSummary" to strings.shellHostSummary,
    "isHostConnected" to true,
  )

  fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = shellListeners,
      initialPayload = loadShellSnapshot(),
      listener = listener,
    )

  fun loadSettingsOverview(): Map<String, Any?> =
    synchronized(lock) { settingsFacade.loadOverview() }.toMap()

  fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = settingsOverviewListeners,
      initialPayload = loadSettingsOverview(),
      listener = listener,
    )

  fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> {
    val routeId = SettingsRouteId.fromWireValue(routeIdRaw) ?: SettingsRouteId.WORKSPACE_ACCESS
    return synchronized(lock) { settingsFacade.loadDetail(routeId) }.toMap()
  }

  fun loadLlmConfig(): Map<String, Any?> =
    synchronized(lock) { llmConfigFacade.load() }.toMap()

  fun saveLlmConfig(
    enabled: Boolean,
    providerId: String,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      llmConfigFacade.save(
        SaveLlmConfigRequest(
          enabled = enabled,
          providerId = providerId,
          protocol = protocol,
          providerName = providerName,
          providerNotes = providerNotes,
          baseUrl = baseUrl,
          apiKey = apiKey,
          model = model,
          reasoningEffort = reasoningEffort,
          systemPrompt = systemPrompt,
        ),
      )
    }
    return snapshot.toMap()
  }

  fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, Any?> =
    synchronized(lock) {
      llmConfigFacade.validate(
        ValidateLlmConfigRequest(
          providerId = providerId,
          protocol = protocol,
          baseUrl = baseUrl,
          apiKey = apiKey,
          model = model,
          reasoningEffort = reasoningEffort,
        ),
      )
    }.toMap()

  fun loadPersonalizationConfig(): Map<String, Any?> =
    synchronized(lock) { personalizationFacade.load() }.toMap()

  fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      personalizationFacade.save(
        SavePersonalizationConfigRequest(
          presetId = presetId,
          customLabel = customLabel,
          customGuidance = customGuidance,
        ),
      )
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  fun setAppLanguage(languageId: String): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      val updated = personalizationFacade.setAppLanguage(languageId)
      if (appContext == null) {
        updated
      } else {
        refreshLocalizedResourcesLocked()
        personalizationFacade.load()
      }
    }
    emitShellSnapshot()
    emitSettingsOverview()
    emitSkillsSnapshot()
    emitChatSnapshot()
    return snapshot.toMap()
  }

  fun runPersonalizationReset(scopeId: String): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      personalizationFacade.reset(PersonalizationResetScope.fromWireValue(scopeId))
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  fun loadMcpSettings(): Map<String, Any?> =
    synchronized(lock) { mcpSettingsFacade.load() }.toMap()

  fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    synchronized(lock) { mcpSettingsFacade.setMasterEnabled(enabled) }.toMap()

  fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> = synchronized(lock) {
    mcpSettingsFacade.setServerEnabled(serverId = serverId, enabled = enabled)
  }.toMap()

  fun loadSkillsSnapshot(query: String = ""): Map<String, Any?> =
    synchronized(lock) { skillsFacade.loadSnapshot(query) }.toMap()

  fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = skillsListeners,
      initialPayload = loadSkillsSnapshot(),
      listener = listener,
    )

  fun loadFilesSnapshot(): Map<String, Any?> = synchronized(lock) {
    workspaceSnapshotProvider()
  }

  fun setSkillEnabled(skillId: String, enabled: Boolean) {
    synchronized(lock) {
      require(skillsFacade.setSkillEnabled(skillId = skillId, enabled = enabled)) {
        "Skill '$skillId' is not installed."
      }
    }
    emitSkillsSnapshot()
  }

  fun installSuggestedSkill(skillId: String): String {
    val installed = synchronized(lock) {
      skillsFacade.installSuggestedSkill(skillId)
    }
    require(installed) {
      "Unable to install '$skillId' from the local catalog."
    }
    emitSkillsSnapshot()
    return strings.skillInstalled(skillId)
  }

  fun deleteInstalledSkill(skillId: String): String {
    val deleted = synchronized(lock) {
      skillsFacade.deleteInstalledSkill(skillId)
    }
    require(deleted) {
      "Unable to remove '$skillId'."
    }
    emitSkillsSnapshot()
    return strings.skillRemoved(skillId)
  }

  fun refreshSkills(): String {
    synchronized(lock) {
      skillsFacade.refresh()
    }
    emitSkillsSnapshot()
    return strings.skillsReloaded
  }

  fun loadSkillInstructions(skillId: String): Map<String, Any?> {
    val instructions = synchronized(lock) {
      skillsFacade.loadInstructions(skillId)
    }
    requireNotNull(instructions) {
      "Skill '$skillId' is unavailable."
    }
    return instructions.toMap()
  }

  fun activateSkillsInstallSource(sourceId: String): String =
    synchronized(lock) { skillsFacade.activateInstallSource(sourceId) }

  fun loadChatSnapshot(): Map<String, Any?> = synchronized(lock) {
    val chatState = chatSessionStore.loadState()
    val activeSession = chatState.activeSession
    val visibleMessages = activeSession.messages.filter(::isVisibleChatMessage)
    val pendingCount = pendingTaskCount(activeSession.sessionId)
    val pendingApprovals = pendingApprovalsForSession(activeSession.sessionId)
    val activeSessionTitle = displaySessionTitle(activeSession.title)
    mapOf(
      "screenTitle" to strings.chatScreenTitle,
      "modeLabel" to strings.chatModeLabel,
      "sessionButtonLabel" to strings.chatSessionButtonLabel,
      "composerPlaceholder" to strings.composerPlaceholder,
      "summary" to mapOf(
        "title" to activeSessionTitle,
        "badge" to strings.chatMessagesBadge(visibleMessages.size),
        "body" to if (pendingApprovals.isNotEmpty()) {
          strings.chatSummaryApprovalRequired
        } else if (pendingCount > 0) {
          strings.chatSummaryReplyInProgress
        } else if (visibleMessages.isEmpty()) {
          strings.chatSummaryStartNewSession
        } else {
          strings.chatSummaryRestored
        },
      ),
      "messages" to visibleMessages.map(::chatMessageToMap),
      "pendingApprovals" to pendingApprovals.map { approval ->
        mapOf(
          "runId" to approval.runId,
          "taskId" to approval.taskId,
          "pendingMessageId" to approval.pendingMessageId,
          "risk" to if (approval.isHighRisk) "high_risk" else "standard",
          "isHighRisk" to approval.isHighRisk,
          "title" to approval.title,
          "body" to approval.body,
          "approveLabel" to strings.chatApprovalApproveLabel,
          "rejectLabel" to strings.chatApprovalRejectLabel,
        )
      },
      "drawer" to mapOf(
        "eyebrow" to strings.chatRecentSessionsEyebrow,
        "title" to strings.chatRecentSessionsTitle,
        "ctaLabel" to strings.chatNewSessionLabel,
        "sessions" to chatState.sessions.map { session ->
          mapOf(
            "sessionId" to session.sessionId,
            "title" to displaySessionTitle(session.title),
            "preview" to sanitizeDrawerPreviewText(session.lastMessagePreview),
            "meta" to strings.chatMessagesBadge(session.messageCount),
            "isSelected" to (session.sessionId == activeSession.sessionId),
          )
        },
      ),
      "runtimeActivity" to runtimeActivitySnapshotLocked(activeSession.sessionId),
      "isInputEnabled" to true,
    )
  }

  fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = chatListeners,
      initialPayload = loadChatSnapshot(),
      listener = listener,
    )

  fun loadChatRuntimeSnapshot(): Map<String, Any?> = synchronized(lock) {
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    runtimeActivitySnapshotLocked(activeSessionId)
  }

  fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = synchronized(lock) {
    findRunSnapshotLocked(runId)?.let(::runSnapshotToMap)
  }

  fun waitForChatRun(
    runId: String,
    timeoutMs: Long = DEFAULT_RUN_WAIT_TIMEOUT_MS,
  ): Map<String, Any?>? = waitForRunSnapshot(runId, timeoutMs)?.let(::runSnapshotToMap)

  fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = chatRuntimeListeners,
      initialPayload = loadChatRuntimeSnapshot(),
      listener = listener,
    )

  fun createChatSession() {
    synchronized(lock) {
      val createdState = chatSessionStore.createSession()
      sessionRuntimeManager.forSession(createdState.activeSession.sessionId).resume()
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun selectChatSession(sessionId: String) {
    synchronized(lock) {
      val selectedState = chatSessionStore.selectSession(sessionId)
      sessionRuntimeManager.forSession(selectedState.activeSession.sessionId).resume()
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun approveChatApproval(taskId: String) {
    synchronized(lock) {
      val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
      val approval = pendingApprovalForIdentifier(activeSessionId, taskId)
        ?: error("Pending approval '$taskId' is unavailable.")
      approvalRegistry.markApproved(activeSessionId, approval.taskId, toolName = approval.toolName)
      val retried = sessionRuntimeManager.forSession(activeSessionId).requestRetry(approval.taskId)
      if (!retried) {
        approvalRegistry.clear(activeSessionId, approval.taskId)
        error("Unable to retry pending approval '$taskId'.")
      }
      clearPendingApprovalLocked(activeSessionId, approval.taskId)
      approval.pendingMessageId?.let { pendingMessageId ->
        chatSessionStore.replaceMessage(
          sessionId = activeSessionId,
          messageId = pendingMessageId,
          role = ChatTranscriptRole.ASSISTANT,
          text = strings.agentThinking,
        )
      }
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun rejectChatApproval(taskId: String) {
    synchronized(lock) {
      val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
      val approval = pendingApprovalForIdentifier(activeSessionId, taskId)
        ?: error("Pending approval '$taskId' is unavailable.")
      approvalRegistry.markRejected(activeSessionId, approval.taskId)
      clearPendingApprovalLocked(activeSessionId, approval.taskId)
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun submitChatMessage(text: String): Map<String, Any?>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) {
      return null
    }

    val submission = synchronized(lock) {
      val activeState = chatSessionStore.loadState()
      val sessionId = activeState.activeSession.sessionId
      val handle = sessionRuntimeManager.forSession(sessionId)
      val pendingMessageId = chatSessionStore.reserveMessageId(ChatTranscriptRole.ASSISTANT)
      val submittedRun = handle.submitPrompt(
        userText = ChatRuntimeTextFormatter.format(
          text = trimmed,
          commandLabel = null,
          attachments = emptyList(),
        ),
        pendingMessageId = pendingMessageId,
        visibleThroughMessageId = pendingMessageId,
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = "FLUTTER_CHAT_ALLOW",
          detail = "Flutter chat currently runs with host-managed allow mode.",
        ),
        metadata = mapOf(
          "chatMode" to DEFAULT_MODE_LABEL,
        ),
      )
      try {
        chatSessionStore.appendSubmittedTurn(
          sessionId = sessionId,
          userText = trimmed,
          assistantMessageId = pendingMessageId,
          assistantPlaceholderText = strings.agentThinking,
        )
      } catch (throwable: Throwable) {
        handle.requestCancel(submittedRun.taskId)
        throw throwable
      }
      handle.ensureProcessing()
      submittedRun
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
    return runSubmissionToMap(submission)
  }

  private fun ensureActiveSessionResumed() {
    val activeSessionId = synchronized(lock) { chatSessionStore.loadState().activeSession.sessionId }
    sessionRuntimeManager.forSession(activeSessionId).resume()
  }

  private fun pendingTaskCount(sessionId: String): Int = sessionRuntimeManager.forSession(sessionId)
    .snapshot()
    .tasks
    .count { taskSnapshot ->
      when (taskSnapshot.lifecycleState) {
        QueueTaskLifecycleState.QUEUED,
        QueueTaskLifecycleState.RUNNING,
        QueueTaskLifecycleState.RETRY_PENDING,
        QueueTaskLifecycleState.CANCEL_REQUESTED,
        -> true

        QueueTaskLifecycleState.COMPLETED,
        QueueTaskLifecycleState.CANCELLED,
        QueueTaskLifecycleState.FAILED,
        -> false
      }
    }

  private fun pendingApprovalsForSession(sessionId: String): List<PendingApprovalSnapshot> {
    val snapshot = sessionRuntimeManager.forSession(sessionId).snapshot()
    val transientApprovals = pendingApprovalsBySession[sessionId].orEmpty()
    approvalRegistry.retainKnownTasks(
      sessionId = sessionId,
      taskIds = snapshot.tasks.mapTo(linkedSetOf()) { taskSnapshot -> taskSnapshot.task.id } + transientApprovals.keys,
    )

    val combined = linkedMapOf<String, PendingApprovalSnapshot>()
    snapshot.tasks
      .asSequence()
      .filter { taskSnapshot ->
        taskSnapshot.lifecycleState == QueueTaskLifecycleState.FAILED &&
          isApprovalRequiredError(taskSnapshot.lastErrorCode)
      }
      .map { taskSnapshot ->
        val isHighRisk = taskSnapshot.lastErrorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED
        PendingApprovalSnapshot(
          runId = runIdFor(taskSnapshot.task),
          taskId = taskSnapshot.task.id,
          pendingMessageId = taskSnapshot.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.takeIf(String::isNotBlank),
          toolName = null,
          isHighRisk = isHighRisk,
          title = if (isHighRisk) {
            strings.chatHighRiskApprovalRequiredTitle
          } else {
            strings.chatApprovalRequiredTitle
          },
          body = sanitizeApprovalBody(
            body = taskSnapshot.lastErrorMessage,
            isHighRisk = isHighRisk,
          ),
        )
      }
      .forEach { approval ->
        combined[approval.taskId] = approval
      }
    transientApprovals.values.forEach { approval ->
      combined[approval.taskId] = approval
    }
    return combined.values.filter { approval ->
      !approvalRegistry.isApproved(sessionId, approval.taskId) &&
        !approvalRegistry.isRejected(sessionId, approval.taskId)
    }
  }

  private fun pendingApprovalForIdentifier(
    sessionId: String,
    taskIdOrRunId: String,
  ): PendingApprovalSnapshot? = pendingApprovalsForSession(sessionId)
    .firstOrNull { approval ->
      approval.taskId == taskIdOrRunId || approval.runId == taskIdOrRunId
    }

  private fun recordPendingApprovalLocked(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    val isHighRisk = result.errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED
    val approval = PendingApprovalSnapshot(
      runId = runIdFor(task),
      taskId = task.id,
      pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
        ?.takeIf(String::isNotBlank),
      toolName = result.metadata["toolName"]?.takeIf(String::isNotBlank),
      isHighRisk = isHighRisk,
      title = if (isHighRisk) {
        strings.chatHighRiskApprovalRequiredTitle
      } else {
        strings.chatApprovalRequiredTitle
      },
      body = composeApprovalBody(
        body = sanitizeApprovalBody(
          body = result.errorMessage,
          isHighRisk = isHighRisk,
        ),
        toolReason = result.metadata["toolReason"],
      ),
    )
    pendingApprovalsBySession
      .getOrPut(sessionId) { linkedMapOf() }[task.id] = approval
  }

  private fun clearPendingApprovalLocked(sessionId: String, taskId: String) {
    val sessionApprovals = pendingApprovalsBySession[sessionId] ?: return
    sessionApprovals.remove(taskId)
    if (sessionApprovals.isEmpty()) {
      pendingApprovalsBySession.remove(sessionId)
    }
  }

  private fun recordRuntimeEventLocked(sessionId: String, event: OpenCrayAgentRunEvent) {
    val events = runtimeEventsBySession.getOrPut(sessionId) { ArrayDeque() }
    events += event
    while (events.size > MAX_RUNTIME_EVENT_HISTORY) {
      events.removeFirst()
    }
  }

  private fun runtimeActivitySnapshotLocked(sessionId: String): Map<String, Any?> {
    val runs = sessionRuntimeManager.forSession(sessionId).listRuns()
    val recentEvents = runtimeEventsBySession[sessionId]?.toList().orEmpty()
    return mapOf(
      "sessionId" to sessionId,
      "activeRuns" to runs
        .filterNot(AgentRunSnapshot::isTerminal)
        .map { run ->
          runSnapshotToMap(
            run.copy(
              lastEvent = run.lastEvent ?: recentEvents.lastOrNull { event -> event.runId == run.runId },
            ),
          )
        },
      "events" to recentEvents.map(::runtimeEventToMap),
    )
  }

  private fun findRunSnapshotLocked(runId: String): AgentRunSnapshot? {
    val sessionIds = chatSessionStore.loadState().sessions
      .mapTo(linkedSetOf()) { session -> session.sessionId }
    return sessionIds.firstNotNullOfOrNull { sessionId ->
      sessionRuntimeManager.forSession(sessionId).findRun(runId)
    }
  }

  private fun waitForRunSnapshot(runId: String, timeoutMs: Long): AgentRunSnapshot? {
    val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
    val existing = synchronized(lock) { findRunSnapshotLocked(runId) }
    if (existing != null) {
      return sessionRuntimeManager.forSession(existing.sessionId).waitForRun(runId, boundedTimeoutMs)
    }
    val deadline = System.currentTimeMillis() + boundedTimeoutMs
    while (true) {
      val discovered = synchronized(lock) { findRunSnapshotLocked(runId) }
      if (discovered != null) {
        return sessionRuntimeManager.forSession(discovered.sessionId).waitForRun(
          runId,
          (deadline - System.currentTimeMillis()).coerceAtLeast(0L),
        )
      }
      if (System.currentTimeMillis() >= deadline) {
        return null
      }
      Thread.sleep(RUN_LOOKUP_POLL_INTERVAL_MS)
    }
  }

  private fun runSubmissionToMap(submission: AgentRunSubmission): Map<String, Any?> = mapOf(
    "sessionId" to submission.sessionId,
    "runId" to submission.runId,
    "taskId" to submission.taskId,
    "acceptedAtEpochMs" to submission.acceptedAtEpochMs,
  )

  private fun runSnapshotToMap(run: AgentRunSnapshot): Map<String, Any?> = mapOf(
    "sessionId" to run.sessionId,
    "runId" to run.runId,
    "taskId" to run.taskId,
    "acceptedAtEpochMs" to run.acceptedAtEpochMs,
    "updatedAtEpochMs" to run.updatedAtEpochMs,
    "lifecycleState" to run.lifecycleState?.name?.lowercase(),
    "taskState" to run.taskState?.name?.lowercase(),
    "attempt" to run.attempt,
    "executionStatus" to run.executionStatus?.name?.lowercase(),
    "errorCode" to run.errorCode,
    "errorMessage" to run.errorMessage,
    "responseFormat" to run.responseFormat,
    "pendingMessageId" to run.pendingMessageId,
    "isTerminal" to run.isTerminal,
    "lastEvent" to run.lastEvent?.let(::runtimeEventToMap),
  )

  private fun runIdFor(task: AgentTask): String =
    task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private fun runtimeEventToMap(event: OpenCrayAgentRunEvent): Map<String, Any?> = when (event) {
    is OpenCrayLifecycleEvent -> mapOf(
      "kind" to "lifecycle",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "phase" to event.phase.name.lowercase(),
      "status" to event.status?.name?.lowercase(),
      "errorCode" to event.errorCode,
      "errorMessage" to event.errorMessage,
    )
    is OpenCrayAssistantEvent -> mapOf(
      "kind" to "assistant",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "responseFormat" to event.responseFormat,
      "isFinal" to event.isFinal,
      "text" to event.text,
    )
    is OpenCrayToolCallEvent -> mapOf(
      "kind" to "tool_call",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.call.toolName,
      "toolReason" to event.call.reason,
      "argumentsJson" to event.call.arguments.toString(),
    )
    is OpenCrayToolResultEvent -> mapOf(
      "kind" to "tool_result",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.call.toolName,
      "toolStatus" to event.result.status.name.lowercase(),
      "errorCode" to event.result.errorCode,
      "errorMessage" to event.result.errorMessage,
      "contentPreview" to event.result.content.take(MAX_RUNTIME_EVENT_PREVIEW_CHARS),
    )
  }

  private fun isApprovalRequiredResult(result: ExecutionResult): Boolean =
    result.status == ExecutionStatus.DENIED && isApprovalRequiredError(result.errorCode)

  private fun isApprovalRequiredError(errorCode: String?): Boolean =
    errorCode == ERROR_APPROVAL_REQUIRED || errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED

  private fun isVisibleChatMessage(message: ChatTranscriptMessageEntry): Boolean =
    message.role != ChatTranscriptRole.SYSTEM

  private fun displaySessionTitle(rawTitle: String): String =
    if (rawTitle == ChatSessionLocalStore.DEFAULT_SESSION_TITLE) {
      strings.chatDefaultSessionTitle
    } else {
      rawTitle
    }

  private fun chatMessageToMap(message: ChatTranscriptMessageEntry): Map<String, Any?> {
    val resolvedText = message.text ?: chatSessionStore.promptTemplateBody(message.promptTemplateRefId).orEmpty()
    val kind = when (message.role) {
      ChatTranscriptRole.USER -> "outbound"
      ChatTranscriptRole.ASSISTANT -> "inbound"
      ChatTranscriptRole.TOOL -> "timeline"
      ChatTranscriptRole.SYSTEM -> "timeline"
    }
    val visibleText = when (message.role) {
      ChatTranscriptRole.ASSISTANT -> sanitizePotentialInternalAgentText(
        text = resolvedText,
        fallback = strings.agentInternalPayloadHidden,
      )

      else -> resolvedText
    }
    return mapOf(
      "kind" to kind,
      "text" to visibleText,
      "meta" to "",
    )
  }

  private fun finalTextFor(result: ExecutionResult): String {
    val rawText = when (result.status) {
      ExecutionStatus.SUCCESS -> result.stdout.ifBlank { strings.agentEmptyAnswer }
      ExecutionStatus.CANCELLED -> strings.agentCancelled
      ExecutionStatus.DENIED -> result.errorMessage ?: strings.agentFailed(
        result.errorCode ?: result.status.name,
      )
      ExecutionStatus.FAILED -> if (result.errorCode == AppAgentSessionTaskRuntimeFactory.ERROR_CODE_MISSING_LLM_CONFIG) {
        strings.agentMissingLlm
      } else {
        strings.agentFailed(result.errorMessage ?: result.errorCode ?: result.status.name)
      }

      else -> strings.agentFailed(result.errorMessage ?: result.errorCode ?: result.status.name)
    }
    return sanitizePotentialInternalAgentText(
      text = rawText,
      fallback = when (result.status) {
        ExecutionStatus.SUCCESS -> strings.agentInternalPayloadHidden
        ExecutionStatus.DENIED -> approvalFallbackBody(
          isHighRisk = result.errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED,
        )
        else -> strings.agentInternalPayloadHidden
      },
    )
  }

  private fun sanitizeApprovalBody(body: String?, isHighRisk: Boolean): String {
    val fallback = approvalFallbackBody(isHighRisk = isHighRisk)
    val resolved = body?.takeIf(String::isNotBlank) ?: return fallback
    return sanitizePotentialInternalAgentText(
      text = resolved,
      fallback = fallback,
    )
  }

  private fun approvalFallbackBody(isHighRisk: Boolean): String = if (isHighRisk) {
    strings.chatHighRiskApprovalRequiredBody
  } else {
    strings.chatSummaryApprovalRequired
  }

  private fun sanitizeDrawerPreviewText(text: String): String {
    val restoredFallback = restoreKnownPreviewFallback(text.trim())
    if (restoredFallback != null) {
      return restoredFallback
    }
    return sanitizePotentialInternalAgentText(
      text = text,
      fallback = strings.agentInternalPayloadHidden,
    )
  }

  private fun restoreKnownPreviewFallback(text: String): String? {
    val knownFallbacks = listOf(
      strings.agentInternalPayloadHidden,
      strings.chatSummaryApprovalRequired,
      strings.chatHighRiskApprovalRequiredBody,
    )
    return knownFallbacks.firstOrNull { fallback ->
      text == fallback.take(DRAWER_PREVIEW_MAX_CHARS).trimEnd()
    }
  }

  private fun composeApprovalBody(body: String, toolReason: String?): String {
    val reason = toolReason
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return body
    return buildString {
      appendLine(body)
      append("Reason: ")
      append(reason)
    }
  }

  private fun sanitizePotentialInternalAgentText(text: String, fallback: String): String {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return text
    return if (looksLikeInternalToolPayload(trimmed)) fallback else text
  }

  private fun looksLikeInternalToolPayload(text: String): Boolean {
    val jsonCandidate = extractEmbeddedJsonObject(text) ?: return false
    val normalized = jsonCandidate.lowercase()
    val explicitToolAction =
      "\"type\"" in normalized &&
        ("\"tool_call\"" in normalized || "\"tool\"" in normalized)
    val toolArgumentShape = "\"tool_name\"" in normalized && "\"arguments\"" in normalized
    return explicitToolAction || toolArgumentShape
  }

  private fun extractEmbeddedJsonObject(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      return trimmed
    }
    var depth = 0
    var startIndex = -1
    var inString = false
    var escaped = false
    for ((index, character) in raw.withIndex()) {
      when {
        inString && escaped -> escaped = false
        inString && character == '\\' -> escaped = true
        character == '"' -> inString = !inString
        !inString && character == '{' -> {
          if (depth == 0) {
            startIndex = index
          }
          depth += 1
        }

        !inString && character == '}' -> {
          depth -= 1
          if (depth == 0 && startIndex >= 0) {
            return raw.substring(startIndex, index + 1)
          }
        }
      }
    }
    return null
  }

  internal fun currentMcpExposureReport() =
    synchronized(lock) { mcpSettingsFacade.currentExposureReport() }

  internal fun currentEnabledSkillRoots() =
    synchronized(lock) { skillsFacade.enabledSkillRoots() }

  private fun refreshLocalizedResourcesLocked() {
    val baseContext = appContext ?: return
    val localizedContext = OpenCrayLocaleManager.wrap(baseContext)
    settingsFacade = LocalSettingsFacade.fromContext(localizedContext)
    llmConfigFacade = LocalLlmConfigFacade.fromContext(localizedContext)
    personalizationFacade = LocalPersonalizationFacade.createForTest(
      context = localizedContext,
      store = PersonalizationLocalStore.fromContext(baseContext),
      queueIdleProvider = {
        val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
        pendingTaskCount(activeSessionId) == 0
      },
    )
    mcpSettingsFacade = LocalMcpSettingsFacade.createForTest(
      context = localizedContext,
      settingsStore = McpSettingsStore.fromContext(baseContext),
      registryStore = AppMcpRegistryStore.fromContext(baseContext),
    )
    skillsFacade = LocalSkillsFacade.fromContext(localizedContext)
    strings = localizedHostRuntimeStrings(localizedContext)
  }

  private fun SettingsOverviewSnapshot.toMap(): Map<String, Any?> = mapOf(
    "eyebrow" to eyebrow,
    "title" to title,
    "subtitle" to subtitle,
    "deviceTitle" to deviceTitle,
    "deviceSummary" to deviceSummary,
    "entries" to entries.map { entry ->
      mapOf(
        "routeId" to entry.routeId.wireValue,
        "title" to entry.title,
      )
    },
  )

  private fun SettingsDetailSnapshot.toMap(): Map<String, Any?> = mapOf(
    "routeId" to routeId.wireValue,
    "title" to title,
    "subtitle" to subtitle,
    "sections" to sections.map { section -> section.toMap() },
  )

  private fun SettingsSectionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "helperText" to helperText,
    "rows" to rows.map { row -> row.toMap() },
    "segmentedOptions" to segmentedOptions,
    "segmentedIndex" to segmentedIndex,
    "inlinePanelText" to inlinePanelText,
    "backgroundTone" to backgroundTone.wireValue,
  )

  private fun SettingsRowSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "subtitle" to subtitle,
    "trailingKind" to trailingKind.wireValue,
    "toggleValue" to toggleValue,
    "valueLabel" to valueLabel,
  )

  private fun LlmConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
    "localeTag" to localeTag,
    "enabled" to enabled,
    "providerId" to providerId,
    "protocol" to protocol,
    "providerOptions" to providerOptions.map { option -> option.toMap() },
    "providerName" to providerName,
    "providerNotes" to providerNotes,
    "baseUrl" to baseUrl,
    "apiKey" to apiKey,
    "model" to model,
    "reasoningEffort" to reasoningEffort,
    "systemPrompt" to systemPrompt,
    "helperText" to helperText,
  )

  private fun LlmProviderOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "subtitle" to subtitle,
    "defaultBaseUrl" to defaultBaseUrl,
    "defaultModel" to defaultModel,
    "isCustom" to isCustom,
  )

  private fun LlmValidationResult.toMap(): Map<String, Any?> = mapOf(
    "isSuccess" to isSuccess,
    "message" to message,
  )

  private fun PersonalizationConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "subtitle" to subtitle,
    "introTitle" to introTitle,
    "introBody" to introBody,
    "introHelper" to introHelper,
    "presetsTitle" to presetsTitle,
    "presetsHelper" to presetsHelper,
    "presets" to presets.map { preset -> preset.toMap() },
    "selectedPresetId" to selectedPresetId,
    "customOverlayTitle" to customOverlayTitle,
    "customOverlayHelper" to customOverlayHelper,
    "customLabelHint" to customLabelHint,
    "customLabelHelper" to customLabelHelper,
    "customGuidanceHint" to customGuidanceHint,
    "customGuidanceHelper" to customGuidanceHelper,
    "customLabel" to customLabel,
    "customGuidance" to customGuidance,
    "behaviorDefaultsTitle" to behaviorDefaultsTitle,
    "appLanguageTitle" to appLanguageTitle,
    "appLanguageOptions" to appLanguageOptions.map { option -> option.toMap() },
    "selectedAppLanguageId" to selectedAppLanguageId,
    "livePreviewTitle" to livePreviewTitle,
    "livePreviewName" to livePreviewName,
    "livePreviewSummary" to livePreviewSummary,
    "queueTitle" to queueTitle,
    "queueBody" to queueBody,
    "queueIsIdle" to queueIsIdle,
    "lastResetTitle" to lastResetTitle,
    "lastResetMessage" to lastResetMessage,
    "resetActions" to resetActions.map { action -> action.toMap() },
  )

  private fun PersonalizationPresetSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "summary" to summary,
    "voice" to voice,
    "status" to status,
    "isSelected" to isSelected,
  )

  private fun PersonalizationLanguageOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "isSelected" to isSelected,
  )

  private fun PersonalizationResetActionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "scopeId" to scope.wireValue,
    "title" to title,
    "scopeBody" to scopeBody,
    "retainBody" to retainBody,
    "confirmationToken" to confirmationToken,
    "inputHint" to inputHint,
    "disabledGuidance" to disabledGuidance,
    "typeExactGuidance" to typeExactGuidance,
    "armedGuidance" to armedGuidance,
    "isInputEnabled" to isInputEnabled,
  )

  private fun McpSettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "subtitle" to subtitle,
    "masterTitle" to masterTitle,
    "masterSummary" to masterSummary,
    "masterEnabled" to masterEnabled,
    "summaryLine" to summaryLine,
    "serversTitle" to serversTitle,
    "serversHelper" to serversHelper,
    "masterDisabledTitle" to masterDisabledTitle,
    "masterDisabledBody" to masterDisabledBody,
    "servers" to servers.map { server -> server.toMap() },
  )

  private fun McpServerSettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "statusLabel" to statusLabel,
    "statusTone" to statusTone,
    "trustLine" to trustLine,
    "authLine" to authLine,
    "readinessLine" to readinessLine,
    "transportLine" to transportLine,
    "exposureLine" to exposureLine,
    "guidance" to guidance,
    "actionLabel" to actionLabel,
    "actionTurnsOn" to actionTurnsOn,
    "isActionEnabled" to isActionEnabled,
  )

  private fun SkillsSnapshot.toMap(): Map<String, Any?> = mapOf(
    "installedSkills" to installedSkills.map { skill -> skill.toMap() },
    "installSources" to installSources.map { source -> source.toMap() },
    "suggestedSkills" to suggestedSkills.map { suggestion -> suggestion.toMap() },
  )

  private fun InstalledSkillSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "description" to description,
    "isEnabled" to isEnabled,
    "sourceDirectoryPath" to sourceDirectoryPath,
    "canDelete" to canDelete,
  )

  private fun InstallSourceSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "subtitle" to subtitle,
    "actionLabel" to actionLabel,
    "isAvailable" to isAvailable,
  )

  private fun SuggestedSkillSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "description" to description,
  )

  private fun SkillInstructionsSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "description" to description,
    "body" to body,
    "sourceDirectoryPath" to sourceDirectoryPath,
    "isEnabled" to isEnabled,
    "canDelete" to canDelete,
  )

  private fun observeWithInitial(
    listeners: LinkedHashSet<(Map<String, Any?>) -> Unit>,
    initialPayload: Map<String, Any?>,
    listener: (Map<String, Any?>) -> Unit,
  ): () -> Unit {
    synchronized(lock) {
      listeners += listener
    }
    mainThreadPoster.post { listener(initialPayload) }
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  private fun emitChatSnapshot() {
    val payload = loadChatSnapshot()
    emitSnapshot(chatListeners, payload)
  }

  private fun emitChatRuntimeSnapshot() {
    val payload = loadChatRuntimeSnapshot()
    emitSnapshot(chatRuntimeListeners, payload)
  }

  private fun emitShellSnapshot() {
    val payload = loadShellSnapshot()
    emitSnapshot(shellListeners, payload)
  }

  private fun emitSettingsOverview() {
    val payload = loadSettingsOverview()
    emitSnapshot(settingsOverviewListeners, payload)
  }

  private fun emitSkillsSnapshot() {
    val payload = loadSkillsSnapshot()
    emitSnapshot(skillsListeners, payload)
  }

  private fun emitSnapshot(
    listeners: LinkedHashSet<(Map<String, Any?>) -> Unit>,
    payload: Map<String, Any?>,
  ) {
    val currentListeners = synchronized(lock) { listeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      currentListeners.forEach { listener -> listener(payload) }
    }
  }

  companion object {
    private const val DEFAULT_MODE_LABEL: String = "AUTO"
    private const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    private const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    private const val DEFAULT_RUN_WAIT_TIMEOUT_MS: Long = 15_000L
    private const val RUN_LOOKUP_POLL_INTERVAL_MS: Long = 50L
    private const val MAX_RUNTIME_EVENT_HISTORY: Int = 24
    private const val MAX_RUNTIME_EVENT_PREVIEW_CHARS: Int = 240
    private const val DRAWER_PREVIEW_MAX_CHARS: Int = 52

    @Volatile
    private var instance: OpenCrayHostRuntime? = null

    fun fromContext(context: Context): OpenCrayHostRuntime =
      instance ?: synchronized(this) {
        instance ?: createFromContext(context.applicationContext).also { created ->
          instance = created
        }
      }

    internal fun createForTest(
      stateStore: AppShellStateStore,
      chatSessionStore: ChatSessionLocalStore,
      settingsFacade: SettingsFacade,
      llmConfigFacade: LlmConfigFacade = EmptyLlmConfigFacade,
      personalizationFacade: PersonalizationFacade = EmptyPersonalizationFacade,
      mcpSettingsFacade: McpSettingsFacade = EmptyMcpSettingsFacade,
      skillsFacade: SkillsFacade = EmptySkillsFacade,
      workspaceSnapshotProvider: () -> Map<String, Any?> = {
        WorkspaceTreeSnapshot(
          rootName = AppAgentWorkspace.DIRECTORY_NAME,
          rootPath = AppAgentWorkspace.DIRECTORY_NAME,
          availableBytes = 0L,
          directoryCount = 0,
          fileCount = 0,
          entryCount = 0,
          isTruncated = false,
          children = emptyList(),
        ).toMap()
      },
      sessionRuntimeManager: AgentSessionRuntimeManager,
      approvalRegistry: AgentTaskApprovalRegistry = AgentTaskApprovalRegistry(),
      strings: HostRuntimeStrings = HostRuntimeStrings(
        localeTag = "en",
        shellHostLabel = "HOST CONNECTED",
        shellHostSummary = "Android host bridge is attached to the live app runtime.",
        chatScreenTitle = "Chat",
        chatModeLabel = "AUTO",
        chatSessionButtonLabel = "Sessions",
        chatRecentSessionsEyebrow = "Recent sessions",
        chatRecentSessionsTitle = "Recent sessions",
        chatNewSessionLabel = "New session",
        chatDefaultSessionTitle = "New chat",
        chatMessagesBadge = { count -> "$count messages" },
        chatSummaryReplyInProgress = "Reply in progress",
        chatSummaryStartNewSession = "Start a new session",
        chatSummaryRestored = "Local transcript is restored into the runtime window for each task.",
        skillInstalled = { skillId -> "Installed $skillId." },
        skillRemoved = { skillId -> "Removed $skillId." },
        skillsReloaded = "Reloaded skills from local storage.",
        composerPlaceholder = "Message OpenCray",
        agentThinking = "Thinking",
        agentCancelled = "Cancelled",
        agentMissingLlm = "Missing LLM",
        agentEmptyAnswer = "The model returned an empty answer.",
        agentFailed = { detail -> "Failed: $detail" },
      ),
      mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
    ): OpenCrayHostRuntime = OpenCrayHostRuntime(
      appContext = null,
      stateStore = stateStore,
      chatSessionStore = chatSessionStore,
      settingsFacade = settingsFacade,
      llmConfigFacade = llmConfigFacade,
      personalizationFacade = personalizationFacade,
      mcpSettingsFacade = mcpSettingsFacade,
      skillsFacade = skillsFacade,
      workspaceSnapshotProvider = workspaceSnapshotProvider,
      sessionRuntimeManager = sessionRuntimeManager,
      approvalRegistry = approvalRegistry,
      strings = strings,
      mainThreadPoster = mainThreadPoster,
    )

    private fun createFromContext(appContext: Context): OpenCrayHostRuntime {
      BuiltinSkillsSeeder.fromContext(appContext).seedBundledSkillsIfNeeded()
      val localizedContext = OpenCrayLocaleManager.wrap(appContext)
      val llmSettingsStore = LlmSettingsStore.fromContext(appContext)
      val personalizationStore = PersonalizationLocalStore.fromContext(appContext)
      val chatSessionStore = ChatSessionLocalStore.fromContext(appContext)
      val skillsFacade = LocalSkillsFacade.fromContext(localizedContext)
      val mcpSettingsStore = McpSettingsStore.fromContext(appContext)
      val mcpRegistryStore = AppMcpRegistryStore.fromContext(appContext)
      val providerUserAgent = OpenCrayUserAgent.fromContext(appContext)
      val chatExecutor: ExecutorService = Executors.newSingleThreadExecutor()
      val chatContextFactory = ChatRuntimeSessionContextFactory(chatSessionStore)
      val approvalRegistry = AgentTaskApprovalRegistry()
      val workspaceSnapshotProvider = {
        AppAgentWorkspaceSnapshotFactory.createSnapshot(
          AppAgentWorkspace.ensureRootForContext(appContext),
        ).toMap()
      }
      lateinit var hostRuntime: OpenCrayHostRuntime
      val personalizationFacade = LocalPersonalizationFacade.createForTest(
        context = localizedContext,
        store = personalizationStore,
        queueIdleProvider = {
          val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
          hostRuntime.pendingTaskCount(activeSessionId) == 0
        },
      )
      val mcpSettingsFacade = LocalMcpSettingsFacade.createForTest(
        context = localizedContext,
        settingsStore = mcpSettingsStore,
        registryStore = mcpRegistryStore,
      )
      hostRuntime = OpenCrayHostRuntime(
        appContext = appContext,
        stateStore = AppShellStateStore.fromContext(appContext),
        chatSessionStore = chatSessionStore,
        settingsFacade = LocalSettingsFacade.fromContext(localizedContext),
        llmConfigFacade = LocalLlmConfigFacade.fromContext(localizedContext),
        personalizationFacade = personalizationFacade,
        mcpSettingsFacade = mcpSettingsFacade,
        skillsFacade = skillsFacade,
        workspaceSnapshotProvider = workspaceSnapshotProvider,
        sessionRuntimeManager = DefaultAgentSessionRuntimeManager(
          agentId = "opencray-flutter-host",
          runtimeFactory = AppAgentSessionTaskRuntimeFactory(
            llmSettingsProvider = { llmSettingsStore.load() },
            sessionContextFactory = chatContextFactory,
            soulProfileProvider = { personalizationStore.loadSoulProfile() },
            workspaceRootsProvider = { setOf(AppAgentWorkspace.ensureRootForContext(appContext)) },
            skillsRootsProvider = { hostRuntime.currentEnabledSkillRoots() },
            mcpReportProvider = { hostRuntime.currentMcpExposureReport() },
            memoryRecordsProvider = personalizationStore::listMemoryRecords,
            providerUserAgent = providerUserAgent,
            approvalRegistry = approvalRegistry,
          ),
          snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
          executor = chatExecutor,
        ),
        approvalRegistry = approvalRegistry,
        strings = localizedHostRuntimeStrings(localizedContext),
        mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
      )
      return hostRuntime
    }

    private fun localizedHostRuntimeStrings(context: Context): HostRuntimeStrings = HostRuntimeStrings(
      localeTag = LocaleSettingsStore.fromContext(context).loadLanguage().tag,
      shellHostLabel = context.getString(R.string.flutter_host_label_android),
      shellHostSummary = context.getString(R.string.flutter_host_summary_android),
      chatScreenTitle = context.getString(R.string.shell_tab_chat),
      chatModeLabel = context.getString(R.string.chat_mode_auto),
      chatSessionButtonLabel = context.getString(R.string.chat_sessions_button),
      chatRecentSessionsEyebrow = context.getString(R.string.chat_recent_sessions_eyebrow),
      chatRecentSessionsTitle = context.getString(R.string.chat_recent_sessions_title),
      chatNewSessionLabel = context.getString(R.string.chat_new_session),
      chatDefaultSessionTitle = context.getString(R.string.chat_default_session_title),
      chatMessagesBadge = { count ->
        context.getString(R.string.chat_messages_badge, count)
      },
      chatSummaryReplyInProgress = context.getString(R.string.chat_summary_reply_in_progress),
      chatSummaryStartNewSession = context.getString(R.string.chat_summary_start_new_session),
      chatSummaryRestored = context.getString(R.string.chat_summary_restored),
      skillInstalled = { skillId ->
        context.getString(R.string.skills_message_installed, skillId)
      },
      skillRemoved = { skillId ->
        context.getString(R.string.skills_message_removed, skillId)
      },
      skillsReloaded = context.getString(R.string.skills_message_reloaded),
      composerPlaceholder = context.getString(R.string.chat_message_opencray),
      agentThinking = context.getString(R.string.chat_agent_thinking),
      agentCancelled = context.getString(R.string.chat_agent_cancelled),
      agentMissingLlm = context.getString(R.string.chat_agent_missing_llm),
      agentEmptyAnswer = context.getString(
        R.string.chat_agent_failed,
        "The model returned an empty answer.",
      ),
      agentFailed = { detail ->
        context.getString(R.string.chat_agent_failed, detail)
      },
    )
  }
}

internal data class HostRuntimeStrings(
  val localeTag: String,
  val shellHostLabel: String,
  val shellHostSummary: String,
  val chatScreenTitle: String,
  val chatModeLabel: String,
  val chatSessionButtonLabel: String,
  val chatRecentSessionsEyebrow: String,
  val chatRecentSessionsTitle: String,
  val chatNewSessionLabel: String,
  val chatDefaultSessionTitle: String,
  val chatMessagesBadge: (Int) -> String,
  val chatSummaryReplyInProgress: String,
  val chatSummaryStartNewSession: String,
  val chatSummaryRestored: String,
  val skillInstalled: (String) -> String,
  val skillRemoved: (String) -> String,
  val skillsReloaded: String,
  val composerPlaceholder: String,
  val agentThinking: String,
  val agentCancelled: String,
  val agentMissingLlm: String,
  val agentEmptyAnswer: String,
  val agentFailed: (String) -> String,
  val agentInternalPayloadHidden: String = "The agent produced an internal tool payload instead of a user-facing reply.",
  val chatSummaryApprovalRequired: String = "Approval required before the agent can continue.",
  val chatApprovalRequiredTitle: String = "Approval required",
  val chatHighRiskApprovalRequiredTitle: String = "High-risk approval required",
  val chatHighRiskApprovalRequiredBody: String = "High-risk approval required. Review this request carefully before approving.",
  val chatApprovalApproveLabel: String = "Approve",
  val chatApprovalRejectLabel: String = "Reject",
)

private data class PendingApprovalSnapshot(
  val runId: String,
  val taskId: String,
  val pendingMessageId: String?,
  val toolName: String?,
  val isHighRisk: Boolean,
  val title: String,
  val body: String,
)

internal fun interface MainThreadPoster {
  fun post(action: () -> Unit)
}

internal class HandlerMainThreadPoster(
  private val handler: Handler,
) : MainThreadPoster {
  override fun post(action: () -> Unit) {
    handler.post(action)
  }
}

internal object ImmediateMainThreadPoster : MainThreadPoster {
  override fun post(action: () -> Unit) {
    action()
  }
}
