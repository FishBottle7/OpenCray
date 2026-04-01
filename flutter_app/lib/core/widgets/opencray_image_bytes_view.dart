import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

bool openCrayIsSvgMimeType(String mimeType) =>
    mimeType.trim().split(';').first.toLowerCase() == 'image/svg+xml';

class OpenCrayImageBytesView extends StatelessWidget {
  const OpenCrayImageBytesView({
    super.key,
    required this.bytes,
    required this.mimeType,
    this.fit = BoxFit.contain,
    this.filterQuality = FilterQuality.medium,
    this.gaplessPlayback = true,
  });

  final Uint8List bytes;
  final String mimeType;
  final BoxFit fit;
  final FilterQuality filterQuality;
  final bool gaplessPlayback;

  @override
  Widget build(BuildContext context) {
    if (openCrayIsSvgMimeType(mimeType)) {
      return SvgPicture.string(
        utf8.decode(bytes, allowMalformed: true),
        fit: fit,
      );
    }
    return Image.memory(
      bytes,
      fit: fit,
      filterQuality: filterQuality,
      gaplessPlayback: gaplessPlayback,
    );
  }
}
