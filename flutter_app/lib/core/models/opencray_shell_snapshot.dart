import '../../app/opencray_tabs.dart';

class OpenCrayShellSnapshot {
  const OpenCrayShellSnapshot({
    required this.initialTab,
    required this.localeTag,
    required this.hostLabel,
    required this.hostSummary,
    required this.isHostConnected,
  });

  final OpenCrayTab initialTab;
  final String localeTag;
  final String hostLabel;
  final String hostSummary;
  final bool isHostConnected;

  OpenCrayShellSnapshot copyWith({
    OpenCrayTab? initialTab,
    String? localeTag,
    String? hostLabel,
    String? hostSummary,
    bool? isHostConnected,
  }) {
    return OpenCrayShellSnapshot(
      initialTab: initialTab ?? this.initialTab,
      localeTag: localeTag ?? this.localeTag,
      hostLabel: hostLabel ?? this.hostLabel,
      hostSummary: hostSummary ?? this.hostSummary,
      isHostConnected: isHostConnected ?? this.isHostConnected,
    );
  }
}
