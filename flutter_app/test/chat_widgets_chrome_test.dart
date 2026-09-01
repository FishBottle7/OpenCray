import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_sandbox_settings.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

void main() {
  testWidgets('host rebuild stays silent in chat ui', (tester) async {
    final runtimeSnapshots =
        StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
    addTearDown(runtimeSnapshots.close);
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
      findsNothing,
    );
  });

  testWidgets('host-backed chat does not show seed content while loading', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
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

    expect(find.text(copy.chatSeedWorkspaceReady), findsNothing);
    expect(find.text(copy.chatSeedSafeModeAsks), findsNothing);
    expect(find.text('Inspect the workspace.'), findsNothing);
    expect(find.text(copy.chatSeedEmptyTitle), findsOneWidget);

    bridge.loadChatSnapshotCompleter!.complete(bridge.chatSnapshot);
    await tester.pumpAndSettle();

    expect(find.text('Inspect the workspace.'), findsOneWidget);
  });

  testWidgets(
    'runtime streaming does not force-scroll when the reader is away from bottom',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: streamingScrollMessages(),
          updatedAtEpochMs: 1000,
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-scroll',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          updatedAtEpochMs: 1000,
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

      final ScrollController controller = mainChatScrollController(tester);
      expect(controller.position.maxScrollExtent, greaterThan(0));
      controller.jumpTo(0);
      await tester.pump();
      final double previousMaxExtent = controller.position.maxScrollExtent;

      runtimeSnapshots.add(
        streamingProcessRuntimeSnapshot(
          output: streamingProcessOutput(18),
          updatedAtEpochMs: 2000,
        ),
      );
      await tester.pump(const Duration(milliseconds: 20));
      await tester.pumpAndSettle();

      expect(
        controller.position.maxScrollExtent,
        greaterThan(previousMaxExtent),
      );
      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-streaming-indicator-runtime-process-task-scroll-process-scroll',
          ),
        ),
        findsOneWidget,
      );
      expect(controller.position.pixels, lessThan(8));
    },
  );

  testWidgets(
    'runtime streaming follows a pinned bottom without waiting for scroll animation',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
          messages: streamingScrollMessages(),
          updatedAtEpochMs: 1000,
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-scroll',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          updatedAtEpochMs: 1000,
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

      final ScrollController controller = mainChatScrollController(tester);
      controller.jumpTo(controller.position.maxScrollExtent);
      await tester.pump();
      final double previousMaxExtent = controller.position.maxScrollExtent;

      runtimeSnapshots.add(
        streamingProcessRuntimeSnapshot(
          output: streamingProcessOutput(18),
          updatedAtEpochMs: 2000,
        ),
      );
      await tester.pump(const Duration(milliseconds: 20));
      await tester.pump();

      expect(
        controller.position.maxScrollExtent,
        greaterThan(previousMaxExtent),
      );
      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-streaming-indicator-runtime-process-task-scroll-process-scroll',
          ),
        ),
        findsOneWidget,
      );
      expect(
        (controller.position.maxScrollExtent - controller.position.pixels)
            .abs(),
        lessThan(1),
      );
    },
  );

  testWidgets('chat top utility bar keeps frequent controls visually quiet', (
    tester,
  ) async {
    await tester.pumpWidget(buildChatHarness());
    await tester.pumpAndSettle();

    expect(chatSessionsButton(), findsOneWidget);
    expect(find.text('Sessions'), findsNothing);

    // The strip carries the automation mode and nothing else. Picking a runtime
    // backend is configuration and lives in Settings, so neither the label nor a
    // switcher for it belongs up here.
    expect(
      find.byKey(const ValueKey<String>('chat-runtime-mode-label')),
      findsOneWidget,
    );
    expect(find.text('SAFE'), findsOneWidget);
    expect(find.text('Local'), findsNothing);
    expect(find.text('Cloud'), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('chat-runtime-environment-selector')),
      findsNothing,
    );
  });

  testWidgets('chat transcript rows are lazily built while scrolling', (
    tester,
  ) async {
    final messages = List<ChatMessageData>.generate(
      120,
      (index) => ChatMessageData(
        messageId: 'lazy-message-$index',
        kind: index.isEven ? ChatMessageKind.inbound : ChatMessageKind.outbound,
        text: 'Transcript message $index',
      ),
    );

    await tester.pumpWidget(buildChatHarness(messages: messages));
    await tester.pumpAndSettle();

    final firstBubble = find.byKey(
      const ValueKey<String>('chat-bubble-lazy-message-0'),
    );
    final lastBubble = find.byKey(
      const ValueKey<String>('chat-bubble-lazy-message-119'),
    );

    final scrollView = chatScrollView();
    final scrollableState = scrollableStateFor(tester, scrollView);
    scrollableState.position.jumpTo(0);
    await tester.pumpAndSettle();

    expect(firstBubble, findsOneWidget);
    expect(lastBubble, findsNothing);

    // Lazy viewports re-estimate maxScrollExtent as rows build; keep jumping
    // until the reported end stops moving so the real bottom is reached.
    double previousExtent = -1;
    while (scrollableState.position.maxScrollExtent != previousExtent) {
      previousExtent = scrollableState.position.maxScrollExtent;
      scrollableState.position.jumpTo(previousExtent);
      await tester.pumpAndSettle();
    }

    expect(lastBubble, findsOneWidget);
  });

}
