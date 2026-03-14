import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/features/settings/settings.dart';

void main() {
  testWidgets('standalone llm page auto-saves when a field loses focus', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        enabled: false,
        providerId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            isCustom: false,
          ),
        ],
        providerName: 'OpenAI',
        providerNotes: '',
        baseUrl: 'https://api.openai.com/v1',
        apiKey: '',
        model: 'gpt-4o-mini',
        reasoningEffort: 'medium',
        systemPrompt: '',
        helperText: 'Helper text',
      ),
      validationResult: const LlmValidationResult(
        isSuccess: true,
        message: 'Validated.',
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.llm,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(Switch), findsNothing);

    await tester.enterText(find.byType(TextField).at(1), 'secret');
    await tester.tap(find.byType(TextField).at(2));
    await tester.pumpAndSettle();

    expect(facade.saveCallCount, 1);
    expect(facade.llmConfig.apiKey, 'secret');
    expect(facade.llmConfig.enabled, isTrue);
  });

  testWidgets(
    'standalone llm page shows validation feedback when validation fails',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          enabled: true,
          providerId: 'openai',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'openai',
              title: 'OpenAI',
              subtitle: 'Official OpenAI-compatible endpoint.',
              defaultBaseUrl: 'https://api.openai.com/v1',
              defaultModel: 'gpt-4o-mini',
              isCustom: false,
            ),
          ],
          providerName: 'OpenAI',
          providerNotes: '',
          baseUrl: 'https://api.openai.com/v1',
          apiKey: 'secret',
          model: 'gpt-4o-mini',
          reasoningEffort: 'medium',
          systemPrompt: '',
          helperText: 'Helper text',
        ),
        validationResult: const LlmValidationResult(
          isSuccess: false,
          message: 'Invalid API key.',
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.llm,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('Validate Model'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));

      expect(find.text('Invalid API key.'), findsOneWidget);
      expect(facade.saveCallCount, 0);
    },
  );

  testWidgets(
    'custom provider supports Anthropic protocol and xhigh reasoning',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          enabled: true,
          providerId: 'custom',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'custom',
              title: 'Custom provider',
              subtitle: 'Any OpenAI-compatible or Anthropic endpoint.',
              defaultBaseUrl: '',
              defaultModel: '',
              isCustom: true,
            ),
          ],
          providerName: 'Custom provider',
          providerNotes: '',
          baseUrl: 'https://api.example.com/v1',
          apiKey: 'secret',
          model: 'claude-3-7-sonnet',
          reasoningEffort: 'medium',
          systemPrompt: '',
          helperText: 'Helper text',
        ),
        validationResult: const LlmValidationResult(
          isSuccess: true,
          message: 'Validated.',
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.llm,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('OpenAI compatible'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Anthropic').last);
      await tester.pumpAndSettle();

      expect(facade.saveCallCount, 1);
      expect(facade.llmConfig.protocol, 'anthropic');

      await tester.ensureVisible(find.text('Medium'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Medium'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('XHigh'));
      await tester.pumpAndSettle();

      expect(facade.saveCallCount, 2);
      expect(facade.llmConfig.reasoningEffort, 'xhigh');
    },
  );
}

class _FakeSettingsFacade implements SettingsFacade {
  _FakeSettingsFacade({
    required this.llmConfig,
    required this.validationResult,
  });

  LlmConfigSnapshot llmConfig;
  final LlmValidationResult validationResult;
  final PersonalizationConfigSnapshot personalizationConfig =
      const PersonalizationConfigSnapshot(
        title: 'Personalization',
        subtitle: 'Test snapshot',
        introTitle: 'Intro',
        introBody: 'Intro body',
        introHelper: 'Intro helper',
        presetsTitle: 'Presets',
        presetsHelper: 'Pick a preset',
        presets: <PersonalizationPresetOption>[
          PersonalizationPresetOption(
            id: 'steady',
            title: 'Steady',
            summary: 'Direct and calm',
            voice: 'Quiet',
            status: 'Selected',
            isSelected: true,
          ),
        ],
        selectedPresetId: 'steady',
        customOverlayTitle: 'Overlay',
        customOverlayHelper: 'Helper',
        customLabelHint: 'Label',
        customLabelHelper: 'Label helper',
        customGuidanceHint: 'Guidance',
        customGuidanceHelper: 'Guidance helper',
        customLabel: '',
        customGuidance: '',
        behaviorDefaultsTitle: 'Behavior defaults',
        appLanguageTitle: 'App language',
        appLanguageOptions: <PersonalizationLanguageOption>[
          PersonalizationLanguageOption(
            id: 'en',
            title: 'English',
            isSelected: true,
          ),
          PersonalizationLanguageOption(
            id: 'zh-CN',
            title: '中文',
            isSelected: false,
          ),
        ],
        selectedAppLanguageId: 'en',
        livePreviewTitle: 'Preview',
        livePreviewName: 'Steady',
        livePreviewSummary: 'Direct and calm',
        queueTitle: 'Queue',
        queueBody: 'Idle',
        queueIsIdle: true,
        lastResetTitle: 'Last reset',
        lastResetMessage: '',
        resetActions: <PersonalizationResetAction>[
          PersonalizationResetAction(
            scopeId: 'memory',
            title: 'Reset memory',
            scopeBody: 'Scope',
            retainBody: 'Retain',
            confirmationToken: 'RESET MEMORY',
            inputHint: 'Type RESET MEMORY',
            disabledGuidance: 'Disabled',
            typeExactGuidance: 'Type exact token',
            armedGuidance: 'Armed',
            isInputEnabled: true,
          ),
        ],
      );
  final McpSettingsSnapshot mcpSettings = const McpSettingsSnapshot(
    title: 'MCP',
    subtitle: 'Test snapshot',
    masterTitle: 'Enable MCP',
    masterSummary: 'Summary',
    masterEnabled: true,
    summaryLine: '1 server',
    serversTitle: 'Servers',
    serversHelper: 'Helper',
    masterDisabledTitle: 'Disabled',
    masterDisabledBody: 'Turn it on',
    servers: <McpServerSnapshot>[
      McpServerSnapshot(
        id: 'filesystem',
        title: 'Filesystem',
        statusLabel: 'Enabled',
        statusTone: 'positive',
        trustLine: 'Trust',
        authLine: 'Auth',
        readinessLine: 'Ready',
        transportLine: 'Transport',
        exposureLine: 'Exposure',
        guidance: 'Guidance',
        actionLabel: 'Disable',
        actionTurnsOn: false,
        isActionEnabled: true,
      ),
    ],
  );
  int saveCallCount = 0;

  @override
  Future<SettingsOverviewSnapshot> loadOverview() async =>
      const SettingsOverviewSnapshot(
        eyebrow: 'APP SHELL',
        title: 'Settings',
        subtitle: 'Access, providers, and personal defaults.',
        deviceTitle: 'OpenCray on this device',
        deviceSummary: 'Personalization: Quiet',
        entries: <SettingsHomeEntrySnapshot>[],
      );

  @override
  Stream<SettingsOverviewSnapshot> watchOverview() =>
      Stream<SettingsOverviewSnapshot>.empty();

  @override
  Future<SettingsDetailSnapshot> loadDetail(SettingsPage page) async =>
      SettingsDetailSnapshot(
        page: page,
        title: '',
        subtitle: '',
        sections: const <SettingsSectionSnapshot>[],
      );

  @override
  Future<LlmConfigSnapshot> loadLlmConfig() async => llmConfig;

  @override
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
  }) async {
    saveCallCount += 1;
    llmConfig = LlmConfigSnapshot(
      enabled: enabled,
      providerId: providerId,
      protocol: protocol,
      providerOptions: llmConfig.providerOptions,
      providerName: providerName,
      providerNotes: providerNotes,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
      helperText: llmConfig.helperText,
    );
    return llmConfig;
  }

  @override
  Future<LlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
  }) async => validationResult;

  @override
  Future<PersonalizationConfigSnapshot> loadPersonalizationConfig() async =>
      personalizationConfig;

  @override
  Future<PersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async => personalizationConfig;

  @override
  Future<PersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async => personalizationConfig;

  @override
  Future<PersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async => personalizationConfig;

  @override
  Future<McpSettingsSnapshot> loadMcpSettings() async => mcpSettings;

  @override
  Future<McpSettingsSnapshot> setMcpMasterEnabled(bool enabled) async =>
      mcpSettings;

  @override
  Future<McpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  }) async => mcpSettings;
}
