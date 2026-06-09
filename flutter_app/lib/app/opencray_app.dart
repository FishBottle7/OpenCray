import 'package:flutter/material.dart';

import '../core/bridge/opencray_host_bridge.dart';
import '../core/bridge/opencray_seed_bridge.dart';
import '../core/copy/opencray_ui_copy.dart';
import '../core/design/opencray_motion.dart';
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
          return openCrayHorizontalPageRoute<void>(
            settings: settings,
            builder: (context) => SettingsFeatureScreen(
              initialPage: entry.settingsInitialPage,
              facade: BridgeSettingsFacade(bridge: _bridge),
              standalone: true,
              debugBridge: _bridge,
            ),
          );
        }
        return openCrayHorizontalPageRoute<void>(
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

class _ShellEntry extends StatefulWidget {
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
  State<_ShellEntry> createState() => _ShellEntryState();
}

class _ShellEntryState extends State<_ShellEntry> {
  late final ChatFeatureController _chatController = ChatFeatureController();
  late final FilesFeatureController _filesController = FilesFeatureController();

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<OpenCrayShellSnapshot>(
      future: widget.snapshotFuture ?? widget.bridge.loadShellSnapshot(),
      builder: (context, snapshot) {
        final OpenCrayShellSnapshot? shellSnapshot = snapshot.data;
        if (shellSnapshot == null) {
          return const ColoredBox(color: Color(0xFFF8F8FA));
        }
        final initialTab = widget.tab ?? OpenCrayTab.chat;
        final initialSettingsPage = widget.tab == OpenCrayTab.settings
            ? widget.settingsInitialPage
            : shellSnapshot.initialSettingsPage;
        final effectiveShellSnapshot = shellSnapshot.copyWith(
          initialTab: initialTab,
          initialSettingsPage: initialSettingsPage,
        );
        return OpenCrayAppShell(
          bridge: widget.bridge,
          initialSnapshot: effectiveShellSnapshot,
          initialTab: initialTab,
          initialSettingsPage: initialSettingsPage,
          chatController: _chatController,
          filesController: _filesController,
          buildersForSnapshot: (snapshot) => _defaultBuilders(
            snapshot,
            bridge: widget.bridge,
            chatController: _chatController,
            filesController: _filesController,
          ),
        );
      },
    );
  }
}

Map<OpenCrayTab, OpenCrayTabBuilder> _defaultBuilders(
  OpenCrayShellSnapshot snapshot, {
  required OpenCrayHostBridge bridge,
  required ChatFeatureController chatController,
  required FilesFeatureController filesController,
}) {
  final copy = OpenCrayUiCopy.fromLocaleTag(snapshot.localeTag);
  return {
    OpenCrayTab.chat: (context, isActive) => OpenCrayChatFeature(
      bridge: bridge,
      copy: copy,
      isTabActive: isActive,
      controller: chatController,
    ),
    OpenCrayTab.skills: (context, isActive) =>
        SkillsFeatureScreen(bridge: bridge, copy: copy, isTabActive: isActive),
    OpenCrayTab.files: (context, isActive) => FilesFeatureScreen(
      bridge: bridge,
      copy: copy,
      isTabActive: isActive,
      controller: filesController,
    ),
    OpenCrayTab.settings: (context, isActive) => SettingsFeatureScreen(
      key: ValueKey<String>(
        'settings-screen-${snapshot.initialSettingsPage.routeId}',
      ),
      initialPage: snapshot.initialSettingsPage,
      facade: BridgeSettingsFacade(bridge: bridge),
      standalone: false,
      debugBridge: bridge,
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
    routeName: '/settings/notifications-background',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.notificationsBackground,
  ),
  _RouteEntry(
    routeName: '/settings/notification-channels',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.notificationChannels,
  ),
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
    routeName: '/settings/api-integrations',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.apiIntegrations,
  ),
  _RouteEntry(
    routeName: '/settings/privacy',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.privacyTelemetry,
  ),
  _RouteEntry(
    routeName: '/settings/network-search',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.networkSearch,
  ),
  _RouteEntry(
    routeName: '/settings/media-speech',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.mediaSpeech,
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
  _RouteEntry(
    routeName: '/settings/agents',
    tab: OpenCrayTab.settings,
    settingsInitialPage: SettingsPage.agents,
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
