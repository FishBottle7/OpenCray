import 'dart:async';
import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_agent_snapshot.dart';
import 'package:opencray/core/models/opencray_chat_draft_attachment.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_debug_snapshot.dart';
import 'package:opencray/core/models/opencray_file_image_preview.dart';
import 'package:opencray/core/models/opencray_image_reference.dart';
import 'package:opencray/core/models/opencray_sandbox_preview_embed_config.dart';
import 'package:opencray/core/models/opencray_sandbox_settings.dart';
import 'package:opencray/core/models/opencray_file_text_preview.dart';
import 'package:opencray/core/models/opencray_file_voice_playback_source.dart';
import 'package:opencray/core/models/opencray_strong_background.dart';
import 'package:opencray/features/chat/chat_feature.dart';
import 'package:opencray/features/chat/chat_voice_playback.dart';

String weekdayLabelFor(DateTime dateTime) {
  const List<String> weekdayLabels = <String>[
    'Mon',
    'Tue',
    'Wed',
    'Thu',
    'Fri',
    'Sat',
    'Sun',
  ];
  return weekdayLabels[dateTime.weekday - 1];
}

String dateLabelFor(DateTime dateTime, {required DateTime now}) {
  const List<String> monthLabels = <String>[
    'Jan',
    'Feb',
    'Mar',
    'Apr',
    'May',
    'Jun',
    'Jul',
    'Aug',
    'Sep',
    'Oct',
    'Nov',
    'Dec',
  ];
  final String month = monthLabels[dateTime.month - 1];
  if (dateTime.year != now.year) {
    return '$month ${dateTime.day}, ${dateTime.year}';
  }
  return '$month ${dateTime.day}';
}

List<TextSpan> collectLeafTextSpans(InlineSpan span) {
  if (span is! TextSpan) {
    return const <TextSpan>[];
  }
  final List<InlineSpan>? children = span.children;
  if (children == null || children.isEmpty) {
    return <TextSpan>[span];
  }
  return children
      .expand<TextSpan>(collectLeafTextSpans)
      .toList(growable: false);
}

Finder findRichTextWithPlainText(String text) =>
    find.byWidgetPredicate((widget) {
      if (widget is! RichText) {
        return false;
      }
      return widget.text.toPlainText() == text;
    });

/// Matches bubble text that may carry the trailing inline streaming
/// indicator, which contributes a single object-replacement placeholder
/// (U+FFFC) to the rendered plain text while the message streams.
Finder findStreamedText(String text) => find.byWidgetPredicate((widget) {
  if (widget is! Text) {
    return false;
  }
  final String plain = widget.data ?? widget.textSpan?.toPlainText() ?? '';
  return plain == text || plain == '$text￼';
}, description: 'streamed text "$text"');

Finder chatSessionsButton() =>
    find.byKey(const ValueKey<String>('chat-sessions-button'));

Finder chatScrollView() =>
    find.byKey(const ValueKey<String>('chat-scroll-view'));

ScrollableState scrollableStateFor(WidgetTester tester, Finder scrollHost) {
  return tester.state<ScrollableState>(
    find.descendant(of: scrollHost, matching: find.byType(Scrollable)).first,
  );
}

void jumpToScrollEnd(WidgetTester tester, Finder scrollHost) {
  final ScrollableState scrollableState = scrollableStateFor(
    tester,
    scrollHost,
  );
  scrollableState.position.jumpTo(scrollableState.position.maxScrollExtent);
}

void activateRichTextLink(WidgetTester tester, Finder richTextFinder) {
  final RichText richText = tester.widget<RichText>(richTextFinder);
  final TapGestureRecognizer recognizer = collectLeafTextSpans(
    richText.text,
  ).map((span) => span.recognizer).whereType<TapGestureRecognizer>().first;
  recognizer.onTap?.call();
}

Widget buildChatHarness({
  List<ChatPendingApprovalData> pendingApprovals =
      const <ChatPendingApprovalData>[],
  List<ChatTodoItemData> todos = const <ChatTodoItemData>[],
  ChatComposerState? composer,
  List<ChatMessageData>? messages,
  List<ChatRunTraceData>? runTraces,
  ChatSessionsDrawerState? drawer,
  bool drawerOpen = false,
}) {
  final copy = OpenCrayUiCopy.fromLocaleTag('en');
  final traceBody = List<String>.generate(
    40,
    (index) => 'Running line ${index + 1}: checking repository state.',
  ).join('\n');
  return MaterialApp(
    home: Scaffold(
      body: OpenCrayChatFeature(
        copy: copy,
        state: ChatFeatureState(
          variant: ChatPrototypeVariant.main,
          screenTitle: 'Chat',
          summary: const ChatSessionSummary(
            title: 'Session',
            badge: '1 message',
            body: 'Reply in progress',
          ),
          messages:
              messages ??
              const <ChatMessageData>[
                ChatMessageData(
                  kind: ChatMessageKind.outbound,
                  text: 'Inspect the workspace.',
                ),
              ],
          runTraces:
              runTraces ??
              <ChatRunTraceData>[
                ChatRunTraceData(
                  runId: 'run-1',
                  taskId: 'task-1',
                  label: copy.chatRunWorkingLabel,
                  body: traceBody,
                  history: <ChatRunTraceHistoryEntry>[
                    ChatRunTraceHistoryEntry(
                      label: copy.chatRunWorkingLabel,
                      body: copy.chatRunThinkingActive,
                    ),
                    ChatRunTraceHistoryEntry(
                      label: 'Read',
                      body:
                          'Read README.md lines 5-6\n  └ Project uses the Gradle wrapper from the repo root.\n  Use .\\\\gradlew.bat test to run JVM tests.',
                      inspectorCallParts: <ChatRunTraceInspectorTextPart>[
                        ChatRunTraceInspectorTextPart(
                          text: 'Read',
                          semantic: ChatRunTraceInspectorTextSemantic.action,
                        ),
                        ChatRunTraceInspectorTextPart(text: ' '),
                        ChatRunTraceInspectorTextPart(
                          text: 'README.md',
                          semantic: ChatRunTraceInspectorTextSemantic.target,
                        ),
                        ChatRunTraceInspectorTextPart(text: ' '),
                        ChatRunTraceInspectorTextPart(
                          text: 'lines 5-6',
                          semantic: ChatRunTraceInspectorTextSemantic.scope,
                        ),
                      ],
                      inspectorResultBody:
                          'Project uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
                    ),
                  ],
                ),
              ],
          pendingApprovals: pendingApprovals,
          composer:
              composer ??
              ChatComposerState(
                placeholder: copy.chatComposerPlaceholder,
                todos: todos,
              ),
          drawer:
              drawer ??
              const ChatSessionsDrawerState(
                eyebrow: 'Recent sessions',
                title: 'Recent sessions',
                ctaLabel: 'New session',
                sessions: <ChatSessionListItemData>[],
              ),
          drawerOpen: drawerOpen,
        ),
      ),
    ),
  );
}

Future<void> openRunTraceFullscreen(
  WidgetTester tester,
  Finder bubbleFinder,
) async {
  final Iterable<GestureDetector> gestures = tester
      .widgetList<GestureDetector>(
        find.descendant(
          of: bubbleFinder,
          matching: find.byType(GestureDetector),
        ),
      )
      .where((gesture) => gesture.onDoubleTap != null);
  final GestureDetector detector = gestures.first;
  detector.onDoubleTap!.call();
  await tester.pumpAndSettle();
}

OpenCrayChatSnapshot hostChatSnapshot({
  List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals =
      const <OpenCrayChatPendingApprovalSnapshot>[],
  List<OpenCrayChatTodoSnapshot> todos = const <OpenCrayChatTodoSnapshot>[],
  String todoState = 'empty',
  int? todoHideDelayMs,
  int? todoCompletedAtEpochMs,
  int updatedAtEpochMs = 0,
  List<OpenCrayChatMessageSnapshot>? messages,
  OpenCrayChatDrawerSnapshot? drawer,
  OpenCrayChatRuntimeSnapshot? runtimeActivity,
}) {
  return OpenCrayChatSnapshot(
    screenTitle: 'Chat',
    modeLabel: 'SAFE',
    sessionButtonLabel: 'Sessions',
    composerPlaceholder: 'Message OpenCray',
    summary: OpenCrayChatSummarySnapshot(
      title: 'Session',
      badge: '1 message',
      body: 'Reply in progress',
    ),
    messages:
        messages ??
        <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Inspect the workspace.',
          ),
        ],
    drawer:
        drawer ??
        OpenCrayChatDrawerSnapshot(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: <OpenCrayChatSessionItemSnapshot>[],
        ),
    isInputEnabled: true,
    todos: todos,
    todoState: todoState,
    todoHideDelayMs: todoHideDelayMs,
    todoCompletedAtEpochMs: todoCompletedAtEpochMs,
    pendingApprovals: pendingApprovals,
    runtimeActivity: runtimeActivity,
    updatedAtEpochMs: updatedAtEpochMs,
  );
}

double topYForDescendantText(WidgetTester tester, Finder scope, String text) {
  final Finder finder = find.descendant(
    of: scope,
    matching: find.textContaining(text),
  );
  expect(finder, findsWidgets);
  return tester.getTopLeft(finder.first).dy;
}

ScrollController mainChatScrollController(WidgetTester tester) {
  final Finder finder = find.byWidgetPredicate(
    (Widget widget) =>
        widget is SingleChildScrollView &&
        widget.controller != null &&
        widget.scrollDirection == Axis.vertical,
  );
  expect(finder, findsWidgets);
  return tester.widget<SingleChildScrollView>(finder.first).controller!;
}

List<OpenCrayChatMessageSnapshot> streamingScrollMessages() {
  return List<OpenCrayChatMessageSnapshot>.generate(34, (int index) {
    final bool outbound = index.isEven;
    return OpenCrayChatMessageSnapshot(
      messageId: index == 33
          ? 'scroll-anchor-message'
          : 'scroll-message-$index',
      kind: outbound ? 'outbound' : 'inbound',
      text:
          'Scrollable message ${index + 1}\n'
          'Line A keeps this thread tall enough to scroll.\n'
          'Line B keeps this thread tall enough to scroll.',
      createdAtEpochMs: 1000 + index,
    );
  });
}

String streamingProcessOutput(int lineCount) {
  return List<String>.generate(
    lineCount,
    (int index) => 'streamed stdout line ${index + 1}',
  ).join('\n');
}

OpenCrayChatRuntimeSnapshot streamingProcessRuntimeSnapshot({
  required String output,
  required int updatedAtEpochMs,
}) {
  return OpenCrayChatRuntimeSnapshot(
    sessionId: 'session-scroll',
    activeRuns: <OpenCrayChatRunSnapshot>[
      OpenCrayChatRunSnapshot(
        sessionId: 'session-scroll',
        runId: 'run-scroll',
        taskId: 'task-scroll',
        acceptedAtEpochMs: 1500,
        updatedAtEpochMs: updatedAtEpochMs,
        attempt: 1,
        pendingMessageId: 'scroll-anchor-message',
        isTerminal: false,
        managedProcessIds: const <String>['process-scroll'],
        managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
          OpenCrayChatManagedProcessSnapshot(
            processId: 'process-scroll',
            status: 'running',
            command: 'python',
            args: const <String>['script.py'],
            processStarted: true,
            startedAtEpochMs: 1500,
            updatedAtEpochMs: updatedAtEpochMs,
            stdout: output,
          ),
        ],
        runningManagedProcessCount: 1,
        hasLiveManagedProcesses: true,
      ),
    ],
    events: const <OpenCrayChatRuntimeEventSnapshot>[],
    updatedAtEpochMs: updatedAtEpochMs,
  );
}

class FakeChatBridge implements OpenCrayHostBridge {
  FakeChatBridge({
    required this.chatSnapshot,
    required this.runtimeSnapshot,
    this.imagePreviews = const <String, OpenCrayFileImagePreview>{},
    this.textPreviews = const <String, OpenCrayFileTextPreview>{},
    this.voicePlaybackSources =
        const <String, OpenCrayFileVoicePlaybackSource>{},
    this.chatSnapshotStream,
    this.runtimeSnapshotStream,
    this.liveAssistantDraftEventStream,
    this.runtimeEventDeltaStream,
    this.onRecallChatMessage,
    OpenCraySandboxSettingsSnapshot? sandboxSettings,
  }) : sandboxSettings =
           sandboxSettings ??
           const OpenCraySandboxSettingsSnapshot(
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

  final OpenCrayChatSnapshot chatSnapshot;
  OpenCrayChatRuntimeSnapshot runtimeSnapshot;
  final Map<String, OpenCrayFileImagePreview> imagePreviews;
  final Map<String, OpenCrayFileTextPreview> textPreviews;
  final Map<String, OpenCrayFileVoicePlaybackSource> voicePlaybackSources;
  final Stream<OpenCrayChatSnapshot>? chatSnapshotStream;
  final Stream<OpenCrayChatRuntimeSnapshot>? runtimeSnapshotStream;
  final Stream<OpenCrayChatLiveAssistantDraftEvent>?
  liveAssistantDraftEventStream;
  final Stream<OpenCrayChatRuntimeEventDelta>? runtimeEventDeltaStream;
  final Future<void> Function(String sessionId, String messageId)?
  onRecallChatMessage;
  OpenCraySandboxSettingsSnapshot sandboxSettings;
  final List<String> approvedApprovalIds = <String>[];
  final List<String> cancelledRunIds = <String>[];
  final List<String> rejectedApprovalIds = <String>[];
  final List<String> retriedRunIds = <String>[];
  final List<String> loadedTextPreviews = <String>[];
  final List<String> loadedVoicePlaybackSources = <String>[];
  final List<String> openedWorkspaceEntries = <String>[];
  final List<String> openedExternalUris = <String>[];
  final List<List<String>> sharedWorkspaceEntries = <List<String>>[];
  final List<SavedWorkspaceMediaRequest> savedWorkspaceMediaAttachments =
      <SavedWorkspaceMediaRequest>[];
  final List<String> shownNativeToasts = <String>[];
  int createChatSessionCallCount = 0;
  Completer<void>? createChatSessionCompleter;
  final List<String> copiedSessionIds = <String>[];
  final List<String> deletedSessionIds = <String>[];
  final List<String> deletedMessageIds = <String>[];
  final List<String> recalledMessageIds = <String>[];
  final List<String> selectedSessionIds = <String>[];
  Object? pickChatAttachmentsError;
  Object? submitChatMessageError;
  final Map<OpenCrayChatDraftAttachmentKind, List<OpenCrayChatDraftAttachment>>
  pickedAttachmentsByKind =
      <OpenCrayChatDraftAttachmentKind, List<OpenCrayChatDraftAttachment>>{};
  final List<String> submittedMessages = <String>[];
  final List<List<OpenCrayChatDraftAttachment>> submittedAttachments =
      <List<OpenCrayChatDraftAttachment>>[];
  final List<OpenCraySandboxSettingsSnapshot> savedSandboxSettings =
      <OpenCraySandboxSettingsSnapshot>[];
  int loadChatSnapshotCallCount = 0;
  int loadChatRuntimeSnapshotCallCount = 0;
  Object? loadChatRuntimeSnapshotError;
  int refreshSandboxSessionInfoCallCount = 0;
  int resolveSandboxPreviewEmbedConfigCallCount = 0;
  Completer<OpenCrayChatSnapshot>? loadChatSnapshotCompleter;
  Completer<void>? refreshSandboxSessionInfoCompleter;
  Object? refreshSandboxSessionInfoError;
  Object? resolveSandboxPreviewEmbedConfigError;
  OpenCraySandboxPreviewEmbedConfig? sandboxPreviewEmbedConfig;

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) async {}

  @override
  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  ) async {
    final preview = imagePreviews[relativePath];
    if (preview != null) {
      return preview;
    }
    throw StateError('Missing image preview for $relativePath');
  }

  @override
  Future<OpenCraySandboxPreviewEmbedConfig> resolveSandboxPreviewEmbedConfig(
    String previewUrl,
  ) async {
    resolveSandboxPreviewEmbedConfigCallCount += 1;
    final error = resolveSandboxPreviewEmbedConfigError;
    if (error != null) {
      throw error;
    }
    return sandboxPreviewEmbedConfig ??
        OpenCraySandboxPreviewEmbedConfig(
          previewUrl: previewUrl,
          providerId: 'e2b',
          headers: const <String, String>{},
          sessionMatched: true,
          accessTokenConfigured: false,
        );
  }

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async {
    loadedTextPreviews.add(relativePath);
    final preview = textPreviews[relativePath];
    if (preview != null) {
      return preview;
    }
    throw StateError('Missing text preview for $relativePath');
  }

  @override
  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  ) async {
    loadedVoicePlaybackSources.add(relativePath);
    final source = voicePlaybackSources[relativePath];
    if (source != null) {
      return source;
    }
    throw StateError('Missing voice playback source for $relativePath');
  }

  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async {
    loadChatSnapshotCallCount += 1;
    final completer = loadChatSnapshotCompleter;
    if (completer != null) {
      return completer.future;
    }
    return chatSnapshot;
  }

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() =>
      chatSnapshotStream ?? Stream<OpenCrayChatSnapshot>.empty();

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async {
    loadChatRuntimeSnapshotCallCount += 1;
    final error = loadChatRuntimeSnapshotError;
    if (error != null) {
      throw error;
    }
    return runtimeSnapshot;
  }

  @override
  Future<OpenCraySandboxSettingsSnapshot> loadSandboxSettings() async =>
      sandboxSettings;

  @override
  Future<OpenCraySandboxSettingsSnapshot> saveSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  ) async {
    sandboxSettings = snapshot;
    savedSandboxSettings.add(snapshot);
    return sandboxSettings;
  }

  @override
  Future<OpenCrayStrongBackgroundSnapshot>
  loadStrongBackgroundSnapshot() async =>
      const OpenCrayStrongBackgroundSnapshot(
        source: 'strong-background',
        available: false,
        tierId: 'baseline',
        setupComplete: false,
        recommendedActionIds: <String>[],
        notifications: OpenCrayStrongBackgroundNotificationsSnapshot(),
        exactAlarms: OpenCrayStrongBackgroundExactAlarmSnapshot(),
        batteryOptimization:
            OpenCrayStrongBackgroundBatteryOptimizationSnapshot(),
        actions: <OpenCrayStrongBackgroundActionSnapshot>[],
      );

  @override
  Future<OpenCrayStrongBackgroundActionResult> performStrongBackgroundAction(
    String actionId,
  ) async => OpenCrayStrongBackgroundActionResult(
    source: 'strong-background-action',
    actionId: actionId,
    available: false,
    launched: false,
  );

  @override
  Future<List<OpenCraySettingsImageAsset>> listSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> importSettingsImageAssets(
    List<String> uriStrings,
  ) async => const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCrayAgentSnapshot>> listAgents() async =>
      const <OpenCrayAgentSnapshot>[];

  @override
  Future<OpenCrayAgentSnapshot?> loadActiveAgent() async => null;

  @override
  Future<OpenCrayAgentSnapshot> createAgent(
    OpenCrayAgentCreateRequest request,
  ) async => throw UnimplementedError();

  @override
  Future<OpenCrayAgentSnapshot?> selectAgent(String agentId) async => null;

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() =>
      runtimeSnapshotStream ?? Stream<OpenCrayChatRuntimeSnapshot>.empty();

  @override
  Stream<OpenCrayChatLiveAssistantDraftEvent> watchLiveAssistantDraftEvents() =>
      liveAssistantDraftEventStream ??
      Stream<OpenCrayChatLiveAssistantDraftEvent>.empty();

  @override
  Stream<OpenCrayChatRuntimeEventDelta> watchRuntimeEventDeltas() =>
      runtimeEventDeltaStream ?? Stream<OpenCrayChatRuntimeEventDelta>.empty();

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      const OpenCrayMemoryDebugSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        records: <OpenCrayMemoryDebugRecordSnapshot>[],
      );

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async =>
      const OpenCrayMemoryDebugLinksSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        records: <OpenCrayMemoryDebugLinksEntrySnapshot>[],
      );

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      const OpenCraySoulDebugSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        overlayRecords: <OpenCrayMemoryDebugRecordSnapshot>[],
        fieldSources: <OpenCraySoulFieldSourceSnapshot>[],
      );

  @override
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async => OpenCrayMemoryDebugSearchSnapshot(
    sessionId: '',
    observedAtEpochMs: 0,
    query: query,
  );

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async => OpenCrayMemoryDebugSliceSnapshot(
    sessionId: '',
    observedAtEpochMs: 0,
    path: path,
    startLine: fromLine ?? 1,
    endLine: (fromLine ?? 1) + lines - 1,
  );

  @override
  Future<void> approveChatApproval(String approvalId) async {
    approvedApprovalIds.add(approvalId);
  }

  @override
  Future<void> approveChatApprovalForSession(String approvalId) async {
    approvedApprovalIds.add(approvalId);
  }

  @override
  Future<void> rejectChatApproval(String approvalId) async {
    rejectedApprovalIds.add(approvalId);
  }

  @override
  Future<void> interruptChatRun(String runIdOrTaskId) async {
    cancelledRunIds.add(runIdOrTaskId);
  }

  @override
  Future<void> retryChatRun(String runIdOrTaskId) async {
    retriedRunIds.add(runIdOrTaskId);
  }

  @override
  Future<void> openWorkspaceEntry(String relativePath) async {
    openedWorkspaceEntries.add(relativePath);
  }

  @override
  Future<void> openExternalUri(String uri) async {
    openedExternalUris.add(uri);
  }

  @override
  Future<void> shareWorkspaceEntries(List<String> relativePaths) async {
    sharedWorkspaceEntries.add(List<String>.of(relativePaths));
  }

  @override
  Future<OpenCraySavedWorkspaceMediaAttachment> saveWorkspaceMediaAttachment({
    required String relativePath,
    required String kind,
  }) async {
    savedWorkspaceMediaAttachments.add(
      SavedWorkspaceMediaRequest(relativePath: relativePath, kind: kind),
    );
    return OpenCraySavedWorkspaceMediaAttachment(
      displayName: relativePath.split('/').last,
      collection: kind == 'voice' ? 'recordings' : 'downloads',
    );
  }

  @override
  Future<void> showNativeToast(String message) async {
    shownNativeToasts.add(message);
  }

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async {
    final error = pickChatAttachmentsError;
    if (error != null) {
      throw error;
    }
    return List<OpenCrayChatDraftAttachment>.of(
      pickedAttachmentsByKind[kind] ?? const <OpenCrayChatDraftAttachment>[],
    );
  }

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments =
        const <OpenCrayChatDraftAttachment>[],
  }) async {
    final error = submitChatMessageError;
    if (error != null) {
      throw error;
    }
    submittedMessages.add(text);
    submittedAttachments.add(List<OpenCrayChatDraftAttachment>.of(attachments));
    return const OpenCrayChatRunSubmission(
      sessionId: 'session-1',
      runId: 'run-1',
      taskId: 'task-1',
      acceptedAtEpochMs: 0,
    );
  }

  @override
  Future<void> refreshSandboxSessionInfo() async {
    refreshSandboxSessionInfoCallCount += 1;
    final completer = refreshSandboxSessionInfoCompleter;
    if (completer != null) {
      await completer.future;
    }
    final error = refreshSandboxSessionInfoError;
    if (error != null) {
      throw error;
    }
  }

  @override
  Future<void> createChatSession() async {
    createChatSessionCallCount += 1;
    final completer = createChatSessionCompleter;
    if (completer != null) {
      await completer.future;
    }
  }

  @override
  Future<void> copyChatSession(String sessionId) async {
    copiedSessionIds.add(sessionId);
  }

  @override
  Future<void> deleteChatSession(String sessionId) async {
    deletedSessionIds.add(sessionId);
  }

  @override
  Future<void> deleteChatMessage({
    required String sessionId,
    required String messageId,
  }) async {
    deletedMessageIds.add(messageId);
  }

  @override
  Future<void> recallChatMessage({
    required String sessionId,
    required String messageId,
  }) async {
    recalledMessageIds.add(messageId);
    final handler = onRecallChatMessage;
    if (handler != null) {
      await handler(sessionId, messageId);
    }
  }

  @override
  Future<void> selectChatSession(String sessionId) async {
    selectedSessionIds.add(sessionId);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class SavedWorkspaceMediaRequest {
  const SavedWorkspaceMediaRequest({
    required this.relativePath,
    required this.kind,
  });

  final String relativePath;
  final String kind;
}

class FakeVoicePlaybackLog {
  final List<String> sourcePaths = <String>[];
  final List<Duration> seekPositions = <Duration>[];
  int playCount = 0;
  int pauseCount = 0;
}

class FakeVoicePlaybackController implements ChatVoicePlaybackController {
  FakeVoicePlaybackController(this.log);

  final FakeVoicePlaybackLog log;
  final StreamController<ChatVoicePlaybackSnapshot> _snapshots =
      StreamController<ChatVoicePlaybackSnapshot>.broadcast();
  ChatVoicePlaybackSnapshot _state = const ChatVoicePlaybackSnapshot();

  @override
  ChatVoicePlaybackSnapshot get currentState => _state;

  @override
  Stream<ChatVoicePlaybackSnapshot> get snapshots => _snapshots.stream;

  @override
  Future<void> setSource({required String filePath}) async {
    log.sourcePaths.add(filePath);
    _state = _state.copyWith(
      duration: const Duration(milliseconds: 4200),
      clearError: true,
    );
    _snapshots.add(_state);
  }

  @override
  Future<void> play() async {
    log.playCount += 1;
    _state = _state.copyWith(isPlaying: true, clearError: true);
    _snapshots.add(_state);
  }

  @override
  Future<void> pause() async {
    log.pauseCount += 1;
    _state = _state.copyWith(isPlaying: false, clearError: true);
    _snapshots.add(_state);
  }

  @override
  Future<void> seek(Duration position) async {
    log.seekPositions.add(position);
    _state = _state.copyWith(position: position, clearError: true);
    _snapshots.add(_state);
  }

  @override
  Future<void> dispose() async {
    await _snapshots.close();
  }
}

OpenCrayFileImagePreview fakeImagePreview({
  required String name,
  required String relativePath,
}) {
  return OpenCrayFileImagePreview(
    name: name,
    relativePath: relativePath,
    bytes: base64Decode(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wn7n8sAAAAASUVORK5CYII=',
    ),
    mimeType: 'image/png',
    width: 1,
    height: 1,
  );
}
