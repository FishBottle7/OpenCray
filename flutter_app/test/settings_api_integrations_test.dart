import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/design/opencray_controls.dart';
import 'package:opencray/features/settings/settings.dart';

import 'settings_feature_test_support.dart';

void main() {
  testWidgets(
    'network search page saves slot edits, openai search base url and model, and add slot',
    (tester) async {
      final facade = buildSettingsFacade();

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

      await tester.tap(find.byType(OpenCraySwitch).first);
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
      final facade = buildSettingsFacade();

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

  testWidgets('standalone E2B page saves backend routing changes', (
    tester,
  ) async {
    final facade = buildSettingsFacade();

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
    final facade = buildSettingsFacade();

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
}
