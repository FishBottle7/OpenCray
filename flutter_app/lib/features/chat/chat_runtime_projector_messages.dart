// ignore_for_file: annotate_overrides

part of 'chat_feature_screen.dart';

mixin _ProjectorMessagesDomain on _ChatRuntimeProjectorDeps {
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
