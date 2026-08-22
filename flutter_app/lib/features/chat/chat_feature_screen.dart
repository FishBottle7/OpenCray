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

class _ChatMessageMenuOverlay extends StatelessWidget {
  const _ChatMessageMenuOverlay({
    required this.copy,
    required this.menu,
    required this.isExiting,
    required this.onActionSelected,
  });

  final OpenCrayUiCopy copy;
  final _ActiveChatMessageMenu menu;
  final bool isExiting;
  final ValueChanged<_ChatMessageMenuAction> onActionSelected;

  @override
  Widget build(BuildContext context) {
    final bool useRedoAction = menu.showsRedo;
    final bool secondaryEnabled = useRedoAction ? menu.canRedo : menu.canRecall;
    final IconData secondaryIcon = useRedoAction
        ? Icons.redo_rounded
        : Icons.undo_rounded;
    final String secondaryLabel = useRedoAction
        ? copy.chatMessageRedoAction
        : copy.chatMessageRecallAction;
    final _ChatMessageMenuAction secondaryAction = useRedoAction
        ? _ChatMessageMenuAction.redo
        : _ChatMessageMenuAction.recall;
    final bool useBranchAction = !menu.isOutgoing;
    final bool tertiaryEnabled = useBranchAction
        ? menu.canBranch
        : menu.canEdit;
    final IconData tertiaryIcon = useBranchAction
        ? Icons.call_split_rounded
        : Icons.edit_rounded;
    final String tertiaryLabel = useBranchAction
        ? copy.chatMessageBranchAction
        : copy.chatMessageEditAction;
    final _ChatMessageMenuAction tertiaryAction = useBranchAction
        ? _ChatMessageMenuAction.branch
        : _ChatMessageMenuAction.edit;
    const double menuWidth = 202;
    const double menuHeight = 118;
    final Size screenSize = MediaQuery.sizeOf(context);
    final double minTop = MediaQuery.paddingOf(context).top + 44 + 12;
    final double unclampedLeft = menu.isOutgoing
        ? menu.bubbleRect.right - menuWidth
        : menu.bubbleRect.left;
    final double left = unclampedLeft.clamp(
      20.0,
      screenSize.width - menuWidth - 20,
    );
    final double top = (menu.bubbleRect.top - menuHeight - 8).clamp(
      minTop,
      screenSize.height - menuHeight - 12,
    );

    return Stack(
      children: <Widget>[
        Positioned(
          left: left,
          top: top,
          child: TweenAnimationBuilder<double>(
            duration: OpenCrayMotion.resolve(
              context,
              isExiting ? _chatMessageMenuExitDuration : OpenCrayMotion.micro,
            ),
            curve: isExiting ? OpenCrayMotion.exit : OpenCrayMotion.enter,
            tween: Tween<double>(
              begin: isExiting ? 1 : 0,
              end: isExiting ? 0 : 1,
            ),
            builder: (BuildContext context, double value, Widget? child) {
              final double side = menu.isOutgoing ? 1 : -1;
              final double offsetX = side * (1 - value) * 12;
              final double offsetY = (1 - value) * -4;
              return Opacity(
                opacity: value.clamp(0, 1),
                child: Transform.translate(
                  offset: Offset(offsetX, offsetY),
                  child: child,
                ),
              );
            },
            child: ClipRRect(
              borderRadius: BorderRadius.circular(20),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
                child: Container(
                  key: ValueKey<String>(
                    'chat-message-menu-${menu.message.messageId}',
                  ),
                  width: menuWidth,
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.92),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: const Color(0xCCFFFFFF)),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      Row(
                        children: <Widget>[
                          _ChatMessageMenuItem(
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-copy',
                            ),
                            icon: CupertinoIcons.doc_on_doc,
                            label: copy.chatMessageCopyAction,
                            onTap: () =>
                                onActionSelected(_ChatMessageMenuAction.copy),
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: ValueKey<String>(
                              'chat-message-menu-action-${secondaryAction.name}',
                            ),
                            icon: secondaryIcon,
                            label: secondaryLabel,
                            enabled: secondaryEnabled,
                            onTap: secondaryEnabled
                                ? () => onActionSelected(secondaryAction)
                                : null,
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: ValueKey<String>(
                              'chat-message-menu-action-${tertiaryAction.name}',
                            ),
                            icon: tertiaryIcon,
                            label: tertiaryLabel,
                            enabled: tertiaryEnabled,
                            onTap: tertiaryEnabled
                                ? () => onActionSelected(tertiaryAction)
                                : null,
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: <Widget>[
                          _ChatMessageMenuItem(
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-delete',
                            ),
                            icon: CupertinoIcons.delete_left,
                            label: copy.chatMessageDeleteAction,
                            isDestructive: true,
                            enabled: menu.canDelete,
                            onTap: menu.canDelete
                                ? () => onActionSelected(
                                    _ChatMessageMenuAction.delete,
                                  )
                                : null,
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-multiSelect',
                            ),
                            icon: CupertinoIcons.check_mark_circled,
                            label: copy.chatMessageSelectAction,
                            onTap: () => onActionSelected(
                              _ChatMessageMenuAction.multiSelect,
                            ),
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-quote',
                            ),
                            icon: CupertinoIcons.reply,
                            label: copy.chatMessageQuoteAction,
                            onTap: () =>
                                onActionSelected(_ChatMessageMenuAction.quote),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _ChatMessageMenuItem extends StatelessWidget {
  const _ChatMessageMenuItem({
    this.itemKey,
    required this.icon,
    required this.label,
    required this.onTap,
    this.enabled = true,
    this.isDestructive = false,
  });

  final Key? itemKey;
  final IconData icon;
  final String label;
  final VoidCallback? onTap;
  final bool enabled;
  final bool isDestructive;

  @override
  Widget build(BuildContext context) {
    final Color foregroundColor = isDestructive
        ? OpenCrayColors.danger
        : OpenCrayColors.textPrimary;
    final Color labelColor = isDestructive
        ? OpenCrayColors.danger
        : OpenCrayColors.textSecondary;
    return GestureDetector(
      key: itemKey,
      onTap: enabled ? onTap : null,
      behavior: HitTestBehavior.opaque,
      child: Opacity(
        opacity: enabled ? 1 : 0.34,
        child: SizedBox(
          width: 52,
          height: 46,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: <Widget>[
              Icon(icon, size: 17, color: foregroundColor),
              const SizedBox(height: 3),
              Text(
                label,
                style: _ChatTextStyles.messageMenuLabel.copyWith(
                  color: labelColor,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ChatRunTraceInlineActions extends StatefulWidget {
  const _ChatRunTraceInlineActions({
    required this.copy,
    required this.traces,
    this.showRetryActions = true,
    this.showInterruptActions = true,
    this.interruptConfirmRunId,
    this.busyInterruptRunIds = const <String>{},
    this.busyRetryRunIds = const <String>{},
    this.onArmInterruptRunTrace,
    this.onDismissInterruptRunTrace,
    this.onInterruptRunTrace,
    this.onRetryRunTrace,
  });

  final OpenCrayUiCopy copy;
  final List<ChatRunTraceData> traces;
  final bool showRetryActions;
  final bool showInterruptActions;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData>? onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onRetryRunTrace;

  @override
  State<_ChatRunTraceInlineActions> createState() =>
      _ChatRunTraceInlineActionsState();
}

class _ChatRunTraceInlineActionsState
    extends State<_ChatRunTraceInlineActions> {
  bool _outsideDismissReady = false;

  ChatRunTraceData? get _confirmTrace {
    if (!widget.showInterruptActions) {
      return null;
    }
    final String confirmRunId = widget.interruptConfirmRunId?.trim() ?? '';
    if (confirmRunId.isEmpty) {
      return null;
    }
    for (final trace in widget.traces) {
      if (trace.interruptId == confirmRunId &&
          trace.canInterrupt &&
          !trace.isTerminal) {
        return trace;
      }
    }
    return null;
  }

  @override
  void initState() {
    super.initState();
    _scheduleOutsideDismissReady();
  }

  @override
  void didUpdateWidget(covariant _ChatRunTraceInlineActions oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.interruptConfirmRunId != widget.interruptConfirmRunId) {
      _outsideDismissReady = false;
      _scheduleOutsideDismissReady();
    }
  }

  void _scheduleOutsideDismissReady() {
    if (_confirmTrace == null) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _confirmTrace == null) {
        return;
      }
      setState(() {
        _outsideDismissReady = true;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final List<Widget> rows = <Widget>[];
    for (final trace in widget.traces) {
      final bool showConfirm = _confirmTrace == trace;
      final bool interruptBusy = widget.busyInterruptRunIds.contains(
        trace.interruptId,
      );
      final bool retryBusy = widget.busyRetryRunIds.contains(trace.retryId);
      final bool canShowInterrupt =
          widget.showInterruptActions &&
          !trace.isTerminal &&
          (trace.canInterrupt || showConfirm || interruptBusy) &&
          (widget.onArmInterruptRunTrace != null ||
              showConfirm ||
              interruptBusy);
      final bool canShowRetry =
          widget.showRetryActions &&
          trace.isRetryable &&
          widget.onRetryRunTrace != null;
      if (!canShowInterrupt && !canShowRetry) {
        continue;
      }
      if (showConfirm) {
        rows.add(
          _RunTraceInterruptConfirmRow(
            key: ValueKey<String>(
              'chat-run-trace-interrupt-confirm-${trace.interruptId}',
            ),
            copy: widget.copy,
            runId: trace.interruptId,
            isBusy: interruptBusy,
            onConfirmed: widget.onInterruptRunTrace == null
                ? null
                : () => widget.onInterruptRunTrace!(trace),
          ),
        );
        continue;
      }
      final List<Widget> actions = <Widget>[];
      if (canShowRetry) {
        actions.add(
          _RunTraceActionButton(
            label: retryBusy ? '${trace.retryLabel!}...' : trace.retryLabel!,
            onTap: retryBusy ? null : () => widget.onRetryRunTrace!(trace),
          ),
        );
      }
      if (canShowInterrupt) {
        actions.add(
          _RunTraceInlineInterruptAction(
            key: ValueKey<String>(
              'chat-run-trace-interrupt-${trace.interruptId}',
            ),
            label: interruptBusy
                ? widget.copy.chatRunInterruptBusy
                : widget.copy.chatRunInterruptAction,
            enabled: !interruptBusy,
            onTap: interruptBusy || widget.onArmInterruptRunTrace == null
                ? null
                : () => widget.onArmInterruptRunTrace!(trace),
          ),
        );
      }
      if (actions.isNotEmpty) {
        rows.add(Wrap(spacing: 10, runSpacing: 8, children: actions));
      }
    }
    if (rows.isEmpty) {
      return const SizedBox.shrink();
    }
    return TapRegion(
      onTapOutside: _confirmTrace != null && _outsideDismissReady
          ? (_) {
              final ChatRunTraceData? trace = _confirmTrace;
              if (trace != null) {
                widget.onDismissInterruptRunTrace?.call(trace);
              }
            }
          : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: rows
            .asMap()
            .entries
            .map(
              (entry) => Padding(
                padding: EdgeInsets.only(
                  bottom: entry.key == rows.length - 1 ? 0 : 8,
                ),
                child: entry.value,
              ),
            )
            .toList(growable: false),
      ),
    );
  }
}

class _ChatMessageBubble extends StatefulWidget {
  const _ChatMessageBubble({
    required this.bridge,
    required this.copy,
    required this.message,
    required this.voicePlaybackControllerFactory,
    required this.backgroundColor,
    required this.textColor,
    required this.maxWidth,
    required this.selectionMode,
    required this.onLongPress,
    required this.onTextSelectionChanged,
    this.attachedRunTraces = const <ChatRunTraceData>[],
    this.interruptConfirmRunId,
    this.busyInterruptRunIds = const <String>{},
    this.busyRetryRunIds = const <String>{},
    this.onArmInterruptRunTrace,
    this.onDismissInterruptRunTrace,
    this.onInterruptRunTrace,
    this.onRetryRunTrace,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageData message;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final Color backgroundColor;
  final Color textColor;
  final double maxWidth;
  final bool selectionMode;
  final void Function(ChatMessageData, Rect, String?) onLongPress;
  final ValueChanged<OpenCrayMarkdownSelectionSnapshot?> onTextSelectionChanged;
  final List<ChatRunTraceData> attachedRunTraces;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData>? onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onRetryRunTrace;

  @override
  State<_ChatMessageBubble> createState() => _ChatMessageBubbleState();
}

class _ChatMessageBubbleState extends State<_ChatMessageBubble> {
  static const Duration _menuDelay = Duration(milliseconds: 220);
  static const double _cancelDistance = 18;

  Timer? _longPressTimer;
  Offset? _pointerDownGlobalPosition;
  bool _didOpenMenu = false;
  String? _selectedText;
  String? _selectedTextAtPointerDown;

  @override
  void dispose() {
    _longPressTimer?.cancel();
    super.dispose();
  }

  void _startLongPressTimer(PointerDownEvent event) {
    _longPressTimer?.cancel();
    _pointerDownGlobalPosition = event.position;
    _didOpenMenu = false;
    _selectedTextAtPointerDown = _selectedText;
    _longPressTimer = Timer(_menuDelay, _openMenuFromCurrentBounds);
  }

  void _handlePointerMove(PointerMoveEvent event) {
    final Offset? origin = _pointerDownGlobalPosition;
    if (origin == null) {
      return;
    }
    if ((event.position - origin).distance > _cancelDistance) {
      _longPressTimer?.cancel();
    }
  }

  void _cancelLongPressTimer() {
    _longPressTimer?.cancel();
    _longPressTimer = null;
    _pointerDownGlobalPosition = null;
    _selectedTextAtPointerDown = null;
  }

  void _openMenuFromCurrentBounds() {
    if (!mounted || _didOpenMenu) {
      return;
    }
    final RenderObject? renderObject = context.findRenderObject();
    if (renderObject is! RenderBox) {
      return;
    }
    _didOpenMenu = true;
    widget.onLongPress(
      widget.message,
      renderObject.localToGlobal(Offset.zero) & renderObject.size,
      _selectedTextAtPointerDown,
    );
  }

  @override
  Widget build(BuildContext context) {
    final selectionTheme = chatBubbleSelectionTheme(widget.message.kind);
    final _ChatInlineAttachmentContent inlineBody =
        _buildChatInlineAttachmentContent(
          widget.message.text.trim(),
          widget.message.attachments,
        );
    final Set<String> inlineAttachmentIds = inlineBody.referencedAttachmentIds;
    final List<ChatMessageAttachmentData> imageAttachments = widget
        .message
        .attachments
        .where(
          (attachment) =>
              attachment.kind == ChatAttachmentKind.image &&
              !inlineAttachmentIds.contains(attachment.attachmentId),
        )
        .toList(growable: false);
    final List<ChatMessageAttachmentData> otherAttachments = widget
        .message
        .attachments
        .where(
          (attachment) =>
              attachment.kind != ChatAttachmentKind.image &&
              !inlineAttachmentIds.contains(attachment.attachmentId),
        )
        .toList(growable: false);
    final bool hasText = inlineBody.segments.isNotEmpty;
    final bool hasImages = imageAttachments.isNotEmpty;
    final bool hasOtherAttachments = otherAttachments.isNotEmpty;
    final bool showStreamingIndicator =
        hasText &&
        widget.message.kind == ChatMessageKind.inbound &&
        widget.message.isStreaming;
    final String indicatorKeySuffix = widget.message.messageId.trim().isNotEmpty
        ? widget.message.messageId.trim()
        : 'anonymous';
    final Widget? streamingIndicator = showStreamingIndicator
        ? _ChatStreamingTailIndicator(
            key: ValueKey<String>(
              'chat-streaming-indicator-$indicatorKeySuffix',
            ),
            color: widget.textColor,
          )
        : null;
    final _ChatInlineAttachmentSegment? lastSegment =
        inlineBody.segments.isNotEmpty ? inlineBody.segments.last : null;
    final bool canInlineStreamingIndicator =
        streamingIndicator != null &&
        lastSegment is _ChatInlineMarkdownSegment &&
        openCrayMarkdownCanInlineTrailingWidget(lastSegment.markdown);
    final bool isOutboundBubble =
        widget.message.kind == ChatMessageKind.outbound;
    final bool useBrandGradient =
        isOutboundBubble && widget.backgroundColor == _ChatPalette.accent;
    final bool showInboundOutline =
        !isOutboundBubble && widget.backgroundColor == Colors.white;
    final Widget bubble = ConstrainedBox(
      key: ValueKey<String>('chat-bubble-${widget.message.messageId}'),
      constraints: BoxConstraints(maxWidth: widget.maxWidth),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: useBrandGradient ? null : widget.backgroundColor,
          gradient: useBrandGradient
              ? const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: <Color>[Color(0xFF3D7BF7), OpenCrayColors.primary],
                )
              : null,
          shape: RoundedSuperellipseBorder(
            borderRadius: const BorderRadius.all(Radius.circular(18)),
            side: showInboundOutline
                ? BorderSide(
                    color: OpenCrayColors.divider.withValues(alpha: 0.85),
                  )
                : BorderSide.none,
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              if (hasText)
                _ChatBubbleMarkdownBody(
                  bridge: widget.bridge,
                  copy: widget.copy,
                  content: inlineBody,
                  textColor: widget.textColor,
                  backgroundColor: widget.backgroundColor,
                  messageId: widget.message.messageId,
                  contentMaxWidth: widget.maxWidth - 28,
                  selectionTheme: selectionTheme,
                  trailingInlineIndicator: canInlineStreamingIndicator
                      ? streamingIndicator
                      : null,
                  onSelectionChanged: (selection) {
                    if (_didOpenMenu && _pointerDownGlobalPosition != null) {
                      return;
                    }
                    _selectedText = selection?.plainText;
                    widget.onTextSelectionChanged(selection);
                  },
                  contextMenuBuilder:
                      (
                        BuildContext context,
                        SelectableRegionState selectableRegionState,
                        OpenCrayMarkdownSelectionSnapshot? selection,
                      ) => const SizedBox.shrink(),
                  voicePlaybackControllerFactory:
                      widget.voicePlaybackControllerFactory,
                  isOutgoing: widget.message.kind == ChatMessageKind.outbound,
                ),
              if (streamingIndicator != null && !canInlineStreamingIndicator)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: streamingIndicator,
                ),
              if (hasImages) ...<Widget>[
                if (hasText) const SizedBox(height: 10),
                _ChatImageAttachmentGroup(
                  bridge: widget.bridge,
                  copy: widget.copy,
                  messageId: widget.message.messageId,
                  attachments: imageAttachments,
                  maxWidth: widget.maxWidth - 28,
                  isOutgoing: widget.message.kind == ChatMessageKind.outbound,
                ),
              ],
              if (hasOtherAttachments) ...<Widget>[
                if (hasText || hasImages) const SizedBox(height: 10),
                ...otherAttachments.asMap().entries.map((entry) {
                  return Padding(
                    padding: EdgeInsets.only(
                      bottom: entry.key == otherAttachments.length - 1 ? 0 : 8,
                    ),
                    child: _ChatAttachmentTile(
                      bridge: widget.bridge,
                      copy: widget.copy,
                      attachment: entry.value,
                      voicePlaybackControllerFactory:
                          widget.voicePlaybackControllerFactory,
                      isOutgoing:
                          widget.message.kind == ChatMessageKind.outbound,
                    ),
                  );
                }),
              ],
              if (widget.attachedRunTraces.isNotEmpty) ...<Widget>[
                if (hasText || hasImages || hasOtherAttachments)
                  const SizedBox(height: 10),
                _ChatRunTraceInlineActions(
                  copy: widget.copy,
                  traces: widget.attachedRunTraces,
                  showInterruptActions: false,
                  interruptConfirmRunId: widget.interruptConfirmRunId,
                  busyInterruptRunIds: widget.busyInterruptRunIds,
                  busyRetryRunIds: widget.busyRetryRunIds,
                  onArmInterruptRunTrace: widget.onArmInterruptRunTrace,
                  onDismissInterruptRunTrace: widget.onDismissInterruptRunTrace,
                  onInterruptRunTrace: widget.onInterruptRunTrace,
                  onRetryRunTrace: widget.onRetryRunTrace,
                ),
              ],
            ],
          ),
        ),
      ),
    );
    if (widget.selectionMode) {
      return IgnorePointer(child: bubble);
    }
    return Listener(
      onPointerDown: _startLongPressTimer,
      onPointerMove: _handlePointerMove,
      onPointerUp: (_) => _cancelLongPressTimer(),
      onPointerCancel: (_) => _cancelLongPressTimer(),
      child: bubble,
    );
  }
}

class _ChatStreamingTailIndicator extends StatefulWidget {
  const _ChatStreamingTailIndicator({super.key, required this.color});

  final Color color;

  @override
  State<_ChatStreamingTailIndicator> createState() =>
      _ChatStreamingTailIndicatorState();
}

class _ChatStreamingTailIndicatorState
    extends State<_ChatStreamingTailIndicator> {
  Timer? _pulseTimer;
  bool _highlighted = true;

  @override
  void initState() {
    super.initState();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncPulseTimer();
  }

  void _syncPulseTimer() {
    final bool disableAnimations =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (disableAnimations) {
      _pulseTimer?.cancel();
      _pulseTimer = null;
      _highlighted = true;
      return;
    }
    _pulseTimer ??= Timer.periodic(const Duration(milliseconds: 920), (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _highlighted = !_highlighted;
      });
    });
  }

  @override
  void didUpdateWidget(covariant _ChatStreamingTailIndicator oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.color != widget.color) {
      _syncPulseTimer();
    }
  }

  @override
  void dispose() {
    _pulseTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bool disableAnimations =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    return ExcludeSemantics(
      child: SizedBox(
        width: 10,
        height: 10,
        child: Center(
          child: AnimatedOpacity(
            opacity: disableAnimations || _highlighted ? 0.95 : 0.38,
            duration: const Duration(milliseconds: 460),
            curve: Curves.easeInOut,
            child: AnimatedScale(
              scale: disableAnimations || _highlighted ? 1.08 : 0.82,
              duration: const Duration(milliseconds: 460),
              curve: Curves.easeInOut,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: widget.color.withValues(alpha: 0.78),
                  shape: BoxShape.circle,
                ),
                child: const SizedBox(width: 5.5, height: 5.5),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

final RegExp _chatAttachmentMarkdownReferencePattern = RegExp(
  r'(!?)\[([^\]]*)\]\((attachment:[^)]+)\)',
);

@immutable
class _ChatInlineAttachmentContent {
  const _ChatInlineAttachmentContent({
    required this.segments,
    required this.referencedAttachmentIds,
  });

  final List<_ChatInlineAttachmentSegment> segments;
  final Set<String> referencedAttachmentIds;
}

abstract class _ChatInlineAttachmentSegment {
  const _ChatInlineAttachmentSegment();
}

class _ChatInlineMarkdownSegment extends _ChatInlineAttachmentSegment {
  const _ChatInlineMarkdownSegment(this.markdown);

  final String markdown;
}

class _ChatInlineResolvedAttachmentSegment
    extends _ChatInlineAttachmentSegment {
  const _ChatInlineResolvedAttachmentSegment({
    required this.reference,
    required this.attachment,
  });

  final _ChatInlineAttachmentReference reference;
  final ChatMessageAttachmentData attachment;
}

@immutable
class _ChatInlineAttachmentReference {
  const _ChatInlineAttachmentReference({
    required this.raw,
    required this.label,
    required this.targetToken,
    required this.isImage,
  });

  final String raw;
  final String label;
  final String targetToken;
  final bool isImage;

  String get fallbackLabel {
    final String trimmedLabel = label.trim();
    if (trimmedLabel.isNotEmpty) {
      return trimmedLabel;
    }
    final String normalizedToken = targetToken.trim();
    if (normalizedToken.isEmpty) {
      return '';
    }
    final String stripped = normalizedToken
        .replaceAll('\\', '/')
        .split('/')
        .last
        .trim();
    return stripped;
  }
}

_ChatInlineAttachmentContent _buildChatInlineAttachmentContent(
  String text,
  List<ChatMessageAttachmentData> attachments,
) {
  final String normalizedText = text.trim();
  if (normalizedText.isEmpty) {
    return const _ChatInlineAttachmentContent(
      segments: <_ChatInlineAttachmentSegment>[],
      referencedAttachmentIds: <String>{},
    );
  }
  final List<RegExpMatch> matches = _chatAttachmentMarkdownReferencePattern
      .allMatches(normalizedText)
      .toList();
  if (matches.isEmpty) {
    return _ChatInlineAttachmentContent(
      segments: <_ChatInlineAttachmentSegment>[
        _ChatInlineMarkdownSegment(normalizedText),
      ],
      referencedAttachmentIds: <String>{},
    );
  }

  final List<_ChatInlineAttachmentSegment> segments =
      <_ChatInlineAttachmentSegment>[];
  final Set<String> referencedAttachmentIds = <String>{};
  int cursor = 0;

  void addMarkdownSegment(String chunk) {
    final String normalizedChunk = chunk.trim();
    if (normalizedChunk.isEmpty) {
      return;
    }
    segments.add(_ChatInlineMarkdownSegment(normalizedChunk));
  }

  for (final RegExpMatch match in matches) {
    addMarkdownSegment(normalizedText.substring(cursor, match.start));
    final _ChatInlineAttachmentReference reference =
        _chatInlineAttachmentReferenceFromMatch(match);
    final ChatMessageAttachmentData? attachment =
        _resolveChatInlineAttachmentReference(reference, attachments);
    if (attachment != null) {
      segments.add(
        _ChatInlineResolvedAttachmentSegment(
          reference: reference,
          attachment: attachment,
        ),
      );
      final String attachmentId = attachment.attachmentId.trim();
      if (attachmentId.isNotEmpty) {
        referencedAttachmentIds.add(attachmentId);
      }
    } else {
      addMarkdownSegment(reference.fallbackLabel);
    }
    cursor = match.end;
  }
  addMarkdownSegment(normalizedText.substring(cursor));

  return _ChatInlineAttachmentContent(
    segments: segments,
    referencedAttachmentIds: referencedAttachmentIds,
  );
}

_ChatInlineAttachmentReference _chatInlineAttachmentReferenceFromMatch(
  RegExpMatch match,
) {
  final String href = (match.group(3) ?? '')
      .trim()
      .split(' ')
      .first
      .trim()
      .replaceFirst(RegExp(r'^attachment:'), '')
      .replaceFirst(RegExp(r'^//'), '')
      .trim();
  return _ChatInlineAttachmentReference(
    raw: match.group(0) ?? '',
    label: (match.group(2) ?? '').trim(),
    targetToken: _normalizeChatAttachmentMarkdownToken(href),
    isImage: (match.group(1) ?? '') == '!',
  );
}

ChatMessageAttachmentData? _resolveChatInlineAttachmentReference(
  _ChatInlineAttachmentReference reference,
  List<ChatMessageAttachmentData> attachments,
) {
  if (attachments.isEmpty) {
    return null;
  }
  final List<ChatMessageAttachmentData> compatibleAttachments =
      reference.isImage
      ? attachments
            .where((attachment) => attachment.kind == ChatAttachmentKind.image)
            .toList(growable: false)
      : attachments;
  if (compatibleAttachments.isEmpty) {
    return null;
  }

  if (reference.targetToken.isNotEmpty && reference.targetToken != 'artifact') {
    for (final ChatMessageAttachmentData attachment in compatibleAttachments) {
      if (_chatAttachmentMatchesToken(attachment, reference.targetToken)) {
        return attachment;
      }
    }
  }

  final String labelToken = _normalizeChatAttachmentMarkdownToken(
    reference.label,
  );
  if (labelToken.isNotEmpty) {
    for (final ChatMessageAttachmentData attachment in compatibleAttachments) {
      if (_chatAttachmentMatchesToken(attachment, labelToken)) {
        return attachment;
      }
    }
  }

  if ((reference.targetToken.isEmpty || reference.targetToken == 'artifact') &&
      compatibleAttachments.length == 1) {
    return compatibleAttachments.single;
  }
  return null;
}

bool _chatAttachmentMatchesToken(
  ChatMessageAttachmentData attachment,
  String token,
) {
  final String normalizedToken = _normalizeChatAttachmentMarkdownToken(token);
  if (normalizedToken.isEmpty) {
    return false;
  }
  final String normalizedAttachmentId = _normalizeChatAttachmentMarkdownToken(
    attachment.attachmentId,
  );
  final String normalizedLocalPath = _normalizeChatAttachmentMarkdownToken(
    attachment.localPath,
  );
  final String normalizedDisplayName = _normalizeChatAttachmentMarkdownToken(
    attachment.displayName,
  );
  final String normalizedBaseName = normalizedLocalPath.contains('/')
      ? normalizedLocalPath.split('/').last
      : normalizedLocalPath;
  return normalizedToken == normalizedAttachmentId ||
      normalizedToken == normalizedLocalPath ||
      normalizedToken == normalizedDisplayName ||
      normalizedToken == normalizedBaseName;
}

String _normalizeChatAttachmentMarkdownToken(String value) => value
    .trim()
    .replaceAll('\\', '/')
    .replaceFirst(RegExp(r'^/'), '')
    .toLowerCase();

Future<void> _handleOpenCrayMarkdownLinkTap(
  BuildContext context, {
  required String? href,
  required OpenCrayUiCopy copy,
  OpenCrayHostBridge? bridge,
}) async {
  final String target = href?.trim() ?? '';
  if (target.isEmpty) {
    return;
  }
  final ScaffoldMessengerState? messenger = ScaffoldMessenger.maybeOf(context);
  try {
    final String? routeName = openCrayResolveMarkdownInternalRoute(target);
    if (routeName != null) {
      await Navigator.of(context).pushNamed(routeName);
      return;
    }
    final OpenCrayHostBridge? hostBridge = bridge;
    if (hostBridge == null) {
      throw StateError('Missing host bridge for markdown link target.');
    }
    final Uri? externalUri = openCrayResolveMarkdownExternalUri(target);
    if (externalUri != null) {
      await hostBridge.openExternalUri(externalUri.toString());
      return;
    }
    final Uri? uri = Uri.tryParse(target);
    if (_isWorkspaceRelativeChatLink(uri, target)) {
      final String relativePath = _normalizeChatWorkspaceRelativePath(target);
      if (_isPreviewableTextRelativePath(relativePath)) {
        final OpenCrayFileTextPreview preview = await hostBridge
            .loadWorkspaceTextPreview(relativePath);
        if (!context.mounted) {
          return;
        }
        await _showChatTextPreviewDialog(context, preview, bridge: hostBridge);
        return;
      }
      await hostBridge.openWorkspaceEntry(relativePath);
      return;
    }
    throw StateError('Unsupported markdown link target.');
  } catch (error) {
    if (!context.mounted) {
      return;
    }
    final String message = openCrayMarkdownLocalizedErrorMessage(
      error,
      copy,
      fallback: copy.chatMessageActionFailed,
    );
    messenger
      ?..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }
}

bool _fontWeightIsEmphasized(FontWeight? fontWeight) {
  return fontWeight != null && fontWeight.index >= FontWeight.w600.index;
}

MarkdownStyleSheet _openCrayMarkdownStyleSheet(
  BuildContext context, {
  required TextStyle bodyStyle,
  required Color surfaceColor,
  required Color textColor,
  Color? linkColor,
  bool preferAccentForStrong = false,
  Color? strongAccentColor,
}) {
  final MarkdownStyleSheet base = MarkdownStyleSheet.fromTheme(
    Theme.of(context),
  );
  final bool darkSurface =
      ThemeData.estimateBrightnessForColor(surfaceColor) == Brightness.dark;
  final Color resolvedLinkColor =
      linkColor ??
      (darkSurface
          ? const Color(0xFFDCEBFF)
          : Theme.of(context).colorScheme.primary);
  final Color resolvedStrongAccentColor =
      strongAccentColor ?? Theme.of(context).colorScheme.primary;
  final Color chromeColor = darkSurface
      ? Colors.white.withValues(alpha: 0.18)
      : Colors.black.withValues(alpha: 0.08);
  final Color subtleChromeColor = darkSurface
      ? Colors.white.withValues(alpha: 0.12)
      : Colors.black.withValues(alpha: 0.05);
  final bool accentStrong =
      preferAccentForStrong || _fontWeightIsEmphasized(bodyStyle.fontWeight);
  final TextStyle headingStyle = bodyStyle.copyWith(
    fontWeight: FontWeight.w700,
    height: 1.3,
  );
  return base.copyWith(
    a: bodyStyle.copyWith(
      color: resolvedLinkColor,
      fontWeight: FontWeight.w600,
      decoration: TextDecoration.underline,
      decorationThickness: 1.2,
      decorationColor: resolvedLinkColor.withValues(alpha: 0.75),
    ),
    p: bodyStyle,
    pPadding: EdgeInsets.zero,
    strong: accentStrong
        ? bodyStyle.copyWith(color: resolvedStrongAccentColor)
        : bodyStyle.copyWith(fontWeight: FontWeight.w700),
    em: bodyStyle.copyWith(fontStyle: FontStyle.italic),
    del: bodyStyle.copyWith(decoration: TextDecoration.lineThrough),
    h1: headingStyle.copyWith(fontSize: 20),
    h2: headingStyle.copyWith(fontSize: 18),
    h3: headingStyle.copyWith(fontSize: 16),
    h4: headingStyle.copyWith(fontSize: 15),
    h5: headingStyle.copyWith(fontSize: 14),
    h6: headingStyle.copyWith(fontSize: 14),
    listBullet: bodyStyle,
    code: TextStyle(
      fontSize: 13,
      height: 1.45,
      fontFamily: 'monospace',
      color: textColor,
    ),
    codeblockPadding: const EdgeInsets.all(10),
    codeblockDecoration: BoxDecoration(
      color: subtleChromeColor,
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: chromeColor),
    ),
    blockquote: bodyStyle.copyWith(
      color: textColor.withValues(alpha: darkSurface ? 0.88 : 0.82),
    ),
    blockquotePadding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
    blockquoteDecoration: BoxDecoration(
      color: subtleChromeColor,
      borderRadius: BorderRadius.circular(12),
      border: Border(left: BorderSide(color: chromeColor, width: 3)),
    ),
    tableHead: bodyStyle.copyWith(fontWeight: FontWeight.w700),
    tableBody: bodyStyle,
    tableHeadAlign: TextAlign.left,
    tablePadding: const EdgeInsets.only(top: 2, bottom: 4),
    tableBorder: TableBorder.all(color: chromeColor),
    tableColumnWidth: const IntrinsicColumnWidth(),
    tableCellsPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
    tableCellsDecoration: BoxDecoration(color: subtleChromeColor),
    tableHeadCellsDecoration: BoxDecoration(color: chromeColor),
    horizontalRuleDecoration: BoxDecoration(
      border: Border(top: BorderSide(color: chromeColor)),
    ),
    blockSpacing: 10,
  );
}

MarkdownStyleSheet _runTraceMarkdownStyleSheet(
  BuildContext context, {
  required TextStyle bodyStyle,
  required Color surfaceColor,
  bool preferAccentForStrong = false,
}) {
  final Color textColor = bodyStyle.color ?? _ChatPalette.textPrimary;
  return _openCrayMarkdownStyleSheet(
    context,
    bodyStyle: bodyStyle,
    surfaceColor: surfaceColor,
    textColor: textColor,
    linkColor: _ChatPalette.inspectorAction,
    preferAccentForStrong: preferAccentForStrong,
    strongAccentColor: _ChatPalette.inspectorAction,
  );
}

class _OpenCrayMarkdownTextBlock extends StatelessWidget {
  const _OpenCrayMarkdownTextBlock({
    super.key,
    required this.copy,
    required this.data,
    required this.bodyStyle,
    required this.surfaceColor,
    this.bridge,
    this.preferAccentForStrong = false,
  });

  final OpenCrayUiCopy copy;
  final String data;
  final TextStyle bodyStyle;
  final Color surfaceColor;
  final OpenCrayHostBridge? bridge;
  final bool preferAccentForStrong;

  @override
  Widget build(BuildContext context) {
    final String markdown = data.trim();
    if (markdown.isEmpty) {
      return const SizedBox.shrink();
    }
    return OpenCrayMarkdownBody(
      data: markdown,
      hostBridge: bridge,
      onTapLink: (_, href, __) {
        unawaited(
          _handleOpenCrayMarkdownLinkTap(
            context,
            href: href,
            copy: copy,
            bridge: bridge,
          ),
        );
      },
      latexTextStyle: bodyStyle,
      styleSheet: _runTraceMarkdownStyleSheet(
        context,
        bodyStyle: bodyStyle,
        surfaceColor: surfaceColor,
        preferAccentForStrong: preferAccentForStrong,
      ),
      imageBackgroundColor: surfaceColor.withValues(alpha: 0.45),
      imageBorderColor: (bodyStyle.color ?? _ChatPalette.textPrimary)
          .withValues(alpha: 0.16),
    );
  }
}

class _ChatBubbleMarkdownBody extends StatelessWidget {
  const _ChatBubbleMarkdownBody({
    required this.bridge,
    required this.copy,
    required this.content,
    required this.textColor,
    required this.backgroundColor,
    required this.messageId,
    required this.contentMaxWidth,
    required this.selectionTheme,
    required this.onSelectionChanged,
    required this.contextMenuBuilder,
    required this.voicePlaybackControllerFactory,
    required this.isOutgoing,
    this.trailingInlineIndicator,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final _ChatInlineAttachmentContent content;
  final Color textColor;
  final Color backgroundColor;
  final String messageId;
  final double contentMaxWidth;
  final TextSelectionThemeData selectionTheme;
  final ValueChanged<OpenCrayMarkdownSelectionSnapshot?> onSelectionChanged;
  final OpenCrayMarkdownContextMenuBuilder contextMenuBuilder;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final bool isOutgoing;

  /// Streaming indicator rendered inline right after the final character of
  /// the last markdown segment while the message is still streaming.
  final Widget? trailingInlineIndicator;

  Future<void> _handleLinkTap(BuildContext context, String? href) async {
    await _handleOpenCrayMarkdownLinkTap(
      context,
      href: href,
      copy: copy,
      bridge: bridge,
    );
  }

  @override
  Widget build(BuildContext context) {
    final TextStyle bodyStyle = _ChatTextStyles.bubble.copyWith(
      color: textColor,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: content.segments
          .asMap()
          .entries
          .map((entry) {
            final _ChatInlineAttachmentSegment segment = entry.value;
            final bool isLastSegment =
                entry.key == content.segments.length - 1;
            final Widget child;
            if (segment is _ChatInlineMarkdownSegment) {
              child = OpenCraySelectableMarkdownBody(
                key: ValueKey<String>(
                  'chat-bubble-markdown-$messageId-${entry.key}',
                ),
                data: segment.markdown,
                hostBridge: bridge,
                selectionTheme: selectionTheme,
                onSelectionChanged: onSelectionChanged,
                contextMenuBuilder: contextMenuBuilder,
                trailingInlineWidget: isLastSegment
                    ? trailingInlineIndicator
                    : null,
                onTapLink: (_, href, __) {
                  unawaited(_handleLinkTap(context, href));
                },
                latexTextStyle: bodyStyle,
                styleSheet: _chatMarkdownStyleSheet(
                  context,
                  textColor: textColor,
                  backgroundColor: backgroundColor,
                ),
                imageBackgroundColor: backgroundColor.withValues(alpha: 0.12),
                imageBorderColor: textColor.withValues(alpha: 0.18),
              );
            } else if (segment is _ChatInlineResolvedAttachmentSegment) {
              if (segment.reference.isImage &&
                  segment.attachment.kind == ChatAttachmentKind.image) {
                child = _ChatImageAttachmentPreview(
                  bridge: bridge,
                  copy: copy,
                  attachment: segment.attachment,
                  maxWidth: contentMaxWidth,
                  isOutgoing: isOutgoing,
                );
              } else {
                child = _ChatAttachmentTile(
                  bridge: bridge,
                  copy: copy,
                  attachment: segment.attachment,
                  voicePlaybackControllerFactory:
                      voicePlaybackControllerFactory,
                  isOutgoing: isOutgoing,
                );
              }
            } else {
              child = const SizedBox.shrink();
            }
            return Padding(
              padding: EdgeInsets.only(
                bottom: entry.key == content.segments.length - 1 ? 0 : 10,
              ),
              child: child,
            );
          })
          .toList(growable: false),
    );
  }
}

MarkdownStyleSheet _chatMarkdownStyleSheet(
  BuildContext context, {
  required Color textColor,
  required Color backgroundColor,
}) {
  final TextStyle bodyStyle = _ChatTextStyles.bubble.copyWith(color: textColor);
  return _openCrayMarkdownStyleSheet(
    context,
    bodyStyle: bodyStyle,
    surfaceColor: backgroundColor,
    textColor: textColor,
  );
}

class _ChatImageAttachmentGroup extends StatelessWidget {
  const _ChatImageAttachmentGroup({
    required this.bridge,
    required this.copy,
    required this.messageId,
    required this.attachments,
    required this.maxWidth,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final String messageId;
  final List<ChatMessageAttachmentData> attachments;
  final double maxWidth;
  final bool isOutgoing;

  @override
  Widget build(BuildContext context) {
    if (attachments.isEmpty) {
      return const SizedBox.shrink();
    }
    final int columnCount = switch (attachments.length) {
      1 => 1,
      <= 4 => 2,
      _ => 3,
    };
    const double spacing = 6;
    final double contentWidth =
        (maxWidth - spacing * (columnCount - 1)) / columnCount;

    return Wrap(
      key: ValueKey<String>('chat-message-image-group-$messageId'),
      spacing: spacing,
      runSpacing: spacing,
      children: attachments
          .map((attachment) {
            return SizedBox(
              width: columnCount == 1 ? maxWidth : contentWidth,
              child: _ChatImageAttachmentPreview(
                bridge: bridge,
                copy: copy,
                attachment: attachment,
                maxWidth: columnCount == 1 ? maxWidth : contentWidth,
                isOutgoing: isOutgoing,
              ),
            );
          })
          .toList(growable: false),
    );
  }
}

class _ChatImageAttachmentPreview extends StatefulWidget {
  const _ChatImageAttachmentPreview({
    required this.bridge,
    required this.copy,
    required this.attachment,
    required this.maxWidth,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageAttachmentData attachment;
  final double maxWidth;
  final bool isOutgoing;

  @override
  State<_ChatImageAttachmentPreview> createState() =>
      _ChatImageAttachmentPreviewState();
}

class _ChatImageAttachmentPreviewState
    extends State<_ChatImageAttachmentPreview> {
  Future<OpenCrayFileImagePreview>? _previewFuture;

  @override
  void initState() {
    super.initState();
    _previewFuture = _loadPreview();
  }

  @override
  void didUpdateWidget(covariant _ChatImageAttachmentPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.attachment.localPath != widget.attachment.localPath ||
        oldWidget.bridge != widget.bridge) {
      _previewFuture = _loadPreview();
    }
  }

  Future<OpenCrayFileImagePreview>? _loadPreview() {
    final bridge = widget.bridge;
    final localPath = widget.attachment.localPath.trim();
    if (bridge == null || localPath.isEmpty) {
      return null;
    }
    return bridge.loadWorkspaceImagePreview(localPath);
  }

  Future<void> _showFullscreenPreview(
    BuildContext context,
    OpenCrayFileImagePreview preview,
  ) {
    return _showChatPreviewDialog(
      context,
      barrierColor: const Color(0xB3000000),
      builder: (dialogContext) {
        return Dialog(
          backgroundColor: Colors.transparent,
          insetPadding: const EdgeInsets.all(16),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(20),
            child: InteractiveViewer(
              child: ColoredBox(
                color: Colors.black,
                child: OpenCrayImageBytesView(
                  bytes: preview.bytes,
                  mimeType: preview.mimeType,
                  fit: BoxFit.contain,
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final Color borderColor = widget.isOutgoing
        ? Colors.white.withValues(alpha: 0.22)
        : _ChatPalette.border;
    final Color placeholderColor = widget.isOutgoing
        ? Colors.white.withValues(alpha: 0.14)
        : OpenCrayColors.surfaceMuted;
    final Future<OpenCrayFileImagePreview>? previewFuture = _previewFuture;
    final double stableAspectRatio = _chatMessageAttachmentStableAspectRatio(
      widget.attachment,
    );
    if (previewFuture == null) {
      return _ChatImageAttachmentPlaceholder(
        attachment: widget.attachment,
        maxWidth: widget.maxWidth,
        isOutgoing: widget.isOutgoing,
      );
    }
    return FutureBuilder<OpenCrayFileImagePreview>(
      future: previewFuture,
      builder: (context, snapshot) {
        final OpenCrayFileImagePreview? preview = snapshot.data;
        final bool ready = preview != null && preview.bytes.isNotEmpty;
        return GestureDetector(
          key: ValueKey<String>(
            'chat-message-image-attachment-${widget.attachment.attachmentId}',
          ),
          onTap: ready ? () => _showFullscreenPreview(context, preview) : null,
          child: AspectRatio(
            aspectRatio: stableAspectRatio,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(14),
              child: Stack(
                fit: StackFit.expand,
                children: <Widget>[
                  DecoratedBox(
                    decoration: BoxDecoration(
                      color: placeholderColor,
                      border: Border.all(color: borderColor),
                    ),
                    child: AnimatedSwitcher(
                      duration: OpenCrayMotion.resolve(
                        context,
                        OpenCrayMotion.quick,
                      ),
                      switchInCurve: OpenCrayMotion.enter,
                      switchOutCurve: OpenCrayMotion.exit,
                      transitionBuilder:
                          (Widget child, Animation<double> animation) {
                            return FadeTransition(
                              opacity: animation,
                              child: child,
                            );
                          },
                      child: ready
                          ? OpenCrayImageBytesView(
                              key: ValueKey<String>(
                                'chat-message-image-ready-${widget.attachment.attachmentId}',
                              ),
                              bytes: preview.bytes,
                              mimeType: preview.mimeType,
                              fit: BoxFit.cover,
                            )
                          : _ChatImageAttachmentPlaceholderBody(
                              key: ValueKey<String>(
                                'chat-message-image-placeholder-${widget.attachment.attachmentId}',
                              ),
                              attachment: widget.attachment,
                              isOutgoing: widget.isOutgoing,
                            ),
                    ),
                  ),
                  Positioned(
                    top: 6,
                    right: 6,
                    child: _ChatAttachmentActions(
                      bridge: widget.bridge,
                      copy: widget.copy,
                      attachment: widget.attachment,
                      isOutgoing: widget.isOutgoing,
                      keyPrefix: 'chat-message-image-attachment',
                      overlay: true,
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

double _chatMessageAttachmentStableAspectRatio(
  ChatMessageAttachmentData attachment,
) {
  final int width = attachment.widthPx ?? 0;
  final int height = attachment.heightPx ?? 0;
  if (width <= 0 || height <= 0) {
    return 1;
  }
  return (width / height).clamp(0.65, 1.65).toDouble();
}

class _ChatImageAttachmentPlaceholder extends StatelessWidget {
  const _ChatImageAttachmentPlaceholder({
    required this.attachment,
    required this.maxWidth,
    required this.isOutgoing,
  });

  final ChatMessageAttachmentData attachment;
  final double maxWidth;
  final bool isOutgoing;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      key: ValueKey<String>(
        'chat-message-image-attachment-${attachment.attachmentId}',
      ),
      width: maxWidth,
      child: AspectRatio(
        aspectRatio: _chatMessageAttachmentStableAspectRatio(attachment),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(14),
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: isOutgoing
                  ? Colors.white.withValues(alpha: 0.14)
                  : OpenCrayColors.surfaceMuted,
              border: Border.all(
                color: isOutgoing
                    ? Colors.white.withValues(alpha: 0.22)
                    : _ChatPalette.border,
              ),
            ),
            child: _ChatImageAttachmentPlaceholderBody(
              attachment: attachment,
              isOutgoing: isOutgoing,
            ),
          ),
        ),
      ),
    );
  }
}

class _ChatImageAttachmentPlaceholderBody extends StatelessWidget {
  const _ChatImageAttachmentPlaceholderBody({
    super.key,
    required this.attachment,
    required this.isOutgoing,
  });

  final ChatMessageAttachmentData attachment;
  final bool isOutgoing;

  @override
  Widget build(BuildContext context) {
    final Color foregroundColor = isOutgoing
        ? Colors.white.withValues(alpha: 0.9)
        : _ChatPalette.textSecondary;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Icon(Icons.image_outlined, size: 22, color: foregroundColor),
            const SizedBox(height: 8),
            Text(
              attachment.displayName,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: _ChatTextStyles.attachmentLabel.copyWith(
                color: foregroundColor,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ChatAttachmentTile extends StatelessWidget {
  const _ChatAttachmentTile({
    required this.bridge,
    required this.copy,
    required this.attachment,
    required this.voicePlaybackControllerFactory,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageAttachmentData attachment;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final bool isOutgoing;

  @override
  Widget build(BuildContext context) {
    if (attachment.kind == ChatAttachmentKind.voice) {
      return _ChatVoiceAttachmentTile(
        bridge: bridge,
        copy: copy,
        attachment: attachment,
        voicePlaybackControllerFactory: voicePlaybackControllerFactory,
        isOutgoing: isOutgoing,
      );
    }
    return _ChatFileAttachmentTile(
      bridge: bridge,
      copy: copy,
      attachment: attachment,
      isOutgoing: isOutgoing,
    );
  }
}

class _ChatFileAttachmentTile extends StatelessWidget {
  const _ChatFileAttachmentTile({
    required this.bridge,
    required this.copy,
    required this.attachment,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageAttachmentData attachment;
  final bool isOutgoing;

  Future<void> _openAttachment(BuildContext context) async {
    final hostBridge = bridge;
    final localPath = attachment.localPath.trim();
    final messenger = ScaffoldMessenger.maybeOf(context);
    if (hostBridge == null || localPath.isEmpty) {
      return;
    }
    try {
      if (_isPreviewableTextAttachment(attachment)) {
        final preview = await hostBridge.loadWorkspaceTextPreview(localPath);
        if (!context.mounted) {
          return;
        }
        await _showChatTextPreviewDialog(context, preview, bridge: hostBridge);
        return;
      }
      await hostBridge.openWorkspaceEntry(localPath);
    } catch (_) {
      messenger
        ?..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(copy.chatMessageActionFailed)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final bool canOpen =
        bridge != null && attachment.localPath.trim().isNotEmpty;
    final Color surfaceColor = isOutgoing
        ? Colors.white.withValues(alpha: 0.12)
        : OpenCrayColors.surfaceMuted;
    final Color borderColor = isOutgoing
        ? Colors.white.withValues(alpha: 0.22)
        : _ChatPalette.border;
    final Color titleColor = isOutgoing
        ? Colors.white
        : _ChatPalette.textPrimary;
    final Color detailColor = isOutgoing
        ? Colors.white.withValues(alpha: 0.82)
        : _ChatPalette.textSecondary;

    return Ink(
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: borderColor),
      ),
      child: Material(
        color: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(
            children: <Widget>[
              Expanded(
                child: InkWell(
                  key: ValueKey<String>(
                    'chat-message-attachment-${attachment.attachmentId}',
                  ),
                  borderRadius: BorderRadius.circular(10),
                  onTap: canOpen ? () => _openAttachment(context) : null,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 2),
                    child: Row(
                      children: <Widget>[
                        Container(
                          width: 36,
                          height: 36,
                          decoration: BoxDecoration(
                            color: isOutgoing
                                ? Colors.white.withValues(alpha: 0.18)
                                : Colors.white,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Icon(
                            _isPreviewableTextAttachment(attachment)
                                ? Icons.article_outlined
                                : Icons.description_outlined,
                            size: 18,
                            color: titleColor,
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: <Widget>[
                              Text(
                                attachment.displayName,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: _ChatTextStyles.attachmentLabel.copyWith(
                                  color: titleColor,
                                ),
                              ),
                              const SizedBox(height: 3),
                              Text(
                                _chatAttachmentDetailText(attachment),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: _ChatTextStyles.attachmentDetail
                                    .copyWith(color: detailColor),
                              ),
                            ],
                          ),
                        ),
                        if (canOpen) ...<Widget>[
                          const SizedBox(width: 10),
                          Icon(
                            _isPreviewableTextAttachment(attachment)
                                ? Icons.visibility_outlined
                                : Icons.open_in_new_rounded,
                            size: 16,
                            color: detailColor,
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
              if (canOpen) ...<Widget>[
                const SizedBox(width: 8),
                _ChatAttachmentActions(
                  bridge: bridge,
                  copy: copy,
                  attachment: attachment,
                  isOutgoing: isOutgoing,
                  keyPrefix: 'chat-message-attachment',
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _ChatVoiceAttachmentTile extends StatefulWidget {
  const _ChatVoiceAttachmentTile({
    required this.bridge,
    required this.copy,
    required this.attachment,
    required this.voicePlaybackControllerFactory,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageAttachmentData attachment;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final bool isOutgoing;

  @override
  State<_ChatVoiceAttachmentTile> createState() =>
      _ChatVoiceAttachmentTileState();
}

class _ChatVoiceAttachmentTileState extends State<_ChatVoiceAttachmentTile> {
  late final ChatVoicePlaybackController _playbackController =
      (widget.voicePlaybackControllerFactory ??
      createDefaultChatVoicePlaybackController)();
  OpenCrayFileVoicePlaybackSource? _voiceSource;
  bool _isResolvingSource = false;
  bool _isTranscriptExpanded = false;
  double? _dragProgress;
  int _lastChildInteractionAtEpochMs = 0;

  @override
  void dispose() {
    unawaited(_playbackController.dispose());
    super.dispose();
  }

  Future<bool> _ensureVoiceSourceLoaded() async {
    final hostBridge = widget.bridge;
    final localPath = widget.attachment.localPath.trim();
    if (hostBridge == null || localPath.isEmpty || _isResolvingSource) {
      return false;
    }
    if (_voiceSource != null) {
      return true;
    }
    try {
      setState(() {
        _isResolvingSource = true;
      });
      final source = await hostBridge.loadWorkspaceVoicePlaybackSource(
        localPath,
      );
      if (!mounted) {
        return false;
      }
      await _playbackController.setSource(filePath: source.localFilePath);
      if (!mounted) {
        return false;
      }
      setState(() {
        _voiceSource = source;
        _isResolvingSource = false;
      });
      return true;
    } catch (_) {
      if (mounted) {
        setState(() {
          _isResolvingSource = false;
        });
      }
      _showPlaybackError();
      return false;
    }
  }

  Future<void> _togglePlayback() async {
    if (_shouldSuppressParentToggle()) {
      return;
    }
    if (!await _ensureVoiceSourceLoaded()) {
      return;
    }
    try {
      if (_playbackController.currentState.isPlaying) {
        await _playbackController.pause();
      } else {
        await _playbackController.play();
      }
    } catch (_) {
      _showPlaybackError();
    }
  }

  Future<void> _seekToFraction(double fraction, Duration totalDuration) async {
    if (totalDuration <= Duration.zero) {
      return;
    }
    if (!await _ensureVoiceSourceLoaded()) {
      return;
    }
    final Duration target = Duration(
      milliseconds: (totalDuration.inMilliseconds * fraction.clamp(0.0, 1.0))
          .round(),
    );
    try {
      await _playbackController.seek(target);
    } catch (_) {
      _showPlaybackError();
    }
  }

  void _startWaveformDrag(double fraction) {
    _recordChildInteraction();
    setState(() {
      _dragProgress = fraction.clamp(0.0, 1.0);
    });
  }

  void _updateWaveformDrag(double fraction) {
    setState(() {
      _dragProgress = fraction.clamp(0.0, 1.0);
    });
  }

  Future<void> _finishWaveformDrag(Duration totalDuration) async {
    final double? dragProgress = _dragProgress;
    setState(() {
      _dragProgress = null;
    });
    if (dragProgress == null) {
      return;
    }
    await _seekToFraction(dragProgress, totalDuration);
  }

  void _toggleTranscript() {
    _recordChildInteraction();
    setState(() {
      _isTranscriptExpanded = !_isTranscriptExpanded;
    });
  }

  void _recordChildInteraction() {
    _lastChildInteractionAtEpochMs = DateTime.now().millisecondsSinceEpoch;
  }

  bool _shouldSuppressParentToggle() {
    final int now = DateTime.now().millisecondsSinceEpoch;
    return now - _lastChildInteractionAtEpochMs <
        _chatVoiceChildInteractionSuppressionWindowMs;
  }

  void _showPlaybackError() {
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.maybeOf(context)
      ?..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(content: Text(widget.copy.chatMessageActionFailed)),
      );
  }

  Future<void> _shareAttachment() async {
    _recordChildInteraction();
    await _shareChatAttachment(
      context,
      bridge: widget.bridge,
      copy: widget.copy,
      attachment: widget.attachment,
    );
  }

  Future<void> _saveAttachment() async {
    _recordChildInteraction();
    await _saveChatAttachment(
      context,
      bridge: widget.bridge,
      copy: widget.copy,
      attachment: widget.attachment,
    );
  }

  List<int> _effectiveWaveformBars() {
    if (widget.attachment.waveformBars.isNotEmpty) {
      return widget.attachment.waveformBars;
    }
    return _chatFallbackVoiceWaveformBars;
  }

  @override
  Widget build(BuildContext context) {
    final bool canPlay =
        widget.bridge != null && widget.attachment.localPath.trim().isNotEmpty;
    final Color surfaceColor = widget.isOutgoing
        ? Colors.white.withValues(alpha: 0.12)
        : OpenCrayColors.surfaceMuted;
    final Color borderColor = widget.isOutgoing
        ? Colors.white.withValues(alpha: 0.22)
        : _ChatPalette.border;
    final Color titleColor = widget.isOutgoing
        ? Colors.white
        : _ChatPalette.textPrimary;
    final Color detailColor = widget.isOutgoing
        ? Colors.white.withValues(alpha: 0.82)
        : _ChatPalette.textSecondary;

    return StreamBuilder<ChatVoicePlaybackSnapshot>(
      stream: _playbackController.snapshots,
      initialData: _playbackController.currentState,
      builder: (context, snapshot) {
        final ChatVoicePlaybackSnapshot playback =
            snapshot.data ?? const ChatVoicePlaybackSnapshot();
        final bool isBusy = _isResolvingSource || playback.isLoading;
        final Duration fallbackDuration = Duration(
          milliseconds:
              widget.attachment.durationMs ?? _voiceSource?.durationMs ?? 0,
        );
        final Duration totalDuration = playback.duration > Duration.zero
            ? playback.duration
            : fallbackDuration;
        final Duration currentPosition =
            totalDuration > Duration.zero && playback.position > totalDuration
            ? totalDuration
            : playback.position;
        final double currentProgress = totalDuration.inMilliseconds > 0
            ? currentPosition.inMilliseconds / totalDuration.inMilliseconds
            : 0;
        final bool showPlaybackClock =
            totalDuration > Duration.zero &&
            (currentPosition > Duration.zero || playback.isPlaying);
        final double progress = (_dragProgress ?? currentProgress)
            .clamp(0.0, 1.0)
            .toDouble();
        final String detailText =
            showPlaybackClock && totalDuration > Duration.zero
            ? '${_formatAttachmentDuration(currentPosition.inMilliseconds)} / ${_formatAttachmentDuration(totalDuration.inMilliseconds)}'
            : _chatAttachmentDetailText(widget.attachment);
        final String? transcriptText = (() {
          final String candidate =
              widget.attachment.transcriptText?.trim() ?? '';
          return candidate.isEmpty ? null : candidate;
        })();
        final bool shouldCollapseTranscript =
            transcriptText != null &&
            _shouldCollapseVoiceTranscript(transcriptText);
        final List<int> waveformBars = _effectiveWaveformBars();

        final bool canUseAttachmentActions =
            widget.bridge != null &&
            widget.attachment.localPath.trim().isNotEmpty;

        return Ink(
          key: ValueKey<String>(
            'chat-message-attachment-${widget.attachment.attachmentId}',
          ),
          decoration: BoxDecoration(
            color: surfaceColor,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: borderColor),
          ),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              borderRadius: BorderRadius.circular(14),
              onTap: canPlay ? _togglePlayback : null,
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
                child: Row(
                  children: <Widget>[
                    Container(
                      width: 36,
                      height: 36,
                      decoration: BoxDecoration(
                        color: widget.isOutgoing
                            ? Colors.white.withValues(alpha: 0.18)
                            : Colors.white,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Center(
                        child: isBusy
                            ? SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(
                                  strokeWidth: 1.8,
                                  color: titleColor,
                                ),
                              )
                            : Icon(
                                playback.isPlaying
                                    ? Icons.pause_rounded
                                    : Icons.play_arrow_rounded,
                                size: 18,
                                color: titleColor,
                              ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Text(
                            widget.attachment.displayName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: _ChatTextStyles.attachmentLabel.copyWith(
                              color: titleColor,
                            ),
                          ),
                          const SizedBox(height: 3),
                          Text(
                            detailText,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: _ChatTextStyles.attachmentDetail.copyWith(
                              color: detailColor,
                            ),
                          ),
                          const SizedBox(height: 10),
                          _ChatVoiceWaveform(
                            key: ValueKey<String>(
                              'chat-message-attachment-waveform-${widget.attachment.attachmentId}',
                            ),
                            bars: waveformBars,
                            progress: progress,
                            playedColor: widget.isOutgoing
                                ? Colors.white
                                : _ChatPalette.accent,
                            unplayedColor: widget.isOutgoing
                                ? Colors.white.withValues(alpha: 0.18)
                                : OpenCrayColors.outline,
                            onTapSeek: canPlay && totalDuration > Duration.zero
                                ? (fraction) {
                                    _recordChildInteraction();
                                    unawaited(
                                      _seekToFraction(fraction, totalDuration),
                                    );
                                  }
                                : null,
                            onDragStart:
                                canPlay && totalDuration > Duration.zero
                                ? _startWaveformDrag
                                : null,
                            onDragUpdate:
                                canPlay && totalDuration > Duration.zero
                                ? _updateWaveformDrag
                                : null,
                            onDragEnd: canPlay && totalDuration > Duration.zero
                                ? () {
                                    unawaited(
                                      _finishWaveformDrag(totalDuration),
                                    );
                                  }
                                : null,
                          ),
                          if (transcriptText != null) ...<Widget>[
                            const SizedBox(height: 8),
                            Text(
                              transcriptText,
                              key: ValueKey<String>(
                                'chat-message-attachment-transcript-${widget.attachment.attachmentId}',
                              ),
                              maxLines:
                                  shouldCollapseTranscript &&
                                      !_isTranscriptExpanded
                                  ? 2
                                  : null,
                              overflow:
                                  shouldCollapseTranscript &&
                                      !_isTranscriptExpanded
                                  ? TextOverflow.ellipsis
                                  : TextOverflow.visible,
                              style: _ChatTextStyles.attachmentDetail.copyWith(
                                color: detailColor,
                                height: 1.35,
                              ),
                            ),
                            if (shouldCollapseTranscript)
                              Padding(
                                padding: const EdgeInsets.only(top: 4),
                                child: GestureDetector(
                                  behavior: HitTestBehavior.opaque,
                                  onTap: _toggleTranscript,
                                  child: Text(
                                    _isTranscriptExpanded
                                        ? 'Hide transcript'
                                        : 'Show transcript',
                                    key: ValueKey<String>(
                                      'chat-message-attachment-transcript-toggle-${widget.attachment.attachmentId}',
                                    ),
                                    style: _ChatTextStyles.attachmentDetail
                                        .copyWith(
                                          color: titleColor,
                                          fontWeight: FontWeight.w600,
                                        ),
                                  ),
                                ),
                              ),
                          ],
                        ],
                      ),
                    ),
                    if (canUseAttachmentActions) ...<Widget>[
                      const SizedBox(width: 8),
                      _ChatAttachmentActions(
                        bridge: widget.bridge,
                        copy: widget.copy,
                        attachment: widget.attachment,
                        isOutgoing: widget.isOutgoing,
                        keyPrefix: 'chat-message-attachment',
                        onShare: _shareAttachment,
                        onSave: _saveAttachment,
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _ChatAttachmentActions extends StatelessWidget {
  const _ChatAttachmentActions({
    required this.bridge,
    required this.copy,
    required this.attachment,
    required this.isOutgoing,
    required this.keyPrefix,
    this.overlay = false,
    this.onShare,
    this.onSave,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageAttachmentData attachment;
  final bool isOutgoing;
  final String keyPrefix;
  final bool overlay;
  final Future<void> Function()? onShare;
  final Future<void> Function()? onSave;

  @override
  Widget build(BuildContext context) {
    final bool enabled =
        bridge != null && attachment.localPath.trim().isNotEmpty;
    final Color foregroundColor = overlay || isOutgoing
        ? Colors.white
        : _ChatPalette.textSecondary;
    final Color backgroundColor = overlay
        ? Colors.black.withValues(alpha: 0.42)
        : (isOutgoing
              ? Colors.white.withValues(alpha: 0.14)
              : Colors.white.withValues(alpha: 0.72));
    final BorderSide border = BorderSide(
      color: overlay || isOutgoing
          ? Colors.white.withValues(alpha: 0.18)
          : _ChatPalette.border,
    );
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(8),
        border: Border.fromBorderSide(border),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          _ChatAttachmentActionButton(
            key: ValueKey<String>(
              '$keyPrefix-share-${attachment.attachmentId}',
            ),
            tooltip: copy.chatAttachmentShareAction,
            icon: Icons.share_outlined,
            color: foregroundColor,
            onPressed: enabled
                ? () async {
                    if (onShare != null) {
                      await onShare!();
                      return;
                    }
                    await _shareChatAttachment(
                      context,
                      bridge: bridge,
                      copy: copy,
                      attachment: attachment,
                    );
                  }
                : null,
          ),
          _ChatAttachmentActionButton(
            key: ValueKey<String>('$keyPrefix-save-${attachment.attachmentId}'),
            tooltip: copy.chatAttachmentSaveAction,
            icon: Icons.download_rounded,
            color: foregroundColor,
            onPressed: enabled
                ? () async {
                    if (onSave != null) {
                      await onSave!();
                      return;
                    }
                    await _saveChatAttachment(
                      context,
                      bridge: bridge,
                      copy: copy,
                      attachment: attachment,
                    );
                  }
                : null,
          ),
        ],
      ),
    );
  }
}

class _ChatAttachmentActionButton extends StatelessWidget {
  const _ChatAttachmentActionButton({
    super.key,
    required this.tooltip,
    required this.icon,
    required this.color,
    required this.onPressed,
  });

  final String tooltip;
  final IconData icon;
  final Color color;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return IconButton(
      tooltip: tooltip,
      icon: Icon(icon, size: 16),
      color: color,
      disabledColor: color.withValues(alpha: 0.36),
      visualDensity: VisualDensity.compact,
      constraints: const BoxConstraints.tightFor(width: 30, height: 30),
      padding: EdgeInsets.zero,
      onPressed: onPressed,
    );
  }
}

Future<void> _shareChatAttachment(
  BuildContext context, {
  required OpenCrayHostBridge? bridge,
  required OpenCrayUiCopy copy,
  required ChatMessageAttachmentData attachment,
}) async {
  final OpenCrayHostBridge? hostBridge = bridge;
  final String localPath = attachment.localPath.trim();
  if (hostBridge == null || localPath.isEmpty) {
    return;
  }
  try {
    await hostBridge.shareWorkspaceEntries(<String>[localPath]);
  } catch (_) {
    if (!context.mounted) {
      return;
    }
    _showChatAttachmentFeedback(context, copy.chatAttachmentShareFailed);
  }
}

Future<void> _saveChatAttachment(
  BuildContext context, {
  required OpenCrayHostBridge? bridge,
  required OpenCrayUiCopy copy,
  required ChatMessageAttachmentData attachment,
}) async {
  final OpenCrayHostBridge? hostBridge = bridge;
  final String localPath = attachment.localPath.trim();
  if (hostBridge == null || localPath.isEmpty) {
    return;
  }
  try {
    final OpenCraySavedWorkspaceMediaAttachment saved = await hostBridge
        .saveWorkspaceMediaAttachment(
          relativePath: localPath,
          kind: attachment.kind.name,
        );
    if (!context.mounted) {
      return;
    }
    final String message = saved.collection == 'recordings'
        ? copy.chatAttachmentSavedToRecordings
        : copy.chatAttachmentSavedToDownloads;
    _showChatAttachmentFeedback(context, message);
  } catch (_) {
    if (!context.mounted) {
      return;
    }
    _showChatAttachmentFeedback(context, copy.chatAttachmentSaveFailed);
  }
}

void _showChatAttachmentFeedback(BuildContext context, String message) {
  ScaffoldMessenger.maybeOf(context)
    ?..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message)));
}

class _ChatVoiceWaveform extends StatelessWidget {
  const _ChatVoiceWaveform({
    super.key,
    required this.bars,
    required this.progress,
    required this.playedColor,
    required this.unplayedColor,
    this.onTapSeek,
    this.onDragStart,
    this.onDragUpdate,
    this.onDragEnd,
  });

  final List<int> bars;
  final double progress;
  final Color playedColor;
  final Color unplayedColor;
  final ValueChanged<double>? onTapSeek;
  final ValueChanged<double>? onDragStart;
  final ValueChanged<double>? onDragUpdate;
  final VoidCallback? onDragEnd;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final double width = constraints.maxWidth.isFinite
            ? constraints.maxWidth
            : 0;
        double fractionFor(Offset localPosition) {
          if (width <= 0) {
            return 0;
          }
          return (localPosition.dx / width).clamp(0.0, 1.0);
        }

        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapDown: onTapSeek == null
              ? null
              : (details) => onTapSeek!(fractionFor(details.localPosition)),
          onHorizontalDragStart: onDragStart == null
              ? null
              : (details) => onDragStart!(fractionFor(details.localPosition)),
          onHorizontalDragUpdate: onDragUpdate == null
              ? null
              : (details) => onDragUpdate!(fractionFor(details.localPosition)),
          onHorizontalDragEnd: onDragEnd == null ? null : (_) => onDragEnd!(),
          child: SizedBox(
            height: 32,
            width: double.infinity,
            child: CustomPaint(
              painter: _ChatVoiceWaveformPainter(
                bars: bars,
                progress: progress,
                playedColor: playedColor,
                unplayedColor: unplayedColor,
              ),
            ),
          ),
        );
      },
    );
  }
}

class _ChatVoiceWaveformPainter extends CustomPainter {
  const _ChatVoiceWaveformPainter({
    required this.bars,
    required this.progress,
    required this.playedColor,
    required this.unplayedColor,
  });

  final List<int> bars;
  final double progress;
  final Color playedColor;
  final Color unplayedColor;

  @override
  void paint(Canvas canvas, Size size) {
    if (bars.isEmpty || size.width <= 0 || size.height <= 0) {
      return;
    }
    final double spacing = bars.length >= 40 ? 1.5 : 2.0;
    final double totalSpacing =
        spacing * math.max(0, bars.length - 1).toDouble();
    final double barWidth = (size.width - totalSpacing) / bars.length;
    if (barWidth <= 0) {
      return;
    }
    final Paint playedPaint = Paint()..color = playedColor;
    final Paint unplayedPaint = Paint()..color = unplayedColor;
    final double playedCutoff = (size.width * progress.clamp(0.0, 1.0))
        .clamp(0.0, size.width)
        .toDouble();
    double x = 0;
    for (final int value in bars) {
      final double normalized = (value.clamp(0, 100)) / 100.0;
      final double barHeight = math
          .max(4.0, size.height * math.max(0.16, normalized))
          .toDouble();
      final double top = (size.height - barHeight) / 2;
      final RRect barRect = RRect.fromRectAndRadius(
        Rect.fromLTWH(x, top, barWidth, barHeight),
        Radius.circular(barWidth / 2),
      );
      final double barCenter = x + (barWidth / 2);
      canvas.drawRRect(
        barRect,
        barCenter <= playedCutoff ? playedPaint : unplayedPaint,
      );
      x += barWidth + spacing;
    }
    final double handleX = playedCutoff.clamp(0.0, size.width).toDouble();
    canvas.drawCircle(
      Offset(handleX, size.height / 2),
      3.5,
      Paint()..color = playedColor,
    );
  }

  @override
  bool shouldRepaint(covariant _ChatVoiceWaveformPainter oldDelegate) =>
      oldDelegate.bars != bars ||
      oldDelegate.progress != progress ||
      oldDelegate.playedColor != playedColor ||
      oldDelegate.unplayedColor != unplayedColor;
}

bool _shouldCollapseVoiceTranscript(String text) =>
    text.length > 72 || text.contains('\n');

const List<int> _chatFallbackVoiceWaveformBars = <int>[
  20,
  34,
  28,
  44,
  36,
  58,
  42,
  52,
  38,
  48,
  34,
  46,
  30,
  40,
  26,
  36,
  24,
  32,
];

const int _chatVoiceChildInteractionSuppressionWindowMs = 250;

String _chatAttachmentDetailText(ChatMessageAttachmentData attachment) {
  final String kindLabel = switch (attachment.kind) {
    ChatAttachmentKind.image => 'Image',
    ChatAttachmentKind.voice => 'Voice',
    ChatAttachmentKind.file => 'File',
  };
  final List<String> parts = <String>[kindLabel];
  final String extension = attachment.displayName.contains('.')
      ? attachment.displayName.split('.').last.toUpperCase()
      : '';
  if (extension.isNotEmpty) {
    parts.add(extension);
  }
  if (attachment.sizeBytes != null && attachment.sizeBytes! >= 0) {
    parts.add(_formatAttachmentBytes(attachment.sizeBytes!));
  }
  if (attachment.durationMs != null && attachment.durationMs! > 0) {
    parts.add(_formatAttachmentDuration(attachment.durationMs!));
  }
  return parts.join(' · ');
}

String _formatAttachmentBytes(int sizeBytes) {
  const List<String> units = <String>['B', 'KB', 'MB', 'GB', 'TB'];
  double value = sizeBytes.toDouble();
  int unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  final String text = value >= 10 || unitIndex == 0
      ? value.toStringAsFixed(0)
      : value.toStringAsFixed(1);
  return '$text ${units[unitIndex]}';
}

String _formatAttachmentDuration(int durationMs) {
  final Duration duration = Duration(milliseconds: durationMs);
  final int minutes = duration.inMinutes;
  final int seconds = duration.inSeconds.remainder(60);
  return '${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
}

Future<void> _showChatTextPreviewDialog(
  BuildContext context,
  OpenCrayFileTextPreview preview, {
  OpenCrayHostBridge? bridge,
}) {
  return _showChatPreviewDialog(
    context,
    builder: (dialogContext) =>
        _ChatTextPreviewDialog(preview: preview, bridge: bridge),
  );
}

Future<void> _showChatPreviewDialog(
  BuildContext context, {
  required WidgetBuilder builder,
  Color barrierColor = const Color(0x8A0B0E14),
}) {
  return showGeneralDialog<void>(
    context: context,
    barrierDismissible: true,
    barrierLabel: MaterialLocalizations.of(context).modalBarrierDismissLabel,
    barrierColor: barrierColor,
    transitionDuration: OpenCrayMotion.resolve(context, OpenCrayMotion.panel),
    pageBuilder:
        (
          BuildContext dialogContext,
          Animation<double> animation,
          Animation<double> secondaryAnimation,
        ) => builder(dialogContext),
    transitionBuilder:
        (
          BuildContext dialogContext,
          Animation<double> animation,
          Animation<double> secondaryAnimation,
          Widget child,
        ) {
          final bool reduce = OpenCrayMotion.reduce(dialogContext);
          final Animation<double> curvedAnimation = CurvedAnimation(
            parent: animation,
            curve: OpenCrayMotion.enter,
            reverseCurve: OpenCrayMotion.exit,
          );
          final Widget faded = FadeTransition(
            opacity: curvedAnimation,
            child: child,
          );
          if (reduce) {
            return faded;
          }
          return SlideTransition(
            position: Tween<Offset>(
              begin: const Offset(0, 0.03),
              end: Offset.zero,
            ).animate(curvedAnimation),
            child: faded,
          );
        },
  );
}

bool _isPreviewableTextAttachment(ChatMessageAttachmentData attachment) {
  final String normalizedMimeType =
      attachment.mimeType?.trim().toLowerCase() ?? '';
  if (normalizedMimeType.startsWith('text/') ||
      _chatPreviewableTextMimeTypes.contains(normalizedMimeType)) {
    return true;
  }
  return _isPreviewableTextFileName(attachment.displayName);
}

bool _isPreviewableTextRelativePath(String relativePath) =>
    _isPreviewableTextFileName(_chatRelativePathFileName(relativePath));

bool _isPreviewableTextFileName(String fileName) {
  final String normalizedName = fileName.trim().toLowerCase();
  if (normalizedName.isEmpty) {
    return false;
  }
  if (_chatPreviewableTextFileNames.contains(normalizedName)) {
    return true;
  }
  final String extension = normalizedName.contains('.')
      ? normalizedName.split('.').last
      : '';
  return _chatPreviewableTextExtensions.contains(extension);
}

bool _isWorkspaceRelativeChatLink(Uri? uri, String href) {
  if (href.trim().isEmpty) {
    return false;
  }
  if (uri != null && uri.hasScheme) {
    return false;
  }
  final String normalizedPath = _normalizeChatWorkspaceRelativePath(href);
  return normalizedPath.isNotEmpty && !normalizedPath.startsWith('/');
}

String _normalizeChatWorkspaceRelativePath(String href) {
  final String trimmed = href.trim();
  if (trimmed.isEmpty) {
    return '';
  }
  final Uri? uri = Uri.tryParse(trimmed);
  final String path = uri != null && !uri.hasScheme ? uri.path : trimmed;
  return _safeDecodeChatLinkPath(path).replaceAll('\\', '/').trim();
}

String _chatRelativePathFileName(String relativePath) {
  final String normalizedPath = _normalizeChatWorkspaceRelativePath(
    relativePath,
  );
  if (normalizedPath.isEmpty) {
    return '';
  }
  final int slashIndex = normalizedPath.lastIndexOf('/');
  if (slashIndex < 0) {
    return normalizedPath;
  }
  return normalizedPath.substring(slashIndex + 1);
}

String _safeDecodeChatLinkPath(String value) {
  try {
    return Uri.decodeFull(value);
  } on FormatException {
    return value;
  }
}

MarkdownStyleSheet _chatTextPreviewMarkdownStyleSheet(BuildContext context) {
  final MarkdownStyleSheet base = MarkdownStyleSheet.fromTheme(
    Theme.of(context),
  );
  final Color linkColor = Theme.of(context).colorScheme.primary;
  return base.copyWith(
    a: TextStyle(
      fontSize: 13,
      height: 1.5,
      fontWeight: FontWeight.w600,
      color: linkColor,
      decoration: TextDecoration.underline,
      decorationColor: linkColor.withValues(alpha: 0.75),
    ),
    p: const TextStyle(
      fontSize: 13,
      height: 1.5,
      color: _ChatPalette.textPrimary,
    ),
    h1: const TextStyle(
      fontSize: 23,
      height: 1.2,
      fontWeight: FontWeight.w700,
      color: _ChatPalette.textPrimary,
    ),
    h2: const TextStyle(
      fontSize: 19,
      height: 1.24,
      fontWeight: FontWeight.w700,
      color: _ChatPalette.textPrimary,
    ),
    h3: const TextStyle(
      fontSize: 16,
      height: 1.3,
      fontWeight: FontWeight.w700,
      color: _ChatPalette.textPrimary,
    ),
    listBullet: const TextStyle(
      fontSize: 13,
      height: 1.5,
      color: _ChatPalette.textPrimary,
    ),
    code: const TextStyle(
      fontSize: 12.5,
      height: 1.45,
      fontFamily: 'monospace',
      color: _ChatPalette.textPrimary,
    ),
    codeblockPadding: const EdgeInsets.all(12),
    codeblockDecoration: BoxDecoration(
      color: OpenCrayColors.surfaceMuted,
      borderRadius: BorderRadius.circular(12),
    ),
    blockSpacing: 14,
    blockquote: const TextStyle(
      fontSize: 12.5,
      height: 1.5,
      color: _ChatPalette.textSecondary,
    ),
    blockquoteDecoration: BoxDecoration(
      color: OpenCrayColors.surfaceMuted,
      borderRadius: BorderRadius.circular(12),
      border: const Border(
        left: BorderSide(color: _ChatPalette.border, width: 3),
      ),
    ),
    horizontalRuleDecoration: const BoxDecoration(
      border: Border(top: BorderSide(color: _ChatPalette.border, width: 1)),
    ),
  );
}

class _ChatTextPreviewDialog extends StatelessWidget {
  const _ChatTextPreviewDialog({required this.preview, this.bridge});

  final OpenCrayFileTextPreview preview;
  final OpenCrayHostBridge? bridge;

  Widget _buildMarkdownSelectionContextMenu(
    BuildContext context,
    SelectableRegionState selectableRegionState,
    OpenCrayMarkdownSelectionSnapshot? selection,
    String markdown,
  ) {
    final List<ContextMenuButtonItem> buttonItems = selectableRegionState
        .contextMenuButtonItems
        .map((item) {
          if (item.type != ContextMenuButtonType.copy) {
            return item;
          }
          return item.copyWith(
            onPressed: () async {
              final OpenCrayMarkdownClipboardPayload? payload =
                  openCrayBuildMarkdownSelectionClipboardPayload(
                    markdown,
                    selectedText: selection?.plainText ?? '',
                    selectionStartOffset: selection?.range?.startOffset,
                    selectionEndOffset: selection?.range?.endOffset,
                  );
              if (payload == null) {
                item.onPressed?.call();
                return;
              }
              final OpenCrayHostBridge? hostBridge = bridge;
              if (hostBridge == null) {
                await Clipboard.setData(ClipboardData(text: payload.plainText));
              } else {
                await hostBridge.copyRichTextToClipboard(
                  plainText: payload.plainText,
                  htmlText: payload.htmlText,
                );
              }
              openCrayFinalizeSelectionCopyUi(selectableRegionState);
            },
          );
        })
        .toList(growable: false);
    return AdaptiveTextSelectionToolbar.buttonItems(
      anchors: selectableRegionState.contextMenuAnchors,
      buttonItems: buttonItems,
    );
  }

  @override
  Widget build(BuildContext context) {
    final String content = preview.isTruncated
        ? '${preview.content}\n\n...'
        : preview.content;
    return Dialog(
      key: const ValueKey<String>('chat-text-preview-dialog'),
      insetPadding: const EdgeInsets.all(20),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560, maxHeight: 560),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 16, 18, 18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      preview.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: _ChatPalette.textPrimary,
                      ),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close_rounded),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Expanded(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: OpenCrayColors.surfaceSubtle,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: _ChatPalette.border),
                  ),
                  child: Scrollbar(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(14),
                      child: openCrayIsMarkdownFileName(preview.name)
                          ? OpenCraySelectableMarkdownBody(
                              key: const ValueKey<String>(
                                'chat-text-preview-markdown',
                              ),
                              data: content,
                              hostBridge: bridge,
                              documentRelativePath: preview.relativePath,
                              latexTextStyle: const TextStyle(
                                fontSize: 13,
                                height: 1.5,
                                color: _ChatPalette.textPrimary,
                              ),
                              styleSheet: _chatTextPreviewMarkdownStyleSheet(
                                context,
                              ),
                              imageBackgroundColor: OpenCrayColors.surfaceMuted,
                              imageBorderColor: _ChatPalette.border,
                              contextMenuBuilder:
                                  (
                                    BuildContext context,
                                    SelectableRegionState selectableRegionState,
                                    OpenCrayMarkdownSelectionSnapshot?
                                    selection,
                                  ) => _buildMarkdownSelectionContextMenu(
                                    context,
                                    selectableRegionState,
                                    selection,
                                    content,
                                  ),
                            )
                          : SelectionArea(
                              child: Text(
                                content,
                                style: const TextStyle(
                                  fontSize: 13,
                                  height: 1.5,
                                  color: _ChatPalette.textPrimary,
                                ),
                              ),
                            ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

const Set<String> _chatPreviewableTextFileNames = <String>{
  '.env',
  '.gitattributes',
  '.gitignore',
  'gradlew',
  'gradlew.bat',
  'license',
  'makefile',
  'readme',
  'readme.md',
};

const Set<String> _chatPreviewableTextExtensions = <String>{
  'bash',
  'conf',
  'config',
  'css',
  'csv',
  'dart',
  'htm',
  'html',
  'ini',
  'java',
  'js',
  'json',
  'jsx',
  'kt',
  'kts',
  'log',
  'markdown',
  'md',
  'properties',
  'py',
  'scss',
  'sh',
  'sql',
  'toml',
  'ts',
  'tsx',
  'txt',
  'xml',
  'yaml',
  'yml',
  'zsh',
};

const Set<String> _chatPreviewableTextMimeTypes = <String>{
  'application/json',
  'application/xml',
  'application/x-yaml',
};

const int _chatComposerMaxImageAttachments = 9;

class _ComposerCard extends StatelessWidget {
  const _ComposerCard({
    required this.copy,
    required this.state,
    required this.bridge,
    required this.controller,
    required this.focusNode,
    required this.onPlusPressed,
    required this.onSendPressed,
    required this.interruptTrace,
    required this.interruptConfirmRunId,
    required this.busyInterruptRunIds,
    required this.onArmInterruptRunTrace,
    required this.onDismissInterruptRunTrace,
    required this.onInterruptRunTrace,
    required this.onAddActionSelected,
    required this.onCommandSelected,
    required this.onAttachmentRemoved,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final OpenCrayHostBridge? bridge;
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback onPlusPressed;
  final VoidCallback onSendPressed;
  final ChatRunTraceData? interruptTrace;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final ValueChanged<ChatRunTraceData> onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onInterruptRunTrace;
  final ValueChanged<ChatAddActionData> onAddActionSelected;
  final VoidCallback onCommandSelected;
  final ValueChanged<ChatAttachmentData> onAttachmentRemoved;

  @override
  Widget build(BuildContext context) {
    final bool hasTodos = state.composer.todos.isNotEmpty;
    final bool hasCommands = state.composer.commandOptions.isNotEmpty;
    final bool hasPersistentIntegratedSurface =
        hasCommands || state.composer.attachments.isNotEmpty;

    return TweenAnimationBuilder<double>(
      tween: Tween<double>(end: state.composer.showAddMenu ? 1 : 0),
      duration: OpenCrayMotion.resolve(
        context,
        state.composer.showAddMenu
            ? OpenCrayMotion.expand
            : OpenCrayMotion.quick,
      ),
      curve: OpenCrayMotion.expandCurve,
      builder: (context, addMenuProgress, child) {
        final double surfaceProgress = hasPersistentIntegratedSurface
            ? 1
            : addMenuProgress;
        final Widget content = Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            if (hasTodos) ...<Widget>[
              _TodoListPanel(todos: state.composer.todos),
              const SizedBox(height: 12),
            ],
            _ComposerSecondarySection(
              sectionKey: 'commands',
              isVisible: hasCommands,
              bottomGap: 10,
              child: hasCommands
                  ? Container(
                      decoration: BoxDecoration(
                        color: _ChatPalette.subtleSurface,
                        borderRadius: BorderRadius.circular(14),
                      ),
                      padding: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Text(
                            copy.chatCommands,
                            style: _ChatTextStyles.commandsLabel,
                          ),
                          const SizedBox(height: 8),
                          ...state.composer.commandOptions.map(
                            (ChatCommandOptionData option) =>
                                _CommandOptionTile(
                                  option: option,
                                  onPressed: onCommandSelected,
                                ),
                          ),
                        ],
                      ),
                    )
                  : const SizedBox.shrink(),
            ),
            _ComposerAttachmentSection(
              attachments: state.composer.attachments,
              bridge: bridge,
              onAttachmentRemoved: onAttachmentRemoved,
            ),
            AnimatedBuilder(
              animation: controller,
              builder: (BuildContext context, Widget? child) {
                final bool hasSendableContent =
                    controller.text.trim().isNotEmpty ||
                    state.composer.attachments.isNotEmpty;
                final ChatRunTraceData? effectiveInterruptTrace =
                    state.isInputEnabled && !hasSendableContent
                    ? interruptTrace
                    : null;
                final bool showInterruptConfirm =
                    effectiveInterruptTrace != null &&
                    interruptConfirmRunId ==
                        effectiveInterruptTrace.interruptId;
                if (showInterruptConfirm) {
                  return _ComposerInterruptConfirmSurface(
                    copy: copy,
                    trace: effectiveInterruptTrace,
                    isBusy: busyInterruptRunIds.contains(
                      effectiveInterruptTrace.interruptId,
                    ),
                    onDismiss: () =>
                        onDismissInterruptRunTrace(effectiveInterruptTrace),
                    onConfirmed: () =>
                        onInterruptRunTrace(effectiveInterruptTrace),
                  );
                }
                return _InputRow(
                  placeholder: state.composer.placeholder,
                  controller: controller,
                  focusNode: focusNode,
                  enabled: state.isInputEnabled,
                  surfaceProgress: surfaceProgress,
                  defaultGlassProgress: hasTodos ? 0 : 1 - surfaceProgress,
                  plusProgress: addMenuProgress,
                  interruptTrace: effectiveInterruptTrace,
                  isInterruptBusy:
                      effectiveInterruptTrace != null &&
                      busyInterruptRunIds.contains(
                        effectiveInterruptTrace.interruptId,
                      ),
                  onInterruptPressed: effectiveInterruptTrace == null
                      ? null
                      : () => onArmInterruptRunTrace(effectiveInterruptTrace),
                  onPlusPressed: onPlusPressed,
                  onSendPressed: onSendPressed,
                );
              },
            ),
            _ComposerAddTray(
              progress: addMenuProgress,
              isOpen: state.composer.showAddMenu,
              copy: copy,
              actions: state.composer.addActions,
              onAddActionSelected: onAddActionSelected,
            ),
          ],
        );

        final Widget surface;
        if (hasTodos) {
          surface = _ComposerGlassSurface(
            child: Padding(padding: const EdgeInsets.all(12), child: content),
          );
        } else {
          surface = _ComposerMaterialSurface(
            progress: surfaceProgress,
            child: content,
          );
        }

        return AnimatedSize(
          duration: OpenCrayMotion.resolve(context, OpenCrayMotion.expand),
          curve: OpenCrayMotion.expandCurve,
          alignment: Alignment.bottomCenter,
          child: surface,
        );
      },
    );
  }
}

class _ComposerSecondarySection extends StatefulWidget {
  const _ComposerSecondarySection({
    required this.sectionKey,
    required this.isVisible,
    required this.child,
    this.bottomGap = 0,
    this.updateChildDuringExit = false,
  });

  final String sectionKey;
  final bool isVisible;
  final Widget child;
  final double bottomGap;
  final bool updateChildDuringExit;

  @override
  State<_ComposerSecondarySection> createState() =>
      _ComposerSecondarySectionState();
}

class _ComposerSecondarySectionState extends State<_ComposerSecondarySection> {
  Widget? _motionChild;
  int _exitEpoch = 0;

  @override
  void initState() {
    super.initState();
    if (widget.isVisible) {
      _motionChild = widget.child;
    }
  }

  @override
  void didUpdateWidget(covariant _ComposerSecondarySection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isVisible) {
      _exitEpoch += 1;
      _motionChild = widget.child;
      return;
    }
    if (widget.updateChildDuringExit) {
      _motionChild = widget.child;
    }
    if (oldWidget.isVisible && _motionChild != null) {
      _scheduleClearAfterExit();
    }
  }

  @override
  Widget build(BuildContext context) {
    final Widget? child = widget.isVisible ? widget.child : _motionChild;
    if (child == null) {
      return SizedBox.shrink(
        key: ValueKey<String>('chat-composer-${widget.sectionKey}-empty'),
      );
    }
    final bool reduce = OpenCrayMotion.reduce(context);
    final double target = widget.isVisible ? 1 : 0;
    return TweenAnimationBuilder<double>(
      key: ValueKey<String>('chat-composer-${widget.sectionKey}-section'),
      tween: Tween<double>(end: target),
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.expand),
      curve: OpenCrayMotion.expandCurve,
      child: Padding(
        padding: EdgeInsets.only(bottom: widget.bottomGap),
        child: child,
      ),
      builder: (BuildContext context, double value, Widget? child) {
        final double t = reduce ? target : value.clamp(0.0, 1.0).toDouble();
        return ClipRect(
          child: Align(
            alignment: Alignment.bottomCenter,
            heightFactor: t,
            child: IgnorePointer(
              ignoring: !widget.isVisible,
              child: Opacity(
                opacity: reduce
                    ? target
                    : (t * 1.18).clamp(0.0, 1.0).toDouble(),
                child: Transform.translate(
                  offset: Offset(0, (1 - t) * 6),
                  child: child,
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  void _scheduleClearAfterExit() {
    _exitEpoch += 1;
    final int epoch = _exitEpoch;
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.expand,
    );
    if (duration == Duration.zero || _isAutomatedWidgetTest) {
      if (mounted && epoch == _exitEpoch) {
        setState(() {
          _motionChild = null;
        });
      }
      return;
    }
    Future<void>.delayed(duration, () {
      if (!mounted || widget.isVisible || epoch != _exitEpoch) {
        return;
      }
      setState(() {
        _motionChild = null;
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

class _ComposerAttachmentSection extends StatelessWidget {
  const _ComposerAttachmentSection({
    required this.attachments,
    required this.bridge,
    required this.onAttachmentRemoved,
  });

  final List<ChatAttachmentData> attachments;
  final OpenCrayHostBridge? bridge;
  final ValueChanged<ChatAttachmentData> onAttachmentRemoved;

  @override
  Widget build(BuildContext context) {
    return _ComposerSecondarySection(
      sectionKey: 'attachments',
      isVisible: attachments.isNotEmpty,
      bottomGap: 10,
      updateChildDuringExit: true,
      child: _ComposerAttachmentStrip(
        key: const ValueKey<String>('chat-composer-attachment-strip'),
        attachments: attachments,
        bridge: bridge,
        onAttachmentRemoved: onAttachmentRemoved,
      ),
    );
  }
}

class _ComposerAttachmentStrip extends StatefulWidget {
  const _ComposerAttachmentStrip({
    super.key,
    required this.attachments,
    required this.bridge,
    required this.onAttachmentRemoved,
  });

  final List<ChatAttachmentData> attachments;
  final OpenCrayHostBridge? bridge;
  final ValueChanged<ChatAttachmentData> onAttachmentRemoved;

  @override
  State<_ComposerAttachmentStrip> createState() =>
      _ComposerAttachmentStripState();
}

class _ComposerAttachmentStripState extends State<_ComposerAttachmentStrip> {
  final Map<String, ChatAttachmentData> _exitingAttachments =
      <String, ChatAttachmentData>{};
  final Map<String, int> _exitEpochByAttachment = <String, int>{};

  @override
  void didUpdateWidget(covariant _ComposerAttachmentStrip oldWidget) {
    super.didUpdateWidget(oldWidget);
    final Set<String> currentIds = widget.attachments
        .map((ChatAttachmentData attachment) => attachment.id)
        .toSet();
    for (final ChatAttachmentData attachment in oldWidget.attachments) {
      if (!currentIds.contains(attachment.id)) {
        _exitingAttachments[attachment.id] = attachment;
        _scheduleAttachmentExitClear(attachment.id);
      }
    }
    for (final ChatAttachmentData attachment in widget.attachments) {
      _exitingAttachments.remove(attachment.id);
      _exitEpochByAttachment.remove(attachment.id);
    }
  }

  @override
  Widget build(BuildContext context) {
    final Set<String> currentIds = widget.attachments
        .map((ChatAttachmentData attachment) => attachment.id)
        .toSet();
    final List<_ComposerAttachmentStripEntry> entries =
        <_ComposerAttachmentStripEntry>[
          ...widget.attachments.map(
            (ChatAttachmentData attachment) => _ComposerAttachmentStripEntry(
              attachment: attachment,
              isPresent: true,
            ),
          ),
          ..._exitingAttachments.values
              .where(
                (ChatAttachmentData attachment) =>
                    !currentIds.contains(attachment.id),
              )
              .map(
                (ChatAttachmentData attachment) =>
                    _ComposerAttachmentStripEntry(
                      attachment: attachment,
                      isPresent: false,
                    ),
              ),
        ];
    if (entries.isEmpty) {
      return const SizedBox.shrink(
        key: ValueKey<String>('chat-composer-attachments-empty'),
      );
    }
    return SizedBox(
      key: const ValueKey<String>('chat-composer-attachments'),
      height: 68,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: EdgeInsets.zero,
        physics: const BouncingScrollPhysics(),
        itemCount: entries.length,
        separatorBuilder: (BuildContext context, int index) =>
            const SizedBox(width: 8),
        itemBuilder: (BuildContext context, int index) {
          final _ComposerAttachmentStripEntry entry = entries[index];
          return _ComposerAttachmentCardMotion(
            key: ValueKey<String>(
              'chat-composer-attachment-motion-${entry.attachment.id}',
            ),
            isPresent: entry.isPresent,
            child: _AttachmentCard(
              attachment: entry.attachment,
              bridge: widget.bridge,
              onRemove: entry.isPresent
                  ? () => widget.onAttachmentRemoved(entry.attachment)
                  : () {},
            ),
          );
        },
      ),
    );
  }

  void _scheduleAttachmentExitClear(String attachmentId) {
    final int epoch = (_exitEpochByAttachment[attachmentId] ?? 0) + 1;
    _exitEpochByAttachment[attachmentId] = epoch;
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.expand,
    );
    if (duration == Duration.zero || _isAutomatedWidgetTest) {
      _exitingAttachments.remove(attachmentId);
      _exitEpochByAttachment.remove(attachmentId);
      return;
    }
    Future<void>.delayed(duration, () {
      if (!mounted || _exitEpochByAttachment[attachmentId] != epoch) {
        return;
      }
      final bool isCurrent = widget.attachments.any(
        (ChatAttachmentData attachment) => attachment.id == attachmentId,
      );
      if (isCurrent) {
        return;
      }
      setState(() {
        _exitingAttachments.remove(attachmentId);
        _exitEpochByAttachment.remove(attachmentId);
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

class _ComposerAttachmentStripEntry {
  const _ComposerAttachmentStripEntry({
    required this.attachment,
    required this.isPresent,
  });

  final ChatAttachmentData attachment;
  final bool isPresent;
}

class _ComposerAttachmentCardMotion extends StatefulWidget {
  const _ComposerAttachmentCardMotion({
    super.key,
    required this.isPresent,
    required this.child,
  });

  final bool isPresent;
  final Widget child;

  @override
  State<_ComposerAttachmentCardMotion> createState() =>
      _ComposerAttachmentCardMotionState();
}

class _ComposerAttachmentCardMotionState
    extends State<_ComposerAttachmentCardMotion> {
  late bool _isPresent = widget.isPresent;

  @override
  void initState() {
    super.initState();
    if (widget.isPresent && !_isAutomatedWidgetTest) {
      _isPresent = false;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          return;
        }
        setState(() {
          _isPresent = true;
        });
      });
    }
  }

  @override
  void didUpdateWidget(covariant _ComposerAttachmentCardMotion oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.isPresent != widget.isPresent) {
      setState(() {
        _isPresent = widget.isPresent;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final Duration duration = OpenCrayMotion.resolve(
      context,
      _isPresent ? OpenCrayMotion.expand : OpenCrayMotion.quick,
    );
    final Curve curve = _isPresent ? OpenCrayMotion.enter : OpenCrayMotion.exit;
    return AnimatedSlide(
      offset: _isPresent ? Offset.zero : const Offset(0, 0.14),
      duration: duration,
      curve: curve,
      child: AnimatedOpacity(
        opacity: _isPresent ? 1 : 0,
        duration: duration,
        curve: curve,
        child: IgnorePointer(ignoring: !_isPresent, child: widget.child),
      ),
    );
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

class _ComposerMaterialSurface extends StatelessWidget {
  const _ComposerMaterialSurface({required this.progress, required this.child});

  final double progress;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final double t = progress.clamp(0.0, 1.0).toDouble();
    return DecoratedBox(
      key: const ValueKey<String>('chat-composer-material-surface'),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: t),
        borderRadius: BorderRadius.circular(16 + (8 * t)),
        border: Border.all(color: _ChatPalette.border.withValues(alpha: t)),
        boxShadow: t == 0
            ? const <BoxShadow>[]
            : <BoxShadow>[
                BoxShadow(
                  color: const Color(0xFF0D1B2A).withValues(alpha: 0.05 * t),
                  blurRadius: 18 * t,
                  offset: Offset(0, 5 * t),
                ),
              ],
      ),
      child: Padding(padding: EdgeInsets.all(10 * t), child: child),
    );
  }
}

class _ComposerAddTray extends StatelessWidget {
  const _ComposerAddTray({
    required this.progress,
    required this.isOpen,
    required this.copy,
    required this.actions,
    required this.onAddActionSelected,
  });

  final double progress;
  final bool isOpen;
  final OpenCrayUiCopy copy;
  final List<ChatAddActionData> actions;
  final ValueChanged<ChatAddActionData> onAddActionSelected;

  @override
  Widget build(BuildContext context) {
    if (!isOpen && progress <= 0) {
      return const SizedBox.shrink(
        key: ValueKey<String>('chat-composer-add-menu-empty'),
      );
    }
    final double t = progress.clamp(0.0, 1.0).toDouble();
    return ClipRect(
      key: const ValueKey<String>('chat-composer-add-tray'),
      child: Align(
        alignment: Alignment.topCenter,
        heightFactor: OpenCrayMotion.reduce(context) ? (isOpen ? 1 : 0) : t,
        child: Opacity(
          opacity: OpenCrayMotion.reduce(context)
              ? (isOpen ? 1 : 0)
              : (t * 1.2).clamp(0.0, 1.0).toDouble(),
          child: IgnorePointer(
            ignoring: !isOpen,
            child: _ComposerAddMenu(
              key: const ValueKey<String>('chat-composer-add-menu'),
              copy: copy,
              actions: actions,
              onAddActionSelected: onAddActionSelected,
            ),
          ),
        ),
      ),
    );
  }
}

class _ComposerAddMenu extends StatelessWidget {
  const _ComposerAddMenu({
    super.key,
    required this.copy,
    required this.actions,
    required this.onAddActionSelected,
  });

  final OpenCrayUiCopy copy;
  final List<ChatAddActionData> actions;
  final ValueChanged<ChatAddActionData> onAddActionSelected;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(copy.chatAddToMessage, style: _ChatTextStyles.sectionLabel),
          const SizedBox(height: 10),
          Row(
            children: actions
                .map(
                  (ChatAddActionData action) => Expanded(
                    flex: action.label == copy.chatActionCommand ? 12 : 9,
                    child: Padding(
                      padding: EdgeInsets.only(
                        right: action == actions.last ? 0 : 8,
                      ),
                      child: _AddActionPill(
                        action: action,
                        onPressed: () => onAddActionSelected(action),
                      ),
                    ),
                  ),
                )
                .toList(),
          ),
        ],
      ),
    );
  }
}

class _ComposerInterruptConfirmSurface extends StatefulWidget {
  const _ComposerInterruptConfirmSurface({
    required this.copy,
    required this.trace,
    required this.isBusy,
    required this.onDismiss,
    required this.onConfirmed,
  });

  final OpenCrayUiCopy copy;
  final ChatRunTraceData trace;
  final bool isBusy;
  final VoidCallback onDismiss;
  final VoidCallback onConfirmed;

  @override
  State<_ComposerInterruptConfirmSurface> createState() =>
      _ComposerInterruptConfirmSurfaceState();
}

class _ComposerInterruptConfirmSurfaceState
    extends State<_ComposerInterruptConfirmSurface> {
  bool _outsideDismissReady = false;

  @override
  void initState() {
    super.initState();
    _scheduleOutsideDismissReady();
  }

  @override
  void didUpdateWidget(covariant _ComposerInterruptConfirmSurface oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.trace.interruptId != widget.trace.interruptId) {
      _outsideDismissReady = false;
      _scheduleOutsideDismissReady();
    }
  }

  void _scheduleOutsideDismissReady() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _outsideDismissReady = true;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return TapRegion(
      onTapOutside: _outsideDismissReady ? (_) => widget.onDismiss() : null,
      child: _RunTraceInterruptConfirmRow(
        key: ValueKey<String>(
          'chat-composer-interrupt-confirm-${widget.trace.interruptId}',
        ),
        copy: widget.copy,
        runId: widget.trace.interruptId,
        isBusy: widget.isBusy,
        onConfirmed: widget.onConfirmed,
      ),
    );
  }
}

class _ComposerGlassSurface extends StatelessWidget {
  const _ComposerGlassSurface({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(24),
      child: Stack(
        children: <Widget>[
          Positioned.fill(
            child: IgnorePointer(
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
                child: DecoratedBox(
                  key: const ValueKey<String>('chat-composer-todo-surface'),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(
                      color: Colors.white.withValues(alpha: 0.42),
                    ),
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: <Color>[
                        Colors.white.withValues(alpha: 0.34),
                        Colors.white.withValues(alpha: 0.22),
                        OpenCrayColors.primaryTint.withValues(alpha: 0.18),
                        OpenCrayColors.primaryBorder.withValues(alpha: 0.12),
                      ],
                      stops: const <double>[0, 0.28, 0.72, 1],
                    ),
                  ),
                  child: const SizedBox.expand(),
                ),
              ),
            ),
          ),
          child,
        ],
      ),
    );
  }
}

class _TodoListPanel extends StatefulWidget {
  const _TodoListPanel({required this.todos});

  static const int _maxVisibleTodoCount = 4;
  static const double _itemHeight = 28;
  static const double _itemGap = 6;

  final List<ChatTodoItemData> todos;

  @override
  State<_TodoListPanel> createState() => _TodoListPanelState();
}

class _TodoListPanelState extends State<_TodoListPanel> {
  bool _isExpanded = true;

  @override
  Widget build(BuildContext context) {
    final int visibleCount = math.min(
      widget.todos.length,
      _TodoListPanel._maxVisibleTodoCount,
    );
    final double listHeight =
        (visibleCount * _TodoListPanel._itemHeight) +
        (visibleCount > 0 ? (visibleCount - 1) * _TodoListPanel._itemGap : 0);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Row(
          children: <Widget>[
            Text('TODO', style: _ChatTextStyles.todoLabel),
            const Spacer(),
            GestureDetector(
              key: const ValueKey<String>('chat-composer-todo-chevron'),
              behavior: HitTestBehavior.opaque,
              onTap: () {
                setState(() {
                  _isExpanded = !_isExpanded;
                });
              },
              child: SizedBox.square(
                dimension: 24,
                child: Center(
                  child: AnimatedRotation(
                    duration: OpenCrayMotion.resolve(
                      context,
                      OpenCrayMotion.quick,
                    ),
                    turns: _isExpanded ? 0.5 : 0,
                    child: const Icon(
                      CupertinoIcons.chevron_up,
                      size: 13,
                      color: _ChatPalette.textTertiary,
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
        if (_isExpanded) ...<Widget>[
          const SizedBox(height: 10),
          SizedBox(
            key: const ValueKey<String>('chat-composer-todo-list'),
            height: listHeight,
            child: ListView.separated(
              padding: EdgeInsets.zero,
              physics: widget.todos.length > _TodoListPanel._maxVisibleTodoCount
                  ? const ClampingScrollPhysics()
                  : const NeverScrollableScrollPhysics(),
              itemCount: widget.todos.length,
              itemBuilder: (BuildContext context, int index) {
                return _TodoRow(todo: widget.todos[index], index: index);
              },
              separatorBuilder: (BuildContext context, int index) {
                return const SizedBox(height: _TodoListPanel._itemGap);
              },
            ),
          ),
        ],
      ],
    );
  }
}

class _TodoRow extends StatelessWidget {
  const _TodoRow({required this.todo, required this.index});

  final ChatTodoItemData todo;
  final int index;

  @override
  Widget build(BuildContext context) {
    final TextStyle style = switch (todo.status) {
      ChatTodoStatus.pending => _ChatTextStyles.todoItem,
      ChatTodoStatus.inProgress => _ChatTextStyles.todoItem.copyWith(
        color: _ChatPalette.accent,
        fontWeight: FontWeight.w600,
      ),
      ChatTodoStatus.completed => _ChatTextStyles.todoItem.copyWith(
        color: _ChatPalette.textSecondary,
        decoration: TextDecoration.lineThrough,
        decorationColor: _ChatPalette.textSecondary,
      ),
    };

    return SizedBox(
      key: ValueKey<String>('chat-composer-todo-row-$index'),
      height: _TodoListPanel._itemHeight,
      child: Row(
        children: <Widget>[
          _TodoStatusIndicator(status: todo.status, index: index),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              todo.displayText,
              key: ValueKey<String>('chat-composer-todo-text-$index'),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: style,
            ),
          ),
        ],
      ),
    );
  }
}

class _TodoStatusIndicator extends StatelessWidget {
  const _TodoStatusIndicator({required this.status, required this.index});

  final ChatTodoStatus status;
  final int index;

  @override
  Widget build(BuildContext context) {
    final BoxDecoration decoration = switch (status) {
      ChatTodoStatus.pending => BoxDecoration(
        shape: BoxShape.circle,
        color: Colors.transparent,
        border: Border.all(color: _ChatPalette.composerStroke, width: 1.3),
      ),
      ChatTodoStatus.inProgress => BoxDecoration(
        shape: BoxShape.circle,
        color: Colors.transparent,
        border: Border.all(color: _ChatPalette.accent, width: 1.3),
      ),
      ChatTodoStatus.completed => BoxDecoration(
        shape: BoxShape.circle,
        color: _ChatPalette.todoCompletedFill,
      ),
    };

    return Container(
      key: ValueKey<String>('chat-composer-todo-indicator-$index'),
      width: 10,
      height: 10,
      decoration: decoration,
    );
  }
}

class _InputRow extends StatelessWidget {
  const _InputRow({
    required this.placeholder,
    required this.controller,
    required this.focusNode,
    required this.enabled,
    required this.surfaceProgress,
    required this.defaultGlassProgress,
    required this.plusProgress,
    required this.interruptTrace,
    required this.isInterruptBusy,
    required this.onInterruptPressed,
    required this.onPlusPressed,
    required this.onSendPressed,
  });

  final String placeholder;
  final TextEditingController controller;
  final FocusNode focusNode;
  final bool enabled;
  final double surfaceProgress;
  final double defaultGlassProgress;
  final double plusProgress;
  final ChatRunTraceData? interruptTrace;
  final bool isInterruptBusy;
  final VoidCallback? onInterruptPressed;
  final VoidCallback onPlusPressed;
  final VoidCallback onSendPressed;

  @override
  Widget build(BuildContext context) {
    const BorderRadius messageFieldRadius = BorderRadius.all(
      Radius.circular(18),
    );
    const BorderRadius messageFieldInnerRadius = BorderRadius.all(
      Radius.circular(17),
    );
    const double messageFieldMinHeight = 40;
    const double messageFieldMaxHeight = 92;

    final Widget inputRow = Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: <Widget>[
        Expanded(
          child: GestureDetector(
            onTap: enabled ? () => focusNode.requestFocus() : null,
            behavior: HitTestBehavior.opaque,
            child: AnimatedBuilder(
              animation: focusNode,
              builder: (BuildContext context, Widget? child) {
                final double surfaceT = surfaceProgress
                    .clamp(0.0, 1.0)
                    .toDouble();
                final double outlineT = focusNode.hasFocus ? 1 : surfaceT;
                final Color fieldOutlineColor = focusNode.hasFocus
                    ? _ChatPalette.accent
                    : _ChatPalette.composerStroke;

                return AnimatedSize(
                  duration: OpenCrayMotion.resolve(
                    context,
                    OpenCrayMotion.expand,
                  ),
                  curve: OpenCrayMotion.expandCurve,
                  alignment: Alignment.bottomCenter,
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(
                      minHeight: messageFieldMinHeight,
                      maxHeight: messageFieldMaxHeight,
                    ),
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: Color.lerp(
                          Colors.white,
                          fieldOutlineColor,
                          outlineT,
                        ),
                        borderRadius: messageFieldRadius,
                      ),
                      child: Padding(
                        padding: EdgeInsets.all(outlineT),
                        child: ClipRRect(
                          borderRadius: BorderRadius.lerp(
                            messageFieldRadius,
                            messageFieldInnerRadius,
                            outlineT,
                          )!,
                          child: ColoredBox(
                            color: Colors.white,
                            child: Padding(
                              padding: const EdgeInsets.fromLTRB(
                                14,
                                10,
                                14,
                                10,
                              ),
                              child: TextField(
                                controller: controller,
                                focusNode: focusNode,
                                enabled: enabled,
                                onTapOutside: enabled
                                    ? (_) => focusNode.unfocus()
                                    : null,
                                minLines: 1,
                                maxLines: 4,
                                textAlignVertical: TextAlignVertical.center,
                                decoration: InputDecoration(
                                  border: InputBorder.none,
                                  enabledBorder: InputBorder.none,
                                  focusedBorder: InputBorder.none,
                                  disabledBorder: InputBorder.none,
                                  errorBorder: InputBorder.none,
                                  focusedErrorBorder: InputBorder.none,
                                  isCollapsed: true,
                                  hintText: placeholder,
                                  hintStyle: _ChatTextStyles.placeholder,
                                  contentPadding: EdgeInsets.zero,
                                ),
                                textInputAction: TextInputAction.newline,
                                onSubmitted: enabled
                                    ? (_) => onSendPressed()
                                    : null,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ),
        const SizedBox(width: 10),
        _CircleButton(
          key: const ValueKey<String>('chat-composer-plus-button'),
          backgroundColor: Color.lerp(
            Colors.white,
            _ChatPalette.plusActiveSurface,
            plusProgress.clamp(0.0, 1.0).toDouble(),
          )!,
          foregroundColor: Color.lerp(
            _ChatPalette.textSecondary,
            _ChatPalette.accent,
            plusProgress.clamp(0.0, 1.0).toDouble(),
          )!,
          icon: Icons.add_rounded,
          onPressed: enabled ? onPlusPressed : null,
        ),
        const SizedBox(width: 8),
        AnimatedBuilder(
          animation: controller,
          builder: (BuildContext context, Widget? child) {
            final bool showInterruptButton =
                enabled &&
                interruptTrace != null &&
                controller.text.trim().isEmpty;
            if (showInterruptButton) {
              return _CircleButton(
                key: const ValueKey<String>('chat-composer-interrupt-button'),
                backgroundColor: _ChatPalette.runTraceInterruptAction,
                foregroundColor: Colors.white,
                icon: Icons.stop_rounded,
                onPressed: isInterruptBusy ? null : onInterruptPressed,
              );
            }
            return _CircleButton(
              key: const ValueKey<String>('chat-composer-send-button'),
              backgroundColor: _ChatPalette.accent,
              gradient: OpenCrayGradients.brand,
              foregroundColor: Colors.white,
              icon: Icons.arrow_upward_rounded,
              onPressed: enabled ? onSendPressed : null,
            );
          },
        ),
      ],
    );

    final double glassT = defaultGlassProgress.clamp(0.0, 1.0).toDouble();
    if (glassT <= 0) {
      return inputRow;
    }

    return Stack(
      clipBehavior: Clip.none,
      children: <Widget>[
        Positioned(
          left: -8,
          right: -8,
          top: -6,
          bottom: -6,
          child: IgnorePointer(
            child: ClipRRect(
              borderRadius: messageFieldRadius,
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
                child: Opacity(
                  opacity: glassT,
                  child: const DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: <Color>[
                          Color(0x00FFFFFF),
                          Color(0x73FFFFFF),
                          Color(0x61F0F5FF),
                          Color(0x00F0F5FF),
                        ],
                        stops: <double>[0, 0.32, 0.72, 1],
                      ),
                      borderRadius: messageFieldRadius,
                    ),
                    child: SizedBox.expand(),
                  ),
                ),
              ),
            ),
          ),
        ),
        inputRow,
      ],
    );
  }
}

class _CircleButton extends StatelessWidget {
  const _CircleButton({
    super.key,
    required this.backgroundColor,
    required this.foregroundColor,
    required this.icon,
    required this.onPressed,
    this.gradient,
  });

  final Color backgroundColor;
  final Color foregroundColor;
  final IconData icon;
  final VoidCallback? onPressed;
  final Gradient? gradient;

  @override
  Widget build(BuildContext context) {
    final bool enabled = onPressed != null;
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          color: enabled ? backgroundColor : backgroundColor.withValues(alpha: 0.4),
          gradient: enabled ? gradient : null,
          borderRadius: BorderRadius.circular(14),
          boxShadow: enabled && gradient != null
              ? const <BoxShadow>[
                  BoxShadow(
                    color: Color(0x3D2563EB),
                    offset: Offset(0, 3),
                    blurRadius: 10,
                  ),
                ]
              : null,
        ),
        child: Icon(
          icon,
          color: enabled
              ? foregroundColor
              : foregroundColor.withValues(alpha: 0.5),
          size: 18,
        ),
      ),
    );
  }
}

class _AddActionPill extends StatelessWidget {
  const _AddActionPill({required this.action, required this.onPressed});

  final ChatAddActionData action;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 40,
        decoration: BoxDecoration(
          color: OpenCrayColors.surfaceSubtle,
          borderRadius: BorderRadius.circular(12),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 10),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Icon(action.icon, size: 16, color: _ChatPalette.textPrimary),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                action.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: _ChatTextStyles.addAction,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AttachmentCard extends StatelessWidget {
  const _AttachmentCard({
    required this.attachment,
    required this.onRemove,
    this.bridge,
  });

  final ChatAttachmentData attachment;
  final VoidCallback onRemove;
  final OpenCrayHostBridge? bridge;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      key: ValueKey<String>('chat-composer-attachment-${attachment.id}'),
      width: 168,
      child: Stack(
        children: <Widget>[
          Container(
            margin: const EdgeInsets.only(top: 2, right: 2),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: attachment.accentColor,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Row(
              children: <Widget>[
                _ComposerAttachmentLeadingVisual(
                  attachment: attachment,
                  bridge: bridge,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: <Widget>[
                      Text(
                        attachment.label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: _ChatTextStyles.attachmentLabel,
                      ),
                      const SizedBox(height: 2),
                      Text(
                        attachment.detail,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: _ChatTextStyles.attachmentDetail,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            top: 0,
            right: 0,
            child: GestureDetector(
              onTap: onRemove,
              behavior: HitTestBehavior.opaque,
              child: Container(
                width: 16,
                height: 16,
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.96),
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: _ChatPalette.border),
                ),
                alignment: Alignment.center,
                child: const Icon(
                  Icons.close_rounded,
                  size: 10,
                  color: _ChatPalette.textSecondary,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ComposerAttachmentLeadingVisual extends StatefulWidget {
  const _ComposerAttachmentLeadingVisual({
    required this.attachment,
    required this.bridge,
  });

  final ChatAttachmentData attachment;
  final OpenCrayHostBridge? bridge;

  @override
  State<_ComposerAttachmentLeadingVisual> createState() =>
      _ComposerAttachmentLeadingVisualState();
}

class _ComposerAttachmentLeadingVisualState
    extends State<_ComposerAttachmentLeadingVisual> {
  Future<OpenCrayFileImagePreview>? _previewFuture;

  @override
  void initState() {
    super.initState();
    _previewFuture = _loadPreview();
  }

  @override
  void didUpdateWidget(covariant _ComposerAttachmentLeadingVisual oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.bridge != widget.bridge ||
        oldWidget.attachment.id != widget.attachment.id ||
        oldWidget.attachment.draftAttachment?.relativePath !=
            widget.attachment.draftAttachment?.relativePath) {
      _previewFuture = _loadPreview();
    }
  }

  Future<OpenCrayFileImagePreview>? _loadPreview() {
    if (widget.attachment.kind != ChatAttachmentKind.image) {
      return null;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    final String relativePath =
        widget.attachment.draftAttachment?.relativePath.trim() ?? '';
    if (bridge == null || relativePath.isEmpty) {
      return null;
    }
    return bridge.loadWorkspaceImagePreview(relativePath);
  }

  @override
  Widget build(BuildContext context) {
    final ChatAttachmentData attachment = widget.attachment;
    final IconData icon = attachment.kind == ChatAttachmentKind.image
        ? Icons.image_outlined
        : Icons.description_outlined;
    final Future<OpenCrayFileImagePreview>? previewFuture = _previewFuture;
    if (previewFuture == null) {
      return _buildAnimatedLeadingVisual(
        context,
        _buildIconPlaceholder(icon, attachment.id),
      );
    }
    return FutureBuilder<OpenCrayFileImagePreview>(
      future: previewFuture,
      builder: (context, snapshot) {
        final OpenCrayFileImagePreview? preview = snapshot.data;
        final bool ready = preview != null && preview.bytes.isNotEmpty;
        if (!ready) {
          return _buildAnimatedLeadingVisual(
            context,
            _buildIconPlaceholder(icon, attachment.id),
          );
        }
        return _buildAnimatedLeadingVisual(
          context,
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: Container(
              key: ValueKey<String>(
                'chat-composer-image-preview-${attachment.id}',
              ),
              width: 32,
              height: 32,
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.75),
                borderRadius: BorderRadius.circular(10),
              ),
              child: OpenCrayImageBytesView(
                bytes: preview.bytes,
                mimeType: preview.mimeType,
                fit: BoxFit.cover,
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildAnimatedLeadingVisual(BuildContext context, Widget child) {
    return AnimatedSwitcher(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.quick),
      switchInCurve: OpenCrayMotion.enter,
      switchOutCurve: OpenCrayMotion.exit,
      transitionBuilder: (Widget child, Animation<double> animation) {
        return FadeTransition(opacity: animation, child: child);
      },
      child: child,
    );
  }

  Widget _buildIconPlaceholder(IconData icon, String attachmentId) {
    return Container(
      key: ValueKey<String>('chat-composer-attachment-icon-$attachmentId'),
      width: 32,
      height: 32,
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.75),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Icon(icon, size: 16, color: _ChatPalette.textPrimary),
    );
  }
}

class _CommandOptionTile extends StatelessWidget {
  const _CommandOptionTile({required this.option, required this.onPressed});

  final ChatCommandOptionData option;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: OpenCrayColors.surfaceSubtle,
            borderRadius: BorderRadius.circular(14),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
            child: Row(
              children: <Widget>[
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(option.label, style: _ChatTextStyles.commandTitle),
                      const SizedBox(height: 3),
                      Text(
                        option.description,
                        style: _ChatTextStyles.commandDescription,
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                const Icon(
                  Icons.chevron_right_rounded,
                  size: 18,
                  color: _ChatPalette.textSecondary,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
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
