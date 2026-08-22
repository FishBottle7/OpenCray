import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show SelectedContentRange;
import 'package:flutter/services.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/copy/opencray_ui_copy.dart';
import '../../core/models/opencray_chat_draft_attachment.dart';
import '../../core/models/opencray_chat_snapshot.dart';
import '../../core/models/opencray_file_image_preview.dart';
import '../../core/models/opencray_file_text_preview.dart';
import '../../core/models/opencray_file_voice_playback_source.dart';
import '../../core/models/opencray_sandbox_preview_embed_config.dart';
import '../../core/models/opencray_sandbox_settings.dart';
import '../../core/design/opencray_motion.dart';
import '../../core/design/opencray_tokens.dart';
import '../../core/widgets/opencray_image_bytes_view.dart';
import '../../core/widgets/opencray_markdown.dart';
import 'chat_models.dart';
import 'chat_seed_data.dart';
import 'chat_voice_playback.dart';

part 'chat_design_tokens.dart';
part 'chat_runtime_merge.dart';
part 'chat_state_equivalence.dart';
part 'chat_live_draft_projection.dart';
part 'chat_realtime_queue.dart';
part 'chat_runtime_projector.dart';
part 'chat_widgets_chrome.dart';
part 'chat_widgets_approvals.dart';
part 'chat_widgets_run_trace.dart';
part 'chat_widgets_run_trace_inspector.dart';
part 'chat_widgets_message.dart';
part 'chat_widgets_attachments.dart';
part 'chat_widgets_composer.dart';

@visibleForTesting
TextSelectionThemeData chatBubbleSelectionTheme(ChatMessageKind kind) {
  return switch (kind) {
    ChatMessageKind.outbound => const TextSelectionThemeData(
      // Outbound bubbles already use the app accent, so switch to a bright
      // translucent selection color to preserve contrast.
      selectionColor: Color(0x52FFFFFF),
      selectionHandleColor: Colors.white,
    ),
    _ => const TextSelectionThemeData(
      selectionColor: Color(0x332563EB),
      selectionHandleColor: OpenCrayColors.primary,
    ),
  };
}

enum _SessionMenuAction { copy, delete }

enum _ChatRuntimeEnvironment { local, cloud }

enum _ChatMessageMenuAction {
  copy,
  recall,
  redo,
  edit,
  branch,
  delete,
  multiSelect,
  quote,
}

const OpenCraySandboxSettingsSnapshot _defaultSandboxSettingsSnapshot =
    OpenCraySandboxSettingsSnapshot(
      localeTag: 'en',
      enabled: false,
      providerId: 'e2b',
      defaultBackend: 'local',
      sessionMode: 'ephemeral',
      autoResume: false,
      idleTimeoutMinutes: 15,
      startupTimeoutMs: 30000,
      requestTimeoutMs: 300000,
      timeoutAction: 'kill',
      templateId: '',
      e2bApiKey: '',
      apiKeyConfigured: false,
    );

@visibleForTesting
const Duration chatSandboxSessionAutoRefreshDebounce = Duration(
  milliseconds: 900,
);

const Duration _chatMessageDeleteMotionDuration = OpenCrayMotion.panel;
const double _chatMessageDeleteSlideDistance = 22;
const double _chatTranscriptInsertOffset = 10;
const Duration _chatMessageMenuExitDuration = OpenCrayMotion.micro;

@immutable
class _ActiveChatMessageMenu {
  const _ActiveChatMessageMenu({
    required this.message,
    required this.bubbleRect,
    required this.selectionVersionAtOpen,
    this.redoPrompt,
    this.selectedText,
  });

  final ChatMessageData message;
  final Rect bubbleRect;
  final int selectionVersionAtOpen;
  final ChatMessageData? redoPrompt;
  final String? selectedText;

  bool get isOutgoing => message.kind == ChatMessageKind.outbound;

  bool get canRecall => isOutgoing && !message.isEphemeral;

  bool get showsRedo => message.kind == ChatMessageKind.inbound;

  bool get canRedo => redoPrompt != null && !message.isEphemeral;

  bool get canEdit => isOutgoing && !message.isEphemeral;

  bool get canBranch =>
      message.kind == ChatMessageKind.inbound && !message.isEphemeral;

  bool get canDelete =>
      !message.isEphemeral ||
      _isRuntimeProjectedAgentMessageId(message.messageId.trim());
}

@immutable
class _SandboxSessionLifecycleRefreshSchedule {
  const _SandboxSessionLifecycleRefreshSchedule({
    required this.key,
    required this.delayMs,
  });

  final String key;
  final int delayMs;
}

class ChatFeatureController {
  bool Function()? _backPressHandler;

  bool consumeBackPress() => _backPressHandler?.call() ?? false;
}

class OpenCrayChatFeature extends StatefulWidget {
  const OpenCrayChatFeature({
    super.key,
    required this.copy,
    this.state,
    this.bridge,
    this.voicePlaybackControllerFactory,
    this.isTabActive = true,
    this.controller,
    this.bottomInset = 10,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState? state;
  final OpenCrayHostBridge? bridge;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final bool isTabActive;
  final ChatFeatureController? controller;
  final double bottomInset;

  @override
  State<OpenCrayChatFeature> createState() => _OpenCrayChatFeatureState();
}

enum _ApprovalResolutionKind { approved, approvedForSession, rejected }

class _OpenCrayChatFeatureState extends State<OpenCrayChatFeature> {
  static const AnimationStyle _sessionMenuAnimationStyle = AnimationStyle(
    duration: OpenCrayMotion.instant,
    reverseDuration: Duration(milliseconds: 90),
    curve: OpenCrayMotion.enter,
    reverseCurve: OpenCrayMotion.exit,
  );

  late ChatFeatureState _state = _initialStateForWidget();
  late final TextEditingController _composerController =
      TextEditingController();
  late final FocusNode _composerFocusNode = FocusNode();
  final ScrollController _chatScrollController = ScrollController();
  final GlobalKey _chatOverlayKey = GlobalKey();
  final GlobalKey _composerKey = GlobalKey();
  StreamSubscription<OpenCrayChatSnapshot>? _chatSubscription;
  StreamSubscription<OpenCrayChatRuntimeSnapshot>? _chatRuntimeSubscription;
  StreamSubscription<OpenCrayChatLiveAssistantDraftEvent>?
  _liveAssistantDraftSubscription;
  StreamSubscription<OpenCrayChatRuntimeEventDelta>?
  _runtimeEventDeltaSubscription;
  OpenCrayChatSnapshot? _latestChatSnapshot;
  OpenCrayChatRuntimeSnapshot? _latestChatRuntimeSnapshot;
  Timer? _runtimeProjectionFlushTimer;
  OpenCrayChatRuntimeSnapshot? _pendingRuntimeProjectionSnapshot;
  final Map<String, Map<String, OpenCrayChatLiveAssistantDraftSnapshot>>
  _liveAssistantDraftOverridesBySession =
      <String, Map<String, OpenCrayChatLiveAssistantDraftSnapshot>>{};
  final Map<String, Map<String, int>>
  _liveAssistantDraftEventEpochBySessionAndMessage =
      <String, Map<String, int>>{};
  final Map<String, Map<String, int>>
  _liveAssistantDraftEventSequenceBySessionAndIdentity =
      <String, Map<String, int>>{};
  final Map<String, Map<String, bool>>
  _liveAssistantDraftEventClearedBySessionAndIdentity =
      <String, Map<String, bool>>{};
  final Map<String, String> _runtimeStreamInstanceBySession =
      <String, String>{};
  final Map<String, String> _runtimeBridgeEpochBySession = <String, String>{};
  final Map<String, Set<String>> _seenRealtimeEventIdsBySession =
      <String, Set<String>>{};
  final Map<String, int> _runtimeEventDeltaSequenceBySession = <String, int>{};
  bool _runtimeEventDeltaResyncInFlight = false;
  static const int _maxQueuedRealtimeEventsAfterResync = 512;
  final List<_QueuedRealtimeEnvelope> _queuedRealtimeEventsAfterResync =
      <_QueuedRealtimeEnvelope>[];
  int _queuedRealtimeArrivalOrdinal = 0;
  Timer? _runtimeDeltaResyncRetryTimer;
  Timer? _bridgeRecoveryTimer;
  int _bridgeBindingEpoch = 0;
  Future<void> _bridgeBindingBarrier = Future<void>.value();
  final Set<String> _approvalTaskIdsInFlight = <String>{};
  final Map<String, _ApprovalResolutionKind> _approvalResolutionById =
      <String, _ApprovalResolutionKind>{};
  final Set<String> _locallyDismissedApprovalIds = <String>{};
  final Map<String, Timer> _approvalResolutionDismissTimers = <String, Timer>{};
  final Set<String> _interruptRunIdsInFlight = <String>{};
  final Set<String> _retryRunIdsInFlight = <String>{};
  final Set<String> _selectedMessageIds = <String>{};
  final Map<String, String> _selectedTextByMessageId = <String, String>{};
  final Map<String, SelectedContentRange> _selectedTextRangeByMessageId =
      <String, SelectedContentRange>{};
  final Map<String, int> _selectedTextVersionByMessageId = <String, int>{};
  final Map<String, Set<String>> _locallyDeletedMessageIdsBySession =
      <String, Set<String>>{};
  final Map<String, Set<String>> _deletingMessageIdsBySession =
      <String, Set<String>>{};
  final Set<String> _locallyDeletedSessionIds = <String>{};
  _ActiveChatMessageMenu? _activeMessageMenu;
  _ActiveChatMessageMenu? _exitingMessageMenu;
  int _messageMenuExitEpoch = 0;
  bool _suppressNextTransientUiDismiss = false;
  Timer? _todoArchiveHideTimer;
  Timer? _sandboxSessionAutoRefreshTimer;
  Timer? _sandboxSessionLifecycleRefreshTimer;
  String? _hiddenArchivedTodoFingerprint;
  String? _scheduledTodoArchiveFingerprint;
  String? _scheduledSandboxSessionRefreshAnchor;
  final List<String> _queuedSandboxSessionRefreshAnchors = <String>[];
  String? _lastSandboxSessionRefreshAnchor;
  String? _scheduledSandboxSessionLifecycleRefreshKey;
  bool _queuedSandboxSessionLifecycleRefresh = false;
  String? _interruptConfirmRunId;
  double _composerHeight = 0;
  bool _scrollToBottomScheduled = false;
  bool _scheduledScrollToBottomAnimated = true;
  OpenCraySandboxSettingsSnapshot _sandboxSettings =
      _defaultSandboxSettingsSnapshot;
  bool _sandboxSessionRefreshInFlight = false;

  bool get _usesHostBridge => widget.bridge != null;

  ChatFeatureState _initialStateForWidget() {
    final ChatFeatureState? providedState = widget.state;
    if (providedState != null) {
      return providedState;
    }
    if (!_usesHostBridge) {
      return OpenCrayChatSeedData.main(widget.copy);
    }
    return ChatFeatureState(
      variant: ChatPrototypeVariant.empty,
      screenTitle: widget.copy.chatSeedScreenTitle,
      summary: ChatSessionSummary(
        title: widget.copy.chatSeedEmptyTitle,
        badge: '',
        body: widget.copy.chatSeedEmptyBody,
      ),
      messages: const <ChatMessageData>[],
      runTraces: const <ChatRunTraceData>[],
      composer: ChatComposerState(
        placeholder: widget.copy.chatComposerPlaceholder,
      ),
      drawer: ChatSessionsDrawerState(
        eyebrow: widget.copy.chatSeedDrawerEyebrow,
        title: widget.copy.chatSeedRecentSessions,
        ctaLabel: widget.copy.chatSeedNewSession,
        sessions: const <ChatSessionListItemData>[],
      ),
      emptyThreadHeight: 260,
    );
  }

  _ChatRuntimeEnvironment get _selectedRuntimeEnvironment =>
      _sandboxSettings.defaultBackend == 'sandbox'
      ? _ChatRuntimeEnvironment.cloud
      : _ChatRuntimeEnvironment.local;

  ChatRunTraceData? get _composerInterruptTrace {
    for (final trace in _state.runTraces.reversed) {
      if (trace.canInterrupt && trace.interruptId.trim().isNotEmpty) {
        return trace;
      }
    }
    return null;
  }

  String get _activeSessionId {
    for (final session in _state.drawer.sessions) {
      if (session.isSelected) {
        return session.sessionId;
      }
    }
    if (_state.drawer.sessions.isNotEmpty) {
      return _state.drawer.sessions.first.sessionId;
    }
    final String runtimeSessionId =
        _latestChatRuntimeSnapshot?.sessionId.trim() ??
        _latestChatSnapshot?.runtimeActivity?.sessionId.trim() ??
        '';
    if (runtimeSessionId.isNotEmpty) {
      return runtimeSessionId;
    }
    return 'chat-session';
  }

  String _sessionIdForState(ChatFeatureState state) {
    for (final session in state.drawer.sessions) {
      if (session.isSelected) {
        return session.sessionId;
      }
    }
    if (state.drawer.sessions.isNotEmpty) {
      return state.drawer.sessions.first.sessionId;
    }
    final String runtimeSessionId =
        _latestChatRuntimeSnapshot?.sessionId.trim() ??
        _latestChatSnapshot?.runtimeActivity?.sessionId.trim() ??
        '';
    if (runtimeSessionId.isNotEmpty) {
      return runtimeSessionId;
    }
    return 'chat-session';
  }

  bool _runtimeDeltaTargetsActiveSession(String sessionId) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return false;
    }
    if (normalizedSessionId == _activeSessionId) {
      return true;
    }
    if (_latestChatSnapshot == null) {
      return true;
    }
    final String runtimeSessionId =
        _latestChatRuntimeSnapshot?.sessionId.trim() ?? '';
    if (runtimeSessionId.isNotEmpty) {
      return normalizedSessionId == runtimeSessionId;
    }
    final String snapshotSessionId = _latestChatSnapshot == null
        ? ''
        : _snapshotActiveSessionId(_latestChatSnapshot!).trim();
    if (snapshotSessionId.isNotEmpty) {
      return normalizedSessionId == snapshotSessionId;
    }
    return false;
  }

  void _rememberLocallyDeletedMessages(
    String sessionId,
    Set<String> messageIds,
  ) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty || messageIds.isEmpty) {
      return;
    }
    _locallyDeletedMessageIdsBySession
        .putIfAbsent(normalizedSessionId, () => <String>{})
        .addAll(messageIds);
  }

  Set<String> _deletingMessageIdsForSession(String sessionId) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return const <String>{};
    }
    return _deletingMessageIdsBySession[normalizedSessionId] ??
        const <String>{};
  }

  void _rememberDeletingMessageIds(
    String sessionId,
    Iterable<String> messageIds,
  ) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return;
    }
    final Set<String> normalizedIds = messageIds
        .map((messageId) => messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();
    if (normalizedIds.isEmpty) {
      return;
    }
    _deletingMessageIdsBySession
        .putIfAbsent(normalizedSessionId, () => <String>{})
        .addAll(normalizedIds);
  }

  void _forgetDeletingMessageIds(
    String sessionId,
    Iterable<String> messageIds,
  ) {
    final String normalizedSessionId = sessionId.trim();
    final Set<String>? deletingIds =
        _deletingMessageIdsBySession[normalizedSessionId];
    if (deletingIds == null) {
      return;
    }
    for (final String messageId in messageIds) {
      deletingIds.remove(messageId.trim());
    }
    if (deletingIds.isEmpty) {
      _deletingMessageIdsBySession.remove(normalizedSessionId);
    }
  }

  Set<String> _stageMessageDeleteMotion(
    String sessionId,
    Iterable<String> messageIds,
  ) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return const <String>{};
    }
    final Set<String> visibleMessageIds = _state.messages
        .map((message) => message.messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();
    final Set<String> alreadyDeleting = _deletingMessageIdsForSession(
      normalizedSessionId,
    );
    final Set<String> deletingIds = messageIds
        .map((messageId) => messageId.trim())
        .where(
          (messageId) =>
              messageId.isNotEmpty &&
              visibleMessageIds.contains(messageId) &&
              !alreadyDeleting.contains(messageId),
        )
        .toSet();
    if (deletingIds.isEmpty) {
      return const <String>{};
    }
    setState(() {
      _rememberDeletingMessageIds(normalizedSessionId, deletingIds);
      _removeSelectionForMessages(deletingIds);
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
    });
    return deletingIds;
  }

  Future<void> _waitForMessageDeleteMotion() async {
    final Duration duration = OpenCrayMotion.resolve(
      context,
      _chatMessageDeleteMotionDuration,
    );
    if (duration == Duration.zero) {
      return;
    }
    await Future<void>.delayed(duration);
  }

  void _forgetLocallyDeletedMessages(
    String sessionId,
    Iterable<String> messageIds,
  ) {
    final String normalizedSessionId = sessionId.trim();
    final Set<String>? deletedIds =
        _locallyDeletedMessageIdsBySession[normalizedSessionId];
    if (deletedIds == null) {
      return;
    }
    for (final String messageId in messageIds) {
      deletedIds.remove(messageId.trim());
    }
    if (deletedIds.isEmpty) {
      _locallyDeletedMessageIdsBySession.remove(normalizedSessionId);
    }
  }

  void _removeSelectionForMessages(Iterable<String> messageIds) {
    for (final String messageId in messageIds) {
      final String normalizedMessageId = messageId.trim();
      if (normalizedMessageId.isEmpty) {
        continue;
      }
      _selectedMessageIds.remove(normalizedMessageId);
      _selectedTextByMessageId.remove(normalizedMessageId);
      _selectedTextRangeByMessageId.remove(normalizedMessageId);
      _selectedTextVersionByMessageId.remove(normalizedMessageId);
    }
  }

  Set<String> _deleteTargetMessageIdsForMessages(
    Iterable<ChatMessageData> messages,
  ) {
    final Set<String> targetIds = <String>{};
    for (final message in messages) {
      targetIds.addAll(_deleteTargetMessageIdsForMessage(message));
    }
    return targetIds;
  }

  Set<String> _deleteTargetMessageIdsForMessage(ChatMessageData message) {
    final String messageId = message.messageId.trim();
    if (messageId.isEmpty) {
      return const <String>{};
    }
    final String anchorMessageId = _agentTurnDeleteAnchorMessageId(message);
    if (anchorMessageId.isEmpty) {
      return <String>{messageId};
    }
    return _agentMessageIdsForTurnAnchor(anchorMessageId)..add(messageId);
  }

  String _agentTurnDeleteAnchorMessageId(ChatMessageData message) {
    final String messageId = message.messageId.trim();
    if (messageId.isEmpty || message.kind != ChatMessageKind.inbound) {
      return '';
    }
    if (message.runtimeAnchorMessageId.trim().isNotEmpty ||
        _isRuntimeProjectedAgentMessageId(messageId)) {
      return '';
    }
    if (_state.messages.any(
          (candidate) => candidate.runtimeAnchorMessageId.trim() == messageId,
        ) ||
        _state.runTraces.any(
          (trace) => trace.anchorMessageId.trim() == messageId,
        )) {
      return messageId;
    }
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _latestChatRuntimeSnapshot ?? _latestChatSnapshot?.runtimeActivity;
    if (runtimeSnapshot != null &&
        _visibleRuns(
          runtimeSnapshot,
        ).any((run) => run.pendingMessageId?.trim() == messageId)) {
      return messageId;
    }
    final int anchorIndex = _state.messages.indexWhere(
      (candidate) => candidate.messageId.trim() == messageId,
    );
    if (anchorIndex <= 0) {
      return '';
    }
    for (int index = anchorIndex - 1; index >= 0; index -= 1) {
      final ChatMessageData candidate = _state.messages[index];
      if (candidate.kind == ChatMessageKind.outbound) {
        break;
      }
      if (candidate.kind == ChatMessageKind.inbound &&
          _isRuntimeProjectedAgentMessageId(candidate.messageId.trim())) {
        return messageId;
      }
    }
    return '';
  }

  Set<String> _agentMessageIdsForTurnAnchor(String anchorMessageId) {
    final String normalizedAnchorMessageId = anchorMessageId.trim();
    if (normalizedAnchorMessageId.isEmpty) {
      return const <String>{};
    }
    final Set<String> messageIds = <String>{normalizedAnchorMessageId};
    for (final ChatMessageData message in _state.messages) {
      final String messageId = message.messageId.trim();
      if (messageId.isEmpty) {
        continue;
      }
      if (messageId == normalizedAnchorMessageId ||
          message.runtimeAnchorMessageId.trim() == normalizedAnchorMessageId) {
        messageIds.add(messageId);
      }
    }
    final int anchorIndex = _state.messages.indexWhere(
      (message) => message.messageId.trim() == normalizedAnchorMessageId,
    );
    if (anchorIndex >= 0) {
      for (int index = anchorIndex - 1; index >= 0; index -= 1) {
        final ChatMessageData candidate = _state.messages[index];
        if (candidate.kind == ChatMessageKind.outbound) {
          break;
        }
        final String candidateId = candidate.messageId.trim();
        if (candidate.kind == ChatMessageKind.inbound &&
            candidateId.isNotEmpty) {
          messageIds.add(candidateId);
        }
      }
    }
    return messageIds;
  }

  List<ChatPendingApprovalData> _visiblePendingApprovals(
    List<ChatPendingApprovalData> approvals,
  ) {
    if (_locallyDismissedApprovalIds.isEmpty) {
      return approvals;
    }
    return approvals
        .where(
          (approval) =>
              !_locallyDismissedApprovalIds.contains(approval.approvalId),
        )
        .toList(growable: false);
  }

  ChatFeatureState _applyLocalDeletionTombstones(ChatFeatureState state) {
    final String sessionId = _sessionIdForState(state).trim();
    final bool selectedSessionDeleted =
        sessionId.isNotEmpty && _locallyDeletedSessionIds.contains(sessionId);
    final Set<String> locallyDeletedMessageIds = sessionId.isEmpty
        ? const <String>{}
        : _locallyDeletedMessageIdsBySession[sessionId] ?? const <String>{};
    List<ChatMessageData> messages = state.messages;
    List<ChatRunTraceData> runTraces = state.runTraces;
    List<ChatPendingApprovalData> pendingApprovals = state.pendingApprovals;
    ChatComposerState composer = state.composer;
    if (selectedSessionDeleted) {
      messages = const <ChatMessageData>[];
      runTraces = const <ChatRunTraceData>[];
      pendingApprovals = const <ChatPendingApprovalData>[];
      composer = composer.copyWith(todos: const <ChatTodoItemData>[]);
    }
    pendingApprovals = _visiblePendingApprovals(pendingApprovals);
    if (locallyDeletedMessageIds.isNotEmpty) {
      messages = messages
          .where(
            (message) =>
                !locallyDeletedMessageIds.contains(message.messageId.trim()) &&
                !locallyDeletedMessageIds.contains(
                  message.runtimeAnchorMessageId.trim(),
                ),
          )
          .toList(growable: false);
      runTraces = runTraces
          .where(
            (trace) => !locallyDeletedMessageIds.contains(
              trace.anchorMessageId.trim(),
            ),
          )
          .toList(growable: false);
    }

    ChatSessionsDrawerState drawer = state.drawer;
    if (_locallyDeletedSessionIds.isNotEmpty && drawer.sessions.isNotEmpty) {
      final List<ChatSessionListItemData> remainingSessions = drawer.sessions
          .where(
            (session) =>
                !_locallyDeletedSessionIds.contains(session.sessionId.trim()),
          )
          .toList(growable: false);
      if (remainingSessions.length != drawer.sessions.length) {
        String selectedSessionId = '';
        for (final ChatSessionListItemData session in remainingSessions) {
          if (session.isSelected) {
            selectedSessionId = session.sessionId;
            break;
          }
        }
        if (selectedSessionId.isEmpty && remainingSessions.isNotEmpty) {
          selectedSessionId = remainingSessions.first.sessionId;
        }
        drawer = ChatSessionsDrawerState(
          eyebrow: drawer.eyebrow,
          title: drawer.title,
          ctaLabel: drawer.ctaLabel,
          sessions: remainingSessions
              .map(
                (session) => ChatSessionListItemData(
                  sessionId: session.sessionId,
                  title: session.title,
                  preview: session.preview,
                  meta: session.meta,
                  isSelected: session.sessionId == selectedSessionId,
                  lastMessageAtEpochMs: session.lastMessageAtEpochMs,
                  unreadCount: session.sessionId == selectedSessionId
                      ? 0
                      : session.unreadCount,
                ),
              )
              .toList(growable: false),
        );
      }
    }

    final bool threadEmpty = messages.isEmpty && runTraces.isEmpty;
    return state.copyWith(
      variant: threadEmpty && composer.todos.isEmpty && pendingApprovals.isEmpty
          ? ChatPrototypeVariant.empty
          : ChatPrototypeVariant.main,
      messages: messages,
      runTraces: runTraces,
      pendingApprovals: pendingApprovals,
      composer: composer,
      drawer: drawer,
      emptyThreadHeight: threadEmpty ? 260 : 0,
    );
  }

  void _pruneLocalDeletionTombstones(OpenCrayChatSnapshot snapshot) {
    final Set<String> snapshotApprovalIds = snapshot.pendingApprovals
        .map(
          (approval) => approval.runId.trim().isNotEmpty
              ? approval.runId.trim()
              : approval.taskId.trim(),
        )
        .where((approvalId) => approvalId.isNotEmpty)
        .toSet();
    _approvalResolutionById.removeWhere(
      (approvalId, _) => !snapshotApprovalIds.contains(approvalId),
    );
    _locallyDismissedApprovalIds.removeWhere(
      (approvalId) => !snapshotApprovalIds.contains(approvalId),
    );
    final List<String> staleApprovalTimers = _approvalResolutionDismissTimers
        .keys
        .where((approvalId) => !snapshotApprovalIds.contains(approvalId))
        .toList(growable: false);
    for (final String approvalId in staleApprovalTimers) {
      _approvalResolutionDismissTimers.remove(approvalId)?.cancel();
    }

    if (_locallyDeletedSessionIds.isNotEmpty) {
      final Set<String> snapshotSessionIds = snapshot.drawer.sessions
          .map((session) => session.sessionId.trim())
          .where((sessionId) => sessionId.isNotEmpty)
          .toSet();
      _locallyDeletedSessionIds.removeWhere(
        (sessionId) => !snapshotSessionIds.contains(sessionId),
      );
    }

    final String sessionId = _snapshotActiveSessionId(snapshot).trim();
    final Set<String>? deletedMessageIds =
        _locallyDeletedMessageIdsBySession[sessionId];
    if (sessionId.isEmpty || deletedMessageIds == null) {
      return;
    }
    final Set<String> snapshotMessageIds = snapshot.messages
        .asMap()
        .entries
        .map((entry) {
          final String messageId = entry.value.messageId.trim();
          return messageId.isNotEmpty
              ? messageId
              : 'message-${entry.key}-${entry.value.kind}';
        })
        .toSet();
    final Set<String> runtimeTombstoneIds =
        _runtimeProjectionTombstoneIdsForSession(sessionId);
    deletedMessageIds.removeWhere(
      (messageId) =>
          !snapshotMessageIds.contains(messageId) &&
          !runtimeTombstoneIds.contains(messageId),
    );
    if (deletedMessageIds.isEmpty) {
      _locallyDeletedMessageIdsBySession.remove(sessionId);
    }
  }

  Set<String> _runtimeProjectionTombstoneIdsForSession(String sessionId) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return const <String>{};
    }
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _latestChatRuntimeSnapshot ?? _latestChatSnapshot?.runtimeActivity;
    if (runtimeSnapshot == null ||
        runtimeSnapshot.sessionId.trim() != normalizedSessionId) {
      return const <String>{};
    }
    final Set<String> messageIds = <String>{};
    final List<OpenCrayChatRunSnapshot> visibleRuns = _visibleRuns(
      runtimeSnapshot,
    );
    final Map<String, OpenCrayChatRunSnapshot> visibleRunsByRunId =
        <String, OpenCrayChatRunSnapshot>{
          for (final run in visibleRuns)
            if (run.runId.trim().isNotEmpty) run.runId.trim(): run,
        };
    final Map<String, OpenCrayChatRunSnapshot> visibleRunsByTaskId =
        <String, OpenCrayChatRunSnapshot>{
          for (final run in visibleRuns)
            if (run.taskId.trim().isNotEmpty) run.taskId.trim(): run,
        };
    for (final OpenCrayChatRunSnapshot run in visibleRuns) {
      final String pendingMessageId = run.pendingMessageId?.trim() ?? '';
      if (pendingMessageId.isNotEmpty) {
        messageIds.add(pendingMessageId);
      }
      for (final OpenCrayChatManagedProcessSnapshot process
          in run.managedProcesses) {
        messageIds.addAll(
          _projectedManagedProcessMessageIds(run: run, process: process),
        );
      }
    }
    for (final OpenCrayChatRuntimeEventSnapshot event
        in runtimeSnapshot.events) {
      if (event.kind != 'assistant_phase' ||
          event.isFinal == true ||
          _hideAssistantPhaseBubble(event)) {
        continue;
      }
      final OpenCrayChatRunSnapshot? run =
          visibleRunsByRunId[event.runId.trim()] ??
          visibleRunsByTaskId[event.taskId.trim()];
      if (run == null || !run.matchesRuntimeEvent(event)) {
        continue;
      }
      messageIds.addAll(_assistantPhaseMessageIds(event));
    }
    return messageIds;
  }

  ChatComposerState _composerStateForHostSnapshot(ChatFeatureState nextState) {
    if (_sessionIdForState(nextState) != _activeSessionId) {
      return nextState.composer;
    }
    return nextState.composer.copyWith(
      attachments: _state.composer.attachments,
      selectedCommand: _state.composer.selectedCommand,
      commandOptions: _state.composer.commandOptions,
      addActions: _state.composer.addActions,
      showAddMenu: _state.composer.showAddMenu,
    );
  }

  bool get _isMessageSelectionMode => _selectedMessageIds.isNotEmpty;

  int get _selectedMessageCount => _selectedMessageIds.length;

  List<ChatMessageData> get _selectedMessagesInOrder => _state.messages
      .where((message) => _selectedMessageIds.contains(message.messageId))
      .toList(growable: false);

  @override
  void initState() {
    super.initState();
    widget.controller?._backPressHandler = _consumeBackPress;
    _chatScrollController.addListener(_handleChatScrollChanged);
    _bindBridge(widget.bridge);
  }

  @override
  void didUpdateWidget(covariant OpenCrayChatFeature oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller?._backPressHandler = null;
      widget.controller?._backPressHandler = _consumeBackPress;
    }
    if (oldWidget.bridge != widget.bridge) {
      _bindBridge(widget.bridge);
    }
    if (oldWidget.isTabActive &&
        !widget.isTabActive &&
        _isMessageSelectionMode) {
      _clearMessageSelection(emitHaptic: false);
    }
    if (oldWidget.isTabActive != widget.isTabActive) {
      if (widget.isTabActive) {
        _syncSandboxSessionAutoRefresh();
        _syncSandboxSessionLifecycleAutoRefresh();
      } else {
        _cancelScheduledSandboxSessionAutoRefresh();
        _cancelScheduledSandboxSessionLifecycleRefresh();
      }
    }
  }

  @override
  void dispose() {
    _bridgeBindingEpoch += 1;
    widget.controller?._backPressHandler = null;
    _chatScrollController.removeListener(_handleChatScrollChanged);
    _chatSubscription?.cancel();
    _chatRuntimeSubscription?.cancel();
    _liveAssistantDraftSubscription?.cancel();
    _runtimeEventDeltaSubscription?.cancel();
    _runtimeProjectionFlushTimer?.cancel();
    _runtimeDeltaResyncRetryTimer?.cancel();
    _bridgeRecoveryTimer?.cancel();
    _todoArchiveHideTimer?.cancel();
    for (final Timer timer in _approvalResolutionDismissTimers.values) {
      timer.cancel();
    }
    _approvalResolutionDismissTimers.clear();
    _sandboxSessionAutoRefreshTimer?.cancel();
    _sandboxSessionLifecycleRefreshTimer?.cancel();
    _composerController.dispose();
    _composerFocusNode.dispose();
    _chatScrollController.dispose();
    super.dispose();
  }

  void _bindBridge(OpenCrayHostBridge? bridge) {
    final int bindingEpoch = ++_bridgeBindingEpoch;
    _bridgeRecoveryTimer?.cancel();
    _bridgeRecoveryTimer = null;
    final List<StreamSubscription<Object?>> subscriptionsToCancel =
        <StreamSubscription<Object?>>[
          if (_chatSubscription != null) _chatSubscription!,
          if (_chatRuntimeSubscription != null) _chatRuntimeSubscription!,
          if (_liveAssistantDraftSubscription != null)
            _liveAssistantDraftSubscription!,
          if (_runtimeEventDeltaSubscription != null)
            _runtimeEventDeltaSubscription!,
        ];
    _chatSubscription = null;
    _chatRuntimeSubscription = null;
    _liveAssistantDraftSubscription = null;
    _runtimeEventDeltaSubscription = null;
    _resetRealtimeReducerForBridgeChange();
    _runtimeEventDeltaResyncInFlight = bridge != null;
    final Future<void> previousBinding = _bridgeBindingBarrier;
    _bridgeBindingBarrier = _completeBridgeBinding(
      previousBinding: previousBinding,
      subscriptionsToCancel: subscriptionsToCancel,
      bridge: bridge,
      bindingEpoch: bindingEpoch,
    );
  }

  Future<void> _completeBridgeBinding({
    required Future<void> previousBinding,
    required List<StreamSubscription<Object?>> subscriptionsToCancel,
    required OpenCrayHostBridge? bridge,
    required int bindingEpoch,
  }) async {
    try {
      await previousBinding;
    } catch (_) {}
    try {
      await Future.wait(
        subscriptionsToCancel.map((subscription) => subscription.cancel()),
      );
    } catch (_) {}
    if (!mounted ||
        _bridgeBindingEpoch != bindingEpoch ||
        widget.bridge != bridge ||
        bridge == null) {
      return;
    }
    _chatSubscription = bridge.watchChatSnapshot().listen(
      (snapshot) {
        if (_bridgeBindingEpoch == bindingEpoch) {
          _handleChatSnapshot(snapshot);
        }
      },
      onError: (Object error, StackTrace stackTrace) {
        _handleRealtimeStreamError(bridge, bindingEpoch);
      },
    );
    _chatRuntimeSubscription = bridge.watchChatRuntimeSnapshot().listen(
      (snapshot) {
        if (_bridgeBindingEpoch == bindingEpoch) {
          _handleChatRuntimeSnapshot(snapshot);
        }
      },
      onError: (Object error, StackTrace stackTrace) {
        _handleRealtimeStreamError(bridge, bindingEpoch);
      },
    );
    _liveAssistantDraftSubscription = bridge
        .watchLiveAssistantDraftEvents()
        .listen(
          (event) {
            if (_bridgeBindingEpoch == bindingEpoch) {
              _handleLiveAssistantDraftEvent(event);
            }
          },
          onError: (Object error, StackTrace stackTrace) {
            _handleRealtimeStreamError(bridge, bindingEpoch);
          },
        );
    _runtimeEventDeltaSubscription = bridge.watchRuntimeEventDeltas().listen(
      (delta) {
        if (_bridgeBindingEpoch == bindingEpoch) {
          _handleRuntimeEventDelta(delta);
        }
      },
      onError: (Object error, StackTrace stackTrace) {
        _handleRealtimeStreamError(bridge, bindingEpoch);
      },
    );
    unawaited(_hydrateBridgeBinding(bridge, bindingEpoch));
  }

  void _resetRealtimeReducerForBridgeChange() {
    _runtimeDeltaResyncRetryTimer?.cancel();
    _runtimeDeltaResyncRetryTimer = null;
    _runtimeProjectionFlushTimer?.cancel();
    _runtimeProjectionFlushTimer = null;
    _pendingRuntimeProjectionSnapshot = null;
    _latestChatSnapshot = null;
    _latestChatRuntimeSnapshot = null;
    _runtimeStreamInstanceBySession.clear();
    _runtimeBridgeEpochBySession.clear();
    _seenRealtimeEventIdsBySession.clear();
    _runtimeEventDeltaSequenceBySession.clear();
    _queuedRealtimeEventsAfterResync.clear();
    _queuedRealtimeArrivalOrdinal = 0;
    _liveAssistantDraftOverridesBySession.clear();
    _liveAssistantDraftEventEpochBySessionAndMessage.clear();
    _liveAssistantDraftEventSequenceBySessionAndIdentity.clear();
    _liveAssistantDraftEventClearedBySessionAndIdentity.clear();
  }

  Future<void> _hydrateBridgeBinding(
    OpenCrayHostBridge bridge,
    int bindingEpoch,
  ) async {
    bool hydrated = false;
    try {
      hydrated = await _hydrateFromHost(
        bridge,
        bindingEpoch: bindingEpoch,
        authoritativeRuntimeSnapshot: true,
      );
    } catch (_) {}
    if (!mounted || _bridgeBindingEpoch != bindingEpoch) {
      return;
    }
    _runtimeEventDeltaResyncInFlight = false;
    if (hydrated) {
      _drainQueuedRealtimeEventsAfterResync();
    } else {
      _handleRealtimeStreamError(bridge, bindingEpoch);
    }
  }

  void _handleRealtimeStreamError(OpenCrayHostBridge bridge, int bindingEpoch) {
    if (!mounted ||
        _bridgeBindingEpoch != bindingEpoch ||
        widget.bridge != bridge ||
        _bridgeRecoveryTimer != null) {
      return;
    }
    _bridgeRecoveryTimer = Timer(const Duration(milliseconds: 250), () {
      _bridgeRecoveryTimer = null;
      if (mounted &&
          _bridgeBindingEpoch == bindingEpoch &&
          widget.bridge == bridge) {
        _bindBridge(bridge);
      }
    });
  }

  bool _consumeBackPress() {
    if (_isMessageSelectionMode) {
      _clearMessageSelection();
      return true;
    }
    return false;
  }

  void _handleChatScrollChanged() {
    if (_activeMessageMenu == null) {
      return;
    }
    final _ActiveChatMessageMenu menu = _activeMessageMenu!;
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _exitingMessageMenu = menu;
    });
    _scheduleMessageMenuExitClear();
  }

  void _dismissMessageMenu() {
    if (_activeMessageMenu == null) {
      return;
    }
    final _ActiveChatMessageMenu menu = _activeMessageMenu!;
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _exitingMessageMenu = menu;
    });
    _scheduleMessageMenuExitClear();
  }

  void _scheduleMessageMenuExitClear() {
    _messageMenuExitEpoch += 1;
    final int epoch = _messageMenuExitEpoch;
    final Duration duration = OpenCrayMotion.resolve(
      context,
      _chatMessageMenuExitDuration,
    );
    if (duration == Duration.zero || _isAutomatedWidgetTest) {
      if (mounted && epoch == _messageMenuExitEpoch) {
        setState(() {
          _exitingMessageMenu = null;
        });
      }
      return;
    }
    Future<void>.delayed(duration, () {
      if (!mounted ||
          epoch != _messageMenuExitEpoch ||
          _activeMessageMenu != null) {
        return;
      }
      setState(() {
        _exitingMessageMenu = null;
      });
    });
  }

  void _dismissTransientUi() {
    if (_suppressNextTransientUiDismiss) {
      _suppressNextTransientUiDismiss = false;
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    final bool shouldCloseComposerMenus =
        _state.composer.showAddMenu ||
        _state.composer.commandOptions.isNotEmpty;
    if (!shouldCloseComposerMenus && _activeMessageMenu == null) {
      return;
    }
    final _ActiveChatMessageMenu? menu = _activeMessageMenu;
    setState(() {
      if (shouldCloseComposerMenus) {
        _state = _state.copyWith(
          composer: _state.composer.copyWith(
            showAddMenu: false,
            commandOptions: const <ChatCommandOptionData>[],
            clearSelectedCommand: true,
          ),
        );
      }
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      if (menu != null) {
        _exitingMessageMenu = menu;
      }
    });
    if (menu != null) {
      _scheduleMessageMenuExitClear();
    }
  }

  void _showMessageFeedback(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  bool get _isAutomatedWidgetTest {
    bool result = false;
    assert(() {
      result = WidgetsBinding.instance.runtimeType.toString().contains(
        'TestWidgetsFlutterBinding',
      );
      return true;
    }());
    return result;
  }

  Future<void> _handleRuntimeEnvironmentSelected(
    _ChatRuntimeEnvironment environment,
  ) async {
    final String nextBackend = switch (environment) {
      _ChatRuntimeEnvironment.cloud => 'sandbox',
      _ChatRuntimeEnvironment.local => 'local',
    };
    if (_sandboxSettings.defaultBackend == nextBackend) {
      return;
    }
    final OpenCraySandboxSettingsSnapshot previousSnapshot = _sandboxSettings;
    final OpenCraySandboxSettingsSnapshot nextSnapshot = _sandboxSettings
        .copyWith(defaultBackend: nextBackend);
    setState(() {
      _sandboxSettings = nextSnapshot;
    });
    final bridge = widget.bridge;
    if (bridge == null) {
      return;
    }
    try {
      final savedSnapshot = await bridge.saveSandboxSettings(nextSnapshot);
      if (!mounted) {
        return;
      }
      setState(() {
        _sandboxSettings = savedSnapshot;
      });
      if (_selectedRuntimeEnvironment == _ChatRuntimeEnvironment.cloud) {
        _syncSandboxSessionAutoRefresh();
        _syncSandboxSessionLifecycleAutoRefresh();
      } else {
        _resetSandboxSessionAutoRefreshTracking();
        _cancelScheduledSandboxSessionLifecycleRefresh();
      }
    } catch (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _sandboxSettings = previousSnapshot;
      });
      if (_selectedRuntimeEnvironment == _ChatRuntimeEnvironment.cloud) {
        _syncSandboxSessionAutoRefresh();
        _syncSandboxSessionLifecycleAutoRefresh();
      } else {
        _resetSandboxSessionAutoRefreshTracking();
        _cancelScheduledSandboxSessionLifecycleRefresh();
      }
      _showMessageFeedback('Unable to update runtime environment.');
    }
  }

  void _emitSelectionHaptic() {
    unawaited(HapticFeedback.selectionClick());
  }

  void _handleMessageTextSelectionChanged(
    ChatMessageData message,
    OpenCrayMarkdownSelectionSnapshot? selection,
  ) {
    if (message.messageId.isEmpty) {
      return;
    }
    _selectedTextVersionByMessageId[message.messageId] =
        (_selectedTextVersionByMessageId[message.messageId] ?? 0) + 1;
    final String normalized = selection?.plainText.trim() ?? '';
    if (normalized.isEmpty) {
      _selectedTextByMessageId.remove(message.messageId);
      _selectedTextRangeByMessageId.remove(message.messageId);
      return;
    }
    _selectedTextByMessageId[message.messageId] = normalized;
    final SelectedContentRange? range = selection?.range;
    if (range == null) {
      _selectedTextRangeByMessageId.remove(message.messageId);
    } else {
      _selectedTextRangeByMessageId[message.messageId] = range;
    }
  }

  OpenCrayMarkdownSelectionSnapshot? _resolvedSelectedCopyForMenu(
    _ActiveChatMessageMenu menu,
  ) {
    final String liveSelectedText =
        _selectedTextByMessageId[menu.message.messageId]?.trim() ?? '';
    final String fallbackSelectedText = menu.selectedText?.trim() ?? '';
    final int liveSelectionVersion =
        _selectedTextVersionByMessageId[menu.message.messageId] ?? 0;
    final bool canUseLiveSelection =
        fallbackSelectedText.isNotEmpty ||
        liveSelectionVersion > menu.selectionVersionAtOpen;
    if (canUseLiveSelection && liveSelectedText.isNotEmpty) {
      return OpenCrayMarkdownSelectionSnapshot(
        plainText: liveSelectedText,
        range: _selectedTextRangeByMessageId[menu.message.messageId],
      );
    }
    if (fallbackSelectedText.isEmpty) {
      return null;
    }
    return OpenCrayMarkdownSelectionSnapshot(plainText: fallbackSelectedText);
  }

  Future<void> _copyMessageFromMenu(_ActiveChatMessageMenu menu) async {
    final OpenCrayMarkdownSelectionSnapshot? selectedCopy =
        _resolvedSelectedCopyForMenu(menu);
    final String selectedText = selectedCopy?.plainText ?? '';
    if (selectedText.isNotEmpty) {
      final OpenCrayMarkdownClipboardPayload? selectionPayload =
          openCrayBuildMarkdownSelectionClipboardPayload(
            menu.message.text,
            selectedText: selectedText,
            selectionStartOffset: selectedCopy?.range?.startOffset,
            selectionEndOffset: selectedCopy?.range?.endOffset,
          );
      if (selectionPayload != null) {
        final OpenCrayHostBridge? bridge = widget.bridge;
        if (bridge == null) {
          await Clipboard.setData(
            ClipboardData(text: selectionPayload.plainText),
          );
        } else {
          await bridge.copyRichTextToClipboard(
            plainText: selectionPayload.plainText,
            htmlText: selectionPayload.htmlText,
          );
        }
        return;
      }
      await Clipboard.setData(ClipboardData(text: selectedText));
      return;
    }
    final OpenCrayMarkdownClipboardPayload? clipboardPayload =
        openCrayBuildMarkdownClipboardPayload(menu.message.text);
    if (clipboardPayload == null) {
      await Clipboard.setData(ClipboardData(text: menu.message.text));
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null) {
      await Clipboard.setData(ClipboardData(text: clipboardPayload.plainText));
      return;
    }
    await bridge.copyRichTextToClipboard(
      plainText: clipboardPayload.plainText,
      htmlText: clipboardPayload.htmlText,
    );
  }

  void _enterMessageSelectionMode(ChatMessageData message) {
    if (message.messageId.isEmpty) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    _emitSelectionHaptic();
    setState(() {
      _selectedMessageIds
        ..clear()
        ..add(message.messageId);
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _state = _state.copyWith(
        drawerOpen: false,
        composer: _state.composer.copyWith(
          showAddMenu: false,
          commandOptions: const <ChatCommandOptionData>[],
          clearSelectedCommand: true,
        ),
      );
    });
  }

  void _clearMessageSelection({bool emitHaptic = true}) {
    if (_selectedMessageIds.isEmpty) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    if (emitHaptic) {
      _emitSelectionHaptic();
    }
    setState(() {
      _selectedMessageIds.clear();
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
    });
  }

  void _toggleMessageSelection(ChatMessageData message) {
    if (message.kind == ChatMessageKind.timeline || message.messageId.isEmpty) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    _emitSelectionHaptic();
    setState(() {
      if (_selectedMessageIds.contains(message.messageId)) {
        _selectedMessageIds.remove(message.messageId);
      } else {
        _selectedMessageIds.add(message.messageId);
      }
    });
  }

  String _selectedMessagesClipboardText() {
    final List<String> chunks = _selectedMessagesInOrder
        .map((message) {
          final List<String> parts = <String>[];
          final String text = message.text.trim();
          if (text.isNotEmpty) {
            parts.add(text);
          }
          for (final attachment in message.attachments) {
            parts.add('[${attachment.displayName}]');
          }
          return parts.join('\n').trim();
        })
        .where((chunk) => chunk.isNotEmpty)
        .toList(growable: false);
    return chunks.join('\n\n');
  }

  Future<void> _copySelectedMessages() async {
    final String text = _selectedMessagesClipboardText();
    if (text.isEmpty) {
      return;
    }
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) {
      return;
    }
    _showMessageFeedback(widget.copy.chatSelectionCopied);
  }

  Future<void> _deleteSelectedMessages() async {
    final List<String> selectedIds = _selectedMessageIds
        .map((messageId) => messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toList(growable: false);
    if (selectedIds.isEmpty) {
      return;
    }
    final Set<String> deleteIdSet = _deleteTargetMessageIdsForMessages(
      _selectedMessagesInOrder,
    )..addAll(selectedIds);
    final List<String> deleteIds = <String>[
      for (final ChatMessageData message in _state.messages)
        if (deleteIdSet.contains(message.messageId.trim()))
          message.messageId.trim(),
    ];
    for (final String selectedId in selectedIds) {
      if (!deleteIds.contains(selectedId)) {
        deleteIds.add(selectedId);
      }
    }
    final String sessionId = _activeSessionId;
    final Set<String> deletingIds = _stageMessageDeleteMotion(
      sessionId,
      deleteIds,
    );
    if (deletingIds.isEmpty) {
      return;
    }
    await _waitForMessageDeleteMotion();
    if (!mounted) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      setState(() {
        _forgetDeletingMessageIds(sessionId, deletingIds);
        _rememberLocallyDeletedMessages(sessionId, deletingIds);
        _state = _applyLocalDeletionTombstones(_state);
      });
      final Set<String> pendingIds = <String>{...deletingIds};
      final Set<String> failedOrUnsentIds = <String>{};
      for (final String messageId in deleteIds) {
        if (!deletingIds.contains(messageId)) {
          continue;
        }
        try {
          await bridge.deleteChatMessage(
            sessionId: sessionId,
            messageId: messageId,
          );
          pendingIds.remove(messageId);
        } catch (_) {
          failedOrUnsentIds
            ..add(messageId)
            ..addAll(pendingIds);
          break;
        }
      }
      if (failedOrUnsentIds.isNotEmpty) {
        if (!mounted) {
          return;
        }
        _forgetLocallyDeletedMessages(sessionId, failedOrUnsentIds);
        _applyHostState();
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    setState(() {
      _forgetDeletingMessageIds(sessionId, deletingIds);
      _state = _state.copyWith(
        messages: _state.messages
            .where((message) => !deletingIds.contains(message.messageId.trim()))
            .toList(growable: false),
      );
    });
  }

  void _handleMessageLongPress(
    ChatMessageData message,
    Rect globalBubbleRect,
    String? selectedText,
  ) {
    final BuildContext? overlayContext = _chatOverlayKey.currentContext;
    final RenderObject? overlayRenderObject = overlayContext
        ?.findRenderObject();
    if (overlayRenderObject is! RenderBox) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    unawaited(HapticFeedback.lightImpact());
    final Rect bubbleRect = Rect.fromPoints(
      overlayRenderObject.globalToLocal(globalBubbleRect.topLeft),
      overlayRenderObject.globalToLocal(globalBubbleRect.bottomRight),
    );
    setState(() {
      _state = _state.copyWith(
        composer: _state.composer.copyWith(
          showAddMenu: false,
          commandOptions: const <ChatCommandOptionData>[],
          clearSelectedCommand: true,
        ),
      );
      _suppressNextTransientUiDismiss = true;
      _messageMenuExitEpoch += 1;
      _exitingMessageMenu = null;
      _activeMessageMenu = _ActiveChatMessageMenu(
        message: message,
        bubbleRect: bubbleRect,
        selectionVersionAtOpen:
            _selectedTextVersionByMessageId[message.messageId] ?? 0,
        redoPrompt: _redoPromptForMessage(message),
        selectedText: selectedText,
      );
    });
  }

  Future<void> _handleMessageMenuAction(_ChatMessageMenuAction action) async {
    final activeMenu = _activeMessageMenu;
    if (activeMenu == null) {
      return;
    }
    _dismissMessageMenu();
    switch (action) {
      case _ChatMessageMenuAction.copy:
        await _copyMessageFromMenu(activeMenu);
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageCopied);
        break;
      case _ChatMessageMenuAction.recall:
        if (!activeMenu.canRecall) {
          return;
        }
        await _recallChatMessage(activeMenu.message);
        break;
      case _ChatMessageMenuAction.redo:
        if (!activeMenu.canRedo) {
          return;
        }
        await _redoChatMessage(activeMenu.message);
        break;
      case _ChatMessageMenuAction.edit:
        if (!activeMenu.canEdit) {
          return;
        }
        await _editChatMessage(activeMenu.message);
        break;
      case _ChatMessageMenuAction.branch:
        if (!activeMenu.canBranch) {
          return;
        }
        await _branchChatMessage(activeMenu.message);
        break;
      case _ChatMessageMenuAction.delete:
        if (!activeMenu.canDelete) {
          return;
        }
        await _deleteChatMessage(activeMenu.message);
        break;
      case _ChatMessageMenuAction.multiSelect:
        _enterMessageSelectionMode(activeMenu.message);
        break;
      case _ChatMessageMenuAction.quote:
        _quoteChatMessage(activeMenu.message);
        break;
    }
  }

  Future<void> _deleteChatMessage(ChatMessageData message) async {
    final String messageId = message.messageId.trim();
    if (messageId.isEmpty) {
      return;
    }
    final Set<String> deleteIdSet = _deleteTargetMessageIdsForMessage(message);
    final List<String> deleteIds = deleteIdSet.toList(growable: false);
    if (!deleteIds.contains(messageId)) {
      deleteIds.add(messageId);
    }
    final String sessionId = _activeSessionId;
    final Set<String> deletingIds = _stageMessageDeleteMotion(
      sessionId,
      deleteIds,
    );
    if (deletingIds.isEmpty) {
      return;
    }
    await _waitForMessageDeleteMotion();
    if (!mounted) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      setState(() {
        _forgetDeletingMessageIds(sessionId, deletingIds);
        _rememberLocallyDeletedMessages(sessionId, deletingIds);
        _state = _applyLocalDeletionTombstones(_state);
      });
      final Set<String> pendingIds = <String>{...deletingIds};
      final Set<String> failedOrUnsentIds = <String>{};
      try {
        for (final String deletedMessageId in deleteIds) {
          if (!deletingIds.contains(deletedMessageId)) {
            continue;
          }
          await bridge.deleteChatMessage(
            sessionId: sessionId,
            messageId: deletedMessageId,
          );
          pendingIds.remove(deletedMessageId);
        }
      } catch (_) {
        if (!mounted) {
          return;
        }
        failedOrUnsentIds.addAll(pendingIds);
        _forgetLocallyDeletedMessages(sessionId, failedOrUnsentIds);
        _applyHostState();
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    setState(() {
      _forgetDeletingMessageIds(sessionId, deletingIds);
      _state = _state.copyWith(
        messages: _state.messages
            .where(
              (candidate) => !deletingIds.contains(candidate.messageId.trim()),
            )
            .toList(growable: false),
      );
    });
  }

  Future<void> _recallChatMessage(ChatMessageData message) async {
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        await bridge.recallChatMessage(
          sessionId: _activeSessionId,
          messageId: message.messageId,
        );
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    final int recallIndex = _state.messages.indexWhere(
      (candidate) => candidate.messageId == message.messageId,
    );
    if (recallIndex < 0) {
      return;
    }
    setState(() {
      _state = _state.copyWith(
        messages: _state.messages.take(recallIndex).toList(growable: false),
      );
    });
  }

  ChatMessageData? _redoPromptForMessage(ChatMessageData message) {
    if (message.kind != ChatMessageKind.inbound || message.isEphemeral) {
      return null;
    }
    final int messageIndex = _state.messages.indexWhere(
      (candidate) => candidate.messageId == message.messageId,
    );
    if (messageIndex <= 0) {
      return null;
    }
    for (int index = messageIndex - 1; index >= 0; index -= 1) {
      final ChatMessageData candidate = _state.messages[index];
      if (candidate.kind == ChatMessageKind.outbound &&
          !candidate.isEphemeral) {
        return candidate;
      }
    }
    return null;
  }

  Future<void> _redoChatMessage(ChatMessageData message) async {
    final ChatMessageData? redoPrompt = _redoPromptForMessage(message);
    if (redoPrompt == null) {
      if (!mounted) {
        return;
      }
      _showMessageFeedback(widget.copy.chatMessageActionFailed);
      return;
    }
    final bridge = widget.bridge;
    final List<OpenCrayChatDraftAttachment> redoAttachments =
        _draftAttachmentsForMessage(redoPrompt);
    if (bridge != null) {
      try {
        await bridge.recallChatMessage(
          sessionId: _activeSessionId,
          messageId: redoPrompt.messageId,
        );
        await bridge.submitChatMessage(
          redoPrompt.text,
          attachments: redoAttachments,
        );
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    final int promptIndex = _state.messages.indexWhere(
      (candidate) => candidate.messageId == redoPrompt.messageId,
    );
    if (promptIndex < 0) {
      return;
    }
    final int stamp = DateTime.now().microsecondsSinceEpoch;
    setState(() {
      _state = _state.copyWith(
        messages: <ChatMessageData>[
          ..._state.messages.take(promptIndex),
          ChatMessageData(
            messageId: 'redo-outbound-$stamp',
            kind: ChatMessageKind.outbound,
            text: redoPrompt.text,
            attachments: redoPrompt.attachments,
          ),
          ChatMessageData(
            messageId: 'redo-inbound-$stamp',
            kind: ChatMessageKind.inbound,
            text: widget.copy.chatRunThinkingActive,
          ),
        ],
      );
    });
    _scheduleScrollToBottom();
  }

  Future<void> _editChatMessage(ChatMessageData message) async {
    final String draft = message.text;
    final List<ChatAttachmentData> draftAttachments =
        _composerAttachmentsForMessage(message);
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        await bridge.recallChatMessage(
          sessionId: _activeSessionId,
          messageId: message.messageId,
        );
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
        return;
      }
    } else {
      final int recallIndex = _state.messages.indexWhere(
        (candidate) => candidate.messageId == message.messageId,
      );
      if (recallIndex < 0) {
        return;
      }
      setState(() {
        _state = _state.copyWith(
          messages: _state.messages.take(recallIndex).toList(growable: false),
        );
      });
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _state = _state.copyWith(
        composer: _state.composer.copyWith(
          attachments: draftAttachments,
          commandOptions: const <ChatCommandOptionData>[],
          showAddMenu: false,
          clearSelectedCommand: true,
        ),
      );
    });
    _composerController.value = TextEditingValue(
      text: draft,
      selection: TextSelection.collapsed(offset: draft.length),
    );
  }

  Future<void> _branchChatMessage(ChatMessageData message) async {
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        await bridge.branchChatSessionFromMessage(
          sessionId: _activeSessionId,
          messageId: message.messageId,
        );
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    final int branchIndex = _state.messages.indexWhere(
      (candidate) => candidate.messageId == message.messageId,
    );
    final List<ChatMessageData> branchMessages =
        (branchIndex >= 0
                ? _state.messages.take(branchIndex + 1)
                : _state.messages)
            .toList(growable: false);
    final ChatSessionListItemData sourceSession = _state.drawer.sessions
        .firstWhere(
          (session) => session.isSelected,
          orElse: () => _state.drawer.sessions.first,
        );
    final String preview = _branchPreviewText(
      branchMessages,
      fallback: sourceSession.preview,
    );
    final ChatSessionListItemData branchSession = ChatSessionListItemData(
      sessionId:
          '${sourceSession.sessionId}-branch-${_state.drawer.sessions.length + 1}',
      title: _branchSessionTitle(sourceSession.title),
      preview: preview,
      meta: sourceSession.meta,
      isSelected: true,
      lastMessageAtEpochMs: sourceSession.lastMessageAtEpochMs,
    );
    final List<ChatSessionListItemData> updatedSessions =
        <ChatSessionListItemData>[
          branchSession,
          ..._state.drawer.sessions.map(
            (session) => ChatSessionListItemData(
              sessionId: session.sessionId,
              title: session.title,
              preview: session.preview,
              meta: session.meta,
              isSelected: false,
              lastMessageAtEpochMs: session.lastMessageAtEpochMs,
              unreadCount: session.unreadCount,
            ),
          ),
        ];
    setState(() {
      _state = _state.copyWith(
        messages: branchMessages,
        summary: ChatSessionSummary(
          title: branchSession.title,
          badge: _state.summary.badge,
          body: preview.isNotEmpty ? preview : _state.summary.body,
        ),
        drawer: ChatSessionsDrawerState(
          eyebrow: _state.drawer.eyebrow,
          title: _state.drawer.title,
          ctaLabel: _state.drawer.ctaLabel,
          sessions: updatedSessions,
        ),
      );
    });
  }

  void _quoteChatMessage(ChatMessageData message) {
    final String quoted = message.text
        .trim()
        .split('\n')
        .map((line) => '> $line')
        .join('\n');
    if (quoted.isEmpty) {
      return;
    }
    final String existing = _composerController.text.trimLeft();
    final String nextText = existing.isEmpty
        ? '$quoted\n\n'
        : '$quoted\n\n$existing';
    _composerController.value = TextEditingValue(
      text: nextText,
      selection: TextSelection.collapsed(offset: nextText.length),
    );
    _composerFocusNode.requestFocus();
    _showMessageFeedback(widget.copy.chatMessageQuoted);
  }

  String _branchPreviewText(
    List<ChatMessageData> messages, {
    required String fallback,
  }) {
    for (final ChatMessageData message in messages.reversed) {
      final String trimmed = message.text.trim();
      if (trimmed.isEmpty || message.kind == ChatMessageKind.timeline) {
        continue;
      }
      return trimmed;
    }
    return fallback;
  }

  String _branchSessionTitle(String title) {
    if (title.endsWith(' branch')) {
      return title;
    }
    if (title.length >= 25) {
      return '${title.substring(0, 25)} branch';
    }
    return '$title branch';
  }

  @override
  Widget build(BuildContext context) {
    const double toolbarReserveHeight = 44;
    final double topGlassBarHeight =
        MediaQuery.paddingOf(context).top + toolbarReserveHeight + 4;
    final List<ChatPendingApprovalData> visibleApprovals =
        _visiblePendingApprovals(_state.pendingApprovals);
    final bool showApprovalSurface =
        !_isMessageSelectionMode && visibleApprovals.isNotEmpty;
    final Widget bottomSurface = _isMessageSelectionMode
        ? _ChatSelectionToolbar(
            copy: widget.copy,
            selectedCount: _selectedMessageCount,
            onCopyPressed: _selectedMessageIds.isEmpty
                ? null
                : () {
                    _copySelectedMessages();
                  },
            onDeletePressed: _selectedMessageIds.isEmpty
                ? null
                : () {
                    _deleteSelectedMessages();
                  },
          )
        : showApprovalSurface
        ? _PendingApprovalOverlaySurface(
            copy: widget.copy,
            approvals: visibleApprovals,
            busyApprovalTaskIds: _approvalTaskIdsInFlight,
            approvalResolutionById: _approvalResolutionById,
            onApproveApproval: _approvePendingApproval,
            onApproveApprovalForSession: _approvePendingApprovalForSession,
            onRejectApproval: _rejectPendingApproval,
          )
        : _ComposerCard(
            copy: widget.copy,
            state: _state,
            bridge: widget.bridge,
            controller: _composerController,
            focusNode: _composerFocusNode,
            onPlusPressed: _togglePlusMenu,
            onSendPressed: () {
              _sendCurrentState();
            },
            interruptTrace: _composerInterruptTrace,
            interruptConfirmRunId: _interruptConfirmRunId,
            busyInterruptRunIds: _interruptRunIdsInFlight,
            onArmInterruptRunTrace: _armRunInterruptTrace,
            onDismissInterruptRunTrace: _dismissRunInterruptTrace,
            onInterruptRunTrace: _interruptRunTrace,
            onAddActionSelected: _handleAddAction,
            onCommandSelected: _showCommandMenu,
            onAttachmentRemoved: _removeAttachment,
          );
    _scheduleComposerHeightSync();

    final Widget page = ColoredBox(
      color: _ChatPalette.background,
      child: Stack(
        key: _chatOverlayKey,
        children: <Widget>[
          SafeArea(
            bottom: false,
            child: Column(
              children: <Widget>[
                const SizedBox(height: toolbarReserveHeight),
                Expanded(
                  child: Stack(
                    children: <Widget>[
                      Positioned.fill(
                        child: GestureDetector(
                          onTap: _dismissTransientUi,
                          behavior: HitTestBehavior.translucent,
                          child: CustomScrollView(
                            key: const ValueKey<String>('chat-scroll-view'),
                            controller: _chatScrollController,
                            slivers: <Widget>[
                              SliverPadding(
                                padding: EdgeInsets.fromLTRB(
                                  20,
                                  4,
                                  20,
                                  _composerScrollInset(),
                                ),
                                sliver: _ChatScrollContent(
                                  bridge: widget.bridge,
                                  copy: widget.copy,
                                  state: _state,
                                  scrollController: _chatScrollController,
                                  showSandboxPreviewCards:
                                      _selectedRuntimeEnvironment ==
                                      _ChatRuntimeEnvironment.cloud,
                                  voicePlaybackControllerFactory:
                                      widget.voicePlaybackControllerFactory,
                                  selectedMessageIds: _selectedMessageIds,
                                  deletingMessageIds:
                                      _deletingMessageIdsForSession(
                                        _sessionIdForState(_state),
                                      ),
                                  interruptConfirmRunId: _interruptConfirmRunId,
                                  busyInterruptRunIds: _interruptRunIdsInFlight,
                                  busyRetryRunIds: _retryRunIdsInFlight,
                                  onArmInterruptRunTrace: _armRunInterruptTrace,
                                  onDismissInterruptRunTrace:
                                      _dismissRunInterruptTrace,
                                  onInterruptRunTrace: _interruptRunTrace,
                                  onRetryRunTrace: _retryRunTrace,
                                  onMessageLongPress: _handleMessageLongPress,
                                  onMessageSelectionToggle:
                                      _toggleMessageSelection,
                                  onMessageTextSelectionChanged:
                                      _handleMessageTextSelectionChanged,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      Align(
                        alignment: Alignment.bottomCenter,
                        child: Padding(
                          padding: EdgeInsets.fromLTRB(
                            20,
                            0,
                            20,
                            widget.bottomInset,
                          ),
                          child: KeyedSubtree(
                            key: _composerKey,
                            child: bottomSurface,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          if (_activeMessageMenu != null || _exitingMessageMenu != null)
            Positioned.fill(
              child: Stack(
                children: <Widget>[
                  if (_activeMessageMenu != null)
                    Positioned.fill(
                      child: GestureDetector(
                        key: const ValueKey<String>(
                          'chat-message-menu-dismiss-layer',
                        ),
                        onTap: _dismissMessageMenu,
                        behavior: HitTestBehavior.translucent,
                        child: const SizedBox.expand(),
                      ),
                    ),
                  _ChatMessageMenuOverlay(
                    copy: widget.copy,
                    menu: _activeMessageMenu ?? _exitingMessageMenu!,
                    isExiting: _activeMessageMenu == null,
                    onActionSelected: _handleMessageMenuAction,
                  ),
                ],
              ),
            ),
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: IgnorePointer(
              child: AnimatedBuilder(
                animation: _chatScrollController,
                builder: (BuildContext context, Widget? child) {
                  return _TopGlassBar(
                    height: topGlassBarHeight,
                    strength: _topGlassStrength(),
                  );
                },
              ),
            ),
          ),
          SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 6, 20, 0),
              child: _ChatToolbar(
                copy: widget.copy,
                sessionButtonLabel: _state.sessionButtonLabel,
                modeLabel: _state.modeLabel,
                runtimeEnvironment: _selectedRuntimeEnvironment,
                onRuntimeEnvironmentSelected: _handleRuntimeEnvironmentSelected,
                onSessionsPressed: _showDrawer,
                isSelectionMode: _isMessageSelectionMode,
                selectedCount: _selectedMessageCount,
                onDonePressed: _clearMessageSelection,
              ),
            ),
          ),
          _SessionsDrawerOverlay(
            isOpen: _state.drawerOpen,
            copy: widget.copy,
            drawer: _state.drawer,
            onDismiss: _closeDrawer,
            onNewSessionPressed: _showEmpty,
            onSessionPressed: _handleSessionSelected,
            onSessionLongPress: _handleSessionLongPress,
          ),
        ],
      ),
    );
    if (widget.controller != null) {
      return page;
    }
    return PopScope<void>(
      canPop: !_isMessageSelectionMode,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) {
          return;
        }
        _consumeBackPress();
      },
      child: page,
    );
  }

  double _composerScrollInset() {
    final double measuredHeight = _composerHeight;
    if (measuredHeight > 0) {
      return widget.bottomInset + measuredHeight + 12;
    }
    return widget.bottomInset + 84;
  }

  double _topGlassStrength() {
    if (!_chatScrollController.hasClients) {
      return 0;
    }
    final double offset = _chatScrollController.position.pixels;
    if (offset <= 0) {
      return 0;
    }
    if (offset >= 56) {
      return 1;
    }
    return offset / 56;
  }

  void _scheduleComposerHeightSync() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      final BuildContext? composerContext = _composerKey.currentContext;
      final double? nextHeight = composerContext?.size?.height;
      if (nextHeight == null || (nextHeight - _composerHeight).abs() < 0.5) {
        return;
      }
      setState(() {
        _composerHeight = nextHeight;
      });
    });
  }

  bool _isChatScrollPinnedToBottom({double tolerance = 48}) {
    if (!_chatScrollController.hasClients) {
      return true;
    }
    final ScrollPosition position = _chatScrollController.position;
    return position.maxScrollExtent - position.pixels <= tolerance;
  }

  void _scheduleScrollToBottom({
    bool animated = true,
    bool onlyIfPinned = false,
    bool? wasPinnedToBottom,
  }) {
    final bool pinnedBeforeUpdate =
        wasPinnedToBottom ?? _isChatScrollPinnedToBottom();
    if (onlyIfPinned && !pinnedBeforeUpdate) {
      return;
    }
    if (_scrollToBottomScheduled) {
      _scheduledScrollToBottomAnimated =
          _scheduledScrollToBottomAnimated && animated;
      return;
    }
    _scrollToBottomScheduled = true;
    _scheduledScrollToBottomAnimated = animated;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final bool shouldAnimate = _scheduledScrollToBottomAnimated;
      _scrollToBottomScheduled = false;
      _scheduledScrollToBottomAnimated = true;
      if (!mounted || !_chatScrollController.hasClients) {
        return;
      }
      final ScrollPosition position = _chatScrollController.position;
      final double target = position.maxScrollExtent;
      if ((target - position.pixels).abs() < 1) {
        return;
      }
      if (shouldAnimate) {
        _chatScrollController.animateTo(
          target,
          duration: OpenCrayMotion.expand,
          curve: OpenCrayMotion.enter,
        );
        return;
      }
      _chatScrollController.jumpTo(target);
    });
  }

  void _showDrawer() {
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _state = _state.copyWith(drawerOpen: true);
    });
  }

  void _closeDrawer() {
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _state = _state.copyWith(drawerOpen: false);
    });
  }

  void _showEmpty() {
    final bridge = widget.bridge;
    _dismissMessageMenu();
    if (bridge != null) {
      unawaited(_createSessionFromBridge(bridge));
      return;
    }
    setState(() {
      _state = OpenCrayChatSeedData.empty(
        widget.copy,
      ).copyWith(drawerOpen: false);
    });
  }

  Future<void> _createSessionFromBridge(OpenCrayHostBridge bridge) async {
    try {
      await bridge.createChatSession();
      if (!mounted) {
        return;
      }
      _closeDrawer();
    } catch (_) {
      if (!mounted) {
        return;
      }
      _showSessionActionFailed();
    }
  }

  void _togglePlusMenu() {
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      final composer = _state.composer;
      _state = _state.copyWith(
        composer: composer.copyWith(
          showAddMenu: !composer.showAddMenu,
          addActions: OpenCrayChatSeedData.sampleAddActions(widget.copy),
          commandOptions: const <ChatCommandOptionData>[],
          clearSelectedCommand: true,
        ),
      );
    });
  }

  void _handleAddAction(ChatAddActionData action) {
    if (action.label == widget.copy.chatActionCommand) {
      setState(() {
        _state = _state.copyWith(
          composer: _state.composer.copyWith(
            showAddMenu: false,
            commandOptions: OpenCrayChatSeedData.sampleCommandOptions(
              widget.copy,
            ),
            clearSelectedCommand: true,
          ),
        );
      });
      return;
    }

    if (_attachmentKindForAction(action.label) ==
            OpenCrayChatDraftAttachmentKind.image &&
        _currentComposerImageCount >= _chatComposerMaxImageAttachments) {
      _showComposerNotice(_attachmentImageLimitMessage(skippedCount: 0));
      return;
    }

    final bridge = widget.bridge;
    if (bridge == null) {
      setState(() {
        final currentAttachments = List<ChatAttachmentData>.of(
          _state.composer.attachments,
        );
        final attachment = _attachmentForAction(action.label);
        final alreadyPresent = currentAttachments.any(
          (ChatAttachmentData item) => item.id == attachment.id,
        );
        if (!alreadyPresent) {
          currentAttachments.add(attachment);
        }
        _state = _state.copyWith(
          composer: _state.composer.copyWith(
            attachments: currentAttachments,
            showAddMenu: true,
            addActions: OpenCrayChatSeedData.sampleAddActions(widget.copy),
          ),
        );
      });
      return;
    }

    unawaited(_pickAttachmentsFromBridge(action, bridge));
  }

  Future<void> _pickAttachmentsFromBridge(
    ChatAddActionData action,
    OpenCrayHostBridge bridge,
  ) async {
    try {
      final pickedAttachments = await bridge.pickChatAttachments(
        kind: _attachmentKindForAction(action.label),
      );
      if (!mounted || pickedAttachments.isEmpty) {
        return;
      }
      int duplicateCount = 0;
      int skippedImageCount = 0;
      setState(() {
        final currentAttachments = List<ChatAttachmentData>.of(
          _state.composer.attachments,
        );
        int imageCount = currentAttachments.where((ChatAttachmentData item) {
          return item.kind == ChatAttachmentKind.image;
        }).length;
        for (final attachment in pickedAttachments) {
          final draft = _draftAttachmentForComposer(attachment);
          final alreadyPresent = currentAttachments.any(
            (ChatAttachmentData item) => item.id == draft.id,
          );
          if (!alreadyPresent) {
            if (draft.kind == ChatAttachmentKind.image &&
                imageCount >= _chatComposerMaxImageAttachments) {
              skippedImageCount += 1;
              continue;
            }
            currentAttachments.add(draft);
            if (draft.kind == ChatAttachmentKind.image) {
              imageCount += 1;
            }
          } else {
            duplicateCount += 1;
          }
        }
        _state = _state.copyWith(
          composer: _state.composer.copyWith(
            attachments: currentAttachments,
            showAddMenu: true,
            addActions: OpenCrayChatSeedData.sampleAddActions(widget.copy),
          ),
        );
      });
      final notices = <String>[
        if (duplicateCount > 0) _attachmentDuplicateMessage(duplicateCount),
        if (skippedImageCount > 0)
          _attachmentImageLimitMessage(skippedCount: skippedImageCount),
      ];
      if (mounted && notices.isNotEmpty) {
        _showComposerNotice(notices.join(' '));
      }
    } catch (error) {
      if (!mounted) {
        return;
      }
      _showComposerNotice(_attachmentPickerFailureMessage(error));
    }
  }

  void _showCommandMenu() {
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _state = _state.copyWith(
        composer: _state.composer.copyWith(
          showAddMenu: false,
          commandOptions: OpenCrayChatSeedData.sampleCommandOptions(
            widget.copy,
          ),
          clearSelectedCommand: true,
        ),
      );
    });
  }

  Future<void> _sendCurrentState() async {
    _dismissMessageMenu();
    if (!_state.isInputEnabled) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      final text = _composerController.text.trim();
      final attachments = _state.composer.attachments
          .map((ChatAttachmentData attachment) => attachment.draftAttachment)
          .whereType<OpenCrayChatDraftAttachment>()
          .toList(growable: false);
      if (text.isEmpty && attachments.isEmpty) {
        return;
      }
      try {
        await bridge.submitChatMessage(text, attachments: attachments);
        if (!mounted) {
          return;
        }
        setState(() {
          _composerController.clear();
          _state = _state.copyWith(
            composer: _state.composer.copyWith(
              attachments: const <ChatAttachmentData>[],
              commandOptions: const <ChatCommandOptionData>[],
              showAddMenu: false,
              clearSelectedCommand: true,
            ),
          );
        });
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showComposerNotice(widget.copy.chatSubmitFailed);
      }
      return;
    }
    setState(() {
      _state = _state.copyWith(
        composer: _state.composer.copyWith(
          attachments: const <ChatAttachmentData>[],
          commandOptions: const <ChatCommandOptionData>[],
          showAddMenu: false,
          clearSelectedCommand: true,
        ),
      );
    });
  }

  void _handleChatSnapshot(OpenCrayChatSnapshot snapshot) {
    if (!shouldReplaceObservedChatSnapshot(_latestChatSnapshot, snapshot)) {
      return;
    }
    final OpenCrayChatRuntimeSnapshot? embeddedRuntime =
        snapshot.runtimeActivity;
    if (embeddedRuntime != null) {
      final OpenCrayChatRuntimeSnapshot resolvedRuntime =
          _latestChatRuntimeSnapshot != null &&
              !_runtimeSnapshotsShareSession(
                _latestChatRuntimeSnapshot!,
                embeddedRuntime,
              ) &&
              embeddedRuntime.sessionId.trim().isNotEmpty
          ? embeddedRuntime
          : resolveChatRuntimeSnapshot(
                  _latestChatRuntimeSnapshot,
                  embeddedRuntime,
                ) ??
                embeddedRuntime;
      _reconcileLiveAssistantDraftOverrides(resolvedRuntime);
      if (shouldReplaceObservedRuntimeSnapshot(
        _latestChatRuntimeSnapshot,
        resolvedRuntime,
      )) {
        _latestChatRuntimeSnapshot = resolvedRuntime;
        _recordRuntimeSnapshotWatermark(resolvedRuntime);
      }
    }
    _latestChatSnapshot = snapshot;
    _applyHostState();
  }

  bool _handleChatRuntimeSnapshot(
    OpenCrayChatRuntimeSnapshot snapshot, {
    bool authoritative = false,
  }) {
    final String streamInstanceId = snapshot.streamInstanceId?.trim() ?? '';
    final bool hasOrderedEnvelope =
        streamInstanceId.isNotEmpty && snapshot.lastSequence != null;
    if (hasOrderedEnvelope) {
      if (_runtimeSnapshotIsBehindCurrentWatermark(snapshot)) {
        return false;
      }
      authoritative = true;
    }
    final OpenCrayChatRuntimeSnapshot resolvedSnapshot = authoritative
        ? snapshot
        : _latestChatRuntimeSnapshot != null &&
              !_runtimeSnapshotsShareSession(
                _latestChatRuntimeSnapshot!,
                snapshot,
              ) &&
              snapshot.sessionId.trim().isNotEmpty
        ? snapshot
        : resolveChatRuntimeSnapshot(_latestChatRuntimeSnapshot, snapshot) ??
              snapshot;
    if (!authoritative &&
        !shouldReplaceObservedRuntimeSnapshot(
          _latestChatRuntimeSnapshot,
          resolvedSnapshot,
        )) {
      return false;
    }
    final List<String> activeRunSummaries = resolvedSnapshot.activeRuns
        .map(
          (run) =>
              '${run.runId}:${run.managedProcesses.length}/${run.runningManagedProcessCount}/${run.hasLiveManagedProcesses}',
        )
        .toList(growable: false);
    _runTraceDebug(
      'feature.runtime session=${resolvedSnapshot.sessionId} activeRuns=${resolvedSnapshot.activeRuns.length} retainedRuns=${resolvedSnapshot.retainedRuns.length} events=${resolvedSnapshot.events.length} runs=${activeRunSummaries.join(';')}',
    );
    _reconcileLiveAssistantDraftOverrides(resolvedSnapshot);
    _latestChatRuntimeSnapshot = resolvedSnapshot;
    _recordRuntimeSnapshotWatermark(
      resolvedSnapshot,
      authoritative: authoritative,
    );
    _queueRuntimeActivityPatch(resolvedSnapshot);
    return true;
  }

  bool _runtimeSnapshotIsBehindCurrentWatermark(
    OpenCrayChatRuntimeSnapshot snapshot,
  ) {
    final String sessionId = snapshot.sessionId.trim();
    final String streamInstanceId = snapshot.streamInstanceId?.trim() ?? '';
    final int? lastSequence = snapshot.lastSequence;
    if (sessionId.isEmpty || streamInstanceId.isEmpty || lastSequence == null) {
      return false;
    }
    final String currentStreamInstanceId = _currentRuntimeStreamInstanceId(
      sessionId,
    );
    final int? currentSequence = _runtimeEventDeltaSequenceBySession[sessionId];
    return currentStreamInstanceId == streamInstanceId &&
        currentSequence != null &&
        lastSequence < currentSequence;
  }

  void _recordRuntimeSnapshotWatermark(
    OpenCrayChatRuntimeSnapshot snapshot, {
    bool authoritative = false,
  }) {
    final String sessionId = snapshot.sessionId.trim();
    if (sessionId.isEmpty) {
      return;
    }
    final String streamInstanceId = snapshot.streamInstanceId?.trim() ?? '';
    final String previousStreamInstanceId =
        _runtimeStreamInstanceBySession[sessionId] ?? '';
    final String bridgeEpoch = snapshot.bridgeEpoch?.trim() ?? '';
    final String previousBridgeEpoch =
        _runtimeBridgeEpochBySession[sessionId] ?? '';
    final bool identityChanged =
        (streamInstanceId.isNotEmpty &&
            previousStreamInstanceId != streamInstanceId) ||
        (bridgeEpoch.isNotEmpty && previousBridgeEpoch != bridgeEpoch);
    if (identityChanged) {
      _resetRealtimeSessionIdentity(
        sessionId: sessionId,
        streamInstanceId: streamInstanceId,
        bridgeEpoch: bridgeEpoch,
      );
    }
    if (streamInstanceId.isNotEmpty) {
      _runtimeStreamInstanceBySession[sessionId] = streamInstanceId;
    }
    if (bridgeEpoch.isNotEmpty) {
      _runtimeBridgeEpochBySession[sessionId] = bridgeEpoch;
    }
    final int? lastSequence = snapshot.lastSequence;
    if (lastSequence != null) {
      _runtimeEventDeltaSequenceBySession[sessionId] = math.max(
        0,
        lastSequence,
      );
    } else if (authoritative) {
      _runtimeEventDeltaSequenceBySession.remove(sessionId);
    }
  }

  String _currentRuntimeStreamInstanceId(String sessionId) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return '';
    }
    final String recorded =
        _runtimeStreamInstanceBySession[normalizedSessionId] ?? '';
    if (recorded.isNotEmpty) {
      return recorded;
    }
    final OpenCrayChatRuntimeSnapshot? snapshot = _latestChatRuntimeSnapshot;
    if (snapshot?.sessionId.trim() == normalizedSessionId) {
      return snapshot?.streamInstanceId?.trim() ?? '';
    }
    return '';
  }

  String _currentRuntimeBridgeEpoch(String sessionId) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return '';
    }
    final String recorded =
        _runtimeBridgeEpochBySession[normalizedSessionId] ?? '';
    if (recorded.isNotEmpty) {
      return recorded;
    }
    final OpenCrayChatRuntimeSnapshot? snapshot = _latestChatRuntimeSnapshot;
    if (snapshot?.sessionId.trim() == normalizedSessionId) {
      return snapshot?.bridgeEpoch?.trim() ?? '';
    }
    return '';
  }

  void _handleRuntimeEventDelta(OpenCrayChatRuntimeEventDelta delta) {
    if (!mounted) {
      return;
    }
    final String sessionId = delta.sessionId.trim();
    if (!_runtimeDeltaTargetsActiveSession(sessionId)) {
      return;
    }
    if (_runtimeEventDeltaResyncInFlight) {
      _queueRuntimeEventDeltaForResync(delta);
      return;
    }
    final String deltaStreamInstanceId = delta.streamInstanceId?.trim() ?? '';
    final String currentStreamInstanceId = _currentRuntimeStreamInstanceId(
      sessionId,
    );
    final String deltaBridgeEpoch = delta.bridgeEpoch?.trim() ?? '';
    final String currentBridgeEpoch = _currentRuntimeBridgeEpoch(sessionId);
    if ((deltaStreamInstanceId.isNotEmpty &&
            currentStreamInstanceId.isNotEmpty &&
            deltaStreamInstanceId != currentStreamInstanceId) ||
        (deltaBridgeEpoch.isNotEmpty &&
            currentBridgeEpoch.isNotEmpty &&
            deltaBridgeEpoch != currentBridgeEpoch) ||
        (delta.lastSequence != null &&
            delta.sequence > 0 &&
            delta.lastSequence != delta.sequence)) {
      _queueRuntimeEventDeltaForResync(delta);
      unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
      return;
    }
    final bool hasSequenceWatermark = _runtimeEventDeltaSequenceBySession
        .containsKey(sessionId);
    final int previousSequence =
        _runtimeEventDeltaSequenceBySession[sessionId] ?? 0;
    if (delta.sequence > 0 && hasSequenceWatermark) {
      if (delta.sequence <= previousSequence) {
        return;
      }
      if (delta.sequence != previousSequence + 1) {
        _queueRuntimeEventDeltaForResync(delta);
        unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
        return;
      }
    }
    if (delta.runPatchMode.trim().toLowerCase() == 'snapshot') {
      _queueRuntimeEventDeltaForResync(delta);
      unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
      return;
    }
    if (_realtimeEventIdWasSeen(sessionId, delta.eventId)) {
      _recordAppliedRuntimeDelta(delta);
      return;
    }
    if (!delta.hasRuntimeActivityPatch) {
      _recordAppliedRuntimeDelta(delta);
      return;
    }
    final OpenCrayChatRuntimeSnapshot? currentSnapshot =
        _latestChatRuntimeSnapshot?.sessionId.trim() == sessionId
        ? _latestChatRuntimeSnapshot
        : _latestChatSnapshot?.runtimeActivity?.sessionId.trim() == sessionId
        ? _latestChatSnapshot?.runtimeActivity
        : null;
    if (currentSnapshot == null &&
        delta.activeRuns.isEmpty &&
        delta.retainedRuns.isEmpty &&
        delta.events.isEmpty &&
        delta.subAgents.isEmpty &&
        delta.liveAssistantDrafts.isEmpty &&
        delta.hostLifecycle == null &&
        delta.updatedAtEpochMs <= 0) {
      _resyncRuntimeSnapshotAfterDeltaMiss();
      return;
    }
    final int latestDeltaEpochMs = delta.events.fold<int>(
      0,
      (latest, event) => math.max(latest, event.emittedAtEpochMs),
    );
    final OpenCrayChatRuntimeSnapshot
    deltaSnapshot = OpenCrayChatRuntimeSnapshot(
      sessionId: sessionId,
      activeRuns: delta.activeRuns,
      retainedRuns: delta.retainedRuns,
      subAgents: delta.subAgents,
      events: delta.events,
      liveAssistantDrafts: delta.liveAssistantDrafts,
      streamInstanceId: delta.streamInstanceId,
      lastSequence:
          delta.lastSequence ?? (delta.sequence > 0 ? delta.sequence : null),
      bridgeInstanceId: delta.bridgeInstanceId,
      bridgeEpoch: delta.bridgeEpoch,
      hostLifecycle: delta.hostLifecycle,
      updatedAtEpochMs: math.max(delta.updatedAtEpochMs, latestDeltaEpochMs),
    );
    final OpenCrayChatRuntimeSnapshot patchedSnapshot = currentSnapshot == null
        ? deltaSnapshot
        : _mergeRuntimeDeltaSnapshot(
            currentSnapshot,
            deltaSnapshot,
            hasActiveRunsPatch:
                delta.runPatchMode.trim().toLowerCase() == 'replace' &&
                delta.hasActiveRunsPatch &&
                (delta.activeRuns.isEmpty ||
                    _runtimeRunListPatchCanReplace(
                      currentSnapshot.activeRuns,
                      deltaSnapshot.activeRuns,
                    )),
            hasRetainedRunsPatch:
                delta.runPatchMode.trim().toLowerCase() == 'replace' &&
                delta.hasRetainedRunsPatch &&
                (delta.retainedRuns.isEmpty ||
                    _runtimeRunListPatchCanReplace(
                      currentSnapshot.retainedRuns,
                      deltaSnapshot.retainedRuns,
                    )),
            hasSubAgentsPatch: delta.hasSubAgentsPatch,
            hasLiveAssistantDraftsPatch: delta.hasLiveAssistantDraftsPatch,
          );
    if (!shouldReplaceObservedRuntimeSnapshot(
      currentSnapshot,
      patchedSnapshot,
    )) {
      if (delta.hasLiveAssistantDraftsPatch) {
        final OpenCrayChatRuntimeSnapshot draftPatchedSnapshot =
            _runtimeSnapshotWithLiveDraftPatch(currentSnapshot, deltaSnapshot);
        _reconcileLiveAssistantDraftOverrides(
          draftPatchedSnapshot,
          liveAssistantDraftsAuthoritative: true,
        );
        if (_latestChatSnapshot != null) {
          _queueRuntimeActivityPatch(draftPatchedSnapshot);
        }
      }
      _recordAppliedRuntimeDelta(delta);
      return;
    }
    if (delta.hasLiveAssistantDraftsPatch) {
      _reconcileLiveAssistantDraftOverrides(
        patchedSnapshot,
        liveAssistantDraftsAuthoritative: true,
      );
    }
    _latestChatRuntimeSnapshot = patchedSnapshot;
    _recordAppliedRuntimeDelta(delta);
    if (_latestChatSnapshot == null) {
      return;
    }
    _queueRuntimeActivityPatch(patchedSnapshot);
  }

  OpenCrayChatRuntimeSnapshot _runtimeSnapshotWithLiveDraftPatch(
    OpenCrayChatRuntimeSnapshot? currentSnapshot,
    OpenCrayChatRuntimeSnapshot deltaSnapshot,
  ) {
    final OpenCrayChatRuntimeSnapshot base = currentSnapshot ?? deltaSnapshot;
    return OpenCrayChatRuntimeSnapshot(
      sessionId: deltaSnapshot.sessionId.trim().isNotEmpty
          ? deltaSnapshot.sessionId
          : base.sessionId,
      activeRuns: base.activeRuns,
      retainedRuns: base.retainedRuns,
      subAgents: base.subAgents,
      events: base.events,
      liveAssistantDrafts: deltaSnapshot.liveAssistantDrafts,
      streamInstanceId: deltaSnapshot.streamInstanceId ?? base.streamInstanceId,
      lastSequence: deltaSnapshot.lastSequence ?? base.lastSequence,
      flutterAppInstanceId:
          deltaSnapshot.flutterAppInstanceId ?? base.flutterAppInstanceId,
      bridgeInstanceId:
          deltaSnapshot.bridgeInstanceId ?? base.bridgeInstanceId,
      bridgeEpoch: deltaSnapshot.bridgeEpoch ?? base.bridgeEpoch,
      hostLifecycle: deltaSnapshot.hostLifecycle ?? base.hostLifecycle,
      updatedAtEpochMs: math.max(
        base.updatedAtEpochMs,
        deltaSnapshot.updatedAtEpochMs,
      ),
    );
  }

  Future<void> _resyncRuntimeSnapshotAfterDeltaMiss() async {
    if (_runtimeEventDeltaResyncInFlight) {
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null) {
      return;
    }
    final int bindingEpoch = _bridgeBindingEpoch;
    _runtimeDeltaResyncRetryTimer?.cancel();
    _runtimeDeltaResyncRetryTimer = null;
    _runtimeEventDeltaResyncInFlight = true;
    bool resynced = false;
    try {
      final OpenCrayChatRuntimeSnapshot snapshot = await bridge
          .loadChatRuntimeSnapshot();
      if (!mounted ||
          _bridgeBindingEpoch != bindingEpoch ||
          widget.bridge != bridge) {
        return;
      }
      resynced = _handleChatRuntimeSnapshot(snapshot, authoritative: true);
    } catch (_) {}
    if (!mounted ||
        _bridgeBindingEpoch != bindingEpoch ||
        widget.bridge != bridge) {
      return;
    }
    _runtimeEventDeltaResyncInFlight = false;
    if (resynced) {
      _drainQueuedRealtimeEventsAfterResync();
    } else {
      _scheduleRuntimeDeltaResyncRetry();
    }
  }

  void _recordAppliedRuntimeDelta(OpenCrayChatRuntimeEventDelta delta) {
    final String sessionId = delta.sessionId.trim();
    if (sessionId.isEmpty) {
      return;
    }
    final String streamInstanceId = delta.streamInstanceId?.trim() ?? '';
    if (streamInstanceId.isNotEmpty) {
      _runtimeStreamInstanceBySession[sessionId] = streamInstanceId;
    }
    final String bridgeEpoch = delta.bridgeEpoch?.trim() ?? '';
    if (bridgeEpoch.isNotEmpty) {
      _runtimeBridgeEpochBySession[sessionId] = bridgeEpoch;
    }
    if (delta.sequence > 0) {
      _runtimeEventDeltaSequenceBySession[sessionId] = delta.sequence;
    }
    _markRealtimeEventIdSeen(sessionId, delta.eventId);
  }

  bool _realtimeEventIdWasSeen(String sessionId, String? eventId) {
    final String normalizedEventId = eventId?.trim() ?? '';
    if (normalizedEventId.isEmpty) {
      return false;
    }
    return _seenRealtimeEventIdsBySession[sessionId]?.contains(
          normalizedEventId,
        ) ??
        false;
  }

  void _markRealtimeEventIdSeen(String sessionId, String? eventId) {
    final String normalizedEventId = eventId?.trim() ?? '';
    if (sessionId.isEmpty || normalizedEventId.isEmpty) {
      return;
    }
    _seenRealtimeEventIdsBySession
        .putIfAbsent(sessionId, () => <String>{})
        .add(normalizedEventId);
  }

  void _queueRuntimeEventDeltaForResync(OpenCrayChatRuntimeEventDelta delta) {
    _queueRealtimeEnvelope(
      _QueuedRealtimeEnvelope.delta(
        value: delta,
        arrivalOrdinal: _queuedRealtimeArrivalOrdinal++,
      ),
    );
  }

  void _queueLiveAssistantDraftEventForResync(
    OpenCrayChatLiveAssistantDraftEvent event,
  ) {
    _queueRealtimeEnvelope(
      _QueuedRealtimeEnvelope.draft(
        value: event,
        arrivalOrdinal: _queuedRealtimeArrivalOrdinal++,
      ),
    );
  }

  void _queueRealtimeEnvelope(_QueuedRealtimeEnvelope envelope) {
    final String deduplicationKey = envelope.deduplicationKey;
    if (envelope.sequence > 0 || envelope.eventId.isNotEmpty) {
      final int existingIndex = _queuedRealtimeEventsAfterResync.indexWhere(
        (queued) => queued.deduplicationKey == deduplicationKey,
      );
      if (existingIndex >= 0) {
        _queuedRealtimeEventsAfterResync[existingIndex] = envelope;
        return;
      }
    }
    _queuedRealtimeEventsAfterResync.add(envelope);
    if (_queuedRealtimeEventsAfterResync.length >
        _maxQueuedRealtimeEventsAfterResync) {
      _queuedRealtimeEventsAfterResync.removeRange(
        0,
        _queuedRealtimeEventsAfterResync.length -
            _maxQueuedRealtimeEventsAfterResync,
      );
    }
  }

  void _resetRealtimeSessionIdentity({
    required String sessionId,
    required String streamInstanceId,
    required String bridgeEpoch,
  }) {
    _runtimeEventDeltaSequenceBySession.remove(sessionId);
    _seenRealtimeEventIdsBySession.remove(sessionId);
    _liveAssistantDraftOverridesBySession.remove(sessionId);
    _liveAssistantDraftEventEpochBySessionAndMessage.remove(sessionId);
    _liveAssistantDraftEventSequenceBySessionAndIdentity.remove(sessionId);
    _liveAssistantDraftEventClearedBySessionAndIdentity.remove(sessionId);
    _queuedRealtimeEventsAfterResync.removeWhere((queued) {
      if (queued.sessionId != sessionId) {
        return false;
      }
      final bool streamMismatch =
          streamInstanceId.isNotEmpty &&
          queued.streamInstanceId.isNotEmpty &&
          queued.streamInstanceId != streamInstanceId;
      final bool bridgeMismatch =
          bridgeEpoch.isNotEmpty &&
          queued.bridgeEpoch.isNotEmpty &&
          queued.bridgeEpoch != bridgeEpoch;
      return streamMismatch || bridgeMismatch;
    });
  }

  void _drainQueuedRealtimeEventsAfterResync() {
    if (_runtimeEventDeltaResyncInFlight) {
      return;
    }
    final List<_QueuedRealtimeEnvelope> queuedEvents =
        _queuedRealtimeEventsAfterResync.toList(growable: false)
          ..sort((left, right) {
            final int leftSequence = left.sequence;
            final int rightSequence = right.sequence;
            if (leftSequence > 0 && rightSequence > 0 &&
                leftSequence != rightSequence) {
              return leftSequence.compareTo(rightSequence);
            }
            if (leftSequence > 0 && rightSequence <= 0) {
              return -1;
            }
            if (leftSequence <= 0 && rightSequence > 0) {
              return 1;
            }
            if (leftSequence == rightSequence &&
                left.kind != right.kind) {
              return left.kind == _QueuedRealtimeKind.runtimeDelta ? -1 : 1;
            }
            return left.arrivalOrdinal.compareTo(right.arrivalOrdinal);
          });
    _queuedRealtimeEventsAfterResync.clear();
    for (final envelope in queuedEvents) {
      final String sessionId = envelope.sessionId;
      final String currentStreamInstanceId = _currentRuntimeStreamInstanceId(
        sessionId,
      );
      final String currentBridgeEpoch = _currentRuntimeBridgeEpoch(sessionId);
      if ((currentStreamInstanceId.isNotEmpty &&
              envelope.streamInstanceId.isNotEmpty &&
              currentStreamInstanceId != envelope.streamInstanceId) ||
          (currentBridgeEpoch.isNotEmpty &&
              envelope.bridgeEpoch.isNotEmpty &&
              currentBridgeEpoch != envelope.bridgeEpoch)) {
        continue;
      }
      switch (envelope.value) {
        case OpenCrayChatRuntimeEventDelta delta:
          _handleRuntimeEventDelta(delta);
          break;
        case OpenCrayChatLiveAssistantDraftEvent event:
          _handleLiveAssistantDraftEvent(event);
          break;
        default:
          break;
      }
    }
  }

  void _scheduleRuntimeDeltaResyncRetry() {
    if (!mounted ||
        widget.bridge == null ||
        _runtimeDeltaResyncRetryTimer != null) {
      return;
    }
    _runtimeDeltaResyncRetryTimer = Timer(
      const Duration(milliseconds: 300),
      () {
        _runtimeDeltaResyncRetryTimer = null;
        if (mounted) {
          unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
        }
      },
    );
  }

  void _handleLiveAssistantDraftEvent(
    OpenCrayChatLiveAssistantDraftEvent event,
  ) {
    if (_runtimeEventDeltaResyncInFlight) {
      _queueLiveAssistantDraftEventForResync(event);
      return;
    }
    final String sessionId = event.sessionId.trim();
    final String currentStreamInstanceId = _currentRuntimeStreamInstanceId(
      sessionId,
    );
    final String eventStreamInstanceId = event.streamInstanceId?.trim() ?? '';
    final String currentBridgeEpoch = _currentRuntimeBridgeEpoch(sessionId);
    final String eventBridgeEpoch = event.bridgeEpoch?.trim() ?? '';
    if ((currentStreamInstanceId.isNotEmpty &&
            eventStreamInstanceId.isNotEmpty &&
            currentStreamInstanceId != eventStreamInstanceId) ||
        (currentBridgeEpoch.isNotEmpty &&
            eventBridgeEpoch.isNotEmpty &&
            currentBridgeEpoch != eventBridgeEpoch) ||
        (event.lastSequence != null &&
            event.sequence != null &&
            event.lastSequence != event.sequence)) {
      _queueLiveAssistantDraftEventForResync(event);
      unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
      return;
    }
    final int? eventSequence = event.sequence;
    final bool hasSequenceWatermark = _runtimeEventDeltaSequenceBySession
        .containsKey(sessionId);
    final int previousSequence =
        _runtimeEventDeltaSequenceBySession[sessionId] ?? 0;
    if (eventSequence != null && hasSequenceWatermark) {
      if (eventSequence <= previousSequence) {
        return;
      }
      if (eventSequence != previousSequence + 1) {
        _queueLiveAssistantDraftEventForResync(event);
        unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
        return;
      }
    }
    if (_realtimeEventIdWasSeen(sessionId, event.eventId)) {
      return;
    }
    if (!_storeLiveAssistantDraftOverride(event)) {
      return;
    }
    _recordAppliedLiveDraftEvent(event);
    if (!mounted || _latestChatSnapshot == null) {
      return;
    }
    if (sessionId.isEmpty || sessionId != _activeSessionId) {
      return;
    }
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _latestChatRuntimeSnapshot ?? _latestChatSnapshot?.runtimeActivity;
    final OpenCrayChatRuntimeSnapshot
    effectiveRuntime = OpenCrayChatRuntimeSnapshot(
      sessionId: sessionId,
      activeRuns:
          runtimeSnapshot?.activeRuns ?? const <OpenCrayChatRunSnapshot>[],
      retainedRuns:
          runtimeSnapshot?.retainedRuns ?? const <OpenCrayChatRunSnapshot>[],
      subAgents:
          runtimeSnapshot?.subAgents ?? const <OpenCrayChatSubAgentSnapshot>[],
      events:
          runtimeSnapshot?.events ?? const <OpenCrayChatRuntimeEventSnapshot>[],
      liveAssistantDrafts:
          runtimeSnapshot?.liveAssistantDrafts ??
          const <OpenCrayChatLiveAssistantDraftSnapshot>[],
      streamInstanceId:
          event.streamInstanceId ?? runtimeSnapshot?.streamInstanceId,
      lastSequence:
          event.lastSequence ?? event.sequence ?? runtimeSnapshot?.lastSequence,
      flutterAppInstanceId: runtimeSnapshot?.flutterAppInstanceId,
      bridgeInstanceId:
          event.bridgeInstanceId ?? runtimeSnapshot?.bridgeInstanceId,
      bridgeEpoch: event.bridgeEpoch ?? runtimeSnapshot?.bridgeEpoch,
      hostLifecycle: runtimeSnapshot?.hostLifecycle,
      updatedAtEpochMs: math.max(
        runtimeSnapshot?.updatedAtEpochMs ?? 0,
        event.updatedAtEpochMs,
      ),
    );
    _queueRuntimeActivityPatch(effectiveRuntime);
  }

  void _recordAppliedLiveDraftEvent(OpenCrayChatLiveAssistantDraftEvent event) {
    final String sessionId = event.sessionId.trim();
    final String streamInstanceId = event.streamInstanceId?.trim() ?? '';
    final String bridgeEpoch = event.bridgeEpoch?.trim() ?? '';
    if (streamInstanceId.isNotEmpty) {
      _runtimeStreamInstanceBySession[sessionId] = streamInstanceId;
    }
    if (bridgeEpoch.isNotEmpty) {
      _runtimeBridgeEpochBySession[sessionId] = bridgeEpoch;
    }
    if (event.sequence != null && event.sequence! > 0) {
      _runtimeEventDeltaSequenceBySession[sessionId] = event.sequence!;
    }
    _markRealtimeEventIdSeen(sessionId, event.eventId);
  }

  bool _storeLiveAssistantDraftOverride(
    OpenCrayChatLiveAssistantDraftEvent event,
  ) {
    final String sessionId = event.sessionId.trim();
    final String pendingMessageId = event.pendingMessageId.trim();
    if (sessionId.isEmpty || pendingMessageId.isEmpty) {
      return false;
    }
    final String identity = _runtimeDraftIdentity(
      pendingMessageId: pendingMessageId,
      executionId: event.executionId,
    );
    final int? eventSequence = event.sequence;
    final Map<String, int> sessionEventSequences =
        _liveAssistantDraftEventSequenceBySessionAndIdentity.putIfAbsent(
          sessionId,
          () => <String, int>{},
        );
    final int? previousSequence = sessionEventSequences[identity];
    if (eventSequence != null &&
        previousSequence != null &&
        eventSequence <= previousSequence) {
      return false;
    }
    final Map<String, int> sessionEventEpochs =
        _liveAssistantDraftEventEpochBySessionAndMessage.putIfAbsent(
          sessionId,
          () => <String, int>{},
        );
    final int eventEpochMs = event.updatedAtEpochMs;
    final int previousEventEpochMs = sessionEventEpochs[identity] ?? 0;
    if (eventEpochMs > 0 && previousEventEpochMs > eventEpochMs) {
      return false;
    }
    final Map<String, bool> sessionClearedStates =
        _liveAssistantDraftEventClearedBySessionAndIdentity.putIfAbsent(
          sessionId,
          () => <String, bool>{},
        );
    if (eventSequence == null &&
        eventEpochMs > 0 &&
        previousEventEpochMs == eventEpochMs &&
        sessionClearedStates[identity] == true &&
        !event.cleared) {
      return false;
    }
    if (eventSequence != null) {
      sessionEventSequences[identity] = eventSequence;
    }
    if (eventEpochMs > 0) {
      sessionEventEpochs[identity] = eventEpochMs;
    }
    sessionClearedStates[identity] = event.cleared;
    final Map<String, OpenCrayChatLiveAssistantDraftSnapshot> sessionDrafts =
        _liveAssistantDraftOverridesBySession.putIfAbsent(
          sessionId,
          () => <String, OpenCrayChatLiveAssistantDraftSnapshot>{},
        );
    if (event.cleared) {
      sessionDrafts.remove(identity);
      if (sessionDrafts.isEmpty) {
        _liveAssistantDraftOverridesBySession.remove(sessionId);
      }
      return true;
    }
    sessionDrafts[identity] = OpenCrayChatLiveAssistantDraftSnapshot(
      runId: event.runId,
      taskId: event.taskId,
      pendingMessageId: pendingMessageId,
      text: event.text,
      updatedAtEpochMs: event.updatedAtEpochMs,
      executionId: event.executionId,
      streamInstanceId: event.streamInstanceId,
      sequence: event.sequence,
      lastSequence: event.lastSequence,
      eventId: event.eventId,
      bridgeInstanceId: event.bridgeInstanceId,
      bridgeEpoch: event.bridgeEpoch,
    );
    return true;
  }

  void _reconcileLiveAssistantDraftOverrides(
    OpenCrayChatRuntimeSnapshot? authoritativeSnapshot, {
    bool liveAssistantDraftsAuthoritative = false,
  }) {
    final String sessionId = authoritativeSnapshot?.sessionId.trim() ?? '';
    if (sessionId.isEmpty) {
      return;
    }
    final Map<String, OpenCrayChatLiveAssistantDraftSnapshot>? sessionDrafts =
        _liveAssistantDraftOverridesBySession[sessionId];
    if (sessionDrafts == null || sessionDrafts.isEmpty) {
      return;
    }
    final Map<String, OpenCrayChatLiveAssistantDraftSnapshot>
    authoritativeDraftsByIdentity =
        <String, OpenCrayChatLiveAssistantDraftSnapshot>{
          for (final draft in authoritativeSnapshot!.liveAssistantDrafts)
            if (draft.pendingMessageId.trim().isNotEmpty)
              _runtimeDraftIdentity(
                pendingMessageId: draft.pendingMessageId,
                executionId: draft.executionId,
              ): draft,
        };
    final int authoritativeVersion = runtimeSnapshotVersion(
      authoritativeSnapshot,
    );
    final List<String> keysToRemove = <String>[];
    final String authoritativeStreamInstanceId =
        authoritativeSnapshot.streamInstanceId?.trim() ?? '';
    sessionDrafts.forEach((identity, overrideDraft) {
      final String overrideStreamInstanceId =
          overrideDraft.streamInstanceId?.trim() ?? '';
      if (authoritativeStreamInstanceId.isNotEmpty &&
          overrideStreamInstanceId.isNotEmpty &&
          authoritativeStreamInstanceId != overrideStreamInstanceId) {
        keysToRemove.add(identity);
        return;
      }
      final OpenCrayChatLiveAssistantDraftSnapshot? authoritativeDraft =
          authoritativeDraftsByIdentity[identity];
      if (authoritativeDraft != null &&
          _draftIsAtLeastAsNew(authoritativeDraft, overrideDraft)) {
        keysToRemove.add(identity);
        return;
      }
      if (authoritativeDraft == null &&
          (liveAssistantDraftsAuthoritative ||
              authoritativeVersion >= overrideDraft.updatedAtEpochMs)) {
        keysToRemove.add(identity);
      }
    });
    for (final String key in keysToRemove) {
      sessionDrafts.remove(key);
    }
    if (sessionDrafts.isEmpty) {
      _liveAssistantDraftOverridesBySession.remove(sessionId);
    }
  }

  void _cancelTodoArchiveHideTimer() {
    _todoArchiveHideTimer?.cancel();
    _todoArchiveHideTimer = null;
    _scheduledTodoArchiveFingerprint = null;
  }

  void _syncTodoArchiveVisibility(OpenCrayChatSnapshot snapshot) {
    final String? fingerprint = _archivedTodoFingerprint(snapshot);
    if (fingerprint == null) {
      _hiddenArchivedTodoFingerprint = null;
      _cancelTodoArchiveHideTimer();
      return;
    }
    if (_hiddenArchivedTodoFingerprint == fingerprint) {
      _cancelTodoArchiveHideTimer();
      return;
    }
    if (_scheduledTodoArchiveFingerprint == fingerprint &&
        _todoArchiveHideTimer != null) {
      return;
    }
    final int hideDelayMs = snapshot.todoHideDelayMs ?? 0;
    if (hideDelayMs <= 0) {
      _hiddenArchivedTodoFingerprint = fingerprint;
      _cancelTodoArchiveHideTimer();
      return;
    }
    _cancelTodoArchiveHideTimer();
    _scheduledTodoArchiveFingerprint = fingerprint;
    _todoArchiveHideTimer = Timer(Duration(milliseconds: hideDelayMs), () {
      if (!mounted) {
        return;
      }
      _scheduledTodoArchiveFingerprint = null;
      _hiddenArchivedTodoFingerprint = fingerprint;
      _applyHostState();
    });
  }

  void _applyHostState() {
    if (!mounted) {
      return;
    }
    final snapshot = _latestChatSnapshot;
    if (snapshot == null) {
      return;
    }
    _pruneLocalDeletionTombstones(snapshot);
    _syncTodoArchiveVisibility(snapshot);
    final ChatFeatureState nextState = _mapSnapshot(
      snapshot,
      _latestChatRuntimeSnapshot,
    );
    final ChatFeatureState resolvedNextState = _applyLocalDeletionTombstones(
      nextState.copyWith(
        drawerOpen: _state.drawerOpen,
        composer: _composerStateForHostSnapshot(nextState),
      ),
    );
    if (chatFeatureStatesEquivalent(_state, resolvedNextState)) {
      _syncSandboxSessionAutoRefresh();
      _syncSandboxSessionLifecycleAutoRefresh();
      return;
    }
    final bool wasPinnedToBottom = _isChatScrollPinnedToBottom();
    final bool shouldScrollToBottom =
        resolvedNextState.messages.length > _state.messages.length ||
        resolvedNextState.runTraces.length > _state.runTraces.length ||
        resolvedNextState.pendingApprovals.length >
            _state.pendingApprovals.length;
    final Set<String> retainedSelection = _selectedMessageIds
        .where(
          (messageId) => resolvedNextState.messages.any(
            (message) => message.messageId == messageId,
          ),
        )
        .toSet();
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _state = resolvedNextState;
      _selectedMessageIds
        ..clear()
        ..addAll(retainedSelection);
    });
    if (shouldScrollToBottom) {
      _scheduleScrollToBottom(
        onlyIfPinned: true,
        wasPinnedToBottom: wasPinnedToBottom,
      );
    }
    _syncSandboxSessionAutoRefresh();
    _syncSandboxSessionLifecycleAutoRefresh();
  }

  void _queueRuntimeActivityPatch(OpenCrayChatRuntimeSnapshot runtimeSnapshot) {
    _pendingRuntimeProjectionSnapshot = runtimeSnapshot;
    if (_runtimeProjectionFlushTimer != null) {
      return;
    }
    _runtimeProjectionFlushTimer = Timer(
      const Duration(milliseconds: 16),
      _flushQueuedRuntimeActivityPatch,
    );
  }

  void _flushQueuedRuntimeActivityPatch() {
    _runtimeProjectionFlushTimer = null;
    if (!mounted) {
      _pendingRuntimeProjectionSnapshot = null;
      return;
    }
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _pendingRuntimeProjectionSnapshot ??
        _latestChatRuntimeSnapshot ??
        _latestChatSnapshot?.runtimeActivity;
    _pendingRuntimeProjectionSnapshot = null;
    if (runtimeSnapshot == null) {
      return;
    }
    _applyRuntimeActivityPatch(runtimeSnapshot);
  }

  void _applyRuntimeActivityPatch(OpenCrayChatRuntimeSnapshot runtimeSnapshot) {
    if (!mounted) {
      return;
    }
    final OpenCrayChatSnapshot? snapshot = _latestChatSnapshot;
    if (snapshot == null) {
      return;
    }
    final String expectedSessionId = _runtimeProjectionExpectedSessionId(
      snapshot,
    );
    final OpenCrayChatRuntimeSnapshot? effectiveRuntime =
        _resolveRuntimeSnapshot(
          expectedSessionId: expectedSessionId,
          embedded: snapshot.runtimeActivity,
          streamed: runtimeSnapshot,
        );
    final OpenCrayChatSnapshot projectionSnapshot =
        expectedSessionId.isNotEmpty &&
            expectedSessionId != _snapshotActiveSessionId(snapshot)
        ? _projectionSnapshotForLocalSession(
            source: snapshot,
            runtimeSnapshot: effectiveRuntime,
          )
        : snapshot;
    final _RuntimeProjectionPatch runtimeProjection = _mapRuntimeProjection(
      projectionSnapshot,
      effectiveRuntime,
    );
    final ChatFeatureState nextState = _applyLocalDeletionTombstones(
      _state.copyWith(
        variant:
            runtimeProjection.messages.isEmpty &&
                runtimeProjection.runTraces.isEmpty &&
                _state.composer.todos.isEmpty &&
                _state.pendingApprovals.isEmpty
            ? ChatPrototypeVariant.empty
            : ChatPrototypeVariant.main,
        messages: runtimeProjection.messages,
        runTraces: runtimeProjection.runTraces,
        emptyThreadHeight:
            runtimeProjection.messages.isEmpty &&
                runtimeProjection.runTraces.isEmpty
            ? 260
            : 0,
      ),
    );
    final bool wasPinnedToBottom = _isChatScrollPinnedToBottom();
    final bool shouldScrollToBottom =
        nextState.messages.length > _state.messages.length ||
        nextState.runTraces.length > _state.runTraces.length;
    final bool hasOpenInspector =
        _RunTraceBubbleState.hasOpenInspectorForTraces(nextState.runTraces);
    if (!hasOpenInspector &&
        _runtimeProjectionVisibleStateEquivalent(_state, nextState)) {
      _syncSandboxSessionAutoRefresh();
      _syncSandboxSessionLifecycleAutoRefresh();
      return;
    }
    final Set<String> retainedSelection = _selectedMessageIds
        .where(
          (messageId) => nextState.messages.any(
            (message) => message.messageId == messageId,
          ),
        )
        .toSet();
    // Runtime deltas must propagate fresh trace objects to any open inspector,
    // even when the visible projection is structurally equivalent.
    setState(() {
      _state = nextState;
      _selectedMessageIds
        ..clear()
        ..addAll(retainedSelection);
    });
    if (shouldScrollToBottom) {
      _scheduleScrollToBottom(
        animated: false,
        onlyIfPinned: true,
        wasPinnedToBottom: wasPinnedToBottom,
      );
    }
    _syncSandboxSessionAutoRefresh();
    _syncSandboxSessionLifecycleAutoRefresh();
  }

  void _cancelScheduledSandboxSessionAutoRefresh() {
    _sandboxSessionAutoRefreshTimer?.cancel();
    _sandboxSessionAutoRefreshTimer = null;
    _scheduledSandboxSessionRefreshAnchor = null;
    _queuedSandboxSessionRefreshAnchors.clear();
  }

  void _cancelScheduledSandboxSessionLifecycleRefresh() {
    _sandboxSessionLifecycleRefreshTimer?.cancel();
    _sandboxSessionLifecycleRefreshTimer = null;
    _scheduledSandboxSessionLifecycleRefreshKey = null;
    _queuedSandboxSessionLifecycleRefresh = false;
  }

  void _resetSandboxSessionAutoRefreshTracking() {
    _cancelScheduledSandboxSessionAutoRefresh();
    _lastSandboxSessionRefreshAnchor = null;
  }

  void _syncSandboxSessionAutoRefresh() {
    if (!mounted ||
        !widget.isTabActive ||
        _selectedRuntimeEnvironment != _ChatRuntimeEnvironment.cloud) {
      _cancelScheduledSandboxSessionAutoRefresh();
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _latestChatRuntimeSnapshot ?? _latestChatSnapshot?.runtimeActivity;
    if (bridge == null || runtimeSnapshot == null) {
      _cancelScheduledSandboxSessionAutoRefresh();
      return;
    }
    final String? anchor = _sandboxSessionAutoRefreshAnchor(runtimeSnapshot);
    if (anchor == null) {
      _cancelScheduledSandboxSessionAutoRefresh();
      return;
    }
    if (_lastSandboxSessionRefreshAnchor == anchor ||
        _scheduledSandboxSessionRefreshAnchor == anchor) {
      return;
    }
    if (_sandboxSessionRefreshInFlight) {
      _enqueueSandboxSessionRefreshAnchor(anchor);
      return;
    }
    _scheduleSandboxSessionAutoRefresh(anchor);
  }

  void _syncSandboxSessionLifecycleAutoRefresh() {
    if (!mounted ||
        !widget.isTabActive ||
        _selectedRuntimeEnvironment != _ChatRuntimeEnvironment.cloud) {
      _cancelScheduledSandboxSessionLifecycleRefresh();
      return;
    }
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _latestChatRuntimeSnapshot ?? _latestChatSnapshot?.runtimeActivity;
    if (widget.bridge == null || runtimeSnapshot == null) {
      _cancelScheduledSandboxSessionLifecycleRefresh();
      return;
    }
    final _SandboxSessionLifecycleRefreshSchedule? schedule =
        _sandboxSessionLifecycleRefreshSchedule(runtimeSnapshot);
    if (schedule == null) {
      _cancelScheduledSandboxSessionLifecycleRefresh();
      return;
    }
    if (_scheduledSandboxSessionLifecycleRefreshKey == schedule.key) {
      return;
    }
    _scheduleSandboxSessionLifecycleRefresh(schedule);
  }

  void _scheduleSandboxSessionAutoRefresh(String anchor) {
    _sandboxSessionAutoRefreshTimer?.cancel();
    _scheduledSandboxSessionRefreshAnchor = anchor;
    _sandboxSessionAutoRefreshTimer = Timer(
      chatSandboxSessionAutoRefreshDebounce,
      () {
        _sandboxSessionAutoRefreshTimer = null;
        _scheduledSandboxSessionRefreshAnchor = null;
        unawaited(_runSandboxSessionAutoRefresh(anchor));
      },
    );
  }

  void _scheduleSandboxSessionLifecycleRefresh(
    _SandboxSessionLifecycleRefreshSchedule schedule,
  ) {
    _sandboxSessionLifecycleRefreshTimer?.cancel();
    _scheduledSandboxSessionLifecycleRefreshKey = schedule.key;
    _sandboxSessionLifecycleRefreshTimer = Timer(
      Duration(milliseconds: schedule.delayMs),
      () {
        _sandboxSessionLifecycleRefreshTimer = null;
        _scheduledSandboxSessionLifecycleRefreshKey = null;
        unawaited(_runSandboxSessionLifecycleRefresh());
      },
    );
  }

  Future<void> _runSandboxSessionAutoRefresh(String anchor) async {
    if (!mounted ||
        !widget.isTabActive ||
        _selectedRuntimeEnvironment != _ChatRuntimeEnvironment.cloud) {
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null || _sandboxSessionRefreshInFlight) {
      return;
    }
    _sandboxSessionRefreshInFlight = true;
    try {
      await bridge.refreshSandboxSessionInfo();
      _lastSandboxSessionRefreshAnchor = anchor;
    } catch (_) {
      _lastSandboxSessionRefreshAnchor = anchor;
    } finally {
      _sandboxSessionRefreshInFlight = false;
      final String? queuedAnchor = _dequeueSandboxSessionRefreshAnchor();
      final bool canContinue = mounted;
      final bool shouldScheduleQueuedAnchor =
          canContinue &&
          queuedAnchor != null &&
          queuedAnchor != _lastSandboxSessionRefreshAnchor &&
          widget.isTabActive &&
          _selectedRuntimeEnvironment == _ChatRuntimeEnvironment.cloud;
      if (shouldScheduleQueuedAnchor) {
        _scheduleSandboxSessionAutoRefresh(queuedAnchor);
      } else if (canContinue) {
        _syncSandboxSessionAutoRefresh();
      }
      if (canContinue) {
        _syncSandboxSessionLifecycleAutoRefresh();
      }
    }
  }

  void _enqueueSandboxSessionRefreshAnchor(String anchor) {
    final String normalizedAnchor = anchor.trim();
    if (normalizedAnchor.isEmpty ||
        normalizedAnchor == _scheduledSandboxSessionRefreshAnchor ||
        normalizedAnchor == _lastSandboxSessionRefreshAnchor ||
        _queuedSandboxSessionRefreshAnchors.contains(normalizedAnchor)) {
      return;
    }
    _queuedSandboxSessionRefreshAnchors.add(normalizedAnchor);
  }

  String? _dequeueSandboxSessionRefreshAnchor() {
    while (_queuedSandboxSessionRefreshAnchors.isNotEmpty) {
      final String anchor = _queuedSandboxSessionRefreshAnchors.removeAt(0);
      if (anchor == _lastSandboxSessionRefreshAnchor) {
        continue;
      }
      return anchor;
    }
    return null;
  }

  Future<void> _runSandboxSessionLifecycleRefresh() async {
    if (!mounted ||
        !widget.isTabActive ||
        _selectedRuntimeEnvironment != _ChatRuntimeEnvironment.cloud) {
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null) {
      return;
    }
    if (_sandboxSessionRefreshInFlight) {
      _queuedSandboxSessionLifecycleRefresh = true;
      return;
    }
    _sandboxSessionRefreshInFlight = true;
    try {
      await bridge.refreshSandboxSessionInfo();
    } catch (_) {
      // Ignore lifecycle refresh failures and wait for the next schedule.
    } finally {
      _sandboxSessionRefreshInFlight = false;
      final bool queuedLifecycleRefresh = _queuedSandboxSessionLifecycleRefresh;
      _queuedSandboxSessionLifecycleRefresh = false;
      final bool canContinue = mounted;
      if (canContinue) {
        _syncSandboxSessionAutoRefresh();
        _syncSandboxSessionLifecycleAutoRefresh();
        if (queuedLifecycleRefresh &&
            _sandboxSessionLifecycleRefreshTimer == null &&
            _selectedRuntimeEnvironment == _ChatRuntimeEnvironment.cloud) {
          _scheduleSandboxSessionLifecycleRefresh(
            const _SandboxSessionLifecycleRefreshSchedule(
              key: 'queued',
              delayMs: 1000,
            ),
          );
        }
      }
    }
  }

  String? _sandboxSessionAutoRefreshAnchor(
    OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  ) {
    final List<OpenCrayChatRuntimeEventSnapshot> sortedEvents =
        runtimeSnapshot.events.toList(growable: false)..sort(
          (left, right) =>
              right.emittedAtEpochMs.compareTo(left.emittedAtEpochMs),
        );
    final Set<String> activeRunIds = runtimeSnapshot.activeRuns
        .map((run) => run.runId.trim())
        .where((runId) => runId.isNotEmpty && !runId.startsWith('runtime-'))
        .toSet();
    int? latestSessionInfoEventEpochMs;
    for (final OpenCrayChatRuntimeEventSnapshot event in sortedEvents) {
      if (_isSandboxSessionInfoToolResult(event)) {
        latestSessionInfoEventEpochMs ??= event.emittedAtEpochMs;
        continue;
      }
      if (!_isSandboxExecutionToolResult(event)) {
        continue;
      }
      final String runId = event.runId.trim();
      if (runId.isEmpty || activeRunIds.contains(runId)) {
        return null;
      }
      if (latestSessionInfoEventEpochMs != null &&
          latestSessionInfoEventEpochMs > event.emittedAtEpochMs) {
        return null;
      }
      return '$runId:${event.emittedAtEpochMs}';
    }
    return null;
  }

  bool _isSandboxExecutionToolResult(OpenCrayChatRuntimeEventSnapshot event) {
    if (event.kind != 'tool_result') {
      return false;
    }
    final String toolName = event.toolName?.trim().toLowerCase() ?? '';
    if (toolName.startsWith('sandbox_')) {
      return false;
    }
    return _resultMetadataValue(event, 'sandboxProvider') != null;
  }

  bool _isSandboxSessionInfoToolResult(OpenCrayChatRuntimeEventSnapshot event) {
    final String toolName = event.toolName?.trim().toLowerCase() ?? '';
    return event.kind == 'tool_result' && toolName == 'sandbox_session_info';
  }

  _SandboxSessionLifecycleRefreshSchedule?
  _sandboxSessionLifecycleRefreshSchedule(
    OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  ) {
    final List<OpenCrayChatRuntimeEventSnapshot> sortedEvents =
        runtimeSnapshot.events.toList(growable: false)..sort(
          (left, right) =>
              right.emittedAtEpochMs.compareTo(left.emittedAtEpochMs),
        );
    for (final OpenCrayChatRuntimeEventSnapshot event in sortedEvents) {
      if (!_isSandboxSessionInfoToolResult(event)) {
        continue;
      }
      final int? delayMs = _resultMetadataInt(
        event,
        'sandboxSessionAutoRefreshAfterMs',
      );
      if (delayMs == null || delayMs <= 0) {
        return null;
      }
      return _SandboxSessionLifecycleRefreshSchedule(
        key: '${event.runId}:${event.emittedAtEpochMs}:$delayMs',
        delayMs: delayMs,
      );
    }
    return null;
  }

  Future<void> _approvePendingApproval(ChatPendingApprovalData approval) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      resolutionKind: _ApprovalResolutionKind.approved,
      action: (bridge) => bridge.approveChatApproval(approval.approvalId),
    );
  }

  Future<void> _approvePendingApprovalForSession(
    ChatPendingApprovalData approval,
  ) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      resolutionKind: _ApprovalResolutionKind.approvedForSession,
      action: (bridge) =>
          bridge.approveChatApprovalForSession(approval.approvalId),
    );
  }

  Future<void> _rejectPendingApproval(ChatPendingApprovalData approval) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      resolutionKind: _ApprovalResolutionKind.rejected,
      action: (bridge) => bridge.rejectChatApproval(approval.approvalId),
    );
  }

  Future<void> _runApprovalAction({
    required String approvalId,
    required _ApprovalResolutionKind resolutionKind,
    required Future<void> Function(OpenCrayHostBridge bridge) action,
  }) async {
    final bridge = widget.bridge;
    if (bridge == null || _approvalTaskIdsInFlight.contains(approvalId)) {
      return;
    }
    setState(() {
      _approvalTaskIdsInFlight.add(approvalId);
    });
    try {
      await action(bridge);
      if (!mounted) {
        return;
      }
      setState(() {
        _approvalResolutionById[approvalId] = resolutionKind;
      });
      _scheduleApprovalResolutionDismiss(approvalId);
    } catch (_) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(widget.copy.chatApprovalActionFailed)),
      );
    } finally {
      if (!mounted) {
        _approvalTaskIdsInFlight.remove(approvalId);
      } else {
        setState(() {
          _approvalTaskIdsInFlight.remove(approvalId);
        });
      }
    }
  }

  void _scheduleApprovalResolutionDismiss(String approvalId) {
    _approvalResolutionDismissTimers.remove(approvalId)?.cancel();
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.panel,
    );
    final Duration holdDuration = duration == Duration.zero
        ? Duration.zero
        : duration + const Duration(milliseconds: 280);
    if (holdDuration == Duration.zero) {
      _dismissResolvedApproval(approvalId);
      return;
    }
    _approvalResolutionDismissTimers[approvalId] = Timer(holdDuration, () {
      _approvalResolutionDismissTimers.remove(approvalId);
      _dismissResolvedApproval(approvalId);
    });
  }

  void _dismissResolvedApproval(String approvalId) {
    if (!mounted) {
      _approvalResolutionById.remove(approvalId);
      _locallyDismissedApprovalIds.add(approvalId);
      return;
    }
    setState(() {
      _approvalResolutionById.remove(approvalId);
      _locallyDismissedApprovalIds.add(approvalId);
      _state = _applyLocalDeletionTombstones(_state);
    });
  }

  Future<void> _retryRunTrace(ChatRunTraceData trace) async {
    final bridge = widget.bridge;
    final retryId = trace.retryId;
    if (bridge == null ||
        !trace.isRetryable ||
        _retryRunIdsInFlight.contains(retryId)) {
      return;
    }
    setState(() {
      _retryRunIdsInFlight.add(retryId);
    });
    try {
      await bridge.retryChatRun(retryId);
    } catch (_) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            widget.copy.isChinese
                ? '无法重新启动这次运行。'
                : 'Unable to restart this run.',
          ),
        ),
      );
    } finally {
      if (!mounted) {
        _retryRunIdsInFlight.remove(retryId);
      } else {
        setState(() {
          _retryRunIdsInFlight.remove(retryId);
        });
      }
    }
  }

  void _armRunInterruptTrace(ChatRunTraceData trace) {
    final String interruptId = trace.interruptId;
    if (!trace.canInterrupt ||
        interruptId.isEmpty ||
        _interruptRunIdsInFlight.contains(interruptId)) {
      return;
    }
    setState(() {
      _interruptConfirmRunId = interruptId;
    });
  }

  void _dismissRunInterruptTrace(ChatRunTraceData trace) {
    final String interruptId = trace.interruptId;
    if (_interruptConfirmRunId != interruptId) {
      return;
    }
    setState(() {
      _interruptConfirmRunId = null;
    });
  }

  Future<void> _interruptRunTrace(ChatRunTraceData trace) async {
    final bridge = widget.bridge;
    final String interruptId = trace.interruptId;
    if (bridge == null ||
        !trace.canInterrupt ||
        interruptId.isEmpty ||
        _interruptRunIdsInFlight.contains(interruptId)) {
      return;
    }
    setState(() {
      _interruptConfirmRunId = null;
      _interruptRunIdsInFlight.add(interruptId);
    });
    try {
      await bridge.interruptChatRun(interruptId);
    } catch (_) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(widget.copy.chatRunInterruptFailed)),
      );
    } finally {
      if (!mounted) {
        _interruptRunIdsInFlight.remove(interruptId);
      } else {
        setState(() {
          _interruptRunIdsInFlight.remove(interruptId);
        });
      }
    }
  }

  void _removeAttachment(ChatAttachmentData attachment) {
    setState(() {
      final attachments = List<ChatAttachmentData>.of(
        _state.composer.attachments,
      )..removeWhere((ChatAttachmentData item) => item.id == attachment.id);
      _state = _state.copyWith(
        composer: _state.composer.copyWith(attachments: attachments),
      );
    });
  }

  OpenCrayChatDraftAttachmentKind _attachmentKindForAction(String label) {
    if (label == widget.copy.chatActionImage) {
      return OpenCrayChatDraftAttachmentKind.image;
    }
    return OpenCrayChatDraftAttachmentKind.file;
  }

  ChatAttachmentData _attachmentForAction(String label) {
    if (label == widget.copy.chatActionImage) {
      return OpenCrayChatSeedData.sampleAttachments(widget.copy).firstWhere(
        (ChatAttachmentData item) => item.kind == ChatAttachmentKind.image,
      );
    }
    return OpenCrayChatSeedData.sampleAttachments(widget.copy).firstWhere(
      (ChatAttachmentData item) => item.kind == ChatAttachmentKind.file,
    );
  }

  ChatAttachmentData _draftAttachmentForComposer(
    OpenCrayChatDraftAttachment attachment,
  ) {
    final bool isImage =
        attachment.kind == OpenCrayChatDraftAttachmentKind.image;
    final bool isVoice =
        attachment.kind == OpenCrayChatDraftAttachmentKind.voice;
    final String detail =
        attachment.sizeBytes != null && attachment.sizeBytes! >= 0
        ? _formatAttachmentBytes(attachment.sizeBytes!)
        : attachment.durationMs != null && attachment.durationMs! > 0
        ? _formatAttachmentDuration(attachment.durationMs!)
        : (widget.copy.isChinese
              ? (isImage ? '图片附件' : (isVoice ? '语音附件' : '文件附件'))
              : (isImage
                    ? 'Image attachment'
                    : (isVoice ? 'Voice attachment' : 'File attachment')));
    return ChatAttachmentData(
      id: attachment.id,
      kind: isImage
          ? ChatAttachmentKind.image
          : (isVoice ? ChatAttachmentKind.voice : ChatAttachmentKind.file),
      label: attachment.displayName,
      detail: detail,
      accentColor: isImage
          ? OpenCrayColors.primaryTint
          : (isVoice ? OpenCrayColors.successTint : OpenCrayColors.surfaceMuted),
      draftAttachment: attachment,
    );
  }

  OpenCrayChatDraftAttachment? _draftAttachmentForMessageAttachment(
    ChatMessageAttachmentData attachment,
  ) {
    final String chatAttachmentId = attachment.attachmentId.trim();
    final String relativePath = attachment.localPath.trim();
    if (chatAttachmentId.isEmpty && relativePath.isEmpty) {
      return null;
    }
    return OpenCrayChatDraftAttachment(
      kind: switch (attachment.kind) {
        ChatAttachmentKind.image => OpenCrayChatDraftAttachmentKind.image,
        ChatAttachmentKind.voice => OpenCrayChatDraftAttachmentKind.voice,
        ChatAttachmentKind.file => OpenCrayChatDraftAttachmentKind.file,
      },
      displayName: attachment.displayName,
      relativePath: relativePath,
      chatAttachmentId: chatAttachmentId.isEmpty ? null : chatAttachmentId,
      mimeType: attachment.mimeType,
      sizeBytes: attachment.sizeBytes,
      durationMs: attachment.durationMs,
      waveformBars: attachment.waveformBars,
      transcriptText: attachment.transcriptText,
    );
  }

  List<OpenCrayChatDraftAttachment> _draftAttachmentsForMessage(
    ChatMessageData message,
  ) {
    return message.attachments
        .map(_draftAttachmentForMessageAttachment)
        .whereType<OpenCrayChatDraftAttachment>()
        .toList(growable: false);
  }

  List<ChatAttachmentData> _composerAttachmentsForMessage(
    ChatMessageData message,
  ) {
    return _draftAttachmentsForMessage(
      message,
    ).map(_draftAttachmentForComposer).toList(growable: false);
  }

  int get _currentComposerImageCount =>
      _state.composer.attachments.where((ChatAttachmentData attachment) {
        return attachment.kind == ChatAttachmentKind.image;
      }).length;

  String _attachmentDuplicateMessage(int duplicateCount) {
    if (widget.copy.isChinese) {
      return '已自动忽略 $duplicateCount 个重复附件。';
    }
    return duplicateCount == 1
        ? 'Ignored 1 duplicate attachment.'
        : 'Ignored $duplicateCount duplicate attachments.';
  }

  String _attachmentImageLimitMessage({required int skippedCount}) {
    if (widget.copy.isChinese) {
      return skippedCount > 0
          ? '单条消息最多添加 $_chatComposerMaxImageAttachments 张图片，已忽略 $skippedCount 张。'
          : '单条消息最多添加 $_chatComposerMaxImageAttachments 张图片。';
    }
    return skippedCount > 0
        ? 'Each message supports up to $_chatComposerMaxImageAttachments images. Skipped $skippedCount.'
        : 'Each message supports up to $_chatComposerMaxImageAttachments images.';
  }

  String _attachmentPickerFailureMessage(Object error) {
    final explicitMessage = switch (error) {
      PlatformException(:final message?) => _normalizeAttachmentErrorMessage(
        message,
      ),
      UnsupportedError(:final message?) => _normalizeAttachmentErrorMessage(
        message,
      ),
      _ => _normalizeAttachmentErrorMessage(error.toString()),
    };
    if (explicitMessage != null) {
      return explicitMessage;
    }
    return widget.copy.isChinese ? '无法添加附件。' : 'Unable to add attachment.';
  }

  String? _normalizeAttachmentErrorMessage(String rawMessage) {
    var message = rawMessage.trim();
    if (message.isEmpty) {
      return null;
    }
    if (message.startsWith('Bad state: ')) {
      return null;
    }
    if (message.startsWith('Unsupported operation: ')) {
      message = message.substring('Unsupported operation: '.length).trim();
    }
    if (message.startsWith('HttpException: ')) {
      message = message.substring('HttpException: '.length).trim();
    }
    message = message.replaceFirst(RegExp(r', uri = .*$'), '').trim();
    final localRuntimeMatch = RegExp(
      r'^Local runtime returned HTTP \d+: (.+)$',
    ).firstMatch(message);
    if (localRuntimeMatch != null) {
      message = localRuntimeMatch.group(1)?.trim() ?? '';
    }
    if (message.startsWith('{') && message.endsWith('}')) {
      final decoded = _tryDecodeAttachmentErrorMessage(message);
      if (decoded != null) {
        message = decoded;
      }
    }
    return message.isEmpty ? null : message;
  }

  String? _tryDecodeAttachmentErrorMessage(String payload) {
    try {
      final decoded = jsonDecode(payload);
      if (decoded is Map<Object?, Object?>) {
        final error = decoded['error'] as String?;
        return error?.trim().isNotEmpty == true ? error!.trim() : null;
      }
    } catch (_) {}
    return null;
  }

  void _showComposerNotice(String message) {
    final bridge = widget.bridge;
    if (bridge != null) {
      unawaited(bridge.showNativeToast(message));
      return;
    }
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  void _handleSessionSelected(ChatSessionListItemData session) {
    _dismissMessageMenu();
    final bridge = widget.bridge;
    if (bridge != null) {
      setState(() {
        _state = _stateForLocallySelectedSession(session.sessionId);
      });
      unawaited(_selectSessionFromBridge(bridge, session.sessionId));
      return;
    }
    setState(() {
      final sessions = _state.drawer.sessions
          .map(
            (item) => ChatSessionListItemData(
              sessionId: item.sessionId,
              title: item.title,
              preview: item.preview,
              meta: item.meta,
              isSelected: item.sessionId == session.sessionId,
              lastMessageAtEpochMs: item.lastMessageAtEpochMs,
              unreadCount: item.sessionId == session.sessionId
                  ? 0
                  : item.unreadCount,
            ),
          )
          .toList(growable: false);
      _state = _state.copyWith(
        drawer: ChatSessionsDrawerState(
          eyebrow: _state.drawer.eyebrow,
          title: _state.drawer.title,
          ctaLabel: _state.drawer.ctaLabel,
          sessions: sessions,
        ),
        drawerOpen: false,
      );
    });
  }

  Future<void> _selectSessionFromBridge(
    OpenCrayHostBridge bridge,
    String sessionId,
  ) async {
    try {
      await bridge.selectChatSession(sessionId);
    } catch (_) {
      if (!mounted) {
        return;
      }
      _applyHostState();
      _showSessionActionFailed();
    }
  }

  ChatFeatureState _stateForLocallySelectedSession(String sessionId) {
    final String selectedSessionId = sessionId.trim();
    final List<ChatSessionListItemData> sessions = _state.drawer.sessions
        .map(
          (item) => ChatSessionListItemData(
            sessionId: item.sessionId,
            title: item.title,
            preview: item.preview,
            meta: item.meta,
            isSelected: item.sessionId == selectedSessionId,
            lastMessageAtEpochMs: item.lastMessageAtEpochMs,
            unreadCount: item.sessionId == selectedSessionId
                ? 0
                : item.unreadCount,
          ),
        )
        .toList(growable: false);
    final bool threadEmpty = selectedSessionId != _activeSessionId;
    return _state.copyWith(
      variant: threadEmpty ? ChatPrototypeVariant.empty : _state.variant,
      messages: threadEmpty ? const <ChatMessageData>[] : _state.messages,
      runTraces: threadEmpty ? const <ChatRunTraceData>[] : _state.runTraces,
      pendingApprovals: threadEmpty
          ? const <ChatPendingApprovalData>[]
          : _state.pendingApprovals,
      composer: threadEmpty
          ? _state.composer.copyWith(todos: const <ChatTodoItemData>[])
          : _state.composer,
      drawer: ChatSessionsDrawerState(
        eyebrow: _state.drawer.eyebrow,
        title: _state.drawer.title,
        ctaLabel: _state.drawer.ctaLabel,
        sessions: sessions,
      ),
      drawerOpen: false,
      emptyThreadHeight: threadEmpty ? 260 : _state.emptyThreadHeight,
    );
  }

  Future<void> _handleSessionLongPress(
    ChatSessionListItemData session,
    Offset globalPosition,
  ) async {
    _dismissMessageMenu();
    FocusManager.instance.primaryFocus?.unfocus();
    unawaited(HapticFeedback.lightImpact());
    final RenderObject? overlayRenderObject = Overlay.of(
      context,
    ).context.findRenderObject();
    final Size overlaySize = overlayRenderObject is RenderBox
        ? overlayRenderObject.size
        : MediaQuery.of(context).size;
    final action = await showMenu<_SessionMenuAction>(
      context: context,
      position: RelativeRect.fromLTRB(
        globalPosition.dx,
        globalPosition.dy,
        overlaySize.width - globalPosition.dx,
        overlaySize.height - globalPosition.dy,
      ),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      popUpAnimationStyle: _sessionMenuAnimationStyle,
      items: <PopupMenuEntry<_SessionMenuAction>>[
        PopupMenuItem<_SessionMenuAction>(
          value: _SessionMenuAction.copy,
          child: Row(
            children: <Widget>[
              const Icon(Icons.copy_rounded, size: 18),
              const SizedBox(width: 10),
              Text(widget.copy.filesCopyAction),
            ],
          ),
        ),
        PopupMenuItem<_SessionMenuAction>(
          value: _SessionMenuAction.delete,
          child: Row(
            children: <Widget>[
              const Icon(
                Icons.delete_outline_rounded,
                size: 18,
                color: OpenCrayColors.danger,
              ),
              const SizedBox(width: 10),
              Text(
                widget.copy.filesDeleteAction,
                style: const TextStyle(color: OpenCrayColors.danger),
              ),
            ],
          ),
        ),
      ],
    );
    if (!mounted || action == null) {
      return;
    }
    switch (action) {
      case _SessionMenuAction.copy:
        await _copySession(session);
        break;
      case _SessionMenuAction.delete:
        await _deleteSession(session);
        break;
    }
  }

  Future<void> _copySession(ChatSessionListItemData session) async {
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        await bridge.copyChatSession(session.sessionId);
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showSessionActionFailed();
      }
      return;
    }
    setState(() {
      final copiedSession = ChatSessionListItemData(
        sessionId: '${session.sessionId}-copy-${_state.drawer.sessions.length}',
        title: _seedCopySessionTitle(session.title),
        preview: session.preview,
        meta: session.meta,
        isSelected: true,
        lastMessageAtEpochMs: session.lastMessageAtEpochMs,
      );
      _state = _state.copyWith(
        drawer: ChatSessionsDrawerState(
          eyebrow: _state.drawer.eyebrow,
          title: _state.drawer.title,
          ctaLabel: _state.drawer.ctaLabel,
          sessions: <ChatSessionListItemData>[
            copiedSession,
            ..._state.drawer.sessions.map(_copySessionTileUnselected),
          ],
        ),
      );
    });
  }

  Future<void> _deleteSession(ChatSessionListItemData session) async {
    final String sessionId = session.sessionId.trim();
    if (sessionId.isEmpty) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      setState(() {
        _locallyDeletedSessionIds.add(sessionId);
        _state = _applyLocalDeletionTombstones(_state);
      });
      try {
        await bridge.deleteChatSession(sessionId);
      } catch (_) {
        if (!mounted) {
          return;
        }
        _locallyDeletedSessionIds.remove(sessionId);
        _applyHostState();
        _showSessionActionFailed();
      }
      return;
    }
    setState(() {
      final remainingSessions = _state.drawer.sessions
          .where((item) => item.sessionId != sessionId)
          .toList(growable: false);
      if (remainingSessions.isEmpty) {
        _state = OpenCrayChatSeedData.empty(widget.copy);
        return;
      }
      final String selectedSessionId = remainingSessions
          .firstWhere(
            (item) => item.isSelected,
            orElse: () => remainingSessions.first,
          )
          .sessionId;
      _state = _state.copyWith(
        drawer: ChatSessionsDrawerState(
          eyebrow: _state.drawer.eyebrow,
          title: _state.drawer.title,
          ctaLabel: _state.drawer.ctaLabel,
          sessions: remainingSessions
              .map(
                (item) => ChatSessionListItemData(
                  sessionId: item.sessionId,
                  title: item.title,
                  preview: item.preview,
                  meta: item.meta,
                  isSelected: item.sessionId == selectedSessionId,
                  lastMessageAtEpochMs: item.lastMessageAtEpochMs,
                  unreadCount: item.sessionId == selectedSessionId
                      ? 0
                      : item.unreadCount,
                ),
              )
              .toList(growable: false),
        ),
      );
    });
  }

  void _showSessionActionFailed() {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(widget.copy.chatSessionActionFailed)),
    );
  }

  ChatSessionListItemData _copySessionTileUnselected(
    ChatSessionListItemData session,
  ) {
    return ChatSessionListItemData(
      sessionId: session.sessionId,
      title: session.title,
      preview: session.preview,
      meta: session.meta,
      isSelected: false,
      lastMessageAtEpochMs: session.lastMessageAtEpochMs,
      unreadCount: session.unreadCount,
    );
  }

  String _seedCopySessionTitle(String title) {
    if (title.endsWith(' copy')) {
      return title;
    }
    if (title.length >= 27) {
      return '${title.substring(0, 27)} copy';
    }
    return '$title copy';
  }

  Future<bool> _hydrateFromHost(
    OpenCrayHostBridge bridge, {
    required int bindingEpoch,
    bool authoritativeRuntimeSnapshot = false,
  }) async {
    final snapshotFuture = bridge.loadChatSnapshot();
    final runtimeSnapshotFuture = bridge.loadChatRuntimeSnapshot();
    final sandboxSettingsFuture = bridge.loadSandboxSettings();
    final snapshot = await snapshotFuture;
    final runtimeSnapshot = await runtimeSnapshotFuture;
    OpenCraySandboxSettingsSnapshot sandboxSettings = _sandboxSettings;
    try {
      sandboxSettings = await sandboxSettingsFuture;
    } catch (_) {}
    if (!mounted ||
        _bridgeBindingEpoch != bindingEpoch ||
        widget.bridge != bridge) {
      return false;
    }
    final OpenCrayChatRuntimeSnapshot resolvedRuntimeSnapshot =
        authoritativeRuntimeSnapshot
        ? _runtimeSnapshotIsBehindCurrentWatermark(runtimeSnapshot) &&
                  _latestChatRuntimeSnapshot != null
              ? _latestChatRuntimeSnapshot!
              : runtimeSnapshot
        : _latestChatRuntimeSnapshot != null &&
              !_runtimeSnapshotsShareSession(
                _latestChatRuntimeSnapshot!,
                runtimeSnapshot,
              ) &&
              runtimeSnapshot.sessionId.trim().isNotEmpty
        ? runtimeSnapshot
        : resolveChatRuntimeSnapshot(
                _latestChatRuntimeSnapshot,
                runtimeSnapshot,
              ) ??
              runtimeSnapshot;
    _latestChatSnapshot = snapshot;
    _latestChatRuntimeSnapshot = resolvedRuntimeSnapshot;
    _recordRuntimeSnapshotWatermark(
      resolvedRuntimeSnapshot,
      authoritative: authoritativeRuntimeSnapshot,
    );
    _pruneLocalDeletionTombstones(snapshot);
    _syncTodoArchiveVisibility(snapshot);
    final ChatFeatureState nextState = _applyLocalDeletionTombstones(
      _mapSnapshot(snapshot, resolvedRuntimeSnapshot),
    );
    final Set<String> retainedSelection = _selectedMessageIds
        .where(
          (messageId) => nextState.messages.any(
            (message) => message.messageId == messageId,
          ),
        )
        .toSet();
    setState(() {
      _state = nextState.copyWith(
        drawerOpen: _state.drawerOpen,
        composer: _composerStateForHostSnapshot(nextState),
      );
      _sandboxSettings = sandboxSettings;
      _selectedMessageIds
        ..clear()
        ..addAll(retainedSelection);
    });
    if (snapshot.messages.isNotEmpty || nextState.runTraces.isNotEmpty) {
      _scheduleScrollToBottom(animated: false);
    }
    _syncSandboxSessionAutoRefresh();
    _syncSandboxSessionLifecycleAutoRefresh();
    return true;
  }

  ChatRuntimeProjector _chatProjector() => ChatRuntimeProjector(
        copy: widget.copy,
        usesHostBridge: _usesHostBridge,
        drawerSessions: _state.drawer.sessions,
        activeSessionId: _activeSessionId,
        liveAssistantDraftOverridesBySession:
            _liveAssistantDraftOverridesBySession,
        hiddenArchivedTodoFingerprint: _hiddenArchivedTodoFingerprint,
      );
  String? _archivedTodoFingerprint(OpenCrayChatSnapshot snapshot) => _chatProjector()._archivedTodoFingerprint(snapshot);
  ChatFeatureState _mapSnapshot( OpenCrayChatSnapshot snapshot, OpenCrayChatRuntimeSnapshot? runtimeSnapshot, ) => _chatProjector()._mapSnapshot(snapshot, runtimeSnapshot);
  _RuntimeProjectionPatch _mapRuntimeProjection( OpenCrayChatSnapshot snapshot, OpenCrayChatRuntimeSnapshot? effectiveRuntime, ) => _chatProjector()._mapRuntimeProjection(snapshot, effectiveRuntime);
  List<String> _projectedManagedProcessMessageIds({ required OpenCrayChatRunSnapshot run, required OpenCrayChatManagedProcessSnapshot process, }) => _chatProjector()._projectedManagedProcessMessageIds(run: run, process: process);
  List<String> _assistantPhaseMessageIds( OpenCrayChatRuntimeEventSnapshot event, ) => _chatProjector()._assistantPhaseMessageIds(event);
  bool _hideAssistantPhaseBubble(OpenCrayChatRuntimeEventSnapshot event) => _chatProjector()._hideAssistantPhaseBubble(event);
  String? _resultMetadataValue( OpenCrayChatRuntimeEventSnapshot event, String key, ) => _chatProjector()._resultMetadataValue(event, key);
  int? _resultMetadataInt(OpenCrayChatRuntimeEventSnapshot event, String key) => _chatProjector()._resultMetadataInt(event, key);
  String _runtimeProjectionExpectedSessionId(OpenCrayChatSnapshot snapshot) => _chatProjector()._runtimeProjectionExpectedSessionId(snapshot);
  String _snapshotActiveSessionId(OpenCrayChatSnapshot snapshot) => _chatProjector()._snapshotActiveSessionId(snapshot);
  OpenCrayChatSnapshot _projectionSnapshotForLocalSession({ required OpenCrayChatSnapshot source, required OpenCrayChatRuntimeSnapshot? runtimeSnapshot, }) => _chatProjector()._projectionSnapshotForLocalSession(source: source, runtimeSnapshot: runtimeSnapshot);
  OpenCrayChatRuntimeSnapshot? _resolveRuntimeSnapshot({ required String expectedSessionId, OpenCrayChatRuntimeSnapshot? embedded, OpenCrayChatRuntimeSnapshot? streamed, }) => _chatProjector()._resolveRuntimeSnapshot(expectedSessionId: expectedSessionId, embedded: embedded, streamed: streamed);

}

class _SessionsDrawerOverlay extends StatefulWidget {
  const _SessionsDrawerOverlay({
    required this.isOpen,
    required this.copy,
    required this.drawer,
    required this.onDismiss,
    required this.onNewSessionPressed,
    required this.onSessionPressed,
    required this.onSessionLongPress,
  });

  final bool isOpen;
  final OpenCrayUiCopy copy;
  final ChatSessionsDrawerState drawer;
  final VoidCallback onDismiss;
  final VoidCallback onNewSessionPressed;
  final ValueChanged<ChatSessionListItemData> onSessionPressed;
  final void Function(ChatSessionListItemData, Offset) onSessionLongPress;

  @override
  State<_SessionsDrawerOverlay> createState() => _SessionsDrawerOverlayState();
}

class _SessionsDrawerOverlayState extends State<_SessionsDrawerOverlay> {
  bool _isMountedForMotion = false;
  int _closeEpoch = 0;
  bool _animateFromClosed = false;

  @override
  void initState() {
    super.initState();
    _isMountedForMotion = widget.isOpen;
    _animateFromClosed = widget.isOpen;
  }

  @override
  void didUpdateWidget(_SessionsDrawerOverlay oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isOpen) {
      _closeEpoch += 1;
      if (!_isMountedForMotion) {
        setState(() {
          _isMountedForMotion = true;
          _animateFromClosed = true;
        });
      }
      return;
    }
    if (!oldWidget.isOpen || !_isMountedForMotion) {
      return;
    }
    _scheduleUnmountAfterExit();
  }

  @override
  Widget build(BuildContext context) {
    final EdgeInsets safePadding = MediaQuery.of(context).padding;
    final bool reduce = OpenCrayMotion.reduce(context);
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.panel,
    );
    if (!_isMountedForMotion) {
      return const Positioned.fill(child: SizedBox.shrink());
    }
    final bool isPresented = widget.isOpen && !_animateFromClosed;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !widget.isOpen || !_animateFromClosed) {
        return;
      }
      setState(() {
        _animateFromClosed = false;
      });
    });

    return Positioned.fill(
      child: IgnorePointer(
        ignoring: !widget.isOpen,
        child: Stack(
          children: [
            Positioned.fill(
              child: GestureDetector(
                onTap: widget.onDismiss,
                behavior: HitTestBehavior.opaque,
                child: AnimatedOpacity(
                  opacity: isPresented ? 1 : 0,
                  duration: duration,
                  curve: isPresented
                      ? OpenCrayMotion.enter
                      : OpenCrayMotion.exit,
                  child: const ColoredBox(
                    color: Color(0x26101828),
                    child: SizedBox.expand(),
                  ),
                ),
              ),
            ),
            AnimatedSlide(
              offset: reduce || isPresented ? Offset.zero : const Offset(-1, 0),
              duration: duration,
              curve: isPresented ? OpenCrayMotion.enter : OpenCrayMotion.exit,
              child: AnimatedOpacity(
                opacity: isPresented ? 1 : 0,
                duration: duration,
                curve: isPresented ? OpenCrayMotion.enter : OpenCrayMotion.exit,
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Container(
                    width: 286,
                    color: Colors.white,
                    padding: EdgeInsets.fromLTRB(
                      16,
                      safePadding.top + 18,
                      16,
                      safePadding.bottom + 20,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        Text(
                          widget.drawer.eyebrow,
                          style: _ChatTextStyles.drawerEyebrow,
                        ),
                        const SizedBox(height: 10),
                        Text(
                          widget.drawer.title,
                          style: _ChatTextStyles.drawerTitle,
                        ),
                        const SizedBox(height: 16),
                        GestureDetector(
                          onTap: widget.onNewSessionPressed,
                          behavior: HitTestBehavior.opaque,
                          child: Container(
                            height: 40,
                            width: 132,
                            decoration: BoxDecoration(
                              gradient: OpenCrayGradients.brand,
                              borderRadius: BorderRadius.circular(14),
                              boxShadow: const <BoxShadow>[
                                BoxShadow(
                                  color: Color(0x3D2563EB),
                                  offset: Offset(0, 3),
                                  blurRadius: 10,
                                ),
                              ],
                            ),
                            alignment: Alignment.center,
                            child: Text(
                              widget.drawer.ctaLabel,
                              style: _ChatTextStyles.drawerCta,
                            ),
                          ),
                        ),
                        const SizedBox(height: 16),
                        Expanded(
                          child: ListView.separated(
                            key: const ValueKey<String>('chat-session-list'),
                            itemBuilder: (BuildContext context, int index) {
                              final ChatSessionListItemData session =
                                  widget.drawer.sessions[index];
                              return _SessionListTile(
                                key: ValueKey<String>(
                                  'chat-session-row-${session.sessionId}',
                                ),
                                copy: widget.copy,
                                session: session,
                                onPressed: () =>
                                    widget.onSessionPressed(session),
                                onLongPressStart: (details) =>
                                    widget.onSessionLongPress(
                                      session,
                                      details.globalPosition,
                                    ),
                              );
                            },
                            separatorBuilder:
                                (BuildContext context, int index) {
                                  return const SizedBox(height: 10);
                                },
                            itemCount: widget.drawer.sessions.length,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _scheduleUnmountAfterExit() {
    _closeEpoch += 1;
    final int epoch = _closeEpoch;
    if (OpenCrayMotion.reduce(context) || _isAutomatedWidgetTest) {
      setState(() {
        _isMountedForMotion = false;
      });
      return;
    }
    Future<void>.delayed(OpenCrayMotion.panel, () {
      if (!mounted || widget.isOpen || epoch != _closeEpoch) {
        return;
      }
      setState(() {
        _isMountedForMotion = false;
      });
    });
  }

  bool get _isAutomatedWidgetTest {
    bool result = false;
    assert(() {
      result = WidgetsBinding.instance.runtimeType.toString().contains(
        'TestWidgetsFlutterBinding',
      );
      return true;
    }());
    return result;
  }
}

class _SessionListTile extends StatefulWidget {
  const _SessionListTile({
    super.key,
    required this.copy,
    required this.session,
    required this.onPressed,
    required this.onLongPressStart,
  });

  final OpenCrayUiCopy copy;
  final ChatSessionListItemData session;
  final VoidCallback onPressed;
  final ValueChanged<LongPressStartDetails> onLongPressStart;

  @override
  State<_SessionListTile> createState() => _SessionListTileState();
}

class _SessionListTileState extends State<_SessionListTile> {
  bool _isPressed = false;

  @override
  Widget build(BuildContext context) {
    final String sessionMetaLabel = _formatChatSessionTimestampLabel(
      widget.copy,
      widget.session.lastMessageAtEpochMs,
      widget.session.meta,
    );
    final bool isSelected = widget.session.isSelected;
    final bool hasUnread = widget.session.unreadCount > 0;
    final Color backgroundColor = isSelected
        ? OpenCrayColors.primaryTint
        : _isPressed
        ? OpenCrayColors.surfaceMuted
        : OpenCrayColors.surfaceSubtle;
    final Color borderColor = isSelected
        ? OpenCrayColors.primaryBorder
        : hasUnread
        ? OpenCrayColors.dangerBorder
        : Colors.transparent;
    final Color railColor = isSelected
        ? _ChatPalette.accent
        : hasUnread
        ? OpenCrayColors.danger
        : Colors.transparent;
    return Semantics(
      button: true,
      selected: isSelected,
      child: GestureDetector(
        onTapDown: (_) => _setPressed(true),
        onTapUp: (_) {
          _setPressed(false);
          widget.onPressed();
        },
        onTapCancel: () => _setPressed(false),
        onLongPressStart: (LongPressStartDetails details) {
          _setPressed(false);
          widget.onLongPressStart(details);
        },
        behavior: HitTestBehavior.opaque,
        child: AnimatedContainer(
          duration: OpenCrayMotion.resolve(context, OpenCrayMotion.quick),
          curve: OpenCrayMotion.enter,
          decoration: BoxDecoration(
            color: backgroundColor,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: borderColor),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                AnimatedContainer(
                  duration: OpenCrayMotion.resolve(
                    context,
                    OpenCrayMotion.quick,
                  ),
                  curve: OpenCrayMotion.enter,
                  width: 3,
                  height: isSelected ? 44 : 10,
                  margin: const EdgeInsets.only(top: 2),
                  decoration: BoxDecoration(
                    color: railColor,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Row(
                        children: <Widget>[
                          Expanded(
                            child: Text(
                              widget.session.title,
                              style: _ChatTextStyles.sessionTitle.copyWith(
                                fontWeight: isSelected
                                    ? FontWeight.w700
                                    : FontWeight.w600,
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Text(
                            sessionMetaLabel,
                            style: _ChatTextStyles.sessionMeta,
                          ),
                          if (hasUnread) ...<Widget>[
                            const SizedBox(width: 8),
                            _SessionUnreadBadge(
                              sessionId: widget.session.sessionId,
                              count: widget.session.unreadCount,
                            ),
                          ],
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        widget.session.preview,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: _ChatTextStyles.sessionPreview,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _setPressed(bool value) {
    if (_isPressed == value) {
      return;
    }
    setState(() {
      _isPressed = value;
    });
  }
}

class _SessionUnreadBadge extends StatelessWidget {
  const _SessionUnreadBadge({required this.sessionId, required this.count});

  final String sessionId;
  final int count;

  @override
  Widget build(BuildContext context) {
    if (count <= 1) {
      return Container(
        key: ValueKey<String>('chat-session-unread-$sessionId'),
        width: 10,
        height: 10,
        decoration: const BoxDecoration(
          color: OpenCrayColors.danger,
          shape: BoxShape.circle,
        ),
      );
    }
    final String label = count > 99 ? '99+' : '$count';
    return Container(
      key: ValueKey<String>('chat-session-unread-$sessionId'),
      constraints: const BoxConstraints(minWidth: 20, minHeight: 20),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: OpenCrayColors.danger,
        borderRadius: BorderRadius.circular(999),
      ),
      alignment: Alignment.center,
      child: Text(
        label,
        style: _ChatTextStyles.timeline.copyWith(
          color: Colors.white,
          fontSize: 11,
        ),
      ),
    );
  }
}
