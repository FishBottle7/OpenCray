class OpenCrayPersonalizationConfigSnapshot {
  const OpenCrayPersonalizationConfigSnapshot({
    required this.title,
    required this.subtitle,
    required this.introTitle,
    required this.introBody,
    required this.introHelper,
    required this.presetsTitle,
    required this.presetsHelper,
    required this.presets,
    required this.selectedPresetId,
    required this.customOverlayTitle,
    required this.customOverlayHelper,
    required this.customLabelHint,
    required this.customLabelHelper,
    required this.customGuidanceHint,
    required this.customGuidanceHelper,
    required this.customLabel,
    required this.customGuidance,
    required this.behaviorDefaultsTitle,
    required this.appLanguageTitle,
    required this.appLanguageOptions,
    required this.selectedAppLanguageId,
    required this.livePreviewTitle,
    required this.livePreviewName,
    required this.livePreviewSummary,
    required this.queueTitle,
    required this.queueBody,
    required this.queueIsIdle,
    required this.lastResetTitle,
    required this.lastResetMessage,
    required this.resetActions,
  });

  final String title;
  final String subtitle;
  final String introTitle;
  final String introBody;
  final String introHelper;
  final String presetsTitle;
  final String presetsHelper;
  final List<OpenCrayPersonalizationPresetOptionSnapshot> presets;
  final String selectedPresetId;
  final String customOverlayTitle;
  final String customOverlayHelper;
  final String customLabelHint;
  final String customLabelHelper;
  final String customGuidanceHint;
  final String customGuidanceHelper;
  final String customLabel;
  final String customGuidance;
  final String behaviorDefaultsTitle;
  final String appLanguageTitle;
  final List<OpenCrayPersonalizationLanguageOptionSnapshot> appLanguageOptions;
  final String selectedAppLanguageId;
  final String livePreviewTitle;
  final String livePreviewName;
  final String livePreviewSummary;
  final String queueTitle;
  final String queueBody;
  final bool queueIsIdle;
  final String lastResetTitle;
  final String lastResetMessage;
  final List<OpenCrayPersonalizationResetActionSnapshot> resetActions;

  factory OpenCrayPersonalizationConfigSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayPersonalizationConfigSnapshot(
      title: payload['title'] as String? ?? '',
      subtitle: payload['subtitle'] as String? ?? '',
      introTitle: payload['introTitle'] as String? ?? '',
      introBody: payload['introBody'] as String? ?? '',
      introHelper: payload['introHelper'] as String? ?? '',
      presetsTitle: payload['presetsTitle'] as String? ?? '',
      presetsHelper: payload['presetsHelper'] as String? ?? '',
      presets: _requireList(payload['presets'])
          .map(_requireMap)
          .map(OpenCrayPersonalizationPresetOptionSnapshot.fromMap)
          .toList(growable: false),
      selectedPresetId: payload['selectedPresetId'] as String? ?? '',
      customOverlayTitle: payload['customOverlayTitle'] as String? ?? '',
      customOverlayHelper: payload['customOverlayHelper'] as String? ?? '',
      customLabelHint: payload['customLabelHint'] as String? ?? '',
      customLabelHelper: payload['customLabelHelper'] as String? ?? '',
      customGuidanceHint: payload['customGuidanceHint'] as String? ?? '',
      customGuidanceHelper: payload['customGuidanceHelper'] as String? ?? '',
      customLabel: payload['customLabel'] as String? ?? '',
      customGuidance: payload['customGuidance'] as String? ?? '',
      behaviorDefaultsTitle: payload['behaviorDefaultsTitle'] as String? ?? '',
      appLanguageTitle: payload['appLanguageTitle'] as String? ?? '',
      appLanguageOptions: _requireList(payload['appLanguageOptions'])
          .map(_requireMap)
          .map(OpenCrayPersonalizationLanguageOptionSnapshot.fromMap)
          .toList(growable: false),
      selectedAppLanguageId: payload['selectedAppLanguageId'] as String? ?? '',
      livePreviewTitle: payload['livePreviewTitle'] as String? ?? '',
      livePreviewName: payload['livePreviewName'] as String? ?? '',
      livePreviewSummary: payload['livePreviewSummary'] as String? ?? '',
      queueTitle: payload['queueTitle'] as String? ?? '',
      queueBody: payload['queueBody'] as String? ?? '',
      queueIsIdle: payload['queueIsIdle'] as bool? ?? false,
      lastResetTitle: payload['lastResetTitle'] as String? ?? '',
      lastResetMessage: payload['lastResetMessage'] as String? ?? '',
      resetActions: _requireList(payload['resetActions'])
          .map(_requireMap)
          .map(OpenCrayPersonalizationResetActionSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayPersonalizationLanguageOptionSnapshot {
  const OpenCrayPersonalizationLanguageOptionSnapshot({
    required this.id,
    required this.title,
    required this.isSelected,
  });

  final String id;
  final String title;
  final bool isSelected;

  factory OpenCrayPersonalizationLanguageOptionSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayPersonalizationLanguageOptionSnapshot(
      id: payload['id'] as String? ?? '',
      title: payload['title'] as String? ?? '',
      isSelected: payload['isSelected'] as bool? ?? false,
    );
  }
}

class OpenCrayPersonalizationPresetOptionSnapshot {
  const OpenCrayPersonalizationPresetOptionSnapshot({
    required this.id,
    required this.title,
    required this.summary,
    required this.voice,
    required this.status,
    required this.isSelected,
  });

  final String id;
  final String title;
  final String summary;
  final String voice;
  final String status;
  final bool isSelected;

  factory OpenCrayPersonalizationPresetOptionSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayPersonalizationPresetOptionSnapshot(
      id: payload['id'] as String? ?? '',
      title: payload['title'] as String? ?? '',
      summary: payload['summary'] as String? ?? '',
      voice: payload['voice'] as String? ?? '',
      status: payload['status'] as String? ?? '',
      isSelected: payload['isSelected'] as bool? ?? false,
    );
  }
}

class OpenCrayPersonalizationResetActionSnapshot {
  const OpenCrayPersonalizationResetActionSnapshot({
    required this.scopeId,
    required this.title,
    required this.scopeBody,
    required this.retainBody,
    required this.confirmationToken,
    required this.inputHint,
    required this.disabledGuidance,
    required this.typeExactGuidance,
    required this.armedGuidance,
    required this.isInputEnabled,
  });

  final String scopeId;
  final String title;
  final String scopeBody;
  final String retainBody;
  final String confirmationToken;
  final String inputHint;
  final String disabledGuidance;
  final String typeExactGuidance;
  final String armedGuidance;
  final bool isInputEnabled;

  factory OpenCrayPersonalizationResetActionSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayPersonalizationResetActionSnapshot(
      scopeId: payload['scopeId'] as String? ?? '',
      title: payload['title'] as String? ?? '',
      scopeBody: payload['scopeBody'] as String? ?? '',
      retainBody: payload['retainBody'] as String? ?? '',
      confirmationToken: payload['confirmationToken'] as String? ?? '',
      inputHint: payload['inputHint'] as String? ?? '',
      disabledGuidance: payload['disabledGuidance'] as String? ?? '',
      typeExactGuidance: payload['typeExactGuidance'] as String? ?? '',
      armedGuidance: payload['armedGuidance'] as String? ?? '',
      isInputEnabled: payload['isInputEnabled'] as bool? ?? false,
    );
  }
}

List<Object?> _requireList(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <Object?>[];
  }
  return list;
}

Map<Object?, Object?> _requireMap(Object? payload) {
  final map = payload as Map<Object?, Object?>?;
  if (map == null) {
    throw const FormatException('Expected a map payload from host bridge.');
  }
  return map;
}
