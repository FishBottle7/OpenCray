class OpenCrayFileTextPreview {
  const OpenCrayFileTextPreview({
    required this.name,
    required this.relativePath,
    required this.content,
    required this.isTruncated,
  });

  final String name;
  final String relativePath;
  final String content;
  final bool isTruncated;

  factory OpenCrayFileTextPreview.fromMap(Map<Object?, Object?> payload) {
    return OpenCrayFileTextPreview(
      name: payload['name'] as String? ?? '',
      relativePath: payload['relativePath'] as String? ?? '',
      content: payload['content'] as String? ?? '',
      isTruncated: payload['isTruncated'] as bool? ?? false,
    );
  }
}
