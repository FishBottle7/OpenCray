import '../../app/opencray_tabs.dart';

class OpenCrayShellSnapshot {
  const OpenCrayShellSnapshot({
    required this.initialTab,
    required this.hostLabel,
    required this.hostSummary,
    required this.isHostConnected,
  });

  final OpenCrayTab initialTab;
  final String hostLabel;
  final String hostSummary;
  final bool isHostConnected;

  OpenCrayShellSnapshot copyWith({
    OpenCrayTab? initialTab,
    String? hostLabel,
    String? hostSummary,
    bool? isHostConnected,
  }) {
    return OpenCrayShellSnapshot(
      initialTab: initialTab ?? this.initialTab,
      hostLabel: hostLabel ?? this.hostLabel,
      hostSummary: hostSummary ?? this.hostSummary,
      isHostConnected: isHostConnected ?? this.isHostConnected,
    );
  }
}
