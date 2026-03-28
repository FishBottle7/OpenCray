class OpenCraySandboxSettingsSnapshot {
  const OpenCraySandboxSettingsSnapshot({
    required this.localeTag,
    required this.enabled,
    required this.providerId,
    required this.defaultBackend,
    required this.sessionMode,
    required this.autoResume,
    required this.idleTimeoutMinutes,
    required this.startupTimeoutMs,
    required this.requestTimeoutMs,
    required this.timeoutAction,
    required this.templateId,
    required this.e2bApiKey,
    required this.apiKeyConfigured,
  });

  final String localeTag;
  final bool enabled;
  final String providerId;
  final String defaultBackend;
  final String sessionMode;
  final bool autoResume;
  final int idleTimeoutMinutes;
  final int startupTimeoutMs;
  final int requestTimeoutMs;
  final String timeoutAction;
  final String templateId;
  final String e2bApiKey;
  final bool apiKeyConfigured;

  OpenCraySandboxSettingsSnapshot copyWith({
    String? localeTag,
    bool? enabled,
    String? providerId,
    String? defaultBackend,
    String? sessionMode,
    bool? autoResume,
    int? idleTimeoutMinutes,
    int? startupTimeoutMs,
    int? requestTimeoutMs,
    String? timeoutAction,
    String? templateId,
    String? e2bApiKey,
    bool? apiKeyConfigured,
  }) {
    return OpenCraySandboxSettingsSnapshot(
      localeTag: localeTag ?? this.localeTag,
      enabled: enabled ?? this.enabled,
      providerId: providerId ?? this.providerId,
      defaultBackend: defaultBackend ?? this.defaultBackend,
      sessionMode: sessionMode ?? this.sessionMode,
      autoResume: autoResume ?? this.autoResume,
      idleTimeoutMinutes: idleTimeoutMinutes ?? this.idleTimeoutMinutes,
      startupTimeoutMs: startupTimeoutMs ?? this.startupTimeoutMs,
      requestTimeoutMs: requestTimeoutMs ?? this.requestTimeoutMs,
      timeoutAction: timeoutAction ?? this.timeoutAction,
      templateId: templateId ?? this.templateId,
      e2bApiKey: e2bApiKey ?? this.e2bApiKey,
      apiKeyConfigured: apiKeyConfigured ?? this.apiKeyConfigured,
    );
  }

  factory OpenCraySandboxSettingsSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    int readInt(String key, int fallback) {
      final rawValue = payload[key];
      if (rawValue is int) {
        return rawValue;
      }
      if (rawValue is num) {
        return rawValue.toInt();
      }
      return int.tryParse(rawValue?.toString() ?? '') ?? fallback;
    }

    return OpenCraySandboxSettingsSnapshot(
      localeTag: payload['localeTag'] as String? ?? 'en',
      enabled: payload['enabled'] as bool? ?? false,
      providerId: payload['providerId'] as String? ?? 'e2b',
      defaultBackend: payload['defaultBackend'] as String? ?? 'local',
      sessionMode: payload['sessionMode'] as String? ?? 'ephemeral',
      autoResume: payload['autoResume'] as bool? ?? false,
      idleTimeoutMinutes: readInt('idleTimeoutMinutes', 15),
      startupTimeoutMs: readInt('startupTimeoutMs', 30000),
      requestTimeoutMs: readInt('requestTimeoutMs', 300000),
      timeoutAction: payload['timeoutAction'] as String? ?? 'kill',
      templateId: payload['templateId'] as String? ?? '',
      e2bApiKey: payload['e2bApiKey'] as String? ?? '',
      apiKeyConfigured: payload['apiKeyConfigured'] as bool? ?? false,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'localeTag': localeTag,
    'enabled': enabled,
    'providerId': providerId,
    'defaultBackend': defaultBackend,
    'sessionMode': sessionMode,
    'autoResume': autoResume,
    'idleTimeoutMinutes': idleTimeoutMinutes,
    'startupTimeoutMs': startupTimeoutMs,
    'requestTimeoutMs': requestTimeoutMs,
    'timeoutAction': timeoutAction,
    'templateId': templateId,
    'e2bApiKey': e2bApiKey,
    'apiKeyConfigured': apiKeyConfigured,
  };
}
