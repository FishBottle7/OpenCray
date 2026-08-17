part of 'settings_feature.dart';

const String _memoryOperatorSuppressedReason = 'operator_suppressed';

bool _recordHasSoulObjectPayload(OpenCrayMemoryDebugRecordSnapshot record) =>
    record.extensions['soul_object_type']?.trim().isNotEmpty == true;

OpenCrayMemoryDebugLinksEntrySnapshot? _findMemoryDebugLinksEntry(
  OpenCrayMemoryDebugLinksSnapshot? snapshot,
  String recordId,
) {
  if (snapshot == null || recordId.trim().isEmpty) {
    return null;
  }
  for (final entry in snapshot.records) {
    if (entry.recordId == recordId) {
      return entry;
    }
  }
  return null;
}

List<OpenCraySoulFieldSourceSnapshot> _linkedSoulFieldSources(
  OpenCraySoulDebugSnapshot? snapshot,
  String recordId,
) {
  if (snapshot == null || recordId.trim().isEmpty) {
    return const <OpenCraySoulFieldSourceSnapshot>[];
  }
  return _resolvedSoulFieldSources(
    snapshot,
  ).where((source) => source.recordId == recordId).toList(growable: false);
}

List<(String, List<OpenCraySoulFieldSourceSnapshot>)>
_groupLinkedSoulFieldSources(List<OpenCraySoulFieldSourceSnapshot> sources) {
  final grouped = <String, List<OpenCraySoulFieldSourceSnapshot>>{};
  for (final source in sources) {
    final recordId = source.recordId.trim();
    if (recordId.isEmpty) {
      continue;
    }
    grouped.putIfAbsent(recordId, () => <OpenCraySoulFieldSourceSnapshot>[]);
    grouped[recordId]!.add(source);
  }
  final entries = grouped.entries.toList(growable: false)
    ..sort((left, right) => left.key.compareTo(right.key));
  return entries
      .map((entry) => (entry.key, entry.value))
      .toList(growable: false);
}

List<Widget> _buildMemoryLinkDetails({
  required OpenCrayMemoryDebugLinksEntrySnapshot? links,
  required List<OpenCraySoulFieldSourceSnapshot> soulFieldSources,
  required bool includeSoulFieldsSection,
}) {
  final widgets = <Widget>[];

  void addSectionTitle(String title) {
    if (widgets.isNotEmpty) {
      widgets.add(const SizedBox(height: 12));
    }
    widgets.add(Text(title, style: _SettingsTextStyles.bodyStrong));
    widgets.add(const SizedBox(height: 8));
  }

  if (links?.sourceRun != null ||
      (links?.sourceTaskId.isNotEmpty ?? false) ||
      (links?.sourceSessionId.isNotEmpty ?? false)) {
    addSectionTitle('Origin');
    final sourceRun = links?.sourceRun;
    if (sourceRun != null) {
      widgets.add(
        _DebugKeyValueLine('Source run', _formatDebugRunLinkSummary(sourceRun)),
      );
    }
    if ((links?.sourceTaskId.isNotEmpty ?? false) &&
        sourceRun?.taskId != links!.sourceTaskId) {
      widgets.add(_DebugKeyValueLine('Source task', links.sourceTaskId));
    }
    if ((links?.sourceSessionId.isNotEmpty ?? false) &&
        sourceRun?.sessionId != links!.sourceSessionId) {
      widgets.add(_DebugKeyValueLine('Source session', links.sourceSessionId));
    }
  }

  if (includeSoulFieldsSection && soulFieldSources.isNotEmpty) {
    addSectionTitle('Soul fields');
    for (int index = 0; index < soulFieldSources.length; index++) {
      final source = soulFieldSources[index];
      widgets.add(
        _DebugKeyValueLine(
          _debugSoulFieldLabel(source.field),
          _formatSoulFieldSourceSummary(source),
        ),
      );
      if (source.preferenceKey.isNotEmpty) {
        widgets.add(_DebugKeyValueLine('Preference key', source.preferenceKey));
      }
      if (source.sourceDetail.isNotEmpty) {
        widgets.add(_DebugKeyValueLine('Why', source.sourceDetail));
      }
      if (index < soulFieldSources.length - 1) {
        widgets.add(const SizedBox(height: 8));
      }
    }
  }

  if (links != null && links.promptRecalls.isNotEmpty) {
    addSectionTitle('Prompt recall');
    for (final recall in links.promptRecalls) {
      widgets.add(
        _DebugLinkEventRow(
          title:
              '${_formatDebugClockTime(recall.occurredAtEpochMs)} · ${recall.run.runId}',
          detail: _formatPromptRecallLinkDetail(recall),
        ),
      );
      if (recall != links.promptRecalls.last) {
        widgets.add(const SizedBox(height: 10));
      }
    }
  }

  if (links != null && links.toolRetrievals.isNotEmpty) {
    addSectionTitle('Tool retrieval');
    for (final retrieval in links.toolRetrievals) {
      widgets.add(
        _DebugLinkEventRow(
          title:
              '${_formatDebugClockTime(retrieval.occurredAtEpochMs)} · ${retrieval.toolName}',
          detail: _formatToolRetrievalLinkDetail(retrieval),
        ),
      );
      if (retrieval != links.toolRetrievals.last) {
        widgets.add(const SizedBox(height: 10));
      }
    }
  }

  if (links != null && links.maintenanceActions.isNotEmpty) {
    addSectionTitle('Maintenance');
    for (final action in links.maintenanceActions) {
      widgets.add(
        _DebugLinkEventRow(
          title:
              '${_formatDebugClockTime(action.occurredAtEpochMs)} · ${_memoryMaintenanceActionLabel(action.action)}',
          detail: _formatMaintenanceActionLinkDetail(action),
        ),
      );
      if (action != links.maintenanceActions.last) {
        widgets.add(const SizedBox(height: 10));
      }
    }
  }

  if (widgets.isEmpty) {
    widgets.add(
      const Text(
        'No linked run or soul activity is available for this record yet.',
        style: _SettingsTextStyles.body,
      ),
    );
  }
  return widgets;
}

String _formatDebugRunLinkSummary(OpenCrayDebugRunLinkSnapshot run) {
  final state = run.executionStatus?.trim().isNotEmpty == true
      ? run.executionStatus!
      : (run.lifecycleState?.trim().isNotEmpty == true
            ? run.lifecycleState!
            : 'pending');
  return '${run.runId} · ${run.taskId} · $state';
}

String _formatPromptRecallLinkDetail(
  OpenCrayMemoryPromptRecallLinkSnapshot recall,
) {
  final parts = <String>[
    'Task ${recall.run.taskId}',
    'Session ${recall.run.sessionId}',
  ];
  if (recall.score != null) {
    parts.add('score ${recall.score}');
  }
  if (recall.matchedTerms.isNotEmpty) {
    parts.add('matched ${recall.matchedTerms.join(', ')}');
  }
  return parts.join(' · ');
}

String _formatToolRetrievalLinkDetail(
  OpenCrayMemoryToolRetrievalLinkSnapshot retrieval,
) {
  final parts = <String>[
    'Run ${retrieval.run.runId}',
    'Task ${retrieval.run.taskId}',
  ];
  if (retrieval.query?.trim().isNotEmpty == true) {
    parts.add('query ${retrieval.query!.trim()}');
  } else if (retrieval.path?.trim().isNotEmpty == true) {
    parts.add('path ${retrieval.path!.trim()}');
  } else if (retrieval.paths.isNotEmpty) {
    parts.add(retrieval.paths.join(', '));
  }
  if (retrieval.queryTerms.isNotEmpty) {
    parts.add('terms ${retrieval.queryTerms.join(', ')}');
  }
  if (retrieval.lineRanges.isNotEmpty) {
    parts.add('lines ${retrieval.lineRanges.join(', ')}');
  } else if (retrieval.fromLine != null &&
      retrieval.returnedLineCount != null) {
    final endLine = retrieval.fromLine! + retrieval.returnedLineCount! - 1;
    parts.add('lines ${retrieval.fromLine}-$endLine');
  }
  return parts.join(' · ');
}

String _memoryMaintenanceActionLabel(String action) {
  switch (action) {
    case 'written':
      return 'Written';
    case 'resolved':
      return 'Resolved';
    case 'suppressed':
      return 'Suppressed';
    case 'reaffirmed':
      return 'Reaffirmed';
    case 'expired':
      return 'Expired';
    case 'flush_written':
      return 'Flush write';
    default:
      return action;
  }
}

String _formatMaintenanceActionLinkDetail(
  OpenCrayMemoryMaintenanceActionLinkSnapshot action,
) {
  return 'Run ${action.run.runId} · Task ${action.run.taskId} · Session ${action.run.sessionId}';
}

List<String> _collectRecentDebugRunIds(OpenCrayChatRuntimeSnapshot snapshot) {
  final runEpochs = <String, int>{};
  for (final run in snapshot.activeRuns) {
    if (run.runId.trim().isEmpty) {
      continue;
    }
    runEpochs[run.runId] = run.updatedAtEpochMs;
  }
  for (final run in snapshot.retainedRuns) {
    if (run.runId.trim().isEmpty) {
      continue;
    }
    final existingEpoch = runEpochs[run.runId];
    if (existingEpoch == null || run.updatedAtEpochMs > existingEpoch) {
      runEpochs[run.runId] = run.updatedAtEpochMs;
    }
  }
  for (final event in snapshot.events) {
    if (event.runId.trim().isEmpty) {
      continue;
    }
    final existingEpoch = runEpochs[event.runId];
    if (existingEpoch == null || event.emittedAtEpochMs > existingEpoch) {
      runEpochs[event.runId] = event.emittedAtEpochMs;
    }
  }
  final recentRuns = runEpochs.entries.toList(growable: false)
    ..sort((left, right) => right.value.compareTo(left.value));
  return recentRuns
      .map((entry) => entry.key)
      .where((runId) => runId.trim().isNotEmpty)
      .take(8)
      .toList(growable: false);
}

PersonalizationPresetOption? _selectDebugPreset(
  PersonalizationConfigSnapshot snapshot,
) {
  for (final preset in snapshot.presets) {
    if (preset.id == snapshot.selectedPresetId) {
      return preset;
    }
  }
  return snapshot.presets.isEmpty ? null : snapshot.presets.first;
}

PersonalizationLanguageOption? _selectDebugLanguage(
  PersonalizationConfigSnapshot snapshot,
) {
  for (final option in snapshot.appLanguageOptions) {
    if (option.id == snapshot.selectedAppLanguageId || option.isSelected) {
      return option;
    }
  }
  return snapshot.appLanguageOptions.isEmpty
      ? null
      : snapshot.appLanguageOptions.first;
}

bool _isDurableMemoryKind(String kind) =>
    kind == 'user_preference' ||
    kind == 'durable_instruction' ||
    kind == 'project_fact';

String _memoryRecordTitleLine(OpenCrayMemoryDebugRecordSnapshot record) {
  final state = record.isExpired
      ? 'expired'
      : (record.status.isEmpty ? 'unknown' : record.status);
  return '${record.id} · ${record.kind.isEmpty ? 'kind unavailable' : record.kind} · $state';
}

String _memoryRecordSummaryLine(OpenCrayMemoryDebugRecordSnapshot record) {
  final parts = <String>[];
  if (record.scope.isNotEmpty) {
    parts.add('Scope ${record.scope}');
  }
  if (record.preferenceValue.isNotEmpty) {
    parts.add('Preference ${record.preferenceValue}');
  }
  if (record.updatedAtEpochMs > 0) {
    parts.add('Updated ${_formatDebugClockTime(record.updatedAtEpochMs)}');
  }
  if (record.sourceSessionId.isNotEmpty) {
    parts.add('Session ${record.sourceSessionId}');
  }
  if (parts.isEmpty) {
    parts.add(_truncateDebugText(record.content, 90));
  }
  return parts.join(' · ');
}

String _formatMemoryTtl(int ttlMs) {
  if (ttlMs < 60 * 1000) {
    return '${(ttlMs / 1000).round()}s';
  }
  if (ttlMs < 60 * 60 * 1000) {
    return '${(ttlMs / (60 * 1000)).round()}m';
  }
  if (ttlMs < 24 * 60 * 60 * 1000) {
    return '${(ttlMs / (60 * 60 * 1000)).round()}h';
  }
  return '${(ttlMs / (24 * 60 * 60 * 1000)).round()}d';
}

String _formatMemoryLineRange(int startLine, int endLine) {
  if (startLine <= 0 || endLine <= 0) {
    return 'unknown';
  }
  return startLine == endLine ? '$startLine' : '$startLine-$endLine';
}

List<Widget> _buildSoulProfileLines(OpenCraySoulProfileDebugSnapshot snapshot) {
  final lines = <Widget>[];

  void addLine(String label, String value) {
    if (value.trim().isEmpty) {
      return;
    }
    lines.add(_DebugKeyValueLine(label, value));
  }

  addLine('Preset', snapshot.presetName);
  addLine('Display name', snapshot.displayName);
  addLine('Voice', snapshot.voice);
  addLine('Preferred naming', snapshot.preferredNaming);
  addLine('Preferred address style', snapshot.preferredAddressStyle);
  addLine('Warmth offset', snapshot.warmthPreferenceOffset);
  addLine('Formality offset', snapshot.formalityPreferenceOffset);
  addLine('Initiative offset', snapshot.initiativePreferenceOffset);
  addLine('Playfulness offset', snapshot.playfulnessPreferenceOffset);
  addLine('Reassurance offset', snapshot.reassurancePreferenceOffset);
  addLine('Intimacy band', snapshot.intimacyPermissionBand);
  addLine('Playfulness band', snapshot.playfulnessPermissionBand);
  addLine('Supportive reassurance', snapshot.supportiveReassuranceAllowed);
  addLine(
    'Proactive relational check-in',
    snapshot.proactiveRelationalCheckInAllowed,
  );
  addLine('Light playfulness', snapshot.lightPlayfulnessAllowed);
  addLine('Playful teasing', snapshot.playfulTeasingAllowed);
  addLine('High intimacy allowed', snapshot.highIntimacyBehaviorAllowed);
  addLine('Playful affection allowed', snapshot.playfulAffectionAllowed);
  addLine('Tone', snapshot.tone);
  addLine('Verbosity', snapshot.verbosity);
  addLine('Relationship', snapshot.userRelationshipStyle);
  addLine('Risk tolerance', snapshot.riskTolerance);
  addLine('Tool use bias', snapshot.toolUseBias);
  if (snapshot.customGuidance.isNotEmpty) {
    addLine(
      'Custom guidance',
      _truncateDebugText(snapshot.customGuidance, 140),
    );
  }
  if (snapshot.escalationRules.isNotEmpty) {
    addLine('Escalation rules', snapshot.escalationRules.join(' | '));
  }
  if (snapshot.forbiddenBehaviors.isNotEmpty) {
    addLine('Forbidden', snapshot.forbiddenBehaviors.join(' | '));
  }
  if (snapshot.collaborationPreferences.isNotEmpty) {
    addLine('Collaboration', snapshot.collaborationPreferences.join(' | '));
  }
  if (snapshot.extensions.isNotEmpty) {
    addLine(
      'Extensions',
      snapshot.extensions.entries
          .map((entry) => '${entry.key}=${entry.value}')
          .join(' | '),
    );
  }
  if (lines.isEmpty) {
    lines.add(
      const Text(
        'No resolved fields are populated.',
        style: _SettingsTextStyles.body,
      ),
    );
  }
  return lines;
}

List<Widget> _buildInteractionPreferenceDebugLines(
  OpenCrayInteractionPreferenceDebugSnapshot snapshot,
) {
  final lines = <Widget>[_DebugKeyValueLine('Scope', snapshot.scope)];
  if (snapshot.snapshotRecordId.isNotEmpty) {
    lines.add(_DebugKeyValueLine('Snapshot record', snapshot.snapshotRecordId));
  }
  if (snapshot.preferredNaming.isNotEmpty) {
    lines.add(_DebugKeyValueLine('Preferred naming', snapshot.preferredNaming));
  }
  if (snapshot.preferredAddressStyle.isNotEmpty) {
    lines.add(
      _DebugKeyValueLine(
        'Preferred address style',
        snapshot.preferredAddressStyle,
      ),
    );
  }
  if (snapshot.derivedRelationshipStyle.isNotEmpty) {
    lines.add(
      _DebugKeyValueLine(
        'Derived relationship style',
        snapshot.derivedRelationshipStyle,
      ),
    );
  }
  if (snapshot.state.preferredNamingSupport > 0) {
    lines.add(
      _DebugKeyValueLine(
        'Preferred naming support',
        '${snapshot.state.preferredNamingSupport}',
      ),
    );
  }
  lines.add(
    _DebugKeyValueLine(
      'Warmth axis',
      _formatPreferenceAxisSummary(snapshot.state.warmth),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Formality axis',
      _formatPreferenceAxisSummary(snapshot.state.formality),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Initiative axis',
      _formatPreferenceAxisSummary(snapshot.state.initiative),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Playfulness axis',
      _formatPreferenceAxisSummary(snapshot.state.playfulness),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Reassurance axis',
      _formatPreferenceAxisSummary(snapshot.state.reassurance),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Address style state',
      _formatPreferredAddressStateSummary(snapshot.state.addressStyle),
    ),
  );
  if (snapshot.state.lastUpdatedAtEpochMs != null) {
    lines.add(
      _DebugKeyValueLine(
        'Preference updated',
        _formatDebugClockTime(snapshot.state.lastUpdatedAtEpochMs!),
      ),
    );
  }
  return lines;
}

List<Widget> _buildRelationshipStateDebugLines(
  OpenCrayRelationshipStateDebugSnapshot snapshot,
) {
  final lines = <Widget>[_DebugKeyValueLine('Scope', snapshot.scope)];
  if (snapshot.snapshotRecordId.isNotEmpty) {
    lines.add(_DebugKeyValueLine('Snapshot record', snapshot.snapshotRecordId));
  }
  if (snapshot.appliedEventRecordIds.isNotEmpty) {
    lines.add(
      _DebugKeyValueLine(
        'Applied events',
        snapshot.appliedEventRecordIds.join(', '),
      ),
    );
  }
  lines.add(
    _DebugKeyValueLine(
      'Scores',
      'familiarity ${snapshot.state.familiarity}, trust ${snapshot.state.trust}, safety ${snapshot.state.safety}, intimacy ${snapshot.state.intimacyPermission}, playfulness ${snapshot.state.playfulnessPermission}, affection ${snapshot.state.affectionTendency}, reciprocity ${snapshot.state.reciprocity}',
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Bands',
      'intimacy ${snapshot.intimacyPermissionBand}, playfulness ${snapshot.playfulnessPermissionBand}',
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Recent negative guard',
      snapshot.recentNegativeGuardActive ? 'active' : 'inactive',
    ),
  );
  if (snapshot.state.lastPositiveEventAtEpochMs != null) {
    lines.add(
      _DebugKeyValueLine(
        'Last positive event',
        _formatDebugClockTime(snapshot.state.lastPositiveEventAtEpochMs!),
      ),
    );
  }
  if (snapshot.state.lastNegativeEventAtEpochMs != null) {
    lines.add(
      _DebugKeyValueLine(
        'Last negative event',
        _formatDebugClockTime(snapshot.state.lastNegativeEventAtEpochMs!),
      ),
    );
  }
  if (snapshot.state.lastUpdatedAtEpochMs != null) {
    lines.add(
      _DebugKeyValueLine(
        'State updated',
        _formatDebugClockTime(snapshot.state.lastUpdatedAtEpochMs!),
      ),
    );
  }
  if (snapshot.derivedAddressStyle.isNotEmpty) {
    lines.add(
      _DebugKeyValueLine('Derived address style', snapshot.derivedAddressStyle),
    );
  } else {
    lines.add(const _DebugKeyValueLine('Derived address style', 'none'));
  }
  lines.add(
    _DebugKeyValueLine(
      'Supportive style unlock',
      _formatGateVerdict(snapshot.supportiveStyleUnlocked),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Supportive checks',
      _formatSoulGateChecks(snapshot.supportiveStyleChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Warm tone unlock',
      _formatGateVerdict(snapshot.warmToneUnlocked),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Warm tone checks',
      _formatSoulGateChecks(snapshot.warmToneChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Friendly address checks',
      _formatSoulGateChecks(snapshot.friendlyAddressChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Intimate address checks',
      _formatSoulGateChecks(snapshot.intimateAddressChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'High intimacy behavior',
      _formatGateVerdict(snapshot.highIntimacyBehaviorAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'High intimacy checks',
      _formatSoulGateChecks(snapshot.highIntimacyChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Playful affection',
      _formatGateVerdict(snapshot.playfulAffectionAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Playful affection checks',
      _formatSoulGateChecks(snapshot.playfulAffectionChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Supportive reassurance',
      _formatGateVerdict(snapshot.supportiveReassuranceAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Supportive reassurance checks',
      _formatSoulGateChecks(snapshot.supportiveReassuranceChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Proactive relational check-in',
      _formatGateVerdict(snapshot.proactiveRelationalCheckInAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Proactive check-in checks',
      _formatSoulGateChecks(snapshot.proactiveRelationalCheckInChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Light playfulness',
      _formatGateVerdict(snapshot.lightPlayfulnessAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Light playfulness checks',
      _formatSoulGateChecks(snapshot.lightPlayfulnessChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Playful teasing',
      _formatGateVerdict(snapshot.playfulTeasingAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Playful teasing checks',
      _formatSoulGateChecks(snapshot.playfulTeasingChecks),
    ),
  );
  return lines;
}

String _formatPreferenceAxisSummary(
  OpenCrayPreferenceAxisStateSnapshot snapshot,
) {
  return 'offset ${snapshot.offset}, higher ${snapshot.higherSupport}, lower ${snapshot.lowerSupport}';
}

String _formatPreferredAddressStateSummary(
  OpenCrayPreferredAddressStateSnapshot snapshot,
) {
  return '${snapshot.selectedStyle} | neutral ${snapshot.neutralSupport}, friendly ${snapshot.friendlySupport}, intimate ${snapshot.intimateSupport}';
}

String _formatSoulGateChecks(List<OpenCraySoulGateCheckSnapshot> checks) {
  if (checks.isEmpty) {
    return 'none';
  }
  return checks.map(_formatSoulGateCheck).join(' | ');
}

String _formatSoulGateCheck(OpenCraySoulGateCheckSnapshot check) {
  final verdict = check.passed ? 'pass' : 'fail';
  if (check.currentValue != null && check.threshold != null) {
    return '${check.key} ${check.currentValue}/${check.threshold} $verdict';
  }
  if (check.actualBoolean != null) {
    final actual = check.actualBoolean! ? 'true' : 'false';
    final expected = check.expectedBoolean == null
        ? ''
        : '/${check.expectedBoolean! ? 'true' : 'false'}';
    return '${check.key} $actual$expected $verdict';
  }
  return '${check.key} $verdict';
}

String _formatGateVerdict(bool allowed) => allowed ? 'allowed' : 'blocked';

List<OpenCraySoulFieldSourceSnapshot> _resolvedSoulFieldSources(
  OpenCraySoulDebugSnapshot snapshot,
) {
  if (snapshot.fieldSources.isNotEmpty) {
    return snapshot.fieldSources;
  }
  final effectiveSoul = snapshot.effectiveSoul;
  if (effectiveSoul == null) {
    return const <OpenCraySoulFieldSourceSnapshot>[];
  }
  final interactionPreferenceDebug = snapshot.interactionPreferenceDebug;
  final relationshipStateDebug = snapshot.relationshipStateDebug;
  final fallback = <OpenCraySoulFieldSourceSnapshot>[];

  String fallbackSourceType() {
    return snapshot.overlayRecords.isEmpty ? 'stored_soul' : 'memory_overlay';
  }

  String fallbackSourceLabel() {
    return snapshot.overlayRecords.isEmpty ? 'stored soul' : 'memory overlay';
  }

  String interactionPreferenceSourceLabel() {
    switch (interactionPreferenceDebug?.scope) {
      case 'workspace':
        return 'workspace interaction preference';
      case 'session':
        return 'session interaction preference';
      case 'user':
        return 'user interaction preference';
      default:
        return 'interaction preference';
    }
  }

  String relationshipStateSourceLabel() {
    switch (relationshipStateDebug?.scope) {
      case 'workspace':
        return 'workspace relationship state';
      case 'session':
        return 'session relationship state';
      case 'user':
        return 'user relationship state';
      default:
        return 'relationship state';
    }
  }

  void addSource({
    required String field,
    required String value,
    required String sourceType,
    required String sourceLabel,
    String recordId = '',
    String sourceScope = '',
    String sourceDetail = '',
  }) {
    final normalizedValue = value.trim();
    if (normalizedValue.isEmpty) {
      return;
    }
    fallback.add(
      OpenCraySoulFieldSourceSnapshot(
        field: field,
        value: normalizedValue,
        sourceType: sourceType,
        sourceLabel: sourceLabel,
        recordId: recordId,
        sourceScope: sourceScope,
        sourceDetail: sourceDetail,
      ),
    );
  }

  addSource(
    field: 'presetName',
    value: effectiveSoul.presetName,
    sourceType: 'stored_soul',
    sourceLabel: 'stored soul preset',
  );
  addSource(
    field: 'displayName',
    value: effectiveSoul.displayName,
    sourceType: fallbackSourceType(),
    sourceLabel: fallbackSourceLabel(),
  );
  addSource(
    field: 'voice',
    value: effectiveSoul.voice,
    sourceType: fallbackSourceType(),
    sourceLabel: fallbackSourceLabel(),
  );
  addSource(
    field: 'preferredNaming',
    value: effectiveSoul.preferredNaming,
    sourceType:
        interactionPreferenceDebug?.preferredNaming.trim().isNotEmpty == true
        ? 'interaction_preference'
        : fallbackSourceType(),
    sourceLabel:
        interactionPreferenceDebug?.preferredNaming.trim().isNotEmpty == true
        ? interactionPreferenceSourceLabel()
        : fallbackSourceLabel(),
    recordId:
        interactionPreferenceDebug?.preferredNaming.trim().isNotEmpty == true
        ? interactionPreferenceDebug?.snapshotRecordId ?? ''
        : '',
    sourceScope:
        interactionPreferenceDebug?.preferredNaming.trim().isNotEmpty == true
        ? interactionPreferenceDebug?.scope ?? ''
        : '',
    sourceDetail:
        interactionPreferenceDebug?.preferredNaming.trim().isNotEmpty == true
        ? 'Projected interaction-preference snapshot'
        : '',
  );
  addSource(
    field: 'preferredAddressStyle',
    value: effectiveSoul.preferredAddressStyle,
    sourceType:
        interactionPreferenceDebug?.preferredAddressStyle.trim().isNotEmpty ==
            true
        ? 'interaction_preference'
        : (relationshipStateDebug?.derivedAddressStyle.trim().isNotEmpty == true
              ? 'relationship_state'
              : fallbackSourceType()),
    sourceLabel:
        interactionPreferenceDebug?.preferredAddressStyle.trim().isNotEmpty ==
            true
        ? interactionPreferenceSourceLabel()
        : (relationshipStateDebug?.derivedAddressStyle.trim().isNotEmpty == true
              ? relationshipStateSourceLabel()
              : fallbackSourceLabel()),
    recordId:
        interactionPreferenceDebug?.preferredAddressStyle.trim().isNotEmpty ==
            true
        ? interactionPreferenceDebug?.snapshotRecordId ?? ''
        : (relationshipStateDebug?.derivedAddressStyle.trim().isNotEmpty == true
              ? relationshipStateDebug?.snapshotRecordId ?? ''
              : ''),
    sourceScope:
        interactionPreferenceDebug?.preferredAddressStyle.trim().isNotEmpty ==
            true
        ? interactionPreferenceDebug?.scope ?? ''
        : (relationshipStateDebug?.derivedAddressStyle.trim().isNotEmpty == true
              ? relationshipStateDebug?.scope ?? ''
              : ''),
    sourceDetail:
        interactionPreferenceDebug?.preferredAddressStyle.trim().isNotEmpty ==
            true
        ? 'Projected interaction-preference snapshot'
        : (relationshipStateDebug?.derivedAddressStyle.trim().isNotEmpty == true
              ? 'Derived from relationship gates'
              : ''),
  );
  addSource(
    field: 'warmthPreferenceOffset',
    value: effectiveSoul.warmthPreferenceOffset,
    sourceType: 'interaction_preference',
    sourceLabel: interactionPreferenceSourceLabel(),
    recordId: interactionPreferenceDebug?.snapshotRecordId ?? '',
    sourceScope: interactionPreferenceDebug?.scope ?? '',
    sourceDetail: 'Projected interaction-preference snapshot',
  );
  addSource(
    field: 'formalityPreferenceOffset',
    value: effectiveSoul.formalityPreferenceOffset,
    sourceType: 'interaction_preference',
    sourceLabel: interactionPreferenceSourceLabel(),
    recordId: interactionPreferenceDebug?.snapshotRecordId ?? '',
    sourceScope: interactionPreferenceDebug?.scope ?? '',
    sourceDetail: 'Projected interaction-preference snapshot',
  );
  addSource(
    field: 'initiativePreferenceOffset',
    value: effectiveSoul.initiativePreferenceOffset,
    sourceType: 'interaction_preference',
    sourceLabel: interactionPreferenceSourceLabel(),
    recordId: interactionPreferenceDebug?.snapshotRecordId ?? '',
    sourceScope: interactionPreferenceDebug?.scope ?? '',
    sourceDetail: 'Projected interaction-preference snapshot',
  );
  addSource(
    field: 'playfulnessPreferenceOffset',
    value: effectiveSoul.playfulnessPreferenceOffset,
    sourceType: 'interaction_preference',
    sourceLabel: interactionPreferenceSourceLabel(),
    recordId: interactionPreferenceDebug?.snapshotRecordId ?? '',
    sourceScope: interactionPreferenceDebug?.scope ?? '',
    sourceDetail: 'Projected interaction-preference snapshot',
  );
  addSource(
    field: 'reassurancePreferenceOffset',
    value: effectiveSoul.reassurancePreferenceOffset,
    sourceType: 'interaction_preference',
    sourceLabel: interactionPreferenceSourceLabel(),
    recordId: interactionPreferenceDebug?.snapshotRecordId ?? '',
    sourceScope: interactionPreferenceDebug?.scope ?? '',
    sourceDetail: 'Projected interaction-preference snapshot',
  );
  addSource(
    field: 'intimacyPermissionBand',
    value: effectiveSoul.intimacyPermissionBand,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail: 'Derived from relationship-state score band',
  );
  addSource(
    field: 'playfulnessPermissionBand',
    value: effectiveSoul.playfulnessPermissionBand,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail: 'Derived from relationship-state score band',
  );
  addSource(
    field: 'supportiveReassuranceAllowed',
    value: effectiveSoul.supportiveReassuranceAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from relationship state and constrained by reassurance preference',
  );
  addSource(
    field: 'proactiveRelationalCheckInAllowed',
    value: effectiveSoul.proactiveRelationalCheckInAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from relationship state and constrained by initiative preference',
  );
  addSource(
    field: 'lightPlayfulnessAllowed',
    value: effectiveSoul.lightPlayfulnessAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from relationship state and constrained by playfulness preference',
  );
  addSource(
    field: 'playfulTeasingAllowed',
    value: effectiveSoul.playfulTeasingAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from relationship state and constrained by playfulness preference',
  );
  addSource(
    field: 'highIntimacyBehaviorAllowed',
    value: effectiveSoul.highIntimacyBehaviorAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from trust, safety, reciprocity, and intimacy',
  );
  addSource(
    field: 'playfulAffectionAllowed',
    value: effectiveSoul.playfulAffectionAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from playfulness, safety, and reciprocity',
  );
  addSource(
    field: 'customGuidance',
    value: effectiveSoul.customGuidance,
    sourceType: 'stored_soul',
    sourceLabel: 'stored soul',
  );
  addSource(
    field: 'tone',
    value: effectiveSoul.tone,
    sourceType: fallbackSourceType(),
    sourceLabel: fallbackSourceLabel(),
  );
  addSource(
    field: 'verbosity',
    value: effectiveSoul.verbosity,
    sourceType: fallbackSourceType(),
    sourceLabel: fallbackSourceLabel(),
  );
  addSource(
    field: 'userRelationshipStyle',
    value: effectiveSoul.userRelationshipStyle,
    sourceType: fallbackSourceType(),
    sourceLabel: fallbackSourceLabel(),
  );
  addSource(
    field: 'riskTolerance',
    value: effectiveSoul.riskTolerance,
    sourceType: 'stored_soul',
    sourceLabel: 'stored soul',
  );
  addSource(
    field: 'toolUseBias',
    value: effectiveSoul.toolUseBias,
    sourceType: 'stored_soul',
    sourceLabel: 'stored soul',
  );
  if (effectiveSoul.escalationRules.isNotEmpty) {
    addSource(
      field: 'escalationRules',
      value: effectiveSoul.escalationRules.join(' | '),
      sourceType: 'stored_soul',
      sourceLabel: 'stored soul',
    );
  }
  if (effectiveSoul.forbiddenBehaviors.isNotEmpty) {
    addSource(
      field: 'forbiddenBehaviors',
      value: effectiveSoul.forbiddenBehaviors.join(' | '),
      sourceType: 'stored_soul',
      sourceLabel: 'stored soul',
    );
  }
  if (effectiveSoul.collaborationPreferences.isNotEmpty) {
    addSource(
      field: 'collaborationPreferences',
      value: effectiveSoul.collaborationPreferences.join(' | '),
      sourceType: 'stored_soul',
      sourceLabel: 'stored soul',
    );
  }
  return fallback;
}

String _debugSoulFieldLabel(String field) {
  switch (field) {
    case 'presetName':
      return 'preset';
    case 'displayName':
      return 'display name';
    case 'voice':
      return 'voice';
    case 'preferredNaming':
      return 'preferred naming';
    case 'preferredAddressStyle':
      return 'preferred address style';
    case 'warmthPreferenceOffset':
      return 'warmth offset';
    case 'formalityPreferenceOffset':
      return 'formality offset';
    case 'initiativePreferenceOffset':
      return 'initiative offset';
    case 'playfulnessPreferenceOffset':
      return 'playfulness offset';
    case 'reassurancePreferenceOffset':
      return 'reassurance offset';
    case 'intimacyPermissionBand':
      return 'intimacy band';
    case 'playfulnessPermissionBand':
      return 'playfulness band';
    case 'supportiveReassuranceAllowed':
      return 'supportive reassurance allowed';
    case 'proactiveRelationalCheckInAllowed':
      return 'proactive check-in allowed';
    case 'lightPlayfulnessAllowed':
      return 'light playfulness allowed';
    case 'playfulTeasingAllowed':
      return 'playful teasing allowed';
    case 'highIntimacyBehaviorAllowed':
      return 'high intimacy allowed';
    case 'playfulAffectionAllowed':
      return 'playful affection allowed';
    case 'customGuidance':
      return 'custom guidance';
    case 'tone':
      return 'tone';
    case 'verbosity':
      return 'verbosity';
    case 'userRelationshipStyle':
      return 'relationship';
    case 'riskTolerance':
      return 'risk tolerance';
    case 'toolUseBias':
      return 'tool use bias';
    case 'escalationRules':
      return 'escalation rules';
    case 'forbiddenBehaviors':
      return 'forbidden behaviors';
    case 'collaborationPreferences':
      return 'collaboration preferences';
    default:
      return field;
  }
}

String _formatSoulFieldSourceSummary(OpenCraySoulFieldSourceSnapshot source) {
  final sourcePrefix = source.sourceLabel.isEmpty
      ? source.sourceType
      : source.sourceLabel;
  final scopeSuffix = source.sourceScope.isEmpty
      ? ''
      : ' · ${source.sourceScope}';
  final detailSuffix = source.sourceDetail.isEmpty
      ? ''
      : ' · ${source.sourceDetail}';
  return '$sourcePrefix$scopeSuffix$detailSuffix: ${source.value}';
}

String _formatDebugDuration(int deltaMs) {
  if (deltaMs <= 0) {
    return '0s';
  }
  final seconds = deltaMs / 1000;
  if (seconds >= 10) {
    return '${seconds.toStringAsFixed(0)}s';
  }
  return '${seconds.toStringAsFixed(1)}s';
}

String _formatDebugClockTime(int epochMs) {
  if (epochMs <= 0) {
    return 'n/a';
  }
  final time = DateTime.fromMillisecondsSinceEpoch(epochMs);
  final hour = time.hour.toString().padLeft(2, '0');
  final minute = time.minute.toString().padLeft(2, '0');
  final second = time.second.toString().padLeft(2, '0');
  return '$hour:$minute:$second';
}

String _truncateDebugText(String value, int maxChars) {
  if (value.length <= maxChars) {
    return value;
  }
  return '${value.substring(0, maxChars - 3)}...';
}

String _renderDebugStringMap(Map<String, String> values) {
  final entries = values.entries.toList(growable: false)
    ..sort((left, right) => left.key.compareTo(right.key));
  return entries.map((entry) => '${entry.key}: ${entry.value}').join('\n');
}

String? _trimmedDebugValue(String? value) {
  final normalized = value?.trim();
  if (normalized == null || normalized.isEmpty) {
    return null;
  }
  return normalized;
}

Map<String, dynamic>? _decodeDebugJsonObject(String? value) {
  final normalized = _trimmedDebugValue(value);
  if (normalized == null) {
    return null;
  }
  try {
    final decoded = jsonDecode(normalized);
    if (decoded is! Map) {
      return null;
    }
    return decoded.map<String, dynamic>(
      (key, rawValue) => MapEntry(key.toString(), rawValue),
    );
  } catch (_) {
    return null;
  }
}

String? _debugArgumentString(
  Map<String, dynamic>? arguments,
  String key, {
  String? fallbackKey,
}) {
  final dynamic rawValue =
      arguments?[key] ?? (fallbackKey == null ? null : arguments?[fallbackKey]);
  final normalized = rawValue?.toString().trim();
  if (normalized == null || normalized.isEmpty) {
    return null;
  }
  return normalized;
}

int? _debugArgumentInt(Map<String, dynamic>? arguments, String key) {
  final dynamic rawValue = arguments?[key];
  if (rawValue is int) {
    return rawValue;
  }
  if (rawValue is num) {
    return rawValue.toInt();
  }
  if (rawValue is String) {
    return int.tryParse(rawValue.trim());
  }
  return null;
}

List<dynamic>? _debugArgumentList(Map<String, dynamic>? arguments, String key) {
  final dynamic rawValue = arguments?[key];
  if (rawValue is List<dynamic>) {
    return rawValue;
  }
  if (rawValue is List) {
    return rawValue.cast<dynamic>();
  }
  return null;
}

List<String> _debugArgumentStringList(
  Map<String, dynamic>? arguments,
  String key,
) {
  final values = _debugArgumentList(arguments, key);
  if (values == null || values.isEmpty) {
    return const <String>[];
  }
  return values
      .map((value) => value?.toString().trim() ?? '')
      .where((value) => value.isNotEmpty)
      .toList(growable: false);
}

String _debugReadRangeSummary({required int? offset, required int? limit}) {
  if (offset == null && limit == null) {
    return '';
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

String _debugSubagentTypeDisplay(String rawValue) => rawValue
    .split(RegExp(r'[-_\s]+'))
    .where((segment) => segment.isNotEmpty)
    .map(
      (segment) =>
          '${segment[0].toUpperCase()}${segment.substring(1).toLowerCase()}',
    )
    .join(' ');

String _debugToolActionSummary({
  required String toolName,
  required Map<String, dynamic>? arguments,
}) {
  switch (toolName) {
    case 'Read':
      final String? path = _debugArgumentString(
        arguments,
        'file_path',
        fallbackKey: 'path',
      );
      if (path == null) {
        return 'Call $toolName';
      }
      final int? offset = _debugArgumentInt(arguments, 'offset');
      final int? limit = _debugArgumentInt(arguments, 'limit');
      final String range = _debugReadRangeSummary(offset: offset, limit: limit);
      return range.isEmpty ? 'Read $path' : 'Read $path $range';
    case 'LS':
      final String path =
          _debugArgumentString(arguments, 'path', fallbackKey: 'file_path') ??
          '.';
      return 'List $path';
    case 'Grep':
      final String? pattern = _debugArgumentString(arguments, 'pattern');
      if (pattern == null) {
        return 'Call $toolName';
      }
      final String path = _debugArgumentString(arguments, 'path') ?? '.';
      final String? glob = _debugArgumentString(arguments, 'glob');
      return glob == null
          ? 'Search "$pattern" in $path'
          : 'Search "$pattern" in $path (glob: $glob)';
    case 'Glob':
      final String? pattern = _debugArgumentString(arguments, 'pattern');
      if (pattern == null) {
        return 'Call $toolName';
      }
      final String path = _debugArgumentString(arguments, 'path') ?? '.';
      return 'Match $pattern in $path';
    case 'Write':
      final String? path = _debugArgumentString(
        arguments,
        'file_path',
        fallbackKey: 'path',
      );
      return path == null ? 'Call $toolName' : 'Write $path';
    case 'Edit':
      final String? path = _debugArgumentString(
        arguments,
        'file_path',
        fallbackKey: 'path',
      );
      return path == null ? 'Call $toolName' : 'Edit $path';
    case 'MultiEdit':
      final String? path = _debugArgumentString(
        arguments,
        'file_path',
        fallbackKey: 'path',
      );
      if (path == null) {
        return 'Call $toolName';
      }
      final int editCount = _debugArgumentList(arguments, 'edits')?.length ?? 0;
      return editCount <= 0
          ? 'MultiEdit $path'
          : 'Apply $editCount edit(s) to $path';
    case 'WebSearch':
      final String operation =
          _debugArgumentString(arguments, 'operation')?.toLowerCase() ?? '';
      final String? directQuery = _debugArgumentString(arguments, 'query');
      final List<String> queryList = _debugArgumentStringList(
        arguments,
        'queries',
      );
      final String? query =
          directQuery ?? (queryList.isEmpty ? null : queryList.first);
      final String? url = _debugArgumentString(arguments, 'url');
      final String? text =
          _debugArgumentString(arguments, 'text') ??
          _debugArgumentString(arguments, 'pattern');
      final List<String> domains = _debugArgumentStringList(
        arguments,
        'domains',
      );
      final String domainSuffix = domains.isEmpty
          ? ''
          : ' within ${domains.join(', ')}';
      switch (operation) {
        case 'open_page':
          return url == null
              ? 'Call $toolName'
              : 'Open search result page $url';
        case 'find_in_page':
          if (url == null && text == null) {
            return 'Call $toolName';
          }
          final String target = text == null ? '' : ' "$text"';
          final String location = url == null ? '' : ' in $url';
          return 'Find in page$target$location';
        default:
          return query == null
              ? 'Search the web$domainSuffix'
              : 'Search the web for "$query"$domainSuffix';
      }
    case 'TodoWrite':
      if (arguments?.containsKey('todos') != true) {
        return 'Read current todo list';
      }
      final int todoCount = _debugArgumentList(arguments, 'todos')?.length ?? 0;
      return todoCount <= 0
          ? 'Update todo list'
          : 'Update $todoCount todo item(s)';
    case 'Task':
      final String? description = _debugArgumentString(
        arguments,
        'description',
      );
      final String? subagentType =
          _debugArgumentString(arguments, 'subagent_type') ??
          _debugArgumentString(arguments, 'subagentType');
      final String target = subagentType == null
          ? 'subagent'
          : _debugSubagentTypeDisplay(subagentType);
      return description == null
          ? 'Delegate to $target'
          : 'Delegate to $target: ${_truncateDebugText(description, 64)}';
    default:
      return toolName.trim().isEmpty ? 'Tool call' : 'Call $toolName';
  }
}

String? _debugPreviewValue(dynamic rawValue) {
  if (rawValue == null) {
    return null;
  }
  if (rawValue is String) {
    final normalized = rawValue.trim();
    return normalized.isEmpty ? null : _truncateDebugText(normalized, 48);
  }
  if (rawValue is bool) {
    return rawValue ? 'true' : 'false';
  }
  if (rawValue is num) {
    return '$rawValue';
  }
  if (rawValue is List) {
    if (rawValue.isEmpty) {
      return null;
    }
    final List<String> preview = rawValue
        .map(_debugPreviewValue)
        .whereType<String>()
        .take(2)
        .toList(growable: false);
    if (preview.isEmpty) {
      return '${rawValue.length} item(s)';
    }
    final String suffix = rawValue.length > preview.length ? ', ...' : '';
    return '${preview.join(', ')}$suffix';
  }
  if (rawValue is Map) {
    return '${rawValue.length} field(s)';
  }
  final normalized = rawValue.toString().trim();
  return normalized.isEmpty ? null : _truncateDebugText(normalized, 48);
}

String? _debugMapPreview(Map<String, dynamic>? values) {
  if (values == null || values.isEmpty) {
    return null;
  }
  const priorityKeys = <String>[
    'file_path',
    'path',
    'query',
    'pattern',
    'url',
    'command',
    'script_path',
    'name',
    'process_id',
    'agent_id',
    'description',
    'prompt',
    'text',
  ];
  final parts = <String>[];
  for (final key in priorityKeys) {
    final String? preview = _debugPreviewValue(values[key]);
    if (preview == null) {
      continue;
    }
    parts.add('$key $preview');
    if (parts.length >= 2) {
      return parts.join(' · ');
    }
  }
  for (final entry in values.entries) {
    if (priorityKeys.contains(entry.key)) {
      continue;
    }
    final String? preview = _debugPreviewValue(entry.value);
    if (preview == null) {
      continue;
    }
    parts.add('${entry.key} $preview');
    if (parts.length >= 2) {
      break;
    }
  }
  return parts.isEmpty ? null : parts.join(' · ');
}

String? _debugToolCallDetailPreview({
  required String toolName,
  required Map<String, dynamic>? arguments,
  required String? rawArgumentsJson,
}) {
  switch (toolName) {
    case 'Task':
      final String? prompt = _debugArgumentString(arguments, 'prompt');
      final String? contextMode = _debugArgumentString(
        arguments,
        'context_mode',
      );
      final parts = <String>[
        if (prompt != null) 'prompt ${_truncateDebugText(prompt, 72)}',
        if (contextMode != null) 'context $contextMode',
      ];
      final List<String> allowedTools = _debugArgumentStringList(
        arguments,
        'allowed_tools',
      );
      if (allowedTools.isNotEmpty) {
        parts.add('allowed ${_truncateDebugText(allowedTools.join(', '), 72)}');
      }
      return parts.isEmpty ? null : parts.join(' · ');
    case 'WebSearch':
      final List<String> sourceUrls = _debugArgumentStringList(
        arguments,
        'sourceUrls',
      );
      if (sourceUrls.isEmpty) {
        return null;
      }
      return 'sources ${_truncateDebugText(sourceUrls.join(', '), 72)}';
    case 'TodoWrite':
      final List<dynamic>? todos = _debugArgumentList(arguments, 'todos');
      if (todos == null || todos.isEmpty) {
        return null;
      }
      final List<String> preview = <String>[];
      for (final dynamic rawTodo in todos) {
        if (rawTodo is! Map) {
          continue;
        }
        final Map<String, dynamic> todo = rawTodo.map<String, dynamic>(
          (key, value) => MapEntry(key.toString(), value),
        );
        final String? content = _debugArgumentString(todo, 'content');
        if (content == null) {
          continue;
        }
        final String? status = _debugArgumentString(todo, 'status');
        preview.add(
          status == null
              ? _truncateDebugText(content, 48)
              : '${_truncateDebugText(content, 40)} [$status]',
        );
        if (preview.length >= 2) {
          break;
        }
      }
      return preview.isEmpty ? null : preview.join(' · ');
    default:
      final String? mapPreview = _debugMapPreview(arguments);
      if (mapPreview != null) {
        return mapPreview;
      }
      final String? normalizedRawArguments = _trimmedDebugValue(
        rawArgumentsJson,
      );
      return normalizedRawArguments == null
          ? null
          : _truncateDebugText(normalizedRawArguments, 120);
  }
}

String? _debugResultMetadataValue(
  OpenCrayChatRuntimeEventSnapshot event,
  String key,
) {
  final normalized = event.resultMetadata[key]?.trim();
  if (normalized == null || normalized.isEmpty) {
    return null;
  }
  return normalized;
}

int? _debugResultMetadataInt(
  OpenCrayChatRuntimeEventSnapshot event,
  String key,
) {
  final String? value = _debugResultMetadataValue(event, key);
  return value == null ? null : int.tryParse(value);
}

bool? _debugResultMetadataBool(
  OpenCrayChatRuntimeEventSnapshot event,
  String key,
) {
  final String? value = _debugResultMetadataValue(event, key)?.toLowerCase();
  if (value == 'true') {
    return true;
  }
  if (value == 'false') {
    return false;
  }
  return null;
}

List<String> _debugCsvValues(String? value) {
  final normalized = _trimmedDebugValue(value);
  if (normalized == null) {
    return const <String>[];
  }
  return normalized
      .split(',')
      .map((entry) => entry.trim())
      .where((entry) => entry.isNotEmpty)
      .toList(growable: false);
}

bool _debugResultMetadataTruncated(OpenCrayChatRuntimeEventSnapshot event) {
  const candidateKeys = <String>[
    'truncated',
    'outputTruncated',
    'contentTruncated',
    'resultTruncated',
  ];
  for (final key in candidateKeys) {
    if (_debugResultMetadataBool(event, key) == true) {
      return true;
    }
  }
  return false;
}

Map<String, dynamic>? _debugToolResultArgumentsFallback({
  required String toolName,
  required OpenCrayChatRuntimeEventSnapshot event,
}) {
  switch (toolName) {
    case 'Read':
      final String? filePath = _debugResultMetadataValue(event, 'filePath');
      if (filePath == null) {
        return null;
      }
      return <String, dynamic>{
        'file_path': filePath,
        if (_debugResultMetadataInt(event, 'offset') != null)
          'offset': _debugResultMetadataInt(event, 'offset'),
        if (_debugResultMetadataInt(event, 'limit') != null)
          'limit': _debugResultMetadataInt(event, 'limit'),
      };
    case 'LS':
      return <String, dynamic>{
        if (_debugResultMetadataValue(event, 'path') != null)
          'path': _debugResultMetadataValue(event, 'path'),
      };
    case 'Grep':
      final String? pattern = _debugResultMetadataValue(event, 'pattern');
      if (pattern == null) {
        return null;
      }
      return <String, dynamic>{
        'pattern': pattern,
        if (_debugResultMetadataValue(event, 'path') != null)
          'path': _debugResultMetadataValue(event, 'path'),
        if (_debugResultMetadataValue(event, 'glob') != null)
          'glob': _debugResultMetadataValue(event, 'glob'),
      };
    case 'Glob':
      final String? pattern = _debugResultMetadataValue(event, 'pattern');
      if (pattern == null) {
        return null;
      }
      return <String, dynamic>{
        'pattern': pattern,
        if (_debugResultMetadataValue(event, 'path') != null)
          'path': _debugResultMetadataValue(event, 'path'),
      };
    case 'WebSearch':
      final String? operation = _debugResultMetadataValue(
        event,
        'providerManagedOperation',
      );
      final String? query = _debugResultMetadataValue(event, 'query');
      final String? url = _debugResultMetadataValue(event, 'url');
      final String? text = _debugResultMetadataValue(event, 'text');
      final List<String> sourceUrls = _debugCsvValues(
        _debugResultMetadataValue(event, 'sourceUrls'),
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
      final String? filePath = _debugResultMetadataValue(event, 'filePath');
      if (filePath == null) {
        return null;
      }
      return <String, dynamic>{'file_path': filePath};
    case 'Task':
      final String? description = _debugResultMetadataValue(
        event,
        'delegationDescription',
      );
      final String? prompt = _debugResultMetadataValue(
        event,
        'delegationPromptPreview',
      );
      final String? subagentType =
          _debugResultMetadataValue(event, 'delegationSubagentType') ??
          _debugResultMetadataValue(event, 'subagentType');
      final String? contextMode =
          _debugResultMetadataValue(event, 'delegationContextMode') ??
          _debugResultMetadataValue(event, 'subagentContextMode');
      final List<String> allowedTools = _debugCsvValues(
        _debugResultMetadataValue(event, 'delegationAllowedTools'),
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
    default:
      return null;
  }
}

String _debugToolResultActionSummary({
  required String toolName,
  required OpenCrayChatRuntimeEventSnapshot event,
}) => _debugToolActionSummary(
  toolName: toolName,
  arguments: _debugToolResultArgumentsFallback(
    toolName: toolName,
    event: event,
  ),
);

String? _debugGenericResultMetadataPreview(
  OpenCrayChatRuntimeEventSnapshot event,
) {
  if (event.resultMetadata.isEmpty) {
    return null;
  }
  final parts = <String>[];
  for (final entry in event.resultMetadata.entries) {
    final String key = entry.key.trim();
    final String value = entry.value.trim();
    if (key.isEmpty || value.isEmpty) {
      continue;
    }
    parts.add('$key ${_truncateDebugText(value, 48)}');
    if (parts.length >= 3) {
      break;
    }
  }
  return parts.isEmpty ? null : parts.join(' · ');
}

String? _debugToolResultMetadataSummary({
  required String toolName,
  required OpenCrayChatRuntimeEventSnapshot event,
}) {
  switch (toolName) {
    case 'LS':
      final int? entryCount = _debugResultMetadataInt(event, 'entryCount');
      final String? path = _debugResultMetadataValue(event, 'path');
      final bool truncated = _debugResultMetadataTruncated(event);
      if (entryCount == null) {
        return null;
      }
      final String summary = path == null
          ? 'Listed $entryCount entr${entryCount == 1 ? 'y' : 'ies'}'
          : 'Listed $entryCount entr${entryCount == 1 ? 'y' : 'ies'} in $path';
      return truncated
          ? '$summary. Output truncated at the tool result limit.'
          : summary;
    case 'Read':
      final int? returnedLineCount = _debugResultMetadataInt(
        event,
        'returnedLineCount',
      );
      final int? totalLineCount = _debugResultMetadataInt(
        event,
        'totalLineCount',
      );
      final bool truncated = _debugResultMetadataTruncated(event);
      final String? filePath = _debugResultMetadataValue(event, 'filePath');
      if (returnedLineCount == null &&
          totalLineCount == null &&
          !truncated &&
          filePath == null) {
        return null;
      }
      final parts = <String>[
        if (returnedLineCount != null)
          returnedLineCount == 1
              ? 'Returned 1 line'
              : 'Returned $returnedLineCount lines',
        if (filePath != null) 'from $filePath',
        if (totalLineCount != null)
          totalLineCount == 1 ? '(1-line file)' : '($totalLineCount-line file)',
        if (truncated) 'Output truncated to the read budget.',
      ];
      return parts.join(' ');
    case 'Grep':
      final int? matchCount = _debugResultMetadataInt(event, 'matchCount');
      final String? pattern = _debugResultMetadataValue(event, 'pattern');
      final String? path = _debugResultMetadataValue(event, 'path');
      final bool truncated = _debugResultMetadataTruncated(event);
      if (matchCount == null) {
        return null;
      }
      final String target = path ?? '.';
      final String summary = pattern == null
          ? (matchCount == 1
                ? 'Found 1 match in $target'
                : 'Found $matchCount matches in $target')
          : (matchCount == 1
                ? 'Found 1 match for "$pattern" in $target'
                : 'Found $matchCount matches for "$pattern" in $target');
      return truncated
          ? '$summary. Output truncated at the tool result limit.'
          : summary;
    case 'Glob':
      final int? matchCount = _debugResultMetadataInt(event, 'matchCount');
      final String? pattern = _debugResultMetadataValue(event, 'pattern');
      final String? path = _debugResultMetadataValue(event, 'path');
      final bool truncated = _debugResultMetadataTruncated(event);
      if (matchCount == null) {
        return null;
      }
      final String target = path ?? '.';
      final String summary = pattern == null
          ? 'Matched $matchCount path(s) in $target'
          : 'Matched $matchCount path(s) for $pattern in $target';
      return truncated
          ? '$summary. Output truncated at the tool result limit.'
          : summary;
    case 'WebSearch':
      final int? sourceCount = _debugResultMetadataInt(event, 'sourceCount');
      final String? operation = _debugResultMetadataValue(
        event,
        'providerManagedOperation',
      )?.toLowerCase();
      final String? status = _debugResultMetadataValue(
        event,
        'providerManagedStatus',
      );
      final String? query = _debugResultMetadataValue(event, 'query');
      final String? url = _debugResultMetadataValue(event, 'url');
      final String? text = _debugResultMetadataValue(event, 'text');
      final bool managed =
          _debugResultMetadataValue(event, 'providerManaged') == 'true';
      if (sourceCount == null &&
          operation == null &&
          status == null &&
          query == null &&
          url == null &&
          text == null) {
        return null;
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
      final int? replacementCount = _debugResultMetadataInt(
        event,
        'replacementCount',
      );
      final String? filePath = _debugResultMetadataValue(event, 'filePath');
      if (replacementCount == null && filePath == null) {
        return null;
      }
      return <String>[
        if (replacementCount != null)
          replacementCount == 1
              ? 'Applied 1 replacement'
              : 'Applied $replacementCount replacements',
        if (filePath != null) 'in $filePath',
      ].join(' ');
    case 'MultiEdit':
      final int? replacementCount = _debugResultMetadataInt(
        event,
        'replacementCount',
      );
      final int? editCount = _debugResultMetadataInt(event, 'editCount');
      final String? filePath = _debugResultMetadataValue(event, 'filePath');
      if (replacementCount == null && editCount == null && filePath == null) {
        return null;
      }
      return <String>[
        if (editCount != null)
          editCount == 1 ? 'Applied 1 edit' : 'Applied $editCount edits',
        if (replacementCount != null)
          replacementCount == 1
              ? '(1 replacement)'
              : '($replacementCount replacements)',
        if (filePath != null) 'in $filePath',
      ].join(' ');
    case 'Task':
      final String? executionState = _debugResultMetadataValue(
        event,
        'executionState',
      );
      final String? childStatus =
          _debugResultMetadataValue(event, 'status') ??
          _debugResultMetadataValue(event, 'delegationStatus');
      final String? subagentType =
          _debugResultMetadataValue(event, 'delegationSubagentType') ??
          _debugResultMetadataValue(event, 'subagentType');
      final String? contextMode =
          _debugResultMetadataValue(event, 'delegationContextMode') ??
          _debugResultMetadataValue(event, 'subagentContextMode');
      final int? turnCount = _debugResultMetadataInt(event, 'childTurnCount');
      final int? toolCallCount = _debugResultMetadataInt(
        event,
        'childToolCallCount',
      );
      final List<String> allowedTools = _debugCsvValues(
        _debugResultMetadataValue(event, 'delegationAllowedTools'),
      );
      final parts = <String>[
        if (executionState != null) 'state $executionState',
        if (childStatus != null) 'status $childStatus',
        if (subagentType != null)
          'subagent ${_debugSubagentTypeDisplay(subagentType)}',
        if (contextMode != null) 'context $contextMode',
        if (turnCount != null) 'turns $turnCount',
        if (toolCallCount != null) 'tool calls $toolCallCount',
        if (allowedTools.isNotEmpty)
          'allowed ${_truncateDebugText(allowedTools.join(', '), 72)}',
      ];
      return parts.isEmpty ? null : parts.join(' · ');
    case 'TodoWrite':
      final int? todoCount = _debugResultMetadataInt(event, 'todoCount');
      final int addedTodoCount =
          _debugResultMetadataInt(event, 'addedTodoCount') ?? 0;
      final int removedTodoCount =
          _debugResultMetadataInt(event, 'removedTodoCount') ?? 0;
      final int statusChangedTodoCount =
          _debugResultMetadataInt(event, 'statusChangedTodoCount') ?? 0;
      final bool mutated = _debugResultMetadataBool(event, 'mutated') ?? false;
      if (todoCount == null &&
          addedTodoCount == 0 &&
          removedTodoCount == 0 &&
          statusChangedTodoCount == 0 &&
          !mutated) {
        return null;
      }
      return <String>[
        if (todoCount != null)
          todoCount == 1 ? '1 todo in list' : '$todoCount todos in list',
        if (mutated) 'mutated',
        if (addedTodoCount > 0)
          addedTodoCount == 1 ? 'added 1' : 'added $addedTodoCount',
        if (removedTodoCount > 0)
          removedTodoCount == 1 ? 'removed 1' : 'removed $removedTodoCount',
        if (statusChangedTodoCount > 0)
          statusChangedTodoCount == 1
              ? '1 status change'
              : '$statusChangedTodoCount status changes',
      ].join(' · ');
    default:
      return _debugGenericResultMetadataPreview(event);
  }
}

String _summarizeToolCallEvent(OpenCrayChatRuntimeEventSnapshot event) {
  final String? toolName = _trimmedDebugValue(event.toolName);
  final Map<String, dynamic>? arguments = _decodeDebugJsonObject(
    event.argumentsJson,
  );
  final parts = <String>[
    toolName == null
        ? 'Tool call'
        : _debugToolActionSummary(toolName: toolName, arguments: arguments),
  ];
  final String? reason = _trimmedDebugValue(event.toolReason);
  if (reason != null) {
    parts.add('reason ${_truncateDebugText(reason, 72)}');
  }
  if (toolName != null) {
    final String? detail = _debugToolCallDetailPreview(
      toolName: toolName,
      arguments: arguments,
      rawArgumentsJson: event.argumentsJson,
    );
    if (detail != null) {
      parts.add(detail);
    }
  } else {
    final String? normalizedArguments = _trimmedDebugValue(event.argumentsJson);
    if (normalizedArguments != null) {
      parts.add(_truncateDebugText(normalizedArguments, 120));
    }
  }
  return parts.join(' · ');
}

String _summarizeToolResultEvent(OpenCrayChatRuntimeEventSnapshot event) {
  final String? toolName = _trimmedDebugValue(event.toolName);
  final parts = <String>[
    toolName == null
        ? 'Tool result'
        : _debugToolResultActionSummary(toolName: toolName, event: event),
  ];
  final String? status =
      _trimmedDebugValue(event.toolStatus) ?? _trimmedDebugValue(event.status);
  if (status != null) {
    parts.add(status);
  }
  if (toolName != null) {
    final String? metadataSummary = _debugToolResultMetadataSummary(
      toolName: toolName,
      event: event,
    );
    if (metadataSummary != null) {
      parts.add(metadataSummary);
    }
  } else {
    final String? metadataPreview = _debugGenericResultMetadataPreview(event);
    if (metadataPreview != null) {
      parts.add(metadataPreview);
    }
  }
  final String? errorCode = _trimmedDebugValue(event.errorCode);
  final String? errorMessage = _trimmedDebugValue(event.errorMessage);
  if (errorCode != null) {
    parts.add(errorCode);
  }
  if (errorMessage != null) {
    parts.add(_truncateDebugText(errorMessage, 96));
  } else {
    final String? contentPreview =
        _trimmedDebugValue(event.contentPreview) ??
        _trimmedDebugValue(event.content);
    if (contentPreview != null) {
      parts.add(_truncateDebugText(contentPreview, 96));
    }
  }
  return parts.join(' · ');
}

String? _runAttemptReasonSummary(OpenCrayChatRunSnapshot run) {
  final recoverySummary = _trimmedDebugValue(run.recoveryPlan?.summary);
  if (recoverySummary != null) {
    return recoverySummary;
  }
  final recoveryReason = _trimmedDebugValue(run.diagnostics?.recoveryReason);
  if (recoveryReason != null) {
    return _debugRecoveryReasonSummary(recoveryReason);
  }
  final continuationKind = _trimmedDebugValue(run.lastEvent?.continuationKind);
  if (continuationKind != null) {
    return 'Continued via ${_humanizeDebugCode(continuationKind)}.';
  }
  return null;
}

String? _runAttemptReasonCode(OpenCrayChatRunSnapshot run) =>
    _trimmedDebugValue(run.recoveryPlan?.reasonCode) ??
    _trimmedDebugValue(run.diagnostics?.recoveryReason) ??
    _trimmedDebugValue(run.lastEvent?.continuationKind);

String _debugRecoveryReasonSummary(String code) {
  switch (code) {
    case 'host_restart_inflight_task_interrupted':
      return 'The host restarted while this run was in flight, so it required an explicit retry before continuing.';
    default:
      return '${_humanizeDebugCode(code)}.';
  }
}

String _humanizeDebugCode(String value) => value
    .split(RegExp(r'[_-]+'))
    .where((segment) => segment.isNotEmpty)
    .map(
      (segment) =>
          '${segment[0].toUpperCase()}${segment.substring(1).toLowerCase()}',
    )
    .join(' ');

String _summarizeRuntimeEvent(OpenCrayChatRuntimeEventSnapshot event) {
  switch (event.kind) {
    case 'memory_write':
      return 'written ${event.writtenRecordIds.length} · resolved ${event.resolvedRecordIds.length} · suppressed ${event.suppressedRecordIds.length} · reaffirmed ${event.reaffirmedRecordIds.length} · expired ${event.expiredRecordIds.length}';
    case 'memory_retrieval':
      final parts = <String>[];
      if (event.operation?.trim().isNotEmpty == true) {
        parts.add(event.operation!.trim());
      }
      if (event.queryTerms.isNotEmpty) {
        parts.add('terms ${event.queryTerms.join(', ')}');
      }
      if (event.resultCount != null) {
        parts.add('results ${event.resultCount}');
      }
      return parts.isEmpty ? 'memory retrieval' : parts.join(' · ');
    case 'memory_flush':
      return event.writtenRecordIds.isEmpty
          ? 'no durable writes'
          : 'wrote ${event.writtenRecordIds.length} durable records';
    case 'tool_call':
      return _summarizeToolCallEvent(event);
    case 'tool_result':
      return _summarizeToolResultEvent(event);
    default:
      final parts = <String>[];
      if (event.status?.trim().isNotEmpty == true) {
        parts.add(event.status!.trim());
      }
      if (event.phase?.trim().isNotEmpty == true) {
        parts.add(event.phase!.trim());
      }
      if (event.text?.trim().isNotEmpty == true) {
        parts.add(_truncateDebugText(event.text!.trim(), 90));
      }
      if (event.errorCode?.trim().isNotEmpty == true) {
        parts.add(event.errorCode!.trim());
      }
      return parts.isEmpty ? 'No additional payload.' : parts.join(' · ');
  }
}
