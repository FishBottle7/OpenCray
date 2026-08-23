part of 'opencray_local_runtime_bridge.dart';

mixin _LocalRuntimeBridgeAgentsDomain on _LocalRuntimeBridgeDeps {
  @override
  Future<List<OpenCrayAgentSnapshot>> listAgents() async =>
      (await _getJson('v1/agents') as List<Object?>? ?? const <Object?>[])
          .map(_requireMap)
          .map(OpenCrayAgentSnapshot.fromMap)
          .toList(growable: false);

  @override
  Future<OpenCrayAgentSnapshot?> loadActiveAgent() async {
    final payload = await _getJson('v1/active_agent');
    if (payload == null) {
      return null;
    }
    return OpenCrayAgentSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<OpenCrayAgentSnapshot> createAgent(
    OpenCrayAgentCreateRequest request,
  ) async => OpenCrayAgentSnapshot.fromMap(
    _requireMap(
      await _requestJson('POST', 'v1/create_agent', body: request.toMap()),
    ),
  );

  @override
  Future<OpenCrayAgentSnapshot?> selectAgent(String agentId) async {
    final payload = await _requestJson(
      'POST',
      'v1/select_agent',
      body: <String, Object?>{'agentId': agentId},
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayAgentSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<OpenCraySoulVisualIdentity?> loadSoulVisualIdentity() async {
    final payload = await _getJson('v1/soul_visual_identity');
    if (payload == null) {
      return null;
    }
    return OpenCraySoulVisualIdentity.fromMap(_requireMap(payload));
  }

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulPrimaryPortrait(
    OpenCrayImageReferenceSource source,
  ) async {
    final payload = await _requestJson(
      'POST',
      'v1/save_soul_primary_portrait',
      body: <String, Object?>{'source': source.toMap()},
    );
    if (payload == null) {
      return null;
    }
    return OpenCraySoulVisualIdentity.fromMap(_requireMap(payload));
  }

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulReferenceImage({
    required String refId,
    required OpenCrayImageReferenceSource source,
  }) async {
    final payload = await _requestJson(
      'POST',
      'v1/save_soul_reference_image',
      body: <String, Object?>{'refId': refId, 'source': source.toMap()},
    );
    if (payload == null) {
      return null;
    }
    return OpenCraySoulVisualIdentity.fromMap(_requireMap(payload));
  }

  @override
  Future<List<OpenCrayImageReference>> listMemoryImageReferences(
    String memoryId,
  ) async =>
      (await _getJson(
                    'v1/memory_image_references',
                    queryParameters: <String, String>{'memoryId': memoryId},
                  )
                  as List<Object?>? ??
              const <Object?>[])
          .map(_requireMap)
          .map(OpenCrayImageReference.fromMap)
          .toList(growable: false);

  @override
  Future<OpenCrayMemoryImageReferenceResult?> attachMemoryImageReference({
    required String memoryId,
    required OpenCrayImageReferenceSource source,
    String? preferredMode,
  }) async {
    final payload = await _requestJson(
      'POST',
      'v1/attach_memory_image_reference',
      body: <String, Object?>{
        'memoryId': memoryId,
        'source': source.toMap(),
        'preferredMode': preferredMode,
      },
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayMemoryImageReferenceResult.fromMap(_requireMap(payload));
  }
}
