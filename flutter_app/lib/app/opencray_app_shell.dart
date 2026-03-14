import 'dart:async';

import 'package:flutter/material.dart';

import '../core/bridge/opencray_host_bridge.dart';
import '../core/models/opencray_shell_snapshot.dart';
import '../core/design/opencray_widgets.dart';
import 'opencray_tabs.dart';

typedef OpenCrayTabBuilder = Widget Function(BuildContext context);
typedef OpenCrayTabBuilderFactory =
    Map<OpenCrayTab, OpenCrayTabBuilder> Function(
      OpenCrayShellSnapshot snapshot,
    );

class OpenCrayAppShell extends StatefulWidget {
  const OpenCrayAppShell({
    super.key,
    required this.bridge,
    required this.initialSnapshot,
    required this.buildersForSnapshot,
    required this.initialTab,
  });

  final OpenCrayHostBridge bridge;
  final OpenCrayShellSnapshot initialSnapshot;
  final OpenCrayTabBuilderFactory buildersForSnapshot;
  final OpenCrayTab initialTab;

  @override
  State<OpenCrayAppShell> createState() => _OpenCrayAppShellState();
}

class _OpenCrayAppShellState extends State<OpenCrayAppShell> {
  late OpenCrayShellSnapshot _snapshot = widget.initialSnapshot;
  late OpenCrayTab _selectedTab = widget.initialTab;
  StreamSubscription<OpenCrayShellSnapshot>? _subscription;

  @override
  void initState() {
    super.initState();
    _subscription = widget.bridge.watchShellSnapshot().listen((snapshot) {
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
      });
    });
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final builders = widget.buildersForSnapshot(_snapshot);
    return Scaffold(
      body: IndexedStack(
        index: OpenCrayTab.values.indexOf(_selectedTab),
        children: [
          for (final tab in OpenCrayTab.values)
            KeyedSubtree(
              key: ValueKey<String>('shell-tab-${tab.routeSegment}'),
              child: builders[tab]!(context),
            ),
        ],
      ),
      bottomNavigationBar: OpenCrayBottomNavigation(
        selectedTab: _selectedTab,
        onTabSelected: (tab) {
          if (_selectedTab == tab) {
            return;
          }
          setState(() {
            _selectedTab = tab;
          });
        },
      ),
    );
  }
}
