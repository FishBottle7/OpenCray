import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/app/opencray_app.dart';
import 'package:opencray/app/opencray_tabs.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/models/opencray_shell_snapshot.dart';
import 'package:opencray/features/settings/settings.dart';

void main() {
  testWidgets('renders shell with all bottom tabs', (tester) async {
    final bridge = OpenCraySeedBridge(
      initialSnapshot: const OpenCrayShellSnapshot(
        initialTab: OpenCrayTab.chat,
        localeTag: 'en',
        hostLabel: 'HOST READY',
        hostSummary: 'Flutter shell is attached to a seed bridge.',
        isHostConnected: false,
      ),
    );

    await tester.pumpWidget(OpenCrayApp(bridge: bridge));
    await tester.pumpAndSettle();

    expect(find.text('Chat'), findsOneWidget);
    expect(find.text('CHAT'), findsOneWidget);
    expect(find.text('SKILLS'), findsOneWidget);
    expect(find.text('FILES'), findsOneWidget);
    expect(find.text('SETTINGS'), findsOneWidget);
  });

  testWidgets('settings route opens notifications and background page', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
      initialSnapshot: const OpenCrayShellSnapshot(
        initialTab: OpenCrayTab.chat,
        localeTag: 'en',
        hostLabel: 'HOST READY',
        hostSummary: 'Flutter shell is attached to a seed bridge.',
        isHostConnected: false,
      ),
    );

    await tester.pumpWidget(OpenCrayApp(bridge: bridge));
    await tester.pumpAndSettle();
    unawaited(
      tester
          .state<NavigatorState>(find.byType(Navigator))
          .pushNamed('/settings/notifications-background'),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('Notifications & Background'), findsOneWidget);
    expect(find.text('Background protection status'), findsOneWidget);
  });

  testWidgets('scheduled task route decodes id and opens task detail', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
      initialSnapshot: const OpenCrayShellSnapshot(
        initialTab: OpenCrayTab.chat,
        localeTag: 'en',
        hostLabel: 'HOST READY',
        hostSummary: 'Flutter shell is attached to a seed bridge.',
        isHostConnected: false,
      ),
    );

    await tester.pumpWidget(OpenCrayApp(bridge: bridge));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    unawaited(
      tester
          .state<NavigatorState>(find.byType(Navigator))
          .pushNamed('/settings/scheduled-tasks?scheduleId=schedule%2F1'),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('Task details'), findsOneWidget);
    expect(
      find.text('Scheduled task schedule/1 was not found.'),
      findsOneWidget,
    );
  });

  testWidgets('waits for the shell snapshot without rendering seed chat', (
    tester,
  ) async {
    final bridge = _DelayedShellBridge();

    await tester.pumpWidget(OpenCrayApp(bridge: bridge));
    await tester.pump();

    expect(find.text('Why is write access pending?'), findsNothing);
    expect(find.text('CHAT'), findsNothing);
  });

  testWidgets(
    'home starts on chat even when shell snapshot persisted settings',
    (tester) async {
      final bridge = OpenCraySeedBridge(
        initialSnapshot: const OpenCrayShellSnapshot(
          initialTab: OpenCrayTab.settings,
          initialSettingsPage: SettingsPage.privacyTelemetry,
          localeTag: 'en',
          hostLabel: 'HOST READY',
          hostSummary: 'Flutter shell is attached to a seed bridge.',
          isHostConnected: false,
        ),
      );

      await tester.pumpWidget(OpenCrayApp(bridge: bridge));
      await tester.pumpAndSettle();

      expect(find.text('Chat'), findsOneWidget);
      expect(find.text('Privacy & Telemetry'), findsNothing);
    },
  );
}

class _DelayedShellBridge implements OpenCrayHostBridge {
  final Completer<OpenCrayShellSnapshot> _shellSnapshotCompleter =
      Completer<OpenCrayShellSnapshot>();

  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() =>
      _shellSnapshotCompleter.future;

  @override
  Stream<OpenCrayShellSnapshot> watchShellSnapshot() =>
      const Stream<OpenCrayShellSnapshot>.empty();

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
