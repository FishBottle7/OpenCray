import 'settings_models.dart';

abstract interface class SettingsFacade {
  Future<SettingsOverviewSnapshot> loadOverview();

  Stream<SettingsOverviewSnapshot> watchOverview();

  Future<SettingsDetailSnapshot> loadDetail(SettingsPage page);

  Future<LlmConfigSnapshot> loadLlmConfig();

  Future<LlmConfigSnapshot> saveLlmConfig({
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

  Future<LlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
  });

  Future<PersonalizationConfigSnapshot> loadPersonalizationConfig();

  Future<PersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  });

  Future<PersonalizationConfigSnapshot> setAppLanguage(String languageId);

  Future<PersonalizationConfigSnapshot> runPersonalizationReset(String scopeId);

  Future<McpSettingsSnapshot> loadMcpSettings();

  Future<McpSettingsSnapshot> setMcpMasterEnabled(bool enabled);

  Future<McpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  });
}
