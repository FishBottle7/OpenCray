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
      const Color(0x332563EB),
    );
    expect(
      chatBubbleSelectionTheme(ChatMessageKind.inbound).selectionHandleColor,
      const Color(0xFF2563EB),
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

  testWidgets(
    'delete action slides inbound bubbles toward the left before removal',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      await tester.pumpWidget(_buildHarness(copy));
      await tester.pumpAndSettle();

      final targetBubble = find.byKey(
        const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
      );
      final double beforeLeft = tester.getRect(targetBubble).left;

      await _openMessageMenu(tester, targetBubble);
      await tester.tap(find.text(copy.chatMessageDeleteAction));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 90));

      expect(targetBubble, findsOneWidget);
      expect(tester.getRect(targetBubble).left, lessThan(beforeLeft));

      await tester.pumpAndSettle();

      expect(targetBubble, findsNothing);
    },
  );

  testWidgets(
    'delete action slides outbound bubbles toward the right before removal',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      await tester.pumpWidget(_buildHarness(copy));
      await tester.pumpAndSettle();

      final targetBubble = find.byKey(
        const ValueKey<String>('chat-bubble-seed-main-outbound-2'),
      );
      await tester.ensureVisible(targetBubble);
      await tester.pumpAndSettle();
      final double beforeRight = tester.getRect(targetBubble).right;

      await _openMessageMenu(tester, targetBubble);
      await tester.tap(find.text(copy.chatMessageDeleteAction));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 90));

      expect(targetBubble, findsOneWidget);
      expect(tester.getRect(targetBubble).right, greaterThan(beforeRight));

      await tester.pumpAndSettle();

      expect(targetBubble, findsNothing);
    },
  );

  testWidgets(
    'select action enters multi-select mode and highlights the row behind the bubble',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('zh-CN');
      await tester.pumpWidget(_buildHarness(copy));
      await tester.pumpAndSettle();

      final targetBubble = find.byKey(
        const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
      );

      await _openMessageMenu(tester, targetBubble);
      await tester.tap(find.text(copy.chatMessageSelectAction));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-selection-toolbar')),
        findsOneWidget,
      );
      expect(find.text(copy.chatSelectionCount(1)), findsOneWidget);
      expect(
        _rowHighlightColor(tester, 'seed-main-inbound-2'),
        const Color(0xFFE8ECF2),
      );
      expect(
        _rowHighlightColor(tester, 'seed-main-outbound-2'),
        Colors.transparent,
      );

      final outboundRow = find.byKey(
        const ValueKey<String>('chat-message-row-seed-main-outbound-2'),
      );
      await tester.ensureVisible(outboundRow);
      await tester.tap(outboundRow);
      await tester.pumpAndSettle();

      expect(find.text(copy.chatSelectionCount(2)), findsOneWidget);
      expect(
        _rowHighlightColor(tester, 'seed-main-outbound-2'),
        const Color(0xFFE8ECF2),
      );
    },
  );

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
    'copy action uses the latest selected text while the menu is open',
    (tester) async {
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

      await _openMessageMenu(tester, targetBubble);
      selectionWidget.onSelectionChanged!(
        const SelectedContent(plainText: 'before edits'),
      );
      await tester.pump();
      await tester.tap(find.text(copy.chatMessageCopyAction));
      await tester.pumpAndSettle();

      expect(platformCalls, isNotEmpty);
      expect(platformCalls.last.method, 'Clipboard.setData');
      expect(platformCalls.last.arguments, <String, dynamic>{
        'text': 'before edits',
      });
    },
  );

  testWidgets(
    'copy action preserves hyperlink payloads for a fully selected markdown link',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = OpenCraySeedBridge(
        initialChatSnapshot: const OpenCrayChatSnapshot(
          screenTitle: 'Chat',
          modeLabel: 'SEED',
          sessionButtonLabel: 'Sessions',
          composerPlaceholder: 'Message OpenCray',
          summary: OpenCrayChatSummarySnapshot(
            title: 'Seed summary',
            badge: 'Ready',
            body: 'Body',
          ),
          messages: <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'seed-main-timeline',
              kind: 'timeline',
              text: 'Today',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'seed-main-inbound-2',
              kind: 'inbound',
              text: 'Open [docs](https://opencray.dev/docs) now',
            ),
          ],
          drawer: OpenCrayChatDrawerSnapshot(
            eyebrow: 'Recent sessions',
            title: 'Recent sessions',
            ctaLabel: 'New session',
            sessions: <OpenCrayChatSessionItemSnapshot>[
              OpenCrayChatSessionItemSnapshot(
                sessionId: 'seed-session',
                title: 'Seed session',
                preview: 'Flutter chat is attached to a seed bridge.',
                meta: '2 messages',
                isSelected: true,
              ),
            ],
          ),
          isInputEnabled: true,
        ),
      );
      await tester.pumpWidget(_buildHarness(copy, bridge: bridge));
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
        const SelectedContent(plainText: 'docs'),
      );
      await tester.pump();

      await _openMessageMenu(tester, targetBubble);
      await tester.tap(find.text(copy.chatMessageCopyAction));
      await tester.pumpAndSettle();

      expect(bridge.lastCopiedPlainText, 'https://opencray.dev/docs');
      expect(
        bridge.lastCopiedHtmlText,
        contains('href="https://opencray.dev/docs"'),
      );
      expect(bridge.lastCopiedHtmlText, contains('>docs<'));
    },
  );

  testWidgets(
    'copy action falls back to plain text for a partial markdown link selection',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = OpenCraySeedBridge(
        initialChatSnapshot: const OpenCrayChatSnapshot(
          screenTitle: 'Chat',
          modeLabel: 'SEED',
          sessionButtonLabel: 'Sessions',
          composerPlaceholder: 'Message OpenCray',
          summary: OpenCrayChatSummarySnapshot(
            title: 'Seed summary',
            badge: 'Ready',
            body: 'Body',
          ),
          messages: <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'seed-main-timeline',
              kind: 'timeline',
              text: 'Today',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'seed-main-inbound-2',
              kind: 'inbound',
              text: 'Open [docs](https://opencray.dev/docs) now',
            ),
          ],
          drawer: OpenCrayChatDrawerSnapshot(
            eyebrow: 'Recent sessions',
            title: 'Recent sessions',
            ctaLabel: 'New session',
            sessions: <OpenCrayChatSessionItemSnapshot>[
              OpenCrayChatSessionItemSnapshot(
                sessionId: 'seed-session',
                title: 'Seed session',
                preview: 'Flutter chat is attached to a seed bridge.',
                meta: '2 messages',
                isSelected: true,
              ),
            ],
          ),
          isInputEnabled: true,
        ),
      );
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
      await tester.pumpWidget(_buildHarness(copy, bridge: bridge));
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
        const SelectedContent(plainText: 'doc'),
      );
      await tester.pump();

      await _openMessageMenu(tester, targetBubble);
      await tester.tap(find.text(copy.chatMessageCopyAction));
      await tester.pumpAndSettle();

      expect(platformCalls, isNotEmpty);
      expect(platformCalls.last.method, 'Clipboard.setData');
      expect(platformCalls.last.arguments, <String, dynamic>{'text': 'doc'});
      expect(bridge.lastCopiedPlainText, isNull);
      expect(bridge.lastCopiedHtmlText, isNull);
    },
  );

  testWidgets(
    'copy action preserves markdown hyperlinks when copying the full message',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = OpenCraySeedBridge(
        initialChatSnapshot: const OpenCrayChatSnapshot(
          screenTitle: 'Chat',
          modeLabel: 'SEED',
          sessionButtonLabel: 'Sessions',
          composerPlaceholder: 'Message OpenCray',
          summary: OpenCrayChatSummarySnapshot(
            title: 'Seed summary',
            badge: 'Ready',
            body: 'Body',
          ),
          messages: <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'seed-main-timeline',
              kind: 'timeline',
              text: 'Today',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'seed-main-inbound-2',
              kind: 'inbound',
              text: 'Open [docs](https://opencray.dev/docs)',
            ),
          ],
          drawer: OpenCrayChatDrawerSnapshot(
            eyebrow: 'Recent sessions',
            title: 'Recent sessions',
            ctaLabel: 'New session',
            sessions: <OpenCrayChatSessionItemSnapshot>[
              OpenCrayChatSessionItemSnapshot(
                sessionId: 'seed-session',
                title: 'Seed session',
                preview: 'Flutter chat is attached to a seed bridge.',
                meta: '2 messages',
                isSelected: true,
              ),
            ],
          ),
          isInputEnabled: true,
        ),
      );
      await tester.pumpWidget(_buildHarness(copy, bridge: bridge));
      await tester.pumpAndSettle();

      final targetBubble = find.byKey(
        const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
      );

      await _openMessageMenu(tester, targetBubble);
      await tester.tap(find.text(copy.chatMessageCopyAction));
      await tester.pumpAndSettle();

      expect(bridge.lastCopiedPlainText, 'Open https://opencray.dev/docs');
      expect(
        bridge.lastCopiedHtmlText,
        contains('href="https://opencray.dev/docs"'),
      );
      expect(bridge.lastCopiedHtmlText, contains('>docs<'));
    },
  );

  testWidgets(
    'selection toolbar copies selected messages in transcript order',
    (tester) async {
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

      await _openMessageMenu(tester, targetBubble);
      await tester.tap(find.text(copy.chatMessageSelectAction));
      await tester.pumpAndSettle();
      final outboundRow = find.byKey(
        const ValueKey<String>('chat-message-row-seed-main-outbound-2'),
      );
      await tester.ensureVisible(outboundRow);
      await tester.tap(outboundRow);
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const ValueKey<String>('chat-selection-copy')),
      );
      await tester.pumpAndSettle();

      expect(platformCalls, isNotEmpty);
      expect(platformCalls.last.method, 'Clipboard.setData');
      expect(platformCalls.last.arguments, <String, dynamic>{
        'text':
            '${copy.chatSeedSafeModeAsks}\n\n${copy.chatSeedShowCurrentLimits}',
      });
    },
  );

  testWidgets('selection toolbar deletes all selected messages', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    await tester.pumpWidget(_buildHarness(copy));
    await tester.pumpAndSettle();

    final targetBubble = find.byKey(
      const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
    );

    await _openMessageMenu(tester, targetBubble);
    await tester.tap(find.text(copy.chatMessageSelectAction));
    await tester.pumpAndSettle();
    final outboundRow = find.byKey(
      const ValueKey<String>('chat-message-row-seed-main-outbound-2'),
    );
    await tester.ensureVisible(outboundRow);
    await tester.tap(outboundRow);
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey<String>('chat-selection-delete')),
    );
    await tester.pumpAndSettle();

    expect(find.text(copy.chatSeedSafeModeAsks), findsNothing);
    expect(find.text(copy.chatSeedShowCurrentLimits), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('chat-selection-toolbar')),
      findsNothing,
    );
  });

  testWidgets('system back exits chat multi-select mode before popping', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    await tester.pumpWidget(_buildHarness(copy));
    await tester.pumpAndSettle();

    final targetBubble = find.byKey(
      const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
    );

    await _openMessageMenu(tester, targetBubble);
    await tester.tap(find.text(copy.chatMessageSelectAction));
    await tester.pumpAndSettle();

    expect(find.text(copy.chatSelectionCount(1)), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.byType(OpenCrayChatFeature), findsOneWidget);
    expect(find.text(copy.chatSelectionCount(1)), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('chat-selection-toolbar')),
      findsNothing,
    );
  });

  testWidgets('selection mode emits selection haptics on enter and toggle', (
    tester,
  ) async {
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

    await _openMessageMenu(tester, targetBubble);
    await tester.tap(find.text(copy.chatMessageSelectAction));
    await tester.pumpAndSettle();

    final outboundRow = find.byKey(
      const ValueKey<String>('chat-message-row-seed-main-outbound-2'),
    );
    await tester.ensureVisible(outboundRow);
    await tester.tap(outboundRow);
    await tester.pumpAndSettle();

    final selectionHaptics = platformCalls.where(
      (call) =>
          call.method == 'HapticFeedback.vibrate' &&
          call.arguments == 'HapticFeedbackType.selectionClick',
    );
    expect(selectionHaptics.length, greaterThanOrEqualTo(2));
  });

  testWidgets(
    'agent redo rewinds to the prompt and regenerates with the bridge',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = OpenCraySeedBridge(
        initialChatSnapshot: _seedBridgeChatSnapshotWithPromptAttachment(copy),
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
      _jumpToChatEnd(tester);
      await tester.pumpAndSettle();

      final snapshot = await bridge.loadChatSnapshot();
      final outboundMessage = snapshot.messages[snapshot.messages.length - 2];

      expect(find.text(copy.chatSeedSafeModeAsks), findsNothing);
      expect(find.text(copy.chatSeedShowCurrentLimits), findsNothing);
      expect(
        find.text('Seed bridge stored your message locally.'),
        findsOneWidget,
      );
      expect(outboundMessage.kind, 'outbound');
      expect(outboundMessage.text, copy.chatSeedWhyWritePending);
      expect(outboundMessage.attachments, hasLength(1));
      expect(
        outboundMessage.attachments.single.displayName,
        'camera_first.jpg',
      );
      expect(
        outboundMessage.attachments.single.localPath,
        '.opencray/chat/camera_first.jpg',
      );
    },
  );

  testWidgets(
    'user edit recalls the turn and restores the prompt into the composer',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = OpenCraySeedBridge(
        initialChatSnapshot: _seedBridgeChatSnapshotWithPromptAttachment(copy),
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
      expect(find.text('camera_first.jpg'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-seed-main-outbound-1')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-seed-main-inbound-2')),
        findsNothing,
      );

      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pumpAndSettle();

      final snapshot = await bridge.loadChatSnapshot();
      final outboundMessage = snapshot.messages[snapshot.messages.length - 2];
      expect(outboundMessage.kind, 'outbound');
      expect(outboundMessage.text, copy.chatSeedWhyWritePending);
      expect(outboundMessage.attachments, hasLength(1));
      expect(
        outboundMessage.attachments.single.displayName,
        'camera_first.jpg',
      );
      expect(
        outboundMessage.attachments.single.localPath,
        '.opencray/chat/camera_first.jpg',
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

Color? _rowHighlightColor(WidgetTester tester, String messageId) {
  final AnimatedContainer container = tester.widget<AnimatedContainer>(
    find.byKey(ValueKey<String>('chat-message-row-bg-$messageId')),
  );
  final BoxDecoration decoration = container.decoration! as BoxDecoration;
  return decoration.color;
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

OpenCrayChatSnapshot _seedBridgeChatSnapshotWithPromptAttachment(
  OpenCrayUiCopy copy,
) {
  final base = _seedBridgeChatSnapshot(copy);
  return OpenCrayChatSnapshot(
    screenTitle: base.screenTitle,
    modeLabel: base.modeLabel,
    sessionButtonLabel: base.sessionButtonLabel,
    composerPlaceholder: base.composerPlaceholder,
    summary: base.summary,
    messages: base.messages
        .map(
          (message) => message.messageId == 'seed-main-outbound-1'
              ? OpenCrayChatMessageSnapshot(
                  messageId: message.messageId,
                  kind: message.kind,
                  text: message.text,
                  meta: message.meta,
                  createdAtEpochMs: message.createdAtEpochMs,
                  isEphemeral: message.isEphemeral,
                  attachments: const <OpenCrayChatAttachmentSnapshot>[
                    OpenCrayChatAttachmentSnapshot(
                      attachmentId: 'prompt-image-1',
                      kind: 'image',
                      displayName: 'camera_first.jpg',
                      localPath: '.opencray/chat/camera_first.jpg',
                      mimeType: 'image/jpeg',
                      sizeBytes: 2048,
                      widthPx: 1440,
                      heightPx: 1080,
                    ),
                  ],
                )
              : message,
        )
        .toList(growable: false),
    drawer: base.drawer,
    isInputEnabled: base.isInputEnabled,
    pendingApprovals: base.pendingApprovals,
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

void _jumpToChatEnd(WidgetTester tester) {
  final scrollableState = tester.state<ScrollableState>(
    find
        .descendant(
          of: find.byKey(const ValueKey<String>('chat-scroll-view')),
          matching: find.byType(Scrollable),
        )
        .first,
  );
  scrollableState.position.jumpTo(scrollableState.position.maxScrollExtent);
}
