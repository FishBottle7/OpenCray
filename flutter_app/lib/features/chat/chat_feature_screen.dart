import 'dart:async';
import 'dart:convert';
import 'dart:ui';

import 'package:flutter/material.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/copy/opencray_ui_copy.dart';
import '../../core/models/opencray_chat_snapshot.dart';
import 'chat_models.dart';
import 'chat_seed_data.dart';

@visibleForTesting
OpenCrayChatRuntimeSnapshot? resolveChatRuntimeSnapshot(
  OpenCrayChatRuntimeSnapshot? embedded,
  OpenCrayChatRuntimeSnapshot? streamed,
) {
  if (embedded == null) {
    return streamed;
  }
  if (streamed == null) {
    return embedded;
  }
  final int embeddedVersion = runtimeSnapshotVersion(embedded);
  final int streamedVersion = runtimeSnapshotVersion(streamed);
  if (streamedVersion > embeddedVersion) {
    return streamed;
  }
  if (embeddedVersion > streamedVersion) {
    return embedded;
  }
  if (embedded.activeRuns.length != streamed.activeRuns.length) {
    return embedded.activeRuns.length < streamed.activeRuns.length
        ? embedded
        : streamed;
  }
  return streamed;
}

@visibleForTesting
int runtimeSnapshotVersion(OpenCrayChatRuntimeSnapshot snapshot) {
  final int latestEventEpochMs = snapshot.events.fold<int>(
    0,
    (latest, event) =>
        latest > event.emittedAtEpochMs ? latest : event.emittedAtEpochMs,
  );
  final int latestRunEpochMs = snapshot.activeRuns.fold<int>(
    0,
    (latest, run) =>
        latest > run.updatedAtEpochMs ? latest : run.updatedAtEpochMs,
  );
  return latestEventEpochMs > latestRunEpochMs
      ? latestEventEpochMs
      : latestRunEpochMs;
}

class OpenCrayChatFeature extends StatefulWidget {
  const OpenCrayChatFeature({
    super.key,
    required this.copy,
    this.state,
    this.bridge,
    this.bottomInset = 10,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState? state;
  final OpenCrayHostBridge? bridge;
  final double bottomInset;

  @override
  State<OpenCrayChatFeature> createState() => _OpenCrayChatFeatureState();
}

class _OpenCrayChatFeatureState extends State<OpenCrayChatFeature> {
  late ChatFeatureState _state =
      widget.state ?? OpenCrayChatSeedData.main(widget.copy);
  late final TextEditingController _composerController =
      TextEditingController();
  late final FocusNode _composerFocusNode = FocusNode();
  final ScrollController _chatScrollController = ScrollController();
  final GlobalKey _composerKey = GlobalKey();
  StreamSubscription<OpenCrayChatSnapshot>? _chatSubscription;
  StreamSubscription<OpenCrayChatRuntimeSnapshot>? _chatRuntimeSubscription;
  OpenCrayChatSnapshot? _latestChatSnapshot;
  OpenCrayChatRuntimeSnapshot? _latestChatRuntimeSnapshot;
  final Set<String> _approvalTaskIdsInFlight = <String>{};
  double _composerHeight = 0;

  bool get _usesHostBridge => widget.bridge != null;

  @override
  void initState() {
    super.initState();
    final bridge = widget.bridge;
    if (bridge != null) {
      _hydrateFromHost(bridge);
      _chatSubscription = bridge.watchChatSnapshot().listen(
        _handleChatSnapshot,
      );
      _chatRuntimeSubscription = bridge.watchChatRuntimeSnapshot().listen(
        _handleChatRuntimeSnapshot,
      );
    }
  }

  @override
  void dispose() {
    _chatSubscription?.cancel();
    _chatRuntimeSubscription?.cancel();
    _composerController.dispose();
    _composerFocusNode.dispose();
    _chatScrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const double toolbarReserveHeight = 44;
    final double topGlassBarHeight =
        MediaQuery.paddingOf(context).top + toolbarReserveHeight + 4;
    _scheduleComposerHeightSync();

    return ColoredBox(
      color: _ChatPalette.background,
      child: Stack(
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
                          onTap: _closeComposerMenus,
                          behavior: HitTestBehavior.translucent,
                          child: SingleChildScrollView(
                            controller: _chatScrollController,
                            padding: EdgeInsets.fromLTRB(
                              20,
                              4,
                              20,
                              _composerScrollInset(),
                            ),
                            child: _ChatScrollContent(
                              copy: widget.copy,
                              state: _state,
                              busyApprovalTaskIds: _approvalTaskIdsInFlight,
                              onApproveApproval: _approvePendingApproval,
                              onRejectApproval: _rejectPendingApproval,
                            ),
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
                            child: _ComposerCard(
                              copy: widget.copy,
                              state: _state,
                              controller: _composerController,
                              focusNode: _composerFocusNode,
                              onPlusPressed: _togglePlusMenu,
                              onSendPressed: () {
                                _sendCurrentState();
                              },
                              onAddActionSelected: _handleAddAction,
                              onCommandSelected: _showCommandMenu,
                              onAttachmentRemoved: _removeAttachment,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: IgnorePointer(
              child: _TopGlassBar(height: topGlassBarHeight),
            ),
          ),
          SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 6, 20, 0),
              child: _ChatToolbar(
                sessionButtonLabel: _state.sessionButtonLabel,
                modeLabel: _state.modeLabel,
                onSessionsPressed: _showDrawer,
              ),
            ),
          ),
          if (_state.drawerOpen)
            _SessionsDrawerOverlay(
              drawer: _state.drawer,
              onDismiss: _closeDrawer,
              onNewSessionPressed: _showEmpty,
              onSessionPressed: _handleSessionSelected,
            ),
        ],
      ),
    );
  }

  double _composerScrollInset() {
    final double measuredHeight = _composerHeight;
    if (measuredHeight > 0) {
      return widget.bottomInset + measuredHeight + 12;
    }
    return widget.bottomInset + 84;
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

  void _scheduleScrollToBottom({bool animated = true}) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_chatScrollController.hasClients) {
        return;
      }
      final ScrollPosition position = _chatScrollController.position;
      final double target = position.maxScrollExtent;
      if ((target - position.pixels).abs() < 1) {
        return;
      }
      if (animated) {
        _chatScrollController.animateTo(
          target,
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
        );
        return;
      }
      _chatScrollController.jumpTo(target);
    });
  }

  void _showDrawer() {
    setState(() {
      _state = _state.copyWith(drawerOpen: true);
    });
  }

  void _closeDrawer() {
    setState(() {
      _state = _state.copyWith(drawerOpen: false);
    });
  }

  void _showEmpty() {
    final bridge = widget.bridge;
    if (bridge != null) {
      bridge.createChatSession();
      return;
    }
    setState(() {
      _state = OpenCrayChatSeedData.empty(widget.copy);
    });
  }

  void _togglePlusMenu() {
    setState(() {
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
    setState(() {
      if (action.label == widget.copy.chatActionCommand) {
        _state = _state.copyWith(
          composer: _state.composer.copyWith(
            showAddMenu: false,
            commandOptions: OpenCrayChatSeedData.sampleCommandOptions(
              widget.copy,
            ),
            clearSelectedCommand: true,
          ),
        );
      } else {
        final currentAttachments = List<ChatAttachmentData>.of(
          _state.composer.attachments,
        );
        final attachment = _attachmentForAction(action.label);
        final alreadyPresent = currentAttachments.any(
          (ChatAttachmentData item) => item.label == attachment.label,
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
      }
    });
  }

  void _showCommandMenu() {
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
  }

  Future<void> _sendCurrentState() async {
    if (!_state.isInputEnabled) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      final text = _composerController.text.trim();
      if (text.isEmpty) {
        return;
      }
      try {
        await bridge.submitChatMessage(text);
        if (!mounted) {
          return;
        }
        _composerController.clear();
      } catch (_) {
        if (!mounted) {
          return;
        }
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(widget.copy.chatSubmitFailed)));
      }
      return;
    }
    setState(() {
      _state = _state.copyWith(
        composer: ChatComposerState(placeholder: _state.composer.placeholder),
      );
    });
  }

  void _handleChatSnapshot(OpenCrayChatSnapshot snapshot) {
    _latestChatSnapshot = snapshot;
    _applyHostState();
  }

  void _handleChatRuntimeSnapshot(OpenCrayChatRuntimeSnapshot snapshot) {
    _latestChatRuntimeSnapshot = snapshot;
    _applyHostState();
  }

  void _applyHostState() {
    if (!mounted) {
      return;
    }
    final snapshot = _latestChatSnapshot;
    if (snapshot == null) {
      return;
    }
    final ChatFeatureState nextState = _mapSnapshot(
      snapshot,
      _latestChatRuntimeSnapshot,
    );
    final bool shouldScrollToBottom =
        nextState.messages.length > _state.messages.length ||
        nextState.runTraces.length > _state.runTraces.length ||
        nextState.pendingApprovals.length > _state.pendingApprovals.length;
    setState(() {
      _state = nextState;
    });
    if (shouldScrollToBottom) {
      _scheduleScrollToBottom();
    }
  }

  Future<void> _approvePendingApproval(ChatPendingApprovalData approval) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      action: (bridge) => bridge.approveChatApproval(approval.approvalId),
    );
  }

  Future<void> _rejectPendingApproval(ChatPendingApprovalData approval) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      action: (bridge) => bridge.rejectChatApproval(approval.approvalId),
    );
  }

  Future<void> _runApprovalAction({
    required String approvalId,
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

  void _closeComposerMenus() {
    FocusManager.instance.primaryFocus?.unfocus();
    if (!_state.composer.showAddMenu &&
        _state.composer.commandOptions.isEmpty) {
      return;
    }
    setState(() {
      _state = _state.copyWith(
        composer: _state.composer.copyWith(
          showAddMenu: false,
          commandOptions: const <ChatCommandOptionData>[],
          clearSelectedCommand: true,
        ),
      );
    });
  }

  void _removeAttachment(ChatAttachmentData attachment) {
    setState(() {
      final attachments =
          List<ChatAttachmentData>.of(_state.composer.attachments)..removeWhere(
            (ChatAttachmentData item) => item.label == attachment.label,
          );
      _state = _state.copyWith(
        composer: _state.composer.copyWith(attachments: attachments),
      );
    });
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

  void _handleSessionSelected(ChatSessionListItemData session) {
    final bridge = widget.bridge;
    if (bridge != null) {
      bridge.selectChatSession(session.sessionId);
      _closeDrawer();
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

  Future<void> _hydrateFromHost(OpenCrayHostBridge bridge) async {
    final snapshot = await bridge.loadChatSnapshot();
    final runtimeSnapshot = await bridge.loadChatRuntimeSnapshot();
    if (!mounted) {
      return;
    }
    _latestChatSnapshot = snapshot;
    _latestChatRuntimeSnapshot = runtimeSnapshot;
    setState(() {
      _state = _mapSnapshot(snapshot, runtimeSnapshot);
    });
    if (snapshot.messages.isNotEmpty || runtimeSnapshot.activeRuns.isNotEmpty) {
      _scheduleScrollToBottom(animated: false);
    }
  }

  ChatFeatureState _mapSnapshot(
    OpenCrayChatSnapshot snapshot,
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  ) {
    final OpenCrayChatRuntimeSnapshot? effectiveRuntime =
        _resolveRuntimeSnapshot(snapshot.runtimeActivity, runtimeSnapshot);
    final List<ChatRunTraceData> runTraces = _mapRunTraces(effectiveRuntime);
    final List<ChatMessageData> messages = _mapMessages(
      snapshot.messages,
      hideThinkingPlaceholder: runTraces.isNotEmpty,
    );
    return ChatFeatureState(
      variant: messages.isEmpty && runTraces.isEmpty
              && snapshot.pendingApprovals.isEmpty
          ? ChatPrototypeVariant.empty
          : ChatPrototypeVariant.main,
      screenTitle: snapshot.screenTitle,
      summary: ChatSessionSummary(
        title: snapshot.summary.title,
        badge: snapshot.summary.badge,
        body: snapshot.summary.body,
      ),
      messages: messages,
      runTraces: runTraces,
      composer: ChatComposerState(
        placeholder: snapshot.composerPlaceholder,
        attachments: const <ChatAttachmentData>[],
        commandOptions: const <ChatCommandOptionData>[],
        addActions: _usesHostBridge
            ? const <ChatAddActionData>[]
            : OpenCrayChatSeedData.sampleAddActions(widget.copy),
      ),
      drawer: ChatSessionsDrawerState(
        eyebrow: snapshot.drawer.eyebrow,
        title: snapshot.drawer.title,
        ctaLabel: snapshot.drawer.ctaLabel,
        sessions: snapshot.drawer.sessions
            .map(
              (session) => ChatSessionListItemData(
                sessionId: session.sessionId,
                title: session.title,
                preview: session.preview,
                meta: session.meta,
                isSelected: session.isSelected,
                unreadCount: session.unreadCount,
              ),
            )
            .toList(growable: false),
      ),
      pendingApprovals: snapshot.pendingApprovals
          .map(
            (approval) => ChatPendingApprovalData(
              runId: approval.runId,
              taskId: approval.taskId,
              title: approval.title,
              body: approval.body,
              approveLabel: approval.approveLabel,
              rejectLabel: approval.rejectLabel,
              isHighRisk: approval.isHighRisk,
            ),
          )
          .toList(growable: false),
      modeLabel: snapshot.modeLabel,
      sessionButtonLabel: snapshot.sessionButtonLabel,
      emptyThreadHeight: messages.isEmpty && runTraces.isEmpty ? 260 : 0,
      isInputEnabled: snapshot.isInputEnabled,
    );
  }

  List<ChatMessageData> _mapMessages(
    List<OpenCrayChatMessageSnapshot> messages, {
    required bool hideThinkingPlaceholder,
  }) {
    final mapped = messages
        .map(
          (message) => ChatMessageData(
            kind: switch (message.kind) {
              'timeline' => ChatMessageKind.timeline,
              'outbound' => ChatMessageKind.outbound,
              _ => ChatMessageKind.inbound,
            },
            text: message.text,
            meta: message.meta,
          ),
        )
        .toList(growable: true);
    if (hideThinkingPlaceholder && mapped.isNotEmpty) {
      final lastMessage = mapped.last;
      if (lastMessage.kind == ChatMessageKind.inbound &&
          _thinkingPlaceholders.contains(lastMessage.text.trim())) {
        mapped.removeLast();
      }
    }
    return mapped;
  }

  List<ChatRunTraceData> _mapRunTraces(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  ) {
    if (runtimeSnapshot == null || runtimeSnapshot.activeRuns.isEmpty) {
      return const <ChatRunTraceData>[];
    }
    final activeRuns = runtimeSnapshot.activeRuns.toList(growable: false)
      ..sort(
        (left, right) =>
            left.acceptedAtEpochMs.compareTo(right.acceptedAtEpochMs),
      );
    return activeRuns
        .map(
          (run) => _mapRunTrace(
            run: run,
            runtimeSnapshot: runtimeSnapshot,
          ),
        )
        .toList(growable: false);
  }

  ChatRunTraceData _mapRunTrace({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  }) {
    final List<OpenCrayChatRuntimeEventSnapshot> runEvents = _runEventsFor(
      run: run,
      runtimeSnapshot: runtimeSnapshot,
    );
    final event = run.lastEvent;
    final toolName = event?.toolName?.trim();
    final bool waitingApproval = _isWaitingApproval(run);
    final List<ChatRunTraceHistoryEntry> history = _buildRunTraceHistory(
      run: run,
      runEvents: runEvents,
    );
    final OpenCrayChatRuntimeEventSnapshot? pairedToolCall =
        event?.kind == 'tool_result'
        ? _findPreviousToolCall(
            runEvents,
            beforeIndex: runEvents.length,
            toolName: toolName,
          )
        : null;
    switch (event?.kind) {
      case 'tool_call':
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: toolName?.isNotEmpty == true
              ? toolName!
              : widget.copy.chatRunWorkingLabel,
          body: _buildToolCallPreviewBody(event!),
          history: history,
          isHighRisk: waitingApproval &&
              run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'tool_result':
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: waitingApproval
              ? widget.copy.chatRunWaitingApprovalLabel
              : toolName?.isNotEmpty == true
              ? toolName!
              : widget.copy.chatRunWorkingLabel,
          body: _buildToolResultPreviewBody(
            event: event!,
            pairedToolCall: pairedToolCall,
            waitingApproval: waitingApproval,
            runErrorMessage: run.errorMessage,
          ),
          history: history,
          isHighRisk: waitingApproval &&
              run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'assistant':
        final text = event?.text?.trim();
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: widget.copy.chatRunWorkingLabel,
          body: text?.isNotEmpty == true
              ? text!
              : widget.copy.chatRunThinkingActive,
          history: history,
          isHighRisk: waitingApproval &&
              run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      default:
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: waitingApproval
              ? widget.copy.chatRunWaitingApprovalLabel
              : widget.copy.chatRunWorkingLabel,
          body: waitingApproval
              ? run.errorMessage?.trim().isNotEmpty == true
                  ? run.errorMessage!.trim()
                  : widget.copy.chatRunWaitingApprovalLabel
              : widget.copy.chatRunThinkingActive,
          history: history,
          isHighRisk: waitingApproval &&
              run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
    }
  }

  List<ChatRunTraceHistoryEntry> _buildRunTraceHistory({
    required OpenCrayChatRunSnapshot run,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  }) {
    final history = <ChatRunTraceHistoryEntry>[];
    for (int index = 0; index < runEvents.length; index += 1) {
      final mapped = _mapRunTraceHistoryEntry(
        event: runEvents[index],
        runEvents: runEvents,
        index: index,
      );
      if (mapped != null) {
        history.add(mapped);
      }
    }
    if (history.isEmpty) {
      history.add(
        ChatRunTraceHistoryEntry(
          label: widget.copy.chatRunWorkingLabel,
          body: widget.copy.chatRunThinkingActive,
        ),
      );
    }
    if (_isWaitingApproval(run)) {
      final approvalBody = run.errorMessage?.trim();
      final waitingEntry = ChatRunTraceHistoryEntry(
        label: widget.copy.chatRunWaitingApprovalLabel,
        body: approvalBody?.isNotEmpty == true
            ? approvalBody!
            : widget.copy.chatRunWaitingApprovalLabel,
        isHighRisk: run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
      );
      if (history.isEmpty ||
          history.last.label != waitingEntry.label ||
          history.last.body != waitingEntry.body) {
        history.add(waitingEntry);
      }
    }
    return history;
  }

  ChatRunTraceHistoryEntry? _mapRunTraceHistoryEntry(
    {
    required OpenCrayChatRuntimeEventSnapshot event,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
    required int index,
  }
  ) {
    final toolName = event.toolName?.trim();
    switch (event.kind) {
      case 'lifecycle':
        if (event.phase?.toLowerCase() == 'start') {
          return ChatRunTraceHistoryEntry(
            label: widget.copy.chatRunWorkingLabel,
            body: widget.copy.chatRunThinkingActive,
          );
        }
        return null;
      case 'tool_call':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : widget.copy.chatRunWorkingLabel;
        return ChatRunTraceHistoryEntry(
          label: resolvedToolName,
          body: _buildToolCallHistoryBody(event),
        );
      case 'tool_result':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : widget.copy.chatRunWorkingLabel;
        final OpenCrayChatRuntimeEventSnapshot? pairedToolCall =
            _findPreviousToolCall(
              runEvents,
              beforeIndex: index,
              toolName: resolvedToolName,
            );
        return ChatRunTraceHistoryEntry(
          label: resolvedToolName,
          body: _buildToolResultHistoryBody(
            event: event,
            pairedToolCall: pairedToolCall,
          ),
          isHighRisk: event.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'assistant':
        final text = event.text?.trim();
        return ChatRunTraceHistoryEntry(
          label: widget.copy.chatRunWorkingLabel,
          body: text?.isNotEmpty == true
              ? text!
              : widget.copy.chatRunThinkingActive,
        );
      default:
        return null;
    }
  }

  bool _isWaitingApproval(OpenCrayChatRunSnapshot run) =>
      run.errorCode == 'APPROVAL_REQUIRED' ||
      run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED';

  List<OpenCrayChatRuntimeEventSnapshot> _runEventsFor({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  }) => runtimeSnapshot.events
      .where((event) => event.runId == run.runId)
      .toList(growable: false)
    ..sort(
      (left, right) => left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
    );

  OpenCrayChatRuntimeEventSnapshot? _findPreviousToolCall(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    required int beforeIndex,
    String? toolName,
  }) {
    final String? normalizedToolName = toolName?.trim();
    for (int index = beforeIndex - 1; index >= 0; index -= 1) {
      final candidate = runEvents[index];
      if (candidate.kind != 'tool_call') {
        continue;
      }
      final String? candidateToolName = candidate.toolName?.trim();
      if (normalizedToolName == null ||
          normalizedToolName.isEmpty ||
          candidateToolName == normalizedToolName) {
        return candidate;
      }
    }
    return null;
  }

  String _buildToolCallPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String resolvedToolName =
        _nonEmpty(event.toolName) ??
        widget.copy.chatRunWorkingLabel;
    final String summary = _toolActionSummary(
      toolName: resolvedToolName,
      argumentsJson: event.argumentsJson,
    );
    final String? reason = _nonEmpty(event.toolReason);
    return _joinTraceSections(<String?>[
      summary,
      reason,
    ]);
  }

  String _buildToolResultPreviewBody({
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
    required bool waitingApproval,
    required String? runErrorMessage,
  }) {
    final String resolvedToolName =
        _nonEmpty(event.toolName) ??
        widget.copy.chatRunWorkingLabel;
    final String summary = _toolActionSummary(
      toolName: resolvedToolName,
      argumentsJson: pairedToolCall?.argumentsJson,
    );
    final String? message = (waitingApproval
            ? _nonEmpty(runErrorMessage)
            : _nonEmpty(event.errorMessage)) ??
        _nonEmpty(event.contentPreview);
    return _joinTraceSections(<String?>[
      summary,
      message ?? widget.copy.chatRunToolFollowUp(resolvedToolName),
    ]);
  }

  String _buildToolCallHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String resolvedToolName =
        _nonEmpty(event.toolName) ??
        widget.copy.chatRunWorkingLabel;
    final String summary = _toolActionSummary(
      toolName: resolvedToolName,
      argumentsJson: event.argumentsJson,
    );
    final String? reason = _nonEmpty(event.toolReason);
    final String? detail = _toolCallDetailBody(
      toolName: resolvedToolName,
      argumentsJson: event.argumentsJson,
    );
    return _joinTraceSections(<String?>[
      summary,
      reason,
      detail,
    ]);
  }

  String _buildToolResultHistoryBody({
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String resolvedToolName =
        _nonEmpty(event.toolName) ??
        widget.copy.chatRunWorkingLabel;
    final String summary = _toolActionSummary(
      toolName: resolvedToolName,
      argumentsJson: pairedToolCall?.argumentsJson,
    );
    final String? errorMessage = _nonEmpty(event.errorMessage);
    final String? preview = _nonEmpty(event.contentPreview);
    return _joinTraceSections(<String?>[
      summary,
      errorMessage ?? preview ?? widget.copy.chatRunToolFollowUp(resolvedToolName),
    ]);
  }

  String _toolActionSummary({
    required String toolName,
    required String? argumentsJson,
  }) {
    final Map<String, dynamic>? arguments = _decodeJsonObject(argumentsJson);
    final String fallback = widget.copy.chatRunCallingTool(toolName);
    switch (toolName) {
      case 'Read':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return fallback;
        }
        final int? offset = _argumentInt(arguments, 'offset');
        final int? limit = _argumentInt(arguments, 'limit');
        final String range = _readRangeSummary(offset: offset, limit: limit);
        return widget.copy.isChinese
            ? '读取 $path${range.isNotEmpty ? '，$range' : ''}'
            : 'Read $path${range.isNotEmpty ? ' $range' : ''}';
      case 'Grep':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return fallback;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        final String? glob = _argumentString(arguments, 'glob');
        final String globSuffix = glob == null
            ? ''
            : widget.copy.isChinese
            ? '，glob: $glob'
            : ' (glob: $glob)';
        return widget.copy.isChinese
            ? '在 $path 中搜索 "$pattern"$globSuffix'
            : 'Search "$pattern" in $path$globSuffix';
      case 'Glob':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return fallback;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        return widget.copy.isChinese
            ? '在 $path 中匹配 $pattern'
            : 'Match $pattern in $path';
      case 'LS':
        final String path =
            _argumentString(arguments, 'path') ??
            _argumentString(arguments, 'file_path') ??
            '.';
        return widget.copy.isChinese ? '列出 $path' : 'List $path';
      case 'Write':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        return path == null
            ? fallback
            : widget.copy.isChinese
            ? '写入 $path'
            : 'Write $path';
      case 'Edit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        return path == null
            ? fallback
            : widget.copy.isChinese
            ? '编辑 $path'
            : 'Edit $path';
      case 'MultiEdit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        final int editCount = _argumentList(arguments, 'edits')?.length ?? 0;
        if (path == null) {
          return fallback;
        }
        if (editCount <= 0) {
          return widget.copy.isChinese ? '批量编辑 $path' : 'MultiEdit $path';
        }
        return widget.copy.isChinese
            ? '对 $path 应用 $editCount 处编辑'
            : 'Apply $editCount edit(s) to $path';
      case 'TodoWrite':
        final int todoCount = _argumentList(arguments, 'todos')?.length ?? 0;
        if (todoCount <= 0) {
          return widget.copy.isChinese ? '读取当前待办列表' : 'Read current todo list';
        }
        return widget.copy.isChinese
            ? '更新 $todoCount 条待办'
            : 'Update $todoCount todo(s)';
      default:
        return fallback;
    }
  }

  String? _toolCallDetailBody({
    required String toolName,
    required String? argumentsJson,
  }) {
    final Map<String, dynamic>? arguments = _decodeJsonObject(argumentsJson);
    if (arguments == null || arguments.isEmpty) {
      return _nonEmpty(argumentsJson);
    }
    switch (toolName) {
      case 'Edit':
        return _editDetailBody(arguments);
      case 'MultiEdit':
        return _multiEditDetailBody(arguments);
      case 'Write':
        return _writeDetailBody(arguments);
      default:
        return _prettyJson(arguments);
    }
  }

  String? _editDetailBody(Map<String, dynamic> arguments) {
    final String? oldString = _argumentString(arguments, 'old_string');
    final String? newString = _argumentString(arguments, 'new_string');
    if (oldString == null || newString == null) {
      return _prettyJson(arguments);
    }
    return _diffBlock(oldString: oldString, newString: newString);
  }

  String? _multiEditDetailBody(Map<String, dynamic> arguments) {
    final List<dynamic>? edits = _argumentList(arguments, 'edits');
    if (edits == null || edits.isEmpty) {
      return _prettyJson(arguments);
    }
    final List<String> blocks = <String>[];
    for (int index = 0; index < edits.length; index += 1) {
      final dynamic rawEdit = edits[index];
      if (rawEdit is! Map) {
        continue;
      }
      final Map<String, dynamic> edit = Map<String, dynamic>.from(
        rawEdit.map(
          (key, value) => MapEntry(key.toString(), value),
        ),
      );
      final String? oldString = _argumentString(edit, 'old_string');
      final String? newString = _argumentString(edit, 'new_string');
      if (oldString == null || newString == null) {
        blocks.add(_prettyJson(edit));
        continue;
      }
      blocks.add(
        _joinTraceSections(<String>[
          widget.copy.isChinese ? '编辑 ${index + 1}' : 'Edit ${index + 1}',
          _diffBlock(oldString: oldString, newString: newString),
        ]),
      );
    }
    return blocks.isEmpty ? _prettyJson(arguments) : blocks.join('\n\n');
  }

  String? _writeDetailBody(Map<String, dynamic> arguments) {
    final String? content = _argumentString(arguments, 'content');
    if (content == null) {
      return _prettyJson(arguments);
    }
    return content;
  }

  String _diffBlock({
    required String oldString,
    required String newString,
  }) {
    final List<String> removed = _diffLines(prefix: '-', text: oldString);
    final List<String> added = _diffLines(prefix: '+', text: newString);
    return <String>[
      ...removed,
      ...added,
    ].join('\n');
  }

  List<String> _diffLines({
    required String prefix,
    required String text,
  }) {
    final List<String> lines = text
        .replaceAll('\r\n', '\n')
        .replaceAll('\r', '\n')
        .split('\n');
    if (lines.isEmpty) {
      return <String>['$prefix '];
    }
    return lines.map((line) => '$prefix $line').toList(growable: false);
  }

  String _readRangeSummary({
    required int? offset,
    required int? limit,
  }) {
    if (offset == null && limit == null) {
      return '';
    }
    if (widget.copy.isChinese) {
      if (offset != null && limit != null) {
        final int endLine = offset + limit - 1;
        return '第 $offset-$endLine 行';
      }
      if (offset != null) {
        return '从第 $offset 行开始';
      }
      return '前 $limit 行';
    }
    if (offset != null && limit != null) {
      final int endLine = offset + limit - 1;
      return 'lines $offset-$endLine';
    }
    if (offset != null) {
      return 'from line $offset';
    }
    return 'first $limit lines';
  }

  Map<String, dynamic>? _decodeJsonObject(String? rawJson) {
    final String? normalized = _nonEmpty(rawJson);
    if (normalized == null) {
      return null;
    }
    try {
      final dynamic decoded = jsonDecode(normalized);
      if (decoded is! Map) {
        return null;
      }
      return Map<String, dynamic>.from(
        decoded.map((key, value) => MapEntry(key.toString(), value)),
      );
    } catch (_) {
      return null;
    }
  }

  String? _argumentString(
    Map<String, dynamic>? arguments,
    String key, {
    String? fallbackKey,
  }) {
    if (arguments == null) {
      return null;
    }
    final dynamic value = arguments[key] ?? (fallbackKey == null ? null : arguments[fallbackKey]);
    final String normalized = switch (value) {
      null => '',
      String stringValue => stringValue.trim(),
      _ => value.toString().trim(),
    };
    return normalized.isEmpty ? null : normalized;
  }

  int? _argumentInt(Map<String, dynamic>? arguments, String key) {
    if (arguments == null) {
      return null;
    }
    final dynamic value = arguments[key];
    return switch (value) {
      int intValue => intValue,
      num numValue => numValue.toInt(),
      String stringValue => int.tryParse(stringValue.trim()),
      _ => null,
    };
  }

  List<dynamic>? _argumentList(Map<String, dynamic>? arguments, String key) {
    if (arguments == null) {
      return null;
    }
    final dynamic value = arguments[key];
    return value is List<dynamic> ? value : null;
  }

  String _prettyJson(Map<String, dynamic> value) =>
      const JsonEncoder.withIndent('  ').convert(value);

  String _joinTraceSections(List<String?> sections) => sections
      .map((section) => section?.trim() ?? '')
      .where((section) => section.isNotEmpty)
      .join('\n\n');

  String? _nonEmpty(String? value) {
    final String normalized = value?.trim() ?? '';
    return normalized.isEmpty ? null : normalized;
  }

  static const Set<String> _thinkingPlaceholders = <String>{
    'Thinking',
    'Thinking…',
    'Thinking...',
    '思考中',
    '思考中…',
    '思考中...',
  };

  OpenCrayChatRuntimeSnapshot? _resolveRuntimeSnapshot(
    OpenCrayChatRuntimeSnapshot? embedded,
    OpenCrayChatRuntimeSnapshot? streamed,
  ) => resolveChatRuntimeSnapshot(embedded, streamed);
}

class _ChatScrollContent extends StatelessWidget {
  const _ChatScrollContent({
    required this.copy,
    required this.state,
    required this.busyApprovalTaskIds,
    required this.onApproveApproval,
    required this.onRejectApproval,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final Set<String> busyApprovalTaskIds;
  final ValueChanged<ChatPendingApprovalData> onApproveApproval;
  final ValueChanged<ChatPendingApprovalData> onRejectApproval;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Text(state.screenTitle, style: _ChatTextStyles.pageTitle),
        const SizedBox(height: 20),
        _SummaryCard(summary: state.summary),
        const SizedBox(height: 20),
        if (state.messages.isEmpty &&
            state.runTraces.isEmpty &&
            state.pendingApprovals.isEmpty)
          SizedBox(height: state.emptyThreadHeight)
        else
          _MessageList(
            copy: copy,
            messages: state.messages,
            runTraces: state.runTraces,
            pendingApprovals: state.pendingApprovals,
            busyApprovalTaskIds: busyApprovalTaskIds,
            onApproveApproval: onApproveApproval,
            onRejectApproval: onRejectApproval,
          ),
      ],
    );
  }
}

class _TopGlassBar extends StatelessWidget {
  const _TopGlassBar({required this.height});

  final double height;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: height,
      child: ClipRect(
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 22, sigmaY: 22),
          child: DecoratedBox(
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: <Color>[
                  Color(0xF5FFFFFF),
                  Color(0xD9FFFFFF),
                  Color(0x85F8FAFE),
                  Color(0x00F8FAFE),
                ],
                stops: <double>[0, 0.34, 0.76, 1],
              ),
              border: Border(bottom: BorderSide(color: Color(0x30FFFFFF))),
              boxShadow: <BoxShadow>[
                BoxShadow(
                  color: Color(0x10000000),
                  blurRadius: 20,
                  offset: Offset(0, 8),
                ),
              ],
            ),
            child: const SizedBox.expand(),
          ),
        ),
      ),
    );
  }
}

class _ChatToolbar extends StatelessWidget {
  const _ChatToolbar({
    required this.sessionButtonLabel,
    required this.modeLabel,
    required this.onSessionsPressed,
  });

  final String sessionButtonLabel;
  final String modeLabel;
  final VoidCallback onSessionsPressed;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: <Widget>[
        GestureDetector(
          onTap: onSessionsPressed,
          behavior: HitTestBehavior.opaque,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(999),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  const Icon(
                    Icons.menu_rounded,
                    size: 14,
                    color: _ChatPalette.textSecondary,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    sessionButtonLabel,
                    style: _ChatTextStyles.toolbarButton,
                  ),
                  const SizedBox(width: 2),
                  const Icon(
                    Icons.chevron_right_rounded,
                    size: 16,
                    color: _ChatPalette.textSecondary,
                  ),
                ],
              ),
            ),
          ),
        ),
        const Spacer(),
        Text(modeLabel, style: _ChatTextStyles.modeLabel),
      ],
    );
  }
}

class _SummaryCard extends StatelessWidget {
  const _SummaryCard({required this.summary});

  final ChatSessionSummary summary;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              children: <Widget>[
                Expanded(
                  child: Text(summary.title, style: _ChatTextStyles.cardTitle),
                ),
                const SizedBox(width: 12),
                Text(summary.badge, style: _ChatTextStyles.summaryBadge),
              ],
            ),
            const SizedBox(height: 8),
            Text(summary.body, style: _ChatTextStyles.bodyMuted),
          ],
        ),
      ),
    );
  }
}

class _PendingApprovalCard extends StatelessWidget {
  const _PendingApprovalCard({
    required this.copy,
    required this.approval,
    required this.isBusy,
    required this.onApprove,
    required this.onReject,
  });

  final OpenCrayUiCopy copy;
  final ChatPendingApprovalData approval;
  final bool isBusy;
  final VoidCallback onApprove;
  final VoidCallback onReject;

  @override
  Widget build(BuildContext context) {
    final Color accentColor = approval.isHighRisk
        ? _ChatPalette.highRiskAccent
        : _ChatPalette.accent;
    final Color surfaceColor = approval.isHighRisk
        ? _ChatPalette.highRiskSurface
        : Colors.white;

    return DecoratedBox(
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: approval.isHighRisk
              ? _ChatPalette.highRiskBorder
              : _ChatPalette.border,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Expanded(
                  child: Text(approval.title, style: _ChatTextStyles.cardTitle),
                ),
                if (approval.isHighRisk) ...<Widget>[
                  const SizedBox(width: 12),
                  DecoratedBox(
                    decoration: BoxDecoration(
                      color: _ChatPalette.highRiskBadgeSurface,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      child: Text(
                        copy.chatHighRiskApproval,
                        style: _ChatTextStyles.highRiskBadge,
                      ),
                    ),
                  ),
                ],
              ],
            ),
            if (approval.body.trim().isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              Text(approval.body, style: _ChatTextStyles.bodyMuted),
            ],
            const SizedBox(height: 12),
            Row(
              children: <Widget>[
                Expanded(
                  child: _ApprovalActionButton(
                    label: approval.rejectLabel,
                    foregroundColor: _ChatPalette.textSecondary,
                    backgroundColor: Colors.white,
                    borderColor: _ChatPalette.border,
                    onPressed: isBusy ? null : onReject,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: _ApprovalActionButton(
                    label: approval.approveLabel,
                    foregroundColor: Colors.white,
                    backgroundColor: accentColor,
                    borderColor: accentColor,
                    onPressed: isBusy ? null : onApprove,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ApprovalActionButton extends StatelessWidget {
  const _ApprovalActionButton({
    required this.label,
    required this.foregroundColor,
    required this.backgroundColor,
    required this.borderColor,
    required this.onPressed,
  });

  final String label;
  final Color foregroundColor;
  final Color backgroundColor;
  final Color borderColor;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final bool enabled = onPressed != null;
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 38,
        decoration: BoxDecoration(
          color: enabled
              ? backgroundColor
              : backgroundColor.withValues(alpha: 0.55),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: enabled ? borderColor : borderColor.withValues(alpha: 0.55),
          ),
        ),
        alignment: Alignment.center,
        child: Text(
          label,
          style: _ChatTextStyles.approvalAction.copyWith(
            color: enabled
                ? foregroundColor
                : foregroundColor.withValues(alpha: 0.6),
          ),
        ),
      ),
    );
  }
}

class _MessageList extends StatelessWidget {
  const _MessageList({
    required this.copy,
    required this.messages,
    required this.runTraces,
    required this.pendingApprovals,
    required this.busyApprovalTaskIds,
    required this.onApproveApproval,
    required this.onRejectApproval,
  });

  final OpenCrayUiCopy copy;
  final List<ChatMessageData> messages;
  final List<ChatRunTraceData> runTraces;
  final List<ChatPendingApprovalData> pendingApprovals;
  final Set<String> busyApprovalTaskIds;
  final ValueChanged<ChatPendingApprovalData> onApproveApproval;
  final ValueChanged<ChatPendingApprovalData> onRejectApproval;

  @override
  Widget build(BuildContext context) {
    final remainingApprovals = List<ChatPendingApprovalData>.of(
      pendingApprovals,
      growable: true,
    );
    final children = <Widget>[];

    for (final message in messages) {
      switch (message.kind) {
        case ChatMessageKind.timeline:
          children.add(
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Center(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: const Color(0xFFE9E9ED),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 5,
                    ),
                    child: Text(
                      message.text,
                      style: _ChatTextStyles.timeline,
                    ),
                  ),
                ),
              ),
            ),
          );
        case ChatMessageKind.inbound:
          children.add(
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Align(
                alignment: Alignment.centerLeft,
                child: _Bubble(
                  text: message.text,
                  backgroundColor: Colors.white,
                  textColor: _ChatPalette.textPrimary,
                  maxWidth: 252,
                ),
              ),
            ),
          );
        case ChatMessageKind.outbound:
          children.add(
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Align(
                alignment: Alignment.centerRight,
                child: _Bubble(
                  text: message.text,
                  backgroundColor: _ChatPalette.accent,
                  textColor: Colors.white,
                  maxWidth: 236,
                ),
              ),
            ),
          );
      }
    }

    for (final trace in runTraces) {
      children.add(
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Align(
            alignment: Alignment.centerLeft,
            child: _RunTraceBubble(
              key: ValueKey<String>('chat-run-trace-${trace.runId}'),
              trace: trace,
            ),
          ),
        ),
      );
      final matchingApprovalIndex = remainingApprovals.indexWhere(
        (approval) =>
            approval.runId == trace.runId || approval.taskId == trace.taskId,
      );
      if (matchingApprovalIndex >= 0) {
        final approval = remainingApprovals.removeAt(matchingApprovalIndex);
        children.add(
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Align(
              alignment: Alignment.centerLeft,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 292),
                child: _PendingApprovalCard(
                  copy: copy,
                  approval: approval,
                  isBusy: busyApprovalTaskIds.contains(approval.approvalId),
                  onApprove: () => onApproveApproval(approval),
                  onReject: () => onRejectApproval(approval),
                ),
              ),
            ),
          ),
        );
      }
    }

    for (final approval in remainingApprovals) {
      children.add(
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Align(
            alignment: Alignment.centerLeft,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 292),
              child: _PendingApprovalCard(
                copy: copy,
                approval: approval,
                isBusy: busyApprovalTaskIds.contains(approval.approvalId),
                onApprove: () => onApproveApproval(approval),
                onReject: () => onRejectApproval(approval),
              ),
            ),
          ),
        ),
      );
    }

    return Column(children: children);
  }
}

class _RunTraceBubble extends StatefulWidget {
  const _RunTraceBubble({super.key, required this.trace});

  final ChatRunTraceData trace;

  @override
  State<_RunTraceBubble> createState() => _RunTraceBubbleState();
}

class _RunTraceBubbleState extends State<_RunTraceBubble> {
  late final ScrollController _bodyScrollController = ScrollController();

  @override
  void dispose() {
    _bodyScrollController.dispose();
    super.dispose();
  }

  Future<void> _openFullscreen() {
    return showDialog<void>(
      context: context,
      barrierColor: const Color(0x8A0B0E14),
      builder: (dialogContext) => _RunTraceFullscreenSheet(trace: widget.trace),
    );
  }

  @override
  Widget build(BuildContext context) {
    final trace = widget.trace;
    const double bubbleWidth = 252;
    const double bodyMaxHeight = 136;
    final Color surfaceColor = trace.isHighRisk
        ? _ChatPalette.highRiskSurface
        : const Color(0xFFF3F4F7);
    final Color borderColor = trace.isHighRisk
        ? _ChatPalette.highRiskBorder
        : const Color(0xFFE0E2E8);
    final Color chipColor = trace.isHighRisk
        ? _ChatPalette.highRiskBadgeSurface
        : const Color(0xFFE7EBF4);
    final Color chipTextColor = trace.isHighRisk
        ? _ChatPalette.highRiskAccent
        : _ChatPalette.textSecondary;
    return GestureDetector(
      onDoubleTap: _openFullscreen,
      behavior: HitTestBehavior.opaque,
      child: SizedBox(
        width: bubbleWidth,
        child: DecoratedBox(
          decoration: ShapeDecoration(
            color: surfaceColor,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(18),
              side: BorderSide(color: borderColor),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                DecoratedBox(
                  decoration: BoxDecoration(
                    color: chipColor,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 4,
                    ),
                    child: Text(
                      trace.label,
                      style: _ChatTextStyles.timeline.copyWith(
                        color: chipTextColor,
                      ),
                    ),
                  ),
                ),
                if (trace.body.trim().isNotEmpty) ...<Widget>[
                  const SizedBox(height: 8),
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxHeight: bodyMaxHeight),
                    child: Scrollbar(
                      controller: _bodyScrollController,
                      child: SingleChildScrollView(
                        key: ValueKey<String>(
                          'chat-run-trace-scroll-${trace.runId}',
                        ),
                        controller: _bodyScrollController,
                        primary: false,
                        padding: EdgeInsets.zero,
                        physics: const ClampingScrollPhysics(),
                        child: Text(
                          trace.body,
                          style: _ChatTextStyles.bubble.copyWith(
                            color: _ChatPalette.textPrimary,
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RunTraceFullscreenSheet extends StatefulWidget {
  const _RunTraceFullscreenSheet({required this.trace});

  final ChatRunTraceData trace;

  @override
  State<_RunTraceFullscreenSheet> createState() =>
      _RunTraceFullscreenSheetState();
}

class _RunTraceFullscreenSheetState extends State<_RunTraceFullscreenSheet> {
  late final ScrollController _scrollController = ScrollController();

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final trace = widget.trace;
    final history = trace.history.isNotEmpty
        ? trace.history
        : <ChatRunTraceHistoryEntry>[
            ChatRunTraceHistoryEntry(
              label: trace.label,
              body: trace.body,
              isHighRisk: trace.isHighRisk,
            ),
          ];
    final Color surfaceColor = trace.isHighRisk
        ? _ChatPalette.highRiskSurface
        : Colors.white;
    final Color borderColor = trace.isHighRisk
        ? _ChatPalette.highRiskBorder
        : _ChatPalette.border;
    final Color chipColor = trace.isHighRisk
        ? _ChatPalette.highRiskBadgeSurface
        : const Color(0xFFE7EBF4);
    final Color chipTextColor = trace.isHighRisk
        ? _ChatPalette.highRiskAccent
        : _ChatPalette.textSecondary;
    return Dialog.fullscreen(
      key: ValueKey<String>('chat-run-trace-fullscreen-${trace.runId}'),
      backgroundColor: _ChatPalette.background,
      child: SafeArea(
        child: Column(
          children: <Widget>[
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 10),
              child: Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      trace.label,
                      style: _ChatTextStyles.cardTitle,
                    ),
                  ),
                  GestureDetector(
                    onTap: () => Navigator.of(context).pop(),
                    behavior: HitTestBehavior.opaque,
                    child: Container(
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(color: _ChatPalette.border),
                      ),
                      alignment: Alignment.center,
                      child: const Icon(
                        Icons.close_rounded,
                        size: 18,
                        color: _ChatPalette.textPrimary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: surfaceColor,
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: borderColor),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        DecoratedBox(
                          decoration: BoxDecoration(
                            color: chipColor,
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 5,
                            ),
                            child: Text(
                              trace.label,
                              style: _ChatTextStyles.timeline.copyWith(
                                color: chipTextColor,
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 14),
                        Expanded(
                          child: Scrollbar(
                            controller: _scrollController,
                            thumbVisibility: true,
                            child: SingleChildScrollView(
                              key: ValueKey<String>(
                                'chat-run-trace-fullscreen-scroll-${trace.runId}',
                              ),
                              controller: _scrollController,
                              primary: false,
                              padding: EdgeInsets.zero,
                              physics: const ClampingScrollPhysics(),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: history
                                    .map(
                                      (entry) => Padding(
                                        padding: const EdgeInsets.only(
                                          bottom: 12,
                                        ),
                                        child: _RunTraceHistoryCard(
                                          entry: entry,
                                        ),
                                      ),
                                    )
                                    .toList(growable: false),
                              ),
                            ),
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
}

class _RunTraceHistoryCard extends StatelessWidget {
  const _RunTraceHistoryCard({required this.entry});

  final ChatRunTraceHistoryEntry entry;

  @override
  Widget build(BuildContext context) {
    final Color chipColor = entry.isHighRisk
        ? _ChatPalette.highRiskBadgeSurface
        : const Color(0xFFEFF2F8);
    final Color chipTextColor = entry.isHighRisk
        ? _ChatPalette.highRiskAccent
        : _ChatPalette.textSecondary;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFF7F8FB),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0xFFE6E8EF)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            DecoratedBox(
              decoration: BoxDecoration(
                color: chipColor,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 8,
                  vertical: 4,
                ),
                child: Text(
                  entry.label,
                  style: _ChatTextStyles.timeline.copyWith(
                    color: chipTextColor,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              entry.body,
              style: _ChatTextStyles.bubble.copyWith(
                color: _ChatPalette.textPrimary,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({
    required this.text,
    required this.backgroundColor,
    required this.textColor,
    required this.maxWidth,
  });

  final String text;
  final Color backgroundColor;
  final Color textColor;
  final double maxWidth;

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: BoxConstraints(maxWidth: maxWidth),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: backgroundColor,
          shape: const RoundedSuperellipseBorder(
            borderRadius: BorderRadius.all(Radius.circular(18)),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Text(
            text,
            style: _ChatTextStyles.bubble.copyWith(color: textColor),
          ),
        ),
      ),
    );
  }
}

class _ComposerCard extends StatelessWidget {
  const _ComposerCard({
    required this.copy,
    required this.state,
    required this.controller,
    required this.focusNode,
    required this.onPlusPressed,
    required this.onSendPressed,
    required this.onAddActionSelected,
    required this.onCommandSelected,
    required this.onAttachmentRemoved,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback onPlusPressed;
  final VoidCallback onSendPressed;
  final ValueChanged<ChatAddActionData> onAddActionSelected;
  final VoidCallback onCommandSelected;
  final ValueChanged<ChatAttachmentData> onAttachmentRemoved;

  @override
  Widget build(BuildContext context) {
    final bool hasIntegratedSurface =
        state.composer.commandOptions.isNotEmpty ||
        state.composer.attachments.isNotEmpty ||
        state.composer.showAddMenu;

    final Widget content = Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        if (state.composer.commandOptions.isNotEmpty) ...<Widget>[
          Container(
            decoration: BoxDecoration(
              color: _ChatPalette.subtleSurface,
              borderRadius: BorderRadius.circular(14),
            ),
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(copy.chatCommands, style: _ChatTextStyles.commandsLabel),
                const SizedBox(height: 8),
                ...state.composer.commandOptions.map(
                  (ChatCommandOptionData option) => _CommandOptionTile(
                    option: option,
                    onPressed: onCommandSelected,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
        ],
        if (state.composer.attachments.isNotEmpty) ...<Widget>[
          SizedBox(
            height: 68,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemBuilder: (BuildContext context, int index) {
                return _AttachmentCard(
                  attachment: state.composer.attachments[index],
                  onRemove: () =>
                      onAttachmentRemoved(state.composer.attachments[index]),
                );
              },
              separatorBuilder: (BuildContext context, int index) {
                return const SizedBox(width: 8);
              },
              itemCount: state.composer.attachments.length,
            ),
          ),
          const SizedBox(height: 10),
        ],
        _InputRow(
          placeholder: state.composer.placeholder,
          controller: controller,
          focusNode: focusNode,
          enabled: state.isInputEnabled,
          hasIntegratedSurface: hasIntegratedSurface,
          showDefaultGlass: !hasIntegratedSurface,
          plusHighlighted: hasIntegratedSurface,
          onPlusPressed: onPlusPressed,
          onSendPressed: onSendPressed,
        ),
        if (state.composer.showAddMenu) ...<Widget>[
          const SizedBox(height: 10),
          Text(copy.chatAddToMessage, style: _ChatTextStyles.sectionLabel),
          const SizedBox(height: 10),
          Row(
            children: state.composer.addActions
                .map(
                  (ChatAddActionData action) => Expanded(
                    flex: action.label == copy.chatActionCommand ? 12 : 9,
                    child: Padding(
                      padding: EdgeInsets.only(
                        right: action == state.composer.addActions.last ? 0 : 8,
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
      ],
    );

    if (!hasIntegratedSurface) {
      return content;
    }

    return DecoratedBox(
      decoration: _ChatDecorations.card(),
      child: Padding(padding: const EdgeInsets.all(10), child: content),
    );
  }
}

class _InputRow extends StatelessWidget {
  const _InputRow({
    required this.placeholder,
    required this.controller,
    required this.focusNode,
    required this.enabled,
    required this.hasIntegratedSurface,
    required this.showDefaultGlass,
    required this.plusHighlighted,
    required this.onPlusPressed,
    required this.onSendPressed,
  });

  final String placeholder;
  final TextEditingController controller;
  final FocusNode focusNode;
  final bool enabled;
  final bool hasIntegratedSurface;
  final bool showDefaultGlass;
  final bool plusHighlighted;
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
                final bool showOutline =
                    hasIntegratedSurface || focusNode.hasFocus;
                final Color fieldOutlineColor = focusNode.hasFocus
                    ? _ChatPalette.accent
                    : _ChatPalette.composerStroke;

                return AnimatedSize(
                  duration: const Duration(milliseconds: 160),
                  curve: Curves.easeOutCubic,
                  alignment: Alignment.bottomCenter,
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(
                      minHeight: messageFieldMinHeight,
                      maxHeight: messageFieldMaxHeight,
                    ),
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: showOutline ? fieldOutlineColor : Colors.white,
                        borderRadius: messageFieldRadius,
                      ),
                      child: Padding(
                        padding: EdgeInsets.all(showOutline ? 1 : 0),
                        child: ClipRRect(
                          borderRadius: showOutline
                              ? messageFieldInnerRadius
                              : messageFieldRadius,
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
          backgroundColor: plusHighlighted
              ? _ChatPalette.plusActiveSurface
              : Colors.white,
          foregroundColor: plusHighlighted
              ? _ChatPalette.accent
              : _ChatPalette.textSecondary,
          icon: Icons.add_rounded,
          onPressed: enabled ? onPlusPressed : null,
        ),
        const SizedBox(width: 8),
        _CircleButton(
          backgroundColor: _ChatPalette.accent,
          foregroundColor: Colors.white,
          icon: Icons.arrow_upward_rounded,
          onPressed: enabled ? onSendPressed : null,
        ),
      ],
    );

    if (!showDefaultGlass) {
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
        inputRow,
      ],
    );
  }
}

class _CircleButton extends StatelessWidget {
  const _CircleButton({
    required this.backgroundColor,
    required this.foregroundColor,
    required this.icon,
    required this.onPressed,
  });

  final Color backgroundColor;
  final Color foregroundColor;
  final IconData icon;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          color: onPressed == null
              ? backgroundColor.withValues(alpha: 0.4)
              : backgroundColor,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Icon(
          icon,
          color: onPressed == null
              ? foregroundColor.withValues(alpha: 0.5)
              : foregroundColor,
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
          color: const Color(0xFFF7F7F9),
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
  const _AttachmentCard({required this.attachment, required this.onRemove});

  final ChatAttachmentData attachment;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final IconData icon = attachment.kind == ChatAttachmentKind.image
        ? Icons.image_outlined
        : Icons.description_outlined;

    return SizedBox(
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
                Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.75),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Icon(icon, size: 16, color: _ChatPalette.textPrimary),
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
            color: const Color(0xFFF7F7F9),
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

class _SessionsDrawerOverlay extends StatelessWidget {
  const _SessionsDrawerOverlay({
    required this.drawer,
    required this.onDismiss,
    required this.onNewSessionPressed,
    required this.onSessionPressed,
  });

  final ChatSessionsDrawerState drawer;
  final VoidCallback onDismiss;
  final VoidCallback onNewSessionPressed;
  final ValueChanged<ChatSessionListItemData> onSessionPressed;

  @override
  Widget build(BuildContext context) {
    final EdgeInsets safePadding = MediaQuery.of(context).padding;

    return Positioned.fill(
      child: Row(
        children: <Widget>[
          Container(
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
                Text(drawer.eyebrow, style: _ChatTextStyles.drawerEyebrow),
                const SizedBox(height: 10),
                Text(drawer.title, style: _ChatTextStyles.drawerTitle),
                const SizedBox(height: 16),
                GestureDetector(
                  onTap: onNewSessionPressed,
                  behavior: HitTestBehavior.opaque,
                  child: Container(
                    height: 40,
                    width: 132,
                    decoration: BoxDecoration(
                      color: _ChatPalette.accent,
                      borderRadius: BorderRadius.circular(14),
                    ),
                    alignment: Alignment.center,
                    child: Text(
                      drawer.ctaLabel,
                      style: _ChatTextStyles.drawerCta,
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                Expanded(
                  child: ListView.separated(
                    itemBuilder: (BuildContext context, int index) {
                      return _SessionListTile(
                        session: drawer.sessions[index],
                        onPressed: () =>
                            onSessionPressed(drawer.sessions[index]),
                      );
                    },
                    separatorBuilder: (BuildContext context, int index) {
                      return const SizedBox(height: 10);
                    },
                    itemCount: drawer.sessions.length,
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            child: GestureDetector(
              onTap: onDismiss,
              behavior: HitTestBehavior.opaque,
              child: ColoredBox(
                color: const Color(0x26111111),
                child: const SizedBox.expand(),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SessionListTile extends StatelessWidget {
  const _SessionListTile({required this.session, required this.onPressed});

  final ChatSessionListItemData session;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: session.isSelected
              ? const Color(0xFFF4F7FF)
              : const Color(0xFFF7F7F9),
          borderRadius: BorderRadius.circular(14),
          border: session.isSelected
              ? Border.all(color: const Color(0xFFD8E5FF))
              : null,
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      session.title,
                      style: _ChatTextStyles.sessionTitle,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(session.meta, style: _ChatTextStyles.sessionMeta),
                  if (session.unreadCount > 0) ...<Widget>[
                    const SizedBox(width: 8),
                    _SessionUnreadBadge(
                      sessionId: session.sessionId,
                      count: session.unreadCount,
                    ),
                  ],
                ],
              ),
              const SizedBox(height: 6),
              Text(
                session.preview,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: _ChatTextStyles.sessionPreview,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SessionUnreadBadge extends StatelessWidget {
  const _SessionUnreadBadge({
    required this.sessionId,
    required this.count,
  });

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
          color: Color(0xFFFF3B30),
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
        color: const Color(0xFFFF3B30),
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

class _ChatDecorations {
  const _ChatDecorations._();

  static BoxDecoration card() {
    return BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: _ChatPalette.border),
    );
  }
}

class _ChatPalette {
  const _ChatPalette._();

  static const Color background = Color(0xFFF5F5F7);
  static const Color accent = Color(0xFF007AFF);
  static const Color highRiskAccent = Color(0xFFC84B31);
  static const Color highRiskSurface = Color(0xFFFFF5F2);
  static const Color highRiskBorder = Color(0xFFF2C6BA);
  static const Color highRiskBadgeSurface = Color(0xFFFFE0D7);
  static const Color textPrimary = Color(0xFF111111);
  static const Color textSecondary = Color(0xFF6E6E73);
  static const Color textTertiary = Color(0xFF8E8E93);
  static const Color border = Color(0xFFE8E8ED);
  static const Color composerStroke = Color(0xFFD7D7DC);
  static const Color plusActiveSurface = Color(0xFFEEF5FF);
  static const Color subtleSurface = Color(0xFFF7F7FA);
}

class _ChatTextStyles {
  const _ChatTextStyles._();

  static const TextStyle pageTitle = TextStyle(
    fontSize: 30,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
    letterSpacing: -0.6,
  );

  static const TextStyle cardTitle = TextStyle(
    fontSize: 17,
    height: 1.25,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle bodyMuted = TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w400,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle bubble = TextStyle(
    fontSize: 14,
    height: 1.35,
    fontWeight: FontWeight.w500,
  );

  static const TextStyle timeline = TextStyle(
    fontSize: 11,
    height: 1.2,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle toolbarButton = TextStyle(
    fontSize: 13,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle modeLabel = TextStyle(
    fontSize: 12,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ChatPalette.accent,
  );

  static const TextStyle summaryBadge = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle highRiskBadge = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ChatPalette.highRiskAccent,
  );

  static const TextStyle placeholder = TextStyle(
    fontSize: 15,
    height: 1.2,
    fontWeight: FontWeight.w400,
    color: _ChatPalette.textTertiary,
  );

  static const TextStyle sectionLabel = TextStyle(
    fontSize: 13,
    height: 1.15,
    fontWeight: FontWeight.w700,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle commandsLabel = TextStyle(
    fontSize: 12,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle addAction = TextStyle(
    fontSize: 13,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle approvalAction = TextStyle(
    fontSize: 13,
    height: 1.1,
    fontWeight: FontWeight.w700,
  );

  static const TextStyle attachmentLabel = TextStyle(
    fontSize: 13,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle attachmentDetail = TextStyle(
    fontSize: 11,
    height: 1.2,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle commandTitle = TextStyle(
    fontSize: 14,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle commandDescription = TextStyle(
    fontSize: 12,
    height: 1.3,
    fontWeight: FontWeight.w400,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle drawerEyebrow = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle drawerTitle = TextStyle(
    fontSize: 24,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle drawerCta = TextStyle(
    fontSize: 14,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: Colors.white,
  );

  static const TextStyle sessionTitle = TextStyle(
    fontSize: 14,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle sessionMeta = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle sessionPreview = TextStyle(
    fontSize: 12,
    height: 1.3,
    fontWeight: FontWeight.w400,
    color: _ChatPalette.textSecondary,
  );
}
