import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../models/opencray_chat_draft_attachment.dart';
import '../models/opencray_chat_snapshot.dart';
import '../models/opencray_debug_snapshot.dart';
import '../models/opencray_file_image_preview.dart';
import '../models/opencray_file_text_preview.dart';
import '../models/opencray_file_voice_playback_source.dart';
import '../models/opencray_files_snapshot.dart';
import '../models/opencray_llm_config.dart';
import '../models/opencray_llm_validation.dart';
import '../models/opencray_media_speech_config.dart';
import '../models/opencray_mcp_settings.dart';
import '../models/opencray_network_search_config.dart';
import '../models/opencray_personalization_config.dart';
import '../models/opencray_safety_settings.dart';
import '../models/opencray_settings_snapshot.dart';
import '../models/opencray_shell_snapshot.dart';
import '../models/opencray_skills_snapshot.dart';
import '../models/opencray_strong_background.dart';
import '../models/opencray_twin_import_source_probe.dart';
import '../models/opencray_workspace_text_document.dart';
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
  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  ) async => OpenCrayFileImagePreview.fromMap(
    await _getMap(
      'v1/workspace_image_preview',
      queryParameters: <String, String>{'relativePath': relativePath},
    ),
  );

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
  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  ) async => OpenCrayFileVoicePlaybackSource.fromMap(
    await _getMap(
      'v1/workspace_voice_playback_source',
      queryParameters: <String, String>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayWorkspaceTextDocument> loadWorkspaceTextDocument(
    String relativePath,
  ) async => OpenCrayWorkspaceTextDocument.fromMap(
    await _getMap(
      'v1/workspace_text_document',
      queryParameters: <String, String>{'relativePath': relativePath},
    ),
  );

  @override
  Future<void> openWorkspaceEntry(String relativePath) => _postVoid(
    'v1/open_workspace_entry',
    <String, Object?>{'relativePath': relativePath},
  );

  @override
  Future<void> openExternalUri(String uri) =>
      _postVoid('v1/open_external_uri', <String, Object?>{'uri': uri});

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
  Future<OpenCrayFilesSnapshot> createWorkspaceTextFile({
    required String parentRelativePath,
    required String name,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/create_workspace_text_file', <String, Object?>{
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
  Future<OpenCrayFilesSnapshot> saveWorkspaceTextDocument({
    required String targetRelativePath,
    required String content,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/save_workspace_text_document', <String, Object?>{
      'targetRelativePath': targetRelativePath,
      'content': content,
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
  Future<OpenCrayStrongBackgroundSnapshot>
  loadStrongBackgroundSnapshot() async =>
      OpenCrayStrongBackgroundSnapshot.fromMap(
        await _getMap('v1/strong_background_snapshot'),
      );

  @override
  Future<OpenCrayStrongBackgroundActionResult> performStrongBackgroundAction(
    String actionId,
  ) async => OpenCrayStrongBackgroundActionResult.fromMap(
    await _postMap('v1/perform_strong_background_action', <String, Object?>{
      'actionId': actionId,
    }),
  );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      OpenCrayNetworkSearchConfigSnapshot.fromMap(
        await _getMap('v1/network_search_config'),
      );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<OpenCrayNetworkSearchSlotSnapshot> slots,
  ) async => OpenCrayNetworkSearchConfigSnapshot.fromMap(
    await _postMap('v1/save_network_search_config', <String, Object?>{
      'slots': slots.map((slot) => slot.toMap()).toList(growable: false),
    }),
  );

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> loadMediaSpeechConfig() async =>
      OpenCrayMediaSpeechConfigSnapshot.fromMap(
        await _getMap('v1/media_speech_config'),
      );

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    OpenCrayMediaSpeechConfigSnapshot snapshot,
  ) async => OpenCrayMediaSpeechConfigSnapshot.fromMap(
    await _postMap('v1/save_media_speech_config', snapshot.toMap()),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig() async =>
      OpenCrayLlmConfigSnapshot.fromMap(await _getMap('v1/llm_config'));

  @override
  Future<OpenCrayLlmConfigSnapshot> saveLlmConfig({
    required bool enabled,
    required String providerId,
    required String selectedProviderOptionId,
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
      'selectedProviderOptionId': selectedProviderOptionId,
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
  Future<OpenCrayLlmConfigSnapshot> saveCustomLlmProvider({
    required String selectedProviderOptionId,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
  }) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _postMap('v1/save_custom_llm_provider', <String, Object?>{
      'selectedProviderOptionId': selectedProviderOptionId,
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
  Future<OpenCrayTwinImportSourceProbeSnapshot> probeTwinImportSource(
    String filePath,
  ) async => OpenCrayTwinImportSourceProbeSnapshot.fromMap(
    await _postMap('v1/probe_twin_import_source', <String, Object?>{
      'filePath': filePath,
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
  Future<OpenCraySafetySettingsSnapshot> loadSafetySettings() async =>
      OpenCraySafetySettingsSnapshot.fromMap(
        await _getMap('v1/safety_settings'),
      );

  @override
  Future<bool> authorizeExternalAccessLocation(String locationId) async => true;

  @override
  Future<OpenCraySafetySettingsSnapshot> saveSafetySettings({
    required String automationModeId,
    required bool rollbackJournalEnabled,
    required int maxFilesPerBatch,
    int maxAgentTurns = 0,
    int maxToolCalls = 0,
    required int undoWindowHours,
    required String fileChangesPolicyId,
    required String fileDeletesPolicyId,
    required String shellCommandsPolicyId,
    required String externalAccessModeId,
    required bool photoLibraryEnabled,
    required bool downloadsEnabled,
    required bool documentsEnabled,
    required bool recordingsEnabled,
    required String workspaceAccessProfileId,
    required bool readOnlyOutsideWorkspace,
    String liveContextModeId = 'full',
    bool memoryToolsEnabled = true,
  }) async => OpenCraySafetySettingsSnapshot.fromMap(
    await _postMap('v1/save_safety_settings', <String, Object?>{
      'automationModeId': automationModeId,
      'rollbackJournalEnabled': rollbackJournalEnabled,
      'maxFilesPerBatch': maxFilesPerBatch,
      'maxAgentTurns': maxAgentTurns,
      'maxToolCalls': maxToolCalls,
      'undoWindowHours': undoWindowHours,
      'fileChangesPolicyId': fileChangesPolicyId,
      'fileDeletesPolicyId': fileDeletesPolicyId,
      'shellCommandsPolicyId': shellCommandsPolicyId,
      'externalAccessModeId': externalAccessModeId,
      'photoLibraryEnabled': photoLibraryEnabled,
      'downloadsEnabled': downloadsEnabled,
      'documentsEnabled': documentsEnabled,
      'recordingsEnabled': recordingsEnabled,
      'workspaceAccessProfileId': workspaceAccessProfileId,
      'readOnlyOutsideWorkspace': readOnlyOutsideWorkspace,
      'liveContextModeId': liveContextModeId,
      'memoryToolsEnabled': memoryToolsEnabled,
    }),
  );

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
  }) async => OpenCraySkillsSnapshot.fromMap(
    await _getMap(
      'v1/skills_snapshot',
      queryParameters: query.trim().isEmpty
          ? null
          : <String, String>{'query': query},
    ),
  );

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
  Future<String?> checkInstalledSkillUpdates({String skillId = ''}) =>
      _getNullableString(
        'v1/check_installed_skill_updates',
        queryParameters: skillId.trim().isEmpty
            ? null
            : <String, String>{'skillId': skillId},
      );

  @override
  Future<String?> updateInstalledSkill(String skillId) => _postNullableString(
    'v1/update_installed_skill',
    <String, Object?>{'skillId': skillId},
  );

  @override
  Future<OpenCraySkillSourceInspectionSnapshot> inspectSkillSource(
    String sourceRef,
  ) async => OpenCraySkillSourceInspectionSnapshot.fromMap(
    await _postMap('v1/inspect_skill_source', <String, Object?>{
      'sourceRef': sourceRef,
    }),
  );

  @override
  Future<String?> installSkillSource(
    String sourceRef, {
    String selectedSkillName = '',
  }) => _postNullableString('v1/install_skill_source', <String, Object?>{
    'sourceRef': sourceRef,
    if (selectedSkillName.trim().isNotEmpty)
      'selectedSkillName': selectedSkillName,
  });

  @override
  Future<String?> installSkillSourceBatch(
    String sourceRef, {
    List<String> selectedSkillNames = const <String>[],
  }) => _postNullableString('v1/install_skill_source_batch', <String, Object?>{
    'sourceRef': sourceRef,
    if (selectedSkillNames.isNotEmpty) 'selectedSkillNames': selectedSkillNames,
  });

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
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      OpenCrayMemoryDebugSnapshot.fromMap(
        await _getMap('v1/memory_debug_snapshot'),
      );

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async =>
      OpenCrayMemoryDebugLinksSnapshot.fromMap(
        await _getMap('v1/memory_debug_links_snapshot'),
      );

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      OpenCraySoulDebugSnapshot.fromMap(
        await _getMap('v1/soul_debug_snapshot'),
      );

  @override
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async => OpenCrayMemoryDebugSearchSnapshot.fromMap(
    await _postMap('v1/memory_debug_search', <String, Object?>{
      'query': query,
      'maxResults': maxResults,
      'minScore': minScore,
    }),
  );

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async => OpenCrayMemoryDebugSliceSnapshot.fromMap(
    await _postMap('v1/memory_debug_slice', <String, Object?>{
      'path': path,
      'fromLine': fromLine,
      'lines': lines,
    }),
  );

  @override
  Future<void> applyMemoryDebugAction({
    required String recordId,
    required String actionId,
  }) => _postVoid('v1/memory_debug_action', <String, Object?>{
    'recordId': recordId,
    'actionId': actionId,
  });

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
  Future<void> copyChatSession(String sessionId) => _postVoid(
    'v1/copy_chat_session',
    <String, Object?>{'sessionId': sessionId},
  );

  @override
  Future<void> deleteChatSession(String sessionId) => _postVoid(
    'v1/delete_chat_session',
    <String, Object?>{'sessionId': sessionId},
  );

  @override
  Future<void> selectChatSession(String sessionId) => _postVoid(
    'v1/select_chat_session',
    <String, Object?>{'sessionId': sessionId},
  );

  @override
  Future<void> branchChatSessionFromMessage({
    required String sessionId,
    required String messageId,
  }) => _postVoid('v1/branch_chat_session_from_message', <String, Object?>{
    'sessionId': sessionId,
    'messageId': messageId,
  });

  @override
  Future<void> deleteChatMessage({
    required String sessionId,
    required String messageId,
  }) => _postVoid('v1/delete_chat_message', <String, Object?>{
    'sessionId': sessionId,
    'messageId': messageId,
  });

  @override
  Future<void> recallChatMessage({
    required String sessionId,
    required String messageId,
  }) => _postVoid('v1/recall_chat_message', <String, Object?>{
    'sessionId': sessionId,
    'messageId': messageId,
  });

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async => const <OpenCrayChatDraftAttachment>[];

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments =
        const <OpenCrayChatDraftAttachment>[],
  }) async {
    final payload = await _requestJson(
      'POST',
      'v1/submit_chat_message',
      body: <String, Object?>{
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
  Future<void> approveChatApproval(String approvalId) => _postVoid(
    'v1/approve_chat_approval',
    <String, Object?>{'runId': approvalId, 'taskId': approvalId},
  );

  @override
  Future<void> rejectChatApproval(String approvalId) => _postVoid(
    'v1/reject_chat_approval',
    <String, Object?>{'runId': approvalId, 'taskId': approvalId},
  );

  @override
  Future<void> retryChatRun(String runIdOrTaskId) => _postVoid(
    'v1/retry_chat_run',
    <String, Object?>{'runId': runIdOrTaskId, 'taskId': runIdOrTaskId},
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

  Future<String?> _getNullableString(
    String path, {
    Map<String, String>? queryParameters,
  }) async {
    final payload = await _requestJson(
      'GET',
      path,
      queryParameters: queryParameters,
    );
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
    return OpenCrayShellSnapshot.fromMap(
      payload,
      defaultHostSummary:
          'Flutter shell is attached to a local runtime bridge.',
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
