import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/app/opencray_tabs.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_agent_snapshot.dart';
import 'package:opencray/core/models/opencray_debug_snapshot.dart';
import 'package:opencray/core/models/opencray_image_reference.dart';
import 'package:opencray/core/models/opencray_shell_snapshot.dart';
import 'package:opencray/features/settings/settings.dart';

const List<LlmOnDeviceModelOption> _defaultOnDeviceLlmModels =
    <LlmOnDeviceModelOption>[
      LlmOnDeviceModelOption(
        id: 'gemma-4-e2b-it',
        title: 'Gemma 4 E2B',
        subtitle: 'Instruction-tuned Gemma 4 E2B for LiteRT-LM.',
        sizeLabel: '2.58 GB',
        fileSizeBytes: 2583085056,
        installState: 'ready',
        downloadedBytes: 2583085056,
        downloadBytesPerSecond: 0,
        sha256Verified: true,
      ),
      LlmOnDeviceModelOption(
        id: 'gemma-4-e4b-it',
        title: 'Gemma 4 E4B',
        subtitle: 'Instruction-tuned Gemma 4 E4B for LiteRT-LM.',
        sizeLabel: '3.65 GB',
        fileSizeBytes: 3654467584,
        installState: 'not_downloaded',
      ),
    ];

void main() {
  test('event alert route uses canonical id and accepts the legacy id', () {
    expect(SettingsPage.eventAlerts.routeId, 'event_alerts');
    expect(settingsPageFromRouteId('event_alerts'), SettingsPage.eventAlerts);
    expect(
      settingsPageFromRouteId('notification_channels'),
      SettingsPage.eventAlerts,
    );
  });

  testWidgets('standalone llm page auto-saves when a field loses focus', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
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

    expect(
      find.byKey(const ValueKey<String>('settings-llm-streaming-toggle')),
      findsOneWidget,
    );

    await tester.enterText(find.byType(TextField).at(1), 'secret');
    final modelField = find.byType(TextField).at(2);
    await tester.ensureVisible(modelField);
    await tester.tap(modelField, warnIfMissed: false);
    await tester.pumpAndSettle();

    expect(facade.saveCallCount, 1);
    expect(facade.llmConfig.apiKey, 'secret');
    expect(facade.llmConfig.enabled, isTrue);
  });

  testWidgets(
    'standalone llm page uses normal keyboards for non-api-key fields, dismisses focus, and clears api key',
    (tester) async {
      final facade = _buildSettingsFacade();
      facade.llmConfig = const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: 'secret',
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
        onDeviceModels: _defaultOnDeviceLlmModels,
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

      final Finder fields = find.byType(TextField);
      expect(
        tester.widget<TextField>(fields.at(0)).keyboardType,
        TextInputType.url,
      );
      expect(
        tester.widget<TextField>(fields.at(1)).keyboardType,
        TextInputType.visiblePassword,
      );
      expect(
        tester.widget<TextField>(fields.at(2)).keyboardType,
        TextInputType.text,
      );

      await tester.ensureVisible(fields.at(0));
      await tester.tap(fields.at(0));
      await tester.pump();
      expect(
        tester.widget<TextField>(fields.at(0)).focusNode?.hasFocus,
        isTrue,
      );

      await tester.ensureVisible(find.text('LLM'));
      await tester.tap(find.text('LLM'));
      await tester.pump();
      expect(
        tester.widget<TextField>(fields.at(0)).focusNode?.hasFocus,
        isFalse,
      );

      final Finder clearApiKeyButton = find.byKey(
        const ValueKey<String>('settings-llm-api-key-clear'),
      );
      await tester.ensureVisible(clearApiKeyButton);
      await tester.tap(clearApiKeyButton);
      await tester.pumpAndSettle();

      expect(tester.widget<TextField>(fields.at(1)).controller!.text, isEmpty);
      expect(facade.llmConfig.apiKey, '');
    },
  );

  testWidgets(
    'standalone llm page saves context budget preset and raw overrides',
    (tester) async {
      final facade = _buildSettingsFacade();
      facade.llmConfig = const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: 'secret',
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
        contextBudgetPreset: 'balanced',
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

      await tester.ensureVisible(find.text('Context budget'));
      await tester.tap(find.text('Expanded'));
      await tester.pumpAndSettle();

      expect(facade.llmConfig.contextBudgetPreset, 'expanded');

      final reservedOutputField = find.byKey(
        const ValueKey<String>('settings-llm-context-budget-reserved-output'),
      );
      await tester.enterText(reservedOutputField, '3072');
      await tester.tap(find.text('Safety margin'));
      await tester.pumpAndSettle();

      final safetyMarginField = find.byKey(
        const ValueKey<String>('settings-llm-context-budget-safety-margin'),
      );
      await tester.enterText(safetyMarginField, '1536');
      await tester.tap(find.text('Effective input'));
      await tester.pumpAndSettle();

      final effectiveInputField = find.byKey(
        const ValueKey<String>('settings-llm-context-budget-effective-input'),
      );
      await tester.enterText(effectiveInputField, '0.92');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(facade.llmConfig.contextBudgetReservedOutputTokens, 3072);
      expect(facade.llmConfig.contextBudgetSafetyMarginTokens, 1536);
      expect(facade.llmConfig.contextBudgetEffectiveInputPercent, 0.92);
    },
  );

  testWidgets(
    'standalone llm page clears input focus when the keyboard closes',
    (tester) async {
      final facade = _buildSettingsFacade();
      addTearDown(tester.view.resetViewInsets);

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

      final Finder baseUrlField = find.byType(TextField).first;
      await tester.ensureVisible(baseUrlField);
      await tester.tap(baseUrlField);
      await tester.pump();
      expect(
        tester.widget<TextField>(baseUrlField).focusNode?.hasFocus,
        isTrue,
      );

      tester.view.viewInsets = const FakeViewPadding(bottom: 320);
      tester.binding.handleMetricsChanged();
      await tester.pump();
      expect(FocusManager.instance.primaryFocus, isNotNull);

      tester.view.viewInsets = FakeViewPadding.zero;
      tester.binding.handleMetricsChanged();
      await tester.pump();
      expect(
        tester.widget<TextField>(baseUrlField).focusNode?.hasFocus,
        isFalse,
      );
    },
  );

  testWidgets(
    'standalone llm page shows download speed without a separate downloading chip',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'openai',
          selectedProviderOptionId: 'openai',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'openai',
              providerId: 'openai',
              title: 'OpenAI',
              subtitle: 'Official OpenAI-compatible endpoint.',
              defaultBaseUrl: 'https://api.openai.com/v1',
              defaultModel: 'gpt-4o-mini',
              protocol: 'openai',
              apiKey: '',
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
          providerMode: 'on_device_model',
          onDeviceModels: <LlmOnDeviceModelOption>[
            LlmOnDeviceModelOption(
              id: 'gemma-4-e2b-it',
              title: 'Gemma 4 E2B',
              subtitle: 'Instruction-tuned Gemma 4 E2B for LiteRT-LM.',
              sizeLabel: '2.58 GB',
              fileSizeBytes: 2583085056,
              installState: 'downloading',
              downloadedBytes: 260000000,
              downloadBytesPerSecond: 12582912,
            ),
          ],
          selectedOnDeviceModelId: 'gemma-4-e2b-it',
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

      expect(
        find.text('Downloading · 0.24 GB / 2.58 GB · 12.0 MB/s'),
        findsOneWidget,
      );
      expect(find.text('Downloading'), findsNothing);
      expect(find.text('Cancel'), findsOneWidget);
    },
  );

  testWidgets(
    'standalone llm page shows validation feedback when validation fails',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'openai',
          selectedProviderOptionId: 'openai',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'openai',
              providerId: 'openai',
              title: 'OpenAI',
              subtitle: 'Official OpenAI-compatible endpoint.',
              defaultBaseUrl: 'https://api.openai.com/v1',
              defaultModel: 'gpt-4o-mini',
              protocol: 'openai',
              apiKey: '',
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

      final validateAction = find.text('Validate Model');
      await tester.ensureVisible(validateAction);
      await tester.tap(validateAction, warnIfMissed: false);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));

      expect(find.text('Invalid API key.'), findsOneWidget);
      expect(facade.saveCallCount, 0);
    },
  );

  testWidgets(
    'standalone llm page can validate repeatedly after a completed attempt',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'openai',
          selectedProviderOptionId: 'openai',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'openai',
              providerId: 'openai',
              title: 'OpenAI',
              subtitle: 'Official OpenAI-compatible endpoint.',
              defaultBaseUrl: 'https://api.openai.com/v1',
              defaultModel: 'gpt-4o-mini',
              protocol: 'openai',
              apiKey: '',
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

      final validateAction = find.text('Validate Model');
      await tester.ensureVisible(validateAction);
      await tester.tap(validateAction, warnIfMissed: false);
      await tester.pumpAndSettle();
      await tester.ensureVisible(validateAction);
      await tester.tap(validateAction, warnIfMissed: false);
      await tester.pumpAndSettle();

      expect(facade.validationCallCount, 2);
      expect(find.text('Validated.'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'standalone llm page ignores repeated validate taps while draft save is pending',
    (tester) async {
      final saveStarted = Completer<void>();
      final allowSaveToFinish = Completer<void>();
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: false,
          providerId: 'openai',
          selectedProviderOptionId: 'openai',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'openai',
              providerId: 'openai',
              title: 'OpenAI',
              subtitle: 'Official OpenAI-compatible endpoint.',
              defaultBaseUrl: 'https://api.openai.com/v1',
              defaultModel: 'gpt-4o-mini',
              protocol: 'openai',
              apiKey: '',
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
        onSaveLlmConfig: () async {
          if (!saveStarted.isCompleted) {
            saveStarted.complete();
          }
          await allowSaveToFinish.future;
        },
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

      await tester.enterText(find.byType(TextField).at(1), 'secret');
      await tester.pump();

      final validateAction = find.text('Validate Model');
      await tester.ensureVisible(validateAction);
      await tester.tap(validateAction, warnIfMissed: false);
      await tester.ensureVisible(validateAction);
      await tester.tap(validateAction, warnIfMissed: false);
      await tester.pump();

      expect(saveStarted.isCompleted, isTrue);
      expect(facade.saveCallCount, 1);
      expect(facade.validationCallCount, 0);

      allowSaveToFinish.complete();
      await tester.pumpAndSettle();

      expect(facade.validationCallCount, 1);
      expect(find.text('Validated.'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('custom provider supports Anthropic protocol and thinking off', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'custom',
        selectedProviderOptionId: 'custom',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'custom',
            providerId: 'custom',
            title: 'Custom provider',
            subtitle:
                'Any OpenAI-compatible, OpenAI Responses, or Anthropic endpoint.',
            defaultBaseUrl: '',
            defaultModel: '',
            protocol: 'openai',
            apiKey: '',
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

    await tester.ensureVisible(find.text('XHigh'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('XHigh'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Off'));
    await tester.pumpAndSettle();

    expect(facade.saveCallCount, 3);
    expect(facade.llmConfig.reasoningEffort, 'off');
  });

  testWidgets('custom provider supports OpenAI Responses protocol', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'custom',
        selectedProviderOptionId: 'custom',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'custom',
            providerId: 'custom',
            title: 'Custom provider',
            subtitle:
                'Any OpenAI-compatible, OpenAI Responses, or Anthropic endpoint.',
            defaultBaseUrl: '',
            defaultModel: '',
            protocol: 'openai',
            apiKey: '',
            isCustom: true,
          ),
        ],
        providerName: 'Custom provider',
        providerNotes: '',
        baseUrl: 'https://api.openai.com/v1',
        apiKey: 'secret',
        model: 'gpt-5-mini',
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
    await tester.tap(find.text('OpenAI Responses').last);
    await tester.pumpAndSettle();

    expect(facade.saveCallCount, 1);
    expect(facade.llmConfig.protocol, 'openai_responses');
    expect(find.text('OpenAI Responses'), findsOneWidget);
  });

  testWidgets('openai prompt cache controls save key scope and retention', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-5-mini',
            protocol: 'openai',
            apiKey: '',
            isCustom: false,
          ),
        ],
        providerName: 'OpenAI',
        providerNotes: '',
        baseUrl: 'https://api.openai.com/v1',
        apiKey: 'secret',
        model: 'gpt-5-mini',
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

    await tester.ensureVisible(find.text('Disabled'));
    await tester.tap(find.text('Disabled'), warnIfMissed: false);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Per session').last);
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Default'));
    await tester.tap(find.text('Default'), warnIfMissed: false);
    await tester.pumpAndSettle();
    await tester.tap(find.text('24 hours').last);
    await tester.pumpAndSettle();

    expect(facade.saveCallCount, 2);
    expect(facade.llmConfig.openAiPromptCacheKeyStrategy, 'session');
    expect(facade.llmConfig.openAiPromptCacheRetention, '24h');
    expect(find.text('24 hours'), findsOneWidget);
  });

  testWidgets('anthropic prompt cache controls save toggle and ttl', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'custom',
        selectedProviderOptionId: 'custom',
        protocol: 'anthropic',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'custom',
            providerId: 'custom',
            title: 'Custom provider',
            subtitle:
                'Any OpenAI-compatible, OpenAI Responses, or Anthropic endpoint.',
            defaultBaseUrl: '',
            defaultModel: '',
            protocol: 'anthropic',
            apiKey: '',
            isCustom: true,
          ),
        ],
        providerName: 'Custom provider',
        providerNotes: '',
        baseUrl: 'https://api.anthropic.com',
        apiKey: 'secret',
        model: 'claude-3-7-sonnet',
        reasoningEffort: 'high',
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

    final promptCacheToggle = find.byKey(
      const ValueKey<String>('settings-llm-anthropic-prompt-cache-toggle'),
    );
    await tester.ensureVisible(promptCacheToggle);
    await tester.tap(promptCacheToggle, warnIfMissed: false);
    await tester.pumpAndSettle();

    expect(facade.saveCallCount, 1);
    expect(facade.llmConfig.anthropicPromptCachingEnabled, isTrue);
    expect(find.text('5 minutes'), findsOneWidget);

    final ttlField = find.text('5 minutes');
    await tester.ensureVisible(ttlField);
    await tester.tap(ttlField, warnIfMissed: false);
    await tester.pumpAndSettle();
    await tester.tap(find.text('1 hour').last);
    await tester.pumpAndSettle();

    expect(facade.saveCallCount, 2);
    expect(facade.llmConfig.anthropicPromptCacheTtl, '1h');
    expect(find.text('1 hour'), findsOneWidget);
  });

  testWidgets('llm streaming toggle saves disabled state', (tester) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
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

    final streamingToggle = find.byKey(
      const ValueKey<String>('settings-llm-streaming-toggle'),
    );
    await tester.ensureVisible(streamingToggle);
    await tester.tap(streamingToggle, warnIfMissed: false);
    await tester.pumpAndSettle();

    expect(facade.saveCallCount, 1);
    expect(facade.llmConfig.streamingEnabled, isFalse);
  });

  testWidgets(
    'standalone llm page switches to on-device cards and hides cloud validation',
    (tester) async {
      final facade = _buildSettingsFacade();

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

      expect(find.text('Validate Model'), findsOneWidget);

      await tester.tap(find.text('On-device'));
      await tester.pumpAndSettle();

      expect(facade.saveCallCount, 1);
      expect(facade.llmConfig.providerMode, 'on_device_model');
      expect(find.text('On-device model'), findsOneWidget);
      expect(find.text('Sampling & limits'), findsOneWidget);
      expect(find.text('Runtime'), findsOneWidget);
      expect(find.text('Gemma 4 E2B'), findsOneWidget);
      expect(find.text('Installed · 2.58 GB'), findsOneWidget);
      expect(find.text('Validate Model'), findsNothing);
    },
  );

  testWidgets('standalone llm page saves on-device tuning controls', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: true,
        providerMode: 'on_device_model',
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
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
        onDeviceModels: _defaultOnDeviceLlmModels,
        selectedOnDeviceModelId: 'gemma-4-e2b-it',
        onDeviceMaxContextWindow: 32768,
        onDeviceMaxTokens: 4096,
        onDeviceTopK: 40,
        onDeviceTopP: 0.95,
        onDeviceTemperature: 0.70,
        onDeviceAccelerator: 'gpu',
        onDeviceThinkingEnabled: false,
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

    await tester.enterText(find.byType(TextField).at(0), '16384');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump(const Duration(milliseconds: 500));

    await tester.enterText(find.byType(TextField).at(1), '2048');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump(const Duration(milliseconds: 500));

    await tester.enterText(find.byType(TextField).at(2), '24');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump(const Duration(milliseconds: 500));

    await tester.enterText(find.byType(TextField).at(3), '0.90');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump(const Duration(milliseconds: 500));

    await tester.enterText(find.byType(TextField).at(4), '0.40');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump(const Duration(milliseconds: 500));

    await tester.ensureVisible(find.text('CPU'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('CPU'), warnIfMissed: false);
    await tester.pump(const Duration(milliseconds: 500));

    await tester.ensureVisible(find.text('On'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('On'), warnIfMissed: false);
    await tester.pump(const Duration(milliseconds: 500));

    expect(facade.llmConfig.onDeviceMaxContextWindow, 16384);
    expect(facade.llmConfig.onDeviceMaxTokens, 2048);
    expect(facade.llmConfig.onDeviceTopK, 24);
    expect(facade.llmConfig.onDeviceTopP, 0.9);
    expect(facade.llmConfig.onDeviceTemperature, 0.4);
    expect(facade.llmConfig.onDeviceAccelerator, 'cpu');
    expect(facade.llmConfig.onDeviceThinkingEnabled, isTrue);
  });

  testWidgets(
    'standalone notifications page saves the master switch and opens event alerts',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: false,
          providerId: 'openai',
          selectedProviderOptionId: 'openai',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'openai',
              providerId: 'openai',
              title: 'OpenAI',
              subtitle: 'Official OpenAI-compatible endpoint.',
              defaultBaseUrl: 'https://api.openai.com/v1',
              defaultModel: 'gpt-4o-mini',
              protocol: 'openai',
              apiKey: '',
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
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      final masterSwitch = find.descendant(
        of: find.byKey(const ValueKey<String>('notification-master-enabled')),
        matching: find.byType(Switch),
      );
      await tester.ensureVisible(masterSwitch);
      await tester.tap(masterSwitch);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(facade.notificationSaveCallCount, 1);
      expect(facade.notificationSettings.masterEnabled, isFalse);

      await tester.ensureVisible(find.text('Alert types'));
      await tester.pump();
      await tester.tap(find.text('Alert types'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('Event Alerts'), findsOneWidget);
      expect(find.text('Approval requests'), findsOneWidget);
    },
  );

  testWidgets(
    'notifications page opens scheduled tasks and list switch saves',
    (tester) async {
      final facade = _buildSettingsFacade()
        ..scheduledTasks = const ScheduledTasksSnapshot(
          tasks: <ScheduledTaskSummary>[
            ScheduledTaskSummary(
              scheduleId: 'schedule-1',
              sessionId: 'session-1',
              title: 'Morning review',
              enabled: true,
              triggerKind: 'recurrence',
              triggerSummary: 'Every day at 09:00',
              nextTriggerAtEpochMs: 1784336400000,
            ),
          ],
          totalCount: 1,
          enabledCount: 1,
        );
      final debugBridge = _buildDebugBridge();

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
            debugBridge: debugBridge,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('1 tasks, 1 enabled.'), findsOneWidget);
      final scheduledTasksLink = find.text('Scheduled tasks');
      await tester.ensureVisible(scheduledTasksLink);
      await tester.tap(scheduledTasksLink);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('Morning review'), findsOneWidget);
      final taskSwitch = find.descendant(
        of: find.byKey(const ValueKey<String>('settings-scheduled-tasks')),
        matching: find.byType(Switch),
      );
      await tester.tap(taskSwitch);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(facade.scheduledTaskEnabledRequests, <bool>[false]);
      expect(facade.scheduledTasks.tasks.single.enabled, isFalse);

      await tester.tap(find.text('Morning review'));
      await tester.pump();
      expect(
        debugBridge.persistedSettingsRouteIds.last,
        SettingsPage.scheduledTasks.routeId,
      );
    },
  );

  testWidgets('scheduled task detail runs snoozes toggles and shows history', (
    tester,
  ) async {
    final facade = _buildSettingsFacade()
      ..scheduledTaskDetail = const ScheduledTaskDetailSnapshot(
        task: ScheduledTaskDetails(
          scheduleId: 'schedule-1',
          sessionId: 'session-1',
          title: 'Morning review',
          enabled: true,
          triggerKind: 'recurrence',
          triggerSummary: 'Every day at 09:00',
          prompt: 'Review open work and summarize blockers.',
          conflictPolicy: 'queue',
          foregroundNotificationRequired: true,
          notifyOnQueued: true,
          notifyOnApproval: true,
          notifyOnCompletion: true,
          notifyOnInterruption: true,
          createdAtEpochMs: 1784200000000,
          updatedAtEpochMs: 1784200000000,
          nextTriggerAtEpochMs: 1784336400000,
        ),
        recentRuns: <ScheduledTaskRunRecord>[
          ScheduledTaskRunRecord(
            scheduleRunId: 'schedule-run-1',
            triggerReason: 'alarm',
            result: 'accepted',
            triggeredAtEpochMs: 1784250000000,
            updatedAtEpochMs: 1784250000000,
          ),
        ],
        totalRunCount: 1,
      );

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.scheduledTaskDetail,
          initialScheduleId: 'schedule-1',
          standalone: true,
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(
      find.text('Review open work and summarize blockers.'),
      findsOneWidget,
    );
    expect(find.text('Accepted'), findsOneWidget);
    expect(find.text('Exact alarm trigger'), findsOneWidget);

    final runNow = find.byKey(const ValueKey<String>('scheduled-task-run-now'));
    await tester.ensureVisible(runNow);
    await tester.tap(runNow);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(facade.scheduledTaskRunNowCallCount, 1);

    final snooze = find.byKey(const ValueKey<String>('scheduled-task-snooze'));
    await tester.ensureVisible(snooze);
    await tester.tap(snooze);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(facade.scheduledTaskSnoozeCallCount, 1);

    final enabledSwitch = find.descendant(
      of: find.byKey(const ValueKey<String>('scheduled-task-enabled')),
      matching: find.byType(Switch),
    );
    await tester.ensureVisible(enabledSwitch);
    await tester.tap(enabledSwitch);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(facade.scheduledTaskEnabledRequests, <bool>[false]);
  });

  testWidgets('event alerts page saves per-event toggles', (tester) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
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
          initialPage: SettingsPage.eventAlerts,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch).first);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(facade.notificationSaveCallCount, 1);
    expect(facade.notificationSettings.approvalRequestsEnabled, isFalse);
    expect(find.text('Approval requests'), findsOneWidget);
  });

  testWidgets(
    'notifications page keeps the newest state during overlapping saves',
    (tester) async {
      final firstSaveStarted = Completer<void>();
      final releaseFirstSave = Completer<void>();
      var saveInvocation = 0;
      final facade = _notificationTestFacade(
        onSaveNotificationSettings: (snapshot) async {
          saveInvocation += 1;
          if (saveInvocation == 1) {
            firstSaveStarted.complete();
            await releaseFirstSave.future;
          }
          return snapshot;
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      final masterSwitch = find.descendant(
        of: find.byKey(const ValueKey<String>('notification-master-enabled')),
        matching: find.byType(Switch),
      );
      final quietHoursSwitch = find.descendant(
        of: find.byKey(
          const ValueKey<String>('notification-quiet-hours-enabled'),
        ),
        matching: find.byType(Switch),
      );
      await tester.tap(masterSwitch);
      await tester.pump();
      await firstSaveStarted.future;
      await tester.ensureVisible(quietHoursSwitch);
      await tester.pump();
      await tester.tap(quietHoursSwitch);
      await tester.pump();

      expect(facade.notificationSaveCallCount, 1);
      expect(facade.notificationSaveRequests.single.masterEnabled, isFalse);
      expect(facade.notificationSaveRequests.single.quietHoursEnabled, isTrue);

      releaseFirstSave.complete();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(facade.notificationSaveCallCount, 2);
      expect(facade.notificationSaveRequests.last.masterEnabled, isFalse);
      expect(facade.notificationSaveRequests.last.quietHoursEnabled, isFalse);
      expect(facade.notificationSettings.masterEnabled, isFalse);
      expect(facade.notificationSettings.quietHoursEnabled, isFalse);
    },
  );

  testWidgets('notifications page rolls back the latest failed save', (
    tester,
  ) async {
    final facade = _notificationTestFacade(
      onSaveNotificationSettings: (snapshot) async {
        throw Exception('Notification save rejected');
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.notificationsBackground,
          standalone: true,
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    final masterSwitch = find.descendant(
      of: find.byKey(const ValueKey<String>('notification-master-enabled')),
      matching: find.byType(Switch),
    );
    expect(tester.widget<Switch>(masterSwitch).value, isTrue);

    await tester.tap(masterSwitch);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(tester.widget<Switch>(masterSwitch).value, isTrue);
    expect(facade.notificationSettings.masterEnabled, isTrue);
    expect(find.text('Notification save rejected'), findsOneWidget);
  });

  testWidgets(
    'notifications page keeps a newer edit when an older save fails',
    (tester) async {
      final firstSaveStarted = Completer<void>();
      final releaseFirstSave = Completer<void>();
      var saveInvocation = 0;
      final facade = _notificationTestFacade(
        onSaveNotificationSettings: (snapshot) async {
          saveInvocation += 1;
          if (saveInvocation == 1) {
            firstSaveStarted.complete();
            await releaseFirstSave.future;
            throw Exception('Older save failed');
          }
          return snapshot;
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      final masterSwitch = find.descendant(
        of: find.byKey(const ValueKey<String>('notification-master-enabled')),
        matching: find.byType(Switch),
      );
      final quietHoursSwitch = find.descendant(
        of: find.byKey(
          const ValueKey<String>('notification-quiet-hours-enabled'),
        ),
        matching: find.byType(Switch),
      );
      await tester.tap(masterSwitch);
      await tester.pump();
      await firstSaveStarted.future;
      await tester.ensureVisible(quietHoursSwitch);
      await tester.tap(quietHoursSwitch);
      await tester.pump();

      releaseFirstSave.complete();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(facade.notificationSaveCallCount, 2);
      expect(facade.notificationSettings.masterEnabled, isFalse);
      expect(facade.notificationSettings.quietHoursEnabled, isFalse);
      expect(tester.widget<Switch>(masterSwitch).value, isFalse);
    },
  );

  testWidgets(
    'scheduled task summary failure does not block notification controls',
    (tester) async {
      final facade = _notificationTestFacade(
        onLoadScheduledTasks: () async {
          throw Exception('Scheduled summary unavailable');
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(
        find.byKey(const ValueKey<String>('notification-master-enabled')),
        findsOneWidget,
      );
      expect(
        find.text(
          'The task summary is temporarily unavailable. Management remains available.',
        ),
        findsOneWidget,
      );
      expect(find.text('System controls'), findsOneWidget);
    },
  );

  testWidgets('event alerts page keeps rapid per-event changes', (
    tester,
  ) async {
    final firstSaveStarted = Completer<void>();
    final releaseFirstSave = Completer<void>();
    var saveInvocation = 0;
    final facade = _notificationTestFacade(
      onSaveNotificationSettings: (snapshot) async {
        saveInvocation += 1;
        if (saveInvocation == 1) {
          firstSaveStarted.complete();
          await releaseFirstSave.future;
        }
        return snapshot;
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.eventAlerts,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    final approvalSwitch = find.descendant(
      of: find.byKey(
        const ValueKey<String>('notification-event-approval-requests'),
      ),
      matching: find.byType(Switch),
    );
    final reminderSwitch = find.descendant(
      of: find.byKey(
        const ValueKey<String>('notification-event-approval-reminder'),
      ),
      matching: find.byType(Switch),
    );
    await tester.tap(approvalSwitch);
    await tester.pump();
    await firstSaveStarted.future;
    await tester.ensureVisible(reminderSwitch);
    await tester.pump();
    await tester.tap(reminderSwitch);
    await tester.pump();

    releaseFirstSave.complete();
    await tester.pumpAndSettle();

    expect(facade.notificationSaveCallCount, 2);
    expect(
      facade.notificationSaveRequests.last.approvalRequestsEnabled,
      isFalse,
    );
    expect(
      facade.notificationSaveRequests.last.approvalReminderEnabled,
      isFalse,
    );
    expect(facade.notificationSettings.approvalRequestsEnabled, isFalse);
    expect(facade.notificationSettings.approvalReminderEnabled, isFalse);
  });

  testWidgets(
    'notifications page launches strong background action from system row',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: false,
          providerId: 'openai',
          selectedProviderOptionId: 'openai',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'openai',
              providerId: 'openai',
              title: 'OpenAI',
              subtitle: 'Official OpenAI-compatible endpoint.',
              defaultBaseUrl: 'https://api.openai.com/v1',
              defaultModel: 'gpt-4o-mini',
              protocol: 'openai',
              apiKey: '',
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
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      await tester.ensureVisible(find.text('Foreground service notice'));
      await tester.pump();
      await tester.tap(find.text('Foreground service notice'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(
        facade.strongBackgroundActionRequests,
        contains(StrongBackgroundActionId.openNotificationSettings),
      );
    },
  );

  testWidgets(
    'notifications page shows active profile when notifications and alarms are ready',
    (tester) async {
      final facade =
          _FakeSettingsFacade(
              llmConfig: const LlmConfigSnapshot(
                localeTag: 'en',
                enabled: false,
                providerId: 'openai',
                selectedProviderOptionId: 'openai',
                protocol: 'openai',
                providerOptions: <LlmProviderOption>[
                  LlmProviderOption(
                    id: 'openai',
                    providerId: 'openai',
                    title: 'OpenAI',
                    subtitle: 'Official OpenAI-compatible endpoint.',
                    defaultBaseUrl: 'https://api.openai.com/v1',
                    defaultModel: 'gpt-4o-mini',
                    protocol: 'openai',
                    apiKey: '',
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
            )
            ..strongBackgroundSnapshot = const StrongBackgroundSnapshot(
              source: 'strong-background',
              available: true,
              tier: StrongBackgroundTier.activeBackground,
              setupComplete: false,
              recommendedActionIds: <StrongBackgroundActionId>[
                StrongBackgroundActionId.requestIgnoreBatteryOptimizations,
              ],
              notifications: StrongBackgroundNotificationsSnapshot(
                permissionRequired: true,
                permissionGranted: true,
                enabled: true,
                configured: true,
              ),
              exactAlarms: StrongBackgroundExactAlarmSnapshot(
                accessRequired: true,
                accessGranted: true,
                configured: true,
              ),
              batteryOptimization: StrongBackgroundBatteryOptimizationSnapshot(
                supported: true,
                exempt: false,
                configured: false,
              ),
              actions: <StrongBackgroundActionSnapshot>[
                StrongBackgroundActionSnapshot(
                  id: StrongBackgroundActionId
                      .requestIgnoreBatteryOptimizations,
                  available: true,
                  recommended: true,
                ),
              ],
            );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Background protection status'), findsOneWidget);
      expect(find.text('Current status'), findsOneWidget);
      expect(find.text('Active'), findsOneWidget);
      expect(find.text('AUTO'), findsNothing);
      expect(find.text('ACTIVE'), findsNothing);
      expect(find.text('STRONG'), findsNothing);
      expect(
        find.text(
          'Notifications and exact alarms are ready, but the device is not yet in the strongest local background tier.',
        ),
        findsOneWidget,
      );
      expect(
        find.text(
          'Event alerts and scheduled wakes are ready. Addressing battery restrictions enables stronger local background protection.',
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('home settings hides Agent entry from the overview list', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
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
      overviewSnapshot: const SettingsOverviewSnapshot(
        eyebrow: 'APP SHELL',
        title: 'Settings',
        subtitle: 'Access, providers, and personal defaults.',
        deviceTitle: 'OpenCray on this device',
        deviceSummary: 'API routes: Search + Media',
        entries: <SettingsHomeEntrySnapshot>[
          SettingsHomeEntrySnapshot(page: SettingsPage.agents, title: 'Agent'),
          SettingsHomeEntrySnapshot(
            page: SettingsPage.apiIntegrations,
            title: 'API Integrations',
          ),
        ],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(home: SettingsFeatureScreen(facade: facade)),
    );
    await tester.pumpAndSettle();

    expect(find.text('Agent'), findsNothing);
    expect(find.text('API Integrations'), findsOneWidget);

    await tester.tap(find.text('API Integrations'));
    await tester.pumpAndSettle();

    expect(find.byIcon(Icons.arrow_back_ios_new_rounded), findsOneWidget);
    expect(find.text('Settings'), findsOneWidget);
    expect(find.text('Routing rules'), findsOneWidget);
  });

  testWidgets('agents page loads host-backed agents and persists creation', (
    tester,
  ) async {
    final facade = _buildSettingsFacade();
    final bridge = OpenCraySeedBridge(
      initialAgents: const <OpenCrayAgentSnapshot>[
        OpenCrayAgentSnapshot(
          agentId: 'agent-aster',
          displayName: 'Aster',
          presetName: 'steady',
          plasticity: 'medium',
          mode: 'full',
          baseDescription:
              'Calm, concrete, and good at turning ideas into steps.',
          llm: OpenCrayAgentLlmConfig(
            provider: 'openai',
            protocol: 'openai',
            model: 'gpt-4o-mini',
          ),
          createdAtEpochMs: 1000,
          updatedAtEpochMs: 2000,
        ),
        OpenCrayAgentSnapshot(
          agentId: 'agent-nova',
          displayName: 'Nova',
          presetName: 'builder',
          plasticity: 'low',
          mode: 'noSoul',
          baseDescription:
              'Execution-focused agent for repo cleanup and patch review.',
          llm: OpenCrayAgentLlmConfig(
            provider: 'anthropic',
            protocol: 'anthropic',
            model: 'claude-3-7-sonnet',
          ),
          createdAtEpochMs: 1100,
          updatedAtEpochMs: 2100,
        ),
      ],
      initialActiveAgentId: 'agent-aster',
    );

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.agents,
          standalone: true,
          debugBridge: bridge,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('2 saved agents'), findsOneWidget);
    expect(find.text('Aster'), findsOneWidget);
    expect(find.text('Nova'), findsOneWidget);

    await tester.tap(find.text('New agent'));
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('agent-create-status-card')),
      findsOneWidget,
    );
    expect(find.text('Draft ready'), findsOneWidget);

    await tester.enterText(find.byType(TextField).first, 'Rhea');
    await tester.pumpAndSettle();

    expect(find.text('Unsaved changes'), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey<String>('agent-create-status-card')),
        matching: find.text('Rhea'),
      ),
      findsOneWidget,
    );

    await tester.ensureVisible(find.text('Create agent').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Create agent').last);
    await tester.pumpAndSettle();

    expect(find.text('3 saved agents'), findsOneWidget);
    expect(find.text('Rhea'), findsOneWidget);

    final activeAgent = await bridge.loadActiveAgent();
    expect(activeAgent?.displayName, 'Rhea');
  });

  testWidgets('host-backed agents page selects an existing agent in place', (
    tester,
  ) async {
    final facade = _buildSettingsFacade();
    final bridge = OpenCraySeedBridge(
      initialAgents: const <OpenCrayAgentSnapshot>[
        OpenCrayAgentSnapshot(
          agentId: 'agent-aster',
          displayName: 'Aster',
          presetName: 'steady',
          plasticity: 'medium',
          mode: 'full',
          llm: OpenCrayAgentLlmConfig(
            provider: 'openai',
            protocol: 'openai',
            model: 'gpt-4o-mini',
          ),
          createdAtEpochMs: 1000,
          updatedAtEpochMs: 2000,
        ),
        OpenCrayAgentSnapshot(
          agentId: 'agent-nova',
          displayName: 'Nova',
          presetName: 'builder',
          plasticity: 'medium',
          mode: 'lightweight',
          llm: OpenCrayAgentLlmConfig(
            provider: 'anthropic',
            protocol: 'anthropic',
            model: 'claude-3-7-sonnet',
          ),
          createdAtEpochMs: 1100,
          updatedAtEpochMs: 2100,
        ),
      ],
      initialActiveAgentId: 'agent-aster',
    );

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.agents,
          standalone: true,
          debugBridge: bridge,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Create agent'), findsNothing);

    await tester.tap(find.text('Nova'));
    await tester.pumpAndSettle();

    expect(find.text('Create agent'), findsNothing);
    expect(find.text('2 saved agents'), findsOneWidget);

    final activeAgent = await bridge.loadActiveAgent();
    expect(activeAgent?.agentId, 'agent-nova');
  });

  testWidgets('agents media page supports naming additional image references', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
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
          initialPage: SettingsPage.agents,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('4 saved agents'), findsOneWidget);

    await tester.tap(find.text('New agent'));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Media samples'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Media samples'));
    await tester.pumpAndSettle();

    expect(find.text('Add more'), findsOneWidget);

    await tester.ensureVisible(find.text('Add more'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Add more'));
    await tester.pumpAndSettle();

    expect(find.text('Image label'), findsOneWidget);

    await tester.enterText(find.byType(TextField).last, 'Side profile');
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(find.text('Image label'), findsNothing);
    expect(find.text('Side profile'), findsOneWidget);
  });

  testWidgets(
    'agents media page imports settings image assets through the debug bridge',
    (tester) async {
      final facade = _buildSettingsFacade();
      final debugBridge = _buildDebugBridge(
        pickedSettingsImageAssetBatches: const <List<OpenCraySettingsImageAsset>>[
          <OpenCraySettingsImageAsset>[
            OpenCraySettingsImageAsset(
              assetId: 'settings-asset-11',
              displayName: 'studio-angle.png',
              relativePath: 'settings-image-assets/studio-angle.png',
              mimeType: 'image/png',
              sha256:
                  '1111111111111111111111111111111111111111111111111111111111111111',
              sizeBytes: 1024,
              createdAtEpochMs: 3100,
            ),
            OpenCraySettingsImageAsset(
              assetId: 'settings-asset-12',
              displayName: 'linen_coat.jpg',
              relativePath: 'settings-image-assets/linen_coat.jpg',
              mimeType: 'image/jpeg',
              sha256:
                  '2222222222222222222222222222222222222222222222222222222222222222',
              sizeBytes: 2048,
              createdAtEpochMs: 3200,
            ),
          ],
        ],
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.agents,
            standalone: true,
            debugBridge: debugBridge,
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('New agent'));
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.text('Media samples'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Media samples'));
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.text('Add more'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Add more'));
      await tester.pumpAndSettle();

      expect(debugBridge.pickSettingsImageAssetsCallCount, 1);
      expect(find.text('Image label'), findsNothing);
      expect(find.text('Studio Angle'), findsOneWidget);
      expect(find.text('Linen Coat'), findsOneWidget);
    },
  );

  testWidgets(
    'agent create flow exposes twin import page and updates summary',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: false,
          providerId: 'openai',
          selectedProviderOptionId: 'openai',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'openai',
              providerId: 'openai',
              title: 'OpenAI',
              subtitle: 'Official OpenAI-compatible endpoint.',
              defaultBaseUrl: 'https://api.openai.com/v1',
              defaultModel: 'gpt-4o-mini',
              protocol: 'openai',
              apiKey: '',
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
            initialPage: SettingsPage.agents,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byIcon(Icons.arrow_back_ios_new_rounded), findsOneWidget);
      expect(find.text('Settings'), findsOneWidget);

      await tester.tap(find.text('New agent'));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('agent-create-twin-import')),
        findsOneWidget,
      );
      expect(find.text('Not set'), findsOneWidget);

      await tester.ensureVisible(
        find.byKey(const ValueKey<String>('agent-create-twin-import')),
      );
      await tester.pumpAndSettle();

      await tester.tap(
        find.byKey(const ValueKey<String>('agent-create-twin-import')),
      );
      await tester.pumpAndSettle();

      expect(find.text('Twin import'), findsOneWidget);
      expect(find.text('CHAT HISTORY'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('agent-twin-import-run-draft')),
        findsOneWidget,
      );

      await tester.ensureVisible(
        find.byKey(const ValueKey<String>('agent-twin-import-run-draft')),
      );
      await tester.pumpAndSettle();

      await tester.tap(
        find.byKey(const ValueKey<String>('agent-twin-import-run-draft')),
      );
      await tester.pumpAndSettle();

      expect(find.text('Twin import'), findsOneWidget);
      expect(find.text('ChatLab JSONL'), findsOneWidget);
    },
  );

  testWidgets('custom provider save action adds a reusable provider option', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'custom',
        selectedProviderOptionId: 'custom',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'custom',
            providerId: 'custom',
            title: 'Custom provider',
            subtitle:
                'Any OpenAI-compatible, OpenAI Responses, or Anthropic endpoint.',
            defaultBaseUrl: '',
            defaultModel: '',
            protocol: 'openai',
            apiKey: '',
            isCustom: true,
          ),
        ],
        providerName: 'Custom provider',
        providerNotes: '',
        baseUrl: '',
        apiKey: '',
        model: '',
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

    await tester.enterText(find.byType(TextField).at(0), 'Acme');
    await tester.enterText(find.byType(TextField).at(1), 'Regional fallback');
    await tester.enterText(
      find.byType(TextField).at(2),
      'https://api.acme.example/v1',
    );
    await tester.enterText(find.byType(TextField).at(3), 'secret');
    await tester.enterText(find.byType(TextField).at(4), 'claude-3-7-sonnet');

    final onTap = tester
        .widget<InkWell>(
          find.byKey(const ValueKey<String>('settings-llm-save-provider')),
        )
        .onTap;
    expect(onTap, isNotNull);
    await tester.runAsync(() async {
      final result = Function.apply(onTap!, const <Object?>[]);
      if (result is Future<void>) {
        await result;
      }
    });
    await tester.pumpAndSettle();

    expect(facade.llmConfig.selectedProviderOptionId, 'saved-custom');
    expect(facade.llmConfig.providerOptions.last.title, 'Acme');
    expect(facade.llmConfig.providerOptions.last.subtitle, 'Regional fallback');
    expect(facade.llmConfig.providerOptions.last.apiKey, 'secret');
  });

  testWidgets(
    'local OpenAI-compatible custom provider stays enabled without API key',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: false,
          providerId: 'custom',
          selectedProviderOptionId: 'custom',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'custom',
              providerId: 'custom',
              title: 'Custom provider',
              subtitle:
                  'Any OpenAI-compatible, OpenAI Responses, or Anthropic endpoint.',
              defaultBaseUrl: '',
              defaultModel: '',
              protocol: 'openai',
              apiKey: '',
              isCustom: true,
            ),
          ],
          providerName: 'Custom provider',
          providerNotes: '',
          baseUrl: '',
          apiKey: '',
          model: '',
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

      await tester.enterText(find.byType(TextField).at(0), 'LM Studio');
      await tester.enterText(
        find.byType(TextField).at(1),
        'Local desktop endpoint',
      );
      await tester.enterText(
        find.byType(TextField).at(2),
        'http://10.0.2.2:1234/v1',
      );
      await tester.enterText(find.byType(TextField).at(3), '');
      await tester.enterText(
        find.byType(TextField).at(4),
        'qwen2.5-7b-instruct',
      );

      final onTap = tester
          .widget<InkWell>(
            find.byKey(const ValueKey<String>('settings-llm-save-provider')),
          )
          .onTap;
      expect(onTap, isNotNull);
      await tester.runAsync(() async {
        final result = Function.apply(onTap!, const <Object?>[]);
        if (result is Future<void>) {
          await result;
        }
      });
      await tester.pumpAndSettle();

      expect(facade.llmConfig.enabled, isTrue);
      expect(facade.llmConfig.apiKey, isEmpty);
      expect(facade.llmConfig.providerOptions.last.apiKey, isEmpty);
      expect(
        facade.llmConfig.providerOptions.last.defaultBaseUrl,
        'http://10.0.2.2:1234/v1',
      );
    },
  );

  testWidgets(
    'IPv6 loopback OpenAI-compatible custom provider stays enabled without API key',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: false,
          providerId: 'custom',
          selectedProviderOptionId: 'custom',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'custom',
              providerId: 'custom',
              title: 'Custom provider',
              subtitle:
                  'Any OpenAI-compatible, OpenAI Responses, or Anthropic endpoint.',
              defaultBaseUrl: '',
              defaultModel: '',
              protocol: 'openai',
              apiKey: '',
              isCustom: true,
            ),
          ],
          providerName: 'Custom provider',
          providerNotes: '',
          baseUrl: '',
          apiKey: '',
          model: '',
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

      await tester.enterText(find.byType(TextField).at(0), 'LM Studio IPv6');
      await tester.enterText(
        find.byType(TextField).at(1),
        'IPv6 loopback endpoint',
      );
      await tester.enterText(
        find.byType(TextField).at(2),
        'http://[::1]:1234/v1',
      );
      await tester.enterText(find.byType(TextField).at(3), '');
      await tester.enterText(
        find.byType(TextField).at(4),
        'qwen2.5-7b-instruct',
      );

      final onTap = tester
          .widget<InkWell>(
            find.byKey(const ValueKey<String>('settings-llm-save-provider')),
          )
          .onTap;
      expect(onTap, isNotNull);
      await tester.runAsync(() async {
        final result = Function.apply(onTap!, const <Object?>[]);
        if (result is Future<void>) {
          await result;
        }
      });
      await tester.pumpAndSettle();

      expect(facade.llmConfig.enabled, isTrue);
      expect(facade.llmConfig.apiKey, isEmpty);
      expect(facade.llmConfig.providerOptions.last.apiKey, isEmpty);
      expect(
        facade.llmConfig.providerOptions.last.defaultBaseUrl,
        'http://[::1]:1234/v1',
      );
    },
  );

  testWidgets(
    'saved custom provider edits stay selected, show temporary hint, and overwrite on save',
    (tester) async {
      final facade = _FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'custom',
          selectedProviderOptionId: 'saved-custom',
          protocol: 'anthropic',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'custom',
              providerId: 'custom',
              title: 'Custom provider',
              subtitle:
                  'Any OpenAI-compatible, OpenAI Responses, or Anthropic endpoint.',
              defaultBaseUrl: '',
              defaultModel: '',
              protocol: 'openai',
              apiKey: '',
              isCustom: true,
            ),
            LlmProviderOption(
              id: 'saved-custom',
              providerId: 'custom',
              title: 'Acme',
              subtitle: 'Regional fallback',
              defaultBaseUrl: 'https://api.acme.example/v1',
              defaultModel: 'claude-3-7-sonnet',
              protocol: 'anthropic',
              apiKey: 'secret',
              isCustom: true,
            ),
          ],
          providerName: 'Acme',
          providerNotes: 'Regional fallback',
          baseUrl: 'https://api.acme.example/v1',
          apiKey: 'secret',
          model: 'claude-3-7-sonnet',
          reasoningEffort: 'high',
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

      await tester.enterText(find.byType(TextField).at(0), 'Acme Edge');
      await tester.pump();

      expect(find.text('Temp edit'), findsOneWidget);
      expect(find.text('Best for larger provider lists.'), findsNothing);
      expect(facade.llmConfig.selectedProviderOptionId, 'saved-custom');

      final onTap = tester
          .widget<InkWell>(
            find.byKey(const ValueKey<String>('settings-llm-save-provider')),
          )
          .onTap;
      expect(onTap, isNotNull);
      await tester.runAsync(() async {
        final result = Function.apply(onTap!, const <Object?>[]);
        if (result is Future<void>) {
          await result;
        }
      });
      await tester.pumpAndSettle();

      expect(find.text('Temp edit'), findsNothing);
      expect(facade.llmConfig.selectedProviderOptionId, 'saved-custom');
      expect(
        facade.llmConfig.providerOptions.where(
          (option) => option.id == 'saved-custom',
        ),
        hasLength(1),
      );
      expect(facade.llmConfig.providerOptions.last.title, 'Acme Edge');
      expect(
        facade.llmConfig.providerOptions.last.subtitle,
        'Regional fallback',
      );
    },
  );

  testWidgets('primary provider sheet scrolls when the provider list is long', (
    tester,
  ) async {
    final providerOptions = <LlmProviderOption>[
      for (var index = 0; index < 18; index++)
        LlmProviderOption(
          id: 'provider-$index',
          providerId: 'provider-$index',
          title: 'Provider ${index.toString().padLeft(2, '0')}',
          subtitle: 'Test provider $index',
          defaultBaseUrl: 'https://provider$index.example.com/v1',
          defaultModel: 'model-$index',
          protocol: 'openai',
          apiKey: '',
          isCustom: false,
        ),
    ];
    final initialOption = providerOptions.first;
    final finalOption = providerOptions.last;
    final facade = _FakeSettingsFacade(
      llmConfig: LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: initialOption.providerId,
        selectedProviderOptionId: initialOption.id,
        protocol: initialOption.protocol,
        providerOptions: providerOptions,
        providerName: initialOption.title,
        providerNotes: initialOption.subtitle,
        baseUrl: initialOption.defaultBaseUrl,
        apiKey: '',
        model: initialOption.defaultModel,
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

    await tester.tap(find.text(initialOption.title));
    await tester.pumpAndSettle();

    final scrollable = find.byKey(
      const ValueKey<String>('settings-llm-provider-sheet-scroll'),
    );
    expect(scrollable, findsOneWidget);

    await tester.dragUntilVisible(
      find.text(finalOption.title),
      scrollable,
      const Offset(0, -200),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text(finalOption.title));
    await tester.pumpAndSettle();

    expect(facade.llmConfig.selectedProviderOptionId, finalOption.id);
    expect(facade.llmConfig.providerId, finalOption.providerId);
    expect(find.text(finalOption.title), findsOneWidget);
  });

  testWidgets('about version page opens debug tools and renders context trace details', (
    tester,
  ) async {
    final facade = _buildDebugSettingsFacade();
    final debugBridge = _buildDebugBridge();

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
    expect(find.text('Open Debug Tools'), findsOneWidget);

    await tester.tap(find.text('Open Debug Tools'));
    await tester.pumpAndSettle();

    expect(find.text('Runtime Diagnostics'), findsOneWidget);
    expect(find.text('Context & Memory Trace'), findsOneWidget);
    expect(find.text('Memory Inspector'), findsOneWidget);
    expect(find.text('Soul Inspector'), findsOneWidget);

    await tester.tap(find.text('Context & Memory Trace'));
    await tester.pumpAndSettle();

    expect(find.text('Run overview'), findsOneWidget);
    expect(find.text('Context setup'), findsOneWidget);
    expect(find.text('Memory writes'), findsOneWidget);
    expect(find.text('Memory recall'), findsOneWidget);
    expect(find.text('Skill context'), findsOneWidget);
    expect(find.text('Soul resolution'), findsOneWidget);
    expect(find.text('Raw trace'), findsOneWidget);
    expect(find.text('Projected subagents'), findsOneWidget);
    expect(find.text('run-memory'), findsWidgets);
    expect(find.text('Subagent: Inspect detached recovery'), findsOneWidget);
    expect(find.text('Status: background running'), findsOneWidget);
    expect(find.text('Execution: active'), findsOneWidget);
    expect(find.text('Approval: pending'), findsOneWidget);
    expect(find.text('Mailbox: 1 pending / 2 total'), findsOneWidget);
    expect(
      find.textContaining(
        'Last delivered: mailbox-memory-1',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Pending approval tool: Read', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Pending approval child: run child-run-detached-memory / task child-task-detached-memory',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'researcher · minimal · depth 1 · Detached child runtime is still running in the background.',
      ),
      findsOneWidget,
    );
    expect(find.text('Response shape: openai_tool_calls'), findsOneWidget);
    expect(find.text('Cache break: user_setting_changed'), findsOneWidget);
    expect(
      find.textContaining(
        'Tool path: requested yes, observed yes, parsed yes',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Responses recovery: 1 (responses_restored_replay_required)',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Local continuation: used 0, fallback 1, mode full_rebuild, reason user_setting_changed',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Responses context updates: pending 1, hash hash-dynamic-context',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Last successful tool: EchoProbe',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(find.text('Mode: full'), findsOneWidget);
    expect(find.text('Soul: disabled'), findsOneWidget);
    expect(find.text('Memory recall: enabled'), findsOneWidget);
    expect(find.text('Preset: dev (selected balanced)'), findsOneWidget);
    expect(find.text('Pressure: emergency'), findsOneWidget);
    expect(find.text('Layers: 4/2/1/1'), findsOneWidget);
    expect(find.text('Overflow: unresolved'), findsOneWidget);
    expect(find.text('Source caps: expanded'), findsOneWidget);
    expect(
      find.textContaining(
        'Source cap profile: expanded (stable fallback for dev envelope)',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Working State: compact, 220 -> 120 tokens, optional support context #70, ops reduce_working_state_compact',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Retrieved Memory: omitted, 48 -> 0 tokens, bounded durable recall #90, ops omit_layer',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Attempt: 1', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining('Execution: 2', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Execution kind: checkpoint_resume',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Retry code:', findRichText: true),
      findsNothing,
    );
    expect(find.text('Outcome: written'), findsOneWidget);
    expect(
      find.textContaining('Execution mode: inline', findRichText: true),
      findsAtLeastNWidgets(2),
    );
    expect(find.text('Compacted: yes'), findsOneWidget);
    expect(
      find.textContaining(
        'Remote compaction: used, supported, requested, trigger pre_compaction',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Remote compaction details: output 2, compaction 1, encrypted 1',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(find.text('Restricted: yes'), findsOneWidget);
    expect(find.text('Matched: 2'), findsOneWidget);
    expect(find.text('Injected: 1'), findsOneWidget);
    expect(
      find.textContaining(
        'AGENTS.md: workspace/AGENTS.md · injected 520/900 chars · truncated',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Summary window: included 1, omitted 2',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'memory-link: skills/memory-link/SKILL.md · manual · inline · user-invocable',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Allowed tools: skill_read, memory_search',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Query terms: chinese, gradle', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining('Record: memory-user', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining('Reason: max_records', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining('scope_mismatch: 1', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining('expired: 2', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Written ids: memory-user, commitment-1',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Bridge note: Detailed soul attribution and relationship gates live in Soul Inspector only.',
        findRichText: true,
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Read workspace/AGENTS.md lines 1-14'),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'reason Inspect workspace instructions before planning.',
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Returned 14 lines from workspace/AGENTS.md (56-line file)',
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Repository guidelines for mobile work.'),
      findsOneWidget,
    );
    expect(find.text('No additional payload.'), findsNothing);
  });

  testWidgets(
    'runtime diagnostics page renders detached host and service state',
    (tester) async {
      final facade = _buildDebugSettingsFacade();
      final debugBridge = _buildDebugBridge();

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

      await tester.tap(find.text('Open Debug Tools'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Runtime Diagnostics'));
      await tester.pumpAndSettle();

      expect(find.text('Connection & transport'), findsOneWidget);
      expect(find.text('Local runtime server'), findsOneWidget);
      expect(find.text('Host & ownership'), findsOneWidget);
      expect(find.text('Runtime owner work'), findsOneWidget);
      expect(find.text('Runtime service'), findsOneWidget);
      expect(find.text('Transport: binder'), findsOneWidget);
      expect(find.text('Phase: listening'), findsWidgets);
      expect(find.text('Detached owner: yes'), findsOneWidget);
      expect(find.text('Active work: yes'), findsWidgets);
      expect(
        find.textContaining('Listening port: 42617', findRichText: true),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'Pending work sessions: session-1',
          findRichText: true,
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'Service instance: runtime-service-1',
          findRichText: true,
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining('Last start id: 7', findRichText: true),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'memory inspector renders persisted records and selection details',
    (tester) async {
      final facade = _buildDebugSettingsFacade();
      final debugBridge = _buildDebugBridge();

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

      await tester.tap(find.text('Open Debug Tools'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Memory Inspector'));
      await tester.pumpAndSettle();

      final linkedActivityCard = find.byKey(
        const ValueKey<String>('settings-memory-linked-activity-card'),
      );

      expect(find.text('Store summary'), findsOneWidget);
      expect(find.text('Filter'), findsOneWidget);
      expect(find.text('Memory records'), findsOneWidget);
      expect(find.text('Selected record'), findsOneWidget);
      expect(find.text('Linked activity'), findsOneWidget);
      expect(
        find.textContaining('memory-user', findRichText: true),
        findsWidgets,
      );
      expect(
        find.textContaining('commitment-1', findRichText: true),
        findsWidgets,
      );
      expect(
        find.textContaining('Kind: user_preference', findRichText: true),
        findsOneWidget,
      );
      expect(
        find.textContaining('Latest state: active', findRichText: true),
        findsOneWidget,
      );
      expect(
        find.textContaining('Preference value: Xiao Bai', findRichText: true),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: linkedActivityCard,
          matching: find.textContaining(
            'Source run: run-memory-origin-1',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: linkedActivityCard,
          matching: find.textContaining(
            'display name: user memory: Xiao Bai',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: linkedActivityCard,
          matching: find.textContaining('memory_search'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'memory inspector applies suppress action and refreshes linked activity',
    (tester) async {
      final facade = _buildDebugSettingsFacade();
      final debugBridge = _buildDebugBridge();

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

      await tester.tap(find.text('Open Debug Tools'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Memory Inspector'));
      await tester.pumpAndSettle();

      final suppressAction = find.byKey(
        const ValueKey<String>('settings-memory-action-suppress'),
      );
      await tester.ensureVisible(suppressAction);
      await tester.tap(suppressAction);
      await tester.pumpAndSettle();

      final linkedActivityCard = find.byKey(
        const ValueKey<String>('settings-memory-linked-activity-card'),
      );

      expect(debugBridge.lastMemoryActionRecordId, 'memory-user');
      expect(debugBridge.lastMemoryActionId, 'suppress');
      expect(
        find.textContaining('Latest state: resolved', findRichText: true),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'Resolution reason: operator_suppressed',
          findRichText: true,
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('settings-memory-action-reaffirm')),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: linkedActivityCard,
          matching: find.textContaining('Suppressed', findRichText: true),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('memory inspector can search projected memory and load a slice', (
    tester,
  ) async {
    final facade = _buildDebugSettingsFacade();
    final debugBridge = _buildDebugBridge();

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

    await tester.tap(find.text('Open Debug Tools'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Memory Inspector'));
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const ValueKey<String>('settings-memory-search-input')),
      'xiao bai',
    );
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pumpAndSettle();

    expect(debugBridge.lastMemorySearchQuery, 'xiao bai');
    expect(debugBridge.lastMemorySlicePath, 'MEMORY.md');
    expect(debugBridge.lastMemorySliceFromLine, 5);
    expect(debugBridge.lastMemorySliceLines, 1);
    expect(find.text('Search projected memory'), findsOneWidget);
    expect(find.text('Selected snippet'), findsOneWidget);
    expect(
      find.textContaining('memory-user · MEMORY.md#5', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining('User prefers Chinese replies.', findRichText: true),
      findsWidgets,
    );
  });

  testWidgets('soul inspector renders snapshot-backed soul details', (
    tester,
  ) async {
    final facade = _buildDebugSettingsFacade();
    final debugBridge = _buildDebugBridge();

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

    await tester.tap(find.text('Open Debug Tools'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Soul Inspector'));
    await tester.pumpAndSettle();

    final storedSoulCard = find.byKey(
      const ValueKey<String>('settings-soul-stored-card'),
    );
    final baseSoulCard = find.byKey(
      const ValueKey<String>('settings-soul-base-card'),
    );
    final effectiveSoulCard = find.byKey(
      const ValueKey<String>('settings-soul-effective-card'),
    );
    final fieldSourcesCard = find.byKey(
      const ValueKey<String>('settings-soul-field-sources-card'),
    );
    final interactionPreferenceCard = find.byKey(
      const ValueKey<String>('settings-soul-interaction-preference-card'),
    );
    final relationshipGatesCard = find.byKey(
      const ValueKey<String>('settings-soul-relationship-gates-card'),
    );
    final linkedActivityCard = find.byKey(
      const ValueKey<String>('settings-soul-linked-activity-card'),
    );

    expect(find.text('Stored soul'), findsOneWidget);
    expect(find.text('Base soul'), findsOneWidget);
    expect(find.text('Memory overlays'), findsOneWidget);
    expect(find.text('Effective soul'), findsOneWidget);
    expect(find.text('Interaction preference'), findsOneWidget);
    expect(find.text('Relationship gates'), findsOneWidget);
    expect(find.text('Field sources'), findsOneWidget);
    expect(
      find.descendant(
        of: storedSoulCard,
        matching: find.textContaining('Preset: STEADY', findRichText: true),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: baseSoulCard,
        matching: find.textContaining(
          'Display name: Night Shift',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: effectiveSoulCard,
        matching: find.textContaining(
          'Display name: Xiao Bai',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: effectiveSoulCard,
        matching: find.textContaining(
          'Voice: warm and gentle',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: interactionPreferenceCard,
        matching: find.textContaining(
          'Preferred naming: A-Cheng',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: interactionPreferenceCard,
        matching: find.textContaining(
          'Preferred address style: friendly',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: relationshipGatesCard,
        matching: find.textContaining(
          'Derived address style: intimate',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: relationshipGatesCard,
        matching: find.textContaining(
          'High intimacy behavior: allowed',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: fieldSourcesCard,
        matching: find.textContaining(
          'display name ->: user memory: Xiao Bai',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(find.text('Linked activity'), findsWidgets);
    expect(
      find.descendant(
        of: linkedActivityCard,
        matching: find.textContaining('memory-user · display name'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: linkedActivityCard,
        matching: find.textContaining('run-memory-origin-1'),
      ),
      findsOneWidget,
    );
  });

  testWidgets(
    'soul inspector falls back to projected field sources when explicit field sources are absent',
    (tester) async {
      final facade = _buildDebugSettingsFacade();
      final debugBridge = _FakeDebugBridge(
        shellSnapshot: const OpenCrayShellSnapshot(
          initialTab: OpenCrayTab.chat,
          localeTag: 'en',
          hostLabel: 'HOST READY',
          hostSummary: 'Fallback debug bridge.',
          isHostConnected: true,
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        runSnapshots: const <String, OpenCrayChatRunSnapshot>{},
        memorySnapshot: const OpenCrayMemoryDebugSnapshot(
          sessionId: 'session-1',
          observedAtEpochMs: 5000,
        ),
        linksSnapshot: _buildFallbackFieldSourceLinksSnapshot(),
        memorySearchSnapshot: const OpenCrayMemoryDebugSearchSnapshot(
          sessionId: 'session-1',
          observedAtEpochMs: 5000,
        ),
        memorySliceSnapshot: const OpenCrayMemoryDebugSliceSnapshot(
          sessionId: 'session-1',
          observedAtEpochMs: 5000,
        ),
        soulSnapshot: _buildFallbackFieldSourceSoulSnapshot(),
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

      await tester.tap(find.text('Open Debug Tools'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Soul Inspector'));
      await tester.pumpAndSettle();

      final fieldSourcesCard = find.byKey(
        const ValueKey<String>('settings-soul-field-sources-card'),
      );
      final linkedActivityCard = find.byKey(
        const ValueKey<String>('settings-soul-linked-activity-card'),
      );

      expect(
        find.descendant(
          of: fieldSourcesCard,
          matching: find.textContaining(
            'warmth offset ->: user interaction preference · user · Projected interaction-preference snapshot: 1',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fieldSourcesCard,
          matching: find.textContaining(
            'reassurance offset ->: user interaction preference · user · Projected interaction-preference snapshot: 1',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fieldSourcesCard,
          matching: find.textContaining(
            'supportive reassurance allowed ->: user relationship state · user · Relationship gate derived from relationship state and constrained by reassurance preference: true',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fieldSourcesCard,
          matching: find.textContaining(
            'playful teasing allowed ->: user relationship state · user · Relationship gate derived from relationship state and constrained by playfulness preference: true',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: linkedActivityCard,
          matching: find.textContaining('interaction-state · preferred naming'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: linkedActivityCard,
          matching: find.textContaining('relationship-state · intimacy band'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: linkedActivityCard,
          matching: find.textContaining('run-interaction-state'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: linkedActivityCard,
          matching: find.textContaining('run-relationship-state'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'network search page saves slot edits, openai search base url and model, and add slot',
    (tester) async {
      final facade = _buildSettingsFacade();

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.networkSearch,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Network & Search'), findsOneWidget);

      await tester.enterText(find.byType(TextField).at(0), 'Primary Exa');
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pump(const Duration(milliseconds: 800));

      expect(facade.networkSearchConfig.slots.first.label, 'Primary Exa');

      await tester.tap(find.text('OPENAI').first);
      await tester.pump(const Duration(milliseconds: 500));

      expect(
        facade.networkSearchConfig.slots.first.providerId,
        'openai_web_search',
      );

      await tester.enterText(
        find.byType(TextField).at(1),
        'https://proxy.example.com/v1',
      );
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pump(const Duration(milliseconds: 800));

      expect(
        facade.networkSearchConfig.slots.first.baseUrl,
        'https://proxy.example.com/v1',
      );

      await tester.enterText(find.byType(TextField).at(2), 'gpt-5-mini');
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pump(const Duration(milliseconds: 800));

      expect(facade.networkSearchConfig.slots.first.model, 'gpt-5-mini');

      await tester.tap(find.byType(Switch).first);
      await tester.pump(const Duration(milliseconds: 500));

      expect(facade.networkSearchConfig.slots.first.enabled, isFalse);

      await tester.ensureVisible(find.text('+ Add search slot'));
      await tester.pump();
      await tester.tap(find.text('+ Add search slot'));
      await tester.pump();

      expect(facade.networkSearchConfig.slots.length, 3);
    },
  );

  testWidgets('home settings opens API integrations entry', (tester) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
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
      overviewSnapshot: const SettingsOverviewSnapshot(
        eyebrow: 'APP SHELL',
        title: 'Settings',
        subtitle: 'Access, providers, and personal defaults.',
        deviceTitle: 'OpenCray on this device',
        deviceSummary: 'API routes: Search + Media',
        entries: <SettingsHomeEntrySnapshot>[
          SettingsHomeEntrySnapshot(
            page: SettingsPage.apiIntegrations,
            title: 'API Integrations',
          ),
        ],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(home: SettingsFeatureScreen(facade: facade)),
    );
    await tester.pumpAndSettle();

    expect(find.text('API Integrations'), findsOneWidget);

    await tester.tap(find.text('API Integrations'));
    await tester.pumpAndSettle();

    expect(find.text('Routing rules'), findsOneWidget);
    expect(find.text('Network & Search'), findsWidgets);
    expect(find.text('Media & Speech'), findsOneWidget);
  });

  testWidgets(
    'api integrations page opens sandbox providers and the E2B detail page',
    (tester) async {
      final facade = _buildSettingsFacade();

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.apiIntegrations,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Sandbox Providers'), findsOneWidget);

      await tester.tap(find.text('Sandbox Providers'));
      await tester.pumpAndSettle();

      expect(
        find.text('Disabled by default; local execution remains available'),
        findsOneWidget,
      );
      expect(find.text('E2B'), findsWidgets);

      await tester.tap(find.text('E2B').first);
      await tester.pumpAndSettle();

      expect(find.text('Default backend'), findsOneWidget);
      expect(find.text('Run locally'), findsOneWidget);
      expect(find.text('Run in cloud'), findsOneWidget);
    },
  );

  testWidgets('privacy route maps to the privacy detail page', (tester) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
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
          initialPage: SettingsPage.privacyTelemetry,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Privacy & Telemetry'), findsOneWidget);
    expect(find.text('Share crash diagnostics'), findsOneWidget);
    expect(find.text('API Integrations'), findsNothing);
  });

  testWidgets('settings page persists shell target for privacy subpage', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
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
      overviewSnapshot: const SettingsOverviewSnapshot(
        eyebrow: 'APP SHELL',
        title: 'Settings',
        subtitle: 'Access, providers, and personal defaults.',
        deviceTitle: 'OpenCray on this device',
        deviceSummary: 'API routes: Search + Media',
        entries: <SettingsHomeEntrySnapshot>[
          SettingsHomeEntrySnapshot(
            page: SettingsPage.privacyTelemetry,
            title: 'Privacy & Telemetry',
          ),
        ],
      ),
    );
    final debugBridge = _buildDebugBridge();

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(facade: facade, debugBridge: debugBridge),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('Privacy & Telemetry'));
    await tester.pumpAndSettle();

    expect(debugBridge.persistedShellTabs.last, OpenCrayTab.settings);
    expect(
      debugBridge.persistedSettingsRouteIds.last,
      SettingsPage.privacyTelemetry.routeId,
    );
  });

  testWidgets('standalone E2B page saves backend routing changes', (
    tester,
  ) async {
    final facade = _buildSettingsFacade();

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.sandboxE2b,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Run in cloud'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Run in cloud'));
    await tester.pump(const Duration(milliseconds: 400));

    expect(facade.sandboxSaveCallCount, 1);
    expect(facade.sandboxSettings.defaultBackend, 'sandbox');
  });

  testWidgets('media speech page saves edited fields and stt route', (
    tester,
  ) async {
    final facade = _buildSettingsFacade();

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.mediaSpeech,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Media & Speech'), findsOneWidget);
    expect(find.text('On-device Model'), findsWidgets);

    await tester.enterText(
      find.byType(TextField).at(1),
      'https://media.example.com',
    );
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump(const Duration(milliseconds: 800));

    expect(
      facade.mediaSpeechConfig.imageGeneration.baseUrl,
      'https://media.example.com',
    );

    await tester.ensureVisible(find.text('Video generation'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(6), 'Runway Turbo');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump(const Duration(milliseconds: 800));

    expect(facade.mediaSpeechConfig.videoGeneration.provider, 'Runway Turbo');

    await tester.enterText(find.byType(TextField).at(15), 'tts-omni');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump(const Duration(milliseconds: 800));

    expect(facade.mediaSpeechConfig.voiceGeneration.model, 'tts-omni');

    await tester.ensureVisible(find.text('External API'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('External API'));
    await tester.pump(const Duration(milliseconds: 500));

    expect(facade.mediaSpeechConfig.sttRoute, MediaSpeechSttRoute.externalApi);
    expect(find.text('OpenAI Whisper'), findsWidgets);
  });

  testWidgets('safety page saves mode and file delete policy changes', (
    tester,
  ) async {
    final facade = _FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
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
          initialPage: SettingsPage.safetyLimits,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('DEV'));
    await tester.pumpAndSettle();

    expect(facade.safetySettings.automationMode, SafetyAutomationMode.dev);

    final turnLimitValue = find
        .byKey(const ValueKey<String>('settings-safety-max-agent-turns-value'))
        .first;
    final toolCallLimitValue = find
        .byKey(const ValueKey<String>('settings-safety-max-tool-calls-value'))
        .first;

    await tester.ensureVisible(turnLimitValue);
    await tester.pumpAndSettle();

    expect(
      find.descendant(of: turnLimitValue, matching: find.text('No limit')),
      findsOneWidget,
    );

    await tester.tap(turnLimitValue);
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '24');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(facade.safetySettings.maxAgentTurns, 24);
    expect(
      find.descendant(of: turnLimitValue, matching: find.text('24 turns')),
      findsOneWidget,
    );

    await tester.ensureVisible(toolCallLimitValue);
    await tester.pumpAndSettle();
    await tester.tap(toolCallLimitValue);
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '18');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(facade.safetySettings.maxToolCalls, 18);
    expect(
      find.descendant(of: toolCallLimitValue, matching: find.text('18 calls')),
      findsOneWidget,
    );

    await tester.tap(find.text('Customize sensitive actions'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('File deletes'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Block'));
    await tester.pumpAndSettle();

    expect(facade.safetySettings.fileDeletesPolicy, ToolPolicyOverride.block);
  });

  testWidgets('safety page saves file change policy from dedicated page', (
    tester,
  ) async {
    final facade = _buildSettingsFacade();

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.safetyLimits,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('Customize sensitive actions'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('File changes'));
    await tester.pumpAndSettle();

    expect(find.text('Inherit from mode'), findsOneWidget);

    await tester.tap(find.text('Allow'));
    await tester.pumpAndSettle();

    expect(facade.safetySettings.fileChangesPolicy, ToolPolicyOverride.allow);
  });

  testWidgets(
    'safety page keeps external location disabled when authorization is denied',
    (tester) async {
      final facade = _buildSettingsFacade();
      facade.authorizationResponses['recordings'] = false;

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.safetyLimits,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('Customize sensitive actions'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('External access'));
      await tester.pumpAndSettle();

      await tester.tap(find.byType(Switch).at(3));
      await tester.pumpAndSettle();

      expect(facade.authorizationRequests, <String>['recordings']);
      expect(facade.safetySettings.isLocationEnabled('recordings'), isFalse);
      expect(facade.safetySaveCallCount, 0);
      expect(
        find.text('Recordings access is unavailable or was not granted.'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'workspace access page saves profile and approved path navigation',
    (tester) async {
      final facade = _buildSettingsFacade();

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.workspaceAccess,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('OPEN'));
      await tester.pumpAndSettle();

      expect(
        facade.safetySettings.workspaceAccessProfile,
        WorkspaceAccessProfile.open,
      );

      await tester.tap(find.byType(Switch).first);
      await tester.pumpAndSettle();

      expect(facade.safetySettings.memoryToolsEnabled, isFalse);

      await tester.ensureVisible(find.text('Read-only outside workspace'));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(Switch).at(1));
      await tester.pumpAndSettle();

      expect(facade.safetySettings.readOnlyOutsideWorkspace, isFalse);

      await tester.ensureVisible(find.text('Full'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Full'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('No soul').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('No soul').last);
      await tester.pumpAndSettle();

      expect(facade.safetySettings.liveContextMode, LiveContextMode.noSoul);

      await tester.ensureVisible(find.text('Review approved paths'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Review approved paths'));
      await tester.pumpAndSettle();

      expect(find.text('Approved paths'), findsOneWidget);
      expect(find.text('Workspace root'), findsOneWidget);
      expect(find.text('Photo library'), findsOneWidget);
    },
  );
}

_FakeSettingsFacade _buildSettingsFacade() {
  return _FakeSettingsFacade(
    llmConfig: const LlmConfigSnapshot(
      localeTag: 'en',
      enabled: false,
      providerId: 'openai',
      selectedProviderOptionId: 'openai',
      protocol: 'openai',
      providerOptions: <LlmProviderOption>[
        LlmProviderOption(
          id: 'openai',
          providerId: 'openai',
          title: 'OpenAI',
          subtitle: 'Official OpenAI-compatible endpoint.',
          defaultBaseUrl: 'https://api.openai.com/v1',
          defaultModel: 'gpt-4o-mini',
          protocol: 'openai',
          apiKey: '',
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
      onDeviceModels: _defaultOnDeviceLlmModels,
    ),
    validationResult: const LlmValidationResult(
      isSuccess: true,
      message: 'Validated.',
    ),
  );
}

_FakeSettingsFacade _buildDebugSettingsFacade({
  PersonalizationConfigSnapshot? personalizationConfig,
}) {
  return _FakeSettingsFacade(
    llmConfig: const LlmConfigSnapshot(
      localeTag: 'en',
      enabled: false,
      providerId: 'openai',
      selectedProviderOptionId: 'openai',
      protocol: 'openai',
      providerOptions: <LlmProviderOption>[
        LlmProviderOption(
          id: 'openai',
          providerId: 'openai',
          title: 'OpenAI',
          subtitle: 'Official OpenAI-compatible endpoint.',
          defaultBaseUrl: 'https://api.openai.com/v1',
          defaultModel: 'gpt-4o-mini',
          protocol: 'openai',
          apiKey: '',
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
      onDeviceModels: _defaultOnDeviceLlmModels,
    ),
    validationResult: const LlmValidationResult(
      isSuccess: true,
      message: 'Validated.',
    ),
    personalizationConfig: personalizationConfig,
  );
}

_FakeDebugBridge _buildDebugBridge({
  List<List<OpenCraySettingsImageAsset>> pickedSettingsImageAssetBatches =
      const <List<OpenCraySettingsImageAsset>>[],
}) {
  return _FakeDebugBridge(
    pickedSettingsImageAssetBatches: pickedSettingsImageAssetBatches,
    shellSnapshot: const OpenCrayShellSnapshot(
      initialTab: OpenCrayTab.chat,
      localeTag: 'en',
      hostLabel: 'HOST READY',
      hostSummary: 'Detached runtime service active.',
      isHostConnected: true,
      localRuntimeServerState: OpenCrayLocalRuntimeServerStateSnapshot(
        phase: 'listening',
        bindAddress: '127.0.0.1',
        requestedPort: 42617,
        listeningPort: 42617,
        lastStartAttemptAtEpochMs: 1200,
        lastStartedAtEpochMs: 1300,
        changedAtEpochMs: 1300,
      ),
      hostLifecycle: OpenCrayHostLifecycleSnapshot(
        processStartId: 'process-1',
        processStartedAtEpochMs: 1000,
        hostInstanceId: 'host-ui-1',
        runtimeOwnerId: 'owner-service-1',
        hostCreatedAtEpochMs: 2000,
      ),
      runtimeOwnerLifecycle: OpenCrayHostLifecycleSnapshot(
        processStartId: 'process-1',
        processStartedAtEpochMs: 1000,
        hostInstanceId: 'host-service-1',
        runtimeOwnerId: 'owner-service-1',
        hostCreatedAtEpochMs: 1500,
      ),
      runtimeOwnerWorkSummary: OpenCrayRuntimeOwnerWorkSummarySnapshot(
        hasActiveWork: true,
        trackedSessionCount: 2,
        activeRunCount: 1,
        activeSessionCount: 1,
        activeSessionIds: <String>['session-1'],
        pendingWorkSessionIds: <String>['session-1'],
        liveManagedProcessSessionIds: <String>['session-1'],
      ),
      runtimeServiceLifecycle: OpenCrayRuntimeServiceLifecycleSnapshot(
        processStartId: 'process-1',
        processStartedAtEpochMs: 1000,
        serviceInstanceId: 'runtime-service-1',
        serviceCreatedAtEpochMs: 1400,
      ),
      runtimeServiceWorkState: OpenCrayRuntimeServiceWorkStateSnapshot(
        phase: 'active_work',
        hasActiveWork: true,
        keepAliveRequired: true,
        keepAliveReason: 'active_run',
        changedAtEpochMs: 2300,
        activeSinceEpochMs: 1500,
      ),
      runtimeServiceKeepAliveState:
          OpenCrayRuntimeServiceKeepAliveStateSnapshot(
            phase: 'active_work',
            idleGraceMs: 30000,
            stopScheduled: false,
            hasSeenStartCommand: true,
            lastStartId: 7,
            lastStartCommandAtEpochMs: 1450,
            changedAtEpochMs: 2300,
          ),
      runtimeServiceConnectionState:
          OpenCrayRuntimeServiceConnectionStateSnapshot(
            phase: 'bound',
            transport: 'binder',
            serviceStartRequested: true,
            bindingRequested: true,
            binderAvailable: true,
          ),
    ),
    runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
      sessionId: 'session-1',
      activeRuns: <OpenCrayChatRunSnapshot>[
        OpenCrayChatRunSnapshot(
          sessionId: 'session-1',
          runId: 'run-memory',
          taskId: 'task-memory',
          acceptedAtEpochMs: 1000,
          updatedAtEpochMs: 2400,
          attempt: 1,
          executionOrdinal: 2,
          executionKind: 'checkpoint_resume',
          isTerminal: true,
          executionStatus: 'success',
          taskState: 'completed',
          responseFormat: 'json_final',
        ),
      ],
      subAgents: <OpenCrayChatSubAgentSnapshot>[
        OpenCrayChatSubAgentSnapshot(
          parentRunId: 'run-memory',
          parentTaskId: 'task-memory',
          childRunId: 'child-run-detached-memory',
          childTaskId: 'child-task-detached-memory',
          label: 'Inspect detached recovery',
          subagentType: 'researcher',
          contextMode: 'minimal',
          depth: 1,
          phase: 'resumed',
          status: 'background_running',
          executionState: 'background_running',
          continuationKind: 'background_resume',
          resumable: true,
          summary: 'Detached child runtime is still running in the background.',
          startedAtEpochMs: 1750,
          updatedAtEpochMs: 2350,
          eventCount: 0,
          hasActiveExecution: true,
          mailboxMessageCount: 2,
          mailboxPendingMessageCount: 1,
          mailboxLastDeliveredMessageId: 'mailbox-memory-1',
          hasPendingApprovalResume: true,
          pendingApprovalToolName: 'Read',
          pendingApprovalChildRunId: 'child-run-detached-memory',
          pendingApprovalChildTaskId: 'child-task-detached-memory',
        ),
      ],
      events: <OpenCrayChatRuntimeEventSnapshot>[
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'memory_retrieval',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 1600,
          operation: 'search',
          queryTerms: <String>['chinese', 'gradle'],
          resultCount: 2,
        ),
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'tool_call',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 1700,
          toolName: 'Read',
          toolReason: 'Inspect workspace instructions before planning.',
          argumentsJson:
              '{"file_path":"workspace/AGENTS.md","offset":1,"limit":14}',
        ),
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'tool_result',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 1800,
          toolName: 'Read',
          toolStatus: 'success',
          contentPreview: 'Repository guidelines for mobile work.',
          resultMetadata: <String, String>{
            'filePath': 'workspace/AGENTS.md',
            'returnedLineCount': '14',
            'totalLineCount': '56',
          },
        ),
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'memory_write',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 2200,
          writtenRecordIds: <String>['memory-user', 'commitment-1'],
          writtenKinds: <String>['user_preference', 'task_commitment'],
          resolvedRecordIds: <String>['memory-project'],
          reaffirmedRecordIds: <String>['commitment-1'],
          expiredRecordIds: <String>['memory-old'],
        ),
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'memory_flush',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 2300,
          writtenRecordIds: <String>['memory-user'],
          writtenKinds: <String>['user_preference'],
        ),
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'final',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 2400,
          status: 'success',
        ),
      ],
    ),
    runSnapshots: <String, OpenCrayChatRunSnapshot>{
      'run-memory': const OpenCrayChatRunSnapshot(
        sessionId: 'session-1',
        runId: 'run-memory',
        taskId: 'task-memory',
        acceptedAtEpochMs: 1000,
        updatedAtEpochMs: 2400,
        attempt: 1,
        executionOrdinal: 2,
        executionKind: 'checkpoint_resume',
        isTerminal: true,
        executionStatus: 'success',
        taskState: 'completed',
        responseFormat: 'json_final',
        recoveryPlan: OpenCrayChatRunRecoveryPlanSnapshot(
          action: 'resume_from_checkpoint',
          reasonCode: 'durable_general_resume_checkpoint',
          summary:
              'Resumed from durable checkpoint instead of rerunning from task input.',
          safeToAutoResume: true,
          requiresUserAction: false,
          checkpointKind: 'general_resume',
          journalTailKind: 'tool_result',
        ),
        diagnostics: OpenCrayChatRunDiagnosticsSnapshot(
          recoveryReason: 'host_restart_inflight_task_interrupted',
        ),
        llmDiagnostics: OpenCrayChatRunLlmDiagnosticsSnapshot(
          providerResponseShape: 'openai_tool_calls',
          nativeToolCallRequested: true,
          nativeToolCallObserved: true,
          parsedToolCallObserved: true,
          fallbackParserAttempted: false,
          fallbackParserSucceeded: false,
          responsesContinuationRecoveryCount: 1,
          responsesContinuationRecoveryLastReason:
              'responses_restored_replay_required',
          localContinuationUsedCount: 0,
          localContinuationFallbackCount: 1,
          localContinuationLastMode: 'full_rebuild',
          localContinuationLastReason: 'user_setting_changed',
          responsesPendingContextUpdateCount: 1,
          responsesPendingContextUpdateHash: 'hash-dynamic-context',
          toolCallEventEmitted: true,
          toolResultEventEmitted: true,
          contextCacheBreakReason: 'user_setting_changed',
          lastSuccessfulToolName: 'EchoProbe',
        ),
        liveContext: OpenCrayChatRunLiveContextSnapshot(
          mode: 'no_soul',
          soulEnabled: false,
          memoryRecallEnabled: true,
        ),
        contextBudget: OpenCrayChatRunContextBudgetSnapshot(
          applied: true,
          pressureMode: 'EMERGENCY',
          selectedPreset: 'balanced',
          effectivePreset: 'dev',
          presetSource: 'raw',
          presetDiverged: true,
          sourcePreset: 'expanded',
          sourceTranscriptMaxMessages: 16,
          sourceInjectedMemoryMaxRecords: 6,
          sourceMemoryRecallMaxRecords: 8,
          sourceBootstrapMaxChars: 4800,
          sourceSkillInventoryMaxSkills: 12,
          sourceActiveSkillMaxChars: 4800,
          sourceRecentObservationMaxEntries: 6,
          sourceMemoryFlushMaxToolObservations: 12,
          contextWindowTokens: 900,
          reservedOutputTokens: 256,
          safetyMarginTokens: 96,
          hardInputTokens: 548,
          targetInputTokens: 512,
          emergencyInputTokens: 548,
          unresolvedOverflow: true,
          fullLayerCount: 4,
          compactLayerCount: 2,
          minimalLayerCount: 1,
          omittedLayerCount: 1,
          reducedLayerNames: <String>['Working State', 'Conversation'],
          omittedLayerNames: <String>['Retrieved Memory'],
          layers: <OpenCrayChatRunContextBudgetLayerSnapshot>[
            OpenCrayChatRunContextBudgetLayerSnapshot(
              id: 'WORKING_STATE',
              name: 'Working State',
              priorityClass: 'OPTIONAL_SUPPORT_CONTEXT',
              retentionPriority: 70,
              estimatedTokensBefore: 220,
              estimatedTokensAfter: 120,
              finalState: 'compact',
              omitted: false,
              reduced: true,
              appliedOperators: <String>['reduce_working_state_compact'],
            ),
            OpenCrayChatRunContextBudgetLayerSnapshot(
              id: 'CONVERSATION',
              name: 'Conversation',
              priorityClass: 'RECENT_REPLAY',
              retentionPriority: 110,
              estimatedTokensBefore: 420,
              estimatedTokensAfter: 180,
              finalState: 'minimal',
              omitted: false,
              reduced: true,
              appliedOperators: <String>['reduce_conversation_window_minimal'],
            ),
            OpenCrayChatRunContextBudgetLayerSnapshot(
              id: 'RETRIEVED_MEMORY',
              name: 'Retrieved Memory',
              priorityClass: 'BOUNDED_DURABLE_RECALL',
              retentionPriority: 90,
              estimatedTokensBefore: 48,
              estimatedTokensAfter: 0,
              finalState: 'omitted',
              omitted: true,
              reduced: false,
              appliedOperators: <String>['omit_layer'],
            ),
          ],
          layerSummary:
              'WORKING_STATE:compact:20;CONVERSATION:minimal:120;RETRIEVED_MEMORY:omitted:48',
        ),
        memoryFlush: OpenCrayChatRunMemoryFlushSnapshot(
          outcome: 'written',
          executionMode: 'inline',
          omittedMessageCount: 6,
          omittedCharCount: 2100,
          signature: 'user-intro|tool-scan|workspace-facts',
          candidateCount: 2,
          writtenRecordCount: 1,
          writtenKinds: <String>['user_preference'],
          writtenRecordIds: <String>['memory-user'],
        ),
        bootstrap: OpenCrayChatRunBootstrapSnapshot(
          mode: 'full',
          visibleFileCount: 3,
          injectedFileCount: 2,
          omittedFileCount: 1,
          truncatedFileCount: 1,
          files: <OpenCrayChatRunBootstrapFileSnapshot>[
            OpenCrayChatRunBootstrapFileSnapshot(
              name: 'AGENTS.md',
              relativePath: 'workspace/AGENTS.md',
              sourceCharCount: 900,
              injectedCharCount: 520,
              truncated: true,
            ),
            OpenCrayChatRunBootstrapFileSnapshot(
              name: 'PROJECT.md',
              relativePath: 'workspace/PROJECT.md',
              sourceCharCount: 480,
              injectedCharCount: 480,
              truncated: false,
            ),
          ],
        ),
        durableCompaction: OpenCrayChatRunDurableCompactionSnapshot(
          compactedThisRun: true,
          executionMode: 'inline',
          sourceTranscriptMessageCount: 18,
          retainedTranscriptMessageCount: 7,
          latestCompactedMessageCount: 9,
          includedSummaryCount: 1,
          omittedSummaryCount: 2,
          totalSummaryCount: 3,
          totalCompactedMessageCount: 14,
          latestCompactedAtEpochMs: 1950,
          remoteCompaction: OpenCrayChatRunRemoteCompactionSnapshot(
            requested: true,
            supported: true,
            used: true,
            triggerStage: 'pre_compaction',
            outputItemCount: 2,
            compactionItemCount: 1,
            encryptedContentCount: 1,
          ),
        ),
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
        skillInventory: OpenCrayChatRunSkillInventorySnapshot(
          visibleSkillCount: 3,
          injectedSkillCount: 2,
          omittedSkillCount: 1,
          implicitSkillCount: 0,
          invalidSkillCount: 0,
          omittedTraceSkillCount: 1,
          skills: <OpenCrayChatRunVisibleSkillSnapshot>[
            OpenCrayChatRunVisibleSkillSnapshot(
              name: 'memory-link',
              relativePath: 'skills/memory-link/SKILL.md',
              invocationControl: 'manual',
              userInvocable: true,
              executionContext: 'inline',
            ),
            OpenCrayChatRunVisibleSkillSnapshot(
              name: 'repo-planner',
              relativePath: 'skills/repo-planner/SKILL.md',
              invocationControl: 'agent',
              userInvocable: false,
              executionContext: 'inline',
            ),
          ],
        ),
        activeSkill: OpenCrayChatRunActiveSkillSnapshot(
          name: 'memory-link',
          relativePath: 'skills/memory-link/SKILL.md',
          invocationControl: 'manual',
          executionContext: 'inline',
          activationSource: 'skill_read',
          toolRestrictionEnabled: true,
          truncated: false,
          allowedToolKeys: <String>['skill_read', 'memory_search'],
        ),
      ),
    },
    memorySnapshot: const OpenCrayMemoryDebugSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      records: <OpenCrayMemoryDebugRecordSnapshot>[
        OpenCrayMemoryDebugRecordSnapshot(
          id: 'memory-user',
          content: 'User prefers Chinese replies.',
          kind: 'user_preference',
          scope: 'user',
          status: 'active',
          source: 'user_input',
          sourceSessionId: 'session-1',
          updatedAtEpochMs: 4200,
          lastConfirmedAtEpochMs: 4200,
          preferenceKey: 'agent_display_name',
          preferenceValue: 'Xiao Bai',
          preferenceTemporality: 'durable',
          tags: <String>['kind:user_preference', 'scope:user'],
          extensions: <String, String>{
            'kind': 'user_preference',
            'scope': 'user',
            'status': 'active',
          },
        ),
        OpenCrayMemoryDebugRecordSnapshot(
          id: 'commitment-1',
          content: 'Finish context-management backlog.',
          kind: 'task_commitment',
          scope: 'session',
          status: 'open',
          source: 'assistant_output',
          sourceSessionId: 'session-1',
          sourceTaskId: 'task-memory',
          updatedAtEpochMs: 4100,
          lastConfirmedAtEpochMs: 4100,
          ttlMs: 1209600000,
          tags: <String>['kind:task_commitment', 'scope:session'],
          extensions: <String, String>{
            'kind': 'task_commitment',
            'scope': 'session',
            'status': 'open',
          },
        ),
        OpenCrayMemoryDebugRecordSnapshot(
          id: 'memory-old',
          content: 'Old workspace preference.',
          kind: 'user_preference',
          scope: 'workspace',
          status: 'active',
          source: 'user_input',
          sourceSessionId: 'session-0',
          workspaceId: 'workspace-main',
          updatedAtEpochMs: 1000,
          lastConfirmedAtEpochMs: 1000,
          ttlMs: 1000,
          isExpired: true,
          tags: <String>['kind:user_preference', 'scope:workspace'],
          extensions: <String, String>{
            'kind': 'user_preference',
            'scope': 'workspace',
            'status': 'active',
          },
        ),
      ],
    ),
    linksSnapshot: const OpenCrayMemoryDebugLinksSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      records: <OpenCrayMemoryDebugLinksEntrySnapshot>[
        OpenCrayMemoryDebugLinksEntrySnapshot(
          recordId: 'memory-user',
          sourceSessionId: 'session-1',
          sourceTaskId: 'task-memory-origin-1',
          sourceRun: OpenCrayDebugRunLinkSnapshot(
            sessionId: 'session-1',
            runId: 'run-memory-origin-1',
            taskId: 'task-memory-origin-1',
            acceptedAtEpochMs: 2000,
            updatedAtEpochMs: 2200,
            executionStatus: 'success',
            lifecycleState: 'completed',
          ),
          promptRecalls: <OpenCrayMemoryPromptRecallLinkSnapshot>[
            OpenCrayMemoryPromptRecallLinkSnapshot(
              occurredAtEpochMs: 4600,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-memory',
                taskId: 'task-memory',
                acceptedAtEpochMs: 1000,
                updatedAtEpochMs: 2400,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
              score: 420,
              matchedTerms: <String>['chinese'],
            ),
          ],
          toolRetrievals: <OpenCrayMemoryToolRetrievalLinkSnapshot>[
            OpenCrayMemoryToolRetrievalLinkSnapshot(
              occurredAtEpochMs: 1600,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-memory',
                taskId: 'task-memory',
                acceptedAtEpochMs: 1000,
                updatedAtEpochMs: 2400,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
              toolName: 'memory_search',
              operation: 'search',
              query: 'what name should I call the agent',
              queryTerms: <String>['name', 'agent'],
              paths: <String>['memory/2024-03-11.md'],
              lineRanges: <String>['5-8'],
            ),
          ],
          maintenanceActions: <OpenCrayMemoryMaintenanceActionLinkSnapshot>[
            OpenCrayMemoryMaintenanceActionLinkSnapshot(
              action: 'written',
              occurredAtEpochMs: 2200,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-memory-origin-1',
                taskId: 'task-memory-origin-1',
                acceptedAtEpochMs: 2000,
                updatedAtEpochMs: 2200,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
            ),
            OpenCrayMemoryMaintenanceActionLinkSnapshot(
              action: 'flush_written',
              occurredAtEpochMs: 2300,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-memory',
                taskId: 'task-memory',
                acceptedAtEpochMs: 1000,
                updatedAtEpochMs: 2400,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
            ),
          ],
        ),
        OpenCrayMemoryDebugLinksEntrySnapshot(
          recordId: 'memory-style',
          sourceSessionId: 'session-1',
          sourceTaskId: 'task-memory-style-1',
          sourceRun: OpenCrayDebugRunLinkSnapshot(
            sessionId: 'session-1',
            runId: 'run-memory-style-1',
            taskId: 'task-memory-style-1',
            acceptedAtEpochMs: 3000,
            updatedAtEpochMs: 3250,
            executionStatus: 'success',
            lifecycleState: 'completed',
          ),
          maintenanceActions: <OpenCrayMemoryMaintenanceActionLinkSnapshot>[
            OpenCrayMemoryMaintenanceActionLinkSnapshot(
              action: 'written',
              occurredAtEpochMs: 3250,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-memory-style-1',
                taskId: 'task-memory-style-1',
                acceptedAtEpochMs: 3000,
                updatedAtEpochMs: 3250,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
            ),
          ],
        ),
      ],
    ),
    memorySearchSnapshot: const OpenCrayMemoryDebugSearchSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      queryTerms: <String>['xiao', 'bai'],
      corpusFileCount: 2,
      results: <OpenCrayMemoryDebugSearchResultSnapshot>[
        OpenCrayMemoryDebugSearchResultSnapshot(
          recordId: 'memory-user',
          path: 'MEMORY.md',
          startLine: 5,
          endLine: 5,
          score: 420,
          matchedTerms: <String>['xiao', 'bai'],
          kind: 'user_preference',
          scope: 'user',
          status: 'active',
          snippet:
              '- [memory-user] kind=user_preference scope=user status=active confirmed_at=2026-03-19 content=User prefers Chinese replies.',
        ),
      ],
    ),
    memorySliceSnapshot: const OpenCrayMemoryDebugSliceSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      path: 'MEMORY.md',
      text:
          '- [memory-user] kind=user_preference scope=user status=active confirmed_at=2026-03-19 content=User prefers Chinese replies.',
      startLine: 5,
      endLine: 5,
      totalLineCount: 12,
      recordIds: <String>['memory-user'],
    ),
    soulSnapshot: const OpenCraySoulDebugSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      storedSoul: OpenCrayStoredSoulRecordSnapshot(
        agentId: 'app-shell-personalization',
        displayName: 'Night Shift',
        presetName: 'STEADY',
        customGuidance: 'Keep replies calm and concrete.',
        recordVersion: 2,
        createdAtEpochMs: 1000,
        updatedAtEpochMs: 4300,
        extensions: <String, String>{
          'preset': 'STEADY',
          'tone': 'steady',
          'verbosity': 'balanced',
          'user_relationship_style': 'collaborative',
          'risk_tolerance': 'conservative',
          'tool_use_bias': 'verify_first',
        },
      ),
      baseSoul: OpenCraySoulProfileDebugSnapshot(
        presetName: 'STEADY',
        displayName: 'Night Shift',
        customGuidance: 'Keep replies calm and concrete.',
        tone: 'steady',
        verbosity: 'balanced',
        userRelationshipStyle: 'collaborative',
        riskTolerance: 'conservative',
        toolUseBias: 'verify_first',
        escalationRules: <String>[
          'Ask before risky workspace or environment changes.',
        ],
        forbiddenBehaviors: <String>[
          'Do not fabricate workspace facts when local evidence is required.',
        ],
        collaborationPreferences: <String>[
          'Keep reasoning concrete and implementation-first.',
        ],
      ),
      effectiveSoul: OpenCraySoulProfileDebugSnapshot(
        presetName: 'STEADY',
        displayName: 'Xiao Bai',
        voice: 'warm and gentle',
        customGuidance: 'Keep replies calm and concrete.',
        tone: 'warm',
        verbosity: 'balanced',
        userRelationshipStyle: 'supportive',
        riskTolerance: 'conservative',
        toolUseBias: 'verify_first',
        escalationRules: <String>[
          'Ask before risky workspace or environment changes.',
        ],
        forbiddenBehaviors: <String>[
          'Do not fabricate workspace facts when local evidence is required.',
        ],
        collaborationPreferences: <String>[
          'Keep reasoning concrete and implementation-first.',
        ],
      ),
      interactionPreferenceDebug: OpenCrayInteractionPreferenceDebugSnapshot(
        scope: 'user',
        snapshotRecordId: 'interaction-state',
        preferredNaming: 'A-Cheng',
        preferredAddressStyle: 'friendly',
        derivedRelationshipStyle: 'warm',
        state: OpenCrayInteractionPreferenceStateSnapshot(
          warmth: OpenCrayPreferenceAxisStateSnapshot(
            offset: 1,
            higherSupport: 2,
          ),
          formality: OpenCrayPreferenceAxisStateSnapshot(
            offset: -1,
            lowerSupport: 2,
          ),
          initiative: OpenCrayPreferenceAxisStateSnapshot(),
          addressStyle: OpenCrayPreferredAddressStateSnapshot(
            selectedStyle: 'friendly',
            friendlySupport: 2,
          ),
          preferredNaming: 'A-Cheng',
          preferredNamingSupport: 2,
        ),
      ),
      relationshipStateDebug: OpenCrayRelationshipStateDebugSnapshot(
        scope: 'user',
        snapshotRecordId: 'relationship-state',
        state: OpenCrayRelationshipStateSnapshot(
          familiarity: 66,
          trust: 74,
          safety: 76,
          intimacyPermission: 61,
          playfulnessPermission: 44,
          affectionTendency: 34,
          reciprocity: 49,
        ),
        recentNegativeGuardActive: false,
        supportiveStyleUnlocked: true,
        supportiveStyleChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'trust',
            passed: true,
            currentValue: 74,
            threshold: 25,
          ),
        ],
        warmToneUnlocked: true,
        warmToneChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'intimacy_permission',
            passed: true,
            currentValue: 61,
            threshold: 25,
          ),
        ],
        derivedAddressStyle: 'intimate',
        friendlyAddressChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'trust',
            passed: true,
            currentValue: 74,
            threshold: 35,
          ),
        ],
        intimateAddressChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'recent_negative_guard_inactive',
            passed: true,
            actualBoolean: true,
            expectedBoolean: true,
          ),
        ],
        intimacyPermissionBand: 'warm',
        playfulnessPermissionBand: 'familiar',
        highIntimacyBehaviorAllowed: true,
        highIntimacyChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'intimacy_permission',
            passed: true,
            currentValue: 61,
            threshold: 50,
          ),
        ],
        playfulAffectionAllowed: true,
        playfulAffectionChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'playfulness_permission',
            passed: true,
            currentValue: 44,
            threshold: 35,
          ),
        ],
      ),
      overlayRecords: <OpenCrayMemoryDebugRecordSnapshot>[
        OpenCrayMemoryDebugRecordSnapshot(
          id: 'memory-user',
          content: 'Call the agent Xiao Bai and keep the tone warmer.',
          kind: 'user_preference',
          scope: 'user',
          status: 'active',
          source: 'user_input',
          sourceSessionId: 'session-1',
          updatedAtEpochMs: 4200,
          lastConfirmedAtEpochMs: 4200,
          preferenceKey: 'agent_display_name',
          preferenceValue: 'Xiao Bai',
          preferenceTemporality: 'durable',
          extensions: <String, String>{'soul_display_name': 'Xiao Bai'},
        ),
        OpenCrayMemoryDebugRecordSnapshot(
          id: 'memory-style',
          content: 'Keep a warmer tone.',
          kind: 'user_preference',
          scope: 'session',
          status: 'active',
          source: 'user_input',
          sourceSessionId: 'session-1',
          updatedAtEpochMs: 4250,
          lastConfirmedAtEpochMs: 4250,
          preferenceKey: 'agent_style_profile',
          preferenceValue: 'warm',
          preferenceTemporality: 'session',
          extensions: <String, String>{
            'soul_tone': 'warm',
            'soul_voice': 'warm and gentle',
            'soul_user_relationship_style': 'supportive',
          },
        ),
      ],
      fieldSources: <OpenCraySoulFieldSourceSnapshot>[
        OpenCraySoulFieldSourceSnapshot(
          field: 'presetName',
          value: 'STEADY',
          sourceType: 'stored_soul',
          sourceLabel: 'stored soul preset',
        ),
        OpenCraySoulFieldSourceSnapshot(
          field: 'displayName',
          value: 'Xiao Bai',
          sourceType: 'memory_overlay',
          sourceLabel: 'user memory',
          recordId: 'memory-user',
          preferenceKey: 'agent_display_name',
        ),
        OpenCraySoulFieldSourceSnapshot(
          field: 'tone',
          value: 'warm',
          sourceType: 'memory_overlay',
          sourceLabel: 'session memory',
          recordId: 'memory-style',
          preferenceKey: 'agent_style_profile',
        ),
        OpenCraySoulFieldSourceSnapshot(
          field: 'voice',
          value: 'warm and gentle',
          sourceType: 'memory_overlay',
          sourceLabel: 'session memory',
          recordId: 'memory-style',
          preferenceKey: 'agent_style_profile',
        ),
      ],
    ),
  );
}

OpenCraySoulDebugSnapshot _buildFallbackFieldSourceSoulSnapshot() =>
    const OpenCraySoulDebugSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      storedSoul: OpenCrayStoredSoulRecordSnapshot(
        agentId: 'app-shell-personalization',
        displayName: 'Night Shift',
        presetName: 'STEADY',
        customGuidance: 'Keep replies calm and concrete.',
      ),
      baseSoul: OpenCraySoulProfileDebugSnapshot(
        presetName: 'STEADY',
        displayName: 'Night Shift',
        customGuidance: 'Keep replies calm and concrete.',
        tone: 'steady',
        verbosity: 'balanced',
        userRelationshipStyle: 'collaborative',
        riskTolerance: 'conservative',
        toolUseBias: 'verify_first',
      ),
      effectiveSoul: OpenCraySoulProfileDebugSnapshot(
        presetName: 'STEADY',
        displayName: 'Night Shift',
        voice: 'steady and warm',
        preferredNaming: 'A-Cheng',
        preferredAddressStyle: 'friendly',
        warmthPreferenceOffset: '1',
        formalityPreferenceOffset: '-1',
        initiativePreferenceOffset: '1',
        playfulnessPreferenceOffset: '1',
        reassurancePreferenceOffset: '1',
        intimacyPermissionBand: 'warm',
        playfulnessPermissionBand: 'familiar',
        supportiveReassuranceAllowed: 'true',
        proactiveRelationalCheckInAllowed: 'true',
        lightPlayfulnessAllowed: 'true',
        playfulTeasingAllowed: 'true',
        highIntimacyBehaviorAllowed: 'true',
        playfulAffectionAllowed: 'true',
        customGuidance: 'Keep replies calm and concrete.',
        tone: 'warm',
        verbosity: 'balanced',
        userRelationshipStyle: 'supportive',
        riskTolerance: 'conservative',
        toolUseBias: 'verify_first',
      ),
      fieldSources: <OpenCraySoulFieldSourceSnapshot>[],
      interactionPreferenceDebug: OpenCrayInteractionPreferenceDebugSnapshot(
        scope: 'user',
        snapshotRecordId: 'interaction-state',
        preferredNaming: 'A-Cheng',
        preferredAddressStyle: 'friendly',
        derivedRelationshipStyle: 'warm',
        state: OpenCrayInteractionPreferenceStateSnapshot(
          warmth: OpenCrayPreferenceAxisStateSnapshot(
            offset: 1,
            higherSupport: 2,
          ),
          formality: OpenCrayPreferenceAxisStateSnapshot(
            offset: -1,
            lowerSupport: 2,
          ),
          initiative: OpenCrayPreferenceAxisStateSnapshot(
            offset: 1,
            higherSupport: 2,
          ),
          playfulness: OpenCrayPreferenceAxisStateSnapshot(
            offset: 1,
            higherSupport: 2,
          ),
          reassurance: OpenCrayPreferenceAxisStateSnapshot(
            offset: 1,
            higherSupport: 2,
          ),
          addressStyle: OpenCrayPreferredAddressStateSnapshot(
            selectedStyle: 'friendly',
            friendlySupport: 2,
          ),
          preferredNaming: 'A-Cheng',
          preferredNamingSupport: 2,
        ),
      ),
      relationshipStateDebug: OpenCrayRelationshipStateDebugSnapshot(
        scope: 'user',
        snapshotRecordId: 'relationship-state',
        state: OpenCrayRelationshipStateSnapshot(
          familiarity: 66,
          trust: 74,
          safety: 76,
          intimacyPermission: 61,
          playfulnessPermission: 44,
          affectionTendency: 34,
          reciprocity: 49,
        ),
        recentNegativeGuardActive: false,
        supportiveStyleUnlocked: true,
        warmToneUnlocked: true,
        derivedAddressStyle: 'intimate',
        intimacyPermissionBand: 'warm',
        playfulnessPermissionBand: 'familiar',
        supportiveReassuranceAllowed: true,
        proactiveRelationalCheckInAllowed: true,
        lightPlayfulnessAllowed: true,
        playfulTeasingAllowed: true,
        highIntimacyBehaviorAllowed: true,
        playfulAffectionAllowed: true,
      ),
    );

OpenCrayMemoryDebugLinksSnapshot _buildFallbackFieldSourceLinksSnapshot() =>
    const OpenCrayMemoryDebugLinksSnapshot(
      sessionId: 'session-1',
      observedAtEpochMs: 5000,
      records: <OpenCrayMemoryDebugLinksEntrySnapshot>[
        OpenCrayMemoryDebugLinksEntrySnapshot(
          recordId: 'interaction-state',
          sourceSessionId: 'session-1',
          sourceTaskId: 'task-interaction-state',
          sourceRun: OpenCrayDebugRunLinkSnapshot(
            sessionId: 'session-1',
            runId: 'run-interaction-state',
            taskId: 'task-interaction-state',
            acceptedAtEpochMs: 2300,
            updatedAtEpochMs: 2300,
            executionStatus: 'success',
            lifecycleState: 'completed',
          ),
          maintenanceActions: <OpenCrayMemoryMaintenanceActionLinkSnapshot>[
            OpenCrayMemoryMaintenanceActionLinkSnapshot(
              action: 'written',
              occurredAtEpochMs: 2300,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-interaction-state',
                taskId: 'task-interaction-state',
                acceptedAtEpochMs: 2300,
                updatedAtEpochMs: 2300,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
            ),
          ],
        ),
        OpenCrayMemoryDebugLinksEntrySnapshot(
          recordId: 'relationship-state',
          sourceSessionId: 'session-1',
          sourceTaskId: 'task-relationship-state',
          sourceRun: OpenCrayDebugRunLinkSnapshot(
            sessionId: 'session-1',
            runId: 'run-relationship-state',
            taskId: 'task-relationship-state',
            acceptedAtEpochMs: 2400,
            updatedAtEpochMs: 2400,
            executionStatus: 'success',
            lifecycleState: 'completed',
          ),
          maintenanceActions: <OpenCrayMemoryMaintenanceActionLinkSnapshot>[
            OpenCrayMemoryMaintenanceActionLinkSnapshot(
              action: 'written',
              occurredAtEpochMs: 2400,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-relationship-state',
                taskId: 'task-relationship-state',
                acceptedAtEpochMs: 2400,
                updatedAtEpochMs: 2400,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
            ),
          ],
        ),
      ],
    );

_FakeSettingsFacade _notificationTestFacade({
  Future<NotificationSettingsSnapshot> Function(
    NotificationSettingsSnapshot snapshot,
  )?
  onSaveNotificationSettings,
  Future<ScheduledTasksSnapshot> Function()? onLoadScheduledTasks,
}) => _FakeSettingsFacade(
  llmConfig: const LlmConfigSnapshot(
    localeTag: 'en',
    enabled: false,
    providerId: 'openai',
    selectedProviderOptionId: 'openai',
    protocol: 'openai',
    providerOptions: <LlmProviderOption>[],
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
  onSaveNotificationSettings: onSaveNotificationSettings,
  onLoadScheduledTasks: onLoadScheduledTasks,
);

ScheduledTaskDetails _copyScheduledTaskDetails(
  ScheduledTaskDetails task, {
  bool? enabled,
}) => ScheduledTaskDetails(
  scheduleId: task.scheduleId,
  sessionId: task.sessionId,
  title: task.title,
  enabled: enabled ?? task.enabled,
  triggerKind: task.triggerKind,
  triggerSummary: task.triggerSummary,
  prompt: task.prompt,
  nextTriggerAtEpochMs: task.nextTriggerAtEpochMs,
  snoozedUntilEpochMs: task.snoozedUntilEpochMs,
  conflictPolicy: task.conflictPolicy,
  foregroundNotificationRequired: task.foregroundNotificationRequired,
  notifyOnQueued: task.notifyOnQueued,
  notifyOnApproval: task.notifyOnApproval,
  notifyOnCompletion: task.notifyOnCompletion,
  notifyOnInterruption: task.notifyOnInterruption,
  createdAtEpochMs: task.createdAtEpochMs,
  updatedAtEpochMs: task.updatedAtEpochMs,
);

class _FakeSettingsFacade implements SettingsFacade {
  _FakeSettingsFacade({
    required this.llmConfig,
    required this.validationResult,
    this.onSaveLlmConfig,
    this.onSaveNotificationSettings,
    this.onLoadScheduledTasks,
    PersonalizationConfigSnapshot? personalizationConfig,
    SettingsOverviewSnapshot? overviewSnapshot,
  }) : personalizationConfig =
           personalizationConfig ??
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
           ),
       overviewSnapshot =
           overviewSnapshot ??
           const SettingsOverviewSnapshot(
             eyebrow: 'APP SHELL',
             title: 'Settings',
             subtitle: 'Access, providers, and personal defaults.',
             deviceTitle: 'OpenCray on this device',
             deviceSummary: 'API routes: Search + Media',
             entries: <SettingsHomeEntrySnapshot>[],
           );

  NetworkSearchConfigSnapshot networkSearchConfig =
      const NetworkSearchConfigSnapshot(
        localeTag: 'en',
        title: 'Network & Search',
        subtitle: 'Add API keys here. Enabled slots run top to bottom.',
        slots: <NetworkSearchSlotSnapshot>[
          NetworkSearchSlotSnapshot(
            id: 'slot-1',
            providerId: 'exa',
            label: 'Primary Exa',
            baseUrl: '',
            model: '',
            apiKey: 'sk_live_demo',
            enabled: true,
          ),
          NetworkSearchSlotSnapshot(
            id: 'slot-2',
            providerId: 'tavily',
            label: 'Tavily Backup',
            baseUrl: '',
            model: '',
            apiKey: 'tvly-demo',
            enabled: true,
          ),
        ],
      );
  MediaSpeechConfigSnapshot mediaSpeechConfig = const MediaSpeechConfigSnapshot(
    localeTag: 'en',
    title: 'Media & Speech',
    subtitle: 'Configure media APIs and STT routing.',
    imageGeneration: MediaProviderConfigSnapshot(
      provider: 'Fal AI',
      baseUrl: 'https://api.fal.ai',
      endpoint: '/v1/images',
      model: 'flux-pro',
      authProtocol: 'bearer',
      apiKey: '',
    ),
    videoGeneration: MediaProviderConfigSnapshot(
      provider: 'Runway',
      baseUrl: 'https://api.runwayml.com',
      endpoint: '/v1/videos',
      model: 'gen4_turbo',
      authProtocol: 'bearer',
      apiKey: '',
    ),
    voiceGeneration: VoiceProviderConfigSnapshot(
      provider: 'OpenAI TTS',
      baseUrl: 'https://api.openai.com',
      endpoint: '/v1/audio/speech',
      model: 'tts-1',
      voicePreset: 'alloy · calm',
      authProtocol: 'bearer',
      apiKey: '',
    ),
    sttRoute: MediaSpeechSttRoute.onDeviceModel,
    externalStt: MediaProviderConfigSnapshot(
      provider: 'OpenAI Whisper',
      baseUrl: 'https://api.openai.com',
      endpoint: '/v1/audio/transcriptions',
      model: 'whisper-1',
      authProtocol: 'bearer',
      apiKey: '',
    ),
    onDeviceModel: OnDeviceSttConfigSnapshot(
      modelPackage: 'Whisper Small',
      downloadStatus: 'Not downloaded · 1.4 GB',
    ),
  );
  LlmConfigSnapshot llmConfig;
  final LlmValidationResult validationResult;
  final Future<void> Function()? onSaveLlmConfig;
  final Future<NotificationSettingsSnapshot> Function(
    NotificationSettingsSnapshot snapshot,
  )?
  onSaveNotificationSettings;
  final Future<ScheduledTasksSnapshot> Function()? onLoadScheduledTasks;
  final PersonalizationConfigSnapshot personalizationConfig;
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
  SafetySettingsSnapshot safetySettings = const SafetySettingsSnapshot(
    automationMode: SafetyAutomationMode.auto,
    rollbackJournalEnabled: true,
    maxFilesPerBatch: 20,
    undoWindowHours: 24,
    fileChangesPolicy: ToolPolicyOverride.inherit,
    fileDeletesPolicy: ToolPolicyOverride.inherit,
    shellCommandsPolicy: ToolPolicyOverride.inherit,
    externalAccessMode: ExternalAccessMode.selectPaths,
    locations: <SafetyLocationSetting>[
      SafetyLocationSetting(id: 'photo_library', enabled: true),
      SafetyLocationSetting(id: 'downloads', enabled: true),
      SafetyLocationSetting(id: 'documents', enabled: false),
      SafetyLocationSetting(id: 'recordings', enabled: false),
    ],
    workspaceAccessProfile: WorkspaceAccessProfile.work,
    readOnlyOutsideWorkspace: true,
  );
  SandboxSettingsSnapshot sandboxSettings = const SandboxSettingsSnapshot(
    localeTag: 'en',
    enabled: false,
    providerId: 'e2b',
    defaultBackend: 'local',
    sessionMode: 'ephemeral',
    autoResume: false,
    idleTimeoutMinutes: 15,
    startupTimeoutMs: 30000,
    requestTimeoutMs: 300000,
    timeoutAction: 'kill',
    templateId: '',
    e2bApiKey: '',
    apiKeyConfigured: false,
  );
  NotificationSettingsSnapshot notificationSettings =
      const NotificationSettingsSnapshot(
        masterEnabled: true,
        defaultDeliveryMode: NotificationDeliveryMode.all,
        quietHoursEnabled: true,
        quietHoursStartMinutes: 23 * 60,
        quietHoursEndMinutes: 8 * 60,
        approvalRequestsEnabled: true,
        approvalReminderEnabled: true,
        taskFinishedEnabled: true,
        taskFailedEnabled: true,
        scheduledWakeEnabled: true,
        backgroundTaskPausedEnabled: true,
        serviceRecoveredEnabled: true,
      );
  ScheduledTasksSnapshot scheduledTasks = const ScheduledTasksSnapshot(
    tasks: <ScheduledTaskSummary>[],
    totalCount: 0,
    enabledCount: 0,
  );
  ScheduledTaskDetailSnapshot? scheduledTaskDetail;
  final List<bool> scheduledTaskEnabledRequests = <bool>[];
  int scheduledTaskRunNowCallCount = 0;
  int scheduledTaskSnoozeCallCount = 0;
  StrongBackgroundSnapshot strongBackgroundSnapshot =
      const StrongBackgroundSnapshot(
        source: 'strong-background',
        available: true,
        tier: StrongBackgroundTier.baseline,
        setupComplete: false,
        recommendedActionIds: <StrongBackgroundActionId>[
          StrongBackgroundActionId.openNotificationSettings,
        ],
        notifications: StrongBackgroundNotificationsSnapshot(
          permissionRequired: true,
          permissionGranted: false,
          enabled: false,
          configured: false,
        ),
        exactAlarms: StrongBackgroundExactAlarmSnapshot(
          accessRequired: true,
          accessGranted: false,
          configured: false,
        ),
        batteryOptimization: StrongBackgroundBatteryOptimizationSnapshot(
          supported: true,
          exempt: false,
          configured: false,
        ),
        actions: <StrongBackgroundActionSnapshot>[
          StrongBackgroundActionSnapshot(
            id: StrongBackgroundActionId.openNotificationSettings,
            available: true,
            recommended: true,
          ),
        ],
      );
  final Map<String, bool> authorizationResponses = <String, bool>{};
  final List<String> authorizationRequests = <String>[];
  final List<StrongBackgroundActionId> strongBackgroundActionRequests =
      <StrongBackgroundActionId>[];
  final SettingsOverviewSnapshot overviewSnapshot;
  int saveCallCount = 0;
  int validationCallCount = 0;
  int notificationSaveCallCount = 0;
  final List<NotificationSettingsSnapshot> notificationSaveRequests =
      <NotificationSettingsSnapshot>[];
  int sandboxSaveCallCount = 0;
  int safetySaveCallCount = 0;

  @override
  Future<SettingsOverviewSnapshot> loadOverview() async => overviewSnapshot;

  @override
  Stream<SettingsOverviewSnapshot> watchOverview() =>
      Stream<SettingsOverviewSnapshot>.empty();

  @override
  Future<SettingsDetailSnapshot> loadDetail(
    SettingsPage page,
  ) async => SettingsDetailSnapshot(
    page: page,
    title: switch (page) {
      SettingsPage.notificationsBackground => 'Notifications & Background',
      SettingsPage.eventAlerts => 'Event Alerts',
      SettingsPage.privacyTelemetry => 'Privacy & Telemetry',
      SettingsPage.aboutVersion => 'About & Version',
      _ => '',
    },
    subtitle: switch (page) {
      SettingsPage.notificationsBackground =>
        'Control alerts, service visibility, and wakeups.',
      SettingsPage.eventAlerts =>
        'Choose which app events can publish a new alert.',
      SettingsPage.privacyTelemetry =>
        'Review what stays on device and what diagnostic signals are shared.',
      SettingsPage.aboutVersion => 'Build information and app diagnostics.',
      _ => '',
    },
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
        : page == SettingsPage.privacyTelemetry
        ? const <SettingsSectionSnapshot>[
            SettingsSectionSnapshot(
              title: 'Diagnostics',
              rows: <SettingsRowSnapshot>[
                SettingsRowSnapshot.toggle(
                  title: 'Share crash diagnostics',
                  subtitle: 'Include app and runtime failure summaries only.',
                  toggleValue: false,
                ),
              ],
            ),
          ]
        : const <SettingsSectionSnapshot>[],
  );

  @override
  Future<NotificationSettingsSnapshot> loadNotificationSettings() async =>
      notificationSettings;

  @override
  Future<NotificationSettingsSnapshot> saveNotificationSettings(
    NotificationSettingsSnapshot snapshot,
  ) async {
    notificationSaveCallCount += 1;
    notificationSaveRequests.add(snapshot);
    notificationSettings =
        await onSaveNotificationSettings?.call(snapshot) ?? snapshot;
    return notificationSettings;
  }

  @override
  Future<ScheduledTasksSnapshot> loadScheduledTasks() async {
    final loader = onLoadScheduledTasks;
    return loader == null ? scheduledTasks : loader();
  }

  @override
  Future<ScheduledTaskDetailSnapshot> loadScheduledTask(
    String scheduleId,
  ) async {
    final detail = scheduledTaskDetail;
    if (detail == null || detail.task.scheduleId != scheduleId) {
      throw StateError('Scheduled task $scheduleId was not found.');
    }
    return detail;
  }

  @override
  Future<ScheduledTaskActionResult> updateScheduledTaskEnabled({
    required String scheduleId,
    required bool enabled,
  }) async {
    scheduledTaskEnabledRequests.add(enabled);
    final updatedTasks = scheduledTasks.tasks
        .map(
          (task) => task.scheduleId == scheduleId
              ? ScheduledTaskSummary(
                  scheduleId: task.scheduleId,
                  sessionId: task.sessionId,
                  title: task.title,
                  enabled: enabled,
                  triggerKind: task.triggerKind,
                  triggerSummary: task.triggerSummary,
                  nextTriggerAtEpochMs: task.nextTriggerAtEpochMs,
                  snoozedUntilEpochMs: task.snoozedUntilEpochMs,
                )
              : task,
        )
        .toList(growable: false);
    scheduledTasks = ScheduledTasksSnapshot(
      tasks: updatedTasks,
      totalCount: scheduledTasks.totalCount,
      enabledCount: updatedTasks.where((task) => task.enabled).length,
    );
    final detail = scheduledTaskDetail;
    if (detail != null && detail.task.scheduleId == scheduleId) {
      scheduledTaskDetail = ScheduledTaskDetailSnapshot(
        task: _copyScheduledTaskDetails(detail.task, enabled: enabled),
        recentRuns: detail.recentRuns,
        totalRunCount: detail.totalRunCount,
      );
    }
    return ScheduledTaskActionResult(
      action: 'update_enabled',
      scheduleId: scheduleId,
      title: detail?.task.title ?? '',
      enabled: enabled,
    );
  }

  @override
  Future<ScheduledTaskActionResult> runScheduledTaskNow(
    String scheduleId,
  ) async {
    scheduledTaskRunNowCallCount += 1;
    return ScheduledTaskActionResult(
      action: 'run_now',
      scheduleId: scheduleId,
      title: scheduledTaskDetail?.task.title ?? '',
      scheduleRunId: 'schedule-run-$scheduledTaskRunNowCallCount',
    );
  }

  @override
  Future<ScheduledTaskActionResult> snoozeScheduledTask({
    required String scheduleId,
    int durationMinutes = 15,
  }) async {
    scheduledTaskSnoozeCallCount += 1;
    return ScheduledTaskActionResult(
      action: 'snooze',
      scheduleId: scheduleId,
      title: scheduledTaskDetail?.task.title ?? '',
      snoozedUntilEpochMs: DateTime.now()
          .add(Duration(minutes: durationMinutes))
          .millisecondsSinceEpoch,
    );
  }

  @override
  Future<StrongBackgroundSnapshot> loadStrongBackgroundSnapshot() async =>
      strongBackgroundSnapshot;

  @override
  Future<StrongBackgroundActionResult> performStrongBackgroundAction(
    StrongBackgroundActionId actionId,
  ) async {
    strongBackgroundActionRequests.add(actionId);
    return StrongBackgroundActionResult(
      source: 'strong-background-action',
      actionId: actionId,
      available: true,
      launched: true,
    );
  }

  @override
  Future<NetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      networkSearchConfig;

  @override
  Future<NetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<NetworkSearchSlotSnapshot> slots,
  ) async {
    networkSearchConfig = NetworkSearchConfigSnapshot(
      localeTag: networkSearchConfig.localeTag,
      title: networkSearchConfig.title,
      subtitle: networkSearchConfig.subtitle,
      slots: slots,
    );
    return networkSearchConfig;
  }

  @override
  Future<MediaSpeechConfigSnapshot> loadMediaSpeechConfig() async =>
      mediaSpeechConfig;

  @override
  Future<MediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    MediaSpeechConfigSnapshot snapshot,
  ) async {
    mediaSpeechConfig = snapshot;
    return mediaSpeechConfig;
  }

  @override
  Future<SandboxSettingsSnapshot> loadSandboxSettings() async =>
      sandboxSettings;

  @override
  Future<SandboxSettingsSnapshot> saveSandboxSettings(
    SandboxSettingsSnapshot snapshot,
  ) async {
    sandboxSaveCallCount += 1;
    sandboxSettings = snapshot.copyWith(
      apiKeyConfigured:
          snapshot.e2bApiKey.trim().isNotEmpty || snapshot.apiKeyConfigured,
    );
    return sandboxSettings;
  }

  @override
  Future<LlmConfigSnapshot> loadLlmConfig() async => llmConfig;

  @override
  Future<LlmConfigSnapshot> saveLlmConfig({
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
  }) async {
    saveCallCount += 1;
    await onSaveLlmConfig?.call();
    final hasExplicitContextBudgetPayload = contextBudgetPreset != null;
    llmConfig = LlmConfigSnapshot(
      localeTag: llmConfig.localeTag,
      enabled: enabled,
      streamingEnabled: streamingEnabled ?? llmConfig.streamingEnabled,
      providerMode: providerMode,
      providerId: providerId,
      selectedProviderOptionId: selectedProviderOptionId,
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
      openAiPromptCacheKeyStrategy:
          openAiPromptCacheKeyStrategy ??
          llmConfig.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention:
          openAiPromptCacheRetention ?? llmConfig.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled:
          anthropicPromptCachingEnabled ??
          llmConfig.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl:
          anthropicPromptCacheTtl ?? llmConfig.anthropicPromptCacheTtl,
      onDeviceModels: llmConfig.onDeviceModels,
      selectedOnDeviceModelId: selectedOnDeviceModelId,
      onDeviceMaxContextWindow: onDeviceMaxContextWindow,
      onDeviceMaxTokens: onDeviceMaxTokens,
      onDeviceTopK: onDeviceTopK,
      onDeviceTopP: onDeviceTopP,
      onDeviceTemperature: onDeviceTemperature,
      onDeviceAccelerator: onDeviceAccelerator,
      onDeviceThinkingEnabled: onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled: onDeviceLiteModeEnabled,
      contextBudgetPreset: contextBudgetPreset ?? llmConfig.contextBudgetPreset,
      contextBudgetReservedOutputTokens: hasExplicitContextBudgetPayload
          ? contextBudgetReservedOutputTokens
          : llmConfig.contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens: hasExplicitContextBudgetPayload
          ? contextBudgetSafetyMarginTokens
          : llmConfig.contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent: hasExplicitContextBudgetPayload
          ? contextBudgetEffectiveInputPercent
          : llmConfig.contextBudgetEffectiveInputPercent,
    );
    return llmConfig;
  }

  @override
  Future<LlmConfigSnapshot> saveCustomLlmProvider({
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
  }) async {
    saveCallCount += 1;
    final savedOptionId = selectedProviderOptionId == 'custom'
        ? 'saved-custom'
        : selectedProviderOptionId;
    final savedOption = LlmProviderOption(
      id: savedOptionId,
      providerId: 'custom',
      title: providerName,
      subtitle: providerNotes,
      defaultBaseUrl: baseUrl,
      defaultModel: model,
      protocol: protocol,
      apiKey: apiKey,
      isCustom: true,
    );
    llmConfig = LlmConfigSnapshot(
      localeTag: llmConfig.localeTag,
      enabled:
          baseUrl.isNotEmpty &&
          (apiKey.isNotEmpty ||
              _llmEndpointAllowsBlankApiKeyForTest(
                protocol: protocol,
                baseUrl: baseUrl,
              )),
      streamingEnabled: streamingEnabled ?? llmConfig.streamingEnabled,
      providerMode: 'cloud',
      providerId: 'custom',
      selectedProviderOptionId: savedOptionId,
      protocol: protocol,
      providerOptions: <LlmProviderOption>[
        for (final option in llmConfig.providerOptions)
          if (option.id != savedOptionId) option,
        savedOption,
      ],
      providerName: providerName,
      providerNotes: providerNotes,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
      helperText: llmConfig.helperText,
      openAiPromptCacheKeyStrategy:
          openAiPromptCacheKeyStrategy ??
          llmConfig.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention:
          openAiPromptCacheRetention ?? llmConfig.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled:
          anthropicPromptCachingEnabled ??
          llmConfig.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl:
          anthropicPromptCacheTtl ?? llmConfig.anthropicPromptCacheTtl,
      onDeviceModels: llmConfig.onDeviceModels,
      selectedOnDeviceModelId: llmConfig.selectedOnDeviceModelId,
      onDeviceMaxContextWindow: llmConfig.onDeviceMaxContextWindow,
      onDeviceMaxTokens: llmConfig.onDeviceMaxTokens,
      onDeviceTopK: llmConfig.onDeviceTopK,
      onDeviceTopP: llmConfig.onDeviceTopP,
      onDeviceTemperature: llmConfig.onDeviceTemperature,
      onDeviceAccelerator: llmConfig.onDeviceAccelerator,
      onDeviceThinkingEnabled: llmConfig.onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled: llmConfig.onDeviceLiteModeEnabled,
      contextBudgetPreset: llmConfig.contextBudgetPreset,
      contextBudgetReservedOutputTokens:
          llmConfig.contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens:
          llmConfig.contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent:
          llmConfig.contextBudgetEffectiveInputPercent,
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
  }) async {
    validationCallCount += 1;
    return validationResult;
  }

  @override
  Future<LlmConfigSnapshot> downloadOnDeviceLlmModel(String modelId) async {
    llmConfig = llmConfig.copyWith(
      onDeviceModels: llmConfig.onDeviceModels
          .map(
            (option) => option.id == modelId
                ? LlmOnDeviceModelOption(
                    id: option.id,
                    title: option.title,
                    subtitle: option.subtitle,
                    sizeLabel: option.sizeLabel,
                    fileSizeBytes: option.fileSizeBytes,
                    installState: 'ready',
                    downloadedBytes: option.fileSizeBytes,
                    downloadBytesPerSecond: 0,
                    sha256Verified: true,
                    isSelected: option.isSelected,
                    lastError: null,
                  )
                : option,
          )
          .toList(growable: false),
    );
    return llmConfig;
  }

  @override
  Future<LlmConfigSnapshot> cancelOnDeviceLlmModelDownload(
    String modelId,
  ) async => llmConfig;

  @override
  Future<LlmConfigSnapshot> deleteOnDeviceLlmModel(String modelId) async {
    llmConfig = llmConfig.copyWith(
      onDeviceModels: llmConfig.onDeviceModels
          .map(
            (option) => option.id == modelId
                ? LlmOnDeviceModelOption(
                    id: option.id,
                    title: option.title,
                    subtitle: option.subtitle,
                    sizeLabel: option.sizeLabel,
                    fileSizeBytes: option.fileSizeBytes,
                    installState: 'not_downloaded',
                    downloadedBytes: 0,
                    downloadBytesPerSecond: 0,
                    sha256Verified: false,
                    isSelected: option.isSelected,
                    lastError: null,
                  )
                : option,
          )
          .toList(growable: false),
    );
    return llmConfig;
  }

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

  @override
  Future<SafetySettingsSnapshot> loadSafetySettings() async => safetySettings;

  @override
  Future<bool> authorizeExternalAccessLocation(String locationId) async {
    authorizationRequests.add(locationId);
    return authorizationResponses[locationId] ?? true;
  }

  @override
  Future<SafetySettingsSnapshot> saveSafetySettings(
    SafetySettingsSnapshot snapshot,
  ) async {
    safetySaveCallCount += 1;
    safetySettings = snapshot;
    return safetySettings;
  }
}

bool _llmEndpointAllowsBlankApiKeyForTest({
  required String protocol,
  required String baseUrl,
}) {
  final normalizedProtocol = protocol.trim().toLowerCase();
  if (normalizedProtocol != 'openai' &&
      normalizedProtocol != 'openai_responses') {
    return false;
  }
  final host = (Uri.tryParse(baseUrl.trim())?.host ?? '').trim().toLowerCase();
  if (host.isEmpty) {
    return false;
  }
  if (host == 'localhost' ||
      host == 'localhost.localdomain' ||
      host == '0.0.0.0' ||
      host == '::1' ||
      host == '10.0.2.2' ||
      host == 'host.docker.internal' ||
      host.endsWith('.local')) {
    return true;
  }
  if (host.startsWith('127.') ||
      host.startsWith('10.') ||
      host.startsWith('192.168.')) {
    return true;
  }
  if (!host.startsWith('172.')) {
    return false;
  }
  final parts = host.split('.');
  if (parts.length < 2) {
    return false;
  }
  final secondOctet = int.tryParse(parts[1]);
  return secondOctet != null && secondOctet >= 16 && secondOctet <= 31;
}

class _FakeDebugBridge extends OpenCraySeedBridge {
  _FakeDebugBridge({
    required this.shellSnapshot,
    required this.runtimeSnapshot,
    required Map<String, OpenCrayChatRunSnapshot> runSnapshots,
    required this.memorySnapshot,
    required this.linksSnapshot,
    required this.memorySearchSnapshot,
    required this.memorySliceSnapshot,
    required this.soulSnapshot,
    List<List<OpenCraySettingsImageAsset>> pickedSettingsImageAssetBatches =
        const <List<OpenCraySettingsImageAsset>>[],
  }) : _runSnapshots = runSnapshots,
       _pickedSettingsImageAssetBatches = pickedSettingsImageAssetBatches
           .map(
             (batch) =>
                 List<OpenCraySettingsImageAsset>.from(batch, growable: false),
           )
           .toList(growable: true),
       _currentRuntimeSnapshot = runtimeSnapshot,
       _currentMemorySnapshot = memorySnapshot,
       _currentLinksSnapshot = linksSnapshot,
       _currentMemorySearchSnapshot = memorySearchSnapshot,
       _currentMemorySliceSnapshot = memorySliceSnapshot,
       _seedSoulSnapshot = soulSnapshot,
       _currentSoulSnapshot = soulSnapshot,
       super(initialSnapshot: shellSnapshot);

  final OpenCrayShellSnapshot shellSnapshot;
  final OpenCrayChatRuntimeSnapshot runtimeSnapshot;
  final Map<String, OpenCrayChatRunSnapshot> _runSnapshots;
  final OpenCrayMemoryDebugSnapshot memorySnapshot;
  final OpenCrayMemoryDebugLinksSnapshot linksSnapshot;
  final OpenCrayMemoryDebugSearchSnapshot memorySearchSnapshot;
  final OpenCrayMemoryDebugSliceSnapshot memorySliceSnapshot;
  final OpenCraySoulDebugSnapshot soulSnapshot;
  final List<List<OpenCraySettingsImageAsset>> _pickedSettingsImageAssetBatches;
  final OpenCrayChatRuntimeSnapshot _currentRuntimeSnapshot;
  OpenCrayMemoryDebugSnapshot _currentMemorySnapshot;
  OpenCrayMemoryDebugLinksSnapshot _currentLinksSnapshot;
  final OpenCrayMemoryDebugSearchSnapshot _currentMemorySearchSnapshot;
  final OpenCrayMemoryDebugSliceSnapshot _currentMemorySliceSnapshot;
  final OpenCraySoulDebugSnapshot _seedSoulSnapshot;
  OpenCraySoulDebugSnapshot _currentSoulSnapshot;
  int pickSettingsImageAssetsCallCount = 0;
  String? lastMemorySearchQuery;
  String? lastMemorySlicePath;
  int? lastMemorySliceFromLine;
  int? lastMemorySliceLines;
  String? lastMemoryActionRecordId;
  String? lastMemoryActionId;
  final List<OpenCrayTab> persistedShellTabs = <OpenCrayTab>[];
  final List<String?> persistedSettingsRouteIds = <String?>[];

  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() async => shellSnapshot;

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) async {
    persistedShellTabs.add(
      OpenCrayTab.values.firstWhere(
        (tab) =>
            selectedTab == tab.routeSegment || selectedTab == tab.routeName,
        orElse: () => OpenCrayTab.chat,
      ),
    );
    persistedSettingsRouteIds.add(settingsSubpage);
  }

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      _currentRuntimeSnapshot;

  @override
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async =>
      _runSnapshots[runId];

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      _currentMemorySnapshot;

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async => _currentLinksSnapshot;

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      _currentSoulSnapshot;

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async {
    pickSettingsImageAssetsCallCount += 1;
    if (_pickedSettingsImageAssetBatches.isEmpty) {
      return const <OpenCraySettingsImageAsset>[];
    }
    return _pickedSettingsImageAssetBatches.removeAt(0);
  }

  @override
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async {
    lastMemorySearchQuery = query;
    return OpenCrayMemoryDebugSearchSnapshot(
      sessionId: _currentMemorySearchSnapshot.sessionId,
      workspaceId: _currentMemorySearchSnapshot.workspaceId,
      observedAtEpochMs: _currentMemorySearchSnapshot.observedAtEpochMs,
      query: query,
      queryTerms: _currentMemorySearchSnapshot.queryTerms,
      corpusFileCount: _currentMemorySearchSnapshot.corpusFileCount,
      results: _currentMemorySearchSnapshot.results,
    );
  }

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async {
    lastMemorySlicePath = path;
    lastMemorySliceFromLine = fromLine;
    lastMemorySliceLines = lines;
    return _currentMemorySliceSnapshot;
  }

  @override
  Future<void> applyMemoryDebugAction({
    required String recordId,
    required String actionId,
  }) async {
    lastMemoryActionRecordId = recordId;
    lastMemoryActionId = actionId;
    final records = _currentMemorySnapshot.records
        .map(
          (record) => record.id == recordId
              ? _applyMemoryRecordAction(record: record, actionId: actionId)
              : record,
        )
        .toList(growable: false);
    _currentMemorySnapshot = OpenCrayMemoryDebugSnapshot(
      sessionId: _currentMemorySnapshot.sessionId,
      workspaceId: _currentMemorySnapshot.workspaceId,
      observedAtEpochMs: _currentMemorySnapshot.observedAtEpochMs + 1,
      records: records,
    );
    final occurredAtEpochMs = _currentLinksSnapshot.observedAtEpochMs + 1;
    final run = OpenCrayDebugRunLinkSnapshot(
      sessionId: _currentMemorySnapshot.sessionId,
      runId: 'run-memory-debug-$actionId',
      taskId: 'task-memory-debug-$actionId',
      acceptedAtEpochMs: occurredAtEpochMs,
      updatedAtEpochMs: occurredAtEpochMs,
      executionStatus: 'success',
      lifecycleState: 'completed',
    );
    final existingEntry = _currentLinksSnapshot.records
        .where((entry) => entry.recordId == recordId)
        .cast<OpenCrayMemoryDebugLinksEntrySnapshot?>()
        .firstWhere((entry) => entry != null, orElse: () => null);
    final nextEntry = OpenCrayMemoryDebugLinksEntrySnapshot(
      recordId: recordId,
      sourceSessionId:
          existingEntry?.sourceSessionId ?? _currentMemorySnapshot.sessionId,
      sourceTaskId: existingEntry?.sourceTaskId ?? '',
      sourceRun: existingEntry?.sourceRun,
      promptRecalls:
          existingEntry?.promptRecalls ??
          const <OpenCrayMemoryPromptRecallLinkSnapshot>[],
      toolRetrievals:
          existingEntry?.toolRetrievals ??
          const <OpenCrayMemoryToolRetrievalLinkSnapshot>[],
      maintenanceActions: <OpenCrayMemoryMaintenanceActionLinkSnapshot>[
        ...?existingEntry?.maintenanceActions,
        OpenCrayMemoryMaintenanceActionLinkSnapshot(
          action: actionId == 'suppress' ? 'suppressed' : 'reaffirmed',
          occurredAtEpochMs: occurredAtEpochMs,
          run: run,
        ),
      ],
    );
    final nextLinkEntries = <OpenCrayMemoryDebugLinksEntrySnapshot>[
      for (final entry in _currentLinksSnapshot.records)
        if (entry.recordId == recordId) nextEntry else entry,
      if (existingEntry == null) nextEntry,
    ];
    _currentLinksSnapshot = OpenCrayMemoryDebugLinksSnapshot(
      sessionId: _currentLinksSnapshot.sessionId,
      workspaceId: _currentLinksSnapshot.workspaceId,
      observedAtEpochMs: occurredAtEpochMs,
      records: nextLinkEntries,
    );
    final seedFieldSources = _seedSoulSnapshot.fieldSources;
    final seedOverlayRecords = _seedSoulSnapshot.overlayRecords;
    final nextFieldSources = actionId == 'suppress'
        ? _currentSoulSnapshot.fieldSources
              .where((source) => source.recordId != recordId)
              .toList(growable: false)
        : <OpenCraySoulFieldSourceSnapshot>[
            ..._currentSoulSnapshot.fieldSources,
            ...seedFieldSources.where(
              (source) =>
                  source.recordId == recordId &&
                  !_currentSoulSnapshot.fieldSources.contains(source),
            ),
          ];
    final nextOverlayRecords = actionId == 'suppress'
        ? _currentSoulSnapshot.overlayRecords
              .where((record) => record.id != recordId)
              .toList(growable: false)
        : <OpenCrayMemoryDebugRecordSnapshot>[
            ..._currentSoulSnapshot.overlayRecords,
            ...seedOverlayRecords.where(
              (overlay) =>
                  overlay.id == recordId &&
                  !_currentSoulSnapshot.overlayRecords.contains(overlay),
            ),
          ];
    _currentSoulSnapshot = OpenCraySoulDebugSnapshot(
      sessionId: _currentSoulSnapshot.sessionId,
      workspaceId: _currentSoulSnapshot.workspaceId,
      observedAtEpochMs: _currentSoulSnapshot.observedAtEpochMs + 1,
      storedSoul: _currentSoulSnapshot.storedSoul,
      baseSoul: _currentSoulSnapshot.baseSoul,
      effectiveSoul: _currentSoulSnapshot.effectiveSoul,
      overlayRecords: nextOverlayRecords,
      fieldSources: nextFieldSources,
      interactionPreferenceDebug:
          _currentSoulSnapshot.interactionPreferenceDebug,
      relationshipStateDebug: _currentSoulSnapshot.relationshipStateDebug,
    );
  }

  OpenCrayMemoryDebugRecordSnapshot _applyMemoryRecordAction({
    required OpenCrayMemoryDebugRecordSnapshot record,
    required String actionId,
  }) {
    const nextEpochMs = 6001;
    switch (actionId) {
      case 'suppress':
        return OpenCrayMemoryDebugRecordSnapshot(
          id: record.id,
          content: record.content,
          kind: record.kind,
          scope: record.scope,
          status: 'resolved',
          source: record.source,
          sourceSessionId: record.sourceSessionId,
          sourceTaskId: record.sourceTaskId,
          workspaceId: record.workspaceId,
          preferenceKey: record.preferenceKey,
          preferenceValue: record.preferenceValue,
          preferenceTemporality: record.preferenceTemporality,
          createdAtEpochMs: record.createdAtEpochMs,
          updatedAtEpochMs: nextEpochMs,
          lastConfirmedAtEpochMs: record.lastConfirmedAtEpochMs,
          resolvedAtEpochMs: nextEpochMs,
          ttlMs: record.ttlMs,
          isExpired: record.isExpired,
          recordVersion: record.recordVersion + 1,
          resolutionReason: 'operator_suppressed',
          supersededBy: record.supersededBy,
          tags: record.tags,
          extensions: record.extensions,
        );
      case 'reaffirm':
        return OpenCrayMemoryDebugRecordSnapshot(
          id: record.id,
          content: record.content,
          kind: record.kind,
          scope: record.scope,
          status: record.kind == 'task_commitment' ? 'open' : 'active',
          source: record.source,
          sourceSessionId: record.sourceSessionId,
          sourceTaskId: record.sourceTaskId,
          workspaceId: record.workspaceId,
          preferenceKey: record.preferenceKey,
          preferenceValue: record.preferenceValue,
          preferenceTemporality: record.preferenceTemporality,
          createdAtEpochMs: record.createdAtEpochMs,
          updatedAtEpochMs: nextEpochMs,
          lastConfirmedAtEpochMs: nextEpochMs,
          resolvedAtEpochMs: null,
          ttlMs: record.ttlMs,
          isExpired: record.isExpired,
          recordVersion: record.recordVersion + 1,
          resolutionReason: '',
          supersededBy: '',
          tags: record.tags,
          extensions: record.extensions,
        );
      default:
        throw StateError('Unsupported memory debug action $actionId');
    }
  }
}
