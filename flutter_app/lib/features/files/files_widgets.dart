part of 'files_feature.dart';

class _TitleRow extends StatelessWidget {
  const _TitleRow({
    required this.copy,
    required this.isSelectionMode,
    required this.selectedCount,
    required this.onDone,
  });

  final OpenCrayUiCopy copy;
  final bool isSelectionMode;
  final int selectedCount;
  final VoidCallback? onDone;

  @override
  Widget build(BuildContext context) {
    final title = isSelectionMode
        ? copy.filesSelectedCount(selectedCount)
        : copy.filesTitle;
    return Row(
      children: [
        Expanded(
          child: Text(
            title,
            style: const TextStyle(
              fontSize: 28,
              height: 1.05,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.6,
              color: FilesFeatureScreen.textPrimary,
            ),
          ),
        ),
        if (isSelectionMode)
          TextButton(
            key: const ValueKey<String>('files-selection-done'),
            onPressed: onDone,
            child: Text(
              copy.filesDoneAction,
              style: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: FilesFeatureScreen.accent,
              ),
            ),
          ),
      ],
    );
  }
}

class _SearchBar extends StatefulWidget {
  const _SearchBar({
    required this.controller,
    required this.hint,
    required this.clearLabel,
  });

  final TextEditingController controller;
  final String hint;
  final String clearLabel;

  @override
  State<_SearchBar> createState() => _SearchBarState();
}

class _SearchBarState extends State<_SearchBar> {
  late final FocusNode _focusNode = FocusNode()
    ..addListener(_handleSearchStateChanged);

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_handleSearchStateChanged);
  }

  @override
  void didUpdateWidget(covariant _SearchBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller == widget.controller) {
      return;
    }
    oldWidget.controller.removeListener(_handleSearchStateChanged);
    widget.controller.addListener(_handleSearchStateChanged);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_handleSearchStateChanged);
    _focusNode.dispose();
    super.dispose();
  }

  void _handleSearchStateChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final bool hasQuery = widget.controller.text.trim().isNotEmpty;
    final bool isActive = hasQuery || _focusNode.hasFocus;
    return AnimatedContainer(
      key: const ValueKey<String>('files-search-surface'),
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      decoration: BoxDecoration(
        color: isActive ? OpenCrayColors.primaryTint : Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: isActive ? OpenCrayColors.primary : Colors.transparent,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 2, 8, 2),
        child: Row(
          children: [
            Icon(
              CupertinoIcons.search,
              size: 18,
              color: isActive
                  ? FilesFeatureScreen.accent
                  : FilesFeatureScreen.textTertiary,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: TextField(
                key: const ValueKey<String>('files-search-field'),
                controller: widget.controller,
                focusNode: _focusNode,
                decoration: InputDecoration(
                  border: InputBorder.none,
                  hintText: widget.hint,
                  hintStyle: const TextStyle(
                    fontSize: 14,
                    height: 1.2,
                    color: FilesFeatureScreen.textTertiary,
                  ),
                ),
              ),
            ),
            AnimatedOpacity(
              opacity: hasQuery ? 1 : 0,
              duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
              curve: OpenCrayMotion.enter,
              child: IgnorePointer(
                ignoring: !hasQuery,
                child: IconButton(
                  key: const ValueKey<String>('files-search-clear'),
                  tooltip: widget.clearLabel,
                  onPressed: () => widget.controller.clear(),
                  icon: const Icon(CupertinoIcons.xmark_circle_fill, size: 18),
                  color: FilesFeatureScreen.textTertiary,
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints.tightFor(
                    width: 32,
                    height: 32,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _LocationCard extends StatelessWidget {
  const _LocationCard({
    required this.copy,
    required this.snapshot,
    required this.directoryName,
    required this.absolutePath,
    required this.visibleItemCount,
    required this.isLoading,
    required this.isMutating,
    required this.actionRowKey,
    required this.breadcrumbs,
    required this.breadcrumbsEnabled,
    required this.onRefresh,
    required this.onCreateFolder,
    required this.onBreadcrumbTap,
  });

  final OpenCrayUiCopy copy;
  final OpenCrayFilesSnapshot? snapshot;
  final String directoryName;
  final String absolutePath;
  final int visibleItemCount;
  final bool isLoading;
  final bool isMutating;
  final GlobalKey actionRowKey;
  final List<_BreadcrumbSegment> breadcrumbs;
  final bool breadcrumbsEnabled;
  final Future<void> Function() onRefresh;
  final Future<void> Function() onCreateFolder;
  final ValueChanged<String> onBreadcrumbTap;

  @override
  Widget build(BuildContext context) {
    final statsLabel = copy.filesDirectoryItemCount(visibleItemCount);

    return DecoratedBox(
      decoration: BoxDecoration(
        color: FilesFeatureScreen.surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: OpenCrayColors.divider),
        boxShadow: OpenCrayShadows.card,
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(
                  copy.filesLocationTitle,
                  style: const TextStyle(
                    fontSize: 12,
                    height: 1.2,
                    color: FilesFeatureScreen.textTertiary,
                  ),
                ),
                const Spacer(),
                InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: isLoading || isMutating ? null : () => onRefresh(),
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 6,
                    ),
                    decoration: BoxDecoration(
                      color: FilesFeatureScreen.surfaceMuted,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text(
                      copy.filesRefreshAction,
                      style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: FilesFeatureScreen.textSecondary,
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              directoryName,
              style: const TextStyle(
                fontSize: 17,
                height: 1.2,
                fontWeight: FontWeight.w600,
                letterSpacing: -0.2,
                color: FilesFeatureScreen.textPrimary,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              absolutePath,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: 13,
                height: 1.3,
                color: FilesFeatureScreen.textSecondary,
              ),
            ),
            const SizedBox(height: 10),
            Text(
              statsLabel,
              style: const TextStyle(
                fontSize: 13,
                height: 1.2,
                color: FilesFeatureScreen.textSecondary,
              ),
            ),
            const SizedBox(height: 12),
            Container(
              key: actionRowKey,
              padding: const EdgeInsets.only(top: 2),
              child: _LocationActionRow(
                newLabel: copy.filesNewAction,
                breadcrumbs: breadcrumbs,
                breadcrumbsEnabled: breadcrumbsEnabled,
                onBreadcrumbTap: onBreadcrumbTap,
                onCreateFolder: onCreateFolder,
                isBusy: isLoading || isMutating,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StickyLocationBar extends StatelessWidget {
  const _StickyLocationBar({
    super.key,
    required this.copy,
    required this.breadcrumbs,
    required this.breadcrumbsEnabled,
    required this.onBreadcrumbTap,
    required this.onCreateFolder,
    required this.isBusy,
  });

  final OpenCrayUiCopy copy;
  final List<_BreadcrumbSegment> breadcrumbs;
  final bool breadcrumbsEnabled;
  final ValueChanged<String> onBreadcrumbTap;
  final Future<void> Function() onCreateFolder;
  final bool isBusy;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: FilesFeatureScreen.surface.withValues(alpha: 0.96),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: OpenCrayColors.divider),
        boxShadow: OpenCrayShadows.floating,
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
        child: _LocationActionRow(
          isSticky: true,
          newLabel: copy.filesNewAction,
          breadcrumbs: breadcrumbs,
          breadcrumbsEnabled: breadcrumbsEnabled,
          onBreadcrumbTap: onBreadcrumbTap,
          onCreateFolder: onCreateFolder,
          isBusy: isBusy,
        ),
      ),
    );
  }
}

class _LocationActionRow extends StatelessWidget {
  const _LocationActionRow({
    required this.newLabel,
    required this.breadcrumbs,
    required this.breadcrumbsEnabled,
    required this.onBreadcrumbTap,
    required this.onCreateFolder,
    required this.isBusy,
    this.isSticky = false,
  });

  final String newLabel;
  final List<_BreadcrumbSegment> breadcrumbs;
  final bool breadcrumbsEnabled;
  final ValueChanged<String> onBreadcrumbTap;
  final Future<void> Function() onCreateFolder;
  final bool isBusy;
  final bool isSticky;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: _BreadcrumbTrail(
            breadcrumbs: breadcrumbs,
            onBreadcrumbTap: breadcrumbsEnabled ? onBreadcrumbTap : null,
          ),
        ),
        const SizedBox(width: 12),
        InkWell(
          key: isSticky
              ? const ValueKey<String>('files-sticky-new')
              : const ValueKey<String>('files-location-new'),
          borderRadius: BorderRadius.circular(12),
          onTap: isBusy ? null : () => onCreateFolder(),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(
                  CupertinoIcons.folder_badge_plus,
                  size: 18,
                  color: FilesFeatureScreen.accent,
                ),
                const SizedBox(width: 6),
                Text(
                  newLabel,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: FilesFeatureScreen.accent,
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _BreadcrumbTrail extends StatelessWidget {
  const _BreadcrumbTrail({
    required this.breadcrumbs,
    required this.onBreadcrumbTap,
  });

  final List<_BreadcrumbSegment> breadcrumbs;
  final ValueChanged<String>? onBreadcrumbTap;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          for (var index = 0; index < breadcrumbs.length; index++) ...[
            if (index > 0)
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 4),
                child: Text(
                  '/',
                  style: TextStyle(
                    fontSize: 13,
                    color: FilesFeatureScreen.textTertiary,
                  ),
                ),
              ),
            _BreadcrumbChip(
              segment: breadcrumbs[index],
              isCurrent: index == breadcrumbs.length - 1,
              onTap:
                  index == breadcrumbs.length - 1 ||
                      breadcrumbs[index].relativePath == null ||
                      onBreadcrumbTap == null
                  ? null
                  : () => onBreadcrumbTap!(breadcrumbs[index].relativePath!),
            ),
          ],
        ],
      ),
    );
  }
}

class _BreadcrumbChip extends StatelessWidget {
  const _BreadcrumbChip({
    required this.segment,
    required this.isCurrent,
    required this.onTap,
  });

  final _BreadcrumbSegment segment;
  final bool isCurrent;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final color = isCurrent
        ? FilesFeatureScreen.textPrimary
        : FilesFeatureScreen.textTertiary;
    return InkWell(
      key: segment.relativePath == null
          ? null
          : ValueKey<String>(
              'files-breadcrumb-${segment.relativePath!.isEmpty ? 'root' : segment.relativePath}',
            ),
      borderRadius: BorderRadius.circular(10),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 2),
        child: Text(
          segment.label,
          style: TextStyle(
            fontSize: 13,
            height: 1.2,
            fontWeight: isCurrent ? FontWeight.w700 : FontWeight.w500,
            color: color,
          ),
        ),
      ),
    );
  }
}

class _DirectoryCard extends StatelessWidget {
  const _DirectoryCard({
    required this.copy,
    required this.query,
    required this.isFiltered,
    required this.snapshot,
    required this.entries,
    required this.isLoading,
    required this.errorMessage,
    required this.isSelectionMode,
    required this.selectedPaths,
    required this.pendingTransfer,
    required this.onEntryTap,
    required this.onEntryLongPress,
  });

  final OpenCrayUiCopy copy;
  final String query;
  final bool isFiltered;
  final OpenCrayFilesSnapshot? snapshot;
  final List<OpenCrayFileTreeNodeSnapshot> entries;
  final bool isLoading;
  final String? errorMessage;
  final bool isSelectionMode;
  final Set<String> selectedPaths;
  final _PendingTransfer? pendingTransfer;
  final ValueChanged<OpenCrayFileTreeNodeSnapshot> onEntryTap;
  final ValueChanged<OpenCrayFileTreeNodeSnapshot> onEntryLongPress;

  @override
  Widget build(BuildContext context) {
    if (isLoading && snapshot == null) {
      return const SliverPadding(
        padding: EdgeInsets.symmetric(horizontal: 20),
        sliver: SliverToBoxAdapter(
          child: OpenCrayStateCard(
            key: ValueKey<String>('files-state-loading'),
            isLoading: true,
            padding: EdgeInsets.all(24),
          ),
        ),
      );
    }

    if (errorMessage != null && snapshot == null) {
      return SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        sliver: SliverToBoxAdapter(
          child: OpenCrayStateCard(
            key: const ValueKey<String>('files-state-error'),
            tone: OpenCrayStateTone.danger,
            leadingIcon: Icons.error_outline,
            title: copy.filesLoadFailed,
            body: errorMessage!,
          ),
        ),
      );
    }

    if (snapshot == null || entries.isEmpty) {
      final hasQuery = query.trim().isNotEmpty;
      return SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        sliver: SliverToBoxAdapter(
          child: OpenCrayStateCard(
            key: ValueKey<String>(
              hasQuery ? 'files-state-filtered-empty' : 'files-state-empty',
            ),
            tone: hasQuery
                ? OpenCrayStateTone.accent
                : OpenCrayStateTone.neutral,
            leadingIcon: hasQuery
                ? Icons.search_off
                : Icons.folder_open_outlined,
            title: hasQuery
                ? copy.filesNoMatchesTitle
                : copy.filesFolderEmptyTitle,
            body: hasQuery
                ? copy.filesNoMatchesBody(query)
                : copy.filesFolderEmptyBody,
          ),
        ),
      );
    }

    final int headerChildCount = isFiltered ? 2 : 0;
    final int entryChildCount = entries.length * 2 - 1;
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      sliver: DecoratedSliver(
        decoration: BoxDecoration(
          color: FilesFeatureScreen.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: OpenCrayColors.divider),
          boxShadow: OpenCrayShadows.card,
        ),
        sliver: SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
          sliver: SliverList(
            delegate: SliverChildBuilderDelegate(
              (context, index) {
                if (isFiltered && index == 0) {
                  return _DirectoryFilterStatus(
                    label: copy.filesFilteredStatus(
                      query.trim(),
                      entries.length,
                    ),
                  );
                }
                if (isFiltered && index == 1) {
                  return const Divider(
                    height: 16,
                    color: FilesFeatureScreen.divider,
                  );
                }
                final adjustedIndex = index - headerChildCount;
                if (adjustedIndex.isOdd) {
                  return const Divider(
                    height: 1,
                    color: FilesFeatureScreen.divider,
                  );
                }
                final entry = entries[adjustedIndex ~/ 2];
                return _DirectoryEntryTile(
                  entry: entry,
                  copy: copy,
                  isSelectionMode: isSelectionMode,
                  isSelected: selectedPaths.contains(entry.relativePath),
                  isFaded:
                      pendingTransfer?.move == true &&
                      pendingTransfer!.sourceRelativePaths.contains(
                        entry.relativePath,
                      ),
                  onTap: () => onEntryTap(entry),
                  onLongPress: () => onEntryLongPress(entry),
                );
              },
              childCount: headerChildCount + entryChildCount,
              addAutomaticKeepAlives: false,
              addSemanticIndexes: false,
            ),
          ),
        ),
      ),
    );
  }
}

class _DirectoryFilterStatus extends StatelessWidget {
  const _DirectoryFilterStatus({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 2),
      child: Row(
        key: const ValueKey<String>('files-filter-status'),
        children: [
          const Icon(
            CupertinoIcons.line_horizontal_3_decrease_circle,
            size: 16,
            color: FilesFeatureScreen.accent,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: 12,
                height: 1.2,
                fontWeight: FontWeight.w600,
                color: FilesFeatureScreen.textSecondary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _DirectoryEntryTile extends StatelessWidget {
  const _DirectoryEntryTile({
    required this.entry,
    required this.copy,
    required this.isSelectionMode,
    required this.isSelected,
    required this.isFaded,
    required this.onTap,
    required this.onLongPress,
  });

  final OpenCrayFileTreeNodeSnapshot entry;
  final OpenCrayUiCopy copy;
  final bool isSelectionMode;
  final bool isSelected;
  final bool isFaded;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    final icon = entry.isDirectory
        ? CupertinoIcons.folder_fill
        : CupertinoIcons.doc_text;
    final iconColor = entry.isDirectory
        ? FilesFeatureScreen.accent
        : FilesFeatureScreen.textTertiary;
    final metaText = entry.isDirectory
        ? copy.filesDirectoryItemCount(entry.childCount)
        : _formatBytes(entry.sizeBytes ?? 0);

    return AnimatedOpacity(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      opacity: isFaded ? 0.38 : 1,
      child: InkWell(
        key: ValueKey<String>('files-row-${entry.relativePath}'),
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(14),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 10),
          decoration: BoxDecoration(
            color: isSelected
                ? FilesFeatureScreen.surfacePressed
                : Colors.transparent,
            borderRadius: BorderRadius.circular(14),
          ),
          child: Row(
            children: [
              if (isSelectionMode) ...[
                Icon(
                  isSelected
                      ? CupertinoIcons.check_mark_circled_solid
                      : CupertinoIcons.circle,
                  size: 20,
                  color: isSelected
                      ? FilesFeatureScreen.accent
                      : FilesFeatureScreen.textTertiary,
                ),
                const SizedBox(width: 10),
              ],
              Icon(icon, size: 20, color: iconColor),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      entry.name,
                      style: const TextStyle(
                        fontSize: 15,
                        height: 1.25,
                        fontWeight: FontWeight.w500,
                        color: FilesFeatureScreen.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      metaText,
                      style: const TextStyle(
                        fontSize: 12,
                        color: FilesFeatureScreen.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
              if (entry.isDirectory && !isSelectionMode)
                const Icon(
                  CupertinoIcons.chevron_right,
                  size: 16,
                  color: FilesFeatureScreen.textTertiary,
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SelectionToolbar extends StatelessWidget {
  const _SelectionToolbar({
    required this.copy,
    required this.isVisible,
    required this.isPendingTransfer,
    required this.operationState,
    required this.canShare,
    required this.canMove,
    required this.canCopy,
    required this.canPaste,
    required this.canRename,
    required this.canDelete,
    required this.onShare,
    required this.onMove,
    required this.onCopyOrPaste,
    required this.onRename,
    required this.onDelete,
  });

  final OpenCrayUiCopy copy;
  final bool isVisible;
  final bool isPendingTransfer;
  final _FilesOperationState? operationState;
  final bool canShare;
  final bool canMove;
  final bool canCopy;
  final bool canPaste;
  final bool canRename;
  final bool canDelete;
  final VoidCallback? onShare;
  final VoidCallback? onMove;
  final VoidCallback? onCopyOrPaste;
  final VoidCallback? onRename;
  final VoidCallback? onDelete;

  @override
  Widget build(BuildContext context) {
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.panel,
    );
    final bool reduce = OpenCrayMotion.reduce(context);
    return AnimatedSlide(
      offset: reduce || isVisible ? Offset.zero : const Offset(0, 1),
      duration: duration,
      curve: isVisible ? OpenCrayMotion.enter : OpenCrayMotion.exit,
      child: AnimatedOpacity(
        opacity: isVisible ? 1 : 0,
        duration: duration,
        curve: isVisible ? OpenCrayMotion.enter : OpenCrayMotion.exit,
        child: DecoratedBox(
          key: isVisible
              ? const ValueKey<String>('files-selection-toolbar')
              : null,
          decoration: const BoxDecoration(
            color: FilesFeatureScreen.surface,
            borderRadius: BorderRadius.vertical(top: Radius.circular(18)),
            border: Border(top: BorderSide(color: FilesFeatureScreen.divider)),
            boxShadow: [
              BoxShadow(
                color: Color(0x12101828),
                blurRadius: 18,
                offset: Offset(0, -6),
              ),
            ],
          ),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 8),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (operationState != null) ...[
                  _FilesOperationStatusStrip(
                    copy: copy,
                    state: operationState!,
                  ),
                  const SizedBox(height: 8),
                ],
                Row(
                  children: [
                    Expanded(
                      child: _SelectionActionGroup(
                        semanticsLabel: copy.filesSelectionStandardActions,
                        child: Row(
                          children: [
                            Expanded(
                              child: _ToolbarItem(
                                key: const ValueKey<String>(
                                  'files-toolbar-action-share',
                                ),
                                icon: CupertinoIcons.share,
                                label: copy.filesShareAction,
                                enabled: canShare,
                                onTap: onShare,
                              ),
                            ),
                            Expanded(
                              child: _ToolbarItem(
                                key: const ValueKey<String>(
                                  'files-toolbar-action-move',
                                ),
                                icon: CupertinoIcons.folder,
                                label: copy.filesMoveAction,
                                enabled: canMove,
                                onTap: onMove,
                              ),
                            ),
                            Expanded(
                              child: _ToolbarItem(
                                key: ValueKey<String>(
                                  isPendingTransfer
                                      ? 'files-toolbar-action-paste'
                                      : 'files-toolbar-action-copy',
                                ),
                                icon: isPendingTransfer
                                    ? CupertinoIcons.doc_on_clipboard
                                    : CupertinoIcons.doc_on_doc,
                                label: isPendingTransfer
                                    ? copy.filesPasteAction
                                    : copy.filesCopyAction,
                                enabled: isPendingTransfer ? canPaste : canCopy,
                                onTap: onCopyOrPaste,
                              ),
                            ),
                            Expanded(
                              child: _ToolbarItem(
                                key: const ValueKey<String>(
                                  'files-toolbar-action-rename',
                                ),
                                icon: CupertinoIcons.pencil,
                                label: copy.filesRenameAction,
                                enabled: canRename,
                                onTap: onRename,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    _SelectionDangerAction(
                      semanticsLabel: copy.filesSelectionDangerActions,
                      child: _ToolbarItem(
                        key: const ValueKey<String>(
                          'files-toolbar-action-delete',
                        ),
                        icon: CupertinoIcons.delete,
                        label: copy.filesDeleteAction,
                        enabled: canDelete,
                        accentColor: FilesFeatureScreen.danger,
                        onTap: onDelete,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _FilesOperationStatusStrip extends StatelessWidget {
  const _FilesOperationStatusStrip({required this.copy, required this.state});

  final OpenCrayUiCopy copy;
  final _FilesOperationState state;

  @override
  Widget build(BuildContext context) {
    final bool isFailed = state == _FilesOperationState.failed;
    final bool isPending =
        state == _FilesOperationState.pasting ||
        state == _FilesOperationState.deleting;
    final Color textColor = isFailed
        ? FilesFeatureScreen.danger
        : state == _FilesOperationState.done
        ? OpenCrayColors.success
        : FilesFeatureScreen.textSecondary;
    final Color surfaceColor = isFailed
        ? OpenCrayColors.dangerTint
        : state == _FilesOperationState.done
        ? OpenCrayColors.successTint
        : FilesFeatureScreen.surfaceMuted;
    return AnimatedContainer(
      key: ValueKey<String>('files-operation-status-${state.name}'),
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: textColor.withValues(alpha: 0.14)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (isPending) ...[
            SizedBox.square(
              dimension: 12,
              child: CircularProgressIndicator(
                strokeWidth: 1.5,
                color: textColor,
              ),
            ),
            const SizedBox(width: 7),
          ],
          Text(
            _filesOperationLabel(copy, state),
            style: TextStyle(
              fontSize: 12,
              height: 1.1,
              fontWeight: FontWeight.w700,
              color: textColor,
            ),
          ),
        ],
      ),
    );
  }
}

String _filesOperationLabel(OpenCrayUiCopy copy, _FilesOperationState state) =>
    switch (state) {
      _FilesOperationState.copyReady => copy.filesOperationPreparingCopy,
      _FilesOperationState.moveReady => copy.filesOperationPreparingMove,
      _FilesOperationState.pasting => copy.filesOperationPasting,
      _FilesOperationState.deleting => copy.filesOperationDeleting,
      _FilesOperationState.done => copy.filesOperationDone,
      _FilesOperationState.failed => copy.filesOperationFailed,
    };

class _SelectionActionGroup extends StatelessWidget {
  const _SelectionActionGroup({
    required this.semanticsLabel,
    required this.child,
  });

  final String semanticsLabel;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: semanticsLabel,
      container: true,
      child: DecoratedBox(
        key: const ValueKey<String>('files-toolbar-standard-group'),
        decoration: BoxDecoration(
          color: FilesFeatureScreen.surfaceMuted,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
          child: child,
        ),
      ),
    );
  }
}

class _SelectionDangerAction extends StatelessWidget {
  const _SelectionDangerAction({
    required this.semanticsLabel,
    required this.child,
  });

  final String semanticsLabel;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: semanticsLabel,
      container: true,
      child: DecoratedBox(
        key: const ValueKey<String>('files-toolbar-danger-group'),
        decoration: BoxDecoration(
          color: FilesFeatureScreen.danger.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: FilesFeatureScreen.danger.withValues(alpha: 0.18),
          ),
        ),
        child: SizedBox(width: 64, child: child),
      ),
    );
  }
}

class _ToolbarItem extends StatelessWidget {
  const _ToolbarItem({
    super.key,
    required this.icon,
    required this.label,
    required this.enabled,
    required this.onTap,
    this.accentColor = FilesFeatureScreen.textSecondary,
  });

  final IconData icon;
  final String label;
  final bool enabled;
  final VoidCallback? onTap;
  final Color accentColor;

  @override
  Widget build(BuildContext context) {
    final color = enabled
        ? accentColor
        : FilesFeatureScreen.textTertiary.withValues(alpha: 0.55);
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: enabled ? onTap : null,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 20, color: color),
            const SizedBox(height: 4),
            Text(
              label,
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: color,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
