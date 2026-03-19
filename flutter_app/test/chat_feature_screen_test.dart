import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_debug_snapshot.dart';
import 'package:opencray/core/models/opencray_file_image_preview.dart';
import 'package:opencray/core/models/opencray_file_text_preview.dart';
import 'package:opencray/core/models/opencray_file_voice_playback_source.dart';
import 'package:opencray/features/chat/chat_feature.dart';
import 'package:opencray/features/chat/chat_voice_playback.dart';

void main() {
  test(
    'resolveChatRuntimeSnapshot prefers the settled snapshot when versions tie',
    () {
      final embedded = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[],
        events: const <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'assistant',
            runId: 'run-1',
            taskId: 'task-1',
            emittedAtEpochMs: 3000,
          ),
        ],
      );
      final streamed = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-1',
            taskId: 'task-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: false,
          ),
        ],
        events: const <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'assistant',
            runId: 'run-1',
            taskId: 'task-1',
            emittedAtEpochMs: 3000,
          ),
        ],
      );

      final resolved = resolveChatRuntimeSnapshot(embedded, streamed);

      expect(resolved, same(embedded));
      expect(
        runtimeSnapshotVersion(embedded),
        runtimeSnapshotVersion(streamed),
      );
    },
  );

  testWidgets('running card opens a full-screen view on double tap', (
    tester,
  ) async {
    await tester.pumpWidget(_buildChatHarness());
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-1'),
    );
    final center = tester.getCenter(bubbleFinder);

    await tester.tapAt(center);
    await tester.pump(const Duration(milliseconds: 40));
    await tester.tapAt(center);
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-run-trace-fullscreen-run-1')),
      findsOneWidget,
    );
    expect(find.textContaining('Read README.md lines 5-6'), findsWidgets);
    expect(find.textContaining('"file_path": "README.md"'), findsOneWidget);
    expect(
      find.textContaining(
        'Project uses the Gradle wrapper from the repo root.',
      ),
      findsOneWidget,
    );
  });

  testWidgets(
    'host-mapped run trace shows tool parameters in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final toolCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-host-1',
        taskId: 'task-host-1',
        emittedAtEpochMs: 2000,
        toolName: 'Read',
        argumentsJson: '{"file_path":"README.md","offset":5,"limit":2}',
      );
      final toolResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-host-1',
        taskId: 'task-host-1',
        emittedAtEpochMs: 3000,
        toolName: 'Read',
        contentPreview:
            'Project uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
        resultMetadata: const <String, String>{
          'filePath': 'README.md',
          'offset': '5',
          'limit': '2',
          'returnedLineCount': '2',
          'totalLineCount': '12',
        },
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-host-1',
              taskId: 'task-host-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: false,
              lastEvent: toolResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            const OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-host-1',
              taskId: 'task-host-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            toolCall,
            toolResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('Read README.md lines 5-6'), findsOneWidget);
      expect(
        find.textContaining('Returned 2 lines from README.md'),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'Project uses the Gradle wrapper from the repo root.',
        ),
        findsOneWidget,
      );

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-host-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-host-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('"file_path": "README.md"'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('"offset": 5'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('"limit": 2'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Use .\\\\gradlew.bat test to run JVM tests.',
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows delegated Task and subagent lifecycle details',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const taskCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-subagent-1',
        taskId: 'task-subagent-1',
        emittedAtEpochMs: 2000,
        toolName: 'Task',
        argumentsJson:
            '{"description":"Inspect README","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}',
      );
      const subagentStarted = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-1',
        taskId: 'task-subagent-1',
        emittedAtEpochMs: 2500,
        phase: 'started',
        label: 'Inspect README',
        childRunId: 'subagent-run-1',
        childTaskId: 'subagent-task-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
      );
      const subagentCompleted = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-1',
        taskId: 'task-subagent-1',
        emittedAtEpochMs: 2800,
        phase: 'completed',
        label: 'Inspect README',
        childRunId: 'subagent-run-1',
        childTaskId: 'subagent-task-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
        text: 'README says hello.',
      );
      const taskResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-subagent-1',
        taskId: 'task-subagent-1',
        emittedAtEpochMs: 3200,
        toolName: 'Task',
        contentPreview: 'Child summary: README says hello.',
        resultMetadata: <String, String>{
          'delegationDescription': 'Inspect README',
          'delegationPromptPreview': 'Read README.md and summarize it.',
          'delegationSubagentType': 'researcher',
          'delegationContextMode': 'minimal',
          'delegationAllowedTools': 'Glob,Grep,LS,Read',
          'childExecutionStatus': 'success',
          'childTurnCount': '2',
          'childToolCallCount': '1',
          'childRunId': 'subagent-run-1',
          'childTaskId': 'subagent-task-1',
        },
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-subagent-1',
              taskId: 'task-subagent-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3200,
              attempt: 1,
              isTerminal: false,
              lastEvent: taskResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-subagent-1',
              taskId: 'task-subagent-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            taskCall,
            subagentStarted,
            subagentCompleted,
            taskResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining('Researcher started: Inspect README'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Researcher completed: Inspect README'),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'Researcher completed. minimal context, 2 turns, 1 tool call.',
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining('Child summary: README says hello.'),
        findsOneWidget,
      );

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-subagent-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-subagent-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Delegate to Researcher: Inspect README',
          ),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Prompt: Read README.md and summarize it.',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Allowed tools: Glob, Grep, LS, Read'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Summary: README says hello.'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('host-mapped read summary prefers stable result limit metadata', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final toolResult = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-host-read-limit-1',
      taskId: 'task-host-read-limit-1',
      emittedAtEpochMs: 3000,
      toolName: 'Read',
      contentPreview:
          'Project uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
      resultMetadata: const <String, String>{
        'filePath': 'README.md',
        'returnedLineCount': '2',
        'totalLineCount': '12',
        'resultLimitApplied': 'true',
        'resultTruncated': 'true',
        'resultLimitKind': 'read_byte_budget',
      },
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-host-read-limit-1',
            taskId: 'task-host-read-limit-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            isTerminal: false,
            lastEvent: toolResult,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          const OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-host-read-limit-1',
            taskId: 'task-host-read-limit-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          toolResult,
        ],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.textContaining('Returned 2 lines from README.md'),
      findsOneWidget,
    );
    expect(
      find.textContaining('Output truncated to the read budget.'),
      findsOneWidget,
    );
  });

  testWidgets(
    'compact running card keeps recent LS history details and results',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const lsCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-host-ls-1',
        taskId: 'task-host-ls-1',
        emittedAtEpochMs: 2000,
        toolName: 'LS',
        argumentsJson: '{"path":"src","max_entries":5}',
      );
      const lsResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-host-ls-1',
        taskId: 'task-host-ls-1',
        emittedAtEpochMs: 3000,
        toolName: 'LS',
        contentPreview: 'file\tsrc/main.dart\nfile\tsrc/app.dart',
        resultMetadata: <String, String>{'path': 'src', 'entryCount': '2'},
      );
      const grepCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-host-ls-1',
        taskId: 'task-host-ls-1',
        emittedAtEpochMs: 4000,
        toolName: 'Grep',
        argumentsJson: '{"pattern":"TODO","path":"src"}',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-host-ls-1',
              taskId: 'task-host-ls-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 4000,
              attempt: 1,
              isTerminal: false,
              lastEvent: grepCall,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-host-ls-1',
              taskId: 'task-host-ls-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            lsCall,
            lsResult,
            grepCall,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('List src'), findsWidgets);
      expect(find.textContaining('Listed 2 entries in src'), findsOneWidget);
      expect(find.textContaining('"path": "src"'), findsOneWidget);
      expect(find.textContaining('file\tsrc/main.dart'), findsOneWidget);
      expect(find.textContaining('Search "TODO" in src'), findsOneWidget);
    },
  );

  testWidgets(
    'compact running card shows approval request details from runtime history',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const approvalEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'approval_wait',
        runId: 'run-approval-1',
        taskId: 'task-approval-1',
        emittedAtEpochMs: 2200,
        status: 'required',
        toolName: 'Bash',
        isHighRisk: true,
        text:
            'High-risk approval required\n\nCommand: git status --short\nWorking directory: .\nAgent reason: Check repository state before editing.\n\nApproval is required before Bash can run.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              isTerminal: false,
              errorCode: 'HIGH_RISK_APPROVAL_REQUIRED',
              lastEvent: approvalEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            approvalEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text(copy.chatRunWaitingApprovalLabel), findsOneWidget);
      expect(
        find.textContaining('Command: git status --short'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Approval is required before Bash can run.'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows public progress summaries in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const progressEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'progress',
        runId: 'run-progress-1',
        taskId: 'task-progress-1',
        emittedAtEpochMs: 2200,
        stage: 'Planning',
        text: 'Scanning README and Gradle files before choosing the next tool.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'runtime-progress-run-progress-1-2200',
              kind: 'inbound',
              text:
                  'Planning\n\nScanning README and Gradle files before choosing the next tool.',
              isEphemeral: true,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              isTerminal: false,
              lastEvent: progressEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            progressEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining(
          'Scanning README and Gradle files before choosing the next tool.',
        ),
        findsWidgets,
      );
      expect(find.text('Planning'), findsOneWidget);
      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-bubble-runtime-progress-run-progress-1-2200',
          ),
        ),
        findsOneWidget,
      );

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-progress-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenScrollFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-scroll-run-progress-1',
        ),
      );

      expect(
        find.descendant(
          of: fullscreenScrollFinder,
          matching: find.text('Planning'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenScrollFinder,
          matching: find.textContaining(
            'Scanning README and Gradle files before choosing the next tool.',
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows memory retrieval details in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const memoryEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'memory_retrieval',
        runId: 'run-memory-1',
        taskId: 'task-memory-1',
        emittedAtEpochMs: 2000,
        toolName: 'memory_search',
        operation: 'search',
        query: 'gradle wrapper repo root',
        queryTerms: <String>['gradle', 'wrapper', 'repo', 'root'],
        resultCount: 1,
        corpusFileCount: 1,
        paths: <String>['memory/2024-03-11.md'],
        lineRanges: <String>['5-8'],
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-memory-1',
              taskId: 'task-memory-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2000,
              attempt: 1,
              isTerminal: false,
              lastEvent: memoryEvent,
            ),
          ],
          events: const <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-memory-1',
              taskId: 'task-memory-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            memoryEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining('Search memory for "gradle wrapper repo root"'),
        findsOneWidget,
      );
      expect(find.textContaining('memory/2024-03-11.md#5-8'), findsOneWidget);

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-memory-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-memory-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Query terms: gradle, wrapper, repo, root',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('memory/2024-03-11.md#5-8'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows memory maintenance details in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const memoryEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'memory_write',
        runId: 'run-memory-write-1',
        taskId: 'task-memory-write-1',
        emittedAtEpochMs: 2500,
        writtenRecordIds: <String>['memory-user-1'],
        writtenKinds: <String>['user_preference'],
        resolvedRecordIds: <String>['commitment-done-1'],
        reaffirmedRecordIds: <String>['commitment-keep-1'],
        expiredRecordIds: <String>['commitment-old-1'],
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-memory-write-1',
              taskId: 'task-memory-write-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2500,
              attempt: 1,
              isTerminal: false,
              lastEvent: memoryEvent,
            ),
          ],
          events: const <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-memory-write-1',
              taskId: 'task-memory-write-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            memoryEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining(
          'Memory maintenance: wrote 1 record, resolved 1 record, reaffirmed 1 record, expired 1 record',
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining('Resolved: commitment-done-1'),
        findsOneWidget,
      );

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-memory-write-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-memory-write-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Written: memory-user-1'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Kinds: user_preference'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Reaffirmed: commitment-keep-1'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Expired: commitment-old-1'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows context setup traces in full-screen view',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const lifecycleEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'lifecycle',
        runId: 'run-context-1',
        taskId: 'task-context-1',
        emittedAtEpochMs: 1000,
        phase: 'start',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-context-1',
              taskId: 'task-context-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: false,
              lastEvent: lifecycleEvent,
              liveContext: OpenCrayChatRunLiveContextSnapshot(
                mode: 'no_soul',
                soulEnabled: false,
                memoryRecallEnabled: true,
              ),
              memoryTrace: OpenCrayChatRunMemoryTraceSnapshot(
                matchedRecordCount: 2,
                injectedRecordCount: 1,
                omittedRecordCount: 1,
                queryTerms: <String>['gradle', 'wrapper'],
                selected: <OpenCrayChatRunMemorySelectedSnapshot>[
                  OpenCrayChatRunMemorySelectedSnapshot(
                    id: 'mem-workspace',
                    score: 420,
                    matchedTerms: <String>['gradle', 'wrapper'],
                  ),
                ],
                omitted: <OpenCrayChatRunMemoryOmittedSnapshot>[
                  OpenCrayChatRunMemoryOmittedSnapshot(
                    id: 'mem-old',
                    reason: 'max_records',
                  ),
                ],
              ),
              memoryFlush: OpenCrayChatRunMemoryFlushSnapshot(
                outcome: 'written',
                candidateCount: 2,
                writtenRecordCount: 1,
                writtenKinds: <String>['project_fact'],
                writtenRecordIds: <String>['mem-workspace'],
              ),
              bootstrap: OpenCrayChatRunBootstrapSnapshot(
                mode: 'full',
                visibleFileCount: 2,
                injectedFileCount: 2,
                truncatedFileCount: 1,
                files: <OpenCrayChatRunBootstrapFileSnapshot>[
                  OpenCrayChatRunBootstrapFileSnapshot(
                    name: 'AGENTS.md',
                    relativePath: 'AGENTS.md',
                    sourceCharCount: 42,
                    injectedCharCount: 42,
                    truncated: false,
                  ),
                  OpenCrayChatRunBootstrapFileSnapshot(
                    name: 'PROJECT.md',
                    relativePath: 'PROJECT.md',
                    sourceCharCount: 80,
                    injectedCharCount: 31,
                    truncated: true,
                  ),
                ],
              ),
              durableCompaction: OpenCrayChatRunDurableCompactionSnapshot(
                compactedThisRun: true,
                sourceTranscriptMessageCount: 18,
                retainedTranscriptMessageCount: 12,
                latestCompactedMessageCount: 6,
                includedSummaryCount: 1,
                totalSummaryCount: 1,
                totalCompactedMessageCount: 6,
              ),
              skillInventory: OpenCrayChatRunSkillInventorySnapshot(
                visibleSkillCount: 2,
                injectedSkillCount: 2,
                implicitSkillCount: 1,
                skills: <OpenCrayChatRunVisibleSkillSnapshot>[
                  OpenCrayChatRunVisibleSkillSnapshot(
                    name: 'ui-ux-pro-max',
                    relativePath: 'skills/ui-ux-pro-max/SKILL.md',
                    invocationControl: 'manual',
                    userInvocable: true,
                    executionContext: 'shared',
                  ),
                ],
              ),
              activeSkill: OpenCrayChatRunActiveSkillSnapshot(
                name: 'ui-ux-pro-max',
                relativePath: 'skills/ui-ux-pro-max/SKILL.md',
                activationSource: 'skill_read',
                toolRestrictionEnabled: true,
                allowedToolKeys: <String>['read', 'write'],
              ),
            ),
          ],
          events: const <OpenCrayChatRuntimeEventSnapshot>[lifecycleEvent],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-context-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-context-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Mode: no_soul'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Soul disabled'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Automatic memory recall enabled'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Mode: full'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('AGENTS.md (AGENTS.md)'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Query terms: gradle, wrapper'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Outcome: written'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Retained 12/18 transcript messages'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('ui-ux-pro-max'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Allowed tools: read, write'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('running card body scrolls independently', (tester) async {
    await tester.pumpWidget(_buildChatHarness());
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-1'),
    );
    final scrollableFinder = find.descendant(
      of: bubbleFinder,
      matching: find.byType(Scrollable),
    );
    final scrollableStateBefore = tester.state<ScrollableState>(
      scrollableFinder,
    );

    expect(scrollableStateBefore.position.pixels, 0);

    await tester.drag(
      find.byKey(const ValueKey<String>('chat-run-trace-scroll-run-1')),
      const Offset(0, -180),
    );
    await tester.pumpAndSettle();

    final scrollableStateAfter = tester.state<ScrollableState>(
      scrollableFinder,
    );
    expect(scrollableStateAfter.position.pixels, greaterThan(0));
  });

  testWidgets('full-screen running card body scrolls independently', (
    tester,
  ) async {
    await tester.pumpWidget(_buildChatHarness());
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-1'),
    );
    final center = tester.getCenter(bubbleFinder);

    await tester.tapAt(center);
    await tester.pump(const Duration(milliseconds: 40));
    await tester.tapAt(center);
    await tester.pumpAndSettle();

    final fullscreenScrollFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-fullscreen-scroll-run-1'),
    );
    final fullscreenScrollableFinder = find.descendant(
      of: find.byKey(const ValueKey<String>('chat-run-trace-fullscreen-run-1')),
      matching: find.byType(Scrollable),
    );
    final scrollableStateBefore = tester.state<ScrollableState>(
      fullscreenScrollableFinder,
    );

    expect(scrollableStateBefore.position.pixels, 0);

    await tester.drag(fullscreenScrollFinder, const Offset(0, -220));
    await tester.pumpAndSettle();

    final scrollableStateAfter = tester.state<ScrollableState>(
      fullscreenScrollableFinder,
    );
    expect(scrollableStateAfter.position.pixels, greaterThan(0));
  });

  testWidgets(
    'approval card is rendered inline with the running trace instead of at the top',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      await tester.pumpWidget(
        _buildChatHarness(
          pendingApprovals: const <ChatPendingApprovalData>[
            ChatPendingApprovalData(
              runId: 'run-1',
              taskId: 'task-1',
              title: 'Approval required',
              body: 'Write note.txt?',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: false,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text(copy.chatPendingApprovalsTitle), findsNothing);
      expect(find.text('Approval required'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-1')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-backed approval card shows tool name, concrete request details, and agent reason',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          pendingApprovals: const <OpenCrayChatPendingApprovalSnapshot>[
            OpenCrayChatPendingApprovalSnapshot(
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              title: 'Approval required',
              body:
                  'Command: git status --short\nWorking directory: .\nAgent reason: Check repository state before editing.\n\nApproval is required before Bash can run.',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: false,
              toolName: 'Bash',
              requestSummary: 'git status --short',
              primaryDetail: 'git status --short',
              workingDirectory: '.',
              reason: 'Check repository state before editing.',
              message: 'Approval is required before Bash can run.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1001,
              attempt: 1,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Approval required'), findsOneWidget);
      expect(find.text('Bash'), findsOneWidget);
      expect(find.text('Request'), findsOneWidget);
      expect(find.text('git status --short'), findsOneWidget);
      expect(find.text('Working directory'), findsOneWidget);
      expect(find.text('.'), findsOneWidget);
      expect(find.text('Agent reason'), findsOneWidget);
      expect(
        find.text('Check repository state before editing.'),
        findsOneWidget,
      );
      expect(
        find.text('Approval is required before Bash can run.'),
        findsOneWidget,
      );

      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();

      expect(bridge.approvedApprovalIds, <String>['run-approval-1']);
    },
  );

  testWidgets('chat messages render timestamps and 8-minute time dividers', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final DateTime now = DateTime.now().toLocal();
    final DateTime firstAt = DateTime(now.year, now.month, now.day, 9, 0);
    final DateTime secondAt = firstAt.add(const Duration(minutes: 5));
    final DateTime thirdAt = secondAt.add(const Duration(minutes: 8));
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'message-1',
            kind: 'outbound',
            text: 'First message',
            createdAtEpochMs: firstAt.millisecondsSinceEpoch,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'message-2',
            kind: 'inbound',
            text: 'Second message',
            createdAtEpochMs: secondAt.millisecondsSinceEpoch,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'message-3',
            kind: 'outbound',
            text: 'Third message',
            createdAtEpochMs: thirdAt.millisecondsSinceEpoch,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-message-divider-message-1')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-message-divider-message-2')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-message-divider-message-3')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-message-time-message-1')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-message-time-message-2')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-message-time-message-3')),
      findsNothing,
    );
  });

  testWidgets('session drawer shows unread dot and count badges', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildChatHarness(
        drawerOpen: true,
        drawer: const ChatSessionsDrawerState(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: <ChatSessionListItemData>[
            ChatSessionListItemData(
              sessionId: 'session-dot',
              title: 'Background dot',
              preview: 'Agent replied in the background.',
              meta: '2 messages',
              unreadCount: 1,
            ),
            ChatSessionListItemData(
              sessionId: 'session-count',
              title: 'Background count',
              preview: 'More than one reply arrived.',
              meta: '5 messages',
              unreadCount: 3,
            ),
          ],
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-session-unread-session-dot')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-session-unread-session-count')),
      findsOneWidget,
    );
    expect(find.text('3'), findsOneWidget);
  });

  testWidgets('host-backed session drawer shows recent message time labels', (
    tester,
  ) async {
    final DateTime now = DateTime.now().toLocal();
    final DateTime todayAt = DateTime(now.year, now.month, now.day, 14, 32);
    final DateTime yesterdayAt = todayAt.subtract(const Duration(days: 1));
    final DateTime weekdayAt = todayAt.subtract(const Duration(days: 3));
    final DateTime olderAt = todayAt.subtract(const Duration(days: 12));
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        drawer: OpenCrayChatDrawerSnapshot(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: <OpenCrayChatSessionItemSnapshot>[
            OpenCrayChatSessionItemSnapshot(
              sessionId: 'session-today',
              title: 'Today session',
              preview: 'A recent reply arrived.',
              meta: '2 messages',
              isSelected: false,
              lastMessageAtEpochMs: todayAt.millisecondsSinceEpoch,
            ),
            OpenCrayChatSessionItemSnapshot(
              sessionId: 'session-yesterday',
              title: 'Yesterday session',
              preview: 'A reply arrived yesterday.',
              meta: '5 messages',
              isSelected: false,
              lastMessageAtEpochMs: yesterdayAt.millisecondsSinceEpoch,
            ),
            OpenCrayChatSessionItemSnapshot(
              sessionId: 'session-weekday',
              title: 'Weekday session',
              preview: 'A reply arrived earlier this week.',
              meta: '8 messages',
              isSelected: false,
              lastMessageAtEpochMs: weekdayAt.millisecondsSinceEpoch,
            ),
            OpenCrayChatSessionItemSnapshot(
              sessionId: 'session-older',
              title: 'Older session',
              preview: 'A reply arrived earlier this month.',
              meta: '13 messages',
              isSelected: false,
              lastMessageAtEpochMs: olderAt.millisecondsSinceEpoch,
            ),
          ],
        ),
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
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

    await tester.tap(find.text('Sessions'));
    await tester.pumpAndSettle();

    expect(find.text('14:32'), findsOneWidget);
    expect(find.text('Yesterday'), findsOneWidget);
    expect(find.text(_weekdayLabelFor(weekdayAt)), findsOneWidget);
    expect(find.text(_dateLabelFor(olderAt, now: now)), findsOneWidget);
    expect(find.text('2 messages'), findsNothing);
    expect(find.text('5 messages'), findsNothing);
    expect(find.text('8 messages'), findsNothing);
    expect(find.text('13 messages'), findsNothing);
  });

  testWidgets(
    'host message renders image, voice, and file attachments in one bubble',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final playbackLog = _FakeVoicePlaybackLog();
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'assistant-media',
              kind: 'inbound',
              text: 'Here are the generated assets.',
              attachments: const <OpenCrayChatAttachmentSnapshot>[
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'image-1',
                  kind: 'image',
                  displayName: 'diagram-a.png',
                  localPath:
                      '.opencray/chat-media/session-1/hash-a/diagram-a.png',
                ),
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'image-2',
                  kind: 'image',
                  displayName: 'diagram-b.png',
                  localPath:
                      '.opencray/chat-media/session-1/hash-b/diagram-b.png',
                ),
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'voice-1',
                  kind: 'voice',
                  displayName: 'voice-note.m4a',
                  localPath:
                      '.opencray/chat-media/session-1/hash-c/voice-note.m4a',
                  durationMs: 4200,
                ),
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'file-1',
                  kind: 'file',
                  displayName: 'report.pdf',
                  localPath: '.opencray/chat-media/session-1/hash-d/report.pdf',
                  sizeBytes: 4096,
                ),
              ],
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        imagePreviews: <String, OpenCrayFileImagePreview>{
          '.opencray/chat-media/session-1/hash-a/diagram-a.png':
              _fakeImagePreview(
                name: 'diagram-a.png',
                relativePath:
                    '.opencray/chat-media/session-1/hash-a/diagram-a.png',
              ),
          '.opencray/chat-media/session-1/hash-b/diagram-b.png':
              _fakeImagePreview(
                name: 'diagram-b.png',
                relativePath:
                    '.opencray/chat-media/session-1/hash-b/diagram-b.png',
              ),
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: copy,
              bridge: bridge,
              voicePlaybackControllerFactory: () =>
                  _FakeVoicePlaybackController(playbackLog),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-bubble-assistant-media')),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-message-image-group-assistant-media'),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-message-image-attachment-image-1'),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-message-image-attachment-image-2'),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-message-attachment-voice-1')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-message-attachment-file-1')),
        findsOneWidget,
      );
      expect(find.text('Here are the generated assets.'), findsOneWidget);
      expect(find.text('voice-note.m4a'), findsOneWidget);
      expect(find.text('report.pdf'), findsOneWidget);
    },
  );

  testWidgets('host attachment tiles open workspace files on tap', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final playbackLog = _FakeVoicePlaybackLog();
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: <OpenCrayChatMessageSnapshot>[
          const OpenCrayChatMessageSnapshot(
            messageId: 'assistant-open-media',
            kind: 'inbound',
            text: '',
            attachments: <OpenCrayChatAttachmentSnapshot>[
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'voice-open-1',
                kind: 'voice',
                displayName: 'voice-note.m4a',
                localPath:
                    '.opencray/chat-media/session-1/hash-c/voice-note.m4a',
                durationMs: 4200,
              ),
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'file-open-1',
                kind: 'file',
                displayName: 'report.pdf',
                localPath: '.opencray/chat-media/session-1/hash-d/report.pdf',
                sizeBytes: 4096,
              ),
            ],
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      voicePlaybackSources: <String, OpenCrayFileVoicePlaybackSource>{
        '.opencray/chat-media/session-1/hash-c/voice-note.m4a':
            const OpenCrayFileVoicePlaybackSource(
              name: 'voice-note.m4a',
              relativePath:
                  '.opencray/chat-media/session-1/hash-c/voice-note.m4a',
              localFilePath: '/workspace/session-1/voice-note.m4a',
              mimeType: 'audio/mp4',
              durationMs: 4200,
            ),
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(
            copy: copy,
            bridge: bridge,
            voicePlaybackControllerFactory: () =>
                _FakeVoicePlaybackController(playbackLog),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(
        const ValueKey<String>('chat-message-attachment-voice-open-1'),
      ),
    );
    await tester.pump();
    await tester.tap(
      find.byKey(const ValueKey<String>('chat-message-attachment-file-open-1')),
    );
    await tester.pump();

    expect(bridge.loadedVoicePlaybackSources, <String>[
      '.opencray/chat-media/session-1/hash-c/voice-note.m4a',
    ]);
    expect(playbackLog.sourcePaths, <String>[
      '/workspace/session-1/voice-note.m4a',
    ]);
    expect(playbackLog.playCount, 1);
    expect(bridge.openedWorkspaceEntries, <String>[
      '.opencray/chat-media/session-1/hash-d/report.pdf',
    ]);
  });

  testWidgets('voice attachment waveform seeks and transcript expands', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final playbackLog = _FakeVoicePlaybackLog();
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-voice-enhanced',
            kind: 'inbound',
            text: '',
            attachments: <OpenCrayChatAttachmentSnapshot>[
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'voice-enhanced-1',
                kind: 'voice',
                displayName: 'voice-note.m4a',
                localPath:
                    '.opencray/chat-media/session-1/hash-e/voice-note.m4a',
                durationMs: 4200,
                waveformBars: <int>[12, 28, 56, 72, 40, 88],
                transcriptText:
                    'Line one explains the change.\nLine two adds more detail about the update.',
              ),
            ],
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      voicePlaybackSources: <String, OpenCrayFileVoicePlaybackSource>{
        '.opencray/chat-media/session-1/hash-e/voice-note.m4a':
            const OpenCrayFileVoicePlaybackSource(
              name: 'voice-note.m4a',
              relativePath:
                  '.opencray/chat-media/session-1/hash-e/voice-note.m4a',
              localFilePath: '/workspace/session-1/voice-note.m4a',
              mimeType: 'audio/mp4',
              durationMs: 4200,
            ),
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(
            copy: copy,
            bridge: bridge,
            voicePlaybackControllerFactory: () =>
                _FakeVoicePlaybackController(playbackLog),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final transcriptFinder = find.byKey(
      const ValueKey<String>(
        'chat-message-attachment-transcript-voice-enhanced-1',
      ),
    );
    final toggleFinder = find.byKey(
      const ValueKey<String>(
        'chat-message-attachment-transcript-toggle-voice-enhanced-1',
      ),
    );
    final waveformFinder = find.byKey(
      const ValueKey<String>(
        'chat-message-attachment-waveform-voice-enhanced-1',
      ),
    );

    expect(waveformFinder, findsOneWidget);
    expect(toggleFinder, findsOneWidget);
    expect((tester.widget<Text>(transcriptFinder)).maxLines, 2);

    final Rect waveformRect = tester.getRect(waveformFinder);
    final TestGesture gesture = await tester.startGesture(
      Offset(waveformRect.left + 4, waveformRect.center.dy),
    );
    await gesture.moveTo(
      Offset(
        waveformRect.left + waveformRect.width * 0.75,
        waveformRect.center.dy,
      ),
    );
    await gesture.up();
    await tester.pump();

    expect(bridge.loadedVoicePlaybackSources, <String>[
      '.opencray/chat-media/session-1/hash-e/voice-note.m4a',
    ]);
    expect(playbackLog.sourcePaths, <String>[
      '/workspace/session-1/voice-note.m4a',
    ]);
    expect(
      playbackLog.seekPositions.last.inMilliseconds,
      inInclusiveRange(2800, 3400),
    );

    await tester.tap(toggleFinder);
    await tester.pump();

    expect((tester.widget<Text>(transcriptFinder)).maxLines, isNull);
    expect(find.text('Hide transcript'), findsOneWidget);
  });

  testWidgets('text file attachments open an internal preview on tap', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-text-preview',
            kind: 'inbound',
            text: '',
            attachments: <OpenCrayChatAttachmentSnapshot>[
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'file-preview-1',
                kind: 'file',
                displayName: 'report.md',
                localPath: '.opencray/chat-media/session-1/hash-d/report.md',
                sizeBytes: 128,
              ),
            ],
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      textPreviews: <String, OpenCrayFileTextPreview>{
        '.opencray/chat-media/session-1/hash-d/report.md':
            const OpenCrayFileTextPreview(
              name: 'report.md',
              relativePath: '.opencray/chat-media/session-1/hash-d/report.md',
              content: '# Report\n\nPreview body',
              isTruncated: false,
            ),
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(
        const ValueKey<String>('chat-message-attachment-file-preview-1'),
      ),
    );
    await tester.pumpAndSettle();

    expect(bridge.loadedTextPreviews, <String>[
      '.opencray/chat-media/session-1/hash-d/report.md',
    ]);
    expect(bridge.openedWorkspaceEntries, isEmpty);
    expect(
      find.byKey(const ValueKey<String>('chat-text-preview-dialog')),
      findsOneWidget,
    );
    expect(find.textContaining('Preview body'), findsOneWidget);
  });

  testWidgets('composer hides todo chrome when todo list is empty', (
    tester,
  ) async {
    await tester.pumpWidget(_buildChatHarness());
    await tester.pumpAndSettle();

    expect(find.text('TODO'), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-surface')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsNothing,
    );
  });

  testWidgets(
    'composer renders todo glass surface with approved status styling',
    (tester) async {
      await tester.pumpWidget(
        _buildChatHarness(
          todos: const <ChatTodoItemData>[
            ChatTodoItemData(
              content: 'Review chat composer layout',
              status: ChatTodoStatus.pending,
            ),
            ChatTodoItemData(
              content: 'Highlight active todo text',
              status: ChatTodoStatus.inProgress,
              activeForm: 'Highlighting active todo text',
            ),
            ChatTodoItemData(
              content: 'Approve Pencil prototype',
              status: ChatTodoStatus.completed,
            ),
            ChatTodoItemData(
              content: 'Ship Flutter implementation',
              status: ChatTodoStatus.pending,
            ),
            ChatTodoItemData(
              content: 'Verify scrolling for overflow',
              status: ChatTodoStatus.pending,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-composer-todo-surface')),
        findsOneWidget,
      );
      expect(find.text('TODO'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
        findsOneWidget,
      );

      final Size listSize = tester.getSize(
        find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      );
      expect(listSize.height, 130);

      final Text activeText = tester.widget<Text>(
        find.byKey(const ValueKey<String>('chat-composer-todo-text-1')),
      );
      final Text completedText = tester.widget<Text>(
        find.byKey(const ValueKey<String>('chat-composer-todo-text-2')),
      );
      final Container pendingIndicator = tester.widget<Container>(
        find.byKey(const ValueKey<String>('chat-composer-todo-indicator-0')),
      );
      final Container activeIndicator = tester.widget<Container>(
        find.byKey(const ValueKey<String>('chat-composer-todo-indicator-1')),
      );
      final Container completedIndicator = tester.widget<Container>(
        find.byKey(const ValueKey<String>('chat-composer-todo-indicator-2')),
      );

      final BoxDecoration pendingDecoration =
          pendingIndicator.decoration! as BoxDecoration;
      final BoxDecoration activeDecoration =
          activeIndicator.decoration! as BoxDecoration;
      final BoxDecoration completedDecoration =
          completedIndicator.decoration! as BoxDecoration;

      expect(find.text('Highlighting active todo text'), findsOneWidget);
      expect(activeText.style?.color, const Color(0xFF007AFF));
      expect(completedText.style?.decoration, TextDecoration.lineThrough);
      expect(pendingDecoration.color, Colors.transparent);
      expect(
        (pendingDecoration.border! as Border).top.color,
        const Color(0xFFD7D7DC),
      );
      expect(activeDecoration.color, Colors.transparent);
      expect(
        (activeDecoration.border! as Border).top.color,
        const Color(0xFF007AFF),
      );
      expect(completedDecoration.color, const Color(0xFFB8BDC7));
    },
  );

  testWidgets('todo panel toggles collapsed state from the header row', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildChatHarness(
        todos: const <ChatTodoItemData>[
          ChatTodoItemData(
            content: 'Review chat composer layout',
            status: ChatTodoStatus.pending,
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsOneWidget,
    );

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-composer-todo-toggle')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsNothing,
    );

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-composer-todo-toggle')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsOneWidget,
    );
  });
}

String _weekdayLabelFor(DateTime dateTime) {
  const List<String> weekdayLabels = <String>[
    'Mon',
    'Tue',
    'Wed',
    'Thu',
    'Fri',
    'Sat',
    'Sun',
  ];
  return weekdayLabels[dateTime.weekday - 1];
}

String _dateLabelFor(DateTime dateTime, {required DateTime now}) {
  const List<String> monthLabels = <String>[
    'Jan',
    'Feb',
    'Mar',
    'Apr',
    'May',
    'Jun',
    'Jul',
    'Aug',
    'Sep',
    'Oct',
    'Nov',
    'Dec',
  ];
  final String month = monthLabels[dateTime.month - 1];
  if (dateTime.year != now.year) {
    return '$month ${dateTime.day}, ${dateTime.year}';
  }
  return '$month ${dateTime.day}';
}

Widget _buildChatHarness({
  List<ChatPendingApprovalData> pendingApprovals =
      const <ChatPendingApprovalData>[],
  List<ChatTodoItemData> todos = const <ChatTodoItemData>[],
  ChatSessionsDrawerState? drawer,
  bool drawerOpen = false,
}) {
  final copy = OpenCrayUiCopy.fromLocaleTag('en');
  final traceBody = List<String>.generate(
    40,
    (index) => 'Running line ${index + 1}: checking repository state.',
  ).join('\n');
  return MaterialApp(
    home: Scaffold(
      body: OpenCrayChatFeature(
        copy: copy,
        state: ChatFeatureState(
          variant: ChatPrototypeVariant.main,
          screenTitle: 'Chat',
          summary: const ChatSessionSummary(
            title: 'Session',
            badge: '1 message',
            body: 'Reply in progress',
          ),
          messages: const <ChatMessageData>[
            ChatMessageData(
              kind: ChatMessageKind.outbound,
              text: 'Inspect the workspace.',
            ),
          ],
          runTraces: <ChatRunTraceData>[
            ChatRunTraceData(
              runId: 'run-1',
              taskId: 'task-1',
              label: copy.chatRunWorkingLabel,
              body: traceBody,
              history: <ChatRunTraceHistoryEntry>[
                ChatRunTraceHistoryEntry(
                  label: copy.chatRunWorkingLabel,
                  body: copy.chatRunThinkingActive,
                ),
                ChatRunTraceHistoryEntry(
                  label: 'Read',
                  body:
                      'Read README.md lines 5-6\n\n{\n  "file_path": "README.md",\n  "offset": 5,\n  "limit": 2\n}',
                ),
                ChatRunTraceHistoryEntry(
                  label: 'Read',
                  body:
                      'Read README.md lines 5-6\n\nProject uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
                ),
              ],
            ),
          ],
          pendingApprovals: pendingApprovals,
          composer: ChatComposerState(
            placeholder: copy.chatComposerPlaceholder,
            todos: todos,
          ),
          drawer:
              drawer ??
              const ChatSessionsDrawerState(
                eyebrow: 'Recent sessions',
                title: 'Recent sessions',
                ctaLabel: 'New session',
                sessions: <ChatSessionListItemData>[],
              ),
          drawerOpen: drawerOpen,
        ),
      ),
    ),
  );
}

OpenCrayChatSnapshot _hostChatSnapshot({
  List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals =
      const <OpenCrayChatPendingApprovalSnapshot>[],
  List<OpenCrayChatMessageSnapshot>? messages,
  OpenCrayChatDrawerSnapshot? drawer,
}) {
  return OpenCrayChatSnapshot(
    screenTitle: 'Chat',
    modeLabel: 'SAFE',
    sessionButtonLabel: 'Sessions',
    composerPlaceholder: 'Message OpenCray',
    summary: OpenCrayChatSummarySnapshot(
      title: 'Session',
      badge: '1 message',
      body: 'Reply in progress',
    ),
    messages:
        messages ??
        <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Inspect the workspace.',
          ),
        ],
    drawer:
        drawer ??
        OpenCrayChatDrawerSnapshot(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: <OpenCrayChatSessionItemSnapshot>[],
        ),
    isInputEnabled: true,
    pendingApprovals: pendingApprovals,
  );
}

class _FakeChatBridge implements OpenCrayHostBridge {
  _FakeChatBridge({
    required this.chatSnapshot,
    required this.runtimeSnapshot,
    this.imagePreviews = const <String, OpenCrayFileImagePreview>{},
    this.textPreviews = const <String, OpenCrayFileTextPreview>{},
    this.voicePlaybackSources =
        const <String, OpenCrayFileVoicePlaybackSource>{},
  });

  final OpenCrayChatSnapshot chatSnapshot;
  final OpenCrayChatRuntimeSnapshot runtimeSnapshot;
  final Map<String, OpenCrayFileImagePreview> imagePreviews;
  final Map<String, OpenCrayFileTextPreview> textPreviews;
  final Map<String, OpenCrayFileVoicePlaybackSource> voicePlaybackSources;
  final List<String> approvedApprovalIds = <String>[];
  final List<String> rejectedApprovalIds = <String>[];
  final List<String> loadedTextPreviews = <String>[];
  final List<String> loadedVoicePlaybackSources = <String>[];
  final List<String> openedWorkspaceEntries = <String>[];

  @override
  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  ) async {
    final preview = imagePreviews[relativePath];
    if (preview != null) {
      return preview;
    }
    throw StateError('Missing image preview for $relativePath');
  }

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async {
    loadedTextPreviews.add(relativePath);
    final preview = textPreviews[relativePath];
    if (preview != null) {
      return preview;
    }
    throw StateError('Missing text preview for $relativePath');
  }

  @override
  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  ) async {
    loadedVoicePlaybackSources.add(relativePath);
    final source = voicePlaybackSources[relativePath];
    if (source != null) {
      return source;
    }
    throw StateError('Missing voice playback source for $relativePath');
  }

  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async => chatSnapshot;

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() =>
      const Stream<OpenCrayChatSnapshot>.empty();

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      runtimeSnapshot;

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() =>
      const Stream<OpenCrayChatRuntimeSnapshot>.empty();

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      const OpenCrayMemoryDebugSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        records: <OpenCrayMemoryDebugRecordSnapshot>[],
      );

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async =>
      const OpenCrayMemoryDebugLinksSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        records: <OpenCrayMemoryDebugLinksEntrySnapshot>[],
      );

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      const OpenCraySoulDebugSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        overlayRecords: <OpenCrayMemoryDebugRecordSnapshot>[],
        fieldSources: <OpenCraySoulFieldSourceSnapshot>[],
      );

  @override
  Future<void> approveChatApproval(String approvalId) async {
    approvedApprovalIds.add(approvalId);
  }

  @override
  Future<void> rejectChatApproval(String approvalId) async {
    rejectedApprovalIds.add(approvalId);
  }

  @override
  Future<void> openWorkspaceEntry(String relativePath) async {
    openedWorkspaceEntries.add(relativePath);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FakeVoicePlaybackLog {
  final List<String> sourcePaths = <String>[];
  final List<Duration> seekPositions = <Duration>[];
  int playCount = 0;
  int pauseCount = 0;
}

class _FakeVoicePlaybackController implements ChatVoicePlaybackController {
  _FakeVoicePlaybackController(this.log);

  final _FakeVoicePlaybackLog log;
  final StreamController<ChatVoicePlaybackSnapshot> _snapshots =
      StreamController<ChatVoicePlaybackSnapshot>.broadcast();
  ChatVoicePlaybackSnapshot _state = const ChatVoicePlaybackSnapshot();

  @override
  ChatVoicePlaybackSnapshot get currentState => _state;

  @override
  Stream<ChatVoicePlaybackSnapshot> get snapshots => _snapshots.stream;

  @override
  Future<void> setSource({required String filePath}) async {
    log.sourcePaths.add(filePath);
    _state = _state.copyWith(
      duration: const Duration(milliseconds: 4200),
      clearError: true,
    );
    _snapshots.add(_state);
  }

  @override
  Future<void> play() async {
    log.playCount += 1;
    _state = _state.copyWith(isPlaying: true, clearError: true);
    _snapshots.add(_state);
  }

  @override
  Future<void> pause() async {
    log.pauseCount += 1;
    _state = _state.copyWith(isPlaying: false, clearError: true);
    _snapshots.add(_state);
  }

  @override
  Future<void> seek(Duration position) async {
    log.seekPositions.add(position);
    _state = _state.copyWith(position: position, clearError: true);
    _snapshots.add(_state);
  }

  @override
  Future<void> dispose() async {
    await _snapshots.close();
  }
}

OpenCrayFileImagePreview _fakeImagePreview({
  required String name,
  required String relativePath,
}) {
  return OpenCrayFileImagePreview(
    name: name,
    relativePath: relativePath,
    bytes: base64Decode(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wn7n8sAAAAASUVORK5CYII=',
    ),
    mimeType: 'image/png',
    width: 1,
    height: 1,
  );
}
