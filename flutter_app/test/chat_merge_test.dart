import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

void main() {
  test(
    'resolveChatRuntimeSnapshot merges process details with newer drafts',
    () {
      const embedded = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 6000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-merge-process-1',
            taskId: 'task-merge-process-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2200,
            attempt: 1,
            pendingMessageId: 'pending-merge-process-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
          OpenCrayChatLiveAssistantDraftSnapshot(
            runId: 'run-merge-process-1',
            taskId: 'task-merge-process-1',
            pendingMessageId: 'pending-merge-process-1',
            text: 'Newer final draft',
            updatedAtEpochMs: 6000,
          ),
        ],
      );
      const streamed = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 3000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-merge-process-1',
            taskId: 'task-merge-process-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            pendingMessageId: 'pending-merge-process-1',
            managedProcessIds: <String>['proc-merge'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-merge',
                status: 'running',
                command: 'npm',
                args: <String>['run', 'dev'],
                processStarted: true,
                startedAtEpochMs: 2400,
                updatedAtEpochMs: 3000,
                stdoutPreview: 'ready on http://localhost:3000',
              ),
            ],
            runningManagedProcessCount: 1,
            hasLiveManagedProcesses: true,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

      final resolved = resolveChatRuntimeSnapshot(embedded, streamed);

      expect(resolved!.liveAssistantDrafts.single.text, 'Newer final draft');
      expect(
        resolved.activeRuns.single.managedProcesses.single.processId,
        'proc-merge',
      );
    },
  );

  test(
    'resolveChatRuntimeSnapshot merges runs by stable task id when run id drifts',
    () {
      const embedded = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-stale-task-stable',
            taskId: 'task-stable-merge',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            pendingMessageId: 'pending-stable-merge',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      const streamed = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 3000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-live-task-stable',
            taskId: 'task-stable-merge',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            pendingMessageId: 'pending-stable-merge',
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-stable-merge',
                status: 'running',
                command: 'npm',
                args: <String>['run', 'dev'],
                processStarted: true,
                startedAtEpochMs: 2500,
                updatedAtEpochMs: 3000,
                stdoutPreview: 'streaming output',
              ),
            ],
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

      final resolved = resolveChatRuntimeSnapshot(embedded, streamed);

      expect(resolved!.activeRuns, hasLength(1));
      expect(resolved.activeRuns.single.taskId, 'task-stable-merge');
      expect(resolved.activeRuns.single.runId, 'run-live-task-stable');
      expect(
        resolved.activeRuns.single.managedProcesses.single.processId,
        'proc-stable-merge',
      );
    },
  );

  test(
    'resolveChatRuntimeSnapshot preserves process details when terminal update is thin',
    () {
      const running = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-terminal-thin-1',
            taskId: 'task-terminal-thin-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 5000,
            attempt: 1,
            pendingMessageId: 'pending-terminal-thin-1',
            managedProcessIds: <String>['proc-terminal-thin'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-terminal-thin',
                status: 'running',
                command: 'npm',
                args: <String>['run', 'dev'],
                processStarted: true,
                startedAtEpochMs: 2400,
                updatedAtEpochMs: 5000,
                stdoutPreview: 'ready on http://localhost:3000',
              ),
            ],
            runningManagedProcessCount: 1,
            hasLiveManagedProcesses: true,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      const terminal = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        retainedRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-terminal-thin-1',
            taskId: 'task-terminal-thin-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 5000,
            attempt: 1,
            pendingMessageId: 'pending-terminal-thin-1',
            isTerminal: true,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

      final resolved = resolveChatRuntimeSnapshot(running, terminal);

      expect(resolved!.activeRuns, isEmpty);
      expect(resolved.retainedRuns.single.isTerminal, isTrue);
      expect(resolved.retainedRuns.single.runningManagedProcessCount, 0);
      expect(resolved.retainedRuns.single.hasLiveManagedProcesses, isFalse);
      expect(
        resolved.retainedRuns.single.managedProcesses.single.processId,
        'proc-terminal-thin',
      );
    },
  );

  test(
    'resolveChatRuntimeSnapshot preserves richer event details when timestamps tie',
    () {
      const rich = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-rich-event-1',
            taskId: 'task-rich-event-1',
            emittedAtEpochMs: 5000,
            toolName: 'Read',
            content: 'full file contents',
            contentPreview: 'full file',
          ),
        ],
      );
      const thin = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-rich-event-1',
            taskId: 'task-rich-event-1',
            emittedAtEpochMs: 5000,
            toolName: 'Read',
          ),
        ],
      );

      final resolved = resolveChatRuntimeSnapshot(rich, thin);

      expect(resolved!.events.single.content, 'full file contents');
      expect(resolved.events.single.contentPreview, 'full file');
    },
  );

  testWidgets(
    'runtime merge deltas update one run without clearing sibling runs',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      const firstRun = OpenCrayChatRunSnapshot(
        sessionId: 'session-1',
        runId: 'run-merge-delta-1',
        taskId: 'task-merge-delta-1',
        acceptedAtEpochMs: 1000,
        updatedAtEpochMs: 1200,
        attempt: 1,
        pendingMessageId: 'pending-merge-delta-1',
        managedProcessIds: <String>['proc-merge-delta-1'],
        managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
          OpenCrayChatManagedProcessSnapshot(
            processId: 'proc-merge-delta-1',
            status: 'running',
            command: 'npm',
            args: <String>['run', 'dev'],
            processStarted: true,
            startedAtEpochMs: 1100,
            updatedAtEpochMs: 1200,
            stdoutPreview: 'first run old output',
          ),
        ],
        runningManagedProcessCount: 1,
        hasLiveManagedProcesses: true,
        isTerminal: false,
      );
      const secondRun = OpenCrayChatRunSnapshot(
        sessionId: 'session-1',
        runId: 'run-merge-delta-2',
        taskId: 'task-merge-delta-2',
        acceptedAtEpochMs: 1300,
        updatedAtEpochMs: 1400,
        attempt: 1,
        pendingMessageId: 'pending-merge-delta-2',
        managedProcessIds: <String>['proc-merge-delta-2'],
        managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
          OpenCrayChatManagedProcessSnapshot(
            processId: 'proc-merge-delta-2',
            status: 'running',
            command: 'python',
            args: <String>['worker.py'],
            processStarted: true,
            startedAtEpochMs: 1350,
            updatedAtEpochMs: 1400,
            stdoutPreview: 'second run stays visible',
          ),
        ],
        runningManagedProcessCount: 1,
        hasLiveManagedProcesses: true,
        isTerminal: false,
      );
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'user-merge-delta-1',
              kind: 'outbound',
              text: 'Start two processes.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-merge-delta-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1200,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-merge-delta-2',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1400,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1400,
          activeRuns: <OpenCrayChatRunSnapshot>[firstRun, secondRun],
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

      expect(find.textContaining('first run old output'), findsWidgets);
      expect(find.textContaining('second run stays visible'), findsWidgets);

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 1,
          totalLength: 0,
          runPatchMode: 'merge',
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-merge-delta-1',
              taskId: 'task-merge-delta-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1600,
              attempt: 1,
              pendingMessageId: 'pending-merge-delta-1',
              managedProcessIds: <String>['proc-merge-delta-1'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-merge-delta-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  processStarted: true,
                  startedAtEpochMs: 1100,
                  updatedAtEpochMs: 1600,
                  stdoutPreview: 'first run fresh output',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          hasActiveRunsPatch: true,
          updatedAtEpochMs: 1600,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('first run fresh output'), findsWidgets);
      expect(find.textContaining('first run old output'), findsNothing);
      expect(find.textContaining('second run stays visible'), findsWidgets);
    },
  );

  testWidgets('hashed Kotlin assistant phase ids merge with runtime aliases', (
    tester,
  ) async {
    const text = 'Kotlin-generated phase identity.';
    final String hashedId =
        'runtime-assistant-commentary-run-hashed-phase-1-0-Planning-2200-${javaStringHashCode(text)}';
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: <OpenCrayChatMessageSnapshot>[
          const OpenCrayChatMessageSnapshot(
            messageId: 'pending-hashed-phase-1',
            kind: 'inbound',
            text: 'Thinking',
          ),
          OpenCrayChatMessageSnapshot(
            messageId: hashedId,
            kind: 'inbound',
            text: 'Planning\n\n$text',
            isEphemeral: true,
            createdAtEpochMs: 2200,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        streamInstanceId: 'stream-hashed-phase-1',
        lastSequence: 0,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-hashed-phase-1',
            taskId: 'task-hashed-phase-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2200,
            attempt: 1,
            pendingMessageId: 'pending-hashed-phase-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            eventId: 'event-hashed-phase-1',
            kind: 'assistant_phase',
            runId: 'run-hashed-phase-1',
            taskId: 'task-hashed-phase-1',
            emittedAtEpochMs: 2200,
            phase: 'commentary',
            turn: 0,
            stage: 'Planning',
            text: text,
          ),
        ],
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
      find.byKey(ValueKey<String>('chat-bubble-$hashedId')),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-assistant-commentary-task-hashed-phase-1-0-Planning-2200',
        ),
      ),
      findsNothing,
    );
  });

  testWidgets('runtime event merge keys keep different turns distinct', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Read README twice.',
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-turn-key-1',
            kind: 'inbound',
            text: 'Thinking',
            createdAtEpochMs: 1000,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-turn-key-1',
            taskId: 'task-turn-key-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 1400,
            attempt: 1,
            pendingMessageId: 'pending-turn-key-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-turn-key-1',
            taskId: 'task-turn-key-1',
            emittedAtEpochMs: 1500,
            turn: 0,
            toolName: 'Read',
            contentPreview: 'turn zero content',
          ),
        ],
        updatedAtEpochMs: 1500,
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

    final runTraceFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-turn-key-1'),
    );
    expect(runTraceFinder, findsOneWidget);
    await openRunTraceFullscreen(tester, runTraceFinder);
    final fullscreenFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-fullscreen-run-turn-key-1'),
    );

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 1,
        totalLength: 2,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-turn-key-1',
            taskId: 'task-turn-key-1',
            emittedAtEpochMs: 1500,
            turn: 1,
            toolName: 'Read',
            contentPreview: 'turn one content',
          ),
        ],
        updatedAtEpochMs: 1600,
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.textContaining('turn zero content', findRichText: true),
      ),
      findsWidgets,
    );
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.textContaining('turn one content', findRichText: true),
      ),
      findsWidgets,
    );
  });

  testWidgets('duplicate active and retained runs render one status line', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    const duplicateRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-duplicate-visible-1',
      taskId: 'task-duplicate-visible-1',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1700,
      attempt: 1,
      pendingMessageId: 'pending-duplicate-visible-1',
      managedProcessIds: <String>['proc-duplicate-visible-1'],
      managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
        OpenCrayChatManagedProcessSnapshot(
          processId: 'proc-duplicate-visible-1',
          status: 'running',
          command: 'npm',
          args: <String>['run', 'dev'],
          processStarted: true,
          startedAtEpochMs: 1200,
          updatedAtEpochMs: 1700,
          stdoutPreview: 'ready on http://localhost:3000',
        ),
      ],
      runningManagedProcessCount: 1,
      hasLiveManagedProcesses: true,
      isTerminal: false,
    );
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        updatedAtEpochMs: 1000,
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'user-duplicate-visible-1',
            kind: 'outbound',
            text: 'Start the preview server.',
            createdAtEpochMs: 1000,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-duplicate-visible-1',
            kind: 'inbound',
            text: 'The server is starting.',
            createdAtEpochMs: 1300,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 1700,
        activeRuns: <OpenCrayChatRunSnapshot>[duplicateRun],
        retainedRuns: <OpenCrayChatRunSnapshot>[duplicateRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_call',
            runId: 'run-duplicate-visible-1',
            taskId: 'task-duplicate-visible-1',
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
      const ValueKey<String>('chat-run-trace-run-duplicate-visible-1'),
    );
    final processBubbleFinder = find.byKey(
      const ValueKey<String>(
        'chat-bubble-runtime-process-task-duplicate-visible-1-proc-duplicate-visible-1',
      ),
    );

    expect(runTraceFinder, findsOneWidget);
    expect(processBubbleFinder, findsOneWidget);
    expect(
      tester.getTopLeft(runTraceFinder).dy,
      lessThan(tester.getTopLeft(processBubbleFinder).dy),
    );
  });

  testWidgets(
    'projected subagent state matches stale events by stable task ids',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const staleSubagentEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-stale-parent',
        taskId: 'task-subagent-stable-parent',
        emittedAtEpochMs: 2100,
        phase: 'started',
        status: 'running',
        label: 'Inspect README',
        childRunId: 'child-run-stale-subagent',
        childTaskId: 'child-task-stable-subagent',
        subagentType: 'researcher',
        text: 'Delegated child runtime started.',
      );
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-subagent-live-parent',
              taskId: 'task-subagent-stable-parent',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2600,
              attempt: 1,
              isTerminal: false,
            ),
          ],
          subAgents: <OpenCrayChatSubAgentSnapshot>[
            OpenCrayChatSubAgentSnapshot(
              parentRunId: 'run-subagent-live-parent',
              parentTaskId: 'task-subagent-stable-parent',
              childRunId: 'child-run-live-subagent',
              childTaskId: 'child-task-stable-subagent',
              label: 'Inspect README',
              subagentType: 'researcher',
              contextMode: 'minimal',
              depth: 1,
              phase: 'resumed',
              status: 'background_running',
              executionState: 'background_running',
              continuationKind: 'background_resume',
              resumable: true,
              summary: 'Delegated child runtime resumed in the background.',
              startedAtEpochMs: 1800,
              updatedAtEpochMs: 2600,
              eventCount: 0,
              mailboxMessageCount: 2,
              mailboxPendingMessageCount: 1,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[staleSubagentEvent],
          updatedAtEpochMs: 2600,
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
        const ValueKey<String>('chat-run-trace-run-subagent-live-parent'),
      );
      expect(bubbleFinder, findsOneWidget);
      await openRunTraceFullscreen(tester, bubbleFinder);

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-subagent-live-parent',
        ),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Researcher running in background: Inspect README',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Mailbox: 1 pending / 2 total'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Delegated child runtime started.',
            findRichText: true,
          ),
        ),
        findsNothing,
      );
    },
  );
}
