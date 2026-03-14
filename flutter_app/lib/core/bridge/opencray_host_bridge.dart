import '../models/opencray_chat_snapshot.dart';
import '../models/opencray_llm_config.dart';
import '../models/opencray_llm_validation.dart';
import '../models/opencray_mcp_settings.dart';
import '../models/opencray_personalization_config.dart';
import '../models/opencray_settings_snapshot.dart';
import '../models/opencray_shell_snapshot.dart';
import '../models/opencray_skills_snapshot.dart';

abstract interface class OpenCrayHostBridge {
  Future<OpenCrayShellSnapshot> loadShellSnapshot();

  Stream<OpenCrayShellSnapshot> watchShellSnapshot();

  Future<OpenCraySettingsOverviewSnapshot> loadSettingsOverview();

  Stream<OpenCraySettingsOverviewSnapshot> watchSettingsOverview();

  Future<OpenCraySettingsDetailSnapshot> loadSettingsDetail(String routeId);

  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig();

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

  Future<OpenCrayMcpSettingsSnapshot> loadMcpSettings();

  Future<OpenCrayMcpSettingsSnapshot> setMcpMasterEnabled(bool enabled);

  Future<OpenCrayMcpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  });

  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot();

  Stream<OpenCraySkillsSnapshot> watchSkillsSnapshot();

  Future<void> setSkillEnabled(String skillId, bool enabled);

  Future<String?> refreshSkills();

  Future<String?> installSuggestedSkill(String skillId);

  Future<String?> deleteInstalledSkill(String skillId);

  Future<OpenCraySkillInstructionsSnapshot?> loadSkillInstructions(
    String skillId,
  );

  Future<OpenCrayChatSnapshot> loadChatSnapshot();

  Stream<OpenCrayChatSnapshot> watchChatSnapshot();

  Future<void> createChatSession();

  Future<void> selectChatSession(String sessionId);

  Future<void> submitChatMessage(String text);
}
