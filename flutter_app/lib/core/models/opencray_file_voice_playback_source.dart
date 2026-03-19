class OpenCrayFileVoicePlaybackSource {
  const OpenCrayFileVoicePlaybackSource({
    required this.name,
    required this.relativePath,
    required this.localFilePath,
    this.mimeType,
    this.sizeBytes,
    this.durationMs,
  });

  final String name;
  final String relativePath;
  final String localFilePath;
  final String? mimeType;
  final int? sizeBytes;
  final int? durationMs;

  factory OpenCrayFileVoicePlaybackSource.fromMap(
    Map<Object?, Object?> payload,
  ) {
    int? parseInt(Object? value) => switch (value) {
      int intValue => intValue,
      num numValue => numValue.toInt(),
      _ => int.tryParse(value?.toString() ?? ''),
    };

    return OpenCrayFileVoicePlaybackSource(
      name: payload['name'] as String? ?? '',
      relativePath: payload['relativePath'] as String? ?? '',
      localFilePath: payload['localFilePath'] as String? ?? '',
      mimeType: payload['mimeType'] as String?,
      sizeBytes: parseInt(payload['sizeBytes']),
      durationMs: parseInt(payload['durationMs']),
    );
  }
}
