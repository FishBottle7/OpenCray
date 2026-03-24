import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_draft_attachment.dart';
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

  test(
    'resolveChatRuntimeSnapshot prefers the streamed snapshot when host changes',
    () {
      final embedded = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[],
        events: const <OpenCrayChatRuntimeEventSnapshot>[],
        hostLifecycle: const OpenCrayHostLifecycleSnapshot(
          hostInstanceId: 'host-1',
          hostCreatedAtEpochMs: 4000,
        ),
      );
      final streamed = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[],
        events: const <OpenCrayChatRuntimeEventSnapshot>[],
        hostLifecycle: const OpenCrayHostLifecycleSnapshot(
          hostInstanceId: 'host-2',
          hostCreatedAtEpochMs: 4000,
        ),
      );

      final resolved = resolveChatRuntimeSnapshot(embedded, streamed);

      expect(resolved, same(streamed));
    },
  );

  testWidgets('host rebuild shows explicit restart notice', (tester) async {
    final runtimeSnapshots =
        StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
    addTearDown(runtimeSnapshots.close);
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        hostLifecycle: OpenCrayHostLifecycleSnapshot(
          hostInstanceId: 'host-1',
          hostCreatedAtEpochMs: 1000,
        ),
      ),
      chatSnapshotStream: Stream<OpenCrayChatSnapshot>.empty(),
      runtimeSnapshotStream: runtimeSnapshots.stream,
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

    runtimeSnapshots.add(
      const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        hostLifecycle: OpenCrayHostLifecycleSnapshot(
          hostInstanceId: 'host-2',
          hostCreatedAtEpochMs: 1000,
        ),
      ),
    );

    await tester.pumpAndSettle();

    expect(
      find.text(
        'Runtime host rebuilt. Interrupted runs now require an explicit restart.',
      ),
      findsOneWidget,
    );
  });

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
    expect(find.textContaining('"file_path": "README.md"'), findsNothing);
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
          matching: find.textContaining('Read README.md lines 5-6'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Returned 2 lines from README.md'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('"file_path":"README.md"'),
        ),
        findsNothing,
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
    'full-screen inspector colors tool call semantics without changing outer trace text',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final toolCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-host-color-1',
        taskId: 'task-host-color-1',
        emittedAtEpochMs: 2000,
        toolName: 'Read',
        argumentsJson: '{"file_path":"README.md","offset":5,"limit":2}',
      );
      final toolResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-host-color-1',
        taskId: 'task-host-color-1',
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
              runId: 'run-host-color-1',
              taskId: 'task-host-color-1',
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
              runId: 'run-host-color-1',
              taskId: 'task-host-color-1',
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

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-host-color-1'),
      );
      final Offset center = tester.getCenter(bubbleFinder);
      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-host-color-1'),
      );
      final Finder semanticRichTextFinder = find.descendant(
        of: fullscreenFinder,
        matching: find.byWidgetPredicate((widget) {
          if (widget is! RichText) {
            return false;
          }
          return widget.text.toPlainText() == 'Read README.md lines 5-6';
        }),
      );

      expect(semanticRichTextFinder, findsOneWidget);
      final RichText richText = tester.widget<RichText>(semanticRichTextFinder);
      final TextSpan rootSpan = richText.text as TextSpan;
      List<TextSpan> collectLeafSpans(InlineSpan span) {
        if (span is! TextSpan) {
          return const <TextSpan>[];
        }
        final List<InlineSpan>? children = span.children;
        if (children == null || children.isEmpty) {
          return <TextSpan>[span];
        }
        return children
            .expand<TextSpan>(collectLeafSpans)
            .toList(growable: false);
      }

      final List<TextSpan> spans = collectLeafSpans(rootSpan);

      expect(spans.map((span) => span.text).toList(), <String>[
        'Read',
        ' ',
        'README.md',
        ' ',
        'lines 5-6',
      ]);
      expect(spans.map((span) => span.style?.color).toList(), <Color?>[
        const Color(0xFF007AFF),
        const Color(0xFF111111),
        const Color(0xFF7C3AED),
        const Color(0xFF111111),
        const Color(0xFF16A34A),
      ]);
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
      const subagentResumed = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-1',
        taskId: 'task-subagent-1',
        emittedAtEpochMs: 2650,
        phase: 'resumed',
        label: 'Inspect README',
        childRunId: 'subagent-run-1',
        childTaskId: 'subagent-task-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
        text: 'Delegated child approval granted. The child will continue.',
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
            subagentResumed,
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
          matching: find.text('Main agent'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.text('Researcher'),
        ),
        findsOneWidget,
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
          matching: find.textContaining('Child summary: README says hello.'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Researcher started: Inspect README'),
        ),
        findsNothing,
      );
      expect(find.text('Researcher'), findsOneWidget);

      await tester.tap(find.text('Researcher'));
      await tester.pumpAndSettle();

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Researcher started: Inspect README'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Researcher resumed: Inspect README'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Researcher completed: Inspect README'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Summary: README says hello.'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Delegate to Researcher: Inspect README',
          ),
        ),
        findsNothing,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Prompt: Read README.md and summarize it.',
          ),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'compact run trace prefers resumed subagent preview over trailing approval result',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const taskCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-subagent-approval-1',
        taskId: 'task-subagent-approval-1',
        emittedAtEpochMs: 2000,
        toolName: 'Task',
        argumentsJson:
            '{"description":"Inspect README","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}',
      );
      const subagentStarted = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-approval-1',
        taskId: 'task-subagent-approval-1',
        emittedAtEpochMs: 2500,
        phase: 'started',
        label: 'Inspect README',
        childRunId: 'subagent-run-approval-1',
        childTaskId: 'subagent-task-approval-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
      );
      const subagentResumed = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-approval-1',
        taskId: 'task-subagent-approval-1',
        emittedAtEpochMs: 2650,
        phase: 'resumed',
        label: 'Inspect README',
        childRunId: 'subagent-run-approval-1',
        childTaskId: 'subagent-task-approval-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
        text: 'Delegated child approval granted. The child will continue.',
      );
      const approvalResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'approval_result',
        runId: 'run-subagent-approval-1',
        taskId: 'task-subagent-approval-1',
        emittedAtEpochMs: 2700,
        toolName: 'Read',
        status: 'approved',
        text: 'Approval granted. The agent is resuming.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-subagent-approval-1',
              taskId: 'task-subagent-approval-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2700,
              attempt: 1,
              isTerminal: false,
              lastEvent: subagentResumed,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-subagent-approval-1',
              taskId: 'task-subagent-approval-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            taskCall,
            subagentStarted,
            subagentResumed,
            approvalResult,
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

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-subagent-approval-1'),
      );

      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.textContaining('Researcher resumed: Inspect README'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.textContaining(
            'Delegated child approval granted. The child will continue.',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.textContaining(
            'Approval granted. The agent is resuming.',
          ),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'subagent preview prefers execution state for background queued events',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const subagentQueued = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-background-1',
        taskId: 'task-subagent-background-1',
        emittedAtEpochMs: 2500,
        phase: 'started',
        status: 'background_queued',
        label: 'Inspect README',
        childRunId: 'subagent-run-background-1',
        childTaskId: 'subagent-task-background-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
        executionState: 'background_queued',
        continuationKind: 'background_resume',
        text: 'Waiting to continue in the background.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-subagent-background-1',
              taskId: 'task-subagent-background-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2500,
              attempt: 1,
              isTerminal: false,
              lastEvent: subagentQueued,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-subagent-background-1',
              taskId: 'task-subagent-background-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            subagentQueued,
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

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-subagent-background-1'),
      );

      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.textContaining(
            'Researcher queued in background: Inspect README',
          ),
        ),
        findsOneWidget,
      );

      final center = tester.getCenter(bubbleFinder);
      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-subagent-background-1',
        ),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Continuation: Resumes in background'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Summary: Waiting to continue in the background.',
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'Task result summary prefers child execution state over legacy status',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const taskCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-subagent-background-result-1',
        taskId: 'task-subagent-background-result-1',
        emittedAtEpochMs: 2000,
        toolName: 'Task',
        argumentsJson:
            '{"description":"Inspect README","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}',
      );
      const taskResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-subagent-background-result-1',
        taskId: 'task-subagent-background-result-1',
        emittedAtEpochMs: 3200,
        toolName: 'Task',
        contentPreview:
            'Child summary: README inspection continues in background.',
        resultMetadata: <String, String>{
          'delegationDescription': 'Inspect README',
          'delegationPromptPreview': 'Read README.md and summarize it.',
          'delegationSubagentType': 'researcher',
          'delegationContextMode': 'minimal',
          'delegationAllowedTools': 'Glob,Grep,LS,Read',
          'childExecutionState': 'background_running',
          'childExecutionStatus': 'FAILED',
          'childContinuationKind': 'background_resume',
          'childTurnCount': '2',
          'childToolCallCount': '1',
          'childRunId': 'subagent-run-background-result-1',
          'childTaskId': 'subagent-task-background-result-1',
        },
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-subagent-background-result-1',
              taskId: 'task-subagent-background-result-1',
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
              runId: 'run-subagent-background-result-1',
              taskId: 'task-subagent-background-result-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            taskCall,
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
        find.textContaining(
          'Researcher running in background. minimal context, 2 turns, 1 tool call.',
        ),
        findsOneWidget,
      );
      expect(find.textContaining('Researcher failed'), findsNothing);
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
    'compact running card shows rejected approval as awaiting direction',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const approvalRejected = OpenCrayChatRuntimeEventSnapshot(
        kind: 'approval_result',
        runId: 'run-approval-rejected-1',
        taskId: 'task-approval-rejected-1',
        emittedAtEpochMs: 2400,
        status: 'rejected',
        toolName: 'Write',
        text: 'Approval rejected. Waiting for the next instruction.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-approval-rejected-1',
              taskId: 'task-approval-rejected-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2400,
              attempt: 1,
              isTerminal: false,
              lastEvent: approvalRejected,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-approval-rejected-1',
              taskId: 'task-approval-rejected-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            approvalRejected,
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

      expect(find.text(copy.chatRunAwaitingDirectionLabel), findsOneWidget);
      expect(find.textContaining('Approval rejected'), findsOneWidget);
    },
  );

  testWidgets(
    'compact running card shows cancelled run as awaiting direction',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const cancellationEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'cancelled',
        runId: 'run-cancelled-1',
        taskId: 'task-cancelled-1',
        emittedAtEpochMs: 2500,
        toolName: 'Bash',
        text: 'Run cancelled. The agent is waiting for your next instruction.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-cancelled-1',
              taskId: 'task-cancelled-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2500,
              attempt: 1,
              isTerminal: false,
              lastEvent: cancellationEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-cancelled-1',
              taskId: 'task-cancelled-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            cancellationEvent,
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

      expect(find.text(copy.chatRunAwaitingDirectionLabel), findsOneWidget);
      expect(find.textContaining('Run cancelled.'), findsOneWidget);
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
    'host-mapped run trace shows applied supplements in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const supplementEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'supplement',
        runId: 'run-supplement-1',
        taskId: 'task-supplement-1',
        emittedAtEpochMs: 2500,
        turn: 1,
        entryId: 'supplement-1',
        text: 'Also check the tests before answering.',
        checkpoint: 'turn_start',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-supplement-1',
              taskId: 'task-supplement-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2500,
              attempt: 1,
              isTerminal: false,
              lastEvent: supplementEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-supplement-1',
              taskId: 'task-supplement-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            supplementEvent,
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

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-supplement-1'),
      );

      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.textContaining(
            'Also check the tests before answering.',
          ),
        ),
        findsOneWidget,
      );

      final center = tester.getCenter(bubbleFinder);
      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-supplement-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Also check the tests before answering.',
          ),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Applied at turn start'),
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
        suppressedRecordIds: <String>['memory-muted-1'],
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
          'Memory maintenance: wrote 1 record, resolved 1 record, suppressed 1 record, reaffirmed 1 record, expired 1 record',
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
          matching: find.textContaining('Suppressed: memory-muted-1'),
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
    'approval card replaces the composer with a bottom glass surface',
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

      expect(
        find.byKey(const ValueKey<String>('chat-approval-surface')),
        findsOneWidget,
      );
      expect(find.text('Approval required'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-1')),
        findsOneWidget,
      );
      expect(find.text(copy.chatComposerPlaceholder), findsNothing);
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

      expect(
        find.byKey(const ValueKey<String>('chat-approval-surface')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-approval-card-run-approval-1')),
        findsOneWidget,
      );
      expect(find.text('Approval required'), findsOneWidget);
      expect(find.text(copy.chatComposerPlaceholder), findsNothing);
      expect(find.text('git status --short'), findsOneWidget);
      expect(find.text('Working directory  .'), findsOneWidget);
      expect(
        find.text('Reason  Check repository state before editing.'),
        findsOneWidget,
      );
      expect(
        find.text('Approval is required before Bash can run.'),
        findsOneWidget,
      );

      await tester.ensureVisible(find.text('Approve'));
      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();

      expect(bridge.approvedApprovalIds, <String>['run-approval-1']);
    },
  );

  testWidgets(
    'multiple approvals render as a stacked queue and only the first one is actionable',
    (tester) async {
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          pendingApprovals: const <OpenCrayChatPendingApprovalSnapshot>[
            OpenCrayChatPendingApprovalSnapshot(
              runId: 'run-approval-stack-1',
              taskId: 'task-approval-stack-1',
              title: 'Approval required',
              body: 'Write lib/a.dart',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: false,
              requestSummary: 'Write lib/a.dart',
              reason: 'Patch the first file.',
            ),
            OpenCrayChatPendingApprovalSnapshot(
              runId: 'run-approval-stack-2',
              taskId: 'task-approval-stack-2',
              title: 'Approval required',
              body: 'Write lib/b.dart',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: false,
              requestSummary: 'Write lib/b.dart',
              reason: 'Patch the second file.',
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
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-approval-stack')),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-approval-card-run-approval-stack-1'),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-approval-card-run-approval-stack-2'),
        ),
        findsOneWidget,
      );
      expect(find.text('Write lib/a.dart'), findsWidgets);
      expect(find.text('Write lib/b.dart'), findsWidgets);

      await tester.ensureVisible(find.text('Approve'));
      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();

      expect(bridge.approvedApprovalIds, <String>['run-approval-stack-1']);
      expect(bridge.rejectedApprovalIds, isEmpty);
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

  testWidgets(
    'archived completed todos auto-hide after the visibility window',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          todos: const <OpenCrayChatTodoSnapshot>[
            OpenCrayChatTodoSnapshot(
              content: 'Review chat composer layout',
              status: 'completed',
            ),
            OpenCrayChatTodoSnapshot(
              content: 'Ship Flutter implementation',
              status: 'completed',
            ),
          ],
          todoState: 'archived_completed',
          todoHideDelayMs: 4000,
          todoCompletedAtEpochMs: 1700000003000,
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

      expect(find.text('TODO'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-composer-todo-list')),
        findsOneWidget,
      );

      await tester.pump(const Duration(seconds: 4));
      await tester.pump();

      expect(find.text('TODO'), findsNothing);
      expect(
        find.byKey(const ValueKey<String>('chat-composer-todo-list')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-composer-todo-surface')),
        findsNothing,
      );
    },
  );

  testWidgets('composer picks and submits attachments without requiring text', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.pickedAttachmentsByKind[OpenCrayChatDraftAttachmentKind.image] =
        <OpenCrayChatDraftAttachment>[
          const OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.image,
            displayName: 'workspace-shot.png',
            relativePath: '.opencray/chat-drafts/workspace-shot.png',
            mimeType: 'image/png',
            sizeBytes: 2048,
          ),
        ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionImage));
    await tester.pumpAndSettle();

    expect(find.text('workspace-shot.png'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
    await tester.pumpAndSettle();

    expect(bridge.submittedMessages, <String>['']);
    expect(bridge.submittedAttachments, hasLength(1));
    expect(
      bridge.submittedAttachments.single.single.relativePath,
      '.opencray/chat-drafts/workspace-shot.png',
    );
    expect(find.text('workspace-shot.png'), findsNothing);
  });

  testWidgets('composer deduplicates repeated attachments with feedback', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.pickedAttachmentsByKind[OpenCrayChatDraftAttachmentKind.file] =
        <OpenCrayChatDraftAttachment>[
          const OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.file,
            displayName: 'report.md',
            relativePath: '.opencray/chat-drafts/hash-a/report.md',
            mimeType: 'text/markdown',
            sizeBytes: 512,
          ),
          const OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.file,
            displayName: 'report.md',
            relativePath: '.opencray/chat-drafts/hash-a/report.md',
            mimeType: 'text/markdown',
            sizeBytes: 512,
          ),
        ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionFile));
    await tester.pumpAndSettle();

    expect(find.text('report.md'), findsOneWidget);
    expect(
      bridge.shownNativeToasts,
      contains('Ignored 1 duplicate attachment.'),
    );
  });

  testWidgets('composer enforces the image limit with feedback', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.pickedAttachmentsByKind[OpenCrayChatDraftAttachmentKind
        .image] = List<OpenCrayChatDraftAttachment>.generate(10, (int index) {
      final imageNumber = index + 1;
      return OpenCrayChatDraftAttachment(
        kind: OpenCrayChatDraftAttachmentKind.image,
        displayName: 'image-$imageNumber.png',
        relativePath:
            '.opencray/chat-drafts/hash-$imageNumber/image-$imageNumber.png',
        mimeType: 'image/png',
        sizeBytes: 1024 + imageNumber,
      );
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionImage));
    await tester.pumpAndSettle();

    expect(find.text('image-1.png'), findsOneWidget);
    expect(
      bridge.shownNativeToasts,
      contains('Each message supports up to 9 images. Skipped 1.'),
    );

    await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
    await tester.pumpAndSettle();

    expect(bridge.submittedAttachments.single, hasLength(9));
  });

  testWidgets('composer shows native feedback when attachment picking fails', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.pickChatAttachmentsError = StateError('picker failed');

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionFile));
    await tester.pumpAndSettle();

    expect(bridge.shownNativeToasts, contains('Unable to add attachment.'));
  });

  testWidgets('composer shows native feedback when submit fails', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.submitChatMessageError = StateError('submit failed');
    bridge.pickedAttachmentsByKind[OpenCrayChatDraftAttachmentKind.file] =
        <OpenCrayChatDraftAttachment>[
          const OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.file,
            displayName: 'report.md',
            relativePath: '.opencray/chat-drafts/hash-a/report.md',
            mimeType: 'text/markdown',
            sizeBytes: 512,
          ),
        ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionFile));
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
    await tester.pumpAndSettle();

    expect(bridge.shownNativeToasts, contains(copy.chatSubmitFailed));
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
                      'Read README.md lines 5-6\n  └ Project uses the Gradle wrapper from the repo root.\n  Use .\\\\gradlew.bat test to run JVM tests.',
                  inspectorCallParts: <ChatRunTraceInspectorTextPart>[
                    ChatRunTraceInspectorTextPart(
                      text: 'Read',
                      semantic: ChatRunTraceInspectorTextSemantic.action,
                    ),
                    ChatRunTraceInspectorTextPart(text: ' '),
                    ChatRunTraceInspectorTextPart(
                      text: 'README.md',
                      semantic: ChatRunTraceInspectorTextSemantic.target,
                    ),
                    ChatRunTraceInspectorTextPart(text: ' '),
                    ChatRunTraceInspectorTextPart(
                      text: 'lines 5-6',
                      semantic: ChatRunTraceInspectorTextSemantic.scope,
                    ),
                  ],
                  inspectorResultBody:
                      'Project uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
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
  List<OpenCrayChatTodoSnapshot> todos = const <OpenCrayChatTodoSnapshot>[],
  String todoState = 'empty',
  int? todoHideDelayMs,
  int? todoCompletedAtEpochMs,
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
    todos: todos,
    todoState: todoState,
    todoHideDelayMs: todoHideDelayMs,
    todoCompletedAtEpochMs: todoCompletedAtEpochMs,
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
    this.chatSnapshotStream,
    this.runtimeSnapshotStream,
  });

  final OpenCrayChatSnapshot chatSnapshot;
  final OpenCrayChatRuntimeSnapshot runtimeSnapshot;
  final Map<String, OpenCrayFileImagePreview> imagePreviews;
  final Map<String, OpenCrayFileTextPreview> textPreviews;
  final Map<String, OpenCrayFileVoicePlaybackSource> voicePlaybackSources;
  final Stream<OpenCrayChatSnapshot>? chatSnapshotStream;
  final Stream<OpenCrayChatRuntimeSnapshot>? runtimeSnapshotStream;
  final List<String> approvedApprovalIds = <String>[];
  final List<String> rejectedApprovalIds = <String>[];
  final List<String> loadedTextPreviews = <String>[];
  final List<String> loadedVoicePlaybackSources = <String>[];
  final List<String> openedWorkspaceEntries = <String>[];
  final List<String> shownNativeToasts = <String>[];
  Object? pickChatAttachmentsError;
  Object? submitChatMessageError;
  final Map<OpenCrayChatDraftAttachmentKind, List<OpenCrayChatDraftAttachment>>
  pickedAttachmentsByKind =
      <OpenCrayChatDraftAttachmentKind, List<OpenCrayChatDraftAttachment>>{};
  final List<String> submittedMessages = <String>[];
  final List<List<OpenCrayChatDraftAttachment>> submittedAttachments =
      <List<OpenCrayChatDraftAttachment>>[];

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
      chatSnapshotStream ?? Stream<OpenCrayChatSnapshot>.empty();

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      runtimeSnapshot;

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() =>
      runtimeSnapshotStream ?? Stream<OpenCrayChatRuntimeSnapshot>.empty();

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
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async => OpenCrayMemoryDebugSearchSnapshot(
    sessionId: '',
    observedAtEpochMs: 0,
    query: query,
  );

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async => OpenCrayMemoryDebugSliceSnapshot(
    sessionId: '',
    observedAtEpochMs: 0,
    path: path,
    startLine: fromLine ?? 1,
    endLine: (fromLine ?? 1) + lines - 1,
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
  Future<void> showNativeToast(String message) async {
    shownNativeToasts.add(message);
  }

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async {
    final error = pickChatAttachmentsError;
    if (error != null) {
      throw error;
    }
    return List<OpenCrayChatDraftAttachment>.of(
      pickedAttachmentsByKind[kind] ?? const <OpenCrayChatDraftAttachment>[],
    );
  }

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments =
        const <OpenCrayChatDraftAttachment>[],
  }) async {
    final error = submitChatMessageError;
    if (error != null) {
      throw error;
    }
    submittedMessages.add(text);
    submittedAttachments.add(List<OpenCrayChatDraftAttachment>.of(attachments));
    return const OpenCrayChatRunSubmission(
      sessionId: 'session-1',
      runId: 'run-1',
      taskId: 'task-1',
      acceptedAtEpochMs: 0,
    );
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
