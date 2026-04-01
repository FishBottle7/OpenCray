import 'package:flutter/foundation.dart';

enum OpenCrayImageReferenceRole {
  evidence,
  portrait,
  reference;

  String get wireValue => switch (this) {
    OpenCrayImageReferenceRole.evidence => 'evidence',
    OpenCrayImageReferenceRole.portrait => 'portrait',
    OpenCrayImageReferenceRole.reference => 'reference',
  };

  static OpenCrayImageReferenceRole fromWireValue(String raw) {
    return switch (raw.trim().toLowerCase()) {
      'evidence' => OpenCrayImageReferenceRole.evidence,
      'portrait' => OpenCrayImageReferenceRole.portrait,
      _ => OpenCrayImageReferenceRole.reference,
    };
  }
}

enum OpenCrayImageReferenceStorageScope {
  workspace,
  agentPrivate;

  String get wireValue => switch (this) {
    OpenCrayImageReferenceStorageScope.workspace => 'workspace',
    OpenCrayImageReferenceStorageScope.agentPrivate => 'agent_private',
  };

  static OpenCrayImageReferenceStorageScope fromWireValue(String raw) {
    return switch (raw.trim().toLowerCase()) {
      'agent_private' => OpenCrayImageReferenceStorageScope.agentPrivate,
      _ => OpenCrayImageReferenceStorageScope.workspace,
    };
  }
}

enum OpenCrayImageReferenceSourceKind {
  chatAttachment,
  runArtifact,
  settingsAsset,
  workspacePath,
  durableAsset;

  String get wireValue => switch (this) {
    OpenCrayImageReferenceSourceKind.chatAttachment => 'chat_attachment',
    OpenCrayImageReferenceSourceKind.runArtifact => 'run_artifact',
    OpenCrayImageReferenceSourceKind.settingsAsset => 'settings_asset',
    OpenCrayImageReferenceSourceKind.workspacePath => 'workspace_path',
    OpenCrayImageReferenceSourceKind.durableAsset => 'durable_asset',
  };

  static OpenCrayImageReferenceSourceKind fromWireValue(String raw) {
    return switch (raw.trim().toLowerCase()) {
      'chat_attachment' => OpenCrayImageReferenceSourceKind.chatAttachment,
      'run_artifact' => OpenCrayImageReferenceSourceKind.runArtifact,
      'settings_asset' => OpenCrayImageReferenceSourceKind.settingsAsset,
      'durable_asset' => OpenCrayImageReferenceSourceKind.durableAsset,
      _ => OpenCrayImageReferenceSourceKind.workspacePath,
    };
  }
}

@immutable
class OpenCraySettingsImageAsset {
  const OpenCraySettingsImageAsset({
    required this.assetId,
    required this.displayName,
    required this.relativePath,
    required this.mimeType,
    required this.sha256,
    required this.sizeBytes,
    required this.createdAtEpochMs,
  });

  final String assetId;
  final String displayName;
  final String relativePath;
  final String mimeType;
  final String sha256;
  final int sizeBytes;
  final int createdAtEpochMs;

  factory OpenCraySettingsImageAsset.fromMap(Map<Object?, Object?> map) {
    return OpenCraySettingsImageAsset(
      assetId: map['assetId'] as String? ?? '',
      displayName: map['displayName'] as String? ?? '',
      relativePath: map['relativePath'] as String? ?? '',
      mimeType: map['mimeType'] as String? ?? '',
      sha256: map['sha256'] as String? ?? '',
      sizeBytes: _asInt(map['sizeBytes']) ?? 0,
      createdAtEpochMs: _asInt(map['createdAtEpochMs']) ?? 0,
    );
  }
}

@immutable
class OpenCrayImageReferenceSource {
  const OpenCrayImageReferenceSource({
    required this.sourceKind,
    this.chatAttachmentId,
    this.artifactId,
    this.settingsAssetId,
    this.relativePath,
    this.displayName,
    this.mimeType,
    this.sourceSessionId,
    this.sourceMessageId,
  });

  final OpenCrayImageReferenceSourceKind sourceKind;
  final String? chatAttachmentId;
  final String? artifactId;
  final String? settingsAssetId;
  final String? relativePath;
  final String? displayName;
  final String? mimeType;
  final String? sourceSessionId;
  final String? sourceMessageId;

  factory OpenCrayImageReferenceSource.fromMap(Map<Object?, Object?> map) {
    return OpenCrayImageReferenceSource(
      sourceKind: OpenCrayImageReferenceSourceKind.fromWireValue(
        map['sourceKind'] as String? ?? 'workspace_path',
      ),
      chatAttachmentId: map['chatAttachmentId'] as String?,
      artifactId: map['artifactId'] as String?,
      settingsAssetId: map['settingsAssetId'] as String?,
      relativePath: map['relativePath'] as String?,
      displayName: map['displayName'] as String?,
      mimeType: map['mimeType'] as String?,
      sourceSessionId: map['sourceSessionId'] as String?,
      sourceMessageId: map['sourceMessageId'] as String?,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'sourceKind': sourceKind.wireValue,
    'chatAttachmentId': chatAttachmentId,
    'artifactId': artifactId,
    'settingsAssetId': settingsAssetId,
    'relativePath': relativePath,
    'displayName': displayName,
    'mimeType': mimeType,
    'sourceSessionId': sourceSessionId,
    'sourceMessageId': sourceMessageId,
  };
}

@immutable
class OpenCrayImageReference {
  const OpenCrayImageReference({
    required this.refId,
    required this.role,
    required this.storageScope,
    required this.relativePath,
    required this.summary,
    required this.createdAtEpochMs,
    this.mimeType,
    this.sha256,
    this.widthPx,
    this.heightPx,
    this.caption,
    this.sourceLabel,
    this.sourceSessionId,
    this.sourceMessageId,
  });

  final String refId;
  final OpenCrayImageReferenceRole role;
  final OpenCrayImageReferenceStorageScope storageScope;
  final String relativePath;
  final String? mimeType;
  final String? sha256;
  final int? widthPx;
  final int? heightPx;
  final String? caption;
  final String summary;
  final String? sourceLabel;
  final String? sourceSessionId;
  final String? sourceMessageId;
  final int createdAtEpochMs;

  factory OpenCrayImageReference.fromMap(Map<Object?, Object?> map) {
    return OpenCrayImageReference(
      refId: map['refId'] as String? ?? '',
      role: OpenCrayImageReferenceRole.fromWireValue(
        map['role'] as String? ?? 'reference',
      ),
      storageScope: OpenCrayImageReferenceStorageScope.fromWireValue(
        map['storageScope'] as String? ?? 'workspace',
      ),
      relativePath: map['relativePath'] as String? ?? '',
      mimeType: map['mimeType'] as String?,
      sha256: map['sha256'] as String?,
      widthPx: _asInt(map['widthPx']),
      heightPx: _asInt(map['heightPx']),
      caption: map['caption'] as String?,
      summary: map['summary'] as String? ?? '',
      sourceLabel: map['sourceLabel'] as String?,
      sourceSessionId: map['sourceSessionId'] as String?,
      sourceMessageId: map['sourceMessageId'] as String?,
      createdAtEpochMs: _asInt(map['createdAtEpochMs']) ?? 0,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'refId': refId,
    'role': role.wireValue,
    'storageScope': storageScope.wireValue,
    'relativePath': relativePath,
    'mimeType': mimeType,
    'sha256': sha256,
    'widthPx': widthPx,
    'heightPx': heightPx,
    'caption': caption,
    'summary': summary,
    'sourceLabel': sourceLabel,
    'sourceSessionId': sourceSessionId,
    'sourceMessageId': sourceMessageId,
    'createdAtEpochMs': createdAtEpochMs,
  };
}

@immutable
class OpenCraySoulVisualIdentity {
  const OpenCraySoulVisualIdentity({
    this.portraitSummary,
    this.primaryPortrait,
    this.referenceImages = const <OpenCrayImageReference>[],
  });

  final String? portraitSummary;
  final OpenCrayImageReference? primaryPortrait;
  final List<OpenCrayImageReference> referenceImages;

  factory OpenCraySoulVisualIdentity.fromMap(Map<Object?, Object?> map) {
    return OpenCraySoulVisualIdentity(
      portraitSummary: map['portraitSummary'] as String?,
      primaryPortrait: _asMap(map['primaryPortrait']) == null
          ? null
          : OpenCrayImageReference.fromMap(_asMap(map['primaryPortrait'])!),
      referenceImages: _asList(map['referenceImages'])
          .map(_asMap)
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayImageReference.fromMap)
          .toList(growable: false),
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'portraitSummary': portraitSummary,
    'primaryPortrait': primaryPortrait?.toMap(),
    'referenceImages': referenceImages
        .map((OpenCrayImageReference reference) => reference.toMap())
        .toList(growable: false),
  };
}

@immutable
class OpenCrayMemoryImageReferenceResult {
  const OpenCrayMemoryImageReferenceResult({
    required this.memoryId,
    required this.recordVersion,
    required this.updatedAtEpochMs,
    required this.imageReferences,
  });

  final String memoryId;
  final int recordVersion;
  final int updatedAtEpochMs;
  final List<OpenCrayImageReference> imageReferences;

  factory OpenCrayMemoryImageReferenceResult.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayMemoryImageReferenceResult(
      memoryId: map['memoryId'] as String? ?? '',
      recordVersion: _asInt(map['recordVersion']) ?? 0,
      updatedAtEpochMs: _asInt(map['updatedAtEpochMs']) ?? 0,
      imageReferences: _asList(map['imageReferences'])
          .map(_asMap)
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayImageReference.fromMap)
          .toList(growable: false),
    );
  }
}

int? _asInt(Object? value) {
  return switch (value) {
    int typedValue => typedValue,
    num typedValue => typedValue.toInt(),
    _ => null,
  };
}

Map<Object?, Object?>? _asMap(Object? value) => value as Map<Object?, Object?>?;

List<Object?> _asList(Object? value) => value as List<Object?>? ?? const <Object?>[];
