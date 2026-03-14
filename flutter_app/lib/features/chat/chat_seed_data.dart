import 'package:flutter/material.dart';

import 'chat_models.dart';

class OpenCrayChatSeedData {
  const OpenCrayChatSeedData._();

  static final ChatSessionsDrawerState _drawer = ChatSessionsDrawerState(
    eyebrow: 'SESSION HISTORY',
    title: 'Recent sessions',
    ctaLabel: 'New session',
    sessions: const <ChatSessionListItemData>[
      ChatSessionListItemData(
        sessionId: 'seed-refine-mobile-layout',
        title: 'Refine mobile layout',
        preview: 'Safe mode still asks before edits in this session.',
        meta: 'Now',
        isSelected: true,
      ),
      ChatSessionListItemData(
        sessionId: 'seed-review-shell-limits',
        title: 'Review shell limits',
        preview: 'Summarize the current permission boundaries.',
        meta: '18 min ago',
      ),
      ChatSessionListItemData(
        sessionId: 'seed-prepare-flutter-shell',
        title: 'Prepare Flutter shell',
        preview: 'Split the migration into host and presentation layers.',
        meta: 'Yesterday',
      ),
    ],
  );

  static const ChatSessionSummary _summary = ChatSessionSummary(
    title: 'Refine mobile layout',
    badge: '3 pending',
    body: 'Safe mode still asks before edits in this session.',
  );

  static const List<ChatMessageData> _mainMessages = <ChatMessageData>[
    ChatMessageData(kind: ChatMessageKind.timeline, text: 'Today'),
    ChatMessageData(
      kind: ChatMessageKind.inbound,
      text: 'Workspace ready. I can inspect or edit.',
    ),
    ChatMessageData(
      kind: ChatMessageKind.outbound,
      text: 'Why is write access pending?',
    ),
    ChatMessageData(
      kind: ChatMessageKind.inbound,
      text: 'Safe mode still asks before edits.',
    ),
    ChatMessageData(
      kind: ChatMessageKind.outbound,
      text: 'Show current limits.',
    ),
  ];

  static const List<ChatMessageData> _attachmentMessages = <ChatMessageData>[
    ChatMessageData(kind: ChatMessageKind.timeline, text: 'Today'),
    ChatMessageData(
      kind: ChatMessageKind.inbound,
      text: 'Drop a screenshot or workspace file into the next prompt.',
    ),
    ChatMessageData(
      kind: ChatMessageKind.outbound,
      text: 'Use these two files in the next pass.',
    ),
  ];

  static const List<ChatMessageData> _commandMessages = <ChatMessageData>[
    ChatMessageData(kind: ChatMessageKind.timeline, text: 'Today'),
    ChatMessageData(
      kind: ChatMessageKind.inbound,
      text: 'Choose a command shortcut or keep typing.',
    ),
    ChatMessageData(
      kind: ChatMessageKind.outbound,
      text: 'Open a command for the workspace root.',
    ),
  ];

  static const List<ChatMessageData> _addMenuMessages = <ChatMessageData>[
    ChatMessageData(
      kind: ChatMessageKind.inbound,
      text: 'Add an image, file, or command before sending.',
    ),
  ];

  static const List<ChatAttachmentData> _attachments = <ChatAttachmentData>[
    ChatAttachmentData(
      kind: ChatAttachmentKind.image,
      label: 'workspace-shot.png',
      detail: 'Image · 1.8 MB',
      accentColor: Color(0xFFE6F0FF),
    ),
    ChatAttachmentData(
      kind: ChatAttachmentKind.file,
      label: 'mobile-ui-layout-spec.md',
      detail: 'File · 12 KB',
      accentColor: Color(0xFFF2F3F7),
    ),
  ];

  static const List<ChatCommandOptionData> _commandOptions =
      <ChatCommandOptionData>[
        ChatCommandOptionData(
          label: 'Command',
          description: 'Run a workspace shell command with approval.',
        ),
        ChatCommandOptionData(
          label: 'Patch',
          description: 'Apply a structured file patch to the current repo.',
        ),
        ChatCommandOptionData(
          label: 'Review',
          description: 'Scan the current change list and summarize risks.',
        ),
      ];

  static const List<ChatAddActionData> _addActions = <ChatAddActionData>[
    ChatAddActionData(label: 'Image', icon: Icons.image_outlined),
    ChatAddActionData(label: 'File', icon: Icons.attach_file_rounded),
    ChatAddActionData(label: 'Command', icon: Icons.terminal_rounded),
  ];

  static ChatFeatureState main() {
    return ChatFeatureState(
      variant: ChatPrototypeVariant.main,
      screenTitle: 'Chat',
      summary: _summary,
      messages: _mainMessages,
      composer: const ChatComposerState(),
      drawer: _drawer,
    );
  }

  static ChatFeatureState empty() {
    return ChatFeatureState(
      variant: ChatPrototypeVariant.empty,
      screenTitle: 'Chat',
      summary: const ChatSessionSummary(
        title: 'Start a new session',
        badge: 'No history yet',
        body: 'Message OpenCray with a task, file, image, or command.',
      ),
      messages: const <ChatMessageData>[],
      composer: const ChatComposerState(),
      drawer: _drawer,
      emptyThreadHeight: 280,
    );
  }

  static ChatFeatureState attachments() {
    return ChatFeatureState(
      variant: ChatPrototypeVariant.attachments,
      screenTitle: 'Chat',
      summary: const ChatSessionSummary(
        title: 'Review attached files',
        badge: '2 items ready',
        body: 'These attachments stay with the next message only.',
      ),
      messages: _attachmentMessages,
      composer: const ChatComposerState(attachments: _attachments),
      drawer: _drawer,
    );
  }

  static ChatFeatureState commandMenu() {
    return ChatFeatureState(
      variant: ChatPrototypeVariant.commandMenu,
      screenTitle: 'Chat',
      summary: const ChatSessionSummary(
        title: 'Command shortcuts',
        badge: 'Safe mode',
        body: 'Choose a command surface before sending the next prompt.',
      ),
      messages: _commandMessages,
      composer: const ChatComposerState(
        selectedCommand: 'Command',
        commandOptions: _commandOptions,
      ),
      drawer: _drawer,
    );
  }

  static ChatFeatureState addMenu() {
    return ChatFeatureState(
      variant: ChatPrototypeVariant.addMenu,
      screenTitle: 'Chat',
      summary: const ChatSessionSummary(
        title: 'Prepare the next turn',
        badge: 'Composer open',
        body: 'Add context before you send the next request.',
      ),
      messages: _addMenuMessages,
      composer: const ChatComposerState(
        showAddMenu: true,
        addActions: _addActions,
      ),
      drawer: _drawer,
      emptyThreadHeight: 170,
    );
  }

  static ChatFeatureState drawerOpen() {
    return main().copyWith(drawerOpen: true);
  }

  static List<ChatAttachmentData> sampleAttachments() =>
      List<ChatAttachmentData>.of(_attachments);

  static List<ChatCommandOptionData> sampleCommandOptions() =>
      List<ChatCommandOptionData>.of(_commandOptions);

  static List<ChatAddActionData> sampleAddActions() =>
      List<ChatAddActionData>.of(_addActions);
}
