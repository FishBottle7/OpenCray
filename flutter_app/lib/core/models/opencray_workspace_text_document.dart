class OpenCrayWorkspaceTextDocument {
  const OpenCrayWorkspaceTextDocument({
    required this.name,
    required this.relativePath,
    required this.content,
  });

  final String name;
  final String relativePath;
  final String content;

  factory OpenCrayWorkspaceTextDocument.fromMap(Map<Object?, Object?> payload) {
    return OpenCrayWorkspaceTextDocument(
      name: payload['name'] as String? ?? '',
      relativePath: payload['relativePath'] as String? ?? '',
      content: payload['content'] as String? ?? '',
    );
  }
}
