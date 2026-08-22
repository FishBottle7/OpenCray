part of 'chat_feature_screen.dart';

class _TimedChatRunTraceHistoryEntry {
  const _TimedChatRunTraceHistoryEntry({
    required this.sortEpochMs,
    required this.sourceOrder,
    required this.entry,
  });

  final int sortEpochMs;
  final int sourceOrder;
  final ChatRunTraceHistoryEntry entry;
}

class _RuntimeProjectionPatch {
  const _RuntimeProjectionPatch({
    required this.messages,
    required this.runTraces,
  });

  final List<ChatMessageData> messages;
  final List<ChatRunTraceData> runTraces;
}

class _RuntimeProjectedMessagePatch {
  const _RuntimeProjectedMessagePatch({
    required this.anchorMessageId,
    required this.text,
    required this.isStreaming,
  });

  final String anchorMessageId;
  final String text;
  final bool isStreaming;
}

@immutable
class _TodoTraceSummary {
  const _TodoTraceSummary({
    required this.todoCount,
    required this.pendingCount,
    required this.inProgressCount,
    required this.completedCount,
    this.activeTodoContent,
  });

  final int todoCount;
  final int pendingCount;
  final int inProgressCount;
  final int completedCount;
  final String? activeTodoContent;
}

class ChatRuntimeProjector {
  ChatRuntimeProjector({
    required this.copy,
    required this.usesHostBridge,
    required this.drawerSessions,
    required this.activeSessionId,
    required this.liveAssistantDraftOverridesBySession,
    required this.hiddenArchivedTodoFingerprint,
  });

  final OpenCrayUiCopy copy;
  final bool usesHostBridge;
  final List<ChatSessionListItemData> drawerSessions;
  final String activeSessionId;
  final Map<String, Map<String, OpenCrayChatLiveAssistantDraftSnapshot>>
      liveAssistantDraftOverridesBySession;
  final String? hiddenArchivedTodoFingerprint;

  static const Map<String, String> _displayToolAliases = <String, String>{
    'workspace_read_file': 'Read',
    'workspace_list_files': 'LS',
    'workspace_write_file': 'Write',
    'workspace_import_file': 'ImportFile',
    'bash': 'Bash',
    'list': 'LS',
    'ls': 'LS',
    'read': 'Read',
    'write': 'Write',
    'grep': 'Grep',
    'glob': 'Glob',
    'websearch': 'WebSearch',
    'webfetch': 'WebFetch',
    'generateimage': 'GenerateImage',
    'imagegenerate': 'GenerateImage',
    'synthesizespeech': 'SynthesizeSpeech',
    'texttospeech': 'SynthesizeSpeech',
    'tts': 'SynthesizeSpeech',
    'edit': 'Edit',
    'multiedit': 'MultiEdit',
    'importfile': 'ImportFile',
    'import': 'ImportFile',
    'importchatattachment': 'import_chat_attachment',
    'searchworkspacedocument': 'search_workspace_document',
    'inspectworkspacepackage': 'inspect_workspace_package',
    'extractworkspacepackage': 'extract_workspace_package',
    'viewworkspacedocument': 'view_workspace_document',
    'viewworkspaceimage': 'view_workspace_image',
    'viewworkspacepdf': 'view_workspace_pdf',
    'todowrite': 'TodoWrite',
    'scheduledtaskcreate': 'ScheduledTaskCreate',
    'scheduled_task_create': 'ScheduledTaskCreate',
    'scheduledtasklist': 'ScheduledTaskList',
    'scheduled_task_list': 'ScheduledTaskList',
    'scheduledtaskget': 'ScheduledTaskGet',
    'scheduled_task_get': 'ScheduledTaskGet',
    'scheduledtaskupdate': 'ScheduledTaskUpdate',
    'scheduled_task_update': 'ScheduledTaskUpdate',
    'scheduledtaskdelete': 'ScheduledTaskDelete',
    'scheduled_task_delete': 'ScheduledTaskDelete',
    'task': 'Task',
    'spawnagent': 'spawn_agent',
    'waitagent': 'wait_agent',
    'sendinput': 'send_input',
    'closeagent': 'close_agent',
    'listsubagents': 'list_subagents',
    'list_handles': 'list_subagents',
    'listhandles': 'list_subagents',
    'processstart': 'ProcessStart',
    'processlist': 'ProcessList',
    'processread': 'ProcessRead',
    'processwait': 'ProcessWait',
    'processterminate': 'ProcessTerminate',
  };

  static const Set<String> _thinkingPlaceholders = <String>{
    'Thinking',
    'Thinking…',
    'Thinking...',
    'OpenCray is thinking...',
    '思考中',
    '思考中…',
    '思考中...',
  };

  String? _archivedTodoFingerprint(OpenCrayChatSnapshot snapshot) {
    if (snapshot.todoState != 'archived_completed' || snapshot.todos.isEmpty) {
      return null;
    }
    final int? completedAtEpochMs = snapshot.todoCompletedAtEpochMs;
    if (completedAtEpochMs == null || completedAtEpochMs <= 0) {
      return null;
    }
    final String encodedTodos = snapshot.todos
        .map(
          (todo) => '${todo.content}|${todo.status}|${todo.activeForm ?? ''}',
        )
        .join('||');
    return '$completedAtEpochMs::$encodedTodos';
  }

  List<ChatTodoItemData> _mapVisibleTodos(OpenCrayChatSnapshot snapshot) {
    final String? archivedFingerprint = _archivedTodoFingerprint(snapshot);
    if (archivedFingerprint != null &&
        hiddenArchivedTodoFingerprint == archivedFingerprint) {
      return const <ChatTodoItemData>[];
    }
    return snapshot.todos
        .map(
          (OpenCrayChatTodoSnapshot todo) => ChatTodoItemData(
            content: todo.content,
            status: switch (todo.status) {
              'completed' => ChatTodoStatus.completed,
              'in_progress' ||
              'in-progress' ||
              'inprogress' => ChatTodoStatus.inProgress,
              _ => ChatTodoStatus.pending,
            },
            activeForm: todo.activeForm,
          ),
        )
        .toList(growable: false);
  }

  ChatFeatureState _mapSnapshot(
    OpenCrayChatSnapshot snapshot,
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  ) {
    final OpenCrayChatRuntimeSnapshot? effectiveRuntime =
        _resolveRuntimeSnapshot(
          expectedSessionId: _snapshotActiveSessionId(snapshot),
          embedded: snapshot.runtimeActivity,
          streamed: runtimeSnapshot,
        );
    final _RuntimeProjectionPatch runtimeProjection = _mapRuntimeProjection(
      snapshot,
      effectiveRuntime,
    );
    final List<ChatTodoItemData> visibleTodos = _mapVisibleTodos(snapshot);
    return ChatFeatureState(
      variant:
          runtimeProjection.messages.isEmpty &&
              runtimeProjection.runTraces.isEmpty &&
              visibleTodos.isEmpty &&
              snapshot.pendingApprovals.isEmpty
          ? ChatPrototypeVariant.empty
          : ChatPrototypeVariant.main,
      screenTitle: snapshot.screenTitle,
      summary: ChatSessionSummary(
        title: snapshot.summary.title,
        badge: snapshot.summary.badge,
        body: snapshot.summary.body,
      ),
      messages: runtimeProjection.messages,
      runTraces: runtimeProjection.runTraces,
      composer: ChatComposerState(
        placeholder: snapshot.composerPlaceholder,
        todos: visibleTodos,
        attachments: const <ChatAttachmentData>[],
        commandOptions: const <ChatCommandOptionData>[],
        addActions: usesHostBridge
            ? const <ChatAddActionData>[]
            : OpenCrayChatSeedData.sampleAddActions(copy),
      ),
      drawer: ChatSessionsDrawerState(
        eyebrow: snapshot.drawer.eyebrow,
        title: snapshot.drawer.title,
        ctaLabel: snapshot.drawer.ctaLabel,
        sessions: snapshot.drawer.sessions
            .map(
              (session) => ChatSessionListItemData(
                sessionId: session.sessionId,
                title: session.title,
                preview: session.preview,
                meta: session.meta,
                isSelected: session.isSelected,
                lastMessageAtEpochMs: session.lastMessageAtEpochMs,
                unreadCount: session.unreadCount,
              ),
            )
            .toList(growable: false),
      ),
      pendingApprovals: snapshot.pendingApprovals
          .map(
            (approval) => ChatPendingApprovalData(
              runId: approval.runId,
              taskId: approval.taskId,
              title: approval.title,
              body: approval.body,
              approveLabel: approval.approveLabel,
              rejectLabel: approval.rejectLabel,
              isHighRisk: approval.isHighRisk,
              supportsSessionApproval: approval.supportsSessionApproval,
              approveForSessionLabel: approval.approveForSessionLabel,
              toolName: approval.toolName,
              requestSummary: approval.requestSummary,
              primaryDetail: approval.primaryDetail,
              pathDetails: approval.pathDetails,
              workingDirectory: approval.workingDirectory,
              reason: approval.reason,
              message: approval.message,
            ),
          )
          .toList(growable: false),
      modeLabel: snapshot.modeLabel,
      sessionButtonLabel: snapshot.sessionButtonLabel,
      emptyThreadHeight:
          runtimeProjection.messages.isEmpty &&
              runtimeProjection.runTraces.isEmpty
          ? 260
          : 0,
      isInputEnabled: snapshot.isInputEnabled,
    );
  }

  _RuntimeProjectionPatch _mapRuntimeProjection(
    OpenCrayChatSnapshot snapshot,
    OpenCrayChatRuntimeSnapshot? effectiveRuntime,
  ) {
    final List<ChatRunTraceData> runTraces = _mapRunTraces(
      effectiveRuntime,
      snapshot.pendingApprovals,
      snapshot.messages
          .map((message) => message.messageId.trim())
          .where((messageId) => messageId.isNotEmpty)
          .toSet(),
    );
    final Map<String, String> liveDraftTextByMessageId =
        _liveDraftTextByMessageId(effectiveRuntime);
    final Map<String, _RuntimeProjectedMessagePatch>
    runtimeProjectedMessagePatches = _runtimeProjectedMessagePatchesByMessageId(
      effectiveRuntime,
    );
    final List<ChatMessageData> anchoredMessages = _mapMessages(
      snapshot.messages,
      hideThinkingPlaceholder: false,
      draftTextByMessageId: liveDraftTextByMessageId,
      runtimeProjectedMessagePatches: runtimeProjectedMessagePatches,
    );
    final Set<String> existingMessageIds = anchoredMessages
        .map((message) => message.messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();
    final List<ChatMessageData> projectedLiveDraftMessages =
        _mapUnanchoredLiveDraftMessages(effectiveRuntime, existingMessageIds);
    final List<ChatMessageData> anchoredVisibleMessages = <ChatMessageData>[
      ...anchoredMessages,
      ...projectedLiveDraftMessages,
    ];
    final List<ChatMessageData> messages = _trimHiddenThinkingPlaceholder(
      _mergeProjectedAssistantPhaseMessages(
        messages: anchoredVisibleMessages,
        runtimeSnapshot: effectiveRuntime,
      ),
      hideThinkingPlaceholder: runTraces.isNotEmpty,
    );
    return _RuntimeProjectionPatch(messages: messages, runTraces: runTraces);
  }

  Map<String, String> _liveDraftTextByMessageId(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  ) {
    if (runtimeSnapshot == null) {
      return const <String, String>{};
    }
    final Map<String, String> visibleDrafts = <String, String>{};
    for (final draft in runtimeSnapshot.liveAssistantDrafts) {
      final String pendingMessageId = draft.pendingMessageId.trim();
      final String? visibleDraftText = _visibleAssistantDraftText(draft.text);
      if (pendingMessageId.isEmpty ||
          visibleDraftText == null ||
          !_shouldDisplayLiveAssistantDraft(
            runId: draft.runId,
            taskId: draft.taskId,
            executionId: draft.executionId,
            runtimeSnapshot: runtimeSnapshot,
          )) {
        continue;
      }
      visibleDrafts[pendingMessageId] = visibleDraftText;
    }
    return visibleDrafts;
  }

  Map<String, _RuntimeProjectedMessagePatch>
  _runtimeProjectedMessagePatchesByMessageId(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  ) {
    if (runtimeSnapshot == null) {
      return const <String, _RuntimeProjectedMessagePatch>{};
    }
    final Map<String, _RuntimeProjectedMessagePatch> patchesByMessageId =
        <String, _RuntimeProjectedMessagePatch>{};
    final List<OpenCrayChatRunSnapshot> visibleRuns = _visibleRuns(
      runtimeSnapshot,
    );
    final Map<String, OpenCrayChatRunSnapshot> visibleRunsByRunId =
        <String, OpenCrayChatRunSnapshot>{
          for (final run in visibleRuns)
            if (run.runId.trim().isNotEmpty) run.runId.trim(): run,
        };
    final Map<String, OpenCrayChatRunSnapshot> visibleRunsByTaskId =
        <String, OpenCrayChatRunSnapshot>{
          for (final run in visibleRuns)
            if (run.taskId.trim().isNotEmpty) run.taskId.trim(): run,
        };
    final Set<String> latestPhaseEventIdentities =
        _latestAssistantPhaseEventIdentities(
          runtimeSnapshot: runtimeSnapshot,
          visibleRunsByRunId: visibleRunsByRunId,
          visibleRunsByTaskId: visibleRunsByTaskId,
        );
    for (final event in runtimeSnapshot.events) {
      if (event.kind != 'assistant_phase' ||
          event.isFinal == true ||
          _hideAssistantPhaseBubble(event)) {
        continue;
      }
      final OpenCrayChatRunSnapshot? run =
          visibleRunsByRunId[event.runId.trim()] ??
          visibleRunsByTaskId[event.taskId.trim()];
      final String anchorMessageId = run?.pendingMessageId?.trim() ?? '';
      if (run == null ||
          anchorMessageId.isEmpty ||
          !run.matchesRuntimeEvent(event)) {
        continue;
      }
      final String text = _projectedAssistantPhaseMessageText(event);
      if (text.trim().isEmpty) {
        continue;
      }
      // Only the event's own ids may be patched: writing through the shared
      // run/turn/stage aliases would let a later narration overwrite the
      // persisted bubble of an earlier one from the same turn.
      for (final messageId in _assistantPhaseClaimMessageIds(event)) {
        patchesByMessageId[messageId] = _RuntimeProjectedMessagePatch(
          anchorMessageId: anchorMessageId,
          text: text,
          isStreaming:
              !run.isTerminal &&
              latestPhaseEventIdentities.contains(
                _assistantPhaseEventIdentity(event),
              ),
        );
      }
    }
    for (final run in visibleRuns) {
      final String anchorMessageId = run.pendingMessageId?.trim() ?? '';
      if (anchorMessageId.isEmpty) {
        continue;
      }
      for (final process in run.managedProcesses) {
        final String text = _projectedManagedProcessMessageText(process);
        if (text.trim().isEmpty) {
          continue;
        }
        for (final messageId in _projectedManagedProcessMessageIds(
          run: run,
          process: process,
        )) {
          patchesByMessageId[messageId] = _RuntimeProjectedMessagePatch(
            anchorMessageId: anchorMessageId,
            text: text,
            isStreaming: !run.isTerminal && _managedProcessIsStreaming(process),
          );
        }
      }
    }
    return patchesByMessageId;
  }

  List<ChatMessageData> _mapMessages(
    List<OpenCrayChatMessageSnapshot> messages, {
    required bool hideThinkingPlaceholder,
    required Map<String, String> draftTextByMessageId,
    Map<String, _RuntimeProjectedMessagePatch> runtimeProjectedMessagePatches =
        const <String, _RuntimeProjectedMessagePatch>{},
  }) {
    final mapped = messages
        .asMap()
        .entries
        .map((entry) {
          final String messageId = entry.value.messageId.trim().isNotEmpty
              ? entry.value.messageId
              : 'message-${entry.key}-${entry.value.kind}';
          final _RuntimeProjectedMessagePatch? runtimePatch =
              runtimeProjectedMessagePatches[messageId];
          final bool hasLiveDraftText =
              draftTextByMessageId.containsKey(messageId) &&
              _shouldReplacePendingThinkingBubble(
                messageKind: entry.value.kind,
                text: entry.value.text,
              );
          return ChatMessageData(
            messageId: messageId,
            kind: switch (entry.value.kind) {
              'timeline' => ChatMessageKind.timeline,
              'outbound' => ChatMessageKind.outbound,
              _ => ChatMessageKind.inbound,
            },
            text:
                runtimePatch?.text ??
                _resolvedChatMessageText(
                  message: entry.value,
                  draftTextByMessageId: draftTextByMessageId,
                ),
            meta: entry.value.meta,
            runtimeAnchorMessageId: runtimePatch?.anchorMessageId ?? '',
            createdAtEpochMs: entry.value.createdAtEpochMs,
            isEphemeral: entry.value.isEphemeral,
            isStreaming: runtimePatch?.isStreaming ?? hasLiveDraftText,
            attachments: entry.value.attachments
                .map(
                  (attachment) => ChatMessageAttachmentData(
                    attachmentId: attachment.attachmentId.trim().isNotEmpty
                        ? attachment.attachmentId
                        : '${entry.value.messageId}-${attachment.localPath}',
                    kind: switch (attachment.kind) {
                      'image' => ChatAttachmentKind.image,
                      'voice' || 'audio' => ChatAttachmentKind.voice,
                      _ => ChatAttachmentKind.file,
                    },
                    displayName: attachment.displayName,
                    localPath: attachment.localPath,
                    mimeType: attachment.mimeType,
                    sizeBytes: attachment.sizeBytes,
                    widthPx: attachment.widthPx,
                    heightPx: attachment.heightPx,
                    durationMs: attachment.durationMs,
                    waveformBars: attachment.waveformBars,
                    transcriptText: attachment.transcriptText,
                    contentSha256: attachment.contentSha256,
                  ),
                )
                .toList(growable: false),
          );
        })
        .toList(growable: true);
    if (hideThinkingPlaceholder && mapped.isNotEmpty) {
      return _trimHiddenThinkingPlaceholder(
        mapped,
        hideThinkingPlaceholder: true,
      );
    }
    return mapped;
  }

  List<ChatMessageData> _trimHiddenThinkingPlaceholder(
    List<ChatMessageData> messages, {
    required bool hideThinkingPlaceholder,
  }) {
    if (!hideThinkingPlaceholder || messages.isEmpty) {
      return messages;
    }
    final ChatMessageData lastMessage = messages.last;
    if (lastMessage.kind != ChatMessageKind.inbound ||
        !_thinkingPlaceholders.contains(lastMessage.text.trim())) {
      return messages;
    }
    return messages.take(messages.length - 1).toList(growable: false);
  }

  String _resolvedChatMessageText({
    required OpenCrayChatMessageSnapshot message,
    required Map<String, String> draftTextByMessageId,
  }) {
    final String baseText = message.text;
    final String messageId = message.messageId.trim();
    final String? liveDraftText = draftTextByMessageId[messageId];
    if (liveDraftText == null ||
        !_shouldReplacePendingThinkingBubble(
          messageKind: message.kind,
          text: baseText,
        )) {
      return baseText;
    }
    return liveDraftText;
  }

  bool _shouldReplacePendingThinkingBubble({
    required String messageKind,
    required String text,
  }) {
    if (messageKind == 'outbound' || messageKind == 'timeline') {
      return false;
    }
    return _thinkingPlaceholders.contains(text.trim());
  }

  List<ChatRunTraceData> _mapRunTraces(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
    List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals,
    Set<String> visibleAnchorMessageIds,
  ) {
    if (runtimeSnapshot == null) {
      return const <ChatRunTraceData>[];
    }
    final activeRuns =
        _visibleRuns(runtimeSnapshot)
            .where((run) {
              final String pendingMessageId =
                  run.pendingMessageId?.trim() ?? '';
              return pendingMessageId.isEmpty ||
                  visibleAnchorMessageIds.contains(pendingMessageId);
            })
            .toList(growable: false)
          ..sort(
            (left, right) =>
                left.acceptedAtEpochMs.compareTo(right.acceptedAtEpochMs),
          );
    final List<ChatRunTraceData> runTraces = activeRuns
        .map(
          (run) => _mapRunTrace(
            run: run,
            runtimeSnapshot: runtimeSnapshot,
            pendingApprovals: pendingApprovals,
          ),
        )
        .toList(growable: false);
    final Set<String> visibleParentRunIds = activeRuns
        .map((run) => run.runId.trim())
        .where((runId) => runId.isNotEmpty)
        .toSet();
    final Set<String> visibleParentTaskIds = activeRuns
        .map((run) => run.taskId.trim())
        .where((taskId) => taskId.isNotEmpty)
        .toSet();
    final List<ChatRunTraceData> detachedSubAgentTraces =
        _detachedSubAgentSnapshots(
          runtimeSnapshot: runtimeSnapshot,
          visibleParentRunIds: visibleParentRunIds,
          visibleParentTaskIds: visibleParentTaskIds,
        ).map(_mapDetachedSubAgentTrace).toList(growable: false);
    if (runTraces.isEmpty && detachedSubAgentTraces.isEmpty) {
      return const <ChatRunTraceData>[];
    }
    return <ChatRunTraceData>[...runTraces, ...detachedSubAgentTraces];
  }

  List<OpenCrayChatSubAgentSnapshot> _subAgentSnapshotsForRun({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  }) =>
      runtimeSnapshot.subAgents
          .where((subAgent) {
            final String parentRunId = subAgent.parentRunId.trim();
            if (parentRunId.isNotEmpty && parentRunId == run.runId.trim()) {
              return true;
            }
            final String parentTaskId = subAgent.parentTaskId.trim();
            return parentTaskId.isNotEmpty && parentTaskId == run.taskId.trim();
          })
          .toList(growable: false)
        ..sort(
          (left, right) =>
              left.updatedAtEpochMs.compareTo(right.updatedAtEpochMs),
        );

  List<OpenCrayChatRuntimeEventSnapshot> _durableSubAgentEventsForRun({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  }) {
    final Map<String, OpenCrayChatRuntimeEventSnapshot> latestEventByKey =
        <String, OpenCrayChatRuntimeEventSnapshot>{};
    for (final event in runEvents) {
      if (event.kind != 'subagent') {
        continue;
      }
      final String key = _subAgentRegistryKeyForEvent(event);
      final OpenCrayChatRuntimeEventSnapshot? existing = latestEventByKey[key];
      if (existing == null ||
          event.emittedAtEpochMs >= existing.emittedAtEpochMs) {
        latestEventByKey[key] = event;
      }
    }
    return _subAgentSnapshotsForRun(run: run, runtimeSnapshot: runtimeSnapshot)
        .map(_syntheticSubAgentEvent)
        .where((event) {
          final OpenCrayChatRuntimeEventSnapshot? existing =
              latestEventByKey[_subAgentRegistryKeyForEvent(event)];
          if (existing == null) {
            return true;
          }
          return _subAgentStateSignature(event) !=
                  _subAgentStateSignature(existing) ||
              event.emittedAtEpochMs > existing.emittedAtEpochMs;
        })
        .toList(growable: false)
      ..sort(
        (left, right) =>
            left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
      );
  }

  List<OpenCrayChatSubAgentSnapshot> _detachedSubAgentSnapshots({
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
    required Set<String> visibleParentRunIds,
    required Set<String> visibleParentTaskIds,
  }) =>
      runtimeSnapshot.subAgents
          .where((subAgent) {
            final String parentRunId = subAgent.parentRunId.trim();
            if (parentRunId.isNotEmpty &&
                visibleParentRunIds.contains(parentRunId)) {
              return false;
            }
            final String parentTaskId = subAgent.parentTaskId.trim();
            return parentTaskId.isEmpty ||
                !visibleParentTaskIds.contains(parentTaskId);
          })
          .toList(growable: false)
        ..sort((left, right) {
          final int startedComparison = left.startedAtEpochMs.compareTo(
            right.startedAtEpochMs,
          );
          if (startedComparison != 0) {
            return startedComparison;
          }
          return left.updatedAtEpochMs.compareTo(right.updatedAtEpochMs);
        });

  ChatRunTraceData _mapDetachedSubAgentTrace(
    OpenCrayChatSubAgentSnapshot snapshot,
  ) {
    final OpenCrayChatRuntimeEventSnapshot event = _syntheticSubAgentEvent(
      snapshot,
    );
    final String historyBody = _buildSubagentHistoryBody(event);
    final ChatRunTraceHistoryEntry historyEntry = _subagentHistoryEntry(
      event: event,
      label: _subagentTraceLabel(event),
      body: historyBody,
      isHighRisk: event.isHighRisk,
    );
    final String previewBody = _buildSubagentPreviewBody(event);
    return ChatRunTraceData(
      runId: _detachedSubAgentTraceId(snapshot),
      taskId: snapshot.childTaskId.trim().isNotEmpty
          ? snapshot.childTaskId
          : snapshot.parentTaskId,
      label: _subagentTraceLabel(event),
      body: _buildCompactTraceBody(
        history: <ChatRunTraceHistoryEntry>[historyEntry],
        fallbackBody: previewBody,
        preferredBody: historyBody,
      ),
      history: <ChatRunTraceHistoryEntry>[historyEntry],
      isHighRisk: snapshot.isHighRisk,
      canInterrupt: false,
    );
  }

  OpenCrayChatRuntimeEventSnapshot _syntheticSubAgentEvent(
    OpenCrayChatSubAgentSnapshot snapshot,
  ) => OpenCrayChatRuntimeEventSnapshot(
    kind: 'subagent',
    runId: snapshot.parentRunId,
    taskId: snapshot.parentTaskId,
    emittedAtEpochMs: snapshot.updatedAtEpochMs,
    phase: snapshot.phase,
    status: snapshot.status,
    text: snapshot.summary,
    isHighRisk: snapshot.isHighRisk,
    label: snapshot.label,
    childRunId: snapshot.childRunId,
    childTaskId: snapshot.childTaskId,
    subagentType: snapshot.subagentType,
    contextMode: snapshot.contextMode,
    depth: snapshot.depth,
    executionState: snapshot.executionState,
    continuationKind: snapshot.continuationKind,
    resultMetadata: <String, String>{
      'hasActiveExecution': '${snapshot.hasActiveExecution}',
      'mailboxMessageCount': '${snapshot.mailboxMessageCount}',
      'mailboxPendingMessageCount': '${snapshot.mailboxPendingMessageCount}',
      'hasPendingApprovalResume': '${snapshot.hasPendingApprovalResume}',
      if (snapshot.pendingApprovalToolName?.trim().isNotEmpty == true)
        'pendingApprovalToolName': snapshot.pendingApprovalToolName!.trim(),
      'pendingApprovalIsHighRisk': '${snapshot.pendingApprovalIsHighRisk}',
      if (snapshot.pendingApprovalChildRunId?.trim().isNotEmpty == true)
        'pendingApprovalChildRunId': snapshot.pendingApprovalChildRunId!.trim(),
      if (snapshot.pendingApprovalChildTaskId?.trim().isNotEmpty == true)
        'pendingApprovalChildTaskId': snapshot.pendingApprovalChildTaskId!.trim(),
      if (snapshot.mailboxLastDeliveredMessageId?.trim().isNotEmpty == true)
        'mailboxLastDeliveredMessageId': snapshot.mailboxLastDeliveredMessageId!
            .trim(),
    },
  );

  OpenCrayChatRuntimeEventSnapshot? _effectiveRunTraceEvent({
    required OpenCrayChatRuntimeEventSnapshot? lastEvent,
    required List<OpenCrayChatRuntimeEventSnapshot> durableSubAgentEvents,
  }) {
    final OpenCrayChatRuntimeEventSnapshot? durableEvent =
        durableSubAgentEvents.isEmpty ? null : durableSubAgentEvents.last;
    if (durableEvent == null) {
      return lastEvent;
    }
    if (lastEvent == null) {
      return durableEvent;
    }
    if (durableEvent.emittedAtEpochMs >= lastEvent.emittedAtEpochMs) {
      return durableEvent;
    }
    return lastEvent;
  }

  String _subAgentRegistryKeyForEvent(OpenCrayChatRuntimeEventSnapshot event) {
    final String childRunId = event.childRunId?.trim() ?? '';
    final String childTaskId = event.childTaskId?.trim() ?? '';
    final String label = event.label?.trim() ?? '';
    final String childKey = childTaskId.isNotEmpty
        ? childTaskId
        : (childRunId.isNotEmpty ? childRunId : label);
    final String parentTaskId = event.taskId.trim();
    final String parentRunId = event.runId.trim();
    final String parentKey = parentTaskId.isNotEmpty
        ? parentTaskId
        : parentRunId;
    return '$parentKey|$childKey';
  }

  String _subAgentStateSignature(OpenCrayChatRuntimeEventSnapshot event) =>
      <String>[
        _subAgentRegistryKeyForEvent(event),
        event.phase?.trim().toLowerCase() ?? '',
        event.status?.trim().toLowerCase() ?? '',
        event.executionState?.trim().toLowerCase() ?? '',
        event.continuationKind?.trim().toLowerCase() ?? '',
        event.text?.trim() ?? '',
        event.resultMetadata['hasActiveExecution']?.trim() ?? '',
        event.resultMetadata['mailboxMessageCount']?.trim() ?? '',
        event.resultMetadata['mailboxPendingMessageCount']?.trim() ?? '',
        event.resultMetadata['hasPendingApprovalResume']?.trim() ?? '',
        event.resultMetadata['pendingApprovalToolName']?.trim() ?? '',
        event.resultMetadata['pendingApprovalIsHighRisk']?.trim() ?? '',
        event.resultMetadata['pendingApprovalChildRunId']?.trim() ?? '',
        event.resultMetadata['pendingApprovalChildTaskId']?.trim() ?? '',
        event.resultMetadata['mailboxLastDeliveredMessageId']?.trim() ?? '',
        event.isHighRisk.toString(),
      ].join('|');

  String _detachedSubAgentTraceId(OpenCrayChatSubAgentSnapshot snapshot) {
    final String childRunId = snapshot.childRunId.trim();
    if (childRunId.isNotEmpty) {
      return childRunId;
    }
    final String childTaskId = snapshot.childTaskId.trim();
    if (childTaskId.isNotEmpty) {
      return childTaskId;
    }
    final String parentKey = snapshot.parentRunId.trim().isNotEmpty
        ? snapshot.parentRunId.trim()
        : snapshot.parentTaskId.trim();
    final String label = snapshot.label.trim().replaceAll(RegExp(r'\s+'), '-');
    return 'subagent-$parentKey-$label';
  }

  List<ChatMessageData> _mergeProjectedAssistantPhaseMessages({
    required List<ChatMessageData> messages,
    required OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  }) {
    if (runtimeSnapshot == null) {
      return messages;
    }
    final List<OpenCrayChatRunSnapshot> visibleRuns = _visibleRuns(
      runtimeSnapshot,
    );
    if (visibleRuns.isEmpty) {
      return messages;
    }
    final Map<String, OpenCrayChatRunSnapshot> visibleRunsByRunId =
        <String, OpenCrayChatRunSnapshot>{
          for (final run in visibleRuns)
            if (run.runId.trim().isNotEmpty) run.runId.trim(): run,
        };
    final Map<String, OpenCrayChatRunSnapshot> visibleRunsByTaskId =
        <String, OpenCrayChatRunSnapshot>{
          for (final run in visibleRuns)
            if (run.taskId.trim().isNotEmpty) run.taskId.trim(): run,
        };
    if (visibleRunsByRunId.isEmpty && visibleRunsByTaskId.isEmpty) {
      return messages;
    }
    final Set<String> visibleMessageIds = messages
        .map((message) => message.messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();
    final List<OpenCrayChatRuntimeEventSnapshot> sortedEvents =
        runtimeSnapshot.events.toList(growable: false)..sort(
          (left, right) =>
              left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
        );
    final Set<String> latestPhaseEventIdentities =
        _latestAssistantPhaseEventIdentities(
          runtimeSnapshot: runtimeSnapshot,
          visibleRunsByRunId: visibleRunsByRunId,
          visibleRunsByTaskId: visibleRunsByTaskId,
        );
    final Map<String, List<ChatMessageData>> projectedByAnchorMessageId =
        <String, List<ChatMessageData>>{};
    final List<ChatMessageData> unanchoredProjected = <ChatMessageData>[];
    final Set<String> seenMessageIds = <String>{...visibleMessageIds};
    final Map<String, int> projectionOrderByMessageId = <String, int>{};
    int nextProjectionOrder = 0;
    for (final event in sortedEvents) {
      final String runId = event.runId.trim();
      final String taskId = event.taskId.trim();
      final OpenCrayChatRunSnapshot? run =
          visibleRunsByRunId[runId] ?? visibleRunsByTaskId[taskId];
      if (run == null ||
          !run.matchesRuntimeEvent(event) ||
          event.kind != 'assistant_phase' ||
          event.isFinal == true ||
          _hideAssistantPhaseBubble(event)) {
        continue;
      }
      final String anchorMessageId = run.pendingMessageId?.trim() ?? '';
      final bool anchorVisible =
          anchorMessageId.isNotEmpty &&
          visibleMessageIds.contains(anchorMessageId);
      // The transcript snapshot may lag behind the realtime event feed (for
      // example on the polling local transport); keep narration for active
      // runs visible at the list tail instead of waiting for the anchor.
      if (!anchorVisible && run.isTerminal) {
        continue;
      }
      final List<String> aliases = _assistantPhaseMessageIds(event);
      final String messageId = aliases.first;
      final List<String> claimedAliases = _assistantPhaseClaimMessageIds(
        event,
      );
      if (aliases.any(visibleMessageIds.contains) ||
          claimedAliases.any(seenMessageIds.contains) ||
          !seenMessageIds.add(messageId)) {
        continue;
      }
      seenMessageIds.addAll(claimedAliases);
      final String text = _projectedAssistantPhaseMessageText(event);
      if (text.trim().isEmpty) {
        continue;
      }
      projectionOrderByMessageId[messageId] = nextProjectionOrder++;
      final ChatMessageData projectedMessage = ChatMessageData(
        messageId: messageId,
        kind: ChatMessageKind.inbound,
        text: text,
        runtimeAnchorMessageId: anchorVisible ? anchorMessageId : '',
        createdAtEpochMs: event.emittedAtEpochMs,
        isEphemeral: true,
        isStreaming:
            !run.isTerminal &&
            latestPhaseEventIdentities.contains(
              _assistantPhaseEventIdentity(event),
            ),
      );
      if (anchorVisible) {
        projectedByAnchorMessageId
            .putIfAbsent(anchorMessageId, () => <ChatMessageData>[])
            .add(projectedMessage);
      } else {
        unanchoredProjected.add(projectedMessage);
      }
    }
    for (final run in visibleRuns) {
      final String anchorMessageId = run.pendingMessageId?.trim() ?? '';
      if (anchorMessageId.isEmpty ||
          !visibleMessageIds.contains(anchorMessageId)) {
        continue;
      }
      final List<OpenCrayChatManagedProcessSnapshot> processes =
          run.managedProcesses.toList(growable: false)..sort((left, right) {
            final int leftSortEpochMs = _managedProcessSortEpochMs(left);
            final int rightSortEpochMs = _managedProcessSortEpochMs(right);
            if (leftSortEpochMs != rightSortEpochMs) {
              return leftSortEpochMs.compareTo(rightSortEpochMs);
            }
            return left.processId.compareTo(right.processId);
          });
      for (final process in processes) {
        final String messageId = _projectedManagedProcessMessageId(
          run: run,
          process: process,
        );
        final List<String> aliases = _projectedManagedProcessMessageIds(
          run: run,
          process: process,
        );
        if (aliases.any(seenMessageIds.contains) ||
            !seenMessageIds.add(messageId)) {
          continue;
        }
        seenMessageIds.addAll(aliases);
        final String text = _projectedManagedProcessMessageText(process);
        if (text.trim().isEmpty) {
          continue;
        }
        projectedByAnchorMessageId
            .putIfAbsent(anchorMessageId, () => <ChatMessageData>[])
            .add(
              ChatMessageData(
                messageId: messageId,
                kind: ChatMessageKind.inbound,
                text: text,
                runtimeAnchorMessageId: anchorMessageId,
                createdAtEpochMs: _managedProcessSortEpochMs(process),
                isEphemeral: true,
                isStreaming:
                    !run.isTerminal && _managedProcessIsStreaming(process),
              ),
            );
      }
    }
    if (projectedByAnchorMessageId.isEmpty && unanchoredProjected.isEmpty) {
      return messages;
    }
    int projectionOrderOf(ChatMessageData message) =>
        projectionOrderByMessageId[message.messageId] ?? 0;
    int compareProjected(ChatMessageData left, ChatMessageData right) {
      final int leftEpochMs = left.createdAtEpochMs ?? 0;
      final int rightEpochMs = right.createdAtEpochMs ?? 0;
      if (leftEpochMs != rightEpochMs) {
        return leftEpochMs.compareTo(rightEpochMs);
      }
      final int leftOrder = projectionOrderOf(left);
      final int rightOrder = projectionOrderOf(right);
      if (leftOrder != rightOrder) {
        return leftOrder.compareTo(rightOrder);
      }
      return left.messageId.compareTo(right.messageId);
    }

    final List<ChatMessageData> mergedMessages = <ChatMessageData>[];
    for (final message in messages) {
      final List<ChatMessageData>? projections =
          projectedByAnchorMessageId[message.messageId];
      if (projections != null) {
        mergedMessages.addAll(
          projections.toList(growable: false)..sort(compareProjected),
        );
      }
      mergedMessages.add(message);
    }
    mergedMessages.addAll(
      unanchoredProjected.toList(growable: false)..sort(compareProjected),
    );
    return mergedMessages;
  }

  List<ChatMessageData> _mapUnanchoredLiveDraftMessages(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
    Set<String> existingMessageIds,
  ) {
    if (runtimeSnapshot == null ||
        runtimeSnapshot.liveAssistantDrafts.isEmpty) {
      return const <ChatMessageData>[];
    }
    final List<ChatMessageData> projected = <ChatMessageData>[];
    final Set<String> seenMessageIds = <String>{...existingMessageIds};
    final List<OpenCrayChatLiveAssistantDraftSnapshot> sortedDrafts =
        runtimeSnapshot.liveAssistantDrafts.toList(growable: false)..sort(
          (left, right) =>
              left.updatedAtEpochMs.compareTo(right.updatedAtEpochMs),
        );
    for (final draft in sortedDrafts) {
      final String messageId = draft.pendingMessageId.trim();
      final String text = _visibleAssistantDraftText(draft.text) ?? '';
      if (messageId.isEmpty ||
          text.isEmpty ||
          !_shouldDisplayLiveAssistantDraft(
            runId: draft.runId,
            taskId: draft.taskId,
            executionId: draft.executionId,
            runtimeSnapshot: runtimeSnapshot,
          ) ||
          !seenMessageIds.add(messageId)) {
        continue;
      }
      projected.add(
        ChatMessageData(
          messageId: messageId,
          kind: ChatMessageKind.inbound,
          text: text,
          isEphemeral: true,
          isStreaming: true,
        ),
      );
    }
    return projected;
  }

  bool _shouldDisplayLiveAssistantDraft({
    required String runId,
    required String taskId,
    required String? executionId,
    required OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  }) {
    if (runtimeSnapshot == null) {
      return true;
    }
    final String normalizedRunId = runId.trim();
    final String normalizedTaskId = taskId.trim();
    if (normalizedRunId.isEmpty && normalizedTaskId.isEmpty) {
      return true;
    }
    OpenCrayChatRunSnapshot? matchedRun;
    for (final run in _visibleRuns(runtimeSnapshot)) {
      if (normalizedRunId.isNotEmpty && run.runId.trim() == normalizedRunId) {
        matchedRun = run;
        break;
      }
      if (normalizedTaskId.isNotEmpty &&
          run.taskId.trim() == normalizedTaskId) {
        matchedRun = run;
        break;
      }
    }
    if (matchedRun == null) {
      return true;
    }
    if (!_draftExecutionMatchesRun(executionId, matchedRun)) {
      return false;
    }
    final OpenCrayChatRuntimeEventSnapshot? latestEvent = _latestRunTraceEvent(
      _runEventsFor(run: matchedRun, runtimeSnapshot: runtimeSnapshot),
    );
    if (latestEvent == null) {
      return true;
    }
    if (latestEvent.kind == 'interrupted') {
      return false;
    }
    return latestEvent.kind != 'assistant_phase' ||
        latestEvent.isFinal == true ||
        _hideAssistantPhaseBubble(latestEvent);
  }

  bool _runHasVisibleLiveAssistantDraft({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  }) {
    if (run.isTerminal) {
      return false;
    }
    final String pendingMessageId = run.pendingMessageId?.trim() ?? '';
    if (pendingMessageId.isEmpty) {
      return false;
    }
    for (final draft in runtimeSnapshot.liveAssistantDrafts) {
      if (draft.pendingMessageId.trim() != pendingMessageId ||
          _visibleAssistantDraftText(draft.text)?.isNotEmpty != true ||
          !_shouldDisplayLiveAssistantDraft(
            runId: draft.runId,
            taskId: draft.taskId,
            executionId: draft.executionId,
            runtimeSnapshot: runtimeSnapshot,
          )) {
        continue;
      }
      final String draftRunId = draft.runId.trim();
      final String draftTaskId = draft.taskId.trim();
      if (!_draftExecutionMatchesRun(draft.executionId, run)) {
        continue;
      }
      if ((draftRunId.isNotEmpty && draftRunId == run.runId.trim()) ||
          (draftTaskId.isNotEmpty && draftTaskId == run.taskId.trim()) ||
          (draftRunId.isEmpty && draftTaskId.isEmpty)) {
        return true;
      }
    }
    return false;
  }

  bool _draftExecutionMatchesRun(
    String? draftExecutionId,
    OpenCrayChatRunSnapshot run,
  ) {
    final String normalizedDraftExecutionId = draftExecutionId?.trim() ?? '';
    final String runExecutionId = run.executionId?.trim() ?? '';
    return normalizedDraftExecutionId.isEmpty ||
        runExecutionId.isEmpty ||
        normalizedDraftExecutionId == runExecutionId;
  }

  bool _hideAssistantPhaseBubble(OpenCrayChatRuntimeEventSnapshot event) {
    final String stage = event.stage?.trim().toLowerCase() ?? '';
    return stage == 'llm_retry' || stage == 'responses_recovery';
  }

  String _projectedManagedProcessMessageId({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatManagedProcessSnapshot process,
  }) {
    final String runId = _projectedRuntimeOwnerKey(run);
    final String processId = process.processId.trim();
    if (processId.isNotEmpty) {
      return 'runtime-process-$runId-$processId';
    }
    final String fingerprint = <String>[
      process.command.trim(),
      process.args.join('\u0001'),
      process.workingDirectory?.trim() ?? '',
      process.startedAtEpochMs.toString(),
    ].join('\u0002');
    return 'runtime-process-$runId-fp-${javaStringHashHex(fingerprint)}';
  }

  List<String> _projectedManagedProcessMessageIds({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatManagedProcessSnapshot process,
  }) {
    final List<String> ownerKeys = _projectedRuntimeOwnerKeys(run);
    if (ownerKeys.isEmpty) {
      return <String>[
        _projectedManagedProcessMessageId(run: run, process: process),
      ];
    }
    final String processId = process.processId.trim();
    final String processKey = processId.isNotEmpty
        ? processId
        : 'fp-${javaStringHashHex(_managedProcessFingerprint(process))}';
    return ownerKeys
        .map((ownerKey) => 'runtime-process-$ownerKey-$processKey')
        .toList(growable: false);
  }

  List<String> _projectedRuntimeOwnerKeys(OpenCrayChatRunSnapshot run) {
    final List<String> keys = <String>[];
    final String taskId = run.taskId.trim();
    final String runId = run.runId.trim();
    if (taskId.isNotEmpty) {
      keys.add(taskId);
    }
    if (runId.isNotEmpty && runId != taskId) {
      keys.add(runId);
    }
    return keys;
  }

  String _projectedRuntimeOwnerKey(OpenCrayChatRunSnapshot run) {
    final List<String> keys = _projectedRuntimeOwnerKeys(run);
    if (keys.isNotEmpty) {
      return keys.first;
    }
    return run.acceptedAtEpochMs.toString();
  }

  String _managedProcessFingerprint(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    return <String>[
      process.command.trim(),
      process.args.join('\u0001'),
      process.workingDirectory?.trim() ?? '',
      process.startedAtEpochMs.toString(),
    ].join('\u0002');
  }

  String _projectedManagedProcessMessageText(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final String command = <String>[
      process.command,
      ...process.args,
    ].map((part) => part.trim()).where((part) => part.isNotEmpty).join(' ');
    final String output =
        (process.stdout.isNotEmpty ? process.stdout : process.stdoutPreview)
            .trim();
    return _joinTraceSections(<String?>[
      'Process ${process.processId}',
      '${_managedProcessStatusSummary(process)}: $command',
      output.isEmpty ? null : output,
    ]);
  }

  bool _managedProcessIsStreaming(OpenCrayChatManagedProcessSnapshot process) {
    final String status = process.status.trim().toLowerCase();
    return status.isEmpty || status == 'running';
  }

  ChatRunTraceData _mapRunTrace({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
    required List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals,
  }) {
    final List<OpenCrayChatRuntimeEventSnapshot> runEvents = _runEventsFor(
      run: run,
      runtimeSnapshot: runtimeSnapshot,
    );
    final List<OpenCrayChatRuntimeEventSnapshot> durableSubAgentEvents =
        _durableSubAgentEventsForRun(
          run: run,
          runtimeSnapshot: runtimeSnapshot,
          runEvents: runEvents,
        );
    final OpenCrayChatRuntimeEventSnapshot? event = _effectiveRunTraceEvent(
      lastEvent: _latestRunTraceEvent(runEvents) ?? run.lastEvent,
      durableSubAgentEvents: durableSubAgentEvents,
    );
    final OpenCrayChatPendingApprovalSnapshot? pendingApproval =
        _pendingApprovalForRun(run: run, pendingApprovals: pendingApprovals);
    final OpenCrayChatRuntimeEventSnapshot? latestApprovalEvent =
        _latestApprovalEvent(runEvents);
    final ChatRunTracePreviewCardData? previewCard = _latestRunTracePreviewCard(
      runEvents,
    );
    final ChatRunTraceSandboxSessionCardData? sessionCard =
        _latestRunTraceSandboxSessionCard(runEvents);
    final toolName = event?.toolName?.trim();
    final bool waitingApproval = _isWaitingApproval(
      run: run,
      runEvents: runEvents,
      pendingApproval: pendingApproval,
    );
    final bool canInterrupt =
        !run.isTerminal &&
        (run.runId.trim().isNotEmpty || run.taskId.trim().isNotEmpty);
    List<ChatRunTraceHistoryEntry> history = _buildRunTraceHistory(
      run: run,
      runEvents: runEvents,
      pendingApproval: pendingApproval,
      durableSubAgentEvents: durableSubAgentEvents,
    );
    final bool isWritingAssistantDraft = _runHasVisibleLiveAssistantDraft(
      run: run,
      runtimeSnapshot: runtimeSnapshot,
    );
    _runTraceDebug(
      'feature.mapRunTrace run=${run.runId} task=${run.taskId} events=${runEvents.length} history=${history.length} managedProcesses=${run.managedProcesses.length} runningManagedProcesses=${run.runningManagedProcessCount} liveManagedProcesses=${run.hasLiveManagedProcesses}',
    );
    ChatRunTraceData buildTrace({
      required String label,
      required String body,
      List<ChatRunTraceHistoryEntry>? historyOverride,
      bool isHighRisk = false,
      bool? canInterruptOverride,
      String? retryLabel,
    }) => ChatRunTraceData(
      runId: run.runId,
      taskId: run.taskId,
      anchorMessageId: run.pendingMessageId?.trim() ?? '',
      label: label,
      body: body,
      history: historyOverride ?? history,
      isHighRisk: isHighRisk,
      isTerminal: run.isTerminal,
      canInterrupt: canInterruptOverride ?? canInterrupt,
      isWritingAssistantDraft: isWritingAssistantDraft,
      retryLabel: retryLabel,
      previewCard: previewCard,
      sessionCard: sessionCard,
    );
    if (_isInterruptedOnRestoreRun(run)) {
      final interruptedEntry = _mainHistoryEntry(
        label: _interruptedRunLabel(),
        body: _interruptedRunBody(run),
      );
      if (history.isEmpty ||
          history.last.label != interruptedEntry.label ||
          history.last.body != interruptedEntry.body) {
        history = <ChatRunTraceHistoryEntry>[...history, interruptedEntry];
      }
      return buildTrace(
        label: interruptedEntry.label,
        body: _buildCompactTraceBody(
          history: history,
          fallbackBody: interruptedEntry.body,
        ),
        historyOverride: history,
        canInterruptOverride: false,
        retryLabel: _retryInterruptedRunLabel(),
      );
    }
    if (_isLlmRetryPausedRun(run)) {
      final pausedEntry = _mainHistoryEntry(
        label: _pausedRunLabel(),
        body: _pausedRunBody(),
      );
      if (history.isEmpty ||
          history.last.label != pausedEntry.label ||
          history.last.body != pausedEntry.body) {
        history = <ChatRunTraceHistoryEntry>[...history, pausedEntry];
      }
      return buildTrace(
        label: pausedEntry.label,
        body: _buildCompactTraceBody(
          history: history,
          fallbackBody: pausedEntry.body,
        ),
        historyOverride: history,
        canInterruptOverride: false,
        retryLabel: copy.chatRunResumeAction,
      );
    }
    if (_isDeferredApprovalDecisionRun(run)) {
      final pausedEntry = _mainHistoryEntry(
        label: _pausedRunLabel(),
        body: _deferredApprovalDecisionBody(),
      );
      if (history.isEmpty ||
          history.last.label != pausedEntry.label ||
          history.last.body != pausedEntry.body) {
        history = <ChatRunTraceHistoryEntry>[...history, pausedEntry];
      }
      return buildTrace(
        label: pausedEntry.label,
        body: _buildCompactTraceBody(
          history: history,
          fallbackBody: pausedEntry.body,
        ),
        historyOverride: history,
        canInterruptOverride: false,
        retryLabel: copy.chatRunResumeAction,
      );
    }
    final _ResolvedApprovalPreview? resolvedApprovalPreview =
        _shouldShowResolvedApprovalPreview(
          run: run,
          event: event,
          latestApprovalEvent: latestApprovalEvent,
          pendingApproval: pendingApproval,
        )
        ? _buildResolvedApprovalPreview(
            run: run,
            runEvents: runEvents,
            latestApprovalEvent: latestApprovalEvent,
          )
        : null;
    if (resolvedApprovalPreview != null) {
      final ChatRunTraceHistoryEntry resolvedEntry = _mainHistoryEntry(
        label: resolvedApprovalPreview.label,
        body: resolvedApprovalPreview.body,
        compactBody: resolvedApprovalPreview.body,
      );
      final List<ChatRunTraceHistoryEntry> baseHistory = history.isEmpty
          ? history
          : history.sublist(0, history.length - 1);
      final List<ChatRunTraceHistoryEntry> resolvedHistory =
          baseHistory.isNotEmpty &&
              baseHistory.last.label == resolvedEntry.label &&
              baseHistory.last.body == resolvedEntry.body
          ? baseHistory
          : <ChatRunTraceHistoryEntry>[...baseHistory, resolvedEntry];
      return buildTrace(
        label: resolvedApprovalPreview.label,
        body: resolvedApprovalPreview.body,
        historyOverride: resolvedHistory,
      );
    }
    String compactBody(String fallbackBody, {String? preferredBody}) =>
        _buildCompactTraceBody(
          history: history,
          fallbackBody: fallbackBody,
          preferredBody: preferredBody ?? fallbackBody,
        );
    final OpenCrayChatRuntimeEventSnapshot? pairedToolCall =
        event?.kind == 'tool_result'
        ? _findPreviousToolCall(
            runEvents,
            beforeIndex: runEvents.length,
            toolName: toolName,
          )
        : null;
    switch (event?.kind) {
      case 'approval_wait':
        return buildTrace(
          label: _approvalTraceLabel(event!),
          body: compactBody(_buildApprovalPreviewBody(event)),
          isHighRisk:
              event.isHighRisk ||
              (waitingApproval &&
                  run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED'),
        );
      case 'approval_result':
        return buildTrace(
          label: _approvalTraceLabel(event!),
          body: compactBody(_buildApprovalPreviewBody(event)),
          isHighRisk: event.isHighRisk,
        );
      case 'interrupted':
        return buildTrace(
          label: _cancellationTraceLabel(event!),
          body: compactBody(_buildCancellationPreviewBody(event)),
        );
      case 'subagent':
        final OpenCrayChatRuntimeEventSnapshot subagentEvent = event!;
        final String previewBody = _buildSubagentPreviewBody(subagentEvent);
        return buildTrace(
          label: _subagentTraceLabel(subagentEvent),
          body: compactBody(
            previewBody,
            preferredBody: _buildSubagentHistoryBody(subagentEvent),
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'supplement':
        return buildTrace(
          label: _supplementTraceLabel(),
          body: compactBody(_buildSupplementPreviewBody(event!)),
        );
      case 'tool_call':
        return buildTrace(
          label: toolName?.isNotEmpty == true
              ? toolName!
              : copy.chatRunWorkingLabel,
          body: compactBody(_buildToolCallPreviewBody(event!)),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'tool_result':
        return buildTrace(
          label: waitingApproval
              ? copy.chatRunWaitingApprovalLabel
              : toolName?.isNotEmpty == true
              ? toolName!
              : copy.chatRunWorkingLabel,
          body: compactBody(
            _buildToolResultPreviewBody(
              event: event!,
              pairedToolCall: pairedToolCall,
              waitingApproval: waitingApproval,
              runErrorMessage: run.errorMessage,
            ),
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'memory_retrieval':
        return buildTrace(
          label: toolName?.isNotEmpty == true
              ? toolName!
              : copy.chatRunWorkingLabel,
          body: compactBody(_buildMemoryRetrievalPreviewBody(event!)),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'memory_write':
        return buildTrace(
          label: _memoryMaintenanceLabel(),
          body: compactBody(_buildMemoryWritePreviewBody(event!)),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'assistant':
        final text = event?.text?.trim();
        return buildTrace(
          label: copy.chatRunWorkingLabel,
          body: compactBody(
            text?.isNotEmpty == true
                ? text!
                : copy.chatRunThinkingActive,
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'assistant_phase':
        final text = event?.text?.trim();
        return buildTrace(
          label: _assistantPhaseEntryLabel(event!),
          body: compactBody(
            text?.isNotEmpty == true
                ? text!
                : copy.chatRunThinkingActive,
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      default:
        return buildTrace(
          label: waitingApproval
              ? copy.chatRunWaitingApprovalLabel
              : copy.chatRunWorkingLabel,
          body: compactBody(
            waitingApproval
                ? run.errorMessage?.trim().isNotEmpty == true
                      ? run.errorMessage!.trim()
                      : copy.chatRunWaitingApprovalLabel
                : copy.chatRunThinkingActive,
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
    }
  }

  List<ChatRunTraceHistoryEntry> _buildRunTraceHistory({
    required OpenCrayChatRunSnapshot run,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
    required OpenCrayChatPendingApprovalSnapshot? pendingApproval,
    List<OpenCrayChatRuntimeEventSnapshot> durableSubAgentEvents =
        const <OpenCrayChatRuntimeEventSnapshot>[],
  }) {
    final timedHistory = <_TimedChatRunTraceHistoryEntry>[];
    final consumedIndexes = <int>{};
    int nextSourceOrder = 0;
    final Set<String> durableSubAgentKeys = durableSubAgentEvents
        .map(_subAgentRegistryKeyForEvent)
        .toSet();
    for (int index = 0; index < runEvents.length; index += 1) {
      if (consumedIndexes.contains(index)) {
        continue;
      }
      if (runEvents[index].kind == 'subagent' &&
          durableSubAgentKeys.contains(
            _subAgentRegistryKeyForEvent(runEvents[index]),
          )) {
        continue;
      }
      final mapped = _mapRunTraceHistoryEntry(
        event: runEvents[index],
        runEvents: runEvents,
        index: index,
        consumedIndexes: consumedIndexes,
      );
      if (mapped != null) {
        timedHistory.add(
          _TimedChatRunTraceHistoryEntry(
            sortEpochMs: runEvents[index].emittedAtEpochMs,
            sourceOrder: nextSourceOrder++,
            entry: mapped,
          ),
        );
      }
    }
    final Set<String> appendedSubAgentStates = runEvents
        .where((event) => event.kind == 'subagent')
        .map(_subAgentStateSignature)
        .toSet();
    for (final event in durableSubAgentEvents) {
      final String signature = _subAgentStateSignature(event);
      if (!appendedSubAgentStates.add(signature)) {
        continue;
      }
      timedHistory.add(
        _TimedChatRunTraceHistoryEntry(
          sortEpochMs: event.emittedAtEpochMs,
          sourceOrder: nextSourceOrder++,
          entry: _subagentHistoryEntry(
            event: event,
            label: _subagentTraceLabel(event),
            body: _buildSubagentHistoryBody(event),
            isHighRisk: event.isHighRisk,
          ),
        ),
      );
    }
    for (final process in _orderedManagedProcesses(run)) {
      timedHistory.add(
        _TimedChatRunTraceHistoryEntry(
          sortEpochMs: _managedProcessSortEpochMs(process),
          sourceOrder: nextSourceOrder++,
          entry: _managedProcessHistoryEntry(process),
        ),
      );
    }
    final ChatRunTraceHistoryEntry? finalAttachmentHistory =
        _buildRunFinalAttachmentHistoryEntry(run);
    if (finalAttachmentHistory != null) {
      timedHistory.add(
        _TimedChatRunTraceHistoryEntry(
          sortEpochMs: run.updatedAtEpochMs,
          sourceOrder: nextSourceOrder++,
          entry: finalAttachmentHistory,
        ),
      );
    }
    timedHistory.sort((
      _TimedChatRunTraceHistoryEntry left,
      _TimedChatRunTraceHistoryEntry right,
    ) {
      if (left.sortEpochMs != right.sortEpochMs) {
        return left.sortEpochMs.compareTo(right.sortEpochMs);
      }
      return left.sourceOrder.compareTo(right.sourceOrder);
    });
    final history = timedHistory
        .map((_TimedChatRunTraceHistoryEntry timedEntry) => timedEntry.entry)
        .toList(growable: true);
    final List<ChatRunTraceHistoryEntry> contextHistory =
        _buildRunContextHistory(run);
    if (contextHistory.isNotEmpty) {
      final int insertionIndex =
          history.isNotEmpty &&
              history.first.label == copy.chatRunWorkingLabel &&
              history.first.body == copy.chatRunThinkingActive
          ? 1
          : 0;
      history.insertAll(insertionIndex, contextHistory);
    }
    final bool hasApprovalWaitEvent = runEvents.any(
      (event) => event.kind == 'approval_wait',
    );
    if (pendingApproval != null && !hasApprovalWaitEvent) {
      final String? approvalBody =
          _nonEmpty(pendingApproval.body) ?? _nonEmpty(run.errorMessage);
      final waitingEntry = _mainHistoryEntry(
        label: copy.chatRunWaitingApprovalLabel,
        body: approvalBody?.isNotEmpty == true
            ? approvalBody!
            : copy.chatRunWaitingApprovalLabel,
        isHighRisk: pendingApproval.isHighRisk,
      );
      if (history.isEmpty ||
          history.last.label != waitingEntry.label ||
          history.last.body != waitingEntry.body) {
        history.add(waitingEntry);
      }
    }
    if (history.isEmpty) {
      history.add(
        _mainHistoryEntry(
          label: copy.chatRunWorkingLabel,
          body: copy.chatRunThinkingActive,
        ),
      );
    }
    return history;
  }

  String get _mainInspectorActorId => _runTraceMainActorId;

  String _mainInspectorActorLabel() =>
      copy.isChinese ? '主代理' : 'Main agent';

  String _subagentInspectorActorId(OpenCrayChatRuntimeEventSnapshot event) {
    final String? explicitId =
        _nonEmpty(event.childRunId) ?? _nonEmpty(event.childTaskId);
    if (explicitId != null) {
      return explicitId;
    }
    return [
      _nonEmpty(event.subagentType),
      _nonEmpty(event.label),
      event.emittedAtEpochMs.toString(),
    ].whereType<String>().join(':');
  }

  String _subagentInspectorActorLabel(OpenCrayChatRuntimeEventSnapshot event) =>
      _subagentTraceLabel(event);

  ChatRunTraceHistoryEntry _mainHistoryEntry({
    required String label,
    required String body,
    String? compactBody,
    bool isHighRisk = false,
    List<ChatRunTraceInspectorTextPart> inspectorCallParts =
        const <ChatRunTraceInspectorTextPart>[],
    String inspectorCallDetail = '',
    String inspectorResultBody = '',
  }) {
    return ChatRunTraceHistoryEntry(
      label: label,
      body: body,
      compactBody: compactBody,
      isHighRisk: isHighRisk,
      inspectorActorId: _mainInspectorActorId,
      inspectorActorLabel: _mainInspectorActorLabel(),
      inspectorCallParts: inspectorCallParts,
      inspectorCallDetail: inspectorCallDetail,
      inspectorResultBody: inspectorResultBody,
    );
  }

  ChatRunTraceHistoryEntry _subagentHistoryEntry({
    required OpenCrayChatRuntimeEventSnapshot event,
    required String label,
    required String body,
    String? compactBody,
    bool isHighRisk = false,
  }) {
    return ChatRunTraceHistoryEntry(
      label: label,
      body: body,
      compactBody: compactBody,
      isHighRisk: isHighRisk,
      inspectorActorId: _subagentInspectorActorId(event),
      inspectorActorLabel: _subagentInspectorActorLabel(event),
    );
  }

  List<OpenCrayChatManagedProcessSnapshot> _orderedManagedProcesses(
    OpenCrayChatRunSnapshot run,
  ) => run.managedProcesses.toList(growable: false)
    ..sort((left, right) {
      final int leftSortEpochMs = _managedProcessSortEpochMs(left);
      final int rightSortEpochMs = _managedProcessSortEpochMs(right);
      if (leftSortEpochMs != rightSortEpochMs) {
        return leftSortEpochMs.compareTo(rightSortEpochMs);
      }
      if (left.updatedAtEpochMs != right.updatedAtEpochMs) {
        return left.updatedAtEpochMs.compareTo(right.updatedAtEpochMs);
      }
      return left.processId.compareTo(right.processId);
    });

  int _managedProcessSortEpochMs(OpenCrayChatManagedProcessSnapshot process) {
    return process.startedAtEpochMs > 0
        ? process.startedAtEpochMs
        : process.updatedAtEpochMs;
  }

  ChatRunTraceHistoryEntry? _buildRunFinalAttachmentHistoryEntry(
    OpenCrayChatRunSnapshot run,
  ) {
    if (run.finalAttachments.isEmpty) {
      return null;
    }
    final String label = copy.isChinese ? '最终附件' : 'Final attachments';
    final String compactBody = run.finalAttachments
        .map(_finalAttachmentTitle)
        .where((title) => title.trim().isNotEmpty)
        .join(', ');
    final String inspectorResultBody = run.finalAttachments
        .map(_finalAttachmentInspectorSection)
        .where((section) => section.trim().isNotEmpty)
        .join('\n\n');
    return _mainHistoryEntry(
      label: label,
      body: compactBody.isNotEmpty ? compactBody : label,
      compactBody: compactBody.isNotEmpty ? compactBody : null,
      inspectorCallParts: <ChatRunTraceInspectorTextPart>[
        _inspectorAction(label),
      ],
      inspectorResultBody: inspectorResultBody,
    );
  }

  String _finalAttachmentTitle(OpenCrayChatAttachmentSnapshot attachment) {
    final String displayName = attachment.displayName.trim();
    if (displayName.isNotEmpty) {
      return displayName;
    }
    final String localPath = attachment.localPath.trim();
    if (localPath.isNotEmpty) {
      return localPath.split('/').last;
    }
    final String attachmentId = attachment.attachmentId.trim();
    if (attachmentId.isNotEmpty) {
      return attachmentId;
    }
    return copy.isChinese ? '未命名附件' : 'Unnamed attachment';
  }

  String _finalAttachmentInspectorSection(
    OpenCrayChatAttachmentSnapshot attachment,
  ) {
    final List<String> sections = <String>[
      _joinTraceSections(<String?>[
        copy.isChinese
            ? '名称：${_finalAttachmentTitle(attachment)}'
            : 'Name: ${_finalAttachmentTitle(attachment)}',
        copy.isChinese
            ? '类型：${attachment.kind}'
            : 'Kind: ${attachment.kind}',
        _nonEmpty(attachment.mimeType) != null
            ? (copy.isChinese
                  ? 'MIME：${attachment.mimeType!}'
                  : 'MIME: ${attachment.mimeType!}')
            : null,
        _nonEmpty(attachment.localPath) != null
            ? (copy.isChinese
                  ? '路径：${attachment.localPath}'
                  : 'Path: ${attachment.localPath}')
            : null,
        attachment.sizeBytes != null
            ? (copy.isChinese
                  ? '大小：${attachment.sizeBytes} bytes'
                  : 'Size: ${attachment.sizeBytes} bytes')
            : null,
        attachment.widthPx != null && attachment.heightPx != null
            ? (copy.isChinese
                  ? '尺寸：${attachment.widthPx} x ${attachment.heightPx}'
                  : 'Dimensions: ${attachment.widthPx} x ${attachment.heightPx}')
            : null,
        attachment.durationMs != null
            ? (copy.isChinese
                  ? '时长：${attachment.durationMs} ms'
                  : 'Duration: ${attachment.durationMs} ms')
            : null,
        attachment.waveformBars.isNotEmpty
            ? (copy.isChinese
                  ? '波形：${attachment.waveformBars.join(', ')}'
                  : 'Waveform: ${attachment.waveformBars.join(', ')}')
            : null,
        _nonEmpty(attachment.transcriptText) != null
            ? (copy.isChinese
                  ? '转写：${attachment.transcriptText!}'
                  : 'Transcript: ${attachment.transcriptText!}')
            : null,
        _nonEmpty(attachment.contentSha256) != null
            ? (copy.isChinese
                  ? 'SHA256：${attachment.contentSha256!}'
                  : 'SHA256: ${attachment.contentSha256!}')
            : null,
      ]),
    ];
    return sections.where((section) => section.trim().isNotEmpty).join('\n\n');
  }

  ChatRunTraceHistoryEntry _managedProcessHistoryEntry(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final String label = _managedProcessHistoryLabel(process);
    final String commandSummary = _managedProcessCommandSummary(process);
    final String compactBody =
        '${_managedProcessStatusSummary(process)}: $commandSummary';
    final String inspectorDetail = _joinTraceSections(<String?>[
      commandSummary,
      process.workingDirectory?.trim().isNotEmpty == true
          ? (copy.isChinese
                ? '目录：${process.workingDirectory!}'
                : 'cwd: ${process.workingDirectory!}')
          : null,
    ]);
    final String inspectorResultBody = _managedProcessInspectorResultBody(
      process,
    );
    return _mainHistoryEntry(
      label: label,
      body: inspectorResultBody.isNotEmpty ? inspectorResultBody : compactBody,
      compactBody: compactBody,
      inspectorCallParts: <ChatRunTraceInspectorTextPart>[
        _inspectorAction(copy.isChinese ? '进程 ' : 'Process '),
        _inspectorTarget(process.processId),
      ],
      inspectorCallDetail: inspectorDetail,
      inspectorResultBody: inspectorResultBody,
    );
  }

  String _managedProcessHistoryLabel(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    return copy.isChinese
        ? '进程 ${process.processId}'
        : 'Process ${process.processId}';
  }

  String _managedProcessCommandSummary(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final List<String> parts = <String>[process.command, ...process.args];
    return parts
        .map((part) => part.trim())
        .where((part) => part.isNotEmpty)
        .join(' ');
  }

  String _managedProcessStatusSummary(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final String status = process.status.trim().toLowerCase();
    switch (status) {
      case 'running':
        return copy.isChinese ? '运行中' : 'running';
      case 'success':
        return copy.isChinese ? '已完成' : 'finished';
      case 'failed':
      case 'spawn_error':
        return copy.isChinese ? '失败' : 'failed';
      case 'cancelled':
        return copy.isChinese ? '已取消' : 'cancelled';
      case 'timeout':
        return copy.isChinese ? '已超时' : 'timed out';
      default:
        return status.isEmpty
            ? (copy.isChinese ? '未知' : 'unknown')
            : status;
    }
  }

  String _managedProcessInspectorResultBody(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final String statusLine = _managedProcessInspectorStatusLine(process);
    final String stdoutContent = process.stdout.isNotEmpty
        ? process.stdout
        : process.stdoutPreview;
    final String stderrContent = process.stderr.isNotEmpty
        ? process.stderr
        : process.stderrPreview;
    final bool stdoutUsesPreviewFallback =
        stdoutContent == process.stdoutPreview;
    final bool stderrUsesPreviewFallback =
        stderrContent == process.stderrPreview;
    final String stdoutSection = _managedProcessOutputSection(
      label: 'stdout',
      content: stdoutContent,
      truncated:
          process.outputLimitExceeded ||
          (stdoutUsesPreviewFallback && process.stdoutTruncated),
    );
    final String stderrSection = _managedProcessOutputSection(
      label: 'stderr',
      content: stderrContent,
      truncated: stderrUsesPreviewFallback && process.stderrTruncated,
    );
    return _joinTraceSections(<String?>[
      statusLine,
      stdoutSection,
      stderrSection,
      _nonEmpty(process.errorMessage),
    ]);
  }

  String _managedProcessInspectorStatusLine(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final List<String> suffixes = <String>[
      if (process.exitCode != null) 'exit ${process.exitCode}',
      if (_nonEmpty(process.errorCode) != null) process.errorCode!,
    ];
    final String suffix = suffixes.isEmpty ? '' : ' (${suffixes.join(', ')})';
    return copy.isChinese
        ? '状态：${_managedProcessStatusSummary(process)}$suffix'
        : 'status: ${_managedProcessStatusSummary(process)}$suffix';
  }

  String _managedProcessOutputSection({
    required String label,
    required String content,
    required bool truncated,
  }) {
    final String normalized = content.trim();
    if (normalized.isEmpty) {
      return '';
    }
    final String suffix = truncated
        ? (copy.isChinese ? '\n[输出已截断]' : '\n[output truncated]')
        : '';
    return '$label\n$normalized$suffix';
  }

  ChatRunTraceHistoryEntry? _mapRunTraceHistoryEntry({
    required OpenCrayChatRuntimeEventSnapshot event,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
    required int index,
    required Set<int> consumedIndexes,
  }) {
    final toolName = _canonicalToolName(event.toolName);
    switch (event.kind) {
      case 'lifecycle':
        if (event.phase?.toLowerCase() == 'start') {
          return _mainHistoryEntry(
            label: copy.chatRunWorkingLabel,
            body: copy.chatRunThinkingActive,
          );
        }
        return null;
      case 'tool_call':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : copy.chatRunWorkingLabel;
        final int? pairedResultIndex = _findNextToolResultIndex(
          runEvents,
          afterIndex: index,
          toolName: resolvedToolName,
        );
        final OpenCrayChatRuntimeEventSnapshot? pairedResult =
            pairedResultIndex == null ? null : runEvents[pairedResultIndex];
        if (pairedResultIndex != null) {
          consumedIndexes.add(pairedResultIndex);
        }
        return _buildGroupedToolHistoryEntry(
          toolName: resolvedToolName,
          toolCallEvent: event,
          toolResultEvent: pairedResult,
        );
      case 'tool_result':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : copy.chatRunWorkingLabel;
        final OpenCrayChatRuntimeEventSnapshot? pairedToolCall =
            _findPreviousToolCall(
              runEvents,
              beforeIndex: index,
              toolName: resolvedToolName,
            );
        return _buildGroupedToolHistoryEntry(
          toolName: resolvedToolName,
          toolCallEvent: pairedToolCall,
          toolResultEvent: event,
        );
      case 'approval_wait':
      case 'approval_result':
        return _mainHistoryEntry(
          label: _approvalTraceLabel(event),
          body: _buildApprovalHistoryBody(event),
          isHighRisk: event.isHighRisk,
        );
      case 'interrupted':
        return _mainHistoryEntry(
          label: _cancellationTraceLabel(event),
          body: _buildCancellationHistoryBody(event),
        );
      case 'subagent':
        return _subagentHistoryEntry(
          event: event,
          label: _subagentTraceLabel(event),
          body: _buildSubagentHistoryBody(event),
        );
      case 'supplement':
        return _mainHistoryEntry(
          label: _supplementTraceLabel(),
          body: _buildSupplementHistoryBody(event),
        );
      case 'memory_retrieval':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : copy.chatRunWorkingLabel;
        return _mainHistoryEntry(
          label: resolvedToolName,
          body: _buildMemoryRetrievalHistoryBody(event),
        );
      case 'memory_write':
        return _mainHistoryEntry(
          label: _memoryMaintenanceLabel(),
          body: _buildMemoryWriteHistoryBody(event),
        );
      case 'assistant':
        final text = event.text?.trim();
        return _mainHistoryEntry(
          label: copy.chatRunWorkingLabel,
          body: text?.isNotEmpty == true
              ? text!
              : copy.chatRunThinkingActive,
        );
      case 'assistant_phase':
        final text = event.text?.trim();
        return _mainHistoryEntry(
          label: _assistantPhaseEntryLabel(event),
          body: text?.isNotEmpty == true
              ? text!
              : copy.chatRunThinkingActive,
        );
      default:
        return null;
    }
  }

  List<ChatRunTraceHistoryEntry> _buildRunContextHistory(
    OpenCrayChatRunSnapshot run,
  ) {
    final history = <ChatRunTraceHistoryEntry>[];
    final String? liveContextBody = _buildRunLiveContextHistoryBody(
      run.liveContext,
    );
    final String? memoryTraceBody = _buildRunMemoryTraceHistoryBody(
      run.memoryTrace,
    );
    final String? stickyMemoryBody = _buildRunStickyMemoryHistoryBody(
      run.stickyMemory,
    );
    final String? memoryFlushBody = _buildRunMemoryFlushHistoryBody(
      run.memoryFlush,
    );
    final String? bootstrapBody = _buildRunBootstrapHistoryBody(run.bootstrap);
    final String? durableCompactionBody = _buildRunDurableCompactionHistoryBody(
      run.durableCompaction,
    );
    final String? skillInventoryBody = _buildRunSkillInventoryHistoryBody(
      run.skillInventory,
    );
    final String? activeSkillBody = _buildRunActiveSkillHistoryBody(
      run.activeSkill,
    );
    if (liveContextBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(english: 'Live Context', chinese: '实时上下文'),
          body: liveContextBody,
        ),
      );
    }
    if (bootstrapBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(english: 'Bootstrap', chinese: '启动上下文'),
          body: bootstrapBody,
        ),
      );
    }
    if (memoryTraceBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(
            english: 'Retrieved Memory',
            chinese: '记忆召回',
          ),
          body: memoryTraceBody,
        ),
      );
    }
    if (stickyMemoryBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(english: 'Sticky Memory', chinese: '固定记忆'),
          body: stickyMemoryBody,
        ),
      );
    }
    if (memoryFlushBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(english: 'Memory Flush', chinese: '记忆刷新'),
          body: memoryFlushBody,
        ),
      );
    }
    if (durableCompactionBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(
            english: 'Durable Compaction',
            chinese: '持久压缩',
          ),
          body: durableCompactionBody,
        ),
      );
    }
    if (skillInventoryBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(
            english: 'Skill Inventory',
            chinese: '技能清单',
          ),
          body: skillInventoryBody,
        ),
      );
    }
    if (activeSkillBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(english: 'Active Skill', chinese: '活动技能'),
          body: activeSkillBody,
        ),
      );
    }
    return history;
  }

  String? _buildRunLiveContextHistoryBody(
    OpenCrayChatRunLiveContextSnapshot? liveContext,
  ) {
    if (liveContext == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (_nonEmpty(liveContext.mode) != null)
        copy.isChinese
            ? '模式 ${liveContext.mode}'
            : 'Mode: ${liveContext.mode}',
      if (liveContext.soulEnabled != null)
        copy.isChinese
            ? (liveContext.soulEnabled! ? 'Soul 已启用' : 'Soul 已关闭')
            : (liveContext.soulEnabled! ? 'Soul enabled' : 'Soul disabled'),
      if (liveContext.memoryRecallEnabled != null)
        copy.isChinese
            ? (liveContext.memoryRecallEnabled! ? '自动记忆召回已启用' : '自动记忆召回已关闭')
            : (liveContext.memoryRecallEnabled!
                  ? 'Automatic memory recall enabled'
                  : 'Automatic memory recall disabled'),
    ];
    return summary.isEmpty
        ? null
        : summary.join(copy.isChinese ? '，' : ', ');
  }

  String? _buildRunMemoryTraceHistoryBody(
    OpenCrayChatRunMemoryTraceSnapshot? trace,
  ) {
    if (trace == null) {
      return null;
    }
    final List<String> countParts = <String>[
      if (trace.matchedRecordCount != null)
        copy.isChinese
            ? '命中 ${trace.matchedRecordCount} 条'
            : '${trace.matchedRecordCount} matched',
      if (trace.injectedRecordCount != null)
        copy.isChinese
            ? '注入 ${trace.injectedRecordCount} 条'
            : '${trace.injectedRecordCount} injected',
      if (trace.omittedRecordCount != null)
        copy.isChinese
            ? '省略 ${trace.omittedRecordCount} 条'
            : '${trace.omittedRecordCount} omitted',
    ];
    final String? queryTerms = trace.queryTerms.isEmpty
        ? null
        : copy.isChinese
        ? '关键词：${trace.queryTerms.join(', ')}'
        : 'Query terms: ${trace.queryTerms.join(', ')}';
    final String? selected = trace.selected.isEmpty
        ? null
        : _labeledMultilineSection(
            englishLabel: 'Selected',
            chineseLabel: '已注入',
            values: trace.selected
                .map(_formatRunMemorySelectedSummary)
                .toList(),
          );
    final String? omitted = trace.omitted.isEmpty
        ? null
        : _labeledMultilineSection(
            englishLabel: 'Omitted',
            chineseLabel: '已省略',
            values: trace.omitted.map(_formatRunMemoryOmittedSummary).toList(),
          );
    final String? filteredCounts = trace.filteredCounts.isEmpty
        ? null
        : copy.isChinese
        ? '过滤统计：${trace.filteredCounts.entries.map((entry) => '${entry.key} ${entry.value}').join('，')}'
        : 'Filtered counts: ${trace.filteredCounts.entries.map((entry) => '${entry.key} ${entry.value}').join(', ')}';
    return _joinTraceSections(<String?>[
      countParts.isEmpty
          ? null
          : countParts.join(copy.isChinese ? '，' : ', '),
      queryTerms,
      selected,
      omitted,
      filteredCounts,
    ]);
  }

  String _formatRunMemorySelectedSummary(
    OpenCrayChatRunMemorySelectedSnapshot selected,
  ) {
    final List<String> parts = <String>[selected.id];
    if (selected.score != null) {
      parts.add(
        copy.isChinese
            ? '分数 ${selected.score}'
            : 'score ${selected.score}',
      );
    }
    if (selected.matchedTerms.isNotEmpty) {
      parts.add(
        copy.isChinese
            ? '匹配 ${selected.matchedTerms.join(', ')}'
            : 'matched ${selected.matchedTerms.join(', ')}',
      );
    }
    return parts.join(copy.isChinese ? '，' : ', ');
  }

  String _formatRunMemoryOmittedSummary(
    OpenCrayChatRunMemoryOmittedSnapshot omitted,
  ) {
    if (omitted.reason.trim().isEmpty) {
      return omitted.id;
    }
    return copy.isChinese
        ? '${omitted.id}，原因 ${omitted.reason}'
        : '${omitted.id}, reason ${omitted.reason}';
  }

  String? _buildRunStickyMemoryHistoryBody(
    OpenCrayChatRunStickyMemorySnapshot? stickyMemory,
  ) {
    if (stickyMemory == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (stickyMemory.injectedRecordCount != null)
        copy.isChinese
            ? '注入 ${stickyMemory.injectedRecordCount} 条'
            : '${stickyMemory.injectedRecordCount} injected',
      if (stickyMemory.omittedRecordCount != null)
        copy.isChinese
            ? '省略 ${stickyMemory.omittedRecordCount} 条'
            : '${stickyMemory.omittedRecordCount} omitted',
    ];
    final String? recordIds = stickyMemory.recordIds.isEmpty
        ? null
        : _labeledMultilineSection(
            englishLabel: 'Pinned record ids',
            chineseLabel: '固定记录',
            values: stickyMemory.recordIds,
          );
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(copy.isChinese ? '，' : ', '),
      recordIds,
    ]);
  }

  String? _buildRunMemoryFlushHistoryBody(
    OpenCrayChatRunMemoryFlushSnapshot? flush,
  ) {
    if (flush == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (flush.outcome != null)
        copy.isChinese
            ? '结果 ${flush.outcome}'
            : 'Outcome: ${flush.outcome}',
      if (flush.candidateCount != null)
        copy.isChinese
            ? '候选 ${flush.candidateCount} 条'
            : '${flush.candidateCount} candidate(s)',
      if (flush.writtenRecordCount != null)
        copy.isChinese
            ? '写入 ${flush.writtenRecordCount} 条'
            : '${flush.writtenRecordCount} written',
    ];
    final List<String> omitted = <String>[
      if (flush.omittedMessageCount != null)
        copy.isChinese
            ? '省略消息 ${flush.omittedMessageCount} 条'
            : '${flush.omittedMessageCount} omitted message(s)',
      if (flush.omittedCharCount != null)
        copy.isChinese
            ? '省略字符 ${flush.omittedCharCount}'
            : '${flush.omittedCharCount} omitted char(s)',
    ];
    final List<String> pressure = <String>[
      if (_nonEmpty(flush.triggerStage) != null)
        copy.isChinese
            ? '触发 ${flush.triggerStage}'
            : 'Trigger: ${flush.triggerStage}',
      if (_nonEmpty(flush.executionMode) != null)
        copy.isChinese
            ? '模式 ${flush.executionMode}'
            : 'Mode: ${flush.executionMode}',
      if (flush.contextWindowTokens != null)
        copy.isChinese
            ? '窗口 ${flush.contextWindowTokens}'
            : 'Window ${flush.contextWindowTokens}',
      if (flush.previousContextWindowTokens != null)
        copy.isChinese
            ? '原窗口 ${flush.previousContextWindowTokens}'
            : 'Previous window ${flush.previousContextWindowTokens}',
      if (flush.autoCompactTokenLimit != null)
        copy.isChinese
            ? '阈值 ${flush.autoCompactTokenLimit}'
            : 'Threshold ${flush.autoCompactTokenLimit}',
      if (flush.estimatedReplayTokens != null)
        copy.isChinese
            ? '估算 ${flush.estimatedReplayTokens}'
            : 'Estimated ${flush.estimatedReplayTokens}',
      if (flush.tokenThresholdTriggered != null)
        copy.isChinese
            ? (flush.tokenThresholdTriggered! ? '已达阈值' : '未达阈值')
            : (flush.tokenThresholdTriggered!
                  ? 'Threshold triggered'
                  : 'Threshold not triggered'),
      if (flush.smallerWindowModelSwitchDetected == true)
        copy.isChinese ? '检测到更小窗口模型' : 'Smaller-window model switch',
    ];
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(copy.isChinese ? '，' : ', '),
      omitted.isEmpty ? null : omitted.join(copy.isChinese ? '，' : ', '),
      pressure.isEmpty
          ? null
          : pressure.join(copy.isChinese ? '，' : ', '),
      flush.signature == null
          ? null
          : copy.isChinese
          ? '签名：${flush.signature}'
          : 'Signature: ${flush.signature}',
      _labeledInlineSection(
        englishLabel: 'Kinds',
        chineseLabel: '类型',
        values: flush.writtenKinds,
      ),
      _labeledInlineSection(
        englishLabel: 'Written',
        chineseLabel: '写入',
        values: flush.writtenRecordIds,
      ),
    ]);
  }

  String? _buildRunBootstrapHistoryBody(
    OpenCrayChatRunBootstrapSnapshot? bootstrap,
  ) {
    if (bootstrap == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (bootstrap.mode != null)
        copy.isChinese
            ? '模式 ${bootstrap.mode}'
            : 'Mode: ${bootstrap.mode}',
      if (bootstrap.visibleFileCount != null)
        copy.isChinese
            ? '可见 ${bootstrap.visibleFileCount} 个文件'
            : '${bootstrap.visibleFileCount} visible file(s)',
      if (bootstrap.injectedFileCount != null)
        copy.isChinese
            ? '注入 ${bootstrap.injectedFileCount} 个'
            : '${bootstrap.injectedFileCount} injected',
      if (bootstrap.omittedFileCount != null)
        copy.isChinese
            ? '省略 ${bootstrap.omittedFileCount} 个'
            : '${bootstrap.omittedFileCount} omitted',
      if (bootstrap.truncatedFileCount != null)
        copy.isChinese
            ? '截断 ${bootstrap.truncatedFileCount} 个'
            : '${bootstrap.truncatedFileCount} truncated',
    ];
    final List<String> files = bootstrap.files
        .map((file) {
          final List<String> suffix = <String>[
            if (file.injectedCharCount != null)
              copy.isChinese
                  ? '注入 ${file.injectedCharCount}'
                  : 'injected ${file.injectedCharCount}',
            if (file.sourceCharCount != null)
              copy.isChinese
                  ? '原始 ${file.sourceCharCount}'
                  : 'source ${file.sourceCharCount}',
            if (file.truncated == true)
              copy.isChinese ? '已截断' : 'truncated',
          ];
          final String detail = suffix.isEmpty
              ? ''
              : copy.isChinese
              ? '，${suffix.join('，')}'
              : ' (${suffix.join(', ')})';
          return '${file.name} (${file.relativePath})$detail';
        })
        .toList(growable: false);
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(copy.isChinese ? '，' : ', '),
      _labeledMultilineSection(
        englishLabel: 'Files',
        chineseLabel: '文件',
        values: files,
      ),
    ]);
  }

  String? _buildRunDurableCompactionHistoryBody(
    OpenCrayChatRunDurableCompactionSnapshot? durableCompaction,
  ) {
    if (durableCompaction == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (durableCompaction.compactedThisRun != null)
        copy.isChinese
            ? (durableCompaction.compactedThisRun! ? '本轮已压缩' : '本轮未压缩')
            : (durableCompaction.compactedThisRun!
                  ? 'Compacted this run'
                  : 'No compaction this run'),
      if (durableCompaction.sourceTranscriptMessageCount != null &&
          durableCompaction.retainedTranscriptMessageCount != null)
        copy.isChinese
            ? '保留 ${durableCompaction.retainedTranscriptMessageCount}/${durableCompaction.sourceTranscriptMessageCount} 条消息'
            : 'Retained ${durableCompaction.retainedTranscriptMessageCount}/${durableCompaction.sourceTranscriptMessageCount} transcript messages',
      if (durableCompaction.latestCompactedMessageCount != null)
        copy.isChinese
            ? '最近压缩 ${durableCompaction.latestCompactedMessageCount} 条'
            : 'Latest compacted ${durableCompaction.latestCompactedMessageCount} message(s)',
    ];
    final List<String> summaryCounts = <String>[
      if (durableCompaction.includedSummaryCount != null)
        copy.isChinese
            ? '纳入摘要 ${durableCompaction.includedSummaryCount} 个'
            : '${durableCompaction.includedSummaryCount} included summary(s)',
      if (durableCompaction.totalSummaryCount != null)
        copy.isChinese
            ? '总摘要 ${durableCompaction.totalSummaryCount} 个'
            : '${durableCompaction.totalSummaryCount} total summary(ies)',
      if (durableCompaction.totalCompactedMessageCount != null)
        copy.isChinese
            ? '累计压缩 ${durableCompaction.totalCompactedMessageCount} 条'
            : '${durableCompaction.totalCompactedMessageCount} total compacted message(s)',
    ];
    final List<String> pressure = <String>[
      if (_nonEmpty(durableCompaction.triggerStage) != null)
        copy.isChinese
            ? '触发 ${durableCompaction.triggerStage}'
            : 'Trigger: ${durableCompaction.triggerStage}',
      if (_nonEmpty(durableCompaction.executionMode) != null)
        copy.isChinese
            ? '模式 ${durableCompaction.executionMode}'
            : 'Mode: ${durableCompaction.executionMode}',
      if (durableCompaction.contextWindowTokens != null)
        copy.isChinese
            ? '窗口 ${durableCompaction.contextWindowTokens}'
            : 'Window ${durableCompaction.contextWindowTokens}',
      if (durableCompaction.previousContextWindowTokens != null)
        copy.isChinese
            ? '原窗口 ${durableCompaction.previousContextWindowTokens}'
            : 'Previous window ${durableCompaction.previousContextWindowTokens}',
      if (durableCompaction.autoCompactTokenLimit != null)
        copy.isChinese
            ? '阈值 ${durableCompaction.autoCompactTokenLimit}'
            : 'Threshold ${durableCompaction.autoCompactTokenLimit}',
      if (durableCompaction.estimatedReplayTokens != null)
        copy.isChinese
            ? '估算 ${durableCompaction.estimatedReplayTokens}'
            : 'Estimated ${durableCompaction.estimatedReplayTokens}',
      if (durableCompaction.tokenThresholdTriggered != null)
        copy.isChinese
            ? (durableCompaction.tokenThresholdTriggered! ? '已达阈值' : '未达阈值')
            : (durableCompaction.tokenThresholdTriggered!
                  ? 'Threshold triggered'
                  : 'Threshold not triggered'),
      if (durableCompaction.smallerWindowModelSwitchDetected == true)
        copy.isChinese
            ? '检测到更小窗口模型'
            : 'Smaller-window model switch',
    ];
    final remote = durableCompaction.remoteCompaction;
    final List<String> remoteState = <String>[];
    final List<String> remoteCounts = <String>[];
    if (remote != null) {
      if (remote.used != null) {
        remoteState.add(
          copy.isChinese
              ? (remote.used! ? '已使用远端压缩' : '未使用远端压缩')
              : (remote.used! ? 'used' : 'not used'),
        );
      }
      if (remote.supported != null) {
        remoteState.add(
          copy.isChinese
              ? (remote.supported! ? '支持' : '不支持')
              : (remote.supported! ? 'supported' : 'unsupported'),
        );
      }
      if (remote.requested != null) {
        remoteState.add(
          copy.isChinese
              ? (remote.requested! ? '已请求' : '未请求')
              : (remote.requested! ? 'requested' : 'not requested'),
        );
      }
      final remoteTriggerStage = _nonEmpty(remote.triggerStage);
      if (remoteTriggerStage != null) {
        remoteState.add(
          copy.isChinese
              ? '触发 $remoteTriggerStage'
              : 'trigger $remoteTriggerStage',
        );
      }
      if (remote.outputItemCount != null) {
        remoteCounts.add(
          copy.isChinese
              ? '输出 ${remote.outputItemCount}'
              : 'output ${remote.outputItemCount}',
        );
      }
      if (remote.compactionItemCount != null) {
        remoteCounts.add(
          copy.isChinese
              ? '压缩项 ${remote.compactionItemCount}'
              : 'compaction ${remote.compactionItemCount}',
        );
      }
      if (remote.encryptedContentCount != null) {
        remoteCounts.add(
          copy.isChinese
              ? '加密内容 ${remote.encryptedContentCount}'
              : 'encrypted ${remote.encryptedContentCount}',
        );
      }
      final fallbackReason = _nonEmpty(remote.fallbackReason);
      if (fallbackReason != null) {
        remoteCounts.add(
          copy.isChinese
              ? '回退 $fallbackReason'
              : 'fallback $fallbackReason',
        );
      }
    }
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(copy.isChinese ? '，' : ', '),
      summaryCounts.isEmpty
          ? null
          : summaryCounts.join(copy.isChinese ? '，' : ', '),
      pressure.isEmpty
          ? null
          : pressure.join(copy.isChinese ? '，' : ', '),
      remoteState.isEmpty
          ? null
          : copy.isChinese
          ? '远端压缩：${remoteState.join('，')}'
          : 'Remote compaction: ${remoteState.join(', ')}',
      remoteCounts.isEmpty
          ? null
          : copy.isChinese
          ? '远端压缩明细：${remoteCounts.join('，')}'
          : 'Remote compaction details: ${remoteCounts.join(', ')}',
      durableCompaction.latestCompactedAtEpochMs == null
          ? null
          : copy.isChinese
          ? '最近压缩时间：${durableCompaction.latestCompactedAtEpochMs}'
          : 'Latest compaction at ${durableCompaction.latestCompactedAtEpochMs}',
    ]);
  }

  String? _buildRunSkillInventoryHistoryBody(
    OpenCrayChatRunSkillInventorySnapshot? skillInventory,
  ) {
    if (skillInventory == null) {
      return null;
    }
    final List<String> counts = <String>[
      if (skillInventory.visibleSkillCount != null)
        copy.isChinese
            ? '可见 ${skillInventory.visibleSkillCount} 个'
            : '${skillInventory.visibleSkillCount} visible',
      if (skillInventory.injectedSkillCount != null)
        copy.isChinese
            ? '注入 ${skillInventory.injectedSkillCount} 个'
            : '${skillInventory.injectedSkillCount} injected',
      if (skillInventory.omittedSkillCount != null)
        copy.isChinese
            ? '省略 ${skillInventory.omittedSkillCount} 个'
            : '${skillInventory.omittedSkillCount} omitted',
      if (skillInventory.implicitSkillCount != null)
        copy.isChinese
            ? '隐式 ${skillInventory.implicitSkillCount} 个'
            : '${skillInventory.implicitSkillCount} implicit',
      if (skillInventory.invalidSkillCount != null)
        copy.isChinese
            ? '无效 ${skillInventory.invalidSkillCount} 个'
            : '${skillInventory.invalidSkillCount} invalid',
    ];
    final String? omittedTrace = skillInventory.omittedTraceSkillCount == null
        ? null
        : copy.isChinese
        ? '省略轨迹 ${skillInventory.omittedTraceSkillCount} 个'
        : 'Omitted trace skills: ${skillInventory.omittedTraceSkillCount}';
    final List<String> skills = skillInventory.skills
        .map((skill) {
          final List<String> parts = <String>[skill.name];
          final String? relativePath = _nonEmpty(skill.relativePath);
          if (relativePath != null) {
            parts.add(relativePath);
          }
          final String? invocationControl = _nonEmpty(skill.invocationControl);
          if (invocationControl != null) {
            parts.add(invocationControl);
          }
          final String? executionContext = _nonEmpty(skill.executionContext);
          if (executionContext != null) {
            parts.add(executionContext);
          }
          return parts.join(copy.isChinese ? '，' : ' · ');
        })
        .toList(growable: false);
    return _joinTraceSections(<String?>[
      counts.isEmpty ? null : counts.join(copy.isChinese ? '，' : ', '),
      omittedTrace,
      _labeledMultilineSection(
        englishLabel: 'Skills',
        chineseLabel: '技能',
        values: skills,
      ),
    ]);
  }

  String? _buildRunActiveSkillHistoryBody(
    OpenCrayChatRunActiveSkillSnapshot? activeSkill,
  ) {
    if (activeSkill == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (_nonEmpty(activeSkill.name) != null)
        copy.isChinese
            ? '名称 ${activeSkill.name}'
            : 'Name: ${activeSkill.name}',
      if (_nonEmpty(activeSkill.relativePath) != null)
        copy.isChinese
            ? '路径 ${activeSkill.relativePath}'
            : 'Path: ${activeSkill.relativePath}',
      if (_nonEmpty(activeSkill.activationSource) != null)
        copy.isChinese
            ? '来源 ${activeSkill.activationSource}'
            : 'Activation: ${activeSkill.activationSource}',
      if (_nonEmpty(activeSkill.executionContext) != null)
        copy.isChinese
            ? '上下文 ${activeSkill.executionContext}'
            : 'Context: ${activeSkill.executionContext}',
      if (activeSkill.toolRestrictionEnabled != null)
        copy.isChinese
            ? (activeSkill.toolRestrictionEnabled! ? '已启用工具限制' : '未启用工具限制')
            : (activeSkill.toolRestrictionEnabled!
                  ? 'Tool restriction enabled'
                  : 'Tool restriction disabled'),
      if (activeSkill.pinned != null)
        copy.isChinese
            ? (activeSkill.pinned! ? '已固定' : '未固定')
            : (activeSkill.pinned! ? 'Pinned' : 'Not pinned'),
      if (activeSkill.truncated != null)
        copy.isChinese
            ? (activeSkill.truncated! ? '胶囊已截断' : '胶囊未截断')
            : (activeSkill.truncated! ? 'Capsule truncated' : 'Capsule intact'),
    ];
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(copy.isChinese ? '，' : ', '),
      _labeledInlineSection(
        englishLabel: 'Allowed tools',
        chineseLabel: '允许工具',
        values: activeSkill.allowedToolKeys,
      ),
    ]);
  }

  OpenCrayChatPendingApprovalSnapshot? _pendingApprovalForRun({
    required OpenCrayChatRunSnapshot run,
    required List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals,
  }) {
    final String runId = run.runId.trim();
    final String taskId = run.taskId.trim();
    for (final approval in pendingApprovals) {
      if (runId.isNotEmpty && approval.runId.trim() == runId) {
        return approval;
      }
      if (taskId.isNotEmpty && approval.taskId.trim() == taskId) {
        return approval;
      }
    }
    return null;
  }

  OpenCrayChatRuntimeEventSnapshot? _latestApprovalEvent(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  ) {
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final event = runEvents[index];
      if (event.kind == 'approval_wait' || event.kind == 'approval_result') {
        return event;
      }
    }
    return null;
  }

  bool _isApprovalRequiredErrorCode(String? errorCode) =>
      errorCode == 'APPROVAL_REQUIRED' ||
      errorCode == 'HIGH_RISK_APPROVAL_REQUIRED';

  bool _isApprovalApprovedEvent(OpenCrayChatRuntimeEventSnapshot? event) =>
      event?.kind == 'approval_result' &&
      _nonEmpty(event?.status)?.toLowerCase() == 'approved';

  bool _isApprovalRejectedEvent(OpenCrayChatRuntimeEventSnapshot? event) =>
      event?.kind == 'approval_result' &&
      _nonEmpty(event?.status)?.toLowerCase() == 'rejected';

  bool _isWaitingApproval({
    required OpenCrayChatRunSnapshot run,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
    required OpenCrayChatPendingApprovalSnapshot? pendingApproval,
  }) {
    if (pendingApproval != null) {
      return true;
    }
    if (_latestApprovalEvent(runEvents) != null) {
      return false;
    }
    return _isApprovalRequiredErrorCode(run.errorCode);
  }

  bool _shouldShowResolvedApprovalPreview({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeEventSnapshot? event,
    required OpenCrayChatRuntimeEventSnapshot? latestApprovalEvent,
    required OpenCrayChatPendingApprovalSnapshot? pendingApproval,
  }) {
    if (pendingApproval != null || run.isTerminal) {
      return false;
    }
    if (event != null &&
        event.kind != 'approval_wait' &&
        event.kind != 'approval_result' &&
        event.kind != 'tool_result') {
      return false;
    }
    if (_isApprovalRejectedEvent(event) ||
        _isApprovalRejectedEvent(latestApprovalEvent)) {
      return false;
    }
    if (_isApprovalApprovedEvent(event) ||
        _isApprovalApprovedEvent(latestApprovalEvent)) {
      return true;
    }
    if (event?.kind == 'approval_wait') {
      return true;
    }
    if (event?.kind == 'tool_result' &&
        _isApprovalRequiredErrorCode(event?.errorCode)) {
      return true;
    }
    return false;
  }

  OpenCrayChatRuntimeEventSnapshot? _latestToolContextEvent(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    String? preferredToolName,
  }) {
    final String? normalizedToolName = _nonEmpty(preferredToolName);
    OpenCrayChatRuntimeEventSnapshot? fallback;
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final candidate = runEvents[index];
      if (candidate.kind != 'tool_call' && candidate.kind != 'tool_result') {
        continue;
      }
      fallback ??= candidate;
      if (normalizedToolName == null) {
        continue;
      }
      final String? candidateToolName = _nonEmpty(candidate.toolName);
      if (candidateToolName == normalizedToolName) {
        return candidate;
      }
    }
    return fallback;
  }

  _ResolvedApprovalPreview _buildResolvedApprovalPreview({
    required OpenCrayChatRunSnapshot run,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
    required OpenCrayChatRuntimeEventSnapshot? latestApprovalEvent,
  }) {
    final String? preferredToolName =
        _nonEmpty(latestApprovalEvent?.toolName) ??
        _nonEmpty(run.lastEvent?.toolName);
    final OpenCrayChatRuntimeEventSnapshot? toolContext =
        _latestToolContextEvent(
          runEvents,
          preferredToolName: preferredToolName,
        );
    final String label =
        _nonEmpty(toolContext?.toolName) ??
        preferredToolName ??
        copy.chatRunWorkingLabel;
    String? actionBody;
    if (toolContext != null) {
      switch (toolContext.kind) {
        case 'tool_call':
          actionBody = _buildToolCallPreviewBody(toolContext);
          break;
        case 'tool_result':
          final int beforeIndex = runEvents.indexOf(toolContext);
          final OpenCrayChatRuntimeEventSnapshot? pairedToolCall =
              beforeIndex < 0
              ? null
              : _findPreviousToolCall(
                  runEvents,
                  beforeIndex: beforeIndex,
                  toolName: toolContext.toolName?.trim(),
                );
          actionBody = pairedToolCall != null
              ? _buildToolCallPreviewBody(pairedToolCall)
              : _toolResultActionSummary(
                  toolName: label,
                  event: toolContext,
                  pairedToolCall: pairedToolCall,
                );
          break;
      }
    }
    final String statusBody = _isApprovalApprovedEvent(latestApprovalEvent)
        ? (copy.isChinese
              ? '审批已通过，正在继续执行 $label。'
              : 'Approval granted. Resuming $label.')
        : (copy.isChinese
              ? '$label 的审批状态已更新，正在恢复执行。'
              : 'Approval updated. Resuming $label.');
    return _ResolvedApprovalPreview(
      label: label,
      body: _joinTraceSections(<String?>[actionBody, statusBody]),
    );
  }

  bool _isInterruptedOnRestoreRun(OpenCrayChatRunSnapshot run) =>
      run.errorCode == 'RESTART_REQUIRES_EXPLICIT_RETRY' ||
      run.errorCode == 'PROCESS_INTERRUPTED_ON_RESTORE';

  bool _isLlmRetryPausedRun(OpenCrayChatRunSnapshot run) =>
      run.lifecycleState?.trim().toLowerCase() == 'suspended' &&
      run.errorCode == 'LLM_RETRY_EXHAUSTED_AWAITING_RESUME';

  bool _isDeferredApprovalDecisionRun(OpenCrayChatRunSnapshot run) {
    final String? checkpointKind = run.recoveryPlan?.checkpointKind
        ?.trim()
        .toLowerCase();
    return run.lifecycleState?.trim().toLowerCase() == 'suspended' &&
        (checkpointKind == 'approved_pending_resume' ||
            checkpointKind == 'rejected_pending_resume') &&
        run.recoveryPlan?.action == 'resume_waiting_for_user';
  }

  String _interruptedRunLabel() =>
      copy.isChinese ? '运行已中断' : 'Run interrupted';

  String _retryInterruptedRunLabel() =>
      copy.isChinese ? '重新启动' : 'Restart run';

  String _pausedRunLabel() => copy.chatRunAwaitingDirectionLabel;

  String _interruptedRunBody(OpenCrayChatRunSnapshot run) {
    final body = run.errorMessage?.trim();
    if (body != null && body.isNotEmpty) {
      return body;
    }
    return copy.isChinese
        ? '宿主重建时这次运行被显式中断，避免从头静默重跑。需要你明确触发后才会继续。'
        : 'This run was interrupted during host recovery to avoid silently rerunning from the beginning. Restart it explicitly when you want to continue.';
  }

  String _pausedRunBody() => copy.chatRunLlmRetryPausedBody;

  String _deferredApprovalDecisionBody() =>
      copy.chatRunApprovalDecisionDeferredBody;

  List<OpenCrayChatRuntimeEventSnapshot> _runEventsFor({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  }) => run.scopeRuntimeEvents(runtimeSnapshot.events).toList(growable: false)
    ..sort(
      (left, right) => left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
    );

  OpenCrayChatRuntimeEventSnapshot? _latestRunTraceEvent(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  ) {
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final OpenCrayChatRuntimeEventSnapshot event = runEvents[index];
      if (event.kind == 'assistant_phase' && _hideAssistantPhaseBubble(event)) {
        continue;
      }
      return event;
    }
    return null;
  }

  ChatRunTracePreviewCardData? _latestRunTracePreviewCard(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  ) {
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final OpenCrayChatRuntimeEventSnapshot event = runEvents[index];
      final String toolName = event.toolName?.trim().toLowerCase() ?? '';
      if (event.kind != 'tool_result' || toolName != 'sandbox_preview_open') {
        continue;
      }
      final String? url = _resultMetadataValue(event, 'previewUrl');
      if (url == null) {
        continue;
      }
      return ChatRunTracePreviewCardData(
        url: url,
        status: _previewStatusFromWire(
          _resultMetadataValue(event, 'previewProbeStatus'),
        ),
        port: _resultMetadataInt(event, 'previewPort'),
        path: _resultMetadataValue(event, 'previewPath'),
        provider: _resultMetadataValue(event, 'sandboxProvider'),
        httpStatusCode: _resultMetadataInt(event, 'previewProbeHttpStatus'),
        message: _resultMetadataValue(event, 'previewProbeMessage'),
      );
    }
    return null;
  }

  ChatRunTraceSandboxSessionCardData? _latestRunTraceSandboxSessionCard(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  ) {
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final OpenCrayChatRuntimeEventSnapshot event = runEvents[index];
      final String toolName = event.toolName?.trim().toLowerCase() ?? '';
      if (event.kind != 'tool_result' || toolName != 'sandbox_session_info') {
        continue;
      }
      final bool sessionPresent =
          _resultMetadataBool(event, 'sandboxSessionPresent') == true;
      return ChatRunTraceSandboxSessionCardData(
        sessionPresent: sessionPresent,
        source: _sandboxSessionSourceFromWire(
          _resultMetadataValue(event, 'sandboxSessionSource'),
        ),
        lifecycleStatus: _sandboxSessionLifecycleStatusFromWire(
          _resultMetadataValue(event, 'sandboxSessionLifecycleStatus'),
          sessionPresent: sessionPresent,
        ),
        provider: _resultMetadataValue(event, 'sandboxProvider'),
        sandboxId: _resultMetadataValue(event, 'sandboxId'),
        sandboxDomain: _resultMetadataValue(event, 'sandboxDomain'),
        templateId: _resultMetadataValue(event, 'sandboxTemplateId'),
        updatedAtEpochMs: _resultMetadataInt(
          event,
          'sandboxSessionUpdatedAtEpochMs',
        ),
        sessionLastActivityAtEpochMs: _resultMetadataInt(
          event,
          'sandboxSessionLastActivityAtEpochMs',
        ),
        sessionStaleAfterEpochMs: _resultMetadataInt(
          event,
          'sandboxSessionStaleAfterEpochMs',
        ),
        lastPreviewUrl: _resultMetadataValue(event, 'sandboxLastPreviewUrl'),
        lastPreviewProbeStatus:
            _resultMetadataValue(event, 'sandboxLastPreviewProbeStatus') == null
            ? null
            : _previewStatusFromWire(
                _resultMetadataValue(event, 'sandboxLastPreviewProbeStatus'),
              ),
        lastPreviewProbeObservedAtEpochMs: _resultMetadataInt(
          event,
          'sandboxLastPreviewProbeObservedAtEpochMs',
        ),
        lastPreviewProbeSource: _resultMetadataValue(
          event,
          'sandboxLastPreviewProbeSource',
        ),
        autoRefreshAfterMs: _resultMetadataInt(
          event,
          'sandboxSessionAutoRefreshAfterMs',
        ),
        previewCandidatePorts: _resultMetadataCsvInts(
          event,
          'sandboxPreviewCandidatePorts',
        ),
        runningRequestIds: _resultMetadataCsvStrings(
          event,
          'sandboxRunningRequestIds',
        ),
      );
    }
    return null;
  }

  ChatRunTracePreviewStatus _previewStatusFromWire(String? rawValue) {
    switch (rawValue?.trim().toLowerCase()) {
      case 'ready':
        return ChatRunTracePreviewStatus.ready;
      case 'reachable':
        return ChatRunTracePreviewStatus.reachable;
      case 'unreachable':
        return ChatRunTracePreviewStatus.unreachable;
      case 'skipped':
      default:
        return ChatRunTracePreviewStatus.skipped;
    }
  }

  ChatRunTraceSandboxSessionSource _sandboxSessionSourceFromWire(
    String? rawValue,
  ) {
    switch (rawValue?.trim().toLowerCase()) {
      case 'active_memory':
        return ChatRunTraceSandboxSessionSource.activeMemory;
      case 'persisted':
        return ChatRunTraceSandboxSessionSource.persisted;
      case 'active_memory_and_persisted':
        return ChatRunTraceSandboxSessionSource.activeAndPersisted;
      case 'none':
      default:
        return ChatRunTraceSandboxSessionSource.none;
    }
  }

  ChatRunTraceSandboxSessionLifecycleStatus
  _sandboxSessionLifecycleStatusFromWire(
    String? rawValue, {
    required bool sessionPresent,
  }) {
    switch (rawValue?.trim().toLowerCase()) {
      case 'active':
        return ChatRunTraceSandboxSessionLifecycleStatus.active;
      case 'stale':
        return ChatRunTraceSandboxSessionLifecycleStatus.stale;
      case 'reclaimed':
        return ChatRunTraceSandboxSessionLifecycleStatus.reclaimed;
      case 'none':
        return ChatRunTraceSandboxSessionLifecycleStatus.none;
      default:
        return sessionPresent
            ? ChatRunTraceSandboxSessionLifecycleStatus.active
            : ChatRunTraceSandboxSessionLifecycleStatus.none;
    }
  }

  String? _canonicalToolName(String? toolName) {
    final String? normalizedToolName = toolName?.trim();
    if (normalizedToolName == null || normalizedToolName.isEmpty) {
      return normalizedToolName;
    }
    return _displayToolAliases[normalizedToolName] ?? normalizedToolName;
  }

  OpenCrayChatRuntimeEventSnapshot? _findPreviousToolCall(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    required int beforeIndex,
    String? toolName,
  }) {
    final String? normalizedToolName = _canonicalToolName(toolName);
    for (int index = beforeIndex - 1; index >= 0; index -= 1) {
      final candidate = runEvents[index];
      if (candidate.kind != 'tool_call') {
        continue;
      }
      final String? candidateToolName = _canonicalToolName(candidate.toolName);
      if (normalizedToolName == null ||
          normalizedToolName.isEmpty ||
          candidateToolName == normalizedToolName) {
        return candidate;
      }
    }
    return null;
  }

  int? _findNextToolResultIndex(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    required int afterIndex,
    String? toolName,
  }) {
    final String? normalizedToolName = _canonicalToolName(toolName);
    for (int index = afterIndex + 1; index < runEvents.length; index += 1) {
      final candidate = runEvents[index];
      if (candidate.kind == 'tool_call') {
        return null;
      }
      if (candidate.kind != 'tool_result') {
        if (!_isSkippableToolGroupingInterveningEvent(candidate)) {
          return null;
        }
        continue;
      }
      final String? candidateToolName = _canonicalToolName(candidate.toolName);
      if (normalizedToolName == null || normalizedToolName.isEmpty) {
        return index;
      }
      if (candidateToolName == normalizedToolName) {
        return index;
      }
    }
    return null;
  }

  bool _isSkippableToolGroupingInterveningEvent(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    if (event.kind == 'lifecycle') {
      final String phase = event.phase?.trim().toLowerCase() ?? '';
      return phase.isNotEmpty;
    }
    return event.kind == 'subagent';
  }

  ChatRunTraceHistoryEntry _buildGroupedToolHistoryEntry({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot? toolCallEvent,
    required OpenCrayChatRuntimeEventSnapshot? toolResultEvent,
  }) {
    final _ToolInspectorCallDisplay callDisplay =
        _buildToolInspectorCallDisplay(
          toolName: toolName,
          event: toolCallEvent,
          toolResultEvent: toolResultEvent,
        );
    final String callBody = _joinTraceSections(<String?>[
      callDisplay.text,
      callDisplay.detail,
    ]);
    final String? resultBody = toolResultEvent == null
        ? null
        : _buildGroupedToolResultBody(
            toolName: toolName,
            event: toolResultEvent,
            pairedToolCall: toolCallEvent,
          );
    final String inspectorBody = resultBody == null
        ? callBody
        : '$callBody\n${_indentGroupedToolBlock(resultBody, connector: true)}';
    final String compactBody = toolResultEvent == null
        ? _buildToolCallPreviewBody(
            toolCallEvent ??
                OpenCrayChatRuntimeEventSnapshot(
                  kind: 'tool_call',
                  runId: '',
                  taskId: '',
                  emittedAtEpochMs: 0,
                  toolName: toolName,
                ),
          )
        : _buildToolResultPreviewBody(
            event: toolResultEvent,
            pairedToolCall: toolCallEvent,
            waitingApproval: false,
            runErrorMessage: null,
          );
    return _mainHistoryEntry(
      label: toolName,
      body: inspectorBody,
      compactBody: compactBody,
      isHighRisk:
          toolResultEvent?.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED' ||
          toolResultEvent?.isHighRisk == true,
      inspectorCallParts: callDisplay.parts,
      inspectorCallDetail: callDisplay.detail ?? '',
      inspectorResultBody: resultBody ?? '',
    );
  }

  _ToolInspectorCallDisplay _buildToolInspectorCallDisplay({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot? event,
    required OpenCrayChatRuntimeEventSnapshot? toolResultEvent,
  }) {
    final Map<String, dynamic>? arguments =
        _decodeJsonObject(_nonEmpty(event?.argumentsJson)) ??
        (toolResultEvent == null
            ? null
            : _toolResultArgumentsFallback(
                toolName: toolName,
                event: toolResultEvent,
              ));
    final List<ChatRunTraceInspectorTextPart> parts =
        _toolInspectorCallParts(toolName: toolName, arguments: arguments) ??
        <ChatRunTraceInspectorTextPart>[
          ChatRunTraceInspectorTextPart(
            text: _toolActionSummaryFromArguments(
              toolName: toolName,
              arguments: arguments,
            ),
          ),
        ];
    final String? reason = _nonEmpty(event?.toolReason);
    final String? detail = _toolInspectorCallDetailBody(
      toolName: toolName,
      argumentsJson: _nonEmpty(event?.argumentsJson),
      toolResultEvent: toolResultEvent,
    );
    final String? combinedDetail =
        _joinTraceSections(<String?>[
          reason == null
              ? null
              : copy.isChinese
              ? '理由：$reason'
              : 'Reason: $reason',
          detail,
        ]).trim().isEmpty
        ? null
        : _joinTraceSections(<String?>[
            reason == null
                ? null
                : copy.isChinese
                ? '理由：$reason'
                : 'Reason: $reason',
            detail,
          ]);
    return _ToolInspectorCallDisplay(
      text: _joinInspectorPartText(parts),
      parts: parts,
      detail: combinedDetail,
    );
  }

  List<ChatRunTraceInspectorTextPart>? _toolInspectorCallParts({
    required String toolName,
    required Map<String, dynamic>? arguments,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    switch (canonicalToolName) {
      case 'Read':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        final String range = _readRangeSummary(
          offset: _argumentInt(arguments, 'offset'),
          limit: _argumentInt(arguments, 'limit'),
        );
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '读取' : 'Read'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
          if (range.isNotEmpty) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? '，' : ' '),
            _inspectorScope(range),
          ],
        ];
      case 'LS':
        final String path =
            _argumentString(arguments, 'path') ??
            _argumentString(arguments, 'file_path') ??
            '.';
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '列出' : 'List'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
        ];
      case 'Grep':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return null;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        final String? glob = _argumentString(arguments, 'glob');
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '搜索' : 'Search'),
          _inspectorNeutral(' '),
          _inspectorTarget('"$pattern"'),
          _inspectorNeutral(copy.isChinese ? ' 于 ' : ' in '),
          _inspectorScope(path),
          if (glob != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? '，glob ' : ' (glob: '),
            _inspectorScope(glob),
            if (!copy.isChinese) _inspectorNeutral(')'),
          ],
        ];
      case 'Glob':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return null;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '匹配' : 'Match'),
          _inspectorNeutral(' '),
          _inspectorTarget(pattern),
          _inspectorNeutral(copy.isChinese ? ' 于 ' : ' in '),
          _inspectorScope(path),
        ];
      case 'Write':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '写入' : 'Write'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
        ];
      case 'ImportFile':
        final String? sourcePath = _argumentString(arguments, 'source_path');
        final String? destinationPath = _argumentString(
          arguments,
          'destination_path',
        );
        if (sourcePath == null || destinationPath == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '导入' : 'Import'),
          _inspectorNeutral(' '),
          _inspectorTarget(sourcePath),
          _inspectorNeutral(copy.isChinese ? ' 到 ' : ' to '),
          _inspectorTarget(destinationPath),
        ];
      case 'Edit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '编辑' : 'Edit'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
        ];
      case 'MultiEdit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        final int editCount = _argumentList(arguments, 'edits')?.length ?? 0;
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '编辑' : 'Edit'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
          if (editCount > 0) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? '，共 ' : ' with '),
            _inspectorScope(
              copy.isChinese
                  ? '$editCount 处修改'
                  : '$editCount change${editCount == 1 ? '' : 's'}',
            ),
          ],
        ];
      case 'WebSearch':
        return _webSearchInspectorParts(arguments);
      case 'TodoWrite':
        final _TodoTraceSummary? summary = _todoSummaryFromArguments(arguments);
        if (arguments?.containsKey('todos') != true) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(copy.isChinese ? '读取' : 'Read'),
            _inspectorNeutral(' '),
            _inspectorTarget(
              copy.isChinese ? '当前待办列表' : 'current todo list',
            ),
          ];
        }
        if (summary == null || summary.todoCount <= 0) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(copy.isChinese ? '清空' : 'Clear'),
            _inspectorNeutral(' '),
            _inspectorTarget(
              copy.isChinese ? '当前待办列表' : 'current todo list',
            ),
          ];
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '更新' : 'Update'),
          _inspectorNeutral(' '),
          _inspectorScope(
            copy.isChinese
                ? '${summary.todoCount} 条待办'
                : '${summary.todoCount} todo${summary.todoCount == 1 ? '' : 's'}',
          ),
          if (summary.activeTodoContent !=
              null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? '，当前进行中：' : ', active: '),
            _inspectorTarget(summary.activeTodoContent!),
          ],
        ];
      case 'Task':
        final String? description = _argumentString(arguments, 'description');
        final String actor = _subagentTypeDisplay(
          _argumentString(arguments, 'subagent_type'),
        );
        if (description == null) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(copy.isChinese ? '委派' : 'Delegate'),
            _inspectorNeutral(copy.isChinese ? '给 ' : ' to '),
            _inspectorScope(actor),
          ];
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '委派' : 'Delegate'),
          _inspectorNeutral(copy.isChinese ? '给 ' : ' to '),
          _inspectorScope(actor),
          _inspectorNeutral(copy.isChinese ? '：' : ': '),
          _inspectorTarget(description),
        ];
      default:
        return null;
    }
  }

  ChatRunTraceInspectorTextPart _inspectorNeutral(String text) =>
      ChatRunTraceInspectorTextPart(text: text);

  ChatRunTraceInspectorTextPart _inspectorAction(String text) =>
      ChatRunTraceInspectorTextPart(
        text: text,
        semantic: ChatRunTraceInspectorTextSemantic.action,
      );

  ChatRunTraceInspectorTextPart _inspectorTarget(String text) =>
      ChatRunTraceInspectorTextPart(
        text: text,
        semantic: ChatRunTraceInspectorTextSemantic.target,
      );

  ChatRunTraceInspectorTextPart _inspectorScope(String text) =>
      ChatRunTraceInspectorTextPart(
        text: text,
        semantic: ChatRunTraceInspectorTextSemantic.scope,
      );

  String _joinInspectorPartText(List<ChatRunTraceInspectorTextPart> parts) =>
      parts.map((part) => part.text).join();

  String _buildToolCallPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String resolvedToolName =
        _canonicalToolName(_nonEmpty(event.toolName)) ??
        copy.chatRunWorkingLabel;
    final String summary = _toolActionSummary(
      toolName: resolvedToolName,
      argumentsJson: event.argumentsJson,
    );
    final String? reason = _nonEmpty(event.toolReason);
    final String? detail = _toolCallDetailBody(
      toolName: resolvedToolName,
      argumentsJson: event.argumentsJson,
    );
    return _joinTraceSections(<String?>[summary, reason, detail]);
  }

  String _buildToolResultPreviewBody({
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
    required bool waitingApproval,
    required String? runErrorMessage,
  }) {
    final String resolvedToolName =
        _canonicalToolName(_nonEmpty(event.toolName)) ??
        copy.chatRunWorkingLabel;
    final String summary = _toolResultActionSummary(
      toolName: resolvedToolName,
      event: event,
      pairedToolCall: pairedToolCall,
    );
    final String? resultSummary = _toolResultMetadataSummary(
      toolName: resolvedToolName,
      event: event,
    );
    final String? message =
        (waitingApproval
            ? _nonEmpty(runErrorMessage)
            : _nonEmpty(event.errorMessage)) ??
        _nonEmpty(event.contentPreview);
    return _joinTraceSections(<String?>[
      summary,
      resultSummary,
      message ?? copy.chatRunToolFollowUp(resolvedToolName),
    ]);
  }

  String _buildCompactTraceBody({
    required List<ChatRunTraceHistoryEntry> history,
    required String fallbackBody,
    String? preferredBody,
  }) {
    final List<ChatRunTraceHistoryEntry> entries = history
        .where(_shouldIncludeCompactHistoryEntry)
        .toList(growable: false);
    if (entries.isEmpty) {
      return fallbackBody;
    }
    final String? preferred = (() {
      final String trimmed = preferredBody?.trim() ?? '';
      return trimmed.isEmpty ? null : trimmed;
    })();
    final int endExclusive = preferred == null
        ? entries.length
        : entries.lastIndexWhere(
                (entry) => _historyCompactBody(entry) == preferred,
              ) +
              1;
    final int boundedEndExclusive = endExclusive > 0
        ? endExclusive
        : entries.length;
    final int startIndex = boundedEndExclusive > 3
        ? boundedEndExclusive - 3
        : 0;
    final String compactBody = entries
        .sublist(startIndex, boundedEndExclusive)
        .map((entry) => _historyCompactBody(entry))
        .where((body) => body.isNotEmpty)
        .join('\n\n');
    return compactBody.trim().isNotEmpty ? compactBody.trim() : fallbackBody;
  }

  bool _shouldIncludeCompactHistoryEntry(ChatRunTraceHistoryEntry entry) {
    final String body = _historyCompactBody(entry);
    if (body.isEmpty) {
      return false;
    }
    return !_thinkingPlaceholders.contains(body);
  }

  String _historyCompactBody(ChatRunTraceHistoryEntry entry) =>
      (entry.compactBody?.trim().isNotEmpty == true
              ? entry.compactBody!
              : entry.body)
          .trim();

  String _assistantPhaseEntryLabel(OpenCrayChatRuntimeEventSnapshot event) {
    final String? stage = _nonEmpty(event.stage);
    if (stage != null) {
      return stage;
    }
    return copy.chatRunWorkingLabel;
  }

  String _projectedAssistantPhaseMessageText(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    final String? stage = _nonEmpty(event.stage);
    final String body =
        _nonEmpty(event.text) ?? copy.chatRunThinkingActive;
    if (stage == null) {
      return body;
    }
    return '$stage\n\n$body';
  }

  String _assistantPhaseTag(OpenCrayChatRuntimeEventSnapshot event) {
    final String phase = event.phase?.trim().toLowerCase() ?? '';
    return phase.isEmpty ? 'commentary' : phase;
  }

  List<String> _assistantPhaseMessageIds(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    final List<String> ids = <String>[];
    void addId(String id) {
      if (id.trim().isEmpty || ids.contains(id)) {
        return;
      }
      ids.add(id);
    }

    final String eventId = event.eventId?.trim() ?? '';
    if (eventId.isNotEmpty) {
      addId('runtime-assistant-event-$eventId');
    }
    addId(_canonicalKotlinAssistantPhaseMessageId(event));
    final List<String> ownerIds = _assistantPhaseOwnerIds(event);
    if (ownerIds.isEmpty) {
      addId(_assistantPhaseMessageIdForOwner(event, ''));
      addId(_assistantPhaseBaseMessageIdForOwner(event, ''));
      return ids;
    }
    for (final String ownerId in ownerIds) {
      addId(_assistantPhaseMessageIdForOwner(event, ownerId));
    }
    addId(_legacyKotlinAssistantPhaseMessageId(event));
    for (final String ownerId in ownerIds) {
      addId(_assistantPhaseBaseMessageIdForOwner(event, ownerId));
    }
    for (final String ownerId in ownerIds) {
      addId(
        'runtime-assistant-${_assistantPhaseTag(event)}-$ownerId-${event.emittedAtEpochMs}',
      );
    }
    return ids;
  }

  /// Aliases an assistant-phase event may claim exclusively when projecting
  /// bubbles. Events with a durable eventId only own their event-scoped id
  /// (plus the legacy content-hash id older persisted transcripts used); the
  /// shared run/turn/stage fallback aliases stay unclaimed so sibling
  /// narrations of the same turn keep their own bubbles instead of being
  /// swallowed by or overwriting the first one.
  List<String> _assistantPhaseClaimMessageIds(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    final String eventId = event.eventId?.trim() ?? '';
    if (eventId.isEmpty) {
      return _assistantPhaseMessageIds(event);
    }
    final List<String> ids = <String>['runtime-assistant-event-$eventId'];
    final String legacyId = _legacyKotlinAssistantPhaseMessageId(event);
    if (legacyId.isNotEmpty && !ids.contains(legacyId)) {
      ids.add(legacyId);
    }
    return ids;
  }

  String _assistantPhaseEventIdentity(OpenCrayChatRuntimeEventSnapshot event) {
    final String eventId = event.eventId?.trim() ?? '';
    if (eventId.isNotEmpty) {
      return 'id:$eventId';
    }
    return 'key:${event.runId.trim()}|${event.taskId.trim()}|${event.turn ?? -1}'
        '|${event.stage?.trim() ?? ''}|${event.emittedAtEpochMs}';
  }

  /// Identities of each visible run's newest non-final assistant-phase event.
  /// Only that narration renders as "streaming" so a single live bubble pulses
  /// per run instead of every historical narration.
  Set<String> _latestAssistantPhaseEventIdentities({
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
    required Map<String, OpenCrayChatRunSnapshot> visibleRunsByRunId,
    required Map<String, OpenCrayChatRunSnapshot> visibleRunsByTaskId,
  }) {
    final Map<String, OpenCrayChatRuntimeEventSnapshot> latestByRunKey =
        <String, OpenCrayChatRuntimeEventSnapshot>{};
    for (final event in runtimeSnapshot.events) {
      if (event.kind != 'assistant_phase' ||
          event.isFinal == true ||
          _hideAssistantPhaseBubble(event)) {
        continue;
      }
      final OpenCrayChatRunSnapshot? run =
          visibleRunsByRunId[event.runId.trim()] ??
          visibleRunsByTaskId[event.taskId.trim()];
      if (run == null || !run.matchesRuntimeEvent(event)) {
        continue;
      }
      final String runKey = '${run.runId.trim()}|${run.taskId.trim()}';
      final OpenCrayChatRuntimeEventSnapshot? current = latestByRunKey[runKey];
      if (current == null ||
          event.emittedAtEpochMs >= current.emittedAtEpochMs) {
        latestByRunKey[runKey] = event;
      }
    }
    return latestByRunKey.values
        .map(_assistantPhaseEventIdentity)
        .toSet();
  }

  String _canonicalKotlinAssistantPhaseMessageId(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    final String runId = event.runId.trim();
    if (runId.isEmpty) {
      return '';
    }
    final String stage = event.stage?.trim().isNotEmpty == true
        ? event.stage!.trim()
        : '-';
    final int turn = event.turn ?? -1;
    return 'runtime-assistant-${_assistantPhaseTag(event)}-$runId-$turn-$stage';
  }

  String _legacyKotlinAssistantPhaseMessageId(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    final String runId = event.runId.trim();
    if (runId.isEmpty) {
      return '';
    }
    final String stage = event.stage?.trim().isNotEmpty == true
        ? event.stage!.trim()
        : '-';
    final int turn = event.turn ?? -1;
    final int textHash = javaStringHashCode(event.text?.trim() ?? '');
    return 'runtime-assistant-${_assistantPhaseTag(event)}-$runId-$turn-$stage-${event.emittedAtEpochMs}-$textHash';
  }

  List<String> _assistantPhaseOwnerIds(OpenCrayChatRuntimeEventSnapshot event) {
    final List<String> ownerIds = <String>[];
    final String taskId = event.taskId.trim();
    final String runId = event.runId.trim();
    if (taskId.isNotEmpty) {
      ownerIds.add(taskId);
    }
    if (runId.isNotEmpty && runId != taskId) {
      ownerIds.add(runId);
    }
    return ownerIds;
  }

  String _assistantPhaseMessageIdForOwner(
    OpenCrayChatRuntimeEventSnapshot event,
    String ownerId,
  ) {
    return _assistantPhaseBaseMessageIdForOwner(event, ownerId);
  }

  String _assistantPhaseBaseMessageIdForOwner(
    OpenCrayChatRuntimeEventSnapshot event,
    String ownerId,
  ) {
    final String stage = event.stage?.trim().isNotEmpty == true
        ? event.stage!.trim()
        : '-';
    final int turn = event.turn ?? -1;
    return 'runtime-assistant-${_assistantPhaseTag(event)}-$ownerId-$turn-$stage-${event.emittedAtEpochMs}';
  }

  String _buildSupplementPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String? text = _nonEmpty(event.text);
    final String? checkpoint = _supplementCheckpointSummary(event);
    return _joinTraceSections(<String?>[
      text ?? checkpoint,
      if (text != null) checkpoint,
    ]);
  }

  String _buildSupplementHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String? text = _nonEmpty(event.text);
    final String? checkpoint = _supplementCheckpointSummary(event);
    return _joinTraceSections(<String?>[
      text ?? checkpoint,
      if (text != null) checkpoint,
    ]);
  }

  String _buildSubagentPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _subagentPhaseSummary(event),
      _subagentControlSection(event),
      _subagentSummarySection(event),
      _subagentMailboxSection(event),
      _subagentContextSection(event),
    ]);
  }

  String _buildSubagentHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _subagentPhaseSummary(event),
      _subagentContextSection(event),
      _subagentControlSection(event),
      _subagentMailboxSection(event),
      _subagentSummarySection(event),
    ]);
  }

  String _subagentTraceLabel(OpenCrayChatRuntimeEventSnapshot event) {
    final String? type = _nonEmpty(event.subagentType);
    if (type != null) {
      return _subagentTypeDisplay(type);
    }
    return _nonEmpty(event.label) ??
        (copy.isChinese ? '子代理' : 'Subagent');
  }

  String _supplementTraceLabel() =>
      copy.isChinese ? '补充输入' : 'Follow-up';

  String? _supplementCheckpointSummary(OpenCrayChatRuntimeEventSnapshot event) {
    return switch (_nonEmpty(event.checkpoint)?.toLowerCase()) {
      'turn_start' =>
        copy.isChinese ? '在轮次开始时应用' : 'Applied at turn start',
      'post_tool_pre_model' =>
        copy.isChinese ? '在工具结果之后应用' : 'Applied after tool result',
      null => null,
      final String checkpoint =>
        copy.isChinese
            ? '应用检查点: ${checkpoint.replaceAll('_', ' ')}'
            : 'Applied at ${checkpoint.replaceAll('_', ' ')}',
    };
  }

  String _subagentPhaseSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final String actor = _subagentTraceLabel(event);
    final String? description = _nonEmpty(event.label);
    final String suffix = description == null || description == actor
        ? ''
        : copy.isChinese
        ? '：$description'
        : ': $description';
    final String? executionStateSummary = _subagentPhaseStateOverrideSummary(
      actor: actor,
      executionState: _subagentExecutionState(event),
    );
    if (executionStateSummary != null) {
      return '$executionStateSummary$suffix';
    }
    switch (_nonEmpty(event.phase)?.toLowerCase()) {
      case 'started':
        return copy.isChinese
            ? '$actor 已启动$suffix'
            : '$actor started$suffix';
      case 'resumed':
        return copy.isChinese
            ? '$actor 已继续$suffix'
            : '$actor resumed$suffix';
      case 'completed':
        return copy.isChinese
            ? '$actor 已完成$suffix'
            : '$actor completed$suffix';
      case 'failed':
        return copy.isChinese
            ? '$actor 失败$suffix'
            : '$actor failed$suffix';
      case 'cancelled':
        return copy.isChinese
            ? '$actor 已取消$suffix'
            : '$actor cancelled$suffix';
      default:
        return copy.isChinese
            ? '$actor 已更新$suffix'
            : '$actor updated$suffix';
    }
  }

  String? _subagentContextSection(OpenCrayChatRuntimeEventSnapshot event) {
    final List<String> lines = <String>[
      if (_nonEmpty(event.contextMode) != null)
        '${_traceSectionLabel(english: 'Context', chinese: '上下文')}: ${_contextModeDisplay(event.contextMode!)}',
      if (event.depth != null)
        '${_traceSectionLabel(english: 'Depth', chinese: '深度')}: ${event.depth}',
      if (_subagentContinuationSummary(event) != null)
        '${_traceSectionLabel(english: 'Continuation', chinese: '继续方式')}: ${_subagentContinuationSummary(event)!}',
    ];
    return lines.isEmpty ? null : lines.join('\n');
  }

  String? _subagentSummarySection(OpenCrayChatRuntimeEventSnapshot event) {
    final String? summary = _nonEmpty(event.text);
    if (summary == null) {
      return null;
    }
    final String label = _traceSectionLabel(english: 'Summary', chinese: '摘要');
    return summary.contains('\n') ? '$label:\n$summary' : '$label: $summary';
  }

  String? _subagentControlSection(OpenCrayChatRuntimeEventSnapshot event) {
    final bool? hasActiveExecution = _resultMetadataBool(
      event,
      'hasActiveExecution',
    );
    final bool? hasPendingApprovalResume = _resultMetadataBool(
      event,
      'hasPendingApprovalResume',
    );
    final bool pendingApprovalIsHighRisk =
        _resultMetadataBool(event, 'pendingApprovalIsHighRisk') == true;
    final String? pendingApprovalToolName = _resultMetadataValue(
      event,
      'pendingApprovalToolName',
    );
    final String? pendingApprovalChildRunId = _resultMetadataValue(
      event,
      'pendingApprovalChildRunId',
    );
    final String? pendingApprovalChildTaskId = _resultMetadataValue(
      event,
      'pendingApprovalChildTaskId',
    );
    final List<String> lines = <String>[
      if (hasActiveExecution == true)
        copy.isChinese
            ? '执行: 当前有活动运行'
            : 'Execution: active',
      if (hasPendingApprovalResume == true)
        copy.isChinese
            ? '审批: ${pendingApprovalIsHighRisk ? '高风险待批' : '待批恢复'}${pendingApprovalToolName == null ? '' : ' ($pendingApprovalToolName)'}'
            : 'Approval: ${pendingApprovalIsHighRisk ? 'high risk pending' : 'pending resume'}${pendingApprovalToolName == null ? '' : ' ($pendingApprovalToolName)'}',
      if (pendingApprovalChildRunId != null || pendingApprovalChildTaskId != null)
        copy.isChinese
            ? '审批子任务: ${[
                if (pendingApprovalChildRunId != null) 'run $pendingApprovalChildRunId',
                if (pendingApprovalChildTaskId != null) 'task $pendingApprovalChildTaskId',
              ].join(' / ')}'
            : 'Approval child: ${[
                if (pendingApprovalChildRunId != null) 'run $pendingApprovalChildRunId',
                if (pendingApprovalChildTaskId != null) 'task $pendingApprovalChildTaskId',
              ].join(' / ')}',
    ];
    return lines.isEmpty ? null : lines.join('\n');
  }

  String? _subagentMailboxSection(OpenCrayChatRuntimeEventSnapshot event) {
    final int? total = _resultMetadataInt(event, 'mailboxMessageCount');
    final int? pending = _resultMetadataInt(
      event,
      'mailboxPendingMessageCount',
    );
    final String? lastDelivered = _resultMetadataValue(
      event,
      'mailboxLastDeliveredMessageId',
    );
    if ((total ?? 0) <= 0 && (pending ?? 0) <= 0 && lastDelivered == null) {
      return null;
    }
    final String label = _traceSectionLabel(english: 'Mailbox', chinese: '邮箱');
    final List<String> lines = <String>[
      if (total != null || pending != null)
        copy.isChinese
            ? '$label: ${pending ?? 0} 待投递 / ${total ?? 0} 总计'
            : '$label: ${pending ?? 0} pending / ${total ?? 0} total',
      if (lastDelivered != null)
        copy.isChinese
            ? '最近已投递: $lastDelivered'
            : 'Last delivered: $lastDelivered',
    ];
    return lines.join('\n');
  }

  String _buildGroupedToolResultBody({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String? resultSummary = _toolResultMetadataSummary(
      toolName: toolName,
      event: event,
    );
    final String? errorMessage = _nonEmpty(event.errorMessage);
    final String? content =
        _nonEmpty(event.content) ?? _nonEmpty(event.contentPreview);
    return _joinTraceSections(<String?>[
      resultSummary,
      if (errorMessage != null && errorMessage != resultSummary) errorMessage,
      if (content != null &&
          content != resultSummary &&
          content != errorMessage)
        content,
      if (resultSummary == null && errorMessage == null && content == null)
        _toolResultFallbackSummary(
          toolName: toolName,
          event: event,
          pairedToolCall: pairedToolCall,
        ),
    ]);
  }

  String? _toolInspectorCallDetailBody({
    required String toolName,
    required String? argumentsJson,
    required OpenCrayChatRuntimeEventSnapshot? toolResultEvent,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    final Map<String, dynamic>? arguments =
        _decodeJsonObject(argumentsJson) ??
        (toolResultEvent == null
            ? null
            : _toolResultArgumentsFallback(
                toolName: toolName,
                event: toolResultEvent,
              ));
    if (arguments == null || arguments.isEmpty) {
      return null;
    }
    switch (canonicalToolName) {
      case 'TodoWrite':
        return _todoWriteDetailBody(arguments);
      case 'Edit':
        return _editDetailBody(arguments);
      case 'MultiEdit':
        return _multiEditDetailBody(arguments);
      case 'Write':
        return _writeDetailBody(arguments);
      case 'Task':
        return _taskDetailBody(arguments);
      case 'WebSearch':
        return _webSearchDetailBody(arguments);
      default:
        return null;
    }
  }

  String? _toolResultFallbackSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String previewBody = _buildToolResultPreviewBody(
      event: event,
      pairedToolCall: pairedToolCall,
      waitingApproval: false,
      runErrorMessage: null,
    ).trim();
    if (previewBody.isEmpty) {
      return null;
    }
    final String? resultSummary = _toolResultMetadataSummary(
      toolName: toolName,
      event: event,
    );
    if (resultSummary != null && previewBody == resultSummary) {
      return null;
    }
    return previewBody;
  }

  String _indentGroupedToolBlock(String body, {required bool connector}) {
    final List<String> lines = body
        .replaceAll('\r\n', '\n')
        .replaceAll('\r', '\n')
        .split('\n');
    return lines
        .asMap()
        .entries
        .map((entry) {
          final String prefix = entry.key == 0
              ? (connector ? '  └ ' : '    ')
              : '    ';
          return '$prefix${entry.value}';
        })
        .join('\n');
  }

  String _buildApprovalPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _approvalEventBody(event);
  }

  String _buildApprovalHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _approvalEventBody(event);
  }

  String _approvalEventBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String? text = _nonEmpty(event.text);
    if (text != null) {
      return text;
    }
    switch (_nonEmpty(event.status)?.toLowerCase()) {
      case 'approved':
        return copy.isChinese
            ? '审批已通过，继续执行。'
            : 'Approval granted. The run is resuming.';
      case 'rejected':
        return copy.isChinese
            ? '审批已拒绝，等待下一步指示。'
            : 'Approval rejected. Waiting for the next instruction.';
      default:
        return copy.chatRunWaitingApprovalLabel;
    }
  }

  String _approvalTraceLabel(OpenCrayChatRuntimeEventSnapshot event) {
    if (event.kind == 'approval_wait') {
      return copy.chatRunWaitingApprovalLabel;
    }
    if (_nonEmpty(event.status)?.toLowerCase() == 'rejected') {
      return copy.chatRunAwaitingDirectionLabel;
    }
    return _canonicalToolName(_nonEmpty(event.toolName)) ??
        copy.chatRunWorkingLabel;
  }

  String _buildCancellationPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _cancellationEventBody(event);
  }

  String _buildCancellationHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _cancellationEventBody(event);
  }

  String _cancellationEventBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _nonEmpty(event.text) ??
        (copy.isChinese ? '本次运行已中断。' : 'Run interrupted.');
  }

  String _cancellationTraceLabel(OpenCrayChatRuntimeEventSnapshot event) {
    return copy.chatRunAwaitingDirectionLabel;
  }

  String _buildMemoryRetrievalPreviewBody(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    return _joinTraceSections(<String?>[
      _memoryRetrievalSummary(event),
      _memoryRetrievalResultBody(event, includeQueryTerms: false),
    ]);
  }

  String _buildMemoryRetrievalHistoryBody(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    return _joinTraceSections(<String?>[
      _memoryRetrievalSummary(event),
      _memoryRetrievalResultBody(event, includeQueryTerms: true),
    ]);
  }

  String _buildMemoryWritePreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _memoryWriteSummary(event),
      _memoryWriteResultBody(event, includeKinds: false),
    ]);
  }

  String _buildMemoryWriteHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _memoryWriteSummary(event),
      _memoryWriteResultBody(event, includeKinds: true),
    ]);
  }

  String _memoryRetrievalSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final String operation = event.operation?.trim().toLowerCase() ?? '';
    switch (operation) {
      case 'search':
        final String? query = _nonEmpty(event.query);
        if (query != null) {
          return copy.isChinese
              ? '检索记忆：“$query”'
              : 'Search memory for "$query"';
        }
        return copy.isChinese ? '检索记忆' : 'Search memory';
      case 'get':
        final String? path = _nonEmpty(event.path);
        final String range = _memoryGetRangeSummary(event);
        if (path != null) {
          return copy.isChinese
              ? '读取记忆 $path${range.isNotEmpty ? '，$range' : ''}'
              : 'Read memory $path${range.isNotEmpty ? ' $range' : ''}';
        }
        return copy.isChinese ? '读取记忆片段' : 'Read memory snippet';
      default:
        return copy.isChinese ? '访问记忆' : 'Access memory';
    }
  }

  String _memoryMaintenanceLabel() => copy.isChinese ? '记忆' : 'Memory';

  String _memoryWriteSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final List<String> parts = <String?>[
      _memoryWriteCountLabel(
        count: event.writtenRecordIds.length,
        singular: 'wrote',
        plural: 'wrote',
        chinese: '写入 ${event.writtenRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.resolvedRecordIds.length,
        singular: 'resolved',
        plural: 'resolved',
        chinese: '解决 ${event.resolvedRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.suppressedRecordIds.length,
        singular: 'suppressed',
        plural: 'suppressed',
        chinese: '抑制 ${event.suppressedRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.reaffirmedRecordIds.length,
        singular: 'reaffirmed',
        plural: 'reaffirmed',
        chinese: '续期 ${event.reaffirmedRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.expiredRecordIds.length,
        singular: 'expired',
        plural: 'expired',
        chinese: '过期 ${event.expiredRecordIds.length} 条',
      ),
    ].whereType<String>().toList(growable: false);
    if (parts.isEmpty) {
      return copy.isChinese
          ? '本轮没有记忆变更。'
          : 'No memory changes recorded for this turn.';
    }
    if (copy.isChinese) {
      return '记忆维护：${parts.join('，')}';
    }
    return 'Memory maintenance: ${parts.join(', ')}';
  }

  String _memoryWriteResultBody(
    OpenCrayChatRuntimeEventSnapshot event, {
    required bool includeKinds,
  }) {
    return _joinTraceSections(<String?>[
      _memoryWriteListSection(
        englishLabel: 'Written',
        chineseLabel: '写入',
        values: event.writtenRecordIds,
      ),
      includeKinds
          ? _memoryWriteListSection(
              englishLabel: 'Kinds',
              chineseLabel: '类型',
              values: event.writtenKinds,
            )
          : null,
      _memoryWriteListSection(
        englishLabel: 'Resolved',
        chineseLabel: '解决',
        values: event.resolvedRecordIds,
      ),
      _memoryWriteListSection(
        englishLabel: 'Suppressed',
        chineseLabel: '抑制',
        values: event.suppressedRecordIds,
      ),
      _memoryWriteListSection(
        englishLabel: 'Reaffirmed',
        chineseLabel: '续期',
        values: event.reaffirmedRecordIds,
      ),
      _memoryWriteListSection(
        englishLabel: 'Expired',
        chineseLabel: '过期',
        values: event.expiredRecordIds,
      ),
    ]);
  }

  String? _memoryWriteCountLabel({
    required int count,
    required String singular,
    required String plural,
    required String chinese,
  }) {
    if (count <= 0) {
      return null;
    }
    if (copy.isChinese) {
      return chinese;
    }
    final String noun = count == 1 ? 'record' : 'records';
    final String verb = count == 1 ? singular : plural;
    return '$verb $count $noun';
  }

  String? _memoryWriteListSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  }) {
    if (values.isEmpty) {
      return null;
    }
    final String label = copy.isChinese ? chineseLabel : englishLabel;
    return '$label: ${values.join(', ')}';
  }

  String _memoryRetrievalResultBody(
    OpenCrayChatRuntimeEventSnapshot event, {
    required bool includeQueryTerms,
  }) {
    final String operation = event.operation?.trim().toLowerCase() ?? '';
    switch (operation) {
      case 'search':
        final String? resultSummary = _memorySearchResultSummary(event);
        final String? matchLocations = _memorySearchMatchLocations(event);
        final String? queryTerms =
            includeQueryTerms && event.queryTerms.isNotEmpty
            ? (copy.isChinese
                  ? '关键词：${event.queryTerms.join(', ')}'
                  : 'Query terms: ${event.queryTerms.join(', ')}')
            : null;
        return _joinTraceSections(<String?>[
          resultSummary,
          matchLocations,
          queryTerms,
        ]);
      case 'get':
        return _joinTraceSections(<String?>[
          _memoryGetResultSummary(event),
          includeQueryTerms ? _memoryGetLocationSummary(event) : null,
        ]);
      default:
        return copy.chatRunThinkingActive;
    }
  }

  String? _memorySearchResultSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final int? resultCount = event.resultCount;
    final int? corpusFileCount = event.corpusFileCount;
    if (resultCount == null && corpusFileCount == null) {
      return null;
    }
    if (copy.isChinese) {
      final String resultPart = resultCount == null ? '' : '命中 $resultCount 条';
      final String corpusPart = corpusFileCount == null
          ? ''
          : '覆盖 $corpusFileCount 个记忆文件';
      return <String>[
        resultPart,
        corpusPart,
      ].where((part) => part.isNotEmpty).join('，');
    }
    final String resultPart = resultCount == null
        ? ''
        : resultCount == 1
        ? '1 match'
        : '$resultCount matches';
    final String corpusPart = corpusFileCount == null
        ? ''
        : corpusFileCount == 1
        ? 'across 1 projected file'
        : 'across $corpusFileCount projected files';
    return <String>[
      resultPart,
      corpusPart,
    ].where((part) => part.isNotEmpty).join(' ');
  }

  String? _memorySearchMatchLocations(OpenCrayChatRuntimeEventSnapshot event) {
    if (event.paths.isEmpty && event.lineRanges.isEmpty) {
      return null;
    }
    final int count = event.paths.length > event.lineRanges.length
        ? event.paths.length
        : event.lineRanges.length;
    final List<String> entries = <String>[];
    for (int index = 0; index < count; index += 1) {
      final String? path = index < event.paths.length
          ? _nonEmpty(event.paths[index])
          : null;
      final String? lineRange = index < event.lineRanges.length
          ? _nonEmpty(event.lineRanges[index])
          : null;
      final String entry = switch ((path, lineRange)) {
        (final String p?, final String r?) => '$p#$r',
        (final String p?, null) => p,
        (null, final String r?) => r,
        _ => '',
      };
      if (entry.isNotEmpty) {
        entries.add(entry);
      }
    }
    if (entries.isEmpty) {
      return null;
    }
    return copy.isChinese
        ? '命中位置：\n${entries.join('\n')}'
        : 'Matches:\n${entries.join('\n')}';
  }

  String _memoryGetRangeSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final int? fromLine = event.fromLine;
    final int? returnedLineCount = event.returnedLineCount;
    if (fromLine == null && returnedLineCount == null) {
      return '';
    }
    if (returnedLineCount == null || returnedLineCount <= 0) {
      return copy.isChinese ? '从第 $fromLine 行开始' : 'from line $fromLine';
    }
    final int endLine = fromLine == null
        ? returnedLineCount
        : fromLine + returnedLineCount - 1;
    if (copy.isChinese) {
      return fromLine == null
          ? '共 $returnedLineCount 行'
          : '第 $fromLine-$endLine 行';
    }
    return fromLine == null
        ? '$returnedLineCount lines'
        : 'lines $fromLine-$endLine';
  }

  String? _memoryGetResultSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final int? returnedLineCount = event.returnedLineCount;
    final int? totalLineCount = event.totalLineCount;
    if (returnedLineCount == null && totalLineCount == null) {
      return null;
    }
    if (copy.isChinese) {
      final String returnedPart = returnedLineCount == null
          ? ''
          : '返回 $returnedLineCount 行';
      final String totalPart = totalLineCount == null
          ? ''
          : '文件总计 $totalLineCount 行';
      return <String>[
        returnedPart,
        totalPart,
      ].where((part) => part.isNotEmpty).join('，');
    }
    final String returnedPart = returnedLineCount == null
        ? ''
        : returnedLineCount == 1
        ? 'Returned 1 line'
        : 'Returned $returnedLineCount lines';
    final String totalPart = totalLineCount == null
        ? ''
        : totalLineCount == 1
        ? 'from a 1-line file'
        : 'from a $totalLineCount-line file';
    return <String>[
      returnedPart,
      totalPart,
    ].where((part) => part.isNotEmpty).join(' ');
  }

  String? _memoryGetLocationSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final String? path = _nonEmpty(event.path);
    final String range = _memoryGetRangeSummary(event);
    if (path == null) {
      return null;
    }
    if (range.isEmpty) {
      return path;
    }
    return copy.isChinese ? '$path，$range' : '$path $range';
  }

  String _toolActionSummary({
    required String toolName,
    required String? argumentsJson,
  }) => _toolActionSummaryFromArguments(
    toolName: toolName,
    arguments: _decodeJsonObject(argumentsJson),
  );

  String _toolActionSummaryFromArguments({
    required String toolName,
    required Map<String, dynamic>? arguments,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    final String fallback = copy.chatRunCallingTool(canonicalToolName);
    switch (canonicalToolName) {
      case 'Read':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return fallback;
        }
        final int? offset = _argumentInt(arguments, 'offset');
        final int? limit = _argumentInt(arguments, 'limit');
        final String range = _readRangeSummary(offset: offset, limit: limit);
        return copy.isChinese
            ? '读取 $path${range.isNotEmpty ? '，$range' : ''}'
            : 'Read $path${range.isNotEmpty ? ' $range' : ''}';
      case 'Grep':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return fallback;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        final String? glob = _argumentString(arguments, 'glob');
        final String globSuffix = glob == null
            ? ''
            : copy.isChinese
            ? '，glob: $glob'
            : ' (glob: $glob)';
        return copy.isChinese
            ? '在 $path 中搜索 "$pattern"$globSuffix'
            : 'Search "$pattern" in $path$globSuffix';
      case 'Glob':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return fallback;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        return copy.isChinese
            ? '在 $path 中匹配 $pattern'
            : 'Match $pattern in $path';
      case 'LS':
        final String path =
            _argumentString(arguments, 'path') ??
            _argumentString(arguments, 'file_path') ??
            '.';
        return copy.isChinese ? '列出 $path' : 'List $path';
      case 'Write':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        return path == null
            ? fallback
            : copy.isChinese
            ? '写入 $path'
            : 'Write $path';
      case 'ImportFile':
        final String? sourcePath = _argumentString(arguments, 'source_path');
        final String? destinationPath = _argumentString(
          arguments,
          'destination_path',
        );
        if (sourcePath == null || destinationPath == null) {
          return fallback;
        }
        return copy.isChinese
            ? '导入 $sourcePath 到 $destinationPath'
            : 'Import $sourcePath to $destinationPath';
      case 'Edit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        return path == null
            ? fallback
            : copy.isChinese
            ? '编辑 $path'
            : 'Edit $path';
      case 'MultiEdit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        final int editCount = _argumentList(arguments, 'edits')?.length ?? 0;
        if (path == null) {
          return fallback;
        }
        if (editCount <= 0) {
          return copy.isChinese ? '批量编辑 $path' : 'MultiEdit $path';
        }
        return copy.isChinese
            ? '对 $path 应用 $editCount 处编辑'
            : 'Apply $editCount edit(s) to $path';
      case 'WebSearch':
        return _webSearchActionSummary(arguments, fallback: fallback);
      case 'TodoWrite':
        final _TodoTraceSummary? summary = _todoSummaryFromArguments(arguments);
        if (arguments?.containsKey('todos') != true) {
          return copy.isChinese ? '读取当前待办列表' : 'Read current todo list';
        }
        return _todoWriteActionSummary(summary: summary, mutated: true);
      case 'Bash':
      case 'command_exec':
        final String? command = _argumentString(arguments, 'command');
        if (command == null) {
          return fallback;
        }
        return copy.isChinese ? '运行命令 $command' : 'Run command $command';
      case 'python_exec':
        final String? scriptPath = _argumentString(arguments, 'script_path');
        if (scriptPath == null) {
          return fallback;
        }
        return copy.isChinese
            ? '运行 Python 脚本 $scriptPath'
            : 'Run Python script $scriptPath';
      case 'ProcessStart':
        final String? scriptPath = _argumentString(arguments, 'script_path');
        final String? command = _argumentString(arguments, 'command');
        if (scriptPath != null) {
          return copy.isChinese
              ? '启动后台 Python 进程 $scriptPath'
              : 'Start background Python process $scriptPath';
        }
        if (command == null) {
          return fallback;
        }
        return copy.isChinese
            ? '启动后台进程 $command'
            : 'Start background process $command';
      case 'ProcessRead':
        final String? processId = _argumentString(arguments, 'process_id');
        if (processId == null) {
          return fallback;
        }
        return copy.isChinese
            ? '读取进程 $processId 的输出'
            : 'Read output for process $processId';
      case 'ProcessWait':
        final String? processId = _argumentString(arguments, 'process_id');
        if (processId == null) {
          return fallback;
        }
        return copy.isChinese
            ? '等待进程 $processId'
            : 'Wait for process $processId';
      case 'ProcessTerminate':
        final String? processId = _argumentString(arguments, 'process_id');
        if (processId == null) {
          return fallback;
        }
        return copy.isChinese
            ? '终止进程 $processId'
            : 'Terminate process $processId';
      case 'WebFetch':
        final String? url = _argumentString(arguments, 'url');
        if (url == null) {
          return fallback;
        }
        return copy.isChinese ? '抓取网页 $url' : 'Fetch $url';
      case 'Task':
        final String? description = _argumentString(arguments, 'description');
        final String? subagentType = _argumentString(
          arguments,
          'subagent_type',
        );
        final String target = subagentType == null
            ? (copy.isChinese ? '子代理' : 'subagent')
            : _subagentTypeDisplay(subagentType);
        if (description == null) {
          return copy.isChinese ? '委派给 $target' : 'Delegate to $target';
        }
        return copy.isChinese
            ? '委派给 $target：$description'
            : 'Delegate to $target: $description';
      default:
        return fallback;
    }
  }

  String _toolResultActionSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String? argumentsJson = _nonEmpty(pairedToolCall?.argumentsJson);
    if (argumentsJson != null) {
      return _toolActionSummary(
        toolName: toolName,
        argumentsJson: argumentsJson,
      );
    }
    if (toolName == 'TodoWrite') {
      return _todoWriteActionSummary(
        summary: _todoSummaryFromResultMetadata(event),
        mutated: _resultMetadataBool(event, 'mutated') == true,
      );
    }
    return _toolActionSummaryFromArguments(
      toolName: toolName,
      arguments: _toolResultArgumentsFallback(toolName: toolName, event: event),
    );
  }

  String? _toolCallDetailBody({
    required String toolName,
    required String? argumentsJson,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    final Map<String, dynamic>? arguments = _decodeJsonObject(argumentsJson);
    if (arguments == null || arguments.isEmpty) {
      return _nonEmpty(argumentsJson);
    }
    switch (canonicalToolName) {
      case 'TodoWrite':
        return _todoWriteDetailBody(arguments);
      case 'Edit':
        return _editDetailBody(arguments);
      case 'MultiEdit':
        return _multiEditDetailBody(arguments);
      case 'Write':
        return _writeDetailBody(arguments);
      case 'Task':
        return _taskDetailBody(arguments);
      case 'WebSearch':
        return _webSearchDetailBody(arguments);
      default:
        return _prettyJson(arguments);
    }
  }

  String _webSearchActionSummary(
    Map<String, dynamic>? arguments, {
    required String fallback,
  }) {
    final String operation = _webSearchOperation(arguments);
    final String? query = _webSearchPrimaryQuery(arguments);
    final String? url = _argumentString(arguments, 'url');
    final String? text = _webSearchFindText(arguments);
    final List<String> domains = _argumentStringList(arguments, 'domains');
    final String domainSuffix = domains.isEmpty
        ? ''
        : copy.isChinese
        ? '，范围 ${domains.join(', ')}'
        : ' within ${domains.join(', ')}';
    switch (operation) {
      case 'open_page':
        if (url == null) {
          return fallback;
        }
        return copy.isChinese
            ? '打开搜索结果页面 $url'
            : 'Open search result page $url';
      case 'find_in_page':
        if (url == null && text == null) {
          return fallback;
        }
        if (copy.isChinese) {
          final String target = text == null ? '' : ' "$text"';
          final String location = url == null ? '' : ' 于 $url';
          return '在页面内搜索$target$location';
        }
        final String target = text == null ? '' : ' "$text"';
        final String location = url == null ? '' : ' in $url';
        return 'Find in page$target$location';
      default:
        if (query == null) {
          return copy.isChinese
              ? '搜索网络$domainSuffix'
              : 'Search the web$domainSuffix';
        }
        return copy.isChinese
            ? '搜索网络 "$query"$domainSuffix'
            : 'Search the web for "$query"$domainSuffix';
    }
  }

  List<ChatRunTraceInspectorTextPart>? _webSearchInspectorParts(
    Map<String, dynamic>? arguments,
  ) {
    final String operation = _webSearchOperation(arguments);
    final String? query = _webSearchPrimaryQuery(arguments);
    final String? url = _argumentString(arguments, 'url');
    final String? text = _webSearchFindText(arguments);
    final List<String> domains = _argumentStringList(arguments, 'domains');
    switch (operation) {
      case 'open_page':
        if (url == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(
            copy.isChinese ? '打开搜索结果页面' : 'Open search result page',
          ),
          _inspectorNeutral(' '),
          _inspectorTarget(url),
        ];
      case 'find_in_page':
        if (url == null && text == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '页内搜索' : 'Find in page'),
          if (text != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(' '),
            _inspectorTarget('"$text"'),
          ],
          if (url != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? ' 于 ' : ' in '),
            _inspectorScope(url),
          ],
        ];
      default:
        if (query == null && domains.isEmpty) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(copy.isChinese ? '搜索网络' : 'Search the web'),
          ];
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '搜索网络' : 'Search the web'),
          if (query != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? ' ' : ' for '),
            _inspectorTarget('"$query"'),
          ],
          if (domains.isNotEmpty) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? '，范围 ' : ' within '),
            _inspectorScope(domains.join(', ')),
          ],
        ];
    }
  }

  String? _webSearchDetailBody(Map<String, dynamic> arguments) {
    final String? query = _argumentString(arguments, 'query');
    final List<String> queries = <String>{
      if (query != null) query,
      ..._argumentStringList(arguments, 'queries'),
    }.toList(growable: false);
    final List<String> domains = _argumentStringList(arguments, 'domains');
    final List<String> sourceUrls = _argumentStringList(
      arguments,
      'sourceUrls',
    );
    final String? url = _argumentString(arguments, 'url');
    final String? text = _webSearchFindText(arguments);
    final String detail = _joinTraceSections(<String?>[
      _labeledInlineSection(
        englishLabel: 'Queries',
        chineseLabel: '查询',
        values: queries,
      ),
      url == null
          ? null
          : '${_traceSectionLabel(english: 'URL', chinese: '链接')}: $url',
      text == null
          ? null
          : '${_traceSectionLabel(english: 'Text', chinese: '文本')}: $text',
      _labeledInlineSection(
        englishLabel: 'Domains',
        chineseLabel: '域名',
        values: domains,
      ),
      _labeledInlineSection(
        englishLabel: 'Sources',
        chineseLabel: '来源',
        values: sourceUrls,
      ),
    ]).trim();
    return detail.isEmpty ? null : detail;
  }

  String _webSearchOperation(Map<String, dynamic>? arguments) =>
      _argumentString(arguments, 'operation')?.trim().toLowerCase() ?? '';

  String? _webSearchPrimaryQuery(Map<String, dynamic>? arguments) {
    final String? query = _argumentString(arguments, 'query');
    if (query != null) {
      return query;
    }
    final List<String> queries = _argumentStringList(arguments, 'queries');
    return queries.isEmpty ? null : queries.first;
  }

  String? _webSearchFindText(Map<String, dynamic>? arguments) =>
      _argumentString(arguments, 'text') ??
      _argumentString(arguments, 'pattern');

  String? _taskDetailBody(Map<String, dynamic> arguments) {
    final String? prompt = _argumentString(arguments, 'prompt');
    final String? contextMode = _argumentString(arguments, 'context_mode');
    final List<String> allowedTools = _argumentStringList(
      arguments,
      'allowed_tools',
    );
    return _joinTraceSections(<String?>[
      prompt == null
          ? null
          : '${_traceSectionLabel(english: 'Prompt', chinese: '提示')}: $prompt',
      contextMode == null
          ? null
          : '${_traceSectionLabel(english: 'Context', chinese: '上下文')}: ${_contextModeDisplay(contextMode)}',
      _labeledInlineSection(
        englishLabel: 'Allowed tools',
        chineseLabel: '允许工具',
        values: allowedTools,
      ),
    ]);
  }

  String? _todoWriteDetailBody(Map<String, dynamic> arguments) {
    if (!arguments.containsKey('todos')) {
      return null;
    }
    final List<dynamic>? todos = _argumentList(arguments, 'todos');
    if (todos == null || todos.isEmpty) {
      return null;
    }
    final List<String> lines = <String>[];
    for (final dynamic rawTodo in todos) {
      if (rawTodo is! Map) {
        continue;
      }
      final Map<String, dynamic> todo = Map<String, dynamic>.from(
        rawTodo.map((key, value) => MapEntry(key.toString(), value)),
      );
      final String? content = _argumentString(todo, 'content');
      if (content == null) {
        continue;
      }
      final String statusLabel = switch (_argumentString(
        todo,
        'status',
      )?.toLowerCase()) {
        'completed' ||
        'complete' ||
        'done' => copy.isChinese ? '[已完成]' : '[completed]',
        'in_progress' ||
        'in-progress' ||
        'inprogress' => copy.isChinese ? '[进行中]' : '[in_progress]',
        _ => copy.isChinese ? '[待处理]' : '[pending]',
      };
      final String? activeForm =
          _argumentString(todo, 'activeForm') ??
          _argumentString(todo, 'active_form');
      lines.add(
        activeForm == null
            ? '$statusLabel $content'
            : copy.isChinese
            ? '$statusLabel $content | 当前动作：$activeForm'
            : '$statusLabel $content | active: $activeForm',
      );
    }
    return lines.isEmpty ? null : lines.join('\n');
  }

  String? _editDetailBody(Map<String, dynamic> arguments) {
    final String? oldString = _argumentString(arguments, 'old_string');
    final String? newString = _argumentString(arguments, 'new_string');
    if (oldString == null || newString == null) {
      return _prettyJson(arguments);
    }
    return _diffBlock(oldString: oldString, newString: newString);
  }

  String? _multiEditDetailBody(Map<String, dynamic> arguments) {
    final List<dynamic>? edits = _argumentList(arguments, 'edits');
    if (edits == null || edits.isEmpty) {
      return _prettyJson(arguments);
    }
    final List<String> blocks = <String>[];
    for (int index = 0; index < edits.length; index += 1) {
      final dynamic rawEdit = edits[index];
      if (rawEdit is! Map) {
        continue;
      }
      final Map<String, dynamic> edit = Map<String, dynamic>.from(
        rawEdit.map((key, value) => MapEntry(key.toString(), value)),
      );
      final String? oldString = _argumentString(edit, 'old_string');
      final String? newString = _argumentString(edit, 'new_string');
      if (oldString == null || newString == null) {
        blocks.add(_prettyJson(edit));
        continue;
      }
      blocks.add(
        _joinTraceSections(<String>[
          copy.isChinese ? '编辑 ${index + 1}' : 'Edit ${index + 1}',
          _diffBlock(oldString: oldString, newString: newString),
        ]),
      );
    }
    return blocks.isEmpty ? _prettyJson(arguments) : blocks.join('\n\n');
  }

  String? _writeDetailBody(Map<String, dynamic> arguments) {
    final String? content = _argumentString(arguments, 'content');
    if (content == null) {
      return _prettyJson(arguments);
    }
    return content;
  }

  String _diffBlock({required String oldString, required String newString}) {
    final List<String> removed = _diffLines(prefix: '-', text: oldString);
    final List<String> added = _diffLines(prefix: '+', text: newString);
    return <String>[...removed, ...added].join('\n');
  }

  List<String> _diffLines({required String prefix, required String text}) {
    final List<String> lines = text
        .replaceAll('\r\n', '\n')
        .replaceAll('\r', '\n')
        .split('\n');
    if (lines.isEmpty) {
      return <String>['$prefix '];
    }
    return lines.map((line) => '$prefix $line').toList(growable: false);
  }

  String _readRangeSummary({required int? offset, required int? limit}) {
    if (offset == null && limit == null) {
      return '';
    }
    if (copy.isChinese) {
      if (offset != null && limit != null) {
        final int endLine = offset + limit - 1;
        return '第 $offset-$endLine 行';
      }
      if (offset != null) {
        return '从第 $offset 行开始';
      }
      return '前 $limit 行';
    }
    if (offset != null && limit != null) {
      final int endLine = offset + limit - 1;
      return 'lines $offset-$endLine';
    }
    if (offset != null) {
      return 'from line $offset';
    }
    return 'first $limit lines';
  }

  Map<String, dynamic>? _toolResultArgumentsFallback({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    switch (canonicalToolName) {
      case 'Read':
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (filePath == null) {
          return null;
        }
        return <String, dynamic>{
          'file_path': filePath,
          if (_resultMetadataInt(event, 'offset') != null)
            'offset': _resultMetadataInt(event, 'offset'),
          if (_resultMetadataInt(event, 'limit') != null)
            'limit': _resultMetadataInt(event, 'limit'),
        };
      case 'LS':
        return <String, dynamic>{
          if (_resultMetadataValue(event, 'path') != null)
            'path': _resultMetadataValue(event, 'path'),
        };
      case 'Grep':
        final String? pattern = _resultMetadataValue(event, 'pattern');
        if (pattern == null) {
          return null;
        }
        return <String, dynamic>{
          'pattern': pattern,
          if (_resultMetadataValue(event, 'path') != null)
            'path': _resultMetadataValue(event, 'path'),
          if (_resultMetadataValue(event, 'glob') != null)
            'glob': _resultMetadataValue(event, 'glob'),
        };
      case 'Glob':
        final String? pattern = _resultMetadataValue(event, 'pattern');
        if (pattern == null) {
          return null;
        }
        return <String, dynamic>{
          'pattern': pattern,
          if (_resultMetadataValue(event, 'path') != null)
            'path': _resultMetadataValue(event, 'path'),
        };
      case 'WebSearch':
        final String? operation = _resultMetadataValue(
          event,
          'providerManagedOperation',
        );
        final String? query = _resultMetadataValue(event, 'query');
        final String? url = _resultMetadataValue(event, 'url');
        final String? text = _resultMetadataValue(event, 'text');
        final List<String> sourceUrls = _csvValues(
          _resultMetadataValue(event, 'sourceUrls'),
        );
        if (operation == null &&
            query == null &&
            url == null &&
            text == null &&
            sourceUrls.isEmpty) {
          return null;
        }
        return <String, dynamic>{
          if (operation != null) 'operation': operation,
          if (query != null) 'query': query,
          if (url != null) 'url': url,
          if (text != null) 'text': text,
          if (sourceUrls.isNotEmpty) 'sourceUrls': sourceUrls,
        };
      case 'Write':
      case 'Edit':
      case 'MultiEdit':
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (filePath == null) {
          return null;
        }
        return <String, dynamic>{'file_path': filePath};
      case 'ImportFile':
        final String? sourcePath = _resultMetadataValue(event, 'sourcePath');
        final String? destinationPath = _resultMetadataValue(
          event,
          'destinationPath',
        );
        if (sourcePath == null || destinationPath == null) {
          return null;
        }
        return <String, dynamic>{
          'source_path': sourcePath,
          'destination_path': destinationPath,
        };
      case 'Task':
        final String? description = _resultMetadataValue(
          event,
          'delegationDescription',
        );
        final String? prompt = _resultMetadataValue(
          event,
          'delegationPromptPreview',
        );
        final String? subagentType =
            _resultMetadataValue(event, 'delegationSubagentType') ??
            _resultMetadataValue(event, 'subagentType');
        final String? contextMode =
            _resultMetadataValue(event, 'delegationContextMode') ??
            _resultMetadataValue(event, 'subagentContextMode');
        final List<String> allowedTools = _csvValues(
          _resultMetadataValue(event, 'delegationAllowedTools'),
        );
        if (description == null &&
            prompt == null &&
            subagentType == null &&
            contextMode == null &&
            allowedTools.isEmpty) {
          return null;
        }
        return <String, dynamic>{
          if (description != null) 'description': description,
          if (prompt != null) 'prompt': prompt,
          if (subagentType != null) 'subagent_type': subagentType,
          if (contextMode != null) 'context_mode': contextMode,
          if (allowedTools.isNotEmpty) 'allowed_tools': allowedTools,
        };
      case 'Bash':
      case 'command_exec':
        final String? command =
            _resultMetadataValue(event, 'commandSummary') ??
            _resultMetadataValue(event, 'command');
        if (command == null) {
          return null;
        }
        return <String, dynamic>{'command': command};
      case 'python_exec':
        final String? scriptPath = _resultMetadataValue(event, 'scriptPath');
        if (scriptPath == null) {
          return null;
        }
        return <String, dynamic>{'script_path': scriptPath};
      case 'ProcessStart':
        final String? processScriptPath = _resultMetadataValue(
          event,
          'scriptPath',
        );
        final String? processCommand =
            _resultMetadataValue(event, 'commandSummary') ??
            _resultMetadataValue(event, 'command');
        if (processScriptPath == null && processCommand == null) {
          return null;
        }
        return <String, dynamic>{
          if (processScriptPath != null) 'script_path': processScriptPath,
          if (processCommand != null) 'command': processCommand,
        };
      case 'ProcessRead':
      case 'ProcessWait':
      case 'ProcessTerminate':
        final String? processId = _resultMetadataValue(event, 'processId');
        if (processId == null) {
          return null;
        }
        return <String, dynamic>{'process_id': processId};
      case 'WebFetch':
        final String? url =
            _resultMetadataValue(event, 'requestedUrl') ??
            _resultMetadataValue(event, 'finalUrl') ??
            _resultMetadataValue(event, 'url');
        if (url == null) {
          return null;
        }
        return <String, dynamic>{'url': url};
      default:
        return null;
    }
  }

  String? _toolResultMetadataSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    switch (canonicalToolName) {
      case 'LS':
        final int? entryCount = _resultMetadataInt(event, 'entryCount');
        final String? path = _resultMetadataValue(event, 'path');
        final bool truncated = _resultMetadataTruncated(event);
        if (entryCount == null) {
          return null;
        }
        if (copy.isChinese) {
          final String summary = path == null
              ? '列出了 $entryCount 项'
              : '在 $path 中列出了 $entryCount 项';
          return truncated ? '$summary，结果已按结果上限截断' : summary;
        }
        final String summary = path == null
            ? 'Listed $entryCount entr${entryCount == 1 ? 'y' : 'ies'}'
            : 'Listed $entryCount entr${entryCount == 1 ? 'y' : 'ies'} in $path';
        return truncated
            ? '$summary. Output truncated at the tool result limit.'
            : summary;
      case 'Read':
        final int? returnedLineCount = _resultMetadataInt(
          event,
          'returnedLineCount',
        );
        final int? totalLineCount = _resultMetadataInt(event, 'totalLineCount');
        final bool truncated = _resultMetadataTruncated(event);
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (returnedLineCount == null &&
            totalLineCount == null &&
            !truncated &&
            filePath == null) {
          return null;
        }
        if (copy.isChinese) {
          final List<String> parts = <String>[
            if (filePath != null) filePath,
            if (returnedLineCount != null) '返回 $returnedLineCount 行',
            if (totalLineCount != null) '文件总计 $totalLineCount 行',
            if (truncated) '结果已按读取预算截断',
          ];
          return parts.join('，');
        }
        final List<String> parts = <String>[
          if (returnedLineCount != null)
            returnedLineCount == 1
                ? 'Returned 1 line'
                : 'Returned $returnedLineCount lines',
          if (filePath != null) 'from $filePath',
          if (totalLineCount != null)
            totalLineCount == 1
                ? '(1-line file)'
                : '($totalLineCount-line file)',
          if (truncated) 'Output truncated to the read budget.',
        ];
        return parts.join(' ');
      case 'Grep':
        final int? matchCount = _resultMetadataInt(event, 'matchCount');
        final String? pattern = _resultMetadataValue(event, 'pattern');
        final String? path = _resultMetadataValue(event, 'path');
        final bool truncated = _resultMetadataTruncated(event);
        if (matchCount == null) {
          return null;
        }
        if (copy.isChinese) {
          final String target = path ?? '.';
          if (pattern == null) {
            final String summary = '在 $target 中找到 $matchCount 处匹配';
            return truncated ? '$summary，结果已按结果上限截断' : summary;
          }
          final String summary = '在 $target 中为 "$pattern" 找到 $matchCount 处匹配';
          return truncated ? '$summary，结果已按结果上限截断' : summary;
        }
        final String target = path ?? '.';
        if (pattern == null) {
          final String summary = matchCount == 1
              ? 'Found 1 match in $target'
              : 'Found $matchCount matches in $target';
          return truncated
              ? '$summary. Output truncated at the tool result limit.'
              : summary;
        }
        final String summary = matchCount == 1
            ? 'Found 1 match for "$pattern" in $target'
            : 'Found $matchCount matches for "$pattern" in $target';
        return truncated
            ? '$summary. Output truncated at the tool result limit.'
            : summary;
      case 'Glob':
        final int? matchCount = _resultMetadataInt(event, 'matchCount');
        final String? pattern = _resultMetadataValue(event, 'pattern');
        final String? path = _resultMetadataValue(event, 'path');
        final bool truncated = _resultMetadataTruncated(event);
        if (matchCount == null) {
          return null;
        }
        if (copy.isChinese) {
          final String target = path ?? '.';
          final String summary = pattern == null
              ? '在 $target 中匹配到 $matchCount 个路径'
              : '在 $target 中为 $pattern 匹配到 $matchCount 个路径';
          return truncated ? '$summary，结果已按结果上限截断' : summary;
        }
        final String target = path ?? '.';
        final String summary = pattern == null
            ? 'Matched $matchCount path(s) in $target'
            : 'Matched $matchCount path(s) for $pattern in $target';
        return truncated
            ? '$summary. Output truncated at the tool result limit.'
            : summary;
      case 'WebSearch':
        final int? sourceCount = _resultMetadataInt(event, 'sourceCount');
        final String? operation = _resultMetadataValue(
          event,
          'providerManagedOperation',
        )?.trim().toLowerCase();
        final String? status = _resultMetadataValue(
          event,
          'providerManagedStatus',
        );
        final String? query = _resultMetadataValue(event, 'query');
        final String? url = _resultMetadataValue(event, 'url');
        final String? text = _resultMetadataValue(event, 'text');
        final bool managed =
            _resultMetadataValue(event, 'providerManaged') == 'true';
        if (sourceCount == null &&
            operation == null &&
            status == null &&
            query == null &&
            url == null &&
            text == null) {
          return null;
        }
        if (copy.isChinese) {
          return <String>[
            if (managed) '原生搜索',
            switch (operation) {
              'open_page' => url == null ? '' : '打开页面 $url',
              'find_in_page' => <String>[
                if (text != null) '页内搜索 "$text"',
                if (url != null) url,
              ].where((part) => part.isNotEmpty).join('，'),
              _ => query == null ? '' : '搜索 "$query"',
            },
            if (sourceCount != null) '来源 $sourceCount 个',
            if (status != null) '状态 $status',
          ].where((part) => part.isNotEmpty).join('，');
        }
        return <String>[
          if (managed) 'Provider-managed search',
          switch (operation) {
            'open_page' => url == null ? '' : 'opened $url',
            'find_in_page' => <String>[
              if (text != null) 'find "$text"',
              if (url != null) 'in $url',
            ].where((part) => part.isNotEmpty).join(' '),
            _ => query == null ? '' : 'search "$query"',
          },
          if (sourceCount != null)
            sourceCount == 1 ? '1 source' : '$sourceCount sources',
          if (status != null) 'status $status',
        ].where((part) => part.isNotEmpty).join(' ');
      case 'Edit':
        final int? replacementCount = _resultMetadataInt(
          event,
          'replacementCount',
        );
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (replacementCount == null) {
          return null;
        }
        if (copy.isChinese) {
          return filePath == null
              ? '应用了 $replacementCount 处替换'
              : '在 $filePath 中应用了 $replacementCount 处替换';
        }
        return filePath == null
            ? 'Applied $replacementCount replacement(s)'
            : 'Applied $replacementCount replacement(s) in $filePath';
      case 'MultiEdit':
        final int? replacementCount = _resultMetadataInt(
          event,
          'replacementCount',
        );
        final int? editCount = _resultMetadataInt(event, 'editCount');
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (replacementCount == null && editCount == null && filePath == null) {
          return null;
        }
        if (copy.isChinese) {
          final List<String> parts = <String>[
            if (filePath != null) filePath,
            if (replacementCount != null) '$replacementCount 处替换',
            if (editCount != null) '$editCount 个编辑块',
          ];
          return parts.isEmpty ? null : '应用了 ${parts.join('，')}';
        }
        final List<String> parts = <String>[
          if (replacementCount != null) '$replacementCount replacement(s)',
          if (editCount != null) 'across $editCount edit(s)',
          if (filePath != null) 'in $filePath',
        ];
        return parts.isEmpty ? null : 'Applied ${parts.join(' ')}';
      case 'ImportFile':
        final String? sourcePath = _resultMetadataValue(event, 'sourcePath');
        final String? destinationPath = _resultMetadataValue(
          event,
          'destinationPath',
        );
        if (sourcePath == null || destinationPath == null) {
          return null;
        }
        return copy.isChinese
            ? '导入 $sourcePath 到 $destinationPath'
            : 'Imported $sourcePath to $destinationPath';
      case 'TodoWrite':
        return _todoWriteResultSummary(event);
      case 'Task':
        final String? executionState = _resultMetadataValue(
          event,
          'childExecutionState',
        );
        final String? status = _resultMetadataValue(
          event,
          'childExecutionStatus',
        );
        final String? subagentType =
            _resultMetadataValue(event, 'delegationSubagentType') ??
            _resultMetadataValue(event, 'subagentType');
        final String? contextMode =
            _resultMetadataValue(event, 'delegationContextMode') ??
            _resultMetadataValue(event, 'subagentContextMode');
        final int? turnCount = _resultMetadataInt(event, 'childTurnCount');
        final int? toolCallCount = _resultMetadataInt(
          event,
          'childToolCallCount',
        );
        final List<String> allowedTools = _csvValues(
          _resultMetadataValue(event, 'delegationAllowedTools'),
        );
        final String actor = _subagentTypeDisplay(subagentType);
        final String statusSummary =
            _subagentExecutionStateSummary(
              actor: actor,
              executionState: executionState,
            ) ??
            switch (status?.toLowerCase()) {
              'success' || 'completed' =>
                copy.isChinese ? '$actor 已完成' : '$actor completed',
              'cancelled' =>
                copy.isChinese ? '$actor 已取消' : '$actor cancelled',
              'approval_required' =>
                copy.isChinese
                    ? '$actor 等待审批'
                    : '$actor waiting for approval',
              'high_risk_approval_required' =>
                copy.isChinese
                    ? '$actor 等待高风险审批'
                    : '$actor waiting for high-risk approval',
              'failed' || 'denied' || 'timeout' =>
                copy.isChinese ? '$actor 失败' : '$actor failed',
              _ =>
                copy.isChinese
                    ? '$actor 已返回结果'
                    : '$actor returned a result',
            };
        final List<String> details = <String>[
          if (contextMode != null)
            copy.isChinese
                ? '上下文 ${_contextModeDisplay(contextMode)}'
                : '${_contextModeDisplay(contextMode)} context',
          if (turnCount != null)
            copy.isChinese
                ? '$turnCount 轮'
                : turnCount == 1
                ? '1 turn'
                : '$turnCount turns',
          if (toolCallCount != null)
            copy.isChinese
                ? '$toolCallCount 次工具调用'
                : toolCallCount == 1
                ? '1 tool call'
                : '$toolCallCount tool calls',
        ];
        final String? allowedToolsSummary = _labeledInlineSection(
          englishLabel: 'Allowed tools',
          chineseLabel: '允许工具',
          values: allowedTools,
        );
        if (details.isEmpty) {
          return _joinTraceSections(<String?>[
            statusSummary,
            allowedToolsSummary,
          ]);
        }
        final String summary = copy.isChinese
            ? '$statusSummary，${details.join('，')}'
            : '$statusSummary. ${details.join(', ')}.';
        return _joinTraceSections(<String?>[summary, allowedToolsSummary]);
      default:
        return null;
    }
  }

  String _todoWriteActionSummary({
    required _TodoTraceSummary? summary,
    required bool mutated,
  }) {
    if (!mutated) {
      return copy.isChinese ? '读取当前待办列表' : 'Read current todo list';
    }
    if (summary == null || summary.todoCount <= 0) {
      return copy.isChinese ? '清空待办列表' : 'Clear the todo list';
    }
    final String? breakdown = _todoBreakdownSummary(summary);
    if (copy.isChinese) {
      final String base = breakdown == null
          ? '更新 ${summary.todoCount} 条待办'
          : '更新 ${summary.todoCount} 条待办（$breakdown）';
      return summary.activeTodoContent == null
          ? base
          : '$base，当前进行中：${summary.activeTodoContent!}';
    }
    final String base = breakdown == null
        ? 'Update ${summary.todoCount} todo(s)'
        : 'Update ${summary.todoCount} todo(s) ($breakdown)';
    return summary.activeTodoContent == null
        ? base
        : '$base, active: ${summary.activeTodoContent!}';
  }

  String? _todoWriteResultSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final _TodoTraceSummary? summary = _todoSummaryFromResultMetadata(event);
    if (summary == null) {
      return null;
    }
    final bool mutated = _resultMetadataBool(event, 'mutated') == true;
    final bool? planChanged = _resultMetadataBool(event, 'planChanged');
    if (!mutated) {
      if (summary.todoCount <= 0) {
        return copy.isChinese
            ? '当前待办列表为空'
            : 'Current todo list is empty';
      }
      final String? breakdown = _todoBreakdownSummary(summary);
      if (copy.isChinese) {
        final String base = breakdown == null
            ? '当前待办列表共 ${summary.todoCount} 项'
            : '当前待办列表共 ${summary.todoCount} 项，$breakdown';
        return summary.activeTodoContent == null
            ? base
            : '$base，当前进行中：${summary.activeTodoContent!}';
      }
      final String base = breakdown == null
          ? 'Current todo list has ${summary.todoCount} item(s)'
          : 'Current todo list has ${summary.todoCount} item(s): $breakdown';
      return summary.activeTodoContent == null
          ? base
          : '$base. Active: ${summary.activeTodoContent!}';
    }
    if (summary.todoCount <= 0) {
      if (copy.isChinese) {
        return planChanged == false ? '待办列表未变化，当前为空' : '待办列表已清空';
      }
      return planChanged == false
          ? 'Plan unchanged. Todo list is empty.'
          : 'Cleared the todo list';
    }
    final int completedDeltaCount =
        _resultMetadataInt(event, 'completedTodoDeltaCount') ?? 0;
    final int addedTodoCount = _resultMetadataInt(event, 'addedTodoCount') ?? 0;
    final int removedTodoCount =
        _resultMetadataInt(event, 'removedTodoCount') ?? 0;
    final int statusChangedTodoCount =
        _resultMetadataInt(event, 'statusChangedTodoCount') ?? 0;
    final int extraStatusChangeCount = math.max(
      0,
      statusChangedTodoCount - completedDeltaCount,
    );
    final List<String> details = <String>[
      if (completedDeltaCount > 0)
        copy.isChinese
            ? '完成 $completedDeltaCount 项'
            : 'completed $completedDeltaCount',
      if (addedTodoCount > 0)
        copy.isChinese
            ? '新增 $addedTodoCount 项'
            : 'added $addedTodoCount',
      if (removedTodoCount > 0)
        copy.isChinese
            ? '移除 $removedTodoCount 项'
            : 'removed $removedTodoCount',
      if (extraStatusChangeCount > 0)
        copy.isChinese
            ? '更新 $extraStatusChangeCount 项状态'
            : 'updated $extraStatusChangeCount status${extraStatusChangeCount == 1 ? '' : 'es'}',
    ];
    if (details.isEmpty) {
      final String? breakdown = _todoBreakdownSummary(summary);
      if (breakdown != null) {
        details.add(breakdown);
      }
    }
    if (copy.isChinese) {
      final String base = planChanged == false ? '待办计划未变化' : '待办计划已更新';
      final String detailText = details.isEmpty
          ? base
          : '$base：${details.join('，')}';
      return summary.activeTodoContent == null
          ? detailText
          : '$detailText，当前进行中：${summary.activeTodoContent!}';
    }
    final String base = planChanged == false
        ? 'Plan unchanged'
        : 'Plan updated';
    final String detailText = details.isEmpty
        ? base
        : '$base: ${details.join(', ')}';
    return summary.activeTodoContent == null
        ? detailText
        : '$detailText. Active now: ${summary.activeTodoContent!}';
  }

  String? _todoBreakdownSummary(_TodoTraceSummary summary) {
    if (summary.todoCount <= 0) {
      return null;
    }
    if (copy.isChinese) {
      return '${summary.pendingCount} 待处理，${summary.inProgressCount} 进行中，${summary.completedCount} 已完成';
    }
    return '${summary.pendingCount} pending, ${summary.inProgressCount} in progress, ${summary.completedCount} completed';
  }

  _TodoTraceSummary? _todoSummaryFromArguments(
    Map<String, dynamic>? arguments,
  ) {
    if (arguments == null || arguments.containsKey('todos') != true) {
      return null;
    }
    final List<dynamic>? todos = _argumentList(arguments, 'todos');
    return _todoSummaryFromTodoList(todos);
  }

  _TodoTraceSummary? _todoSummaryFromResultMetadata(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    final int? todoCount = _resultMetadataInt(event, 'todoCount');
    if (todoCount == null) {
      return null;
    }
    return _TodoTraceSummary(
      todoCount: todoCount,
      pendingCount: _resultMetadataInt(event, 'pendingTodoCount') ?? 0,
      inProgressCount: _resultMetadataInt(event, 'inProgressTodoCount') ?? 0,
      completedCount: _resultMetadataInt(event, 'completedTodoCount') ?? 0,
      activeTodoContent: _resultMetadataValue(event, 'activeTodoContent'),
    );
  }

  _TodoTraceSummary _todoSummaryFromTodoList(List<dynamic>? todos) {
    int pendingCount = 0;
    int inProgressCount = 0;
    int completedCount = 0;
    String? activeTodoContent;
    final List<dynamic> normalizedTodos = todos ?? const <dynamic>[];
    for (final dynamic rawTodo in normalizedTodos) {
      if (rawTodo is! Map) {
        continue;
      }
      final Map<String, dynamic> todo = Map<String, dynamic>.from(
        rawTodo.map((key, value) => MapEntry(key.toString(), value)),
      );
      switch ((_argumentString(todo, 'status') ?? '').trim().toLowerCase()) {
        case 'completed':
        case 'complete':
        case 'done':
          completedCount += 1;
          break;
        case 'in_progress':
        case 'in-progress':
        case 'inprogress':
          inProgressCount += 1;
          activeTodoContent ??= _argumentString(todo, 'content');
          break;
        default:
          pendingCount += 1;
          break;
      }
    }
    return _TodoTraceSummary(
      todoCount: normalizedTodos.length,
      pendingCount: pendingCount,
      inProgressCount: inProgressCount,
      completedCount: completedCount,
      activeTodoContent: activeTodoContent,
    );
  }

  Map<String, dynamic>? _decodeJsonObject(String? rawJson) {
    final String? normalized = _nonEmpty(rawJson);
    if (normalized == null) {
      return null;
    }
    try {
      final dynamic decoded = jsonDecode(normalized);
      if (decoded is! Map) {
        return null;
      }
      return Map<String, dynamic>.from(
        decoded.map((key, value) => MapEntry(key.toString(), value)),
      );
    } catch (_) {
      return null;
    }
  }

  String? _subagentExecutionState(OpenCrayChatRuntimeEventSnapshot event) =>
      _nonEmpty(event.executionState) ?? _nonEmpty(event.status);

  String? _subagentExecutionStateSummary({
    required String actor,
    required String? executionState,
  }) {
    return switch (executionState?.toLowerCase()) {
      'background_queued' =>
        copy.isChinese ? '$actor 已在后台排队' : '$actor queued in background',
      'background_running' =>
        copy.isChinese
            ? '$actor 正在后台运行'
            : '$actor running in background',
      'waiting_approval' =>
        copy.isChinese ? '$actor 等待审批' : '$actor waiting for approval',
      'waiting_high_risk_approval' =>
        copy.isChinese
            ? '$actor 等待高风险审批'
            : '$actor waiting for high-risk approval',
      'running' => copy.isChinese ? '$actor 正在运行' : '$actor running',
      'completed' => copy.isChinese ? '$actor 已完成' : '$actor completed',
      'failed' => copy.isChinese ? '$actor 失败' : '$actor failed',
      'cancelled' => copy.isChinese ? '$actor 已取消' : '$actor cancelled',
      _ => null,
    };
  }

  String? _subagentPhaseStateOverrideSummary({
    required String actor,
    required String? executionState,
  }) {
    return switch (executionState?.toLowerCase()) {
      'background_queued' =>
        copy.isChinese ? '$actor 已在后台排队' : '$actor queued in background',
      'background_running' =>
        copy.isChinese
            ? '$actor 正在后台运行'
            : '$actor running in background',
      'waiting_approval' =>
        copy.isChinese ? '$actor 等待审批' : '$actor waiting for approval',
      'waiting_high_risk_approval' =>
        copy.isChinese
            ? '$actor 等待高风险审批'
            : '$actor waiting for high-risk approval',
      _ => null,
    };
  }

  String? _subagentContinuationSummary(OpenCrayChatRuntimeEventSnapshot event) {
    return switch (_nonEmpty(event.continuationKind)?.toLowerCase()) {
      'background_resume' =>
        copy.isChinese ? '后台继续' : 'Resumes in background',
      'prompt_resume' =>
        copy.isChinese ? '审批后继续' : 'Resumes after approval',
      'none' || null => null,
      final String rawValue => rawValue.replaceAll('_', ' '),
    };
  }

  String? _argumentString(
    Map<String, dynamic>? arguments,
    String key, {
    String? fallbackKey,
  }) {
    if (arguments == null) {
      return null;
    }
    final dynamic value =
        arguments[key] ?? (fallbackKey == null ? null : arguments[fallbackKey]);
    final String normalized = switch (value) {
      null => '',
      String stringValue => stringValue.trim(),
      _ => value.toString().trim(),
    };
    return normalized.isEmpty ? null : normalized;
  }

  int? _argumentInt(Map<String, dynamic>? arguments, String key) {
    if (arguments == null) {
      return null;
    }
    final dynamic value = arguments[key];
    return switch (value) {
      int intValue => intValue,
      num numValue => numValue.toInt(),
      String stringValue => int.tryParse(stringValue.trim()),
      _ => null,
    };
  }

  List<dynamic>? _argumentList(Map<String, dynamic>? arguments, String key) {
    if (arguments == null) {
      return null;
    }
    final dynamic value = arguments[key];
    return value is List<dynamic> ? value : null;
  }

  List<String> _argumentStringList(
    Map<String, dynamic>? arguments,
    String key,
  ) {
    final List<dynamic>? values = _argumentList(arguments, key);
    if (values == null) {
      final String? singleValue = _argumentString(arguments, key);
      return singleValue == null ? const <String>[] : _csvValues(singleValue);
    }
    return values
        .map((value) => value.toString().trim())
        .where((value) => value.isNotEmpty)
        .toList(growable: false);
  }

  List<String> _csvValues(String? value) {
    final String? normalized = _nonEmpty(value);
    if (normalized == null) {
      return const <String>[];
    }
    return normalized
        .split(',')
        .map((entry) => entry.trim())
        .where((entry) => entry.isNotEmpty)
        .toList(growable: false);
  }

  String _prettyJson(Map<String, dynamic> value) =>
      const JsonEncoder.withIndent('  ').convert(value);

  String _joinTraceSections(List<String?> sections) => sections
      .map((section) => section?.trim() ?? '')
      .where((section) => section.isNotEmpty)
      .join('\n\n');

  String _traceSectionLabel({
    required String english,
    required String chinese,
  }) => copy.isChinese ? chinese : english;

  String? _labeledInlineSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  }) {
    if (values.isEmpty) {
      return null;
    }
    final String label = _traceSectionLabel(
      english: englishLabel,
      chinese: chineseLabel,
    );
    return '$label: ${values.join(', ')}';
  }

  String? _labeledMultilineSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  }) {
    final List<String> normalized = values
        .map((value) => value.trim())
        .where((value) => value.isNotEmpty)
        .toList(growable: false);
    if (normalized.isEmpty) {
      return null;
    }
    final String label = _traceSectionLabel(
      english: englishLabel,
      chinese: chineseLabel,
    );
    return '$label:\n${normalized.join('\n')}';
  }

  String? _nonEmpty(String? value) {
    final String normalized = value?.trim() ?? '';
    return normalized.isEmpty ? null : normalized;
  }

  String _subagentTypeDisplay(String? value) {
    final String? normalized = _nonEmpty(value);
    if (normalized == null) {
      return copy.isChinese ? '子代理' : 'Subagent';
    }
    return _humanizeIdentifier(normalized, titleCase: true);
  }

  String _contextModeDisplay(String value) =>
      _humanizeIdentifier(value, titleCase: false);

  String _humanizeIdentifier(String value, {required bool titleCase}) {
    final List<String> parts = value
        .trim()
        .replaceAll(RegExp(r'[_-]+'), ' ')
        .split(RegExp(r'\s+'))
        .where((part) => part.isNotEmpty)
        .toList(growable: false);
    if (parts.isEmpty) {
      return value.trim();
    }
    if (!titleCase) {
      return parts.join(' ');
    }
    return parts
        .map((part) => part[0].toUpperCase() + part.substring(1).toLowerCase())
        .join(' ');
  }

  String? _resultMetadataValue(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) => _nonEmpty(event.resultMetadata[key]);

  List<String> _resultMetadataCsvStrings(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) => (event.resultMetadata[key] ?? '')
      .split(',')
      .map((value) => value.trim())
      .where((value) => value.isNotEmpty)
      .toList(growable: false);

  List<int> _resultMetadataCsvInts(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) => _resultMetadataCsvStrings(
    event,
    key,
  ).map(int.tryParse).whereType<int>().toList(growable: false);

  int? _resultMetadataInt(OpenCrayChatRuntimeEventSnapshot event, String key) =>
      int.tryParse(event.resultMetadata[key]?.trim() ?? '');

  bool? _resultMetadataBool(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) {
    final String value = event.resultMetadata[key]?.trim().toLowerCase() ?? '';
    if (value == 'true') {
      return true;
    }
    if (value == 'false') {
      return false;
    }
    return null;
  }

  bool _resultMetadataTruncated(OpenCrayChatRuntimeEventSnapshot event) {
    return _resultMetadataBool(event, 'resultTruncated') == true ||
        _resultMetadataBool(event, 'truncated') == true;
  }

  String _snapshotActiveSessionId(OpenCrayChatSnapshot snapshot) {
    for (final session in snapshot.drawer.sessions) {
      final String sessionId = session.sessionId.trim();
      if (session.isSelected && sessionId.isNotEmpty) {
        return sessionId;
      }
    }
    final String runtimeSessionId =
        snapshot.runtimeActivity?.sessionId.trim() ?? '';
    if (runtimeSessionId.isNotEmpty) {
      return runtimeSessionId;
    }
    for (final session in snapshot.drawer.sessions) {
      final String sessionId = session.sessionId.trim();
      if (sessionId.isNotEmpty) {
        return sessionId;
      }
    }
    return '';
  }

  String _runtimeProjectionExpectedSessionId(OpenCrayChatSnapshot snapshot) {
    for (final session in drawerSessions) {
      final String sessionId = session.sessionId.trim();
      if (session.isSelected && sessionId.isNotEmpty) {
        return sessionId;
      }
    }
    return _snapshotActiveSessionId(snapshot);
  }

  OpenCrayChatSnapshot _projectionSnapshotForLocalSession({
    required OpenCrayChatSnapshot source,
    required OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  }) {
    return OpenCrayChatSnapshot(
      screenTitle: source.screenTitle,
      modeLabel: source.modeLabel,
      sessionButtonLabel: source.sessionButtonLabel,
      composerPlaceholder: source.composerPlaceholder,
      summary: source.summary,
      messages: const <OpenCrayChatMessageSnapshot>[],
      drawer: source.drawer,
      isInputEnabled: source.isInputEnabled,
      todos: const <OpenCrayChatTodoSnapshot>[],
      todoState: 'empty',
      pendingApprovals: const <OpenCrayChatPendingApprovalSnapshot>[],
      runtimeActivity: runtimeSnapshot,
      updatedAtEpochMs: source.updatedAtEpochMs,
    );
  }

  OpenCrayChatRuntimeSnapshot? _resolveRuntimeSnapshot({
    required String expectedSessionId,
    OpenCrayChatRuntimeSnapshot? embedded,
    OpenCrayChatRuntimeSnapshot? streamed,
  }) {
    final String normalizedExpectedSessionId = expectedSessionId.trim();
    final OpenCrayChatRuntimeSnapshot? resolved =
        normalizedExpectedSessionId.isEmpty
        ? resolveChatRuntimeSnapshot(embedded, streamed)
        : resolveChatRuntimeSnapshotForSession(
            expectedSessionId: normalizedExpectedSessionId,
            embedded: embedded,
            streamed: streamed,
          );
    final String sessionId = normalizedExpectedSessionId.isNotEmpty
        ? normalizedExpectedSessionId
        : resolved?.sessionId.trim() ?? activeSessionId;
    final List<OpenCrayChatLiveAssistantDraftSnapshot> overrideDrafts =
        liveAssistantDraftOverridesBySession[sessionId]?.values.toList(
          growable: false,
        ) ??
        const <OpenCrayChatLiveAssistantDraftSnapshot>[];
    if (overrideDrafts.isEmpty) {
      return resolved;
    }
    final Map<String, OpenCrayChatLiveAssistantDraftSnapshot> mergedDrafts =
        <String, OpenCrayChatLiveAssistantDraftSnapshot>{
          for (final draft
              in resolved?.liveAssistantDrafts ??
                  const <OpenCrayChatLiveAssistantDraftSnapshot>[])
            if (draft.pendingMessageId.trim().isNotEmpty)
              _runtimeDraftIdentity(
                pendingMessageId: draft.pendingMessageId,
                executionId: draft.executionId,
              ): draft,
        };
    for (final draft in overrideDrafts) {
      final String pendingMessageId = draft.pendingMessageId.trim();
      final String resolvedStreamInstanceId =
          resolved?.streamInstanceId?.trim() ?? '';
      final String draftStreamInstanceId = draft.streamInstanceId?.trim() ?? '';
      if (resolvedStreamInstanceId.isNotEmpty &&
          draftStreamInstanceId.isNotEmpty &&
          resolvedStreamInstanceId != draftStreamInstanceId) {
        continue;
      }
      final String identity = _runtimeDraftIdentity(
        pendingMessageId: pendingMessageId,
        executionId: draft.executionId,
      );
      final OpenCrayChatLiveAssistantDraftSnapshot? existing =
          mergedDrafts[identity];
      if (existing == null || _draftIsAtLeastAsNew(draft, existing)) {
        mergedDrafts[identity] = draft;
      }
    }
    final List<OpenCrayChatLiveAssistantDraftSnapshot> sortedDrafts =
        mergedDrafts.values.toList(growable: false)..sort(
          (left, right) =>
              left.updatedAtEpochMs.compareTo(right.updatedAtEpochMs),
        );
    return OpenCrayChatRuntimeSnapshot(
      sessionId: sessionId,
      activeRuns: resolved?.activeRuns ?? const <OpenCrayChatRunSnapshot>[],
      retainedRuns: resolved?.retainedRuns ?? const <OpenCrayChatRunSnapshot>[],
      subAgents: resolved?.subAgents ?? const <OpenCrayChatSubAgentSnapshot>[],
      events: resolved?.events ?? const <OpenCrayChatRuntimeEventSnapshot>[],
      liveAssistantDrafts: sortedDrafts,
      streamInstanceId: resolved?.streamInstanceId,
      lastSequence: resolved?.lastSequence,
      flutterAppInstanceId: resolved?.flutterAppInstanceId,
      bridgeInstanceId: resolved?.bridgeInstanceId,
      bridgeEpoch: resolved?.bridgeEpoch,
      hostLifecycle: resolved?.hostLifecycle,
      updatedAtEpochMs: resolved?.updatedAtEpochMs ?? 0,
    );
  }
}
