import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_sandbox_settings.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

void main() {
  testWidgets(
    'cloud mode auto refreshes sandbox session info after cloud runtime activity',
    (tester) async {
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-1',
              taskId: 'task-cloud-1',
              emittedAtEpochMs: 4200,
              toolName: 'python_exec',
              resultMetadata: const <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'sandbox',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(bridge.refreshSandboxSessionInfoCallCount, 0);

      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);
    },
  );

  testWidgets('local mode does not auto refresh sandbox session info', (
    tester,
  ) async {
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
      runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-cloud-1',
            taskId: 'task-cloud-1',
            emittedAtEpochMs: 4200,
            toolName: 'python_exec',
            resultMetadata: const <String, String>{'sandboxProvider': 'e2b'},
          ),
        ],
      ),
      sandboxSettings: const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'e2b',
        defaultBackend: 'local',
        sessionMode: 'ephemeral',
        autoResume: false,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: 'e2b_demo',
        apiKeyConfigured: true,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
            bridge: bridge,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.pump(chatSandboxSessionAutoRefreshDebounce * 2);
    await tester.pump();

    expect(bridge.refreshSandboxSessionInfoCallCount, 0);
  });

  testWidgets(
    'cloud mode auto refreshes sandbox session info from lifecycle metadata',
    (tester) async {
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-session-info-1',
              taskId: 'task-session-info-1',
              emittedAtEpochMs: 4200,
              toolName: 'sandbox_session_info',
              resultMetadata: const <String, String>{
                'sandboxProvider': 'e2b',
                'sandboxSessionPresent': 'true',
                'sandboxSessionSource': 'active_memory',
                'sandboxSessionLifecycleStatus': 'active',
                'sandboxSessionAutoRefreshAfterMs': '1200',
              },
            ),
          ],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'sandbox',
          sessionMode: 'sticky',
          autoResume: true,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(bridge.refreshSandboxSessionInfoCallCount, 0);

      await tester.pump(const Duration(milliseconds: 1200));
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);
    },
  );

  testWidgets(
    'local mode does not auto refresh sandbox session info from lifecycle metadata',
    (tester) async {
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-session-info-local',
              taskId: 'task-session-info-local',
              emittedAtEpochMs: 4200,
              toolName: 'sandbox_session_info',
              resultMetadata: const <String, String>{
                'sandboxProvider': 'e2b',
                'sandboxSessionPresent': 'true',
                'sandboxSessionSource': 'active_memory',
                'sandboxSessionLifecycleStatus': 'active',
                'sandboxSessionAutoRefreshAfterMs': '1200',
              },
            ),
          ],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'local',
          sessionMode: 'sticky',
          autoResume: true,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.pump(const Duration(milliseconds: 2400));
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 0);
    },
  );

  testWidgets(
    'switching from local to cloud triggers sandbox session auto refresh',
    (tester) async {
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-1',
              taskId: 'task-cloud-1',
              emittedAtEpochMs: 4200,
              toolName: 'python_exec',
              resultMetadata: const <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'local',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(bridge.refreshSandboxSessionInfoCallCount, 0);

      await tester.tap(
        find.byKey(const ValueKey<String>('chat-runtime-environment-selector')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Run in cloud'));
      await tester.pumpAndSettle();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);
    },
  );

  testWidgets(
    'switching to cloud skips auto refresh when newer sandbox session info already exists',
    (tester) async {
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-1',
              taskId: 'task-cloud-1',
              emittedAtEpochMs: 4300,
              toolName: 'sandbox_session_info',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-1',
              taskId: 'task-cloud-1',
              emittedAtEpochMs: 4200,
              toolName: 'python_exec',
              resultMetadata: const <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'local',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(bridge.refreshSandboxSessionInfoCallCount, 0);

      await tester.tap(
        find.byKey(const ValueKey<String>('chat-runtime-environment-selector')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Run in cloud'));
      await tester.pumpAndSettle();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce * 2);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 0);
    },
  );

  testWidgets(
    'sandbox session auto refresh does not loop on the same anchor after failure',
    (tester) async {
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final runtimeSnapshot = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-cloud-1',
            taskId: 'task-cloud-1',
            emittedAtEpochMs: 4200,
            toolName: 'python_exec',
            resultMetadata: const <String, String>{'sandboxProvider': 'e2b'},
          ),
        ],
      );
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
        runtimeSnapshot: runtimeSnapshot,
        runtimeSnapshotStream: runtimeSnapshots.stream,
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'sandbox',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );
      bridge.refreshSandboxSessionInfoError = StateError('refresh failed');

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);

      runtimeSnapshots.add(runtimeSnapshot);
      await tester.pumpAndSettle();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce * 2);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);
    },
  );

  testWidgets(
    'sandbox session auto refresh drains queued anchors after an in-flight refresh',
    (tester) async {
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-1',
              taskId: 'task-cloud-1',
              emittedAtEpochMs: 4200,
              toolName: 'python_exec',
              resultMetadata: <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'sandbox',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );
      final firstRefreshCompleter = Completer<void>();
      final secondRefreshCompleter = Completer<void>();
      final thirdRefreshCompleter = Completer<void>();
      bridge.refreshSandboxSessionInfoCompleter = firstRefreshCompleter;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-2',
              taskId: 'task-cloud-2',
              emittedAtEpochMs: 4300,
              toolName: 'python_exec',
              resultMetadata: <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();
      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-3',
              taskId: 'task-cloud-3',
              emittedAtEpochMs: 4400,
              toolName: 'python_exec',
              resultMetadata: <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      bridge.refreshSandboxSessionInfoCompleter = secondRefreshCompleter;
      firstRefreshCompleter.complete();
      await tester.pump();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 2);

      bridge.refreshSandboxSessionInfoCompleter = thirdRefreshCompleter;
      secondRefreshCompleter.complete();
      await tester.pump();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 3);

      thirdRefreshCompleter.complete();
      await tester.pumpAndSettle();
    },
  );
}
