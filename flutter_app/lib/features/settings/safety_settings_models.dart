enum SafetyAutomationMode { safe, auto, dev }

enum ToolPolicyOverride { inherit, ask, allow, block }

enum WorkspaceAccessProfile { work, ask, open }

enum ExternalAccessMode { blockAll, selectPaths }

enum LiveContextMode { full, lightweight, none, noSoul, noMemoryOrSoul }

enum SubAgentContextMode { minimal, delegated }

const Object _unsetNullableSubAgentContextMode = Object();
const List<String> builtInSubAgentProfileIds = <String>[
  'general-purpose',
  'researcher',
  'reviewer',
  'worker',
];

extension SafetyAutomationModeWire on SafetyAutomationMode {
  String get id {
    switch (this) {
      case SafetyAutomationMode.safe:
        return 'safe';
      case SafetyAutomationMode.auto:
        return 'auto';
      case SafetyAutomationMode.dev:
        return 'dev';
    }
  }
}

extension ToolPolicyOverrideWire on ToolPolicyOverride {
  String get id {
    switch (this) {
      case ToolPolicyOverride.inherit:
        return 'inherit';
      case ToolPolicyOverride.ask:
        return 'ask';
      case ToolPolicyOverride.allow:
        return 'allow';
      case ToolPolicyOverride.block:
        return 'block';
    }
  }
}

extension WorkspaceAccessProfileWire on WorkspaceAccessProfile {
  String get id {
    switch (this) {
      case WorkspaceAccessProfile.work:
        return 'work';
      case WorkspaceAccessProfile.ask:
        return 'ask';
      case WorkspaceAccessProfile.open:
        return 'open';
    }
  }
}

extension ExternalAccessModeWire on ExternalAccessMode {
  String get id {
    switch (this) {
      case ExternalAccessMode.blockAll:
        return 'block_all';
      case ExternalAccessMode.selectPaths:
        return 'select_paths';
    }
  }
}

extension LiveContextModeWire on LiveContextMode {
  String get id {
    switch (this) {
      case LiveContextMode.full:
        return 'full';
      case LiveContextMode.lightweight:
        return 'lightweight';
      case LiveContextMode.none:
        return 'none';
      case LiveContextMode.noSoul:
        return 'no_soul';
      case LiveContextMode.noMemoryOrSoul:
        return 'no_memory_or_soul';
    }
  }
}

extension SubAgentContextModeWire on SubAgentContextMode {
  String get id {
    switch (this) {
      case SubAgentContextMode.minimal:
        return 'minimal';
      case SubAgentContextMode.delegated:
        return 'delegated';
    }
  }
}

SafetyAutomationMode safetyAutomationModeFromId(String id) {
  switch (id) {
    case 'safe':
      return SafetyAutomationMode.safe;
    case 'dev':
      return SafetyAutomationMode.dev;
    default:
      return SafetyAutomationMode.auto;
  }
}

ToolPolicyOverride toolPolicyOverrideFromId(String id) {
  switch (id) {
    case 'ask':
      return ToolPolicyOverride.ask;
    case 'allow':
      return ToolPolicyOverride.allow;
    case 'block':
      return ToolPolicyOverride.block;
    default:
      return ToolPolicyOverride.inherit;
  }
}

WorkspaceAccessProfile workspaceAccessProfileFromId(String id) {
  switch (id) {
    case 'ask':
      return WorkspaceAccessProfile.ask;
    case 'open':
      return WorkspaceAccessProfile.open;
    default:
      return WorkspaceAccessProfile.work;
  }
}

ExternalAccessMode externalAccessModeFromId(String id) {
  switch (id) {
    case 'block_all':
      return ExternalAccessMode.blockAll;
    default:
      return ExternalAccessMode.selectPaths;
  }
}

LiveContextMode liveContextModeFromId(String id) {
  switch (id) {
    case 'lightweight':
      return LiveContextMode.lightweight;
    case 'none':
      return LiveContextMode.none;
    case 'no_soul':
      return LiveContextMode.noSoul;
    case 'no_memory_or_soul':
      return LiveContextMode.noMemoryOrSoul;
    default:
      return LiveContextMode.full;
  }
}

SubAgentContextMode? subAgentContextModeFromId(String? id) {
  switch (id) {
    case 'minimal':
      return SubAgentContextMode.minimal;
    case 'delegated':
      return SubAgentContextMode.delegated;
    default:
      return null;
  }
}

class SafetyLocationSetting {
  const SafetyLocationSetting({required this.id, required this.enabled});

  final String id;
  final bool enabled;

  SafetyLocationSetting copyWith({bool? enabled}) =>
      SafetyLocationSetting(id: id, enabled: enabled ?? this.enabled);
}

class SafetySettingsSnapshot {
  const SafetySettingsSnapshot({
    required this.automationMode,
    required this.rollbackJournalEnabled,
    required this.maxFilesPerBatch,
    this.maxAgentTurns = 0,
    this.maxToolCalls = 0,
    required this.undoWindowHours,
    required this.fileChangesPolicy,
    required this.fileDeletesPolicy,
    required this.shellCommandsPolicy,
    required this.externalAccessMode,
    required this.locations,
    required this.workspaceAccessProfile,
    required this.readOnlyOutsideWorkspace,
    this.liveContextMode = LiveContextMode.full,
    this.memoryToolsEnabled = true,
    this.subAgentContextDefaultMode,
    this.subAgentContextProfileOverrides = const <String, SubAgentContextMode>{},
  });

  final SafetyAutomationMode automationMode;
  final bool rollbackJournalEnabled;
  final int maxFilesPerBatch;
  final int maxAgentTurns;
  final int maxToolCalls;
  final int undoWindowHours;
  final ToolPolicyOverride fileChangesPolicy;
  final ToolPolicyOverride fileDeletesPolicy;
  final ToolPolicyOverride shellCommandsPolicy;
  final ExternalAccessMode externalAccessMode;
  final List<SafetyLocationSetting> locations;
  final WorkspaceAccessProfile workspaceAccessProfile;
  final bool readOnlyOutsideWorkspace;
  final LiveContextMode liveContextMode;
  final bool memoryToolsEnabled;
  final SubAgentContextMode? subAgentContextDefaultMode;
  final Map<String, SubAgentContextMode> subAgentContextProfileOverrides;

  int get approvedRootsCount =>
      1 +
      (externalAccessMode == ExternalAccessMode.selectPaths
          ? locations.where((location) => location.enabled).length
          : 0);

  SafetyLocationSetting location(String id) =>
      locations.firstWhere((location) => location.id == id);

  bool isLocationEnabled(String id) =>
      locations.any((location) => location.id == id && location.enabled);

  SubAgentContextMode? subAgentContextModeForProfile(String profileId) =>
      subAgentContextProfileOverrides[profileId];

  SafetySettingsSnapshot copyWith({
    SafetyAutomationMode? automationMode,
    bool? rollbackJournalEnabled,
    int? maxFilesPerBatch,
    int? maxAgentTurns,
    int? maxToolCalls,
    int? undoWindowHours,
    ToolPolicyOverride? fileChangesPolicy,
    ToolPolicyOverride? fileDeletesPolicy,
    ToolPolicyOverride? shellCommandsPolicy,
    ExternalAccessMode? externalAccessMode,
    List<SafetyLocationSetting>? locations,
    WorkspaceAccessProfile? workspaceAccessProfile,
    bool? readOnlyOutsideWorkspace,
    LiveContextMode? liveContextMode,
    bool? memoryToolsEnabled,
    Object? subAgentContextDefaultMode = _unsetNullableSubAgentContextMode,
    Map<String, SubAgentContextMode>? subAgentContextProfileOverrides,
  }) {
    return SafetySettingsSnapshot(
      automationMode: automationMode ?? this.automationMode,
      rollbackJournalEnabled:
          rollbackJournalEnabled ?? this.rollbackJournalEnabled,
      maxFilesPerBatch: maxFilesPerBatch ?? this.maxFilesPerBatch,
      maxAgentTurns: maxAgentTurns ?? this.maxAgentTurns,
      maxToolCalls: maxToolCalls ?? this.maxToolCalls,
      undoWindowHours: undoWindowHours ?? this.undoWindowHours,
      fileChangesPolicy: fileChangesPolicy ?? this.fileChangesPolicy,
      fileDeletesPolicy: fileDeletesPolicy ?? this.fileDeletesPolicy,
      shellCommandsPolicy: shellCommandsPolicy ?? this.shellCommandsPolicy,
      externalAccessMode: externalAccessMode ?? this.externalAccessMode,
      locations: locations ?? this.locations,
      workspaceAccessProfile:
          workspaceAccessProfile ?? this.workspaceAccessProfile,
      readOnlyOutsideWorkspace:
          readOnlyOutsideWorkspace ?? this.readOnlyOutsideWorkspace,
      liveContextMode: liveContextMode ?? this.liveContextMode,
      memoryToolsEnabled: memoryToolsEnabled ?? this.memoryToolsEnabled,
      subAgentContextDefaultMode:
          identical(
                subAgentContextDefaultMode,
                _unsetNullableSubAgentContextMode,
              )
          ? this.subAgentContextDefaultMode
          : subAgentContextDefaultMode as SubAgentContextMode?,
      subAgentContextProfileOverrides:
          subAgentContextProfileOverrides ?? this.subAgentContextProfileOverrides,
    );
  }

  SafetySettingsSnapshot withLocationEnabled(String id, bool enabled) {
    return copyWith(
      locations: locations
          .map(
            (location) => location.id == id
                ? location.copyWith(enabled: enabled)
                : location,
          )
          .toList(growable: false),
    );
  }

  SafetySettingsSnapshot withSubAgentContextDefaultMode(
    SubAgentContextMode? mode,
  ) {
    return copyWith(subAgentContextDefaultMode: mode);
  }

  SafetySettingsSnapshot withSubAgentContextProfileOverride(
    String profileId,
    SubAgentContextMode? mode,
  ) {
    final nextOverrides = Map<String, SubAgentContextMode>.from(
      subAgentContextProfileOverrides,
    );
    if (mode == null) {
      nextOverrides.remove(profileId);
    } else {
      nextOverrides[profileId] = mode;
    }
    return copyWith(
      subAgentContextProfileOverrides: Map<String, SubAgentContextMode>.unmodifiable(
        nextOverrides,
      ),
    );
  }
}
