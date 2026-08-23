part of 'opencray_platform_bridge.dart';

mixin _PlatformBridgeAgentsDomain on _PlatformBridgeDeps {
  @override
  Future<List<OpenCrayAgentSnapshot>> listAgents() async =>
      (await _invokeList('listAgents'))
          .map(_requireMap)
          .map(OpenCrayAgentSnapshot.fromMap)
          .toList(growable: false);

  @override
  Future<OpenCrayAgentSnapshot?> loadActiveAgent() async {
    final payload = await _invokeNullableMap('loadActiveAgent');
    if (payload == null) {
      return null;
    }
    return OpenCrayAgentSnapshot.fromMap(payload);
  }

  @override
  Future<OpenCrayAgentSnapshot> createAgent(
    OpenCrayAgentCreateRequest request,
  ) async => OpenCrayAgentSnapshot.fromMap(
    await _invokeMap('createAgent', arguments: request.toMap()),
  );

  @override
  Future<OpenCrayAgentSnapshot?> selectAgent(String agentId) async {
    final payload = await _invokeNullableMap(
      'selectAgent',
      arguments: <String, Object?>{'agentId': agentId},
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayAgentSnapshot.fromMap(payload);
  }

  @override
  Future<OpenCraySoulVisualIdentity?> loadSoulVisualIdentity() async {
    final payload = await _invokeNullableMap('loadSoulVisualIdentity');
    if (payload == null) {
      return null;
    }
    return OpenCraySoulVisualIdentity.fromMap(payload);
  }

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulPrimaryPortrait(
    OpenCrayImageReferenceSource source,
  ) async {
    final payload = await _invokeNullableMap(
      'saveSoulPrimaryPortrait',
      arguments: <String, Object?>{'source': source.toMap()},
    );
    if (payload == null) {
      return null;
    }
    return OpenCraySoulVisualIdentity.fromMap(payload);
  }

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulReferenceImage({
    required String refId,
    required OpenCrayImageReferenceSource source,
  }) async {
    final payload = await _invokeNullableMap(
      'saveSoulReferenceImage',
      arguments: <String, Object?>{'refId': refId, 'source': source.toMap()},
    );
    if (payload == null) {
      return null;
    }
    return OpenCraySoulVisualIdentity.fromMap(payload);
  }

  @override
  Future<List<OpenCrayImageReference>> listMemoryImageReferences(
    String memoryId,
  ) async =>
      (await _invokeList(
            'listMemoryImageReferences',
            arguments: <String, Object?>{'memoryId': memoryId},
          ))
          .map(_requireMap)
          .map(OpenCrayImageReference.fromMap)
          .toList(growable: false);

  @override
  Future<OpenCrayMemoryImageReferenceResult?> attachMemoryImageReference({
    required String memoryId,
    required OpenCrayImageReferenceSource source,
    String? preferredMode,
  }) async {
    final payload = await _invokeNullableMap(
      'attachMemoryImageReference',
      arguments: <String, Object?>{
        'memoryId': memoryId,
        'source': source.toMap(),
        'preferredMode': preferredMode,
      },
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayMemoryImageReferenceResult.fromMap(payload);
  }
}
