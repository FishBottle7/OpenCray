import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/app/opencray_tabs.dart';
import 'package:opencray/features/settings/settings.dart';

import 'settings_feature_test_support.dart';

void main() {
  test('event alert route uses canonical id and accepts the legacy id', () {
    expect(SettingsPage.eventAlerts.routeId, 'event_alerts');
    expect(settingsPageFromRouteId('event_alerts'), SettingsPage.eventAlerts);
    expect(
      settingsPageFromRouteId('notification_channels'),
      SettingsPage.eventAlerts,
    );
  });

  testWidgets('home settings hides Agent entry from the overview list', (
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

  testWidgets('privacy route maps to the privacy detail page', (tester) async {
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
            page: SettingsPage.privacyTelemetry,
            title: 'Privacy & Telemetry',
          ),
        ],
      ),
    );
    final debugBridge = buildDebugBridge();

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
}
