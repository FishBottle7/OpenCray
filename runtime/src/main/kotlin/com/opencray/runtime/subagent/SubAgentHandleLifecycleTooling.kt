package com.opencray.runtime.subagent

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAgentRuntime
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.SUBAGENT_WAIT_PROGRESS_POLL_INTERVAL_MS
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.optionalBooleanContent
import com.opencray.runtime.primitiveContent
import com.opencray.runtime.stringArrayContent
import com.opencray.runtime.skills.ActiveSkillCapsule
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

  internal fun OpenCrayAgentRuntime.waitOnSubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): AgentToolResult {
    val handles = subAgentHandleRegistry(cursor)
    val agentId = resolveSubAgentHandleId(call)
      ?: return invalidSubAgentCallResult(call, "wait_agent agent_id must not be blank.")
    val handle = handles[agentId]
      ?: if (cursor == null) {
        latestCoordinatedSubAgentHandleByAgentId(agentId)
          ?: latestClosedCoordinatedSubAgentHandleByAgentId(agentId)
      } else {
        null
      }
      ?: return unknownSubAgentHandleResult(
        call = call,
        agentId = agentId,
      )
    return waitOnResolvedSubAgentHandle(
      task = task,
      turn = turn,
      call = call,
      transcript = transcript,
      cursor = cursor,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = handle,
      handles = handles,
    )
  }

  internal fun OpenCrayAgentRuntime.waitOnRecoverySubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    agentId: String,
    parentRunId: String,
    startDetachedExecutionIfNeeded: Boolean = false,
  ): AgentToolResult {
    val key = SubAgentExecutionKey(
      parentRunId = parentRunId,
      agentId = agentId,
    )
    val handle = config.subAgentExecutionCoordinator.currentHandle(key)
      ?: config.subAgentExecutionCoordinator.closedHandle(key)
      ?: config.seededSubAgentHandles.firstOrNull { seeded ->
        seeded.agentId == agentId && seeded.parentRunId == parentRunId
      }
      ?: return unknownSubAgentHandleResult(
        call = call,
        agentId = agentId,
      )
    val restoredHandle = restoredSubAgentHandle(handle)
    return waitOnResolvedSubAgentHandle(
      task = task,
      turn = turn,
      call = call,
      transcript = transcript,
      cursor = null,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = restoredHandle,
      handles = linkedMapOf(restoredHandle.agentId to restoredHandle),
      startDetachedExecutionIfNeeded = startDetachedExecutionIfNeeded,
    )
  }

  internal fun OpenCrayAgentRuntime.waitOnResolvedSubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    handle: SubAgentHandleState,
    handles: MutableMap<String, SubAgentHandleState>,
    startDetachedExecutionIfNeeded: Boolean = true,
  ): AgentToolResult {
    val agentId = handle.agentId
    if (handle.isTerminalWithoutPendingApprovalResume()) {
      return storedSubAgentHandleResult(call = call, handle = handle)
    }
    val hadActiveExecution = cursor != null && activeSubAgentExecution(cursor = cursor, agentId = agentId) != null
    if (cursor != null && hadActiveExecution) {
      waitForActiveSubAgentExecution(
        cursor = cursor,
        agentId = agentId,
        onProgress = { progressHandle ->
          emitSubAgentWaitProgressEvent(
            task = task,
            turn = turn,
            handle = progressHandle,
          )
        },
      )
      val latestHandleAfterJoin = synchronizedSubAgentHandle(
        cursor = cursor,
        agentId = agentId,
      )
      if (
        latestHandleAfterJoin != null &&
        !latestHandleAfterJoin.canContinueDetachedExecution(hasApprovalContinuation = false)
      ) {
        return storedSubAgentHandleResult(call = call, handle = latestHandleAfterJoin)
      }
    }
    val hadDetachedActiveExecution = cursor == null &&
      config.subAgentExecutionCoordinator.activeExecution(subAgentExecutionKey(handle)) != null
    if (cursor == null && hadDetachedActiveExecution) {
      waitForDetachedSubAgentExecution(
        handle = handle,
        onProgress = { progressHandle ->
          emitSubAgentWaitProgressEvent(
            task = task,
            turn = turn,
            handle = progressHandle,
          )
        },
      )
      val latestHandleAfterJoin = coordinatedSubAgentHandle(handle)
        ?: return detachedSubAgentHandleUnavailableAfterJoinResult(
          call = call,
          handle = handle,
        )
      if (!latestHandleAfterJoin.canContinueDetachedExecution(hasApprovalContinuation = false)) {
        return storedSubAgentHandleResult(call = call, handle = latestHandleAfterJoin)
      }
    }
    if (cursor == null) {
      unavailableCoordinatorBackedDetachedHandleResult(
        call = call,
        handle = handle,
      )?.let { unavailableResult ->
        return unavailableResult
      }
    }
    if (cursor == null && !startDetachedExecutionIfNeeded) {
      return storedSubAgentHandleResult(
        call = call,
        handle = coordinatedSubAgentHandle(handle) ?: handle,
      )
    }
    val approvalContinuation = takePendingApprovalContinuation(
      handle = handle,
      handles = handles,
    )
    if (!handle.canContinueDetachedExecution(hasApprovalContinuation = approvalContinuation != null)) {
      return storedSubAgentHandleResult(call = call, handle = handle)
    }
    val profile = BuiltInSubAgentProfiles.resolve(handle.subagentType)
      ?: return invalidSubAgentCallResult(
        call = call,
        message = "Unknown delegated subagent_type '${handle.subagentType}'.",
      )
    if (cursor != null) {
      val startedHandle = startSubAgentHandleBackgroundExecution(
        task = task,
        turn = turn,
        transcript = transcript,
        parentSessionContext = cursor.sessionContext,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        cursor = cursor,
        handle = handle,
        profile = profile,
        approvalContinuation = approvalContinuation,
        emitResumedPhaseWithoutApproval = true,
      )
      waitForActiveSubAgentExecution(
        cursor = cursor,
        agentId = agentId,
        onProgress = { progressHandle ->
          emitSubAgentWaitProgressEvent(
            task = task,
            turn = turn,
            handle = progressHandle,
          )
        },
      )
      val latestHandle = synchronizedSubAgentHandle(
        cursor = cursor,
        agentId = agentId,
      ) ?: startedHandle
      return storedSubAgentHandleResult(call = call, handle = latestHandle)
    }
    val startedHandle = ensureDetachedSubAgentHandleBackgroundExecution(
      task = task,
      turn = turn,
      transcript = transcript,
      parentSessionContext = cursor?.sessionContext ?: config.sessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = handle,
      handles = handles,
    ) ?: handle
    waitForDetachedSubAgentExecution(
      handle = startedHandle,
      onProgress = { progressHandle ->
        emitSubAgentWaitProgressEvent(
          task = task,
          turn = turn,
          handle = progressHandle,
        )
      },
    )
    val latestHandle = coordinatedSubAgentHandle(startedHandle) ?: startedHandle
    return storedSubAgentHandleResult(call = call, handle = latestHandle)
  }

  internal fun OpenCrayAgentRuntime.ensureDetachedSubAgentHandleBackgroundExecution(
    task: AgentTask,
    turn: Int,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    handle: SubAgentHandleState,
    handles: MutableMap<String, SubAgentHandleState>,
  ): SubAgentHandleState? {
    val normalizedHandle = coordinatedSubAgentHandle(handle) ?: handle
    if (normalizedHandle.isTerminalWithoutPendingApprovalResume()) {
      return normalizedHandle
    }
    if (
      config.subAgentExecutionCoordinator.activeExecution(subAgentExecutionKey(normalizedHandle)) != null
    ) {
      return coordinatedSubAgentHandle(normalizedHandle) ?: normalizedHandle
    }
    val approvalContinuation = takePendingApprovalContinuation(
      handle = normalizedHandle,
      handles = handles,
    )
    if (!normalizedHandle.canContinueDetachedExecution(hasApprovalContinuation = approvalContinuation != null)) {
      return normalizedHandle
    }
    val profile = BuiltInSubAgentProfiles.resolve(normalizedHandle.subagentType)
      ?: error("Unknown delegated subagent_type '${normalizedHandle.subagentType}'.")
    return startDetachedSubAgentHandleBackgroundExecution(
      task = task,
      turn = turn,
      transcript = transcript,
      parentSessionContext = parentSessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = normalizedHandle,
      profile = profile,
      approvalContinuation = approvalContinuation,
      emitResumedPhaseWithoutApproval = true,
    )
  }

  internal fun OpenCrayAgentRuntime.prepareSubAgentMailboxDelivery(
    handle: SubAgentHandleState,
    approvalContinuation: OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation?,
  ): OpenCrayAgentRuntime.PreparedSubAgentMailboxDelivery {
    val normalizedHandle = handle.withNormalizedMailbox()
    val mailbox = normalizedHandle.normalizedMailbox()
    val mailboxDeliveryCursorBeforeCurrentTurn = mailbox.lastDeliveredMessageId
    val pendingMessages = mailbox.pendingMessages()
    val promptResumeState = pendingMessages.fold(
      approvalContinuation?.resume?.promptResumeState ?: normalizedHandle.childPromptResumeState,
    ) { state, message ->
      state?.withAppendedUserInput(message.text)
    }
    if (pendingMessages.isEmpty()) {
      return OpenCrayAgentRuntime.PreparedSubAgentMailboxDelivery(
        handle = normalizedHandle,
        promptResumeState = promptResumeState,
        includeMailboxMessagesInPrompt = promptResumeState == null,
        mailboxDeliveryCursorBeforeCurrentTurn = mailboxDeliveryCursorBeforeCurrentTurn,
      )
    }
    val deliveredMailbox = mailbox.markDeliveredThrough(pendingMessages.last().messageId)
    return OpenCrayAgentRuntime.PreparedSubAgentMailboxDelivery(
      handle = normalizedHandle.copy(
        supplementalInputs = emptyList(),
        mailbox = deliveredMailbox,
        childPromptResumeState = promptResumeState ?: normalizedHandle.childPromptResumeState,
        updatedAtEpochMs = maxOf(
          normalizedHandle.updatedAtEpochMs,
          pendingMessages.last().createdAtEpochMs,
        ),
      ),
      promptResumeState = promptResumeState,
      includeMailboxMessagesInPrompt = promptResumeState == null,
      mailboxDeliveryCursorBeforeCurrentTurn = mailboxDeliveryCursorBeforeCurrentTurn,
    )
  }

  internal fun OpenCrayAgentRuntime.executeSubAgentHandleLifecycle(
    task: AgentTask,
    turn: Int,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    handle: SubAgentHandleState,
    profile: SubAgentProfile,
    handles: MutableMap<String, SubAgentHandleState>,
    approvalContinuation: OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation?,
    emitResumedPhaseWithoutApproval: Boolean,
    retainTerminalHandle: Boolean,
  ): OpenCrayAgentRuntime.SubAgentHandleLifecycleExecution {
    val preparedMailboxDelivery = prepareSubAgentMailboxDelivery(
      handle = handle,
      approvalContinuation = approvalContinuation,
    )
    val runningSnapshot = SubAgentExecutionSnapshot.backgroundRunning(
      headline = when {
        approvalContinuation?.approved == true -> "Queued delegated child run resumed after approval."
        approvalContinuation?.approved == false -> "Queued delegated child run resumed after rejection."
        else -> "Queued delegated child run started."
      },
    )
    val runningHandle = preparedMailboxDelivery.handle.copy(
      snapshot = runningSnapshot,
      pendingApprovalResume = null,
      pendingApprovalDecision = null,
      updatedAtEpochMs = clock(),
    )
    handles[handle.agentId] = runningHandle
    config.subAgentExecutionCoordinator.upsertHandle(runningHandle)
    if (approvalContinuation != null || emitResumedPhaseWithoutApproval) {
      emitSubAgentEvent(
        task = task,
        turn = turn,
        agentId = runningHandle.agentId,
        phase = OpenCraySubAgentPhase.RESUMED,
        childTask = runningHandle.toTask(),
        childRunId = runningHandle.childRunId,
        childTaskId = runningHandle.childTaskId,
        summary = when {
          approvalContinuation?.approved == true ->
            "Delegated child run resumed after approval for ${approvalContinuation.resume.approvedToolName}."
          approvalContinuation?.approved == false ->
            "Delegated child run resumed after rejection for ${approvalContinuation.resume.approvedToolName}."
          else -> "Queued delegated child run started."
        },
        snapshot = runningSnapshot,
        liveContext = runningHandle.childLiveContext,
      )
    }
    val childResult = executeSubAgentHandleRuntime(
      parentTask = task,
      transcript = transcript,
      parentSessionContext = parentSessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = runningHandle,
      profile = profile,
      approvalContinuation = approvalContinuation,
      promptResumeStateOverride = preparedMailboxDelivery.promptResumeState,
      includeMailboxMessagesInPrompt = preparedMailboxDelivery.includeMailboxMessagesInPrompt,
    )
    val compressedChildResult = SubAgentResultCompressor.compress(childResult)
    val childApprovalResume = childApprovalResume(
      childResult = childResult,
      agentId = runningHandle.agentId,
      childRunId = runningHandle.childRunId,
      childTaskId = runningHandle.childTaskId,
    )
    val childLiveContext = SubAgentLiveContextSnapshot.fromRuntimeMetadata(childResult.metadata)
    val updatedHandle = runningHandle
      .withClearedChildPromptCheckpoint(updatedAtEpochMs = clock())
      .copy(
        snapshot = compressedChildResult,
        pendingApprovalResume = childApprovalResume,
        childLiveContext = childLiveContext,
        pendingApprovalDecision = null,
        childExecutionStatus = childResult.status.name,
        childTurnCount = childResult.metadata["turnCount"]?.toIntOrNull(),
        childToolCallCount = childResult.metadata["toolCallCount"]?.toIntOrNull(),
      )
    if (retainTerminalHandle || childApprovalResume != null) {
      handles[handle.agentId] = updatedHandle
      config.subAgentExecutionCoordinator.upsertHandle(updatedHandle)
    } else {
      handles.remove(handle.agentId)
      config.subAgentExecutionCoordinator.removeHandle(subAgentExecutionKey(updatedHandle))
    }
    emitSubAgentEvent(
      task = task,
      turn = turn,
      agentId = updatedHandle.agentId,
      phase = when (childResult.status) {
        ExecutionStatus.SUCCESS -> OpenCraySubAgentPhase.COMPLETED
        ExecutionStatus.CANCELLED -> OpenCraySubAgentPhase.CANCELLED
        ExecutionStatus.FAILED,
        ExecutionStatus.DENIED,
        ExecutionStatus.TIMEOUT,
        -> OpenCraySubAgentPhase.FAILED
      },
      childTask = updatedHandle.toTask(),
      childRunId = updatedHandle.childRunId,
      childTaskId = updatedHandle.childTaskId,
      summary = compressedChildResult.summaryText(),
      snapshot = compressedChildResult,
      liveContext = updatedHandle.childLiveContext,
    )
    return OpenCrayAgentRuntime.SubAgentHandleLifecycleExecution(
      handle = updatedHandle,
      childResult = childResult,
      childApprovalResume = childApprovalResume,
    )
  }

  internal fun OpenCrayAgentRuntime.startSubAgentHandleBackgroundExecution(
    task: AgentTask,
    turn: Int,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor,
    handle: SubAgentHandleState,
    profile: SubAgentProfile,
    approvalContinuation: OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation?,
    emitResumedPhaseWithoutApproval: Boolean,
  ): SubAgentHandleState {
    activeSubAgentExecution(cursor = cursor, agentId = handle.agentId)?.let {
      return synchronizedSubAgentHandle(cursor = cursor, agentId = handle.agentId) ?: handle
    }
    val preparedMailboxDelivery = prepareSubAgentMailboxDelivery(
      handle = handle,
      approvalContinuation = approvalContinuation,
    )
    val runningSnapshot = backgroundRunningSnapshot(approvalContinuation)
    val runningHandle = preparedMailboxDelivery.handle.copy(
      snapshot = runningSnapshot,
      pendingApprovalResume = null,
      pendingApprovalDecision = null,
      updatedAtEpochMs = clock(),
    )
    val shouldEmitResumed = approvalContinuation != null || emitResumedPhaseWithoutApproval
    val cancelRequested = AtomicBoolean(false)
    val closed = AtomicBoolean(false)
    val executor = Executors.newSingleThreadExecutor()
    lateinit var activeExecution: SubAgentActiveExecution
    val future = FutureTask<Unit> {
      val childResult = runCatching {
        executeSubAgentHandleRuntime(
          parentTask = task,
          transcript = transcript,
          parentSessionContext = parentSessionContext,
          hooks = RuntimeExecutionHooks(
            isCancellationRequested = {
              cancelRequested.get() || hooks.isCancellationRequested()
            },
            requestRetry = { _ -> Unit },
            requestSuspend = { _ -> Unit },
          ),
          activeSkillCapsule = activeSkillCapsule,
          handle = runningHandle,
          profile = profile,
          approvalContinuation = approvalContinuation,
          owningExecution = activeExecution,
          promptResumeStateOverride = preparedMailboxDelivery.promptResumeState,
          includeMailboxMessagesInPrompt = preparedMailboxDelivery.includeMailboxMessagesInPrompt,
        )
      }.getOrElse { error ->
        unexpectedSubAgentBackgroundExecutionResult(
          handle = runningHandle,
          error = error,
        )
      }
      completeBackgroundSubAgentExecution(
        task = task,
        turn = turn,
        cursor = cursor,
        handle = runningHandle,
        childResult = childResult,
        executor = executor,
        closed = closed,
        activeExecution = activeExecution,
      )
    }
    activeExecution = SubAgentActiveExecution(
      executor = executor,
      future = future,
      cancelRequested = cancelRequested,
      closed = closed,
      mailboxDeliveryCursorBeforeCurrentTurn =
        preparedMailboxDelivery.mailboxDeliveryCursorBeforeCurrentTurn,
    )
    val registration = config.subAgentExecutionCoordinator.beginExecution(
      handle = runningHandle,
      execution = activeExecution,
    )
    if (!registration.started) {
      closed.set(true)
      future.cancel(true)
      executor.shutdownNow()
      return synchronizedSubAgentHandle(cursor = cursor, agentId = handle.agentId)
        ?: registration.handle
    }
    synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles[handle.agentId] = registration.handle
    }
    executor.execute(future)
    if (shouldEmitResumed) {
      emitResumedSubAgentEvent(
        task = task,
        turn = turn,
        handle = registration.handle,
        approvalContinuation = approvalContinuation,
      )
    }
    return synchronizedSubAgentHandle(cursor = cursor, agentId = handle.agentId) ?: registration.handle
  }

  internal fun OpenCrayAgentRuntime.waitForActiveSubAgentExecution(
    cursor: OpenCrayAgentRuntime.PromptTurnCursor,
    agentId: String,
    onProgress: ((SubAgentHandleState) -> Unit)? = null,
  ) {
    val handle = synchronizedSubAgentHandle(
      cursor = cursor,
      agentId = agentId,
    ) ?: return
    waitForStableSubAgentExecution(
      key = subAgentExecutionKey(handle),
      latestHandleProvider = {
        synchronizedSubAgentHandle(cursor = cursor, agentId = agentId)
      },
      onProgress = onProgress,
    )
  }

  internal fun OpenCrayAgentRuntime.waitForDetachedSubAgentExecution(
    handle: SubAgentHandleState,
    onProgress: ((SubAgentHandleState) -> Unit)? = null,
  ) {
    val key = subAgentExecutionKey(handle)
    waitForStableSubAgentExecution(
      key = key,
      latestHandleProvider = {
        config.subAgentExecutionCoordinator.currentHandle(key) ?: handle
      },
      onProgress = onProgress,
    )
  }

  internal fun OpenCrayAgentRuntime.waitForStableSubAgentExecution(
    key: SubAgentExecutionKey,
    latestHandleProvider: (() -> SubAgentHandleState?)? = null,
    onProgress: ((SubAgentHandleState) -> Unit)? = null,
  ) {
    var emittedHeartbeat = false
    var lastCheckpointAtEpochMs = latestHandleProvider?.invoke()?.childPromptCheckpointAtEpochMs
    while (true) {
      val execution = config.subAgentExecutionCoordinator.activeExecution(key) ?: return
      try {
        execution.future.get(
          SUBAGENT_WAIT_PROGRESS_POLL_INTERVAL_MS,
          TimeUnit.MILLISECONDS,
        )
      } catch (_: TimeoutException) {
        val latestHandle = latestHandleProvider?.invoke() ?: continue
        val checkpointAtEpochMs = latestHandle.childPromptCheckpointAtEpochMs
        val shouldEmitProgress = when {
          checkpointAtEpochMs != null && checkpointAtEpochMs != lastCheckpointAtEpochMs -> true
          !emittedHeartbeat && isWaitingSubAgentState(latestHandle.snapshot.state) -> true
          else -> false
        }
        if (checkpointAtEpochMs != null) {
          lastCheckpointAtEpochMs = checkpointAtEpochMs
        }
        if (shouldEmitProgress) {
          emittedHeartbeat = true
          onProgress?.invoke(latestHandle)
        }
        continue
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
      } catch (_: java.util.concurrent.CancellationException) {
        Unit
      } catch (_: java.util.concurrent.ExecutionException) {
        Unit
      }
      val nextExecution = config.subAgentExecutionCoordinator.activeExecution(key)
      if (nextExecution == null || nextExecution === execution) {
        return
      }
    }
  }

  internal fun OpenCrayAgentRuntime.isWaitingSubAgentState(
    state: SubAgentExecutionState,
  ): Boolean = when (state) {
    SubAgentExecutionState.RUNNING,
    SubAgentExecutionState.BACKGROUND_QUEUED,
    SubAgentExecutionState.BACKGROUND_RUNNING,
    -> true

    else -> false
  }

  internal fun OpenCrayAgentRuntime.activeSubAgentExecution(
    cursor: OpenCrayAgentRuntime.PromptTurnCursor,
    agentId: String,
  ): SubAgentActiveExecution? = synchronizedSubAgentHandle(
    cursor = cursor,
    agentId = agentId,
  )?.let { handle ->
    config.subAgentExecutionCoordinator.activeExecution(subAgentExecutionKey(handle))
  }

  internal fun OpenCrayAgentRuntime.completeBackgroundSubAgentExecution(
    task: AgentTask,
    turn: Int,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor,
    handle: SubAgentHandleState,
    childResult: ExecutionResult,
    executor: ExecutorService,
    closed: AtomicBoolean,
    activeExecution: SubAgentActiveExecution,
  ) {
    if (closed.get()) {
      config.subAgentExecutionCoordinator.takeActiveExecution(
        key = subAgentExecutionKey(handle),
        expectedExecution = activeExecution,
      )
      executor.shutdownNow()
      return
    }
    val updatedSnapshot = SubAgentResultCompressor.compress(childResult)
    val childApprovalResume = childApprovalResume(
      childResult = childResult,
      agentId = handle.agentId,
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
    )
    val childLiveContext = SubAgentLiveContextSnapshot.fromRuntimeMetadata(childResult.metadata)
    val updatedHandle = handle
      .withClearedChildPromptCheckpoint(updatedAtEpochMs = clock())
      .copy(
        snapshot = updatedSnapshot,
        pendingApprovalResume = childApprovalResume,
        pendingApprovalDecision = null,
        childLiveContext = childLiveContext,
        childExecutionStatus = childResult.status.name,
        childTurnCount = childResult.metadata["turnCount"]?.toIntOrNull(),
        childToolCallCount = childResult.metadata["toolCallCount"]?.toIntOrNull(),
      )
    val finalizedHandle = config.subAgentExecutionCoordinator.finishExecution(
      handle = updatedHandle,
      expectedExecution = activeExecution,
    ) ?: run {
      executor.shutdownNow()
      return
    }
    synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles[handle.agentId] = finalizedHandle
    }
    executor.shutdownNow()
    emitSubAgentEvent(
      task = task,
      turn = turn,
      agentId = finalizedHandle.agentId,
      phase = subAgentCompletionPhase(childResult.status),
      childTask = finalizedHandle.toTask(),
      childRunId = finalizedHandle.childRunId,
      childTaskId = finalizedHandle.childTaskId,
      summary = updatedSnapshot.summaryText(),
      snapshot = updatedSnapshot,
      liveContext = finalizedHandle.childLiveContext,
    )
  }

  internal fun OpenCrayAgentRuntime.startDetachedSubAgentHandleBackgroundExecution(
    task: AgentTask,
    turn: Int,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    handle: SubAgentHandleState,
    profile: SubAgentProfile,
    approvalContinuation: OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation?,
    emitResumedPhaseWithoutApproval: Boolean,
  ): SubAgentHandleState {
    val existingExecution = config.subAgentExecutionCoordinator.activeExecution(
      subAgentExecutionKey(handle),
    )
    if (existingExecution != null) {
      return coordinatedSubAgentHandle(handle) ?: handle
    }
    val initialTurn = prepareDetachedSubAgentRunningTurn(
      handle = handle,
      approvalContinuation = approvalContinuation,
      emitResumedPhaseWithoutApproval = emitResumedPhaseWithoutApproval,
    )
    val cancelRequested = AtomicBoolean(false)
    val closed = AtomicBoolean(false)
    val executor = Executors.newSingleThreadExecutor()
    lateinit var activeExecution: SubAgentActiveExecution
    val detachedHooks = RuntimeExecutionHooks(
      isCancellationRequested = {
        cancelRequested.get() || hooks.isCancellationRequested()
      },
      requestRetry = { _ -> Unit },
      requestSuspend = { _ -> Unit },
    )
    val future = FutureTask<Unit> {
      var nextTurn: OpenCrayAgentRuntime.DetachedSubAgentRunningTurn? = initialTurn
      while (nextTurn != null) {
        if (closed.get()) {
          config.subAgentExecutionCoordinator.takeActiveExecution(
            subAgentExecutionKey(nextTurn.handle),
            activeExecution,
          )
          executor.shutdownNow()
          return@FutureTask
        }
        val runningTurn = nextTurn
        val childResult = runCatching {
          executeSubAgentHandleRuntime(
            parentTask = task,
            transcript = transcript,
            parentSessionContext = parentSessionContext,
            hooks = detachedHooks,
            activeSkillCapsule = activeSkillCapsule,
            handle = runningTurn.handle,
            profile = profile,
            approvalContinuation = runningTurn.approvalContinuation,
            owningExecution = activeExecution,
            promptResumeStateOverride = runningTurn.promptResumeStateOverride,
            includeMailboxMessagesInPrompt = runningTurn.includeMailboxMessagesInPrompt,
          )
        }.getOrElse { error ->
          unexpectedSubAgentBackgroundExecutionResult(
            handle = runningTurn.handle,
            error = error,
          )
        }
        if (closed.get()) {
          config.subAgentExecutionCoordinator.takeActiveExecution(
            subAgentExecutionKey(runningTurn.handle),
            activeExecution,
          )
          executor.shutdownNow()
          return@FutureTask
        }
        val completedTurn = completeDetachedBackgroundSubAgentTurn(
          handle = runningTurn.handle,
          childResult = childResult,
        )
        val storedHandle = if (completedTurn.shouldAutoContinue) {
          config.subAgentExecutionCoordinator.upsertHandleIfOwnedByExecution(
            handle = completedTurn.storedHandle,
            expectedExecution = activeExecution,
          )
        } else {
          config.subAgentExecutionCoordinator.finishExecution(
            handle = completedTurn.storedHandle,
            expectedExecution = activeExecution,
          )
        } ?: run {
          executor.shutdownNow()
          return@FutureTask
        }
        emitSubAgentEvent(
          task = task,
          turn = turn,
          agentId = storedHandle.agentId,
          phase = completedTurn.completionPhase,
          childTask = completedTurn.completedHandle.toTask(),
          childRunId = storedHandle.childRunId,
          childTaskId = storedHandle.childTaskId,
          summary = completedTurn.snapshot.summaryText(),
          snapshot = completedTurn.snapshot,
        )
        if (!completedTurn.shouldAutoContinue || closed.get()) {
          executor.shutdownNow()
          return@FutureTask
        }
        nextTurn = prepareDetachedSubAgentRunningTurn(
          handle = latestSubAgentHandle(storedHandle).withNormalizedMailbox(),
          approvalContinuation = null,
          emitResumedPhaseWithoutApproval = true,
        )
        val continuedHandle = config.subAgentExecutionCoordinator.upsertHandleIfOwnedByExecution(
          handle = nextTurn.handle,
          expectedExecution = activeExecution,
        ) ?: run {
          executor.shutdownNow()
          return@FutureTask
        }
        nextTurn = nextTurn.copy(handle = continuedHandle)
        if (nextTurn.emitResumedPhase) {
          emitResumedSubAgentEvent(
            task = task,
            turn = turn,
            handle = nextTurn.handle,
            approvalContinuation = nextTurn.approvalContinuation,
          )
        }
      }
    }
    activeExecution = SubAgentActiveExecution(
      executor = executor,
      future = future,
      cancelRequested = cancelRequested,
      closed = closed,
      mailboxDeliveryCursorBeforeCurrentTurn =
        initialTurn.mailboxDeliveryCursorBeforeCurrentTurn,
    )
    val registration = config.subAgentExecutionCoordinator.beginExecution(
      handle = initialTurn.handle,
      execution = activeExecution,
    )
    if (!registration.started) {
      closed.set(true)
      future.cancel(true)
      executor.shutdownNow()
      return coordinatedSubAgentHandle(initialTurn.handle) ?: registration.handle
    }
    executor.execute(future)
    if (initialTurn.emitResumedPhase) {
      emitResumedSubAgentEvent(
        task = task,
        turn = turn,
        handle = registration.handle,
        approvalContinuation = approvalContinuation,
      )
    }
    return coordinatedSubAgentHandle(initialTurn.handle) ?: registration.handle
  }

  internal fun OpenCrayAgentRuntime.prepareDetachedSubAgentRunningTurn(
    handle: SubAgentHandleState,
    approvalContinuation: OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation?,
    emitResumedPhaseWithoutApproval: Boolean,
  ): OpenCrayAgentRuntime.DetachedSubAgentRunningTurn {
    val preparedMailboxDelivery = prepareSubAgentMailboxDelivery(
      handle = handle,
      approvalContinuation = approvalContinuation,
    )
    val runningHandle = preparedMailboxDelivery.handle.copy(
      snapshot = backgroundRunningSnapshot(approvalContinuation),
      pendingApprovalResume = null,
      pendingApprovalDecision = null,
      updatedAtEpochMs = clock(),
    )
    return OpenCrayAgentRuntime.DetachedSubAgentRunningTurn(
      handle = runningHandle,
      approvalContinuation = approvalContinuation,
      promptResumeStateOverride = preparedMailboxDelivery.promptResumeState,
      includeMailboxMessagesInPrompt = preparedMailboxDelivery.includeMailboxMessagesInPrompt,
      emitResumedPhase = approvalContinuation != null || emitResumedPhaseWithoutApproval,
      mailboxDeliveryCursorBeforeCurrentTurn =
        preparedMailboxDelivery.mailboxDeliveryCursorBeforeCurrentTurn,
    )
  }

  internal fun OpenCrayAgentRuntime.completeDetachedBackgroundSubAgentTurn(
    handle: SubAgentHandleState,
    childResult: ExecutionResult,
  ): OpenCrayAgentRuntime.DetachedSubAgentTurnCompletion {
    val latestHandle = latestSubAgentHandle(handle).withNormalizedMailbox()
    val compressedChildResult = SubAgentResultCompressor.compress(childResult)
    val childApprovalResume = childApprovalResume(
      childResult = childResult,
      agentId = latestHandle.agentId,
      childRunId = latestHandle.childRunId,
      childTaskId = latestHandle.childTaskId,
    )
    val childLiveContext = SubAgentLiveContextSnapshot.fromRuntimeMetadata(childResult.metadata)
    val completedHandle = latestHandle
      .withClearedChildPromptCheckpoint(updatedAtEpochMs = clock())
      .copy(
        snapshot = compressedChildResult,
        pendingApprovalResume = childApprovalResume,
        childLiveContext = childLiveContext,
        pendingApprovalDecision = null,
        childExecutionStatus = childResult.status.name,
        childTurnCount = childResult.metadata["turnCount"]?.toIntOrNull(),
        childToolCallCount = childResult.metadata["toolCallCount"]?.toIntOrNull(),
      )
    val shouldAutoContinue = shouldAutoContinueDetachedMailboxContinuation(
      handle = completedHandle,
      childResult = childResult,
      childApprovalResume = childApprovalResume,
    )
    val storedHandle = if (shouldAutoContinue) {
      completedHandle.copy(
        snapshot = detachedMailboxContinuationQueuedSnapshot(completedHandle),
        updatedAtEpochMs = clock(),
      )
    } else {
      completedHandle
    }
    return OpenCrayAgentRuntime.DetachedSubAgentTurnCompletion(
      storedHandle = storedHandle,
      completedHandle = completedHandle,
      snapshot = compressedChildResult,
      completionPhase = subAgentCompletionPhase(childResult.status),
      shouldAutoContinue = shouldAutoContinue,
    )
  }

  internal fun OpenCrayAgentRuntime.subAgentExecutionKey(
    handle: SubAgentHandleState,
  ): SubAgentExecutionKey = SubAgentExecutionKey.from(handle)

  internal fun OpenCrayAgentRuntime.coordinatedClosedSubAgentHandle(
    handle: SubAgentHandleState,
  ): SubAgentHandleState? = config.subAgentExecutionCoordinator
    .closedHandle(subAgentExecutionKey(handle))
    ?.takeIf { coordinated -> matchesCoordinatedSubAgentHandle(handle, coordinated) }

  internal fun OpenCrayAgentRuntime.coordinatedSubAgentHandle(
    handle: SubAgentHandleState,
  ): SubAgentHandleState? = config.subAgentExecutionCoordinator
    .currentHandle(subAgentExecutionKey(handle))
    ?.takeIf { coordinated -> matchesCoordinatedSubAgentHandle(handle, coordinated) }

  internal fun OpenCrayAgentRuntime.latestCoordinatedSubAgentHandleByAgentId(
    agentId: String,
  ): SubAgentHandleState? = config.subAgentExecutionCoordinator
    .allHandles()
    .asSequence()
    .filter { handle -> handle.agentId == agentId }
    .maxByOrNull(SubAgentHandleState::updatedAtEpochMs)

  internal fun OpenCrayAgentRuntime.latestClosedCoordinatedSubAgentHandleByAgentId(
    agentId: String,
  ): SubAgentHandleState? = config.subAgentExecutionCoordinator
    .allClosedHandles()
    .asSequence()
    .filter { handle -> handle.agentId == agentId }
    .maxByOrNull(SubAgentHandleState::updatedAtEpochMs)

  internal fun OpenCrayAgentRuntime.matchesCoordinatedSubAgentHandle(
    handle: SubAgentHandleState,
    coordinated: SubAgentHandleState,
  ): Boolean =
    coordinated.childRunId == handle.childRunId ||
      coordinated.childTaskId == handle.childTaskId ||
      (
        coordinated.parentRunId == handle.parentRunId &&
          coordinated.parentTaskId == handle.parentTaskId
        )

  internal fun OpenCrayAgentRuntime.synchronizedSubAgentHandle(
    cursor: OpenCrayAgentRuntime.PromptTurnCursor,
    agentId: String,
  ): SubAgentHandleState? {
    val localHandle = synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles[agentId]
    } ?: return null
    val coordinatedHandle = (coordinatedSubAgentHandle(localHandle) ?: localHandle)
      .withNormalizedMailbox()
    synchronized(cursor.subAgentExecutionLock) {
      if (cursor.subAgentHandles[agentId] === localHandle) {
        cursor.subAgentHandles[agentId] = coordinatedHandle
      }
      return cursor.subAgentHandles[agentId]
    }
  }

  internal fun OpenCrayAgentRuntime.synchronizedSubAgentHandles(
    cursor: OpenCrayAgentRuntime.PromptTurnCursor,
  ): List<SubAgentHandleState> {
    val localHandles = synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles.values.toList()
    }
    if (localHandles.isEmpty()) {
      return emptyList()
    }
    val localHandlesByAgentId = localHandles.associateBy(SubAgentHandleState::agentId)
    val synchronizedHandles = localHandles.map { handle ->
      (coordinatedSubAgentHandle(handle) ?: handle).withNormalizedMailbox()
    }
    synchronized(cursor.subAgentExecutionLock) {
      synchronizedHandles.forEach { handle ->
        if (cursor.subAgentHandles[handle.agentId] === localHandlesByAgentId[handle.agentId]) {
          cursor.subAgentHandles[handle.agentId] = handle
        }
      }
      return cursor.subAgentHandles.values.toList()
    }
  }

  internal fun OpenCrayAgentRuntime.backgroundRunningSnapshot(
    approvalContinuation: OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation?,
  ): SubAgentExecutionSnapshot = SubAgentExecutionSnapshot.backgroundRunning(
    headline = when {
      approvalContinuation?.approved == true -> "Queued delegated child run resumed after approval."
      approvalContinuation?.approved == false -> "Queued delegated child run resumed after rejection."
      else -> "Queued delegated child run started."
    },
  )

  internal fun OpenCrayAgentRuntime.emitResumedSubAgentEvent(
    task: AgentTask,
    turn: Int,
    handle: SubAgentHandleState,
    approvalContinuation: OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation?,
  ) {
    emitSubAgentEvent(
      task = task,
      turn = turn,
      agentId = handle.agentId,
      phase = OpenCraySubAgentPhase.RESUMED,
      childTask = handle.toTask(),
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
      summary = when {
        approvalContinuation?.approved == true ->
          "Delegated child run resumed after approval for ${approvalContinuation.resume.approvedToolName}."
        approvalContinuation?.approved == false ->
          "Delegated child run resumed after rejection for ${approvalContinuation.resume.approvedToolName}."
        else -> "Queued delegated child run started."
      },
      snapshot = handle.snapshot,
      liveContext = handle.childLiveContext,
    )
  }

  internal fun OpenCrayAgentRuntime.emitSubAgentWaitProgressEvent(
    task: AgentTask,
    turn: Int,
    handle: SubAgentHandleState,
  ) {
    val checkpointHeadline = when (handle.childPromptCheckpointBoundary) {
      OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST ->
        "Delegated child run prepared its next model request."
      OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED ->
        "Delegated child run parsed its next action batch."
      OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED ->
        "Delegated child run emitted a commentary update."
      OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED ->
        "Delegated child run committed a tool result."
      OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED ->
        "Delegated child run ingested new context."
      OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE ->
        "Delegated child run completed finalization."
      null -> null
    }
    val progressSnapshot = handle.snapshot.copy(
      headline = checkpointHeadline ?: when (handle.snapshot.state) {
        SubAgentExecutionState.BACKGROUND_QUEUED ->
          "Delegated child run is queued and waiting to continue."
        SubAgentExecutionState.BACKGROUND_RUNNING,
        SubAgentExecutionState.RUNNING,
        -> "Delegated child run is still running."
        else -> handle.snapshot.headline
      },
      detailLines = emptyList(),
    )
    emitSubAgentEvent(
      task = task,
      turn = turn,
      phase = when (handle.snapshot.state) {
        SubAgentExecutionState.BACKGROUND_RUNNING -> OpenCraySubAgentPhase.RESUMED
        else -> OpenCraySubAgentPhase.STARTED
      },
      childTask = handle.toTask(),
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
      summary = progressSnapshot.summaryText(),
      snapshot = progressSnapshot,
      liveContext = handle.childLiveContext,
    )
  }

  internal fun OpenCrayAgentRuntime.subAgentCompletionPhase(
    status: ExecutionStatus,
  ): OpenCraySubAgentPhase = when (status) {
    ExecutionStatus.SUCCESS -> OpenCraySubAgentPhase.COMPLETED
    ExecutionStatus.CANCELLED -> OpenCraySubAgentPhase.CANCELLED
    ExecutionStatus.FAILED,
    ExecutionStatus.DENIED,
    ExecutionStatus.TIMEOUT,
    -> OpenCraySubAgentPhase.FAILED
  }

  internal fun OpenCrayAgentRuntime.shouldAutoContinueDetachedMailboxContinuation(
    handle: SubAgentHandleState,
    childResult: ExecutionResult,
    childApprovalResume: SubAgentApprovalResume?,
  ): Boolean = childResult.status == ExecutionStatus.SUCCESS &&
    childApprovalResume == null &&
    handle.normalizedMailbox().pendingMessages().isNotEmpty()

  internal fun OpenCrayAgentRuntime.detachedMailboxContinuationQueuedSnapshot(
    handle: SubAgentHandleState,
  ): SubAgentExecutionSnapshot = SubAgentExecutionSnapshot.backgroundQueued(
    headline =
      "Delegated child run '${handle.description}' received additional parent input and is queued to continue in the background.",
  )

  internal fun SubAgentHandleState.interruptedMailboxContinuationQueuedSnapshot(): SubAgentExecutionSnapshot =
    SubAgentExecutionSnapshot.backgroundQueued(
      headline =
        "Delegated child run '$description' was redirected by new parent input and is restarting in the background.",
    )

  internal fun SubAgentHandleState.withInterruptedMailboxRestart(
    mailboxDeliveryCursorBeforeCurrentTurn: String?,
    updatedAtEpochMs: Long,
  ): SubAgentHandleState = copy(
    supplementalInputs = emptyList(),
    mailbox = normalizedMailbox().rewindDeliveredThrough(mailboxDeliveryCursorBeforeCurrentTurn),
    snapshot = interruptedMailboxContinuationQueuedSnapshot(),
    pendingApprovalResume = null,
    pendingApprovalDecision = null,
    updatedAtEpochMs = maxOf(this.updatedAtEpochMs, updatedAtEpochMs),
  )

  internal fun OpenCrayAgentRuntime.unexpectedSubAgentBackgroundExecutionResult(
    handle: SubAgentHandleState,
    error: Throwable,
  ): ExecutionResult = ExecutionResult(
    taskId = handle.childTaskId,
    status = ExecutionStatus.FAILED,
    stderr = error.stackTraceToString(),
    errorCode = "SUBAGENT_RUNTIME_EXCEPTION",
    errorMessage = error.message ?: error::class.java.simpleName,
    startedAtEpochMs = handle.updatedAtEpochMs,
    finishedAtEpochMs = clock(),
  )

  internal fun OpenCrayAgentRuntime.sendInputToSubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
  ): AgentToolResult {
    val handles = subAgentHandleRegistry(cursor)
    val agentId = resolveSubAgentHandleId(call)
      ?: return invalidSubAgentCallResult(call, "send_input agent_id must not be blank.")
    val message = call.arguments.primitiveContent("message")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: call.arguments.primitiveContent("input")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return invalidSubAgentCallResult(call, "send_input message must not be blank.")
    val interruptRequested = call.arguments.optionalBooleanContent("interrupt") == true
    val handle = handles[agentId]
      ?: if (cursor == null) {
        latestCoordinatedSubAgentHandleByAgentId(agentId)
          ?: latestClosedCoordinatedSubAgentHandleByAgentId(agentId)
      } else {
        null
      }
      ?: return unknownSubAgentHandleResult(
        call = call,
        agentId = agentId,
      )
    if (!handle.canAcceptMailboxInput()) {
      return AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.FAILED,
        content =
          "send_input can only target a queued, background-running, or approval-waiting delegated child handle mailbox.",
        errorCode = "SUBAGENT_NOT_QUEUEABLE",
        errorMessage =
          "send_input can only target a queued, background-running, or approval-waiting delegated child handle mailbox.",
        metadata = subAgentHandleMetadata(handle),
      )
    }
    val now = clock()
    val updatedHandle = handle.withQueuedMailboxInput(
      messageId = "mailbox-${now}-${UUID.randomUUID().toString().take(8)}",
      message = message,
      createdAtEpochMs = now,
    )
    val activeExecution = config.subAgentExecutionCoordinator.activeExecution(
      subAgentExecutionKey(handle),
    )
    val interruptedExistingExecution =
      interruptRequested &&
        handle.snapshot.state == SubAgentExecutionState.BACKGROUND_RUNNING &&
        activeExecution != null
    val restartHandle = if (interruptedExistingExecution) {
      val runningExecution = requireNotNull(activeExecution)
      config.subAgentExecutionCoordinator.cancelActiveExecution(
        key = subAgentExecutionKey(handle),
        markClosed = true,
      )
      updatedHandle.withInterruptedMailboxRestart(
        mailboxDeliveryCursorBeforeCurrentTurn = runningExecution.mailboxDeliveryCursorBeforeCurrentTurn,
        updatedAtEpochMs = now,
      )
    } else {
      updatedHandle
    }
    handles[agentId] = restartHandle
    config.subAgentExecutionCoordinator.upsertHandle(restartHandle)
    val autoResumedHandle = if (interruptedExistingExecution) {
      restartInterruptedSubAgentHandleExecution(
        task = task,
        turn = turn,
        transcript = transcript,
        parentSessionContext = cursor?.sessionContext ?: config.sessionContext,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        cursor = cursor,
        handle = restartHandle,
        handles = handles,
      )
    } else if (restartHandle.isDetachedBackgroundQueued()) {
      ensureDetachedSubAgentHandleBackgroundExecution(
        task = task,
        turn = turn,
        transcript = transcript,
        parentSessionContext = cursor?.sessionContext ?: config.sessionContext,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        handle = restartHandle,
        handles = handles,
      )
    } else {
      null
    }
    val effectiveHandle = autoResumedHandle?.let { coordinatedSubAgentHandle(it) } ?: coordinatedSubAgentHandle(restartHandle)
      ?: autoResumedHandle
      ?: restartHandle
    val mailbox = effectiveHandle.normalizedMailbox()
    return AgentToolResult(
      toolName = call.toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = if (interruptedExistingExecution) {
        "Delegated child input queued and running child redirected."
      } else {
        "Delegated child input queued in mailbox."
      },
      metadata = subAgentHandleMetadata(effectiveHandle) + mapOf(
        "supplementalInputCount" to mailbox.messages.size.toString(),
        "mailboxPendingInputCount" to mailbox.pendingMessages().size.toString(),
        "autoResumed" to (autoResumedHandle != null).toString(),
        "interruptRequested" to interruptRequested.toString(),
        "interruptedExistingExecution" to interruptedExistingExecution.toString(),
      ),
    )
  }

  internal fun OpenCrayAgentRuntime.restartInterruptedSubAgentHandleExecution(
    task: AgentTask,
    turn: Int,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
    handle: SubAgentHandleState,
    handles: MutableMap<String, SubAgentHandleState>,
  ): SubAgentHandleState? {
    val profile = BuiltInSubAgentProfiles.resolve(handle.subagentType)
      ?: return null
    return if (cursor != null) {
      startSubAgentHandleBackgroundExecution(
        task = task,
        turn = turn,
        transcript = transcript,
        parentSessionContext = parentSessionContext,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        cursor = cursor,
        handle = handle,
        profile = profile,
        approvalContinuation = null,
        emitResumedPhaseWithoutApproval = true,
      )
    } else {
      ensureDetachedSubAgentHandleBackgroundExecution(
        task = task,
        turn = turn,
        transcript = transcript,
        parentSessionContext = parentSessionContext,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        handle = handle,
        handles = handles,
      )
    }
  }

  internal fun OpenCrayAgentRuntime.closeSubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
  ): AgentToolResult {
    val handles = subAgentHandleRegistry(cursor)
    val agentId = resolveSubAgentHandleId(call)
      ?: return invalidSubAgentCallResult(call, "close_agent agent_id must not be blank.")
    val handle = handles[agentId]
      ?: if (cursor == null) {
        latestCoordinatedSubAgentHandleByAgentId(agentId)
          ?: latestClosedCoordinatedSubAgentHandleByAgentId(agentId)
      } else {
        null
      }
      ?: return unknownSubAgentHandleResult(
        call = call,
        agentId = agentId,
      )
    val cancelledHandle = handle
      .takeUnless { existingHandle -> existingHandle.snapshot.state.isTerminal() }
      ?.let { existingHandle ->
        existingHandle
          .withClearedChildPromptCheckpoint()
          .copy(
            snapshot = SubAgentExecutionSnapshot(
              state = SubAgentExecutionState.CANCELLED,
              continuationKind = SubAgentContinuationKind.NONE,
              resumable = false,
              requiresUserAction = false,
              isHighRisk = false,
              headline = "Queued delegated child run '${existingHandle.description}' was closed.",
            ),
            pendingApprovalResume = null,
            pendingApprovalDecision = null,
            childExecutionStatus = ExecutionStatus.CANCELLED.name,
          )
      }
    cancelledHandle?.let(config.subAgentExecutionCoordinator::noteClosedHandle)
    config.subAgentExecutionCoordinator.cancelActiveExecution(
      key = subAgentExecutionKey(handle),
      markClosed = true,
    )
    if (cursor != null) {
      synchronized(cursor.subAgentExecutionLock) {
        cursor.subAgentHandles.remove(agentId)
      }
    } else {
      handles.remove(agentId)
    }
    config.subAgentExecutionCoordinator.removeHandle(subAgentExecutionKey(handle))
    clearPendingApprovalContinuationForHandle(handle)
    if (cancelledHandle != null) {
      val cancelledSnapshot = cancelledHandle.snapshot
      emitSubAgentEvent(
        task = task,
        turn = turn,
        agentId = handle.agentId,
        phase = OpenCraySubAgentPhase.CANCELLED,
        childTask = handle.toTask(),
        childRunId = handle.childRunId,
        childTaskId = handle.childTaskId,
        summary = cancelledSnapshot.summaryText(),
        snapshot = cancelledSnapshot,
        liveContext = handle.childLiveContext,
        closed = true,
      )
      return AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.SUCCESS,
        content = cancelledSnapshot.summaryText(),
        metadata = subAgentHandleMetadata(cancelledHandle) + mapOf("closed" to "true"),
      )
    }
    return AgentToolResult(
      toolName = call.toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = "Delegated child handle closed.",
      metadata = subAgentHandleMetadata(handle) + mapOf("closed" to "true"),
    )
  }

  internal fun OpenCrayAgentRuntime.listSubAgentHandles(
    call: AgentToolCall,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
  ): AgentToolResult {
    val handles = listableSubAgentHandles(cursor)
    val openHandleCount = handles.count { handle ->
      !handle.snapshot.state.isTerminal()
    }
    val payload = buildJsonObject {
      put("count", handles.size)
      put("openCount", openHandleCount)
      put(
        "subagents",
        JsonArray(handles.map { subAgentHandleJson(it) }),
      )
    }
    return AgentToolResult(
      toolName = call.toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = config.json.encodeToString(JsonObject.serializer(), payload),
      metadata = mapOf(
        "subagentCount" to handles.size.toString(),
        "openSubagentCount" to openHandleCount.toString(),
      ),
    )
  }

  internal fun OpenCrayAgentRuntime.openSubAgentHandleObservation(
    cursor: OpenCrayAgentRuntime.PromptTurnCursor,
  ): String? {
    val openHandles = synchronizedSubAgentHandles(cursor).filter { handle ->
      !handle.snapshot.state.isTerminal()
    }
    if (openHandles.isEmpty()) {
      return null
    }
    val summary = openHandles.joinToString(separator = ", ") { handle ->
      "${handle.agentId}(${handle.snapshot.state.wireValue})"
    }
    return buildString {
      append("Delegated child handles are still open: ")
      append(summary)
      append(". Use wait_agent to harvest a running or approval-paused child later. ")
      append("Use close_agent to discard any child you no longer need. ")
      append("If you intentionally leave a child running across turns, mention that in your user-facing answer.")
    }
  }

  internal fun OpenCrayAgentRuntime.cancelActiveSubAgentExecutions(
    task: AgentTask,
    turn: Int,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor,
    reason: String,
    removeHandles: Boolean,
    includeInactiveHandles: Boolean = false,
    owningParentRunId: String? = null,
  ) {
    val cancelledEvents = mutableListOf<Pair<SubAgentHandleState, SubAgentExecutionSnapshot>>()
    synchronizedSubAgentHandles(cursor).forEach { handle ->
      if (handle.snapshot.state.isTerminal()) {
        return@forEach
      }
      val executionKey = subAgentExecutionKey(handle)
      if (owningParentRunId != null && executionKey.parentRunId != owningParentRunId) {
        return@forEach
      }
      val cancelledActiveExecution = config.subAgentExecutionCoordinator.cancelActiveExecution(
        executionKey,
        markClosed = true,
      )
      if (cancelledActiveExecution == null && !includeInactiveHandles) {
        return@forEach
      }
      val cancelledHandle = cancelledSubAgentHandle(
        handle = handle,
        reason = reason,
      )
      clearPendingApprovalContinuationForHandle(handle)
      synchronized(cursor.subAgentExecutionLock) {
        if (removeHandles) {
          cursor.subAgentHandles.remove(handle.agentId)
        } else {
          cursor.subAgentHandles[handle.agentId] = cancelledHandle
        }
      }
      if (removeHandles) {
        config.subAgentExecutionCoordinator.removeHandle(executionKey)
      } else {
        config.subAgentExecutionCoordinator.upsertHandle(cancelledHandle)
      }
      cancelledEvents += cancelledHandle to cancelledHandle.snapshot
    }
    cancelledEvents.forEach { (handle, snapshot) ->
      emitSubAgentEvent(
        task = task,
        turn = turn,
        agentId = handle.agentId,
        phase = OpenCraySubAgentPhase.CANCELLED,
        childTask = handle.toTask(),
        childRunId = handle.childRunId,
        childTaskId = handle.childTaskId,
        summary = snapshot.summaryText(),
        snapshot = snapshot,
        liveContext = handle.childLiveContext,
      )
    }
  }

  internal fun OpenCrayAgentRuntime.cancelledSubAgentHandle(
    handle: SubAgentHandleState,
    reason: String,
  ): SubAgentHandleState {
    val headline = when (handle.snapshot.state) {
      SubAgentExecutionState.BACKGROUND_RUNNING -> "Background delegated child run '${handle.description}' was cancelled."
      else -> "Queued delegated child run '${handle.description}' was cancelled."
    }
    return handle.copy(
      snapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.CANCELLED,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        headline = headline,
        detailLines = listOf(reason),
      ),
      pendingApprovalResume = null,
      pendingApprovalDecision = null,
      childPromptResumeState = null,
      childPromptCheckpointBoundary = null,
      childPromptCheckpointAtEpochMs = null,
      childExecutionStatus = ExecutionStatus.CANCELLED.name,
      updatedAtEpochMs = clock(),
    )
  }

  internal fun OpenCrayAgentRuntime.restoredSubAgentHandle(
    handle: SubAgentHandleState,
  ): SubAgentHandleState = (
    coordinatedClosedSubAgentHandle(handle)
      ?: coordinatedSubAgentHandle(handle)
      ?: unavailableCoordinatorBackedDetachedHandle(handle)
      ?: restoredInterruptedBackgroundSubAgentHandle(
        handle = handle,
        restoredAtEpochMs = clock(),
      )
    ).withNormalizedMailbox()

  internal fun OpenCrayAgentRuntime.subAgentHandleRegistry(
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
  ): MutableMap<String, SubAgentHandleState> = if (cursor != null) {
    synchronizedSubAgentHandles(cursor)
    cursor.subAgentHandles
  } else {
    linkedMapOf<String, SubAgentHandleState>().apply {
      (
        config.promptResumeState?.subAgentHandles.orEmpty() +
          config.seededSubAgentHandles
        ).map { restoredSubAgentHandle(it) }
        .forEach { handle ->
          val existing = this[handle.agentId]
          if (existing == null || handle.updatedAtEpochMs >= existing.updatedAtEpochMs) {
            put(handle.agentId, handle)
          }
        }
    }
  }

  internal fun OpenCrayAgentRuntime.listableSubAgentHandles(
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
  ): List<SubAgentHandleState> {
    val dedupedHandles = linkedMapOf<SubAgentExecutionKey, SubAgentHandleState>()
    buildList {
      if (cursor != null) {
        addAll(synchronizedSubAgentHandles(cursor))
      } else {
        addAll(config.promptResumeState?.subAgentHandles.orEmpty())
      }
      addAll(config.seededSubAgentHandles)
      addAll(config.subAgentExecutionCoordinator.allHandles())
      addAll(config.subAgentExecutionCoordinator.allClosedHandles())
    }.map { restoredSubAgentHandle(it) }
      .forEach { handle ->
        val key = subAgentExecutionKey(handle)
        val existing = dedupedHandles[key]
        if (existing == null || handle.updatedAtEpochMs >= existing.updatedAtEpochMs) {
          dedupedHandles[key] = handle
        }
      }
    return dedupedHandles.values.sortedWith(
      compareByDescending<SubAgentHandleState>(SubAgentHandleState::updatedAtEpochMs)
        .thenBy(SubAgentHandleState::parentRunId)
        .thenBy(SubAgentHandleState::agentId),
    )
  }

  internal fun OpenCrayAgentRuntime.resolveSubAgentHandleId(call: AgentToolCall): String? =
    call.arguments.primitiveContent("agent_id")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: call.arguments.primitiveContent("id")
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: call.arguments.stringArrayContent("agent_ids")
        ?.firstOrNull()
      ?: call.arguments.stringArrayContent("ids")
        ?.firstOrNull()

  internal fun OpenCrayAgentRuntime.unknownSubAgentHandleResult(
    call: AgentToolCall,
    agentId: String,
  ): AgentToolResult = AgentToolResult(
    toolName = call.toolName,
    status = AgentToolResultStatus.FAILED,
    content = "Unknown delegated child handle '$agentId'.",
    errorCode = "UNKNOWN_SUBAGENT_HANDLE",
    errorMessage = "Unknown delegated child handle '$agentId'.",
    metadata = mapOf("agentId" to agentId),
  )

  internal fun OpenCrayAgentRuntime.latestSubAgentHandle(
    handle: SubAgentHandleState,
  ): SubAgentHandleState = coordinatedSubAgentHandle(handle)?.let { coordinated ->
    if (coordinated.updatedAtEpochMs >= handle.updatedAtEpochMs) {
      coordinated
    } else {
      handle
    }
  } ?: handle

  internal fun OpenCrayAgentRuntime.hasActiveSubAgentExecution(
    handle: SubAgentHandleState,
  ): Boolean = config.subAgentExecutionCoordinator.activeExecution(
    subAgentExecutionKey(handle),
  ) != null

  internal fun OpenCrayAgentRuntime.subAgentHandleMetadata(
    handle: SubAgentHandleState,
  ): Map<String, String> = linkedMapOf(
    "agentId" to handle.agentId,
    "childSessionId" to handle.childSessionId,
    "subagentType" to handle.subagentType,
    "subagentContextMode" to handle.contextMode,
    "subagentContextModeSource" to handle.contextModeSource,
    "subagentDepth" to handle.depth.toString(),
    "childRunId" to handle.childRunId,
    "childTaskId" to handle.childTaskId,
    "hasActiveExecution" to hasActiveSubAgentExecution(handle).toString(),
  ).apply {
    val mailbox = handle.normalizedMailbox()
    if (mailbox.messages.isNotEmpty()) {
      put("supplementalInputCount", mailbox.messages.size.toString())
      put("mailboxPendingInputCount", mailbox.pendingMessages().size.toString())
      mailbox.lastDeliveredMessageId
        ?.takeIf(String::isNotBlank)
        ?.let { put("mailboxLastDeliveredMessageId", it) }
    }
    handle.childPromptResumeState?.let { put("childHasPromptResumeState", "true") }
    handle.childPromptCheckpointBoundary?.wireValue?.let { put("childPromptCheckpointBoundary", it) }
    handle.childPromptCheckpointAtEpochMs?.let { put("childPromptCheckpointAtEpochMs", it.toString()) }
    handle.childExecutionStatus
      ?.takeIf(String::isNotBlank)
      ?.let { put("childExecutionStatus", it) }
    handle.childTurnCount?.let { put("childTurnCount", it.toString()) }
    handle.childToolCallCount?.let { put("childToolCallCount", it.toString()) }
    putAll(handle.childLiveContext.toMetadataMap())
    putAll(handle.snapshot.metadata())
    handle.pendingApprovalResume?.let { resume ->
      put("hasPendingApprovalResume", "true")
      put("pendingApprovalToolName", resume.approvedToolName)
      put("pendingApprovalIsHighRisk", resume.isHighRisk.toString())
      resume.childRunId
        ?.takeIf(String::isNotBlank)
        ?.let { childRunId -> put("pendingApprovalChildRunId", childRunId) }
      resume.childTaskId
        ?.takeIf(String::isNotBlank)
        ?.let { childTaskId -> put("pendingApprovalChildTaskId", childTaskId) }
      putAll(
        SubAgentApprovalResumeMetadata.encodeToMetadata(
          resume = resume,
          json = config.json,
        ),
      )
    }
  }

  internal fun OpenCrayAgentRuntime.subAgentHandleJson(
    handle: SubAgentHandleState,
  ): JsonObject {
    val mailbox = handle.normalizedMailbox()
    return buildJsonObject {
      put("agentId", handle.agentId)
      put("childSessionId", handle.childSessionId)
      put("parentRunId", handle.parentRunId)
      put("parentTaskId", handle.parentTaskId)
      put("childRunId", handle.childRunId)
      put("childTaskId", handle.childTaskId)
      put("label", handle.description)
      put("subagentType", handle.subagentType)
      put("contextMode", handle.contextMode)
      put("depth", handle.depth)
      put("state", handle.snapshot.state.wireValue)
      put("hasActiveExecution", hasActiveSubAgentExecution(handle))
      put("continuationKind", handle.snapshot.continuationKind.wireValue)
      put("resumable", handle.snapshot.resumable)
      put("requiresUserAction", handle.snapshot.requiresUserAction)
      put("isHighRisk", handle.snapshot.isHighRisk)
      put("summary", handle.snapshot.headline)
      put("mailboxMessageCount", mailbox.messages.size)
      put("mailboxPendingMessageCount", mailbox.pendingMessages().size)
      mailbox.lastDeliveredMessageId
        ?.takeIf(String::isNotBlank)
        ?.let { messageId -> put("mailboxLastDeliveredMessageId", messageId) }
      handle.childExecutionStatus
        ?.takeIf(String::isNotBlank)
        ?.let { status -> put("childExecutionStatus", status) }
      handle.childTurnCount?.let { turnCount -> put("childTurnCount", turnCount) }
      handle.childToolCallCount?.let { toolCallCount -> put("childToolCallCount", toolCallCount) }
      handle.childLiveContext.toMap()?.let { liveContext ->
        put(
          "liveContext",
          buildJsonObject {
            (liveContext["mode"] as String?)?.let { put("mode", it) }
            (liveContext["soulEnabled"] as Boolean?)?.let { put("soulEnabled", it) }
            (liveContext["memoryRecallEnabled"] as Boolean?)?.let {
              put("memoryRecallEnabled", it)
            }
            (liveContext["replaySource"] as String?)?.let { put("replaySource", it) }
            (liveContext["replayMessageCount"] as Int?)?.let { put("replayMessageCount", it) }
            (liveContext["canonicalSource"] as String?)?.let { put("canonicalSource", it) }
            (liveContext["canonicalMessageCount"] as Int?)?.let {
              put("canonicalMessageCount", it)
            }
            (liveContext["canonicalHistoryPreserved"] as Boolean?)?.let {
              put("canonicalHistoryPreserved", it)
            }
          },
        )
      }
      handle.childPromptResumeState?.let { put("hasPromptResumeState", true) }
      handle.childPromptCheckpointBoundary?.wireValue?.let { boundary ->
        put("childPromptCheckpointBoundary", boundary)
      }
      handle.childPromptCheckpointAtEpochMs?.let { checkpointAt ->
        put("childPromptCheckpointAtEpochMs", checkpointAt)
      }
      handle.pendingApprovalResume?.let { resume ->
        put("hasPendingApprovalResume", true)
        put("pendingApprovalToolName", resume.approvedToolName)
        put("pendingApprovalIsHighRisk", resume.isHighRisk)
        resume.childRunId
          ?.takeIf(String::isNotBlank)
          ?.let { childRunId -> put("pendingApprovalChildRunId", childRunId) }
        resume.childTaskId
          ?.takeIf(String::isNotBlank)
          ?.let { childTaskId -> put("pendingApprovalChildTaskId", childTaskId) }
      }
      handle.activeSkillName
        ?.takeIf(String::isNotBlank)
        ?.let { skillName -> put("activeSkillName", skillName) }
      handle.activeSkillActivationSource
        ?.takeIf(String::isNotBlank)
        ?.let { activationSource -> put("activeSkillActivationSource", activationSource) }
      put("createdAtEpochMs", handle.createdAtEpochMs)
      put("updatedAtEpochMs", handle.updatedAtEpochMs)
    }
  }

  internal fun OpenCrayAgentRuntime.detachedSubAgentHandleUnavailableAfterJoinResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
  ): AgentToolResult = storedSubAgentHandleResult(
    call = call,
    handle = unavailableCoordinatorBackedDetachedHandle(handle)
      ?: handle,
  )

  internal fun OpenCrayAgentRuntime.unavailableCoordinatorBackedDetachedHandleResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
  ): AgentToolResult? {
    coordinatedClosedSubAgentHandle(handle)?.let { closedHandle ->
      return storedSubAgentHandleResult(
        call = call,
        handle = closedHandle,
      )
    }
    if (!config.seededDetachedSubAgentHandlesRequireCoordinatorOwnership) {
      return null
    }
    if (!handle.canContinueDetachedExecution(hasApprovalContinuation = false)) {
      return null
    }
    if (coordinatedSubAgentHandle(handle) != null) {
      return null
    }
    return detachedSubAgentHandleUnavailableAfterJoinResult(
      call = call,
      handle = handle,
    )
  }

  internal fun OpenCrayAgentRuntime.unavailableCoordinatorBackedDetachedHandle(
    handle: SubAgentHandleState,
  ): SubAgentHandleState? {
    coordinatedClosedSubAgentHandle(handle)?.let { closedHandle ->
      return closedHandle
    }
    if (!config.seededDetachedSubAgentHandlesRequireCoordinatorOwnership) {
      return null
    }
    if (!handle.canContinueDetachedExecution(hasApprovalContinuation = false)) {
      return null
    }
    if (coordinatedSubAgentHandle(handle) != null) {
      return null
    }
    return handle
      .withClearedChildPromptCheckpoint(updatedAtEpochMs = clock())
      .copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.CANCELLED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Delegated child run '${handle.description}' was cancelled before wait_agent could harvest it.",
        ),
        pendingApprovalResume = null,
        pendingApprovalDecision = null,
        childExecutionStatus = ExecutionStatus.CANCELLED.name,
      )
  }
