// ignore_for_file: annotate_overrides

part of 'chat_feature_screen.dart';

mixin _ProjectorInspectorDomain on _ChatRuntimeProjectorDeps {
  OpenCrayChatRuntimeEventSnapshot? _latestToolContextEvent(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    String? preferredToolName,
  }) {
    final String? normalizedToolName = _nonEmpty(preferredToolName);
    OpenCrayChatRuntimeEventSnapshot? fallback;
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final candidate = runEvents[index];
      if (candidate.kind != 'tool_call' && candidate.kind != 'tool_result') {
        continue;
      }
      fallback ??= candidate;
      if (normalizedToolName == null) {
        continue;
      }
      final String? candidateToolName = _nonEmpty(candidate.toolName);
      if (candidateToolName == normalizedToolName) {
        return candidate;
      }
    }
    return fallback;
  }

  String? _canonicalToolName(String? toolName) {
    final String? normalizedToolName = toolName?.trim();
    if (normalizedToolName == null || normalizedToolName.isEmpty) {
      return normalizedToolName;
    }
    return _displayToolAliases[normalizedToolName] ?? normalizedToolName;
  }

  OpenCrayChatRuntimeEventSnapshot? _findPreviousToolCall(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    required int beforeIndex,
    String? toolName,
  }) {
    final String? normalizedToolName = _canonicalToolName(toolName);
    for (int index = beforeIndex - 1; index >= 0; index -= 1) {
      final candidate = runEvents[index];
      if (candidate.kind != 'tool_call') {
        continue;
      }
      final String? candidateToolName = _canonicalToolName(candidate.toolName);
      if (normalizedToolName == null ||
          normalizedToolName.isEmpty ||
          candidateToolName == normalizedToolName) {
        return candidate;
      }
    }
    return null;
  }

  int? _findNextToolResultIndex(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    required int afterIndex,
    String? toolName,
  }) {
    final String? normalizedToolName = _canonicalToolName(toolName);
    for (int index = afterIndex + 1; index < runEvents.length; index += 1) {
      final candidate = runEvents[index];
      if (candidate.kind == 'tool_call') {
        return null;
      }
      if (candidate.kind != 'tool_result') {
        if (!_isSkippableToolGroupingInterveningEvent(candidate)) {
          return null;
        }
        continue;
      }
      final String? candidateToolName = _canonicalToolName(candidate.toolName);
      if (normalizedToolName == null || normalizedToolName.isEmpty) {
        return index;
      }
      if (candidateToolName == normalizedToolName) {
        return index;
      }
    }
    return null;
  }

  bool _isSkippableToolGroupingInterveningEvent(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    if (event.kind == 'lifecycle') {
      final String phase = event.phase?.trim().toLowerCase() ?? '';
      return phase.isNotEmpty;
    }
    return event.kind == 'subagent';
  }

  ChatRunTraceHistoryEntry _buildGroupedToolHistoryEntry({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot? toolCallEvent,
    required OpenCrayChatRuntimeEventSnapshot? toolResultEvent,
  }) {
    final _ToolInspectorCallDisplay callDisplay =
        _buildToolInspectorCallDisplay(
          toolName: toolName,
          event: toolCallEvent,
          toolResultEvent: toolResultEvent,
        );
    final String callBody = _joinTraceSections(<String?>[
      callDisplay.text,
      callDisplay.detail,
    ]);
    final String? resultBody = toolResultEvent == null
        ? null
        : _buildGroupedToolResultBody(
            toolName: toolName,
            event: toolResultEvent,
            pairedToolCall: toolCallEvent,
          );
    final String inspectorBody = resultBody == null
        ? callBody
        : '$callBody\n${_indentGroupedToolBlock(resultBody, connector: true)}';
    final String compactBody = toolResultEvent == null
        ? _buildToolCallPreviewBody(
            toolCallEvent ??
                OpenCrayChatRuntimeEventSnapshot(
                  kind: 'tool_call',
                  runId: '',
                  taskId: '',
                  emittedAtEpochMs: 0,
                  toolName: toolName,
                ),
          )
        : _buildToolResultPreviewBody(
            event: toolResultEvent,
            pairedToolCall: toolCallEvent,
            waitingApproval: false,
            runErrorMessage: null,
          );
    return _mainHistoryEntry(
      label: toolName,
      body: inspectorBody,
      compactBody: compactBody,
      isHighRisk:
          toolResultEvent?.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED' ||
          toolResultEvent?.isHighRisk == true,
      inspectorCallParts: callDisplay.parts,
      inspectorCallDetail: callDisplay.detail ?? '',
      inspectorResultBody: resultBody ?? '',
    );
  }

  _ToolInspectorCallDisplay _buildToolInspectorCallDisplay({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot? event,
    required OpenCrayChatRuntimeEventSnapshot? toolResultEvent,
  }) {
    final Map<String, dynamic>? arguments =
        _decodeJsonObject(_nonEmpty(event?.argumentsJson)) ??
        (toolResultEvent == null
            ? null
            : _toolResultArgumentsFallback(
                toolName: toolName,
                event: toolResultEvent,
              ));
    final List<ChatRunTraceInspectorTextPart> parts =
        _toolInspectorCallParts(toolName: toolName, arguments: arguments) ??
        <ChatRunTraceInspectorTextPart>[
          ChatRunTraceInspectorTextPart(
            text: _toolActionSummaryFromArguments(
              toolName: toolName,
              arguments: arguments,
            ),
          ),
        ];
    final String? reason = _nonEmpty(event?.toolReason);
    final String? detail = _toolInspectorCallDetailBody(
      toolName: toolName,
      argumentsJson: _nonEmpty(event?.argumentsJson),
      toolResultEvent: toolResultEvent,
    );
    final String? combinedDetail =
        _joinTraceSections(<String?>[
          reason == null
              ? null
              : copy.isChinese
              ? '理由：$reason'
              : 'Reason: $reason',
          detail,
        ]).trim().isEmpty
        ? null
        : _joinTraceSections(<String?>[
            reason == null
                ? null
                : copy.isChinese
                ? '理由：$reason'
                : 'Reason: $reason',
            detail,
          ]);
    return _ToolInspectorCallDisplay(
      text: _joinInspectorPartText(parts),
      parts: parts,
      detail: combinedDetail,
    );
  }

  List<ChatRunTraceInspectorTextPart>? _toolInspectorCallParts({
    required String toolName,
    required Map<String, dynamic>? arguments,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    switch (canonicalToolName) {
      case 'Read':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        final String range = _readRangeSummary(
          offset: _argumentInt(arguments, 'offset'),
          limit: _argumentInt(arguments, 'limit'),
        );
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '读取' : 'Read'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
          if (range.isNotEmpty) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? '，' : ' '),
            _inspectorScope(range),
          ],
        ];
      case 'LS':
        final String path =
            _argumentString(arguments, 'path') ??
            _argumentString(arguments, 'file_path') ??
            '.';
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '列出' : 'List'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
        ];
      case 'Grep':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return null;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        final String? glob = _argumentString(arguments, 'glob');
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '搜索' : 'Search'),
          _inspectorNeutral(' '),
          _inspectorTarget('"$pattern"'),
          _inspectorNeutral(copy.isChinese ? ' 于 ' : ' in '),
          _inspectorScope(path),
          if (glob != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? '，glob ' : ' (glob: '),
            _inspectorScope(glob),
            if (!copy.isChinese) _inspectorNeutral(')'),
          ],
        ];
      case 'Glob':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return null;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '匹配' : 'Match'),
          _inspectorNeutral(' '),
          _inspectorTarget(pattern),
          _inspectorNeutral(copy.isChinese ? ' 于 ' : ' in '),
          _inspectorScope(path),
        ];
      case 'Write':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '写入' : 'Write'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
        ];
      case 'ImportFile':
        final String? sourcePath = _argumentString(arguments, 'source_path');
        final String? destinationPath = _argumentString(
          arguments,
          'destination_path',
        );
        if (sourcePath == null || destinationPath == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '导入' : 'Import'),
          _inspectorNeutral(' '),
          _inspectorTarget(sourcePath),
          _inspectorNeutral(copy.isChinese ? ' 到 ' : ' to '),
          _inspectorTarget(destinationPath),
        ];
      case 'Edit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '编辑' : 'Edit'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
        ];
      case 'MultiEdit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        final int editCount = _argumentList(arguments, 'edits')?.length ?? 0;
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '编辑' : 'Edit'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
          if (editCount > 0) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? '，共 ' : ' with '),
            _inspectorScope(
              copy.isChinese
                  ? '$editCount 处修改'
                  : '$editCount change${editCount == 1 ? '' : 's'}',
            ),
          ],
        ];
      case 'WebSearch':
        return _webSearchInspectorParts(arguments);
      case 'TodoWrite':
        final _TodoTraceSummary? summary = _todoSummaryFromArguments(arguments);
        if (arguments?.containsKey('todos') != true) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(copy.isChinese ? '读取' : 'Read'),
            _inspectorNeutral(' '),
            _inspectorTarget(
              copy.isChinese ? '当前待办列表' : 'current todo list',
            ),
          ];
        }
        if (summary == null || summary.todoCount <= 0) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(copy.isChinese ? '清空' : 'Clear'),
            _inspectorNeutral(' '),
            _inspectorTarget(
              copy.isChinese ? '当前待办列表' : 'current todo list',
            ),
          ];
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '更新' : 'Update'),
          _inspectorNeutral(' '),
          _inspectorScope(
            copy.isChinese
                ? '${summary.todoCount} 条待办'
                : '${summary.todoCount} todo${summary.todoCount == 1 ? '' : 's'}',
          ),
          if (summary.activeTodoContent !=
              null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? '，当前进行中：' : ', active: '),
            _inspectorTarget(summary.activeTodoContent!),
          ],
        ];
      case 'Task':
        final String? description = _argumentString(arguments, 'description');
        final String actor = _subagentTypeDisplay(
          _argumentString(arguments, 'subagent_type'),
        );
        if (description == null) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(copy.isChinese ? '委派' : 'Delegate'),
            _inspectorNeutral(copy.isChinese ? '给 ' : ' to '),
            _inspectorScope(actor),
          ];
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '委派' : 'Delegate'),
          _inspectorNeutral(copy.isChinese ? '给 ' : ' to '),
          _inspectorScope(actor),
          _inspectorNeutral(copy.isChinese ? '：' : ': '),
          _inspectorTarget(description),
        ];
      default:
        return null;
    }
  }

  ChatRunTraceInspectorTextPart _inspectorNeutral(String text) =>
      ChatRunTraceInspectorTextPart(text: text);

  ChatRunTraceInspectorTextPart _inspectorAction(String text) =>
      ChatRunTraceInspectorTextPart(
        text: text,
        semantic: ChatRunTraceInspectorTextSemantic.action,
      );

  ChatRunTraceInspectorTextPart _inspectorTarget(String text) =>
      ChatRunTraceInspectorTextPart(
        text: text,
        semantic: ChatRunTraceInspectorTextSemantic.target,
      );

  ChatRunTraceInspectorTextPart _inspectorScope(String text) =>
      ChatRunTraceInspectorTextPart(
        text: text,
        semantic: ChatRunTraceInspectorTextSemantic.scope,
      );

  String _joinInspectorPartText(List<ChatRunTraceInspectorTextPart> parts) =>
      parts.map((part) => part.text).join();

  String _buildToolCallPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String resolvedToolName =
        _canonicalToolName(_nonEmpty(event.toolName)) ??
        copy.chatRunWorkingLabel;
    final String summary = _toolActionSummary(
      toolName: resolvedToolName,
      argumentsJson: event.argumentsJson,
    );
    final String? reason = _nonEmpty(event.toolReason);
    final String? detail = _toolCallDetailBody(
      toolName: resolvedToolName,
      argumentsJson: event.argumentsJson,
    );
    return _joinTraceSections(<String?>[summary, reason, detail]);
  }

  String _buildToolResultPreviewBody({
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
    required bool waitingApproval,
    required String? runErrorMessage,
  }) {
    final String resolvedToolName =
        _canonicalToolName(_nonEmpty(event.toolName)) ??
        copy.chatRunWorkingLabel;
    final String summary = _toolResultActionSummary(
      toolName: resolvedToolName,
      event: event,
      pairedToolCall: pairedToolCall,
    );
    final String? resultSummary = _toolResultMetadataSummary(
      toolName: resolvedToolName,
      event: event,
    );
    final String? message =
        (waitingApproval
            ? _nonEmpty(runErrorMessage)
            : _nonEmpty(event.errorMessage)) ??
        _nonEmpty(event.contentPreview);
    return _joinTraceSections(<String?>[
      summary,
      resultSummary,
      message ?? copy.chatRunToolFollowUp(resolvedToolName),
    ]);
  }

  String _buildCompactTraceBody({
    required List<ChatRunTraceHistoryEntry> history,
    required String fallbackBody,
    String? preferredBody,
  }) {
    final List<ChatRunTraceHistoryEntry> entries = history
        .where(_shouldIncludeCompactHistoryEntry)
        .toList(growable: false);
    if (entries.isEmpty) {
      return fallbackBody;
    }
    final String? preferred = (() {
      final String trimmed = preferredBody?.trim() ?? '';
      return trimmed.isEmpty ? null : trimmed;
    })();
    final int endExclusive = preferred == null
        ? entries.length
        : entries.lastIndexWhere(
                (entry) => _historyCompactBody(entry) == preferred,
              ) +
              1;
    final int boundedEndExclusive = endExclusive > 0
        ? endExclusive
        : entries.length;
    final int startIndex = boundedEndExclusive > 3
        ? boundedEndExclusive - 3
        : 0;
    final String compactBody = entries
        .sublist(startIndex, boundedEndExclusive)
        .map((entry) => _historyCompactBody(entry))
        .where((body) => body.isNotEmpty)
        .join('\n\n');
    return compactBody.trim().isNotEmpty ? compactBody.trim() : fallbackBody;
  }

  bool _shouldIncludeCompactHistoryEntry(ChatRunTraceHistoryEntry entry) {
    final String body = _historyCompactBody(entry);
    if (body.isEmpty) {
      return false;
    }
    return !_thinkingPlaceholders.contains(body);
  }

  String _historyCompactBody(ChatRunTraceHistoryEntry entry) =>
      (entry.compactBody?.trim().isNotEmpty == true
              ? entry.compactBody!
              : entry.body)
          .trim();

  String _buildSupplementPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String? text = _nonEmpty(event.text);
    final String? checkpoint = _supplementCheckpointSummary(event);
    return _joinTraceSections(<String?>[
      text ?? checkpoint,
      if (text != null) checkpoint,
    ]);
  }

  String _buildSupplementHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String? text = _nonEmpty(event.text);
    final String? checkpoint = _supplementCheckpointSummary(event);
    return _joinTraceSections(<String?>[
      text ?? checkpoint,
      if (text != null) checkpoint,
    ]);
  }

  String _buildSubagentPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _subagentPhaseSummary(event),
      _subagentControlSection(event),
      _subagentSummarySection(event),
      _subagentMailboxSection(event),
      _subagentContextSection(event),
    ]);
  }

  String _buildSubagentHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _subagentPhaseSummary(event),
      _subagentContextSection(event),
      _subagentControlSection(event),
      _subagentMailboxSection(event),
      _subagentSummarySection(event),
    ]);
  }

  String _subagentTraceLabel(OpenCrayChatRuntimeEventSnapshot event) {
    final String? type = _nonEmpty(event.subagentType);
    if (type != null) {
      return _subagentTypeDisplay(type);
    }
    return _nonEmpty(event.label) ??
        (copy.isChinese ? '子代理' : 'Subagent');
  }

  String _supplementTraceLabel() =>
      copy.isChinese ? '补充输入' : 'Follow-up';

  String? _supplementCheckpointSummary(OpenCrayChatRuntimeEventSnapshot event) {
    return switch (_nonEmpty(event.checkpoint)?.toLowerCase()) {
      'turn_start' =>
        copy.isChinese ? '在轮次开始时应用' : 'Applied at turn start',
      'post_tool_pre_model' =>
        copy.isChinese ? '在工具结果之后应用' : 'Applied after tool result',
      null => null,
      final String checkpoint =>
        copy.isChinese
            ? '应用检查点: ${checkpoint.replaceAll('_', ' ')}'
            : 'Applied at ${checkpoint.replaceAll('_', ' ')}',
    };
  }

  String _subagentPhaseSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final String actor = _subagentTraceLabel(event);
    final String? description = _nonEmpty(event.label);
    final String suffix = description == null || description == actor
        ? ''
        : copy.isChinese
        ? '：$description'
        : ': $description';
    final String? executionStateSummary = _subagentPhaseStateOverrideSummary(
      actor: actor,
      executionState: _subagentExecutionState(event),
    );
    if (executionStateSummary != null) {
      return '$executionStateSummary$suffix';
    }
    switch (_nonEmpty(event.phase)?.toLowerCase()) {
      case 'started':
        return copy.isChinese
            ? '$actor 已启动$suffix'
            : '$actor started$suffix';
      case 'resumed':
        return copy.isChinese
            ? '$actor 已继续$suffix'
            : '$actor resumed$suffix';
      case 'completed':
        return copy.isChinese
            ? '$actor 已完成$suffix'
            : '$actor completed$suffix';
      case 'failed':
        return copy.isChinese
            ? '$actor 失败$suffix'
            : '$actor failed$suffix';
      case 'cancelled':
        return copy.isChinese
            ? '$actor 已取消$suffix'
            : '$actor cancelled$suffix';
      default:
        return copy.isChinese
            ? '$actor 已更新$suffix'
            : '$actor updated$suffix';
    }
  }

  String? _subagentContextSection(OpenCrayChatRuntimeEventSnapshot event) {
    final List<String> lines = <String>[
      if (_nonEmpty(event.contextMode) != null)
        '${_traceSectionLabel(english: 'Context', chinese: '上下文')}: ${_contextModeDisplay(event.contextMode!)}',
      if (event.depth != null)
        '${_traceSectionLabel(english: 'Depth', chinese: '深度')}: ${event.depth}',
      if (_subagentContinuationSummary(event) != null)
        '${_traceSectionLabel(english: 'Continuation', chinese: '继续方式')}: ${_subagentContinuationSummary(event)!}',
    ];
    return lines.isEmpty ? null : lines.join('\n');
  }

  String? _subagentSummarySection(OpenCrayChatRuntimeEventSnapshot event) {
    final String? summary = _nonEmpty(event.text);
    if (summary == null) {
      return null;
    }
    final String label = _traceSectionLabel(english: 'Summary', chinese: '摘要');
    return summary.contains('\n') ? '$label:\n$summary' : '$label: $summary';
  }

  String? _subagentControlSection(OpenCrayChatRuntimeEventSnapshot event) {
    final bool? hasActiveExecution = _resultMetadataBool(
      event,
      'hasActiveExecution',
    );
    final bool? hasPendingApprovalResume = _resultMetadataBool(
      event,
      'hasPendingApprovalResume',
    );
    final bool pendingApprovalIsHighRisk =
        _resultMetadataBool(event, 'pendingApprovalIsHighRisk') == true;
    final String? pendingApprovalToolName = _resultMetadataValue(
      event,
      'pendingApprovalToolName',
    );
    final String? pendingApprovalChildRunId = _resultMetadataValue(
      event,
      'pendingApprovalChildRunId',
    );
    final String? pendingApprovalChildTaskId = _resultMetadataValue(
      event,
      'pendingApprovalChildTaskId',
    );
    final List<String> lines = <String>[
      if (hasActiveExecution == true)
        copy.isChinese
            ? '执行: 当前有活动运行'
            : 'Execution: active',
      if (hasPendingApprovalResume == true)
        copy.isChinese
            ? '审批: ${pendingApprovalIsHighRisk ? '高风险待批' : '待批恢复'}${pendingApprovalToolName == null ? '' : ' ($pendingApprovalToolName)'}'
            : 'Approval: ${pendingApprovalIsHighRisk ? 'high risk pending' : 'pending resume'}${pendingApprovalToolName == null ? '' : ' ($pendingApprovalToolName)'}',
      if (pendingApprovalChildRunId != null || pendingApprovalChildTaskId != null)
        copy.isChinese
            ? '审批子任务: ${[
                if (pendingApprovalChildRunId != null) 'run $pendingApprovalChildRunId',
                if (pendingApprovalChildTaskId != null) 'task $pendingApprovalChildTaskId',
              ].join(' / ')}'
            : 'Approval child: ${[
                if (pendingApprovalChildRunId != null) 'run $pendingApprovalChildRunId',
                if (pendingApprovalChildTaskId != null) 'task $pendingApprovalChildTaskId',
              ].join(' / ')}',
    ];
    return lines.isEmpty ? null : lines.join('\n');
  }

  String? _subagentMailboxSection(OpenCrayChatRuntimeEventSnapshot event) {
    final int? total = _resultMetadataInt(event, 'mailboxMessageCount');
    final int? pending = _resultMetadataInt(
      event,
      'mailboxPendingMessageCount',
    );
    final String? lastDelivered = _resultMetadataValue(
      event,
      'mailboxLastDeliveredMessageId',
    );
    if ((total ?? 0) <= 0 && (pending ?? 0) <= 0 && lastDelivered == null) {
      return null;
    }
    final String label = _traceSectionLabel(english: 'Mailbox', chinese: '邮箱');
    final List<String> lines = <String>[
      if (total != null || pending != null)
        copy.isChinese
            ? '$label: ${pending ?? 0} 待投递 / ${total ?? 0} 总计'
            : '$label: ${pending ?? 0} pending / ${total ?? 0} total',
      if (lastDelivered != null)
        copy.isChinese
            ? '最近已投递: $lastDelivered'
            : 'Last delivered: $lastDelivered',
    ];
    return lines.join('\n');
  }

  String _buildGroupedToolResultBody({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String? resultSummary = _toolResultMetadataSummary(
      toolName: toolName,
      event: event,
    );
    final String? errorMessage = _nonEmpty(event.errorMessage);
    final String? content =
        _nonEmpty(event.content) ?? _nonEmpty(event.contentPreview);
    return _joinTraceSections(<String?>[
      resultSummary,
      if (errorMessage != null && errorMessage != resultSummary) errorMessage,
      if (content != null &&
          content != resultSummary &&
          content != errorMessage)
        content,
      if (resultSummary == null && errorMessage == null && content == null)
        _toolResultFallbackSummary(
          toolName: toolName,
          event: event,
          pairedToolCall: pairedToolCall,
        ),
    ]);
  }

  String? _toolInspectorCallDetailBody({
    required String toolName,
    required String? argumentsJson,
    required OpenCrayChatRuntimeEventSnapshot? toolResultEvent,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    final Map<String, dynamic>? arguments =
        _decodeJsonObject(argumentsJson) ??
        (toolResultEvent == null
            ? null
            : _toolResultArgumentsFallback(
                toolName: toolName,
                event: toolResultEvent,
              ));
    if (arguments == null || arguments.isEmpty) {
      return null;
    }
    switch (canonicalToolName) {
      case 'TodoWrite':
        return _todoWriteDetailBody(arguments);
      case 'Edit':
        return _editDetailBody(arguments);
      case 'MultiEdit':
        return _multiEditDetailBody(arguments);
      case 'Write':
        return _writeDetailBody(arguments);
      case 'Task':
        return _taskDetailBody(arguments);
      case 'WebSearch':
        return _webSearchDetailBody(arguments);
      default:
        return null;
    }
  }

  String? _toolResultFallbackSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String previewBody = _buildToolResultPreviewBody(
      event: event,
      pairedToolCall: pairedToolCall,
      waitingApproval: false,
      runErrorMessage: null,
    ).trim();
    if (previewBody.isEmpty) {
      return null;
    }
    final String? resultSummary = _toolResultMetadataSummary(
      toolName: toolName,
      event: event,
    );
    if (resultSummary != null && previewBody == resultSummary) {
      return null;
    }
    return previewBody;
  }

  String _indentGroupedToolBlock(String body, {required bool connector}) {
    final List<String> lines = body
        .replaceAll('\r\n', '\n')
        .replaceAll('\r', '\n')
        .split('\n');
    return lines
        .asMap()
        .entries
        .map((entry) {
          final String prefix = entry.key == 0
              ? (connector ? '  └ ' : '    ')
              : '    ';
          return '$prefix${entry.value}';
        })
        .join('\n');
  }

  String _buildApprovalPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _approvalEventBody(event);
  }

  String _buildApprovalHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _approvalEventBody(event);
  }

  String _approvalEventBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String? text = _nonEmpty(event.text);
    if (text != null) {
      return text;
    }
    switch (_nonEmpty(event.status)?.toLowerCase()) {
      case 'approved':
        return copy.isChinese
            ? '审批已通过，继续执行。'
            : 'Approval granted. The run is resuming.';
      case 'rejected':
        return copy.isChinese
            ? '审批已拒绝，等待下一步指示。'
            : 'Approval rejected. Waiting for the next instruction.';
      default:
        return copy.chatRunWaitingApprovalLabel;
    }
  }

  String _approvalTraceLabel(OpenCrayChatRuntimeEventSnapshot event) {
    if (event.kind == 'approval_wait') {
      return copy.chatRunWaitingApprovalLabel;
    }
    if (_nonEmpty(event.status)?.toLowerCase() == 'rejected') {
      return copy.chatRunAwaitingDirectionLabel;
    }
    return _canonicalToolName(_nonEmpty(event.toolName)) ??
        copy.chatRunWorkingLabel;
  }

  String _buildCancellationPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _cancellationEventBody(event);
  }

  String _buildCancellationHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _cancellationEventBody(event);
  }

  String _cancellationEventBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _nonEmpty(event.text) ??
        (copy.isChinese ? '本次运行已中断。' : 'Run interrupted.');
  }

  String _cancellationTraceLabel(OpenCrayChatRuntimeEventSnapshot event) {
    return copy.chatRunAwaitingDirectionLabel;
  }

  String _buildMemoryRetrievalPreviewBody(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    return _joinTraceSections(<String?>[
      _memoryRetrievalSummary(event),
      _memoryRetrievalResultBody(event, includeQueryTerms: false),
    ]);
  }

  String _buildMemoryRetrievalHistoryBody(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    return _joinTraceSections(<String?>[
      _memoryRetrievalSummary(event),
      _memoryRetrievalResultBody(event, includeQueryTerms: true),
    ]);
  }

  String _buildMemoryWritePreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _memoryWriteSummary(event),
      _memoryWriteResultBody(event, includeKinds: false),
    ]);
  }

  String _buildMemoryWriteHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _memoryWriteSummary(event),
      _memoryWriteResultBody(event, includeKinds: true),
    ]);
  }

  String _memoryRetrievalSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final String operation = event.operation?.trim().toLowerCase() ?? '';
    switch (operation) {
      case 'search':
        final String? query = _nonEmpty(event.query);
        if (query != null) {
          return copy.isChinese
              ? '检索记忆：“$query”'
              : 'Search memory for "$query"';
        }
        return copy.isChinese ? '检索记忆' : 'Search memory';
      case 'get':
        final String? path = _nonEmpty(event.path);
        final String range = _memoryGetRangeSummary(event);
        if (path != null) {
          return copy.isChinese
              ? '读取记忆 $path${range.isNotEmpty ? '，$range' : ''}'
              : 'Read memory $path${range.isNotEmpty ? ' $range' : ''}';
        }
        return copy.isChinese ? '读取记忆片段' : 'Read memory snippet';
      default:
        return copy.isChinese ? '访问记忆' : 'Access memory';
    }
  }

  String _memoryMaintenanceLabel() => copy.isChinese ? '记忆' : 'Memory';

  String _memoryWriteSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final List<String> parts = <String?>[
      _memoryWriteCountLabel(
        count: event.writtenRecordIds.length,
        singular: 'wrote',
        plural: 'wrote',
        chinese: '写入 ${event.writtenRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.resolvedRecordIds.length,
        singular: 'resolved',
        plural: 'resolved',
        chinese: '解决 ${event.resolvedRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.suppressedRecordIds.length,
        singular: 'suppressed',
        plural: 'suppressed',
        chinese: '抑制 ${event.suppressedRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.reaffirmedRecordIds.length,
        singular: 'reaffirmed',
        plural: 'reaffirmed',
        chinese: '续期 ${event.reaffirmedRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.expiredRecordIds.length,
        singular: 'expired',
        plural: 'expired',
        chinese: '过期 ${event.expiredRecordIds.length} 条',
      ),
    ].whereType<String>().toList(growable: false);
    if (parts.isEmpty) {
      return copy.isChinese
          ? '本轮没有记忆变更。'
          : 'No memory changes recorded for this turn.';
    }
    if (copy.isChinese) {
      return '记忆维护：${parts.join('，')}';
    }
    return 'Memory maintenance: ${parts.join(', ')}';
  }

  String _memoryWriteResultBody(
    OpenCrayChatRuntimeEventSnapshot event, {
    required bool includeKinds,
  }) {
    return _joinTraceSections(<String?>[
      _memoryWriteListSection(
        englishLabel: 'Written',
        chineseLabel: '写入',
        values: event.writtenRecordIds,
      ),
      includeKinds
          ? _memoryWriteListSection(
              englishLabel: 'Kinds',
              chineseLabel: '类型',
              values: event.writtenKinds,
            )
          : null,
      _memoryWriteListSection(
        englishLabel: 'Resolved',
        chineseLabel: '解决',
        values: event.resolvedRecordIds,
      ),
      _memoryWriteListSection(
        englishLabel: 'Suppressed',
        chineseLabel: '抑制',
        values: event.suppressedRecordIds,
      ),
      _memoryWriteListSection(
        englishLabel: 'Reaffirmed',
        chineseLabel: '续期',
        values: event.reaffirmedRecordIds,
      ),
      _memoryWriteListSection(
        englishLabel: 'Expired',
        chineseLabel: '过期',
        values: event.expiredRecordIds,
      ),
    ]);
  }

  String? _memoryWriteCountLabel({
    required int count,
    required String singular,
    required String plural,
    required String chinese,
  }) {
    if (count <= 0) {
      return null;
    }
    if (copy.isChinese) {
      return chinese;
    }
    final String noun = count == 1 ? 'record' : 'records';
    final String verb = count == 1 ? singular : plural;
    return '$verb $count $noun';
  }

  String? _memoryWriteListSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  }) {
    if (values.isEmpty) {
      return null;
    }
    final String label = copy.isChinese ? chineseLabel : englishLabel;
    return '$label: ${values.join(', ')}';
  }

  String _memoryRetrievalResultBody(
    OpenCrayChatRuntimeEventSnapshot event, {
    required bool includeQueryTerms,
  }) {
    final String operation = event.operation?.trim().toLowerCase() ?? '';
    switch (operation) {
      case 'search':
        final String? resultSummary = _memorySearchResultSummary(event);
        final String? matchLocations = _memorySearchMatchLocations(event);
        final String? queryTerms =
            includeQueryTerms && event.queryTerms.isNotEmpty
            ? (copy.isChinese
                  ? '关键词：${event.queryTerms.join(', ')}'
                  : 'Query terms: ${event.queryTerms.join(', ')}')
            : null;
        return _joinTraceSections(<String?>[
          resultSummary,
          matchLocations,
          queryTerms,
        ]);
      case 'get':
        return _joinTraceSections(<String?>[
          _memoryGetResultSummary(event),
          includeQueryTerms ? _memoryGetLocationSummary(event) : null,
        ]);
      default:
        return copy.chatRunThinkingActive;
    }
  }

  String? _memorySearchResultSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final int? resultCount = event.resultCount;
    final int? corpusFileCount = event.corpusFileCount;
    if (resultCount == null && corpusFileCount == null) {
      return null;
    }
    if (copy.isChinese) {
      final String resultPart = resultCount == null ? '' : '命中 $resultCount 条';
      final String corpusPart = corpusFileCount == null
          ? ''
          : '覆盖 $corpusFileCount 个记忆文件';
      return <String>[
        resultPart,
        corpusPart,
      ].where((part) => part.isNotEmpty).join('，');
    }
    final String resultPart = resultCount == null
        ? ''
        : resultCount == 1
        ? '1 match'
        : '$resultCount matches';
    final String corpusPart = corpusFileCount == null
        ? ''
        : corpusFileCount == 1
        ? 'across 1 projected file'
        : 'across $corpusFileCount projected files';
    return <String>[
      resultPart,
      corpusPart,
    ].where((part) => part.isNotEmpty).join(' ');
  }

  String? _memorySearchMatchLocations(OpenCrayChatRuntimeEventSnapshot event) {
    if (event.paths.isEmpty && event.lineRanges.isEmpty) {
      return null;
    }
    final int count = event.paths.length > event.lineRanges.length
        ? event.paths.length
        : event.lineRanges.length;
    final List<String> entries = <String>[];
    for (int index = 0; index < count; index += 1) {
      final String? path = index < event.paths.length
          ? _nonEmpty(event.paths[index])
          : null;
      final String? lineRange = index < event.lineRanges.length
          ? _nonEmpty(event.lineRanges[index])
          : null;
      final String entry = switch ((path, lineRange)) {
        (final String p?, final String r?) => '$p#$r',
        (final String p?, null) => p,
        (null, final String r?) => r,
        _ => '',
      };
      if (entry.isNotEmpty) {
        entries.add(entry);
      }
    }
    if (entries.isEmpty) {
      return null;
    }
    return copy.isChinese
        ? '命中位置：\n${entries.join('\n')}'
        : 'Matches:\n${entries.join('\n')}';
  }

  String _memoryGetRangeSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final int? fromLine = event.fromLine;
    final int? returnedLineCount = event.returnedLineCount;
    if (fromLine == null && returnedLineCount == null) {
      return '';
    }
    if (returnedLineCount == null || returnedLineCount <= 0) {
      return copy.isChinese ? '从第 $fromLine 行开始' : 'from line $fromLine';
    }
    final int endLine = fromLine == null
        ? returnedLineCount
        : fromLine + returnedLineCount - 1;
    if (copy.isChinese) {
      return fromLine == null
          ? '共 $returnedLineCount 行'
          : '第 $fromLine-$endLine 行';
    }
    return fromLine == null
        ? '$returnedLineCount lines'
        : 'lines $fromLine-$endLine';
  }

  String? _memoryGetResultSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final int? returnedLineCount = event.returnedLineCount;
    final int? totalLineCount = event.totalLineCount;
    if (returnedLineCount == null && totalLineCount == null) {
      return null;
    }
    if (copy.isChinese) {
      final String returnedPart = returnedLineCount == null
          ? ''
          : '返回 $returnedLineCount 行';
      final String totalPart = totalLineCount == null
          ? ''
          : '文件总计 $totalLineCount 行';
      return <String>[
        returnedPart,
        totalPart,
      ].where((part) => part.isNotEmpty).join('，');
    }
    final String returnedPart = returnedLineCount == null
        ? ''
        : returnedLineCount == 1
        ? 'Returned 1 line'
        : 'Returned $returnedLineCount lines';
    final String totalPart = totalLineCount == null
        ? ''
        : totalLineCount == 1
        ? 'from a 1-line file'
        : 'from a $totalLineCount-line file';
    return <String>[
      returnedPart,
      totalPart,
    ].where((part) => part.isNotEmpty).join(' ');
  }

  String? _memoryGetLocationSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final String? path = _nonEmpty(event.path);
    final String range = _memoryGetRangeSummary(event);
    if (path == null) {
      return null;
    }
    if (range.isEmpty) {
      return path;
    }
    return copy.isChinese ? '$path，$range' : '$path $range';
  }

  String _toolActionSummary({
    required String toolName,
    required String? argumentsJson,
  }) => _toolActionSummaryFromArguments(
    toolName: toolName,
    arguments: _decodeJsonObject(argumentsJson),
  );

  String _toolActionSummaryFromArguments({
    required String toolName,
    required Map<String, dynamic>? arguments,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    final String fallback = copy.chatRunCallingTool(canonicalToolName);
    switch (canonicalToolName) {
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
        return copy.isChinese
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
            : copy.isChinese
            ? '，glob: $glob'
            : ' (glob: $glob)';
        return copy.isChinese
            ? '在 $path 中搜索 "$pattern"$globSuffix'
            : 'Search "$pattern" in $path$globSuffix';
      case 'Glob':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return fallback;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        return copy.isChinese
            ? '在 $path 中匹配 $pattern'
            : 'Match $pattern in $path';
      case 'LS':
        final String path =
            _argumentString(arguments, 'path') ??
            _argumentString(arguments, 'file_path') ??
            '.';
        return copy.isChinese ? '列出 $path' : 'List $path';
      case 'Write':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        return path == null
            ? fallback
            : copy.isChinese
            ? '写入 $path'
            : 'Write $path';
      case 'ImportFile':
        final String? sourcePath = _argumentString(arguments, 'source_path');
        final String? destinationPath = _argumentString(
          arguments,
          'destination_path',
        );
        if (sourcePath == null || destinationPath == null) {
          return fallback;
        }
        return copy.isChinese
            ? '导入 $sourcePath 到 $destinationPath'
            : 'Import $sourcePath to $destinationPath';
      case 'Edit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        return path == null
            ? fallback
            : copy.isChinese
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
          return copy.isChinese ? '批量编辑 $path' : 'MultiEdit $path';
        }
        return copy.isChinese
            ? '对 $path 应用 $editCount 处编辑'
            : 'Apply $editCount edit(s) to $path';
      case 'WebSearch':
        return _webSearchActionSummary(arguments, fallback: fallback);
      case 'TodoWrite':
        final _TodoTraceSummary? summary = _todoSummaryFromArguments(arguments);
        if (arguments?.containsKey('todos') != true) {
          return copy.isChinese ? '读取当前待办列表' : 'Read current todo list';
        }
        return _todoWriteActionSummary(summary: summary, mutated: true);
      case 'Bash':
      case 'command_exec':
        final String? command = _argumentString(arguments, 'command');
        if (command == null) {
          return fallback;
        }
        return copy.isChinese ? '运行命令 $command' : 'Run command $command';
      case 'python_exec':
        final String? scriptPath = _argumentString(arguments, 'script_path');
        if (scriptPath == null) {
          return fallback;
        }
        return copy.isChinese
            ? '运行 Python 脚本 $scriptPath'
            : 'Run Python script $scriptPath';
      case 'ProcessStart':
        final String? scriptPath = _argumentString(arguments, 'script_path');
        final String? command = _argumentString(arguments, 'command');
        if (scriptPath != null) {
          return copy.isChinese
              ? '启动后台 Python 进程 $scriptPath'
              : 'Start background Python process $scriptPath';
        }
        if (command == null) {
          return fallback;
        }
        return copy.isChinese
            ? '启动后台进程 $command'
            : 'Start background process $command';
      case 'ProcessRead':
        final String? processId = _argumentString(arguments, 'process_id');
        if (processId == null) {
          return fallback;
        }
        return copy.isChinese
            ? '读取进程 $processId 的输出'
            : 'Read output for process $processId';
      case 'ProcessWait':
        final String? processId = _argumentString(arguments, 'process_id');
        if (processId == null) {
          return fallback;
        }
        return copy.isChinese
            ? '等待进程 $processId'
            : 'Wait for process $processId';
      case 'ProcessTerminate':
        final String? processId = _argumentString(arguments, 'process_id');
        if (processId == null) {
          return fallback;
        }
        return copy.isChinese
            ? '终止进程 $processId'
            : 'Terminate process $processId';
      case 'WebFetch':
        final String? url = _argumentString(arguments, 'url');
        if (url == null) {
          return fallback;
        }
        return copy.isChinese ? '抓取网页 $url' : 'Fetch $url';
      case 'Task':
        final String? description = _argumentString(arguments, 'description');
        final String? subagentType = _argumentString(
          arguments,
          'subagent_type',
        );
        final String target = subagentType == null
            ? (copy.isChinese ? '子代理' : 'subagent')
            : _subagentTypeDisplay(subagentType);
        if (description == null) {
          return copy.isChinese ? '委派给 $target' : 'Delegate to $target';
        }
        return copy.isChinese
            ? '委派给 $target：$description'
            : 'Delegate to $target: $description';
      default:
        return fallback;
    }
  }

  String _toolResultActionSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String? argumentsJson = _nonEmpty(pairedToolCall?.argumentsJson);
    if (argumentsJson != null) {
      return _toolActionSummary(
        toolName: toolName,
        argumentsJson: argumentsJson,
      );
    }
    if (toolName == 'TodoWrite') {
      return _todoWriteActionSummary(
        summary: _todoSummaryFromResultMetadata(event),
        mutated: _resultMetadataBool(event, 'mutated') == true,
      );
    }
    return _toolActionSummaryFromArguments(
      toolName: toolName,
      arguments: _toolResultArgumentsFallback(toolName: toolName, event: event),
    );
  }

  String? _toolCallDetailBody({
    required String toolName,
    required String? argumentsJson,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    final Map<String, dynamic>? arguments = _decodeJsonObject(argumentsJson);
    if (arguments == null || arguments.isEmpty) {
      return _nonEmpty(argumentsJson);
    }
    switch (canonicalToolName) {
      case 'TodoWrite':
        return _todoWriteDetailBody(arguments);
      case 'Edit':
        return _editDetailBody(arguments);
      case 'MultiEdit':
        return _multiEditDetailBody(arguments);
      case 'Write':
        return _writeDetailBody(arguments);
      case 'Task':
        return _taskDetailBody(arguments);
      case 'WebSearch':
        return _webSearchDetailBody(arguments);
      default:
        return _prettyJson(arguments);
    }
  }

  String _webSearchActionSummary(
    Map<String, dynamic>? arguments, {
    required String fallback,
  }) {
    final String operation = _webSearchOperation(arguments);
    final String? query = _webSearchPrimaryQuery(arguments);
    final String? url = _argumentString(arguments, 'url');
    final String? text = _webSearchFindText(arguments);
    final List<String> domains = _argumentStringList(arguments, 'domains');
    final String domainSuffix = domains.isEmpty
        ? ''
        : copy.isChinese
        ? '，范围 ${domains.join(', ')}'
        : ' within ${domains.join(', ')}';
    switch (operation) {
      case 'open_page':
        if (url == null) {
          return fallback;
        }
        return copy.isChinese
            ? '打开搜索结果页面 $url'
            : 'Open search result page $url';
      case 'find_in_page':
        if (url == null && text == null) {
          return fallback;
        }
        if (copy.isChinese) {
          final String target = text == null ? '' : ' "$text"';
          final String location = url == null ? '' : ' 于 $url';
          return '在页面内搜索$target$location';
        }
        final String target = text == null ? '' : ' "$text"';
        final String location = url == null ? '' : ' in $url';
        return 'Find in page$target$location';
      default:
        if (query == null) {
          return copy.isChinese
              ? '搜索网络$domainSuffix'
              : 'Search the web$domainSuffix';
        }
        return copy.isChinese
            ? '搜索网络 "$query"$domainSuffix'
            : 'Search the web for "$query"$domainSuffix';
    }
  }

  List<ChatRunTraceInspectorTextPart>? _webSearchInspectorParts(
    Map<String, dynamic>? arguments,
  ) {
    final String operation = _webSearchOperation(arguments);
    final String? query = _webSearchPrimaryQuery(arguments);
    final String? url = _argumentString(arguments, 'url');
    final String? text = _webSearchFindText(arguments);
    final List<String> domains = _argumentStringList(arguments, 'domains');
    switch (operation) {
      case 'open_page':
        if (url == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(
            copy.isChinese ? '打开搜索结果页面' : 'Open search result page',
          ),
          _inspectorNeutral(' '),
          _inspectorTarget(url),
        ];
      case 'find_in_page':
        if (url == null && text == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '页内搜索' : 'Find in page'),
          if (text != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(' '),
            _inspectorTarget('"$text"'),
          ],
          if (url != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? ' 于 ' : ' in '),
            _inspectorScope(url),
          ],
        ];
      default:
        if (query == null && domains.isEmpty) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(copy.isChinese ? '搜索网络' : 'Search the web'),
          ];
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(copy.isChinese ? '搜索网络' : 'Search the web'),
          if (query != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? ' ' : ' for '),
            _inspectorTarget('"$query"'),
          ],
          if (domains.isNotEmpty) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(copy.isChinese ? '，范围 ' : ' within '),
            _inspectorScope(domains.join(', ')),
          ],
        ];
    }
  }

  String? _webSearchDetailBody(Map<String, dynamic> arguments) {
    final String? query = _argumentString(arguments, 'query');
    final List<String> queries = <String>{
      if (query != null) query,
      ..._argumentStringList(arguments, 'queries'),
    }.toList(growable: false);
    final List<String> domains = _argumentStringList(arguments, 'domains');
    final List<String> sourceUrls = _argumentStringList(
      arguments,
      'sourceUrls',
    );
    final String? url = _argumentString(arguments, 'url');
    final String? text = _webSearchFindText(arguments);
    final String detail = _joinTraceSections(<String?>[
      _labeledInlineSection(
        englishLabel: 'Queries',
        chineseLabel: '查询',
        values: queries,
      ),
      url == null
          ? null
          : '${_traceSectionLabel(english: 'URL', chinese: '链接')}: $url',
      text == null
          ? null
          : '${_traceSectionLabel(english: 'Text', chinese: '文本')}: $text',
      _labeledInlineSection(
        englishLabel: 'Domains',
        chineseLabel: '域名',
        values: domains,
      ),
      _labeledInlineSection(
        englishLabel: 'Sources',
        chineseLabel: '来源',
        values: sourceUrls,
      ),
    ]).trim();
    return detail.isEmpty ? null : detail;
  }

  String _webSearchOperation(Map<String, dynamic>? arguments) =>
      _argumentString(arguments, 'operation')?.trim().toLowerCase() ?? '';

  String? _webSearchPrimaryQuery(Map<String, dynamic>? arguments) {
    final String? query = _argumentString(arguments, 'query');
    if (query != null) {
      return query;
    }
    final List<String> queries = _argumentStringList(arguments, 'queries');
    return queries.isEmpty ? null : queries.first;
  }

  String? _webSearchFindText(Map<String, dynamic>? arguments) =>
      _argumentString(arguments, 'text') ??
      _argumentString(arguments, 'pattern');

  String? _taskDetailBody(Map<String, dynamic> arguments) {
    final String? prompt = _argumentString(arguments, 'prompt');
    final String? contextMode = _argumentString(arguments, 'context_mode');
    final List<String> allowedTools = _argumentStringList(
      arguments,
      'allowed_tools',
    );
    return _joinTraceSections(<String?>[
      prompt == null
          ? null
          : '${_traceSectionLabel(english: 'Prompt', chinese: '提示')}: $prompt',
      contextMode == null
          ? null
          : '${_traceSectionLabel(english: 'Context', chinese: '上下文')}: ${_contextModeDisplay(contextMode)}',
      _labeledInlineSection(
        englishLabel: 'Allowed tools',
        chineseLabel: '允许工具',
        values: allowedTools,
      ),
    ]);
  }

  String? _todoWriteDetailBody(Map<String, dynamic> arguments) {
    if (!arguments.containsKey('todos')) {
      return null;
    }
    final List<dynamic>? todos = _argumentList(arguments, 'todos');
    if (todos == null || todos.isEmpty) {
      return null;
    }
    final List<String> lines = <String>[];
    for (final dynamic rawTodo in todos) {
      if (rawTodo is! Map) {
        continue;
      }
      final Map<String, dynamic> todo = Map<String, dynamic>.from(
        rawTodo.map((key, value) => MapEntry(key.toString(), value)),
      );
      final String? content = _argumentString(todo, 'content');
      if (content == null) {
        continue;
      }
      final String statusLabel = switch (_argumentString(
        todo,
        'status',
      )?.toLowerCase()) {
        'completed' ||
        'complete' ||
        'done' => copy.isChinese ? '[已完成]' : '[completed]',
        'in_progress' ||
        'in-progress' ||
        'inprogress' => copy.isChinese ? '[进行中]' : '[in_progress]',
        _ => copy.isChinese ? '[待处理]' : '[pending]',
      };
      final String? activeForm =
          _argumentString(todo, 'activeForm') ??
          _argumentString(todo, 'active_form');
      lines.add(
        activeForm == null
            ? '$statusLabel $content'
            : copy.isChinese
            ? '$statusLabel $content | 当前动作：$activeForm'
            : '$statusLabel $content | active: $activeForm',
      );
    }
    return lines.isEmpty ? null : lines.join('\n');
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
        rawEdit.map((key, value) => MapEntry(key.toString(), value)),
      );
      final String? oldString = _argumentString(edit, 'old_string');
      final String? newString = _argumentString(edit, 'new_string');
      if (oldString == null || newString == null) {
        blocks.add(_prettyJson(edit));
        continue;
      }
      blocks.add(
        _joinTraceSections(<String>[
          copy.isChinese ? '编辑 ${index + 1}' : 'Edit ${index + 1}',
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

  String _diffBlock({required String oldString, required String newString}) {
    final List<String> removed = _diffLines(prefix: '-', text: oldString);
    final List<String> added = _diffLines(prefix: '+', text: newString);
    return <String>[...removed, ...added].join('\n');
  }

  List<String> _diffLines({required String prefix, required String text}) {
    final List<String> lines = text
        .replaceAll('\r\n', '\n')
        .replaceAll('\r', '\n')
        .split('\n');
    if (lines.isEmpty) {
      return <String>['$prefix '];
    }
    return lines.map((line) => '$prefix $line').toList(growable: false);
  }

  String _readRangeSummary({required int? offset, required int? limit}) {
    if (offset == null && limit == null) {
      return '';
    }
    if (copy.isChinese) {
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

  Map<String, dynamic>? _toolResultArgumentsFallback({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    switch (canonicalToolName) {
      case 'Read':
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (filePath == null) {
          return null;
        }
        return <String, dynamic>{
          'file_path': filePath,
          if (_resultMetadataInt(event, 'offset') != null)
            'offset': _resultMetadataInt(event, 'offset'),
          if (_resultMetadataInt(event, 'limit') != null)
            'limit': _resultMetadataInt(event, 'limit'),
        };
      case 'LS':
        return <String, dynamic>{
          if (_resultMetadataValue(event, 'path') != null)
            'path': _resultMetadataValue(event, 'path'),
        };
      case 'Grep':
        final String? pattern = _resultMetadataValue(event, 'pattern');
        if (pattern == null) {
          return null;
        }
        return <String, dynamic>{
          'pattern': pattern,
          if (_resultMetadataValue(event, 'path') != null)
            'path': _resultMetadataValue(event, 'path'),
          if (_resultMetadataValue(event, 'glob') != null)
            'glob': _resultMetadataValue(event, 'glob'),
        };
      case 'Glob':
        final String? pattern = _resultMetadataValue(event, 'pattern');
        if (pattern == null) {
          return null;
        }
        return <String, dynamic>{
          'pattern': pattern,
          if (_resultMetadataValue(event, 'path') != null)
            'path': _resultMetadataValue(event, 'path'),
        };
      case 'WebSearch':
        final String? operation = _resultMetadataValue(
          event,
          'providerManagedOperation',
        );
        final String? query = _resultMetadataValue(event, 'query');
        final String? url = _resultMetadataValue(event, 'url');
        final String? text = _resultMetadataValue(event, 'text');
        final List<String> sourceUrls = _csvValues(
          _resultMetadataValue(event, 'sourceUrls'),
        );
        if (operation == null &&
            query == null &&
            url == null &&
            text == null &&
            sourceUrls.isEmpty) {
          return null;
        }
        return <String, dynamic>{
          if (operation != null) 'operation': operation,
          if (query != null) 'query': query,
          if (url != null) 'url': url,
          if (text != null) 'text': text,
          if (sourceUrls.isNotEmpty) 'sourceUrls': sourceUrls,
        };
      case 'Write':
      case 'Edit':
      case 'MultiEdit':
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (filePath == null) {
          return null;
        }
        return <String, dynamic>{'file_path': filePath};
      case 'ImportFile':
        final String? sourcePath = _resultMetadataValue(event, 'sourcePath');
        final String? destinationPath = _resultMetadataValue(
          event,
          'destinationPath',
        );
        if (sourcePath == null || destinationPath == null) {
          return null;
        }
        return <String, dynamic>{
          'source_path': sourcePath,
          'destination_path': destinationPath,
        };
      case 'Task':
        final String? description = _resultMetadataValue(
          event,
          'delegationDescription',
        );
        final String? prompt = _resultMetadataValue(
          event,
          'delegationPromptPreview',
        );
        final String? subagentType =
            _resultMetadataValue(event, 'delegationSubagentType') ??
            _resultMetadataValue(event, 'subagentType');
        final String? contextMode =
            _resultMetadataValue(event, 'delegationContextMode') ??
            _resultMetadataValue(event, 'subagentContextMode');
        final List<String> allowedTools = _csvValues(
          _resultMetadataValue(event, 'delegationAllowedTools'),
        );
        if (description == null &&
            prompt == null &&
            subagentType == null &&
            contextMode == null &&
            allowedTools.isEmpty) {
          return null;
        }
        return <String, dynamic>{
          if (description != null) 'description': description,
          if (prompt != null) 'prompt': prompt,
          if (subagentType != null) 'subagent_type': subagentType,
          if (contextMode != null) 'context_mode': contextMode,
          if (allowedTools.isNotEmpty) 'allowed_tools': allowedTools,
        };
      case 'Bash':
      case 'command_exec':
        final String? command =
            _resultMetadataValue(event, 'commandSummary') ??
            _resultMetadataValue(event, 'command');
        if (command == null) {
          return null;
        }
        return <String, dynamic>{'command': command};
      case 'python_exec':
        final String? scriptPath = _resultMetadataValue(event, 'scriptPath');
        if (scriptPath == null) {
          return null;
        }
        return <String, dynamic>{'script_path': scriptPath};
      case 'ProcessStart':
        final String? processScriptPath = _resultMetadataValue(
          event,
          'scriptPath',
        );
        final String? processCommand =
            _resultMetadataValue(event, 'commandSummary') ??
            _resultMetadataValue(event, 'command');
        if (processScriptPath == null && processCommand == null) {
          return null;
        }
        return <String, dynamic>{
          if (processScriptPath != null) 'script_path': processScriptPath,
          if (processCommand != null) 'command': processCommand,
        };
      case 'ProcessRead':
      case 'ProcessWait':
      case 'ProcessTerminate':
        final String? processId = _resultMetadataValue(event, 'processId');
        if (processId == null) {
          return null;
        }
        return <String, dynamic>{'process_id': processId};
      case 'WebFetch':
        final String? url =
            _resultMetadataValue(event, 'requestedUrl') ??
            _resultMetadataValue(event, 'finalUrl') ??
            _resultMetadataValue(event, 'url');
        if (url == null) {
          return null;
        }
        return <String, dynamic>{'url': url};
      default:
        return null;
    }
  }

  String? _toolResultMetadataSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    switch (canonicalToolName) {
      case 'LS':
        final int? entryCount = _resultMetadataInt(event, 'entryCount');
        final String? path = _resultMetadataValue(event, 'path');
        final bool truncated = _resultMetadataTruncated(event);
        if (entryCount == null) {
          return null;
        }
        if (copy.isChinese) {
          final String summary = path == null
              ? '列出了 $entryCount 项'
              : '在 $path 中列出了 $entryCount 项';
          return truncated ? '$summary，结果已按结果上限截断' : summary;
        }
        final String summary = path == null
            ? 'Listed $entryCount entr${entryCount == 1 ? 'y' : 'ies'}'
            : 'Listed $entryCount entr${entryCount == 1 ? 'y' : 'ies'} in $path';
        return truncated
            ? '$summary. Output truncated at the tool result limit.'
            : summary;
      case 'Read':
        final int? returnedLineCount = _resultMetadataInt(
          event,
          'returnedLineCount',
        );
        final int? totalLineCount = _resultMetadataInt(event, 'totalLineCount');
        final bool truncated = _resultMetadataTruncated(event);
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (returnedLineCount == null &&
            totalLineCount == null &&
            !truncated &&
            filePath == null) {
          return null;
        }
        if (copy.isChinese) {
          final List<String> parts = <String>[
            if (filePath != null) filePath,
            if (returnedLineCount != null) '返回 $returnedLineCount 行',
            if (totalLineCount != null) '文件总计 $totalLineCount 行',
            if (truncated) '结果已按读取预算截断',
          ];
          return parts.join('，');
        }
        final List<String> parts = <String>[
          if (returnedLineCount != null)
            returnedLineCount == 1
                ? 'Returned 1 line'
                : 'Returned $returnedLineCount lines',
          if (filePath != null) 'from $filePath',
          if (totalLineCount != null)
            totalLineCount == 1
                ? '(1-line file)'
                : '($totalLineCount-line file)',
          if (truncated) 'Output truncated to the read budget.',
        ];
        return parts.join(' ');
      case 'Grep':
        final int? matchCount = _resultMetadataInt(event, 'matchCount');
        final String? pattern = _resultMetadataValue(event, 'pattern');
        final String? path = _resultMetadataValue(event, 'path');
        final bool truncated = _resultMetadataTruncated(event);
        if (matchCount == null) {
          return null;
        }
        if (copy.isChinese) {
          final String target = path ?? '.';
          if (pattern == null) {
            final String summary = '在 $target 中找到 $matchCount 处匹配';
            return truncated ? '$summary，结果已按结果上限截断' : summary;
          }
          final String summary = '在 $target 中为 "$pattern" 找到 $matchCount 处匹配';
          return truncated ? '$summary，结果已按结果上限截断' : summary;
        }
        final String target = path ?? '.';
        if (pattern == null) {
          final String summary = matchCount == 1
              ? 'Found 1 match in $target'
              : 'Found $matchCount matches in $target';
          return truncated
              ? '$summary. Output truncated at the tool result limit.'
              : summary;
        }
        final String summary = matchCount == 1
            ? 'Found 1 match for "$pattern" in $target'
            : 'Found $matchCount matches for "$pattern" in $target';
        return truncated
            ? '$summary. Output truncated at the tool result limit.'
            : summary;
      case 'Glob':
        final int? matchCount = _resultMetadataInt(event, 'matchCount');
        final String? pattern = _resultMetadataValue(event, 'pattern');
        final String? path = _resultMetadataValue(event, 'path');
        final bool truncated = _resultMetadataTruncated(event);
        if (matchCount == null) {
          return null;
        }
        if (copy.isChinese) {
          final String target = path ?? '.';
          final String summary = pattern == null
              ? '在 $target 中匹配到 $matchCount 个路径'
              : '在 $target 中为 $pattern 匹配到 $matchCount 个路径';
          return truncated ? '$summary，结果已按结果上限截断' : summary;
        }
        final String target = path ?? '.';
        final String summary = pattern == null
            ? 'Matched $matchCount path(s) in $target'
            : 'Matched $matchCount path(s) for $pattern in $target';
        return truncated
            ? '$summary. Output truncated at the tool result limit.'
            : summary;
      case 'WebSearch':
        final int? sourceCount = _resultMetadataInt(event, 'sourceCount');
        final String? operation = _resultMetadataValue(
          event,
          'providerManagedOperation',
        )?.trim().toLowerCase();
        final String? status = _resultMetadataValue(
          event,
          'providerManagedStatus',
        );
        final String? query = _resultMetadataValue(event, 'query');
        final String? url = _resultMetadataValue(event, 'url');
        final String? text = _resultMetadataValue(event, 'text');
        final bool managed =
            _resultMetadataValue(event, 'providerManaged') == 'true';
        if (sourceCount == null &&
            operation == null &&
            status == null &&
            query == null &&
            url == null &&
            text == null) {
          return null;
        }
        if (copy.isChinese) {
          return <String>[
            if (managed) '原生搜索',
            switch (operation) {
              'open_page' => url == null ? '' : '打开页面 $url',
              'find_in_page' => <String>[
                if (text != null) '页内搜索 "$text"',
                if (url != null) url,
              ].where((part) => part.isNotEmpty).join('，'),
              _ => query == null ? '' : '搜索 "$query"',
            },
            if (sourceCount != null) '来源 $sourceCount 个',
            if (status != null) '状态 $status',
          ].where((part) => part.isNotEmpty).join('，');
        }
        return <String>[
          if (managed) 'Provider-managed search',
          switch (operation) {
            'open_page' => url == null ? '' : 'opened $url',
            'find_in_page' => <String>[
              if (text != null) 'find "$text"',
              if (url != null) 'in $url',
            ].where((part) => part.isNotEmpty).join(' '),
            _ => query == null ? '' : 'search "$query"',
          },
          if (sourceCount != null)
            sourceCount == 1 ? '1 source' : '$sourceCount sources',
          if (status != null) 'status $status',
        ].where((part) => part.isNotEmpty).join(' ');
      case 'Edit':
        final int? replacementCount = _resultMetadataInt(
          event,
          'replacementCount',
        );
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (replacementCount == null) {
          return null;
        }
        if (copy.isChinese) {
          return filePath == null
              ? '应用了 $replacementCount 处替换'
              : '在 $filePath 中应用了 $replacementCount 处替换';
        }
        return filePath == null
            ? 'Applied $replacementCount replacement(s)'
            : 'Applied $replacementCount replacement(s) in $filePath';
      case 'MultiEdit':
        final int? replacementCount = _resultMetadataInt(
          event,
          'replacementCount',
        );
        final int? editCount = _resultMetadataInt(event, 'editCount');
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (replacementCount == null && editCount == null && filePath == null) {
          return null;
        }
        if (copy.isChinese) {
          final List<String> parts = <String>[
            if (filePath != null) filePath,
            if (replacementCount != null) '$replacementCount 处替换',
            if (editCount != null) '$editCount 个编辑块',
          ];
          return parts.isEmpty ? null : '应用了 ${parts.join('，')}';
        }
        final List<String> parts = <String>[
          if (replacementCount != null) '$replacementCount replacement(s)',
          if (editCount != null) 'across $editCount edit(s)',
          if (filePath != null) 'in $filePath',
        ];
        return parts.isEmpty ? null : 'Applied ${parts.join(' ')}';
      case 'ImportFile':
        final String? sourcePath = _resultMetadataValue(event, 'sourcePath');
        final String? destinationPath = _resultMetadataValue(
          event,
          'destinationPath',
        );
        if (sourcePath == null || destinationPath == null) {
          return null;
        }
        return copy.isChinese
            ? '导入 $sourcePath 到 $destinationPath'
            : 'Imported $sourcePath to $destinationPath';
      case 'TodoWrite':
        return _todoWriteResultSummary(event);
      case 'Task':
        final String? executionState = _resultMetadataValue(
          event,
          'childExecutionState',
        );
        final String? status = _resultMetadataValue(
          event,
          'childExecutionStatus',
        );
        final String? subagentType =
            _resultMetadataValue(event, 'delegationSubagentType') ??
            _resultMetadataValue(event, 'subagentType');
        final String? contextMode =
            _resultMetadataValue(event, 'delegationContextMode') ??
            _resultMetadataValue(event, 'subagentContextMode');
        final int? turnCount = _resultMetadataInt(event, 'childTurnCount');
        final int? toolCallCount = _resultMetadataInt(
          event,
          'childToolCallCount',
        );
        final List<String> allowedTools = _csvValues(
          _resultMetadataValue(event, 'delegationAllowedTools'),
        );
        final String actor = _subagentTypeDisplay(subagentType);
        final String statusSummary =
            _subagentExecutionStateSummary(
              actor: actor,
              executionState: executionState,
            ) ??
            switch (status?.toLowerCase()) {
              'success' || 'completed' =>
                copy.isChinese ? '$actor 已完成' : '$actor completed',
              'cancelled' =>
                copy.isChinese ? '$actor 已取消' : '$actor cancelled',
              'approval_required' =>
                copy.isChinese
                    ? '$actor 等待审批'
                    : '$actor waiting for approval',
              'high_risk_approval_required' =>
                copy.isChinese
                    ? '$actor 等待高风险审批'
                    : '$actor waiting for high-risk approval',
              'failed' || 'denied' || 'timeout' =>
                copy.isChinese ? '$actor 失败' : '$actor failed',
              _ =>
                copy.isChinese
                    ? '$actor 已返回结果'
                    : '$actor returned a result',
            };
        final List<String> details = <String>[
          if (contextMode != null)
            copy.isChinese
                ? '上下文 ${_contextModeDisplay(contextMode)}'
                : '${_contextModeDisplay(contextMode)} context',
          if (turnCount != null)
            copy.isChinese
                ? '$turnCount 轮'
                : turnCount == 1
                ? '1 turn'
                : '$turnCount turns',
          if (toolCallCount != null)
            copy.isChinese
                ? '$toolCallCount 次工具调用'
                : toolCallCount == 1
                ? '1 tool call'
                : '$toolCallCount tool calls',
        ];
        final String? allowedToolsSummary = _labeledInlineSection(
          englishLabel: 'Allowed tools',
          chineseLabel: '允许工具',
          values: allowedTools,
        );
        if (details.isEmpty) {
          return _joinTraceSections(<String?>[
            statusSummary,
            allowedToolsSummary,
          ]);
        }
        final String summary = copy.isChinese
            ? '$statusSummary，${details.join('，')}'
            : '$statusSummary. ${details.join(', ')}.';
        return _joinTraceSections(<String?>[summary, allowedToolsSummary]);
      default:
        return null;
    }
  }

  String _todoWriteActionSummary({
    required _TodoTraceSummary? summary,
    required bool mutated,
  }) {
    if (!mutated) {
      return copy.isChinese ? '读取当前待办列表' : 'Read current todo list';
    }
    if (summary == null || summary.todoCount <= 0) {
      return copy.isChinese ? '清空待办列表' : 'Clear the todo list';
    }
    final String? breakdown = _todoBreakdownSummary(summary);
    if (copy.isChinese) {
      final String base = breakdown == null
          ? '更新 ${summary.todoCount} 条待办'
          : '更新 ${summary.todoCount} 条待办（$breakdown）';
      return summary.activeTodoContent == null
          ? base
          : '$base，当前进行中：${summary.activeTodoContent!}';
    }
    final String base = breakdown == null
        ? 'Update ${summary.todoCount} todo(s)'
        : 'Update ${summary.todoCount} todo(s) ($breakdown)';
    return summary.activeTodoContent == null
        ? base
        : '$base, active: ${summary.activeTodoContent!}';
  }

  String? _todoWriteResultSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final _TodoTraceSummary? summary = _todoSummaryFromResultMetadata(event);
    if (summary == null) {
      return null;
    }
    final bool mutated = _resultMetadataBool(event, 'mutated') == true;
    final bool? planChanged = _resultMetadataBool(event, 'planChanged');
    if (!mutated) {
      if (summary.todoCount <= 0) {
        return copy.isChinese
            ? '当前待办列表为空'
            : 'Current todo list is empty';
      }
      final String? breakdown = _todoBreakdownSummary(summary);
      if (copy.isChinese) {
        final String base = breakdown == null
            ? '当前待办列表共 ${summary.todoCount} 项'
            : '当前待办列表共 ${summary.todoCount} 项，$breakdown';
        return summary.activeTodoContent == null
            ? base
            : '$base，当前进行中：${summary.activeTodoContent!}';
      }
      final String base = breakdown == null
          ? 'Current todo list has ${summary.todoCount} item(s)'
          : 'Current todo list has ${summary.todoCount} item(s): $breakdown';
      return summary.activeTodoContent == null
          ? base
          : '$base. Active: ${summary.activeTodoContent!}';
    }
    if (summary.todoCount <= 0) {
      if (copy.isChinese) {
        return planChanged == false ? '待办列表未变化，当前为空' : '待办列表已清空';
      }
      return planChanged == false
          ? 'Plan unchanged. Todo list is empty.'
          : 'Cleared the todo list';
    }
    final int completedDeltaCount =
        _resultMetadataInt(event, 'completedTodoDeltaCount') ?? 0;
    final int addedTodoCount = _resultMetadataInt(event, 'addedTodoCount') ?? 0;
    final int removedTodoCount =
        _resultMetadataInt(event, 'removedTodoCount') ?? 0;
    final int statusChangedTodoCount =
        _resultMetadataInt(event, 'statusChangedTodoCount') ?? 0;
    final int extraStatusChangeCount = math.max(
      0,
      statusChangedTodoCount - completedDeltaCount,
    );
    final List<String> details = <String>[
      if (completedDeltaCount > 0)
        copy.isChinese
            ? '完成 $completedDeltaCount 项'
            : 'completed $completedDeltaCount',
      if (addedTodoCount > 0)
        copy.isChinese
            ? '新增 $addedTodoCount 项'
            : 'added $addedTodoCount',
      if (removedTodoCount > 0)
        copy.isChinese
            ? '移除 $removedTodoCount 项'
            : 'removed $removedTodoCount',
      if (extraStatusChangeCount > 0)
        copy.isChinese
            ? '更新 $extraStatusChangeCount 项状态'
            : 'updated $extraStatusChangeCount status${extraStatusChangeCount == 1 ? '' : 'es'}',
    ];
    if (details.isEmpty) {
      final String? breakdown = _todoBreakdownSummary(summary);
      if (breakdown != null) {
        details.add(breakdown);
      }
    }
    if (copy.isChinese) {
      final String base = planChanged == false ? '待办计划未变化' : '待办计划已更新';
      final String detailText = details.isEmpty
          ? base
          : '$base：${details.join('，')}';
      return summary.activeTodoContent == null
          ? detailText
          : '$detailText，当前进行中：${summary.activeTodoContent!}';
    }
    final String base = planChanged == false
        ? 'Plan unchanged'
        : 'Plan updated';
    final String detailText = details.isEmpty
        ? base
        : '$base: ${details.join(', ')}';
    return summary.activeTodoContent == null
        ? detailText
        : '$detailText. Active now: ${summary.activeTodoContent!}';
  }

  String? _todoBreakdownSummary(_TodoTraceSummary summary) {
    if (summary.todoCount <= 0) {
      return null;
    }
    if (copy.isChinese) {
      return '${summary.pendingCount} 待处理，${summary.inProgressCount} 进行中，${summary.completedCount} 已完成';
    }
    return '${summary.pendingCount} pending, ${summary.inProgressCount} in progress, ${summary.completedCount} completed';
  }

  _TodoTraceSummary? _todoSummaryFromArguments(
    Map<String, dynamic>? arguments,
  ) {
    if (arguments == null || arguments.containsKey('todos') != true) {
      return null;
    }
    final List<dynamic>? todos = _argumentList(arguments, 'todos');
    return _todoSummaryFromTodoList(todos);
  }

  _TodoTraceSummary? _todoSummaryFromResultMetadata(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    final int? todoCount = _resultMetadataInt(event, 'todoCount');
    if (todoCount == null) {
      return null;
    }
    return _TodoTraceSummary(
      todoCount: todoCount,
      pendingCount: _resultMetadataInt(event, 'pendingTodoCount') ?? 0,
      inProgressCount: _resultMetadataInt(event, 'inProgressTodoCount') ?? 0,
      completedCount: _resultMetadataInt(event, 'completedTodoCount') ?? 0,
      activeTodoContent: _resultMetadataValue(event, 'activeTodoContent'),
    );
  }

  _TodoTraceSummary _todoSummaryFromTodoList(List<dynamic>? todos) {
    int pendingCount = 0;
    int inProgressCount = 0;
    int completedCount = 0;
    String? activeTodoContent;
    final List<dynamic> normalizedTodos = todos ?? const <dynamic>[];
    for (final dynamic rawTodo in normalizedTodos) {
      if (rawTodo is! Map) {
        continue;
      }
      final Map<String, dynamic> todo = Map<String, dynamic>.from(
        rawTodo.map((key, value) => MapEntry(key.toString(), value)),
      );
      switch ((_argumentString(todo, 'status') ?? '').trim().toLowerCase()) {
        case 'completed':
        case 'complete':
        case 'done':
          completedCount += 1;
          break;
        case 'in_progress':
        case 'in-progress':
        case 'inprogress':
          inProgressCount += 1;
          activeTodoContent ??= _argumentString(todo, 'content');
          break;
        default:
          pendingCount += 1;
          break;
      }
    }
    return _TodoTraceSummary(
      todoCount: normalizedTodos.length,
      pendingCount: pendingCount,
      inProgressCount: inProgressCount,
      completedCount: completedCount,
      activeTodoContent: activeTodoContent,
    );
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

  String? _subagentExecutionState(OpenCrayChatRuntimeEventSnapshot event) =>
      _nonEmpty(event.executionState) ?? _nonEmpty(event.status);

  String? _subagentExecutionStateSummary({
    required String actor,
    required String? executionState,
  }) {
    return switch (executionState?.toLowerCase()) {
      'background_queued' =>
        copy.isChinese ? '$actor 已在后台排队' : '$actor queued in background',
      'background_running' =>
        copy.isChinese
            ? '$actor 正在后台运行'
            : '$actor running in background',
      'waiting_approval' =>
        copy.isChinese ? '$actor 等待审批' : '$actor waiting for approval',
      'waiting_high_risk_approval' =>
        copy.isChinese
            ? '$actor 等待高风险审批'
            : '$actor waiting for high-risk approval',
      'running' => copy.isChinese ? '$actor 正在运行' : '$actor running',
      'completed' => copy.isChinese ? '$actor 已完成' : '$actor completed',
      'failed' => copy.isChinese ? '$actor 失败' : '$actor failed',
      'cancelled' => copy.isChinese ? '$actor 已取消' : '$actor cancelled',
      _ => null,
    };
  }

  String? _subagentPhaseStateOverrideSummary({
    required String actor,
    required String? executionState,
  }) {
    return switch (executionState?.toLowerCase()) {
      'background_queued' =>
        copy.isChinese ? '$actor 已在后台排队' : '$actor queued in background',
      'background_running' =>
        copy.isChinese
            ? '$actor 正在后台运行'
            : '$actor running in background',
      'waiting_approval' =>
        copy.isChinese ? '$actor 等待审批' : '$actor waiting for approval',
      'waiting_high_risk_approval' =>
        copy.isChinese
            ? '$actor 等待高风险审批'
            : '$actor waiting for high-risk approval',
      _ => null,
    };
  }

  String? _subagentContinuationSummary(OpenCrayChatRuntimeEventSnapshot event) {
    return switch (_nonEmpty(event.continuationKind)?.toLowerCase()) {
      'background_resume' =>
        copy.isChinese ? '后台继续' : 'Resumes in background',
      'prompt_resume' =>
        copy.isChinese ? '审批后继续' : 'Resumes after approval',
      'none' || null => null,
      final String rawValue => rawValue.replaceAll('_', ' '),
    };
  }

  String? _argumentString(
    Map<String, dynamic>? arguments,
    String key, {
    String? fallbackKey,
  }) {
    if (arguments == null) {
      return null;
    }
    final dynamic value =
        arguments[key] ?? (fallbackKey == null ? null : arguments[fallbackKey]);
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

  List<String> _argumentStringList(
    Map<String, dynamic>? arguments,
    String key,
  ) {
    final List<dynamic>? values = _argumentList(arguments, key);
    if (values == null) {
      final String? singleValue = _argumentString(arguments, key);
      return singleValue == null ? const <String>[] : _csvValues(singleValue);
    }
    return values
        .map((value) => value.toString().trim())
        .where((value) => value.isNotEmpty)
        .toList(growable: false);
  }

  List<String> _csvValues(String? value) {
    final String? normalized = _nonEmpty(value);
    if (normalized == null) {
      return const <String>[];
    }
    return normalized
        .split(',')
        .map((entry) => entry.trim())
        .where((entry) => entry.isNotEmpty)
        .toList(growable: false);
  }

  String _prettyJson(Map<String, dynamic> value) =>
      const JsonEncoder.withIndent('  ').convert(value);

  String _joinTraceSections(List<String?> sections) => sections
      .map((section) => section?.trim() ?? '')
      .where((section) => section.isNotEmpty)
      .join('\n\n');

  String _traceSectionLabel({
    required String english,
    required String chinese,
  }) => copy.isChinese ? chinese : english;

  String? _labeledInlineSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  }) {
    if (values.isEmpty) {
      return null;
    }
    final String label = _traceSectionLabel(
      english: englishLabel,
      chinese: chineseLabel,
    );
    return '$label: ${values.join(', ')}';
  }

  String? _labeledMultilineSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  }) {
    final List<String> normalized = values
        .map((value) => value.trim())
        .where((value) => value.isNotEmpty)
        .toList(growable: false);
    if (normalized.isEmpty) {
      return null;
    }
    final String label = _traceSectionLabel(
      english: englishLabel,
      chinese: chineseLabel,
    );
    return '$label:\n${normalized.join('\n')}';
  }

  String? _nonEmpty(String? value) {
    final String normalized = value?.trim() ?? '';
    return normalized.isEmpty ? null : normalized;
  }

  String _subagentTypeDisplay(String? value) {
    final String? normalized = _nonEmpty(value);
    if (normalized == null) {
      return copy.isChinese ? '子代理' : 'Subagent';
    }
    return _humanizeIdentifier(normalized, titleCase: true);
  }

  String _contextModeDisplay(String value) =>
      _humanizeIdentifier(value, titleCase: false);

  String _humanizeIdentifier(String value, {required bool titleCase}) {
    final List<String> parts = value
        .trim()
        .replaceAll(RegExp(r'[_-]+'), ' ')
        .split(RegExp(r'\s+'))
        .where((part) => part.isNotEmpty)
        .toList(growable: false);
    if (parts.isEmpty) {
      return value.trim();
    }
    if (!titleCase) {
      return parts.join(' ');
    }
    return parts
        .map((part) => part[0].toUpperCase() + part.substring(1).toLowerCase())
        .join(' ');
  }

  String? _resultMetadataValue(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) => _nonEmpty(event.resultMetadata[key]);

  List<String> _resultMetadataCsvStrings(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) => (event.resultMetadata[key] ?? '')
      .split(',')
      .map((value) => value.trim())
      .where((value) => value.isNotEmpty)
      .toList(growable: false);

  List<int> _resultMetadataCsvInts(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) => _resultMetadataCsvStrings(
    event,
    key,
  ).map(int.tryParse).whereType<int>().toList(growable: false);

  int? _resultMetadataInt(OpenCrayChatRuntimeEventSnapshot event, String key) =>
      int.tryParse(event.resultMetadata[key]?.trim() ?? '');

  bool? _resultMetadataBool(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) {
    final String value = event.resultMetadata[key]?.trim().toLowerCase() ?? '';
    if (value == 'true') {
      return true;
    }
    if (value == 'false') {
      return false;
    }
    return null;
  }

  bool _resultMetadataTruncated(OpenCrayChatRuntimeEventSnapshot event) {
    return _resultMetadataBool(event, 'resultTruncated') == true ||
        _resultMetadataBool(event, 'truncated') == true;
  }
}
