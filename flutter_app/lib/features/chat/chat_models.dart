import 'package:flutter/material.dart';

import '../../core/models/opencray_chat_draft_attachment.dart';

enum ChatPrototypeVariant { main, empty, attachments, commandMenu, addMenu }

enum ChatMessageKind { timeline, inbound, outbound }

enum ChatAttachmentKind { image, voice, file }

enum ChatTodoStatus { pending, inProgress, completed }

@immutable
class ChatFeatureState {
  const ChatFeatureState({
    required this.variant,
    required this.screenTitle,
    required this.summary,
    required this.messages,
    required this.runTraces,
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
  final List<ChatRunTraceData> runTraces;
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
    List<ChatRunTraceData>? runTraces,
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
      runTraces: runTraces ?? this.runTraces,
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
class ChatRunTraceData {
  const ChatRunTraceData({
    required this.runId,
    required this.taskId,
    this.anchorMessageId = '',
    required this.label,
    required this.body,
    this.history = const <ChatRunTraceHistoryEntry>[],
    this.isHighRisk = false,
    this.isTerminal = false,
    this.canInterrupt = false,
    this.retryLabel,
    this.previewCard,
    this.sessionCard,
  });

  final String runId;
  final String taskId;
  final String anchorMessageId;
  final String label;
  final String body;
  final List<ChatRunTraceHistoryEntry> history;
  final bool isHighRisk;
  final bool isTerminal;
  final bool canInterrupt;
  final String? retryLabel;
  final ChatRunTracePreviewCardData? previewCard;
  final ChatRunTraceSandboxSessionCardData? sessionCard;

  bool get isRetryable => retryLabel?.trim().isNotEmpty == true;

  String get retryId => runId.trim().isNotEmpty ? runId : taskId;

  String get interruptId => runId.trim().isNotEmpty ? runId : taskId;
}

@immutable
class ChatRunTracePreviewCardData {
  const ChatRunTracePreviewCardData({
    required this.url,
    required this.status,
    this.port,
    this.path,
    this.provider,
    this.httpStatusCode,
    this.message,
  });

  final String url;
  final ChatRunTracePreviewStatus status;
  final int? port;
  final String? path;
  final String? provider;
  final int? httpStatusCode;
  final String? message;
}

enum ChatRunTracePreviewStatus { ready, reachable, unreachable, skipped }

@immutable
class ChatRunTraceSandboxSessionCardData {
  const ChatRunTraceSandboxSessionCardData({
    required this.sessionPresent,
    required this.source,
    required this.lifecycleStatus,
    this.provider,
    this.sandboxId,
    this.sandboxDomain,
    this.templateId,
    this.updatedAtEpochMs,
    this.sessionLastActivityAtEpochMs,
    this.sessionStaleAfterEpochMs,
    this.lastPreviewUrl,
    this.lastPreviewProbeStatus,
    this.lastPreviewProbeObservedAtEpochMs,
    this.lastPreviewProbeSource,
    this.autoRefreshAfterMs,
    this.previewCandidatePorts = const <int>[],
    this.runningRequestIds = const <String>[],
  });

  final bool sessionPresent;
  final ChatRunTraceSandboxSessionSource source;
  final ChatRunTraceSandboxSessionLifecycleStatus lifecycleStatus;
  final String? provider;
  final String? sandboxId;
  final String? sandboxDomain;
  final String? templateId;
  final int? updatedAtEpochMs;
  final int? sessionLastActivityAtEpochMs;
  final int? sessionStaleAfterEpochMs;
  final String? lastPreviewUrl;
  final ChatRunTracePreviewStatus? lastPreviewProbeStatus;
  final int? lastPreviewProbeObservedAtEpochMs;
  final String? lastPreviewProbeSource;
  final int? autoRefreshAfterMs;
  final List<int> previewCandidatePorts;
  final List<String> runningRequestIds;
}

enum ChatRunTraceSandboxSessionSource {
  none,
  activeMemory,
  persisted,
  activeAndPersisted,
}

enum ChatRunTraceSandboxSessionLifecycleStatus {
  none,
  active,
  stale,
  reclaimed,
}

@immutable
class ChatRunTraceHistoryEntry {
  const ChatRunTraceHistoryEntry({
    required this.label,
    required this.body,
    this.compactBody,
    this.isHighRisk = false,
    this.inspectorActorId = 'main',
    this.inspectorActorLabel = '',
    this.inspectorCallParts = const <ChatRunTraceInspectorTextPart>[],
    this.inspectorCallDetail = '',
    this.inspectorResultBody = '',
  });

  final String label;
  final String body;
  final String? compactBody;
  final bool isHighRisk;
  final String inspectorActorId;
  final String inspectorActorLabel;
  final List<ChatRunTraceInspectorTextPart> inspectorCallParts;
  final String inspectorCallDetail;
  final String inspectorResultBody;

  bool get hasStructuredInspectorContent =>
      inspectorCallParts.isNotEmpty ||
      inspectorCallDetail.trim().isNotEmpty ||
      inspectorResultBody.trim().isNotEmpty;
}

enum ChatRunTraceInspectorTextSemantic {
  neutral,
  action,
  target,
  scope,
  result,
  connector,
}

@immutable
class ChatRunTraceInspectorTextPart {
  const ChatRunTraceInspectorTextPart({
    required this.text,
    this.semantic = ChatRunTraceInspectorTextSemantic.neutral,
  });

  final String text;
  final ChatRunTraceInspectorTextSemantic semantic;
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
    this.messageId = '',
    required this.kind,
    required this.text,
    this.meta = '',
    this.createdAtEpochMs,
    this.isEphemeral = false,
    this.attachments = const <ChatMessageAttachmentData>[],
  });

  final String messageId;
  final ChatMessageKind kind;
  final String text;
  final String meta;
  final int? createdAtEpochMs;
  final bool isEphemeral;
  final List<ChatMessageAttachmentData> attachments;
}

@immutable
class ChatMessageAttachmentData {
  const ChatMessageAttachmentData({
    this.attachmentId = '',
    required this.kind,
    required this.displayName,
    required this.localPath,
    this.mimeType,
    this.sizeBytes,
    this.widthPx,
    this.heightPx,
    this.durationMs,
    this.waveformBars = const <int>[],
    this.transcriptText,
    this.contentSha256,
  });

  final String attachmentId;
  final ChatAttachmentKind kind;
  final String displayName;
  final String localPath;
  final String? mimeType;
  final int? sizeBytes;
  final int? widthPx;
  final int? heightPx;
  final int? durationMs;
  final List<int> waveformBars;
  final String? transcriptText;
  final String? contentSha256;
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
    this.supportsSessionApproval = false,
    this.approveForSessionLabel = '',
    this.toolName = '',
    this.requestSummary = '',
    this.primaryDetail = '',
    this.pathDetails = const <String>[],
    this.workingDirectory = '',
    this.reason = '',
    this.message = '',
  });

  final String runId;
  final String taskId;
  final String title;
  final String body;
  final String approveLabel;
  final String rejectLabel;
  final bool isHighRisk;
  final bool supportsSessionApproval;
  final String approveForSessionLabel;
  final String toolName;
  final String requestSummary;
  final String primaryDetail;
  final List<String> pathDetails;
  final String workingDirectory;
  final String reason;
  final String message;

  String get approvalId => runId.isNotEmpty ? runId : taskId;
}

@immutable
class ChatComposerState {
  const ChatComposerState({
    this.placeholder = 'Message OpenCray',
    this.todos = const <ChatTodoItemData>[],
    this.attachments = const <ChatAttachmentData>[],
    this.selectedCommand,
    this.commandOptions = const <ChatCommandOptionData>[],
    this.addActions = const <ChatAddActionData>[],
    this.showAddMenu = false,
  });

  final String placeholder;
  final List<ChatTodoItemData> todos;
  final List<ChatAttachmentData> attachments;
  final String? selectedCommand;
  final List<ChatCommandOptionData> commandOptions;
  final List<ChatAddActionData> addActions;
  final bool showAddMenu;

  ChatComposerState copyWith({
    String? placeholder,
    List<ChatTodoItemData>? todos,
    List<ChatAttachmentData>? attachments,
    String? selectedCommand,
    List<ChatCommandOptionData>? commandOptions,
    List<ChatAddActionData>? addActions,
    bool? showAddMenu,
    bool clearSelectedCommand = false,
  }) {
    return ChatComposerState(
      placeholder: placeholder ?? this.placeholder,
      todos: todos ?? this.todos,
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
class ChatTodoItemData {
  const ChatTodoItemData({
    required this.content,
    required this.status,
    this.activeForm,
  });

  final String content;
  final ChatTodoStatus status;
  final String? activeForm;

  String get displayText {
    if (status == ChatTodoStatus.inProgress) {
      final String? activeLabel = activeForm?.trim();
      if (activeLabel != null && activeLabel.isNotEmpty) {
        return activeLabel;
      }
    }
    return content;
  }
}

@immutable
class ChatAttachmentData {
  const ChatAttachmentData({
    required this.id,
    required this.kind,
    required this.label,
    required this.detail,
    required this.accentColor,
    this.draftAttachment,
  });

  final String id;
  final ChatAttachmentKind kind;
  final String label;
  final String detail;
  final Color accentColor;
  final OpenCrayChatDraftAttachment? draftAttachment;
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
    this.lastMessageAtEpochMs,
    this.unreadCount = 0,
  });

  final String sessionId;
  final String title;
  final String preview;
  final String meta;
  final bool isSelected;
  final int? lastMessageAtEpochMs;
  final int unreadCount;
}
