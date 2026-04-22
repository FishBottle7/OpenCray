import 'package:flutter/foundation.dart';

enum OpenCrayChatDraftAttachmentKind {
  image,
  voice,
  file;

  String get wireValue => switch (this) {
    OpenCrayChatDraftAttachmentKind.image => 'image',
    OpenCrayChatDraftAttachmentKind.voice => 'voice',
    OpenCrayChatDraftAttachmentKind.file => 'file',
  };

  static OpenCrayChatDraftAttachmentKind fromWireValue(String raw) {
    return switch (raw.trim().toLowerCase()) {
      'image' => OpenCrayChatDraftAttachmentKind.image,
      'voice' || 'audio' => OpenCrayChatDraftAttachmentKind.voice,
      _ => OpenCrayChatDraftAttachmentKind.file,
    };
  }
}

@immutable
class OpenCrayChatDraftAttachment {
  const OpenCrayChatDraftAttachment({
    required this.kind,
    required this.displayName,
    this.relativePath = '',
    this.artifactId,
    this.chatAttachmentId,
    this.mimeType,
    this.sizeBytes,
    this.durationMs,
    this.waveformBars = const <int>[],
    this.transcriptText,
  });

  final OpenCrayChatDraftAttachmentKind kind;
  final String displayName;
  final String relativePath;
  final String? artifactId;
  final String? chatAttachmentId;
  final String? mimeType;
  final int? sizeBytes;
  final int? durationMs;
  final List<int> waveformBars;
  final String? transcriptText;

  String get id {
    final String trimmedRelativePath = relativePath.trim();
    if (trimmedRelativePath.isNotEmpty) {
      return trimmedRelativePath;
    }
    final String trimmedChatAttachmentId = chatAttachmentId?.trim() ?? '';
    if (trimmedChatAttachmentId.isNotEmpty) {
      return trimmedChatAttachmentId;
    }
    final String trimmedArtifactId = artifactId?.trim() ?? '';
    if (trimmedArtifactId.isNotEmpty) {
      return trimmedArtifactId;
    }
    return displayName;
  }

  factory OpenCrayChatDraftAttachment.fromMap(Map<Object?, Object?> map) {
    return OpenCrayChatDraftAttachment(
      kind: OpenCrayChatDraftAttachmentKind.fromWireValue(
        map['kind'] as String? ?? 'file',
      ),
      displayName: map['displayName'] as String? ?? '',
      relativePath: map['relativePath'] as String? ?? '',
      artifactId: map['artifactId'] as String?,
      chatAttachmentId: map['chatAttachmentId'] as String?,
      mimeType: map['mimeType'] as String?,
      sizeBytes: switch (map['sizeBytes']) {
        int value => value,
        num value => value.toInt(),
        _ => null,
      },
      durationMs: switch (map['durationMs']) {
        int value => value,
        num value => value.toInt(),
        _ => null,
      },
      waveformBars: (map['waveformBars'] as List<Object?>? ?? const <Object?>[])
          .map(
            (Object? value) => switch (value) {
              int number => number,
              num number => number.toInt(),
              _ => null,
            },
          )
          .whereType<int>()
          .toList(growable: false),
      transcriptText: map['transcriptText'] as String?,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'kind': kind.wireValue,
    'displayName': displayName,
    'relativePath': relativePath,
    if (artifactId != null) 'artifactId': artifactId,
    if (chatAttachmentId != null) 'chatAttachmentId': chatAttachmentId,
    'mimeType': mimeType,
    'sizeBytes': sizeBytes,
    'durationMs': durationMs,
    if (waveformBars.isNotEmpty) 'waveformBars': waveformBars,
    if (transcriptText != null) 'transcriptText': transcriptText,
  };
}
