import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/app/opencray_app.dart';
import 'package:opencray/app/opencray_tabs.dart';
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

  testWidgets('restores the settings privacy subpage from shell snapshot', (
    tester,
  ) async {
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

    expect(find.text('Privacy & Telemetry'), findsOneWidget);
    expect(find.text('API Integrations'), findsNothing);
  });
}
