part of 'opencray_markdown.dart';

@immutable
class OpenCrayMarkdownResolvedImage {
  const OpenCrayMarkdownResolvedImage.workspace(
    this.relativePath, {
    required this.originalUri,
  }) : externalUri = null,
       isDataUri = false;

  const OpenCrayMarkdownResolvedImage.external(
    this.externalUri, {
    required this.originalUri,
  }) : relativePath = null,
       isDataUri = false;

  const OpenCrayMarkdownResolvedImage.data({required this.originalUri})
    : relativePath = null,
      externalUri = null,
      isDataUri = true;

  final Uri originalUri;
  final String? relativePath;
  final Uri? externalUri;
  final bool isDataUri;
}

class OpenCrayMarkdownImage extends StatelessWidget {
  const OpenCrayMarkdownImage({
    super.key,
    required this.uri,
    this.title,
    this.alt,
    this.hostBridge,
    this.documentRelativePath = '',
    this.backgroundColor,
    this.borderColor,
  });

  final Uri uri;
  final String? title;
  final String? alt;
  final OpenCrayHostBridge? hostBridge;
  final String documentRelativePath;
  final Color? backgroundColor;
  final Color? borderColor;

  @override
  Widget build(BuildContext context) {
    final OpenCrayMarkdownResolvedImage? resolved =
        openCrayResolveMarkdownImage(
          uri,
          documentRelativePath: documentRelativePath,
        );
    if (resolved == null) {
      return _OpenCrayMarkdownImageFrame(
        backgroundColor: backgroundColor,
        borderColor: borderColor,
        child: _OpenCrayMarkdownImagePlaceholder(label: _fallbackLabel),
      );
    }
    if (resolved.isDataUri) {
      final Uint8List? bytes = resolved.originalUri.data?.contentAsBytes();
      if (bytes == null || bytes.isEmpty) {
        return _OpenCrayMarkdownImageFrame(
          backgroundColor: backgroundColor,
          borderColor: borderColor,
          child: _OpenCrayMarkdownImagePlaceholder(label: _fallbackLabel),
        );
      }
      final String mimeType =
          resolved.originalUri.data?.mimeType ?? 'image/png';
      return _buildInteractiveImage(
        context,
        aspectRatio: null,
        previewBuilder: (_) => OpenCrayImageBytesView(
          bytes: bytes,
          mimeType: mimeType,
          fit: BoxFit.contain,
          filterQuality: FilterQuality.high,
          gaplessPlayback: true,
        ),
        child: OpenCrayImageBytesView(
          bytes: bytes,
          mimeType: mimeType,
          fit: BoxFit.contain,
          filterQuality: FilterQuality.medium,
          gaplessPlayback: true,
        ),
      );
    }
    final Uri? externalUri = resolved.externalUri;
    if (externalUri != null) {
      if (_isSvgUri(externalUri)) {
        return _buildInteractiveImage(
          context,
          aspectRatio: null,
          previewBuilder: (_) => SvgPicture.network(
            externalUri.toString(),
            fit: BoxFit.contain,
            placeholderBuilder: (_) =>
                _OpenCrayMarkdownImagePlaceholder(label: _fallbackLabel),
          ),
          child: SvgPicture.network(
            externalUri.toString(),
            fit: BoxFit.contain,
            placeholderBuilder: (_) =>
                _OpenCrayMarkdownImagePlaceholder(label: _fallbackLabel),
          ),
        );
      }
      return _buildInteractiveImage(
        context,
        aspectRatio: null,
        previewBuilder: (_) => Image.network(
          externalUri.toString(),
          fit: BoxFit.contain,
          filterQuality: FilterQuality.high,
          errorBuilder: (_, _, _) =>
              _OpenCrayMarkdownImagePlaceholder(label: _fallbackLabel),
          loadingBuilder: (context, child, progress) {
            if (progress == null) {
              return child;
            }
            return _OpenCrayMarkdownImagePlaceholder(label: _fallbackLabel);
          },
        ),
        child: Image.network(
          externalUri.toString(),
          fit: BoxFit.contain,
          filterQuality: FilterQuality.medium,
          errorBuilder: (_, _, _) =>
              _OpenCrayMarkdownImagePlaceholder(label: _fallbackLabel),
          loadingBuilder: (context, child, progress) {
            if (progress == null) {
              return child;
            }
            return _OpenCrayMarkdownImagePlaceholder(label: _fallbackLabel);
          },
        ),
      );
    }
    final String relativePath = resolved.relativePath ?? '';
    final OpenCrayHostBridge? bridge = hostBridge;
    if (bridge == null || relativePath.isEmpty) {
      return _OpenCrayMarkdownImageFrame(
        backgroundColor: backgroundColor,
        borderColor: borderColor,
        child: _OpenCrayMarkdownImagePlaceholder(label: _fallbackLabel),
      );
    }
    return FutureBuilder<OpenCrayFileImagePreview>(
      future: bridge.loadWorkspaceImagePreview(relativePath),
      builder: (context, snapshot) {
        final OpenCrayFileImagePreview? preview = snapshot.data;
        final bool ready = preview != null && preview.bytes.isNotEmpty;
        if (!ready) {
          return _OpenCrayMarkdownImageFrame(
            backgroundColor: backgroundColor,
            borderColor: borderColor,
            child: _OpenCrayMarkdownImagePlaceholder(label: _fallbackLabel),
          );
        }
        return _buildInteractiveImage(
          context,
          aspectRatio: preview.aspectRatio,
          previewBuilder: (_) => OpenCrayImageBytesView(
            bytes: preview.bytes,
            mimeType: preview.mimeType,
            fit: BoxFit.contain,
            filterQuality: FilterQuality.high,
            gaplessPlayback: true,
          ),
          child: OpenCrayImageBytesView(
            bytes: preview.bytes,
            mimeType: preview.mimeType,
            fit: BoxFit.contain,
            filterQuality: FilterQuality.medium,
            gaplessPlayback: true,
          ),
        );
      },
    );
  }

  Widget _buildInteractiveImage(
    BuildContext context, {
    required Widget child,
    required WidgetBuilder previewBuilder,
    required double? aspectRatio,
  }) {
    void openPreview() {
      showDialog<void>(
        context: context,
        barrierColor: const Color(0xCC000000),
        builder: (dialogContext) => _OpenCrayMarkdownImagePreviewDialog(
          label: _fallbackLabel,
          child: previewBuilder(dialogContext),
        ),
      );
    }

    return SelectionContainer.disabled(
      child: Listener(
        key: const ValueKey<String>('opencray-markdown-image-tappable'),
        behavior: HitTestBehavior.opaque,
        onPointerUp: (_) => openPreview(),
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onDoubleTap: () {},
          child: _OpenCrayMarkdownImageFrame(
            backgroundColor: backgroundColor,
            borderColor: borderColor,
            aspectRatio: aspectRatio,
            child: child,
          ),
        ),
      ),
    );
  }

  String get _fallbackLabel {
    final String trimmedAlt = alt?.trim() ?? '';
    if (trimmedAlt.isNotEmpty) {
      return trimmedAlt;
    }
    final String trimmedTitle = title?.trim() ?? '';
    if (trimmedTitle.isNotEmpty) {
      return trimmedTitle;
    }
    final String path = _safeDecodeMarkdownPath(
      uri.path.isEmpty ? uri.toString() : uri.path,
    );
    if (path.isEmpty) {
      return 'image';
    }
    final List<String> segments = path.replaceAll('\\', '/').split('/');
    return segments.isEmpty ? 'image' : segments.last;
  }
}

bool _isSvgUri(Uri uri) {
  final UriData? data = uri.data;
  if (data != null) {
    return openCrayIsSvgMimeType(data.mimeType);
  }
  return uri.path.trim().toLowerCase().endsWith('.svg');
}

class _OpenCrayMarkdownImageFrame extends StatelessWidget {
  const _OpenCrayMarkdownImageFrame({
    required this.child,
    this.backgroundColor,
    this.borderColor,
    this.aspectRatio,
  });

  final Widget child;
  final Color? backgroundColor;
  final Color? borderColor;
  final double? aspectRatio;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final Color resolvedBackgroundColor =
        backgroundColor ?? theme.colorScheme.surfaceContainerHighest;
    final Color resolvedBorderColor =
        borderColor ?? theme.dividerColor.withValues(alpha: 0.55);
    return LayoutBuilder(
      builder: (context, constraints) {
        final double maxWidth = constraints.maxWidth.isFinite
            ? constraints.maxWidth
            : 320;
        final double boundedWidth = maxWidth.clamp(0, 420).toDouble();
        Widget content = ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: resolvedBackgroundColor,
              border: Border.all(color: resolvedBorderColor),
              borderRadius: BorderRadius.circular(16),
            ),
            child: child,
          ),
        );
        final double? safeAspectRatio =
            aspectRatio != null && aspectRatio!.isFinite && aspectRatio! > 0
            ? aspectRatio!.clamp(0.4, 2.2).toDouble()
            : null;
        if (safeAspectRatio != null) {
          content = AspectRatio(aspectRatio: safeAspectRatio, child: content);
        } else {
          content = SizedBox(height: 180, child: content);
        }
        return ConstrainedBox(
          constraints: BoxConstraints(
            minWidth: boundedWidth,
            maxWidth: boundedWidth,
            maxHeight: 360,
          ),
          child: content,
        );
      },
    );
  }
}

class _OpenCrayMarkdownImagePlaceholder extends StatelessWidget {
  const _OpenCrayMarkdownImagePlaceholder({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final Color iconColor = Theme.of(context).colorScheme.onSurfaceVariant;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Icon(Icons.image_outlined, size: 22, color: iconColor),
            const SizedBox(height: 8),
            Text(
              label,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: iconColor, height: 1.3),
            ),
          ],
        ),
      ),
    );
  }
}

class _OpenCrayMarkdownImagePreviewDialog extends StatelessWidget {
  const _OpenCrayMarkdownImagePreviewDialog({
    required this.label,
    required this.child,
  });

  final String label;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Dialog(
      key: const ValueKey<String>('opencray-markdown-image-preview-dialog'),
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.all(16),
      child: Stack(
        children: <Widget>[
          Positioned.fill(
            child: Align(
              alignment: Alignment.center,
              child: InteractiveViewer(
                minScale: 0.8,
                maxScale: 6,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(20),
                  child: ColoredBox(
                    color: Colors.black,
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(
                        maxWidth: 960,
                        maxHeight: 720,
                      ),
                      child: child,
                    ),
                  ),
                ),
              ),
            ),
          ),
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: Row(
              children: <Widget>[
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.only(left: 8, top: 8, right: 12),
                    child: Text(
                      label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
                IconButton(
                  key: const ValueKey<String>(
                    'opencray-markdown-image-preview-close',
                  ),
                  onPressed: () => Navigator.of(context).pop(),
                  icon: const Icon(Icons.close_rounded, color: Colors.white),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
