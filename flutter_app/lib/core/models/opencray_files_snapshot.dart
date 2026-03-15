class OpenCrayFilesSnapshot {
  const OpenCrayFilesSnapshot({
    required this.rootName,
    required this.rootPath,
    required this.availableBytes,
    required this.directoryCount,
    required this.fileCount,
    required this.entryCount,
    required this.isTruncated,
    required this.children,
  });

  final String rootName;
  final String rootPath;
  final int availableBytes;
  final int directoryCount;
  final int fileCount;
  final int entryCount;
  final bool isTruncated;
  final List<OpenCrayFileTreeNodeSnapshot> children;

  factory OpenCrayFilesSnapshot.fromMap(Map<Object?, Object?> payload) {
    return OpenCrayFilesSnapshot(
      rootName: payload['rootName'] as String? ?? '',
      rootPath: payload['rootPath'] as String? ?? '',
      availableBytes: _asInt(payload['availableBytes']),
      directoryCount: _asInt(payload['directoryCount']),
      fileCount: _asInt(payload['fileCount']),
      entryCount: _asInt(payload['entryCount']),
      isTruncated: payload['isTruncated'] as bool? ?? false,
      children: _requireList(payload['children'])
          .map(_requireMap)
          .map(OpenCrayFileTreeNodeSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayFileTreeNodeSnapshot {
  const OpenCrayFileTreeNodeSnapshot({
    required this.name,
    required this.relativePath,
    required this.isDirectory,
    required this.childCount,
    required this.sizeBytes,
    required this.isTruncated,
    required this.children,
  });

  final String name;
  final String relativePath;
  final bool isDirectory;
  final int childCount;
  final int? sizeBytes;
  final bool isTruncated;
  final List<OpenCrayFileTreeNodeSnapshot> children;

  factory OpenCrayFileTreeNodeSnapshot.fromMap(Map<Object?, Object?> payload) {
    return OpenCrayFileTreeNodeSnapshot(
      name: payload['name'] as String? ?? '',
      relativePath: payload['relativePath'] as String? ?? '',
      isDirectory: payload['isDirectory'] as bool? ?? false,
      childCount: _asInt(payload['childCount']),
      sizeBytes: _asNullableInt(payload['sizeBytes']),
      isTruncated: payload['isTruncated'] as bool? ?? false,
      children: _requireList(payload['children'])
          .map(_requireMap)
          .map(OpenCrayFileTreeNodeSnapshot.fromMap)
          .toList(growable: false),
    );
  }

  OpenCrayFileTreeNodeSnapshot copyWith({
    List<OpenCrayFileTreeNodeSnapshot>? children,
  }) {
    return OpenCrayFileTreeNodeSnapshot(
      name: name,
      relativePath: relativePath,
      isDirectory: isDirectory,
      childCount: childCount,
      sizeBytes: sizeBytes,
      isTruncated: isTruncated,
      children: children ?? this.children,
    );
  }
}

List<Object?> _requireList(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <Object?>[];
  }
  return list;
}

Map<Object?, Object?> _requireMap(Object? payload) {
  final map = payload as Map<Object?, Object?>?;
  if (map == null) {
    throw const FormatException('Expected a map payload from host bridge.');
  }
  return map;
}

int _asInt(Object? value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  return 0;
}

int? _asNullableInt(Object? value) {
  if (value == null) {
    return null;
  }
  return _asInt(value);
}
