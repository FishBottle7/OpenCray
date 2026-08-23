part of 'opencray_local_runtime_bridge.dart';

mixin _LocalRuntimeBridgeFilesDomain on _LocalRuntimeBridgeDeps {
  @override
  Future<OpenCrayFilesSnapshot> loadFilesSnapshot() async =>
      OpenCrayFilesSnapshot.fromMap(await _getMap('v1/files_snapshot'));

  @override
  Future<OpenCraySandboxPreviewEmbedConfig> resolveSandboxPreviewEmbedConfig(
    String previewUrl,
  ) async => OpenCraySandboxPreviewEmbedConfig.fromMap(
    await _postMap('v1/resolve_sandbox_preview_embed_config', <String, Object?>{
      'previewUrl': previewUrl,
    }),
  );

  @override
  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  ) async => OpenCrayFileImagePreview.fromMap(
    await _getMap(
      'v1/workspace_image_preview',
      queryParameters: <String, String>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async => OpenCrayFileTextPreview.fromMap(
    await _getMap(
      'v1/workspace_text_preview',
      queryParameters: <String, String>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  ) async => OpenCrayFileVoicePlaybackSource.fromMap(
    await _getMap(
      'v1/workspace_voice_playback_source',
      queryParameters: <String, String>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayWorkspaceTextDocument> loadWorkspaceTextDocument(
    String relativePath,
  ) async => OpenCrayWorkspaceTextDocument.fromMap(
    await _getMap(
      'v1/workspace_text_document',
      queryParameters: <String, String>{'relativePath': relativePath},
    ),
  );

  @override
  Future<void> openWorkspaceEntry(String relativePath) => _postVoid(
    'v1/open_workspace_entry',
    <String, Object?>{'relativePath': relativePath},
  );

  @override
  Future<void> openExternalUri(String uri) =>
      _postVoid('v1/open_external_uri', <String, Object?>{'uri': uri});

  @override
  Future<void> copyRichTextToClipboard({
    required String plainText,
    String? htmlText,
  }) => Clipboard.setData(ClipboardData(text: plainText));

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceFolder({
    required String parentRelativePath,
    required String name,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/create_workspace_folder', <String, Object?>{
      'parentRelativePath': parentRelativePath,
      'name': name,
    }),
  );

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceTextFile({
    required String parentRelativePath,
    required String name,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/create_workspace_text_file', <String, Object?>{
      'parentRelativePath': parentRelativePath,
      'name': name,
    }),
  );

  @override
  Future<OpenCrayFilesSnapshot> renameWorkspaceEntry({
    required String targetRelativePath,
    required String newName,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/rename_workspace_entry', <String, Object?>{
      'targetRelativePath': targetRelativePath,
      'newName': newName,
    }),
  );

  @override
  Future<OpenCrayFilesSnapshot> deleteWorkspaceEntries(
    List<String> relativePaths,
  ) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/delete_workspace_entries', <String, Object?>{
      'relativePaths': relativePaths,
    }),
  );

  @override
  Future<OpenCrayFilesSnapshot> saveWorkspaceTextDocument({
    required String targetRelativePath,
    required String content,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/save_workspace_text_document', <String, Object?>{
      'targetRelativePath': targetRelativePath,
      'content': content,
    }),
  );

  @override
  Future<OpenCrayFilesSnapshot> pasteWorkspaceEntries({
    required List<String> sourceRelativePaths,
    required String destinationRelativePath,
    required bool move,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _postMap('v1/paste_workspace_entries', <String, Object?>{
      'sourceRelativePaths': sourceRelativePaths,
      'destinationRelativePath': destinationRelativePath,
      'move': move,
    }),
  );

  @override
  Future<void> shareWorkspaceEntries(List<String> relativePaths) => _postVoid(
    'v1/share_workspace_entries',
    <String, Object?>{'relativePaths': relativePaths},
  );

  @override
  Future<OpenCraySavedWorkspaceMediaAttachment> saveWorkspaceMediaAttachment({
    required String relativePath,
    required String kind,
  }) async => OpenCraySavedWorkspaceMediaAttachment.fromMap(
    await _postMap('v1/save_workspace_media_attachment', <String, Object?>{
      'relativePath': relativePath,
      'kind': kind,
    }),
  );

  @override
  Future<void> showNativeToast(String message) async {}

  @override
  Future<List<OpenCraySettingsImageAsset>> listSettingsImageAssets() async =>
      (await _getJson('v1/settings_image_assets') as List<Object?>? ??
              const <Object?>[])
          .map(_requireMap)
          .map(OpenCraySettingsImageAsset.fromMap)
          .toList(growable: false);

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> importSettingsImageAssets(
    List<String> uriStrings,
  ) async =>
      (await _requestJson(
                    'POST',
                    'v1/import_settings_image_assets',
                    body: <String, Object?>{'uriStrings': uriStrings},
                  )
                  as List<Object?>? ??
              const <Object?>[])
          .map(_requireMap)
          .map(OpenCraySettingsImageAsset.fromMap)
          .toList(growable: false);
}
