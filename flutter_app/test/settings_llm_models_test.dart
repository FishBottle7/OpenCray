import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/features/settings/settings.dart';

import 'settings_feature_test_support.dart';

void main() {
  testWidgets('standalone llm page auto-saves when a field loses focus', (
    tester,
  ) async {
    final facade = FakeSettingsFacade(
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
      final facade = buildSettingsFacade();
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
        onDeviceModels: defaultOnDeviceLlmModels,
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
      final facade = buildSettingsFacade();
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
      final facade = buildSettingsFacade();
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
      final facade = FakeSettingsFacade(
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
      final facade = FakeSettingsFacade(
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
      final facade = FakeSettingsFacade(
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
      final facade = FakeSettingsFacade(
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
    final facade = FakeSettingsFacade(
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
    final facade = FakeSettingsFacade(
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
    final facade = FakeSettingsFacade(
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
    final facade = FakeSettingsFacade(
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
    final facade = FakeSettingsFacade(
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
      final facade = buildSettingsFacade();

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
    final facade = FakeSettingsFacade(
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
        onDeviceModels: defaultOnDeviceLlmModels,
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

  testWidgets('custom provider save action adds a reusable provider option', (
    tester,
  ) async {
    final facade = FakeSettingsFacade(
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
      final facade = FakeSettingsFacade(
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
      final facade = FakeSettingsFacade(
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
      final facade = FakeSettingsFacade(
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
    final facade = FakeSettingsFacade(
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

  testWidgets(
    'llm settings raw context window override saves as dev when it diverges from presets',
    (tester) async {
      final facade = FakeSettingsFacade(
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
          baseUrl: 'https://proxy.example/v1',
          apiKey: 'secret',
          model: 'gpt-4.1',
          reasoningEffort: 'medium',
          systemPrompt: '',
          helperText: 'Helper text',
          resolvedContextWindowTokens: 128000,
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

      final rawActionFinder = find.byKey(
        const ValueKey<String>('settings-llm-context-budget-raw-action'),
      );
      await tester.ensureVisible(rawActionFinder);
      await tester.tap(rawActionFinder);
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField).last, '350000');
      await tester.tap(find.text('Apply'));
      await tester.pumpAndSettle();

      expect(facade.llmConfig.manualContextWindowTokens, 350000);
      expect(facade.llmConfig.resolvedContextWindowTokens, 350000);
      expect(
        find.descendant(
          of: find.byKey(
            const ValueKey<String>('settings-llm-context-budget-preset'),
          ),
          matching: find.text('Dev'),
        ),
        findsOneWidget,
      );
      expect(find.text('Manual override: 350K'), findsOneWidget);
    },
  );
}
