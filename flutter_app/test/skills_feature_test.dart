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
            id: 'git-repository',
            title: 'Git repository',
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
            id: 'git-repository',
            title: 'Git repository',
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

    await tester.tap(find.text('Install').first);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(
      bridge.lastInstalledSourceRef,
      'gitlab:acme/platform/skills@find-skills',
    );
  });
}

class _RecordingSkillsBridge extends OpenCraySeedBridge {
  _RecordingSkillsBridge({
    required super.initialSkillsSnapshot,
    this.searchedSnapshot,
  });

  final OpenCraySkillsSnapshot? searchedSnapshot;
  String? lastQuery;
  String? lastInstalledSourceRef;

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({String query = ''}) async {
    lastQuery = query;
    if (query.trim().isNotEmpty && searchedSnapshot != null) {
      return searchedSnapshot!;
    }
    return super.loadSkillsSnapshot(query: query);
  }

  @override
  Future<String?> installSkillSource(String sourceRef) async {
    lastInstalledSourceRef = sourceRef;
    return 'Installed $sourceRef';
  }
}
