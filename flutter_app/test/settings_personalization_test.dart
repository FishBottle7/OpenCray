import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/models/opencray_agent_snapshot.dart';
import 'package:opencray/core/models/opencray_image_reference.dart';
import 'package:opencray/features/settings/settings.dart';

import 'settings_feature_test_support.dart';

void main() {
  testWidgets('agents page loads host-backed agents and persists creation', (
    tester,
  ) async {
    final facade = buildSettingsFacade();
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
    final facade = buildSettingsFacade();
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
      final facade = buildSettingsFacade();
      final debugBridge = buildDebugBridge(
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
}
