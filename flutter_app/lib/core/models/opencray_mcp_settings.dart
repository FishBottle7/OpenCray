class OpenCrayMcpSettingsSnapshot {
  const OpenCrayMcpSettingsSnapshot({
    required this.title,
    required this.subtitle,
    required this.masterTitle,
    required this.masterSummary,
    required this.masterEnabled,
    required this.summaryLine,
    required this.serversTitle,
    required this.serversHelper,
    required this.masterDisabledTitle,
    required this.masterDisabledBody,
    required this.servers,
  });

  final String title;
  final String subtitle;
  final String masterTitle;
  final String masterSummary;
  final bool masterEnabled;
  final String summaryLine;
  final String serversTitle;
  final String serversHelper;
  final String masterDisabledTitle;
  final String masterDisabledBody;
  final List<OpenCrayMcpServerSnapshot> servers;

  factory OpenCrayMcpSettingsSnapshot.fromMap(Map<Object?, Object?> payload) {
    return OpenCrayMcpSettingsSnapshot(
      title: payload['title'] as String? ?? '',
      subtitle: payload['subtitle'] as String? ?? '',
      masterTitle: payload['masterTitle'] as String? ?? '',
      masterSummary: payload['masterSummary'] as String? ?? '',
      masterEnabled: payload['masterEnabled'] as bool? ?? false,
      summaryLine: payload['summaryLine'] as String? ?? '',
      serversTitle: payload['serversTitle'] as String? ?? '',
      serversHelper: payload['serversHelper'] as String? ?? '',
      masterDisabledTitle: payload['masterDisabledTitle'] as String? ?? '',
      masterDisabledBody: payload['masterDisabledBody'] as String? ?? '',
      servers: _requireList(payload['servers'])
          .map(_requireMap)
          .map(OpenCrayMcpServerSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayMcpServerSnapshot {
  const OpenCrayMcpServerSnapshot({
    required this.id,
    required this.title,
    required this.statusLabel,
    required this.statusTone,
    required this.trustLine,
    required this.authLine,
    required this.readinessLine,
    required this.transportLine,
    required this.exposureLine,
    required this.guidance,
    required this.actionLabel,
    required this.actionTurnsOn,
    required this.isActionEnabled,
  });

  final String id;
  final String title;
  final String statusLabel;
  final String statusTone;
  final String trustLine;
  final String authLine;
  final String readinessLine;
  final String transportLine;
  final String exposureLine;
  final String guidance;
  final String actionLabel;
  final bool actionTurnsOn;
  final bool isActionEnabled;

  factory OpenCrayMcpServerSnapshot.fromMap(Map<Object?, Object?> payload) {
    return OpenCrayMcpServerSnapshot(
      id: payload['id'] as String? ?? '',
      title: payload['title'] as String? ?? '',
      statusLabel: payload['statusLabel'] as String? ?? '',
      statusTone: payload['statusTone'] as String? ?? '',
      trustLine: payload['trustLine'] as String? ?? '',
      authLine: payload['authLine'] as String? ?? '',
      readinessLine: payload['readinessLine'] as String? ?? '',
      transportLine: payload['transportLine'] as String? ?? '',
      exposureLine: payload['exposureLine'] as String? ?? '',
      guidance: payload['guidance'] as String? ?? '',
      actionLabel: payload['actionLabel'] as String? ?? '',
      actionTurnsOn: payload['actionTurnsOn'] as bool? ?? false,
      isActionEnabled: payload['isActionEnabled'] as bool? ?? false,
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
