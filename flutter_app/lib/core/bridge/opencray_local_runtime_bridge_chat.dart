part of 'opencray_local_runtime_bridge.dart';

mixin _LocalRuntimeBridgeChatDomain on _LocalRuntimeBridgeDeps {
  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async =>
      OpenCrayChatSnapshot.fromMap(
        attachChatSnapshotClientLifecycle(
          await _getMap('v1/chat_snapshot'),
          fallbackBridgeInstanceId: _bridgeInstanceId,
        ),
      );

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() => _watchMap(
    () => _getMap('v1/chat_snapshot'),
    (payload) => OpenCrayChatSnapshot.fromMap(
      attachChatSnapshotClientLifecycle(
        payload,
        fallbackBridgeInstanceId: _bridgeInstanceId,
      ),
    ),
  );

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      OpenCrayChatRuntimeSnapshot.fromMap(
        attachChatRuntimeSnapshotClientLifecycle(
          await _getMap('v1/chat_runtime_snapshot'),
          fallbackBridgeInstanceId: _bridgeInstanceId,
        ),
      );

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() => _watchMap(
    () => _getMap('v1/chat_runtime_snapshot'),
    (payload) => OpenCrayChatRuntimeSnapshot.fromMap(
      attachChatRuntimeSnapshotClientLifecycle(
        payload,
        fallbackBridgeInstanceId: _bridgeInstanceId,
      ),
    ),
  );

  @override
  Stream<OpenCrayChatLiveAssistantDraftEvent> watchLiveAssistantDraftEvents() =>
      _runtimeRealtimeController.stream
          .where((envelope) => envelope.kind == 'draft')
          .map((envelope) => envelope.payload)
          .map(OpenCrayChatLiveAssistantDraftEvent.fromMap);

  @override
  Stream<OpenCrayChatRuntimeEventDelta> watchRuntimeEventDeltas() =>
      _runtimeRealtimeController.stream
          .where((envelope) => envelope.kind == 'delta')
          .map((envelope) => envelope.payload)
          .map(OpenCrayChatRuntimeEventDelta.fromMap);

  @override
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async {
    final payload = await _getJson(
      'v1/chat_run_snapshot',
      queryParameters: <String, String>{'runId': runId},
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayChatRunSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      OpenCrayMemoryDebugSnapshot.fromMap(
        await _getMap('v1/memory_debug_snapshot'),
      );

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async =>
      OpenCrayMemoryDebugLinksSnapshot.fromMap(
        await _getMap('v1/memory_debug_links_snapshot'),
      );

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      OpenCraySoulDebugSnapshot.fromMap(
        await _getMap('v1/soul_debug_snapshot'),
      );

  @override
  Future<OpenCrayDebugPythonRunResult> runDebugPythonScript({
    required String fileName,
    required String scriptText,
  }) async => throw UnsupportedError(
    'Running embedded Python debug scripts requires the Android host bridge.',
  );

  @override
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async => OpenCrayMemoryDebugSearchSnapshot.fromMap(
    await _postMap('v1/memory_debug_search', <String, Object?>{
      'query': query,
      'maxResults': maxResults,
      'minScore': minScore,
    }),
  );

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async => OpenCrayMemoryDebugSliceSnapshot.fromMap(
    await _postMap('v1/memory_debug_slice', <String, Object?>{
      'path': path,
      'fromLine': fromLine,
      'lines': lines,
    }),
  );

  @override
  Future<void> applyMemoryDebugAction({
    required String recordId,
    required String actionId,
  }) => _postVoid('v1/memory_debug_action', <String, Object?>{
    'recordId': recordId,
    'actionId': actionId,
  });

  @override
  Future<OpenCrayChatRunSnapshot?> waitForChatRun(
    String runId, {
    Duration timeout = const Duration(seconds: 15),
  }) async {
    final payload = await _requestJson(
      'POST',
      'v1/wait_chat_run',
      body: <String, Object?>{
        'runId': runId,
        'timeoutMs': timeout.inMilliseconds,
      },
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayChatRunSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<void> refreshSandboxSessionInfo() =>
      _postVoid('v1/refresh_sandbox_session_info');

  @override
  Future<void> createChatSession() => _postVoid('v1/create_chat_session');

  @override
  Future<void> copyChatSession(String sessionId) => _postVoid(
    'v1/copy_chat_session',
    <String, Object?>{'sessionId': sessionId},
  );

  @override
  Future<void> deleteChatSession(String sessionId) => _postVoid(
    'v1/delete_chat_session',
    <String, Object?>{'sessionId': sessionId},
  );

  @override
  Future<void> selectChatSession(String sessionId) => _postVoid(
    'v1/select_chat_session',
    <String, Object?>{'sessionId': sessionId},
  );

  @override
  Future<void> branchChatSessionFromMessage({
    required String sessionId,
    required String messageId,
  }) => _postVoid('v1/branch_chat_session_from_message', <String, Object?>{
    'sessionId': sessionId,
    'messageId': messageId,
  });

  @override
  Future<void> deleteChatMessage({
    required String sessionId,
    required String messageId,
  }) => _postVoid('v1/delete_chat_message', <String, Object?>{
    'sessionId': sessionId,
    'messageId': messageId,
  });

  @override
  Future<void> recallChatMessage({
    required String sessionId,
    required String messageId,
  }) => _postVoid('v1/recall_chat_message', <String, Object?>{
    'sessionId': sessionId,
    'messageId': messageId,
  });

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async => throw UnsupportedError(
    'Adding attachments is unavailable in local runtime mode.',
  );

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments =
        const <OpenCrayChatDraftAttachment>[],
  }) async {
    final payload = await _requestJson(
      'POST',
      'v1/submit_chat_message',
      body: <String, Object?>{
        'text': text,
        'attachments': attachments
            .map((OpenCrayChatDraftAttachment attachment) => attachment.toMap())
            .toList(growable: false),
      },
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayChatRunSubmission.fromMap(_requireMap(payload));
  }

  @override
  Future<void> approveChatApproval(String approvalId) => _postVoid(
    'v1/approve_chat_approval',
    <String, Object?>{'runId': approvalId, 'taskId': approvalId},
  );

  @override
  Future<void> approveChatApprovalForSession(String approvalId) => _postVoid(
    'v1/approve_chat_approval_for_session',
    <String, Object?>{'runId': approvalId, 'taskId': approvalId},
  );

  @override
  Future<void> rejectChatApproval(String approvalId) => _postVoid(
    'v1/reject_chat_approval',
    <String, Object?>{'runId': approvalId, 'taskId': approvalId},
  );

  @override
  Future<void> interruptChatRun(String runIdOrTaskId) => _postVoid(
    'v1/interrupt_chat_run',
    <String, Object?>{'runId': runIdOrTaskId, 'taskId': runIdOrTaskId},
  );

  @override
  Future<void> retryChatRun(String runIdOrTaskId) => _postVoid(
    'v1/retry_chat_run',
    <String, Object?>{'runId': runIdOrTaskId, 'taskId': runIdOrTaskId},
  );
}
