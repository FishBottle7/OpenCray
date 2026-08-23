package com.opencray.app.projection

import com.opencray.app.AgentRunSnapshot
import com.opencray.app.AgentTaskApprovalState
import com.opencray.app.AppAgentSessionTaskRuntimeFactory
import com.opencray.app.AgentRunSubmission
import com.opencray.app.PromptCheckpointStore
import com.opencray.app.RunEventJournalStore
import com.opencray.app.RunRecoveryPlan
import com.opencray.app.RunRecoveryPlanner
import com.opencray.app.loadStoredRunRecoveryPlan
import com.opencray.core.contracts.AgentTask
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.process.ManagedProcessSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_MANAGED_PROCESS_OUTPUT_PREVIEW_CHARS: Int = 4_096

private val snapshotReplayJson: Json = Json { ignoreUnknownKeys = true }

private val MEMORY_SELECTED_TRACE_REGEX: Regex = Regex("""^(.+?)@(\d+)(?:\[(.*)])?$""")
private val BOOTSTRAP_FILE_TRACE_REGEX: Regex =
  Regex("""^(.+?)@(.+)\[(\d+)\|(\d+)\|(true|false)]$""")
private val VISIBLE_SKILL_TRACE_REGEX: Regex =
  Regex("""^([a-z0-9-]+)@(.+)\[([^\]|]+)\|(true|false)\|([^\]|]+)]$""")

internal fun runSubmissionToMap(submission: AgentRunSubmission): Map<String, Any?> = mapOf(
  "sessionId" to submission.sessionId,
  "runId" to submission.runId,
  "taskId" to submission.taskId,
  "acceptedAtEpochMs" to submission.acceptedAtEpochMs,
  "diagnostics" to submission.lifecycleDiagnostics.toMap(),
)

internal fun runSnapshotToMap(
  run: AgentRunSnapshot,
  finalAttachmentsForRun: (AgentRunSnapshot) -> List<ChatAttachmentEntry>,
  recoveryPlanForRun: (AgentRunSnapshot) -> RunRecoveryPlan?,
  runtimeEventMapper: (OpenCrayAgentRunEvent) -> Map<String, Any?>,
): Map<String, Any?> = mapOf(
  "sessionId" to run.sessionId,
  "runId" to run.runId,
  "taskId" to run.taskId,
  "acceptedAtEpochMs" to run.acceptedAtEpochMs,
  "updatedAtEpochMs" to run.updatedAtEpochMs,
  "lifecycleState" to run.lifecycleState?.name?.lowercase(),
  "taskState" to run.taskState?.name?.lowercase(),
  "attempt" to run.attempt,
  "executionOrdinal" to run.executionOrdinal,
  "executionId" to run.executionId,
  "executionKind" to run.executionKind,
  "pendingExecutionKind" to run.pendingExecutionKind,
  "executionStatus" to run.executionStatus?.name?.lowercase(),
  "errorCode" to run.errorCode,
  "errorMessage" to run.errorMessage,
  "responseFormat" to run.responseFormat,
  "llmDiagnostics" to llmDiagnosticsFromMetadata(run.resultMetadata),
  "liveContext" to liveContextFromMetadata(run.resultMetadata),
  "contextBudget" to contextBudgetFromMetadata(run.resultMetadata),
  "memoryTrace" to memoryTraceFromMetadata(run.resultMetadata),
  "stickyMemory" to stickyMemoryFromMetadata(run.resultMetadata),
  "memoryFlush" to memoryFlushFromMetadata(run.resultMetadata),
  "bootstrap" to bootstrapFromMetadata(run.resultMetadata),
  "durableCompaction" to durableCompactionFromMetadata(run.resultMetadata),
  "skillInventory" to skillInventoryFromMetadata(run.resultMetadata),
  "activeSkill" to activeSkillFromMetadata(run.resultMetadata),
  "pendingMessageId" to run.pendingMessageId,
  "managedProcessIds" to run.managedProcessIds,
  "managedProcesses" to run.managedProcesses.map(::managedProcessSnapshotToMap),
  "finalAttachments" to finalAttachmentsForRun(run).map(::chatAttachmentSnapshotMap),
  "runningManagedProcessCount" to run.runningManagedProcessCount,
  "hasLiveManagedProcesses" to run.hasLiveManagedProcesses,
  "hasAutoResumeEligibleManagedProcesses" to run.hasAutoResumeEligibleManagedProcesses,
  "isActive" to run.isActive,
  "isTerminal" to run.isTerminal,
  "lastEvent" to run.lastEvent?.let(runtimeEventMapper),
  "diagnostics" to run.lifecycleDiagnostics.toMap(),
  "recoveryPlan" to recoveryPlanForRun(run)?.toMap(),
)

internal fun managedProcessSnapshotToMap(
  snapshot: ManagedProcessSnapshot,
): Map<String, Any?> {
  val stdoutTruncated = snapshot.stdout.length > MAX_MANAGED_PROCESS_OUTPUT_PREVIEW_CHARS
  val stderrTruncated = snapshot.stderr.length > MAX_MANAGED_PROCESS_OUTPUT_PREVIEW_CHARS
  return mapOf(
    "processId" to snapshot.processId,
    "status" to snapshot.status.name.lowercase(),
    "command" to snapshot.command,
    "args" to snapshot.args,
    "workingDirectory" to snapshot.workingDirectory,
    "processStarted" to snapshot.processStarted,
    "timeoutMs" to snapshot.timeoutMs,
    "startedAtEpochMs" to snapshot.startedAtEpochMs,
    "updatedAtEpochMs" to snapshot.updatedAtEpochMs,
    "finishedAtEpochMs" to snapshot.finishedAtEpochMs,
    "exitCode" to snapshot.exitCode,
    "errorCode" to snapshot.errorCode,
    "errorMessage" to snapshot.errorMessage,
    "timedOut" to snapshot.timedOut,
    "cancelled" to snapshot.cancelled,
    "outputLimitExceeded" to snapshot.outputLimitExceeded,
    "stdout" to snapshot.stdout,
    "stderr" to snapshot.stderr,
    "stdoutPreview" to managedProcessOutputPreview(snapshot.stdout),
    "stderrPreview" to managedProcessOutputPreview(snapshot.stderr),
    "stdoutTruncated" to stdoutTruncated,
    "stderrTruncated" to stderrTruncated,
  )
}

internal fun managedProcessOutputPreview(text: String): String =
  text.takeLast(MAX_MANAGED_PROCESS_OUTPUT_PREVIEW_CHARS)

internal fun recoveryPlanForRun(
  run: AgentRunSnapshot,
  approvalStateForTask: (sessionId: String, taskId: String) -> AgentTaskApprovalState?,
  promptCheckpointStoreFor: (String) -> PromptCheckpointStore,
  journalStoreFor: (String) -> RunEventJournalStore,
  planner: RunRecoveryPlanner,
): RunRecoveryPlan? {
  val approvalState = approvalStateForTask(run.sessionId, run.taskId)
  return loadStoredRunRecoveryPlan(
    run = run,
    checkpointStore = promptCheckpointStoreFor(run.sessionId),
    journalStore = journalStoreFor(run.sessionId),
    planner = planner,
    approvalStateOverride = approvalState,
  )
}

internal fun llmDiagnosticsFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val nativeToolCallRequested = metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED]
    ?.toBooleanStrictOrNull()
  val responseShape = metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE]
    ?.takeIf(String::isNotBlank)
  val nativeToolCallObserved = metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED]
    ?.toBooleanStrictOrNull()
  val parsedToolCallObserved = metadata[LiteLlmMetadataKeys.PARSED_TOOL_CALL_OBSERVED]
    ?.toBooleanStrictOrNull()
  val fallbackParserAttempted = metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED]
    ?.toBooleanStrictOrNull()
  val fallbackParserSucceeded = metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_SUCCEEDED]
    ?.toBooleanStrictOrNull()
  val responsesContinuationRecoveryCount = metadata["responsesContinuationRecoveryCount"]
    ?.toIntOrNull()
  val responsesContinuationRecoveryLastReason = metadata["responsesContinuationRecoveryLastReason"]
    ?.takeIf(String::isNotBlank)
  val localContinuationUsedCount = metadata["localContinuationUsedCount"]?.toIntOrNull()
  val localContinuationFallbackCount = metadata["localContinuationFallbackCount"]?.toIntOrNull()
  val localContinuationLastMode = metadata["localContinuationLastMode"]
    ?.takeIf(String::isNotBlank)
  val localContinuationLastReason = metadata["localContinuationLastReason"]
    ?.takeIf(String::isNotBlank)
  val responsesPendingContextUpdateCount = metadata["responsesPendingContextUpdateCount"]
    ?.toIntOrNull()
  val responsesPendingContextUpdateHash = metadata["responsesPendingContextUpdateHash"]
    ?.takeIf(String::isNotBlank)
  val toolCallEventEmitted = metadata[LiteLlmMetadataKeys.TOOL_CALL_EVENT_EMITTED]
    ?.toBooleanStrictOrNull()
  val toolResultEventEmitted = metadata[LiteLlmMetadataKeys.TOOL_RESULT_EVENT_EMITTED]
    ?.toBooleanStrictOrNull()
  val contextCacheBreakReason = metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON]
    ?.takeIf(String::isNotBlank)
  val lastSuccessfulToolName = metadata[LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME]
    ?.takeIf(String::isNotBlank)
  if (
    nativeToolCallRequested == null &&
    responseShape == null &&
    nativeToolCallObserved == null &&
    parsedToolCallObserved == null &&
    fallbackParserAttempted == null &&
    fallbackParserSucceeded == null &&
    responsesContinuationRecoveryCount == null &&
    responsesContinuationRecoveryLastReason == null &&
    localContinuationUsedCount == null &&
    localContinuationFallbackCount == null &&
    localContinuationLastMode == null &&
    localContinuationLastReason == null &&
    responsesPendingContextUpdateCount == null &&
    responsesPendingContextUpdateHash == null &&
    toolCallEventEmitted == null &&
    toolResultEventEmitted == null &&
    contextCacheBreakReason == null &&
    lastSuccessfulToolName == null
  ) {
    return null
  }
  return buildMap {
    put("nativeToolCallRequested", nativeToolCallRequested)
    put("providerResponseShape", responseShape)
    put("nativeToolCallObserved", nativeToolCallObserved)
    put("parsedToolCallObserved", parsedToolCallObserved)
    put("fallbackParserAttempted", fallbackParserAttempted)
    put("fallbackParserSucceeded", fallbackParserSucceeded)
    put("responsesContinuationRecoveryCount", responsesContinuationRecoveryCount)
    put("responsesContinuationRecoveryLastReason", responsesContinuationRecoveryLastReason)
    put("localContinuationUsedCount", localContinuationUsedCount)
    put("localContinuationFallbackCount", localContinuationFallbackCount)
    put("localContinuationLastMode", localContinuationLastMode)
    put("localContinuationLastReason", localContinuationLastReason)
    put("responsesPendingContextUpdateCount", responsesPendingContextUpdateCount)
    put("responsesPendingContextUpdateHash", responsesPendingContextUpdateHash)
    put("toolCallEventEmitted", toolCallEventEmitted)
    put("toolResultEventEmitted", toolResultEventEmitted)
    put("contextCacheBreakReason", contextCacheBreakReason)
    put("lastSuccessfulToolName", lastSuccessfulToolName)
  }
}

internal fun memoryTraceFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val matchedCount = metadata["contextMatchedMemoryCount"]?.toIntOrNull()
  val injectedCount = metadata["contextInjectedMemoryCount"]?.toIntOrNull()
  val omittedCount = metadata["contextOmittedMemoryCount"]?.toIntOrNull()
  val queryTerms = metadata["contextMemoryQueryTerms"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
  val selected = parseSelectedMemoryTrace(metadata["contextMemorySelectedSummary"].orEmpty())
  val omitted = parseOmittedMemoryTrace(metadata["contextMemoryOmittedSummary"].orEmpty())
  val filteredCounts = parseFilteredMemoryCounts(metadata["contextMemoryFilteredCounts"].orEmpty())
  if (
    matchedCount == null &&
    injectedCount == null &&
    omittedCount == null &&
    queryTerms.isEmpty() &&
    selected.isEmpty() &&
    omitted.isEmpty() &&
    filteredCounts.isEmpty()
  ) {
    return null
  }
  return buildMap {
    matchedCount?.let { put("matchedRecordCount", it) }
    injectedCount?.let { put("injectedRecordCount", it) }
    omittedCount?.let { put("omittedRecordCount", it) }
    if (queryTerms.isNotEmpty()) {
      put("queryTerms", queryTerms)
    }
    if (selected.isNotEmpty()) {
      put("selected", selected)
    }
    if (omitted.isNotEmpty()) {
      put("omitted", omitted)
    }
    if (filteredCounts.isNotEmpty()) {
      put("filteredCounts", filteredCounts)
    }
  }
}

internal fun liveContextFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val mode = metadata["contextLiveMode"]?.takeIf(String::isNotBlank)
  val soulEnabled = metadata["contextLiveSoulEnabled"]?.toBooleanStrictOrNull()
  val memoryRecallEnabled = metadata["contextLiveMemoryRecallEnabled"]?.toBooleanStrictOrNull()
  val replaySource = metadata["contextLiveReplaySource"]?.takeIf(String::isNotBlank)
  val replayMessageCount = metadata["contextLiveReplayMessageCount"]?.toIntOrNull()
  val canonicalSource = metadata["contextLiveCanonicalSource"]?.takeIf(String::isNotBlank)
  val canonicalMessageCount = metadata["contextLiveCanonicalMessageCount"]?.toIntOrNull()
  val canonicalHistoryPreserved =
    metadata["contextLiveCanonicalHistoryPreserved"]?.toBooleanStrictOrNull()
  val inheritanceSource = metadata["contextLiveInheritanceSource"]?.takeIf(String::isNotBlank)
  val parentMode = metadata["contextLiveParentMode"]?.takeIf(String::isNotBlank)
  val parentReplayMessageCount = metadata["contextLiveParentReplayMessageCount"]?.toIntOrNull()
  val budgetPreset = metadata["contextLiveBudgetPreset"]?.takeIf(String::isNotBlank)
  if (
    mode == null &&
    soulEnabled == null &&
    memoryRecallEnabled == null &&
    replaySource == null &&
    replayMessageCount == null &&
    canonicalSource == null &&
    canonicalMessageCount == null &&
    canonicalHistoryPreserved == null &&
    inheritanceSource == null &&
    parentMode == null &&
    parentReplayMessageCount == null &&
    budgetPreset == null
  ) {
    return null
  }
  return buildMap {
    mode?.let { put("mode", it) }
    soulEnabled?.let { put("soulEnabled", it) }
    memoryRecallEnabled?.let { put("memoryRecallEnabled", it) }
    replaySource?.let { put("replaySource", it) }
    replayMessageCount?.let { put("replayMessageCount", it) }
    canonicalSource?.let { put("canonicalSource", it) }
    canonicalMessageCount?.let { put("canonicalMessageCount", it) }
    canonicalHistoryPreserved?.let { put("canonicalHistoryPreserved", it) }
    inheritanceSource?.let { put("inheritanceSource", it) }
    parentMode?.let { put("parentMode", it) }
    parentReplayMessageCount?.let { put("parentReplayMessageCount", it) }
    budgetPreset?.let { put("budgetPreset", it) }
  }
}

internal fun contextBudgetFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val applied = metadata["contextBudgetApplied"]?.toBooleanStrictOrNull()
  val pressureMode = metadata["contextBudgetPressureMode"]?.takeIf(String::isNotBlank)
  val selectedPreset = metadata["contextBudgetSelectedPreset"]?.takeIf(String::isNotBlank)
  val effectivePreset = metadata["contextBudgetEffectivePreset"]?.takeIf(String::isNotBlank)
  val presetSource = metadata["contextBudgetPresetSource"]?.takeIf(String::isNotBlank)
  val presetDiverged = metadata["contextBudgetPresetDiverged"]?.toBooleanStrictOrNull()
  val sourcePreset = metadata["contextBudgetSourcePreset"]?.takeIf(String::isNotBlank)
  val sourceTranscriptMaxMessages = metadata["contextBudgetSourceTranscriptMaxMessages"]?.toIntOrNull()
  val sourceInjectedMemoryMaxRecords = metadata["contextBudgetSourceInjectedMemoryMaxRecords"]?.toIntOrNull()
  val sourceMemoryRecallMaxRecords = metadata["contextBudgetSourceMemoryRecallMaxRecords"]?.toIntOrNull()
  val sourceBootstrapMaxChars = metadata["contextBudgetSourceBootstrapMaxChars"]?.toIntOrNull()
  val sourceSkillInventoryMaxSkills = metadata["contextBudgetSourceSkillInventoryMaxSkills"]?.toIntOrNull()
  val sourceActiveSkillMaxChars = metadata["contextBudgetSourceActiveSkillMaxChars"]?.toIntOrNull()
  val sourceRecentObservationMaxEntries = metadata["contextBudgetSourceRecentObservationMaxEntries"]?.toIntOrNull()
  val sourceMemoryFlushMaxToolObservations = metadata["contextBudgetSourceMemoryFlushMaxToolObservations"]?.toIntOrNull()
  val contextWindowTokens = metadata["contextBudgetContextWindowTokens"]?.toIntOrNull()
  val reservedOutputTokens = metadata["contextBudgetReservedOutputTokens"]?.toIntOrNull()
  val safetyMarginTokens = metadata["contextBudgetSafetyMarginTokens"]?.toIntOrNull()
  val hardInputTokens = metadata["contextBudgetHardInputTokens"]?.toIntOrNull()
  val targetInputTokens = metadata["contextBudgetTargetInputTokens"]?.toIntOrNull()
  val emergencyInputTokens = metadata["contextBudgetEmergencyInputTokens"]?.toIntOrNull()
  val unresolvedOverflow = metadata["contextBudgetUnresolvedOverflow"]?.toBooleanStrictOrNull()
  val fullLayerCount = metadata["contextBudgetFullLayerCount"]?.toIntOrNull()
  val compactLayerCount = metadata["contextBudgetCompactLayerCount"]?.toIntOrNull()
  val minimalLayerCount = metadata["contextBudgetMinimalLayerCount"]?.toIntOrNull()
  val omittedLayerCount = metadata["contextBudgetOmittedLayerCount"]?.toIntOrNull()
  val reducedLayerNames = metadata["contextBudgetReducedLayerNames"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
  val omittedLayerNames = metadata["contextBudgetOmittedLayerNames"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
  val layers = parseContextBudgetLayers(metadata["contextBudgetLayerDetails"].orEmpty())
  val layerSummary = metadata["contextBudgetLayerSummary"]?.takeIf(String::isNotBlank)
  if (
    applied == null &&
    pressureMode == null &&
    selectedPreset == null &&
    effectivePreset == null &&
    presetSource == null &&
    presetDiverged == null &&
    sourcePreset == null &&
    sourceTranscriptMaxMessages == null &&
    sourceInjectedMemoryMaxRecords == null &&
    sourceMemoryRecallMaxRecords == null &&
    sourceBootstrapMaxChars == null &&
    sourceSkillInventoryMaxSkills == null &&
    sourceActiveSkillMaxChars == null &&
    sourceRecentObservationMaxEntries == null &&
    sourceMemoryFlushMaxToolObservations == null &&
    contextWindowTokens == null &&
    reservedOutputTokens == null &&
    safetyMarginTokens == null &&
    hardInputTokens == null &&
    targetInputTokens == null &&
    emergencyInputTokens == null &&
    unresolvedOverflow == null &&
    fullLayerCount == null &&
    compactLayerCount == null &&
    minimalLayerCount == null &&
    omittedLayerCount == null &&
    reducedLayerNames.isEmpty() &&
    omittedLayerNames.isEmpty() &&
    layers.isEmpty() &&
    layerSummary == null
  ) {
    return null
  }
  return buildMap {
    applied?.let { put("applied", it) }
    pressureMode?.let { put("pressureMode", it) }
    selectedPreset?.let { put("selectedPreset", it) }
    effectivePreset?.let { put("effectivePreset", it) }
    presetSource?.let { put("presetSource", it) }
    presetDiverged?.let { put("presetDiverged", it) }
    sourcePreset?.let { put("sourcePreset", it) }
    sourceTranscriptMaxMessages?.let { put("sourceTranscriptMaxMessages", it) }
    sourceInjectedMemoryMaxRecords?.let { put("sourceInjectedMemoryMaxRecords", it) }
    sourceMemoryRecallMaxRecords?.let { put("sourceMemoryRecallMaxRecords", it) }
    sourceBootstrapMaxChars?.let { put("sourceBootstrapMaxChars", it) }
    sourceSkillInventoryMaxSkills?.let { put("sourceSkillInventoryMaxSkills", it) }
    sourceActiveSkillMaxChars?.let { put("sourceActiveSkillMaxChars", it) }
    sourceRecentObservationMaxEntries?.let { put("sourceRecentObservationMaxEntries", it) }
    sourceMemoryFlushMaxToolObservations?.let { put("sourceMemoryFlushMaxToolObservations", it) }
    contextWindowTokens?.let { put("contextWindowTokens", it) }
    reservedOutputTokens?.let { put("reservedOutputTokens", it) }
    safetyMarginTokens?.let { put("safetyMarginTokens", it) }
    hardInputTokens?.let { put("hardInputTokens", it) }
    targetInputTokens?.let { put("targetInputTokens", it) }
    emergencyInputTokens?.let { put("emergencyInputTokens", it) }
    unresolvedOverflow?.let { put("unresolvedOverflow", it) }
    fullLayerCount?.let { put("fullLayerCount", it) }
    compactLayerCount?.let { put("compactLayerCount", it) }
    minimalLayerCount?.let { put("minimalLayerCount", it) }
    omittedLayerCount?.let { put("omittedLayerCount", it) }
    if (reducedLayerNames.isNotEmpty()) {
      put("reducedLayerNames", reducedLayerNames)
    }
    if (omittedLayerNames.isNotEmpty()) {
      put("omittedLayerNames", omittedLayerNames)
    }
    if (layers.isNotEmpty()) {
      put("layers", layers)
    }
    layerSummary?.let { put("layerSummary", it) }
  }
}

private fun parseContextBudgetLayers(summary: String): List<Map<String, Any?>> {
  val normalized = summary.trim()
  if (normalized.isEmpty()) {
    return emptyList()
  }
  val payload = runCatching { snapshotReplayJson.parseToJsonElement(normalized) }.getOrNull() as? JsonArray
    ?: return emptyList()
  return payload.mapNotNull { element ->
    val layer = element as? JsonObject ?: return@mapNotNull null
    val id = layer.stringValue("id") ?: return@mapNotNull null
    val name = layer.stringValue("name") ?: id
    buildMap {
      put("id", id)
      put("name", name)
      layer.stringValue("priorityClass")?.let { put("priorityClass", it) }
      layer.intValue("retentionPriority")?.let { put("retentionPriority", it) }
      layer.intValue("estimatedTokensBefore")?.let { put("estimatedTokensBefore", it) }
      layer.intValue("estimatedTokensAfter")?.let { put("estimatedTokensAfter", it) }
      layer.stringValue("finalState")?.let { put("finalState", it) }
      layer.booleanValue("omitted")?.let { put("omitted", it) }
      layer.booleanValue("reduced")?.let { put("reduced", it) }
      val appliedOperators = layer.stringArray("appliedOperators")
      if (appliedOperators.isNotEmpty()) {
        put("appliedOperators", appliedOperators)
      }
    }
  }
}

private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)
  ?.content
  ?.trim()
  ?.takeIf(String::isNotBlank)

private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)
  ?.content
  ?.trim()
  ?.toIntOrNull()

private fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)
  ?.content
  ?.trim()
  ?.toBooleanStrictOrNull()

private fun JsonObject.stringArray(key: String): List<String> = (this[key] as? JsonArray)
  ?.mapNotNull { element ->
    (element as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)
  }
  ?: emptyList()

internal fun memoryFlushFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val outcome = metadata["contextMemoryFlushOutcome"]?.takeIf(String::isNotBlank)
  val triggerStage = metadata["contextMemoryFlushTriggerStage"]?.takeIf(String::isNotBlank)
  val maintenanceTask = metadata["contextMemoryFlushMaintenanceTask"]?.takeIf(String::isNotBlank)
  val executionMode = metadata["contextMemoryFlushExecutionMode"]?.takeIf(String::isNotBlank)
  val contextWindowTokens = metadata["contextMemoryFlushContextWindowTokens"]?.toIntOrNull()
  val previousContextWindowTokens =
    metadata["contextMemoryFlushPreviousContextWindowTokens"]?.toIntOrNull()
  val autoCompactTokenLimit = metadata["contextMemoryFlushAutoCompactTokenLimit"]?.toIntOrNull()
  val estimatedReplayTokens = metadata["contextMemoryFlushEstimatedReplayTokens"]?.toIntOrNull()
  val tokenThresholdTriggered = metadata["contextMemoryFlushTokenThresholdTriggered"]?.toBooleanStrictOrNull()
  val smallerWindowModelSwitchDetected =
    metadata["contextMemoryFlushSmallerWindowModelSwitchDetected"]?.toBooleanStrictOrNull()
  val omittedMessageCount = metadata["contextMemoryFlushOmittedMessageCount"]?.toIntOrNull()
  val omittedCharCount = metadata["contextMemoryFlushOmittedCharCount"]?.toIntOrNull()
  val signature = metadata["contextMemoryFlushSignature"]?.takeIf(String::isNotBlank)
  val candidateCount = metadata["contextMemoryFlushCandidateCount"]?.toIntOrNull()
  val writtenRecordCount = metadata["contextMemoryFlushWrittenRecordCount"]?.toIntOrNull()
  val writtenKinds = metadata["contextMemoryFlushWrittenKinds"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
  val writtenRecordIds = metadata["contextMemoryFlushWrittenRecordIds"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
  val candidateRecordIds = metadata["contextMemoryFlushCandidateRecordIds"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
  if (
    outcome == null &&
    triggerStage == null &&
    maintenanceTask == null &&
    executionMode == null &&
    contextWindowTokens == null &&
    previousContextWindowTokens == null &&
    autoCompactTokenLimit == null &&
    estimatedReplayTokens == null &&
    tokenThresholdTriggered == null &&
    smallerWindowModelSwitchDetected == null &&
    omittedMessageCount == null &&
    omittedCharCount == null &&
    signature == null &&
    candidateCount == null &&
    writtenRecordCount == null &&
    writtenKinds.isEmpty() &&
    writtenRecordIds.isEmpty() &&
    candidateRecordIds.isEmpty()
  ) {
    return null
  }
  return buildMap {
    outcome?.let { put("outcome", it) }
    triggerStage?.let { put("triggerStage", it) }
    maintenanceTask?.let { put("maintenanceTask", it) }
    executionMode?.let { put("executionMode", it) }
    contextWindowTokens?.let { put("contextWindowTokens", it) }
    previousContextWindowTokens?.let { put("previousContextWindowTokens", it) }
    autoCompactTokenLimit?.let { put("autoCompactTokenLimit", it) }
    estimatedReplayTokens?.let { put("estimatedReplayTokens", it) }
    tokenThresholdTriggered?.let { put("tokenThresholdTriggered", it) }
    smallerWindowModelSwitchDetected?.let { put("smallerWindowModelSwitchDetected", it) }
    omittedMessageCount?.let { put("omittedMessageCount", it) }
    omittedCharCount?.let { put("omittedCharCount", it) }
    signature?.let { put("signature", it) }
    candidateCount?.let { put("candidateCount", it) }
    writtenRecordCount?.let { put("writtenRecordCount", it) }
    if (writtenKinds.isNotEmpty()) {
      put("writtenKinds", writtenKinds)
    }
    if (writtenRecordIds.isNotEmpty()) {
      put("writtenRecordIds", writtenRecordIds)
    }
    if (candidateRecordIds.isNotEmpty()) {
      put("candidateRecordIds", candidateRecordIds)
    }
  }
}

internal fun stickyMemoryFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val injectedRecordCount = metadata["contextStickyMemoryInjectedRecordCount"]?.toIntOrNull()
  val omittedRecordCount = metadata["contextStickyMemoryOmittedRecordCount"]?.toIntOrNull()
  val recordIds = metadata["contextStickyMemoryRecordIds"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
  if (injectedRecordCount == null && omittedRecordCount == null && recordIds.isEmpty()) {
    return null
  }
  return buildMap {
    injectedRecordCount?.let { put("injectedRecordCount", it) }
    omittedRecordCount?.let { put("omittedRecordCount", it) }
    if (recordIds.isNotEmpty()) {
      put("recordIds", recordIds)
    }
  }
}

internal fun bootstrapFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val mode = metadata["contextBootstrapMode"]?.takeIf(String::isNotBlank)
  val visibleFileCount = metadata["contextBootstrapVisibleFileCount"]?.toIntOrNull()
  val injectedFileCount = metadata["contextBootstrapInjectedFileCount"]?.toIntOrNull()
  val omittedFileCount = metadata["contextBootstrapOmittedFileCount"]?.toIntOrNull()
  val truncatedFileCount = metadata["contextBootstrapTruncatedFileCount"]?.toIntOrNull()
  val files = parseBootstrapFileTrace(metadata["contextBootstrapFileSummary"].orEmpty())
  if (
    mode == null &&
    visibleFileCount == null &&
    injectedFileCount == null &&
    omittedFileCount == null &&
    truncatedFileCount == null &&
    files.isEmpty()
  ) {
    return null
  }
  return buildMap {
    mode?.let { put("mode", it) }
    visibleFileCount?.let { put("visibleFileCount", it) }
    injectedFileCount?.let { put("injectedFileCount", it) }
    omittedFileCount?.let { put("omittedFileCount", it) }
    truncatedFileCount?.let { put("truncatedFileCount", it) }
    if (files.isNotEmpty()) {
      put("files", files)
    }
  }
}

internal fun skillInventoryFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val visibleCount = metadata["contextVisibleSkillCount"]?.toIntOrNull()
  val injectedCount = metadata["contextInjectedSkillCount"]?.toIntOrNull()
  val omittedCount = metadata["contextOmittedSkillCount"]?.toIntOrNull()
  val implicitCount = metadata["contextImplicitSkillCount"]?.toIntOrNull()
  val invalidCount = metadata["contextInvalidSkillCount"]?.toIntOrNull()
  val omittedTraceCount = metadata["contextVisibleSkillTraceOmittedCount"]?.toIntOrNull()
  val skills = parseVisibleSkillTrace(metadata["contextVisibleSkillSummary"].orEmpty())
  if (
    visibleCount == null &&
    injectedCount == null &&
    omittedCount == null &&
    implicitCount == null &&
    invalidCount == null &&
    omittedTraceCount == null &&
    skills.isEmpty()
  ) {
    return null
  }
  return buildMap {
    visibleCount?.let { put("visibleSkillCount", it) }
    injectedCount?.let { put("injectedSkillCount", it) }
    omittedCount?.let { put("omittedSkillCount", it) }
    implicitCount?.let { put("implicitSkillCount", it) }
    invalidCount?.let { put("invalidSkillCount", it) }
    omittedTraceCount?.let { put("omittedTraceSkillCount", it) }
    if (skills.isNotEmpty()) {
      put("skills", skills)
    }
  }
}

internal fun durableCompactionFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val compactedThisRun = metadata["contextDurableCompactionCompactedThisRun"]?.toBooleanStrictOrNull()
  val triggerStage = metadata["contextDurableCompactionTriggerStage"]?.takeIf(String::isNotBlank)
  val maintenanceTask = metadata["contextDurableCompactionMaintenanceTask"]?.takeIf(String::isNotBlank)
  val executionMode = metadata["contextDurableCompactionExecutionMode"]?.takeIf(String::isNotBlank)
  val contextWindowTokens =
    metadata["contextDurableCompactionContextWindowTokens"]?.toIntOrNull()
  val previousContextWindowTokens =
    metadata["contextDurableCompactionPreviousContextWindowTokens"]?.toIntOrNull()
  val autoCompactTokenLimit =
    metadata["contextDurableCompactionAutoCompactTokenLimit"]?.toIntOrNull()
  val estimatedReplayTokens =
    metadata["contextDurableCompactionEstimatedReplayTokens"]?.toIntOrNull()
  val tokenThresholdTriggered =
    metadata["contextDurableCompactionTokenThresholdTriggered"]?.toBooleanStrictOrNull()
  val smallerWindowModelSwitchDetected =
    metadata["contextDurableCompactionSmallerWindowModelSwitchDetected"]?.toBooleanStrictOrNull()
  val sourceTranscriptMessageCount =
    metadata["contextDurableCompactionSourceTranscriptMessageCount"]?.toIntOrNull()
  val retainedTranscriptMessageCount =
    metadata["contextDurableCompactionRetainedTranscriptMessageCount"]?.toIntOrNull()
  val latestCompactedMessageCount =
    metadata["contextDurableCompactionLatestMessageCount"]?.toIntOrNull()
  val includedSummaryCount =
    metadata["contextDurableCompactionIncludedSummaryCount"]?.toIntOrNull()
  val omittedSummaryCount =
    metadata["contextDurableCompactionOmittedSummaryCount"]?.toIntOrNull()
  val totalCompactedMessageCount =
    metadata["contextDurableCompactionTotalCompactedMessageCount"]?.toIntOrNull()
  val latestCompactedAtEpochMs =
    metadata["contextDurableCompactionLatestAtEpochMs"]?.toLongOrNull()
  val entries = parseDurableCompactionEntryTrace(
    metadata["contextDurableCompactionEntryTraceSummary"].orEmpty(),
  )
  val remoteCompaction = remoteCompactionFromMetadata(metadata)
  val totalSummaryCount = if (includedSummaryCount != null || omittedSummaryCount != null) {
    (includedSummaryCount ?: 0) + (omittedSummaryCount ?: 0)
  } else {
    null
  }
  if (
    compactedThisRun == null &&
    triggerStage == null &&
    maintenanceTask == null &&
    executionMode == null &&
    contextWindowTokens == null &&
    previousContextWindowTokens == null &&
    autoCompactTokenLimit == null &&
    estimatedReplayTokens == null &&
    tokenThresholdTriggered == null &&
    smallerWindowModelSwitchDetected == null &&
    sourceTranscriptMessageCount == null &&
    retainedTranscriptMessageCount == null &&
    latestCompactedMessageCount == null &&
    includedSummaryCount == null &&
    omittedSummaryCount == null &&
    totalCompactedMessageCount == null &&
    latestCompactedAtEpochMs == null &&
    entries.isEmpty() &&
    remoteCompaction == null
  ) {
    return null
  }
  return buildMap {
    compactedThisRun?.let { put("compactedThisRun", it) }
    triggerStage?.let { put("triggerStage", it) }
    maintenanceTask?.let { put("maintenanceTask", it) }
    executionMode?.let { put("executionMode", it) }
    contextWindowTokens?.let { put("contextWindowTokens", it) }
    previousContextWindowTokens?.let { put("previousContextWindowTokens", it) }
    autoCompactTokenLimit?.let { put("autoCompactTokenLimit", it) }
    estimatedReplayTokens?.let { put("estimatedReplayTokens", it) }
    tokenThresholdTriggered?.let { put("tokenThresholdTriggered", it) }
    smallerWindowModelSwitchDetected?.let { put("smallerWindowModelSwitchDetected", it) }
    sourceTranscriptMessageCount?.let { put("sourceTranscriptMessageCount", it) }
    retainedTranscriptMessageCount?.let { put("retainedTranscriptMessageCount", it) }
    latestCompactedMessageCount?.let { put("latestCompactedMessageCount", it) }
    includedSummaryCount?.let { put("includedSummaryCount", it) }
    omittedSummaryCount?.let { put("omittedSummaryCount", it) }
    totalCompactedMessageCount?.let { put("totalCompactedMessageCount", it) }
    totalSummaryCount?.let { put("totalSummaryCount", it) }
    latestCompactedAtEpochMs?.let { put("latestCompactedAtEpochMs", it) }
    if (entries.isNotEmpty()) {
      put("entries", entries)
    }
    remoteCompaction?.let { put("remoteCompaction", it) }
  }
}

internal fun remoteCompactionFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val requested = metadata["responsesRemoteCompactionRequested"]?.toBooleanStrictOrNull()
  val supported = metadata["responsesRemoteCompactionSupported"]?.toBooleanStrictOrNull()
  val used = metadata["responsesRemoteCompactionUsed"]?.toBooleanStrictOrNull()
  val triggerStage = metadata["responsesRemoteCompactionTriggerStage"]?.takeIf(String::isNotBlank)
  val fallbackReason = metadata["responsesRemoteCompactionFallbackReason"]?.takeIf(String::isNotBlank)
  val outputItemCount = metadata["responsesRemoteCompactionOutputItemCount"]?.toIntOrNull()
  val compactionItemCount = metadata["responsesRemoteCompactionItemCount"]?.toIntOrNull()
  val encryptedContentCount = metadata["responsesRemoteCompactionEncryptedContentCount"]?.toIntOrNull()
  if (
    requested == null &&
    supported == null &&
    used == null &&
    triggerStage == null &&
    fallbackReason == null &&
    outputItemCount == null &&
    compactionItemCount == null &&
    encryptedContentCount == null
  ) {
    return null
  }
  return buildMap {
    requested?.let { put("requested", it) }
    supported?.let { put("supported", it) }
    used?.let { put("used", it) }
    triggerStage?.let { put("triggerStage", it) }
    fallbackReason?.let { put("fallbackReason", it) }
    outputItemCount?.let { put("outputItemCount", it) }
    compactionItemCount?.let { put("compactionItemCount", it) }
    encryptedContentCount?.let { put("encryptedContentCount", it) }
  }
}

internal fun activeSkillFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
  val name = metadata["contextActiveSkillName"]?.takeIf(String::isNotBlank)
  val relativePath = metadata["contextActiveSkillRelativePath"]?.takeIf(String::isNotBlank)
  val invocationControl = metadata["contextActiveSkillInvocationControl"]?.takeIf(String::isNotBlank)
  val executionContext = metadata["contextActiveSkillExecutionContext"]?.takeIf(String::isNotBlank)
  val activationSource = metadata["contextActiveSkillActivationSource"]?.takeIf(String::isNotBlank)
  val pinned = metadata["contextActiveSkillPinned"]?.toBooleanStrictOrNull()
  val toolRestrictionEnabled = metadata["contextActiveSkillToolRestrictionEnabled"]?.toBooleanStrictOrNull()
  val truncated = metadata["contextActiveSkillTruncated"]?.toBooleanStrictOrNull()
  val allowedToolKeys = metadata["contextActiveSkillAllowedTools"]
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
  if (
    name == null &&
    relativePath == null &&
    invocationControl == null &&
    executionContext == null &&
    activationSource == null &&
    pinned == null &&
    toolRestrictionEnabled == null &&
    truncated == null &&
    allowedToolKeys.isEmpty()
  ) {
    return null
  }
  return buildMap {
    name?.let { put("name", it) }
    relativePath?.let { put("relativePath", it) }
    invocationControl?.let { put("invocationControl", it) }
    executionContext?.let { put("executionContext", it) }
    activationSource?.let { put("activationSource", it) }
    pinned?.let { put("pinned", it) }
    toolRestrictionEnabled?.let { put("toolRestrictionEnabled", it) }
    truncated?.let { put("truncated", it) }
    if (allowedToolKeys.isNotEmpty()) {
      put("allowedToolKeys", allowedToolKeys)
    }
  }
}

internal fun parseBootstrapFileTrace(raw: String): List<Map<String, Any?>> = raw
  .split(';')
  .map(String::trim)
  .filter(String::isNotBlank)
  .mapNotNull { token ->
    val match = BOOTSTRAP_FILE_TRACE_REGEX.matchEntire(token) ?: return@mapNotNull null
    mapOf(
      "name" to match.groupValues[1],
      "relativePath" to match.groupValues[2],
      "sourceCharCount" to match.groupValues[3].toIntOrNull(),
      "injectedCharCount" to match.groupValues[4].toIntOrNull(),
      "truncated" to match.groupValues[5].toBooleanStrictOrNull(),
    )
  }

internal fun parseSelectedMemoryTrace(raw: String): List<Map<String, Any?>> = raw
  .split(';')
  .map(String::trim)
  .filter(String::isNotBlank)
  .mapNotNull { token ->
    val match = MEMORY_SELECTED_TRACE_REGEX.matchEntire(token) ?: return@mapNotNull null
    val matchedTerms = match.groupValues[3]
      .split('|')
      .map(String::trim)
      .filter(String::isNotBlank)
    mapOf(
      "id" to match.groupValues[1],
      "score" to match.groupValues[2].toIntOrNull(),
      "matchedTerms" to matchedTerms,
    )
  }

internal fun parseOmittedMemoryTrace(raw: String): List<Map<String, Any?>> = raw
  .split(';')
  .map(String::trim)
  .filter(String::isNotBlank)
  .mapNotNull { token ->
    val id = token.substringBefore(':').trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
    val reason = token.substringAfter(':', missingDelimiterValue = "").trim().takeIf(String::isNotBlank)
      ?: return@mapNotNull null
    mapOf(
      "id" to id,
      "reason" to reason,
    )
  }

internal fun parseDurableCompactionEntryTrace(raw: String): List<Map<String, Any?>> = raw
  .split(';')
  .map(String::trim)
  .filter(String::isNotBlank)
  .mapNotNull { token ->
    val parts = token.split('|')
    if (parts.size < 6) {
      return@mapNotNull null
    }
    val compactedMessageCount = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
    val omittedUserMessageCount = parts[1].trim().toIntOrNull() ?: 0
    val omittedAssistantMessageCount = parts[2].trim().toIntOrNull() ?: 0
    val omittedToolMessageCount = parts[3].trim().toIntOrNull() ?: 0
    val omittedSystemMessageCount = parts[4].trim().toIntOrNull() ?: 0
    val compactedAtEpochMs = parts[5].trim().toLongOrNull()?.takeIf { value -> value > 0L }
    buildMap {
      put("compactedMessageCount", compactedMessageCount)
      put("omittedUserMessageCount", omittedUserMessageCount)
      put("omittedAssistantMessageCount", omittedAssistantMessageCount)
      put("omittedToolMessageCount", omittedToolMessageCount)
      put("omittedSystemMessageCount", omittedSystemMessageCount)
      compactedAtEpochMs?.let { put("compactedAtEpochMs", it) }
    }
  }

internal fun parseVisibleSkillTrace(raw: String): List<Map<String, Any?>> = raw
  .split(';')
  .map(String::trim)
  .filter(String::isNotBlank)
  .mapNotNull { token ->
    val match = VISIBLE_SKILL_TRACE_REGEX.matchEntire(token) ?: return@mapNotNull null
    mapOf(
      "name" to match.groupValues[1],
      "relativePath" to match.groupValues[2],
      "invocationControl" to match.groupValues[3],
      "userInvocable" to match.groupValues[4].toBooleanStrictOrNull(),
      "executionContext" to match.groupValues[5],
    )
  }

internal fun parseFilteredMemoryCounts(raw: String): Map<String, Int> = raw
  .split(',')
  .map(String::trim)
  .filter(String::isNotBlank)
  .mapNotNull { token ->
    val reason = token.substringBefore(':').trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
    val count = token.substringAfter(':', missingDelimiterValue = "").trim().toIntOrNull()
      ?: return@mapNotNull null
    reason to count
  }
  .toMap(linkedMapOf())

internal fun runIdFor(task: AgentTask): String =
  task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
    ?.takeIf(String::isNotBlank)
    ?: task.id
