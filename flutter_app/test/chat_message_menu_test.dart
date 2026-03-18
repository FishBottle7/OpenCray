import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show SelectedContent;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/features/chat/chat_feature.dart';

void main() {
  test('chat bubble selection theme uses contrast-aware colors', () {
    expect(
      chatBubbleSelectionTheme(ChatMessageKind.outbound).selectionColor,
      const Color(0x52FFFFFF),
    );
    expect(
      chatBubbleSelectionTheme(ChatMessageKind.outbound).selectionHandleColor,
      Colors.white,
    );
    expect(
      chatBubbleSelectionTheme(ChatMessageKind.inbound).selectionColor,
      const Color(0x33007AFF),
    );
    expect(
      chatBubbleSelectionTheme(ChatMessageKind.inbound).selectionHandleColor,
      const Color(0xFF0A84FF),
    );
  });

  testWidgets(
    'long pressing a bubble shows the action grid and quote populates the composer',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      await tester.pumpWidget(_buildHarness(copy));
      await tester.pumpAndSettle();

      final targetMessage = find.text(copy.chatSeedSafeModeAsks);
      final targetBubble = find.byKey(
        const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
      );

      expect(targetMessage, findsOneWidget);
      expect(targetBubble, findsOneWidget);
      expect(find.byType(SelectionArea), findsWidgets);

      await _openMessageMenu(tester, targetBubble);

      expect(find.text(copy.chatMessageCopyAction), findsOneWidget);
      expect(find.text(copy.chatMessageRedoAction), findsOneWidget);
      expect(find.text(copy.chatMessageBranchAction), findsOneWidget);
      expect(find.text(copy.chatMessageDeleteAction), findsOneWidget);
      expect(find.text(copy.chatMessageSelectAction), findsOneWidget);
      expect(find.text(copy.chatMessageQuoteAction), findsOneWidget);

      await tester.tap(find.text(copy.chatMessageQuoteAction));
      await tester.pumpAndSettle();

      final composer = tester.widget<TextField>(find.byType(TextField));
      expect(composer.controller?.text, '> ${copy.chatSeedSafeModeAsks}\n\n');
    },
  );

  testWidgets('delete action removes the targeted bubble from local state', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    await tester.pumpWidget(_buildHarness(copy));
    await tester.pumpAndSettle();

    final targetMessage = find.text(copy.chatSeedSafeModeAsks);
    final targetBubble = find.byKey(
      const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
    );

    expect(targetMessage, findsOneWidget);
    expect(targetBubble, findsOneWidget);

    await _openMessageMenu(tester, targetBubble);
    await tester.tap(find.text(copy.chatMessageDeleteAction));
    await tester.pumpAndSettle();

    expect(find.text(copy.chatSeedSafeModeAsks), findsNothing);
  });

  testWidgets('copy action prefers the current text selection', (tester) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final List<MethodCall> platformCalls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
          platformCalls.add(call);
          return null;
        });
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null);
    });
    await tester.pumpWidget(_buildHarness(copy));
    await tester.pumpAndSettle();

    final targetBubble = find.byKey(
      const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
    );
    final selectionArea = find.descendant(
      of: targetBubble,
      matching: find.byType(SelectionArea),
    );
    final selectionWidget = tester.widget<SelectionArea>(selectionArea);

    selectionWidget.onSelectionChanged!(
      const SelectedContent(plainText: 'Safe mode'),
    );
    await tester.pump();

    await _openMessageMenu(tester, targetBubble);
    await tester.tap(find.text(copy.chatMessageCopyAction));
    await tester.pumpAndSettle();

    expect(platformCalls, isNotEmpty);
    expect(platformCalls.last.method, 'Clipboard.setData');
    expect(platformCalls.last.arguments, <String, dynamic>{
      'text': 'Safe mode',
    });
  });

  testWidgets(
    'agent redo rewinds to the prompt and regenerates with the bridge',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = OpenCraySeedBridge(
        initialChatSnapshot: _seedBridgeChatSnapshot(copy),
      );
      await tester.pumpWidget(_buildHarness(copy, bridge: bridge));
      await tester.pumpAndSettle();

      final targetBubble = find.byKey(
        const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
      );

      expect(targetBubble, findsOneWidget);

      await _openMessageMenu(tester, targetBubble);

      expect(find.text(copy.chatMessageRedoAction), findsOneWidget);
      expect(find.text(copy.chatMessageRecallAction), findsNothing);

      await tester.tap(find.text(copy.chatMessageRedoAction));
      await tester.pumpAndSettle();

      expect(find.text(copy.chatSeedSafeModeAsks), findsNothing);
      expect(find.text(copy.chatSeedShowCurrentLimits), findsNothing);
      expect(
        find.text('Seed bridge stored your message locally.'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'user edit recalls the turn and restores the prompt into the composer',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = OpenCraySeedBridge(
        initialChatSnapshot: _seedBridgeChatSnapshot(copy),
      );
      await tester.pumpWidget(_buildHarness(copy, bridge: bridge));
      await tester.pumpAndSettle();

      final targetBubble = find.byKey(
        const ValueKey<String>('chat-bubble-seed-main-outbound-1'),
      );

      expect(targetBubble, findsOneWidget);

      await _openMessageMenu(tester, targetBubble);

      expect(find.text(copy.chatMessageRecallAction), findsOneWidget);
      expect(find.text(copy.chatMessageEditAction), findsOneWidget);
      expect(find.text(copy.chatMessageRedoAction), findsNothing);
      expect(find.text(copy.chatMessageBranchAction), findsNothing);

      await tester.tap(find.text(copy.chatMessageEditAction));
      await tester.pumpAndSettle();

      final composer = tester.widget<TextField>(find.byType(TextField));
      expect(composer.controller?.text, copy.chatSeedWhyWritePending);
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-seed-main-outbound-1')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-seed-main-inbound-2')),
        findsNothing,
      );
    },
  );

  testWidgets('agent branch clones history into a new session', (tester) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = OpenCraySeedBridge(
      initialChatSnapshot: _seedBridgeChatSnapshot(copy),
    );
    await tester.pumpWidget(_buildHarness(copy, bridge: bridge));
    await tester.pumpAndSettle();

    final targetBubble = find.byKey(
      const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
    );

    expect(targetBubble, findsOneWidget);

    await _openMessageMenu(tester, targetBubble);

    expect(find.text(copy.chatMessageRedoAction), findsOneWidget);
    expect(find.text(copy.chatMessageBranchAction), findsOneWidget);
    expect(find.text(copy.chatMessageEditAction), findsNothing);

    await tester.tap(find.text(copy.chatMessageBranchAction));
    await tester.pumpAndSettle();

    final snapshot = await bridge.loadChatSnapshot();

    expect(snapshot.summary.title, 'Seed session branch');
    expect(snapshot.drawer.sessions.length, 2);
    expect(snapshot.drawer.sessions.first.title, 'Seed session branch');
    expect(snapshot.drawer.sessions.first.isSelected, true);
    expect(
      snapshot.messages.map((message) => message.text),
      isNot(contains(copy.chatSeedShowCurrentLimits)),
    );
  });
}

Widget _buildHarness(OpenCrayUiCopy copy, {OpenCrayHostBridge? bridge}) {
  return MaterialApp(
    home: Scaffold(
      body: OpenCrayChatFeature(copy: copy, bridge: bridge),
    ),
  );
}

OpenCrayChatSnapshot _seedBridgeChatSnapshot(OpenCrayUiCopy copy) {
  return OpenCrayChatSnapshot(
    screenTitle: 'Chat',
    modeLabel: 'SEED',
    sessionButtonLabel: 'Sessions',
    composerPlaceholder: 'Message OpenCray',
    summary: OpenCrayChatSummarySnapshot(
      title: copy.chatSeedSummaryTitle,
      badge: copy.chatSeedSummaryBadge,
      body: copy.chatSeedSummaryBody,
    ),
    messages: <OpenCrayChatMessageSnapshot>[
      OpenCrayChatMessageSnapshot(
        messageId: 'seed-main-timeline',
        kind: 'timeline',
        text: copy.chatToday,
      ),
      OpenCrayChatMessageSnapshot(
        messageId: 'seed-main-inbound-1',
        kind: 'inbound',
        text: copy.chatSeedWorkspaceReady,
      ),
      OpenCrayChatMessageSnapshot(
        messageId: 'seed-main-outbound-1',
        kind: 'outbound',
        text: copy.chatSeedWhyWritePending,
      ),
      OpenCrayChatMessageSnapshot(
        messageId: 'seed-main-inbound-2',
        kind: 'inbound',
        text: copy.chatSeedSafeModeAsks,
      ),
      OpenCrayChatMessageSnapshot(
        messageId: 'seed-main-outbound-2',
        kind: 'outbound',
        text: copy.chatSeedShowCurrentLimits,
      ),
    ],
    drawer: const OpenCrayChatDrawerSnapshot(
      eyebrow: 'Recent sessions',
      title: 'Recent sessions',
      ctaLabel: 'New session',
      sessions: <OpenCrayChatSessionItemSnapshot>[
        OpenCrayChatSessionItemSnapshot(
          sessionId: 'seed-session',
          title: 'Seed session',
          preview: 'Flutter chat is attached to a seed bridge.',
          meta: '5 messages',
          isSelected: true,
        ),
      ],
    ),
    isInputEnabled: true,
  );
}

Future<void> _openMessageMenu(WidgetTester tester, Finder bubble) async {
  final bubbleRect = tester.getRect(bubble);
  final gesture = await tester.startGesture(
    bubbleRect.topLeft + const Offset(24, 20),
  );
  await tester.pump(const Duration(milliseconds: 260));
  await gesture.up();
  await tester.pumpAndSettle();
}
