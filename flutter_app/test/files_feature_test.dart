import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_files_snapshot.dart';
import 'package:opencray/features/files/files_feature.dart';

void main() {
  testWidgets('files feature renders and filters the workspace tree', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
      initialFilesSnapshot: const OpenCrayFilesSnapshot(
        rootName: 'agent-workspace',
        rootPath: '/tmp/agent-workspace',
        availableBytes: 2048,
        directoryCount: 1,
        fileCount: 2,
        entryCount: 3,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[
          OpenCrayFileTreeNodeSnapshot(
            name: 'docs',
            relativePath: 'docs',
            isDirectory: true,
            childCount: 1,
            sizeBytes: null,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[
              OpenCrayFileTreeNodeSnapshot(
                name: 'report.md',
                relativePath: 'docs/report.md',
                isDirectory: false,
                childCount: 0,
                sizeBytes: 512,
                isTruncated: false,
                children: <OpenCrayFileTreeNodeSnapshot>[],
              ),
            ],
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

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: FilesFeatureScreen(
            bridge: bridge,
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('agent-workspace'), findsOneWidget);
    expect(find.text('docs'), findsOneWidget);
    expect(find.text('todo.txt'), findsOneWidget);
    expect(find.text('report.md'), findsNothing);

    await tester.tap(find.text('docs'));
    await tester.pumpAndSettle();

    expect(find.text('report.md'), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'report');
    await tester.pumpAndSettle();

    expect(find.text('report.md'), findsOneWidget);
    expect(find.text('todo.txt'), findsNothing);
  });
}
