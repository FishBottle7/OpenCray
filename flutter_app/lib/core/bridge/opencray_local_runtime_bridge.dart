import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../../app/opencray_tabs.dart';
import '../models/opencray_chat_snapshot.dart';
import '../models/opencray_file_text_preview.dart';
import '../models/opencray_files_snapshot.dart';
import '../models/opencray_llm_config.dart';
import '../models/opencray_llm_validation.dart';
import '../models/opencray_mcp_settings.dart';
import '../models/opencray_personalization_config.dart';
import '../models/opencray_settings_snapshot.dart';
import '../models/opencray_shell_snapshot.dart';
import '../models/opencray_skills_snapshot.dart';
import 'opencray_host_bridge.dart';

class OpenCrayLocalRuntimeBridge implements OpenCrayHostBridge {
  OpenCrayLocalRuntimeBridge({
    required String baseUrl,
    this.requestTimeout = const Duration(milliseconds: 800),
    this.pollInterval = const Duration(seconds: 2),
  }) : _baseUri = _normalizeBaseUri(baseUrl);

  final Uri _baseUri;
  final Duration requestTimeout;
  final Duration pollInterval;

  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() async =>
      _parseShellSnapshot(await _getMap('v1/shell_snapshot'));

  @override
  Stream<OpenCrayShellSnapshot> watchShellSnapshot() =>
      _watchMap(() => _getMap('v1/shell_snapshot'), _parseShellSnapshot);

  @override
  Future<OpenCrayFilesSnapshot> loadFilesSnapshot() async =>
      OpenCrayFilesSnapshot.fromMap(await _getMap('v1/files_snapshot'));

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async => OpenCrayFileTextPreview.fromMap(
    await _getMap(
      'v1/workspace_text_preview',
      queryParameters: <String, String>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceFolder({
    required String parentRelativePath,
    required String name,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/create_workspace_folder', <String, Object?>{
      'parentRelativePath': parentRelativePath,
      'name': name,
    }),
  );

  @override
  Future<OpenCrayFilesSnapshot> renameWorkspaceEntry({
    required String targetRelativePath,
    required String newName,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/rename_workspace_entry', <String, Object?>{
      'targetRelativePath': targetRelativePath,
      'newName': newName,
    }),
  );

  @override
  Future<OpenCrayFilesSnapshot> deleteWorkspaceEntries(
    List<String> relativePaths,
  ) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/delete_workspace_entries', <String, Object?>{
      'relativePaths': relativePaths,
    }),
  );

  @override
  Future<OpenCrayFilesSnapshot> pasteWorkspaceEntries({
    required List<String> sourceRelativePaths,
    required String destinationRelativePath,
    required bool move,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/paste_workspace_entries', <String, Object?>{
      'sourceRelativePaths': sourceRelativePaths,
      'destinationRelativePath': destinationRelativePath,
      'move': move,
    }),
  );

  @override
  Future<void> shareWorkspaceEntries(List<String> relativePaths) => _postVoid(
    'v1/share_workspace_entries',
    <String, Object?>{'relativePaths': relativePaths},
  );

  @override
  Future<void> showNativeToast(String message) async {}

  @override
  Future<OpenCraySettingsOverviewSnapshot> loadSettingsOverview() async =>
      _parseSettingsOverview(await _getMap('v1/settings_overview'));

  @override
  Stream<OpenCraySettingsOverviewSnapshot> watchSettingsOverview() =>
      _watchMap(() => _getMap('v1/settings_overview'), _parseSettingsOverview);

  @override
  Future<OpenCraySettingsDetailSnapshot> loadSettingsDetail(
    String routeId,
  ) async => _parseSettingsDetail(
    await _getMap(
      'v1/settings_detail',
      queryParameters: <String, String>{'routeId': routeId},
    ),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig() async =>
      OpenCrayLlmConfigSnapshot.fromMap(await _getMap('v1/llm_config'));

  @override
  Future<OpenCrayLlmConfigSnapshot> saveLlmConfig({
    required bool enabled,
    required String providerId,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
  }) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _postMap('v1/save_llm_config', <String, Object?>{
      'enabled': enabled,
      'providerId': providerId,
      'protocol': protocol,
      'providerName': providerName,
      'providerNotes': providerNotes,
      'baseUrl': baseUrl,
      'apiKey': apiKey,
      'model': model,
      'reasoningEffort': reasoningEffort,
      'systemPrompt': systemPrompt,
    }),
  );

  @override
  Future<OpenCrayLlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
  }) async => OpenCrayLlmValidationResult.fromMap(
    await _postMap('v1/validate_llm_config', <String, Object?>{
      'providerId': providerId,
      'protocol': protocol,
      'baseUrl': baseUrl,
      'apiKey': apiKey,
      'model': model,
      'reasoningEffort': reasoningEffort,
    }),
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot>
  loadPersonalizationConfig() async =>
      OpenCrayPersonalizationConfigSnapshot.fromMap(
        await _getMap('v1/personalization_config'),
      );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _postMap('v1/save_personalization_config', <String, Object?>{
      'presetId': presetId,
      'customLabel': customLabel,
      'customGuidance': customGuidance,
    }),
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _postMap('v1/set_app_language', <String, Object?>{
      'languageId': languageId,
    }),
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _postMap('v1/run_personalization_reset', <String, Object?>{
      'scopeId': scopeId,
    }),
  );

  @override
  Future<OpenCrayMcpSettingsSnapshot> loadMcpSettings() async =>
      OpenCrayMcpSettingsSnapshot.fromMap(await _getMap('v1/mcp_settings'));

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpMasterEnabled(bool enabled) async =>
      OpenCrayMcpSettingsSnapshot.fromMap(
        await _postMap('v1/set_mcp_master_enabled', <String, Object?>{
          'enabled': enabled,
        }),
      );

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  }) async => OpenCrayMcpSettingsSnapshot.fromMap(
    await _postMap('v1/set_mcp_server_enabled', <String, Object?>{
      'serverId': serverId,
      'enabled': enabled,
    }),
  );

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot() async =>
      OpenCraySkillsSnapshot.fromMap(await _getMap('v1/skills_snapshot'));

  @override
  Stream<OpenCraySkillsSnapshot> watchSkillsSnapshot() => _watchMap(
    () => _getMap('v1/skills_snapshot'),
    OpenCraySkillsSnapshot.fromMap,
  );

  @override
  Future<void> setSkillEnabled(String skillId, bool enabled) => _postVoid(
    'v1/set_skill_enabled',
    <String, Object?>{'skillId': skillId, 'enabled': enabled},
  );

  @override
  Future<String?> refreshSkills() => _postNullableString('v1/refresh_skills');

  @override
  Future<String?> installSuggestedSkill(String skillId) => _postNullableString(
    'v1/install_suggested_skill',
    <String, Object?>{'skillId': skillId},
  );

  @override
  Future<String?> deleteInstalledSkill(String skillId) => _postNullableString(
    'v1/delete_installed_skill',
    <String, Object?>{'skillId': skillId},
  );

  @override
  Future<OpenCraySkillInstructionsSnapshot?> loadSkillInstructions(
    String skillId,
  ) async {
    final payload = await _getJson(
      'v1/skill_instructions',
      queryParameters: <String, String>{'skillId': skillId},
    );
    if (payload == null) {
      return null;
    }
    return OpenCraySkillInstructionsSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async =>
      OpenCrayChatSnapshot.fromMap(await _getMap('v1/chat_snapshot'));

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() => _watchMap(
    () => _getMap('v1/chat_snapshot'),
    OpenCrayChatSnapshot.fromMap,
  );

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      OpenCrayChatRuntimeSnapshot.fromMap(
        await _getMap('v1/chat_runtime_snapshot'),
      );

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() => _watchMap(
    () => _getMap('v1/chat_runtime_snapshot'),
    OpenCrayChatRuntimeSnapshot.fromMap,
  );

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
  Future<void> createChatSession() => _postVoid('v1/create_chat_session');

  @override
  Future<void> selectChatSession(String sessionId) => _postVoid(
    'v1/select_chat_session',
    <String, Object?>{'sessionId': sessionId},
  );

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(String text) async {
    final payload = await _requestJson(
      'POST',
      'v1/submit_chat_message',
      body: <String, Object?>{'text': text},
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
  Future<void> rejectChatApproval(String approvalId) => _postVoid(
    'v1/reject_chat_approval',
    <String, Object?>{'runId': approvalId, 'taskId': approvalId},
  );

  Stream<T> _watchMap<T>(
    Future<Map<Object?, Object?>> Function() loader,
    T Function(Map<Object?, Object?> payload) parser,
  ) async* {
    String? previousSignature;
    while (true) {
      final payload = await loader();
      final signature = jsonEncode(_normalizeJson(payload));
      if (signature != previousSignature) {
        previousSignature = signature;
        yield parser(payload);
      }
      await Future<void>.delayed(pollInterval);
    }
  }

  Future<Map<Object?, Object?>> _getMap(
    String path, {
    Map<String, String>? queryParameters,
  }) async => _requireMap(
    await _requestJson('GET', path, queryParameters: queryParameters),
  );

  Future<Map<Object?, Object?>> _postMap(
    String path, [
    Map<String, Object?>? body,
  ]) async => _requireMap(await _requestJson('POST', path, body: body));

  Future<String?> _postNullableString(
    String path, [
    Map<String, Object?>? body,
  ]) async {
    final payload = await _requestJson('POST', path, body: body);
    if (payload == null) {
      return null;
    }
    return payload as String?;
  }

  Future<void> _postVoid(String path, [Map<String, Object?>? body]) async {
    await _requestJson('POST', path, body: body);
  }

  Future<Object?> _getJson(
    String path, {
    Map<String, String>? queryParameters,
  }) => _requestJson('GET', path, queryParameters: queryParameters);

  Future<Object?> _requestJson(
    String method,
    String path, {
    Map<String, String>? queryParameters,
    Map<String, Object?>? body,
  }) async {
    final client = HttpClient();
    client.connectionTimeout = requestTimeout;
    try {
      final request = await _openRequest(
        client,
        method,
        path,
        queryParameters: queryParameters,
      ).timeout(requestTimeout);
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      if (body != null) {
        request.headers.contentType = ContentType.json;
        request.write(jsonEncode(body));
      }
      final response = await request.close().timeout(requestTimeout);
      final responseBody = await response.transform(utf8.decoder).join();
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw HttpException(
          'Local runtime returned HTTP ${response.statusCode}${responseBody.isEmpty ? '' : ': $responseBody'}',
          uri: request.uri,
        );
      }
      if (responseBody.trim().isEmpty) {
        return null;
      }
      return jsonDecode(responseBody);
    } finally {
      client.close(force: true);
    }
  }

  Future<HttpClientRequest> _openRequest(
    HttpClient client,
    String method,
    String path, {
    Map<String, String>? queryParameters,
  }) {
    final resolved = _baseUri
        .resolve(path)
        .replace(
          queryParameters: queryParameters?.isEmpty == true
              ? null
              : queryParameters,
        );
    switch (method) {
      case 'POST':
        return client.postUrl(resolved);
      case 'GET':
      default:
        return client.getUrl(resolved);
    }
  }

  static Uri _normalizeBaseUri(String rawBaseUrl) {
    final trimmed = rawBaseUrl.trim();
    final withTrailingSlash = trimmed.endsWith('/') ? trimmed : '$trimmed/';
    return Uri.parse(withTrailingSlash);
  }

  static OpenCrayShellSnapshot _parseShellSnapshot(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayShellSnapshot(
      initialTab: _parseTab(payload['initialTab'] as String?),
      localeTag: payload['localeTag'] as String? ?? 'en',
      hostLabel: payload['hostLabel'] as String? ?? 'HOST READY',
      hostSummary:
          payload['hostSummary'] as String? ??
          'Flutter shell is attached to a local runtime bridge.',
      isHostConnected: payload['isHostConnected'] as bool? ?? true,
    );
  }

  static OpenCraySettingsOverviewSnapshot _parseSettingsOverview(
    Map<Object?, Object?> payload,
  ) {
    return OpenCraySettingsOverviewSnapshot(
      eyebrow: payload['eyebrow'] as String? ?? '',
      title: payload['title'] as String? ?? '',
      subtitle: payload['subtitle'] as String? ?? '',
      deviceTitle: payload['deviceTitle'] as String? ?? '',
      deviceSummary: payload['deviceSummary'] as String? ?? '',
      entries: (_requireList(payload['entries']))
          .map(_requireMap)
          .map(
            (entry) => OpenCraySettingsHomeEntrySnapshot(
              routeId: entry['routeId'] as String? ?? 'home',
              title: entry['title'] as String? ?? '',
            ),
          )
          .toList(growable: false),
    );
  }

  static OpenCraySettingsDetailSnapshot _parseSettingsDetail(
    Map<Object?, Object?> payload,
  ) {
    return OpenCraySettingsDetailSnapshot(
      routeId: payload['routeId'] as String? ?? 'home',
      title: payload['title'] as String? ?? '',
      subtitle: payload['subtitle'] as String? ?? '',
      sections: (_requireList(payload['sections']))
          .map(_requireMap)
          .map(
            (section) => OpenCraySettingsSectionSnapshot(
              title: section['title'] as String? ?? '',
              helperText: section['helperText'] as String?,
              rows: (_requireList(section['rows']))
                  .map(_requireMap)
                  .map(_parseSettingsRow)
                  .toList(growable: false),
              segmentedOptions: _listOfStrings(section['segmentedOptions']),
              segmentedIndex: section['segmentedIndex'] as int?,
              inlinePanelText: section['inlinePanelText'] as String?,
              backgroundTone: _parseBackgroundTone(
                section['backgroundTone'] as String?,
              ),
            ),
          )
          .toList(growable: false),
    );
  }

  static OpenCraySettingsRowSnapshot _parseSettingsRow(
    Map<Object?, Object?> payload,
  ) {
    switch (_parseTrailingKind(payload['trailingKind'] as String?)) {
      case OpenCraySettingsRowTrailingKind.chevron:
        return OpenCraySettingsRowSnapshot.chevron(
          title: payload['title'] as String? ?? '',
          subtitle: payload['subtitle'] as String?,
        );
      case OpenCraySettingsRowTrailingKind.toggle:
        return OpenCraySettingsRowSnapshot.toggle(
          title: payload['title'] as String? ?? '',
          subtitle: payload['subtitle'] as String?,
          toggleValue: payload['toggleValue'] as bool? ?? false,
        );
      case OpenCraySettingsRowTrailingKind.value:
        return OpenCraySettingsRowSnapshot.value(
          title: payload['title'] as String? ?? '',
          valueLabel: payload['valueLabel'] as String? ?? '',
        );
    }
  }

  static OpenCrayTab _parseTab(String? rawValue) {
    switch (rawValue) {
      case 'skills':
        return OpenCrayTab.skills;
      case 'files':
        return OpenCrayTab.files;
      case 'settings':
        return OpenCrayTab.settings;
      case 'chat':
      default:
        return OpenCrayTab.chat;
    }
  }

  static OpenCraySettingsSectionBackgroundTone _parseBackgroundTone(
    String? rawValue,
  ) {
    switch (rawValue) {
      case 'danger':
        return OpenCraySettingsSectionBackgroundTone.danger;
      case 'surface':
      default:
        return OpenCraySettingsSectionBackgroundTone.surface;
    }
  }

  static OpenCraySettingsRowTrailingKind _parseTrailingKind(String? rawValue) {
    switch (rawValue) {
      case 'toggle':
        return OpenCraySettingsRowTrailingKind.toggle;
      case 'value':
        return OpenCraySettingsRowTrailingKind.value;
      case 'chevron':
      default:
        return OpenCraySettingsRowTrailingKind.chevron;
    }
  }
}

List<Object?> _requireList(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <Object?>[];
  }
  return list;
}

List<String>? _listOfStrings(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return null;
  }
  return list.map((value) => value as String? ?? '').toList(growable: false);
}

Map<Object?, Object?> _requireMap(Object? payload) {
  final map = payload as Map<Object?, Object?>?;
  if (map == null) {
    throw const FormatException('Expected a map payload from local runtime.');
  }
  return map;
}

Object? _normalizeJson(Object? value) {
  if (value is Map<Object?, Object?>) {
    final entries = value.entries.toList()
      ..sort((left, right) => '${left.key}'.compareTo('${right.key}'));
    return <String, Object?>{
      for (final entry in entries) '${entry.key}': _normalizeJson(entry.value),
    };
  }
  if (value is List<Object?>) {
    return value.map(_normalizeJson).toList(growable: false);
  }
  return value;
}
