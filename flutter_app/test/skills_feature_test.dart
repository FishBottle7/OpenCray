import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/design/opencray_motion.dart';
import 'package:opencray/core/models/opencray_skills_snapshot.dart';
import 'package:opencray/features/skills/skills_feature.dart';

void main() {
  testWidgets('skills refresh when the tab becomes active again', (
    tester,
  ) async {
    final bridge = _RecordingSkillsBridge(
      initialSkillsSnapshot: const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[
          OpenCrayInstalledSkillSnapshot(
            id: 'find-skills',
            name: 'find-skills',
            description: 'Discover skills.',
            isEnabled: true,
            canDelete: true,
            sourceDirectoryPath: '/seed/skills/find-skills',
          ),
        ],
        installSources: <OpenCraySkillInstallSourceSnapshot>[],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
      ),
    );

    const screenKey = ValueKey<String>('skills-screen');
    await _pumpSkillsScreen(
      tester,
      bridge: bridge,
      isTabActive: false,
      screenKey: screenKey,
      autoRefreshPollInterval: const Duration(days: 1),
    );

    bridge.replaceDefaultSnapshot(
      const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[
          OpenCrayInstalledSkillSnapshot(
            id: 'find-skills',
            name: 'find-skills',
            description: 'Discover skills.',
            isEnabled: true,
            canDelete: true,
            sourceDirectoryPath: '/seed/skills/find-skills',
          ),
          OpenCrayInstalledSkillSnapshot(
            id: 'review-skills',
            name: 'review-skills',
            description: 'Review code changes.',
            isEnabled: true,
            canDelete: true,
            sourceDirectoryPath: '/seed/skills/review-skills',
          ),
        ],
        installSources: <OpenCraySkillInstallSourceSnapshot>[],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
      ),
    );

    await _pumpSkillsScreen(
      tester,
      bridge: bridge,
      isTabActive: true,
      screenKey: screenKey,
      autoRefreshPollInterval: const Duration(days: 1),
    );

    expect(find.text('review-skills'), findsOneWidget);
  });

  testWidgets('skills refresh when the app resumes', (tester) async {
    final bridge = _RecordingSkillsBridge(
      initialSkillsSnapshot: const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[
          OpenCrayInstalledSkillSnapshot(
            id: 'find-skills',
            name: 'find-skills',
            description: 'Discover skills.',
            isEnabled: true,
            canDelete: true,
            sourceDirectoryPath: '/seed/skills/find-skills',
          ),
        ],
        installSources: <OpenCraySkillInstallSourceSnapshot>[],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
      ),
    );

    await _pumpSkillsScreen(
      tester,
      bridge: bridge,
      autoRefreshPollInterval: const Duration(days: 1),
    );

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await tester.pump();

    bridge.replaceDefaultSnapshot(
      const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[
          OpenCrayInstalledSkillSnapshot(
            id: 'find-skills',
            name: 'find-skills',
            description: 'Discover skills.',
            isEnabled: true,
            canDelete: true,
            sourceDirectoryPath: '/seed/skills/find-skills',
          ),
          OpenCrayInstalledSkillSnapshot(
            id: 'review-skills',
            name: 'review-skills',
            description: 'Review code changes.',
            isEnabled: true,
            canDelete: true,
            sourceDirectoryPath: '/seed/skills/review-skills',
          ),
        ],
        installSources: <OpenCraySkillInstallSourceSnapshot>[],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
      ),
    );

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    await tester.pumpAndSettle();

    expect(find.text('review-skills'), findsOneWidget);
  });

  testWidgets(
    'skills polling refreshes active content after external changes',
    (tester) async {
      final bridge = _RecordingSkillsBridge(
        initialSkillsSnapshot: const OpenCraySkillsSnapshot(
          installedSkills: <OpenCrayInstalledSkillSnapshot>[
            OpenCrayInstalledSkillSnapshot(
              id: 'find-skills',
              name: 'find-skills',
              description: 'Discover skills.',
              isEnabled: true,
              canDelete: true,
              sourceDirectoryPath: '/seed/skills/find-skills',
            ),
          ],
          installSources: <OpenCraySkillInstallSourceSnapshot>[],
          suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
        ),
      );

      await _pumpSkillsScreen(
        tester,
        bridge: bridge,
        autoRefreshPollInterval: const Duration(milliseconds: 80),
      );

      bridge.replaceDefaultSnapshot(
        const OpenCraySkillsSnapshot(
          installedSkills: <OpenCrayInstalledSkillSnapshot>[
            OpenCrayInstalledSkillSnapshot(
              id: 'find-skills',
              name: 'find-skills',
              description: 'Discover skills.',
              isEnabled: true,
              canDelete: true,
              sourceDirectoryPath: '/seed/skills/find-skills',
            ),
            OpenCrayInstalledSkillSnapshot(
              id: 'review-skills',
              name: 'review-skills',
              description: 'Review code changes.',
              isEnabled: true,
              canDelete: true,
              sourceDirectoryPath: '/seed/skills/review-skills',
            ),
          ],
          installSources: <OpenCraySkillInstallSourceSnapshot>[],
          suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
        ),
      );

      await tester.pump(const Duration(milliseconds: 90));
      await tester.pump();

      expect(find.text('review-skills'), findsOneWidget);
    },
  );

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

    await _pumpSkillsScreen(tester, bridge: bridge);
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
            installs: 42,
            detailUrl: 'https://skills.sh/roin-orca/skills',
          ),
        ],
      ),
    );

    await _pumpSkillsScreen(
      tester,
      bridge: bridge,
      initialPage: SkillsPage.install,
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    await tester.enterText(find.byType(TextField), 'find');
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 50));

    expect(bridge.lastQuery, 'find');
    expect(bridge.lastSuggestedLimit, 8);
    expect(find.text('skills.sh'), findsOneWidget);
    expect(find.text('42 installs'), findsOneWidget);
    expect(find.text('Install from source'), findsNothing);

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

    await _pumpSkillsScreen(
      tester,
      bridge: bridge,
      initialPage: SkillsPage.install,
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

  testWidgets('install page shows results above sources and can load more', (
    tester,
  ) async {
    final bridge = _RecordingSkillsBridge(
      initialSkillsSnapshot: _skillsSnapshotWithSources(),
      searchedSnapshot: _skillsSnapshotWithSources(
        suggestedSkills: _buildSuggestedSkills(8),
        suggestedSkillsMayHaveMore: true,
      ),
      expandedSearchedSnapshot: _skillsSnapshotWithSources(
        suggestedSkills: _buildSuggestedSkills(9),
      ),
    );

    await _pumpSkillsScreen(
      tester,
      bridge: bridge,
      initialPage: SkillsPage.install,
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    await tester.enterText(find.byType(TextField), 'find');
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 50));

    expect(bridge.lastSuggestedLimit, 8);
    expect(
      tester.getTopLeft(find.text('find-skill-1')).dy,
      lessThan(tester.getTopLeft(find.text('GitHub URL')).dy),
    );
    expect(find.text('Show more'), findsOneWidget);

    await tester.ensureVisible(find.text('Show more'));
    await tester.tap(find.text('Show more'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(bridge.lastSuggestedLimit, 20);
    expect(find.text('find-skill-9'), findsOneWidget);
  });

  testWidgets('manage and install pages switch with horizontal direction', (
    tester,
  ) async {
    final bridge = _RecordingSkillsBridge(
      initialSkillsSnapshot: _skillsSnapshotWithSources(),
    );

    await _pumpSkillsScreen(tester, bridge: bridge);

    OpenCrayDirectionalSwitcher switcher = tester
        .widget<OpenCrayDirectionalSwitcher>(
          find.byType(OpenCrayDirectionalSwitcher),
        );
    expect(switcher.direction, -1);

    await tester.tap(find.text('Install'));
    await tester.pump();

    switcher = tester.widget<OpenCrayDirectionalSwitcher>(
      find.byType(OpenCrayDirectionalSwitcher),
    );
    expect(switcher.direction, 1);
    expect(find.text('GitHub URL'), findsOneWidget);
  });

  testWidgets('install page can preview suggested skill contents', (
    tester,
  ) async {
    final bridge = _RecordingSkillsBridge(
      initialSkillsSnapshot: _skillsSnapshotWithSources(),
      searchedSnapshot: _skillsSnapshotWithSources(
        suggestedSkills: const <OpenCraySuggestedSkillSnapshot>[
          OpenCraySuggestedSkillSnapshot(
            id: 'roin-orca/skills/find-skills',
            name: 'find-skills',
            description: 'roin-orca/skills via skills.sh',
            sourceRef: 'roin-orca/skills@find-skills',
            sourceLabel: 'skills.sh',
            installs: 42,
            detailUrl: 'https://skills.sh/roin-orca/skills',
          ),
        ],
      ),
      suggestedInstructions: const OpenCraySkillInstructionsSnapshot(
        id: 'find-skills',
        name: 'find-skills',
        description: 'Find and install useful skills.',
        markdownBody: '## Usage\nUse this skill to discover skills.',
        sourceDirectoryPath: 'https://skills.sh/roin-orca/skills',
        isEnabled: false,
        canDelete: false,
      ),
    );

    await _pumpSkillsScreen(
      tester,
      bridge: bridge,
      initialPage: SkillsPage.install,
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    await tester.enterText(find.byType(TextField), 'find');
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 50));

    await tester.tap(find.text('Preview'));
    await tester.pumpAndSettle();

    expect(bridge.lastPreviewSourceRef, 'roin-orca/skills@find-skills');
    expect(bridge.lastPreviewSkillName, 'find-skills');
    expect(
      find.textContaining('Use this skill to discover skills.'),
      findsOneWidget,
    );
  });

  testWidgets(
    'install source card inspects and installs all discovered skills',
    (tester) async {
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

      await _pumpSkillsScreen(
        tester,
        bridge: bridge,
        initialPage: SkillsPage.install,
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

      expect(
        bridge.lastInspectedSourceRef,
        'https://github.com/roin-orca/skills',
      );
      expect(find.text('Select all'), findsOneWidget);

      await tester.tap(find.text('Install selected (2)'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));

      expect(bridge.batchInstallRequests, <String>[
        'https://github.com/roin-orca/skills#find-skills,review-skills',
      ]);
    },
  );

  testWidgets(
    'manage page update action updates the selected installed skill',
    (tester) async {
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

      await _pumpSkillsScreen(tester, bridge: bridge);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));

      await tester.tap(find.byIcon(Icons.more_horiz_rounded));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Update skills'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));

      expect(bridge.lastUpdatedSkillId, 'find-skills');
    },
  );
}

class _RecordingSkillsBridge extends OpenCraySeedBridge {
  _RecordingSkillsBridge({
    required super.initialSkillsSnapshot,
    this.searchedSnapshot,
    this.expandedSearchedSnapshot,
    this.inspectedSource,
    this.suggestedInstructions,
  }) : _defaultSnapshot = initialSkillsSnapshot!;

  final OpenCraySkillsSnapshot? searchedSnapshot;
  final OpenCraySkillsSnapshot? expandedSearchedSnapshot;
  final OpenCraySkillSourceInspectionSnapshot? inspectedSource;
  final OpenCraySkillInstructionsSnapshot? suggestedInstructions;
  OpenCraySkillsSnapshot _defaultSnapshot;
  String? lastQuery;
  int? lastSuggestedLimit;
  String? lastInstalledSourceRef;
  String? lastInspectedSourceRef;
  String? lastPreviewSourceRef;
  String? lastPreviewSkillName;
  String? lastUpdatedSkillId;
  final List<String> installRequests = <String>[];
  final List<String> batchInstallRequests = <String>[];

  void replaceDefaultSnapshot(OpenCraySkillsSnapshot snapshot) {
    _defaultSnapshot = snapshot;
  }

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
    int? suggestedLimit,
  }) async {
    lastQuery = query;
    lastSuggestedLimit = suggestedLimit;
    if (query.trim().isNotEmpty && searchedSnapshot != null) {
      if ((suggestedLimit ?? 0) >= 20 && expandedSearchedSnapshot != null) {
        return expandedSearchedSnapshot!;
      }
      return searchedSnapshot!;
    }
    return _defaultSnapshot;
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
  Future<OpenCraySkillInstructionsSnapshot?> loadSuggestedSkillInstructions(
    String sourceRef, {
    String selectedSkillName = '',
  }) async {
    lastPreviewSourceRef = sourceRef;
    lastPreviewSkillName = selectedSkillName;
    return suggestedInstructions;
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

Future<void> _pumpSkillsScreen(
  WidgetTester tester, {
  required OpenCrayHostBridge bridge,
  SkillsPage initialPage = SkillsPage.manage,
  bool isTabActive = true,
  Duration autoRefreshPollInterval = const Duration(seconds: 2),
  Key? screenKey,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: SkillsFeatureScreen(
          key: screenKey,
          bridge: bridge,
          copy: OpenCrayUiCopy.fromLocaleTag('en'),
          initialPage: initialPage,
          isTabActive: isTabActive,
          autoRefreshPollInterval: autoRefreshPollInterval,
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

OpenCraySkillsSnapshot _skillsSnapshotWithSources({
  List<OpenCraySuggestedSkillSnapshot> suggestedSkills =
      const <OpenCraySuggestedSkillSnapshot>[],
  bool suggestedSkillsMayHaveMore = false,
}) {
  return OpenCraySkillsSnapshot(
    installedSkills: const <OpenCrayInstalledSkillSnapshot>[],
    installSources: const <OpenCraySkillInstallSourceSnapshot>[
      OpenCraySkillInstallSourceSnapshot(
        id: 'github-url',
        title: 'GitHub URL',
        subtitle: 'Enter a source ref in search.',
        ctaLabel: 'Use search',
        isAvailable: true,
      ),
    ],
    suggestedSkills: suggestedSkills,
    suggestedSkillsMayHaveMore: suggestedSkillsMayHaveMore,
  );
}

List<OpenCraySuggestedSkillSnapshot> _buildSuggestedSkills(int count) {
  return List<OpenCraySuggestedSkillSnapshot>.generate(count, (index) {
    final skillNumber = index + 1;
    return OpenCraySuggestedSkillSnapshot(
      id: 'find-skill-$skillNumber',
      name: 'find-skill-$skillNumber',
      description: 'Search result $skillNumber',
      sourceRef: 'acme/skills@find-skill-$skillNumber',
      sourceLabel: 'skills.sh',
      installs: 100 - skillNumber,
      detailUrl: 'https://skills.sh/acme/skills',
    );
  }, growable: false);
}
