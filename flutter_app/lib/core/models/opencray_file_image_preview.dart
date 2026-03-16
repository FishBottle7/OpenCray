import 'dart:convert';
import 'dart:typed_data';

class OpenCrayFileImagePreview {
  const OpenCrayFileImagePreview({
    required this.name,
    required this.relativePath,
    required this.bytes,
    required this.mimeType,
    required this.width,
    required this.height,
  });

  final String name;
  final String relativePath;
  final Uint8List bytes;
  final String mimeType;
  final int width;
  final int height;

  double get aspectRatio {
    if (width <= 0 || height <= 0) {
      return 1;
    }
    return width / height;
  }

  factory OpenCrayFileImagePreview.fromMap(Map<Object?, Object?> payload) {
    final encodedBytes = payload['bytesBase64'] as String? ?? '';
    return OpenCrayFileImagePreview(
      name: payload['name'] as String? ?? '',
      relativePath: payload['relativePath'] as String? ?? '',
      bytes: encodedBytes.isEmpty ? Uint8List(0) : base64Decode(encodedBytes),
      mimeType: payload['mimeType'] as String? ?? 'image/png',
      width: (payload['width'] as num?)?.toInt() ?? 1,
      height: (payload['height'] as num?)?.toInt() ?? 1,
    );
  }
}
