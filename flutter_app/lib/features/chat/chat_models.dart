import 'package:flutter/material.dart';

enum ChatPrototypeVariant { main, empty, attachments, commandMenu, addMenu }

enum ChatMessageKind { timeline, inbound, outbound }

enum ChatAttachmentKind { image, file }

@immutable
class ChatFeatureState {
  const ChatFeatureState({
    required this.variant,
    required this.screenTitle,
    required this.summary,
    required this.messages,
    required this.composer,
    required this.drawer,
    this.pendingApprovals = const <ChatPendingApprovalData>[],
    this.modeLabel = 'SAFE',
    this.drawerOpen = false,
    this.sessionButtonLabel = 'Sessions',
    this.emptyThreadHeight = 0,
    this.isInputEnabled = true,
  });

  final ChatPrototypeVariant variant;
  final String screenTitle;
  final ChatSessionSummary summary;
  final List<ChatMessageData> messages;
  final ChatComposerState composer;
  final ChatSessionsDrawerState drawer;
  final List<ChatPendingApprovalData> pendingApprovals;
  final String modeLabel;
  final bool drawerOpen;
  final String sessionButtonLabel;
  final double emptyThreadHeight;
  final bool isInputEnabled;

  ChatFeatureState copyWith({
    ChatPrototypeVariant? variant,
    String? screenTitle,
    ChatSessionSummary? summary,
    List<ChatMessageData>? messages,
    ChatComposerState? composer,
    ChatSessionsDrawerState? drawer,
    List<ChatPendingApprovalData>? pendingApprovals,
    String? modeLabel,
    bool? drawerOpen,
    String? sessionButtonLabel,
    double? emptyThreadHeight,
    bool? isInputEnabled,
  }) {
    return ChatFeatureState(
      variant: variant ?? this.variant,
      screenTitle: screenTitle ?? this.screenTitle,
      summary: summary ?? this.summary,
      messages: messages ?? this.messages,
      composer: composer ?? this.composer,
      drawer: drawer ?? this.drawer,
      pendingApprovals: pendingApprovals ?? this.pendingApprovals,
      modeLabel: modeLabel ?? this.modeLabel,
      drawerOpen: drawerOpen ?? this.drawerOpen,
      sessionButtonLabel: sessionButtonLabel ?? this.sessionButtonLabel,
      emptyThreadHeight: emptyThreadHeight ?? this.emptyThreadHeight,
      isInputEnabled: isInputEnabled ?? this.isInputEnabled,
    );
  }
}

@immutable
class ChatSessionSummary {
  const ChatSessionSummary({
    required this.title,
    required this.badge,
    required this.body,
  });

  final String title;
  final String badge;
  final String body;
}

@immutable
class ChatMessageData {
  const ChatMessageData({
    required this.kind,
    required this.text,
    this.meta = '',
  });

  final ChatMessageKind kind;
  final String text;
  final String meta;
}

@immutable
class ChatPendingApprovalData {
  const ChatPendingApprovalData({
    required this.runId,
    required this.taskId,
    required this.title,
    required this.body,
    required this.approveLabel,
    required this.rejectLabel,
    required this.isHighRisk,
  });

  final String runId;
  final String taskId;
  final String title;
  final String body;
  final String approveLabel;
  final String rejectLabel;
  final bool isHighRisk;

  String get approvalId => runId.isNotEmpty ? runId : taskId;
}

@immutable
class ChatComposerState {
  const ChatComposerState({
    this.placeholder = 'Message OpenCray',
    this.attachments = const <ChatAttachmentData>[],
    this.selectedCommand,
    this.commandOptions = const <ChatCommandOptionData>[],
    this.addActions = const <ChatAddActionData>[],
    this.showAddMenu = false,
  });

  final String placeholder;
  final List<ChatAttachmentData> attachments;
  final String? selectedCommand;
  final List<ChatCommandOptionData> commandOptions;
  final List<ChatAddActionData> addActions;
  final bool showAddMenu;

  ChatComposerState copyWith({
    String? placeholder,
    List<ChatAttachmentData>? attachments,
    String? selectedCommand,
    List<ChatCommandOptionData>? commandOptions,
    List<ChatAddActionData>? addActions,
    bool? showAddMenu,
    bool clearSelectedCommand = false,
  }) {
    return ChatComposerState(
      placeholder: placeholder ?? this.placeholder,
      attachments: attachments ?? this.attachments,
      selectedCommand: clearSelectedCommand
          ? null
          : (selectedCommand ?? this.selectedCommand),
      commandOptions: commandOptions ?? this.commandOptions,
      addActions: addActions ?? this.addActions,
      showAddMenu: showAddMenu ?? this.showAddMenu,
    );
  }
}

@immutable
class ChatAttachmentData {
  const ChatAttachmentData({
    required this.kind,
    required this.label,
    required this.detail,
    required this.accentColor,
  });

  final ChatAttachmentKind kind;
  final String label;
  final String detail;
  final Color accentColor;
}

@immutable
class ChatCommandOptionData {
  const ChatCommandOptionData({required this.label, required this.description});

  final String label;
  final String description;
}

@immutable
class ChatAddActionData {
  const ChatAddActionData({required this.label, required this.icon});

  final String label;
  final IconData icon;
}

@immutable
class ChatSessionsDrawerState {
  const ChatSessionsDrawerState({
    required this.eyebrow,
    required this.title,
    required this.ctaLabel,
    required this.sessions,
  });

  final String eyebrow;
  final String title;
  final String ctaLabel;
  final List<ChatSessionListItemData> sessions;
}

@immutable
class ChatSessionListItemData {
  const ChatSessionListItemData({
    required this.sessionId,
    required this.title,
    required this.preview,
    required this.meta,
    this.isSelected = false,
  });

  final String sessionId;
  final String title;
  final String preview;
  final String meta;
  final bool isSelected;
}
