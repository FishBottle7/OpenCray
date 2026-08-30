import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_files_snapshot.dart';
import 'package:opencray/core/models/opencray_skills_snapshot.dart';
import 'package:opencray/features/files/files_feature.dart';
import 'package:opencray/features/skills/skills_feature.dart';

const OpenCrayFilesSnapshot _filesSnapshot = OpenCrayFilesSnapshot(
  rootName: 'agent-workspace',
  rootPath: '/tmp/agent-workspace',
  availableBytes: 2048,
  directoryCount: 0,
  fileCount: 1,
  entryCount: 1,
  isTruncated: false,
  children: <OpenCrayFileTreeNodeSnapshot>[
    OpenCrayFileTreeNodeSnapshot(
      name: 'notes.txt',
      relativePath: 'notes.txt',
      isDirectory: false,
      childCount: 0,
      sizeBytes: 12,
      isTruncated: false,
      children: <OpenCrayFileTreeNodeSnapshot>[],
    ),
  ],
);

const OpenCraySkillsSnapshot _skillsSnapshot = OpenCraySkillsSnapshot(
  installedSkills: <OpenCrayInstalledSkillSnapshot>[],
  installSources: <OpenCraySkillInstallSourceSnapshot>[],
  suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
);

/// Counts loads and can hold the first one open, which is the only way to see
/// the loading placeholder: the seed bridge normally answers within the frame.
class _CountingBridge extends OpenCraySeedBridge {
  _CountingBridge({this.stallFiles = false, this.stallSkills = false})
    : super(
        initialFilesSnapshot: _filesSnapshot,
        initialSkillsSnapshot: _skillsSnapshot,
      );

  final bool stallFiles;
  final bool stallSkills;
  int filesLoadCount = 0;
  int skillsLoadCount = 0;

  @override
  Future<OpenCrayFilesSnapshot> loadFilesSnapshot() {
    filesLoadCount += 1;
    if (stallFiles) {
      return Completer<OpenCrayFilesSnapshot>().future;
    }
    return super.loadFilesSnapshot();
  }

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
    int? suggestedLimit,
  }) {
    skillsLoadCount += 1;
    if (stallSkills) {
      return Completer<OpenCraySkillsSnapshot>().future;
    }
    return super.loadSkillsSnapshot(
      query: query,
      suggestedLimit: suggestedLimit,
    );
  }

  @override
  Stream<OpenCraySkillsSnapshot> watchSkillsSnapshot() => stallSkills
      ? const Stream<OpenCraySkillsSnapshot>.empty()
      : super.watchSkillsSnapshot();
}

Widget _host(Widget child) =>
    MaterialApp(home: Scaffold(body: child), debugShowCheckedModeBanner: false);

void main() {
  final copy = OpenCrayUiCopy.fromLocaleTag('en');

  testWidgets('files show the directory skeleton until the tree arrives', (
    tester,
  ) async {
    final bridge = _CountingBridge(stallFiles: true);
    await tester.pumpWidget(
      _host(FilesFeatureScreen(bridge: bridge, copy: copy)),
    );
    await tester.pump();

    expect(
      find.byKey(const ValueKey<String>('files-state-loading')),
      findsOneWidget,
    );
    expect(find.text(copy.contentLoadingLabel), findsNothing);
  });

  testWidgets('files pull-to-refresh reloads the snapshot', (tester) async {
    final bridge = _CountingBridge();
    await tester.pumpWidget(
      _host(FilesFeatureScreen(bridge: bridge, copy: copy)),
    );
    await tester.pumpAndSettle();
    final int loadsBeforeGesture = bridge.filesLoadCount;

    await tester.fling(
      find.byKey(const ValueKey<String>('files-scroll-view')),
      const Offset(0, 320),
      1000,
    );
    await tester.pumpAndSettle();

    expect(bridge.filesLoadCount, greaterThan(loadsBeforeGesture));
    expect(
      find.byKey(const ValueKey<String>('files-state-loading')),
      findsNothing,
    );
  });

  testWidgets('skills show the list skeleton until the snapshot arrives', (
    tester,
  ) async {
    final bridge = _CountingBridge(stallSkills: true);
    await tester.pumpWidget(
      _host(SkillsFeatureScreen(bridge: bridge, copy: copy)),
    );
    await tester.pump();

    expect(
      find.byKey(const ValueKey<String>('skills-state-loading')),
      findsOneWidget,
    );
  });
}
