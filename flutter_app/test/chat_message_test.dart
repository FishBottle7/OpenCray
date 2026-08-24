import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_math_fork/flutter_math.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_file_image_preview.dart';
import 'package:opencray/core/models/opencray_file_text_preview.dart';
import 'package:opencray/core/models/opencray_file_voice_playback_source.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

void main() {
  testWidgets('chat messages render timestamps and 8-minute time dividers', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final DateTime now = DateTime.now().toLocal();
    final DateTime firstAt = DateTime(now.year, now.month, now.day, 9, 0);
    final DateTime secondAt = firstAt.add(const Duration(minutes: 5));
    final DateTime thirdAt = secondAt.add(const Duration(minutes: 8));
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
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

  testWidgets('message menu stays open after a long press ends', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: <OpenCrayChatMessageSnapshot>[
          const OpenCrayChatMessageSnapshot(
            messageId: 'message-long-press',
            kind: 'inbound',
            text: 'Long press this message',
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

    await tester.longPress(
      find.byKey(const ValueKey<String>('chat-bubble-message-long-press')),
    );
    await tester.pumpAndSettle();

    expect(find.text(copy.chatMessageCopyAction), findsOneWidget);
    expect(find.text(copy.chatMessageDeleteAction), findsOneWidget);
  });

  testWidgets('message menu actions remain tappable after opening', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final Map<String, Object?> clipboardState = <String, Object?>{};
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (
          MethodCall methodCall,
        ) async {
          switch (methodCall.method) {
            case 'Clipboard.setData':
              final Map<Object?, Object?> arguments =
                  methodCall.arguments as Map<Object?, Object?>;
              clipboardState['text'] = arguments['text'];
              return null;
            case 'Clipboard.getData':
              final Object? text = clipboardState['text'];
              return text == null ? null : <String, Object?>{'text': text};
          }
          return null;
        });
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null);
    });

    const messageText = 'Long press this message';
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: <OpenCrayChatMessageSnapshot>[
          const OpenCrayChatMessageSnapshot(
            messageId: 'message-long-press-action',
            kind: 'inbound',
            text: messageText,
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

    await tester.longPress(
      find.byKey(
        const ValueKey<String>('chat-bubble-message-long-press-action'),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>('chat-message-menu-message-long-press-action'),
      ),
      findsOneWidget,
    );

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-message-menu-action-copy')),
    );
    await tester.pumpAndSettle();

    final ClipboardData? clipboardData = await Clipboard.getData(
      Clipboard.kTextPlain,
    );
    expect(clipboardData?.text, messageText);
    expect(find.text(copy.chatMessageCopied), findsOneWidget);
    expect(
      find.byKey(
        const ValueKey<String>('chat-message-menu-message-long-press-action'),
      ),
      findsNothing,
    );
  });

  testWidgets(
    'host-backed message delete exits before removal and ignores stale snapshots',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      addTearDown(snapshots.close);
      OpenCrayChatSnapshot snapshotWithMessage({
        required int updatedAtEpochMs,
      }) {
        return hostChatSnapshot(
          updatedAtEpochMs: updatedAtEpochMs,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'message-delete-1',
              kind: 'inbound',
              text: 'Delete this message',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'message-keep-1',
              kind: 'outbound',
              text: 'Keep this message',
            ),
          ],
        );
      }

      final bridge = FakeChatBridge(
        chatSnapshot: snapshotWithMessage(updatedAtEpochMs: 1000),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
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

      expect(
        find.byKey(const ValueKey<String>('chat-bubble-message-delete-1')),
        findsOneWidget,
      );
      await tester.longPress(
        find.byKey(const ValueKey<String>('chat-bubble-message-delete-1')),
      );
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const ValueKey<String>('chat-message-menu-action-delete')),
      );
      await tester.pumpAndSettle();

      expect(bridge.deletedMessageIds, <String>['message-delete-1']);
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-message-delete-1')),
        findsNothing,
      );
      expect(find.text('Keep this message'), findsOneWidget);

      snapshots.add(snapshotWithMessage(updatedAtEpochMs: 2000));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-bubble-message-delete-1')),
        findsNothing,
      );
      expect(find.text('Keep this message'), findsOneWidget);
    },
  );

  testWidgets(
    'deleting a final agent bubble hides its process bubble and status line',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(snapshots.close);
      addTearDown(runtimeSnapshots.close);
      const processMessageId = 'runtime-process-task-delete-final-proc-final';
      const runtimeSnapshot = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 1500,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-delete-final',
            taskId: 'task-delete-final',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 1500,
            attempt: 1,
            pendingMessageId: 'assistant-final-delete',
            managedProcessIds: <String>['proc-final'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-final',
                status: 'running',
                command: 'npm',
                args: <String>['test'],
                processStarted: true,
                startedAtEpochMs: 1200,
                updatedAtEpochMs: 1500,
                stdoutPreview: 'running tests',
              ),
            ],
            runningManagedProcessCount: 1,
            hasLiveManagedProcesses: true,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      OpenCrayChatSnapshot snapshotWithTurn({required int updatedAtEpochMs}) {
        return hostChatSnapshot(
          updatedAtEpochMs: updatedAtEpochMs,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'user-delete-final',
              kind: 'outbound',
              text: 'Run npm test',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'assistant-final-delete',
              kind: 'inbound',
              text: 'Tests are still running.',
            ),
          ],
        );
      }

      final bridge = FakeChatBridge(
        chatSnapshot: snapshotWithTurn(updatedAtEpochMs: 1000),
        runtimeSnapshot: runtimeSnapshot,
        chatSnapshotStream: snapshots.stream,
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
        find.byKey(const ValueKey<String>('chat-bubble-$processMessageId')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-delete-final')),
        findsOneWidget,
      );

      await tester.longPress(
        find.byKey(
          const ValueKey<String>('chat-bubble-assistant-final-delete'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const ValueKey<String>('chat-message-menu-action-delete')),
      );
      await tester.pumpAndSettle();

      expect(bridge.deletedMessageIds, <String>[
        'assistant-final-delete',
        processMessageId,
      ]);
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-$processMessageId')),
        findsNothing,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-bubble-assistant-final-delete'),
        ),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-delete-final')),
        findsNothing,
      );

      snapshots.add(snapshotWithTurn(updatedAtEpochMs: 2000));
      runtimeSnapshots.add(runtimeSnapshot);
      await tester.pump();
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-bubble-$processMessageId')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-delete-final')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'deleting a process bubble keeps its final bubble and status line',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(snapshots.close);
      addTearDown(runtimeSnapshots.close);
      const processMessageId = 'runtime-process-task-delete-process-proc-only';
      const runtimeSnapshot = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 1500,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-delete-process',
            taskId: 'task-delete-process',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 1500,
            attempt: 1,
            pendingMessageId: 'assistant-final-keep',
            managedProcessIds: <String>['proc-only'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-only',
                status: 'running',
                command: 'npm',
                args: <String>['run', 'dev'],
                processStarted: true,
                startedAtEpochMs: 1200,
                updatedAtEpochMs: 1500,
                stdoutPreview: 'ready',
              ),
            ],
            runningManagedProcessCount: 1,
            hasLiveManagedProcesses: true,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      OpenCrayChatSnapshot snapshotWithTurn({required int updatedAtEpochMs}) {
        return hostChatSnapshot(
          updatedAtEpochMs: updatedAtEpochMs,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'user-delete-process',
              kind: 'outbound',
              text: 'Start the dev server',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'assistant-final-keep',
              kind: 'inbound',
              text: 'The server is starting.',
            ),
          ],
        );
      }

      final bridge = FakeChatBridge(
        chatSnapshot: snapshotWithTurn(updatedAtEpochMs: 1000),
        runtimeSnapshot: runtimeSnapshot,
        chatSnapshotStream: snapshots.stream,
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

      await tester.longPress(
        find.byKey(const ValueKey<String>('chat-bubble-$processMessageId')),
      );
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const ValueKey<String>('chat-message-menu-action-delete')),
      );
      await tester.pumpAndSettle();

      expect(bridge.deletedMessageIds, <String>[processMessageId]);
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-$processMessageId')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-assistant-final-keep')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-delete-process')),
        findsOneWidget,
      );

      snapshots.add(snapshotWithTurn(updatedAtEpochMs: 2000));
      runtimeSnapshots.add(runtimeSnapshot);
      await tester.pump();
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-bubble-$processMessageId')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-assistant-final-keep')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-delete-process')),
        findsOneWidget,
      );
    },
  );

  testWidgets('selection mode preserves outbound bubble right edge', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(
            copy: copy,
            state: ChatFeatureState(
              variant: ChatPrototypeVariant.main,
              screenTitle: 'Chat',
              summary: const ChatSessionSummary(
                title: 'Session',
                badge: '2 messages',
                body: 'Selecting messages',
              ),
              messages: const <ChatMessageData>[
                ChatMessageData(
                  messageId: 'message-select-user',
                  kind: ChatMessageKind.outbound,
                  text: 'Keep me aligned to the right.',
                ),
                ChatMessageData(
                  messageId: 'message-select-assistant',
                  kind: ChatMessageKind.inbound,
                  text: 'Assistant reply',
                ),
              ],
              runTraces: const <ChatRunTraceData>[],
              composer: ChatComposerState(placeholder: 'Message OpenCray'),
              drawer: ChatSessionsDrawerState(
                eyebrow: 'Recent sessions',
                title: 'Recent sessions',
                ctaLabel: 'New session',
                sessions: const <ChatSessionListItemData>[],
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final userBubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-message-select-user'),
    );
    final double normalRight = tester.getTopRight(userBubbleFinder).dx;

    await tester.longPress(userBubbleFinder);
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(
        const ValueKey<String>('chat-message-menu-action-multiSelect'),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>('chat-message-row-message-select-user'),
      ),
      findsOneWidget,
    );
    expect(tester.getTopRight(userBubbleFinder).dx, closeTo(normalRight, 0.5));
    expect(
      tester
          .getTopRight(
            find.byKey(
              const ValueKey<String>('chat-message-row-bg-message-select-user'),
            ),
          )
          .dx,
      greaterThanOrEqualTo(normalRight),
    );
  });

  testWidgets('streaming indicator renders inline after the last character', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildChatHarness(
        runTraces: const <ChatRunTraceData>[],
        messages: const <ChatMessageData>[
          ChatMessageData(
            messageId: 'streaming-inline',
            kind: ChatMessageKind.inbound,
            text: 'Hello streaming world',
            isStreaming: true,
          ),
        ],
      ),
    );
    await tester.pump();

    final Finder inlineTailText = find.byWidgetPredicate((widget) {
      if (widget is! Text) {
        return false;
      }
      final String plain = widget.data ?? widget.textSpan?.toPlainText() ?? '';
      return plain == 'Hello streaming world￼';
    }, description: 'text with trailing inline streaming indicator');
    expect(inlineTailText, findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey<String>('chat-bubble-streaming-inline')),
        matching: find.byKey(
          const ValueKey<String>('chat-streaming-indicator-streaming-inline'),
        ),
      ),
      findsOneWidget,
    );
  });

  testWidgets('streaming indicator falls back below unfinished code fences', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildChatHarness(
        runTraces: const <ChatRunTraceData>[],
        messages: const <ChatMessageData>[
          ChatMessageData(
            messageId: 'streaming-fence',
            kind: ChatMessageKind.inbound,
            text: '```dart',
            isStreaming: true,
          ),
        ],
      ),
    );
    await tester.pump();

    expect(
      find.descendant(
        of: find.byKey(const ValueKey<String>('chat-bubble-streaming-fence')),
        matching: find.byKey(
          const ValueKey<String>('chat-streaming-indicator-streaming-fence'),
        ),
      ),
      findsOneWidget,
    );
    final Finder placeholderInsideText = find.byWidgetPredicate((widget) {
      if (widget is! Text) {
        return false;
      }
      final String plain = widget.data ?? widget.textSpan?.toPlainText() ?? '';
      return plain.contains('￼');
    }, description: 'text containing an inline placeholder');
    expect(placeholderInsideText, findsNothing);
  });

  testWidgets('assistant message bubbles render markdown emphasis', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildChatHarness(
        messages: const <ChatMessageData>[
          ChatMessageData(
            messageId: 'assistant-markdown-bold',
            kind: ChatMessageKind.inbound,
            text: 'Alpha **Bold** Omega',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-bold'),
    );
    expect(bubbleFinder, findsOneWidget);

    final richTextFinder = find.descendant(
      of: bubbleFinder,
      matching: find.byWidgetPredicate((widget) {
        if (widget is! RichText) {
          return false;
        }
        return widget.text.toPlainText() == 'Alpha Bold Omega';
      }),
    );

    expect(richTextFinder, findsOneWidget);
    final RichText richText = tester.widget<RichText>(richTextFinder);
    final List<TextSpan> spans = collectLeafTextSpans(richText.text);
    final TextSpan boldSpan = spans.firstWhere((span) => span.text == 'Bold');

    expect(boldSpan.style?.fontWeight, FontWeight.w700);
  });

  testWidgets(
    'assistant message bubbles render markdown tables inside scroll views',
    (tester) async {
      await tester.pumpWidget(
        buildChatHarness(
          messages: const <ChatMessageData>[
            ChatMessageData(
              messageId: 'assistant-markdown-table',
              kind: ChatMessageKind.inbound,
              text:
                  '| Name | Value |\n'
                  '| --- | --- |\n'
                  '| Feature | Markdown table rendering |\n'
                  '| Scope | Chat bubbles |',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-assistant-markdown-table'),
      );
      expect(bubbleFinder, findsOneWidget);
      expect(
        find.descendant(of: bubbleFinder, matching: find.byType(Table)),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.byType(SingleChildScrollView),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets('assistant message bubbles render latex formulas', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildChatHarness(
        messages: const <ChatMessageData>[
          ChatMessageData(
            messageId: 'assistant-markdown-formula',
            kind: ChatMessageKind.inbound,
            text: r'Quadratic root: $c = \pm\sqrt{a^2 + b^2}$',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-formula'),
    );
    expect(bubbleFinder, findsOneWidget);
    expect(
      find.descendant(of: bubbleFinder, matching: find.byType(Math)),
      findsOneWidget,
    );
  });

  testWidgets('assistant message bubbles render workspace markdown images', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-markdown-image',
            kind: 'inbound',
            text: 'Diagram:\n\n![Architecture](docs/diagram.png)',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      imagePreviews: <String, OpenCrayFileImagePreview>{
        'docs/diagram.png': fakeImagePreview(
          name: 'diagram.png',
          relativePath: 'docs/diagram.png',
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

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-image'),
    );
    expect(bubbleFinder, findsOneWidget);
    expect(
      find.descendant(of: bubbleFinder, matching: find.byType(Image)),
      findsOneWidget,
    );
  });

  testWidgets(
    'assistant message markdown images open the shared preview dialog',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'assistant-markdown-image-preview',
              kind: 'inbound',
              text: 'Diagram:\n\n![Architecture](docs/diagram.png)',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        imagePreviews: <String, OpenCrayFileImagePreview>{
          'docs/diagram.png': fakeImagePreview(
            name: 'diagram.png',
            relativePath: 'docs/diagram.png',
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

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-assistant-markdown-image-preview'),
      );
      await tester.tap(
        find.descendant(
          of: bubbleFinder,
          matching: find.byKey(
            const ValueKey<String>('opencray-markdown-image-tappable'),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(
          const ValueKey<String>('opencray-markdown-image-preview-dialog'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('assistant message markdown links preview workspace text files', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-markdown-link-preview',
            kind: 'inbound',
            text: 'Open [report.md](docs/report.md)',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      textPreviews: <String, OpenCrayFileTextPreview>{
        'docs/report.md': const OpenCrayFileTextPreview(
          name: 'report.md',
          relativePath: 'docs/report.md',
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

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-link-preview'),
    );
    activateRichTextLink(
      tester,
      find.descendant(
        of: bubbleFinder,
        matching: findRichTextWithPlainText('Open report.md'),
      ),
    );
    await tester.pumpAndSettle();

    expect(bridge.loadedTextPreviews, <String>['docs/report.md']);
    expect(bridge.openedWorkspaceEntries, isEmpty);
    expect(bridge.openedExternalUris, isEmpty);
    expect(
      find.byKey(const ValueKey<String>('chat-text-preview-dialog')),
      findsOneWidget,
    );
    expect(find.textContaining('Preview body'), findsOneWidget);
  });

  testWidgets(
    'assistant message markdown links open non-preview workspace files',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'assistant-markdown-link-file',
              kind: 'inbound',
              text: 'Open [report.pdf](docs/report.pdf)',
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

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-assistant-markdown-link-file'),
      );
      activateRichTextLink(
        tester,
        find.descendant(
          of: bubbleFinder,
          matching: findRichTextWithPlainText('Open report.pdf'),
        ),
      );
      await tester.pumpAndSettle();

      expect(bridge.loadedTextPreviews, isEmpty);
      expect(bridge.openedWorkspaceEntries, <String>['docs/report.pdf']);
      expect(bridge.openedExternalUris, isEmpty);
    },
  );

  testWidgets('assistant message markdown links open external uris', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-markdown-link-external',
            kind: 'inbound',
            text: 'Visit [OpenCray docs](https://opencray.dev/docs)',
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

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-link-external'),
    );
    activateRichTextLink(
      tester,
      find.descendant(
        of: bubbleFinder,
        matching: findRichTextWithPlainText('Visit OpenCray docs'),
      ),
    );
    await tester.pumpAndSettle();

    expect(bridge.loadedTextPreviews, isEmpty);
    expect(bridge.openedWorkspaceEntries, isEmpty);
    expect(bridge.openedExternalUris, <String>['https://opencray.dev/docs']);
  });

  testWidgets('assistant message markdown links open internal settings routes', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-markdown-link-settings',
            kind: 'inbound',
            text:
                'Remote LLM is not ready. Open [Settings -> LLM](/settings/llm).',
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
        routes: <String, WidgetBuilder>{
          '/settings/llm': (_) =>
              const Scaffold(body: Text('LLM Settings Screen')),
        },
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-link-settings'),
    );
    activateRichTextLink(
      tester,
      find.descendant(
        of: bubbleFinder,
        matching: findRichTextWithPlainText(
          'Remote LLM is not ready. Open Settings -> LLM.',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('LLM Settings Screen'), findsOneWidget);
    expect(bridge.loadedTextPreviews, isEmpty);
    expect(bridge.openedWorkspaceEntries, isEmpty);
    expect(bridge.openedExternalUris, isEmpty);
  });

  testWidgets('voice attachment waveform seeks and transcript expands', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final playbackLog = FakeVoicePlaybackLog();
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
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
                FakeVoicePlaybackController(playbackLog),
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
}
