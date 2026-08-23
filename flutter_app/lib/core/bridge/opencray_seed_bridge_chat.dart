part of 'opencray_seed_bridge.dart';

mixin _SeedBridgeChatDomain on _SeedBridgeDeps {
  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async => _chatSnapshot;

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() async* {
    yield _chatSnapshot;
    yield* _chatController.stream;
  }

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      const OpenCrayChatRuntimeSnapshot(
        sessionId: '',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() async* {
    yield await loadChatRuntimeSnapshot();
  }

  @override
  Stream<OpenCrayChatLiveAssistantDraftEvent> watchLiveAssistantDraftEvents() =>
      const Stream<OpenCrayChatLiveAssistantDraftEvent>.empty();

  @override
  Stream<OpenCrayChatRuntimeEventDelta> watchRuntimeEventDeltas() =>
      const Stream<OpenCrayChatRuntimeEventDelta>.empty();

  @override
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async =>
      null;

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      const OpenCrayMemoryDebugSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        records: <OpenCrayMemoryDebugRecordSnapshot>[],
      );

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async =>
      const OpenCrayMemoryDebugLinksSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        records: <OpenCrayMemoryDebugLinksEntrySnapshot>[],
      );

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      const OpenCraySoulDebugSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        overlayRecords: <OpenCrayMemoryDebugRecordSnapshot>[],
        fieldSources: <OpenCraySoulFieldSourceSnapshot>[],
      );

  @override
  Future<OpenCrayDebugPythonRunResult> runDebugPythonScript({
    required String fileName,
    required String scriptText,
  }) async => throw UnsupportedError(
    'Seed bridge does not support embedded Python execution.',
  );

  @override
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async => OpenCrayMemoryDebugSearchSnapshot(
    sessionId: '',
    observedAtEpochMs: 0,
    query: query,
  );

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async => OpenCrayMemoryDebugSliceSnapshot(
    sessionId: '',
    observedAtEpochMs: 0,
    path: path,
    startLine: fromLine ?? 1,
    endLine: (fromLine ?? 1) + lines - 1,
  );

  @override
  Future<void> applyMemoryDebugAction({
    required String recordId,
    required String actionId,
  }) async {}

  @override
  Future<OpenCrayChatRunSnapshot?> waitForChatRun(
    String runId, {
    Duration timeout = const Duration(seconds: 15),
  }) async => null;

  @override
  Future<void> refreshSandboxSessionInfo() async {}

  @override
  Future<void> createChatSession() async {
    _chatSnapshot = const OpenCrayChatSnapshot(
      screenTitle: 'Chat',
      modeLabel: 'SEED',
      sessionButtonLabel: 'Sessions',
      composerPlaceholder: 'Message OpenCray',
      summary: OpenCrayChatSummarySnapshot(
        title: 'New seed session',
        badge: 'Empty',
        body: 'This seed bridge session is empty.',
      ),
      messages: <OpenCrayChatMessageSnapshot>[],
      drawer: OpenCrayChatDrawerSnapshot(
        eyebrow: 'Recent sessions',
        title: 'Recent sessions',
        ctaLabel: 'New session',
        sessions: <OpenCrayChatSessionItemSnapshot>[
          OpenCrayChatSessionItemSnapshot(
            sessionId: 'seed-session-new',
            title: 'New seed session',
            preview: '',
            meta: '0 messages',
            isSelected: true,
          ),
        ],
      ),
      isInputEnabled: true,
    );
    _emitChatSnapshot();
  }

  @override
  Future<void> copyChatSession(String sessionId) async {
    OpenCrayChatSessionItemSnapshot? sourceSession;
    for (final session in _chatSnapshot.drawer.sessions) {
      if (session.sessionId == sessionId) {
        sourceSession = session;
        break;
      }
    }
    if (sourceSession == null) {
      return;
    }
    final copiedSession = OpenCrayChatSessionItemSnapshot(
      sessionId: '$sessionId-copy-${_chatSnapshot.drawer.sessions.length + 1}',
      title: _seedCopySessionTitle(sourceSession.title),
      preview: sourceSession.preview,
      meta: sourceSession.meta,
      isSelected: true,
    );
    final updatedSessions = <OpenCrayChatSessionItemSnapshot>[
      copiedSession,
      ..._chatSnapshot.drawer.sessions.map(
        (session) => OpenCrayChatSessionItemSnapshot(
          sessionId: session.sessionId,
          title: session.title,
          preview: session.preview,
          meta: session.meta,
          isSelected: false,
          unreadCount: session.unreadCount,
        ),
      ),
    ];
    _chatSnapshot = _copyChatSnapshotWith(
      drawer: OpenCrayChatDrawerSnapshot(
        eyebrow: _chatSnapshot.drawer.eyebrow,
        title: _chatSnapshot.drawer.title,
        ctaLabel: _chatSnapshot.drawer.ctaLabel,
        sessions: updatedSessions,
      ),
      summary: OpenCrayChatSummarySnapshot(
        title: copiedSession.title,
        badge: _chatSnapshot.summary.badge,
        body: sourceSession.preview.isNotEmpty
            ? sourceSession.preview
            : _chatSnapshot.summary.body,
      ),
    );
    _emitChatSnapshot();
  }

  @override
  Future<void> deleteChatSession(String sessionId) async {
    final remainingSessions = _chatSnapshot.drawer.sessions
        .where((session) => session.sessionId != sessionId)
        .toList(growable: false);
    if (remainingSessions.length == _chatSnapshot.drawer.sessions.length) {
      return;
    }
    if (remainingSessions.isEmpty) {
      await createChatSession();
      return;
    }
    final selectedSession = remainingSessions.firstWhere(
      (session) => session.isSelected,
      orElse: () => remainingSessions.first,
    );
    final updatedSessions = remainingSessions
        .map(
          (session) => OpenCrayChatSessionItemSnapshot(
            sessionId: session.sessionId,
            title: session.title,
            preview: session.preview,
            meta: session.meta,
            isSelected: session.sessionId == selectedSession.sessionId,
            unreadCount: session.sessionId == selectedSession.sessionId
                ? 0
                : session.unreadCount,
          ),
        )
        .toList(growable: false);
    _chatSnapshot = _copyChatSnapshotWith(
      drawer: OpenCrayChatDrawerSnapshot(
        eyebrow: _chatSnapshot.drawer.eyebrow,
        title: _chatSnapshot.drawer.title,
        ctaLabel: _chatSnapshot.drawer.ctaLabel,
        sessions: updatedSessions,
      ),
      summary: OpenCrayChatSummarySnapshot(
        title: selectedSession.title,
        badge: _chatSnapshot.summary.badge,
        body: selectedSession.preview.isNotEmpty
            ? selectedSession.preview
            : _chatSnapshot.summary.body,
      ),
    );
    _emitChatSnapshot();
  }

  @override
  Future<void> selectChatSession(String sessionId) async {}

  @override
  Future<void> branchChatSessionFromMessage({
    required String sessionId,
    required String messageId,
  }) async {
    if (sessionId != _activeChatSessionId || messageId.trim().isEmpty) {
      return;
    }
    OpenCrayChatSessionItemSnapshot? sourceSession;
    for (final session in _chatSnapshot.drawer.sessions) {
      if (session.sessionId == sessionId) {
        sourceSession = session;
        break;
      }
    }
    if (sourceSession == null) {
      return;
    }
    final int branchUntilIndex = _chatSnapshot.messages.indexWhere(
      (message) => message.messageId == messageId,
    );
    final List<OpenCrayChatMessageSnapshot> branchMessages =
        (branchUntilIndex >= 0
                ? _chatSnapshot.messages.take(branchUntilIndex + 1)
                : _chatSnapshot.messages)
            .toList(growable: false);
    final String preview = _seedChatSessionPreviewFromMessages(
      branchMessages,
      fallback: sourceSession.preview,
    );
    final branchSession = OpenCrayChatSessionItemSnapshot(
      sessionId:
          '$sessionId-branch-${_chatSnapshot.drawer.sessions.length + 1}',
      title: _seedBranchSessionTitle(sourceSession.title),
      preview: preview,
      meta: sourceSession.meta,
      isSelected: true,
    );
    final updatedSessions = <OpenCrayChatSessionItemSnapshot>[
      branchSession,
      ..._chatSnapshot.drawer.sessions.map(
        (session) => OpenCrayChatSessionItemSnapshot(
          sessionId: session.sessionId,
          title: session.title,
          preview: session.preview,
          meta: session.meta,
          isSelected: false,
          unreadCount: session.unreadCount,
        ),
      ),
    ];
    _chatSnapshot = OpenCrayChatSnapshot(
      screenTitle: _chatSnapshot.screenTitle,
      modeLabel: _chatSnapshot.modeLabel,
      sessionButtonLabel: _chatSnapshot.sessionButtonLabel,
      composerPlaceholder: _chatSnapshot.composerPlaceholder,
      summary: OpenCrayChatSummarySnapshot(
        title: branchSession.title,
        badge: _chatSnapshot.summary.badge,
        body: preview.isNotEmpty ? preview : _chatSnapshot.summary.body,
      ),
      messages: branchMessages,
      drawer: OpenCrayChatDrawerSnapshot(
        eyebrow: _chatSnapshot.drawer.eyebrow,
        title: _chatSnapshot.drawer.title,
        ctaLabel: _chatSnapshot.drawer.ctaLabel,
        sessions: updatedSessions,
      ),
      isInputEnabled: _chatSnapshot.isInputEnabled,
      todos: _chatSnapshot.todos,
      todoState: _chatSnapshot.todoState,
      todoHideDelayMs: _chatSnapshot.todoHideDelayMs,
      todoCompletedAtEpochMs: _chatSnapshot.todoCompletedAtEpochMs,
      pendingApprovals: _chatSnapshot.pendingApprovals,
      runtimeActivity: _chatSnapshot.runtimeActivity,
      updatedAtEpochMs: _nextChatSnapshotUpdatedAt(),
    );
    _emitChatSnapshot();
  }

  @override
  Future<void> deleteChatMessage({
    required String sessionId,
    required String messageId,
  }) async {
    if (sessionId != _activeChatSessionId || messageId.trim().isEmpty) {
      return;
    }
    final updatedMessages = _chatSnapshot.messages
        .where((message) => message.messageId != messageId)
        .toList(growable: false);
    if (updatedMessages.length == _chatSnapshot.messages.length) {
      return;
    }
    _chatSnapshot = OpenCrayChatSnapshot(
      screenTitle: _chatSnapshot.screenTitle,
      modeLabel: _chatSnapshot.modeLabel,
      sessionButtonLabel: _chatSnapshot.sessionButtonLabel,
      composerPlaceholder: _chatSnapshot.composerPlaceholder,
      summary: _chatSnapshot.summary,
      messages: updatedMessages,
      drawer: _chatSnapshot.drawer,
      isInputEnabled: _chatSnapshot.isInputEnabled,
      todos: _chatSnapshot.todos,
      todoState: _chatSnapshot.todoState,
      todoHideDelayMs: _chatSnapshot.todoHideDelayMs,
      todoCompletedAtEpochMs: _chatSnapshot.todoCompletedAtEpochMs,
      pendingApprovals: _chatSnapshot.pendingApprovals,
      runtimeActivity: _chatSnapshot.runtimeActivity,
      updatedAtEpochMs: _nextChatSnapshotUpdatedAt(),
    );
    _emitChatSnapshot();
  }

  @override
  Future<void> recallChatMessage({
    required String sessionId,
    required String messageId,
  }) async {
    if (sessionId != _activeChatSessionId || messageId.trim().isEmpty) {
      return;
    }
    final recallIndex = _chatSnapshot.messages.indexWhere(
      (message) => message.messageId == messageId,
    );
    if (recallIndex < 0) {
      return;
    }
    final recalledMessage = _chatSnapshot.messages[recallIndex];
    if (recalledMessage.kind != 'outbound') {
      return;
    }
    _chatSnapshot = OpenCrayChatSnapshot(
      screenTitle: _chatSnapshot.screenTitle,
      modeLabel: _chatSnapshot.modeLabel,
      sessionButtonLabel: _chatSnapshot.sessionButtonLabel,
      composerPlaceholder: _chatSnapshot.composerPlaceholder,
      summary: _chatSnapshot.summary,
      messages: _chatSnapshot.messages
          .take(recallIndex)
          .toList(growable: false),
      drawer: _chatSnapshot.drawer,
      isInputEnabled: _chatSnapshot.isInputEnabled,
      todos: _chatSnapshot.todos,
      todoState: _chatSnapshot.todoState,
      todoHideDelayMs: _chatSnapshot.todoHideDelayMs,
      todoCompletedAtEpochMs: _chatSnapshot.todoCompletedAtEpochMs,
      pendingApprovals: _chatSnapshot.pendingApprovals,
      runtimeActivity: _chatSnapshot.runtimeActivity,
      updatedAtEpochMs: _nextChatSnapshotUpdatedAt(),
    );
    _emitChatSnapshot();
  }

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async {
    final String fileName = switch (kind) {
      OpenCrayChatDraftAttachmentKind.image => 'workspace-shot.png',
      OpenCrayChatDraftAttachmentKind.voice => 'voice-note.m4a',
      OpenCrayChatDraftAttachmentKind.file => 'mobile-ui-layout-spec.md',
    };
    final String mimeType = switch (kind) {
      OpenCrayChatDraftAttachmentKind.image => 'image/png',
      OpenCrayChatDraftAttachmentKind.voice => 'audio/mp4',
      OpenCrayChatDraftAttachmentKind.file => 'text/markdown',
    };
    return <OpenCrayChatDraftAttachment>[
      OpenCrayChatDraftAttachment(
        kind: kind,
        displayName: fileName,
        relativePath: '.opencray/seed/$fileName',
        mimeType: mimeType,
      ),
    ];
  }

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments =
        const <OpenCrayChatDraftAttachment>[],
  }) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty && attachments.isEmpty) {
      return null;
    }
    _chatSnapshot = OpenCrayChatSnapshot(
      screenTitle: _chatSnapshot.screenTitle,
      modeLabel: _chatSnapshot.modeLabel,
      sessionButtonLabel: _chatSnapshot.sessionButtonLabel,
      composerPlaceholder: _chatSnapshot.composerPlaceholder,
      summary: _chatSnapshot.summary,
      messages: <OpenCrayChatMessageSnapshot>[
        ..._chatSnapshot.messages,
        OpenCrayChatMessageSnapshot(
          messageId: _seedMessageId('seed-outbound'),
          kind: 'outbound',
          text: trimmed,
          attachments: attachments
              .map(
                (OpenCrayChatDraftAttachment attachment) =>
                    OpenCrayChatAttachmentSnapshot(
                      attachmentId: attachment.id,
                      kind: attachment.kind.wireValue,
                      displayName: attachment.displayName,
                      localPath: attachment.relativePath,
                      mimeType: attachment.mimeType,
                      sizeBytes: attachment.sizeBytes,
                    ),
              )
              .toList(growable: false),
        ),
        OpenCrayChatMessageSnapshot(
          messageId: _seedMessageId('seed-inbound'),
          kind: 'inbound',
          text: 'Seed bridge stored your message locally.',
        ),
      ],
      drawer: _chatSnapshot.drawer,
      isInputEnabled: true,
      todos: _chatSnapshot.todos,
      todoState: _chatSnapshot.todoState,
      todoHideDelayMs: _chatSnapshot.todoHideDelayMs,
      todoCompletedAtEpochMs: _chatSnapshot.todoCompletedAtEpochMs,
      pendingApprovals: _chatSnapshot.pendingApprovals,
      runtimeActivity: _chatSnapshot.runtimeActivity,
      updatedAtEpochMs: _nextChatSnapshotUpdatedAt(),
    );
    _emitChatSnapshot();
    return const OpenCrayChatRunSubmission(
      sessionId: 'seed-session',
      runId: 'seed-run',
      taskId: 'seed-task',
      acceptedAtEpochMs: 0,
    );
  }

  @override
  Future<void> approveChatApproval(String approvalId) async {
    _resolveChatApproval(approvalId);
  }

  @override
  Future<void> approveChatApprovalForSession(String approvalId) async {
    _resolveChatApproval(approvalId);
  }

  @override
  Future<void> rejectChatApproval(String approvalId) async {
    _resolveChatApproval(approvalId);
  }

  @override
  Future<void> interruptChatRun(String runIdOrTaskId) async {}

  @override
  Future<void> retryChatRun(String runIdOrTaskId) async {}
}
