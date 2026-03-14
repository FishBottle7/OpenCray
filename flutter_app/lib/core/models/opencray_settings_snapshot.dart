class OpenCraySettingsHomeEntrySnapshot {
  const OpenCraySettingsHomeEntrySnapshot({
    required this.routeId,
    required this.title,
  });

  final String routeId;
  final String title;
}

class OpenCraySettingsOverviewSnapshot {
  const OpenCraySettingsOverviewSnapshot({
    required this.eyebrow,
    required this.title,
    required this.subtitle,
    required this.deviceTitle,
    required this.deviceSummary,
    required this.entries,
  });

  final String eyebrow;
  final String title;
  final String subtitle;
  final String deviceTitle;
  final String deviceSummary;
  final List<OpenCraySettingsHomeEntrySnapshot> entries;
}

enum OpenCraySettingsSectionBackgroundTone { surface, danger }

enum OpenCraySettingsRowTrailingKind { chevron, toggle, value }

class OpenCraySettingsRowSnapshot {
  const OpenCraySettingsRowSnapshot.chevron({
    required this.title,
    this.subtitle,
  }) : trailingKind = OpenCraySettingsRowTrailingKind.chevron,
       toggleValue = null,
       valueLabel = null;

  const OpenCraySettingsRowSnapshot.toggle({
    required this.title,
    this.subtitle,
    required this.toggleValue,
  }) : trailingKind = OpenCraySettingsRowTrailingKind.toggle,
       valueLabel = null;

  const OpenCraySettingsRowSnapshot.value({
    required this.title,
    required this.valueLabel,
  }) : trailingKind = OpenCraySettingsRowTrailingKind.value,
       subtitle = null,
       toggleValue = null;

  final String title;
  final String? subtitle;
  final OpenCraySettingsRowTrailingKind trailingKind;
  final bool? toggleValue;
  final String? valueLabel;
}

class OpenCraySettingsSectionSnapshot {
  const OpenCraySettingsSectionSnapshot({
    required this.title,
    this.helperText,
    this.rows = const <OpenCraySettingsRowSnapshot>[],
    this.segmentedOptions,
    this.segmentedIndex,
    this.inlinePanelText,
    this.backgroundTone = OpenCraySettingsSectionBackgroundTone.surface,
  });

  final String title;
  final String? helperText;
  final List<OpenCraySettingsRowSnapshot> rows;
  final List<String>? segmentedOptions;
  final int? segmentedIndex;
  final String? inlinePanelText;
  final OpenCraySettingsSectionBackgroundTone backgroundTone;
}

class OpenCraySettingsDetailSnapshot {
  const OpenCraySettingsDetailSnapshot({
    required this.routeId,
    required this.title,
    required this.subtitle,
    required this.sections,
  });

  final String routeId;
  final String title;
  final String subtitle;
  final List<OpenCraySettingsSectionSnapshot> sections;
}
