part of 'opencray_markdown.dart';

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
