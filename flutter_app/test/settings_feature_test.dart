import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/features/settings/settings.dart';

void main() {
  testWidgets('standalone llm page auto-saves when a field loses focus', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
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
          localeTag: 'en',
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
          localeTag: 'en',
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

  testWidgets(
    'about version page opens debug tools and renders memory trace details',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
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
      final debugBridge = _FakeDebugBridge(
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'final',
              runId: 'run-memory',
              taskId: 'task-memory',
              emittedAtEpochMs: 2000,
            ),
          ],
        ),
        runSnapshots: <String, OpenCrayChatRunSnapshot>{
          'run-memory': const OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-memory',
            taskId: 'task-memory',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: true,
            executionStatus: 'success',
            taskState: 'completed',
            responseFormat: 'json_final',
            memoryTrace: OpenCrayChatRunMemoryTraceSnapshot(
              matchedRecordCount: 2,
              injectedRecordCount: 1,
              omittedRecordCount: 1,
              queryTerms: <String>['chinese', 'gradle'],
              selected: <OpenCrayChatRunMemorySelectedSnapshot>[
                OpenCrayChatRunMemorySelectedSnapshot(
                  id: 'memory-user',
                  score: 420,
                  matchedTerms: <String>['chinese'],
                ),
              ],
              omitted: <OpenCrayChatRunMemoryOmittedSnapshot>[
                OpenCrayChatRunMemoryOmittedSnapshot(
                  id: 'memory-project',
                  reason: 'max_records',
                ),
              ],
              filteredCounts: <String, int>{'scope_mismatch': 1, 'expired': 2},
            ),
          ),
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.aboutVersion,
            standalone: true,
            debugBridge: debugBridge,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Debug tools'), findsOneWidget);
      expect(find.text('Context & Memory Trace'), findsOneWidget);

      await tester.tap(find.text('Context & Memory Trace'));
      await tester.pumpAndSettle();

      expect(find.text('Runtime memory trace'), findsOneWidget);
      expect(find.text('run-memory'), findsWidgets);
      expect(find.text('Matched: 2'), findsOneWidget);
      expect(find.text('Injected: 1'), findsOneWidget);
      expect(find.text('Omitted: 1'), findsOneWidget);
      expect(find.text('Term: chinese'), findsOneWidget);
      expect(find.text('Record: memory-user'), findsOneWidget);
      expect(find.text('Reason: max_records'), findsOneWidget);
      expect(find.text('scope_mismatch: 1'), findsOneWidget);
      expect(find.text('expired: 2'), findsOneWidget);
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
        title: page == SettingsPage.aboutVersion ? 'About & Version' : '',
        subtitle: page == SettingsPage.aboutVersion
            ? 'Build information and app diagnostics.'
            : '',
        sections: page == SettingsPage.aboutVersion
            ? const <SettingsSectionSnapshot>[
                SettingsSectionSnapshot(
                  title: 'Version',
                  rows: <SettingsRowSnapshot>[
                    SettingsRowSnapshot.value(
                      title: 'Installed version',
                      valueLabel: '1.0.0',
                    ),
                  ],
                ),
              ]
            : const <SettingsSectionSnapshot>[],
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
      localeTag: llmConfig.localeTag,
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

class _FakeDebugBridge extends OpenCraySeedBridge {
  _FakeDebugBridge({
    required this.runtimeSnapshot,
    required Map<String, OpenCrayChatRunSnapshot> runSnapshots,
  }) : _runSnapshots = runSnapshots;

  final OpenCrayChatRuntimeSnapshot runtimeSnapshot;
  final Map<String, OpenCrayChatRunSnapshot> _runSnapshots;

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      runtimeSnapshot;

  @override
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async =>
      _runSnapshots[runId];
}
