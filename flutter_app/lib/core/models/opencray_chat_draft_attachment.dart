import 'package:flutter/foundation.dart';

enum OpenCrayChatDraftAttachmentKind {
  image,
  file;

  String get wireValue => switch (this) {
    OpenCrayChatDraftAttachmentKind.image => 'image',
    OpenCrayChatDraftAttachmentKind.file => 'file',
  };

  static OpenCrayChatDraftAttachmentKind fromWireValue(String raw) {
    return switch (raw.trim().toLowerCase()) {
      'image' => OpenCrayChatDraftAttachmentKind.image,
      _ => OpenCrayChatDraftAttachmentKind.file,
    };
  }
}

@immutable
class OpenCrayChatDraftAttachment {
  const OpenCrayChatDraftAttachment({
    required this.kind,
    required this.displayName,
    required this.relativePath,
    this.mimeType,
    this.sizeBytes,
  });

  final OpenCrayChatDraftAttachmentKind kind;
  final String displayName;
  final String relativePath;
  final String? mimeType;
  final int? sizeBytes;

  String get id => relativePath.trim().isNotEmpty ? relativePath : displayName;

  factory OpenCrayChatDraftAttachment.fromMap(Map<Object?, Object?> map) {
    return OpenCrayChatDraftAttachment(
      kind: OpenCrayChatDraftAttachmentKind.fromWireValue(
        map['kind'] as String? ?? 'file',
      ),
      displayName: map['displayName'] as String? ?? '',
      relativePath: map['relativePath'] as String? ?? '',
      mimeType: map['mimeType'] as String?,
      sizeBytes: switch (map['sizeBytes']) {
        int value => value,
        num value => value.toInt(),
        _ => null,
      },
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'kind': kind.wireValue,
    'displayName': displayName,
    'relativePath': relativePath,
    'mimeType': mimeType,
    'sizeBytes': sizeBytes,
  };
}
