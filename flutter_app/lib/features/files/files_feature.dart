import 'package:flutter/material.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/copy/opencray_ui_copy.dart';
import '../../core/models/opencray_files_snapshot.dart';

class FilesFeatureScreen extends StatefulWidget {
  const FilesFeatureScreen({
    super.key,
    required this.bridge,
    required this.copy,
  });

  final OpenCrayHostBridge bridge;
  final OpenCrayUiCopy copy;

  static const _shellBackground = Color(0xFFF5F5F7);
  static const _surface = Colors.white;
  static const _surfaceMuted = Color(0xFFF1F2F6);
  static const _textPrimary = Color(0xFF111111);
  static const _textSecondary = Color(0xFF6E6E73);
  static const _textTertiary = Color(0xFF8E8E93);
  static const _accent = Color(0xFF007AFF);
  static const _divider = Color(0xFFE5E5EA);

  @override
  State<FilesFeatureScreen> createState() => _FilesFeatureScreenState();
}

class _FilesFeatureScreenState extends State<FilesFeatureScreen> {
  late final TextEditingController _searchController = TextEditingController()
    ..addListener(_onQueryChanged);

  OpenCrayFilesSnapshot? _snapshot;
  String _query = '';
  String? _errorMessage;
  bool _isLoading = true;
  final Set<String> _expandedPaths = <String>{};

  @override
  void initState() {
    super.initState();
    _loadSnapshot();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    final visibleNodes = snapshot == null
        ? const <OpenCrayFileTreeNodeSnapshot>[]
        : _visibleNodes(snapshot.children);

    return ColoredBox(
      color: FilesFeatureScreen._shellBackground,
      child: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                widget.copy.filesTitle,
                style: const TextStyle(
                  fontSize: 30,
                  height: 1.05,
                  fontWeight: FontWeight.w600,
                  color: FilesFeatureScreen._textPrimary,
                ),
              ),
              const SizedBox(height: 16),
              _SearchBar(
                controller: _searchController,
                hint: widget.copy.filesSearchHint,
              ),
              const SizedBox(height: 12),
              _LocationCard(
                copy: widget.copy,
                snapshot: snapshot,
                isLoading: _isLoading,
                onRefresh: _loadSnapshot,
              ),
              const SizedBox(height: 12),
              _TreeCard(
                copy: widget.copy,
                isLoading: _isLoading,
                errorMessage: _errorMessage,
                query: _query,
                snapshot: snapshot,
                visibleNodes: visibleNodes,
                expandedPaths: _expandedPaths,
                onRefresh: _loadSnapshot,
                onToggleDirectory: _toggleDirectory,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _loadSnapshot() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });
    try {
      final snapshot = await widget.bridge.loadFilesSnapshot();
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _isLoading = false;
        _errorMessage = null;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
        _errorMessage = '$error';
      });
    }
  }

  void _onQueryChanged() {
    setState(() {
      _query = _searchController.text;
    });
  }

  void _toggleDirectory(OpenCrayFileTreeNodeSnapshot node) {
    if (_query.trim().isNotEmpty) {
      return;
    }
    setState(() {
      if (_expandedPaths.contains(node.relativePath)) {
        _expandedPaths.remove(node.relativePath);
      } else {
        _expandedPaths.add(node.relativePath);
      }
    });
  }

  List<OpenCrayFileTreeNodeSnapshot> _visibleNodes(
    List<OpenCrayFileTreeNodeSnapshot> nodes,
  ) {
    final query = _query.trim().toLowerCase();
    if (query.isEmpty) {
      return nodes;
    }
    return nodes
        .map((node) => _filterNode(node, query))
        .whereType<OpenCrayFileTreeNodeSnapshot>()
        .toList(growable: false);
  }

  OpenCrayFileTreeNodeSnapshot? _filterNode(
    OpenCrayFileTreeNodeSnapshot node,
    String query,
  ) {
    final matchesSelf =
        node.name.toLowerCase().contains(query) ||
        node.relativePath.toLowerCase().contains(query);
    if (!node.isDirectory) {
      return matchesSelf ? node : null;
    }

    if (matchesSelf) {
      return node;
    }

    final filteredChildren = node.children
        .map((child) => _filterNode(child, query))
        .whereType<OpenCrayFileTreeNodeSnapshot>()
        .toList(growable: false);
    if (filteredChildren.isEmpty) {
      return null;
    }
    return node.copyWith(children: filteredChildren);
  }
}

class _SearchBar extends StatelessWidget {
  const _SearchBar({required this.controller, required this.hint});

  final TextEditingController controller;
  final String hint;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
        child: Row(
          children: [
            const Icon(
              Icons.search_rounded,
              size: 18,
              color: FilesFeatureScreen._textTertiary,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: TextField(
                controller: controller,
                decoration: InputDecoration(
                  border: InputBorder.none,
                  hintText: hint,
                  hintStyle: const TextStyle(
                    fontSize: 14,
                    height: 1.2,
                    color: FilesFeatureScreen._textTertiary,
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
    required this.isLoading,
    required this.onRefresh,
  });

  final OpenCrayUiCopy copy;
  final OpenCrayFilesSnapshot? snapshot;
  final bool isLoading;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) {
    final path = snapshot?.rootPath ?? copy.filesLocationPath;
    final rootName = snapshot?.rootName ?? copy.filesLocationTitle;
    final summary = snapshot == null
        ? copy.filesLocationItemCount
        : copy.filesWorkspaceTotals(
            snapshot!.directoryCount,
            snapshot!.fileCount,
          );
    final availableSpace = snapshot == null
        ? copy.filesLocationAvailableSpace
        : copy.filesAvailableSpace(_formatBytes(snapshot!.availableBytes));

    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
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
                    color: FilesFeatureScreen._textTertiary,
                  ),
                ),
                const Spacer(),
                InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: isLoading
                      ? null
                      : () {
                          onRefresh();
                        },
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 6,
                    ),
                    decoration: BoxDecoration(
                      color: FilesFeatureScreen._surfaceMuted,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text(
                      copy.filesRefreshAction,
                      style: const TextStyle(
                        fontSize: 12,
                        height: 1.1,
                        fontWeight: FontWeight.w600,
                        color: FilesFeatureScreen._textSecondary,
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              rootName,
              style: const TextStyle(
                fontSize: 17,
                height: 1.2,
                fontWeight: FontWeight.w600,
                color: FilesFeatureScreen._textPrimary,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              path,
              style: const TextStyle(
                fontSize: 13,
                height: 1.3,
                color: FilesFeatureScreen._textSecondary,
              ),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: [
                Text(
                  summary,
                  style: const TextStyle(
                    fontSize: 13,
                    height: 1.2,
                    color: FilesFeatureScreen._textSecondary,
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: FilesFeatureScreen._surfaceMuted,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    availableSpace,
                    style: const TextStyle(
                      fontSize: 12,
                      height: 1.1,
                      fontWeight: FontWeight.w500,
                      color: FilesFeatureScreen._textSecondary,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _TreeCard extends StatelessWidget {
  const _TreeCard({
    required this.copy,
    required this.isLoading,
    required this.errorMessage,
    required this.query,
    required this.snapshot,
    required this.visibleNodes,
    required this.expandedPaths,
    required this.onRefresh,
    required this.onToggleDirectory,
  });

  final OpenCrayUiCopy copy;
  final bool isLoading;
  final String? errorMessage;
  final String query;
  final OpenCrayFilesSnapshot? snapshot;
  final List<OpenCrayFileTreeNodeSnapshot> visibleNodes;
  final Set<String> expandedPaths;
  final Future<void> Function() onRefresh;
  final ValueChanged<OpenCrayFileTreeNodeSnapshot> onToggleDirectory;

  @override
  Widget build(BuildContext context) {
    if (isLoading && snapshot == null) {
      return const _StateCard(
        child: Padding(
          padding: EdgeInsets.all(20),
          child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      );
    }

    if (errorMessage != null && snapshot == null) {
      return _StateCard(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                copy.filesLoadFailed,
                style: const TextStyle(
                  fontSize: 16,
                  height: 1.2,
                  fontWeight: FontWeight.w600,
                  color: FilesFeatureScreen._textPrimary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                errorMessage!,
                style: const TextStyle(
                  fontSize: 14,
                  height: 1.35,
                  color: FilesFeatureScreen._textSecondary,
                ),
              ),
              const SizedBox(height: 14),
              _ActionChip(label: copy.filesRefreshAction, onTap: onRefresh),
            ],
          ),
        ),
      );
    }

    if (snapshot == null || snapshot!.children.isEmpty) {
      return _StateCard(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                copy.filesEmptyTitle,
                style: const TextStyle(
                  fontSize: 16,
                  height: 1.2,
                  fontWeight: FontWeight.w600,
                  color: FilesFeatureScreen._textPrimary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                copy.filesEmptyBody,
                style: const TextStyle(
                  fontSize: 14,
                  height: 1.35,
                  color: FilesFeatureScreen._textSecondary,
                ),
              ),
            ],
          ),
        ),
      );
    }

    if (visibleNodes.isEmpty) {
      return _StateCard(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                copy.filesNoMatchesTitle,
                style: const TextStyle(
                  fontSize: 16,
                  height: 1.2,
                  fontWeight: FontWeight.w600,
                  color: FilesFeatureScreen._textPrimary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                copy.filesNoMatchesBody(query),
                style: const TextStyle(
                  fontSize: 14,
                  height: 1.35,
                  color: FilesFeatureScreen._textSecondary,
                ),
              ),
            ],
          ),
        ),
      );
    }

    return DecoratedBox(
      decoration: BoxDecoration(
        color: FilesFeatureScreen._surface,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(
                  copy.filesTreeTitle,
                  style: const TextStyle(
                    fontSize: 16,
                    height: 1.2,
                    fontWeight: FontWeight.w600,
                    color: FilesFeatureScreen._textPrimary,
                  ),
                ),
                const Spacer(),
                Text(
                  copy.filesItemsShown(visibleNodes.length),
                  style: const TextStyle(
                    fontSize: 12,
                    height: 1.2,
                    color: FilesFeatureScreen._textTertiary,
                  ),
                ),
              ],
            ),
            if (snapshot!.isTruncated) ...[
              const SizedBox(height: 6),
              Text(
                copy.filesTreeTruncated,
                style: const TextStyle(
                  fontSize: 12,
                  height: 1.3,
                  color: FilesFeatureScreen._textSecondary,
                ),
              ),
            ],
            const SizedBox(height: 12),
            for (var index = 0; index < visibleNodes.length; index++) ...[
              _TreeNodeTile(
                copy: copy,
                node: visibleNodes[index],
                depth: 0,
                alwaysExpanded: query.trim().isNotEmpty,
                expandedPaths: expandedPaths,
                onToggleDirectory: onToggleDirectory,
              ),
              if (index < visibleNodes.length - 1)
                const Divider(height: 1, color: FilesFeatureScreen._divider),
            ],
          ],
        ),
      ),
    );
  }
}

class _TreeNodeTile extends StatelessWidget {
  const _TreeNodeTile({
    required this.copy,
    required this.node,
    required this.depth,
    required this.alwaysExpanded,
    required this.expandedPaths,
    required this.onToggleDirectory,
  });

  final OpenCrayUiCopy copy;
  final OpenCrayFileTreeNodeSnapshot node;
  final int depth;
  final bool alwaysExpanded;
  final Set<String> expandedPaths;
  final ValueChanged<OpenCrayFileTreeNodeSnapshot> onToggleDirectory;

  @override
  Widget build(BuildContext context) {
    final isExpanded =
        alwaysExpanded ||
        (!node.isDirectory ? false : expandedPaths.contains(node.relativePath));
    final leadingIcon = node.isDirectory
        ? (isExpanded ? Icons.folder_open_rounded : Icons.folder_rounded)
        : Icons.description_outlined;
    final leadingColor = node.isDirectory
        ? FilesFeatureScreen._accent
        : FilesFeatureScreen._textTertiary;
    final metaText = node.isDirectory
        ? copy.filesDirectoryItemCount(node.childCount)
        : _formatBytes(node.sizeBytes ?? 0);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        InkWell(
          onTap: node.isDirectory ? () => onToggleDirectory(node) : null,
          borderRadius: BorderRadius.circular(12),
          child: Padding(
            padding: EdgeInsets.fromLTRB(4 + depth * 14, 10, 4, 10),
            child: Row(
              children: [
                Icon(leadingIcon, color: leadingColor, size: 20),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        node.name,
                        style: const TextStyle(
                          fontSize: 15,
                          height: 1.25,
                          fontWeight: FontWeight.w500,
                          color: FilesFeatureScreen._textPrimary,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        metaText,
                        style: const TextStyle(
                          fontSize: 12,
                          height: 1.2,
                          color: FilesFeatureScreen._textSecondary,
                        ),
                      ),
                    ],
                  ),
                ),
                if (node.isDirectory)
                  Icon(
                    isExpanded
                        ? Icons.expand_more_rounded
                        : Icons.chevron_right_rounded,
                    size: 18,
                    color: FilesFeatureScreen._textTertiary,
                  ),
              ],
            ),
          ),
        ),
        if (node.isDirectory && isExpanded && node.children.isNotEmpty) ...[
          for (var index = 0; index < node.children.length; index++) ...[
            _TreeNodeTile(
              copy: copy,
              node: node.children[index],
              depth: depth + 1,
              alwaysExpanded: alwaysExpanded,
              expandedPaths: expandedPaths,
              onToggleDirectory: onToggleDirectory,
            ),
            if (index < node.children.length - 1)
              const Divider(height: 1, color: FilesFeatureScreen._divider),
          ],
          if (node.isTruncated)
            Padding(
              padding: EdgeInsets.fromLTRB(18 + (depth + 1) * 14, 0, 4, 10),
              child: Text(
                copy.filesTreeTruncated,
                style: const TextStyle(
                  fontSize: 12,
                  height: 1.3,
                  color: FilesFeatureScreen._textTertiary,
                ),
              ),
            ),
        ],
      ],
    );
  }
}

class _StateCard extends StatelessWidget {
  const _StateCard({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: FilesFeatureScreen._surface,
        borderRadius: BorderRadius.circular(16),
      ),
      child: child,
    );
  }
}

class _ActionChip extends StatelessWidget {
  const _ActionChip({required this.label, required this.onTap});

  final String label;
  final Future<void> Function() onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(999),
      onTap: () {
        onTap();
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: const Color(0xFFEEF5FF),
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text(
          label,
          style: const TextStyle(
            fontSize: 12,
            height: 1.1,
            fontWeight: FontWeight.w600,
            color: FilesFeatureScreen._accent,
          ),
        ),
      ),
    );
  }
}

String _formatBytes(int bytes) {
  if (bytes <= 0) {
    return '0 B';
  }
  const units = <String>['B', 'KB', 'MB', 'GB', 'TB'];
  var value = bytes.toDouble();
  var unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  final formatted = value >= 10 || unitIndex == 0
      ? value.toStringAsFixed(0)
      : value.toStringAsFixed(1);
  return '$formatted ${units[unitIndex]}';
}
