import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/features/chat/chat_feature.dart';

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
      expect(runtimeSnapshotVersion(embedded), runtimeSnapshotVersion(streamed));
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
    expect(find.textContaining('Project uses the Gradle wrapper from the repo root.'), findsOneWidget);
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
            body: OpenCrayChatFeature(
              copy: copy,
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('Read README.md lines 5-6'), findsOneWidget);
      expect(
        find.textContaining('Project uses the Gradle wrapper from the repo root.'),
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
    final scrollableStateBefore =
        tester.state<ScrollableState>(scrollableFinder);

    expect(scrollableStateBefore.position.pixels, 0);

    await tester.drag(
      find.byKey(const ValueKey<String>('chat-run-trace-scroll-run-1')),
      const Offset(0, -180),
    );
    await tester.pumpAndSettle();

    final scrollableStateAfter = tester.state<ScrollableState>(scrollableFinder);
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
    final scrollableStateBefore =
        tester.state<ScrollableState>(fullscreenScrollableFinder);

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
      expect(find.byKey(const ValueKey<String>('chat-run-trace-run-1')), findsOneWidget);
    },
  );

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
}

Widget _buildChatHarness({
  List<ChatPendingApprovalData> pendingApprovals =
      const <ChatPendingApprovalData>[],
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
          composer: ChatComposerState(placeholder: copy.chatComposerPlaceholder),
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

OpenCrayChatSnapshot _hostChatSnapshot() {
  return const OpenCrayChatSnapshot(
    screenTitle: 'Chat',
    modeLabel: 'SAFE',
    sessionButtonLabel: 'Sessions',
    composerPlaceholder: 'Message OpenCray',
    summary: OpenCrayChatSummarySnapshot(
      title: 'Session',
      badge: '1 message',
      body: 'Reply in progress',
    ),
    messages: <OpenCrayChatMessageSnapshot>[
      OpenCrayChatMessageSnapshot(
        kind: 'outbound',
        text: 'Inspect the workspace.',
      ),
    ],
    drawer: OpenCrayChatDrawerSnapshot(
      eyebrow: 'Recent sessions',
      title: 'Recent sessions',
      ctaLabel: 'New session',
      sessions: <OpenCrayChatSessionItemSnapshot>[],
    ),
    isInputEnabled: true,
  );
}

class _FakeChatBridge implements OpenCrayHostBridge {
  _FakeChatBridge({
    required this.chatSnapshot,
    required this.runtimeSnapshot,
  });

  final OpenCrayChatSnapshot chatSnapshot;
  final OpenCrayChatRuntimeSnapshot runtimeSnapshot;

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
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
