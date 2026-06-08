import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/app/opencray_app.dart';
import 'package:opencray/app/opencray_tabs.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/models/opencray_shell_snapshot.dart';

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
    await tester
        .state<NavigatorState>(find.byType(Navigator))
        .pushNamed('/settings/notifications-background');
    await tester.pumpAndSettle();

    expect(find.text('Notifications & Background'), findsOneWidget);
    expect(find.text('Background profile'), findsOneWidget);
  });
}
