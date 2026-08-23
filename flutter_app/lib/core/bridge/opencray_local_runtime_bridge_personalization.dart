part of 'opencray_local_runtime_bridge.dart';

mixin _LocalRuntimeBridgePersonalizationDomain on _LocalRuntimeBridgeDeps {
  @override
  Future<OpenCrayPersonalizationConfigSnapshot>
  loadPersonalizationConfig() async =>
      OpenCrayPersonalizationConfigSnapshot.fromMap(
        await _getMap('v1/personalization_config'),
      );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _postMap('v1/save_personalization_config', <String, Object?>{
      'presetId': presetId,
      'customLabel': customLabel,
      'customGuidance': customGuidance,
    }),
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _postMap('v1/set_app_language', <String, Object?>{
      'languageId': languageId,
    }),
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async => OpenCrayPersonalizationConfigSnapshot.fromMap(
    await _postMap('v1/run_personalization_reset', <String, Object?>{
      'scopeId': scopeId,
    }),
  );

  @override
  Future<OpenCrayTwinImportSourceProbeSnapshot> probeTwinImportSource(
    String filePath,
  ) async => OpenCrayTwinImportSourceProbeSnapshot.fromMap(
    await _postMap('v1/probe_twin_import_source', <String, Object?>{
      'filePath': filePath,
    }),
  );
}
