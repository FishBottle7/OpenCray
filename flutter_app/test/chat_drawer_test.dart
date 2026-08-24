import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

void main() {
  testWidgets('session drawer shows unread dot and count badges', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildChatHarness(
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

  testWidgets('session drawer rows are lazily built while scrolling', (
    tester,
  ) async {
    final sessions = List<ChatSessionListItemData>.generate(
      80,
      (index) => ChatSessionListItemData(
        sessionId: 'session-lazy-$index',
        title: 'Session $index',
        preview: 'Preview for lazy session $index',
        meta: '$index messages',
        isSelected: index == 0,
      ),
    );

    await tester.pumpWidget(
      buildChatHarness(
        drawerOpen: true,
        drawer: ChatSessionsDrawerState(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: sessions,
        ),
      ),
    );
    await tester.pumpAndSettle();

    final firstRow = find.byKey(
      const ValueKey<String>('chat-session-row-session-lazy-0'),
    );
    final lastRow = find.byKey(
      const ValueKey<String>('chat-session-row-session-lazy-79'),
    );

    expect(firstRow, findsOneWidget);
    expect(lastRow, findsNothing);

    final scrollableState = scrollableStateFor(
      tester,
      find.byKey(const ValueKey<String>('chat-session-list')),
    );
    scrollableState.position.jumpTo(scrollableState.position.maxScrollExtent);
    await tester.pumpAndSettle();

    expect(lastRow, findsOneWidget);
  });

  testWidgets('session drawer opens from the left edge', (tester) async {
    await tester.pumpWidget(
      buildChatHarness(
        drawer: const ChatSessionsDrawerState(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: <ChatSessionListItemData>[
            ChatSessionListItemData(
              sessionId: 'session-motion',
              title: 'Motion session',
              preview: 'Drawer should enter from the left.',
              meta: '1 message',
            ),
          ],
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(chatSessionsButton());
    await tester.pump();

    AnimatedSlide slide = tester.widget<AnimatedSlide>(
      find.byType(AnimatedSlide),
    );
    expect(slide.offset, const Offset(-1, 0));

    await tester.pumpAndSettle();
    slide = tester.widget<AnimatedSlide>(find.byType(AnimatedSlide));
    expect(slide.offset, Offset.zero);
    expect(find.text('Motion session'), findsOneWidget);
  });

  testWidgets('host-backed session drawer shows recent message time labels', (
    tester,
  ) async {
    final DateTime now = DateTime.now().toLocal();
    final DateTime todayAt = DateTime(now.year, now.month, now.day, 14, 32);
    final DateTime yesterdayAt = todayAt.subtract(const Duration(days: 1));
    final DateTime weekdayAt = todayAt.subtract(const Duration(days: 3));
    final DateTime olderAt = todayAt.subtract(const Duration(days: 12));
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
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

    await tester.tap(chatSessionsButton());
    await tester.pumpAndSettle();

    expect(find.text('14:32'), findsOneWidget);
    expect(find.text('Yesterday'), findsOneWidget);
    expect(find.text(weekdayLabelFor(weekdayAt)), findsOneWidget);
    expect(find.text(dateLabelFor(olderAt, now: now)), findsOneWidget);
    expect(find.text('2 messages'), findsNothing);
    expect(find.text('5 messages'), findsNothing);
    expect(find.text('8 messages'), findsNothing);
    expect(find.text('13 messages'), findsNothing);
  });

  testWidgets(
    'host-backed session delete updates drawer immediately and ignores stale snapshots',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      addTearDown(snapshots.close);
      OpenCrayChatSnapshot snapshotWithDeletedSession({
        required int updatedAtEpochMs,
      }) {
        return hostChatSnapshot(
          updatedAtEpochMs: updatedAtEpochMs,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'message-session-delete',
              kind: 'inbound',
              text: 'This deleted session text should disappear.',
              createdAtEpochMs: 1000,
            ),
          ],
          todos: const <OpenCrayChatTodoSnapshot>[
            OpenCrayChatTodoSnapshot(
              content: 'Delete session todo should disappear',
              status: 'in_progress',
            ),
          ],
          todoState: 'active',
          pendingApprovals: const <OpenCrayChatPendingApprovalSnapshot>[
            OpenCrayChatPendingApprovalSnapshot(
              runId: 'run-session-delete',
              taskId: 'task-session-delete',
              title: 'Delete approval should disappear',
              body: 'Approval from deleted session',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: false,
            ),
          ],
          drawer: OpenCrayChatDrawerSnapshot(
            eyebrow: 'Recent sessions',
            title: 'Recent sessions',
            ctaLabel: 'New session',
            sessions: const <OpenCrayChatSessionItemSnapshot>[
              OpenCrayChatSessionItemSnapshot(
                sessionId: 'session-delete',
                title: 'Delete session',
                preview: 'This session will be removed.',
                meta: '2 messages',
                isSelected: true,
              ),
              OpenCrayChatSessionItemSnapshot(
                sessionId: 'session-keep',
                title: 'Keep session',
                preview: 'This session should remain.',
                meta: '1 message',
                isSelected: false,
              ),
            ],
          ),
        );
      }

      final bridge = FakeChatBridge(
        chatSnapshot: snapshotWithDeletedSession(updatedAtEpochMs: 1000),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-delete',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-delete',
              runId: 'run-session-delete',
              taskId: 'task-session-delete',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'message-session-delete',
              isTerminal: false,
            ),
          ],
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

      expect(
        find.text('This deleted session text should disappear.'),
        findsOneWidget,
      );
      expect(find.text('Delete approval should disappear'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-session-delete')),
        findsOneWidget,
      );

      await tester.tap(chatSessionsButton());
      await tester.pumpAndSettle();
      expect(find.text('Delete session'), findsOneWidget);
      expect(find.text('Keep session'), findsOneWidget);

      await tester.longPress(find.text('Delete session'));
      await tester.pumpAndSettle();
      await tester.tap(find.text(copy.filesDeleteAction).last);
      await tester.pumpAndSettle();

      expect(bridge.deletedSessionIds, <String>['session-delete']);
      expect(find.text('Delete session'), findsNothing);
      expect(find.text('Keep session'), findsOneWidget);
      expect(
        find.text('This deleted session text should disappear.'),
        findsNothing,
      );
      expect(find.text('Delete approval should disappear'), findsNothing);
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-session-delete')),
        findsNothing,
      );

      snapshots.add(snapshotWithDeletedSession(updatedAtEpochMs: 2000));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.text('Delete session'), findsNothing);
      expect(find.text('Keep session'), findsOneWidget);
      expect(
        find.text('This deleted session text should disappear.'),
        findsNothing,
      );
    },
  );

  testWidgets(
    'host-backed session selection updates drawer and clears old thread immediately',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'message-session-old',
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
                unreadCount: 2,
              ),
            ],
          ),
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-old',
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

      expect(find.text('Old selected session text'), findsOneWidget);

      await tester.tap(chatSessionsButton());
      await tester.pumpAndSettle();
      await tester.tap(find.text('Next session'));
      await tester.pump();

      expect(bridge.selectedSessionIds, <String>['session-next']);
      expect(find.text('Old selected session text'), findsNothing);
      expect(find.text(copy.chatComposerPlaceholder), findsOneWidget);

      await tester.tap(chatSessionsButton());
      await tester.pumpAndSettle();
      expect(find.text('Next session'), findsOneWidget);
      expect(find.text('Old session'), findsOneWidget);
    },
  );

  testWidgets(
    'new session drawer action waits for host creation before closing the drawer',
    (tester) async {
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
      );
      bridge.createChatSessionCompleter = Completer<void>();

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

      await tester.tap(chatSessionsButton());
      await tester.pumpAndSettle();
      expect(find.text('New session'), findsOneWidget);

      await tester.tap(find.text('New session'));
      await tester.pump();

      expect(bridge.createChatSessionCallCount, 1);
      expect(find.text('New session'), findsOneWidget);

      bridge.createChatSessionCompleter!.complete();
      await tester.pump();
      await tester.pump();

      expect(bridge.createChatSessionCallCount, 1);
      expect(find.text('New session'), findsNothing);
    },
  );
}
