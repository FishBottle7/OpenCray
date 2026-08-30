part of 'settings_feature.dart';

enum _AgentSoulPreset { steady, builder, warm }

enum _AgentPlasticity { low, medium, high }

enum _AgentAddressStyle { neutral, friendly, intimate }

enum _AgentMode { full, lightweight, none, noSoul, noMemoryOrSoul }

enum _AgentAvatarSource { generated, custom }

enum _AgentTwinImportCorpusType { chatHistory, fictionWork }

enum _AgentProvider { openai, anthropic, custom }

enum _AgentProtocol { openAiCompatible, anthropic }

enum _AgentReasoningEffort { low, medium, high, xhigh }

enum _AgentVoiceSummary { calmConcrete, decisiveTechnical, warmReflective }

enum _AgentVerbosity { concise, balanced, detailed }

enum _AgentRelationshipStyle { collaborative, direct, supportive }

enum _AgentRiskTolerance { conservative, balanced, assertive }

enum _AgentToolUseBias { verifyFirst, executionFirst, minimalTools }

enum _AgentCreateStatus { clean, edited, saving, saved, failed }

class _AgentImageReference {
  const _AgentImageReference({
    required this.id,
    required this.label,
    required this.colors,
    this.settingsAsset,
  });

  final String id;
  final String label;
  final List<Color> colors;
  final OpenCraySettingsImageAsset? settingsAsset;

  _AgentImageReference copy() => _AgentImageReference(
    id: id,
    label: label,
    colors: List<Color>.from(colors),
    settingsAsset: settingsAsset,
  );
}

class _AgentDraft {
  _AgentDraft({
    required this.agentName,
    required this.callsYou,
    required this.baseDescription,
    required this.soulPreset,
    required this.plasticity,
    required this.addressStyle,
    required this.mode,
    required this.hasAudioSample,
    required this.audioFileName,
    required this.audioDurationSeconds,
    required this.audioFileSizeLabel,
    required this.imageReferences,
    required this.avatarSource,
    required this.avatarColors,
    required this.customAvatarAsset,
    required this.twinImportConfigured,
    required this.twinImportCorpusType,
    required this.twinImportFileName,
    required this.twinImportFormatLabel,
    required this.twinImportSourceMeta,
    required this.twinImportCloneTarget,
    required this.twinImportCastAs,
    required this.twinImportRelationFocus,
    required this.provider,
    required this.protocol,
    required this.baseUrl,
    required this.apiKey,
    required this.model,
    required this.reasoningEffort,
    required this.voiceSummary,
    required this.verbosity,
    required this.relationshipStyle,
    required this.riskTolerance,
    required this.toolUseBias,
    required this.collaborationGuidance,
    required this.escalationRules,
    required this.forbiddenBehaviors,
  });

  factory _AgentDraft.prototype() => _AgentDraft(
    agentName: 'Aster',
    callsYou: 'Fish',
    baseDescription:
        'Calm, concrete, and good at turning open-ended ideas into short next steps.',
    soulPreset: _AgentSoulPreset.steady,
    plasticity: _AgentPlasticity.medium,
    addressStyle: _AgentAddressStyle.friendly,
    mode: _AgentMode.full,
    hasAudioSample: true,
    audioFileName: 'voice-aster-intro.m4a',
    audioDurationSeconds: 32,
    audioFileSizeLabel: '1.8 MB',
    imageReferences: <_AgentImageReference>[
      _AgentImageReference(
        id: 'front-portrait',
        label: 'Front portrait',
        colors: _agentGradientSets[0],
      ),
      _AgentImageReference(
        id: 'red-outfit',
        label: 'Red outfit',
        colors: _agentGradientSets[1],
      ),
      _AgentImageReference(
        id: 'warm-light',
        label: 'Warm light',
        colors: _agentGradientSets[2],
      ),
    ],
    avatarSource: _AgentAvatarSource.generated,
    avatarColors: _agentGradientSets[0],
    customAvatarAsset: null,
    twinImportConfigured: false,
    twinImportCorpusType: _AgentTwinImportCorpusType.chatHistory,
    twinImportFileName: 'aster_chat_export.chatlab.jsonl',
    twinImportFormatLabel: 'ChatLab JSONL',
    twinImportSourceMeta: '2,481 turns · 2 participants',
    twinImportCloneTarget: 'Aster',
    twinImportCastAs: 'Fish',
    twinImportRelationFocus: 'Ties with Fish',
    provider: _AgentProvider.openai,
    protocol: _AgentProtocol.openAiCompatible,
    baseUrl: 'https://api.openai.com/v1',
    apiKey: 'sk-demo-secret-1234',
    model: 'gpt-4o-mini',
    reasoningEffort: _AgentReasoningEffort.medium,
    voiceSummary: _AgentVoiceSummary.calmConcrete,
    verbosity: _AgentVerbosity.balanced,
    relationshipStyle: _AgentRelationshipStyle.collaborative,
    riskTolerance: _AgentRiskTolerance.conservative,
    toolUseBias: _AgentToolUseBias.verifyFirst,
    collaborationGuidance: 'Keep reasoning concrete and implementation-first.',
    escalationRules: 'Ask before risky workspace or environment changes.',
    forbiddenBehaviors:
        'Do not fabricate workspace facts when local evidence is required.',
  );

  String agentName;
  String callsYou;
  String baseDescription;
  _AgentSoulPreset soulPreset;
  _AgentPlasticity plasticity;
  _AgentAddressStyle addressStyle;
  _AgentMode mode;
  bool hasAudioSample;
  String audioFileName;
  int audioDurationSeconds;
  String audioFileSizeLabel;
  List<_AgentImageReference> imageReferences;
  _AgentAvatarSource avatarSource;
  List<Color> avatarColors;
  OpenCraySettingsImageAsset? customAvatarAsset;
  bool twinImportConfigured;
  _AgentTwinImportCorpusType twinImportCorpusType;
  String twinImportFileName;
  String twinImportFormatLabel;
  String twinImportSourceMeta;
  String twinImportCloneTarget;
  String twinImportCastAs;
  String twinImportRelationFocus;
  _AgentProvider provider;
  _AgentProtocol protocol;
  String baseUrl;
  String apiKey;
  String model;
  _AgentReasoningEffort reasoningEffort;
  _AgentVoiceSummary voiceSummary;
  _AgentVerbosity verbosity;
  _AgentRelationshipStyle relationshipStyle;
  _AgentRiskTolerance riskTolerance;
  _AgentToolUseBias toolUseBias;
  String collaborationGuidance;
  String escalationRules;
  String forbiddenBehaviors;

  _AgentDraft copy() => _AgentDraft(
    agentName: agentName,
    callsYou: callsYou,
    baseDescription: baseDescription,
    soulPreset: soulPreset,
    plasticity: plasticity,
    addressStyle: addressStyle,
    mode: mode,
    hasAudioSample: hasAudioSample,
    audioFileName: audioFileName,
    audioDurationSeconds: audioDurationSeconds,
    audioFileSizeLabel: audioFileSizeLabel,
    imageReferences: imageReferences
        .map((_AgentImageReference image) => image.copy())
        .toList(growable: false),
    avatarSource: avatarSource,
    avatarColors: List<Color>.from(avatarColors),
    customAvatarAsset: customAvatarAsset,
    twinImportConfigured: twinImportConfigured,
    twinImportCorpusType: twinImportCorpusType,
    twinImportFileName: twinImportFileName,
    twinImportFormatLabel: twinImportFormatLabel,
    twinImportSourceMeta: twinImportSourceMeta,
    twinImportCloneTarget: twinImportCloneTarget,
    twinImportCastAs: twinImportCastAs,
    twinImportRelationFocus: twinImportRelationFocus,
    provider: provider,
    protocol: protocol,
    baseUrl: baseUrl,
    apiKey: apiKey,
    model: model,
    reasoningEffort: reasoningEffort,
    voiceSummary: voiceSummary,
    verbosity: verbosity,
    relationshipStyle: relationshipStyle,
    riskTolerance: riskTolerance,
    toolUseBias: toolUseBias,
    collaborationGuidance: collaborationGuidance,
    escalationRules: escalationRules,
    forbiddenBehaviors: forbiddenBehaviors,
  );

  String get nameOrFallback =>
      agentName.trim().isEmpty ? 'Untitled agent' : agentName.trim();

  String get avatarLetter {
    final trimmed = nameOrFallback.trim();
    if (trimmed.isEmpty) {
      return 'A';
    }
    return trimmed.substring(0, 1).toUpperCase();
  }

  String get modeLabel => _labelForAgentMode(mode);

  String get soulPresetLabel => _labelForSoulPreset(soulPreset);

  String get plasticityLabel => _labelForPlasticity(plasticity);

  String get addressStyleLabel => _labelForAddressStyle(addressStyle);

  String get mediaSummary {
    final hasImages = imageReferences.isNotEmpty;
    if (hasAudioSample && hasImages) {
      return 'Audio + images';
    }
    if (hasAudioSample) {
      return 'Audio';
    }
    if (hasImages) {
      return 'Images';
    }
    return 'None';
  }

  String get modelSummary {
    final modelLabel = model.trim().isEmpty ? 'Model' : model.trim();
    return '${_labelForProvider(provider)} · $modelLabel';
  }

  String get advancedSummary => '4 settings';

  String get avatarStatusLabel =>
      avatarSource == _AgentAvatarSource.generated ? 'Generated' : 'Custom';

  String get customAvatarUploadLabel {
    final fileName = customAvatarAsset?.displayName.trim() ?? '';
    if (fileName.isEmpty) {
      return 'PNG / JPG';
    }
    return fileName;
  }

  int get referenceImageCount => imageReferences.length;

  String get twinImportSummary =>
      twinImportConfigured ? twinImportFormatLabel : 'Not set';

  OpenCrayAgentCreateRequest toHostCreateRequest() {
    final OpenCrayAgentAvatarConfig avatarConfig =
        avatarSource == _AgentAvatarSource.custom && customAvatarAsset != null
        ? OpenCrayAgentAvatarConfig(
            source: 'custom',
            settingsAssetId: customAvatarAsset!.assetId,
          )
        : const OpenCrayAgentAvatarConfig(source: 'generated');
    return OpenCrayAgentCreateRequest(
      displayName: nameOrFallback,
      presetName: soulPreset.name,
      plasticity: plasticity.name,
      callsYou: callsYou.trim(),
      addressStyle: addressStyle.name,
      mode: mode.name,
      voiceSummary: voiceSummary.name,
      verbosity: verbosity.name,
      relationshipStyle: relationshipStyle.name,
      riskTolerance: riskTolerance.name,
      toolUseBias: toolUseBias.name,
      baseDescription: baseDescription.trim(),
      collaborationGuidance: collaborationGuidance.trim(),
      escalationRules: escalationRules.trim(),
      forbiddenBehaviors: forbiddenBehaviors.trim(),
      llm: OpenCrayAgentLlmConfig(
        provider: provider.name,
        protocol: protocol == _AgentProtocol.anthropic ? 'anthropic' : 'openai',
        baseUrl: baseUrl.trim().isEmpty ? null : baseUrl.trim(),
        apiKey: apiKey.trim().isEmpty ? null : apiKey.trim(),
        model: model.trim().isEmpty ? 'model' : model.trim(),
        reasoningEffort: reasoningEffort.name,
      ),
      avatar: avatarConfig,
      imageReferences: imageReferences
          .map(
            (_AgentImageReference image) => OpenCrayAgentImageReferenceConfig(
              referenceId: image.id,
              label: image.label,
              settingsAssetId: image.settingsAsset?.assetId,
            ),
          )
          .toList(growable: false),
      activateOnCreate: true,
    );
  }
}

class _SavedAgent {
  const _SavedAgent({
    this.agentId,
    required this.name,
    required this.summary,
    required this.description,
    required this.meta,
    required this.avatarColors,
    this.template,
    this.isActive = false,
  });

  final String? agentId;
  final String name;
  final String summary;
  final String description;
  final String meta;
  final List<Color> avatarColors;
  final _AgentDraft? template;
  final bool isActive;

  factory _SavedAgent.fromDraft(_AgentDraft draft) {
    final modelLabel = draft.model.trim().isEmpty
        ? 'Model'
        : draft.model.trim();
    return _SavedAgent(
      agentId: null,
      name: draft.nameOrFallback,
      summary: '${draft.soulPresetLabel} · ${draft.modeLabel}',
      description: draft.baseDescription.trim().isEmpty
          ? 'No base description yet.'
          : draft.baseDescription.trim(),
      meta:
          '${_labelForProvider(draft.provider)} · $modelLabel · updated just now',
      avatarColors: List<Color>.from(draft.avatarColors),
      template: draft.copy(),
      isActive: false,
    );
  }

  factory _SavedAgent.fromSnapshot(OpenCrayAgentSnapshot snapshot) {
    final providerLabel = snapshot.llm == null
        ? 'Model'
        : _labelForProviderFromRaw(snapshot.llm!.provider);
    final modelLabel = snapshot.llm?.model.trim().isNotEmpty == true
        ? snapshot.llm!.model.trim()
        : 'Model';
    final description = snapshot.baseDescription.trim().isEmpty
        ? 'No base description yet.'
        : snapshot.baseDescription.trim();
    final metaSuffix = snapshot.isActive ? 'active' : 'saved';
    return _SavedAgent(
      agentId: snapshot.agentId,
      name: snapshot.displayName.trim().isEmpty
          ? 'Untitled agent'
          : snapshot.displayName.trim(),
      summary:
          '${_labelForSoulPresetFromRaw(snapshot.presetName)} · ${_labelForAgentModeFromRaw(snapshot.mode)}',
      description: description,
      meta: '$providerLabel · $modelLabel · $metaSuffix',
      avatarColors: _agentGradientForSeed(
        snapshot.avatarSeed?.trim().isNotEmpty == true
            ? snapshot.avatarSeed!
            : snapshot.agentId,
      ),
      template: null,
      isActive: snapshot.isActive,
    );
  }

  _AgentDraft toDraft() => template!.copy();
}

List<Color> _agentGradientForSeed(String seed) {
  final normalizedSeed = seed.trim();
  var hash = 17;
  for (final codeUnit in normalizedSeed.codeUnits) {
    hash = 37 * hash + codeUnit;
  }
  final gradient = _agentGradientSets[hash.abs() % _agentGradientSets.length];
  return List<Color>.from(gradient, growable: false);
}

List<Color> _agentGradientForSettingsAsset(OpenCraySettingsImageAsset asset) {
  final seed = [
    asset.assetId,
    asset.displayName,
    asset.relativePath,
    asset.sha256,
  ].join('|');
  return _agentGradientForSeed(seed);
}

String _deriveAgentImageLabelFromSettingsAsset(
  OpenCraySettingsImageAsset asset,
) {
  final rawName = asset.displayName.trim().isNotEmpty
      ? asset.displayName.trim()
      : asset.relativePath.trim();
  final fileName = rawName.split(RegExp(r'[\\/]')).last;
  final extensionIndex = fileName.lastIndexOf('.');
  final baseName = extensionIndex > 0
      ? fileName.substring(0, extensionIndex)
      : fileName;
  final normalized = baseName
      .replaceAll(RegExp(r'[_\-]+'), ' ')
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
  if (normalized.isEmpty) {
    return 'Reference image';
  }
  return normalized
      .split(' ')
      .where((String token) => token.isNotEmpty)
      .map(_capitalizeAgentImageLabelToken)
      .join(' ');
}

String _capitalizeAgentImageLabelToken(String token) {
  if (token.isEmpty) {
    return token;
  }
  return '${token.substring(0, 1).toUpperCase()}${token.substring(1)}';
}

_AgentImageReference _agentImageReferenceFromSettingsAsset(
  OpenCraySettingsImageAsset asset, {
  String? label,
}) {
  return _AgentImageReference(
    id: asset.assetId,
    label: label?.trim().isNotEmpty == true
        ? label!.trim()
        : _deriveAgentImageLabelFromSettingsAsset(asset),
    colors: _agentGradientForSettingsAsset(asset),
    settingsAsset: asset,
  );
}

List<_SavedAgent> _buildPrototypeAgents() {
  final _AgentDraft aster = _AgentDraft.prototype();
  final _AgentDraft nova = _AgentDraft.prototype()
    ..agentName = 'Nova'
    ..baseDescription =
        'Execution-focused agent for implementation passes, repo cleanup, and patch reviews.'
    ..soulPreset = _AgentSoulPreset.builder
    ..mode = _AgentMode.noSoul
    ..provider = _AgentProvider.anthropic
    ..protocol = _AgentProtocol.anthropic
    ..baseUrl = 'https://api.anthropic.com'
    ..model = 'Sonnet'
    ..avatarColors = _agentGradientSets[1];
  final _AgentDraft quarry = _AgentDraft.prototype()
    ..agentName = 'Quarry'
    ..baseDescription =
        'Research and synthesis agent tuned for tracing code paths and writing concise findings.'
    ..mode = _AgentMode.lightweight
    ..model = 'gpt-4.1'
    ..avatarColors = _agentGradientSets[2];
  final _AgentDraft muse = _AgentDraft.prototype()
    ..agentName = 'Muse'
    ..baseDescription =
        'Reflective writing companion with a warmer tone and reduced runtime context.'
    ..soulPreset = _AgentSoulPreset.warm
    ..mode = _AgentMode.noMemoryOrSoul
    ..provider = _AgentProvider.custom
    ..model = '2.5 Flash'
    ..avatarColors = _agentGradientSets[3];
  return <_SavedAgent>[
    _SavedAgent(
      name: 'Aster',
      summary: 'Steady · Full',
      description:
          'Calm, concrete, and good at turning open-ended ideas into short next steps.',
      meta: 'OpenAI · gpt-4o-mini · updated 2h ago',
      avatarColors: _agentGradientSets[0],
      template: aster,
    ),
    _SavedAgent(
      name: 'Nova',
      summary: 'Builder · No soul',
      description:
          'Execution-focused agent for implementation passes, repo cleanup, and patch reviews.',
      meta: 'Anthropic · Sonnet · updated yesterday',
      avatarColors: _agentGradientSets[1],
      template: nova,
    ),
    _SavedAgent(
      name: 'Quarry',
      summary: 'Steady · Lightweight',
      description:
          'Research and synthesis agent tuned for tracing code paths and writing concise findings.',
      meta: 'OpenAI · gpt-4.1 · updated Mar 18',
      avatarColors: _agentGradientSets[2],
      template: quarry,
    ),
    _SavedAgent(
      name: 'Muse',
      summary: 'Warm · No memory or soul',
      description:
          'Reflective writing companion with a warmer tone and reduced runtime context.',
      meta: 'Gemini · 2.5 Flash · updated Mar 12',
      avatarColors: _agentGradientSets[3],
      template: muse,
    ),
  ];
}

String _labelForSoulPreset(_AgentSoulPreset preset) {
  switch (preset) {
    case _AgentSoulPreset.steady:
      return 'Steady';
    case _AgentSoulPreset.builder:
      return 'Builder';
    case _AgentSoulPreset.warm:
      return 'Warm';
  }
}

String _labelForSoulPresetSegment(_AgentSoulPreset preset) {
  switch (preset) {
    case _AgentSoulPreset.steady:
      return 'STEADY';
    case _AgentSoulPreset.builder:
      return 'BUILDER';
    case _AgentSoulPreset.warm:
      return 'WARM';
  }
}

String _labelForPlasticity(_AgentPlasticity plasticity) {
  switch (plasticity) {
    case _AgentPlasticity.low:
      return 'Low';
    case _AgentPlasticity.medium:
      return 'Medium';
    case _AgentPlasticity.high:
      return 'High';
  }
}

String _bodyForPlasticity(_AgentPlasticity plasticity) {
  switch (plasticity) {
    case _AgentPlasticity.low:
      return 'Stable identity with minimal drift.';
    case _AgentPlasticity.medium:
      return 'Adapts, but keeps the preset recognizable.';
    case _AgentPlasticity.high:
      return 'Learns faster from future memory overlays.';
  }
}

String _labelForAddressStyle(_AgentAddressStyle style) {
  switch (style) {
    case _AgentAddressStyle.neutral:
      return 'Neutral';
    case _AgentAddressStyle.friendly:
      return 'Friendly';
    case _AgentAddressStyle.intimate:
      return 'Intimate';
  }
}

String _bodyForAddressStyle(_AgentAddressStyle style) {
  switch (style) {
    case _AgentAddressStyle.neutral:
      return 'Default, reserved, and least relational.';
    case _AgentAddressStyle.friendly:
      return 'Warm enough for daily use without overstepping.';
    case _AgentAddressStyle.intimate:
      return 'Best only when the relationship gate is intentionally high.';
  }
}

String _labelForTwinImportCorpusTypeSegment(_AgentTwinImportCorpusType type) {
  switch (type) {
    case _AgentTwinImportCorpusType.chatHistory:
      return 'CHAT HISTORY';
    case _AgentTwinImportCorpusType.fictionWork:
      return 'FICTION WORK';
  }
}

String _labelForAgentMode(_AgentMode mode) {
  switch (mode) {
    case _AgentMode.full:
      return 'Full';
    case _AgentMode.lightweight:
      return 'Lightweight';
    case _AgentMode.none:
      return 'None';
    case _AgentMode.noSoul:
      return 'No soul';
    case _AgentMode.noMemoryOrSoul:
      return 'No memory or soul';
  }
}

String _bodyForAgentMode(_AgentMode mode) {
  switch (mode) {
    case _AgentMode.full:
      return 'Full bootstrap plus soul and recalled memory.';
    case _AgentMode.lightweight:
      return 'Lighter bootstrap while keeping soul and recalled memory.';
    case _AgentMode.none:
      return 'Skip workspace bootstrap, but still use soul and recalled memory.';
    case _AgentMode.noSoul:
      return 'Keep recalled memory, but disable soul contract and turn policy.';
    case _AgentMode.noMemoryOrSoul:
      return 'Run with neither recalled memory nor soul layers.';
  }
}

String _labelForProvider(_AgentProvider provider) {
  switch (provider) {
    case _AgentProvider.openai:
      return 'OpenAI';
    case _AgentProvider.anthropic:
      return 'Anthropic';
    case _AgentProvider.custom:
      return 'Custom';
  }
}

String _labelForProtocol(_AgentProtocol protocol) {
  switch (protocol) {
    case _AgentProtocol.openAiCompatible:
      return 'OpenAI compatible';
    case _AgentProtocol.anthropic:
      return 'Anthropic';
  }
}

String _labelForReasoningEffort(_AgentReasoningEffort effort) {
  switch (effort) {
    case _AgentReasoningEffort.low:
      return 'Low';
    case _AgentReasoningEffort.medium:
      return 'Medium';
    case _AgentReasoningEffort.high:
      return 'High';
    case _AgentReasoningEffort.xhigh:
      return 'XHigh';
  }
}

String _labelForVoiceSummary(_AgentVoiceSummary summary) {
  switch (summary) {
    case _AgentVoiceSummary.calmConcrete:
      return 'Calm and concrete';
    case _AgentVoiceSummary.decisiveTechnical:
      return 'Decisive and technical';
    case _AgentVoiceSummary.warmReflective:
      return 'Warm and reflective';
  }
}

String _labelForVerbosity(_AgentVerbosity verbosity) {
  switch (verbosity) {
    case _AgentVerbosity.concise:
      return 'Concise';
    case _AgentVerbosity.balanced:
      return 'Balanced';
    case _AgentVerbosity.detailed:
      return 'Detailed';
  }
}

String _labelForRelationshipStyle(_AgentRelationshipStyle style) {
  switch (style) {
    case _AgentRelationshipStyle.collaborative:
      return 'Collaborative';
    case _AgentRelationshipStyle.direct:
      return 'Direct';
    case _AgentRelationshipStyle.supportive:
      return 'Supportive';
  }
}

String _labelForRiskTolerance(_AgentRiskTolerance tolerance) {
  switch (tolerance) {
    case _AgentRiskTolerance.conservative:
      return 'Conservative';
    case _AgentRiskTolerance.balanced:
      return 'Balanced';
    case _AgentRiskTolerance.assertive:
      return 'Assertive';
  }
}

String _labelForToolUseBias(_AgentToolUseBias bias) {
  switch (bias) {
    case _AgentToolUseBias.verifyFirst:
      return 'Verify first';
    case _AgentToolUseBias.executionFirst:
      return 'Execution first';
    case _AgentToolUseBias.minimalTools:
      return 'Minimal tools';
  }
}

String _labelForSoulPresetFromRaw(String raw) =>
    _labelForSoulPreset(_agentSoulPresetFromRaw(raw));

String _labelForAgentModeFromRaw(String raw) =>
    _labelForAgentMode(_agentModeFromRaw(raw));

String _labelForProviderFromRaw(String raw) =>
    _labelForProvider(_agentProviderFromRaw(raw));

_AgentSoulPreset _agentSoulPresetFromRaw(String raw) {
  for (final preset in _AgentSoulPreset.values) {
    if (preset.name.toLowerCase() == raw.trim().toLowerCase()) {
      return preset;
    }
  }
  return _AgentSoulPreset.steady;
}

_AgentMode _agentModeFromRaw(String raw) {
  for (final mode in _AgentMode.values) {
    if (mode.name.toLowerCase() == raw.trim().toLowerCase()) {
      return mode;
    }
  }
  return _AgentMode.full;
}

_AgentProvider _agentProviderFromRaw(String raw) {
  for (final provider in _AgentProvider.values) {
    if (provider.name.toLowerCase() == raw.trim().toLowerCase()) {
      return provider;
    }
  }
  return _AgentProvider.openai;
}

void _applyTwinImportCorpusPreset(
  _AgentDraft draft,
  _AgentTwinImportCorpusType type, {
  bool alternate = false,
}) {
  draft.twinImportCorpusType = type;
  switch (type) {
    case _AgentTwinImportCorpusType.chatHistory:
      draft
        ..twinImportFileName = alternate
            ? 'voice_notes.chatlab.json'
            : 'aster_chat_export.chatlab.jsonl'
        ..twinImportFormatLabel = alternate ? 'ChatLab JSON' : 'ChatLab JSONL'
        ..twinImportSourceMeta = alternate
            ? '412 turns · 2 participants'
            : '2,481 turns · 2 participants'
        ..twinImportRelationFocus = 'Ties with Fish';
      break;
    case _AgentTwinImportCorpusType.fictionWork:
      draft
        ..twinImportFileName = alternate
            ? 'chapter_bundle.normalized.json'
            : 'aster_novel.normalized.json'
        ..twinImportFormatLabel = 'Normalized fiction'
        ..twinImportSourceMeta = alternate
            ? '24 scenes · 7 characters'
            : '18 scenes · 6 characters'
        ..twinImportRelationFocus = 'Aster ↔ Fish';
      break;
  }
}

void _applyProviderPreset(_AgentDraft draft, _AgentProvider provider) {
  switch (provider) {
    case _AgentProvider.openai:
      draft
        ..baseUrl = 'https://api.openai.com/v1'
        ..model = 'gpt-4o-mini'
        ..protocol = _AgentProtocol.openAiCompatible;
      break;
    case _AgentProvider.anthropic:
      draft
        ..baseUrl = 'https://api.anthropic.com'
        ..model = 'claude-3-7-sonnet'
        ..protocol = _AgentProtocol.anthropic;
      break;
    case _AgentProvider.custom:
      draft
        ..baseUrl = 'https://api.example.com/v1'
        ..model = 'custom-model'
        ..protocol = _AgentProtocol.openAiCompatible;
      break;
  }
}

class _AgentsSettingsPage extends StatefulWidget {
  const _AgentsSettingsPage({
    super.key,
    required this.onBack,
    required this.backLabel,
    this.debugBridge,
  });

  final VoidCallback onBack;
  final String backLabel;
  final OpenCrayHostBridge? debugBridge;

  @override
  State<_AgentsSettingsPage> createState() => _AgentsSettingsPageState();
}

class _AgentsSettingsPageState extends State<_AgentsSettingsPage> {
  List<_SavedAgent> _savedAgents = _buildPrototypeAgents();
  bool _usesHostBackedAgents = false;
  bool _isLoadingAgents = false;
  bool _isSelectingAgent = false;

  @override
  void initState() {
    super.initState();
    _loadAgentsFromBridge();
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OpenCrayPageHeader(
            leading: widget.backLabel.isNotEmpty
                ? _BackLink(onTap: widget.onBack, label: widget.backLabel)
                : null,
            title: 'Agents',
            summary: 'Reuse a saved agent or create a new one.',
            bottomGap: 12,
          ),
          SizedBox(
            height: 34,
            child: Row(
              children: [
                Text(
                  _isLoadingAgents
                      ? 'Loading agents...'
                      : '${_savedAgents.length} saved agents',
                  style: context.settingsText.body.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const Spacer(),
                _AgentPillButton(
                  key: const ValueKey<String>('settings-agents-new-agent'),
                  label: 'New agent',
                  onTap: _openNewAgent,
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          for (int index = 0; index < _savedAgents.length; index++) ...[
            _AgentListCard(
              agent: _savedAgents[index],
              onTap: () => _handleSavedAgentTap(_savedAgents[index]),
            ),
            if (index < _savedAgents.length - 1) const SizedBox(height: 10),
          ],
        ],
      ),
    );
  }

  Future<void> _openNewAgent() async {
    final _SavedAgent? savedAgent = await _pushAgentPage<_SavedAgent>(
      (BuildContext context) => _AgentCreatePage(
        draft: _AgentDraft.prototype(),
        backLabel: 'Agents',
        debugBridge: widget.debugBridge,
        persistToHost: _usesHostBackedAgents,
      ),
    );
    if (!mounted || savedAgent == null) {
      return;
    }
    if (_usesHostBackedAgents) {
      await _loadAgentsFromBridge();
      return;
    }
    setState(() {
      _savedAgents.insert(0, savedAgent);
    });
  }

  Future<void> _handleSavedAgentTap(_SavedAgent agent) async {
    if (_usesHostBackedAgents && agent.agentId != null) {
      await _selectAgent(agent);
      return;
    }
    await _reuseAgent(agent);
  }

  Future<void> _reuseAgent(_SavedAgent agent) async {
    final _SavedAgent? savedAgent = await _pushAgentPage<_SavedAgent>(
      (BuildContext context) => _AgentCreatePage(
        draft: agent.toDraft(),
        backLabel: 'Agents',
        debugBridge: widget.debugBridge,
        persistToHost: false,
      ),
    );
    if (!mounted || savedAgent == null) {
      return;
    }
    setState(() {
      _savedAgents.insert(0, savedAgent);
    });
  }

  Future<T?> _pushAgentPage<T>(WidgetBuilder builder) {
    return Navigator.of(
      context,
    ).push<T>(openCrayHorizontalPageRoute<T>(builder: builder));
  }

  Future<void> _selectAgent(_SavedAgent agent) async {
    final bridge = widget.debugBridge;
    final agentId = agent.agentId;
    if (bridge == null || agentId == null || _isSelectingAgent) {
      return;
    }
    try {
      setState(() {
        _isSelectingAgent = true;
      });
      await bridge.selectAgent(agentId);
      if (!mounted) {
        return;
      }
      await _loadAgentsFromBridge();
    } catch (error) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Failed to select agent: $error')));
    } finally {
      if (mounted) {
        setState(() {
          _isSelectingAgent = false;
        });
      }
    }
  }

  Future<void> _loadAgentsFromBridge() async {
    final bridge = widget.debugBridge;
    if (bridge == null) {
      return;
    }
    setState(() {
      _isLoadingAgents = true;
    });
    try {
      final List<OpenCrayAgentSnapshot> snapshots = await bridge.listAgents();
      final OpenCrayAgentSnapshot? activeAgent = await bridge.loadActiveAgent();
      if (!mounted) {
        return;
      }
      final activeAgentId = activeAgent?.agentId;
      setState(() {
        _savedAgents = snapshots
            .map(
              (OpenCrayAgentSnapshot snapshot) => _SavedAgent.fromSnapshot(
                activeAgentId == null
                    ? snapshot
                    : snapshot.copyWith(
                        isActive: snapshot.agentId == activeAgentId,
                      ),
              ),
            )
            .toList(growable: false);
        _usesHostBackedAgents = true;
        _isLoadingAgents = false;
      });
    } catch (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _savedAgents = _buildPrototypeAgents();
        _usesHostBackedAgents = false;
        _isLoadingAgents = false;
      });
    }
  }
}

class _AgentCreatePage extends StatefulWidget {
  const _AgentCreatePage({
    required this.draft,
    required this.backLabel,
    this.debugBridge,
    this.persistToHost = false,
  });

  final _AgentDraft draft;
  final String backLabel;
  final OpenCrayHostBridge? debugBridge;
  final bool persistToHost;

  @override
  State<_AgentCreatePage> createState() => _AgentCreatePageState();
}

class _AgentCreatePageState extends State<_AgentCreatePage> {
  late final TextEditingController _nameController = TextEditingController(
    text: widget.draft.agentName,
  );
  late final TextEditingController _callsYouController = TextEditingController(
    text: widget.draft.callsYou,
  );
  late final TextEditingController _descriptionController =
      TextEditingController(text: widget.draft.baseDescription);
  bool _isSubmitting = false;
  _AgentCreateStatus _createStatus = _AgentCreateStatus.clean;
  String? _createStatusDetail;

  @override
  void dispose() {
    _nameController.dispose();
    _callsYouController.dispose();
    _descriptionController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final _AgentDraft draft = widget.draft;
    return Scaffold(
      backgroundColor: context.palette.shellBackground,
      body: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              OpenCrayPageHeader(
                leading: _BackLink(
                  onTap: () => Navigator.of(context).pop(),
                  label: widget.backLabel,
                ),
                title: 'Create agent',
                bottomGap: 10,
              ),
              _AgentCreateStatusCard(
                key: const ValueKey<String>('agent-create-status-card'),
                status: _createStatus,
                detail: _createStatusDetail,
                draft: draft,
              ),
              const SizedBox(height: 10),
              _SettingsCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      'Core identity',
                      style: context.settingsText.cardTitle,
                    ),
                    const SizedBox(height: 8),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: 92,
                          child: _AgentAvatarField(
                            draft: draft,
                            onTap: _openAvatarPage,
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: _PrototypeField(
                            label: 'Agent name',
                            controller: _nameController,
                            hintText: 'Agent name',
                            onChanged: (String value) {
                              draft.agentName = value;
                              _markDraftEdited();
                            },
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: _PrototypeField(
                            label: 'How it calls you',
                            controller: _callsYouController,
                            hintText: 'Optional',
                            onChanged: (String value) {
                              draft.callsYou = value;
                              _markDraftEdited();
                            },
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: _PrototypeSelectionField(
                            label: 'Address style',
                            title: draft.addressStyleLabel,
                            onTap: _openAddressStylePage,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    _PrototypeField(
                      label: 'Base description',
                      controller: _descriptionController,
                      hintText: 'Optional',
                      minLines: 3,
                      maxLines: 3,
                      onChanged: (String value) {
                        draft.baseDescription = value;
                        _markDraftEdited();
                      },
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 10),
              _SettingsCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      'Soul & runtime',
                      style: context.settingsText.cardTitle,
                    ),
                    const SizedBox(height: 8),
                    _InteractiveSegmentedSelector(
                      labels: _AgentSoulPreset.values
                          .map((_AgentSoulPreset preset) => preset.name)
                          .toList(growable: false),
                      selectedId: draft.soulPreset.name,
                      labelBuilder: (String value) =>
                          _labelForSoulPresetSegment(
                            _AgentSoulPreset.values.firstWhere(
                              (_AgentSoulPreset preset) => preset.name == value,
                            ),
                          ),
                      onSelected: (String value) {
                        setState(() {
                          draft.soulPreset = _AgentSoulPreset.values.firstWhere(
                            (_AgentSoulPreset preset) => preset.name == value,
                          );
                          _createStatus = _AgentCreateStatus.edited;
                          _createStatusDetail = null;
                        });
                      },
                    ),
                    const SizedBox(height: 4),
                    _AgentSummaryLinkRow(
                      key: const ValueKey<String>('agent-create-twin-import'),
                      title: 'Twin import',
                      value: draft.twinImportSummary,
                      onTap: _openTwinImportPage,
                    ),
                    Divider(height: 1, color: context.palette.divider),
                    _AgentSummaryLinkRow(
                      title: 'Plasticity',
                      value: draft.plasticityLabel,
                      onTap: _openPlasticityPage,
                    ),
                    Divider(height: 1, color: context.palette.divider),
                    _AgentSummaryLinkRow(
                      title: 'Mode',
                      value: draft.modeLabel,
                      onTap: _openModePage,
                    ),
                    Divider(height: 1, color: context.palette.divider),
                    _AgentSummaryLinkRow(
                      title: 'Media samples',
                      value: draft.mediaSummary,
                      onTap: _openMediaPage,
                    ),
                    Divider(height: 1, color: context.palette.divider),
                    _AgentSummaryLinkRow(
                      title: 'Model',
                      value: draft.modelSummary,
                      onTap: _openModelPage,
                    ),
                    Divider(height: 1, color: context.palette.divider),
                    _AgentSummaryLinkRow(
                      title: 'Advanced defaults',
                      value: draft.advancedSummary,
                      onTap: _openAdvancedDefaultsPage,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              Text(
                'Workspace is assigned automatically on creation.',
                style: context.settingsText.selectionMeta.copyWith(
                  height: 16 / 12,
                ),
              ),
              const SizedBox(height: 8),
              _AgentPrimaryButton(
                label: _isSubmitting ? 'Creating...' : 'Create agent',
                onTap: _submitAgent,
              ),
            ],
          ),
        ),
      ),
    );
  }

  _AgentDraft _syncedDraft() {
    widget.draft
      ..agentName = _nameController.text
      ..callsYou = _callsYouController.text
      ..baseDescription = _descriptionController.text;
    return widget.draft;
  }

  void _markDraftEdited() {
    setState(() {
      if (_createStatus != _AgentCreateStatus.saving) {
        _createStatus = _AgentCreateStatus.edited;
        _createStatusDetail = null;
      }
    });
  }

  Future<void> _submitAgent() async {
    if (_isSubmitting) {
      return;
    }
    final _AgentDraft draft = _syncedDraft();
    if (!widget.persistToHost || widget.debugBridge == null) {
      setState(() {
        _createStatus = _AgentCreateStatus.saved;
        _createStatusDetail = 'Local draft ready';
      });
      Navigator.of(context).pop(_SavedAgent.fromDraft(draft));
      return;
    }
    try {
      setState(() {
        _isSubmitting = true;
        _createStatus = _AgentCreateStatus.saving;
        _createStatusDetail = null;
      });
      final snapshot = await widget.debugBridge!.createAgent(
        draft.toHostCreateRequest(),
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _createStatus = _AgentCreateStatus.saved;
        _createStatusDetail = 'Saved as ${snapshot.displayName}';
      });
      await Future<void>.delayed(
        OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      );
      if (!mounted) {
        return;
      }
      Navigator.of(context).pop(_SavedAgent.fromSnapshot(snapshot));
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isSubmitting = false;
        _createStatus = _AgentCreateStatus.failed;
        _createStatusDetail = 'Failed to create agent: $error';
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Failed to create agent: $error')));
    }
  }

  Future<void> _openAvatarPage() async {
    await _pushEditorPage(
      (BuildContext context) =>
          _AgentAvatarPage(draft: _syncedDraft(), bridge: widget.debugBridge),
    );
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _openModePage() async {
    await _pushEditorPage(
      (BuildContext context) => _AgentModePage(draft: _syncedDraft()),
    );
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _openTwinImportPage() async {
    await _pushEditorPage(
      (BuildContext context) => _AgentTwinImportPage(draft: _syncedDraft()),
    );
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _openPlasticityPage() async {
    await _pushEditorPage(
      (BuildContext context) => _AgentPlasticityPage(draft: _syncedDraft()),
    );
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _openAddressStylePage() async {
    await _pushEditorPage(
      (BuildContext context) => _AgentAddressStylePage(draft: _syncedDraft()),
    );
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _openMediaPage() async {
    await _pushEditorPage(
      (BuildContext context) => _AgentMediaSamplesPage(
        draft: _syncedDraft(),
        bridge: widget.debugBridge,
      ),
    );
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _openModelPage() async {
    await _pushEditorPage(
      (BuildContext context) => _AgentModelPage(draft: _syncedDraft()),
    );
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _openAdvancedDefaultsPage() async {
    await _pushEditorPage(
      (BuildContext context) =>
          _AgentAdvancedDefaultsPage(draft: _syncedDraft()),
    );
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _pushEditorPage(WidgetBuilder builder) {
    return Navigator.of(
      context,
    ).push<void>(openCrayHorizontalPageRoute<void>(builder: builder));
  }
}

class _AgentTwinImportPage extends StatefulWidget {
  const _AgentTwinImportPage({required this.draft});

  final _AgentDraft draft;

  @override
  State<_AgentTwinImportPage> createState() => _AgentTwinImportPageState();
}

class _AgentTwinImportPageState extends State<_AgentTwinImportPage> {
  @override
  Widget build(BuildContext context) {
    final _AgentDraft draft = widget.draft;
    return _AgentSubpageScaffold(
      title: 'Twin import',
      subtitle:
          'Initialize soul and memory from chat or fiction. Pick who to clone and who you are in the relationship.',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildSourceCard(draft),
          const SizedBox(height: 10),
          _buildIdentityMappingCard(draft),
          const SizedBox(height: 10),
          _buildInitializeCard(),
          const SizedBox(height: 10),
          _AgentPrimaryButton(
            key: const ValueKey<String>('agent-twin-import-run-draft'),
            label: draft.twinImportConfigured
                ? 'Refresh import draft'
                : 'Run import draft',
            onTap: _runImportDraft,
          ),
        ],
      ),
    );
  }

  Widget _buildSourceCard(_AgentDraft draft) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Source', style: context.settingsText.cardTitle),
          const SizedBox(height: 10),
          _InteractiveSegmentedSelector(
            labels: _AgentTwinImportCorpusType.values
                .map((_AgentTwinImportCorpusType type) => type.name)
                .toList(growable: false),
            selectedId: draft.twinImportCorpusType.name,
            labelBuilder: (String value) =>
                _labelForTwinImportCorpusTypeSegment(
                  _AgentTwinImportCorpusType.values.firstWhere(
                    (_AgentTwinImportCorpusType type) => type.name == value,
                  ),
                ),
            onSelected: _selectCorpusType,
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: <Widget>[
              const _AgentSoftBadge(label: 'AUTO-DETECTED'),
              _TwinImportNeutralBadge(label: draft.twinImportFormatLabel),
            ],
          ),
          const SizedBox(height: 10),
          Container(
            decoration: BoxDecoration(
              color: context.palette.surfaceSubtle,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: context.palette.divider),
            ),
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 36,
                      height: 36,
                      decoration: BoxDecoration(
                        color: context.palette.surfaceMuted,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      alignment: Alignment.center,
                      child: Icon(
                        Icons.description_outlined,
                        size: 18,
                        color: context.palette.textSecondary,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            draft.twinImportFileName,
                            style: context.settingsText.rowTitle.copyWith(
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            draft.twinImportSourceMeta,
                            style: context.settingsText.selectionMeta,
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    _InlineTextAction(
                      label: 'Replace',
                      onTap: _replaceSourceSample,
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Text(
                  'Supported now: ChatLab JSON / JSONL and normalized JSON.',
                  style: context.settingsText.selectionMeta,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildIdentityMappingCard(_AgentDraft draft) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Identity mapping', style: context.settingsText.cardTitle),
          const SizedBox(height: 2),
          _AgentSummaryLinkRow(
            title: 'Clone target',
            value: draft.twinImportCloneTarget,
            onTap: _selectCloneTarget,
          ),
          Divider(height: 1, color: context.palette.divider),
          _AgentSummaryLinkRow(
            title: 'You are cast as',
            value: draft.twinImportCastAs,
            onTap: _selectCastAs,
          ),
          Divider(height: 1, color: context.palette.divider),
          _AgentSummaryLinkRow(
            title: 'Relation focus',
            value: draft.twinImportRelationFocus,
            onTap: _selectRelationFocus,
          ),
          const SizedBox(height: 6),
          Text(
            'Others stay backgrounded unless they change this relationship.',
            style: context.settingsText.selectionMeta,
          ),
        ],
      ),
    );
  }

  Widget _buildInitializeCard() {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Initialize', style: context.settingsText.cardTitle),
          SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _TwinImportSignalPill(label: 'SOUL', active: true),
              ),
              SizedBox(width: 8),
              Expanded(child: _TwinImportSignalPill(label: 'RELATION')),
              SizedBox(width: 8),
              Expanded(child: _TwinImportSignalPill(label: 'MEMORY')),
            ],
          ),
          SizedBox(height: 10),
          Text(
            'Voice, relationship behavior, and memory seeds are drafted together for review before save.',
            style: context.settingsText.selectionMeta,
          ),
        ],
      ),
    );
  }

  void _selectCorpusType(String rawValue) {
    final _AgentTwinImportCorpusType type = _AgentTwinImportCorpusType.values
        .firstWhere(
          (_AgentTwinImportCorpusType value) => value.name == rawValue,
        );
    setState(() {
      _applyTwinImportCorpusPreset(widget.draft, type);
      widget.draft.twinImportConfigured = false;
    });
  }

  void _replaceSourceSample() {
    final bool alternate;
    switch (widget.draft.twinImportCorpusType) {
      case _AgentTwinImportCorpusType.chatHistory:
        alternate =
            widget.draft.twinImportFileName ==
            'aster_chat_export.chatlab.jsonl';
        break;
      case _AgentTwinImportCorpusType.fictionWork:
        alternate =
            widget.draft.twinImportFileName == 'aster_novel.normalized.json';
        break;
    }
    setState(() {
      _applyTwinImportCorpusPreset(
        widget.draft,
        widget.draft.twinImportCorpusType,
        alternate: alternate,
      );
      widget.draft.twinImportConfigured = false;
    });
  }

  Future<void> _selectCloneTarget() async {
    final List<String> options = <String>{
      widget.draft.twinImportCloneTarget,
      'Aster',
      'Nova',
      if (widget.draft.twinImportCorpusType ==
          _AgentTwinImportCorpusType.fictionWork)
        'Lead'
      else
        'Muse',
    }.toList(growable: false);
    final String? value = await _showSelectionSheet<String>(
      context,
      title: 'Clone target',
      selectedValue: widget.draft.twinImportCloneTarget,
      options: options,
      labelBuilder: (String value) => value,
    );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft
        ..twinImportCloneTarget = value
        ..twinImportConfigured = false;
    });
  }

  Future<void> _selectCastAs() async {
    final List<String> options = <String>{
      widget.draft.twinImportCastAs,
      'Fish',
      'User',
      if (widget.draft.twinImportCorpusType ==
          _AgentTwinImportCorpusType.fictionWork)
        'Second lead'
      else
        'Partner',
    }.toList(growable: false);
    final String? value = await _showSelectionSheet<String>(
      context,
      title: 'You are cast as',
      selectedValue: widget.draft.twinImportCastAs,
      options: options,
      labelBuilder: (String value) => value,
    );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft
        ..twinImportCastAs = value
        ..twinImportConfigured = false;
    });
  }

  Future<void> _selectRelationFocus() async {
    final List<String> options = <String>{
      widget.draft.twinImportRelationFocus,
      if (widget.draft.twinImportCorpusType ==
          _AgentTwinImportCorpusType.fictionWork)
        'Aster ↔ Fish'
      else
        'Ties with Fish',
      'Family context',
      if (widget.draft.twinImportCorpusType ==
          _AgentTwinImportCorpusType.fictionWork)
        'Rival route'
      else
        'Work context',
    }.toList(growable: false);
    final String? value = await _showSelectionSheet<String>(
      context,
      title: 'Relation focus',
      selectedValue: widget.draft.twinImportRelationFocus,
      options: options,
      labelBuilder: (String value) => value,
    );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft
        ..twinImportRelationFocus = value
        ..twinImportConfigured = false;
    });
  }

  void _runImportDraft() {
    widget.draft.twinImportConfigured = true;
    Navigator.of(context).pop();
  }
}

class _AgentModePage extends StatefulWidget {
  const _AgentModePage({required this.draft});

  final _AgentDraft draft;

  @override
  State<_AgentModePage> createState() => _AgentModePageState();
}

class _AgentModePageState extends State<_AgentModePage> {
  @override
  Widget build(BuildContext context) {
    return _AgentSubpageScaffold(
      title: 'Mode',
      subtitle:
          'Choose how much bootstrap, soul, and recalled memory this agent should use at runtime.',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'Context mode',
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 10),
                for (
                  int index = 0;
                  index < _AgentMode.values.length;
                  index++
                ) ...[
                  _AgentChoiceTile(
                    title: _labelForAgentMode(_AgentMode.values[index]),
                    body: _bodyForAgentMode(_AgentMode.values[index]),
                    selected: widget.draft.mode == _AgentMode.values[index],
                    selectedLabel: 'Selected',
                    onTap: () {
                      setState(() {
                        widget.draft.mode = _AgentMode.values[index];
                      });
                    },
                  ),
                  if (index < _AgentMode.values.length - 1)
                    const SizedBox(height: 8),
                ],
              ],
            ),
          ),
          const SizedBox(height: 10),
          const _AgentInfoCard(
            title: 'What changes',
            body:
                'Full / Lightweight / None keep soul and recalled memory enabled, but change how much bootstrap context is injected. No soul keeps memory recall but removes soul behavior layers. No memory or soul strips both for the leanest runtime posture.',
          ),
        ],
      ),
    );
  }
}

class _AgentPlasticityPage extends StatefulWidget {
  const _AgentPlasticityPage({required this.draft});

  final _AgentDraft draft;

  @override
  State<_AgentPlasticityPage> createState() => _AgentPlasticityPageState();
}

class _AgentPlasticityPageState extends State<_AgentPlasticityPage> {
  @override
  Widget build(BuildContext context) {
    return _AgentSubpageScaffold(
      title: 'Plasticity',
      subtitle:
          'Control how readily the soul adapts after new memory and interaction signals.',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'Plasticity level',
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 10),
                for (
                  int index = 0;
                  index < _AgentPlasticity.values.length;
                  index++
                ) ...[
                  _AgentChoiceTile(
                    title: _labelForPlasticity(_AgentPlasticity.values[index]),
                    body: _bodyForPlasticity(_AgentPlasticity.values[index]),
                    selected:
                        widget.draft.plasticity ==
                        _AgentPlasticity.values[index],
                    selectedLabel: 'Selected',
                    onTap: () {
                      setState(() {
                        widget.draft.plasticity =
                            _AgentPlasticity.values[index];
                      });
                    },
                  ),
                  if (index < _AgentPlasticity.values.length - 1)
                    const SizedBox(height: 8),
                ],
              ],
            ),
          ),
          const SizedBox(height: 10),
          const _AgentInfoCard(
            title: 'What it affects',
            body:
                'Lower plasticity keeps the core preset steadier. Higher plasticity lets later memory and interaction preferences reshape the effective soul more aggressively.',
          ),
        ],
      ),
    );
  }
}

class _AgentAddressStylePage extends StatefulWidget {
  const _AgentAddressStylePage({required this.draft});

  final _AgentDraft draft;

  @override
  State<_AgentAddressStylePage> createState() => _AgentAddressStylePageState();
}

class _AgentAddressStylePageState extends State<_AgentAddressStylePage> {
  @override
  Widget build(BuildContext context) {
    return _AgentSubpageScaffold(
      title: 'Address style',
      subtitle:
          'Choose how the agent addresses you when a personal name is not used.',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'Style options',
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 10),
                for (
                  int index = 0;
                  index < _AgentAddressStyle.values.length;
                  index++
                ) ...[
                  _AgentChoiceTile(
                    title: _labelForAddressStyle(
                      _AgentAddressStyle.values[index],
                    ),
                    body: _bodyForAddressStyle(
                      _AgentAddressStyle.values[index],
                    ),
                    selected:
                        widget.draft.addressStyle ==
                        _AgentAddressStyle.values[index],
                    selectedLabel: 'Selected',
                    onTap: () {
                      setState(() {
                        widget.draft.addressStyle =
                            _AgentAddressStyle.values[index];
                      });
                    },
                  ),
                  if (index < _AgentAddressStyle.values.length - 1)
                    const SizedBox(height: 8),
                ],
              ],
            ),
          ),
          const SizedBox(height: 10),
          const _AgentInfoCard(
            title: 'How it combines',
            body:
                'If a preferred personal name is set, that name wins first. Address style becomes the fallback tone for direct address when naming is unavailable or intentionally omitted.',
          ),
        ],
      ),
    );
  }
}

class _AgentAvatarPage extends StatefulWidget {
  const _AgentAvatarPage({required this.draft, this.bridge});

  final _AgentDraft draft;
  final OpenCrayHostBridge? bridge;

  @override
  State<_AgentAvatarPage> createState() => _AgentAvatarPageState();
}

class _AgentAvatarPageState extends State<_AgentAvatarPage> {
  @override
  Widget build(BuildContext context) {
    return _AgentSubpageScaffold(
      title: 'Avatar',
      subtitle:
          'Show the avatar on the first step. Generate one from references or upload a custom image for this agent.',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'Current avatar',
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 12),
                Container(
                  height: 184,
                  decoration: BoxDecoration(
                    color: context.palette.surfaceSubtle,
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: context.palette.divider),
                  ),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      _AgentAvatarCircle(
                        letter: widget.draft.avatarLetter,
                        colors: widget.draft.avatarColors,
                        size: 116,
                        fontSize: 42,
                      ),
                      const SizedBox(height: 10),
                      _AgentSoftBadge(label: widget.draft.avatarStatusLabel),
                    ],
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  'This avatar is visible on the create page before the agent is saved.',
                  style: context.settingsText.selectionMeta,
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Source', style: context.settingsText.cardTitle),
                const SizedBox(height: 6),
                _AgentDetailActionRow(
                  title: 'Generate from references',
                  subtitle:
                      'Use uploaded reference images to create a portrait',
                  trailingLabel: '${widget.draft.referenceImageCount} ready',
                  onTap: widget.draft.referenceImageCount <= 0
                      ? null
                      : () {
                          setState(() {
                            widget.draft.avatarSource =
                                _AgentAvatarSource.generated;
                            widget.draft.customAvatarAsset = null;
                            widget.draft.avatarColors = widget
                                .draft
                                .imageReferences
                                .first
                                .colors
                                .toList(growable: false);
                          });
                        },
                ),
                Divider(height: 1, color: context.palette.divider),
                _AgentDetailActionRow(
                  title: 'Upload custom avatar',
                  subtitle:
                      'Pick a PNG or JPG that should override the generated avatar',
                  trailingLabel: widget.draft.customAvatarUploadLabel,
                  onTap: _pickCustomAvatar,
                ),
                const SizedBox(height: 10),
                Text(
                  'A custom avatar overrides the generated one until you replace or remove it.',
                  style: context.settingsText.selectionMeta,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _pickCustomAvatar() async {
    final bridge = widget.bridge;
    if (bridge == null) {
      setState(() {
        widget.draft.avatarSource = _AgentAvatarSource.custom;
        widget.draft.customAvatarAsset = null;
        widget.draft.avatarColors = _agentGradientSets[3];
      });
      return;
    }
    try {
      final assets = await bridge.pickSettingsImageAssets();
      if (!mounted || assets.isEmpty) {
        return;
      }
      final OpenCraySettingsImageAsset selectedAsset = assets.first;
      if (assets.length > 1) {
        final fileName = selectedAsset.displayName.trim().isEmpty
            ? 'the first selected image'
            : selectedAsset.displayName.trim();
        await _showBridgeMessage(
          'Picked ${assets.length} images. Using $fileName for the avatar.',
        );
      }
      if (!mounted) {
        return;
      }
      setState(() {
        widget.draft.avatarSource = _AgentAvatarSource.custom;
        widget.draft.customAvatarAsset = selectedAsset;
        widget.draft.avatarColors = _agentGradientForSettingsAsset(
          selectedAsset,
        );
      });
    } catch (_) {
      if (!mounted) {
        return;
      }
      await _showBridgeMessage('Could not pick a custom avatar.');
    }
  }

  Future<void> _showBridgeMessage(String message) async {
    final messenger = ScaffoldMessenger.maybeOf(context);
    messenger?.hideCurrentSnackBar();
    messenger?.showSnackBar(SnackBar(content: Text(message)));
    try {
      await widget.bridge?.showNativeToast(message);
    } catch (_) {}
  }
}

class _AgentSubpageScaffold extends StatelessWidget {
  const _AgentSubpageScaffold({
    required this.title,
    required this.subtitle,
    required this.child,
  });

  final String title;
  final String subtitle;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: context.palette.shellBackground,
      body: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              OpenCrayPageHeader(
                leading: _BackLink(
                  onTap: () => Navigator.of(context).pop(),
                  label: 'Create agent',
                ),
                title: title,
                summary: subtitle,
                bottomGap: 10,
              ),
              child,
            ],
          ),
        ),
      ),
    );
  }
}

class _AgentMediaSamplesPage extends StatefulWidget {
  const _AgentMediaSamplesPage({required this.draft, this.bridge});

  final _AgentDraft draft;
  final OpenCrayHostBridge? bridge;

  @override
  State<_AgentMediaSamplesPage> createState() => _AgentMediaSamplesPageState();
}

class _AgentMediaSamplesPageState extends State<_AgentMediaSamplesPage> {
  @override
  Widget build(BuildContext context) {
    return _AgentSubpageScaffold(
      title: 'Media samples',
      subtitle:
          'Optional references for future voice and visual setup. Safe to skip for text-only agents.',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildAudioCard(),
          const SizedBox(height: 10),
          _buildImageCard(),
        ],
      ),
    );
  }

  Widget _buildAudioCard() {
    if (!widget.draft.hasAudioSample) {
      return _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Audio sample', style: context.settingsText.cardTitle),
            const SizedBox(height: 12),
            _AgentUploadZone(
              title: 'Upload audio',
              body: 'Tap to choose a voice sample or drop a clip here.',
              buttonLabel: 'Select file',
              onTap: () {
                setState(() {
                  widget.draft
                    ..hasAudioSample = true
                    ..audioFileName = 'voice-aster-intro.m4a'
                    ..audioDurationSeconds = 32
                    ..audioFileSizeLabel = '1.8 MB';
                });
              },
            ),
            const SizedBox(height: 12),
            Text(
              'Use one clean clip to hint at timbre and cadence.',
              style: context.settingsText.selectionMeta,
            ),
          ],
        ),
      );
    }
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'Audio sample',
                  style: context.settingsText.cardTitle,
                ),
              ),
              const _AgentSoftBadge(label: 'Attached'),
            ],
          ),
          const SizedBox(height: 12),
          Container(
            decoration: BoxDecoration(
              color: context.palette.surfaceSubtle,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: context.palette.divider),
            ),
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        widget.draft.audioFileName,
                        style: context.settingsText.rowTitle.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    Text(
                      '${widget.draft.audioDurationSeconds} s',
                      style: context.settingsText.selectionMeta,
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Container(
                  height: 56,
                  decoration: BoxDecoration(
                    color: context.palette.surfaceMuted,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 16,
                  ),
                  child: Row(
                    children: const [
                      _AgentPlayDot(),
                      SizedBox(width: 8),
                      _AgentWaveBar(height: 24),
                      SizedBox(width: 6),
                      _AgentWaveBar(height: 14),
                      SizedBox(width: 6),
                      _AgentWaveBar(height: 30),
                      SizedBox(width: 6),
                      _AgentWaveBar(height: 18),
                      SizedBox(width: 6),
                      _AgentWaveBar(height: 26),
                      SizedBox(width: 6),
                      _AgentWaveBar(height: 12),
                      SizedBox(width: 6),
                      _AgentWaveBar(height: 22),
                      Spacer(),
                    ],
                  ),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    _AgentTertiaryButton(label: 'Preview', onTap: () {}),
                    const SizedBox(width: 8),
                    _AgentMutedButton(
                      label: 'Replace',
                      onTap: () {
                        setState(() {
                          widget.draft
                            ..audioFileName = 'voice-agent-reference.m4a'
                            ..audioDurationSeconds = 27
                            ..audioFileSizeLabel = '1.6 MB';
                        });
                      },
                    ),
                    const Spacer(),
                    Text(
                      widget.draft.audioFileSizeLabel,
                      style: context.settingsText.selectionMeta,
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Text(
            'This sample is stored as a reference clip and can be replaced later.',
            style: context.settingsText.selectionMeta,
          ),
        ],
      ),
    );
  }

  Widget _buildImageCard() {
    final List<_AgentImageReference> images = widget.draft.imageReferences;
    if (images.isEmpty) {
      return _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Image references',
              style: context.settingsText.cardTitle,
            ),
            const SizedBox(height: 12),
            _AgentUploadZone(
              title: 'Upload images',
              body:
                  'Tap to choose one or more reference images or drop them here.',
              buttonLabel: 'Select images',
              onTap: _addImageReference,
            ),
            const SizedBox(height: 12),
            Text(
              'Portrait, outfit, and mood board images can be uploaded together.',
              style: context.settingsText.selectionMeta,
            ),
          ],
        ),
      );
    }
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'Image references',
                  style: context.settingsText.cardTitle,
                ),
              ),
              _AgentSoftBadge(label: '${images.length} images'),
            ],
          ),
          const SizedBox(height: 12),
          Container(
            decoration: BoxDecoration(
              color: context.palette.surfaceSubtle,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: context.palette.divider),
            ),
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  '${images.length} labeled references',
                  style: context.settingsText.rowSubtitle.copyWith(
                    color: context.palette.textSecondary,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 10),
                SizedBox(
                  height: 104,
                  child: ListView.separated(
                    scrollDirection: Axis.horizontal,
                    itemCount: images.length,
                    separatorBuilder: (_, __) => const SizedBox(width: 8),
                    itemBuilder: (BuildContext context, int index) {
                      return _AgentReferenceCard(image: images[index]);
                    },
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              _AgentMutedButton(label: 'Preview all', onTap: _previewAllImages),
              const SizedBox(width: 8),
              _AgentTertiaryButton(
                label: 'Add more',
                onTap: _addImageReference,
              ),
              const Spacer(),
              Text(
                '${images.length} images',
                style: context.settingsText.selectionMeta,
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _previewAllImages() async {
    final List<_AgentImageReference> images = widget.draft.imageReferences;
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: context.palette.surface,
      sheetAnimationStyle: OpenCrayMotion.sheetAnimationStyle(context),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (BuildContext context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'Image references',
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 10,
                  runSpacing: 10,
                  children: images
                      .map(
                        (_AgentImageReference image) => SizedBox(
                          width: 100,
                          child: _AgentReferenceCard(image: image),
                        ),
                      )
                      .toList(growable: false),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Future<void> _addImageReference() async {
    final bridge = widget.bridge;
    if (bridge == null) {
      await _addPrototypeImageReference();
      return;
    }
    try {
      final importedAssets = await bridge.pickSettingsImageAssets();
      if (!mounted || importedAssets.isEmpty) {
        return;
      }
      await _addImportedImageReferences(importedAssets);
    } catch (_) {
      if (!mounted) {
        return;
      }
      await _showBridgeMessage('Could not add image references.');
    }
  }

  Future<void> _addPrototypeImageReference() async {
    final int nextIndex = widget.draft.imageReferences.length;
    final List<Color> colors =
        _agentGradientSets[nextIndex % _agentGradientSets.length];
    final String? label = await _showImageNamingDialog(context, colors: colors);
    if (!mounted || label == null) {
      return;
    }
    setState(() {
      widget.draft.imageReferences = <_AgentImageReference>[
        ...widget.draft.imageReferences,
        _AgentImageReference(
          id: 'image-$nextIndex',
          label: label,
          colors: colors,
        ),
      ];
      if (widget.draft.avatarSource == _AgentAvatarSource.generated &&
          widget.draft.imageReferences.isNotEmpty) {
        widget.draft.avatarColors = widget.draft.imageReferences.first.colors
            .toList(growable: false);
      }
    });
  }

  Future<void> _addImportedImageReferences(
    List<OpenCraySettingsImageAsset> importedAssets,
  ) async {
    final Set<String> existingAssetIds = widget.draft.imageReferences
        .map((image) => image.settingsAsset?.assetId ?? '')
        .where((String assetId) => assetId.isNotEmpty)
        .toSet();
    final freshAssets = importedAssets
        .where(
          (OpenCraySettingsImageAsset asset) =>
              asset.assetId.trim().isEmpty ||
              !existingAssetIds.contains(asset.assetId),
        )
        .toList(growable: false);
    if (freshAssets.isEmpty) {
      await _showBridgeMessage('Those images are already attached.');
      return;
    }
    List<_AgentImageReference> nextReferences;
    if (freshAssets.length == 1) {
      final OpenCraySettingsImageAsset asset = freshAssets.single;
      final String? label = await _showImageNamingDialog(
        context,
        colors: _agentGradientForSettingsAsset(asset),
      );
      if (!mounted || label == null) {
        return;
      }
      nextReferences = <_AgentImageReference>[
        _agentImageReferenceFromSettingsAsset(asset, label: label),
      ];
    } else {
      nextReferences = freshAssets
          .map(_agentImageReferenceFromSettingsAsset)
          .toList(growable: false);
    }
    if (!mounted || nextReferences.isEmpty) {
      return;
    }
    setState(() {
      widget.draft.imageReferences = <_AgentImageReference>[
        ...widget.draft.imageReferences,
        ...nextReferences,
      ];
      if (widget.draft.avatarSource == _AgentAvatarSource.generated &&
          widget.draft.imageReferences.isNotEmpty) {
        widget.draft.avatarColors = widget.draft.imageReferences.first.colors
            .toList(growable: false);
      }
    });
    final skippedCount = importedAssets.length - freshAssets.length;
    if (skippedCount > 0) {
      await _showBridgeMessage(
        skippedCount == 1
            ? '1 image was already attached.'
            : '$skippedCount images were already attached.',
      );
    }
  }

  Future<void> _showBridgeMessage(String message) async {
    final messenger = ScaffoldMessenger.maybeOf(context);
    messenger?.hideCurrentSnackBar();
    messenger?.showSnackBar(SnackBar(content: Text(message)));
    try {
      await widget.bridge?.showNativeToast(message);
    } catch (_) {}
  }
}

class _AgentModelPage extends StatefulWidget {
  const _AgentModelPage({required this.draft});

  final _AgentDraft draft;

  @override
  State<_AgentModelPage> createState() => _AgentModelPageState();
}

class _AgentModelPageState extends State<_AgentModelPage> {
  late final TextEditingController _baseUrlController = TextEditingController(
    text: widget.draft.baseUrl,
  );
  late final TextEditingController _apiKeyController = TextEditingController(
    text: widget.draft.apiKey,
  );
  late final TextEditingController _modelController = TextEditingController(
    text: widget.draft.model,
  );

  @override
  void dispose() {
    _baseUrlController.dispose();
    _apiKeyController.dispose();
    _modelController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return _AgentSubpageScaffold(
      title: 'Agent model',
      subtitle:
          'Use the same provider, API, and reasoning flow as LLM, but scoped for this agent.',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Provider', style: context.settingsText.cardTitle),
                const SizedBox(height: 10),
                _PrototypeSelectionField(
                  label: 'Primary provider',
                  title: _labelForProvider(widget.draft.provider),
                  onTap: _selectProvider,
                ),
                const SizedBox(height: 10),
                _PrototypeSelectionField(
                  label: 'API protocol',
                  title: _labelForProtocol(widget.draft.protocol),
                  onTap: _selectProtocol,
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Connection', style: context.settingsText.cardTitle),
                const SizedBox(height: 10),
                _PrototypeField(
                  label: 'Base URL',
                  controller: _baseUrlController,
                  hintText: 'https://api.example.com/v1',
                  onChanged: (String value) {
                    widget.draft.baseUrl = value;
                  },
                ),
                const SizedBox(height: 10),
                _PrototypeField(
                  label: 'API key',
                  controller: _apiKeyController,
                  hintText: 'sk-...',
                  obscureText: true,
                  trailing: _apiKeyController.text.trim().isEmpty
                      ? null
                      : _FieldClearButton(
                          buttonKey: const ValueKey<String>(
                            'settings-agent-api-key-clear',
                          ),
                          onTap: _clearApiKey,
                        ),
                  onChanged: (String value) {
                    setState(() {
                      widget.draft.apiKey = value;
                    });
                  },
                ),
                const SizedBox(height: 10),
                _PrototypeField(
                  label: 'Model name',
                  controller: _modelController,
                  hintText: 'gpt-4o-mini',
                  onChanged: (String value) {
                    widget.draft.model = value;
                  },
                ),
                const SizedBox(height: 10),
                _PrototypeSelectionField(
                  label: 'Reasoning effort',
                  title: _labelForReasoningEffort(widget.draft.reasoningEffort),
                  onTap: _selectReasoningEffort,
                ),
                const SizedBox(height: 8),
                Text(
                  'Leave prompt override empty to use the default OpenCray system prompt.',
                  style: context.settingsText.selectionMeta,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _selectProvider() async {
    final _AgentProvider? value = await _showSelectionSheet<_AgentProvider>(
      context,
      title: 'Primary provider',
      selectedValue: widget.draft.provider,
      options: _AgentProvider.values,
      labelBuilder: _labelForProvider,
    );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft.provider = value;
      _applyProviderPreset(widget.draft, value);
      _baseUrlController.text = widget.draft.baseUrl;
      _modelController.text = widget.draft.model;
    });
  }

  void _clearApiKey() {
    if (_apiKeyController.text.isEmpty) {
      return;
    }
    setState(() {
      _apiKeyController.clear();
      widget.draft.apiKey = '';
    });
  }

  Future<void> _selectProtocol() async {
    final _AgentProtocol? value = await _showSelectionSheet<_AgentProtocol>(
      context,
      title: 'API protocol',
      selectedValue: widget.draft.protocol,
      options: _AgentProtocol.values,
      labelBuilder: _labelForProtocol,
    );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft.protocol = value;
    });
  }

  Future<void> _selectReasoningEffort() async {
    final _AgentReasoningEffort? value =
        await _showSelectionSheet<_AgentReasoningEffort>(
          context,
          title: 'Reasoning effort',
          selectedValue: widget.draft.reasoningEffort,
          options: _AgentReasoningEffort.values,
          labelBuilder: _labelForReasoningEffort,
        );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft.reasoningEffort = value;
    });
  }
}

class _AgentAdvancedDefaultsPage extends StatefulWidget {
  const _AgentAdvancedDefaultsPage({required this.draft});

  final _AgentDraft draft;

  @override
  State<_AgentAdvancedDefaultsPage> createState() =>
      _AgentAdvancedDefaultsPageState();
}

class _AgentAdvancedDefaultsPageState
    extends State<_AgentAdvancedDefaultsPage> {
  late final TextEditingController _collaborationController =
      TextEditingController(text: widget.draft.collaborationGuidance);
  late final TextEditingController _escalationController =
      TextEditingController(text: widget.draft.escalationRules);
  late final TextEditingController _forbiddenController = TextEditingController(
    text: widget.draft.forbiddenBehaviors,
  );

  @override
  void dispose() {
    _collaborationController.dispose();
    _escalationController.dispose();
    _forbiddenController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return _AgentSubpageScaffold(
      title: 'Advanced defaults',
      subtitle: 'Fine-tune runtime posture beyond the selected preset.',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'Runtime posture',
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 6),
                _AgentSummaryLinkRow(
                  title: 'Voice summary',
                  value: _labelForVoiceSummary(widget.draft.voiceSummary),
                  onTap: _selectVoiceSummary,
                ),
                Divider(height: 1, color: context.palette.divider),
                _AgentSummaryLinkRow(
                  title: 'Verbosity',
                  value: _labelForVerbosity(widget.draft.verbosity),
                  onTap: _selectVerbosity,
                ),
                Divider(height: 1, color: context.palette.divider),
                _AgentSummaryLinkRow(
                  title: 'Relationship style',
                  value: _labelForRelationshipStyle(
                    widget.draft.relationshipStyle,
                  ),
                  onTap: _selectRelationshipStyle,
                ),
                Divider(height: 1, color: context.palette.divider),
                _AgentSummaryLinkRow(
                  title: 'Risk tolerance',
                  value: _labelForRiskTolerance(widget.draft.riskTolerance),
                  onTap: _selectRiskTolerance,
                ),
                Divider(height: 1, color: context.palette.divider),
                _AgentSummaryLinkRow(
                  title: 'Tool use bias',
                  value: _labelForToolUseBias(widget.draft.toolUseBias),
                  onTap: _selectToolUseBias,
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'Written guidance',
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 10),
                _AgentMultilineField(
                  label: 'Collaboration preferences',
                  controller: _collaborationController,
                  onChanged: (String value) {
                    widget.draft.collaborationGuidance = value;
                  },
                ),
                const SizedBox(height: 10),
                _AgentMultilineField(
                  label: 'Escalation rules',
                  controller: _escalationController,
                  onChanged: (String value) {
                    widget.draft.escalationRules = value;
                  },
                ),
                const SizedBox(height: 10),
                _AgentMultilineField(
                  label: 'Forbidden behaviors',
                  controller: _forbiddenController,
                  onChanged: (String value) {
                    widget.draft.forbiddenBehaviors = value;
                  },
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _selectVoiceSummary() async {
    final _AgentVoiceSummary? value =
        await _showSelectionSheet<_AgentVoiceSummary>(
          context,
          title: 'Voice summary',
          selectedValue: widget.draft.voiceSummary,
          options: _AgentVoiceSummary.values,
          labelBuilder: _labelForVoiceSummary,
        );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft.voiceSummary = value;
    });
  }

  Future<void> _selectVerbosity() async {
    final _AgentVerbosity? value = await _showSelectionSheet<_AgentVerbosity>(
      context,
      title: 'Verbosity',
      selectedValue: widget.draft.verbosity,
      options: _AgentVerbosity.values,
      labelBuilder: _labelForVerbosity,
    );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft.verbosity = value;
    });
  }

  Future<void> _selectRelationshipStyle() async {
    final _AgentRelationshipStyle? value =
        await _showSelectionSheet<_AgentRelationshipStyle>(
          context,
          title: 'Relationship style',
          selectedValue: widget.draft.relationshipStyle,
          options: _AgentRelationshipStyle.values,
          labelBuilder: _labelForRelationshipStyle,
        );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft.relationshipStyle = value;
    });
  }

  Future<void> _selectRiskTolerance() async {
    final _AgentRiskTolerance? value =
        await _showSelectionSheet<_AgentRiskTolerance>(
          context,
          title: 'Risk tolerance',
          selectedValue: widget.draft.riskTolerance,
          options: _AgentRiskTolerance.values,
          labelBuilder: _labelForRiskTolerance,
        );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft.riskTolerance = value;
    });
  }

  Future<void> _selectToolUseBias() async {
    final _AgentToolUseBias? value =
        await _showSelectionSheet<_AgentToolUseBias>(
          context,
          title: 'Tool use bias',
          selectedValue: widget.draft.toolUseBias,
          options: _AgentToolUseBias.values,
          labelBuilder: _labelForToolUseBias,
        );
    if (!mounted || value == null) {
      return;
    }
    setState(() {
      widget.draft.toolUseBias = value;
    });
  }
}