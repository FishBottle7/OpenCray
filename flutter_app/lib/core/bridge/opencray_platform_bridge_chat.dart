part of 'opencray_platform_bridge.dart';

mixin _PlatformBridgeChatDomain on _PlatformBridgeDeps {
  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async =>
      OpenCrayChatSnapshot.fromMap(
        attachChatSnapshotClientLifecycle(
          await _invokeMap('loadChatSnapshot'),
          fallbackBridgeInstanceId: _fallbackBridgeInstanceId,
        ),
      );

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() => _chatSnapshotChannel
      .receiveBroadcastStream()
      .map(_requireMap)
      .map(
        (payload) => attachChatSnapshotClientLifecycle(
          payload,
          fallbackBridgeInstanceId: _fallbackBridgeInstanceId,
        ),
      )
      .map(OpenCrayChatSnapshot.fromMap);

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      OpenCrayChatRuntimeSnapshot.fromMap(
        attachChatRuntimeSnapshotClientLifecycle(
          await _invokeMap('loadChatRuntimeSnapshot'),
          fallbackBridgeInstanceId: _fallbackBridgeInstanceId,
        ),
      );

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() =>
      _chatRuntimeSnapshotChannel
          .receiveBroadcastStream()
          .map(_requireMap)
          .map(
            (payload) => attachChatRuntimeSnapshotClientLifecycle(
              payload,
              fallbackBridgeInstanceId: _fallbackBridgeInstanceId,
            ),
          )
          .map(OpenCrayChatRuntimeSnapshot.fromMap);

  @override
  Stream<OpenCrayChatLiveAssistantDraftEvent> watchLiveAssistantDraftEvents() =>
      _liveAssistantDraftChannel
          .receiveBroadcastStream()
          .map(_requireMap)
          .map(OpenCrayChatLiveAssistantDraftEvent.fromMap);

  @override
  Stream<OpenCrayChatRuntimeEventDelta> watchRuntimeEventDeltas() =>
      _runtimeEventDeltaChannel
          .receiveBroadcastStream()
          .map(_requireMap)
          .map(OpenCrayChatRuntimeEventDelta.fromMap);

  @override
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      'loadChatRunSnapshot',
      <String, Object?>{'runId': runId},
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayChatRunSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      OpenCrayMemoryDebugSnapshot.fromMap(
        await _invokeMap('loadMemoryDebugSnapshot'),
      );

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async =>
      OpenCrayMemoryDebugLinksSnapshot.fromMap(
        await _invokeMap('loadMemoryDebugLinksSnapshot'),
      );

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      OpenCraySoulDebugSnapshot.fromMap(
        await _invokeMap('loadSoulDebugSnapshot'),
      );

  @override
  Future<OpenCrayDebugPythonRunResult> runDebugPythonScript({
    required String fileName,
    required String scriptText,
  }) async => OpenCrayDebugPythonRunResult.fromMap(
    await _invokeMap(
      'runDebugPythonScript',
      arguments: <String, Object?>{
        'fileName': fileName,
        'scriptText': scriptText,
      },
    ),
  );

  @override
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async => OpenCrayMemoryDebugSearchSnapshot.fromMap(
    await _invokeMap(
      'searchMemoryDebug',
      arguments: <String, Object?>{
        'query': query,
        'maxResults': maxResults,
        'minScore': minScore,
      },
    ),
  );

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async => OpenCrayMemoryDebugSliceSnapshot.fromMap(
    await _invokeMap(
      'getMemoryDebugSlice',
      arguments: <String, Object?>{
        'path': path,
        'fromLine': fromLine,
        'lines': lines,
      },
    ),
  );

  @override
  Future<void> applyMemoryDebugAction({
    required String recordId,
    required String actionId,
  }) => _methodChannel.invokeMethod<void>(
    'applyMemoryDebugAction',
    <String, Object?>{'recordId': recordId, 'actionId': actionId},
  );

  @override
  Future<OpenCrayChatRunSnapshot?> waitForChatRun(
    String runId, {
    Duration timeout = const Duration(seconds: 15),
  }) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      'waitForChatRun',
      <String, Object?>{'runId': runId, 'timeoutMs': timeout.inMilliseconds},
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayChatRunSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<void> refreshSandboxSessionInfo() =>
      _methodChannel.invokeMethod<void>('refreshSandboxSessionInfo');

  @override
  Future<void> createChatSession() =>
      _methodChannel.invokeMethod<void>('createChatSession');

  @override
  Future<void> copyChatSession(String sessionId) =>
      _methodChannel.invokeMethod<void>('copyChatSession', <String, Object?>{
        'sessionId': sessionId,
      });

  @override
  Future<void> deleteChatSession(String sessionId) =>
      _methodChannel.invokeMethod<void>('deleteChatSession', <String, Object?>{
        'sessionId': sessionId,
      });

  @override
  Future<void> selectChatSession(String sessionId) =>
      _methodChannel.invokeMethod<void>('selectChatSession', <String, Object?>{
        'sessionId': sessionId,
      });

  @override
  Future<void> branchChatSessionFromMessage({
    required String sessionId,
    required String messageId,
  }) => _methodChannel.invokeMethod<void>(
    'branchChatSessionFromMessage',
    <String, Object?>{'sessionId': sessionId, 'messageId': messageId},
  );

  @override
  Future<void> deleteChatMessage({
    required String sessionId,
    required String messageId,
  }) => _methodChannel.invokeMethod<void>(
    'deleteChatMessage',
    <String, Object?>{'sessionId': sessionId, 'messageId': messageId},
  );

  @override
  Future<void> recallChatMessage({
    required String sessionId,
    required String messageId,
  }) => _methodChannel.invokeMethod<void>(
    'recallChatMessage',
    <String, Object?>{'sessionId': sessionId, 'messageId': messageId},
  );

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async {
    final payload = await _methodChannel.invokeListMethod<Object?>(
      'pickChatAttachments',
      <String, Object?>{'kind': kind.wireValue},
    );
    return (payload ?? const <Object?>[])
        .map(_requireMap)
        .map(OpenCrayChatDraftAttachment.fromMap)
        .toList(growable: false);
  }

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments =
        const <OpenCrayChatDraftAttachment>[],
  }) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      'submitChatMessage',
      <String, Object?>{
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
  Future<void> approveChatApproval(String approvalId) =>
      _methodChannel.invokeMethod<void>(
        'approveChatApproval',
        <String, Object?>{'runId': approvalId, 'taskId': approvalId},
      );

  @override
  Future<void> approveChatApprovalForSession(String approvalId) =>
      _methodChannel.invokeMethod<void>(
        'approveChatApprovalForSession',
        <String, Object?>{'runId': approvalId, 'taskId': approvalId},
      );

  @override
  Future<void> rejectChatApproval(String approvalId) =>
      _methodChannel.invokeMethod<void>('rejectChatApproval', <String, Object?>{
        'runId': approvalId,
        'taskId': approvalId,
      });

  @override
  Future<void> interruptChatRun(String runIdOrTaskId) =>
      _methodChannel.invokeMethod<void>('interruptChatRun', <String, Object?>{
        'runId': runIdOrTaskId,
        'taskId': runIdOrTaskId,
      });

  @override
  Future<void> retryChatRun(String runIdOrTaskId) =>
      _methodChannel.invokeMethod<void>('retryChatRun', <String, Object?>{
        'runId': runIdOrTaskId,
        'taskId': runIdOrTaskId,
      });
}
