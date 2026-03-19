import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_skills_snapshot.dart';
import 'package:opencray/features/skills/skills_feature.dart';

void main() {
  testWidgets('installed skill descriptions are truncated to two lines', (
    tester,
  ) async {
    const description =
        'This skill exposes a much longer summary so the manage list has enough content to wrap beyond two lines on smaller phone-width layouts.';

    final bridge = OpenCraySeedBridge(
      initialSkillsSnapshot: const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[
          OpenCrayInstalledSkillSnapshot(
            id: 'long-skill',
            name: 'long-skill',
            description: description,
            isEnabled: true,
            canDelete: false,
            sourceDirectoryPath: '/seed/skills/long-skill',
          ),
        ],
        installSources: <OpenCraySkillInstallSourceSnapshot>[],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SkillsFeatureScreen(
            bridge: bridge,
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    final descriptionText = tester.widget<Text>(find.text(description));
    expect(descriptionText.maxLines, 2);
    expect(descriptionText.overflow, TextOverflow.ellipsis);
  });

  testWidgets('install page uses searched results and installs by source ref', (
    tester,
  ) async {
    final bridge = _RecordingSkillsBridge(
      initialSkillsSnapshot: const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[],
        installSources: <OpenCraySkillInstallSourceSnapshot>[
          OpenCraySkillInstallSourceSnapshot(
            id: 'github-url',
            title: 'GitHub URL',
            subtitle: 'Enter a source ref in search.',
            ctaLabel: 'Use search',
            isAvailable: true,
          ),
        ],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
      ),
      searchedSnapshot: const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[],
        installSources: <OpenCraySkillInstallSourceSnapshot>[
          OpenCraySkillInstallSourceSnapshot(
            id: 'github-url',
            title: 'GitHub URL',
            subtitle: 'Enter a source ref in search.',
            ctaLabel: 'Use search',
            isAvailable: true,
          ),
        ],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[
          OpenCraySuggestedSkillSnapshot(
            id: 'roin-orca/skills/find-skills',
            name: 'find-skills',
            description: 'roin-orca/skills via skills.sh',
            sourceRef: 'roin-orca/skills@find-skills',
            sourceLabel: 'skills.sh',
          ),
        ],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SkillsFeatureScreen(
            bridge: bridge,
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
            initialPage: SkillsPage.install,
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    await tester.enterText(find.byType(TextField), 'find');
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 50));

    expect(bridge.lastQuery, 'find');
    expect(find.text('skills.sh'), findsOneWidget);

    await tester.tap(find.text('Install').last);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(bridge.lastInstalledSourceRef, 'roin-orca/skills@find-skills');
  });

  testWidgets('install page can install directly from typed source ref', (
    tester,
  ) async {
    final bridge = _RecordingSkillsBridge(
      initialSkillsSnapshot: const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[],
        installSources: <OpenCraySkillInstallSourceSnapshot>[],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SkillsFeatureScreen(
            bridge: bridge,
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
            initialPage: SkillsPage.install,
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    await tester.enterText(
      find.byType(TextField),
      'gitlab:acme/platform/skills@find-skills',
    );
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 50));

    await tester.tap(find.text('Install').last);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(
      bridge.lastInstalledSourceRef,
      'gitlab:acme/platform/skills@find-skills',
    );
  });

  testWidgets('install source card inspects and installs all discovered skills', (
    tester,
  ) async {
    final bridge = _RecordingSkillsBridge(
      initialSkillsSnapshot: const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[],
        installSources: <OpenCraySkillInstallSourceSnapshot>[
          OpenCraySkillInstallSourceSnapshot(
            id: 'github-url',
            title: 'GitHub URL',
            subtitle: 'Inspect a GitHub repository.',
            ctaLabel: 'Inspect',
            isAvailable: true,
          ),
        ],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
      ),
      inspectedSource: const OpenCraySkillSourceInspectionSnapshot(
        sourceType: 'remote_github',
        sourceRef: 'https://github.com/roin-orca/skills',
        sourcePath: 'https://github.com/roin-orca/skills',
        resolvedRevision: 'main',
        resolvedCommitSha: 'deadbeef',
        candidates: <OpenCraySkillSourceInspectionCandidateSnapshot>[
          OpenCraySkillSourceInspectionCandidateSnapshot(
            name: 'find-skills',
            description: 'Discover skills.',
            relativePath: 'skills/find-skills/SKILL.md',
          ),
          OpenCraySkillSourceInspectionCandidateSnapshot(
            name: 'review-skills',
            description: 'Review changes.',
            relativePath: 'skills/review-skills/SKILL.md',
          ),
        ],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SkillsFeatureScreen(
            bridge: bridge,
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
            initialPage: SkillsPage.install,
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    await tester.tap(find.text('GitHub URL'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byType(TextField).last,
      'https://github.com/roin-orca/skills',
    );
    await tester.tap(find.widgetWithText(FilledButton, 'Inspect'));
    await tester.pumpAndSettle();

    expect(bridge.lastInspectedSourceRef, 'https://github.com/roin-orca/skills');
    expect(find.text('Select all'), findsOneWidget);

    await tester.tap(find.text('Install selected (2)'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(
      bridge.batchInstallRequests,
      <String>[
        'https://github.com/roin-orca/skills#find-skills,review-skills',
      ],
    );
  });

  testWidgets('manage page update action updates the selected installed skill', (
    tester,
  ) async {
    final bridge = _RecordingSkillsBridge(
      initialSkillsSnapshot: const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[
          OpenCrayInstalledSkillSnapshot(
            id: 'find-skills',
            name: 'find-skills',
            description: 'Discover and install additional skills.',
            isEnabled: true,
            canDelete: true,
            sourceDirectoryPath: '/seed/skills/find-skills',
          ),
        ],
        installSources: <OpenCraySkillInstallSourceSnapshot>[],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SkillsFeatureScreen(
            bridge: bridge,
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    await tester.tap(find.byIcon(Icons.more_horiz_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Update skills'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(bridge.lastUpdatedSkillId, 'find-skills');
  });
}

class _RecordingSkillsBridge extends OpenCraySeedBridge {
  _RecordingSkillsBridge({
    required super.initialSkillsSnapshot,
    this.searchedSnapshot,
    this.inspectedSource,
  });

  final OpenCraySkillsSnapshot? searchedSnapshot;
  final OpenCraySkillSourceInspectionSnapshot? inspectedSource;
  String? lastQuery;
  String? lastInstalledSourceRef;
  String? lastInspectedSourceRef;
  String? lastUpdatedSkillId;
  final List<String> installRequests = <String>[];
  final List<String> batchInstallRequests = <String>[];

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({String query = ''}) async {
    lastQuery = query;
    if (query.trim().isNotEmpty && searchedSnapshot != null) {
      return searchedSnapshot!;
    }
    return super.loadSkillsSnapshot(query: query);
  }

  @override
  Future<OpenCraySkillSourceInspectionSnapshot> inspectSkillSource(
    String sourceRef,
  ) async {
    lastInspectedSourceRef = sourceRef;
    return inspectedSource ??
        (throw StateError('No inspected source configured for $sourceRef'));
  }

  @override
  Future<String?> installSkillSource(
    String sourceRef, {
    String selectedSkillName = '',
  }) async {
    lastInstalledSourceRef = sourceRef;
    installRequests.add(
      selectedSkillName.trim().isEmpty
          ? sourceRef
          : '$sourceRef#$selectedSkillName',
    );
    return 'Installed $sourceRef';
  }

  @override
  Future<String?> installSkillSourceBatch(
    String sourceRef, {
    List<String> selectedSkillNames = const <String>[],
  }) async {
    batchInstallRequests.add(
      selectedSkillNames.isEmpty
          ? sourceRef
          : '$sourceRef#${selectedSkillNames.join(',')}',
    );
    return 'Installed ${selectedSkillNames.length} skills.';
  }

  @override
  Future<String?> updateInstalledSkill(String skillId) async {
    lastUpdatedSkillId = skillId;
    return 'Updated $skillId';
  }
}
