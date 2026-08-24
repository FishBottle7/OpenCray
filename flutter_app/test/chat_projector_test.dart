import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

void main() {
  test(
    'runtime event deltas distinguish omitted live drafts from explicit clear',
    () {
      final omittedDrafts =
          OpenCrayChatRuntimeEventDelta.fromMap(<Object?, Object?>{
            'sessionId': 'session-1',
            'events': const <Object?>[],
            'totalLength': 0,
            'updatedAtEpochMs': 1200,
          });
      final explicitClear =
          OpenCrayChatRuntimeEventDelta.fromMap(<Object?, Object?>{
            'sessionId': 'session-1',
            'events': const <Object?>[],
            'totalLength': 0,
            'liveAssistantDrafts': const <Object?>[],
            'updatedAtEpochMs': 1300,
          });

      expect(omittedDrafts.hasLiveAssistantDraftsPatch, isFalse);
      expect(explicitClear.hasLiveAssistantDraftsPatch, isTrue);
    },
  );

  test(
    'runtime event deltas distinguish omitted run lists from explicit clears',
    () {
      final omittedRuns =
          OpenCrayChatRuntimeEventDelta.fromMap(<Object?, Object?>{
            'sessionId': 'session-1',
            'events': const <Object?>[],
            'totalLength': 0,
            'updatedAtEpochMs': 1200,
          });
      final explicitClear =
          OpenCrayChatRuntimeEventDelta.fromMap(<Object?, Object?>{
            'sessionId': 'session-1',
            'events': const <Object?>[],
            'totalLength': 0,
            'activeRuns': const <Object?>[],
            'retainedRuns': const <Object?>[],
            'subAgents': const <Object?>[],
            'updatedAtEpochMs': 1300,
          });

      expect(omittedRuns.hasActiveRunsPatch, isFalse);
      expect(omittedRuns.hasRetainedRunsPatch, isFalse);
      expect(omittedRuns.hasSubAgentsPatch, isFalse);
      expect(explicitClear.hasActiveRunsPatch, isTrue);
      expect(explicitClear.hasRetainedRunsPatch, isTrue);
      expect(explicitClear.hasSubAgentsPatch, isTrue);
      expect(explicitClear.hasRuntimeActivityPatch, isTrue);
    },
  );

  testWidgets(
    'same-version streamed runtime overrides a thinner embedded runtime when mapping UI',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      const commentaryEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-streamed-thicker-1',
        taskId: 'task-streamed-thicker-1',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        isFinal: false,
        stage: 'Planning',
        text: 'Inspecting the project layout.',
      );
      const embeddedRuntime = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2200,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 2200,
          runtimeActivity: embeddedRuntime,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-streamed-thicker-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1000,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-streamed-thicker-1',
              taskId: 'task-streamed-thicker-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              pendingMessageId: 'pending-streamed-thicker-1',
              isTerminal: false,
              lastEvent: commentaryEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-streamed-thicker-1',
              taskId: 'task-streamed-thicker-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            commentaryEvent,
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(
          const ValueKey<String>('chat-run-trace-run-streamed-thicker-1'),
        ),
        findsOneWidget,
      );
      expect(find.text('Inspecting the project layout.'), findsWidgets);
    },
  );

  testWidgets(
    'newer thin chat snapshots do not cover runtime projected process bubbles',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final chatSnapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(chatSnapshots.close);
      addTearDown(runtimeSnapshots.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the dev server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-process-stream-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1000,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        chatSnapshotStream: chatSnapshots.stream,
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 3200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-process-stream-1',
              taskId: 'task-process-stream-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3200,
              attempt: 1,
              pendingMessageId: 'pending-process-stream-1',
              managedProcessIds: <String>['proc-stream'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-stream',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  processStarted: true,
                  startedAtEpochMs: 2400,
                  updatedAtEpochMs: 3200,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream'), findsOneWidget);
      expect(
        find.textContaining('ready on http://localhost:3000'),
        findsWidgets,
      );

      const thinRuntimeWithNewDraft = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 6000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-process-stream-1',
            taskId: 'task-process-stream-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3200,
            attempt: 1,
            pendingMessageId: 'pending-process-stream-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
          OpenCrayChatLiveAssistantDraftSnapshot(
            runId: 'run-process-stream-1',
            taskId: 'task-process-stream-1',
            pendingMessageId: 'pending-process-stream-1',
            text: 'Streaming answer after the process starts.',
            updatedAtEpochMs: 6000,
          ),
        ],
      );
      chatSnapshots.add(
        hostChatSnapshot(
          updatedAtEpochMs: 6000,
          runtimeActivity: thinRuntimeWithNewDraft,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the dev server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-process-stream-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream'), findsOneWidget);
      expect(
        find.textContaining('ready on http://localhost:3000'),
        findsWidgets,
      );
      expect(
        findStreamedText('Streaming answer after the process starts.'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'late older chat snapshots do not roll back newer message bubbles',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(updatedAtEpochMs: 1000),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1000,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        chatSnapshotStream: snapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      snapshots.add(
        hostChatSnapshot(
          updatedAtEpochMs: 2000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'message-1',
              kind: 'inbound',
              text: 'Planning\n\nInspecting the project layout.',
              createdAtEpochMs: 2000,
              isEphemeral: true,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Planning'), findsOneWidget);
      expect(find.text('Inspecting the project layout.'), findsOneWidget);

      snapshots.add(hostChatSnapshot(updatedAtEpochMs: 1500));
      await tester.pumpAndSettle();

      expect(find.text('Planning'), findsOneWidget);
      expect(find.text('Inspecting the project layout.'), findsOneWidget);
    },
  );

  testWidgets(
    'late older runtime snapshots do not roll back projected process bubbles or inspector history',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      const assistantPhase = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-progress-1',
        taskId: 'task-progress-1',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        isFinal: false,
        stage: 'Planning',
        text: 'Inspecting the project layout.',
      );
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1000,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
              lastEvent: assistantPhase,
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
            assistantPhase,
          ],
        ),
      );
      await tester.pumpAndSettle();
      jumpToScrollEnd(tester, chatScrollView());
      await tester.pumpAndSettle();

      final String projectedBubbleMessageId =
          'runtime-assistant-commentary-task-progress-1--1-Planning-2200';
      final projectedBubble = find.byKey(
        ValueKey<String>('chat-bubble-$projectedBubbleMessageId'),
      );
      expect(projectedBubble, findsOneWidget);

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-progress-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await openRunTraceFullscreen(tester, runTraceFinder);
      expect(find.text('Inspecting the project layout.'), findsWidgets);
      Navigator.of(tester.element(find.byType(Scaffold))).pop();
      await tester.pumpAndSettle();

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1500,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1500,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
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
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(projectedBubble, findsOneWidget);
      await openRunTraceFullscreen(tester, runTraceFinder);
      expect(find.text('Inspecting the project layout.'), findsWidgets);
    },
  );

  testWidgets('authoritative empty runtime snapshots clear stale run traces', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final runtimeSnapshots =
        StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
    addTearDown(runtimeSnapshots.close);
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-runtime-clear-1',
            kind: 'inbound',
            text: 'Thinking',
            createdAtEpochMs: 1000,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2200,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-runtime-clear-1',
            taskId: 'task-runtime-clear-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2200,
            attempt: 1,
            pendingMessageId: 'pending-runtime-clear-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-runtime-clear-1',
            taskId: 'task-runtime-clear-1',
            emittedAtEpochMs: 2200,
            toolName: 'Read',
            contentPreview: 'Stale runtime detail.',
          ),
        ],
      ),
      runtimeSnapshotStream: runtimeSnapshots.stream,
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
      find.byKey(const ValueKey<String>('chat-run-trace-run-runtime-clear-1')),
      findsOneWidget,
    );
    expect(find.textContaining('Stale runtime detail.'), findsWidgets);

    runtimeSnapshots.add(
      const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2200,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        retainedRuns: <OpenCrayChatRunSnapshot>[],
        subAgents: <OpenCrayChatSubAgentSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[],
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-run-trace-run-runtime-clear-1')),
      findsNothing,
    );
    expect(find.textContaining('Stale runtime detail.'), findsNothing);
  });

  testWidgets(
    'live assistant drafts replace the pending thinking bubble in place',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a long summary.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-1',
              taskId: 'task-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-1',
              taskId: 'task-1',
              pendingMessageId: 'pending-1',
              text: 'Streaming answer in progress',
              updatedAtEpochMs: 1300,
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

      final pendingBubble = find.byKey(
        const ValueKey<String>('chat-bubble-pending-1'),
      );
      expect(pendingBubble, findsOneWidget);
      expect(
        find.descendant(
          of: pendingBubble,
          matching: findStreamedText('Streaming answer in progress'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: pendingBubble,
          matching: find.byKey(
            const ValueKey<String>('chat-streaming-indicator-pending-1'),
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(of: pendingBubble, matching: find.text('Thinking')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'live assistant drafts still render a chat bubble when the pending placeholder is missing',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a long summary.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-1',
              taskId: 'task-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-1',
              taskId: 'task-1',
              pendingMessageId: 'pending-1',
              text: 'Streaming answer in progress',
              updatedAtEpochMs: 1300,
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

      final pendingBubble = find.byKey(
        const ValueKey<String>('chat-bubble-pending-1'),
      );
      expect(pendingBubble, findsOneWidget);
      expect(
        find.descendant(
          of: pendingBubble,
          matching: findStreamedText('Streaming answer in progress'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'live assistant draft events update the chat bubble without a runtime snapshot refresh',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final draftEvents =
          StreamController<OpenCrayChatLiveAssistantDraftEvent>.broadcast();
      addTearDown(draftEvents.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a long summary.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        liveAssistantDraftEventStream: draftEvents.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-1',
          taskId: 'task-1',
          pendingMessageId: 'pending-1',
          text: 'First streamed chunk',
          updatedAtEpochMs: 1300,
        ),
      );
      await tester.pumpAndSettle();

      expect(findStreamedText('First streamed chunk'), findsOneWidget);

      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-1',
          taskId: 'task-1',
          pendingMessageId: 'pending-1',
          text: 'First streamed chunk and more',
          updatedAtEpochMs: 1400,
        ),
      );
      await tester.pumpAndSettle();

      expect(findStreamedText('First streamed chunk'), findsNothing);
      expect(findStreamedText('First streamed chunk and more'), findsOneWidget);
    },
  );

  testWidgets(
    'live assistant draft events coalesce to the latest visible text without rolling back',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final draftEvents =
          StreamController<OpenCrayChatLiveAssistantDraftEvent>.broadcast();
      addTearDown(draftEvents.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a concise report.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-coalesce-1',
              taskId: 'task-coalesce-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-coalesce-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        liveAssistantDraftEventStream: draftEvents.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-coalesce-1',
          taskId: 'task-coalesce-1',
          pendingMessageId: 'pending-coalesce-1',
          text: 'Complete streamed answer.',
          updatedAtEpochMs: 1500,
        ),
      );
      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-coalesce-1',
          taskId: 'task-coalesce-1',
          pendingMessageId: 'pending-coalesce-1',
          text: 'Complete',
          updatedAtEpochMs: 1400,
        ),
      );
      await tester.pumpAndSettle();

      expect(findStreamedText('Complete streamed answer.'), findsOneWidget);
      expect(find.text('Complete'), findsNothing);
    },
  );

  testWidgets('runtime deltas without live draft patches keep draft bubbles', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-keep-draft-1',
      taskId: 'task-keep-draft-1',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1200,
      attempt: 1,
      pendingMessageId: 'pending-keep-draft-1',
      isTerminal: false,
    );
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Write a long summary.',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
          OpenCrayChatLiveAssistantDraftSnapshot(
            runId: 'run-keep-draft-1',
            taskId: 'task-keep-draft-1',
            pendingMessageId: 'pending-keep-draft-1',
            text: 'Streaming answer in progress',
            updatedAtEpochMs: 1300,
          ),
        ],
        updatedAtEpochMs: 1300,
      ),
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(findStreamedText('Streaming answer in progress'), findsOneWidget);

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 1,
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_call',
            runId: 'run-keep-draft-1',
            taskId: 'task-keep-draft-1',
            emittedAtEpochMs: 1500,
            toolName: 'Read',
            argumentsJson: '{"file_path":"README.md"}',
          ),
        ],
        totalLength: 1,
        updatedAtEpochMs: 1500,
      ),
    );
    await tester.pumpAndSettle();

    expect(findStreamedText('Streaming answer in progress'), findsOneWidget);
  });

  testWidgets('runtime deltas clear stale live assistant draft bubbles', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-1',
      taskId: 'task-1',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1200,
      attempt: 1,
      pendingMessageId: 'pending-1',
      isTerminal: false,
    );
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Write a long summary.',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
          OpenCrayChatLiveAssistantDraftSnapshot(
            runId: 'run-1',
            taskId: 'task-1',
            pendingMessageId: 'pending-1',
            text: 'Streaming answer in progress',
            updatedAtEpochMs: 1300,
          ),
        ],
        updatedAtEpochMs: 1300,
      ),
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(findStreamedText('Streaming answer in progress'), findsOneWidget);

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 1,
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        totalLength: 0,
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[],
        hasLiveAssistantDraftsPatch: true,
        updatedAtEpochMs: 1500,
      ),
    );
    await tester.pumpAndSettle();

    expect(findStreamedText('Streaming answer in progress'), findsNothing);
  });

  testWidgets('runtime live draft clear patches remove local draft overrides', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final draftEvents =
        StreamController<OpenCrayChatLiveAssistantDraftEvent>.broadcast();
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(draftEvents.close);
    addTearDown(runtimeEventDeltas.close);
    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-draft-clear-override',
      taskId: 'task-draft-clear-override',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1200,
      attempt: 1,
      pendingMessageId: 'pending-draft-clear-override',
      isTerminal: false,
    );
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Write a long summary.',
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-draft-clear-override',
            kind: 'inbound',
            text: 'Thinking',
            createdAtEpochMs: 1000,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        updatedAtEpochMs: 1200,
      ),
      liveAssistantDraftEventStream: draftEvents.stream,
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    draftEvents.add(
      const OpenCrayChatLiveAssistantDraftEvent(
        sessionId: 'session-1',
        runId: 'run-draft-clear-override',
        taskId: 'task-draft-clear-override',
        pendingMessageId: 'pending-draft-clear-override',
        text: 'Override draft should clear.',
        updatedAtEpochMs: 1500,
      ),
    );
    await tester.pumpAndSettle();

    expect(findStreamedText('Override draft should clear.'), findsOneWidget);

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 1,
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        totalLength: 0,
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[],
        hasActiveRunsPatch: true,
        hasLiveAssistantDraftsPatch: true,
        updatedAtEpochMs: 0,
      ),
    );
    await tester.pumpAndSettle();

    expect(findStreamedText('Override draft should clear.'), findsNothing);
    expect(find.text('Thinking'), findsOneWidget);
  });

  testWidgets('runtime snapshot deltas clear stale active and retained runs', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-clear-delta-1',
      taskId: 'task-clear-delta-1',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1200,
      attempt: 1,
      pendingMessageId: 'pending-clear-delta-1',
      isTerminal: false,
      managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
        OpenCrayChatManagedProcessSnapshot(
          processId: 'proc-clear-delta-1',
          status: 'running',
          command: 'npm',
          args: <String>['run', 'dev'],
          processStarted: true,
          startedAtEpochMs: 1100,
          updatedAtEpochMs: 1200,
          stdoutPreview: 'server booting',
        ),
      ],
    );
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-clear-delta-1',
            kind: 'inbound',
            text: 'Thinking',
            createdAtEpochMs: 1000,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        retainedRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        updatedAtEpochMs: 1200,
      ),
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.textContaining('server booting'), findsWidgets);
    expect(
      find.byKey(const ValueKey<String>('chat-run-trace-run-clear-delta-1')),
      findsOneWidget,
    );

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 1,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        retainedRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        totalLength: 0,
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[],
        hasActiveRunsPatch: true,
        hasRetainedRunsPatch: true,
        hasLiveAssistantDraftsPatch: true,
        updatedAtEpochMs: 0,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.textContaining('server booting'), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('chat-run-trace-run-clear-delta-1')),
      findsNothing,
    );
  });

  testWidgets(
    'live assistant draft events do not recreate a pending bubble after commentary is projected',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final draftEvents =
          StreamController<OpenCrayChatLiveAssistantDraftEvent>.broadcast();
      addTearDown(draftEvents.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
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
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'assistant_phase',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 2200,
              phase: 'commentary',
              isFinal: false,
              turn: 0,
              stage: 'Planning',
              text:
                  'Scanning README and Gradle files before choosing the next tool.',
            ),
          ],
        ),
        liveAssistantDraftEventStream: draftEvents.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-progress-1',
          taskId: 'task-progress-1',
          pendingMessageId: 'pending-1',
          text: 'Streaming answer in progress',
          updatedAtEpochMs: 2300,
        ),
      );
      await tester.pumpAndSettle();

      final commentaryBubbleText = find.descendant(
        of: find.byWidgetPredicate((widget) {
          final Key? key = widget.key;
          return key is ValueKey<String> &&
              key.value.startsWith('chat-bubble-') &&
              key.value != 'chat-bubble-pending-1';
        }),
        matching: find.textContaining(
          'Scanning README and Gradle files before choosing the next tool.',
        ),
      );
      expect(commentaryBubbleText, findsOneWidget);
      expect(
        find.descendant(
          of: find.byKey(const ValueKey<String>('chat-bubble-pending-1')),
          matching: findStreamedText('Streaming answer in progress'),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'runtime event deltas update the open inspector without a runtime snapshot refresh',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-1',
              taskId: 'task-delta-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-delta-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-delta-1',
              taskId: 'task-delta-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsBeforeDelta =
          bridge.loadChatRuntimeSnapshotCallCount;

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-delta-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await openRunTraceFullscreen(tester, runTraceFinder);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-delta-1'),
      );

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          totalLength: 2,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-delta-1',
              taskId: 'task-delta-1',
              emittedAtEpochMs: 1200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsBeforeDelta,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Read', findRichText: true),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('README.md', findRichText: true),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'runtime event deltas refresh the open inspector when only managed process output changes',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-process-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-process-1',
              taskId: 'task-delta-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-delta-process-1',
              managedProcessIds: <String>['proc-delta-process-1'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-delta-process-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1050,
                  updatedAtEpochMs: 1100,
                  stdoutPreview: 'starting dev server',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-delta-process-1',
              taskId: 'task-delta-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsBeforeDelta =
          bridge.loadChatRuntimeSnapshotCallCount;

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-delta-process-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await openRunTraceFullscreen(tester, runTraceFinder);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-delta-process-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'starting dev server',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'ready on http://localhost:3000',
            findRichText: true,
          ),
        ),
        findsNothing,
      );

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 1,
          totalLength: 1,
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-process-1',
              taskId: 'task-delta-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1400,
              attempt: 1,
              pendingMessageId: 'pending-delta-process-1',
              managedProcessIds: <String>['proc-delta-process-1'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-delta-process-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1050,
                  updatedAtEpochMs: 1400,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          updatedAtEpochMs: 1400,
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsBeforeDelta,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'ready on http://localhost:3000',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'runtime event deltas keep newer managed process state when stale patches carry newer events',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-stale-delta-process-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2000,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stale-delta-process-1',
              taskId: 'task-stale-delta-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2000,
              attempt: 1,
              pendingMessageId: 'pending-stale-delta-process-1',
              managedProcessIds: <String>['proc-stale-delta-process-1'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-stale-delta-process-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1050,
                  updatedAtEpochMs: 2000,
                  stdoutPreview: 'fresh server output',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('fresh server output'), findsWidgets);

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 1,
          totalLength: 1,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-stale-delta-process-1',
              taskId: 'task-stale-delta-process-1',
              emittedAtEpochMs: 2200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stale-delta-process-1',
              taskId: 'task-stale-delta-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-stale-delta-process-1',
              managedProcessIds: <String>['proc-stale-delta-process-1'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-stale-delta-process-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1050,
                  updatedAtEpochMs: 1200,
                  stdoutPreview: 'stale server output',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          hasActiveRunsPatch: true,
          updatedAtEpochMs: 1200,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('fresh server output'), findsWidgets);
      expect(find.textContaining('Read', findRichText: true), findsWidgets);
      expect(
        find.textContaining('README.md', findRichText: true),
        findsWidgets,
      );
      expect(find.textContaining('stale server output'), findsNothing);

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 2,
          totalLength: 2,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-stale-delta-process-1',
              taskId: 'task-stale-delta-process-1',
              emittedAtEpochMs: 2300,
              toolName: 'Read',
              contentPreview: 'README loaded.',
            ),
          ],
          activeRuns: <OpenCrayChatRunSnapshot>[],
          hasActiveRunsPatch: true,
          updatedAtEpochMs: 2300,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('fresh server output'), findsWidgets);
      expect(
        find.textContaining('README loaded.', findRichText: true),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'open inspector keeps receiving updates when a run id is added later',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-key-shift-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: '',
              taskId: 'task-key-shift-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-key-shift-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: '',
              taskId: 'task-key-shift-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-task-key-shift-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await openRunTraceFullscreen(tester, runTraceFinder);

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1500,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-key-shift-1',
              taskId: 'task-key-shift-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1500,
              attempt: 1,
              pendingMessageId: 'pending-key-shift-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-key-shift-1',
              taskId: 'task-key-shift-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-key-shift-1',
              taskId: 'task-key-shift-1',
              emittedAtEpochMs: 1500,
              toolName: 'Read',
              contentPreview: 'README body loaded after run id arrived.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining(
          'README body loaded after run id arrived.',
          findRichText: true,
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'open inspector keeps receiving updates when label-only trace gains a task id',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1000,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: '',
              taskId: '',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1000,
              attempt: 1,
              isTerminal: false,
              lastEvent: OpenCrayChatRuntimeEventSnapshot(
                kind: 'lifecycle',
                runId: '',
                taskId: '',
                emittedAtEpochMs: 1000,
                phase: 'start',
              ),
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: '',
              taskId: '',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final runTraceFinder = find.byKey(
        ValueKey<String>('chat-run-trace-${copy.chatRunWorkingLabel}'),
      );
      expect(runTraceFinder, findsOneWidget);
      await openRunTraceFullscreen(tester, runTraceFinder);

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1500,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-label-shift-1',
              taskId: 'task-label-shift-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1500,
              attempt: 1,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-label-shift-1',
              taskId: 'task-label-shift-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-label-shift-1',
              taskId: 'task-label-shift-1',
              emittedAtEpochMs: 1500,
              toolName: 'Read',
              contentPreview:
                  'Label-only inspector received the task id update.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining(
          'Label-only inspector received the task id update.',
          findRichText: true,
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'runtime event deltas ignore totalLength mismatches when sequence is contiguous',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-sequence-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-sequence-1',
              taskId: 'task-delta-sequence-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-delta-sequence-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-delta-sequence-1',
              taskId: 'task-delta-sequence-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsBeforeDelta =
          bridge.loadChatRuntimeSnapshotCallCount;

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 1,
          totalLength: 99,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-delta-sequence-1',
              taskId: 'task-delta-sequence-1',
              emittedAtEpochMs: 1200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsBeforeDelta,
      );
      expect(find.text('Thinking'), findsNothing);
      expect(
        find.byKey(
          const ValueKey<String>('chat-run-trace-run-delta-sequence-1'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('runtime event deltas resync when sequence jumps', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        updatedAtEpochMs: 1000,
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Read the README.',
            createdAtEpochMs: 1000,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-delta-gap-1',
            kind: 'inbound',
            text: 'Thinking',
            createdAtEpochMs: 1100,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 1100,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-delta-gap-1',
            taskId: 'task-delta-gap-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 1100,
            attempt: 1,
            pendingMessageId: 'pending-delta-gap-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-delta-gap-1',
            taskId: 'task-delta-gap-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
        ],
      ),
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final int runtimeSnapshotLoadsBeforeDelta =
        bridge.loadChatRuntimeSnapshotCallCount;

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 1,
        totalLength: 2,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_call',
            runId: 'run-delta-gap-1',
            taskId: 'task-delta-gap-1',
            emittedAtEpochMs: 1200,
            toolName: 'Read',
            argumentsJson: '{"file_path":"README.md"}',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 3,
        totalLength: 3,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-delta-gap-1',
            taskId: 'task-delta-gap-1',
            emittedAtEpochMs: 1300,
            toolName: 'Read',
            contentPreview: 'README body loaded from disk.',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    expect(
      bridge.loadChatRuntimeSnapshotCallCount,
      greaterThan(runtimeSnapshotLoadsBeforeDelta),
    );
  });

  testWidgets(
    'runtime event deltas continue after a resync snapshot is ignored',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-resync-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-resync-1',
              taskId: 'task-delta-resync-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-delta-resync-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-delta-resync-1',
              taskId: 'task-delta-resync-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 1,
          totalLength: 2,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-delta-resync-1',
              taskId: 'task-delta-resync-1',
              emittedAtEpochMs: 1200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 3,
          totalLength: 3,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-delta-resync-1',
              taskId: 'task-delta-resync-1',
              emittedAtEpochMs: 1300,
              toolName: 'Read',
              contentPreview: 'stale delta missed by resync.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsAfterResync =
          bridge.loadChatRuntimeSnapshotCallCount;

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 4,
          totalLength: 4,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-delta-resync-1',
              taskId: 'task-delta-resync-1',
              emittedAtEpochMs: 1400,
              toolName: 'Read',
              contentPreview: 'README body loaded after resync.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsAfterResync,
      );
      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-delta-resync-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await openRunTraceFullscreen(tester, runTraceFinder);
      expect(
        find.descendant(
          of: find.byKey(
            const ValueKey<String>(
              'chat-run-trace-fullscreen-run-delta-resync-1',
            ),
          ),
          matching: find.textContaining(
            'README body loaded after resync.',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets('runtime gap resync replays every queued delta in order', (
    tester,
  ) async {
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-gap-queue-1',
      taskId: 'task-gap-queue-1',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1100,
      attempt: 1,
      pendingMessageId: 'pending-gap-queue-1',
      isTerminal: false,
    );
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-gap-queue-1',
            kind: 'inbound',
            text: 'Thinking',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        streamInstanceId: 'stream-gap-queue-1',
        lastSequence: 1,
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            eventId: 'event-gap-queue-1',
            kind: 'lifecycle',
            runId: 'run-gap-queue-1',
            taskId: 'task-gap-queue-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
        ],
      ),
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
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
    bridge.runtimeSnapshot = const OpenCrayChatRuntimeSnapshot(
      sessionId: 'session-1',
      streamInstanceId: 'stream-gap-queue-1',
      lastSequence: 2,
      activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
      events: <OpenCrayChatRuntimeEventSnapshot>[
        OpenCrayChatRuntimeEventSnapshot(
          eventId: 'event-gap-queue-1',
          kind: 'lifecycle',
          runId: 'run-gap-queue-1',
          taskId: 'task-gap-queue-1',
          emittedAtEpochMs: 1000,
          phase: 'start',
        ),
      ],
    );

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        streamInstanceId: 'stream-gap-queue-1',
        sequence: 3,
        totalLength: 2,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            eventId: 'event-gap-queue-3',
            kind: 'tool_result',
            runId: 'run-gap-queue-1',
            taskId: 'task-gap-queue-1',
            emittedAtEpochMs: 1300,
            toolName: 'Read',
            contentPreview: 'First queued delta survived resync.',
          ),
        ],
      ),
    );
    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        streamInstanceId: 'stream-gap-queue-1',
        sequence: 4,
        totalLength: 3,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            eventId: 'event-gap-queue-4',
            kind: 'tool_result',
            runId: 'run-gap-queue-1',
            taskId: 'task-gap-queue-1',
            emittedAtEpochMs: 1400,
            toolName: 'Read',
            contentPreview: 'Second queued delta survived resync.',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    final runTraceFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-gap-queue-1'),
    );
    expect(runTraceFinder, findsOneWidget);
    await openRunTraceFullscreen(tester, runTraceFinder);
    expect(
      find.textContaining(
        'First queued delta survived resync.',
        findRichText: true,
      ),
      findsWidgets,
    );
    expect(
      find.textContaining(
        'Second queued delta survived resync.',
        findRichText: true,
      ),
      findsWidgets,
    );
  });

  testWidgets('runtime event ids upsert assistant phases without duplicates', (
    tester,
  ) async {
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-event-id-1',
      taskId: 'task-event-id-1',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1100,
      attempt: 1,
      pendingMessageId: 'pending-event-id-1',
      isTerminal: false,
    );
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-event-id-1',
            kind: 'inbound',
            text: 'Thinking',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        streamInstanceId: 'stream-event-id-1',
        lastSequence: 0,
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            eventId: 'assistant-event-id-1',
            kind: 'assistant_phase',
            runId: 'run-event-id-1',
            taskId: 'task-event-id-1',
            emittedAtEpochMs: 1400,
            phase: 'commentary',
            turn: 0,
            stage: 'Planning',
            text: 'Stable phase body.',
          ),
        ],
      ),
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
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

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        streamInstanceId: 'stream-event-id-1',
        sequence: 1,
        totalLength: 1,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            eventId: 'assistant-event-id-1',
            kind: 'assistant_phase',
            runId: 'run-event-id-1',
            taskId: 'task-event-id-1',
            emittedAtEpochMs: 1500,
            phase: 'commentary',
            turn: 0,
            stage: 'Planning',
            text: 'Stable phase body.',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-assistant-commentary-task-event-id-1-0-Planning-1400',
        ),
      ),
      findsNothing,
    );
    expect(
      find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-assistant-commentary-task-event-id-1-0-Planning-1500',
        ),
      ),
      findsOneWidget,
    );
  });

  testWidgets('changing bridges cancels the old realtime subscriptions', (
    tester,
  ) async {
    final oldDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    final newDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(oldDeltas.close);
    addTearDown(newDeltas.close);
    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-bridge-swap-1',
      taskId: 'task-bridge-swap-1',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1100,
      attempt: 1,
      pendingMessageId: 'pending-bridge-swap-1',
      isTerminal: false,
    );
    FakeChatBridge bridgeForStream(
      String streamInstanceId,
      Stream<OpenCrayChatRuntimeEventDelta> stream,
    ) => FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-bridge-swap-1',
            kind: 'inbound',
            text: 'Thinking',
          ),
        ],
      ),
      runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        streamInstanceId: streamInstanceId,
        lastSequence: 0,
        activeRuns: const <OpenCrayChatRunSnapshot>[activeRun],
        events: const <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      runtimeEventDeltaStream: stream,
    );

    final oldBridge = bridgeForStream('stream-bridge-old', oldDeltas.stream);
    final newBridge = bridgeForStream('stream-bridge-new', newDeltas.stream);
    Widget buildHarness(FakeChatBridge bridge) => MaterialApp(
      home: Scaffold(
        body: OpenCrayChatFeature(
          copy: OpenCrayUiCopy.fromLocaleTag('en'),
          bridge: bridge,
        ),
      ),
    );

    await tester.pumpWidget(buildHarness(oldBridge));
    await tester.pumpAndSettle();
    await tester.pumpWidget(buildHarness(newBridge));
    await tester.pumpAndSettle();

    oldDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        streamInstanceId: 'stream-bridge-old',
        sequence: 1,
        totalLength: 1,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            eventId: 'event-old-bridge',
            kind: 'tool_result',
            runId: 'run-bridge-swap-1',
            taskId: 'task-bridge-swap-1',
            emittedAtEpochMs: 1200,
            toolName: 'Read',
            contentPreview: 'Old bridge update must be ignored.',
          ),
        ],
      ),
    );
    newDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        streamInstanceId: 'stream-bridge-new',
        sequence: 1,
        totalLength: 1,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            eventId: 'event-new-bridge',
            kind: 'tool_result',
            runId: 'run-bridge-swap-1',
            taskId: 'task-bridge-swap-1',
            emittedAtEpochMs: 1300,
            toolName: 'Read',
            contentPreview: 'New bridge update is visible.',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    final runTraceFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-bridge-swap-1'),
    );
    await openRunTraceFullscreen(tester, runTraceFinder);
    expect(
      find.textContaining('New bridge update is visible.', findRichText: true),
      findsWidgets,
    );
    expect(
      find.textContaining(
        'Old bridge update must be ignored.',
        findRichText: true,
      ),
      findsNothing,
    );
    expect(newBridge.loadChatRuntimeSnapshotCallCount, greaterThan(0));
  });

  testWidgets('live drafts are isolated by execution identity', (tester) async {
    final draftEvents =
        StreamController<OpenCrayChatLiveAssistantDraftEvent>.broadcast();
    addTearDown(draftEvents.close);
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-execution-draft-1',
            kind: 'inbound',
            text: 'Thinking',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        streamInstanceId: 'stream-execution-draft-1',
        lastSequence: 0,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-execution-draft-1',
            taskId: 'task-execution-draft-1',
            executionId: 'execution-new',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 1100,
            attempt: 2,
            pendingMessageId: 'pending-execution-draft-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      liveAssistantDraftEventStream: draftEvents.stream,
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

    draftEvents.add(
      const OpenCrayChatLiveAssistantDraftEvent(
        sessionId: 'session-1',
        streamInstanceId: 'stream-execution-draft-1',
        sequence: 1,
        runId: 'run-execution-draft-1',
        taskId: 'task-execution-draft-1',
        executionId: 'execution-new',
        pendingMessageId: 'pending-execution-draft-1',
        text: 'New execution draft stays visible.',
        updatedAtEpochMs: 1200,
      ),
    );
    draftEvents.add(
      const OpenCrayChatLiveAssistantDraftEvent(
        sessionId: 'session-1',
        streamInstanceId: 'stream-execution-draft-1',
        sequence: 2,
        runId: 'run-execution-draft-1',
        taskId: 'task-execution-draft-1',
        executionId: 'execution-old',
        pendingMessageId: 'pending-execution-draft-1',
        text: 'Old execution draft must stay hidden.',
        updatedAtEpochMs: 1300,
      ),
    );
    draftEvents.add(
      const OpenCrayChatLiveAssistantDraftEvent(
        sessionId: 'session-1',
        streamInstanceId: 'stream-execution-draft-1',
        sequence: 3,
        runId: 'run-execution-draft-1',
        taskId: 'task-execution-draft-1',
        executionId: 'execution-old',
        pendingMessageId: 'pending-execution-draft-1',
        text: '',
        updatedAtEpochMs: 1400,
        cleared: true,
      ),
    );
    await tester.pumpAndSettle();

    expect(findStreamedText('New execution draft stays visible.'), findsOneWidget);
    expect(find.text('Old execution draft must stay hidden.'), findsNothing);
  });

  testWidgets('resync retries after a transient runtime snapshot failure', (
    tester,
  ) async {
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-resync-retry-1',
      taskId: 'task-resync-retry-1',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1100,
      attempt: 1,
      pendingMessageId: 'pending-resync-retry-1',
      isTerminal: false,
    );
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-resync-retry-1',
            kind: 'inbound',
            text: 'Thinking',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        streamInstanceId: 'stream-resync-retry-1',
        lastSequence: 1,
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
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
    final int initialLoads = bridge.loadChatRuntimeSnapshotCallCount;
    bridge.loadChatRuntimeSnapshotError = StateError('temporary failure');
    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        streamInstanceId: 'stream-resync-retry-1',
        sequence: 3,
        totalLength: 1,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            eventId: 'event-resync-retry-3',
            kind: 'tool_result',
            runId: 'run-resync-retry-1',
            taskId: 'task-resync-retry-1',
            emittedAtEpochMs: 1300,
            toolName: 'Read',
            contentPreview: 'Recovered after retry.',
          ),
        ],
      ),
    );
    await tester.pump();
    expect(bridge.loadChatRuntimeSnapshotCallCount, greaterThan(initialLoads));
    bridge.loadChatRuntimeSnapshotError = null;
    bridge.runtimeSnapshot = const OpenCrayChatRuntimeSnapshot(
      sessionId: 'session-1',
      streamInstanceId: 'stream-resync-retry-1',
      lastSequence: 2,
      activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
      events: <OpenCrayChatRuntimeEventSnapshot>[],
    );
    await tester.pump(const Duration(milliseconds: 350));
    await tester.pumpAndSettle();
    final runTraceFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-resync-retry-1'),
    );
    await openRunTraceFullscreen(tester, runTraceFinder);
    expect(
      find.textContaining('Recovered after retry.', findRichText: true),
      findsWidgets,
    );
  });

  testWidgets(
    'runtime event deltas create run traces without a runtime snapshot refresh',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-create-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsBeforeDelta =
          bridge.loadChatRuntimeSnapshotCallCount;

      const runTraceFinder = ValueKey<String>(
        'chat-run-trace-run-delta-create-1',
      );
      expect(find.byKey(runTraceFinder), findsNothing);

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          totalLength: 1,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-create-1',
              taskId: 'task-delta-create-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-delta-create-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-delta-create-1',
              taskId: 'task-delta-create-1',
              emittedAtEpochMs: 1200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsBeforeDelta,
      );
      final runTrace = find.byKey(runTraceFinder);
      expect(runTrace, findsOneWidget);
      expect(find.text('Thinking'), findsNothing);
      await openRunTraceFullscreen(tester, runTrace);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-delta-create-1'),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('README.md', findRichText: true),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'runtime event deltas update grouped inspector entries without a runtime snapshot refresh',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-grouped-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-grouped-1',
              taskId: 'task-delta-grouped-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-delta-grouped-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-delta-grouped-1',
              taskId: 'task-delta-grouped-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-delta-grouped-1',
              taskId: 'task-delta-grouped-1',
              emittedAtEpochMs: 1200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsBeforeDelta =
          bridge.loadChatRuntimeSnapshotCallCount;

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-delta-grouped-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await openRunTraceFullscreen(tester, runTraceFinder);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-delta-grouped-1'),
      );

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          totalLength: 3,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-delta-grouped-1',
              taskId: 'task-delta-grouped-1',
              emittedAtEpochMs: 1300,
              toolName: 'Read',
              contentPreview: 'README body loaded from disk.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsBeforeDelta,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'README body loaded from disk.',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'live draft events keep projected process bubbles and terminal process status',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      final chatSnapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      final draftEvents =
          StreamController<OpenCrayChatLiveAssistantDraftEvent>.broadcast();
      addTearDown(runtimeSnapshots.close);
      addTearDown(chatSnapshots.close);
      addTearDown(draftEvents.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-live-process-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-live-process-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        chatSnapshotStream: chatSnapshots.stream,
        runtimeSnapshotStream: runtimeSnapshots.stream,
        liveAssistantDraftEventStream: draftEvents.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-live-process-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await openRunTraceFullscreen(tester, runTraceFinder);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-live-process-1'),
      );

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2600,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2600,
              attempt: 1,
              pendingMessageId: 'pending-live-process-1',
              managedProcessIds: <String>['proc-live-process'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-live-process',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 2600,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              emittedAtEpochMs: 1700,
              toolName: 'Bash',
              argumentsJson: '{"cmd":"npm run dev"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-live-process'), findsOneWidget);
      expect(
        find.textContaining('ready on http://localhost:3000'),
        findsWidgets,
      );
      expect(fullscreenFinder, findsOneWidget);
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Bash', findRichText: true),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.text('Process proc-live-process', findRichText: true),
        ),
        findsOneWidget,
      );
      final liveProcessBubbleFinder = find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-process-task-live-process-1-proc-live-process',
        ),
      );
      final liveDraftBubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-pending-live-process-1'),
      );
      expect(liveProcessBubbleFinder, findsOneWidget);
      expect(liveDraftBubbleFinder, findsNothing);

      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-live-process-1',
          taskId: 'task-live-process-1',
          pendingMessageId: 'pending-live-process-1',
          text: 'The dev server is ready; I am checking the result.',
          updatedAtEpochMs: 3200,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-live-process'), findsOneWidget);
      expect(
        findStreamedText('The dev server is ready; I am checking the result.'),
        findsOneWidget,
      );
      expect(liveProcessBubbleFinder, findsOneWidget);
      expect(liveDraftBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(liveProcessBubbleFinder).dy,
        lessThan(tester.getTopLeft(liveDraftBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.text('Process proc-live-process', findRichText: true),
        ),
        findsOneWidget,
      );

      chatSnapshots.add(
        hostChatSnapshot(
          updatedAtEpochMs: 3600,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-live-process-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-live-process'), findsOneWidget);
      expect(
        findStreamedText('The dev server is ready; I am checking the result.'),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Bash', findRichText: true),
        ),
        findsWidgets,
      );

      const terminalToolResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-live-process-1',
        taskId: 'task-live-process-1',
        emittedAtEpochMs: 4100,
        toolName: 'Bash',
        contentPreview: 'server finished successfully',
      );
      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 4200,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          retainedRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 4200,
              attempt: 1,
              pendingMessageId: 'pending-live-process-1',
              managedProcessIds: <String>['proc-live-process'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-live-process',
                  status: 'success',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 4200,
                  finishedAtEpochMs: 4200,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 0,
              hasLiveManagedProcesses: false,
              isTerminal: true,
              lastEvent: terminalToolResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            terminalToolResult,
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.descendant(of: runTraceFinder, matching: find.text('RUNNING')),
        findsNothing,
      );
      expect(
        find.descendant(of: runTraceFinder, matching: find.text('FINISHED')),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('status: finished', findRichText: true),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'runtime patches existing projected process bubbles and anchors the status line above the agent group',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'user-runtime-anchor-1',
              kind: 'outbound',
              text: 'Start the preview server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'runtime-process-task-runtime-anchor-1-proc-1',
              kind: 'inbound',
              text: 'Process proc-1\n\nrunning: npm run dev\n\nstale output',
              createdAtEpochMs: 1200,
              isEphemeral: true,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-runtime-anchor-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1300,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1700,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-runtime-anchor-1',
              taskId: 'task-runtime-anchor-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1700,
              attempt: 1,
              pendingMessageId: 'pending-runtime-anchor-1',
              managedProcessIds: <String>['proc-1'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1200,
                  updatedAtEpochMs: 1700,
                  stdoutPreview: 'fresh output from runtime',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-runtime-anchor-1',
              taskId: 'task-runtime-anchor-1',
              emittedAtEpochMs: 1400,
              toolName: 'WebFetch',
              contentPreview: 'Fetched page content.',
            ),
          ],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-runtime-anchor-1',
              taskId: 'task-runtime-anchor-1',
              pendingMessageId: 'pending-runtime-anchor-1',
              text: 'The preview server is running.',
              updatedAtEpochMs: 1700,
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

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-runtime-anchor-1'),
      );
      final processBubbleFinder = find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-process-task-runtime-anchor-1-proc-1',
        ),
      );
      final draftBubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-pending-runtime-anchor-1'),
      );

      expect(runTraceFinder, findsOneWidget);
      expect(processBubbleFinder, findsOneWidget);
      expect(draftBubbleFinder, findsOneWidget);
      expect(find.text('WRITING REPLY'), findsOneWidget);
      expect(find.textContaining('fresh output from runtime'), findsWidgets);
      expect(find.textContaining('stale output'), findsNothing);

      expect(
        tester.getTopLeft(runTraceFinder).dy,
        lessThan(tester.getTopLeft(processBubbleFinder).dy),
      );
      expect(
        tester.getTopLeft(processBubbleFinder).dy,
        lessThan(tester.getTopLeft(draftBubbleFinder).dy),
      );
    },
  );

  testWidgets(
    'runtime process aliases patch existing run-keyed host bubbles without duplication',
    (tester) async {
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-process-alias-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'runtime-process-run-process-alias-1-proc-alias-1',
              kind: 'inbound',
              text: 'Process proc-alias-1\n\nrunning: npm run dev\n\nstale',
              createdAtEpochMs: 1100,
              isEphemeral: true,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-process-alias-1',
              taskId: 'task-process-alias-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1300,
              attempt: 1,
              pendingMessageId: 'pending-process-alias-1',
              isTerminal: false,
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-alias-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  processStarted: true,
                  startedAtEpochMs: 1100,
                  updatedAtEpochMs: 1300,
                  stdoutPreview: 'fresh process output',
                ),
              ],
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          updatedAtEpochMs: 1300,
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
        find.byKey(
          const ValueKey<String>(
            'chat-bubble-runtime-process-run-process-alias-1-proc-alias-1',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-bubble-runtime-process-task-process-alias-1-proc-alias-1',
          ),
        ),
        findsNothing,
      );
      expect(find.textContaining('fresh process output'), findsWidgets);
      expect(find.textContaining('stale'), findsNothing);
    },
  );

  testWidgets(
    'runtime assistant phase aliases patch existing host bubbles without duplication',
    (tester) async {
      const assistantPhase = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-assistant-alias-1',
        taskId: 'task-assistant-alias-1',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        isFinal: false,
        turn: 0,
        stage: 'Planning',
        text: 'Fresh assistant phase text.',
      );
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId:
                  'runtime-assistant-commentary-run-assistant-alias-1-2200',
              kind: 'inbound',
              text: 'Planning\n\nStale assistant phase text.',
              createdAtEpochMs: 2200,
              isEphemeral: true,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-assistant-alias-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 2300,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-assistant-alias-1',
              taskId: 'task-assistant-alias-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2300,
              attempt: 1,
              pendingMessageId: 'pending-assistant-alias-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[assistantPhase],
          updatedAtEpochMs: 2300,
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
        find.byKey(
          const ValueKey<String>(
            'chat-bubble-runtime-assistant-commentary-run-assistant-alias-1-2200',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-bubble-runtime-assistant-commentary-task-assistant-alias-1-0-Planning-2200',
          ),
        ),
        findsNothing,
      );
      expect(find.textContaining('Fresh assistant phase text.'), findsWidgets);
      expect(find.textContaining('Stale assistant phase text.'), findsNothing);
    },
  );

  testWidgets(
    'no-turn assistant phase aliases patch legacy host bubbles without duplication',
    (tester) async {
      const assistantPhase = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-assistant-no-turn-alias-1',
        taskId: 'task-assistant-no-turn-alias-1',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        isFinal: false,
        stage: 'Planning',
        text: 'Fresh no-turn assistant phase text.',
      );
      final String hashedMessageId =
          'runtime-assistant-commentary-task-assistant-no-turn-alias-1--1-Planning-2200-${javaStringHashCode('Fresh no-turn assistant phase text.')}';
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId:
                  'runtime-assistant-commentary-task-assistant-no-turn-alias-1--1-Planning-2200',
              kind: 'inbound',
              text: 'Planning\n\nStale no-turn assistant phase text.',
              createdAtEpochMs: 2200,
              isEphemeral: true,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-assistant-no-turn-alias-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 2300,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-assistant-no-turn-alias-1',
              taskId: 'task-assistant-no-turn-alias-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2300,
              attempt: 1,
              pendingMessageId: 'pending-assistant-no-turn-alias-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[assistantPhase],
          updatedAtEpochMs: 2300,
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
        find.byKey(
          const ValueKey<String>(
            'chat-bubble-runtime-assistant-commentary-task-assistant-no-turn-alias-1--1-Planning-2200',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(ValueKey<String>('chat-bubble-$hashedMessageId')),
        findsNothing,
      );
      expect(
        find.textContaining('Fresh no-turn assistant phase text.'),
        findsWidgets,
      );
      expect(
        find.textContaining('Stale no-turn assistant phase text.'),
        findsNothing,
      );
    },
  );

  testWidgets('assistant phase deltas update the same bubble identity', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-assistant-update-1',
      taskId: 'task-assistant-update-1',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1200,
      attempt: 1,
      pendingMessageId: 'pending-assistant-update-1',
      isTerminal: false,
    );
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Inspect the workspace.',
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-assistant-update-1',
            kind: 'inbound',
            text: 'Thinking',
            createdAtEpochMs: 1000,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'assistant_phase',
            runId: 'run-assistant-update-1',
            taskId: 'task-assistant-update-1',
            emittedAtEpochMs: 1400,
            phase: 'commentary',
            isFinal: false,
            turn: 0,
            stage: 'Planning',
            text: 'Planning first chunk.',
          ),
        ],
        updatedAtEpochMs: 1400,
      ),
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
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
      const ValueKey<String>(
        'chat-bubble-runtime-assistant-commentary-task-assistant-update-1-0-Planning-1400',
      ),
    );
    expect(bubbleFinder, findsOneWidget);
    expect(find.textContaining('Planning first chunk.'), findsWidgets);

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 1,
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'assistant_phase',
            runId: 'run-assistant-update-1',
            taskId: 'task-assistant-update-1',
            emittedAtEpochMs: 1400,
            phase: 'commentary',
            isFinal: false,
            turn: 0,
            stage: 'Planning',
            text: 'Planning first chunk and more.',
          ),
        ],
        totalLength: 1,
        hasActiveRunsPatch: true,
        updatedAtEpochMs: 1400,
      ),
    );
    await tester.pumpAndSettle();

    expect(bubbleFinder, findsOneWidget);
    expect(find.textContaining('Planning first chunk and more.'), findsWidgets);
    expect(find.textContaining('Planning first chunk.'), findsNothing);
  });

  testWidgets(
    'host projected process bubbles anchor the status line even before runtime patches arrive',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'user-runtime-host-process-1',
              kind: 'outbound',
              text: 'Start the preview server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'runtime-process-task-runtime-host-process-1-proc-1',
              kind: 'inbound',
              text: 'Process proc-1\n\nrunning: npm run dev',
              createdAtEpochMs: 1200,
              isEphemeral: true,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-runtime-host-process-1',
              kind: 'inbound',
              text: 'The server is starting.',
              createdAtEpochMs: 1300,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1700,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-runtime-host-process-1',
              taskId: 'task-runtime-host-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1700,
              attempt: 1,
              pendingMessageId: 'pending-runtime-host-process-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-runtime-host-process-1',
              taskId: 'task-runtime-host-process-1',
              emittedAtEpochMs: 1400,
              toolName: 'Bash',
              argumentsJson: '{"cmd":"npm run dev"}',
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

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-runtime-host-process-1'),
      );
      final processBubbleFinder = find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-process-task-runtime-host-process-1-proc-1',
        ),
      );
      final finalBubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-pending-runtime-host-process-1'),
      );

      expect(runTraceFinder, findsOneWidget);
      expect(processBubbleFinder, findsOneWidget);
      expect(finalBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(runTraceFinder).dy,
        lessThan(tester.getTopLeft(processBubbleFinder).dy),
      );
      expect(
        tester.getTopLeft(processBubbleFinder).dy,
        lessThan(tester.getTopLeft(finalBubbleFinder).dy),
      );
    },
  );

  testWidgets(
    'streamed assistant snapshots keep process bubbles and update the open inspector',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      final chatSnapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      addTearDown(chatSnapshots.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-stream-process-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-stream-process-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        chatSnapshotStream: chatSnapshots.stream,
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-stream-process-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await openRunTraceFullscreen(tester, runTraceFinder);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-stream-process-1',
        ),
      );

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              pendingMessageId: 'pending-stream-process-1',
              managedProcessIds: <String>['proc-stream-process'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-stream-process',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 2200,
                  stdoutPreview: 'alpha output',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1700,
              toolName: 'Bash',
              argumentsJson: '{"cmd":"npm run dev"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream-process'), findsOneWidget);
      expect(find.textContaining('alpha output'), findsWidgets);
      final streamProcessBubbleFinder = find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-process-task-stream-process-1-proc-stream-process',
        ),
      );
      final streamDraftBubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-pending-stream-process-1'),
      );
      expect(streamProcessBubbleFinder, findsOneWidget);
      expect(streamDraftBubbleFinder, findsNothing);
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Bash', findRichText: true),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('alpha output', findRichText: true),
        ),
        findsOneWidget,
      );

      chatSnapshots.add(
        hostChatSnapshot(
          updatedAtEpochMs: 2600,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-stream-process-1',
              kind: 'inbound',
              text: 'The server process is up; I am checking the result.',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream-process'), findsOneWidget);
      expect(
        find.text('The server process is up; I am checking the result.'),
        findsOneWidget,
      );
      expect(find.textContaining('alpha output'), findsWidgets);
      expect(streamProcessBubbleFinder, findsOneWidget);
      expect(streamDraftBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(streamProcessBubbleFinder).dy,
        lessThan(tester.getTopLeft(streamDraftBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('alpha output', findRichText: true),
        ),
        findsOneWidget,
      );

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              pendingMessageId: 'pending-stream-process-1',
              managedProcessIds: <String>['proc-stream-process'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-stream-process',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 2200,
                  stdoutPreview: 'bravo output',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1700,
              toolName: 'Bash',
              argumentsJson: '{"cmd":"npm run dev"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream-process'), findsOneWidget);
      expect(find.textContaining('alpha output'), findsNothing);
      expect(find.textContaining('bravo output'), findsWidgets);
      expect(streamProcessBubbleFinder, findsOneWidget);
      expect(streamDraftBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(streamProcessBubbleFinder).dy,
        lessThan(tester.getTopLeft(streamDraftBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('bravo output', findRichText: true),
        ),
        findsOneWidget,
      );

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 3200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3200,
              attempt: 1,
              pendingMessageId: 'pending-stream-process-1',
              managedProcessIds: <String>['proc-stream-process'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-stream-process',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 3200,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1700,
              toolName: 'Bash',
              argumentsJson: '{"cmd":"npm run dev"}',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 3100,
              toolName: 'Bash',
              contentPreview: 'background process is still running',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream-process'), findsOneWidget);
      expect(find.textContaining('bravo output'), findsNothing);
      expect(
        find.textContaining('ready on http://localhost:3000'),
        findsWidgets,
      );
      expect(streamProcessBubbleFinder, findsOneWidget);
      expect(streamDraftBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(streamProcessBubbleFinder).dy,
        lessThan(tester.getTopLeft(streamDraftBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'ready on http://localhost:3000',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'background process is still running',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );

      chatSnapshots.add(
        hostChatSnapshot(
          updatedAtEpochMs: 3600,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-stream-process-1',
              kind: 'inbound',
              text: 'The server process is up and the preview is reachable.',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream-process'), findsOneWidget);
      expect(
        find.text('The server process is up and the preview is reachable.'),
        findsOneWidget,
      );
      expect(
        find.textContaining('ready on http://localhost:3000'),
        findsWidgets,
      );
      expect(streamProcessBubbleFinder, findsOneWidget);
      expect(streamDraftBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(streamProcessBubbleFinder).dy,
        lessThan(tester.getTopLeft(streamDraftBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'ready on http://localhost:3000',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'live assistant drafts do not overwrite a settled assistant reply',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a long summary.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'The final answer is ready.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-1',
              taskId: 'task-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-1',
              taskId: 'task-1',
              pendingMessageId: 'pending-1',
              text: 'Streaming answer in progress',
              updatedAtEpochMs: 1300,
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

      final settledBubble = find.byKey(
        const ValueKey<String>('chat-bubble-pending-1'),
      );
      expect(settledBubble, findsOneWidget);
      expect(
        find.descendant(
          of: settledBubble,
          matching: find.text('The final answer is ready.'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: settledBubble,
          matching: findStreamedText('Streaming answer in progress'),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'live assistant drafts do not surface tool call payloads as chat bubbles',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-1',
              taskId: 'task-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-1',
              taskId: 'task-1',
              pendingMessageId: 'pending-1',
              text:
                  '{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}',
              updatedAtEpochMs: 1300,
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

      expect(find.textContaining('"tool_name"'), findsNothing);
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-pending-1')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'projected assistant phases suppress competing live drafts while runtime is ahead',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
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
              pendingMessageId: 'pending-1',
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'assistant_phase',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 2200,
              phase: 'commentary',
              isFinal: false,
              turn: 0,
              stage: 'Planning',
              text:
                  'Scanning README and Gradle files before choosing the next tool.',
            ),
          ],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              pendingMessageId: 'pending-1',
              text: 'Streaming answer in progress',
              updatedAtEpochMs: 2300,
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

      final phaseFinder = find.descendant(
        of: find.byWidgetPredicate((widget) {
          final Key? key = widget.key;
          return key is ValueKey<String> &&
              key.value.startsWith('chat-bubble-') &&
              key.value != 'chat-bubble-pending-1';
        }),
        matching: find.textContaining(
          'Scanning README and Gradle files before choosing the next tool.',
        ),
      );
      final pendingFinder = find.descendant(
        of: find.byKey(const ValueKey<String>('chat-bubble-pending-1')),
        matching: findStreamedText('Streaming answer in progress'),
      );

      expect(phaseFinder, findsOneWidget);
      expect(pendingFinder, findsNothing);
    },
  );

  testWidgets(
    'projected assistant phases stay visible when the run becomes retained before chat catches up',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
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
              pendingMessageId: 'pending-1',
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'assistant_phase',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 2200,
              phase: 'commentary',
              isFinal: false,
              turn: 0,
              stage: 'Planning',
              text:
                  'Scanning README and Gradle files before choosing the next tool.',
            ),
          ],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
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
        find.descendant(
          of: find.byWidgetPredicate((widget) {
            final Key? key = widget.key;
            return key is ValueKey<String> &&
                key.value.startsWith('chat-bubble-');
          }),
          matching: find.textContaining(
            'Scanning README and Gradle files before choosing the next tool.',
          ),
        ),
        findsOneWidget,
      );

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          retainedRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2400,
              attempt: 1,
              isTerminal: false,
              pendingMessageId: 'pending-1',
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'assistant_phase',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 2200,
              phase: 'commentary',
              isFinal: false,
              turn: 0,
              stage: 'Planning',
              text:
                  'Scanning README and Gradle files before choosing the next tool.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.descendant(
          of: find.byWidgetPredicate((widget) {
            final Key? key = widget.key;
            return key is ValueKey<String> &&
                key.value.startsWith('chat-bubble-');
          }),
          matching: find.textContaining(
            'Scanning README and Gradle files before choosing the next tool.',
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'persisted assistant phases keep their own bubble when a later phase arrives',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const firstEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-progress-2',
        taskId: 'task-progress-2',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        isFinal: false,
        turn: 0,
        stage: 'Planning',
        text: 'Scanning README and Gradle files before choosing the next tool.',
      );
      const secondEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-progress-2',
        taskId: 'task-progress-2',
        emittedAtEpochMs: 2300,
        phase: 'commentary',
        isFinal: false,
        turn: 1,
        stage: 'Planning',
        text: 'Checking the tests after the first pass.',
      );
      final String firstMessageId =
          'runtime-assistant-commentary-run-progress-2-0-Planning-2200';
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: <OpenCrayChatMessageSnapshot>[
            const OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: firstMessageId,
              kind: 'inbound',
              text:
                  'Planning\n\nScanning README and Gradle files before choosing the next tool.',
              isEphemeral: true,
            ),
            const OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-2',
              taskId: 'task-progress-2',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2300,
              attempt: 1,
              isTerminal: false,
              pendingMessageId: 'pending-1',
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[firstEvent, secondEvent],
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

      final bubbleScope = find.byWidgetPredicate((widget) {
        final Key? key = widget.key;
        return key is ValueKey<String> &&
            key.value.startsWith('chat-bubble-') &&
            key.value != 'chat-bubble-pending-1';
      });
      final firstBubbleText = find.descendant(
        of: bubbleScope,
        matching: find.textContaining(
          'Scanning README and Gradle files before choosing the next tool.',
        ),
      );
      final secondBubbleText = find.descendant(
        of: bubbleScope,
        matching: find.textContaining(
          'Checking the tests after the first pass.',
        ),
      );

      expect(firstBubbleText, findsOneWidget);
      expect(secondBubbleText, findsOneWidget);
      expect(
        tester.getTopLeft(firstBubbleText).dy,
        lessThan(tester.getTopLeft(secondBubbleText).dy),
      );
    },
  );

  testWidgets(
    'host-backed session selection accepts runtime deltas before chat snapshot ack',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'message-session-old-delta',
              kind: 'inbound',
              text: 'Old selected session text',
              createdAtEpochMs: 1000,
            ),
          ],
          drawer: const OpenCrayChatDrawerSnapshot(
            eyebrow: 'Recent sessions',
            title: 'Recent sessions',
            ctaLabel: 'New session',
            sessions: <OpenCrayChatSessionItemSnapshot>[
              OpenCrayChatSessionItemSnapshot(
                sessionId: 'session-old',
                title: 'Old session',
                preview: 'Currently selected',
                meta: '1 message',
                isSelected: true,
              ),
              OpenCrayChatSessionItemSnapshot(
                sessionId: 'session-next',
                title: 'Next session',
                preview: 'Switch here',
                meta: '2 messages',
                isSelected: false,
              ),
            ],
          ),
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-old',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(chatSessionsButton());
      await tester.pumpAndSettle();
      await tester.tap(find.text('Next session'));
      await tester.pump();

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-next',
          sequence: 1,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-next',
              runId: 'run-session-next-delta',
              taskId: 'task-session-next-delta',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1500,
              attempt: 1,
              pendingMessageId: 'pending-session-next-delta',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-session-next-delta',
              taskId: 'task-session-next-delta',
              emittedAtEpochMs: 1500,
              toolName: 'Read',
              contentPreview: 'New session runtime delta arrived first.',
            ),
          ],
          totalLength: 1,
          hasActiveRunsPatch: true,
          updatedAtEpochMs: 1500,
        ),
      );
      await tester.pumpAndSettle();

      expect(bridge.selectedSessionIds, <String>['session-next']);
      expect(find.text('Old selected session text'), findsNothing);
      expect(
        find.byKey(
          const ValueKey<String>('chat-run-trace-run-session-next-delta'),
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining('New session runtime delta arrived first.'),
        findsWidgets,
      );
    },
  );

  testWidgets('runtime delta before chat snapshot is cached and projected', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final chatSnapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(chatSnapshots.close);
    addTearDown(runtimeEventDeltas.close);

    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-before-chat-snapshot',
      taskId: 'task-before-chat-snapshot',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1500,
      attempt: 1,
      pendingMessageId: 'pending-before-chat-snapshot',
      isTerminal: false,
    );
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-before-chat-snapshot',
            kind: 'inbound',
            text: 'Inspect startup state.',
            createdAtEpochMs: 1000,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        updatedAtEpochMs: 1500,
      ),
      chatSnapshotStream: chatSnapshots.stream,
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
    );
    bridge.loadChatSnapshotCompleter = Completer<OpenCrayChatSnapshot>();

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pump();

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 1,
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-before-chat-snapshot',
            taskId: 'task-before-chat-snapshot',
            emittedAtEpochMs: 1500,
            toolName: 'Read',
            contentPreview: 'Runtime arrived before chat snapshot.',
          ),
        ],
        totalLength: 1,
        hasActiveRunsPatch: true,
        updatedAtEpochMs: 1500,
      ),
    );
    await tester.pump();

    expect(
      find.textContaining('Runtime arrived before chat snapshot.'),
      findsNothing,
    );

    bridge.loadChatSnapshotCompleter!.complete(bridge.chatSnapshot);
    chatSnapshots.add(bridge.chatSnapshot);
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>('chat-run-trace-run-before-chat-snapshot'),
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Runtime arrived before chat snapshot.'),
      findsWidgets,
    );
  });
}
