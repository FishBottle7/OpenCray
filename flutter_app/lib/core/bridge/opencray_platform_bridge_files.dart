part of 'opencray_platform_bridge.dart';

mixin _PlatformBridgeFilesDomain on _PlatformBridgeDeps {
  @override
  Future<OpenCrayFilesSnapshot> loadFilesSnapshot() async =>
      OpenCrayFilesSnapshot.fromMap(await _invokeMap('loadFilesSnapshot'));

  @override
  Future<OpenCraySandboxPreviewEmbedConfig> resolveSandboxPreviewEmbedConfig(
    String previewUrl,
  ) async => OpenCraySandboxPreviewEmbedConfig.fromMap(
    await _invokeMap(
      'resolveSandboxPreviewEmbedConfig',
      arguments: <String, Object?>{'previewUrl': previewUrl},
    ),
  );

  @override
  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  ) async => OpenCrayFileImagePreview.fromMap(
    await _invokeMap(
      'loadWorkspaceImagePreview',
      arguments: <String, Object?>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async => OpenCrayFileTextPreview.fromMap(
    await _invokeMap(
      'loadWorkspaceTextPreview',
      arguments: <String, Object?>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  ) async => OpenCrayFileVoicePlaybackSource.fromMap(
    await _invokeMap(
      'loadWorkspaceVoicePlaybackSource',
      arguments: <String, Object?>{'relativePath': relativePath},
    ),
  );

  @override
  Future<OpenCrayWorkspaceTextDocument> loadWorkspaceTextDocument(
    String relativePath,
  ) async => OpenCrayWorkspaceTextDocument.fromMap(
    await _invokeMap(
      'loadWorkspaceTextDocument',
      arguments: <String, Object?>{'relativePath': relativePath},
    ),
  );

  @override
  Future<void> openWorkspaceEntry(String relativePath) =>
      _methodChannel.invokeMethod<void>('openWorkspaceEntry', <String, Object?>{
        'relativePath': relativePath,
      });

  @override
  Future<void> openExternalUri(String uri) => _methodChannel.invokeMethod<void>(
    'openExternalUri',
    <String, Object?>{'uri': uri},
  );

  @override
  Future<void> copyRichTextToClipboard({
    required String plainText,
    String? htmlText,
  }) => _methodChannel.invokeMethod<void>(
    'copyRichTextToClipboard',
    <String, Object?>{'plainText': plainText, 'htmlText': htmlText},
  );

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceFolder({
    required String parentRelativePath,
    required String name,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'createWorkspaceFolder',
      arguments: <String, Object?>{
        'parentRelativePath': parentRelativePath,
        'name': name,
      },
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceTextFile({
    required String parentRelativePath,
    required String name,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'createWorkspaceTextFile',
      arguments: <String, Object?>{
        'parentRelativePath': parentRelativePath,
        'name': name,
      },
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> renameWorkspaceEntry({
    required String targetRelativePath,
    required String newName,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'renameWorkspaceEntry',
      arguments: <String, Object?>{
        'targetRelativePath': targetRelativePath,
        'newName': newName,
      },
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> deleteWorkspaceEntries(
    List<String> relativePaths,
  ) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'deleteWorkspaceEntries',
      arguments: <String, Object?>{'relativePaths': relativePaths},
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> saveWorkspaceTextDocument({
    required String targetRelativePath,
    required String content,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'saveWorkspaceTextDocument',
      arguments: <String, Object?>{
        'targetRelativePath': targetRelativePath,
        'content': content,
      },
    ),
  );

  @override
  Future<OpenCrayFilesSnapshot> pasteWorkspaceEntries({
    required List<String> sourceRelativePaths,
    required String destinationRelativePath,
    required bool move,
  }) async => OpenCrayFilesSnapshot.fromMap(
    await _invokeMap(
      'pasteWorkspaceEntries',
      arguments: <String, Object?>{
        'sourceRelativePaths': sourceRelativePaths,
        'destinationRelativePath': destinationRelativePath,
        'move': move,
      },
    ),
  );

  @override
  Future<void> shareWorkspaceEntries(List<String> relativePaths) =>
      _methodChannel.invokeMethod<void>(
        'shareWorkspaceEntries',
        <String, Object?>{'relativePaths': relativePaths},
      );

  @override
  Future<OpenCraySavedWorkspaceMediaAttachment> saveWorkspaceMediaAttachment({
    required String relativePath,
    required String kind,
  }) async => OpenCraySavedWorkspaceMediaAttachment.fromMap(
    await _invokeMap(
      'saveWorkspaceMediaAttachment',
      arguments: <String, Object?>{'relativePath': relativePath, 'kind': kind},
    ),
  );

  @override
  Future<void> showNativeToast(String message) =>
      _methodChannel.invokeMethod<void>('showNativeToast', <String, Object?>{
        'message': message,
      });

  @override
  Future<List<OpenCraySettingsImageAsset>> listSettingsImageAssets() async =>
      (await _invokeList('listSettingsImageAssets'))
          .map(_requireMap)
          .map(OpenCraySettingsImageAsset.fromMap)
          .toList(growable: false);

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async =>
      (await _invokeList('pickSettingsImageAssets'))
          .map(_requireMap)
          .map(OpenCraySettingsImageAsset.fromMap)
          .toList(growable: false);

  @override
  Future<List<OpenCraySettingsImageAsset>> importSettingsImageAssets(
    List<String> uriStrings,
  ) async =>
      (await _invokeList(
            'importSettingsImageAssets',
            arguments: <String, Object?>{'uriStrings': uriStrings},
          ))
          .map(_requireMap)
          .map(OpenCraySettingsImageAsset.fromMap)
          .toList(growable: false);
}
