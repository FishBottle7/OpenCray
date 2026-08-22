import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

import '../models/opencray_chat_draft_attachment.dart';
import '../models/opencray_chat_snapshot.dart';
import '../models/opencray_agent_snapshot.dart';
import '../models/opencray_debug_snapshot.dart';
import '../models/opencray_file_image_preview.dart';
import '../models/opencray_file_text_preview.dart';
import '../models/opencray_file_voice_playback_source.dart';
import '../models/opencray_files_snapshot.dart';
import '../models/opencray_image_reference.dart';
import '../models/opencray_llm_config.dart';
import '../models/opencray_llm_validation.dart';
import '../models/opencray_media_speech_config.dart';
import '../models/opencray_mcp_settings.dart';
import '../models/opencray_network_search_config.dart';
import '../models/opencray_notification_settings.dart';
import '../models/opencray_personalization_config.dart';
import '../models/opencray_sandbox_preview_embed_config.dart';
import '../models/opencray_sandbox_settings.dart';
import '../models/opencray_safety_settings.dart';
import '../models/opencray_scheduled_tasks.dart';
import '../models/opencray_settings_snapshot.dart';
import '../models/opencray_shell_snapshot.dart';
import '../models/opencray_skills_snapshot.dart';
import '../models/opencray_strong_background.dart';
import '../models/opencray_twin_import_source_probe.dart';
import '../models/opencray_workspace_text_document.dart';
import 'opencray_bridge_lifecycle.dart';
import 'opencray_host_bridge.dart';

class _LocalRuntimeRealtimeEnvelope {
  const _LocalRuntimeRealtimeEnvelope({
    required this.kind,
    required this.payload,
  });

  final String kind;
  final Map<Object?, Object?> payload;
}

class OpenCrayLocalRuntimeBridge implements OpenCrayHostBridge {
  OpenCrayLocalRuntimeBridge({
    required String baseUrl,
    this.requestTimeout = const Duration(milliseconds: 800),
    this.pollInterval = const Duration(seconds: 2),
  }) : _baseUri = _normalizeBaseUri(baseUrl) {
    _runtimeRealtimeController =
        StreamController<_LocalRuntimeRealtimeEnvelope>.broadcast(
          onListen: _startRuntimeRealtimePolling,
          onCancel: _stopRuntimeRealtimePolling,
        );
  }

  final Uri _baseUri;
  final Duration requestTimeout;
  final Duration pollInterval;
  final String _bridgeInstanceId = openCrayLifecycleId('local-runtime-bridge');
  late final StreamController<_LocalRuntimeRealtimeEnvelope>
  _runtimeRealtimeController;
  StreamSubscription<_LocalRuntimeRealtimeEnvelope>?
  _runtimeRealtimeSourceSubscription;

  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() async =>
      _parseShellSnapshot(await _getMap('v1/shell_snapshot'));

  @override
  Stream<OpenCrayShellSnapshot> watchShellSnapshot() =>
      _watchMap(() => _getMap('v1/shell_snapshot'), _parseShellSnapshot);

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) => _postVoid('v1/save_shell_destination', <String, Object?>{
    'selectedTab': selectedTab,
    'settingsSubpage': settingsSubpage,
  });

  @override
  Future<OpenCrayFilesSnapshot> loadFilesSnapshot() async =>
      OpenCrayFilesSnapshot.fromMap(await _getMap('v1/files_snapshot'));

  @override
  Future<OpenCraySandboxPreviewEmbedConfig> resolveSandboxPreviewEmbedConfig(
    String previewUrl,
  ) async => OpenCraySandboxPreviewEmbedConfig.fromMap(
    await _postMap('v1/resolve_sandbox_preview_embed_config', <String, Object?>{
      'previewUrl': previewUrl,
    }),
  );

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
  Future<void> copyRichTextToClipboard({
    required String plainText,
    String? htmlText,
  }) => Clipboard.setData(ClipboardData(text: plainText));

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
  Future<OpenCraySavedWorkspaceMediaAttachment> saveWorkspaceMediaAttachment({
    required String relativePath,
    required String kind,
  }) async => OpenCraySavedWorkspaceMediaAttachment.fromMap(
    await _postMap('v1/save_workspace_media_attachment', <String, Object?>{
      'relativePath': relativePath,
      'kind': kind,
    }),
  );

  @override
  Future<void> showNativeToast(String message) async {}

  @override
  Future<List<OpenCraySettingsImageAsset>> listSettingsImageAssets() async =>
      (await _getJson('v1/settings_image_assets') as List<Object?>? ??
              const <Object?>[])
          .map(_requireMap)
          .map(OpenCraySettingsImageAsset.fromMap)
          .toList(growable: false);

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> importSettingsImageAssets(
    List<String> uriStrings,
  ) async =>
      (await _requestJson(
                    'POST',
                    'v1/import_settings_image_assets',
                    body: <String, Object?>{'uriStrings': uriStrings},
                  )
                  as List<Object?>? ??
              const <Object?>[])
          .map(_requireMap)
          .map(OpenCraySettingsImageAsset.fromMap)
          .toList(growable: false);

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
  Future<OpenCrayNotificationSettingsSnapshot>
  loadNotificationSettings() async =>
      OpenCrayNotificationSettingsSnapshot.fromMap(
        await _getMap('v1/notification_settings'),
      );

  @override
  Future<OpenCrayNotificationSettingsSnapshot> saveNotificationSettings(
    OpenCrayNotificationSettingsSnapshot snapshot,
  ) async => OpenCrayNotificationSettingsSnapshot.fromMap(
    await _postMap('v1/save_notification_settings', snapshot.toMap()),
  );

  @override
  Future<OpenCrayScheduledTasksSnapshot> loadScheduledTasks() async =>
      OpenCrayScheduledTasksSnapshot.fromMap(
        await _getMap('v1/scheduled_tasks'),
      );

  @override
  Future<OpenCrayScheduledTaskDetailSnapshot> loadScheduledTask(
    String scheduleId,
  ) async => OpenCrayScheduledTaskDetailSnapshot.fromMap(
    await _getMap(
      'v1/scheduled_task',
      queryParameters: <String, String>{'scheduleId': scheduleId},
    ),
  );

  @override
  Future<OpenCrayScheduledTaskActionResult> updateScheduledTaskEnabled({
    required String scheduleId,
    required bool enabled,
  }) async => OpenCrayScheduledTaskActionResult.fromMap(
    await _postMap('v1/update_scheduled_task_enabled', <String, Object?>{
      'scheduleId': scheduleId,
      'enabled': enabled,
    }),
  );

  @override
  Future<OpenCrayScheduledTaskActionResult> runScheduledTaskNow(
    String scheduleId,
  ) async => OpenCrayScheduledTaskActionResult.fromMap(
    await _postMap('v1/run_scheduled_task_now', <String, Object?>{
      'scheduleId': scheduleId,
    }),
  );

  @override
  Future<OpenCrayScheduledTaskActionResult> snoozeScheduledTask({
    required String scheduleId,
    int durationMinutes = 15,
  }) async => OpenCrayScheduledTaskActionResult.fromMap(
    await _postMap('v1/snooze_scheduled_task', <String, Object?>{
      'scheduleId': scheduleId,
      'durationMinutes': durationMinutes,
    }),
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
  Future<OpenCraySandboxSettingsSnapshot> loadSandboxSettings() async =>
      OpenCraySandboxSettingsSnapshot.fromMap(
        await _getMap('v1/sandbox_settings'),
      );

  @override
  Future<OpenCraySandboxSettingsSnapshot> saveSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  ) async => OpenCraySandboxSettingsSnapshot.fromMap(
    await _postMap('v1/save_sandbox_settings', snapshot.toMap()),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig() async =>
      OpenCrayLlmConfigSnapshot.fromMap(await _getMap('v1/llm_config'));

  @override
  Future<OpenCrayLlmConfigSnapshot> saveLlmConfig({
    required bool enabled,
    bool? streamingEnabled,
    String providerMode = 'cloud',
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
    String? openAiPromptCacheKeyStrategy,
    String? openAiPromptCacheRetention,
    bool? anthropicPromptCachingEnabled,
    String? anthropicPromptCacheTtl,
    String selectedOnDeviceModelId = 'gemma-4-e2b-it',
    int onDeviceMaxContextWindow = 32768,
    int onDeviceMaxTokens = 4096,
    int onDeviceTopK = 40,
    double onDeviceTopP = 0.95,
    double onDeviceTemperature = 0.70,
    String onDeviceAccelerator = 'gpu',
    bool onDeviceThinkingEnabled = false,
    bool onDeviceLiteModeEnabled = false,
    String? contextBudgetPreset,
    int? contextBudgetReservedOutputTokens,
    int? contextBudgetSafetyMarginTokens,
    double? contextBudgetEffectiveInputPercent,
  }) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _postMap('v1/save_llm_config', <String, Object?>{
      'enabled': enabled,
      if (streamingEnabled != null) 'streamingEnabled': streamingEnabled,
      'providerMode': providerMode,
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
      if (openAiPromptCacheKeyStrategy != null)
        'openAiPromptCacheKeyStrategy': openAiPromptCacheKeyStrategy,
      if (openAiPromptCacheRetention != null)
        'openAiPromptCacheRetention': openAiPromptCacheRetention,
      if (anthropicPromptCachingEnabled != null)
        'anthropicPromptCachingEnabled': anthropicPromptCachingEnabled,
      if (anthropicPromptCacheTtl != null)
        'anthropicPromptCacheTtl': anthropicPromptCacheTtl,
      'selectedOnDeviceModelId': selectedOnDeviceModelId,
      'onDeviceMaxContextWindow': onDeviceMaxContextWindow,
      'onDeviceMaxTokens': onDeviceMaxTokens,
      'onDeviceTopK': onDeviceTopK,
      'onDeviceTopP': onDeviceTopP,
      'onDeviceTemperature': onDeviceTemperature,
      'onDeviceAccelerator': onDeviceAccelerator,
      'onDeviceThinkingEnabled': onDeviceThinkingEnabled,
      'onDeviceLiteModeEnabled': onDeviceLiteModeEnabled,
      'contextBudgetPreset': contextBudgetPreset,
      'contextBudgetReservedOutputTokens': contextBudgetReservedOutputTokens,
      'contextBudgetSafetyMarginTokens': contextBudgetSafetyMarginTokens,
      'contextBudgetEffectiveInputPercent': contextBudgetEffectiveInputPercent,
    }),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> saveCustomLlmProvider({
    required String selectedProviderOptionId,
    bool? streamingEnabled,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
    String? openAiPromptCacheKeyStrategy,
    String? openAiPromptCacheRetention,
    bool? anthropicPromptCachingEnabled,
    String? anthropicPromptCacheTtl,
  }) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _postMap('v1/save_custom_llm_provider', <String, Object?>{
      'selectedProviderOptionId': selectedProviderOptionId,
      if (streamingEnabled != null) 'streamingEnabled': streamingEnabled,
      'protocol': protocol,
      'providerName': providerName,
      'providerNotes': providerNotes,
      'baseUrl': baseUrl,
      'apiKey': apiKey,
      'model': model,
      'reasoningEffort': reasoningEffort,
      'systemPrompt': systemPrompt,
      if (openAiPromptCacheKeyStrategy != null)
        'openAiPromptCacheKeyStrategy': openAiPromptCacheKeyStrategy,
      if (openAiPromptCacheRetention != null)
        'openAiPromptCacheRetention': openAiPromptCacheRetention,
      if (anthropicPromptCachingEnabled != null)
        'anthropicPromptCachingEnabled': anthropicPromptCachingEnabled,
      if (anthropicPromptCacheTtl != null)
        'anthropicPromptCacheTtl': anthropicPromptCacheTtl,
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
  Future<OpenCrayLlmConfigSnapshot> downloadOnDeviceLlmModel(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _postMap('v1/download_on_device_llm_model', <String, Object?>{
      'modelId': modelId,
    }),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> cancelOnDeviceLlmModelDownload(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _postMap('v1/cancel_on_device_llm_model_download', <String, Object?>{
      'modelId': modelId,
    }),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> deleteOnDeviceLlmModel(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _postMap('v1/delete_on_device_llm_model', <String, Object?>{
      'modelId': modelId,
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
    String? subAgentContextDefaultModeId,
    Map<String, String> subAgentContextProfileOverrides = const <String, String>{},
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
      'subAgentContextDefaultModeId': subAgentContextDefaultModeId,
      'subAgentContextProfileOverrides': subAgentContextProfileOverrides,
    }),
  );

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
    int? suggestedLimit,
  }) async => OpenCraySkillsSnapshot.fromMap(
    await _getMap(
      'v1/skills_snapshot',
      queryParameters: () {
        final queryParameters = <String, String>{};
        if (query.trim().isNotEmpty) {
          queryParameters['query'] = query;
          if (suggestedLimit != null) {
            queryParameters['suggestedLimit'] = suggestedLimit.toString();
          }
        }
        return queryParameters.isEmpty ? null : queryParameters;
      }(),
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
  Future<OpenCraySkillInstructionsSnapshot?> loadSuggestedSkillInstructions(
    String sourceRef, {
    String selectedSkillName = '',
  }) async {
    final payload = await _getJson(
      'v1/suggested_skill_instructions',
      queryParameters: <String, String>{
        'sourceRef': sourceRef,
        if (selectedSkillName.trim().isNotEmpty)
          'selectedSkillName': selectedSkillName,
      },
    );
    if (payload == null) {
      return null;
    }
    return OpenCraySkillInstructionsSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async =>
      OpenCrayChatSnapshot.fromMap(
        attachChatSnapshotClientLifecycle(
          await _getMap('v1/chat_snapshot'),
          fallbackBridgeInstanceId: _bridgeInstanceId,
        ),
      );

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() => _watchMap(
    () => _getMap('v1/chat_snapshot'),
    (payload) => OpenCrayChatSnapshot.fromMap(
      attachChatSnapshotClientLifecycle(
        payload,
        fallbackBridgeInstanceId: _bridgeInstanceId,
      ),
    ),
  );

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      OpenCrayChatRuntimeSnapshot.fromMap(
        attachChatRuntimeSnapshotClientLifecycle(
          await _getMap('v1/chat_runtime_snapshot'),
          fallbackBridgeInstanceId: _bridgeInstanceId,
        ),
      );

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() => _watchMap(
    () => _getMap('v1/chat_runtime_snapshot'),
    (payload) => OpenCrayChatRuntimeSnapshot.fromMap(
      attachChatRuntimeSnapshotClientLifecycle(
        payload,
        fallbackBridgeInstanceId: _bridgeInstanceId,
      ),
    ),
  );

  @override
  Stream<OpenCrayChatLiveAssistantDraftEvent> watchLiveAssistantDraftEvents() =>
      _runtimeRealtimeController.stream
          .where((envelope) => envelope.kind == 'draft')
          .map((envelope) => envelope.payload)
          .map(OpenCrayChatLiveAssistantDraftEvent.fromMap);

  @override
  Stream<OpenCrayChatRuntimeEventDelta> watchRuntimeEventDeltas() =>
      _runtimeRealtimeController.stream
          .where((envelope) => envelope.kind == 'delta')
          .map((envelope) => envelope.payload)
          .map(OpenCrayChatRuntimeEventDelta.fromMap);

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
  Future<OpenCrayDebugPythonRunResult> runDebugPythonScript({
    required String fileName,
    required String scriptText,
  }) async => throw UnsupportedError(
    'Running embedded Python debug scripts requires the Android host bridge.',
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
  Future<void> refreshSandboxSessionInfo() =>
      _postVoid('v1/refresh_sandbox_session_info');

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
  }) async => throw UnsupportedError(
    'Adding attachments is unavailable in local runtime mode.',
  );

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
  Future<void> approveChatApprovalForSession(String approvalId) => _postVoid(
    'v1/approve_chat_approval_for_session',
    <String, Object?>{'runId': approvalId, 'taskId': approvalId},
  );

  @override
  Future<void> rejectChatApproval(String approvalId) => _postVoid(
    'v1/reject_chat_approval',
    <String, Object?>{'runId': approvalId, 'taskId': approvalId},
  );

  @override
  Future<void> interruptChatRun(String runIdOrTaskId) => _postVoid(
    'v1/interrupt_chat_run',
    <String, Object?>{'runId': runIdOrTaskId, 'taskId': runIdOrTaskId},
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

  void _startRuntimeRealtimePolling() {
    if (_runtimeRealtimeSourceSubscription != null) {
      return;
    }
    _runtimeRealtimeSourceSubscription = _watchRuntimeRealtimeEnvelopes()
        .listen(
          _runtimeRealtimeController.add,
          onError: _runtimeRealtimeController.addError,
        );
  }

  void _stopRuntimeRealtimePolling() {
    final StreamSubscription<_LocalRuntimeRealtimeEnvelope>? subscription =
        _runtimeRealtimeSourceSubscription;
    _runtimeRealtimeSourceSubscription = null;
    unawaited(subscription?.cancel());
  }

  Stream<_LocalRuntimeRealtimeEnvelope>
  _watchRuntimeRealtimeEnvelopes() async* {
    String sessionId = '';
    String streamInstanceId = '';
    int afterSequence = 0;
    while (true) {
      try {
        final String requestedSessionId = sessionId;
        final String requestedStreamInstanceId = streamInstanceId;
        final int requestedAfterSequence = afterSequence;
        final Map<Object?, Object?> response = await _getMap(
          'v1/chat_runtime_events',
          queryParameters: <String, String>{
            'sessionId': sessionId,
            'streamInstanceId': streamInstanceId,
            'afterSequence': afterSequence.toString(),
            'timeoutMs': '15000',
          },
          timeout: const Duration(seconds: 17),
        );
        final Map<Object?, Object?> payload = _requireMap(response['payload']);
        final String nextSessionId =
            (response['sessionId'] ?? payload['sessionId']) as String? ?? '';
        final String nextStreamInstanceId =
            (response['streamInstanceId'] ?? payload['streamInstanceId'])
                as String? ??
            '';
        final int nextSequence = switch (response['sequence'] ??
            response['lastSequence'] ??
            payload['sequence'] ??
            payload['lastSequence']) {
          int value => value,
          num value => value.toInt(),
          final Object? value => int.tryParse(value?.toString() ?? '') ?? 0,
        };
        final String normalizedNextSessionId = nextSessionId.trim();
        if (normalizedNextSessionId.isNotEmpty) {
          if (sessionId != normalizedNextSessionId) {
            afterSequence = 0;
          }
          sessionId = normalizedNextSessionId;
        }
        if (nextStreamInstanceId.trim().isNotEmpty) {
          if (streamInstanceId != nextStreamInstanceId.trim()) {
            afterSequence = 0;
          }
          streamInstanceId = nextStreamInstanceId.trim();
        }
        if (nextSequence > afterSequence) {
          afterSequence = nextSequence;
        }
        final String kind = response['kind'] as String? ?? '';
        final bool isSameOrderedCursor =
            requestedSessionId.isNotEmpty &&
            requestedStreamInstanceId.isNotEmpty &&
            normalizedNextSessionId == requestedSessionId &&
            nextStreamInstanceId.trim() == requestedStreamInstanceId &&
            nextSequence > requestedAfterSequence;
        if (kind == 'snapshot' && isSameOrderedCursor) {
          yield _LocalRuntimeRealtimeEnvelope(
            kind: 'delta',
            payload: <Object?, Object?>{
              ...attachChatRuntimeSnapshotClientLifecycle(
                payload,
                fallbackBridgeInstanceId: _bridgeInstanceId,
              ),
              'sequence': nextSequence,
              'lastSequence': nextSequence,
              'eventId':
                  'runtime-snapshot-$streamInstanceId-$normalizedNextSessionId-$nextSequence',
              'runPatchMode': 'snapshot',
            },
          );
        } else if (kind == 'draft' || kind == 'delta') {
          yield _LocalRuntimeRealtimeEnvelope(
            kind: kind,
            payload: attachChatRuntimeSnapshotClientLifecycle(
              payload,
              fallbackBridgeInstanceId: _bridgeInstanceId,
            ),
          );
        }
      } catch (_) {
        await Future<void>.delayed(const Duration(milliseconds: 250));
      }
    }
  }

  Future<Map<Object?, Object?>> _getMap(
    String path, {
    Map<String, String>? queryParameters,
    Duration? timeout,
  }) async => _requireMap(
    await _requestJson(
      'GET',
      path,
      queryParameters: queryParameters,
      timeout: timeout,
    ),
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
    Duration? timeout,
  }) async {
    final Duration effectiveTimeout = timeout ?? requestTimeout;
    final client = HttpClient();
    client.connectionTimeout = effectiveTimeout;
    try {
      final request = await _openRequest(
        client,
        method,
        path,
        queryParameters: queryParameters,
      ).timeout(effectiveTimeout);
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      if (body != null) {
        request.headers.contentType = ContentType.json;
        request.write(jsonEncode(body));
      }
      final response = await request.close().timeout(effectiveTimeout);
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

  OpenCrayShellSnapshot _parseShellSnapshot(Map<Object?, Object?> payload) {
    return OpenCrayShellSnapshot.fromMap(
      attachShellSnapshotClientLifecycle(
        payload,
        fallbackBridgeInstanceId: _bridgeInstanceId,
      ),
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
