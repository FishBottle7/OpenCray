part of 'opencray_markdown.dart';

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
    this.trailingInlineWidget,
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

  /// Rendered inline immediately after the last character of [data]. Callers
  /// must gate on [openCrayMarkdownCanInlineTrailingWidget]. Requires
  /// [selectable] to stay false (SelectableText cannot host WidgetSpans).
  final Widget? trailingInlineWidget;

  static final md.ExtensionSet extensionSet = md.ExtensionSet(
    <md.BlockSyntax>[
      LatexBlockSyntax(),
      ...md.ExtensionSet.gitHubFlavored.blockSyntaxes,
    ],
    <md.InlineSyntax>[
      _OpenCrayTrailingInlineSyntax(),
      LatexInlineSyntax(),
      ...md.ExtensionSet.gitHubFlavored.inlineSyntaxes,
    ],
  );

  @override
  Widget build(BuildContext context) {
    final Widget? trailing = selectable ? null : trailingInlineWidget;
    final Widget body = MarkdownBody(
      data: trailing != null
          ? '$data$openCrayMarkdownTrailingInlineMarker'
          : data,
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
        if (trailing != null)
          _openCrayTrailingInlineTag: _OpenCrayTrailingInlineWidgetBuilder(
            trailing,
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
    this.trailingInlineWidget,
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

  /// Rendered inline immediately after the last character of [data]; see
  /// [OpenCrayMarkdownBody.trailingInlineWidget].
  final Widget? trailingInlineWidget;

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
            trailingInlineWidget: widget.trailingInlineWidget,
          ),
        ),
      ),
    );
  }
}
