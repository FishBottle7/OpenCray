package com.opencray.runtime.subagent

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAgentRuntime
import com.opencray.runtime.OpenCrayAgentRuntime.Companion.CHILD_APPROVAL_METADATA_KEYS
import com.opencray.runtime.OpenCrayAgentRuntime.Companion.ERROR_APPROVAL_REQUIRED
import com.opencray.runtime.OpenCrayAgentRuntime.Companion.ERROR_HIGH_RISK_APPROVAL_REQUIRED
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.policy.ToolPolicyPlan
import com.opencray.runtime.primitiveContent
import com.opencray.runtime.skills.ActiveSkillCapsule
import java.util.UUID

  internal fun OpenCrayAgentRuntime.maybeExecuteSubAgentCall(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
  ): AgentToolResult? {
    val canonicalToolName = canonicalSubAgentToolName(call.toolName) ?: return null
    if (!isSubAgentToolExposed(canonicalToolName)) {
      return toolDispatcher.dispatch(task = task, call = call, hooks = hooks)
    }
    when (canonicalToolName) {
      "spawn_agent" -> return spawnSubAgentHandle(
        task = task,
        turn = turn,
        call = call,
        transcript = transcript,
        cursor = cursor,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
      )

      "wait_agent" -> return waitOnSubAgentHandle(
        task = task,
        turn = turn,
        call = call,
        transcript = transcript,
        cursor = cursor,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
      )

      "send_input" -> return sendInputToSubAgentHandle(
        task = task,
        turn = turn,
        call = call,
        transcript = transcript,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        cursor = cursor,
      )

      "close_agent" -> return closeSubAgentHandle(
        task = task,
        turn = turn,
        call = call,
        cursor = cursor,
      )

      "list_subagents" -> return listSubAgentHandles(
        call = call,
        cursor = cursor,
      )

      "Task" -> Unit
      else -> return null
    }
    if (!call.toolName.trim().equals("Task", ignoreCase = true)) {
      return null
    }
    val preparedDelegation = when (
      val prepared = prepareSubAgentDelegation(
        task = task,
        turn = turn,
        call = call,
        activeSkillCapsule = activeSkillCapsule,
        toolName = "Task",
      )
    ) {
      is OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Invalid -> return prepared.result
      is OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Ready -> prepared.delegation
    }
    return when (
      val spawned = spawnPreparedSubAgentHandle(
        task = task,
        turn = turn,
        call = call,
        transcript = transcript,
        cursor = cursor,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        preparedDelegation = preparedDelegation,
      )
    ) {
      is OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Invalid -> taskDelegationToolResult(
        call = call,
        delegationPlan = preparedDelegation.delegationPlan,
        result = spawned.result,
      )

      is OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Ready -> {
        val childResult = spawned.execution.childResult
        if (childResult != null) {
          return childResultToTaskToolResult(
            call = call,
            handle = spawned.execution.handle,
            delegationPlan = preparedDelegation.delegationPlan,
            childResult = childResult,
            compressedChildResult = spawned.execution.handle.snapshot,
          )
        }
        val waitResult = waitOnResolvedSubAgentHandle(
          task = task,
          turn = turn,
          call = call,
          transcript = transcript,
          cursor = cursor,
          hooks = hooks,
          activeSkillCapsule = activeSkillCapsule,
          handle = spawned.execution.handle,
          handles = spawned.execution.handles,
        )
        taskDelegationToolResult(
          call = call,
          delegationPlan = preparedDelegation.delegationPlan,
          result = waitResult,
        )
      }
    }
  }

  internal fun OpenCrayAgentRuntime.canonicalSubAgentToolName(toolName: String): String? = when (toolName.trim().lowercase()) {
    "task" -> "Task"
    "spawn_agent",
    "spawnagent",
    -> "spawn_agent"

    "wait_agent",
    "waitagent",
    -> "wait_agent"

    "send_input",
    "sendinput",
    -> "send_input"

    "close_agent",
    "closeagent",
    -> "close_agent"

    "list_subagents",
    "listsubagents",
    "list_handles",
    "listhandles",
    -> "list_subagents"

    else -> null
  }

  internal fun OpenCrayAgentRuntime.isSubAgentToolExposed(toolName: String): Boolean = toolDispatcher.definitions().any { definition ->
    definition.name.trim().equals(toolName, ignoreCase = true)
  }

  internal fun OpenCrayAgentRuntime.spawnSubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): AgentToolResult {
    val preparedDelegation = when (
      val prepared = prepareSubAgentDelegation(
        task = task,
        turn = turn,
        call = call,
        activeSkillCapsule = activeSkillCapsule,
        toolName = "spawn_agent",
      )
    ) {
      is OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Invalid -> return prepared.result
      is OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Ready -> prepared.delegation
    }
    return when (
      val spawned = spawnPreparedSubAgentHandle(
        task = task,
        turn = turn,
        call = call,
        transcript = transcript,
        cursor = cursor,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        preparedDelegation = preparedDelegation,
      )
    ) {
      is OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Invalid -> spawned.result
      is OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Ready -> {
        val childResult = spawned.execution.childResult
        if (childResult != null) {
          childResultToSpawnAgentToolResult(
            call = call,
            handle = spawned.execution.handle,
            delegationPlan = preparedDelegation.delegationPlan,
            childResult = childResult,
            childApprovalResume = spawned.execution.childApprovalResume,
          )
        } else {
          storedSpawnAgentHandleResult(
            call = call,
            handle = spawned.execution.handle,
            delegationPlan = preparedDelegation.delegationPlan,
          )
        }
      }
    }
  }

  internal fun OpenCrayAgentRuntime.spawnPreparedSubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    cursor: OpenCrayAgentRuntime.PromptTurnCursor?,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    preparedDelegation: OpenCrayAgentRuntime.PreparedSubAgentDelegation,
  ): OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult {
    val handles = subAgentHandleRegistry(cursor)
    val existingHandle = findContinuationHandle(handles)
    val requestedAgentId = call.arguments.primitiveContent("agent_id")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (
      existingHandle != null &&
      requestedAgentId != null &&
      requestedAgentId != existingHandle.agentId
    ) {
      return OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Invalid(
        AgentToolResult(
          toolName = call.toolName,
          status = AgentToolResultStatus.FAILED,
          content = "Delegated child handle '$requestedAgentId' does not match the resumable child handle '${existingHandle.agentId}'.",
          errorCode = "SUBAGENT_HANDLE_MISMATCH",
          errorMessage = "Delegated child handle '$requestedAgentId' does not match the resumable child handle '${existingHandle.agentId}'.",
          metadata = mapOf(
            "agentId" to existingHandle.agentId,
            "requestedAgentId" to requestedAgentId,
          ),
        ),
      )
    }
    val handle = existingHandle ?: run {
      val agentId = requestedAgentId ?: continuationResume()?.agentId ?: "agent-${UUID.randomUUID().toString().take(8)}"
      if (handles.containsKey(agentId)) {
        return OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Invalid(
          AgentToolResult(
            toolName = call.toolName,
            status = AgentToolResultStatus.FAILED,
            content = "Delegated child handle '$agentId' already exists.",
            errorCode = "SUBAGENT_HANDLE_EXISTS",
            errorMessage = "Delegated child handle '$agentId' already exists.",
            metadata = mapOf("agentId" to agentId),
          ),
        )
      }
      createSubAgentHandle(
        task = task,
        prepared = preparedDelegation,
        agentId = agentId,
        childRunId = continuationResume()?.childRunId,
        childTaskId = continuationResume()?.childTaskId,
      ).also { createdHandle ->
        handles[createdHandle.agentId] = createdHandle
        config.subAgentExecutionCoordinator.upsertHandle(createdHandle)
        emitSubAgentEvent(
          task = task,
          turn = turn,
          agentId = createdHandle.agentId,
          phase = OpenCraySubAgentPhase.STARTED,
          childTask = preparedDelegation.childTask,
          childRunId = createdHandle.childRunId,
          childTaskId = createdHandle.childTaskId,
          summary = createdHandle.snapshot.summaryText(),
          snapshot = createdHandle.snapshot,
          liveContext = createdHandle.childLiveContext,
        )
      }
    }
    if (cursor != null) {
      activeSubAgentExecution(cursor = cursor, agentId = handle.agentId)?.let {
        return OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Ready(
          OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleExecution(
            handle = synchronizedSubAgentHandle(
              cursor = cursor,
              agentId = handle.agentId,
            ) ?: handle,
            handles = handles,
          ),
        )
      }
      if (handle.isTerminalWithoutPendingApprovalResume()) {
        return OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Ready(
          OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleExecution(
            handle = handle,
            handles = handles,
          ),
        )
      }
      val approvalContinuation = takePendingApprovalContinuation(
        handle = handle,
        handles = handles,
      )
      if (!handle.canContinueDetachedExecution(hasApprovalContinuation = approvalContinuation != null)) {
        return OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Ready(
          OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleExecution(
            handle = handle,
            handles = handles,
          ),
        )
      }
      val startedHandle = startSubAgentHandleBackgroundExecution(
        task = task,
        turn = turn,
        transcript = transcript,
        parentSessionContext = cursor.sessionContext,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        cursor = cursor,
        handle = handle,
        profile = preparedDelegation.profile,
        approvalContinuation = approvalContinuation,
        emitResumedPhaseWithoutApproval = existingHandle == null,
      )
      return OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Ready(
        OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleExecution(
          handle = startedHandle,
          handles = handles,
        ),
      )
    }
    if (handle.isTerminalWithoutPendingApprovalResume()) {
      return OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Ready(
        OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleExecution(
          handle = handle,
          handles = handles,
        ),
      )
    }
    val approvalContinuation = takePendingApprovalContinuation(
      handle = handle,
      handles = handles,
    )
    if (!handle.canContinueDetachedExecution(hasApprovalContinuation = approvalContinuation != null)) {
      return OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Ready(
        OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleExecution(
          handle = handle,
          handles = handles,
        ),
      )
    }
    val execution = executeSubAgentHandleLifecycle(
      task = task,
      turn = turn,
      transcript = transcript,
      parentSessionContext = cursor?.sessionContext ?: config.sessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = handle,
      profile = preparedDelegation.profile,
      handles = handles,
      approvalContinuation = approvalContinuation,
      emitResumedPhaseWithoutApproval = existingHandle == null,
      retainTerminalHandle = true,
    )
    return OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleResult.Ready(
      OpenCrayAgentRuntime.SpawnPreparedSubAgentHandleExecution(
        handle = execution.handle,
        handles = handles,
        childResult = execution.childResult,
        childApprovalResume = execution.childApprovalResume,
      ),
    )
  }
  internal fun OpenCrayAgentRuntime.clearPendingApprovalContinuationForHandle(handle: SubAgentHandleState) {
    pendingApprovedSubAgentResume = pendingApprovedSubAgentResume?.takeUnless { resume ->
      resumeTargetsHandle(resume = resume, handle = handle)
    }
    pendingRejectedSubAgentResume = pendingRejectedSubAgentResume?.takeUnless { resume ->
      resumeTargetsHandle(resume = resume, handle = handle)
    }
  }

  internal fun OpenCrayAgentRuntime.resumeTargetsHandle(
    resume: SubAgentApprovalResume,
    handle: SubAgentHandleState,
  ): Boolean = when {
    !resume.agentId.isNullOrBlank() -> resume.agentId == handle.agentId
    !resume.childTaskId.isNullOrBlank() -> resume.childTaskId == handle.childTaskId
    !resume.childRunId.isNullOrBlank() -> resume.childRunId == handle.childRunId
    else -> handle.pendingApprovalResume != null
  }
  internal fun OpenCrayAgentRuntime.takePendingApprovalContinuation(
    handle: SubAgentHandleState,
    handles: Map<String, SubAgentHandleState>,
  ): OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation? {
    handle.pendingApprovalDecision?.takeIf { decision ->
      resumeMatchesHandle(decision.resume, handle, handles)
    }?.let { decision ->
      clearPendingApprovalContinuationForHandle(handle)
      return OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation(
        resume = effectiveApprovalResume(handle, decision.resume),
        approved = decision.approved,
      )
    }
    val approvedResume = pendingApprovedSubAgentResume
    val rejectedResume = pendingRejectedSubAgentResume
    check(approvedResume == null || rejectedResume == null) {
      "Only one subagent approval continuation can be pending at a time."
    }
    approvedResume?.takeIf { resumeMatchesHandle(it, handle, handles) }?.let { resume ->
      pendingApprovedSubAgentResume = null
      return OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation(
        resume = effectiveApprovalResume(handle, resume),
        approved = true,
      )
    }
    rejectedResume?.takeIf { resumeMatchesHandle(it, handle, handles) }?.let { resume ->
      pendingRejectedSubAgentResume = null
      return OpenCrayAgentRuntime.PendingSubAgentApprovalContinuation(
        resume = effectiveApprovalResume(handle, resume),
        approved = false,
      )
    }
    return null
  }

  internal fun OpenCrayAgentRuntime.effectiveApprovalResume(
    handle: SubAgentHandleState,
    resume: SubAgentApprovalResume,
  ): SubAgentApprovalResume = (handle.pendingApprovalResume ?: resume).copy(
    isHighRisk = resume.isHighRisk || (handle.pendingApprovalResume?.isHighRisk == true),
    agentId = handle.pendingApprovalResume?.agentId ?: resume.agentId ?: handle.agentId,
    childRunId = handle.pendingApprovalResume?.childRunId ?: resume.childRunId ?: handle.childRunId,
    childTaskId = handle.pendingApprovalResume?.childTaskId ?: resume.childTaskId ?: handle.childTaskId,
  )

  internal fun OpenCrayAgentRuntime.resumeMatchesHandle(
    resume: SubAgentApprovalResume,
    handle: SubAgentHandleState,
    handles: Map<String, SubAgentHandleState>,
  ): Boolean = when {
    !resume.agentId.isNullOrBlank() -> resume.agentId == handle.agentId
    !resume.childTaskId.isNullOrBlank() -> resume.childTaskId == handle.childTaskId
    !resume.childRunId.isNullOrBlank() -> resume.childRunId == handle.childRunId
    else -> handle.pendingApprovalResume != null &&
      handles.values.count { candidate -> candidate.pendingApprovalResume != null } == 1
  }
  internal fun OpenCrayAgentRuntime.childResultToWaitAgentToolResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
    childResult: ExecutionResult,
    childApprovalResume: SubAgentApprovalResume?,
  ): AgentToolResult {
    val metadata = subAgentHandleMetadata(handle) + mapOf(
      SubAgentMetadataKeys.CONTROL_TOOL to call.toolName.lowercase(),
    )
    return when (childResult.status) {
      ExecutionStatus.SUCCESS -> AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.SUCCESS,
        content = handle.snapshot.summaryText(),
        metadata = metadata,
      )

      ExecutionStatus.CANCELLED -> AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.CANCELLED,
        content = handle.snapshot.summaryText(),
        errorCode = "SUBAGENT_CANCELLED",
        errorMessage = childResult.errorMessage ?: "Delegated child run was cancelled.",
        metadata = metadata,
      )

      ExecutionStatus.DENIED -> AgentToolResult(
        toolName = call.toolName,
        status = if (childApprovalResume != null) AgentToolResultStatus.DENIED else AgentToolResultStatus.FAILED,
        content = handle.snapshot.summaryText(),
        errorCode = if (childApprovalResume != null) {
          childResult.errorCode ?: ERROR_APPROVAL_REQUIRED
        } else {
          "SUBAGENT_POLICY_BLOCKED"
        },
        errorMessage = childResult.errorMessage ?: if (childApprovalResume != null) {
          "Delegated child run needs approval before it can continue."
        } else {
          "Delegated child run was blocked by policy."
        },
        metadata = metadata,
      )

      ExecutionStatus.FAILED,
      ExecutionStatus.TIMEOUT,
      -> AgentToolResult(
        toolName = call.toolName,
        status = if (childResult.status == ExecutionStatus.TIMEOUT) {
          AgentToolResultStatus.TIMEOUT
        } else {
          AgentToolResultStatus.FAILED
        },
        content = handle.snapshot.summaryText(),
        errorCode = childResult.errorCode ?: "SUBAGENT_FAILED",
        errorMessage = childResult.errorMessage ?: "Delegated child run failed.",
        metadata = metadata,
      )
    }
  }

  internal fun OpenCrayAgentRuntime.childResultToSpawnAgentToolResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
    delegationPlan: ToolPolicyPlan,
    childResult: ExecutionResult,
    childApprovalResume: SubAgentApprovalResume?,
  ): AgentToolResult {
    val waitLikeResult = childResultToWaitAgentToolResult(
      call = call,
      handle = handle,
      childResult = childResult,
      childApprovalResume = childApprovalResume,
    )
    return waitLikeResult.copy(
      toolName = call.toolName,
      metadata = toolDispatcher.taskDelegationResultMetadata(
        plan = delegationPlan,
        metadata = waitLikeResult.metadata,
      ),
    )
  }

  internal fun OpenCrayAgentRuntime.storedSpawnAgentHandleResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
    delegationPlan: ToolPolicyPlan,
  ): AgentToolResult {
    val storedResult = storedSubAgentHandleResult(
      call = call,
      handle = handle,
    )
    return storedResult.copy(
      metadata = toolDispatcher.taskDelegationResultMetadata(
        plan = delegationPlan,
        metadata = storedResult.metadata,
      ),
    )
  }

  internal fun OpenCrayAgentRuntime.taskDelegationToolResult(
    call: AgentToolCall,
    delegationPlan: ToolPolicyPlan,
    result: AgentToolResult,
  ): AgentToolResult = result.copy(
    toolName = call.toolName,
    metadata = toolDispatcher.taskDelegationResultMetadata(
      plan = delegationPlan,
      metadata = result.metadata,
    ),
  )

  internal fun OpenCrayAgentRuntime.invalidSubAgentCallResult(
    call: AgentToolCall,
    message: String,
  ): AgentToolResult = AgentToolResult(
    toolName = call.toolName,
    status = AgentToolResultStatus.FAILED,
    content = message,
    errorCode = "INVALID_SUBAGENT_TASK",
    errorMessage = message,
  )

  internal fun OpenCrayAgentRuntime.prepareSubAgentDelegation(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    activeSkillCapsule: ActiveSkillCapsule?,
    toolName: String,
  ): OpenCrayAgentRuntime.PreparedSubAgentDelegationResult {
    val description = call.arguments.primitiveContent("description")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Invalid(
        invalidSubAgentCallResult(call, "$toolName description must not be blank."),
      )
    val prompt = call.arguments.primitiveContent("prompt")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Invalid(
        invalidSubAgentCallResult(call, "$toolName prompt must not be blank."),
      )
    val subagentType = call.arguments.primitiveContent("subagent_type")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Invalid(
        invalidSubAgentCallResult(call, "$toolName subagent_type must not be blank."),
      )
    val resolvedSubagentType = BuiltInSubAgentProfiles.normalizedRequestedId(subagentType)
      ?: subagentType
    val profile = BuiltInSubAgentProfiles.resolve(subagentType)
      ?: return OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Invalid(
        invalidSubAgentCallResult(
          call = call,
          message = "Unknown $toolName subagent_type '$subagentType'.",
        ),
      )
    val requestedContextMode = call.arguments.primitiveContent("context_mode")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val requestedMode = when {
      requestedContextMode == null -> null
      else -> {
        val parsedMode = SubAgentContextMode.fromWireValue(requestedContextMode)
          ?: return OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Invalid(
            invalidSubAgentCallResult(
              call = call,
              message = "Unknown $toolName context_mode '$requestedContextMode'. Expected one of: ${SubAgentContextMode.publicWireValuesDescription()}.",
            ),
          )
        if (!parsedMode.publicControlPlaneEnabled) {
          return OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Invalid(
            invalidSubAgentCallResult(
              call = call,
              message = "Unsupported $toolName context_mode '$requestedContextMode'. Expected one of: ${SubAgentContextMode.publicWireValuesDescription()}. mirrored is reserved for internal-only child-runtime flows.",
            ),
          )
        }
        parsedMode
      }
    }
    val contextModeResolution = config.subAgentContextPolicy.resolve(
      profile = profile,
      explicitMode = requestedMode,
    )
    val parentDepth = task.metadata[SubAgentMetadataKeys.DEPTH]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.toIntOrNull()
      ?: 0
    val childDepth = parentDepth + 1
    if (childDepth > config.maxSubAgentDepth) {
      return OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Invalid(
        AgentToolResult(
          toolName = call.toolName,
          status = AgentToolResultStatus.FAILED,
          content = "$toolName delegation depth exceeded the configured child-runtime limit.",
          errorCode = "SUBAGENT_DEPTH_EXCEEDED",
          errorMessage = "$toolName delegation depth exceeded the configured child-runtime limit.",
          metadata = mapOf(
            "subagentType" to resolvedSubagentType,
            "subagentDepth" to childDepth.toString(),
            "maxSubAgentDepth" to config.maxSubAgentDepth.toString(),
          ),
        ),
      )
    }
    val childTask = SubAgentTask(
      description = description,
      prompt = prompt,
      subagentType = resolvedSubagentType,
      contextMode = contextModeResolution.mode,
      contextModeSource = contextModeResolution.source,
      parentRunId = runIdFor(task),
      parentTaskId = task.id,
      parentTurn = turn,
      depth = childDepth,
      activeSkillName = activeSkillCapsule?.name,
      activeSkillActivationSource = activeSkillCapsule?.activationSource,
      activeSkillPinned = activeSkillCapsule?.pinned ?: false,
    )
    val delegationPlan = toolDispatcher.planTaskDelegation(
      task = task,
      toolName = toolName,
      description = description,
      prompt = prompt,
      subagentType = resolvedSubagentType,
      contextMode = childTask.contextMode.wireValue,
      contextModeSource = childTask.contextModeSource.wireValue,
      allowedToolNames = profile.allowedToolNames,
    )
    toolDispatcher.gateTaskDelegation(
      plan = delegationPlan,
      toolName = toolName,
    )?.let { deniedResult ->
      return OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Invalid(deniedResult.copy(toolName = call.toolName))
    }
    return OpenCrayAgentRuntime.PreparedSubAgentDelegationResult.Ready(
      OpenCrayAgentRuntime.PreparedSubAgentDelegation(
        profile = profile,
        childTask = childTask,
        delegationPlan = delegationPlan,
      ),
    )
  }

  internal fun OpenCrayAgentRuntime.createSubAgentHandle(
    task: AgentTask,
    prepared: OpenCrayAgentRuntime.PreparedSubAgentDelegation,
    agentId: String? = null,
    childSessionId: String? = null,
    childRunId: String? = null,
    childTaskId: String? = null,
  ): SubAgentHandleState {
    val createdAt = clock()
    val resolvedAgentId = agentId ?: "agent-${UUID.randomUUID().toString().take(8)}"
    return SubAgentHandleState.queued(
      agentId = resolvedAgentId,
      childSessionId = childSessionId ?: newSubAgentChildSessionId(resolvedAgentId),
      childRunId = childRunId ?: "subagent-${runIdFor(task)}-${prepared.childTask.parentTurn}-${UUID.randomUUID().toString().take(8)}",
      childTaskId = childTaskId ?: "subagent-task-${UUID.randomUUID().toString().take(8)}",
      description = prepared.childTask.description,
      prompt = prepared.childTask.prompt,
      subagentType = prepared.profile.id,
      contextMode = prepared.childTask.contextMode.wireValue,
      contextModeSource = prepared.childTask.contextModeSource.wireValue,
      parentRunId = prepared.childTask.parentRunId,
      parentTaskId = prepared.childTask.parentTaskId,
      parentTurn = prepared.childTask.parentTurn,
      depth = prepared.childTask.depth,
      activeSkillName = prepared.childTask.activeSkillName,
      activeSkillActivationSource = prepared.childTask.activeSkillActivationSource,
      activeSkillPinned = prepared.childTask.activeSkillPinned,
      createdAtEpochMs = createdAt,
    )
  }

  internal fun OpenCrayAgentRuntime.resolveInheritedSubAgentSkillCapsule(
    handle: SubAgentHandleState,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): ActiveSkillCapsule? = resolveActiveSkillCapsule(
    activeSkillName = handle.activeSkillName ?: activeSkillCapsule?.name,
    activationSource = handle.activeSkillActivationSource ?: activeSkillCapsule?.activationSource,
    pinned = if (handle.activeSkillName != null) {
      handle.activeSkillPinned
    } else {
      activeSkillCapsule?.pinned
    },
  ) ?: activeSkillCapsule

  internal fun OpenCrayAgentRuntime.continuationResume(): SubAgentApprovalResume? =
    pendingApprovedSubAgentResume ?: pendingRejectedSubAgentResume

  internal fun OpenCrayAgentRuntime.findContinuationHandle(
    handles: Map<String, SubAgentHandleState>,
  ): SubAgentHandleState? {
    val resume = continuationResume() ?: return null
    return handles.values.firstOrNull { handle -> resumeMatchesHandle(resume, handle, handles) }
  }

  internal fun OpenCrayAgentRuntime.childResultToTaskToolResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
    delegationPlan: ToolPolicyPlan,
    childResult: ExecutionResult,
    compressedChildResult: SubAgentExecutionSnapshot,
  ): AgentToolResult {
    val childTurnCount = childResult.metadata["turnCount"].orEmpty()
    val childToolCallCount = childResult.metadata["toolCallCount"].orEmpty()
    val childLiveContext = SubAgentLiveContextSnapshot.fromRuntimeMetadata(childResult.metadata)
    val childApprovalResume = childApprovalResume(
      childResult = childResult,
      agentId = handle.agentId,
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
    )
    val childApprovalMetadata = childApprovalMetadata(
      childMetadata = childResult.metadata,
      childApprovalResume = childApprovalResume,
    )
    val baseMetadata = linkedMapOf(
      "agentId" to handle.agentId,
      "childSessionId" to handle.childSessionId,
      "subagentType" to handle.subagentType,
      "subagentContextMode" to handle.contextMode,
      "subagentContextModeSource" to handle.contextModeSource,
      "subagentDepth" to handle.depth.toString(),
      SubAgentMetadataKeys.CONTROL_TOOL to call.toolName.lowercase(),
      "childRunId" to handle.childRunId,
      "childTaskId" to childResult.taskId,
      "childExecutionStatus" to childResult.status.name,
    ).apply {
      if (childTurnCount.isNotBlank()) {
        put("childTurnCount", childTurnCount)
      }
      if (childToolCallCount.isNotBlank()) {
        put("childToolCallCount", childToolCallCount)
      }
      putAll(childLiveContext.toMetadataMap())
      putAll(compressedChildResult.metadata())
      putAll(childApprovalMetadata)
    }
    val metadataWithPolicy = toolDispatcher.taskDelegationResultMetadata(
      plan = delegationPlan,
      metadata = baseMetadata,
    )
    return when (childResult.status) {
      ExecutionStatus.SUCCESS -> AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.SUCCESS,
        content = compressedChildResult.summaryText(),
        metadata = metadataWithPolicy,
      )

      ExecutionStatus.CANCELLED -> AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.CANCELLED,
        content = compressedChildResult.summaryText(),
        errorCode = "SUBAGENT_CANCELLED",
        errorMessage = childResult.errorMessage ?: "Delegated child run was cancelled.",
        metadata = metadataWithPolicy,
      )

      ExecutionStatus.DENIED -> AgentToolResult(
        toolName = call.toolName,
        status = if (childApprovalResume != null) {
          AgentToolResultStatus.DENIED
        } else {
          AgentToolResultStatus.FAILED
        },
        content = compressedChildResult.summaryText(),
        errorCode = if (childApprovalResume != null) {
          childResult.errorCode ?: ERROR_APPROVAL_REQUIRED
        } else {
          "SUBAGENT_POLICY_BLOCKED"
        },
        errorMessage = childResult.errorMessage ?: if (childApprovalResume != null) {
          "Delegated child run needs approval before it can continue."
        } else {
          "Delegated child run was blocked by policy."
        },
        metadata = metadataWithPolicy,
      )

      ExecutionStatus.FAILED,
      ExecutionStatus.TIMEOUT,
      -> AgentToolResult(
        toolName = call.toolName,
        status = if (childResult.status == ExecutionStatus.TIMEOUT) {
          AgentToolResultStatus.TIMEOUT
        } else {
          AgentToolResultStatus.FAILED
        },
        content = compressedChildResult.summaryText(),
        errorCode = childResult.errorCode ?: "SUBAGENT_FAILED",
        errorMessage = childResult.errorMessage ?: "Delegated child run failed.",
        metadata = metadataWithPolicy,
      )
    }
  }

  internal fun OpenCrayAgentRuntime.emitSubAgentEvent(
    task: AgentTask,
    turn: Int,
    agentId: String? = null,
    phase: OpenCraySubAgentPhase,
    childTask: SubAgentTask,
    childRunId: String,
    childTaskId: String,
    summary: String?,
    snapshot: SubAgentExecutionSnapshot,
    liveContext: SubAgentLiveContextSnapshot? = null,
    closed: Boolean = false,
  ) {
    eventSink.onRunEvent(
      task = task,
      event = OpenCraySubAgentEvent(
        runId = runIdFor(task),
        taskId = task.id,
        agentId = agentId,
        phase = phase,
        childRunId = childRunId,
        childTaskId = childTaskId,
        label = childTask.description,
        subagentType = childTask.subagentType,
        contextMode = childTask.contextMode.wireValue,
        depth = childTask.depth,
        summary = summary,
        executionState = snapshot.state,
        continuationKind = snapshot.continuationKind,
        liveContext = liveContext?.takeUnless { it.isEmpty },
        resumable = snapshot.resumable,
        requiresUserAction = snapshot.requiresUserAction,
        isHighRisk = snapshot.isHighRisk,
        turn = turn,
        emittedAtEpochMs = clock(),
        closed = closed,
      ),
    )
  }

  internal fun OpenCrayAgentRuntime.childApprovalResume(
    childResult: ExecutionResult,
    agentId: String? = null,
    childRunId: String? = null,
    childTaskId: String? = null,
  ): SubAgentApprovalResume? {
    val encodedResume = SubAgentApprovalResumeMetadata.decodeFromMetadata(
      metadata = childResult.metadata,
      json = config.json,
    )
    if (encodedResume != null) {
      return encodedResume.copy(
        agentId = encodedResume.agentId ?: agentId,
        childRunId = encodedResume.childRunId ?: childRunId,
        childTaskId = encodedResume.childTaskId ?: childTaskId,
      )
    }
    val approvedToolName = approvalToolName(childResult.metadata) ?: return null
    val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = childResult.metadata,
      json = config.json,
    ) ?: return null
    return SubAgentApprovalResume(
      approvedToolName = approvedToolName,
      promptResumeState = promptResumeState,
      isHighRisk = childResult.errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED,
      agentId = agentId,
      childRunId = childRunId,
      childTaskId = childTaskId,
    )
  }

  internal fun OpenCrayAgentRuntime.childApprovalMetadata(
    childMetadata: Map<String, String>,
    childApprovalResume: SubAgentApprovalResume?,
  ): Map<String, String> {
    val forwarded = childMetadata.filterKeys { key -> key in CHILD_APPROVAL_METADATA_KEYS }
    if (childApprovalResume == null) {
      return forwarded
    }
    return forwarded + SubAgentApprovalResumeMetadata.encodeToMetadata(
      resume = childApprovalResume,
      json = config.json,
    )
  }

  internal fun OpenCrayAgentRuntime.approvalToolName(metadata: Map<String, String>): String? =
    metadata[OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: metadata["normalizedToolName"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: metadata["canonicalToolName"]
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: metadata["toolName"]
        ?.trim()
        ?.takeIf(String::isNotBlank)
