import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/features/chat/chat_feature.dart';

void main() {
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
      expect(find.text(copy.chatMessageRecallAction), findsOneWidget);
      expect(find.text(copy.chatMessageDeleteAction), findsOneWidget);
      expect(find.text(copy.chatMessageSelectAction), findsOneWidget);
      expect(find.text(copy.chatMessageQuoteAction), findsOneWidget);

      await tester.tap(find.text(copy.chatMessageQuoteAction));
      await tester.pumpAndSettle();

      final composer = tester.widget<TextField>(find.byType(TextField));
      expect(
        composer.controller?.text,
        '> ${copy.chatSeedSafeModeAsks}\n\n',
      );
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
}

Widget _buildHarness(OpenCrayUiCopy copy) {
  return MaterialApp(
    home: Scaffold(body: OpenCrayChatFeature(copy: copy)),
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
