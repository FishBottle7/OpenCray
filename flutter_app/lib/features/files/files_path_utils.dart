part of 'files_feature.dart';

List<String> _pathSegments(String relativePath) {
  return relativePath
      .split('/')
      .map((segment) => segment.trim())
      .where((segment) => segment.isNotEmpty)
      .toList(growable: false);
}

String _parentPath(String relativePath) {
  final normalized = relativePath.trim();
  if (normalized.isEmpty || !normalized.contains('/')) {
    return '';
  }
  return normalized.substring(0, normalized.lastIndexOf('/'));
}

String _joinRelativePath(String parent, String name) {
  final normalizedParent = parent.trim();
  if (normalizedParent.isEmpty) {
    return name.trim();
  }
  return '$normalizedParent/${name.trim()}';
}

bool _supportsImagePreview(String name) {
  final normalizedName = name.trim().toLowerCase();
  final extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  return _imagePreviewExtensions.contains(extension);
}

bool _supportsTextDocumentName(String name) {
  final normalizedName = name.trim().toLowerCase();
  if (_textPreviewFileNames.contains(normalizedName)) {
    return true;
  }
  final extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  return _textPreviewExtensions.contains(extension);
}

String _formatBytes(int bytes) {
  if (bytes <= 0) {
    return '0 B';
  }
  const units = <String>['B', 'KB', 'MB', 'GB', 'TB'];
  var value = bytes.toDouble();
  var unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  final formatted = value >= 10 || unitIndex == 0
      ? value.toStringAsFixed(0)
      : value.toStringAsFixed(1);
  return '$formatted ${units[unitIndex]}';
}

const Set<String> _imagePreviewExtensions = <String>{
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

const Set<String> _textPreviewFileNames = <String>{
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

const Set<String> _textPreviewExtensions = <String>{
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

extension on List<String> {
  String? get lastOrNull => isEmpty ? null : last;
}
