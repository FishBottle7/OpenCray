part of 'files_feature.dart';

class _TextPreviewDialog extends StatelessWidget {
  const _TextPreviewDialog({
    super.key,
    required this.bridge,
    required this.copy,
    required this.preview,
    required this.onEdit,
  });

  final OpenCrayHostBridge bridge;
  final OpenCrayUiCopy copy;
  final OpenCrayFileTextPreview preview;
  final VoidCallback onEdit;

  Future<void> _handleMarkdownLinkTap(
    BuildContext context,
    String? href,
  ) async {
    final String target = href?.trim() ?? '';
    if (target.isEmpty) {
      return;
    }
    try {
      final String? routeName = openCrayResolveMarkdownInternalRoute(target);
      if (routeName != null) {
        final NavigatorState navigator = Navigator.of(context);
        navigator.pop();
        await navigator.pushNamed(routeName);
        return;
      }
      final Uri? externalUri = openCrayResolveMarkdownExternalUri(target);
      if (externalUri != null) {
        await bridge.openExternalUri(externalUri.toString());
        return;
      }
      throw StateError('Unsupported markdown link target.');
    } catch (error) {
      final String message = openCrayMarkdownLocalizedErrorMessage(error, copy);
      if (message.isNotEmpty) {
        unawaited(bridge.showNativeToast(message).catchError((Object _) {}));
      }
    }
  }

  Widget _buildMarkdownSelectionContextMenu(
    BuildContext context,
    SelectableRegionState selectableRegionState,
    OpenCrayMarkdownSelectionSnapshot? selection,
    String markdown,
  ) {
    final List<ContextMenuButtonItem> buttonItems = selectableRegionState
        .contextMenuButtonItems
        .map((item) {
          if (item.type != ContextMenuButtonType.copy) {
            return item;
          }
          return item.copyWith(
            onPressed: () async {
              final OpenCrayMarkdownClipboardPayload? payload =
                  openCrayBuildMarkdownSelectionClipboardPayload(
                    markdown,
                    selectedText: selection?.plainText ?? '',
                    selectionStartOffset: selection?.range?.startOffset,
                    selectionEndOffset: selection?.range?.endOffset,
                  );
              if (payload == null) {
                item.onPressed?.call();
                return;
              }
              await bridge.copyRichTextToClipboard(
                plainText: payload.plainText,
                htmlText: payload.htmlText,
              );
              openCrayFinalizeSelectionCopyUi(selectableRegionState);
            },
          );
        })
        .toList(growable: false);
    return AdaptiveTextSelectionToolbar.buttonItems(
      anchors: selectableRegionState.contextMenuAnchors,
      buttonItems: buttonItems,
    );
  }

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    return ConstrainedBox(
      constraints: BoxConstraints(
        maxWidth: 560,
        maxHeight: media.size.height * 0.78,
      ),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: FilesFeatureScreen.surface.withValues(alpha: 0.96),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: OpenCrayColors.divider),
          boxShadow: OpenCrayShadows.floating,
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 18, 18, 18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 40,
                    height: 40,
                    decoration: BoxDecoration(
                      color: FilesFeatureScreen.surfaceMuted,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Icon(
                      CupertinoIcons.doc_text,
                      size: 20,
                      color: FilesFeatureScreen.accent,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          preview.name,
                          key: const ValueKey<String>(
                            'files-text-preview-title',
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                            letterSpacing: -0.2,
                            color: FilesFeatureScreen.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          preview.relativePath,
                          key: const ValueKey<String>(
                            'files-text-preview-path',
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 12,
                            color: FilesFeatureScreen.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 12),
                  InkWell(
                    key: const ValueKey<String>('files-text-preview-close'),
                    borderRadius: BorderRadius.circular(16),
                    onTap: () => Navigator.of(context).pop(),
                    child: Container(
                      width: 32,
                      height: 32,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: FilesFeatureScreen.surfaceMuted,
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: const Icon(
                        CupertinoIcons.xmark,
                        size: 16,
                        color: FilesFeatureScreen.textSecondary,
                      ),
                    ),
                  ),
                ],
              ),
              if (preview.isTruncated) ...[
                const SizedBox(height: 14),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 9,
                  ),
                  decoration: BoxDecoration(
                    color: FilesFeatureScreen.surfaceMuted,
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Text(
                    copy.filesPreviewTruncatedNotice,
                    style: const TextStyle(
                      fontSize: 12,
                      height: 1.35,
                      color: FilesFeatureScreen.textSecondary,
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 14),
              const Divider(height: 1, color: FilesFeatureScreen.divider),
              const SizedBox(height: 14),
              Expanded(
                child: GestureDetector(
                  key: const ValueKey<String>('files-text-preview-body'),
                  behavior: HitTestBehavior.opaque,
                  onDoubleTap: onEdit,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: OpenCrayColors.surfaceSubtle,
                      borderRadius: BorderRadius.circular(18),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(14, 14, 14, 14),
                      child: preview.content.isEmpty
                          ? Center(
                              child: Text(
                                copy.filesPreviewEmptyBody,
                                style: const TextStyle(
                                  fontSize: 14,
                                  color: FilesFeatureScreen.textSecondary,
                                ),
                              ),
                            )
                          : SingleChildScrollView(
                              child: openCrayIsMarkdownFileName(preview.name)
                                  ? OpenCraySelectableMarkdownBody(
                                      key: const ValueKey<String>(
                                        'files-text-preview-markdown',
                                      ),
                                      data: preview.content,
                                      hostBridge: bridge,
                                      documentRelativePath:
                                          preview.relativePath,
                                      onTapLink: (_, href, __) {
                                        unawaited(
                                          _handleMarkdownLinkTap(context, href),
                                        );
                                      },
                                      latexTextStyle: const TextStyle(
                                        fontSize: 14,
                                        height: 1.55,
                                        color: FilesFeatureScreen.textPrimary,
                                      ),
                                      styleSheet: _filesMarkdownStyleSheet(
                                        context,
                                      ),
                                      imageBackgroundColor:
                                          OpenCrayColors.surfaceMuted,
                                      imageBorderColor:
                                          FilesFeatureScreen.divider,
                                      contextMenuBuilder:
                                          (
                                            BuildContext context,
                                            SelectableRegionState
                                            selectableRegionState,
                                            OpenCrayMarkdownSelectionSnapshot?
                                            selection,
                                          ) =>
                                              _buildMarkdownSelectionContextMenu(
                                                context,
                                                selectableRegionState,
                                                selection,
                                                preview.content,
                                              ),
                                    )
                                  : SelectionArea(
                                      child: Text(
                                        preview.content,
                                        style: const TextStyle(
                                          fontSize: 13.5,
                                          height: 1.5,
                                          fontFamily: 'monospace',
                                          color: FilesFeatureScreen.textPrimary,
                                        ),
                                      ),
                                    ),
                            ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CreateEntryDialog extends StatefulWidget {
  const _CreateEntryDialog({
    super.key,
    required this.copy,
    required this.onCancel,
    required this.onCreate,
  });

  final OpenCrayUiCopy copy;
  final VoidCallback onCancel;
  final ValueChanged<_NewEntryIntent> onCreate;

  @override
  State<_CreateEntryDialog> createState() => _CreateEntryDialogState();
}

class _CreateEntryDialogState extends State<_CreateEntryDialog> {
  late final TextEditingController _controller = TextEditingController()
    ..addListener(_handleTextChanged);
  _NewEntryIntent _intent = const _NewEntryIntent(
    name: '',
    kind: _NewEntryIntentKind.folder,
  );

  @override
  void initState() {
    super.initState();
    _intent = _resolveNewEntryIntent(_controller.text, widget.copy);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _handleTextChanged() {
    final nextIntent = _resolveNewEntryIntent(_controller.text, widget.copy);
    if (nextIntent.name == _intent.name &&
        nextIntent.kind == _intent.kind &&
        nextIntent.errorText == _intent.errorText) {
      return;
    }
    setState(() {
      _intent = nextIntent;
    });
  }

  void _submit() {
    if (!_intent.canSubmit) {
      return;
    }
    widget.onCreate(_intent);
  }

  @override
  Widget build(BuildContext context) {
    final canSubmit = _intent.canSubmit;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 520),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: FilesFeatureScreen.surface.withValues(alpha: 0.97),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: OpenCrayColors.divider),
          boxShadow: OpenCrayShadows.floating,
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 18, 18, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 40,
                    height: 40,
                    decoration: BoxDecoration(
                      color: FilesFeatureScreen.surfaceMuted,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Icon(
                      CupertinoIcons.folder_badge_plus,
                      size: 20,
                      color: FilesFeatureScreen.accent,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(
                        widget.copy.filesCreateEntryTitle,
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w600,
                          letterSpacing: -0.2,
                          color: FilesFeatureScreen.textPrimary,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  InkWell(
                    key: const ValueKey<String>('files-create-entry-close'),
                    borderRadius: BorderRadius.circular(16),
                    onTap: widget.onCancel,
                    child: Container(
                      width: 32,
                      height: 32,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: FilesFeatureScreen.surfaceMuted,
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: const Icon(
                        CupertinoIcons.xmark,
                        size: 16,
                        color: FilesFeatureScreen.textSecondary,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              TextField(
                key: const ValueKey<String>('files-create-entry-field'),
                controller: _controller,
                autofocus: true,
                textInputAction: TextInputAction.done,
                onSubmitted: (_) => _submit(),
                decoration: InputDecoration(
                  hintText: widget.copy.filesNameFieldHint,
                  errorText: _intent.errorText,
                  filled: true,
                  fillColor: FilesFeatureScreen.surfaceMuted,
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 14,
                  ),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(16),
                    borderSide: BorderSide.none,
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(16),
                    borderSide: BorderSide(
                      color: _intent.errorText == null
                          ? FilesFeatureScreen.accent
                          : FilesFeatureScreen.danger,
                      width: 1.4,
                    ),
                  ),
                  errorBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(16),
                    borderSide: const BorderSide(
                      color: FilesFeatureScreen.danger,
                      width: 1.2,
                    ),
                  ),
                  focusedErrorBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(16),
                    borderSide: const BorderSide(
                      color: FilesFeatureScreen.danger,
                      width: 1.4,
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 14),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: widget.onCancel,
                    child: Text(widget.copy.filesCancelAction),
                  ),
                  const SizedBox(width: 4),
                  AnimatedOpacity(
                    duration: OpenCrayMotion.resolve(
                      context,
                      OpenCrayMotion.micro,
                    ),
                    opacity: canSubmit ? 1 : 0.42,
                    child: TextButton(
                      key: const ValueKey<String>('files-create-entry-submit'),
                      onPressed: canSubmit ? _submit : null,
                      child: Text(widget.copy.filesCreateAction),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _TextEditorDialog extends StatefulWidget {
  const _TextEditorDialog({
    super.key,
    required this.copy,
    required this.document,
    required this.onClose,
    required this.onSave,
    this.autofocus = false,
  });

  final OpenCrayUiCopy copy;
  final OpenCrayWorkspaceTextDocument document;
  final VoidCallback onClose;
  final Future<bool> Function(String content) onSave;
  final bool autofocus;

  @override
  State<_TextEditorDialog> createState() => _TextEditorDialogState();
}

class _TextEditorDialogState extends State<_TextEditorDialog> {
  late final TextEditingController _controller = TextEditingController(
    text: widget.document.content,
  );
  bool _isSaving = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _handleSave() async {
    if (_isSaving) {
      return;
    }
    setState(() {
      _isSaving = true;
    });
    final didSave = await widget.onSave(_controller.text);
    if (!mounted || didSave) {
      return;
    }
    setState(() {
      _isSaving = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    return ConstrainedBox(
      constraints: BoxConstraints(
        maxWidth: 640,
        maxHeight: media.size.height * 0.82,
      ),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: FilesFeatureScreen.surface.withValues(alpha: 0.97),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: OpenCrayColors.divider),
          boxShadow: OpenCrayShadows.floating,
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 18, 18, 18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 40,
                    height: 40,
                    decoration: BoxDecoration(
                      color: FilesFeatureScreen.surfaceMuted,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Icon(
                      CupertinoIcons.doc_text,
                      size: 20,
                      color: FilesFeatureScreen.accent,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.document.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                            letterSpacing: -0.2,
                            color: FilesFeatureScreen.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          widget.document.relativePath,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 12,
                            color: FilesFeatureScreen.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  TextButton(
                    key: const ValueKey<String>('files-text-editor-save'),
                    onPressed: _isSaving ? null : _handleSave,
                    child: _isSaving
                        ? const CupertinoActivityIndicator(radius: 8)
                        : Text(widget.copy.filesSaveAction),
                  ),
                  const SizedBox(width: 4),
                  InkWell(
                    key: const ValueKey<String>('files-text-editor-close'),
                    borderRadius: BorderRadius.circular(16),
                    onTap: _isSaving ? null : widget.onClose,
                    child: Container(
                      width: 32,
                      height: 32,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: FilesFeatureScreen.surfaceMuted,
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: const Icon(
                        CupertinoIcons.xmark,
                        size: 16,
                        color: FilesFeatureScreen.textSecondary,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              const Divider(height: 1, color: FilesFeatureScreen.divider),
              const SizedBox(height: 14),
              Expanded(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: OpenCrayColors.surfaceSubtle,
                    borderRadius: BorderRadius.circular(18),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                    child: TextField(
                      key: const ValueKey<String>('files-text-editor-field'),
                      controller: _controller,
                      autofocus: widget.autofocus,
                      expands: true,
                      minLines: null,
                      maxLines: null,
                      keyboardType: TextInputType.multiline,
                      textAlignVertical: TextAlignVertical.top,
                      style: const TextStyle(
                        fontSize: 14,
                        height: 1.5,
                        fontFamily: 'monospace',
                        color: FilesFeatureScreen.textPrimary,
                      ),
                      decoration: const InputDecoration.collapsed(hintText: ''),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ImagePreviewDialog extends StatelessWidget {
  const _ImagePreviewDialog({super.key, required this.preview});

  final OpenCrayFileImagePreview preview;

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    final maxWidth = media.size.width - 40;
    final maxHeight = media.size.height * 0.72;
    final imageSize = _resolveImagePreviewSize(
      aspectRatio: preview.aspectRatio,
      maxWidth: maxWidth.clamp(0, 680).toDouble(),
      maxHeight: maxHeight,
    );
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 680),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          DecoratedBox(
            decoration: BoxDecoration(
              color: FilesFeatureScreen.surface.withValues(alpha: 0.94),
              borderRadius: BorderRadius.circular(24),
              border: Border.all(color: OpenCrayColors.divider),
              boxShadow: OpenCrayShadows.floating,
            ),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(18, 16, 18, 16),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 40,
                    height: 40,
                    decoration: BoxDecoration(
                      color: FilesFeatureScreen.surfaceMuted,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Icon(
                      CupertinoIcons.photo,
                      size: 20,
                      color: FilesFeatureScreen.accent,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          preview.name,
                          key: const ValueKey<String>(
                            'files-image-preview-title',
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                            letterSpacing: -0.2,
                            color: FilesFeatureScreen.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          preview.relativePath,
                          key: const ValueKey<String>(
                            'files-image-preview-path',
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 12,
                            color: FilesFeatureScreen.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 12),
                  InkWell(
                    key: const ValueKey<String>('files-image-preview-close'),
                    borderRadius: BorderRadius.circular(16),
                    onTap: () => Navigator.of(context).pop(),
                    child: Container(
                      width: 32,
                      height: 32,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: FilesFeatureScreen.surfaceMuted,
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: const Icon(
                        CupertinoIcons.xmark,
                        size: 16,
                        color: FilesFeatureScreen.textSecondary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Align(
            alignment: Alignment.center,
            child: SizedBox(
              width: imageSize.width,
              height: imageSize.height,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(28),
                child: DecoratedBox(
                  decoration: const BoxDecoration(
                    color: OpenCrayColors.surfaceMuted,
                  ),
                  child: OpenCrayImageBytesView(
                    key: const ValueKey<String>('files-image-preview-image'),
                    bytes: preview.bytes,
                    mimeType: preview.mimeType,
                    fit: BoxFit.cover,
                    filterQuality: FilterQuality.medium,
                    gaplessPlayback: true,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

_NewEntryIntent _resolveNewEntryIntent(String rawName, OpenCrayUiCopy copy) {
  final normalizedName = rawName.trim();
  if (normalizedName.isEmpty) {
    return const _NewEntryIntent(name: '', kind: _NewEntryIntentKind.folder);
  }
  if (_supportsTextDocumentName(normalizedName)) {
    return _NewEntryIntent(
      name: normalizedName,
      kind: _NewEntryIntentKind.textFile,
    );
  }
  if (normalizedName.contains('.')) {
    return _NewEntryIntent(
      name: normalizedName,
      kind: _NewEntryIntentKind.unsupportedFile,
      errorText: copy.filesCreateUnsupportedType,
    );
  }
  return _NewEntryIntent(
    name: normalizedName,
    kind: _NewEntryIntentKind.folder,
  );
}

Size _resolveImagePreviewSize({
  required double aspectRatio,
  required double maxWidth,
  required double maxHeight,
}) {
  final resolvedAspectRatio = aspectRatio <= 0 ? 1.0 : aspectRatio;
  var width = maxWidth;
  var height = width / resolvedAspectRatio;
  if (height > maxHeight) {
    height = maxHeight;
    width = height * resolvedAspectRatio;
  }
  return Size(width, height);
}

MarkdownStyleSheet _filesMarkdownStyleSheet(BuildContext context) {
  final base = MarkdownStyleSheet.fromTheme(Theme.of(context));
  final Color linkColor = Theme.of(context).colorScheme.primary;
  return base.copyWith(
    a: TextStyle(
      fontSize: 14,
      height: 1.55,
      fontWeight: FontWeight.w600,
      color: linkColor,
      decoration: TextDecoration.underline,
      decorationColor: linkColor.withValues(alpha: 0.75),
    ),
    p: const TextStyle(
      fontSize: 14,
      height: 1.55,
      color: FilesFeatureScreen.textPrimary,
    ),
    h1: const TextStyle(
      fontSize: 24,
      height: 1.2,
      fontWeight: FontWeight.w700,
      color: FilesFeatureScreen.textPrimary,
    ),
    h2: const TextStyle(
      fontSize: 20,
      height: 1.25,
      fontWeight: FontWeight.w700,
      color: FilesFeatureScreen.textPrimary,
    ),
    h3: const TextStyle(
      fontSize: 17,
      height: 1.3,
      fontWeight: FontWeight.w700,
      color: FilesFeatureScreen.textPrimary,
    ),
    listBullet: const TextStyle(
      fontSize: 14,
      height: 1.55,
      color: FilesFeatureScreen.textPrimary,
    ),
    code: const TextStyle(
      fontSize: 13,
      height: 1.45,
      fontFamily: 'monospace',
      color: FilesFeatureScreen.textPrimary,
    ),
    codeblockPadding: const EdgeInsets.all(12),
    codeblockDecoration: BoxDecoration(
      color: OpenCrayColors.codeSurface,
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: OpenCrayColors.divider),
    ),
    blockSpacing: 14,
    blockquote: const TextStyle(
      fontSize: 13,
      height: 1.5,
      color: FilesFeatureScreen.textSecondary,
    ),
    blockquoteDecoration: BoxDecoration(
      color: OpenCrayColors.surfaceMuted,
      borderRadius: BorderRadius.circular(12),
      border: const Border(
        left: BorderSide(color: FilesFeatureScreen.divider, width: 3),
      ),
    ),
    horizontalRuleDecoration: const BoxDecoration(
      border: Border(
        top: BorderSide(color: FilesFeatureScreen.divider, width: 1),
      ),
    ),
  );
}
