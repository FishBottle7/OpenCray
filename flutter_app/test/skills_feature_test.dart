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
    await tester.pumpAndSettle();

    final descriptionText = tester.widget<Text>(find.text(description));
    expect(descriptionText.maxLines, 2);
    expect(descriptionText.overflow, TextOverflow.ellipsis);
  });
}
