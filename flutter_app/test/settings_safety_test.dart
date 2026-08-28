import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/design/opencray_controls.dart';
import 'package:opencray/features/settings/settings.dart';

import 'settings_feature_test_support.dart';

void main() {
  testWidgets('safety page saves mode and file delete policy changes', (
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
    final facade = buildSettingsFacade();

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
      final facade = buildSettingsFacade();
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

      await tester.tap(find.byType(OpenCraySwitch).at(3));
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
      final facade = buildSettingsFacade();

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

      await tester.ensureVisible(find.text('OPEN'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('OPEN'));
      await tester.pumpAndSettle();

      expect(
        facade.safetySettings.workspaceAccessProfile,
        WorkspaceAccessProfile.open,
      );

      await tester.ensureVisible(find.text('Memory tools'));
      await tester.pumpAndSettle();
      await tester.tapAt(
        Offset(760, tester.getCenter(find.text('Memory tools')).dy),
      );
      await tester.pumpAndSettle();

      expect(facade.safetySettings.memoryToolsEnabled, isFalse);

      await tester.ensureVisible(find.text('Read-only outside workspace'));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(OpenCraySwitch).at(1));
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

      await tester.ensureVisible(find.text('Default mode').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Default mode').last);
      await tester.pumpAndSettle();

      expect(find.text('Child agent context'), findsOneWidget);

      await tester.ensureVisible(find.text('Mode'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Mode'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Delegated').last);
      await tester.pumpAndSettle();

      expect(
        facade.safetySettings.subAgentContextDefaultMode,
        SubAgentContextMode.delegated,
      );

      await tester.ensureVisible(find.text('Reviewer'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Reviewer'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Minimal').last);
      await tester.pumpAndSettle();

      expect(
        facade.safetySettings.subAgentContextModeForProfile('reviewer'),
        SubAgentContextMode.minimal,
      );

      await tester.ensureVisible(find.text('Workspace Access'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Workspace Access'));
      await tester.pumpAndSettle();

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
