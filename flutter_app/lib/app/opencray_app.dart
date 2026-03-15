import 'package:flutter/material.dart';

import '../core/bridge/opencray_host_bridge.dart';
import '../core/bridge/opencray_seed_bridge.dart';
import '../core/copy/opencray_ui_copy.dart';
import '../core/models/opencray_shell_snapshot.dart';
import '../core/design/opencray_theme.dart';
import '../features/chat/chat_feature.dart';
import '../features/files/files.dart';
import '../features/settings/settings.dart';
import '../features/skills/skills.dart';
import 'opencray_app_shell.dart';
import 'opencray_tabs.dart';

class OpenCrayApp extends StatefulWidget {
  const OpenCrayApp({super.key, required this.bridge});

  final OpenCrayHostBridge bridge;

  @override
  State<OpenCrayApp> createState() => _OpenCrayAppState();
}

class _OpenCrayAppState extends State<OpenCrayApp> {
  late final OpenCrayHostBridge _bridge = widget.bridge;
  late final Future<OpenCrayShellSnapshot> _snapshotFuture = _bridge
      .loadShellSnapshot();

  @override
  void dispose() {
    final bridge = _bridge;
    if (bridge is OpenCraySeedBridge) {
      bridge.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'OpenCray',
      debugShowCheckedModeBanner: false,
      theme: OpenCrayTheme.light(),
      onGenerateRoute: (settings) {
        final entry = _routeEntryFor(settings.name);
        if (entry == null) {
          return null;
        }
        if (entry.tab == OpenCrayTab.settings &&
            entry.settingsInitialPage != SettingsPage.home) {
          return MaterialPageRoute<void>(
            settings: settings,
            builder: (context) => SettingsFeatureScreen(
              initialPage: entry.settingsInitialPage,
              facade: BridgeSettingsFacade(bridge: _bridge),
              standalone: true,
            ),
          );
        }
        return MaterialPageRoute<void>(
          settings: settings,
          builder: (context) => _ShellEntry(
            tab: entry.tab,
            settingsInitialPage: entry.settingsInitialPage,
            bridge: _bridge,
          ),
        );
      },
      home: _ShellEntry(bridge: _bridge, snapshotFuture: _snapshotFuture),
    );
  }
}

class _ShellEntry extends StatelessWidget {
  const _ShellEntry({
    required this.bridge,
    this.snapshotFuture,
    this.tab,
    this.settingsInitialPage = SettingsPage.home,
  });

  final OpenCrayTab? tab;
  final OpenCrayHostBridge bridge;
  final Future<OpenCrayShellSnapshot>? snapshotFuture;
  final SettingsPage settingsInitialPage;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<OpenCrayShellSnapshot>(
      future: snapshotFuture ?? bridge.loadShellSnapshot(),
      builder: (context, snapshot) {
        final shellSnapshot =
            snapshot.data ??
            const OpenCrayShellSnapshot(
              initialTab: OpenCrayTab.chat,
              localeTag: 'en',
              hostLabel: 'HOST READY',
              hostSummary: 'Flutter shell is attached to a seed bridge.',
              isHostConnected: false,
            );
        final initialTab = tab ?? shellSnapshot.initialTab;
        return OpenCrayAppShell(
          bridge: bridge,
          initialSnapshot: shellSnapshot,
          initialTab: initialTab,
          buildersForSnapshot: (snapshot) => _defaultBuilders(
            snapshot,
            bridge: bridge,
            settingsInitialPage: settingsInitialPage,
          ),
        );
      },
    );
  }
}

Map<OpenCrayTab, OpenCrayTabBuilder> _defaultBuilders(
  OpenCrayShellSnapshot snapshot, {
  required OpenCrayHostBridge bridge,
  required SettingsPage settingsInitialPage,
}) {
  final copy = OpenCrayUiCopy.fromLocaleTag(snapshot.localeTag);
  return {
    OpenCrayTab.chat: (context) =>
        OpenCrayChatFeature(bridge: bridge, copy: copy),
    OpenCrayTab.skills: (context) =>
        SkillsFeatureScreen(bridge: bridge, copy: copy),
    OpenCrayTab.files: (context) =>
        FilesFeatureScreen(bridge: bridge, copy: copy),
    OpenCrayTab.settings: (context) => SettingsFeatureScreen(
      initialPage: settingsInitialPage,
      facade: BridgeSettingsFacade(bridge: bridge),
      standalone: false,
    ),
  };
}

class _RouteEntry {
  const _RouteEntry({
    required this.routeName,
    required this.tab,
    this.settingsInitialPage = SettingsPage.home,
  });

  final String routeName;
  final OpenCrayTab tab;
  final SettingsPage settingsInitialPage;
}

const List<_RouteEntry> _routeEntries = <_RouteEntry>[
  _RouteEntry(routeName: '/chat', tab: OpenCrayTab.chat),
  _RouteEntry(routeName: '/skills', tab: OpenCrayTab.skills),
  _RouteEntry(routeName: '/files', tab: OpenCrayTab.files),
  _RouteEntry(routeName: '/settings', tab: OpenCrayTab.settings),
  _RouteEntry(
    routeName: '/settings/workspace',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.workspaceAccess,
  ),
  _RouteEntry(
    routeName: '/settings/llm',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.llm,
  ),
  _RouteEntry(
    routeName: '/settings/mcp',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.mcp,
  ),
  _RouteEntry(
    routeName: '/settings/privacy',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.privacyTelemetry,
  ),
  _RouteEntry(
    routeName: '/settings/safety',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.safetyLimits,
  ),
  _RouteEntry(
    routeName: '/settings/about',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.aboutVersion,
  ),
  _RouteEntry(
    routeName: '/settings/personalization',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.personalization,
  ),
];

_RouteEntry? _routeEntryFor(String? routeName) {
  for (final entry in _routeEntries) {
    if (entry.routeName == routeName) {
      return entry;
    }
  }
  return null;
}
