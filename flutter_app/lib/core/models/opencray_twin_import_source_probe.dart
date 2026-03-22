class OpenCrayTwinImportSourceProbeSnapshot {
  const OpenCrayTwinImportSourceProbeSnapshot({
    required this.filePath,
    required this.fileName,
    required this.fileExtension,
    required this.sourceMode,
    required this.formatKey,
    required this.formatLabel,
    required this.confidence,
    required this.usesExistingImporter,
    required this.needsManualSelection,
    required this.notes,
  });

  final String filePath;
  final String fileName;
  final String fileExtension;
  final String? sourceMode;
  final String formatKey;
  final String formatLabel;
  final String confidence;
  final bool usesExistingImporter;
  final bool needsManualSelection;
  final List<String> notes;

  factory OpenCrayTwinImportSourceProbeSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayTwinImportSourceProbeSnapshot(
      filePath: payload['filePath'] as String? ?? '',
      fileName: payload['fileName'] as String? ?? '',
      fileExtension: payload['fileExtension'] as String? ?? '',
      sourceMode: payload['sourceMode'] as String?,
      formatKey: payload['formatKey'] as String? ?? '',
      formatLabel: payload['formatLabel'] as String? ?? '',
      confidence: payload['confidence'] as String? ?? '',
      usesExistingImporter: payload['usesExistingImporter'] as bool? ?? false,
      needsManualSelection: payload['needsManualSelection'] as bool? ?? false,
      notes: (payload['notes'] as List<Object?>? ?? const <Object?>[])
          .map((Object? item) => item as String? ?? '')
          .where((String item) => item.isNotEmpty)
          .toList(growable: false),
    );
  }
}
