part of 'opencray_local_runtime_bridge.dart';

mixin _LocalRuntimeBridgeSkillsDomain on _LocalRuntimeBridgeDeps {
  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
    int? suggestedLimit,
  }) async => OpenCraySkillsSnapshot.fromMap(
    await _getMap(
      'v1/skills_snapshot',
      queryParameters: () {
        final queryParameters = <String, String>{};
        if (query.trim().isNotEmpty) {
          queryParameters['query'] = query;
          if (suggestedLimit != null) {
            queryParameters['suggestedLimit'] = suggestedLimit.toString();
          }
        }
        return queryParameters.isEmpty ? null : queryParameters;
      }(),
    ),
  );

  @override
  Stream<OpenCraySkillsSnapshot> watchSkillsSnapshot() => _watchMap(
    () => _getMap('v1/skills_snapshot'),
    OpenCraySkillsSnapshot.fromMap,
  );

  @override
  Future<void> setSkillEnabled(String skillId, bool enabled) => _postVoid(
    'v1/set_skill_enabled',
    <String, Object?>{'skillId': skillId, 'enabled': enabled},
  );

  @override
  Future<String?> refreshSkills() => _postNullableString('v1/refresh_skills');

  @override
  Future<String?> checkInstalledSkillUpdates({String skillId = ''}) =>
      _getNullableString(
        'v1/check_installed_skill_updates',
        queryParameters: skillId.trim().isEmpty
            ? null
            : <String, String>{'skillId': skillId},
      );

  @override
  Future<String?> updateInstalledSkill(String skillId) => _postNullableString(
    'v1/update_installed_skill',
    <String, Object?>{'skillId': skillId},
  );

  @override
  Future<OpenCraySkillSourceInspectionSnapshot> inspectSkillSource(
    String sourceRef,
  ) async => OpenCraySkillSourceInspectionSnapshot.fromMap(
    await _postMap('v1/inspect_skill_source', <String, Object?>{
      'sourceRef': sourceRef,
    }),
  );

  @override
  Future<String?> installSkillSource(
    String sourceRef, {
    String selectedSkillName = '',
  }) => _postNullableString('v1/install_skill_source', <String, Object?>{
    'sourceRef': sourceRef,
    if (selectedSkillName.trim().isNotEmpty)
      'selectedSkillName': selectedSkillName,
  });

  @override
  Future<String?> installSkillSourceBatch(
    String sourceRef, {
    List<String> selectedSkillNames = const <String>[],
  }) => _postNullableString('v1/install_skill_source_batch', <String, Object?>{
    'sourceRef': sourceRef,
    if (selectedSkillNames.isNotEmpty) 'selectedSkillNames': selectedSkillNames,
  });

  @override
  Future<String?> installSuggestedSkill(String skillId) => _postNullableString(
    'v1/install_suggested_skill',
    <String, Object?>{'skillId': skillId},
  );

  @override
  Future<String?> deleteInstalledSkill(String skillId) => _postNullableString(
    'v1/delete_installed_skill',
    <String, Object?>{'skillId': skillId},
  );

  @override
  Future<OpenCraySkillInstructionsSnapshot?> loadSkillInstructions(
    String skillId,
  ) async {
    final payload = await _getJson(
      'v1/skill_instructions',
      queryParameters: <String, String>{'skillId': skillId},
    );
    if (payload == null) {
      return null;
    }
    return OpenCraySkillInstructionsSnapshot.fromMap(_requireMap(payload));
  }

  @override
  Future<OpenCraySkillInstructionsSnapshot?> loadSuggestedSkillInstructions(
    String sourceRef, {
    String selectedSkillName = '',
  }) async {
    final payload = await _getJson(
      'v1/suggested_skill_instructions',
      queryParameters: <String, String>{
        'sourceRef': sourceRef,
        if (selectedSkillName.trim().isNotEmpty)
          'selectedSkillName': selectedSkillName,
      },
    );
    if (payload == null) {
      return null;
    }
    return OpenCraySkillInstructionsSnapshot.fromMap(_requireMap(payload));
  }
}
