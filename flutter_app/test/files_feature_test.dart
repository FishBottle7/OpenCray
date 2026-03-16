import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/app/opencray_app_shell.dart';
import 'package:opencray/app/opencray_tabs.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/design/opencray_widgets.dart';
import 'package:opencray/core/models/opencray_files_snapshot.dart';
import 'package:opencray/core/models/opencray_shell_snapshot.dart';
import 'package:opencray/features/files/files_feature.dart';

void main() {
  testWidgets(
    'files feature browses directories and supports breadcrumb jumps',
    (tester) async {
      final bridge = OpenCraySeedBridge(
        initialFilesSnapshot: const OpenCrayFilesSnapshot(
          rootName: 'agent-workspace',
          rootPath: '/tmp/agent-workspace',
          availableBytes: 2048,
          directoryCount: 2,
          fileCount: 2,
          entryCount: 4,
          isTruncated: false,
          children: <OpenCrayFileTreeNodeSnapshot>[
            OpenCrayFileTreeNodeSnapshot(
              name: 'docs',
              relativePath: 'docs',
              isDirectory: true,
              childCount: 2,
              sizeBytes: null,
              isTruncated: false,
              children: <OpenCrayFileTreeNodeSnapshot>[
                OpenCrayFileTreeNodeSnapshot(
                  name: 'done',
                  relativePath: 'docs/done',
                  isDirectory: true,
                  childCount: 1,
                  sizeBytes: null,
                  isTruncated: false,
                  children: <OpenCrayFileTreeNodeSnapshot>[
                    OpenCrayFileTreeNodeSnapshot(
                      name: 'notes.txt',
                      relativePath: 'docs/done/notes.txt',
                      isDirectory: false,
                      childCount: 0,
                      sizeBytes: 64,
                      isTruncated: false,
                      children: <OpenCrayFileTreeNodeSnapshot>[],
                    ),
                  ],
                ),
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

      await _pumpFilesScreen(tester, bridge: bridge);

      expect(find.text('Files'), findsOneWidget);
      expect(find.text('Location'), findsOneWidget);
      expect(find.text('docs'), findsOneWidget);
      expect(find.text('todo.txt'), findsOneWidget);
      expect(find.text('report.md'), findsNothing);

      await tester.tap(find.byKey(const ValueKey<String>('files-row-docs')));
      await tester.pumpAndSettle();

      expect(find.text('done'), findsOneWidget);
      expect(find.text('report.md'), findsOneWidget);
      expect(find.text('todo.txt'), findsNothing);

      await tester.tap(
        find.byKey(const ValueKey<String>('files-row-docs/done')),
      );
      await tester.pumpAndSettle();

      expect(find.text('notes.txt'), findsOneWidget);
      expect(find.text('report.md'), findsNothing);

      await tester.tap(
        find.byKey(const ValueKey<String>('files-breadcrumb-docs')),
      );
      await tester.pumpAndSettle();

      expect(find.text('done'), findsOneWidget);
      expect(find.text('report.md'), findsOneWidget);
      expect(find.text('notes.txt'), findsNothing);

      await tester.tap(
        find.byKey(const ValueKey<String>('files-breadcrumb-root')),
      );
      await tester.pumpAndSettle();

      expect(find.text('docs'), findsOneWidget);
      expect(find.text('todo.txt'), findsOneWidget);
    },
  );

  testWidgets('long press enters selection mode and shows the action bar', (
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

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.longPress(
      find.byKey(const ValueKey<String>('files-row-docs')),
    );
    await tester.pumpAndSettle();

    expect(find.text('1 Selected'), findsOneWidget);
    expect(find.text('Done'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('files-selection-toolbar')),
      findsOneWidget,
    );
    expect(find.text('Share'), findsOneWidget);
    expect(find.text('Move'), findsOneWidget);
    expect(find.text('Copy'), findsOneWidget);
    expect(find.text('Rename'), findsOneWidget);
    expect(find.text('Delete'), findsOneWidget);
    expect(find.text('New'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey<String>('files-row-todo.txt')));
    await tester.pumpAndSettle();

    expect(find.text('2 Selected'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey<String>('files-selection-done')),
    );
    await tester.pumpAndSettle();

    expect(find.text('Files'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('files-selection-toolbar')),
      findsNothing,
    );
  });

  testWidgets('system back exits selection mode before popping the route', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
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

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.longPress(
      find.byKey(const ValueKey<String>('files-row-docs')),
    );
    await tester.pumpAndSettle();

    expect(find.text('1 Selected'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.byType(FilesFeatureScreen), findsOneWidget);
    expect(find.text('Files'), findsOneWidget);
    expect(find.text('1 Selected'), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('files-selection-toolbar')),
      findsNothing,
    );
  });

  testWidgets('move enters pending transfer mode and paste relocates files', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
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

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.longPress(
      find.byKey(const ValueKey<String>('files-row-todo.txt')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Move'));
    await tester.pumpAndSettle();

    expect(find.text('Files'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('files-toolbar-action-paste')),
      findsOneWidget,
    );

    final opacity = tester.widget<AnimatedOpacity>(
      find.ancestor(
        of: find.byKey(const ValueKey<String>('files-row-todo.txt')),
        matching: find.byType(AnimatedOpacity),
      ),
    );
    expect(opacity.opacity, 0.38);

    await tester.tap(find.byKey(const ValueKey<String>('files-row-docs')));
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey<String>('files-toolbar-action-paste')),
    );
    await tester.pumpAndSettle();

    expect(find.text('todo.txt'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('files-toolbar-action-paste')),
      findsNothing,
    );

    await tester.tap(
      find.byKey(const ValueKey<String>('files-breadcrumb-root')),
    );
    await tester.pumpAndSettle();

    expect(find.text('todo.txt'), findsNothing);
    expect(find.text('docs'), findsOneWidget);
  });

  testWidgets('system back clears pending transfer mode', (tester) async {
    final bridge = OpenCraySeedBridge(
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

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.longPress(
      find.byKey(const ValueKey<String>('files-row-todo.txt')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Copy'));
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-toolbar-action-paste')),
      findsOneWidget,
    );

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.byType(FilesFeatureScreen), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('files-toolbar-action-paste')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('files-selection-toolbar')),
      findsNothing,
    );
  });

  testWidgets('copy pending state clears when the files tab becomes inactive', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
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

    await _pumpFilesScreen(
      tester,
      bridge: bridge,
      isTabActive: true,
      screenKey: const ValueKey<String>('files-screen'),
    );

    await tester.longPress(
      find.byKey(const ValueKey<String>('files-row-todo.txt')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Copy'));
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-toolbar-action-paste')),
      findsOneWidget,
    );

    final opacity = tester.widget<AnimatedOpacity>(
      find.ancestor(
        of: find.byKey(const ValueKey<String>('files-row-todo.txt')),
        matching: find.byType(AnimatedOpacity),
      ),
    );
    expect(opacity.opacity, 1);

    await _pumpFilesScreen(
      tester,
      bridge: bridge,
      isTabActive: false,
      screenKey: const ValueKey<String>('files-screen'),
    );

    expect(
      find.byKey(const ValueKey<String>('files-selection-toolbar')),
      findsNothing,
    );
  });

  testWidgets('selection mode clears when the files tab becomes inactive', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
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

    await _pumpFilesScreen(
      tester,
      bridge: bridge,
      isTabActive: true,
      screenKey: const ValueKey<String>('files-screen'),
    );

    await tester.longPress(
      find.byKey(const ValueKey<String>('files-row-todo.txt')),
    );
    await tester.pumpAndSettle();

    expect(find.text('1 Selected'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('files-selection-toolbar')),
      findsOneWidget,
    );

    await _pumpFilesScreen(
      tester,
      bridge: bridge,
      isTabActive: false,
      screenKey: const ValueKey<String>('files-screen'),
    );

    expect(find.text('Files'), findsOneWidget);
    expect(find.text('1 Selected'), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('files-selection-toolbar')),
      findsNothing,
    );
  });

  testWidgets('browse mode shows a sticky New bar after scrolling', (
    tester,
  ) async {
    final children = List<OpenCrayFileTreeNodeSnapshot>.generate(
      24,
      (index) => OpenCrayFileTreeNodeSnapshot(
        name: 'item-$index.txt',
        relativePath: 'item-$index.txt',
        isDirectory: false,
        childCount: 0,
        sizeBytes: 128 + index,
        isTruncated: false,
        children: const <OpenCrayFileTreeNodeSnapshot>[],
      ),
    );
    final bridge = OpenCraySeedBridge(
      initialFilesSnapshot: OpenCrayFilesSnapshot(
        rootName: 'agent-workspace',
        rootPath: '/tmp/agent-workspace',
        availableBytes: 2048,
        directoryCount: 0,
        fileCount: children.length,
        entryCount: children.length,
        isTruncated: false,
        children: children,
      ),
    );

    await _pumpFilesScreen(tester, bridge: bridge);

    expect(
      find.byKey(const ValueKey<String>('files-sticky-bar')),
      findsNothing,
    );

    await tester.drag(
      find.byKey(const ValueKey<String>('files-scroll-view')),
      const Offset(0, -260),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-sticky-bar')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('files-sticky-new')),
      findsOneWidget,
    );
  });

  testWidgets('new creates supported text files and opens the editor', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
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

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.tap(find.byKey(const ValueKey<String>('files-location-new')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey<String>('files-create-entry-field')),
      'notes.txt',
    );
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey<String>('files-create-entry-submit')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-text-editor-dialog')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('files-row-notes.txt')),
      findsOneWidget,
    );
    expect(find.text('notes.txt'), findsWidgets);
  });

  testWidgets('new blocks unsupported file types inline', (tester) async {
    final bridge = OpenCraySeedBridge(
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

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.tap(find.byKey(const ValueKey<String>('files-location-new')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey<String>('files-create-entry-field')),
      'archive.zip',
    );
    await tester.pumpAndSettle();

    expect(
      find.text('This file type is not supported here yet.'),
      findsOneWidget,
    );
    final submitButton = tester.widget<TextButton>(
      find.byKey(const ValueKey<String>('files-create-entry-submit')),
    );
    expect(submitButton.onPressed, isNull);
  });

  testWidgets('tapping a text file opens the preview dialog', (tester) async {
    final bridge = OpenCraySeedBridge(
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
            childCount: 1,
            sizeBytes: null,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[
              OpenCrayFileTreeNodeSnapshot(
                name: 'report.md',
                relativePath: 'docs/report.md',
                isDirectory: false,
                childCount: 0,
                sizeBytes: 256,
                isTruncated: false,
                children: <OpenCrayFileTreeNodeSnapshot>[],
              ),
            ],
          ),
        ],
      ),
    );

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.tap(find.byKey(const ValueKey<String>('files-row-docs')));
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey<String>('files-row-docs/report.md')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-text-preview-dialog')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('files-text-preview-backdrop')),
      findsOneWidget,
    );
    expect(find.byType(BackdropFilter), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('files-text-preview-title')),
      findsOneWidget,
    );
    expect(find.text('report.md'), findsWidgets);
    expect(find.textContaining('Preview for docs/report.md'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey<String>('files-text-preview-close')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-text-preview-dialog')),
      findsNothing,
    );
  });

  testWidgets('markdown files render as markdown inside the preview dialog', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
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
            name: 'app',
            relativePath: 'app',
            isDirectory: true,
            childCount: 1,
            sizeBytes: null,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[
              OpenCrayFileTreeNodeSnapshot(
                name: 'README.md',
                relativePath: 'app/README.md',
                isDirectory: false,
                childCount: 0,
                sizeBytes: 96,
                isTruncated: false,
                children: <OpenCrayFileTreeNodeSnapshot>[],
              ),
            ],
          ),
        ],
      ),
    );

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.tap(find.byKey(const ValueKey<String>('files-row-app')));
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey<String>('files-row-app/README.md')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-text-preview-markdown')),
      findsOneWidget,
    );
    expect(find.text('OpenCray Shell'), findsOneWidget);
  });

  testWidgets('double tapping preview body opens the text editor', (
    tester,
  ) async {
    final bridge = OpenCraySeedBridge(
      initialFilesSnapshot: const OpenCrayFilesSnapshot(
        rootName: 'agent-workspace',
        rootPath: '/tmp/agent-workspace',
        availableBytes: 2048,
        directoryCount: 0,
        fileCount: 1,
        entryCount: 1,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[
          OpenCrayFileTreeNodeSnapshot(
            name: 'workspace-notes.txt',
            relativePath: 'workspace-notes.txt',
            isDirectory: false,
            childCount: 0,
            sizeBytes: 128,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[],
          ),
        ],
      ),
    );

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.tap(
      find.byKey(const ValueKey<String>('files-row-workspace-notes.txt')),
    );
    await tester.pumpAndSettle();

    final previewBody = find.byKey(
      const ValueKey<String>('files-text-preview-body'),
    );
    await tester.tap(previewBody);
    await tester.pump(const Duration(milliseconds: 40));
    await tester.tap(previewBody);
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-text-editor-dialog')),
      findsOneWidget,
    );
  });

  testWidgets('saving text edits updates later previews', (tester) async {
    final bridge = OpenCraySeedBridge(
      initialFilesSnapshot: const OpenCrayFilesSnapshot(
        rootName: 'agent-workspace',
        rootPath: '/tmp/agent-workspace',
        availableBytes: 2048,
        directoryCount: 0,
        fileCount: 1,
        entryCount: 1,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[
          OpenCrayFileTreeNodeSnapshot(
            name: 'workspace-notes.txt',
            relativePath: 'workspace-notes.txt',
            isDirectory: false,
            childCount: 0,
            sizeBytes: 128,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[],
          ),
        ],
      ),
    );

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.tap(
      find.byKey(const ValueKey<String>('files-row-workspace-notes.txt')),
    );
    await tester.pumpAndSettle();

    final previewBody = find.byKey(
      const ValueKey<String>('files-text-preview-body'),
    );
    await tester.tap(previewBody);
    await tester.pump(const Duration(milliseconds: 40));
    await tester.tap(previewBody);
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const ValueKey<String>('files-text-editor-field')),
      'Edited body',
    );
    await tester.tap(
      find.byKey(const ValueKey<String>('files-text-editor-save')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-text-editor-dialog')),
      findsNothing,
    );

    await tester.tap(
      find.byKey(const ValueKey<String>('files-row-workspace-notes.txt')),
    );
    await tester.pumpAndSettle();

    expect(find.text('Edited body'), findsOneWidget);
  });

  testWidgets('tapping an image file opens the preview dialog', (tester) async {
    final bridge = OpenCraySeedBridge(
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
            name: 'images',
            relativePath: 'images',
            isDirectory: true,
            childCount: 1,
            sizeBytes: null,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[
              OpenCrayFileTreeNodeSnapshot(
                name: 'cover.png',
                relativePath: 'images/cover.png',
                isDirectory: false,
                childCount: 0,
                sizeBytes: 256,
                isTruncated: false,
                children: <OpenCrayFileTreeNodeSnapshot>[],
              ),
            ],
          ),
        ],
      ),
    );

    await _pumpFilesScreen(tester, bridge: bridge);

    await tester.tap(find.byKey(const ValueKey<String>('files-row-images')));
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey<String>('files-row-images/cover.png')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-image-preview-dialog')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('files-image-preview-backdrop')),
      findsOneWidget,
    );
    expect(find.byType(BackdropFilter), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('files-image-preview-title')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('files-image-preview-image')),
      findsOneWidget,
    );

    await tester.tap(
      find.byKey(const ValueKey<String>('files-image-preview-close')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-image-preview-dialog')),
      findsNothing,
    );
  });

  testWidgets('selection toolbar stays above the shell tab bar', (
    tester,
  ) async {
    final filesController = FilesFeatureController();
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

    await tester.pumpWidget(
      MaterialApp(
        home: OpenCrayAppShell(
          bridge: bridge,
          initialSnapshot: const OpenCrayShellSnapshot(
            initialTab: OpenCrayTab.files,
            localeTag: 'en',
            hostLabel: 'HOST READY',
            hostSummary: 'Ready',
            isHostConnected: true,
          ),
          initialTab: OpenCrayTab.files,
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
            OpenCrayTab.settings: (context, isActive) =>
                const SizedBox.shrink(),
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(OpenCrayBottomNavigation), findsOneWidget);

    await tester.longPress(
      find.byKey(const ValueKey<String>('files-row-docs')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('files-selection-toolbar')),
      findsOneWidget,
    );
    expect(find.byType(OpenCrayBottomNavigation), findsOneWidget);
  });

  testWidgets(
    'selection toolbar stays pinned to the shell tab bar inside a short subdirectory',
    (tester) async {
      final filesController = FilesFeatureController();
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
              childCount: 1,
              sizeBytes: null,
              isTruncated: false,
              children: <OpenCrayFileTreeNodeSnapshot>[
                OpenCrayFileTreeNodeSnapshot(
                  name: 'todo.txt',
                  relativePath: 'docs/todo.txt',
                  isDirectory: false,
                  childCount: 0,
                  sizeBytes: 128,
                  isTruncated: false,
                  children: <OpenCrayFileTreeNodeSnapshot>[],
                ),
              ],
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: OpenCrayAppShell(
            bridge: bridge,
            initialSnapshot: const OpenCrayShellSnapshot(
              initialTab: OpenCrayTab.files,
              localeTag: 'en',
              hostLabel: 'HOST READY',
              hostSummary: 'Ready',
              isHostConnected: true,
            ),
            initialTab: OpenCrayTab.files,
            filesController: filesController,
            buildersForSnapshot: (_) => {
              OpenCrayTab.chat: (context, isActive) => const SizedBox.shrink(),
              OpenCrayTab.skills: (context, isActive) =>
                  const SizedBox.shrink(),
              OpenCrayTab.files: (context, isActive) => FilesFeatureScreen(
                bridge: bridge,
                copy: OpenCrayUiCopy.fromLocaleTag('en'),
                isTabActive: isActive,
                controller: filesController,
              ),
              OpenCrayTab.settings: (context, isActive) =>
                  const SizedBox.shrink(),
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey<String>('files-row-docs')));
      await tester.pumpAndSettle();
      await tester.longPress(
        find.byKey(const ValueKey<String>('files-row-docs/todo.txt')),
      );
      await tester.pumpAndSettle();

      final toolbarRect = tester.getRect(
        find.byKey(const ValueKey<String>('files-selection-toolbar')),
      );
      final tabBarRect = tester.getRect(find.byType(OpenCrayBottomNavigation));

      expect(toolbarRect.bottom, tabBarRect.top);
    },
  );
}

Future<void> _pumpFilesScreen(
  WidgetTester tester, {
  required OpenCraySeedBridge bridge,
  bool isTabActive = true,
  Key? screenKey,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: FilesFeatureScreen(
          key: screenKey,
          bridge: bridge,
          copy: OpenCrayUiCopy.fromLocaleTag('en'),
          isTabActive: isTabActive,
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}
