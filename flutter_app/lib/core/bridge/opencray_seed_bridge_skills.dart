part of 'opencray_seed_bridge.dart';

mixin _SeedBridgeSkillsDomain on _SeedBridgeDeps {
  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
    int? suggestedLimit,
  }) async => _skillsSnapshot;

  @override
  Stream<OpenCraySkillsSnapshot> watchSkillsSnapshot() async* {
    yield _skillsSnapshot;
    yield* _skillsController.stream;
  }

  @override
  Future<void> setSkillEnabled(String skillId, bool enabled) async {
    _skillsSnapshot = OpenCraySkillsSnapshot(
      installedSkills: _skillsSnapshot.installedSkills
          .map(
            (skill) => skill.id == skillId
                ? OpenCrayInstalledSkillSnapshot(
                    id: skill.id,
                    name: skill.name,
                    description: skill.description,
                    isEnabled: enabled,
                    canDelete: skill.canDelete,
                    sourceDirectoryPath: skill.sourceDirectoryPath,
                  )
                : skill,
          )
          .toList(growable: false),
      installSources: _skillsSnapshot.installSources,
      suggestedSkills: _skillsSnapshot.suggestedSkills,
      suggestedSkillsMayHaveMore: _skillsSnapshot.suggestedSkillsMayHaveMore,
    );
    _emitSkillsSnapshot();
  }

  @override
  Future<String?> refreshSkills() async =>
      'Seed bridge refreshed local skills.';

  @override
  Future<String?> checkInstalledSkillUpdates({String skillId = ''}) async =>
      'Seed bridge does not support skill update checks.';

  @override
  Future<String?> updateInstalledSkill(String skillId) async =>
      'Seed bridge does not support updating installed skills.';

  @override
  Future<OpenCraySkillSourceInspectionSnapshot> inspectSkillSource(
    String sourceRef,
  ) async =>
      throw StateError('Seed bridge does not support skill source inspection.');

  @override
  Future<String?> installSkillSource(
    String sourceRef, {
    String selectedSkillName = '',
  }) async => 'Seed bridge does not support skill installation.';

  @override
  Future<String?> installSkillSourceBatch(
    String sourceRef, {
    List<String> selectedSkillNames = const <String>[],
  }) async => 'Seed bridge does not support skill installation.';

  @override
  Future<String?> installSuggestedSkill(String skillId) async =>
      'Seed bridge does not support skill installation.';

  @override
  Future<String?> deleteInstalledSkill(String skillId) async =>
      'Seed bridge does not support deleting installed skills.';

  @override
  Future<OpenCraySkillInstructionsSnapshot?> loadSkillInstructions(
    String skillId,
  ) async {
    final skill = _skillsSnapshot.installedSkills
        .cast<OpenCrayInstalledSkillSnapshot?>()
        .firstWhere(
          (candidate) => candidate?.id == skillId,
          orElse: () => null,
        );
    if (skill == null) {
      return null;
    }
    return OpenCraySkillInstructionsSnapshot(
      id: skill.id,
      name: skill.name,
      description: skill.description,
      markdownBody: 'Seed bridge preview is not backed by the Android host.',
      sourceDirectoryPath: skill.sourceDirectoryPath,
      isEnabled: skill.isEnabled,
      canDelete: skill.canDelete,
    );
  }

  @override
  Future<OpenCraySkillInstructionsSnapshot?> loadSuggestedSkillInstructions(
    String sourceRef, {
    String selectedSkillName = '',
  }) async {
    final suggestion = _skillsSnapshot.suggestedSkills
        .cast<OpenCraySuggestedSkillSnapshot?>()
        .firstWhere(
          (candidate) =>
              candidate?.sourceRef == sourceRef &&
              (selectedSkillName.trim().isEmpty ||
                  candidate?.name == selectedSkillName),
          orElse: () => null,
        );
    if (suggestion == null) {
      return null;
    }
    return OpenCraySkillInstructionsSnapshot(
      id: suggestion.id,
      name: suggestion.name,
      description: suggestion.description,
      markdownBody: 'Seed bridge preview is not backed by the Android host.',
      sourceDirectoryPath: suggestion.detailUrl.isNotEmpty
          ? suggestion.detailUrl
          : suggestion.sourceRef,
      isEnabled: false,
      canDelete: false,
    );
  }
}
