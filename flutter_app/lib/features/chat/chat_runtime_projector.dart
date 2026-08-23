// ignore_for_file: annotate_overrides

part of 'chat_feature_screen.dart';

class _TimedChatRunTraceHistoryEntry {
  const _TimedChatRunTraceHistoryEntry({
    required this.sortEpochMs,
    required this.sourceOrder,
    required this.entry,
  });

  final int sortEpochMs;
  final int sourceOrder;
  final ChatRunTraceHistoryEntry entry;
}

class _RuntimeProjectionPatch {
  const _RuntimeProjectionPatch({
    required this.messages,
    required this.runTraces,
  });

  final List<ChatMessageData> messages;
  final List<ChatRunTraceData> runTraces;
}

class _RuntimeProjectedMessagePatch {
  const _RuntimeProjectedMessagePatch({
    required this.anchorMessageId,
    required this.text,
    required this.isStreaming,
  });

  final String anchorMessageId;
  final String text;
  final bool isStreaming;
}

@immutable
class _TodoTraceSummary {
  const _TodoTraceSummary({
    required this.todoCount,
    required this.pendingCount,
    required this.inProgressCount,
    required this.completedCount,
    this.activeTodoContent,
  });

  final int todoCount;
  final int pendingCount;
  final int inProgressCount;
  final int completedCount;
  final String? activeTodoContent;
}

const Map<String, String> _displayToolAliases = <String, String>{
  'workspace_read_file': 'Read',
  'workspace_list_files': 'LS',
  'workspace_write_file': 'Write',
  'workspace_import_file': 'ImportFile',
  'bash': 'Bash',
  'list': 'LS',
  'ls': 'LS',
  'read': 'Read',
  'write': 'Write',
  'grep': 'Grep',
  'glob': 'Glob',
  'websearch': 'WebSearch',
  'webfetch': 'WebFetch',
  'generateimage': 'GenerateImage',
  'imagegenerate': 'GenerateImage',
  'synthesizespeech': 'SynthesizeSpeech',
  'texttospeech': 'SynthesizeSpeech',
  'tts': 'SynthesizeSpeech',
  'edit': 'Edit',
  'multiedit': 'MultiEdit',
  'importfile': 'ImportFile',
  'import': 'ImportFile',
  'importchatattachment': 'import_chat_attachment',
  'searchworkspacedocument': 'search_workspace_document',
  'inspectworkspacepackage': 'inspect_workspace_package',
  'extractworkspacepackage': 'extract_workspace_package',
  'viewworkspacedocument': 'view_workspace_document',
  'viewworkspaceimage': 'view_workspace_image',
  'viewworkspacepdf': 'view_workspace_pdf',
  'todowrite': 'TodoWrite',
  'scheduledtaskcreate': 'ScheduledTaskCreate',
  'scheduled_task_create': 'ScheduledTaskCreate',
  'scheduledtasklist': 'ScheduledTaskList',
  'scheduled_task_list': 'ScheduledTaskList',
  'scheduledtaskget': 'ScheduledTaskGet',
  'scheduled_task_get': 'ScheduledTaskGet',
  'scheduledtaskupdate': 'ScheduledTaskUpdate',
  'scheduled_task_update': 'ScheduledTaskUpdate',
  'scheduledtaskdelete': 'ScheduledTaskDelete',
  'scheduled_task_delete': 'ScheduledTaskDelete',
  'task': 'Task',
  'spawnagent': 'spawn_agent',
  'waitagent': 'wait_agent',
  'sendinput': 'send_input',
  'closeagent': 'close_agent',
  'listsubagents': 'list_subagents',
  'list_handles': 'list_subagents',
  'listhandles': 'list_subagents',
  'processstart': 'ProcessStart',
  'processlist': 'ProcessList',
  'processread': 'ProcessRead',
  'processwait': 'ProcessWait',
  'processterminate': 'ProcessTerminate',
};

const Set<String> _thinkingPlaceholders = <String>{
  'Thinking',
  'Thinking…',
  'Thinking...',
  'OpenCray is thinking...',
  '思考中',
  '思考中…',
  '思考中...',
};

abstract class _ChatRuntimeProjectorDeps {
  OpenCrayUiCopy get copy;
  bool get usesHostBridge;
  List<ChatSessionListItemData> get drawerSessions;
  String get activeSessionId;
  Map<String, Map<String, OpenCrayChatLiveAssistantDraftSnapshot>>
      get liveAssistantDraftOverridesBySession;
  String? get hiddenArchivedTodoFingerprint;
  List<ChatRunTraceData> _mapRunTraces(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
    List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals,
    Set<String> visibleAnchorMessageIds,
  );
  bool _runHasVisibleLiveAssistantDraft({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  });
  bool _hideAssistantPhaseBubble(OpenCrayChatRuntimeEventSnapshot event);
  String get _mainInspectorActorId;
  ChatRunTraceHistoryEntry _mainHistoryEntry({
    required String label,
    required String body,
    String? compactBody,
    bool isHighRisk = false,
    List<ChatRunTraceInspectorTextPart> inspectorCallParts =
        const <ChatRunTraceInspectorTextPart>[],
    String inspectorCallDetail = '',
    String inspectorResultBody = '',
  });
  int _managedProcessSortEpochMs(OpenCrayChatManagedProcessSnapshot process);
  String _managedProcessStatusSummary(
    OpenCrayChatManagedProcessSnapshot process,
  );
  OpenCrayChatRuntimeEventSnapshot? _latestToolContextEvent(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    String? preferredToolName,
  });
  List<OpenCrayChatRuntimeEventSnapshot> _runEventsFor({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  });
  OpenCrayChatRuntimeEventSnapshot? _latestRunTraceEvent(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  );
  String? _canonicalToolName(String? toolName);
  OpenCrayChatRuntimeEventSnapshot? _findPreviousToolCall(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    required int beforeIndex,
    String? toolName,
  });
  int? _findNextToolResultIndex(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    required int afterIndex,
    String? toolName,
  });
  ChatRunTraceHistoryEntry _buildGroupedToolHistoryEntry({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot? toolCallEvent,
    required OpenCrayChatRuntimeEventSnapshot? toolResultEvent,
  });
  ChatRunTraceInspectorTextPart _inspectorAction(String text);
  ChatRunTraceInspectorTextPart _inspectorTarget(String text);
  String _buildToolCallPreviewBody(OpenCrayChatRuntimeEventSnapshot event);
  String _buildToolResultPreviewBody({
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
    required bool waitingApproval,
    required String? runErrorMessage,
  });
  String _buildCompactTraceBody({
    required List<ChatRunTraceHistoryEntry> history,
    required String fallbackBody,
    String? preferredBody,
  });
  String _assistantPhaseEntryLabel(OpenCrayChatRuntimeEventSnapshot event);
  String _buildSupplementPreviewBody(OpenCrayChatRuntimeEventSnapshot event);
  String _buildSupplementHistoryBody(OpenCrayChatRuntimeEventSnapshot event);
  String _buildSubagentPreviewBody(OpenCrayChatRuntimeEventSnapshot event);
  String _buildSubagentHistoryBody(OpenCrayChatRuntimeEventSnapshot event);
  String _subagentTraceLabel(OpenCrayChatRuntimeEventSnapshot event);
  String _supplementTraceLabel();
  String _buildApprovalPreviewBody(OpenCrayChatRuntimeEventSnapshot event);
  String _buildApprovalHistoryBody(OpenCrayChatRuntimeEventSnapshot event);
  String _approvalTraceLabel(OpenCrayChatRuntimeEventSnapshot event);
  String _buildCancellationPreviewBody(OpenCrayChatRuntimeEventSnapshot event);
  String _buildCancellationHistoryBody(OpenCrayChatRuntimeEventSnapshot event);
  String _cancellationTraceLabel(OpenCrayChatRuntimeEventSnapshot event);
  String _buildMemoryRetrievalPreviewBody(
    OpenCrayChatRuntimeEventSnapshot event,
  );
  String _buildMemoryRetrievalHistoryBody(
    OpenCrayChatRuntimeEventSnapshot event,
  );
  String _buildMemoryWritePreviewBody(OpenCrayChatRuntimeEventSnapshot event);
  String _buildMemoryWriteHistoryBody(OpenCrayChatRuntimeEventSnapshot event);
  String _memoryMaintenanceLabel();
  String _toolResultActionSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  });
  String _joinTraceSections(List<String?> sections);
  String _traceSectionLabel({
    required String english,
    required String chinese,
  });
  String? _labeledInlineSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  });
  String? _labeledMultilineSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  });
  String? _nonEmpty(String? value);
  String? _resultMetadataValue(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  );
  List<String> _resultMetadataCsvStrings(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  );
  List<int> _resultMetadataCsvInts(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  );
  int? _resultMetadataInt(OpenCrayChatRuntimeEventSnapshot event, String key);
  bool? _resultMetadataBool(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  );

}

class ChatRuntimeProjector extends _ChatRuntimeProjectorDeps
    with
        _ProjectorMessagesDomain,
        _ProjectorHistoryDomain,
        _ProjectorInspectorDomain {
  ChatRuntimeProjector({
    required this.copy,
    required this.usesHostBridge,
    required this.drawerSessions,
    required this.activeSessionId,
    required this.liveAssistantDraftOverridesBySession,
    required this.hiddenArchivedTodoFingerprint,
  });

  final OpenCrayUiCopy copy;
  final bool usesHostBridge;
  final List<ChatSessionListItemData> drawerSessions;
  final String activeSessionId;
  final Map<String, Map<String, OpenCrayChatLiveAssistantDraftSnapshot>>
      liveAssistantDraftOverridesBySession;
  final String? hiddenArchivedTodoFingerprint;

  String get _mainInspectorActorId => _runTraceMainActorId;
}
