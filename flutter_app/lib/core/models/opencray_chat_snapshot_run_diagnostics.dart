import 'opencray_chat_snapshot_core.dart';
import 'opencray_chat_snapshot_events.dart';
import 'opencray_chat_snapshot_run_context.dart';
import 'opencray_chat_snapshot_run_memory.dart';
import 'opencray_chat_snapshot_runtime.dart';

class OpenCrayHostLifecycleSnapshot {
  const OpenCrayHostLifecycleSnapshot({
    this.processStartId,
    this.processStartedAtEpochMs,
    this.hostInstanceId,
    this.runtimeOwnerId,
    this.hostCreatedAtEpochMs,
  });

  final String? processStartId;
  final int? processStartedAtEpochMs;
  final String? hostInstanceId;
  final String? runtimeOwnerId;
  final int? hostCreatedAtEpochMs;

  factory OpenCrayHostLifecycleSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayHostLifecycleSnapshot(
      processStartId: map['processStartId'] as String?,
      processStartedAtEpochMs: map['processStartedAtEpochMs'] as int?,
      hostInstanceId: map['hostInstanceId'] as String?,
      runtimeOwnerId: map['runtimeOwnerId'] as String?,
      hostCreatedAtEpochMs: map['hostCreatedAtEpochMs'] as int?,
    );
  }
}

class OpenCrayChatRunDiagnosticsSnapshot {
  const OpenCrayChatRunDiagnosticsSnapshot({
    this.processStartId,
    this.hostInstanceId,
    this.runtimeOwnerId,
    this.submissionSource,
    this.recoveryReason,
    this.queueRestoreEpochMs,
    this.previousLifecycleState,
    this.restoredFromDurableStore,
  });

  final String? processStartId;
  final String? hostInstanceId;
  final String? runtimeOwnerId;
  final String? submissionSource;
  final String? recoveryReason;
  final int? queueRestoreEpochMs;
  final String? previousLifecycleState;
  final bool? restoredFromDurableStore;

  factory OpenCrayChatRunDiagnosticsSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunDiagnosticsSnapshot(
      processStartId: map['processStartId'] as String?,
      hostInstanceId: map['hostInstanceId'] as String?,
      runtimeOwnerId: map['runtimeOwnerId'] as String?,
      submissionSource: map['submissionSource'] as String?,
      recoveryReason: map['recoveryReason'] as String?,
      queueRestoreEpochMs: map['queueRestoreEpochMs'] as int?,
      previousLifecycleState: map['previousLifecycleState'] as String?,
      restoredFromDurableStore: map['restoredFromDurableStore'] as bool?,
    );
  }
}

class OpenCrayChatRunRecoveryPlanSnapshot {
  const OpenCrayChatRunRecoveryPlanSnapshot({
    this.action,
    this.reasonCode,
    this.summary,
    this.safeToAutoResume,
    this.requiresUserAction,
    this.checkpointKind,
    this.approvalState,
    this.journalTailKind,
  });

  final String? action;
  final String? reasonCode;
  final String? summary;
  final bool? safeToAutoResume;
  final bool? requiresUserAction;
  final String? checkpointKind;
  final String? approvalState;
  final String? journalTailKind;

  factory OpenCrayChatRunRecoveryPlanSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunRecoveryPlanSnapshot(
      action: map['action'] as String?,
      reasonCode: map['reasonCode'] as String?,
      summary: map['summary'] as String?,
      safeToAutoResume: map['safeToAutoResume'] as bool?,
      requiresUserAction: map['requiresUserAction'] as bool?,
      checkpointKind: map['checkpointKind'] as String?,
      approvalState: map['approvalState'] as String?,
      journalTailKind: map['journalTailKind'] as String?,
    );
  }
}

class OpenCrayChatRunLlmDiagnosticsSnapshot {
  const OpenCrayChatRunLlmDiagnosticsSnapshot({
    this.nativeToolCallRequested,
    this.providerResponseShape,
    this.nativeToolCallObserved,
    this.parsedToolCallObserved,
    this.fallbackParserAttempted,
    this.fallbackParserSucceeded,
    this.responsesContinuationRecoveryCount,
    this.responsesContinuationRecoveryLastReason,
    this.localContinuationUsedCount,
    this.localContinuationFallbackCount,
    this.localContinuationLastMode,
    this.localContinuationLastReason,
    this.responsesPendingContextUpdateCount,
    this.responsesPendingContextUpdateHash,
    this.toolCallEventEmitted,
    this.toolResultEventEmitted,
    this.contextCacheBreakReason,
    this.lastSuccessfulToolName,
  });

  final bool? nativeToolCallRequested;
  final String? providerResponseShape;
  final bool? nativeToolCallObserved;
  final bool? parsedToolCallObserved;
  final bool? fallbackParserAttempted;
  final bool? fallbackParserSucceeded;
  final int? responsesContinuationRecoveryCount;
  final String? responsesContinuationRecoveryLastReason;
  final int? localContinuationUsedCount;
  final int? localContinuationFallbackCount;
  final String? localContinuationLastMode;
  final String? localContinuationLastReason;
  final int? responsesPendingContextUpdateCount;
  final String? responsesPendingContextUpdateHash;
  final bool? toolCallEventEmitted;
  final bool? toolResultEventEmitted;
  final String? contextCacheBreakReason;
  final String? lastSuccessfulToolName;

  factory OpenCrayChatRunLlmDiagnosticsSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunLlmDiagnosticsSnapshot(
      nativeToolCallRequested: map['nativeToolCallRequested'] as bool?,
      providerResponseShape: map['providerResponseShape'] as String?,
      nativeToolCallObserved: map['nativeToolCallObserved'] as bool?,
      parsedToolCallObserved: map['parsedToolCallObserved'] as bool?,
      fallbackParserAttempted: map['fallbackParserAttempted'] as bool?,
      fallbackParserSucceeded: map['fallbackParserSucceeded'] as bool?,
      responsesContinuationRecoveryCount:
          map['responsesContinuationRecoveryCount'] as int?,
      responsesContinuationRecoveryLastReason:
          map['responsesContinuationRecoveryLastReason'] as String?,
      localContinuationUsedCount: map['localContinuationUsedCount'] as int?,
      localContinuationFallbackCount:
          map['localContinuationFallbackCount'] as int?,
      localContinuationLastMode: map['localContinuationLastMode'] as String?,
      localContinuationLastReason:
          map['localContinuationLastReason'] as String?,
      responsesPendingContextUpdateCount:
          map['responsesPendingContextUpdateCount'] as int?,
      responsesPendingContextUpdateHash:
          map['responsesPendingContextUpdateHash'] as String?,
      toolCallEventEmitted: map['toolCallEventEmitted'] as bool?,
      toolResultEventEmitted: map['toolResultEventEmitted'] as bool?,
      contextCacheBreakReason: map['contextCacheBreakReason'] as String?,
      lastSuccessfulToolName: map['lastSuccessfulToolName'] as String?,
    );
  }
}

class OpenCrayChatRunSnapshot {
  const OpenCrayChatRunSnapshot({
    required this.sessionId,
    required this.runId,
    required this.taskId,
    required this.acceptedAtEpochMs,
    required this.updatedAtEpochMs,
    required this.attempt,
    required this.isTerminal,
    this.executionOrdinal = 0,
    this.executionId,
    this.executionKind,
    this.pendingExecutionKind,
    this.lifecycleState,
    this.taskState,
    this.executionStatus,
    this.errorCode,
    this.errorMessage,
    this.responseFormat,
    this.pendingMessageId,
    this.finalAttachments = const <OpenCrayChatAttachmentSnapshot>[],
    this.managedProcessIds = const <String>[],
    this.managedProcesses = const <OpenCrayChatManagedProcessSnapshot>[],
    this.runningManagedProcessCount = 0,
    this.hasLiveManagedProcesses = false,
    this.lastEvent,
    this.llmDiagnostics,
    this.liveContext,
    this.contextBudget,
    this.memoryTrace,
    this.stickyMemory,
    this.memoryFlush,
    this.bootstrap,
    this.durableCompaction,
    this.skillInventory,
    this.activeSkill,
    this.diagnostics,
    this.recoveryPlan,
  });

  final String sessionId;
  final String runId;
  final String taskId;
  final int acceptedAtEpochMs;
  final int updatedAtEpochMs;
  final String? lifecycleState;
  final String? taskState;
  final int attempt;
  final int executionOrdinal;
  final String? executionId;
  final String? executionKind;
  final String? pendingExecutionKind;
  final String? executionStatus;
  final String? errorCode;
  final String? errorMessage;
  final String? responseFormat;
  final String? pendingMessageId;
  final List<OpenCrayChatAttachmentSnapshot> finalAttachments;
  final List<String> managedProcessIds;
  final List<OpenCrayChatManagedProcessSnapshot> managedProcesses;
  final int runningManagedProcessCount;
  final bool hasLiveManagedProcesses;
  final bool isTerminal;
  final OpenCrayChatRuntimeEventSnapshot? lastEvent;
  final OpenCrayChatRunLlmDiagnosticsSnapshot? llmDiagnostics;
  final OpenCrayChatRunLiveContextSnapshot? liveContext;
  final OpenCrayChatRunContextBudgetSnapshot? contextBudget;
  final OpenCrayChatRunMemoryTraceSnapshot? memoryTrace;
  final OpenCrayChatRunStickyMemorySnapshot? stickyMemory;
  final OpenCrayChatRunMemoryFlushSnapshot? memoryFlush;
  final OpenCrayChatRunBootstrapSnapshot? bootstrap;
  final OpenCrayChatRunDurableCompactionSnapshot? durableCompaction;
  final OpenCrayChatRunSkillInventorySnapshot? skillInventory;
  final OpenCrayChatRunActiveSkillSnapshot? activeSkill;
  final OpenCrayChatRunDiagnosticsSnapshot? diagnostics;
  final OpenCrayChatRunRecoveryPlanSnapshot? recoveryPlan;

  factory OpenCrayChatRunSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawLastEvent = map['lastEvent'];
    final rawLlmDiagnostics = map['llmDiagnostics'];
    final rawLiveContext = map['liveContext'];
    final rawContextBudget = map['contextBudget'];
    final rawMemoryTrace = map['memoryTrace'];
    final rawStickyMemory = map['stickyMemory'];
    final rawMemoryFlush = map['memoryFlush'];
    final rawBootstrap = map['bootstrap'];
    final rawDurableCompaction = map['durableCompaction'];
    final rawSkillInventory = map['skillInventory'];
    final rawActiveSkill = map['activeSkill'];
    final rawDiagnostics = map['diagnostics'];
    final rawRecoveryPlan = map['recoveryPlan'];
    final rawFinalAttachments =
        map['finalAttachments'] as List<Object?>? ?? const <Object?>[];
    final rawManagedProcessIds =
        map['managedProcessIds'] as List<Object?>? ?? const <Object?>[];
    final rawManagedProcesses =
        map['managedProcesses'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatRunSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      acceptedAtEpochMs: map['acceptedAtEpochMs'] as int? ?? 0,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      lifecycleState: map['lifecycleState'] as String?,
      taskState: map['taskState'] as String?,
      attempt: map['attempt'] as int? ?? 0,
      executionOrdinal: map['executionOrdinal'] as int? ?? 0,
      executionId: map['executionId'] as String?,
      executionKind: map['executionKind'] as String?,
      pendingExecutionKind: map['pendingExecutionKind'] as String?,
      executionStatus: map['executionStatus'] as String?,
      errorCode: map['errorCode'] as String?,
      errorMessage: map['errorMessage'] as String?,
      responseFormat: map['responseFormat'] as String?,
      pendingMessageId: map['pendingMessageId'] as String?,
      finalAttachments: rawFinalAttachments
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatAttachmentSnapshot.fromMap)
          .toList(growable: false),
      managedProcessIds: rawManagedProcessIds.whereType<String>().toList(
        growable: false,
      ),
      managedProcesses: rawManagedProcesses
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatManagedProcessSnapshot.fromMap)
          .toList(growable: false),
      runningManagedProcessCount:
          map['runningManagedProcessCount'] as int? ?? 0,
      hasLiveManagedProcesses: map['hasLiveManagedProcesses'] as bool? ?? false,
      isTerminal: map['isTerminal'] as bool? ?? false,
      lastEvent: rawLastEvent is Map<Object?, Object?>
          ? OpenCrayChatRuntimeEventSnapshot.fromMap(rawLastEvent)
          : null,
      llmDiagnostics: rawLlmDiagnostics is Map<Object?, Object?>
          ? OpenCrayChatRunLlmDiagnosticsSnapshot.fromMap(rawLlmDiagnostics)
          : null,
      liveContext: rawLiveContext is Map<Object?, Object?>
          ? OpenCrayChatRunLiveContextSnapshot.fromMap(rawLiveContext)
          : null,
      contextBudget: rawContextBudget is Map<Object?, Object?>
          ? OpenCrayChatRunContextBudgetSnapshot.fromMap(rawContextBudget)
          : null,
      memoryTrace: rawMemoryTrace is Map<Object?, Object?>
          ? OpenCrayChatRunMemoryTraceSnapshot.fromMap(rawMemoryTrace)
          : null,
      stickyMemory: rawStickyMemory is Map<Object?, Object?>
          ? OpenCrayChatRunStickyMemorySnapshot.fromMap(rawStickyMemory)
          : null,
      memoryFlush: rawMemoryFlush is Map<Object?, Object?>
          ? OpenCrayChatRunMemoryFlushSnapshot.fromMap(rawMemoryFlush)
          : null,
      bootstrap: rawBootstrap is Map<Object?, Object?>
          ? OpenCrayChatRunBootstrapSnapshot.fromMap(rawBootstrap)
          : null,
      durableCompaction: rawDurableCompaction is Map<Object?, Object?>
          ? OpenCrayChatRunDurableCompactionSnapshot.fromMap(
              rawDurableCompaction,
            )
          : null,
      skillInventory: rawSkillInventory is Map<Object?, Object?>
          ? OpenCrayChatRunSkillInventorySnapshot.fromMap(rawSkillInventory)
          : null,
      activeSkill: rawActiveSkill is Map<Object?, Object?>
          ? OpenCrayChatRunActiveSkillSnapshot.fromMap(rawActiveSkill)
          : null,
      diagnostics: rawDiagnostics is Map<Object?, Object?>
          ? OpenCrayChatRunDiagnosticsSnapshot.fromMap(rawDiagnostics)
          : null,
      recoveryPlan: rawRecoveryPlan is Map<Object?, Object?>
          ? OpenCrayChatRunRecoveryPlanSnapshot.fromMap(rawRecoveryPlan)
          : null,
    );
  }
}
