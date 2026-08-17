part of 'opencray_seed_bridge.dart';

OpenCrayFilesSnapshot _buildSeedFilesSnapshot() {
  return const OpenCrayFilesSnapshot(
    rootName: 'agent-workspace',
    rootPath: '/seed/agent-workspace',
    availableBytes: 4100000000,
    directoryCount: 3,
    fileCount: 4,
    entryCount: 7,
    isTruncated: false,
    children: <OpenCrayFileTreeNodeSnapshot>[
      OpenCrayFileTreeNodeSnapshot(
        name: 'app',
        relativePath: 'app',
        isDirectory: true,
        childCount: 2,
        sizeBytes: null,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[
          OpenCrayFileTreeNodeSnapshot(
            name: 'shell',
            relativePath: 'app/shell',
            isDirectory: true,
            childCount: 1,
            sizeBytes: null,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[
              OpenCrayFileTreeNodeSnapshot(
                name: 'opencray_shell.dart',
                relativePath: 'app/shell/opencray_shell.dart',
                isDirectory: false,
                childCount: 0,
                sizeBytes: 18432,
                isTruncated: false,
                children: <OpenCrayFileTreeNodeSnapshot>[],
              ),
            ],
          ),
          OpenCrayFileTreeNodeSnapshot(
            name: 'README.md',
            relativePath: 'app/README.md',
            isDirectory: false,
            childCount: 0,
            sizeBytes: 4096,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[],
          ),
        ],
      ),
      OpenCrayFileTreeNodeSnapshot(
        name: 'docs',
        relativePath: 'docs',
        isDirectory: true,
        childCount: 1,
        sizeBytes: null,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[
          OpenCrayFileTreeNodeSnapshot(
            name: 'mobile-ui-layout-spec.md',
            relativePath: 'docs/mobile-ui-layout-spec.md',
            isDirectory: false,
            childCount: 0,
            sizeBytes: 12288,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[],
          ),
        ],
      ),
      OpenCrayFileTreeNodeSnapshot(
        name: 'workspace-notes.txt',
        relativePath: 'workspace-notes.txt',
        isDirectory: false,
        childCount: 0,
        sizeBytes: 1536,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[],
      ),
    ],
  );
}

OpenCrayFilesSnapshot _seedCreateWorkspaceFolder(
  OpenCrayFilesSnapshot snapshot, {
  required String parentRelativePath,
  required String name,
}) {
  final normalizedName = _validateSeedEntryName(name);
  final updatedChildren = _updateSeedDirectoryChildren(
    snapshot.children,
    parentRelativePath.trim(),
    (children) {
      if (children.any((child) => child.name == normalizedName)) {
        throw StateError("An item named '$normalizedName' already exists.");
      }
      return _sortSeedNodes(<OpenCrayFileTreeNodeSnapshot>[
        ...children,
        OpenCrayFileTreeNodeSnapshot(
          name: normalizedName,
          relativePath: _joinSeedPath(
            parentRelativePath.trim(),
            normalizedName,
          ),
          isDirectory: true,
          childCount: 0,
          sizeBytes: null,
          isTruncated: false,
          children: const <OpenCrayFileTreeNodeSnapshot>[],
        ),
      ]);
    },
  );
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

OpenCrayFilesSnapshot _seedCreateWorkspaceTextFile(
  OpenCrayFilesSnapshot snapshot, {
  required String parentRelativePath,
  required String name,
}) {
  final normalizedName = _validateSeedEntryName(name);
  if (!_supportsSeedTextPreview(normalizedName)) {
    throw StateError('Only supported text files can be created here.');
  }
  final updatedChildren = _updateSeedDirectoryChildren(
    snapshot.children,
    parentRelativePath.trim(),
    (children) {
      if (children.any((child) => child.name == normalizedName)) {
        throw StateError("An item named '$normalizedName' already exists.");
      }
      return _sortSeedNodes(<OpenCrayFileTreeNodeSnapshot>[
        ...children,
        OpenCrayFileTreeNodeSnapshot(
          name: normalizedName,
          relativePath: _joinSeedPath(
            parentRelativePath.trim(),
            normalizedName,
          ),
          isDirectory: false,
          childCount: 0,
          sizeBytes: 0,
          isTruncated: false,
          children: const <OpenCrayFileTreeNodeSnapshot>[],
        ),
      ]);
    },
  );
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

OpenCrayFilesSnapshot _seedRenameWorkspaceEntry(
  OpenCrayFilesSnapshot snapshot, {
  required String targetRelativePath,
  required String newName,
}) {
  final normalizedTarget = targetRelativePath.trim();
  final normalizedName = _validateSeedEntryName(newName);
  final parentPath = _seedParentPath(normalizedTarget);
  final updatedChildren = _updateSeedDirectoryChildren(
    snapshot.children,
    parentPath,
    (children) {
      final target = children.where(
        (child) => child.relativePath == normalizedTarget,
      );
      if (target.isEmpty) {
        throw StateError('The selected item no longer exists.');
      }
      if (children.any(
        (child) =>
            child.relativePath != normalizedTarget &&
            child.name == normalizedName,
      )) {
        throw StateError("An item named '$normalizedName' already exists.");
      }
      return _sortSeedNodes(
        children
            .map((child) {
              if (child.relativePath != normalizedTarget) {
                return child;
              }
              return _rebaseSeedNode(
                child,
                newRelativePath: _joinSeedPath(parentPath, normalizedName),
                newName: normalizedName,
              );
            })
            .toList(growable: false),
      );
    },
  );
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

OpenCrayFilesSnapshot _seedDeleteWorkspaceEntries(
  OpenCrayFilesSnapshot snapshot,
  List<String> relativePaths,
) {
  final targets = relativePaths
      .map((path) => path.trim())
      .where((path) => path.isNotEmpty)
      .toSet();
  if (targets.isEmpty) {
    return snapshot;
  }
  final updatedChildren = _removeSeedNodes(snapshot.children, targets);
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

OpenCrayFilesSnapshot _seedPasteWorkspaceEntries(
  OpenCrayFilesSnapshot snapshot, {
  required List<String> sourceRelativePaths,
  required String destinationRelativePath,
  required bool move,
}) {
  final destinationPath = destinationRelativePath.trim();
  final sourcePaths = sourceRelativePaths
      .map((path) => path.trim())
      .where((path) => path.isNotEmpty)
      .toList(growable: false);
  if (sourcePaths.isEmpty) {
    throw StateError('Nothing is selected to paste.');
  }
  final sourceNodes = sourcePaths
      .map((path) => _findSeedNode(snapshot.children, path))
      .toList(growable: false);
  if (sourceNodes.any((node) => node == null)) {
    throw StateError('One or more selected items no longer exist.');
  }
  final resolvedSources = sourceNodes
      .whereType<OpenCrayFileTreeNodeSnapshot>()
      .toList(growable: false);
  for (final source in resolvedSources) {
    if (source.isDirectory &&
        (destinationPath == source.relativePath ||
            destinationPath.startsWith('${source.relativePath}/'))) {
      throw StateError('A folder cannot be moved into itself.');
    }
  }

  final activeSources = <OpenCrayFileTreeNodeSnapshot>[
    for (final source in resolvedSources)
      if (!(move && _seedParentPath(source.relativePath) == destinationPath))
        source,
  ];
  if (activeSources.isEmpty) {
    return snapshot;
  }

  final duplicateNames = <String>{};
  for (final source in activeSources) {
    if (!duplicateNames.add(source.name)) {
      throw StateError("An item named '${source.name}' already exists here.");
    }
  }

  final treeAfterMoveRemoval = move
      ? _removeSeedNodes(
          snapshot.children,
          activeSources.map((source) => source.relativePath).toSet(),
        )
      : snapshot.children;

  final updatedChildren = _updateSeedDirectoryChildren(
    treeAfterMoveRemoval,
    destinationPath,
    (children) {
      for (final source in activeSources) {
        if (children.any((child) => child.name == source.name)) {
          throw StateError(
            "An item named '${source.name}' already exists here.",
          );
        }
      }
      return _sortSeedNodes(<OpenCrayFileTreeNodeSnapshot>[
        ...children,
        for (final source in activeSources)
          _rebaseSeedNode(
            source,
            newRelativePath: _joinSeedPath(destinationPath, source.name),
          ),
      ]);
    },
  );
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

OpenCrayFilesSnapshot _rebuildSeedSnapshot(
  OpenCrayFilesSnapshot snapshot,
  List<OpenCrayFileTreeNodeSnapshot> children,
) {
  final counts = _countSeedChildren(children);
  return OpenCrayFilesSnapshot(
    rootName: snapshot.rootName,
    rootPath: snapshot.rootPath,
    availableBytes: snapshot.availableBytes,
    directoryCount: counts.$1,
    fileCount: counts.$2,
    entryCount: counts.$1 + counts.$2,
    isTruncated: snapshot.isTruncated,
    children: children,
  );
}

OpenCrayFilesSnapshot _seedUpdateWorkspaceFileSize(
  OpenCrayFilesSnapshot snapshot, {
  required String targetRelativePath,
  required int sizeBytes,
}) {
  final updatedChildren = _replaceSeedNode(
    snapshot.children,
    targetRelativePath.trim(),
    (node) => OpenCrayFileTreeNodeSnapshot(
      name: node.name,
      relativePath: node.relativePath,
      isDirectory: node.isDirectory,
      childCount: node.childCount,
      sizeBytes: sizeBytes,
      isTruncated: node.isTruncated,
      children: node.children,
    ),
  );
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

List<OpenCrayFileTreeNodeSnapshot> _updateSeedDirectoryChildren(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
  String directoryPath,
  List<OpenCrayFileTreeNodeSnapshot> Function(
    List<OpenCrayFileTreeNodeSnapshot> children,
  )
  transform,
) {
  final normalizedDirectory = directoryPath.trim();
  if (normalizedDirectory.isEmpty) {
    return transform(nodes);
  }
  var found = false;
  final updatedNodes = nodes
      .map((node) {
        if (node.relativePath == normalizedDirectory) {
          if (!node.isDirectory) {
            throw StateError('Destination directory is unavailable.');
          }
          found = true;
          final updatedChildren = transform(node.children);
          return _copySeedNode(node, children: updatedChildren);
        }
        if (!node.isDirectory) {
          return node;
        }
        final updatedChildren = _updateSeedDirectoryChildrenOrNull(
          node.children,
          normalizedDirectory,
          transform,
          onFound: () => found = true,
        );
        if (updatedChildren == null) {
          return node;
        }
        return _copySeedNode(node, children: updatedChildren);
      })
      .toList(growable: false);
  if (!found) {
    throw StateError('Destination directory is unavailable.');
  }
  return updatedNodes;
}

List<OpenCrayFileTreeNodeSnapshot>? _updateSeedDirectoryChildrenOrNull(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
  String directoryPath,
  List<OpenCrayFileTreeNodeSnapshot> Function(
    List<OpenCrayFileTreeNodeSnapshot> children,
  )
  transform, {
  required void Function() onFound,
}) {
  var changed = false;
  final updatedNodes = nodes
      .map((node) {
        if (node.relativePath == directoryPath) {
          if (!node.isDirectory) {
            throw StateError('Destination directory is unavailable.');
          }
          changed = true;
          onFound();
          return _copySeedNode(node, children: transform(node.children));
        }
        if (!node.isDirectory) {
          return node;
        }
        final updatedChildren = _updateSeedDirectoryChildrenOrNull(
          node.children,
          directoryPath,
          transform,
          onFound: onFound,
        );
        if (updatedChildren == null) {
          return node;
        }
        changed = true;
        return _copySeedNode(node, children: updatedChildren);
      })
      .toList(growable: false);
  return changed ? updatedNodes : null;
}

List<OpenCrayFileTreeNodeSnapshot> _removeSeedNodes(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
  Set<String> targetPaths,
) {
  return nodes
      .where((node) => !targetPaths.contains(node.relativePath))
      .map((node) {
        if (!node.isDirectory) {
          return node;
        }
        return _copySeedNode(
          node,
          children: _removeSeedNodes(node.children, targetPaths),
        );
      })
      .toList(growable: false);
}

List<OpenCrayFileTreeNodeSnapshot> _replaceSeedNode(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
  String targetPath,
  OpenCrayFileTreeNodeSnapshot Function(OpenCrayFileTreeNodeSnapshot node)
  transform,
) {
  return nodes
      .map((node) {
        if (node.relativePath == targetPath) {
          return transform(node);
        }
        if (!node.isDirectory) {
          return node;
        }
        final updatedChildren = _replaceSeedNode(
          node.children,
          targetPath,
          transform,
        );
        if (identical(updatedChildren, node.children)) {
          return node;
        }
        return _copySeedNode(node, children: updatedChildren);
      })
      .toList(growable: false);
}

OpenCrayFileTreeNodeSnapshot? _findSeedNode(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
  String targetPath,
) {
  for (final node in nodes) {
    if (node.relativePath == targetPath) {
      return node;
    }
    final nested = _findSeedNode(node.children, targetPath);
    if (nested != null) {
      return nested;
    }
  }
  return null;
}

List<String> _seedActiveTransferSourcePaths({
  required OpenCrayFilesSnapshot snapshot,
  required List<String> sourceRelativePaths,
  required String destinationRelativePath,
  required bool move,
}) {
  final sourceNodes = sourceRelativePaths
      .map((path) => _findSeedNode(snapshot.children, path))
      .toList(growable: false);
  if (sourceNodes.any((node) => node == null)) {
    throw StateError('One or more selected items no longer exist.');
  }
  final resolvedSources = sourceNodes
      .whereType<OpenCrayFileTreeNodeSnapshot>()
      .toList(growable: false);
  return <String>[
    for (final source in resolvedSources)
      if (!(move &&
          _seedParentPath(source.relativePath) == destinationRelativePath))
        source.relativePath,
  ];
}

Map<String, String> _seedRenameTextDocuments(
  Map<String, String> documentsByPath, {
  required String fromRelativePath,
  required String toRelativePath,
}) {
  return <String, String>{
    for (final entry in documentsByPath.entries)
      _rebaseSeedDocumentPath(
        entry.key,
        fromRelativePath: fromRelativePath,
        toRelativePath: toRelativePath,
      ): entry.value,
  };
}

Map<String, String> _seedDeleteTextDocuments(
  Map<String, String> documentsByPath,
  Set<String> targetPaths,
) {
  return <String, String>{
    for (final entry in documentsByPath.entries)
      if (!_matchesSeedPathPrefix(entry.key, targetPaths))
        entry.key: entry.value,
  };
}

Map<String, String> _seedPasteTextDocuments(
  Map<String, String> documentsByPath, {
  required List<String> sourceRelativePaths,
  required String destinationRelativePath,
  required bool move,
}) {
  final updated = move
      ? _seedDeleteTextDocuments(documentsByPath, sourceRelativePaths.toSet())
      : Map<String, String>.from(documentsByPath);
  for (final sourceRelativePath in sourceRelativePaths) {
    final destinationPath = _joinSeedPath(
      destinationRelativePath,
      _seedBaseName(sourceRelativePath),
    );
    for (final entry in documentsByPath.entries) {
      if (!_matchesSeedPathPrefix(entry.key, <String>{sourceRelativePath})) {
        continue;
      }
      updated[_rebaseSeedDocumentPath(
            entry.key,
            fromRelativePath: sourceRelativePath,
            toRelativePath: destinationPath,
          )] =
          entry.value;
    }
  }
  return updated;
}

String _rebaseSeedDocumentPath(
  String path, {
  required String fromRelativePath,
  required String toRelativePath,
}) {
  if (path == fromRelativePath) {
    return toRelativePath;
  }
  if (path.startsWith('$fromRelativePath/')) {
    return '$toRelativePath/${path.substring(fromRelativePath.length + 1)}';
  }
  return path;
}

bool _matchesSeedPathPrefix(String path, Set<String> prefixes) {
  for (final prefix in prefixes) {
    if (path == prefix || path.startsWith('$prefix/')) {
      return true;
    }
  }
  return false;
}

String _seedBaseName(String relativePath) {
  final normalized = relativePath.trim();
  if (normalized.isEmpty || !normalized.contains('/')) {
    return normalized;
  }
  return normalized.substring(normalized.lastIndexOf('/') + 1);
}

OpenCrayFileTreeNodeSnapshot _rebaseSeedNode(
  OpenCrayFileTreeNodeSnapshot node, {
  required String newRelativePath,
  String? newName,
}) {
  final resolvedName = newName ?? node.name;
  return OpenCrayFileTreeNodeSnapshot(
    name: resolvedName,
    relativePath: newRelativePath,
    isDirectory: node.isDirectory,
    childCount: node.children.length,
    sizeBytes: node.sizeBytes,
    isTruncated: node.isTruncated,
    children: node.children
        .map(
          (child) => _rebaseSeedNode(
            child,
            newRelativePath: _joinSeedPath(newRelativePath, child.name),
          ),
        )
        .toList(growable: false),
  );
}

OpenCrayFileTreeNodeSnapshot _copySeedNode(
  OpenCrayFileTreeNodeSnapshot node, {
  required List<OpenCrayFileTreeNodeSnapshot> children,
}) {
  return OpenCrayFileTreeNodeSnapshot(
    name: node.name,
    relativePath: node.relativePath,
    isDirectory: node.isDirectory,
    childCount: children.length,
    sizeBytes: node.sizeBytes,
    isTruncated: node.isTruncated,
    children: children,
  );
}

List<OpenCrayFileTreeNodeSnapshot> _sortSeedNodes(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
) {
  final sorted = [...nodes];
  sorted.sort((left, right) {
    if (left.isDirectory != right.isDirectory) {
      return left.isDirectory ? -1 : 1;
    }
    return left.name.toLowerCase().compareTo(right.name.toLowerCase());
  });
  return sorted;
}

(int, int) _countSeedChildren(List<OpenCrayFileTreeNodeSnapshot> nodes) {
  var directoryCount = 0;
  var fileCount = 0;
  for (final node in nodes) {
    if (node.isDirectory) {
      directoryCount += 1;
    } else {
      fileCount += 1;
    }
    final nested = _countSeedChildren(node.children);
    directoryCount += nested.$1;
    fileCount += nested.$2;
  }
  return (directoryCount, fileCount);
}

String _seedParentPath(String relativePath) {
  final normalized = relativePath.trim();
  if (normalized.isEmpty || !normalized.contains('/')) {
    return '';
  }
  return normalized.substring(0, normalized.lastIndexOf('/'));
}

String _joinSeedPath(String parentRelativePath, String name) {
  final normalizedParent = parentRelativePath.trim();
  if (normalizedParent.isEmpty) {
    return name;
  }
  return '$normalizedParent/$name';
}

String _validateSeedEntryName(String rawName) {
  final normalized = rawName.trim();
  if (normalized.isEmpty) {
    throw StateError('A name is required.');
  }
  if (normalized == '.' || normalized == '..') {
    throw StateError('That name is not allowed.');
  }
  if (normalized.contains('/') || normalized.contains('\\')) {
    throw StateError('Names cannot contain path separators.');
  }
  return normalized;
}

bool _supportsSeedTextPreview(String name) {
  final normalizedName = name.trim().toLowerCase();
  if (_seedTextPreviewNames.contains(normalizedName)) {
    return true;
  }
  final extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  return _seedTextPreviewExtensions.contains(extension);
}

bool _supportsSeedImagePreview(String name) {
  final normalizedName = name.trim().toLowerCase();
  final extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  return _seedImagePreviewExtensions.contains(extension);
}

String _seedImagePreviewMimeTypeFor(String name) {
  final normalizedName = name.trim().toLowerCase();
  final extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  switch (extension) {
    case 'jpg':
    case 'jpeg':
      return 'image/jpeg';
    case 'gif':
      return 'image/gif';
    case 'webp':
      return 'image/webp';
    case 'bmp':
      return 'image/bmp';
    default:
      return 'image/png';
  }
}

String _seedTextPreviewContentFor({
  required String relativePath,
  required String name,
  required Map<String, String> documentsByPath,
}) {
  return documentsByPath[relativePath] ??
      _seedPreviewContentByPath[relativePath] ??
      'Preview for $relativePath\n\n'
          'This is seeded text content generated by the local preview bridge.\n';
}

const Set<String> _seedTextPreviewNames = <String>{
  '.env',
  '.gitignore',
  '.gitattributes',
  'makefile',
  'readme',
  'readme.md',
  'license',
  'gradlew',
  'gradlew.bat',
};

const Set<String> _seedTextPreviewExtensions = <String>{
  'txt',
  'md',
  'markdown',
  'json',
  'yaml',
  'yml',
  'xml',
  'csv',
  'log',
  'ini',
  'conf',
  'config',
  'properties',
  'toml',
  'dart',
  'kt',
  'kts',
  'java',
  'js',
  'ts',
  'tsx',
  'jsx',
  'css',
  'scss',
  'html',
  'htm',
  'sh',
  'bash',
  'zsh',
  'py',
  'sql',
};

const Set<String> _seedImagePreviewExtensions = <String>{
  'png',
  'jpg',
  'jpeg',
  'webp',
  'gif',
  'bmp',
  'heic',
  'heif',
  'svg',
};

const String _seedImagePreviewBase64 =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wn1yt4AAAAASUVORK5CYII=';

const Map<String, String> _seedPreviewContentByPath = <String, String>{
  'workspace-notes.txt':
      'Workspace notes\n\n'
      '- Keep the Files surface grounded in the current directory.\n'
      '- Text files should open in a lightweight preview sheet.\n'
      '- Dangerous actions stay inside explicit edit states.\n',
  'app/README.md':
      '# OpenCray Shell\n\n'
      'The Flutter shell keeps Chat, Skills, Files, and Settings in one predictable frame.\n',
  'app/shell/opencray_shell.dart':
      'class OpenCrayShell {\n'
      '  const OpenCrayShell();\n'
      '}\n',
  'docs/mobile-ui-layout-spec.md':
      '# Mobile UI Layout Spec\n\n'
      'Files should feel compact, direct, and native on phone-sized screens.\n',
};

OpenCrayTab _parseTab(String raw) {
  final normalized = raw.trim().toLowerCase();
  for (final tab in OpenCrayTab.values) {
    if (tab.routeSegment.toLowerCase() == normalized) {
      return tab;
    }
  }
  return OpenCrayTab.chat;
}
