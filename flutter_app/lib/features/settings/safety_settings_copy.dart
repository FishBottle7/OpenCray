import 'safety_settings_models.dart';

class SafetySettingsCopy {
  const SafetySettingsCopy._();

  static const String safetyTitle = 'Safety & Limits';
  static const String safetySubtitle =
      'Choose how much freedom the agent gets.';
  static const String workspaceTitle = 'Workspace Access';
  static const String workspaceSubtitle =
      'Choose where the agent can read, write, and ask first.';
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

  static String sensitiveActionSummary({
    required String title,
    required ToolPolicyOverride policy,
  }) {
    return '$title · ${policyLabel(policy)}';
  }
}
