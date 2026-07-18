import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:flutter_markdown_plus_latex/flutter_markdown_plus_latex.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:markdown/markdown.dart' as md;

import '../bridge/opencray_host_bridge.dart';
import '../copy/opencray_ui_copy.dart';
import '../models/opencray_file_image_preview.dart';
import 'opencray_image_bytes_view.dart';

const Set<String> _openCrayMarkdownInternalRoutes = <String>{
  '/settings',
  '/settings/notifications-background',
  '/settings/event-alerts',
  '/settings/notification-channels',
  '/settings/workspace',
  '/settings/llm',
  '/settings/mcp',
  '/settings/api-integrations',
  '/settings/privacy',
  '/settings/network-search',
  '/settings/media-speech',
  '/settings/safety',
  '/settings/about',
  '/settings/personalization',
  '/settings/agents',
};

const Set<String> _openCrayMarkdownFileLikeBareLinkSuffixes = <String>{
  '7z',
  'aac',
  'avi',
  'csv',
  'doc',
  'docx',
  'gif',
  'gz',
  'html',
  'htm',
  'java',
  'jpeg',
  'jpg',
  'json',
  'js',
  'kt',
  'log',
  'm4a',
  'markdown',
  'md',
  'mov',
  'mp3',
  'mp4',
  'pdf',
  'png',
  'ppt',
  'pptx',
  'py',
  'sh',
  'sql',
  'svg',
  'tar',
  'ts',
  'txt',
  'wav',
  'webm',
  'webp',
  'xml',
  'yaml',
  'yml',
  'zip',
};

class OpenCrayMarkdownBody extends StatelessWidget {
  const OpenCrayMarkdownBody({
    super.key,
    required this.data,
    this.selectable = false,
    this.styleSheet,
    this.onTapLink,
    this.latexTextStyle,
    this.latexTextScaleFactor,
    this.hostBridge,
    this.documentRelativePath = '',
    this.imageBackgroundColor,
    this.imageBorderColor,
  });

  final String data;
  final bool selectable;
  final MarkdownStyleSheet? styleSheet;
  final MarkdownTapLinkCallback? onTapLink;
  final TextStyle? latexTextStyle;
  final double? latexTextScaleFactor;
  final OpenCrayHostBridge? hostBridge;
  final String documentRelativePath;
  final Color? imageBackgroundColor;
  final Color? imageBorderColor;

  static final md.ExtensionSet extensionSet = md.ExtensionSet(
    <md.BlockSyntax>[
      LatexBlockSyntax(),
      ...md.ExtensionSet.gitHubFlavored.blockSyntaxes,
    ],
    <md.InlineSyntax>[
      LatexInlineSyntax(),
      ...md.ExtensionSet.gitHubFlavored.inlineSyntaxes,
    ],
  );

  @override
  Widget build(BuildContext context) {
    final Widget body = MarkdownBody(
      data: data,
      selectable: selectable,
      styleSheet: styleSheet,
      onTapLink: onTapLink,
      imageBuilder: (uri, title, alt) => OpenCrayMarkdownImage(
        uri: uri,
        title: title,
        alt: alt,
        hostBridge: hostBridge,
        documentRelativePath: documentRelativePath,
        backgroundColor: imageBackgroundColor,
        borderColor: imageBorderColor,
      ),
      builders: <String, MarkdownElementBuilder>{
        'latex': LatexElementBuilder(
          textStyle: latexTextStyle,
          textScaleFactor: latexTextScaleFactor,
        ),
      },
      extensionSet: extensionSet,
    );
    if (!selectable) {
      return body;
    }
    final ThemeData theme = Theme.of(context);
    return Theme(
      data: theme.copyWith(
        textSelectionTheme: openCrayMarkdownSelectionTheme(context),
      ),
      child: body,
    );
  }
}

typedef OpenCrayMarkdownContextMenuBuilder =
    Widget Function(
      BuildContext context,
      SelectableRegionState selectableRegionState,
      OpenCrayMarkdownSelectionSnapshot? selection,
    );

@immutable
class OpenCrayMarkdownClipboardPayload {
  const OpenCrayMarkdownClipboardPayload({
    required this.plainText,
    required this.htmlText,
  });

  final String plainText;
  final String htmlText;
}

@immutable
class OpenCrayMarkdownSelectionSnapshot {
  const OpenCrayMarkdownSelectionSnapshot({
    required this.plainText,
    this.range,
  });

  final String plainText;
  final SelectedContentRange? range;
}

class OpenCraySelectableMarkdownBody extends StatefulWidget {
  const OpenCraySelectableMarkdownBody({
    super.key,
    required this.data,
    this.styleSheet,
    this.onTapLink,
    this.latexTextStyle,
    this.latexTextScaleFactor,
    this.selectionTheme,
    this.onSelectionChanged,
    this.contextMenuBuilder,
    this.hostBridge,
    this.documentRelativePath = '',
    this.imageBackgroundColor,
    this.imageBorderColor,
  });

  final String data;
  final MarkdownStyleSheet? styleSheet;
  final MarkdownTapLinkCallback? onTapLink;
  final TextStyle? latexTextStyle;
  final double? latexTextScaleFactor;
  final TextSelectionThemeData? selectionTheme;
  final ValueChanged<OpenCrayMarkdownSelectionSnapshot?>? onSelectionChanged;
  final OpenCrayMarkdownContextMenuBuilder? contextMenuBuilder;
  final OpenCrayHostBridge? hostBridge;
  final String documentRelativePath;
  final Color? imageBackgroundColor;
  final Color? imageBorderColor;

  @override
  State<OpenCraySelectableMarkdownBody> createState() =>
      _OpenCraySelectableMarkdownBodyState();
}

class _OpenCraySelectableMarkdownBodyState
    extends State<OpenCraySelectableMarkdownBody> {
  final SelectionListenerNotifier _selectionNotifier =
      SelectionListenerNotifier();
  String? _selectedPlainText;

  @override
  void initState() {
    super.initState();
    _selectionNotifier.addListener(_emitSelectionChanged);
  }

  @override
  void dispose() {
    _selectionNotifier.removeListener(_emitSelectionChanged);
    _selectionNotifier.dispose();
    super.dispose();
  }

  OpenCrayMarkdownSelectionSnapshot? get _currentSelection {
    final String plainText = _selectedPlainText ?? '';
    if (plainText.isEmpty) {
      return null;
    }
    final SelectedContentRange? range = _selectionNotifier.registered
        ? _selectionNotifier.selection.range
        : null;
    return OpenCrayMarkdownSelectionSnapshot(
      plainText: plainText,
      range: range,
    );
  }

  void _handleSelectionChanged(SelectedContent? selection) {
    final String plainText = selection?.plainText ?? '';
    _selectedPlainText = plainText.isEmpty ? null : plainText;
    _emitSelectionChanged();
  }

  void _emitSelectionChanged() {
    widget.onSelectionChanged?.call(_currentSelection);
  }

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    return Theme(
      data: theme.copyWith(
        textSelectionTheme:
            widget.selectionTheme ?? openCrayMarkdownSelectionTheme(context),
      ),
      child: SelectionListener(
        selectionNotifier: _selectionNotifier,
        child: SelectionArea(
          onSelectionChanged: _handleSelectionChanged,
          contextMenuBuilder:
              (
                BuildContext context,
                SelectableRegionState selectableRegionState,
              ) {
                final OpenCrayMarkdownContextMenuBuilder? builder =
                    widget.contextMenuBuilder;
                if (builder != null) {
                  return builder(
                    context,
                    selectableRegionState,
                    _currentSelection,
                  );
                }
                return AdaptiveTextSelectionToolbar.selectableRegion(
                  selectableRegionState: selectableRegionState,
                );
              },
          child: OpenCrayMarkdownBody(
            data: widget.data,
            styleSheet: widget.styleSheet,
            onTapLink: widget.onTapLink,
            latexTextStyle: widget.latexTextStyle,
            latexTextScaleFactor: widget.latexTextScaleFactor,
            hostBridge: widget.hostBridge,
            documentRelativePath: widget.documentRelativePath,
            imageBackgroundColor: widget.imageBackgroundColor,
            imageBorderColor: widget.imageBorderColor,
          ),
        ),
      ),
    );
  }
}

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

TextSelectionThemeData openCrayMarkdownSelectionTheme(BuildContext context) {
  final Color primary = Theme.of(context).colorScheme.primary;
  return TextSelectionThemeData(
    selectionColor: primary.withValues(alpha: 0.32),
    selectionHandleColor: primary,
    cursorColor: primary,
  );
}

bool openCrayIsMarkdownFileName(String name) {
  final String normalizedName = name.trim().toLowerCase();
  final String extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  return extension == 'md' || extension == 'markdown';
}

OpenCrayMarkdownResolvedImage? openCrayResolveMarkdownImage(
  Uri uri, {
  String documentRelativePath = '',
}) {
  final String scheme = uri.scheme.trim().toLowerCase();
  if (scheme == 'http' || scheme == 'https') {
    return OpenCrayMarkdownResolvedImage.external(uri, originalUri: uri);
  }
  if (scheme == 'data') {
    return OpenCrayMarkdownResolvedImage.data(originalUri: uri);
  }
  if (scheme.isNotEmpty) {
    return null;
  }
  final String? relativePath = openCrayResolveMarkdownWorkspaceRelativePath(
    uri,
    documentRelativePath: documentRelativePath,
  );
  if (relativePath == null || relativePath.isEmpty) {
    return null;
  }
  return OpenCrayMarkdownResolvedImage.workspace(
    relativePath,
    originalUri: uri,
  );
}

String? openCrayResolveMarkdownWorkspaceRelativePath(
  Uri uri, {
  String documentRelativePath = '',
}) {
  if (uri.hasScheme) {
    return null;
  }
  final String path = _safeDecodeMarkdownPath(
    uri.path.isEmpty ? uri.toString() : uri.path,
  ).trim();
  if (path.isEmpty) {
    return null;
  }
  final bool rootRelative = path.startsWith('/');
  final List<String> segments = <String>[];
  if (!rootRelative) {
    final String baseDirectory = _markdownDocumentBaseDirectory(
      documentRelativePath,
    );
    if (baseDirectory.isNotEmpty) {
      segments.addAll(
        baseDirectory.split('/').where((segment) => segment.isNotEmpty),
      );
    }
  }
  for (final String segment in path.replaceAll('\\', '/').split('/')) {
    final String normalizedSegment = segment.trim();
    if (normalizedSegment.isEmpty || normalizedSegment == '.') {
      continue;
    }
    if (normalizedSegment == '..') {
      if (segments.isEmpty) {
        return null;
      }
      segments.removeLast();
      continue;
    }
    segments.add(normalizedSegment);
  }
  if (segments.isEmpty) {
    return null;
  }
  return segments.join('/');
}

String _markdownDocumentBaseDirectory(String documentRelativePath) {
  final String normalizedPath = documentRelativePath
      .trim()
      .replaceAll('\\', '/')
      .replaceFirst(RegExp(r'^/'), '');
  if (normalizedPath.isEmpty) {
    return '';
  }
  final int slashIndex = normalizedPath.lastIndexOf('/');
  if (slashIndex < 0) {
    return '';
  }
  return normalizedPath.substring(0, slashIndex);
}

String _safeDecodeMarkdownPath(String value) {
  try {
    return Uri.decodeFull(value);
  } on FormatException {
    return value;
  }
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

OpenCrayMarkdownClipboardPayload? openCrayBuildMarkdownClipboardPayload(
  String markdown,
) {
  final String normalizedMarkdown = markdown.trimRight();
  if (normalizedMarkdown.isEmpty) {
    return null;
  }
  final List<md.Node> nodes = md.Document(
    extensionSet: OpenCrayMarkdownBody.extensionSet,
  ).parse(normalizedMarkdown);
  final _OpenCrayMarkdownPlainTextResult plainTextResult =
      _OpenCrayMarkdownPlainTextBuilder().build(nodes);
  if (!plainTextResult.hasHyperlinks) {
    return null;
  }
  return OpenCrayMarkdownClipboardPayload(
    plainText: _normalizeMarkdownClipboardPlainText(plainTextResult.text),
    htmlText: md
        .markdownToHtml(
          normalizedMarkdown,
          extensionSet: OpenCrayMarkdownBody.extensionSet,
        )
        .trimRight(),
  );
}

OpenCrayMarkdownClipboardPayload?
openCrayBuildMarkdownSelectionClipboardPayload(
  String markdown, {
  required String selectedText,
  int? selectionStartOffset,
  int? selectionEndOffset,
}) {
  if (selectedText.isEmpty) {
    return null;
  }
  final _OpenCrayMarkdownSelectionProjection projection =
      _buildMarkdownSelectionProjection(markdown);
  if (projection.visibleText.isEmpty || projection.linkCount == 0) {
    return null;
  }
  final Set<String> seenRanges = <String>{};
  final List<_OpenCraySelectionRange> candidateRanges =
      <_OpenCraySelectionRange>[];
  void addCandidate(int start, int end) {
    final int normalizedStart = start < end ? start : end;
    final int normalizedEnd = start < end ? end : start;
    if (normalizedStart < 0 ||
        normalizedEnd > projection.visibleText.length ||
        normalizedStart >= normalizedEnd) {
      return;
    }
    final String key = '$normalizedStart:$normalizedEnd';
    if (seenRanges.add(key)) {
      candidateRanges.add(
        _OpenCraySelectionRange(
          startOffset: normalizedStart,
          endOffset: normalizedEnd,
        ),
      );
    }
  }

  if (selectionStartOffset != null && selectionEndOffset != null) {
    addCandidate(selectionStartOffset, selectionEndOffset);
  }
  int searchIndex = 0;
  while (searchIndex <= projection.visibleText.length) {
    final int matchIndex = projection.visibleText.indexOf(
      selectedText,
      searchIndex,
    );
    if (matchIndex < 0) {
      break;
    }
    addCandidate(matchIndex, matchIndex + selectedText.length);
    searchIndex = matchIndex + 1;
  }
  final Map<String, OpenCrayMarkdownClipboardPayload> uniquePayloads =
      <String, OpenCrayMarkdownClipboardPayload>{};
  for (final _OpenCraySelectionRange range in candidateRanges) {
    final OpenCrayMarkdownClipboardPayload? payload =
        _buildMarkdownSelectionPayloadForRange(
          projection,
          range,
          selectedText: selectedText,
        );
    if (payload == null) {
      continue;
    }
    final String key = '${payload.plainText}\u0000${payload.htmlText}';
    uniquePayloads[key] = payload;
  }
  if (uniquePayloads.length != 1) {
    return null;
  }
  return uniquePayloads.values.single;
}

String? openCrayResolveMarkdownInternalRoute(String? href) {
  final String target = href?.trim() ?? '';
  if (target.isEmpty) {
    return null;
  }
  final Uri? uri = Uri.tryParse(target);
  if (uri == null || uri.hasScheme || uri.hasAuthority) {
    return null;
  }
  final String path = uri.path.isEmpty ? target : uri.path;
  if (_openCrayMarkdownInternalRoutes.contains(path)) {
    return path;
  }
  return null;
}

Uri? openCrayResolveMarkdownExternalUri(String? href) {
  final String target = href?.trim() ?? '';
  if (target.isEmpty) {
    return null;
  }
  final Uri? uri = Uri.tryParse(target);
  if (uri != null && uri.hasScheme) {
    final String scheme = uri.scheme.trim().toLowerCase();
    return scheme == 'http' || scheme == 'https' ? uri : null;
  }
  if (!_openCrayLooksLikeBareExternalLink(target)) {
    return null;
  }
  return Uri.tryParse('https://$target');
}

String openCrayMarkdownUserFacingErrorMessage(
  Object error, {
  String fallback = '',
}) {
  if (error is PlatformException) {
    final String message = error.message?.trim() ?? '';
    if (message.isNotEmpty) {
      return message;
    }
  }
  final String raw = '$error'.trim();
  if (raw.isEmpty) {
    return fallback;
  }
  const List<String> prefixes = <String>[
    'Bad state: ',
    'Exception: ',
    'Invalid argument(s): ',
  ];
  for (final String prefix in prefixes) {
    if (raw.startsWith(prefix)) {
      return raw.substring(prefix.length).trim();
    }
  }
  if (raw.startsWith('PlatformException(')) {
    final List<String> segments = raw.split(', ');
    if (segments.length >= 2) {
      final String message = segments[1].trim();
      if (message.isNotEmpty && message != 'null') {
        return message;
      }
    }
  }
  return raw;
}

String openCrayMarkdownLocalizedErrorMessage(
  Object error,
  OpenCrayUiCopy copy, {
  String? fallback,
}) {
  final String message = openCrayMarkdownUserFacingErrorMessage(
    error,
    fallback: fallback ?? copy.chatMessageActionFailed,
  );
  switch (message) {
    case 'Unsupported markdown link target.':
      return copy.markdownLinkUnsupported;
    case 'Only http and https links are supported.':
      return copy.markdownLinkHttpOnly;
    case 'No application can open this link.':
      return copy.markdownLinkNoAppAvailable;
    case 'Failed to open the external link.':
      return copy.markdownLinkOpenFailed;
    case 'External links are unavailable.':
      return copy.markdownLinkExternalUnavailable;
    case 'Missing host bridge for markdown link target.':
      return copy.markdownLinkHostUnavailable;
  }
  return message;
}

bool _openCrayLooksLikeBareExternalLink(String target) {
  if (target.startsWith('/') || target.contains(RegExp(r'\s'))) {
    return false;
  }
  final String firstSegment = target
      .split('/')
      .first
      .split('?')
      .first
      .split('#')
      .first;
  if (!firstSegment.contains('.')) {
    return false;
  }
  final List<String> labels = firstSegment.split('.');
  if (labels.any((label) => label.isEmpty)) {
    return false;
  }
  final RegExp labelPattern = RegExp(r'^[A-Za-z0-9-]+$');
  if (labels.any(
    (label) =>
        !labelPattern.hasMatch(label) ||
        label.startsWith('-') ||
        label.endsWith('-'),
  )) {
    return false;
  }
  final String suffix = labels.last.toLowerCase();
  if (_openCrayMarkdownFileLikeBareLinkSuffixes.contains(suffix)) {
    return false;
  }
  return suffix.length >= 2 && suffix.length <= 24;
}

String _normalizeMarkdownClipboardPlainText(String value) {
  return value.replaceAll(RegExp(r'\n{3,}'), '\n\n').trim();
}

void openCrayFinalizeSelectionCopyUi(SelectableRegionState selectableRegion) {
  switch (defaultTargetPlatform) {
    case TargetPlatform.android:
    case TargetPlatform.fuchsia:
      selectableRegion.clearSelection();
      selectableRegion.hideToolbar();
      return;
    case TargetPlatform.iOS:
      selectableRegion.hideToolbar(false);
      return;
    case TargetPlatform.linux:
    case TargetPlatform.macOS:
    case TargetPlatform.windows:
      selectableRegion.hideToolbar();
      return;
  }
}

const HtmlEscape _openCrayMarkdownHtmlTextEscape = HtmlEscape();
const HtmlEscape _openCrayMarkdownHtmlAttributeEscape = HtmlEscape(
  HtmlEscapeMode.attribute,
);

class _OpenCraySelectionRange {
  const _OpenCraySelectionRange({
    required this.startOffset,
    required this.endOffset,
  });

  final int startOffset;
  final int endOffset;
}

class _OpenCrayMarkdownSelectionProjection {
  const _OpenCrayMarkdownSelectionProjection({
    required this.visibleText,
    required this.segments,
    required this.linkCount,
  });

  final String visibleText;
  final List<_OpenCrayMarkdownSelectionSegment> segments;
  final int linkCount;
}

class _OpenCrayMarkdownSelectionSegment {
  const _OpenCrayMarkdownSelectionSegment({
    required this.startOffset,
    required this.endOffset,
    required this.text,
    this.href,
  });

  final int startOffset;
  final int endOffset;
  final String text;
  final String? href;
}

_OpenCrayMarkdownSelectionProjection _buildMarkdownSelectionProjection(
  String markdown,
) {
  final String normalizedMarkdown = markdown.trimRight();
  if (normalizedMarkdown.isEmpty) {
    return const _OpenCrayMarkdownSelectionProjection(
      visibleText: '',
      segments: <_OpenCrayMarkdownSelectionSegment>[],
      linkCount: 0,
    );
  }
  final List<md.Node> nodes = md.Document(
    extensionSet: OpenCrayMarkdownBody.extensionSet,
  ).parse(normalizedMarkdown);
  return _OpenCrayMarkdownSelectionProjectionBuilder().build(nodes);
}

OpenCrayMarkdownClipboardPayload? _buildMarkdownSelectionPayloadForRange(
  _OpenCrayMarkdownSelectionProjection projection,
  _OpenCraySelectionRange range, {
  required String selectedText,
}) {
  if (range.startOffset < 0 ||
      range.endOffset > projection.visibleText.length ||
      range.startOffset >= range.endOffset) {
    return null;
  }
  final String selectedSlice = projection.visibleText.substring(
    range.startOffset,
    range.endOffset,
  );
  if (selectedSlice != selectedText) {
    return null;
  }
  final StringBuffer plainText = StringBuffer();
  final StringBuffer htmlFragment = StringBuffer();
  bool hasFullHyperlink = false;
  for (final _OpenCrayMarkdownSelectionSegment segment in projection.segments) {
    if (segment.endOffset <= range.startOffset ||
        segment.startOffset >= range.endOffset) {
      continue;
    }
    final int localStart = range.startOffset > segment.startOffset
        ? range.startOffset - segment.startOffset
        : 0;
    final int localEnd = range.endOffset < segment.endOffset
        ? range.endOffset - segment.startOffset
        : segment.text.length;
    if (localStart >= localEnd) {
      continue;
    }
    final bool coversWholeSegment =
        localStart == 0 && localEnd == segment.text.length;
    final String href = segment.href?.trim() ?? '';
    if (coversWholeSegment && href.isNotEmpty) {
      hasFullHyperlink = true;
      plainText.write(href);
      htmlFragment.write(
        '<a href="${_openCrayMarkdownHtmlAttributeEscape.convert(href)}">'
        '${_openCrayMarkdownHtmlTextEscape.convert(segment.text)}'
        '</a>',
      );
      continue;
    }
    final String slice = segment.text.substring(localStart, localEnd);
    plainText.write(slice);
    htmlFragment.write(_openCrayTextSliceToHtml(slice));
  }
  if (!hasFullHyperlink) {
    return null;
  }
  return OpenCrayMarkdownClipboardPayload(
    plainText: plainText.toString(),
    htmlText:
        '<span style="white-space: pre-wrap;">${htmlFragment.toString()}</span>',
  );
}

String _openCrayTextSliceToHtml(String value) {
  if (value.isEmpty) {
    return '';
  }
  return _openCrayMarkdownHtmlTextEscape
      .convert(value)
      .replaceAll('\n', '<br />');
}

class _OpenCrayMarkdownSelectionProjectionBuilder {
  final List<_OpenCrayMarkdownSelectionSegment> _segments =
      <_OpenCrayMarkdownSelectionSegment>[];
  int _offset = 0;
  int _linkCount = 0;

  _OpenCrayMarkdownSelectionProjection build(List<md.Node> nodes) {
    _visitNodes(nodes);
    return _OpenCrayMarkdownSelectionProjection(
      visibleText: _segments.map((segment) => segment.text).join(),
      segments: List<_OpenCrayMarkdownSelectionSegment>.unmodifiable(_segments),
      linkCount: _linkCount,
    );
  }

  void _visitNodes(List<md.Node>? nodes) {
    if (nodes == null) {
      return;
    }
    for (final md.Node node in nodes) {
      _visitNode(node);
    }
  }

  void _visitNode(md.Node node) {
    if (node is md.Text) {
      _appendText(node.text);
      return;
    }
    if (node is! md.Element) {
      _appendText(node.textContent);
      return;
    }
    switch (node.tag) {
      case 'a':
        _appendLink(node);
        return;
      case 'br':
        _appendText('\n');
        return;
      case 'ul':
        _appendList(node, ordered: false);
        return;
      case 'ol':
        _appendList(node, ordered: true);
        return;
      case 'li':
        _appendText('• ');
        _visitNodes(node.children);
        _appendText('\n');
        return;
      case 'tr':
        _appendTableRow(node.children);
        _appendText('\n');
        return;
      case 'table':
      case 'thead':
      case 'tbody':
        _visitNodes(node.children);
        return;
      case 'p':
      case 'blockquote':
      case 'pre':
      case 'h1':
      case 'h2':
      case 'h3':
      case 'h4':
      case 'h5':
      case 'h6':
        _visitNodes(node.children);
        _appendText('\n\n');
        return;
      default:
        _visitNodes(node.children);
        return;
    }
  }

  void _appendLink(md.Element element) {
    final String href = element.attributes['href']?.trim() ?? '';
    final String label = _collectInlineText(element.children);
    if (label.isEmpty) {
      return;
    }
    _linkCount += href.isNotEmpty ? 1 : 0;
    _appendText(label, href: href.isNotEmpty ? href : null);
  }

  void _appendList(md.Element element, {required bool ordered}) {
    final List<md.Node> children = element.children ?? const <md.Node>[];
    int itemIndex = 1;
    for (final md.Node child in children) {
      if (child is md.Element && child.tag == 'li') {
        final String prefix = ordered ? '${itemIndex++}. ' : '• ';
        _appendText(prefix);
        _visitNodes(child.children);
        _appendText('\n');
        continue;
      }
      _visitNode(child);
    }
    _appendText('\n');
  }

  void _appendTableRow(List<md.Node>? cells) {
    if (cells == null || cells.isEmpty) {
      return;
    }
    bool isFirstCell = true;
    for (final md.Node cell in cells) {
      if (cell is! md.Element) {
        continue;
      }
      if (!isFirstCell) {
        _appendText(' | ');
      }
      isFirstCell = false;
      _visitNodes(cell.children);
    }
  }

  String _collectInlineText(List<md.Node>? nodes) {
    if (nodes == null || nodes.isEmpty) {
      return '';
    }
    final StringBuffer buffer = StringBuffer();
    for (final md.Node node in nodes) {
      if (node is md.Text) {
        buffer.write(node.text);
        continue;
      }
      if (node is! md.Element) {
        buffer.write(node.textContent);
        continue;
      }
      if (node.tag == 'br') {
        buffer.write('\n');
        continue;
      }
      buffer.write(_collectInlineText(node.children));
    }
    return buffer.toString();
  }

  void _appendText(String text, {String? href}) {
    if (text.isEmpty) {
      return;
    }
    final _OpenCrayMarkdownSelectionSegment? previous = _segments.isEmpty
        ? null
        : _segments.last;
    if (previous != null && previous.href == href) {
      _segments[_segments.length - 1] = _OpenCrayMarkdownSelectionSegment(
        startOffset: previous.startOffset,
        endOffset: previous.endOffset + text.length,
        text: '${previous.text}$text',
        href: href,
      );
      _offset += text.length;
      return;
    }
    _segments.add(
      _OpenCrayMarkdownSelectionSegment(
        startOffset: _offset,
        endOffset: _offset + text.length,
        text: text,
        href: href,
      ),
    );
    _offset += text.length;
  }
}

class _OpenCrayMarkdownPlainTextResult {
  const _OpenCrayMarkdownPlainTextResult({
    required this.text,
    required this.hasHyperlinks,
  });

  final String text;
  final bool hasHyperlinks;
}

class _OpenCrayMarkdownPlainTextBuilder {
  bool _hasHyperlinks = false;

  _OpenCrayMarkdownPlainTextResult build(List<md.Node> nodes) {
    return _OpenCrayMarkdownPlainTextResult(
      text: _renderNodes(nodes),
      hasHyperlinks: _hasHyperlinks,
    );
  }

  String _renderNodes(List<md.Node>? nodes) {
    if (nodes == null || nodes.isEmpty) {
      return '';
    }
    return nodes.map(_renderNode).join();
  }

  String _renderNode(md.Node node) {
    if (node is md.Text) {
      return node.text;
    }
    if (node is! md.Element) {
      return node.textContent;
    }
    switch (node.tag) {
      case 'a':
        _hasHyperlinks = true;
        final String href = node.attributes['href']?.trim() ?? '';
        return href.isNotEmpty ? href : _renderNodes(node.children);
      case 'br':
        return '\n';
      case 'li':
        return '- ${_normalizeInlineSegment(_renderNodes(node.children))}\n';
      case 'ul':
      case 'ol':
        return '${_renderNodes(node.children)}\n';
      case 'tr':
        return '${_renderTableRow(node.children)}\n';
      case 'table':
      case 'thead':
      case 'tbody':
        return '${_renderNodes(node.children)}\n';
      case 'p':
      case 'blockquote':
      case 'pre':
      case 'h1':
      case 'h2':
      case 'h3':
      case 'h4':
      case 'h5':
      case 'h6':
        final String content = _renderNodes(node.children);
        return content.isEmpty ? '' : '$content\n\n';
      case 'th':
      case 'td':
        return _normalizeInlineSegment(_renderNodes(node.children));
      default:
        return _renderNodes(node.children);
    }
  }

  String _renderTableRow(List<md.Node>? cells) {
    if (cells == null || cells.isEmpty) {
      return '';
    }
    return cells
        .map(_renderNode)
        .map(_normalizeInlineSegment)
        .where((cell) => cell.isNotEmpty)
        .join(' | ');
  }

  String _normalizeInlineSegment(String value) {
    return value.replaceAll('\n', ' ').replaceAll(RegExp(r'\s+'), ' ').trim();
  }
}
