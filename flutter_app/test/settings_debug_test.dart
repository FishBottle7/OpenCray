import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/app/opencray_tabs.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_debug_snapshot.dart';
import 'package:opencray/core/models/opencray_shell_snapshot.dart';
import 'package:opencray/features/settings/settings.dart';

import 'settings_feature_test_support.dart';

void main() {
  testWidgets('about version page opens debug tools and renders context trace details', (
    tester,
  ) async {
    final facade = buildDebugSettingsFacade();
    final debugBridge = buildDebugBridge();

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
      final facade = buildDebugSettingsFacade();
      final debugBridge = buildDebugBridge();

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
      final facade = buildDebugSettingsFacade();
      final debugBridge = buildDebugBridge();

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
      final facade = buildDebugSettingsFacade();
      final debugBridge = buildDebugBridge();

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
    final facade = buildDebugSettingsFacade();
    final debugBridge = buildDebugBridge();

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
    final facade = buildDebugSettingsFacade();
    final debugBridge = buildDebugBridge();

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
      final facade = buildDebugSettingsFacade();
      final debugBridge = FakeDebugBridge(
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
        linksSnapshot: buildFallbackFieldSourceLinksSnapshot(),
        memorySearchSnapshot: const OpenCrayMemoryDebugSearchSnapshot(
          sessionId: 'session-1',
          observedAtEpochMs: 5000,
        ),
        memorySliceSnapshot: const OpenCrayMemoryDebugSliceSnapshot(
          sessionId: 'session-1',
          observedAtEpochMs: 5000,
        ),
        soulSnapshot: buildFallbackFieldSourceSoulSnapshot(),
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
}

FakeSettingsFacade buildDebugSettingsFacade({
  PersonalizationConfigSnapshot? personalizationConfig,
}) {
  return FakeSettingsFacade(
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
      onDeviceModels: defaultOnDeviceLlmModels,
    ),
    validationResult: const LlmValidationResult(
      isSuccess: true,
      message: 'Validated.',
    ),
    personalizationConfig: personalizationConfig,
  );
}

OpenCraySoulDebugSnapshot buildFallbackFieldSourceSoulSnapshot() =>
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

OpenCrayMemoryDebugLinksSnapshot buildFallbackFieldSourceLinksSnapshot() =>
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
