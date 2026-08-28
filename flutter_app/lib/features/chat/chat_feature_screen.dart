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
import '../../core/design/opencray_controls.dart';
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
part 'chat_runtime_projector_messages.dart';
part 'chat_runtime_projector_history.dart';
part 'chat_runtime_projector_inspector.dart';
part 'chat_widgets_chrome.dart';
part 'chat_widgets_approvals.dart';
part 'chat_widgets_run_trace.dart';
part 'chat_widgets_run_trace_inspector.dart';
part 'chat_widgets_message.dart';
part 'chat_widgets_attachments.dart';
part 'chat_widgets_composer.dart';
part 'chat_widgets_sessions_drawer.dart';
part 'chat_state_deletion.dart';
part 'chat_message_actions.dart';
part 'chat_realtime_ingestion.dart';
part 'chat_sandbox_refresh.dart';
part 'chat_approval_actions.dart';
part 'chat_composer_attachments.dart';
part 'chat_session_actions.dart';

@visibleForTesting
TextSelectionThemeData chatBubbleSelectionTheme(ChatMessageKind kind) {
  return switch (kind) {
    ChatMessageKind.outbound => const TextSelectionThemeData(
      // Outbound bubbles already use the app accent, so switch to a bright
      // translucent selection color to preserve contrast.
      selectionColor: _ChatPalette.textSelectionOnAccent,
      selectionHandleColor: Colors.white,
    ),
    _ => const TextSelectionThemeData(
      selectionColor: _ChatPalette.textSelectionOnSurface,
      selectionHandleColor: OpenCrayColors.primary,
    ),
  };
}

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
