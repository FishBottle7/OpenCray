import 'safety_settings_models.dart';

class SafetySettingsCopy {
  const SafetySettingsCopy._();

  static const String safetyTitle = 'Safety & Limits';
  static const String safetySubtitle =
      'Choose how much freedom the agent gets.';
  static const String workspaceTitle = 'Workspace Access';
  static const String workspaceSubtitle =
      'Choose where the agent can read, write, and ask first.';
  static const String liveContextTitle = 'Live context';
  static const String liveContextSubtitle =
      'Choose how much repo/bootstrap, soul, and automatic memory recall are injected into each live run.';
  static const String sensitiveActionsTitle = 'Sensitive actions';
  static const String fileChangesTitle = 'File changes';
  static const String fileChangesSubtitle =
      'Choose how the agent handles file edits.';
  static const String shellCommandsTitle = 'Shell commands';
  static const String shellCommandsSubtitle =
      'Choose how the agent handles shell calls.';
  static const String fileDeletesTitle = 'File deletes';
  static const String fileDeletesSubtitle =
      'Choose how the agent handles delete, move, and rename actions.';
  static const String externalAccessTitle = 'External access';
  static const String externalAccessSubtitle =
      'Allow shared locations only.\nPrivate app data stays blocked.';
  static const String approvedPathsTitle = 'Approved paths';
  static const String approvedPathsSubtitle =
      'Review the workspace root and any extra public locations that remain available in this profile.';
  static const String agentTurnLimitTitle = 'Max agent turns';
  static const String agentTurnLimitSubtitle =
      'Tap the value to type a number. 0 means no hard limit. If a limit is set, the last allowed turn is reserved for the final answer.';
  static const String toolCallLimitTitle = 'Max tool calls';
  static const String toolCallLimitSubtitle =
      'Tap the value to type a number. 0 means no hard limit for tool calls in a single run.';
  static const String limitDialogValueLabel = 'Value';
  static const String limitDialogNoLimitHint = 'Enter 0 for no limit.';
  static const String limitDialogValidationError =
      'Enter a whole number greater than or equal to 0.';

  static String automationModeLabel(SafetyAutomationMode mode) {
    switch (mode) {
      case SafetyAutomationMode.safe:
        return 'SAFE';
      case SafetyAutomationMode.auto:
        return 'AUTO';
      case SafetyAutomationMode.dev:
        return 'DEV';
    }
  }

  static String policyLabel(ToolPolicyOverride policy) {
    switch (policy) {
      case ToolPolicyOverride.inherit:
        return 'Inherit from mode';
      case ToolPolicyOverride.ask:
        return 'Ask every time';
      case ToolPolicyOverride.allow:
        return 'Allow';
      case ToolPolicyOverride.block:
        return 'Block';
    }
  }

  static String policyShortLabel(ToolPolicyOverride policy) {
    switch (policy) {
      case ToolPolicyOverride.inherit:
        return 'Mode';
      case ToolPolicyOverride.ask:
        return 'Ask';
      case ToolPolicyOverride.allow:
        return 'Allow';
      case ToolPolicyOverride.block:
        return 'Block';
    }
  }

  static String automationModeDisplayLabel(SafetyAutomationMode mode) {
    switch (mode) {
      case SafetyAutomationMode.safe:
        return 'Safe';
      case SafetyAutomationMode.auto:
        return 'Auto';
      case SafetyAutomationMode.dev:
        return 'Dev';
    }
  }

  static String workspaceProfileLabel(WorkspaceAccessProfile profile) {
    switch (profile) {
      case WorkspaceAccessProfile.work:
        return 'WORK';
      case WorkspaceAccessProfile.ask:
        return 'ASK';
      case WorkspaceAccessProfile.open:
        return 'OPEN';
    }
  }

  static String liveContextModeLabel(LiveContextMode mode) {
    switch (mode) {
      case LiveContextMode.full:
        return 'Full';
      case LiveContextMode.lightweight:
        return 'Lightweight';
      case LiveContextMode.none:
        return 'None';
      case LiveContextMode.noSoul:
        return 'No soul';
      case LiveContextMode.noMemoryOrSoul:
        return 'No memory or soul';
    }
  }

  static String externalAccessModeLabel(ExternalAccessMode mode) {
    switch (mode) {
      case ExternalAccessMode.blockAll:
        return 'Block all';
      case ExternalAccessMode.selectPaths:
        return 'Select paths';
    }
  }

  static String externalAccessModeShortLabel(ExternalAccessMode mode) {
    switch (mode) {
      case ExternalAccessMode.blockAll:
        return 'Block';
      case ExternalAccessMode.selectPaths:
        return 'Select';
    }
  }

  static String locationLabel(String id) {
    switch (id) {
      case 'photo_library':
        return 'Photo library';
      case 'downloads':
        return 'Downloads';
      case 'documents':
        return 'Documents';
      case 'recordings':
        return 'Recordings';
      default:
        return id;
    }
  }

  static String automationSummary(SafetyAutomationMode mode) {
    switch (mode) {
      case SafetyAutomationMode.safe:
        return 'Writes and commands pause for review by default.';
      case SafetyAutomationMode.auto:
        return 'Routine writes keep moving; destructive work still asks.';
      case SafetyAutomationMode.dev:
        return 'The loosest mode. Use overrides if you want any extra friction.';
    }
  }

  static String workspaceProfileSummary(WorkspaceAccessProfile profile) {
    switch (profile) {
      case WorkspaceAccessProfile.work:
        return 'Profiles decide read and write scope.';
      case WorkspaceAccessProfile.ask:
        return 'Profiles decide read and write scope.';
      case WorkspaceAccessProfile.open:
        return 'Profiles decide read and write scope.';
    }
  }

  static String liveContextModeSummary(LiveContextMode mode) {
    switch (mode) {
      case LiveContextMode.full:
        return 'Inject bootstrap files, soul, and automatic memory recall.';
      case LiveContextMode.lightweight:
        return 'Keep AGENTS.md and PROJECT.md, plus soul and automatic memory recall.';
      case LiveContextMode.none:
        return 'Skip bootstrap files but keep soul and automatic memory recall.';
      case LiveContextMode.noSoul:
        return 'Keep AGENTS.md and PROJECT.md plus automatic memory recall, but suppress soul.';
      case LiveContextMode.noMemoryOrSoul:
        return 'Keep AGENTS.md and PROJECT.md only. Soul and automatic memory recall stay off.';
    }
  }

  static String sensitiveActionSummary({
    required String title,
    required ToolPolicyOverride policy,
  }) {
    return '$title · ${policyLabel(policy)}';
  }

  static String agentTurnLimitValue(int turns) {
    return _limitValue(value: turns, singular: 'turn', plural: 'turns');
  }

  static String toolCallLimitValue(int calls) {
    return _limitValue(value: calls, singular: 'call', plural: 'calls');
  }

  static String _limitValue({
    required int value,
    required String singular,
    required String plural,
  }) {
    if (value <= 0) {
      return 'No limit';
    }
    if (value == 1) {
      return '1 $singular';
    }
    return '$value $plural';
  }
}
