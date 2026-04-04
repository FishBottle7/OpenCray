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

abstract interface class OpenCrayHostBridge {
  Future<OpenCrayShellSnapshot> loadShellSnapshot();

  Stream<OpenCrayShellSnapshot> watchShellSnapshot();

  Future<OpenCrayFilesSnapshot> loadFilesSnapshot();

  Future<OpenCraySandboxPreviewEmbedConfig> resolveSandboxPreviewEmbedConfig(
    String previewUrl,
  );

  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  );

  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(String relativePath);

  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  );

  Future<OpenCrayWorkspaceTextDocument> loadWorkspaceTextDocument(
    String relativePath,
  );

  Future<void> openWorkspaceEntry(String relativePath);

  Future<void> openExternalUri(String uri);

  Future<void> copyRichTextToClipboard({
    required String plainText,
    String? htmlText,
  });

  Future<OpenCrayFilesSnapshot> createWorkspaceFolder({
    required String parentRelativePath,
    required String name,
  });

  Future<OpenCrayFilesSnapshot> createWorkspaceTextFile({
    required String parentRelativePath,
    required String name,
  });

  Future<OpenCrayFilesSnapshot> renameWorkspaceEntry({
    required String targetRelativePath,
    required String newName,
  });

  Future<OpenCrayFilesSnapshot> deleteWorkspaceEntries(
    List<String> relativePaths,
  );

  Future<OpenCrayFilesSnapshot> saveWorkspaceTextDocument({
    required String targetRelativePath,
    required String content,
  });

  Future<OpenCrayFilesSnapshot> pasteWorkspaceEntries({
    required List<String> sourceRelativePaths,
    required String destinationRelativePath,
    required bool move,
  });

  Future<void> shareWorkspaceEntries(List<String> relativePaths);

  Future<void> showNativeToast(String message);

  Future<List<OpenCraySettingsImageAsset>> listSettingsImageAssets();

  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets();

  Future<List<OpenCraySettingsImageAsset>> importSettingsImageAssets(
    List<String> uriStrings,
  );

  Future<List<OpenCrayAgentSnapshot>> listAgents();

  Future<OpenCrayAgentSnapshot?> loadActiveAgent();

  Future<OpenCrayAgentSnapshot> createAgent(OpenCrayAgentCreateRequest request);

  Future<OpenCrayAgentSnapshot?> selectAgent(String agentId);

  Future<OpenCraySoulVisualIdentity?> loadSoulVisualIdentity();

  Future<OpenCraySoulVisualIdentity?> saveSoulPrimaryPortrait(
    OpenCrayImageReferenceSource source,
  );

  Future<OpenCraySoulVisualIdentity?> saveSoulReferenceImage({
    required String refId,
    required OpenCrayImageReferenceSource source,
  });

  Future<List<OpenCrayImageReference>> listMemoryImageReferences(
    String memoryId,
  );

  Future<OpenCrayMemoryImageReferenceResult?> attachMemoryImageReference({
    required String memoryId,
    required OpenCrayImageReferenceSource source,
    String? preferredMode,
  });

  Future<OpenCraySettingsOverviewSnapshot> loadSettingsOverview();

  Stream<OpenCraySettingsOverviewSnapshot> watchSettingsOverview();

  Future<OpenCraySettingsDetailSnapshot> loadSettingsDetail(String routeId);

  Future<OpenCrayNotificationSettingsSnapshot> loadNotificationSettings();

  Future<OpenCrayNotificationSettingsSnapshot> saveNotificationSettings(
    OpenCrayNotificationSettingsSnapshot snapshot,
  );

  Future<OpenCrayStrongBackgroundSnapshot> loadStrongBackgroundSnapshot();

  Future<OpenCrayStrongBackgroundActionResult> performStrongBackgroundAction(
    String actionId,
  );

  Future<OpenCrayNetworkSearchConfigSnapshot> loadNetworkSearchConfig();

  Future<OpenCrayNetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<OpenCrayNetworkSearchSlotSnapshot> slots,
  );

  Future<OpenCrayMediaSpeechConfigSnapshot> loadMediaSpeechConfig();

  Future<OpenCrayMediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    OpenCrayMediaSpeechConfigSnapshot snapshot,
  );

  Future<OpenCraySandboxSettingsSnapshot> loadSandboxSettings();

  Future<OpenCraySandboxSettingsSnapshot> saveSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  );

  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig();

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
    String? openAiPromptCacheKeyStrategy,
    String? openAiPromptCacheRetention,
    bool? anthropicPromptCachingEnabled,
    String? anthropicPromptCacheTtl,
  });

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
    String? openAiPromptCacheKeyStrategy,
    String? openAiPromptCacheRetention,
    bool? anthropicPromptCachingEnabled,
    String? anthropicPromptCacheTtl,
  });

  Future<OpenCrayLlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
  });

  Future<OpenCrayPersonalizationConfigSnapshot> loadPersonalizationConfig();

  Future<OpenCrayPersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  });

  Future<OpenCrayPersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  );

  Future<OpenCrayPersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  );

  Future<OpenCrayTwinImportSourceProbeSnapshot> probeTwinImportSource(
    String filePath,
  );

  Future<OpenCrayMcpSettingsSnapshot> loadMcpSettings();

  Future<OpenCrayMcpSettingsSnapshot> setMcpMasterEnabled(bool enabled);

  Future<OpenCrayMcpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  });

  Future<OpenCraySafetySettingsSnapshot> loadSafetySettings();

  Future<bool> authorizeExternalAccessLocation(String locationId);

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
  });

  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
    int? suggestedLimit,
  });

  Stream<OpenCraySkillsSnapshot> watchSkillsSnapshot();

  Future<void> setSkillEnabled(String skillId, bool enabled);

  Future<String?> refreshSkills();

  Future<String?> checkInstalledSkillUpdates({String skillId = ''});

  Future<String?> updateInstalledSkill(String skillId);

  Future<OpenCraySkillSourceInspectionSnapshot> inspectSkillSource(
    String sourceRef,
  );

  Future<String?> installSkillSource(
    String sourceRef, {
    String selectedSkillName = '',
  });

  Future<String?> installSkillSourceBatch(
    String sourceRef, {
    List<String> selectedSkillNames = const <String>[],
  });

  Future<String?> installSuggestedSkill(String skillId);

  Future<String?> deleteInstalledSkill(String skillId);

  Future<OpenCraySkillInstructionsSnapshot?> loadSkillInstructions(
    String skillId,
  );

  Future<OpenCraySkillInstructionsSnapshot?> loadSuggestedSkillInstructions(
    String sourceRef, {
    String selectedSkillName = '',
  });

  Future<OpenCrayChatSnapshot> loadChatSnapshot();

  Stream<OpenCrayChatSnapshot> watchChatSnapshot();

  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot();

  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot();

  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId);

  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot();

  Future<OpenCrayMemoryDebugLinksSnapshot> loadMemoryDebugLinksSnapshot();

  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot();

  Future<OpenCrayDebugPythonRunResult> runDebugPythonScript({
    required String fileName,
    required String scriptText,
  });

  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  });

  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  });

  Future<void> applyMemoryDebugAction({
    required String recordId,
    required String actionId,
  });

  Future<OpenCrayChatRunSnapshot?> waitForChatRun(
    String runId, {
    Duration timeout,
  });

  Future<void> refreshSandboxSessionInfo();

  Future<void> createChatSession();

  Future<void> copyChatSession(String sessionId);

  Future<void> deleteChatSession(String sessionId);

  Future<void> selectChatSession(String sessionId);

  Future<void> branchChatSessionFromMessage({
    required String sessionId,
    required String messageId,
  });

  Future<void> deleteChatMessage({
    required String sessionId,
    required String messageId,
  });

  Future<void> recallChatMessage({
    required String sessionId,
    required String messageId,
  });

  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  });

  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments,
  });

  Future<void> approveChatApproval(String approvalId);

  Future<void> approveChatApprovalForSession(String approvalId);

  Future<void> rejectChatApproval(String approvalId);

  Future<void> interruptChatRun(String runIdOrTaskId);

  Future<void> retryChatRun(String runIdOrTaskId);
}
