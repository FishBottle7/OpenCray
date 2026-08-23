part of 'opencray_seed_bridge.dart';

mixin _SeedBridgeFilesDomain on _SeedBridgeDeps {
  @override
  Future<OpenCrayFilesSnapshot> loadFilesSnapshot() async => _filesSnapshot;

  @override
  Future<OpenCraySandboxPreviewEmbedConfig> resolveSandboxPreviewEmbedConfig(
    String previewUrl,
  ) async => OpenCraySandboxPreviewEmbedConfig(
    previewUrl: previewUrl,
    providerId: '',
    headers: const <String, String>{},
    sessionMatched: false,
    accessTokenConfigured: false,
    unavailableReason:
        'Sandbox preview embedding is unavailable in the seed bridge.',
  );

  @override
  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  ) async {
    final normalizedPath = relativePath.trim();
    final node = _findSeedNode(_filesSnapshot.children, normalizedPath);
    if (node == null) {
      throw StateError('The selected file no longer exists.');
    }
    if (node.isDirectory) {
      throw StateError("Folders can't be previewed here.");
    }
    if (!_supportsSeedImagePreview(node.name)) {
      throw StateError('Image preview is available for image files only.');
    }
    return OpenCrayFileImagePreview(
      name: node.name,
      relativePath: node.relativePath,
      bytes: base64Decode(_seedImagePreviewBase64),
      mimeType: _seedImagePreviewMimeTypeFor(node.name),
      width: 1,
      height: 1,
    );
  }

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async {
    final normalizedPath = relativePath.trim();
    final node = _findSeedNode(_filesSnapshot.children, normalizedPath);
    if (node == null) {
      throw StateError('The selected file no longer exists.');
    }
    if (node.isDirectory) {
      throw StateError("Folders can't be previewed here.");
    }
    if (!_supportsSeedTextPreview(node.name)) {
      throw StateError('Text preview is available for text files only.');
    }
    return OpenCrayFileTextPreview(
      name: node.name,
      relativePath: node.relativePath,
      content: _seedTextPreviewContentFor(
        relativePath: node.relativePath,
        name: node.name,
        documentsByPath: _textDocumentsByPath,
      ),
      isTruncated: false,
    );
  }

  @override
  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  ) async {
    throw StateError('Seed bridge does not support voice playback.');
  }

  @override
  Future<OpenCrayWorkspaceTextDocument> loadWorkspaceTextDocument(
    String relativePath,
  ) async {
    final normalizedPath = relativePath.trim();
    final node = _findSeedNode(_filesSnapshot.children, normalizedPath);
    if (node == null) {
      throw StateError('The selected file no longer exists.');
    }
    if (node.isDirectory) {
      throw StateError("Folders can't be previewed here.");
    }
    if (!_supportsSeedTextPreview(node.name)) {
      throw StateError('Text editing is available for text files only.');
    }
    return OpenCrayWorkspaceTextDocument(
      name: node.name,
      relativePath: node.relativePath,
      content: _seedTextPreviewContentFor(
        relativePath: node.relativePath,
        name: node.name,
        documentsByPath: _textDocumentsByPath,
      ),
    );
  }

  @override
  Future<void> openWorkspaceEntry(String relativePath) async {
    throw StateError('Seed bridge does not support opening workspace files.');
  }

  @override
  Future<void> openExternalUri(String uri) async {
    throw StateError('Seed bridge does not support opening external links.');
  }

  @override
  Future<void> copyRichTextToClipboard({
    required String plainText,
    String? htmlText,
  }) async {
    _lastCopiedPlainText = plainText;
    _lastCopiedHtmlText = htmlText;
    await Clipboard.setData(ClipboardData(text: plainText));
  }

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceFolder({
    required String parentRelativePath,
    required String name,
  }) async {
    _filesSnapshot = _seedCreateWorkspaceFolder(
      _filesSnapshot,
      parentRelativePath: parentRelativePath,
      name: name,
    );
    return _filesSnapshot;
  }

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceTextFile({
    required String parentRelativePath,
    required String name,
  }) async {
    _filesSnapshot = _seedCreateWorkspaceTextFile(
      _filesSnapshot,
      parentRelativePath: parentRelativePath,
      name: name,
    );
    _textDocumentsByPath[_joinSeedPath(
          parentRelativePath.trim(),
          name.trim(),
        )] =
        '';
    return _filesSnapshot;
  }

  @override
  Future<OpenCrayFilesSnapshot> renameWorkspaceEntry({
    required String targetRelativePath,
    required String newName,
  }) async {
    final normalizedTarget = targetRelativePath.trim();
    final renamedPath = _joinSeedPath(
      _seedParentPath(normalizedTarget),
      newName.trim(),
    );
    _filesSnapshot = _seedRenameWorkspaceEntry(
      _filesSnapshot,
      targetRelativePath: normalizedTarget,
      newName: newName,
    );
    _textDocumentsByPath = _seedRenameTextDocuments(
      _textDocumentsByPath,
      fromRelativePath: normalizedTarget,
      toRelativePath: renamedPath,
    );
    return _filesSnapshot;
  }

  @override
  Future<OpenCrayFilesSnapshot> deleteWorkspaceEntries(
    List<String> relativePaths,
  ) async {
    final targets = relativePaths
        .map((path) => path.trim())
        .where((path) => path.isNotEmpty)
        .toSet();
    _filesSnapshot = _seedDeleteWorkspaceEntries(_filesSnapshot, relativePaths);
    _textDocumentsByPath = _seedDeleteTextDocuments(
      _textDocumentsByPath,
      targets,
    );
    return _filesSnapshot;
  }

  @override
  Future<OpenCrayFilesSnapshot> pasteWorkspaceEntries({
    required List<String> sourceRelativePaths,
    required String destinationRelativePath,
    required bool move,
  }) async {
    final normalizedDestination = destinationRelativePath.trim();
    final normalizedSources = sourceRelativePaths
        .map((path) => path.trim())
        .where((path) => path.isNotEmpty)
        .toList(growable: false);
    final activeSourcePaths = _seedActiveTransferSourcePaths(
      snapshot: _filesSnapshot,
      sourceRelativePaths: normalizedSources,
      destinationRelativePath: normalizedDestination,
      move: move,
    );
    _filesSnapshot = _seedPasteWorkspaceEntries(
      _filesSnapshot,
      sourceRelativePaths: normalizedSources,
      destinationRelativePath: normalizedDestination,
      move: move,
    );
    _textDocumentsByPath = _seedPasteTextDocuments(
      _textDocumentsByPath,
      sourceRelativePaths: activeSourcePaths,
      destinationRelativePath: normalizedDestination,
      move: move,
    );
    return _filesSnapshot;
  }

  @override
  Future<OpenCrayFilesSnapshot> saveWorkspaceTextDocument({
    required String targetRelativePath,
    required String content,
  }) async {
    final normalizedTarget = targetRelativePath.trim();
    final node = _findSeedNode(_filesSnapshot.children, normalizedTarget);
    if (node == null) {
      throw StateError('The selected file no longer exists.');
    }
    if (node.isDirectory) {
      throw StateError("Folders can't be edited here.");
    }
    if (!_supportsSeedTextPreview(node.name)) {
      throw StateError('Text editing is available for text files only.');
    }
    _textDocumentsByPath[normalizedTarget] = content;
    _filesSnapshot = _seedUpdateWorkspaceFileSize(
      _filesSnapshot,
      targetRelativePath: normalizedTarget,
      sizeBytes: utf8.encode(content).length,
    );
    return _filesSnapshot;
  }

  @override
  Future<void> shareWorkspaceEntries(List<String> relativePaths) async {
    throw StateError('Seed bridge does not support file sharing.');
  }

  @override
  Future<OpenCraySavedWorkspaceMediaAttachment> saveWorkspaceMediaAttachment({
    required String relativePath,
    required String kind,
  }) async {
    throw StateError('Seed bridge does not support media saving.');
  }

  @override
  Future<void> showNativeToast(String message) async {
    final normalized = message.trim();
    if (normalized.isEmpty) {
      return;
    }
    _shownNativeToasts.add(normalized);
  }

  @override
  Future<List<OpenCraySettingsImageAsset>> listSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> importSettingsImageAssets(
    List<String> uriStrings,
  ) async => const <OpenCraySettingsImageAsset>[];
}
