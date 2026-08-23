part of 'opencray_platform_bridge.dart';

mixin _PlatformBridgePersonalizationDomain on _PlatformBridgeDeps {
  @override
  Future<OpenCrayPersonalizationConfigSnapshot>
  loadPersonalizationConfig() async =>
      OpenCrayPersonalizationConfigSnapshot.fromMap(
        await _invokeMap('loadPersonalizationConfig'),
      );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _invokeMap(
      'savePersonalizationConfig',
      arguments: <String, Object?>{
        'presetId': presetId,
        'customLabel': customLabel,
        'customGuidance': customGuidance,
      },
    ),
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _invokeMap(
      'setAppLanguage',
      arguments: <String, Object?>{'languageId': languageId},
    ),
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _invokeMap(
      'runPersonalizationReset',
      arguments: <String, Object?>{'scopeId': scopeId},
    ),
  );

  @override
  Future<OpenCrayTwinImportSourceProbeSnapshot> probeTwinImportSource(
    String filePath,
  ) async => OpenCrayTwinImportSourceProbeSnapshot.fromMap(
    await _invokeMap(
      'probeTwinImportSource',
      arguments: <String, Object?>{'filePath': filePath},
    ),
  );
}
