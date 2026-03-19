import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/bridge/opencray_host_bridge.dart';
import '../core/copy/opencray_ui_copy.dart';
import '../core/models/opencray_shell_snapshot.dart';
import '../core/design/opencray_widgets.dart';
import '../features/chat/chat_feature.dart';
import '../features/files/files.dart';
import 'opencray_tabs.dart';

typedef OpenCrayTabBuilder =
    Widget Function(BuildContext context, bool isActive);
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
    required this.chatController,
    required this.filesController,
  });

  final OpenCrayHostBridge bridge;
  final OpenCrayShellSnapshot initialSnapshot;
  final OpenCrayTabBuilderFactory buildersForSnapshot;
  final OpenCrayTab initialTab;
  final ChatFeatureController chatController;
  final FilesFeatureController filesController;

  @override
  State<OpenCrayAppShell> createState() => _OpenCrayAppShellState();
}

class _OpenCrayAppShellState extends State<OpenCrayAppShell> {
  static const Duration _exitBackWindow = Duration(seconds: 2);

  late OpenCrayShellSnapshot _snapshot = widget.initialSnapshot;
  late OpenCrayTab _selectedTab = widget.initialTab;
  StreamSubscription<OpenCrayShellSnapshot>? _subscription;
  DateTime? _lastBackPressedAt;

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
    final copy = OpenCrayUiCopy.fromLocaleTag(_snapshot.localeTag);
    return PopScope<void>(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) {
          return;
        }
        if (_selectedTab == OpenCrayTab.chat &&
            widget.chatController.consumeBackPress()) {
          _lastBackPressedAt = null;
          return;
        }
        if (_selectedTab == OpenCrayTab.files &&
            widget.filesController.consumeBackPress()) {
          _lastBackPressedAt = null;
          return;
        }
        final now = DateTime.now();
        final previous = _lastBackPressedAt;
        if (previous != null && now.difference(previous) <= _exitBackWindow) {
          SystemNavigator.pop();
          return;
        }
        _lastBackPressedAt = now;
        unawaited(
          widget.bridge
              .showNativeToast(copy.appBackExitHint)
              .catchError((Object _) {}),
        );
      },
      child: Scaffold(
        body: IndexedStack(
          index: OpenCrayTab.values.indexOf(_selectedTab),
          children: [
            for (final tab in OpenCrayTab.values)
              KeyedSubtree(
                key: ValueKey<String>('shell-tab-${tab.routeSegment}'),
                child: builders[tab]!(context, tab == _selectedTab),
              ),
          ],
        ),
        bottomNavigationBar: OpenCrayBottomNavigation(
          snapshot: _snapshot,
          selectedTab: _selectedTab,
          onTabSelected: (tab) {
            if (_selectedTab == tab) {
              return;
            }
            setState(() {
              _selectedTab = tab;
              _lastBackPressedAt = null;
            });
          },
        ),
      ),
    );
  }
}
