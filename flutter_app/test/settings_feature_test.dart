import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_debug_snapshot.dart';
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
          selectedProviderOptionId: 'custom',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'custom',
              providerId: 'custom',
              title: 'Custom provider',
              subtitle: 'Any OpenAI-compatible or Anthropic endpoint.',
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
            subtitle: 'Any OpenAI-compatible or Anthropic endpoint.',
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
              subtitle: 'Any OpenAI-compatible or Anthropic endpoint.',
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

  testWidgets(
    'about version page opens debug tools and renders context trace details',
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

      expect(find.text('Debug tools'), findsOneWidget);
      expect(find.text('Open Debug Tools'), findsOneWidget);

      await tester.tap(find.text('Open Debug Tools'));
      await tester.pumpAndSettle();

      expect(find.text('Context & Memory Trace'), findsOneWidget);
      expect(find.text('Memory Inspector'), findsOneWidget);
      expect(find.text('Soul Inspector'), findsOneWidget);

      await tester.tap(find.text('Context & Memory Trace'));
      await tester.pumpAndSettle();

      expect(find.text('Run overview'), findsOneWidget);
      expect(find.text('Memory writes'), findsOneWidget);
      expect(find.text('Memory recall'), findsOneWidget);
      expect(find.text('Soul resolution'), findsOneWidget);
      expect(find.text('Raw trace'), findsOneWidget);
      expect(find.text('run-memory'), findsWidgets);
      expect(find.text('Matched: 2'), findsOneWidget);
      expect(find.text('Injected: 1'), findsOneWidget);
      expect(find.text('Omitted: 1'), findsOneWidget);
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
          'Bridge note: Run-level soul attribution is not exposed yet.',
          findRichText: true,
        ),
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

      expect(find.text('Store summary'), findsOneWidget);
      expect(find.text('Filter'), findsOneWidget);
      expect(find.text('Memory records'), findsOneWidget);
      expect(find.text('Selected record'), findsOneWidget);
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
    },
  );

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

    expect(find.text('Stored soul'), findsOneWidget);
    expect(find.text('Base soul'), findsOneWidget);
    expect(find.text('Memory overlays'), findsOneWidget);
    expect(find.text('Effective soul'), findsOneWidget);
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
        of: fieldSourcesCard,
        matching: find.textContaining(
          'display name ->: user memory: Xiao Bai',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
  });

  testWidgets(
    'network search page saves slot edits, provider changes, and add slot',
    (tester) async {
      final facade = _buildSettingsFacade();

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

      expect(find.text('Network & Search'), findsOneWidget);

      await tester.enterText(find.byType(TextField).at(0), 'Primary Exa');
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pump(const Duration(milliseconds: 800));

      expect(facade.networkSearchConfig.slots.first.label, 'Primary Exa');

      await tester.tap(find.text('BRAVE').first);
      await tester.pump(const Duration(milliseconds: 500));

      expect(facade.networkSearchConfig.slots.first.providerId, 'brave');

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

      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();

      expect(facade.safetySettings.readOnlyOutsideWorkspace, isFalse);

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
    ),
    validationResult: const LlmValidationResult(
      isSuccess: true,
      message: 'Validated.',
    ),
    personalizationConfig: personalizationConfig,
  );
}

_FakeDebugBridge _buildDebugBridge() {
  return _FakeDebugBridge(
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
          isTerminal: true,
          executionStatus: 'success',
          taskState: 'completed',
          responseFormat: 'json_final',
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

class _FakeSettingsFacade implements SettingsFacade {
  _FakeSettingsFacade({
    required this.llmConfig,
    required this.validationResult,
    PersonalizationConfigSnapshot? personalizationConfig,
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
            apiKey: 'sk_live_demo',
            enabled: true,
          ),
          NetworkSearchSlotSnapshot(
            id: 'slot-2',
            providerId: 'tavily',
            label: 'Tavily Backup',
            apiKey: 'tvly-demo',
            enabled: true,
          ),
        ],
      );
  LlmConfigSnapshot llmConfig;
  final LlmValidationResult validationResult;
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
  final Map<String, bool> authorizationResponses = <String, bool>{};
  final List<String> authorizationRequests = <String>[];
  int saveCallCount = 0;
  int safetySaveCallCount = 0;

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
  Future<LlmConfigSnapshot> loadLlmConfig() async => llmConfig;

  @override
  Future<LlmConfigSnapshot> saveLlmConfig({
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
  }) async {
    saveCallCount += 1;
    llmConfig = LlmConfigSnapshot(
      localeTag: llmConfig.localeTag,
      enabled: enabled,
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
    );
    return llmConfig;
  }

  @override
  Future<LlmConfigSnapshot> saveCustomLlmProvider({
    required String selectedProviderOptionId,
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
      enabled: baseUrl.isNotEmpty && apiKey.isNotEmpty,
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

class _FakeDebugBridge extends OpenCraySeedBridge {
  _FakeDebugBridge({
    required this.runtimeSnapshot,
    required Map<String, OpenCrayChatRunSnapshot> runSnapshots,
    required this.memorySnapshot,
    required this.soulSnapshot,
  }) : _runSnapshots = runSnapshots;

  final OpenCrayChatRuntimeSnapshot runtimeSnapshot;
  final Map<String, OpenCrayChatRunSnapshot> _runSnapshots;
  final OpenCrayMemoryDebugSnapshot memorySnapshot;
  final OpenCraySoulDebugSnapshot soulSnapshot;

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      runtimeSnapshot;

  @override
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async =>
      _runSnapshots[runId];

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      memorySnapshot;

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      soulSnapshot;
}
