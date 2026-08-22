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

part 'opencray_markdown_body.dart';
part 'opencray_markdown_images.dart';
part 'opencray_markdown_selection.dart';

/// Sentinel appended to markdown source to mark where a trailing inline
/// widget (for example the streaming indicator) should render. Uses a
/// private-use codepoint so real content never contains it.
const String openCrayMarkdownTrailingInlineMarker = '\u{E5C7}';

const String _openCrayTrailingInlineTag = 'opencrayTrailingInline';

/// Whether appending [openCrayMarkdownTrailingInlineMarker] to [markdown]
/// keeps the document structurally intact so the trailing widget can render
/// inline right after the last character. Returns false when the text ends
/// inside or on structural syntax (open code fence, closing fence line,
/// table row, thematic break, unbalanced inline code or display math), in
/// which case callers should render the widget on its own line instead.
bool openCrayMarkdownCanInlineTrailingWidget(String markdown) {
  final String text = markdown.trimRight();
  if (text.isEmpty) {
    return false;
  }
  int fenceDelimiters = 0;
  for (final String line in const LineSplitter().convert(text)) {
    final String trimmed = line.trimLeft();
    if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
      fenceDelimiters += 1;
    }
  }
  if (fenceDelimiters.isOdd) {
    return false;
  }
  if ('\$\$'.allMatches(text).length.isOdd) {
    return false;
  }
  final String lastLine = text.substring(text.lastIndexOf('\n') + 1);
  final String trimmedLastLine = lastLine.trim();
  if (trimmedLastLine.startsWith('```') || trimmedLastLine.startsWith('~~~')) {
    return false;
  }
  if (trimmedLastLine.startsWith('|')) {
    return false;
  }
  if (RegExp(r'^(-{3,}|_{3,}|\*{3,})$').hasMatch(trimmedLastLine)) {
    return false;
  }
  if ('`'.allMatches(lastLine).length.isOdd) {
    return false;
  }
  return true;
}

class _OpenCrayTrailingInlineSyntax extends md.InlineSyntax {
  _OpenCrayTrailingInlineSyntax() : super(openCrayMarkdownTrailingInlineMarker);

  @override
  bool onMatch(md.InlineParser parser, Match match) {
    parser.addNode(md.Element.empty(_openCrayTrailingInlineTag));
    return true;
  }
}

class _OpenCrayTrailingInlineWidgetBuilder extends MarkdownElementBuilder {
  _OpenCrayTrailingInlineWidgetBuilder(this.trailing);

  final Widget trailing;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    return Text.rich(
      TextSpan(
        children: <InlineSpan>[
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: ExcludeSemantics(
              child: SelectionContainer.disabled(child: trailing),
            ),
          ),
        ],
      ),
    );
  }
}

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
