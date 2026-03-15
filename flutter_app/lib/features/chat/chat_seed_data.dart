import 'package:flutter/material.dart';

import '../../core/copy/opencray_ui_copy.dart';
import 'chat_models.dart';

class OpenCrayChatSeedData {
  const OpenCrayChatSeedData._();

  static ChatSessionsDrawerState _drawer(OpenCrayUiCopy copy) =>
      ChatSessionsDrawerState(
        eyebrow: copy.chatSeedDrawerEyebrow,
        title: copy.chatSeedRecentSessions,
        ctaLabel: copy.chatSeedNewSession,
        sessions: <ChatSessionListItemData>[
          ChatSessionListItemData(
            sessionId: 'seed-refine-mobile-layout',
            title: copy.chatSeedRefineLayoutTitle,
            preview: copy.chatSeedRefineLayoutPreview,
            meta: copy.chatSeedNow,
            isSelected: true,
          ),
          ChatSessionListItemData(
            sessionId: 'seed-review-shell-limits',
            title: copy.chatSeedReviewShellTitle,
            preview: copy.chatSeedReviewShellPreview,
            meta: copy.chatSeedMinutesAgo,
          ),
          ChatSessionListItemData(
            sessionId: 'seed-prepare-flutter-shell',
            title: copy.chatSeedPrepareShellTitle,
            preview: copy.chatSeedPrepareShellPreview,
            meta: copy.chatSeedYesterday,
          ),
        ],
      );

  static ChatSessionSummary _summary(OpenCrayUiCopy copy) => ChatSessionSummary(
    title: copy.chatSeedSummaryTitle,
    badge: copy.chatSeedSummaryBadge,
    body: copy.chatSeedSummaryBody,
  );

  static List<ChatMessageData> _mainMessages(OpenCrayUiCopy copy) =>
      <ChatMessageData>[
        ChatMessageData(kind: ChatMessageKind.timeline, text: copy.chatToday),
        ChatMessageData(
          kind: ChatMessageKind.inbound,
          text: copy.chatSeedWorkspaceReady,
        ),
        ChatMessageData(
          kind: ChatMessageKind.outbound,
          text: copy.chatSeedWhyWritePending,
        ),
        ChatMessageData(
          kind: ChatMessageKind.inbound,
          text: copy.chatSeedSafeModeAsks,
        ),
        ChatMessageData(
          kind: ChatMessageKind.outbound,
          text: copy.chatSeedShowCurrentLimits,
        ),
      ];

  static List<ChatMessageData> _attachmentMessages(OpenCrayUiCopy copy) =>
      <ChatMessageData>[
        ChatMessageData(kind: ChatMessageKind.timeline, text: copy.chatToday),
        ChatMessageData(
          kind: ChatMessageKind.inbound,
          text: copy.chatSeedDropFileHint,
        ),
        ChatMessageData(
          kind: ChatMessageKind.outbound,
          text: copy.chatSeedUseTwoFiles,
        ),
      ];

  static List<ChatMessageData> _commandMessages(OpenCrayUiCopy copy) =>
      <ChatMessageData>[
        ChatMessageData(kind: ChatMessageKind.timeline, text: copy.chatToday),
        ChatMessageData(
          kind: ChatMessageKind.inbound,
          text: copy.chatSeedChooseCommand,
        ),
        ChatMessageData(
          kind: ChatMessageKind.outbound,
          text: copy.chatSeedOpenWorkspaceCommand,
        ),
      ];

  static List<ChatMessageData> _addMenuMessages(OpenCrayUiCopy copy) =>
      <ChatMessageData>[
        ChatMessageData(
          kind: ChatMessageKind.inbound,
          text: copy.chatSeedAddBeforeSending,
        ),
      ];

  static List<ChatAttachmentData> sampleAttachments(OpenCrayUiCopy copy) =>
      <ChatAttachmentData>[
        ChatAttachmentData(
          kind: ChatAttachmentKind.image,
          label: 'workspace-shot.png',
          detail: copy.chatSeedImageDetail,
          accentColor: const Color(0xFFE6F0FF),
        ),
        ChatAttachmentData(
          kind: ChatAttachmentKind.file,
          label: 'mobile-ui-layout-spec.md',
          detail: copy.chatSeedFileDetail,
          accentColor: const Color(0xFFF2F3F7),
        ),
      ];

  static List<ChatCommandOptionData> sampleCommandOptions(
    OpenCrayUiCopy copy,
  ) => <ChatCommandOptionData>[
    ChatCommandOptionData(
      label: copy.chatActionCommand,
      description: copy.chatCommandActionDescription,
    ),
    ChatCommandOptionData(
      label: copy.chatCommandPatch,
      description: copy.chatPatchActionDescription,
    ),
    ChatCommandOptionData(
      label: copy.chatCommandReview,
      description: copy.chatReviewActionDescription,
    ),
  ];

  static List<ChatAddActionData> sampleAddActions(
    OpenCrayUiCopy copy,
  ) => <ChatAddActionData>[
    ChatAddActionData(label: copy.chatActionImage, icon: Icons.image_outlined),
    ChatAddActionData(
      label: copy.chatActionFile,
      icon: Icons.attach_file_rounded,
    ),
    ChatAddActionData(
      label: copy.chatActionCommand,
      icon: Icons.terminal_rounded,
    ),
  ];

  static ChatFeatureState main(OpenCrayUiCopy copy) {
    return ChatFeatureState(
      variant: ChatPrototypeVariant.main,
      screenTitle: copy.chatSeedScreenTitle,
      summary: _summary(copy),
      messages: _mainMessages(copy),
      runTraces: const <ChatRunTraceData>[],
      composer: ChatComposerState(placeholder: copy.chatComposerPlaceholder),
      drawer: _drawer(copy),
    );
  }

  static ChatFeatureState empty(OpenCrayUiCopy copy) {
    return ChatFeatureState(
      variant: ChatPrototypeVariant.empty,
      screenTitle: copy.chatSeedScreenTitle,
      summary: ChatSessionSummary(
        title: copy.chatSeedEmptyTitle,
        badge: copy.chatSeedEmptyBadge,
        body: copy.chatSeedEmptyBody,
      ),
      messages: const <ChatMessageData>[],
      runTraces: const <ChatRunTraceData>[],
      composer: ChatComposerState(placeholder: copy.chatComposerPlaceholder),
      drawer: _drawer(copy),
      emptyThreadHeight: 280,
    );
  }

  static ChatFeatureState attachments(OpenCrayUiCopy copy) {
    return ChatFeatureState(
      variant: ChatPrototypeVariant.attachments,
      screenTitle: copy.chatSeedScreenTitle,
      summary: ChatSessionSummary(
        title: copy.chatSeedAttachmentsTitle,
        badge: copy.chatSeedAttachmentsBadge,
        body: copy.chatSeedAttachmentsBody,
      ),
      messages: _attachmentMessages(copy),
      runTraces: const <ChatRunTraceData>[],
      composer: ChatComposerState(
        placeholder: copy.chatComposerPlaceholder,
        attachments: sampleAttachments(copy),
      ),
      drawer: _drawer(copy),
    );
  }

  static ChatFeatureState commandMenu(OpenCrayUiCopy copy) {
    return ChatFeatureState(
      variant: ChatPrototypeVariant.commandMenu,
      screenTitle: copy.chatSeedScreenTitle,
      summary: ChatSessionSummary(
        title: copy.chatSeedCommandTitle,
        badge: copy.chatSeedCommandBadge,
        body: copy.chatSeedCommandBody,
      ),
      messages: _commandMessages(copy),
      runTraces: const <ChatRunTraceData>[],
      composer: ChatComposerState(
        placeholder: copy.chatComposerPlaceholder,
        selectedCommand: copy.chatActionCommand,
        commandOptions: sampleCommandOptions(copy),
      ),
      drawer: _drawer(copy),
    );
  }

  static ChatFeatureState addMenu(OpenCrayUiCopy copy) {
    return ChatFeatureState(
      variant: ChatPrototypeVariant.addMenu,
      screenTitle: copy.chatSeedScreenTitle,
      summary: ChatSessionSummary(
        title: copy.chatSeedAddMenuTitle,
        badge: copy.chatSeedAddMenuBadge,
        body: copy.chatSeedAddMenuBody,
      ),
      messages: _addMenuMessages(copy),
      runTraces: const <ChatRunTraceData>[],
      composer: ChatComposerState(
        placeholder: copy.chatComposerPlaceholder,
        showAddMenu: true,
        addActions: sampleAddActions(copy),
      ),
      drawer: _drawer(copy),
      emptyThreadHeight: 170,
    );
  }

  static ChatFeatureState drawerOpen(OpenCrayUiCopy copy) {
    return main(copy).copyWith(drawerOpen: true);
  }
}
