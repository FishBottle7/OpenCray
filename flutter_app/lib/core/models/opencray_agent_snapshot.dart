import 'package:flutter/foundation.dart';

@immutable
class OpenCrayAgentLlmConfig {
  const OpenCrayAgentLlmConfig({
    required this.provider,
    required this.protocol,
    this.baseUrl,
    this.apiKey,
    required this.model,
    this.reasoningEffort,
  });

  final String provider;
  final String protocol;
  final String? baseUrl;
  final String? apiKey;
  final String model;
  final String? reasoningEffort;

  factory OpenCrayAgentLlmConfig.fromMap(Map<Object?, Object?> map) {
    return OpenCrayAgentLlmConfig(
      provider: map['provider'] as String? ?? '',
      protocol: map['protocol'] as String? ?? '',
      baseUrl: map['baseUrl'] as String?,
      apiKey: map['apiKey'] as String?,
      model: map['model'] as String? ?? '',
      reasoningEffort: map['reasoningEffort'] as String?,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'provider': provider,
    'protocol': protocol,
    'baseUrl': baseUrl,
    'apiKey': apiKey,
    'model': model,
    'reasoningEffort': reasoningEffort,
  };
}

@immutable
class OpenCrayAgentAvatarConfig {
  const OpenCrayAgentAvatarConfig({
    required this.source,
    this.settingsAssetId,
  });

  final String source;
  final String? settingsAssetId;

  factory OpenCrayAgentAvatarConfig.fromMap(Map<Object?, Object?> map) {
    return OpenCrayAgentAvatarConfig(
      source: map['source'] as String? ?? '',
      settingsAssetId: map['settingsAssetId'] as String?,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'source': source,
    'settingsAssetId': settingsAssetId,
  };
}

@immutable
class OpenCrayAgentImageReferenceConfig {
  const OpenCrayAgentImageReferenceConfig({
    required this.referenceId,
    required this.label,
    this.settingsAssetId,
  });

  final String referenceId;
  final String label;
  final String? settingsAssetId;

  factory OpenCrayAgentImageReferenceConfig.fromMap(Map<Object?, Object?> map) {
    return OpenCrayAgentImageReferenceConfig(
      referenceId: map['referenceId'] as String? ?? '',
      label: map['label'] as String? ?? '',
      settingsAssetId: map['settingsAssetId'] as String?,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'referenceId': referenceId,
    'label': label,
    'settingsAssetId': settingsAssetId,
  };
}

@immutable
class OpenCrayAgentCreateRequest {
  const OpenCrayAgentCreateRequest({
    required this.displayName,
    required this.presetName,
    required this.plasticity,
    this.callsYou = '',
    this.addressStyle = '',
    this.mode = 'full',
    this.voiceSummary = '',
    this.verbosity = '',
    this.relationshipStyle = '',
    this.riskTolerance = '',
    this.toolUseBias = '',
    this.baseDescription = '',
    this.collaborationGuidance = '',
    this.escalationRules = '',
    this.forbiddenBehaviors = '',
    this.llm,
    this.avatar,
    this.imageReferences = const <OpenCrayAgentImageReferenceConfig>[],
    this.activateOnCreate = true,
  });

  final String displayName;
  final String presetName;
  final String plasticity;
  final String callsYou;
  final String addressStyle;
  final String mode;
  final String voiceSummary;
  final String verbosity;
  final String relationshipStyle;
  final String riskTolerance;
  final String toolUseBias;
  final String baseDescription;
  final String collaborationGuidance;
  final String escalationRules;
  final String forbiddenBehaviors;
  final OpenCrayAgentLlmConfig? llm;
  final OpenCrayAgentAvatarConfig? avatar;
  final List<OpenCrayAgentImageReferenceConfig> imageReferences;
  final bool activateOnCreate;

  factory OpenCrayAgentCreateRequest.fromMap(Map<Object?, Object?> map) {
    return OpenCrayAgentCreateRequest(
      displayName: map['displayName'] as String? ?? '',
      presetName: map['presetName'] as String? ?? '',
      plasticity: map['plasticity'] as String? ?? '',
      callsYou: map['callsYou'] as String? ?? '',
      addressStyle: map['addressStyle'] as String? ?? '',
      mode: map['mode'] as String? ?? 'full',
      voiceSummary: map['voiceSummary'] as String? ?? '',
      verbosity: map['verbosity'] as String? ?? '',
      relationshipStyle: map['relationshipStyle'] as String? ?? '',
      riskTolerance: map['riskTolerance'] as String? ?? '',
      toolUseBias: map['toolUseBias'] as String? ?? '',
      baseDescription: map['baseDescription'] as String? ?? '',
      collaborationGuidance: map['collaborationGuidance'] as String? ?? '',
      escalationRules: map['escalationRules'] as String? ?? '',
      forbiddenBehaviors: map['forbiddenBehaviors'] as String? ?? '',
      llm: _asMap(map['llm']) == null
          ? null
          : OpenCrayAgentLlmConfig.fromMap(_asMap(map['llm'])!),
      avatar: _asMap(map['avatar']) == null
          ? null
          : OpenCrayAgentAvatarConfig.fromMap(_asMap(map['avatar'])!),
      imageReferences: _asList(map['imageReferences'])
          .map(_asMap)
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayAgentImageReferenceConfig.fromMap)
          .toList(growable: false),
      activateOnCreate: map['activateOnCreate'] as bool? ?? true,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'displayName': displayName,
    'presetName': presetName,
    'plasticity': plasticity,
    'callsYou': callsYou,
    'addressStyle': addressStyle,
    'mode': mode,
    'voiceSummary': voiceSummary,
    'verbosity': verbosity,
    'relationshipStyle': relationshipStyle,
    'riskTolerance': riskTolerance,
    'toolUseBias': toolUseBias,
    'baseDescription': baseDescription,
    'collaborationGuidance': collaborationGuidance,
    'escalationRules': escalationRules,
    'forbiddenBehaviors': forbiddenBehaviors,
    'llm': llm?.toMap(),
    'avatar': avatar?.toMap(),
    'imageReferences': imageReferences
        .map((OpenCrayAgentImageReferenceConfig value) => value.toMap())
        .toList(growable: false),
    'activateOnCreate': activateOnCreate,
  };
}

@immutable
class OpenCrayAgentSnapshot {
  const OpenCrayAgentSnapshot({
    required this.agentId,
    required this.displayName,
    required this.presetName,
    required this.plasticity,
    required this.mode,
    this.callsYou = '',
    this.addressStyle = '',
    this.voiceSummary = '',
    this.verbosity = '',
    this.relationshipStyle = '',
    this.riskTolerance = '',
    this.toolUseBias = '',
    this.baseDescription = '',
    this.collaborationGuidance = '',
    this.escalationRules = '',
    this.forbiddenBehaviors = '',
    this.llm,
    this.avatar,
    this.imageReferences = const <OpenCrayAgentImageReferenceConfig>[],
    this.activeSessionId,
    this.avatarSeed,
    required this.createdAtEpochMs,
    required this.updatedAtEpochMs,
    this.isArchived = false,
    this.isActive = false,
  });

  final String agentId;
  final String displayName;
  final String presetName;
  final String plasticity;
  final String mode;
  final String callsYou;
  final String addressStyle;
  final String voiceSummary;
  final String verbosity;
  final String relationshipStyle;
  final String riskTolerance;
  final String toolUseBias;
  final String baseDescription;
  final String collaborationGuidance;
  final String escalationRules;
  final String forbiddenBehaviors;
  final OpenCrayAgentLlmConfig? llm;
  final OpenCrayAgentAvatarConfig? avatar;
  final List<OpenCrayAgentImageReferenceConfig> imageReferences;
  final String? activeSessionId;
  final String? avatarSeed;
  final int createdAtEpochMs;
  final int updatedAtEpochMs;
  final bool isArchived;
  final bool isActive;

  factory OpenCrayAgentSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayAgentSnapshot(
      agentId: map['agentId'] as String? ?? '',
      displayName: map['displayName'] as String? ?? '',
      presetName: map['presetName'] as String? ?? '',
      plasticity: map['plasticity'] as String? ?? '',
      mode: map['mode'] as String? ?? 'full',
      callsYou: map['callsYou'] as String? ?? '',
      addressStyle: map['addressStyle'] as String? ?? '',
      voiceSummary: map['voiceSummary'] as String? ?? '',
      verbosity: map['verbosity'] as String? ?? '',
      relationshipStyle: map['relationshipStyle'] as String? ?? '',
      riskTolerance: map['riskTolerance'] as String? ?? '',
      toolUseBias: map['toolUseBias'] as String? ?? '',
      baseDescription: map['baseDescription'] as String? ?? '',
      collaborationGuidance: map['collaborationGuidance'] as String? ?? '',
      escalationRules: map['escalationRules'] as String? ?? '',
      forbiddenBehaviors: map['forbiddenBehaviors'] as String? ?? '',
      llm: _asMap(map['llm']) == null
          ? null
          : OpenCrayAgentLlmConfig.fromMap(_asMap(map['llm'])!),
      avatar: _asMap(map['avatar']) == null
          ? null
          : OpenCrayAgentAvatarConfig.fromMap(_asMap(map['avatar'])!),
      imageReferences: _asList(map['imageReferences'])
          .map(_asMap)
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayAgentImageReferenceConfig.fromMap)
          .toList(growable: false),
      activeSessionId: map['activeSessionId'] as String?,
      avatarSeed: map['avatarSeed'] as String?,
      createdAtEpochMs: _asInt(map['createdAtEpochMs']) ?? 0,
      updatedAtEpochMs: _asInt(map['updatedAtEpochMs']) ?? 0,
      isArchived: map['isArchived'] as bool? ?? false,
      isActive: map['isActive'] as bool? ?? false,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'agentId': agentId,
    'displayName': displayName,
    'presetName': presetName,
    'plasticity': plasticity,
    'mode': mode,
    'callsYou': callsYou,
    'addressStyle': addressStyle,
    'voiceSummary': voiceSummary,
    'verbosity': verbosity,
    'relationshipStyle': relationshipStyle,
    'riskTolerance': riskTolerance,
    'toolUseBias': toolUseBias,
    'baseDescription': baseDescription,
    'collaborationGuidance': collaborationGuidance,
    'escalationRules': escalationRules,
    'forbiddenBehaviors': forbiddenBehaviors,
    'llm': llm?.toMap(),
    'avatar': avatar?.toMap(),
    'imageReferences': imageReferences
        .map((OpenCrayAgentImageReferenceConfig value) => value.toMap())
        .toList(growable: false),
    'activeSessionId': activeSessionId,
    'avatarSeed': avatarSeed,
    'createdAtEpochMs': createdAtEpochMs,
    'updatedAtEpochMs': updatedAtEpochMs,
    'isArchived': isArchived,
    'isActive': isActive,
  };

  OpenCrayAgentSnapshot copyWith({
    String? agentId,
    String? displayName,
    String? presetName,
    String? plasticity,
    String? mode,
    String? callsYou,
    String? addressStyle,
    String? voiceSummary,
    String? verbosity,
    String? relationshipStyle,
    String? riskTolerance,
    String? toolUseBias,
    String? baseDescription,
    String? collaborationGuidance,
    String? escalationRules,
    String? forbiddenBehaviors,
    OpenCrayAgentLlmConfig? llm,
    OpenCrayAgentAvatarConfig? avatar,
    List<OpenCrayAgentImageReferenceConfig>? imageReferences,
    String? activeSessionId,
    String? avatarSeed,
    int? createdAtEpochMs,
    int? updatedAtEpochMs,
    bool? isArchived,
    bool? isActive,
  }) {
    return OpenCrayAgentSnapshot(
      agentId: agentId ?? this.agentId,
      displayName: displayName ?? this.displayName,
      presetName: presetName ?? this.presetName,
      plasticity: plasticity ?? this.plasticity,
      mode: mode ?? this.mode,
      callsYou: callsYou ?? this.callsYou,
      addressStyle: addressStyle ?? this.addressStyle,
      voiceSummary: voiceSummary ?? this.voiceSummary,
      verbosity: verbosity ?? this.verbosity,
      relationshipStyle: relationshipStyle ?? this.relationshipStyle,
      riskTolerance: riskTolerance ?? this.riskTolerance,
      toolUseBias: toolUseBias ?? this.toolUseBias,
      baseDescription: baseDescription ?? this.baseDescription,
      collaborationGuidance:
          collaborationGuidance ?? this.collaborationGuidance,
      escalationRules: escalationRules ?? this.escalationRules,
      forbiddenBehaviors: forbiddenBehaviors ?? this.forbiddenBehaviors,
      llm: llm ?? this.llm,
      avatar: avatar ?? this.avatar,
      imageReferences: imageReferences ?? this.imageReferences,
      activeSessionId: activeSessionId ?? this.activeSessionId,
      avatarSeed: avatarSeed ?? this.avatarSeed,
      createdAtEpochMs: createdAtEpochMs ?? this.createdAtEpochMs,
      updatedAtEpochMs: updatedAtEpochMs ?? this.updatedAtEpochMs,
      isArchived: isArchived ?? this.isArchived,
      isActive: isActive ?? this.isActive,
    );
  }
}

int? _asInt(Object? value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  return int.tryParse('${value ?? ''}');
}

Map<Object?, Object?>? _asMap(Object? value) => value is Map<Object?, Object?>
    ? value
    : value is Map
    ? value.cast<Object?, Object?>()
    : null;

List<Object?> _asList(Object? value) {
  if (value is List<Object?>) {
    return value;
  }
  if (value is List) {
    return value.cast<Object?>();
  }
  return const <Object?>[];
}
