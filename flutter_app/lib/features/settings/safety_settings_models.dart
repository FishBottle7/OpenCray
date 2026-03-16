enum SafetyAutomationMode { safe, auto, dev }

enum ToolPolicyOverride { inherit, ask, allow, block }

enum WorkspaceAccessProfile { work, ask, open }

enum ExternalAccessMode { blockAll, selectPaths }

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
    required this.undoWindowHours,
    required this.fileChangesPolicy,
    required this.fileDeletesPolicy,
    required this.shellCommandsPolicy,
    required this.externalAccessMode,
    required this.locations,
    required this.workspaceAccessProfile,
    required this.readOnlyOutsideWorkspace,
  });

  final SafetyAutomationMode automationMode;
  final bool rollbackJournalEnabled;
  final int maxFilesPerBatch;
  final int undoWindowHours;
  final ToolPolicyOverride fileChangesPolicy;
  final ToolPolicyOverride fileDeletesPolicy;
  final ToolPolicyOverride shellCommandsPolicy;
  final ExternalAccessMode externalAccessMode;
  final List<SafetyLocationSetting> locations;
  final WorkspaceAccessProfile workspaceAccessProfile;
  final bool readOnlyOutsideWorkspace;

  int get approvedRootsCount =>
      1 +
      (externalAccessMode == ExternalAccessMode.selectPaths
          ? locations.where((location) => location.enabled).length
          : 0);

  SafetyLocationSetting location(String id) =>
      locations.firstWhere((location) => location.id == id);

  bool isLocationEnabled(String id) =>
      locations.any((location) => location.id == id && location.enabled);

  SafetySettingsSnapshot copyWith({
    SafetyAutomationMode? automationMode,
    bool? rollbackJournalEnabled,
    int? maxFilesPerBatch,
    int? undoWindowHours,
    ToolPolicyOverride? fileChangesPolicy,
    ToolPolicyOverride? fileDeletesPolicy,
    ToolPolicyOverride? shellCommandsPolicy,
    ExternalAccessMode? externalAccessMode,
    List<SafetyLocationSetting>? locations,
    WorkspaceAccessProfile? workspaceAccessProfile,
    bool? readOnlyOutsideWorkspace,
  }) {
    return SafetySettingsSnapshot(
      automationMode: automationMode ?? this.automationMode,
      rollbackJournalEnabled:
          rollbackJournalEnabled ?? this.rollbackJournalEnabled,
      maxFilesPerBatch: maxFilesPerBatch ?? this.maxFilesPerBatch,
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
}
