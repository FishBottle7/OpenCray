part of 'chat_feature_screen.dart';

@visibleForTesting
bool shouldReplaceObservedChatSnapshot(
  OpenCrayChatSnapshot? current,
  OpenCrayChatSnapshot incoming,
) {
  if (current == null) {
    return true;
  }
  final bool incomingIsChatOnly = incoming.runtimeActivity == null;
  final int currentVersion = incomingIsChatOnly
      ? chatContentSnapshotVersion(current)
      : chatSnapshotVersion(current);
  final int incomingVersion = incomingIsChatOnly
      ? chatContentSnapshotVersion(incoming)
      : chatSnapshotVersion(incoming);
  if (incomingVersion != currentVersion) {
    return incomingVersion > currentVersion;
  }
  final int currentRuntimeVersion = current.runtimeActivity == null
      ? 0
      : runtimeSnapshotVersion(current.runtimeActivity!);
  final int incomingRuntimeVersion = incoming.runtimeActivity == null
      ? 0
      : runtimeSnapshotVersion(incoming.runtimeActivity!);
  if (incomingRuntimeVersion != currentRuntimeVersion) {
    return incomingRuntimeVersion > currentRuntimeVersion;
  }
  if (incoming.runtimeActivity != null &&
      shouldReplaceObservedRuntimeSnapshot(
        current.runtimeActivity,
        incoming.runtimeActivity!,
      )) {
    return true;
  }
  if (incoming.messages.length != current.messages.length) {
    if (incoming.messages.length > current.messages.length) {
      return true;
    }
    if (incoming.updatedAtEpochMs > current.updatedAtEpochMs) {
      return true;
    }
    if (_chatSnapshotDropsOnlyEphemeralMessageTail(current, incoming) &&
        _chatSnapshotsHostContentEquivalentExceptMessages(current, incoming)) {
      return false;
    }
    return !_chatSnapshotsHostContentEquivalent(current, incoming);
  }
  return !_chatSnapshotsHostContentEquivalent(current, incoming);
}

@visibleForTesting
bool shouldReplaceObservedRuntimeSnapshot(
  OpenCrayChatRuntimeSnapshot? current,
  OpenCrayChatRuntimeSnapshot incoming,
) {
  if (current == null) {
    return true;
  }
  final String currentSessionId = current.sessionId.trim();
  final String incomingSessionId = incoming.sessionId.trim();
  if (currentSessionId.isNotEmpty &&
      incomingSessionId.isNotEmpty &&
      currentSessionId != incomingSessionId) {
    return true;
  }
  if (!_runtimeSnapshotsShareStreamInstance(current, incoming) &&
      (incoming.streamInstanceId?.trim().isNotEmpty ?? false)) {
    return true;
  }
  final OpenCrayChatRuntimeSnapshot candidate = incoming;
  final int currentVersion = runtimeSnapshotVersion(current);
  final int candidateVersion = runtimeSnapshotVersion(candidate);
  if (candidateVersion != currentVersion) {
    return candidateVersion > currentVersion;
  }
  if (_runtimeSnapshotIsAuthoritativeClear(candidate, current)) {
    return true;
  }
  if (_runtimeSnapshotContinuesTerminalRun(candidate, current)) {
    return true;
  }
  final int currentOperationalVersion = _runtimeOperationalVersion(current);
  final int candidateOperationalVersion = _runtimeOperationalVersion(candidate);
  if (candidateOperationalVersion != currentOperationalVersion) {
    return candidateOperationalVersion > currentOperationalVersion;
  }
  if (_runtimeSnapshotTerminalizesRun(candidate, current)) {
    return true;
  }
  if (_runtimeSnapshotTerminalizesRun(current, candidate)) {
    return false;
  }
  final int currentDetailWeight = _runtimeDetailWeight(current);
  final int candidateDetailWeight = _runtimeDetailWeight(candidate);
  if (candidateDetailWeight != currentDetailWeight) {
    return candidateDetailWeight > currentDetailWeight;
  }
  final int currentDraftVersion = _latestRuntimeDraftEpochMs(current);
  final int candidateDraftVersion = _latestRuntimeDraftEpochMs(candidate);
  if (candidateDraftVersion != currentDraftVersion) {
    return candidateDraftVersion > currentDraftVersion;
  }
  final int currentVisibleRuns = _visibleRunCount(current);
  final int candidateVisibleRuns = _visibleRunCount(candidate);
  if (candidateVisibleRuns != currentVisibleRuns) {
    return candidateVisibleRuns > currentVisibleRuns;
  }
  final String currentHostInstanceId = _hostInstanceId(current);
  final String candidateHostInstanceId = _hostInstanceId(candidate);
  if (currentHostInstanceId != candidateHostInstanceId &&
      candidateHostInstanceId.isNotEmpty) {
    return true;
  }
  return _runtimeSnapshotDisplaySignature(candidate) !=
      _runtimeSnapshotDisplaySignature(current);
}

bool _runtimeSnapshotContinuesTerminalRun(
  OpenCrayChatRuntimeSnapshot candidate,
  OpenCrayChatRuntimeSnapshot current,
) {
  for (final candidateRun in _visibleRuns(candidate)) {
    if (!_runtimeRunIsNonTerminalContinuation(candidateRun)) {
      continue;
    }
    final OpenCrayChatRunSnapshot? currentRun = _findRuntimeRun(
      _visibleRuns(current),
      candidateRun,
    );
    if (currentRun != null &&
        currentRun.isTerminal &&
        !_runtimeRunHasContinuationIntent(currentRun)) {
      return true;
    }
  }
  return false;
}

bool _runtimeSnapshotTerminalizesRun(
  OpenCrayChatRuntimeSnapshot candidate,
  OpenCrayChatRuntimeSnapshot current,
) {
  for (final candidateRun in _visibleRuns(candidate)) {
    if (!candidateRun.isTerminal) {
      continue;
    }
    final OpenCrayChatRunSnapshot? currentRun = _findRuntimeRun(
      _visibleRuns(current),
      candidateRun,
    );
    if (currentRun != null &&
        !currentRun.isTerminal &&
        candidateRun.updatedAtEpochMs >= currentRun.updatedAtEpochMs) {
      return true;
    }
  }
  return false;
}

OpenCrayChatRunSnapshot? _findRuntimeRun(
  List<OpenCrayChatRunSnapshot> runs,
  OpenCrayChatRunSnapshot target,
) {
  for (final run in runs) {
    if (_runtimeRunsReferToSameRun(run, target)) {
      return run;
    }
  }
  return null;
}

List<OpenCrayChatRunSnapshot> _visibleRuns(
  OpenCrayChatRuntimeSnapshot snapshot,
) => _mergeRuntimeRuns(snapshot.activeRuns, snapshot.retainedRuns);

int _visibleRunCount(OpenCrayChatRuntimeSnapshot snapshot) =>
    _visibleRuns(snapshot).length;

String _hostInstanceId(OpenCrayChatRuntimeSnapshot snapshot) =>
    snapshot.hostLifecycle?.hostInstanceId?.trim() ?? '';

String _runtimeSnapshotDisplaySignature(OpenCrayChatRuntimeSnapshot snapshot) {
  return jsonEncode(<String, Object?>{
    'sessionId': snapshot.sessionId,
    'streamInstanceId': snapshot.streamInstanceId,
    'lastSequence': snapshot.lastSequence,
    'bridgeEpoch': snapshot.bridgeEpoch,
    'activeRuns': snapshot.activeRuns
        .map(_runtimeRunDisplaySignature)
        .toList(growable: false),
    'retainedRuns': snapshot.retainedRuns
        .map(_runtimeRunDisplaySignature)
        .toList(growable: false),
    'subAgents': snapshot.subAgents
        .map(_runtimeSubAgentDisplaySignature)
        .toList(growable: false),
    'events': snapshot.events
        .map(_runtimeEventDisplaySignature)
        .toList(growable: false),
    'liveAssistantDrafts': snapshot.liveAssistantDrafts
        .map(_runtimeDraftDisplaySignature)
        .toList(growable: false),
    'hostLifecycle': _runtimeHostLifecycleDisplaySignature(
      snapshot.hostLifecycle,
    ),
  });
}

Map<String, Object?> _runtimeRunDisplaySignature(OpenCrayChatRunSnapshot run) {
  return <String, Object?>{
    'sessionId': run.sessionId,
    'runId': run.runId,
    'taskId': run.taskId,
    'acceptedAtEpochMs': run.acceptedAtEpochMs,
    'updatedAtEpochMs': run.updatedAtEpochMs,
    'lifecycleState': run.lifecycleState,
    'taskState': run.taskState,
    'attempt': run.attempt,
    'executionOrdinal': run.executionOrdinal,
    'executionId': run.executionId,
    'executionKind': run.executionKind,
    'pendingExecutionKind': run.pendingExecutionKind,
    'executionStatus': run.executionStatus,
    'errorCode': run.errorCode,
    'errorMessage': run.errorMessage,
    'responseFormat': run.responseFormat,
    'pendingMessageId': run.pendingMessageId,
    'finalAttachments': run.finalAttachments
        .map(_runtimeAttachmentDisplaySignature)
        .toList(growable: false),
    'managedProcessIds': run.managedProcessIds,
    'managedProcesses': run.managedProcesses
        .map(_runtimeManagedProcessDisplaySignature)
        .toList(growable: false),
    'runningManagedProcessCount': run.runningManagedProcessCount,
    'hasLiveManagedProcesses': run.hasLiveManagedProcesses,
    'isTerminal': run.isTerminal,
    'lastEvent': run.lastEvent == null
        ? null
        : _runtimeEventDisplaySignature(run.lastEvent!),
    'llmDiagnostics': _runtimeLlmDiagnosticsDisplaySignature(
      run.llmDiagnostics,
    ),
    'liveContext': _runtimeLiveContextDisplaySignature(run.liveContext),
    'contextBudget': _runtimeContextBudgetDisplaySignature(run.contextBudget),
    'memoryTrace': _runtimeMemoryTraceDisplaySignature(run.memoryTrace),
    'stickyMemory': _runtimeStickyMemoryDisplaySignature(run.stickyMemory),
    'memoryFlush': _runtimeMemoryFlushDisplaySignature(run.memoryFlush),
    'bootstrap': _runtimeBootstrapDisplaySignature(run.bootstrap),
    'durableCompaction': _runtimeDurableCompactionDisplaySignature(
      run.durableCompaction,
    ),
    'skillInventory': _runtimeSkillInventoryDisplaySignature(
      run.skillInventory,
    ),
    'activeSkill': _runtimeActiveSkillDisplaySignature(run.activeSkill),
    'diagnostics': _runtimeDiagnosticsDisplaySignature(run.diagnostics),
    'recoveryPlan': _runtimeRecoveryPlanDisplaySignature(run.recoveryPlan),
  };
}

Map<String, Object?>? _runtimeLlmDiagnosticsDisplaySignature(
  OpenCrayChatRunLlmDiagnosticsSnapshot? diagnostics,
) {
  if (diagnostics == null) {
    return null;
  }
  return <String, Object?>{
    'nativeToolCallRequested': diagnostics.nativeToolCallRequested,
    'providerResponseShape': diagnostics.providerResponseShape,
    'nativeToolCallObserved': diagnostics.nativeToolCallObserved,
    'parsedToolCallObserved': diagnostics.parsedToolCallObserved,
    'fallbackParserAttempted': diagnostics.fallbackParserAttempted,
    'fallbackParserSucceeded': diagnostics.fallbackParserSucceeded,
    'responsesContinuationRecoveryCount':
        diagnostics.responsesContinuationRecoveryCount,
    'responsesContinuationRecoveryLastReason':
        diagnostics.responsesContinuationRecoveryLastReason,
    'localContinuationUsedCount': diagnostics.localContinuationUsedCount,
    'localContinuationFallbackCount':
        diagnostics.localContinuationFallbackCount,
    'localContinuationLastMode': diagnostics.localContinuationLastMode,
    'localContinuationLastReason': diagnostics.localContinuationLastReason,
    'responsesPendingContextUpdateCount':
        diagnostics.responsesPendingContextUpdateCount,
    'responsesPendingContextUpdateHash':
        diagnostics.responsesPendingContextUpdateHash,
    'toolCallEventEmitted': diagnostics.toolCallEventEmitted,
    'toolResultEventEmitted': diagnostics.toolResultEventEmitted,
    'contextCacheBreakReason': diagnostics.contextCacheBreakReason,
    'lastSuccessfulToolName': diagnostics.lastSuccessfulToolName,
  };
}

Map<String, Object?>? _runtimeLiveContextDisplaySignature(
  OpenCrayChatRunLiveContextSnapshot? context,
) {
  if (context == null) {
    return null;
  }
  return <String, Object?>{
    'mode': context.mode,
    'soulEnabled': context.soulEnabled,
    'memoryRecallEnabled': context.memoryRecallEnabled,
  };
}

Map<String, Object?>? _runtimeContextBudgetDisplaySignature(
  OpenCrayChatRunContextBudgetSnapshot? budget,
) {
  if (budget == null) {
    return null;
  }
  return <String, Object?>{
    'applied': budget.applied,
    'pressureMode': budget.pressureMode,
    'selectedPreset': budget.selectedPreset,
    'effectivePreset': budget.effectivePreset,
    'presetSource': budget.presetSource,
    'presetDiverged': budget.presetDiverged,
    'sourcePreset': budget.sourcePreset,
    'sourceTranscriptMaxMessages': budget.sourceTranscriptMaxMessages,
    'sourceInjectedMemoryMaxRecords': budget.sourceInjectedMemoryMaxRecords,
    'sourceMemoryRecallMaxRecords': budget.sourceMemoryRecallMaxRecords,
    'sourceBootstrapMaxChars': budget.sourceBootstrapMaxChars,
    'sourceSkillInventoryMaxSkills': budget.sourceSkillInventoryMaxSkills,
    'sourceActiveSkillMaxChars': budget.sourceActiveSkillMaxChars,
    'sourceRecentObservationMaxEntries':
        budget.sourceRecentObservationMaxEntries,
    'sourceMemoryFlushMaxToolObservations':
        budget.sourceMemoryFlushMaxToolObservations,
    'contextWindowTokens': budget.contextWindowTokens,
    'reservedOutputTokens': budget.reservedOutputTokens,
    'safetyMarginTokens': budget.safetyMarginTokens,
    'hardInputTokens': budget.hardInputTokens,
    'targetInputTokens': budget.targetInputTokens,
    'emergencyInputTokens': budget.emergencyInputTokens,
    'unresolvedOverflow': budget.unresolvedOverflow,
    'fullLayerCount': budget.fullLayerCount,
    'compactLayerCount': budget.compactLayerCount,
    'minimalLayerCount': budget.minimalLayerCount,
    'omittedLayerCount': budget.omittedLayerCount,
    'reducedLayerNames': budget.reducedLayerNames,
    'omittedLayerNames': budget.omittedLayerNames,
    'layers': budget.layers
        .map(_runtimeContextBudgetLayerDisplaySignature)
        .toList(growable: false),
    'layerSummary': budget.layerSummary,
  };
}

Map<String, Object?> _runtimeContextBudgetLayerDisplaySignature(
  OpenCrayChatRunContextBudgetLayerSnapshot layer,
) => <String, Object?>{
  'id': layer.id,
  'name': layer.name,
  'priorityClass': layer.priorityClass,
  'retentionPriority': layer.retentionPriority,
  'estimatedTokensBefore': layer.estimatedTokensBefore,
  'estimatedTokensAfter': layer.estimatedTokensAfter,
  'finalState': layer.finalState,
  'omitted': layer.omitted,
  'reduced': layer.reduced,
  'appliedOperators': layer.appliedOperators,
};

Map<String, Object?>? _runtimeMemoryTraceDisplaySignature(
  OpenCrayChatRunMemoryTraceSnapshot? trace,
) {
  if (trace == null) {
    return null;
  }
  return <String, Object?>{
    'matchedRecordCount': trace.matchedRecordCount,
    'injectedRecordCount': trace.injectedRecordCount,
    'omittedRecordCount': trace.omittedRecordCount,
    'queryTerms': trace.queryTerms,
    'selected': trace.selected
        .map(
          (record) => <String, Object?>{
            'id': record.id,
            'score': record.score,
            'matchedTerms': record.matchedTerms,
          },
        )
        .toList(growable: false),
    'omitted': trace.omitted
        .map(
          (record) => <String, Object?>{
            'id': record.id,
            'reason': record.reason,
          },
        )
        .toList(growable: false),
    'filteredCounts': trace.filteredCounts,
  };
}

Map<String, Object?>? _runtimeMemoryFlushDisplaySignature(
  OpenCrayChatRunMemoryFlushSnapshot? flush,
) {
  if (flush == null) {
    return null;
  }
  return <String, Object?>{
    'outcome': flush.outcome,
    'triggerStage': flush.triggerStage,
    'executionMode': flush.executionMode,
    'contextWindowTokens': flush.contextWindowTokens,
    'previousContextWindowTokens': flush.previousContextWindowTokens,
    'autoCompactTokenLimit': flush.autoCompactTokenLimit,
    'estimatedReplayTokens': flush.estimatedReplayTokens,
    'tokenThresholdTriggered': flush.tokenThresholdTriggered,
    'smallerWindowModelSwitchDetected': flush.smallerWindowModelSwitchDetected,
    'omittedMessageCount': flush.omittedMessageCount,
    'omittedCharCount': flush.omittedCharCount,
    'signature': flush.signature,
    'candidateCount': flush.candidateCount,
    'writtenRecordCount': flush.writtenRecordCount,
    'writtenKinds': flush.writtenKinds,
    'writtenRecordIds': flush.writtenRecordIds,
  };
}

Map<String, Object?>? _runtimeStickyMemoryDisplaySignature(
  OpenCrayChatRunStickyMemorySnapshot? stickyMemory,
) {
  if (stickyMemory == null) {
    return null;
  }
  return <String, Object?>{
    'injectedRecordCount': stickyMemory.injectedRecordCount,
    'omittedRecordCount': stickyMemory.omittedRecordCount,
    'recordIds': stickyMemory.recordIds,
  };
}

Map<String, Object?>? _runtimeBootstrapDisplaySignature(
  OpenCrayChatRunBootstrapSnapshot? bootstrap,
) {
  if (bootstrap == null) {
    return null;
  }
  return <String, Object?>{
    'mode': bootstrap.mode,
    'visibleFileCount': bootstrap.visibleFileCount,
    'injectedFileCount': bootstrap.injectedFileCount,
    'omittedFileCount': bootstrap.omittedFileCount,
    'truncatedFileCount': bootstrap.truncatedFileCount,
    'files': bootstrap.files
        .map(
          (file) => <String, Object?>{
            'name': file.name,
            'relativePath': file.relativePath,
            'sourceCharCount': file.sourceCharCount,
            'injectedCharCount': file.injectedCharCount,
            'truncated': file.truncated,
          },
        )
        .toList(growable: false),
  };
}

Map<String, Object?>? _runtimeDurableCompactionDisplaySignature(
  OpenCrayChatRunDurableCompactionSnapshot? compaction,
) {
  if (compaction == null) {
    return null;
  }
  return <String, Object?>{
    'compactedThisRun': compaction.compactedThisRun,
    'triggerStage': compaction.triggerStage,
    'executionMode': compaction.executionMode,
    'contextWindowTokens': compaction.contextWindowTokens,
    'previousContextWindowTokens': compaction.previousContextWindowTokens,
    'autoCompactTokenLimit': compaction.autoCompactTokenLimit,
    'estimatedReplayTokens': compaction.estimatedReplayTokens,
    'tokenThresholdTriggered': compaction.tokenThresholdTriggered,
    'smallerWindowModelSwitchDetected':
        compaction.smallerWindowModelSwitchDetected,
    'sourceTranscriptMessageCount': compaction.sourceTranscriptMessageCount,
    'retainedTranscriptMessageCount': compaction.retainedTranscriptMessageCount,
    'latestCompactedMessageCount': compaction.latestCompactedMessageCount,
    'includedSummaryCount': compaction.includedSummaryCount,
    'omittedSummaryCount': compaction.omittedSummaryCount,
    'totalSummaryCount': compaction.totalSummaryCount,
    'totalCompactedMessageCount': compaction.totalCompactedMessageCount,
    'latestCompactedAtEpochMs': compaction.latestCompactedAtEpochMs,
    'remoteCompaction': compaction.remoteCompaction == null
        ? null
        : <String, Object?>{
            'requested': compaction.remoteCompaction!.requested,
            'supported': compaction.remoteCompaction!.supported,
            'used': compaction.remoteCompaction!.used,
            'triggerStage': compaction.remoteCompaction!.triggerStage,
            'fallbackReason': compaction.remoteCompaction!.fallbackReason,
            'outputItemCount': compaction.remoteCompaction!.outputItemCount,
            'compactionItemCount':
                compaction.remoteCompaction!.compactionItemCount,
            'encryptedContentCount':
                compaction.remoteCompaction!.encryptedContentCount,
          },
  };
}

Map<String, Object?>? _runtimeSkillInventoryDisplaySignature(
  OpenCrayChatRunSkillInventorySnapshot? inventory,
) {
  if (inventory == null) {
    return null;
  }
  return <String, Object?>{
    'visibleSkillCount': inventory.visibleSkillCount,
    'injectedSkillCount': inventory.injectedSkillCount,
    'omittedSkillCount': inventory.omittedSkillCount,
    'implicitSkillCount': inventory.implicitSkillCount,
    'invalidSkillCount': inventory.invalidSkillCount,
    'omittedTraceSkillCount': inventory.omittedTraceSkillCount,
    'skills': inventory.skills
        .map(
          (skill) => <String, Object?>{
            'name': skill.name,
            'relativePath': skill.relativePath,
            'invocationControl': skill.invocationControl,
            'userInvocable': skill.userInvocable,
            'executionContext': skill.executionContext,
          },
        )
        .toList(growable: false),
  };
}

Map<String, Object?>? _runtimeActiveSkillDisplaySignature(
  OpenCrayChatRunActiveSkillSnapshot? skill,
) {
  if (skill == null) {
    return null;
  }
  return <String, Object?>{
    'name': skill.name,
    'relativePath': skill.relativePath,
    'invocationControl': skill.invocationControl,
    'executionContext': skill.executionContext,
    'activationSource': skill.activationSource,
    'pinned': skill.pinned,
    'toolRestrictionEnabled': skill.toolRestrictionEnabled,
    'truncated': skill.truncated,
    'allowedToolKeys': skill.allowedToolKeys,
  };
}

Map<String, Object?>? _runtimeDiagnosticsDisplaySignature(
  OpenCrayChatRunDiagnosticsSnapshot? diagnostics,
) {
  if (diagnostics == null) {
    return null;
  }
  return <String, Object?>{
    'processStartId': diagnostics.processStartId,
    'hostInstanceId': diagnostics.hostInstanceId,
    'runtimeOwnerId': diagnostics.runtimeOwnerId,
    'submissionSource': diagnostics.submissionSource,
    'recoveryReason': diagnostics.recoveryReason,
    'queueRestoreEpochMs': diagnostics.queueRestoreEpochMs,
    'previousLifecycleState': diagnostics.previousLifecycleState,
    'restoredFromDurableStore': diagnostics.restoredFromDurableStore,
  };
}

Map<String, Object?>? _runtimeRecoveryPlanDisplaySignature(
  OpenCrayChatRunRecoveryPlanSnapshot? plan,
) {
  if (plan == null) {
    return null;
  }
  return <String, Object?>{
    'action': plan.action,
    'reasonCode': plan.reasonCode,
    'summary': plan.summary,
    'safeToAutoResume': plan.safeToAutoResume,
    'requiresUserAction': plan.requiresUserAction,
    'checkpointKind': plan.checkpointKind,
    'approvalState': plan.approvalState,
    'journalTailKind': plan.journalTailKind,
  };
}

Map<String, Object?> _runtimeManagedProcessDisplaySignature(
  OpenCrayChatManagedProcessSnapshot process,
) {
  return <String, Object?>{
    'processId': process.processId,
    'status': process.status,
    'command': process.command,
    'args': process.args,
    'workingDirectory': process.workingDirectory,
    'processStarted': process.processStarted,
    'timeoutMs': process.timeoutMs,
    'startedAtEpochMs': process.startedAtEpochMs,
    'updatedAtEpochMs': process.updatedAtEpochMs,
    'finishedAtEpochMs': process.finishedAtEpochMs,
    'exitCode': process.exitCode,
    'errorCode': process.errorCode,
    'errorMessage': process.errorMessage,
    'timedOut': process.timedOut,
    'cancelled': process.cancelled,
    'outputLimitExceeded': process.outputLimitExceeded,
    'stdout': process.stdout,
    'stderr': process.stderr,
    'stdoutPreview': process.stdoutPreview,
    'stderrPreview': process.stderrPreview,
    'stdoutTruncated': process.stdoutTruncated,
    'stderrTruncated': process.stderrTruncated,
  };
}

Map<String, Object?> _runtimeEventDisplaySignature(
  OpenCrayChatRuntimeEventSnapshot event,
) {
  return <String, Object?>{
    'kind': event.kind,
    'runId': event.runId,
    'taskId': event.taskId,
    'emittedAtEpochMs': event.emittedAtEpochMs,
    'eventId': event.eventId,
    'executionId': event.executionId,
    'executionOrdinal': event.executionOrdinal,
    'executionKind': event.executionKind,
    'entryId': event.entryId,
    'checkpoint': event.checkpoint,
    'turn': event.turn,
    'phase': event.phase,
    'status': event.status,
    'errorCode': event.errorCode,
    'errorMessage': event.errorMessage,
    'responseFormat': event.responseFormat,
    'isFinal': event.isFinal,
    'text': event.text,
    'stage': event.stage,
    'toolName': event.toolName,
    'isHighRisk': event.isHighRisk,
    'label': event.label,
    'childRunId': event.childRunId,
    'childTaskId': event.childTaskId,
    'subagentType': event.subagentType,
    'contextMode': event.contextMode,
    'depth': event.depth,
    'executionState': event.executionState,
    'continuationKind': event.continuationKind,
    'toolReason': event.toolReason,
    'argumentsJson': event.argumentsJson,
    'toolStatus': event.toolStatus,
    'content': event.content,
    'contentPreview': event.contentPreview,
    'resultMetadata': event.resultMetadata,
    'operation': event.operation,
    'query': event.query,
    'queryTerms': event.queryTerms,
    'resultCount': event.resultCount,
    'corpusFileCount': event.corpusFileCount,
    'recordIds': event.recordIds,
    'writtenRecordIds': event.writtenRecordIds,
    'writtenKinds': event.writtenKinds,
    'resolvedRecordIds': event.resolvedRecordIds,
    'suppressedRecordIds': event.suppressedRecordIds,
    'reaffirmedRecordIds': event.reaffirmedRecordIds,
    'expiredRecordIds': event.expiredRecordIds,
    'paths': event.paths,
    'lineRanges': event.lineRanges,
    'path': event.path,
    'fromLine': event.fromLine,
    'returnedLineCount': event.returnedLineCount,
    'totalLineCount': event.totalLineCount,
  };
}

Map<String, Object?> _runtimeAttachmentDisplaySignature(
  OpenCrayChatAttachmentSnapshot attachment,
) {
  return <String, Object?>{
    'attachmentId': attachment.attachmentId,
    'kind': attachment.kind,
    'displayName': attachment.displayName,
    'localPath': attachment.localPath,
    'mimeType': attachment.mimeType,
    'sizeBytes': attachment.sizeBytes,
    'widthPx': attachment.widthPx,
    'heightPx': attachment.heightPx,
    'durationMs': attachment.durationMs,
    'waveformBars': attachment.waveformBars,
    'transcriptText': attachment.transcriptText,
    'contentSha256': attachment.contentSha256,
  };
}

Map<String, Object?> _runtimeSubAgentDisplaySignature(
  OpenCrayChatSubAgentSnapshot subAgent,
) {
  return <String, Object?>{
    'parentRunId': subAgent.parentRunId,
    'parentTaskId': subAgent.parentTaskId,
    'childRunId': subAgent.childRunId,
    'childTaskId': subAgent.childTaskId,
    'label': subAgent.label,
    'subagentType': subAgent.subagentType,
    'contextMode': subAgent.contextMode,
    'depth': subAgent.depth,
    'phase': subAgent.phase,
    'status': subAgent.status,
    'executionState': subAgent.executionState,
    'continuationKind': subAgent.continuationKind,
    'resumable': subAgent.resumable,
    'requiresUserAction': subAgent.requiresUserAction,
    'isHighRisk': subAgent.isHighRisk,
    'summary': subAgent.summary,
    'startedAtEpochMs': subAgent.startedAtEpochMs,
    'updatedAtEpochMs': subAgent.updatedAtEpochMs,
    'eventCount': subAgent.eventCount,
    'mailboxMessageCount': subAgent.mailboxMessageCount,
    'mailboxPendingMessageCount': subAgent.mailboxPendingMessageCount,
    'mailboxLastDeliveredMessageId': subAgent.mailboxLastDeliveredMessageId,
  };
}

Map<String, Object?> _runtimeDraftDisplaySignature(
  OpenCrayChatLiveAssistantDraftSnapshot draft,
) {
  return <String, Object?>{
    'runId': draft.runId,
    'taskId': draft.taskId,
    'executionId': draft.executionId,
    'streamInstanceId': draft.streamInstanceId,
    'sequence': draft.sequence,
    'lastSequence': draft.lastSequence,
    'eventId': draft.eventId,
    'bridgeEpoch': draft.bridgeEpoch,
    'pendingMessageId': draft.pendingMessageId,
    'text': draft.text,
    'updatedAtEpochMs': draft.updatedAtEpochMs,
  };
}

Map<String, Object?>? _runtimeHostLifecycleDisplaySignature(
  OpenCrayHostLifecycleSnapshot? hostLifecycle,
) {
  if (hostLifecycle == null) {
    return null;
  }
  return <String, Object?>{
    'processStartId': hostLifecycle.processStartId,
    'processStartedAtEpochMs': hostLifecycle.processStartedAtEpochMs,
    'hostInstanceId': hostLifecycle.hostInstanceId,
    'runtimeOwnerId': hostLifecycle.runtimeOwnerId,
    'hostCreatedAtEpochMs': hostLifecycle.hostCreatedAtEpochMs,
  };
}

void _runTraceDebug(String message) {
  if (!kDebugMode) {
    return;
  }
  debugPrint('[OpenCrayDiagFlutter] $message');
}

@visibleForTesting
int javaStringHashCode(String value) {
  int hash = 0;
  for (final int codeUnit in value.codeUnits) {
    hash = (hash * 31 + codeUnit) & 0xffffffff;
  }
  if ((hash & 0x80000000) != 0) {
    hash -= 0x100000000;
  }
  return hash;
}

String javaStringHashHex(String value) =>
    (javaStringHashCode(value) & 0xffffffff).toRadixString(16);

@visibleForTesting
bool chatFeatureStatesEquivalent(
  ChatFeatureState left,
  ChatFeatureState right,
) {
  if (identical(left, right)) {
    return true;
  }
  return left.variant == right.variant &&
      left.screenTitle == right.screenTitle &&
      _chatSessionSummariesEquivalent(left.summary, right.summary) &&
      chatMessagesEquivalent(left.messages, right.messages) &&
      _chatRunTracesEquivalent(left.runTraces, right.runTraces) &&
      _chatComposerStatesEquivalent(left.composer, right.composer) &&
      _chatDrawerStatesEquivalent(left.drawer, right.drawer) &&
      _chatPendingApprovalsEquivalent(
        left.pendingApprovals,
        right.pendingApprovals,
      ) &&
      left.modeLabel == right.modeLabel &&
      left.drawerOpen == right.drawerOpen &&
      left.sessionButtonLabel == right.sessionButtonLabel &&
      left.emptyThreadHeight == right.emptyThreadHeight &&
      left.isInputEnabled == right.isInputEnabled &&
      left.isAwaitingFirstSnapshot == right.isAwaitingFirstSnapshot;
}

@visibleForTesting
bool chatMessagesEquivalent(
  List<ChatMessageData> left,
  List<ChatMessageData> right,
) {
  return _listsEquivalent(
    left,
    right,
    (leftMessage, rightMessage) =>
        leftMessage.messageId == rightMessage.messageId &&
        leftMessage.kind == rightMessage.kind &&
        leftMessage.text == rightMessage.text &&
        leftMessage.meta == rightMessage.meta &&
        leftMessage.runtimeAnchorMessageId ==
            rightMessage.runtimeAnchorMessageId &&
        leftMessage.createdAtEpochMs == rightMessage.createdAtEpochMs &&
        leftMessage.isEphemeral == rightMessage.isEphemeral &&
        leftMessage.isStreaming == rightMessage.isStreaming &&
        _listsEquivalent(
          leftMessage.attachments,
          rightMessage.attachments,
          _chatMessageAttachmentsEquivalent,
        ),
  );
}

bool _runtimeProjectionVisibleStateEquivalent(
  ChatFeatureState left,
  ChatFeatureState right,
) {
  return left.variant == right.variant &&
      left.emptyThreadHeight == right.emptyThreadHeight &&
      chatMessagesEquivalent(left.messages, right.messages) &&
      _chatRunTracesEquivalent(left.runTraces, right.runTraces);
}

bool _chatSessionSummariesEquivalent(
  ChatSessionSummary left,
  ChatSessionSummary right,
) {
  return left.title == right.title &&
      left.badge == right.badge &&
      left.body == right.body;
}

bool _chatRunTracesEquivalent(
  List<ChatRunTraceData> left,
  List<ChatRunTraceData> right,
) {
  return _listsEquivalent(
    left,
    right,
    (leftTrace, rightTrace) =>
        leftTrace.runId == rightTrace.runId &&
        leftTrace.taskId == rightTrace.taskId &&
        leftTrace.anchorMessageId == rightTrace.anchorMessageId &&
        leftTrace.label == rightTrace.label &&
        leftTrace.body == rightTrace.body &&
        leftTrace.isHighRisk == rightTrace.isHighRisk &&
        leftTrace.isTerminal == rightTrace.isTerminal &&
        leftTrace.canInterrupt == rightTrace.canInterrupt &&
        leftTrace.isWritingAssistantDraft ==
            rightTrace.isWritingAssistantDraft &&
        leftTrace.retryLabel == rightTrace.retryLabel &&
        _chatRunTracePreviewCardsEquivalent(
          leftTrace.previewCard,
          rightTrace.previewCard,
        ) &&
        _chatRunTraceSessionCardsEquivalent(
          leftTrace.sessionCard,
          rightTrace.sessionCard,
        ) &&
        _listsEquivalent(
          leftTrace.history,
          rightTrace.history,
          _chatRunTraceHistoryEntriesEquivalent,
        ),
  );
}

bool _chatRunTracePreviewCardsEquivalent(
  ChatRunTracePreviewCardData? left,
  ChatRunTracePreviewCardData? right,
) {
  if (left == null || right == null) {
    return left == right;
  }
  return left.url == right.url &&
      left.status == right.status &&
      left.port == right.port &&
      left.path == right.path &&
      left.provider == right.provider &&
      left.httpStatusCode == right.httpStatusCode &&
      left.message == right.message;
}

bool _chatRunTraceSessionCardsEquivalent(
  ChatRunTraceSandboxSessionCardData? left,
  ChatRunTraceSandboxSessionCardData? right,
) {
  if (left == null || right == null) {
    return left == right;
  }
  return left.sessionPresent == right.sessionPresent &&
      left.source == right.source &&
      left.lifecycleStatus == right.lifecycleStatus &&
      left.provider == right.provider &&
      left.sandboxId == right.sandboxId &&
      left.sandboxDomain == right.sandboxDomain &&
      left.templateId == right.templateId &&
      left.updatedAtEpochMs == right.updatedAtEpochMs &&
      left.sessionLastActivityAtEpochMs == right.sessionLastActivityAtEpochMs &&
      left.sessionStaleAfterEpochMs == right.sessionStaleAfterEpochMs &&
      left.lastPreviewUrl == right.lastPreviewUrl &&
      left.lastPreviewProbeStatus == right.lastPreviewProbeStatus &&
      left.lastPreviewProbeObservedAtEpochMs ==
          right.lastPreviewProbeObservedAtEpochMs &&
      left.lastPreviewProbeSource == right.lastPreviewProbeSource &&
      left.autoRefreshAfterMs == right.autoRefreshAfterMs &&
      _listsEquivalent(
        left.previewCandidatePorts,
        right.previewCandidatePorts,
        _itemsEquivalent,
      ) &&
      _listsEquivalent(
        left.runningRequestIds,
        right.runningRequestIds,
        _itemsEquivalent,
      );
}

bool _chatRunTraceHistoryEntriesEquivalent(
  ChatRunTraceHistoryEntry left,
  ChatRunTraceHistoryEntry right,
) {
  return left.label == right.label &&
      left.body == right.body &&
      left.compactBody == right.compactBody &&
      left.isHighRisk == right.isHighRisk &&
      left.inspectorActorId == right.inspectorActorId &&
      left.inspectorActorLabel == right.inspectorActorLabel &&
      left.inspectorCallDetail == right.inspectorCallDetail &&
      left.inspectorResultBody == right.inspectorResultBody &&
      _listsEquivalent(
        left.inspectorCallParts,
        right.inspectorCallParts,
        _chatRunTraceInspectorTextPartsEquivalent,
      );
}

bool _chatRunTraceInspectorTextPartsEquivalent(
  ChatRunTraceInspectorTextPart left,
  ChatRunTraceInspectorTextPart right,
) {
  return left.text == right.text && left.semantic == right.semantic;
}

bool _chatComposerStatesEquivalent(
  ChatComposerState left,
  ChatComposerState right,
) {
  return left.placeholder == right.placeholder &&
      left.selectedCommand == right.selectedCommand &&
      left.showAddMenu == right.showAddMenu &&
      _listsEquivalent(left.todos, right.todos, _chatTodoItemsEquivalent) &&
      _listsEquivalent(
        left.attachments,
        right.attachments,
        _chatComposerAttachmentsEquivalent,
      ) &&
      _listsEquivalent(
        left.commandOptions,
        right.commandOptions,
        _chatCommandOptionsEquivalent,
      ) &&
      _listsEquivalent(left.addActions, right.addActions, _chatAddActionsEqual);
}

bool _chatTodoItemsEquivalent(ChatTodoItemData left, ChatTodoItemData right) {
  return left.content == right.content &&
      left.status == right.status &&
      left.activeForm == right.activeForm;
}

bool _chatComposerAttachmentsEquivalent(
  ChatAttachmentData left,
  ChatAttachmentData right,
) {
  return left.id == right.id &&
      left.kind == right.kind &&
      left.label == right.label &&
      left.detail == right.detail &&
      left.accentColor == right.accentColor &&
      _draftAttachmentsEquivalent(left.draftAttachment, right.draftAttachment);
}

bool _draftAttachmentsEquivalent(
  OpenCrayChatDraftAttachment? left,
  OpenCrayChatDraftAttachment? right,
) {
  if (left == null || right == null) {
    return left == right;
  }
  return left.kind == right.kind &&
      left.displayName == right.displayName &&
      left.relativePath == right.relativePath &&
      left.artifactId == right.artifactId &&
      left.chatAttachmentId == right.chatAttachmentId &&
      left.mimeType == right.mimeType &&
      left.sizeBytes == right.sizeBytes;
}

bool _chatCommandOptionsEquivalent(
  ChatCommandOptionData left,
  ChatCommandOptionData right,
) {
  return left.label == right.label && left.description == right.description;
}

bool _chatAddActionsEqual(ChatAddActionData left, ChatAddActionData right) {
  return left.label == right.label && left.icon == right.icon;
}

bool _chatDrawerStatesEquivalent(
  ChatSessionsDrawerState left,
  ChatSessionsDrawerState right,
) {
  return left.eyebrow == right.eyebrow &&
      left.title == right.title &&
      left.ctaLabel == right.ctaLabel &&
      _listsEquivalent(
        left.sessions,
        right.sessions,
        _chatSessionListItemsEquivalent,
      );
}

bool _chatSessionListItemsEquivalent(
  ChatSessionListItemData left,
  ChatSessionListItemData right,
) {
  return left.sessionId == right.sessionId &&
      left.title == right.title &&
      left.preview == right.preview &&
      left.meta == right.meta &&
      left.isSelected == right.isSelected &&
      left.lastMessageAtEpochMs == right.lastMessageAtEpochMs &&
      left.unreadCount == right.unreadCount;
}

bool _chatPendingApprovalsEquivalent(
  List<ChatPendingApprovalData> left,
  List<ChatPendingApprovalData> right,
) {
  return _listsEquivalent(
    left,
    right,
    (leftApproval, rightApproval) =>
        leftApproval.runId == rightApproval.runId &&
        leftApproval.taskId == rightApproval.taskId &&
        leftApproval.title == rightApproval.title &&
        leftApproval.body == rightApproval.body &&
        leftApproval.approveLabel == rightApproval.approveLabel &&
        leftApproval.rejectLabel == rightApproval.rejectLabel &&
        leftApproval.isHighRisk == rightApproval.isHighRisk &&
        leftApproval.supportsSessionApproval ==
            rightApproval.supportsSessionApproval &&
        leftApproval.approveForSessionLabel ==
            rightApproval.approveForSessionLabel &&
        leftApproval.toolName == rightApproval.toolName &&
        leftApproval.requestSummary == rightApproval.requestSummary &&
        leftApproval.primaryDetail == rightApproval.primaryDetail &&
        leftApproval.workingDirectory == rightApproval.workingDirectory &&
        leftApproval.reason == rightApproval.reason &&
        leftApproval.message == rightApproval.message &&
        _listsEquivalent(
          leftApproval.pathDetails,
          rightApproval.pathDetails,
          _itemsEquivalent,
        ),
  );
}

bool _chatSnapshotsHostContentEquivalent(
  OpenCrayChatSnapshot left,
  OpenCrayChatSnapshot right,
) {
  return _chatSnapshotsHostContentEquivalentExceptMessages(left, right) &&
      _chatMessageSnapshotsEquivalent(left.messages, right.messages);
}

bool _chatSnapshotsHostContentEquivalentExceptMessages(
  OpenCrayChatSnapshot left,
  OpenCrayChatSnapshot right,
) {
  return left.screenTitle == right.screenTitle &&
      left.modeLabel == right.modeLabel &&
      left.sessionButtonLabel == right.sessionButtonLabel &&
      left.composerPlaceholder == right.composerPlaceholder &&
      left.isInputEnabled == right.isInputEnabled &&
      left.todoState == right.todoState &&
      left.todoHideDelayMs == right.todoHideDelayMs &&
      left.todoCompletedAtEpochMs == right.todoCompletedAtEpochMs &&
      _chatSummarySnapshotsEquivalent(left.summary, right.summary) &&
      _chatDrawerSnapshotsEquivalent(left.drawer, right.drawer) &&
      _chatTodoSnapshotsEquivalent(left.todos, right.todos) &&
      _chatPendingApprovalSnapshotsEquivalent(
        left.pendingApprovals,
        right.pendingApprovals,
      );
}

bool _chatSnapshotDropsOnlyEphemeralMessageTail(
  OpenCrayChatSnapshot current,
  OpenCrayChatSnapshot incoming,
) {
  if (incoming.messages.length >= current.messages.length) {
    return false;
  }
  for (int index = 0; index < incoming.messages.length; index += 1) {
    if (!_chatMessageSnapshotEquivalent(
      current.messages[index],
      incoming.messages[index],
    )) {
      return false;
    }
  }
  return current.messages
      .skip(incoming.messages.length)
      .every((message) => message.isEphemeral);
}

bool _chatSummarySnapshotsEquivalent(
  OpenCrayChatSummarySnapshot left,
  OpenCrayChatSummarySnapshot right,
) {
  return left.title == right.title &&
      left.badge == right.badge &&
      left.body == right.body;
}

bool _chatMessageSnapshotsEquivalent(
  List<OpenCrayChatMessageSnapshot> left,
  List<OpenCrayChatMessageSnapshot> right,
) {
  return _listsEquivalent(left, right, _chatMessageSnapshotEquivalent);
}

bool _chatMessageSnapshotEquivalent(
  OpenCrayChatMessageSnapshot leftMessage,
  OpenCrayChatMessageSnapshot rightMessage,
) {
  return leftMessage.messageId == rightMessage.messageId &&
      leftMessage.kind == rightMessage.kind &&
      leftMessage.text == rightMessage.text &&
      leftMessage.meta == rightMessage.meta &&
      leftMessage.createdAtEpochMs == rightMessage.createdAtEpochMs &&
      leftMessage.isEphemeral == rightMessage.isEphemeral &&
      _chatAttachmentSnapshotsEquivalent(
        leftMessage.attachments,
        rightMessage.attachments,
      );
}

bool _chatAttachmentSnapshotsEquivalent(
  List<OpenCrayChatAttachmentSnapshot> left,
  List<OpenCrayChatAttachmentSnapshot> right,
) {
  return _listsEquivalent(
    left,
    right,
    (leftAttachment, rightAttachment) =>
        leftAttachment.attachmentId == rightAttachment.attachmentId &&
        leftAttachment.kind == rightAttachment.kind &&
        leftAttachment.displayName == rightAttachment.displayName &&
        leftAttachment.localPath == rightAttachment.localPath &&
        leftAttachment.mimeType == rightAttachment.mimeType &&
        leftAttachment.sizeBytes == rightAttachment.sizeBytes &&
        leftAttachment.widthPx == rightAttachment.widthPx &&
        leftAttachment.heightPx == rightAttachment.heightPx &&
        leftAttachment.durationMs == rightAttachment.durationMs &&
        leftAttachment.transcriptText == rightAttachment.transcriptText &&
        leftAttachment.contentSha256 == rightAttachment.contentSha256 &&
        _listsEquivalent(
          leftAttachment.waveformBars,
          rightAttachment.waveformBars,
          _itemsEquivalent,
        ),
  );
}

bool _chatDrawerSnapshotsEquivalent(
  OpenCrayChatDrawerSnapshot left,
  OpenCrayChatDrawerSnapshot right,
) {
  return left.eyebrow == right.eyebrow &&
      left.title == right.title &&
      left.ctaLabel == right.ctaLabel &&
      _listsEquivalent(
        left.sessions,
        right.sessions,
        _chatSessionItemSnapshotsEquivalent,
      );
}

bool _chatSessionItemSnapshotsEquivalent(
  OpenCrayChatSessionItemSnapshot left,
  OpenCrayChatSessionItemSnapshot right,
) {
  return left.sessionId == right.sessionId &&
      left.title == right.title &&
      left.preview == right.preview &&
      left.meta == right.meta &&
      left.isSelected == right.isSelected &&
      left.lastMessageAtEpochMs == right.lastMessageAtEpochMs &&
      left.unreadCount == right.unreadCount;
}

bool _chatTodoSnapshotsEquivalent(
  List<OpenCrayChatTodoSnapshot> left,
  List<OpenCrayChatTodoSnapshot> right,
) {
  return _listsEquivalent(
    left,
    right,
    (leftTodo, rightTodo) =>
        leftTodo.content == rightTodo.content &&
        leftTodo.status == rightTodo.status &&
        leftTodo.activeForm == rightTodo.activeForm,
  );
}

bool _chatPendingApprovalSnapshotsEquivalent(
  List<OpenCrayChatPendingApprovalSnapshot> left,
  List<OpenCrayChatPendingApprovalSnapshot> right,
) {
  return _listsEquivalent(
    left,
    right,
    (leftApproval, rightApproval) =>
        leftApproval.runId == rightApproval.runId &&
        leftApproval.taskId == rightApproval.taskId &&
        leftApproval.title == rightApproval.title &&
        leftApproval.body == rightApproval.body &&
        leftApproval.approveLabel == rightApproval.approveLabel &&
        leftApproval.rejectLabel == rightApproval.rejectLabel &&
        leftApproval.isHighRisk == rightApproval.isHighRisk &&
        leftApproval.supportsSessionApproval ==
            rightApproval.supportsSessionApproval &&
        leftApproval.approveForSessionLabel ==
            rightApproval.approveForSessionLabel &&
        leftApproval.toolName == rightApproval.toolName &&
        leftApproval.requestSummary == rightApproval.requestSummary &&
        leftApproval.primaryDetail == rightApproval.primaryDetail &&
        leftApproval.workingDirectory == rightApproval.workingDirectory &&
        leftApproval.reason == rightApproval.reason &&
        leftApproval.message == rightApproval.message &&
        _listsEquivalent(
          leftApproval.pathDetails,
          rightApproval.pathDetails,
          _itemsEquivalent,
        ),
  );
}

bool _chatMessageAttachmentsEquivalent(
  ChatMessageAttachmentData left,
  ChatMessageAttachmentData right,
) {
  return left.attachmentId == right.attachmentId &&
      left.kind == right.kind &&
      left.displayName == right.displayName &&
      left.localPath == right.localPath &&
      left.mimeType == right.mimeType &&
      left.sizeBytes == right.sizeBytes &&
      left.widthPx == right.widthPx &&
      left.heightPx == right.heightPx &&
      left.durationMs == right.durationMs &&
      left.transcriptText == right.transcriptText &&
      left.contentSha256 == right.contentSha256 &&
      _listsEquivalent(left.waveformBars, right.waveformBars, _itemsEquivalent);
}

bool _itemsEquivalent<T>(T left, T right) => left == right;

bool _listsEquivalent<T>(
  List<T> left,
  List<T> right,
  bool Function(T left, T right) itemEquivalent,
) {
  if (identical(left, right)) {
    return true;
  }
  if (left.length != right.length) {
    return false;
  }
  for (int index = 0; index < left.length; index += 1) {
    if (!itemEquivalent(left[index], right[index])) {
      return false;
    }
  }
  return true;
}
