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
import '../models/opencray_settings_snapshot.dart';
import '../models/opencray_shell_snapshot.dart';
import '../models/opencray_skills_snapshot.dart';
import '../models/opencray_strong_background.dart';
import '../models/opencray_twin_import_source_probe.dart';
import '../models/opencray_workspace_text_document.dart';
import 'opencray_host_bridge.dart';

class OpenCrayPlatformBridge implements OpenCrayHostBridge {
  const OpenCrayPlatformBridge();

  static const MethodChannel _methodChannel = MethodChannel(
    'com.opencray.host/methods',
  );
  static const EventChannel _shellSnapshotChannel = EventChannel(
    'com.opencray.host/shell_snapshot',
  );
  static const EventChannel _settingsOverviewChannel = EventChannel(
    'com.opencray.host/settings_overview',
  );
  static const EventChannel _skillsSnapshotChannel = EventChannel(
    'com.opencray.host/skills_snapshot',
  );
  static const EventChannel _chatSnapshotChannel = EventChannel(
    'com.opencray.host/chat_snapshot',
  );
  static const EventChannel _chatRuntimeSnapshotChannel = EventChannel(
    'com.opencray.host/chat_runtime_snapshot',
  );
  static const EventChannel _liveAssistantDraftChannel = EventChannel(
    'com.opencray.host/live_assistant_draft',
  );
  static const EventChannel _runtimeEventDeltaChannel = EventChannel(
    'com.opencray.host/runtime_event_delta',
  );

  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() async =>
      _parseShellSnapshot(await _invokeMap('loadShellSnapshot'));

  @override
  Stream<OpenCrayShellSnapshot> watchShellSnapshot() => _shellSnapshotChannel
      .receiveBroadcastStream()
      .map(_requireMap)
      .map(_parseShellSnapshot);

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) => _methodChannel.invokeMethod<void>(
    'saveShellDestination',
    <String, Object?>{
      'selectedTab': selectedTab,
      'settingsSubpage': settingsSubpage,
    },
  );

  @override
  Future<OpenCrayFilesSnapshot> loadFilesSnapshot() async =>
      OpenCrayFilesSnapshot.fromMap(await _invokeMap('loadFilesSnapshot'));

  @override
  Future<OpenCraySandboxPreviewEmbedConfig> resolveSandboxPreviewEmbedConfig(
    String previewUrl,
  ) async => OpenCraySandboxPreviewEmbedConfig.fromMap(
    await _invokeMap(
      'resolveSandboxPreviewEmbedConfig',
      arguments: <String, Object?>{'previewUrl': previewUrl},
    ),
  );

  @override
  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  ) async => OpenCrayFileImagePreview.fromMap(
    await _invokeMap(
      'loadWorkspaceImagePreview',
      arguments: <String, Object?>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async => OpenCrayFileTextPreview.fromMap(
    await _invokeMap(
      'loadWorkspaceTextPreview',
      arguments: <String, Object?>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  ) async => OpenCrayFileVoicePlaybackSource.fromMap(
    await _invokeMap(
      'loadWorkspaceVoicePlaybackSource',
      arguments: <String, Object?>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayWorkspaceTextDocument> loadWorkspaceTextDocument(
    String relativePath,
  ) async => OpenCrayWorkspaceTextDocument.fromMap(
    await _invokeMap(
      'loadWorkspaceTextDocument',
      arguments: <String, Object?>{'relativePath': relativePath},
    ),
  );

  @override
  Future<void> openWorkspaceEntry(String relativePath) =>
      _methodChannel.invokeMethod<void>('openWorkspaceEntry', <String, Object?>{
        'relativePath': relativePath,
      });

  @override
  Future<void> openExternalUri(String uri) => _methodChannel.invokeMethod<void>(
    'openExternalUri',
    <String, Object?>{'uri': uri},
  );

  @override
  Future<void> copyRichTextToClipboard({
    required String plainText,
    String? htmlText,
  }) => _methodChannel.invokeMethod<void>(
    'copyRichTextToClipboard',
    <String, Object?>{'plainText': plainText, 'htmlText': htmlText},
  );

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceFolder({
    required String parentRelativePath,
    required String name,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'createWorkspaceFolder',
      arguments: <String, Object?>{
        'parentRelativePath': parentRelativePath,
        'name': name,
      },
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceTextFile({
    required String parentRelativePath,
    required String name,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'createWorkspaceTextFile',
      arguments: <String, Object?>{
        'parentRelativePath': parentRelativePath,
        'name': name,
      },
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> renameWorkspaceEntry({
    required String targetRelativePath,
    required String newName,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'renameWorkspaceEntry',
      arguments: <String, Object?>{
        'targetRelativePath': targetRelativePath,
        'newName': newName,
      },
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> deleteWorkspaceEntries(
    List<String> relativePaths,
  ) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'deleteWorkspaceEntries',
      arguments: <String, Object?>{'relativePaths': relativePaths},
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> saveWorkspaceTextDocument({
    required String targetRelativePath,
    required String content,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'saveWorkspaceTextDocument',
      arguments: <String, Object?>{
        'targetRelativePath': targetRelativePath,
        'content': content,
      },
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> pasteWorkspaceEntries({
    required List<String> sourceRelativePaths,
    required String destinationRelativePath,
    required bool move,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'pasteWorkspaceEntries',
      arguments: <String, Object?>{
        'sourceRelativePaths': sourceRelativePaths,
        'destinationRelativePath': destinationRelativePath,
        'move': move,
      },
    ),
  );

  @override
  Future<void> shareWorkspaceEntries(List<String> relativePaths) =>
      _methodChannel.invokeMethod<void>(
        'shareWorkspaceEntries',
        <String, Object?>{'relativePaths': relativePaths},
      );

  @override
  Future<OpenCraySavedWorkspaceMediaAttachment> saveWorkspaceMediaAttachment({
    required String relativePath,
    required String kind,
  }) async => OpenCraySavedWorkspaceMediaAttachment.fromMap(
    await _invokeMap(
      'saveWorkspaceMediaAttachment',
      arguments: <String, Object?>{'relativePath': relativePath, 'kind': kind},
    ),
  );

  @override
  Future<void> showNativeToast(String message) =>
      _methodChannel.invokeMethod<void>('showNativeToast', <String, Object?>{
        'message': message,
      });

  @override
  Future<List<OpenCraySettingsImageAsset>> listSettingsImageAssets() async =>
      (await _invokeList('listSettingsImageAssets'))
          .map(_requireMap)
          .map(OpenCraySettingsImageAsset.fromMap)
          .toList(growable: false);

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async =>
      (await _invokeList('pickSettingsImageAssets'))
          .map(_requireMap)
          .map(OpenCraySettingsImageAsset.fromMap)
          .toList(growable: false);

  @override
  Future<List<OpenCraySettingsImageAsset>> importSettingsImageAssets(
    List<String> uriStrings,
  ) async =>
      (await _invokeList(
            'importSettingsImageAssets',
            arguments: <String, Object?>{'uriStrings': uriStrings},
          ))
          .map(_requireMap)
          .map(OpenCraySettingsImageAsset.fromMap)
          .toList(growable: false);

  @override
  Future<List<OpenCrayAgentSnapshot>> listAgents() async =>
      (await _invokeList('listAgents'))
          .map(_requireMap)
          .map(OpenCrayAgentSnapshot.fromMap)
          .toList(growable: false);

  @override
  Future<OpenCrayAgentSnapshot?> loadActiveAgent() async {
    final payload = await _invokeNullableMap('loadActiveAgent');
    if (payload == null) {
      return null;
    }
    return OpenCrayAgentSnapshot.fromMap(payload);
  }

  @override
  Future<OpenCrayAgentSnapshot> createAgent(
    OpenCrayAgentCreateRequest request,
  ) async => OpenCrayAgentSnapshot.fromMap(
    await _invokeMap('createAgent', arguments: request.toMap()),
  );

  @override
  Future<OpenCrayAgentSnapshot?> selectAgent(String agentId) async {
    final payload = await _invokeNullableMap(
      'selectAgent',
      arguments: <String, Object?>{'agentId': agentId},
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayAgentSnapshot.fromMap(payload);
  }

  @override
  Future<OpenCraySoulVisualIdentity?> loadSoulVisualIdentity() async {
    final payload = await _invokeNullableMap('loadSoulVisualIdentity');
    if (payload == null) {
      return null;
    }
    return OpenCraySoulVisualIdentity.fromMap(payload);
  }

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulPrimaryPortrait(
    OpenCrayImageReferenceSource source,
  ) async {
    final payload = await _invokeNullableMap(
      'saveSoulPrimaryPortrait',
      arguments: <String, Object?>{'source': source.toMap()},
    );
    if (payload == null) {
      return null;
    }
    return OpenCraySoulVisualIdentity.fromMap(payload);
  }

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulReferenceImage({
    required String refId,
    required OpenCrayImageReferenceSource source,
  }) async {
    final payload = await _invokeNullableMap(
      'saveSoulReferenceImage',
      arguments: <String, Object?>{'refId': refId, 'source': source.toMap()},
    );
    if (payload == null) {
      return null;
    }
    return OpenCraySoulVisualIdentity.fromMap(payload);
  }

  @override
  Future<List<OpenCrayImageReference>> listMemoryImageReferences(
    String memoryId,
  ) async =>
      (await _invokeList(
            'listMemoryImageReferences',
            arguments: <String, Object?>{'memoryId': memoryId},
          ))
          .map(_requireMap)
          .map(OpenCrayImageReference.fromMap)
          .toList(growable: false);

  @override
  Future<OpenCrayMemoryImageReferenceResult?> attachMemoryImageReference({
    required String memoryId,
    required OpenCrayImageReferenceSource source,
    String? preferredMode,
  }) async {
    final payload = await _invokeNullableMap(
      'attachMemoryImageReference',
      arguments: <String, Object?>{
        'memoryId': memoryId,
        'source': source.toMap(),
        'preferredMode': preferredMode,
      },
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayMemoryImageReferenceResult.fromMap(payload);
  }

  @override
  Future<OpenCraySettingsOverviewSnapshot> loadSettingsOverview() async =>
      _parseSettingsOverview(await _invokeMap('loadSettingsOverview'));

  @override
  Stream<OpenCraySettingsOverviewSnapshot> watchSettingsOverview() =>
      _settingsOverviewChannel
          .receiveBroadcastStream()
          .map(_requireMap)
          .map(_parseSettingsOverview);

  @override
  Future<OpenCraySettingsDetailSnapshot> loadSettingsDetail(
    String routeId,
  ) async => _parseSettingsDetail(
    await _invokeMap(
      'loadSettingsDetail',
      arguments: <String, Object?>{'routeId': routeId},
    ),
  );

  @override
  Future<OpenCrayNotificationSettingsSnapshot>
  loadNotificationSettings() async =>
      OpenCrayNotificationSettingsSnapshot.fromMap(
        await _invokeMap('loadNotificationSettings'),
      );

  @override
  Future<OpenCrayNotificationSettingsSnapshot> saveNotificationSettings(
    OpenCrayNotificationSettingsSnapshot snapshot,
  ) async => OpenCrayNotificationSettingsSnapshot.fromMap(
    await _invokeMap('saveNotificationSettings', arguments: snapshot.toMap()),
  );

  @override
  Future<OpenCrayStrongBackgroundSnapshot>
  loadStrongBackgroundSnapshot() async =>
      OpenCrayStrongBackgroundSnapshot.fromMap(
        await _invokeMap('loadStrongBackgroundSnapshot'),
      );

  @override
  Future<OpenCrayStrongBackgroundActionResult> performStrongBackgroundAction(
    String actionId,
  ) async => OpenCrayStrongBackgroundActionResult.fromMap(
    await _invokeMap(
      'performStrongBackgroundAction',
      arguments: <String, Object?>{'actionId': actionId},
    ),
  );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      OpenCrayNetworkSearchConfigSnapshot.fromMap(
        await _invokeMap('loadNetworkSearchConfig'),
      );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<OpenCrayNetworkSearchSlotSnapshot> slots,
  ) async => OpenCrayNetworkSearchConfigSnapshot.fromMap(
    await _invokeMap(
      'saveNetworkSearchConfig',
      arguments: <String, Object?>{
        'slots': slots.map((slot) => slot.toMap()).toList(growable: false),
      },
    ),
  );

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> loadMediaSpeechConfig() async =>
      OpenCrayMediaSpeechConfigSnapshot.fromMap(
        await _invokeMap('loadMediaSpeechConfig'),
      );

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    OpenCrayMediaSpeechConfigSnapshot snapshot,
  ) async => OpenCrayMediaSpeechConfigSnapshot.fromMap(
    await _invokeMap('saveMediaSpeechConfig', arguments: snapshot.toMap()),
  );

  @override
  Future<OpenCraySandboxSettingsSnapshot> loadSandboxSettings() async =>
      OpenCraySandboxSettingsSnapshot.fromMap(
        await _invokeMap('loadSandboxSettings'),
      );

  @override
  Future<OpenCraySandboxSettingsSnapshot> saveSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  ) async => OpenCraySandboxSettingsSnapshot.fromMap(
    await _invokeMap('saveSandboxSettings', arguments: snapshot.toMap()),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig() async =>
      OpenCrayLlmConfigSnapshot.fromMap(await _invokeMap('loadLlmConfig'));

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
    await _invokeMap(
      'saveLlmConfig',
      arguments: <String, Object?>{
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
      },
    ),
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
    await _invokeMap(
      'saveCustomLlmProvider',
      arguments: <String, Object?>{
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
      },
    ),
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
    await _invokeMap(
      'validateLlmConfig',
      arguments: <String, Object?>{
        'providerId': providerId,
        'protocol': protocol,
        'baseUrl': baseUrl,
        'apiKey': apiKey,
        'model': model,
        'reasoningEffort': reasoningEffort,
      },
    ),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> downloadOnDeviceLlmModel(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _invokeMap(
      'downloadOnDeviceLlmModel',
      arguments: <String, Object?>{'modelId': modelId},
    ),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> cancelOnDeviceLlmModelDownload(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _invokeMap(
      'cancelOnDeviceLlmModelDownload',
      arguments: <String, Object?>{'modelId': modelId},
    ),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> deleteOnDeviceLlmModel(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _invokeMap(
      'deleteOnDeviceLlmModel',
      arguments: <String, Object?>{'modelId': modelId},
    ),
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot>
  loadPersonalizationConfig() async =>
      OpenCrayPersonalizationConfigSnapshot.fromMap(
        await _invokeMap('loadPersonalizationConfig'),
      );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _invokeMap(
      'savePersonalizationConfig',
      arguments: <String, Object?>{
        'presetId': presetId,
        'customLabel': customLabel,
        'customGuidance': customGuidance,
      },
    ),
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _invokeMap(
      'setAppLanguage',
      arguments: <String, Object?>{'languageId': languageId},
    ),
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _invokeMap(
      'runPersonalizationReset',
      arguments: <String, Object?>{'scopeId': scopeId},
    ),
  );

  @override
  Future<OpenCrayTwinImportSourceProbeSnapshot> probeTwinImportSource(
    String filePath,
  ) async => OpenCrayTwinImportSourceProbeSnapshot.fromMap(
    await _invokeMap(
      'probeTwinImportSource',
      arguments: <String, Object?>{'filePath': filePath},
    ),
  );

  @override
  Future<OpenCrayMcpSettingsSnapshot> loadMcpSettings() async =>
      OpenCrayMcpSettingsSnapshot.fromMap(await _invokeMap('loadMcpSettings'));

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpMasterEnabled(bool enabled) async =>
      OpenCrayMcpSettingsSnapshot.fromMap(
        await _invokeMap(
          'setMcpMasterEnabled',
          arguments: <String, Object?>{'enabled': enabled},
        ),
      );

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  }) async => OpenCrayMcpSettingsSnapshot.fromMap(
    await _invokeMap(
      'setMcpServerEnabled',
      arguments: <String, Object?>{'serverId': serverId, 'enabled': enabled},
    ),
  );

  @override
  Future<OpenCraySafetySettingsSnapshot> loadSafetySettings() async =>
      OpenCraySafetySettingsSnapshot.fromMap(
        await _invokeMap('loadSafetySettings'),
      );

  @override
  Future<bool> authorizeExternalAccessLocation(String locationId) async =>
      await _methodChannel.invokeMethod<bool>(
        'authorizeExternalAccessLocation',
        <String, Object?>{'locationId': locationId},
      ) ??
      false;

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
    await _invokeMap(
      'saveSafetySettings',
      arguments: <String, Object?>{
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
      },
    ),
  );

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
    int? suggestedLimit,
  }) async => OpenCraySkillsSnapshot.fromMap(
    await _invokeMap(
      'loadSkillsSnapshot',
      arguments: () {
        final arguments = <String, Object?>{};
        if (query.trim().isNotEmpty) {
          arguments['query'] = query;
          if (suggestedLimit != null) {
            arguments['suggestedLimit'] = suggestedLimit;
          }
        }
        return arguments.isEmpty ? null : arguments;
      }(),
    ),
  );

  @override
  Stream<OpenCraySkillsSnapshot> watchSkillsSnapshot() => _skillsSnapshotChannel
      .receiveBroadcastStream()
      .map(_requireMap)
      .map(OpenCraySkillsSnapshot.fromMap);

  @override
  Future<void> setSkillEnabled(String skillId, bool enabled) =>
      _methodChannel.invokeMethod<void>('setSkillEnabled', <String, Object?>{
        'skillId': skillId,
        'enabled': enabled,
      });

  @override
  Future<String?> refreshSkills() async =>
      await _methodChannel.invokeMethod<String>('refreshSkills');

  @override
  Future<String?> checkInstalledSkillUpdates({String skillId = ''}) async =>
      await _methodChannel.invokeMethod<String>(
        'checkInstalledSkillUpdates',
        skillId.trim().isEmpty ? null : <String, Object?>{'skillId': skillId},
      );

  @override
  Future<String?> updateInstalledSkill(String skillId) async =>
      await _methodChannel.invokeMethod<String>(
        'updateInstalledSkill',
        <String, Object?>{'skillId': skillId},
      );

  @override
  Future<OpenCraySkillSourceInspectionSnapshot> inspectSkillSource(
    String sourceRef,
  ) async => OpenCraySkillSourceInspectionSnapshot.fromMap(
    await _invokeMap(
      'inspectSkillSource',
      arguments: <String, Object?>{'sourceRef': sourceRef},
    ),
  );

  @override
  Future<String?> installSkillSource(
    String sourceRef, {
    String selectedSkillName = '',
  }) async => await _methodChannel
      .invokeMethod<String>('installSkillSource', <String, Object?>{
        'sourceRef': sourceRef,
        if (selectedSkillName.trim().isNotEmpty)
          'selectedSkillName': selectedSkillName,
      });

  @override
  Future<String?> installSkillSourceBatch(
    String sourceRef, {
    List<String> selectedSkillNames = const <String>[],
  }) async => await _methodChannel
      .invokeMethod<String>('installSkillSourceBatch', <String, Object?>{
        'sourceRef': sourceRef,
        if (selectedSkillNames.isNotEmpty)
          'selectedSkillNames': selectedSkillNames,
      });

  @override
  Future<String?> installSuggestedSkill(String skillId) async =>
      await _methodChannel.invokeMethod<String>(
        'installSuggestedSkill',
        <String, Object?>{'skillId': skillId},
      );

  @override
  Future<String?> deleteInstalledSkill(String skillId) async =>
      await _methodChannel.invokeMethod<String>(
        'deleteInstalledSkill',
        <String, Object?>{'skillId': skillId},
      );

  @override
  Future<OpenCraySkillInstructionsSnapshot?> loadSkillInstructions(
    String skillId,
  ) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      'loadSkillInstructions',
      <String, Object?>{'skillId': skillId},
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
    final payload = await _methodChannel.invokeMethod<Object?>(
      'loadSuggestedSkillInstructions',
      <String, Object?>{
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
      OpenCrayChatSnapshot.fromMap(await _invokeMap('loadChatSnapshot'));

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() => _chatSnapshotChannel
      .receiveBroadcastStream()
      .map(_requireMap)
      .map(OpenCrayChatSnapshot.fromMap);

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      OpenCrayChatRuntimeSnapshot.fromMap(
        await _invokeMap('loadChatRuntimeSnapshot'),
      );

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() =>
      _chatRuntimeSnapshotChannel
          .receiveBroadcastStream()
          .map(_requireMap)
          .map(OpenCrayChatRuntimeSnapshot.fromMap);

  @override
  Stream<OpenCrayChatLiveAssistantDraftEvent> watchLiveAssistantDraftEvents() =>
      _liveAssistantDraftChannel
          .receiveBroadcastStream()
          .map(_requireMap)
          .map(OpenCrayChatLiveAssistantDraftEvent.fromMap);

  @override
  Stream<OpenCrayChatRuntimeEventDelta> watchRuntimeEventDeltas() =>
      _runtimeEventDeltaChannel
          .receiveBroadcastStream()
          .map(_requireMap)
          .map(OpenCrayChatRuntimeEventDelta.fromMap);

  @override
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      'loadChatRunSnapshot',
      <String, Object?>{'runId': runId},
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayChatRunSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      OpenCrayMemoryDebugSnapshot.fromMap(
        await _invokeMap('loadMemoryDebugSnapshot'),
      );

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async =>
      OpenCrayMemoryDebugLinksSnapshot.fromMap(
        await _invokeMap('loadMemoryDebugLinksSnapshot'),
      );

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      OpenCraySoulDebugSnapshot.fromMap(
        await _invokeMap('loadSoulDebugSnapshot'),
      );

  @override
  Future<OpenCrayDebugPythonRunResult> runDebugPythonScript({
    required String fileName,
    required String scriptText,
  }) async => OpenCrayDebugPythonRunResult.fromMap(
    await _invokeMap(
      'runDebugPythonScript',
      arguments: <String, Object?>{
        'fileName': fileName,
        'scriptText': scriptText,
      },
    ),
  );

  @override
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async => OpenCrayMemoryDebugSearchSnapshot.fromMap(
    await _invokeMap(
      'searchMemoryDebug',
      arguments: <String, Object?>{
        'query': query,
        'maxResults': maxResults,
        'minScore': minScore,
      },
    ),
  );

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async => OpenCrayMemoryDebugSliceSnapshot.fromMap(
    await _invokeMap(
      'getMemoryDebugSlice',
      arguments: <String, Object?>{
        'path': path,
        'fromLine': fromLine,
        'lines': lines,
      },
    ),
  );

  @override
  Future<void> applyMemoryDebugAction({
    required String recordId,
    required String actionId,
  }) => _methodChannel.invokeMethod<void>(
    'applyMemoryDebugAction',
    <String, Object?>{'recordId': recordId, 'actionId': actionId},
  );

  @override
  Future<OpenCrayChatRunSnapshot?> waitForChatRun(
    String runId, {
    Duration timeout = const Duration(seconds: 15),
  }) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      'waitForChatRun',
      <String, Object?>{'runId': runId, 'timeoutMs': timeout.inMilliseconds},
    );
    if (payload == null) {
      return null;
    }
    return OpenCrayChatRunSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<void> refreshSandboxSessionInfo() =>
      _methodChannel.invokeMethod<void>('refreshSandboxSessionInfo');

  @override
  Future<void> createChatSession() =>
      _methodChannel.invokeMethod<void>('createChatSession');

  @override
  Future<void> copyChatSession(String sessionId) =>
      _methodChannel.invokeMethod<void>('copyChatSession', <String, Object?>{
        'sessionId': sessionId,
      });

  @override
  Future<void> deleteChatSession(String sessionId) =>
      _methodChannel.invokeMethod<void>('deleteChatSession', <String, Object?>{
        'sessionId': sessionId,
      });

  @override
  Future<void> selectChatSession(String sessionId) =>
      _methodChannel.invokeMethod<void>('selectChatSession', <String, Object?>{
        'sessionId': sessionId,
      });

  @override
  Future<void> branchChatSessionFromMessage({
    required String sessionId,
    required String messageId,
  }) => _methodChannel.invokeMethod<void>(
    'branchChatSessionFromMessage',
    <String, Object?>{'sessionId': sessionId, 'messageId': messageId},
  );

  @override
  Future<void> deleteChatMessage({
    required String sessionId,
    required String messageId,
  }) => _methodChannel.invokeMethod<void>(
    'deleteChatMessage',
    <String, Object?>{'sessionId': sessionId, 'messageId': messageId},
  );

  @override
  Future<void> recallChatMessage({
    required String sessionId,
    required String messageId,
  }) => _methodChannel.invokeMethod<void>(
    'recallChatMessage',
    <String, Object?>{'sessionId': sessionId, 'messageId': messageId},
  );

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async {
    final payload = await _methodChannel.invokeListMethod<Object?>(
      'pickChatAttachments',
      <String, Object?>{'kind': kind.wireValue},
    );
    return (payload ?? const <Object?>[])
        .map(_requireMap)
        .map(OpenCrayChatDraftAttachment.fromMap)
        .toList(growable: false);
  }

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments =
        const <OpenCrayChatDraftAttachment>[],
  }) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      'submitChatMessage',
      <String, Object?>{
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
  Future<void> approveChatApproval(String approvalId) =>
      _methodChannel.invokeMethod<void>(
        'approveChatApproval',
        <String, Object?>{'runId': approvalId, 'taskId': approvalId},
      );

  @override
  Future<void> approveChatApprovalForSession(String approvalId) =>
      _methodChannel.invokeMethod<void>(
        'approveChatApprovalForSession',
        <String, Object?>{'runId': approvalId, 'taskId': approvalId},
      );

  @override
  Future<void> rejectChatApproval(String approvalId) =>
      _methodChannel.invokeMethod<void>('rejectChatApproval', <String, Object?>{
        'runId': approvalId,
        'taskId': approvalId,
      });

  @override
  Future<void> interruptChatRun(String runIdOrTaskId) =>
      _methodChannel.invokeMethod<void>('interruptChatRun', <String, Object?>{
        'runId': runIdOrTaskId,
        'taskId': runIdOrTaskId,
      });

  @override
  Future<void> retryChatRun(String runIdOrTaskId) =>
      _methodChannel.invokeMethod<void>('retryChatRun', <String, Object?>{
        'runId': runIdOrTaskId,
        'taskId': runIdOrTaskId,
      });

  static Future<Map<Object?, Object?>> _invokeMap(
    String method, {
    Map<String, Object?>? arguments,
  }) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      method,
      arguments,
    );
    return _requireMap(payload);
  }

  static Future<Map<Object?, Object?>?> _invokeNullableMap(
    String method, {
    Map<String, Object?>? arguments,
  }) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      method,
      arguments,
    );
    if (payload == null) {
      return null;
    }
    return _requireMap(payload);
  }

  static Future<List<Object?>> _invokeList(
    String method, {
    Map<String, Object?>? arguments,
  }) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      method,
      arguments,
    );
    return _requireList(payload);
  }

  static Map<Object?, Object?> _requireMap(Object? payload) {
    final map = payload as Map<Object?, Object?>?;
    if (map == null) {
      throw const FormatException('Expected a map payload from host bridge.');
    }
    return map;
  }

  static OpenCrayShellSnapshot _parseShellSnapshot(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayShellSnapshot.fromMap(
      payload,
      defaultHostSummary: 'Flutter shell is attached to a host bridge.',
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

  static List<Object?> _requireList(Object? payload) {
    final list = payload as List<Object?>?;
    if (list == null) {
      return const <Object?>[];
    }
    return list;
  }

  static List<String>? _listOfStrings(Object? payload) {
    final list = payload as List<Object?>?;
    if (list == null) {
      return null;
    }
    return list.map((value) => value as String? ?? '').toList(growable: false);
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
