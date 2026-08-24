
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_draft_attachment.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_file_image_preview.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

void main() {
  testWidgets('tapping outside the composer dismisses chat input focus', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-focus',
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

    await tester.tap(find.byType(TextField));
    await tester.pump();
    TextField composerField = tester.widget<TextField>(find.byType(TextField));
    expect(composerField.focusNode?.hasFocus ?? false, isTrue);

    await tester.tapAt(const Offset(24, 24));
    await tester.pump();
    composerField = tester.widget<TextField>(find.byType(TextField));
    expect(composerField.focusNode?.hasFocus ?? false, isFalse);
  });

  testWidgets('plus menu expands inside animated composer surface', (
    tester,
  ) async {
    await tester.pumpWidget(buildChatHarness());
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-composer-plus-button')),
    );
    await tester.pump();

    final addMenuFinder = find.byKey(
      const ValueKey<String>('chat-composer-add-menu'),
    );
    final addTrayFinder = find.byKey(
      const ValueKey<String>('chat-composer-add-tray'),
    );
    expect(addMenuFinder, findsOneWidget);
    expect(addTrayFinder, findsOneWidget);
    expect(
      find.descendant(of: addTrayFinder, matching: addMenuFinder),
      findsOneWidget,
    );
    expect(
      find.ancestor(of: addMenuFinder, matching: find.byType(AnimatedSwitcher)),
      findsNothing,
    );
    expect(
      find.descendant(of: addTrayFinder, matching: find.byType(Align)),
      findsOneWidget,
    );
    expect(
      find.ancestor(of: addMenuFinder, matching: find.byType(AnimatedSize)),
      findsWidgets,
    );

    await tester.pumpAndSettle();

    final plusIcon = tester.widget<Icon>(
      find.descendant(
        of: find.byKey(const ValueKey<String>('chat-composer-plus-button')),
        matching: find.byIcon(Icons.add_rounded),
      ),
    );
    expect(plusIcon.color, const Color(0xFF2563EB));
  });

  testWidgets('composer keeps mixed sections in a stable vertical order', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    await tester.pumpWidget(
      buildChatHarness(
        composer: ChatComposerState(
          placeholder: copy.chatComposerPlaceholder,
          todos: const <ChatTodoItemData>[
            ChatTodoItemData(
              content: 'Review composer stack',
              status: ChatTodoStatus.inProgress,
            ),
          ],
          commandOptions: const <ChatCommandOptionData>[
            ChatCommandOptionData(label: '/plan', description: 'Plan the work'),
          ],
          attachments: <ChatAttachmentData>[
            ChatAttachmentData(
              id: 'draft-mixed-1',
              kind: ChatAttachmentKind.file,
              label: 'notes.md',
              detail: '4 KB',
              accentColor: Colors.blue,
              draftAttachment: const OpenCrayChatDraftAttachment(
                kind: OpenCrayChatDraftAttachmentKind.file,
                displayName: 'notes.md',
                relativePath: 'docs/notes.md',
                mimeType: 'text/markdown',
                sizeBytes: 4096,
              ),
            ),
          ],
          addActions: const <ChatAddActionData>[
            ChatAddActionData(label: 'Attach file', icon: Icons.attach_file),
            ChatAddActionData(label: 'Command', icon: Icons.terminal_rounded),
          ],
          showAddMenu: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    final double todoY = tester
        .getTopLeft(
          find.byKey(const ValueKey<String>('chat-composer-todo-list')),
        )
        .dy;
    final double commandY = tester.getTopLeft(find.text(copy.chatCommands)).dy;
    final double attachmentY = tester
        .getTopLeft(
          find.byKey(const ValueKey<String>('chat-composer-attachments')),
        )
        .dy;
    final double inputY = tester.getTopLeft(find.byType(TextField)).dy;
    final double trayY = tester
        .getTopLeft(
          find.byKey(const ValueKey<String>('chat-composer-add-tray')),
        )
        .dy;

    expect(todoY, lessThan(commandY));
    expect(commandY, lessThan(attachmentY));
    expect(attachmentY, lessThan(inputY));
    expect(inputY, lessThan(trayY));
  });

  testWidgets('composer hides todo chrome when todo list is empty', (
    tester,
  ) async {
    await tester.pumpWidget(buildChatHarness());
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
        buildChatHarness(
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
      expect(activeText.style?.color, const Color(0xFF2563EB));
      expect(completedText.style?.decoration, TextDecoration.lineThrough);
      expect(pendingDecoration.color, Colors.transparent);
      expect(
        (pendingDecoration.border! as Border).top.color,
        const Color(0xFFD5DCE6),
      );
      expect(activeDecoration.color, Colors.transparent);
      expect(
        (activeDecoration.border! as Border).top.color,
        const Color(0xFF2563EB),
      );
      expect(completedDecoration.color, const Color(0xFF95A0B1));
    },
  );

  testWidgets('composer todo list expands and collapses from the header', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildChatHarness(
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
        ],
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsOneWidget,
    );
    AnimatedRotation rotation = tester.widget<AnimatedRotation>(
      find.descendant(
        of: find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
        matching: find.byType(AnimatedRotation),
      ),
    );
    expect(rotation.turns, 0.5);
    expect(find.text('Review chat composer layout'), findsOneWidget);
    expect(find.text('Highlighting active todo text'), findsOneWidget);
    expect(find.text('Approve Pencil prototype'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
    );
    await tester.pumpAndSettle();

    expect(find.text('TODO'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsNothing,
    );
    rotation = tester.widget<AnimatedRotation>(
      find.descendant(
        of: find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
        matching: find.byType(AnimatedRotation),
      ),
    );
    expect(rotation.turns, 0);
    expect(find.text('Review chat composer layout'), findsNothing);
    expect(find.text('Highlighting active todo text'), findsNothing);
    expect(find.text('Approve Pencil prototype'), findsNothing);

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsOneWidget,
    );
    rotation = tester.widget<AnimatedRotation>(
      find.descendant(
        of: find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
        matching: find.byType(AnimatedRotation),
      ),
    );
    expect(rotation.turns, 0.5);
    expect(find.text('Review chat composer layout'), findsOneWidget);
    expect(find.text('Highlighting active todo text'), findsOneWidget);
    expect(find.text('Approve Pencil prototype'), findsOneWidget);
  });

  testWidgets(
    'archived completed todos auto-hide after the visibility window',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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

  testWidgets('composer image attachments render a thumbnail preview card', (
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
      imagePreviews: <String, OpenCrayFileImagePreview>{
        '.opencray/chat-drafts/workspace-shot.png': fakeImagePreview(
          name: 'workspace-shot.png',
          relativePath: '.opencray/chat-drafts/workspace-shot.png',
        ),
      },
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

    expect(
      find.byKey(
        const ValueKey<String>(
          'chat-composer-image-preview-.opencray/chat-drafts/workspace-shot.png',
        ),
      ),
      findsOneWidget,
    );
  });

  testWidgets('composer deduplicates repeated attachments with feedback', (
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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

  testWidgets('composer surfaces explicit attachment picking failures', (
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
    bridge.pickChatAttachmentsError = UnsupportedError(
      'Adding attachments is unavailable in local runtime mode.',
    );

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

    expect(
      bridge.shownNativeToasts,
      contains('Adding attachments is unavailable in local runtime mode.'),
    );
  });

  testWidgets('composer shows native feedback when submit fails', (
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
