import 'package:flutter/foundation.dart';

@immutable
class OpenCraySandboxPreviewEmbedConfig {
  const OpenCraySandboxPreviewEmbedConfig({
    required this.previewUrl,
    required this.providerId,
    required this.headers,
    required this.sessionMatched,
    required this.accessTokenConfigured,
    this.unavailableReason,
  });

  final String previewUrl;
  final String providerId;
  final Map<String, String> headers;
  final bool sessionMatched;
  final bool accessTokenConfigured;
  final String? unavailableReason;

  factory OpenCraySandboxPreviewEmbedConfig.fromMap(Map<Object?, Object?> map) {
    final Object? rawHeaders = map['headers'];
    final Map<String, String> headers;
    if (rawHeaders is Map<Object?, Object?>) {
      headers = <String, String>{
        for (final MapEntry<Object?, Object?> entry in rawHeaders.entries)
          if ((entry.key as String?)?.trim().isNotEmpty == true &&
              (entry.value as String?)?.trim().isNotEmpty == true)
            (entry.key as String).trim(): (entry.value as String).trim(),
      };
    } else {
      headers = const <String, String>{};
    }
    return OpenCraySandboxPreviewEmbedConfig(
      previewUrl: map['previewUrl'] as String? ?? '',
      providerId: map['providerId'] as String? ?? '',
      headers: headers,
      sessionMatched: map['sessionMatched'] as bool? ?? false,
      accessTokenConfigured: map['accessTokenConfigured'] as bool? ?? false,
      unavailableReason: map['unavailableReason'] as String?,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'previewUrl': previewUrl,
    'providerId': providerId,
    'headers': headers,
    'sessionMatched': sessionMatched,
    'accessTokenConfigured': accessTokenConfigured,
    'unavailableReason': unavailableReason,
  };
}
