import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/app/opencray_app_shell.dart';
import 'package:opencray/app/opencray_tabs.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_files_snapshot.dart';
import 'package:opencray/core/models/opencray_shell_snapshot.dart';
import 'package:opencray/features/files/files.dart';

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

    await _pumpShell(
      tester,
      bridge: bridge,
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

    await _pumpShell(
      tester,
      bridge: bridge,
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
}

Future<void> _pumpShell(
  WidgetTester tester, {
  required OpenCraySeedBridge bridge,
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
        filesController: filesController,
        buildersForSnapshot: (_) => {
          OpenCrayTab.chat: (context, isActive) => const SizedBox.shrink(),
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
