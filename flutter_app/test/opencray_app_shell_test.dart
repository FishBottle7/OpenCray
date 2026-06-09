import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/app/opencray_app_shell.dart';
import 'package:opencray/app/opencray_tabs.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_files_snapshot.dart';
import 'package:opencray/core/models/opencray_shell_snapshot.dart';
import 'package:opencray/features/chat/chat_feature.dart';
import 'package:opencray/features/files/files.dart';
import 'package:opencray/features/settings/settings.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const platformChannel = SystemChannels.platform;
  final platformCalls = <MethodCall>[];

  setUp(() {
    platformCalls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(platformChannel, (call) async {
          platformCalls.add(call);
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(platformChannel, null);
  });

  testWidgets('shell back delegates to files selection state first', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
      initialSnapshot: const OpenCrayShellSnapshot(
        initialTab: OpenCrayTab.files,
        localeTag: 'en',
        hostLabel: 'HOST READY',
        hostSummary: 'Ready',
        isHostConnected: true,
      ),
      initialFilesSnapshot: const OpenCrayFilesSnapshot(
        rootName: 'agent-workspace',
        rootPath: '/tmp/agent-workspace',
        availableBytes: 2048,
        directoryCount: 1,
        fileCount: 1,
        entryCount: 2,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[
          OpenCrayFileTreeNodeSnapshot(
            name: 'docs',
            relativePath: 'docs',
            isDirectory: true,
            childCount: 0,
            sizeBytes: null,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[],
          ),
          OpenCrayFileTreeNodeSnapshot(
            name: 'todo.txt',
            relativePath: 'todo.txt',
            isDirectory: false,
            childCount: 0,
            sizeBytes: 128,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[],
          ),
        ],
      ),
    );
    final filesController = FilesFeatureController();
    final chatController = ChatFeatureController();

    await _pumpShell(
      tester,
      bridge: bridge,
      chatController: chatController,
      filesController: filesController,
      initialTab: OpenCrayTab.files,
    );

    await tester.longPress(
      find.byKey(const ValueKey<String>('files-row-docs')),
    );
    await tester.pumpAndSettle();

    expect(find.text('1 Selected'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.text('Files'), findsOneWidget);
    expect(find.text('1 Selected'), findsNothing);
    expect(bridge.shownNativeToasts, isEmpty);
    expect(
      platformCalls.where((call) => call.method == 'SystemNavigator.pop'),
      isEmpty,
    );
  });

  testWidgets('shell back exits chat multi-select before exiting the app', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
      initialSnapshot: const OpenCrayShellSnapshot(
        initialTab: OpenCrayTab.chat,
        localeTag: 'en',
        hostLabel: 'HOST READY',
        hostSummary: 'Ready',
        isHostConnected: true,
      ),
      initialFilesSnapshot: const OpenCrayFilesSnapshot(
        rootName: 'agent-workspace',
        rootPath: '/tmp/agent-workspace',
        availableBytes: 2048,
        directoryCount: 0,
        fileCount: 0,
        entryCount: 0,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[],
      ),
    );
    final chatController = ChatFeatureController();
    final filesController = FilesFeatureController();

    await _pumpShell(
      tester,
      bridge: bridge,
      chatController: chatController,
      filesController: filesController,
      initialTab: OpenCrayTab.chat,
    );

    final targetBubble = find.byKey(
      const ValueKey<String>('chat-bubble-seed-main-inbound-2'),
    );
    await _openMessageMenu(tester, targetBubble);
    await tester.tap(find.text('Select'));
    await tester.pumpAndSettle();

    expect(find.text('1 Selected'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.byType(OpenCrayChatFeature), findsOneWidget);
    expect(find.text('1 Selected'), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('chat-selection-toolbar')),
      findsNothing,
    );
    expect(bridge.shownNativeToasts, isEmpty);
    expect(
      platformCalls.where((call) => call.method == 'SystemNavigator.pop'),
      isEmpty,
    );
  });

  testWidgets('shell requires double back before exiting the app', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
      initialSnapshot: const OpenCrayShellSnapshot(
        initialTab: OpenCrayTab.chat,
        localeTag: 'en',
        hostLabel: 'HOST READY',
        hostSummary: 'Ready',
        isHostConnected: true,
      ),
      initialFilesSnapshot: const OpenCrayFilesSnapshot(
        rootName: 'agent-workspace',
        rootPath: '/tmp/agent-workspace',
        availableBytes: 2048,
        directoryCount: 0,
        fileCount: 0,
        entryCount: 0,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[],
      ),
    );
    final filesController = FilesFeatureController();
    final chatController = ChatFeatureController();

    await _pumpShell(
      tester,
      bridge: bridge,
      chatController: chatController,
      filesController: filesController,
      initialTab: OpenCrayTab.chat,
    );

    await tester.binding.handlePopRoute();
    await tester.pump();

    expect(bridge.shownNativeToasts, <String>[
      OpenCrayUiCopy.fromLocaleTag('en').appBackExitHint,
    ]);
    expect(
      platformCalls.where((call) => call.method == 'SystemNavigator.pop'),
      isEmpty,
    );

    await tester.binding.handlePopRoute();
    await tester.pump();

    expect(
      platformCalls.where((call) => call.method == 'SystemNavigator.pop'),
      hasLength(1),
    );
  });

  testWidgets('shell persists selected tab through the host bridge', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
      initialSnapshot: const OpenCrayShellSnapshot(
        initialTab: OpenCrayTab.chat,
        localeTag: 'en',
        hostLabel: 'HOST READY',
        hostSummary: 'Ready',
        isHostConnected: true,
      ),
    );
    final filesController = FilesFeatureController();
    final chatController = ChatFeatureController();

    await _pumpShell(
      tester,
      bridge: bridge,
      chatController: chatController,
      filesController: filesController,
      initialTab: OpenCrayTab.chat,
    );

    await tester.tap(find.text('FILES'));
    await tester.pumpAndSettle();

    final snapshot = await bridge.loadShellSnapshot();
    expect(snapshot.initialTab, OpenCrayTab.files);
    expect(snapshot.initialSettingsPage, SettingsPage.home);
  });

  testWidgets('shell tab transition preserves chat widget state', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
      initialSnapshot: const OpenCrayShellSnapshot(
        initialTab: OpenCrayTab.chat,
        localeTag: 'en',
        hostLabel: 'HOST READY',
        hostSummary: 'Ready',
        isHostConnected: true,
      ),
    );
    final filesController = FilesFeatureController();
    final chatController = ChatFeatureController();

    await _pumpShell(
      tester,
      bridge: bridge,
      chatController: chatController,
      filesController: filesController,
      initialTab: OpenCrayTab.chat,
    );

    await tester.enterText(find.byType(TextField), 'keep this draft');
    await tester.pump();

    await tester.tap(find.text('FILES'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('CHAT'));
    await tester.pumpAndSettle();

    final TextField composer = tester.widget<TextField>(find.byType(TextField));
    expect(composer.controller?.text, 'keep this draft');
  });

  testWidgets('shell snapshot updates do not force a tab switch', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
      initialSnapshot: const OpenCrayShellSnapshot(
        initialTab: OpenCrayTab.chat,
        localeTag: 'en',
        hostLabel: 'HOST READY',
        hostSummary: 'Ready',
        isHostConnected: true,
      ),
    );
    final filesController = FilesFeatureController();
    final chatController = ChatFeatureController();

    await _pumpShell(
      tester,
      bridge: bridge,
      chatController: chatController,
      filesController: filesController,
      initialTab: OpenCrayTab.chat,
    );

    expect(find.byType(OpenCrayChatFeature), findsOneWidget);

    await bridge.saveShellDestination(
      selectedTab: OpenCrayTab.settings.routeSegment,
      settingsSubpage: SettingsPage.privacyTelemetry.routeId,
    );
    await tester.pumpAndSettle();

    expect(find.byType(OpenCrayChatFeature), findsOneWidget);
  });
}

Future<void> _pumpShell(
  WidgetTester tester, {
  required OpenCraySeedBridge bridge,
  required ChatFeatureController chatController,
  required FilesFeatureController filesController,
  required OpenCrayTab initialTab,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      home: OpenCrayAppShell(
        bridge: bridge,
        initialSnapshot: OpenCrayShellSnapshot(
          initialTab: initialTab,
          localeTag: 'en',
          hostLabel: 'HOST READY',
          hostSummary: 'Ready',
          isHostConnected: true,
        ),
        initialTab: initialTab,
        chatController: chatController,
        filesController: filesController,
        buildersForSnapshot: (_) => {
          OpenCrayTab.chat: (context, isActive) => OpenCrayChatFeature(
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
            isTabActive: isActive,
            controller: chatController,
          ),
          OpenCrayTab.skills: (context, isActive) => const SizedBox.shrink(),
          OpenCrayTab.files: (context, isActive) => FilesFeatureScreen(
            bridge: bridge,
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
            isTabActive: isActive,
            controller: filesController,
          ),
          OpenCrayTab.settings: (context, isActive) => const SizedBox.shrink(),
        },
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _openMessageMenu(WidgetTester tester, Finder bubble) async {
  final bubbleRect = tester.getRect(bubble);
  final gesture = await tester.startGesture(
    bubbleRect.topLeft + const Offset(24, 20),
  );
  await tester.pump(const Duration(milliseconds: 260));
  await gesture.up();
  await tester.pumpAndSettle();
}
