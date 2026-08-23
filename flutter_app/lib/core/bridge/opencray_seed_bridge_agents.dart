part of 'opencray_seed_bridge.dart';

mixin _SeedBridgeAgentsDomain on _SeedBridgeDeps {
  @override
  Future<List<OpenCrayAgentSnapshot>> listAgents() async =>
      _materializeAgents();

  @override
  Future<OpenCrayAgentSnapshot?> loadActiveAgent() async {
    final activeAgentId = _activeAgentId;
    if (activeAgentId == null) {
      return null;
    }
    for (final agent in _materializeAgents()) {
      if (agent.agentId == activeAgentId) {
        return agent;
      }
    }
    return null;
  }

  @override
  Future<OpenCrayAgentSnapshot> createAgent(
    OpenCrayAgentCreateRequest request,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    final agentId = _allocateSeedAgentId();
    final snapshot = OpenCrayAgentSnapshot(
      agentId: agentId,
      displayName: request.displayName.trim().isEmpty
          ? 'Untitled agent'
          : request.displayName.trim(),
      presetName: request.presetName.trim().isEmpty
          ? 'steady'
          : request.presetName.trim(),
      plasticity: request.plasticity.trim().isEmpty
          ? 'medium'
          : request.plasticity.trim(),
      mode: request.mode.trim().isEmpty ? 'full' : request.mode.trim(),
      callsYou: request.callsYou.trim(),
      addressStyle: request.addressStyle.trim(),
      voiceSummary: request.voiceSummary.trim(),
      verbosity: request.verbosity.trim(),
      relationshipStyle: request.relationshipStyle.trim(),
      riskTolerance: request.riskTolerance.trim(),
      toolUseBias: request.toolUseBias.trim(),
      baseDescription: request.baseDescription.trim(),
      collaborationGuidance: request.collaborationGuidance.trim(),
      escalationRules: request.escalationRules.trim(),
      forbiddenBehaviors: request.forbiddenBehaviors.trim(),
      llm: request.llm,
      avatar: request.avatar,
      imageReferences: List<OpenCrayAgentImageReferenceConfig>.from(
        request.imageReferences,
        growable: false,
      ),
      activeSessionId: 'seed-session-$agentId',
      avatarSeed: request.avatar?.settingsAssetId ?? request.displayName.trim(),
      createdAtEpochMs: now,
      updatedAtEpochMs: now,
    );
    _agents.insert(0, snapshot);
    if (request.activateOnCreate || _activeAgentId == null) {
      _activeAgentId = agentId;
    }
    return _materializeAgent(snapshot);
  }

  @override
  Future<OpenCrayAgentSnapshot?> selectAgent(String agentId) async {
    final normalizedAgentId = agentId.trim();
    for (final agent in _agents) {
      if (agent.agentId == normalizedAgentId) {
        _activeAgentId = normalizedAgentId;
        return _materializeAgent(agent);
      }
    }
    throw StateError('Unknown seed agent: $normalizedAgentId');
  }

  @override
  Future<OpenCraySoulVisualIdentity?> loadSoulVisualIdentity() async => null;

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulPrimaryPortrait(
    OpenCrayImageReferenceSource source,
  ) async => null;

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulReferenceImage({
    required String refId,
    required OpenCrayImageReferenceSource source,
  }) async => null;

  @override
  Future<List<OpenCrayImageReference>> listMemoryImageReferences(
    String memoryId,
  ) async => const <OpenCrayImageReference>[];

  @override
  Future<OpenCrayMemoryImageReferenceResult?> attachMemoryImageReference({
    required String memoryId,
    required OpenCrayImageReferenceSource source,
    String? preferredMode,
  }) async => null;
}
