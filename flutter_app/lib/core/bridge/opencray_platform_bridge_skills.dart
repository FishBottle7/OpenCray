part of 'opencray_platform_bridge.dart';

mixin _PlatformBridgeSkillsDomain on _PlatformBridgeDeps {
  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
    int? suggestedLimit,
  }) async => OpenCraySkillsSnapshot.fromMap(
    await _invokeMap(
      'loadSkillsSnapshot',
      arguments: () {
        final arguments = <String, Object?>{};
        if (query.trim().isNotEmpty) {
          arguments['query'] = query;
          if (suggestedLimit != null) {
            arguments['suggestedLimit'] = suggestedLimit;
          }
        }
        return arguments.isEmpty ? null : arguments;
      }(),
    ),
  );

  @override
  Stream<OpenCraySkillsSnapshot> watchSkillsSnapshot() => _skillsSnapshotChannel
      .receiveBroadcastStream()
      .map(_requireMap)
      .map(OpenCraySkillsSnapshot.fromMap);

  @override
  Future<void> setSkillEnabled(String skillId, bool enabled) =>
      _methodChannel.invokeMethod<void>('setSkillEnabled', <String, Object?>{
        'skillId': skillId,
        'enabled': enabled,
      });

  @override
  Future<String?> refreshSkills() async =>
      await _methodChannel.invokeMethod<String>('refreshSkills');

  @override
  Future<String?> checkInstalledSkillUpdates({String skillId = ''}) async =>
      await _methodChannel.invokeMethod<String>(
        'checkInstalledSkillUpdates',
        skillId.trim().isEmpty ? null : <String, Object?>{'skillId': skillId},
      );

  @override
  Future<String?> updateInstalledSkill(String skillId) async =>
      await _methodChannel.invokeMethod<String>(
        'updateInstalledSkill',
        <String, Object?>{'skillId': skillId},
      );

  @override
  Future<OpenCraySkillSourceInspectionSnapshot> inspectSkillSource(
    String sourceRef,
  ) async => OpenCraySkillSourceInspectionSnapshot.fromMap(
    await _invokeMap(
      'inspectSkillSource',
      arguments: <String, Object?>{'sourceRef': sourceRef},
    ),
  );

  @override
  Future<String?> installSkillSource(
    String sourceRef, {
    String selectedSkillName = '',
  }) async => await _methodChannel
      .invokeMethod<String>('installSkillSource', <String, Object?>{
        'sourceRef': sourceRef,
        if (selectedSkillName.trim().isNotEmpty)
          'selectedSkillName': selectedSkillName,
      });

  @override
  Future<String?> installSkillSourceBatch(
    String sourceRef, {
    List<String> selectedSkillNames = const <String>[],
  }) async => await _methodChannel
      .invokeMethod<String>('installSkillSourceBatch', <String, Object?>{
        'sourceRef': sourceRef,
        if (selectedSkillNames.isNotEmpty)
          'selectedSkillNames': selectedSkillNames,
      });

  @override
  Future<String?> installSuggestedSkill(String skillId) async =>
      await _methodChannel.invokeMethod<String>(
        'installSuggestedSkill',
        <String, Object?>{'skillId': skillId},
      );

  @override
  Future<String?> deleteInstalledSkill(String skillId) async =>
      await _methodChannel.invokeMethod<String>(
        'deleteInstalledSkill',
        <String, Object?>{'skillId': skillId},
      );

  @override
  Future<OpenCraySkillInstructionsSnapshot?> loadSkillInstructions(
    String skillId,
  ) async {
    final payload = await _methodChannel.invokeMethod<Object?>(
      'loadSkillInstructions',
      <String, Object?>{'skillId': skillId},
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
    final payload = await _methodChannel.invokeMethod<Object?>(
      'loadSuggestedSkillInstructions',
      <String, Object?>{
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
