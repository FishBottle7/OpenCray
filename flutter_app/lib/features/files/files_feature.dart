import 'dart:async';
import 'dart:ui';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:flutter/services.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/copy/opencray_ui_copy.dart';
import '../../core/models/opencray_file_image_preview.dart';
import '../../core/models/opencray_file_text_preview.dart';
import '../../core/models/opencray_files_snapshot.dart';
import '../../core/models/opencray_workspace_text_document.dart';
import '../../core/design/opencray_motion.dart';
import '../../core/widgets/opencray_image_bytes_view.dart';
import '../../core/widgets/opencray_markdown.dart';

class FilesFeatureController {
  bool Function()? _backPressHandler;

  bool consumeBackPress() => _backPressHandler?.call() ?? false;
}

enum _NewEntryIntentKind { folder, textFile, unsupportedFile }

class _NewEntryIntent {
  const _NewEntryIntent({
    required this.name,
    required this.kind,
    this.errorText,
  });

  final String name;
  final _NewEntryIntentKind kind;
  final String? errorText;

  bool get canSubmit =>
      name.isNotEmpty && kind != _NewEntryIntentKind.unsupportedFile;
}

enum _TextPreviewDialogResult { edit }

const double _fallbackStickyBrowseBarTriggerScrollOffset = 200;

class FilesFeatureScreen extends StatefulWidget {
  const FilesFeatureScreen({
    super.key,
    required this.bridge,
    required this.copy,
    this.isTabActive = true,
    this.controller,
    this.autoRefreshPollInterval = const Duration(seconds: 2),
  });

  final OpenCrayHostBridge bridge;
  final OpenCrayUiCopy copy;
  final bool isTabActive;
  final FilesFeatureController? controller;
  final Duration autoRefreshPollInterval;

  static const Color shellBackground = Color(0xFFF5F5F7);
  static const Color surface = Colors.white;
  static const Color surfaceMuted = Color(0xFFF1F2F6);
  static const Color surfacePressed = Color(0xFFE9F1FF);
  static const Color textPrimary = Color(0xFF111111);
  static const Color textSecondary = Color(0xFF6E6E73);
  static const Color textTertiary = Color(0xFF8E8E93);
  static const Color accent = Color(0xFF007AFF);
  static const Color danger = Color(0xFFFF3B30);
  static const Color divider = Color(0xFFE5E5EA);

  @override
  State<FilesFeatureScreen> createState() => _FilesFeatureScreenState();
}

class _FilesFeatureScreenState extends State<FilesFeatureScreen>
    with WidgetsBindingObserver {
  late final TextEditingController _searchController = TextEditingController()
    ..addListener(_handleQueryChanged);
  late final ScrollController _scrollController = ScrollController()
    ..addListener(_handleScroll);
  final GlobalKey _locationActionRowKey = GlobalKey();

  OpenCrayFilesSnapshot? _snapshot;
  String _currentDirectoryPath = '';
  String _query = '';
  String? _errorMessage;
  bool _isLoading = true;
  bool _isMutating = false;
  bool _isSelectionMode = false;
  bool _showStickyBrowseBar = false;
  double? _stickyBrowseBarTriggerScrollOffset;
  Set<String> _selectedPaths = <String>{};
  _PendingTransfer? _pendingTransfer;
  Timer? _autoRefreshTimer;
  late AppLifecycleState _appLifecycleState;
  bool _isSnapshotLoadInFlight = false;
  bool _hasQueuedSnapshotLoad = false;
  bool _queuedSnapshotLoadShowBusyIndicator = false;

  bool get _hasPendingTransfer => _pendingTransfer != null;
  bool get _handlesBackPress => _isSelectionMode || _hasPendingTransfer;
  bool get _showsSelectionToolbar => _isSelectionMode || _hasPendingTransfer;

  @override
  void initState() {
    super.initState();
    _appLifecycleState =
        WidgetsBinding.instance.lifecycleState ?? AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
    widget.controller?._backPressHandler = _consumeBackPress;
    unawaited(_loadSnapshot());
    _syncAutoRefreshTimer();
  }

  @override
  void didUpdateWidget(covariant FilesFeatureScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller?._backPressHandler = null;
      widget.controller?._backPressHandler = _consumeBackPress;
    }
    if (oldWidget.isTabActive &&
        !widget.isTabActive &&
        (_isSelectionMode || _hasPendingTransfer)) {
      setState(() {
        _isSelectionMode = false;
        _selectedPaths = <String>{};
        _pendingTransfer = null;
      });
    }
    if (!oldWidget.isTabActive &&
        widget.isTabActive &&
        _appLifecycleState == AppLifecycleState.resumed) {
      _scheduleSilentRefresh();
    }
    if (oldWidget.isTabActive != widget.isTabActive ||
        oldWidget.autoRefreshPollInterval != widget.autoRefreshPollInterval) {
      _syncAutoRefreshTimer();
    }
  }

  @override
  void dispose() {
    _autoRefreshTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    widget.controller?._backPressHandler = null;
    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _appLifecycleState = state;
    _syncAutoRefreshTimer();
    if (state == AppLifecycleState.resumed && widget.isTabActive) {
      _scheduleSilentRefresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    _scheduleStickyBarSync();
    final snapshot = _snapshot;
    final currentEntries = snapshot == null
        ? const <OpenCrayFileTreeNodeSnapshot>[]
        : _visibleEntries(snapshot);

    return PopScope<void>(
      canPop: !_handlesBackPress,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) {
          return;
        }
        _consumeBackPress();
      },
      child: ColoredBox(
        color: FilesFeatureScreen.shellBackground,
        child: SafeArea(
          bottom: false,
          child: Stack(
            fit: StackFit.expand,
            children: [
              AbsorbPointer(
                absorbing: _isMutating,
                child: CustomScrollView(
                  key: const ValueKey<String>('files-scroll-view'),
                  controller: _scrollController,
                  slivers: [
                    SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            _TitleRow(
                              copy: widget.copy,
                              isSelectionMode: _isSelectionMode,
                              selectedCount: _selectedPaths.length,
                              onDone: _isSelectionMode
                                  ? _exitSelectionMode
                                  : null,
                            ),
                            const SizedBox(height: 16),
                            _SearchBar(
                              controller: _searchController,
                              hint: widget.copy.filesSearchHint,
                              clearLabel: widget.copy.filesSearchClearAction,
                            ),
                            const SizedBox(height: 12),
                            _LocationCard(
                              copy: widget.copy,
                              snapshot: snapshot,
                              directoryName: _currentDirectoryName(snapshot),
                              absolutePath: _currentAbsolutePath(snapshot),
                              visibleItemCount: currentEntries.length,
                              isLoading: _isLoading,
                              isMutating: _isMutating,
                              actionRowKey: _locationActionRowKey,
                              breadcrumbs: _visibleBreadcrumbs(),
                              breadcrumbsEnabled: !_isSelectionMode,
                              onRefresh: _handleManualRefresh,
                              onCreateFolder: _handleCreateFolder,
                              onBreadcrumbTap: _handleBreadcrumbTap,
                            ),
                            const SizedBox(height: 12),
                          ],
                        ),
                      ),
                    ),
                    _DirectoryCard(
                      copy: widget.copy,
                      query: _query,
                      isFiltered: _query.trim().isNotEmpty,
                      snapshot: snapshot,
                      entries: currentEntries,
                      isLoading: _isLoading,
                      errorMessage: _errorMessage,
                      isSelectionMode: _isSelectionMode,
                      selectedPaths: _selectedPaths,
                      pendingTransfer: _pendingTransfer,
                      onEntryTap: _handleEntryTap,
                      onEntryLongPress: _handleEntryLongPress,
                    ),
                    SliverToBoxAdapter(
                      child: SizedBox(
                        height: _showsSelectionToolbar ? 118 : 28,
                      ),
                    ),
                  ],
                ),
              ),
              if (_showStickyBrowseBar && snapshot != null)
                Positioned(
                  top: MediaQuery.of(context).padding.top + 6,
                  left: 20,
                  right: 20,
                  child: _StickyLocationBar(
                    key: const ValueKey<String>('files-sticky-bar'),
                    copy: widget.copy,
                    breadcrumbs: _visibleBreadcrumbs(),
                    breadcrumbsEnabled: !_isSelectionMode,
                    onBreadcrumbTap: _handleBreadcrumbTap,
                    onCreateFolder: _handleCreateFolder,
                    isBusy: _isLoading || _isMutating,
                  ),
                ),
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: IgnorePointer(
                  ignoring: !_showsSelectionToolbar,
                  child: _SelectionToolbar(
                    copy: widget.copy,
                    isVisible: _showsSelectionToolbar,
                    isPendingTransfer: _hasPendingTransfer,
                    canShare: _isSelectionMode && _selectedPaths.isNotEmpty,
                    canMove: _isSelectionMode && _selectedPaths.isNotEmpty,
                    canCopy: _isSelectionMode && _selectedPaths.isNotEmpty,
                    canPaste: _hasPendingTransfer,
                    canRename: _isSelectionMode && _selectedPaths.length == 1,
                    canDelete: _isSelectionMode && _selectedPaths.isNotEmpty,
                    onShare: _handleShare,
                    onMove: _isSelectionMode
                        ? () => _handleStartTransfer(true)
                        : null,
                    onCopyOrPaste: _hasPendingTransfer
                        ? _handlePasteTransfer
                        : (_isSelectionMode
                              ? () => _handleStartTransfer(false)
                              : null),
                    onRename: _isSelectionMode && _selectedPaths.length == 1
                        ? _handleRename
                        : null,
                    onDelete: _isSelectionMode && _selectedPaths.isNotEmpty
                        ? _handleDelete
                        : null,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _handleManualRefresh() => _loadSnapshot(showBusyIndicator: true);

  Future<void> _loadSnapshot({bool showBusyIndicator = true}) async {
    if (_isMutating || _isSnapshotLoadInFlight) {
      _queueSnapshotLoad(showBusyIndicator: showBusyIndicator);
      return;
    }
    _isSnapshotLoadInFlight = true;
    final showLoading = showBusyIndicator || _snapshot == null;
    if (showLoading) {
      setState(() {
        _isLoading = true;
        _errorMessage = null;
      });
    }
    try {
      final snapshot = await widget.bridge.loadFilesSnapshot();
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _currentDirectoryPath = _normalizeDirectoryPath(
          snapshot,
          _currentDirectoryPath,
        );
        _selectedPaths = _sanitizeSelectedPaths(snapshot, _selectedPaths);
        _isSelectionMode = _isSelectionMode && _selectedPaths.isNotEmpty;
        _pendingTransfer = _sanitizePendingTransfer(snapshot, _pendingTransfer);
        _isLoading = false;
        _errorMessage = null;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      final message = _userFacingErrorMessage(error);
      if (showLoading || _snapshot == null || showBusyIndicator) {
        setState(() {
          _isLoading = false;
          if (_snapshot == null || showBusyIndicator) {
            _errorMessage = message;
          }
        });
      }
    } finally {
      _isSnapshotLoadInFlight = false;
      _flushQueuedSnapshotLoad();
    }
  }

  void _handleQueryChanged() {
    setState(() {
      _query = _searchController.text;
    });
  }

  void _handleScroll() {
    _syncStickyBarVisibility();
  }

  void _scheduleStickyBarSync() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      _syncStickyBarVisibility();
    });
  }

  void _syncStickyBarVisibility() {
    final actionContext = _locationActionRowKey.currentContext;
    bool? shouldShow;
    if (actionContext != null) {
      final renderObject = actionContext.findRenderObject();
      if (renderObject is RenderBox && renderObject.hasSize) {
        final actionTop = renderObject.localToGlobal(Offset.zero).dy;
        final threshold = MediaQuery.of(context).padding.top + 18;
        final scrollOffset = _scrollController.hasClients
            ? _scrollController.offset
            : 0.0;
        final measuredTriggerOffset = (scrollOffset + actionTop - threshold)
            .clamp(0.0, double.infinity)
            .toDouble();
        final previousTriggerOffset = _stickyBrowseBarTriggerScrollOffset;
        final triggerOffset =
            previousTriggerOffset == null ||
                measuredTriggerOffset < previousTriggerOffset
            ? measuredTriggerOffset
            : previousTriggerOffset;
        _stickyBrowseBarTriggerScrollOffset = triggerOffset;
        shouldShow = actionTop <= threshold || scrollOffset >= triggerOffset;
      }
    }

    final bool nextShouldShow;
    if (shouldShow == null) {
      if (!_scrollController.hasClients) {
        return;
      }
      final triggerOffset =
          _stickyBrowseBarTriggerScrollOffset ??
          _fallbackStickyBrowseBarTriggerScrollOffset;
      nextShouldShow = _scrollController.offset >= triggerOffset;
    } else {
      nextShouldShow = shouldShow;
    }

    if (nextShouldShow == _showStickyBrowseBar) {
      return;
    }
    setState(() {
      _showStickyBrowseBar = nextShouldShow;
    });
  }

  List<OpenCrayFileTreeNodeSnapshot> _visibleEntries(
    OpenCrayFilesSnapshot snapshot,
  ) {
    final query = _query.trim().toLowerCase();
    final entries = [..._currentEntries(snapshot)]..sort(_compareEntries);
    if (query.isEmpty) {
      return entries;
    }
    return entries
        .where(
          (entry) =>
              entry.name.toLowerCase().contains(query) ||
              entry.relativePath.toLowerCase().contains(query),
        )
        .toList(growable: false);
  }

  List<OpenCrayFileTreeNodeSnapshot> _currentEntries(
    OpenCrayFilesSnapshot snapshot,
  ) {
    if (_currentDirectoryPath.isEmpty) {
      return snapshot.children;
    }
    final node = _findNodeByPath(snapshot.children, _currentDirectoryPath);
    if (node == null || !node.isDirectory) {
      return const <OpenCrayFileTreeNodeSnapshot>[];
    }
    return node.children;
  }

  int _compareEntries(
    OpenCrayFileTreeNodeSnapshot left,
    OpenCrayFileTreeNodeSnapshot right,
  ) {
    if (left.isDirectory != right.isDirectory) {
      return left.isDirectory ? -1 : 1;
    }
    return left.name.toLowerCase().compareTo(right.name.toLowerCase());
  }

  String _currentDirectoryName(OpenCrayFilesSnapshot? snapshot) {
    if (_currentDirectoryPath.isEmpty) {
      return snapshot?.rootName ?? widget.copy.filesLocationTitle;
    }
    return _pathSegments(_currentDirectoryPath).lastOrNull ??
        widget.copy.filesLocationTitle;
  }

  String _currentAbsolutePath(OpenCrayFilesSnapshot? snapshot) {
    final rootPath = (snapshot?.rootPath ?? '').replaceAll('\\', '/').trim();
    if (rootPath.isEmpty) {
      return _currentDirectoryPath.isEmpty ? '/' : '/$_currentDirectoryPath';
    }
    if (_currentDirectoryPath.isEmpty) {
      return rootPath;
    }
    return '$rootPath/$_currentDirectoryPath';
  }

  List<_BreadcrumbSegment> _visibleBreadcrumbs() {
    final segments = _pathSegments(_currentDirectoryPath);
    if (segments.isEmpty) {
      return const <_BreadcrumbSegment>[
        _BreadcrumbSegment(label: '/', relativePath: ''),
      ];
    }

    final breadcrumbs = <_BreadcrumbSegment>[
      const _BreadcrumbSegment(label: '/', relativePath: ''),
    ];
    var current = '';
    for (final segment in segments) {
      current = current.isEmpty ? segment : '$current/$segment';
      breadcrumbs.add(
        _BreadcrumbSegment(label: segment, relativePath: current),
      );
    }

    if (breadcrumbs.length <= 3) {
      return breadcrumbs;
    }

    return <_BreadcrumbSegment>[
      const _BreadcrumbSegment(label: '...', relativePath: null),
      ...breadcrumbs.sublist(breadcrumbs.length - 2),
    ];
  }

  void _handleBreadcrumbTap(String relativePath) {
    if (_currentDirectoryPath == relativePath) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      _currentDirectoryPath = relativePath;
    });
    _scheduleSilentRefresh();
  }

  void _handleEntryTap(OpenCrayFileTreeNodeSnapshot entry) {
    FocusManager.instance.primaryFocus?.unfocus();
    if (_isSelectionMode) {
      setState(() {
        if (_selectedPaths.contains(entry.relativePath)) {
          _selectedPaths.remove(entry.relativePath);
        } else {
          _selectedPaths.add(entry.relativePath);
        }
        if (_selectedPaths.isEmpty) {
          _isSelectionMode = false;
        }
      });
      return;
    }
    if (entry.isDirectory) {
      setState(() {
        _currentDirectoryPath = entry.relativePath;
      });
      _scheduleSilentRefresh();
      return;
    }
    if (_supportsImagePreview(entry.name)) {
      unawaited(_openImagePreview(entry));
      return;
    }
    unawaited(_openTextPreview(entry));
  }

  void _handleEntryLongPress(OpenCrayFileTreeNodeSnapshot entry) {
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      _pendingTransfer = null;
      _isSelectionMode = true;
      _selectedPaths = <String>{entry.relativePath};
    });
  }

  void _exitSelectionMode() {
    setState(() {
      _isSelectionMode = false;
      _selectedPaths = <String>{};
    });
  }

  bool _consumeBackPress() {
    if (!_handlesBackPress) {
      return false;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      _isSelectionMode = false;
      _selectedPaths = <String>{};
      _pendingTransfer = null;
    });
    return true;
  }

  Future<void> _handleCreateFolder() async {
    final intent = await _promptForNewEntry();
    if (!mounted || intent == null || !intent.canSubmit) {
      return;
    }
    switch (intent.kind) {
      case _NewEntryIntentKind.folder:
        await _runSnapshotMutation(
          () => widget.bridge.createWorkspaceFolder(
            parentRelativePath: _currentDirectoryPath,
            name: intent.name,
          ),
          applyState: _applySnapshot,
        );
        return;
      case _NewEntryIntentKind.textFile:
        final createdRelativePath = _joinRelativePath(
          _currentDirectoryPath,
          intent.name,
        );
        final didCreate = await _runSnapshotMutation(
          () => widget.bridge.createWorkspaceTextFile(
            parentRelativePath: _currentDirectoryPath,
            name: intent.name,
          ),
          applyState: _applySnapshot,
        );
        if (didCreate && mounted) {
          await _openTextEditor(createdRelativePath, autofocus: true);
        }
        return;
      case _NewEntryIntentKind.unsupportedFile:
        return;
    }
  }

  Future<void> _handleRename() async {
    if (_selectedPaths.length != 1) {
      return;
    }
    final targetPath = _selectedPaths.single;
    final currentName = _pathSegments(targetPath).lastOrNull ?? '';
    final nextName = await _promptForName(
      title: widget.copy.filesRenameEntryTitle,
      confirmLabel: widget.copy.filesSaveAction,
      initialValue: currentName,
    );
    if (!mounted || nextName == null) {
      return;
    }
    final renamedPath = _joinRelativePath(_parentPath(targetPath), nextName);
    await _runSnapshotMutation(
      () => widget.bridge.renameWorkspaceEntry(
        targetRelativePath: targetPath,
        newName: nextName,
      ),
      applyState: (snapshot) {
        _snapshot = snapshot;
        _currentDirectoryPath = _normalizeDirectoryPath(
          snapshot,
          _currentDirectoryPath,
        );
        _selectedPaths = _findNodeByPath(snapshot.children, renamedPath) == null
            ? <String>{}
            : <String>{renamedPath};
        _isSelectionMode = _selectedPaths.isNotEmpty;
        _pendingTransfer = _sanitizePendingTransfer(snapshot, _pendingTransfer);
        _errorMessage = null;
      },
    );
  }

  Future<void> _handleDelete() async {
    final targets = _selectedPaths.toList(growable: false);
    if (targets.isEmpty) {
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(widget.copy.filesDeleteConfirmTitle(targets.length)),
        content: Text(widget.copy.filesDeleteConfirmBody(targets.length)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(widget.copy.filesCancelAction),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(
              widget.copy.filesDeleteAction,
              style: const TextStyle(color: FilesFeatureScreen.danger),
            ),
          ),
        ],
      ),
    );
    if (!mounted || confirmed != true) {
      return;
    }
    await _runSnapshotMutation(
      () => widget.bridge.deleteWorkspaceEntries(targets),
      applyState: (snapshot) {
        _snapshot = snapshot;
        _currentDirectoryPath = _normalizeDirectoryPath(
          snapshot,
          _currentDirectoryPath,
        );
        _selectedPaths = <String>{};
        _isSelectionMode = false;
        _pendingTransfer = _sanitizePendingTransfer(snapshot, _pendingTransfer);
        _errorMessage = null;
      },
    );
  }

  Future<void> _handleShare() async {
    final targets = _selectedPaths.toList(growable: false);
    if (targets.isEmpty || _isMutating) {
      return;
    }
    setState(() {
      _isMutating = true;
    });
    try {
      await widget.bridge.shareWorkspaceEntries(targets);
    } catch (error) {
      if (!mounted) {
        return;
      }
      _showToast(_userFacingErrorMessage(error));
    } finally {
      if (mounted) {
        setState(() {
          _isMutating = false;
        });
      }
    }
  }

  void _handleStartTransfer(bool move) {
    if (_selectedPaths.isEmpty) {
      return;
    }
    final orderedPaths = _selectedPaths.toList()..sort();
    setState(() {
      _pendingTransfer = _PendingTransfer(
        sourceRelativePaths: orderedPaths,
        move: move,
      );
      _isSelectionMode = false;
      _selectedPaths = <String>{};
    });
  }

  Future<void> _handlePasteTransfer() async {
    final pendingTransfer = _pendingTransfer;
    if (pendingTransfer == null) {
      return;
    }
    await _runSnapshotMutation(
      () => widget.bridge.pasteWorkspaceEntries(
        sourceRelativePaths: pendingTransfer.sourceRelativePaths,
        destinationRelativePath: _currentDirectoryPath,
        move: pendingTransfer.move,
      ),
      applyState: (snapshot) {
        _snapshot = snapshot;
        _currentDirectoryPath = _normalizeDirectoryPath(
          snapshot,
          _currentDirectoryPath,
        );
        _selectedPaths = <String>{};
        _isSelectionMode = false;
        _pendingTransfer = null;
        _errorMessage = null;
      },
    );
  }

  void _applySnapshot(OpenCrayFilesSnapshot snapshot) {
    _snapshot = snapshot;
    _currentDirectoryPath = _normalizeDirectoryPath(
      snapshot,
      _currentDirectoryPath,
    );
    _selectedPaths = _sanitizeSelectedPaths(snapshot, _selectedPaths);
    _isSelectionMode = _isSelectionMode && _selectedPaths.isNotEmpty;
    _pendingTransfer = _sanitizePendingTransfer(snapshot, _pendingTransfer);
    _errorMessage = null;
  }

  Future<bool> _runSnapshotMutation(
    Future<OpenCrayFilesSnapshot> Function() mutation, {
    required void Function(OpenCrayFilesSnapshot snapshot) applyState,
  }) async {
    if (_isMutating) {
      return false;
    }
    setState(() {
      _isMutating = true;
    });
    try {
      final snapshot = await mutation();
      if (!mounted) {
        return false;
      }
      setState(() {
        applyState(snapshot);
      });
      return true;
    } catch (error) {
      if (!mounted) {
        return false;
      }
      _showToast(_userFacingErrorMessage(error));
      return false;
    } finally {
      if (mounted) {
        setState(() {
          _isMutating = false;
        });
      }
      _flushQueuedSnapshotLoad();
    }
  }

  bool get _shouldAutoRefresh =>
      widget.isTabActive &&
      _appLifecycleState == AppLifecycleState.resumed &&
      widget.autoRefreshPollInterval > Duration.zero;

  void _scheduleSilentRefresh() {
    if (!mounted || !widget.isTabActive) {
      return;
    }
    unawaited(_loadSnapshot(showBusyIndicator: false));
  }

  void _syncAutoRefreshTimer() {
    _autoRefreshTimer?.cancel();
    if (!_shouldAutoRefresh) {
      _autoRefreshTimer = null;
      return;
    }
    _autoRefreshTimer = Timer.periodic(widget.autoRefreshPollInterval, (_) {
      _scheduleSilentRefresh();
    });
  }

  void _queueSnapshotLoad({required bool showBusyIndicator}) {
    _hasQueuedSnapshotLoad = true;
    _queuedSnapshotLoadShowBusyIndicator =
        _queuedSnapshotLoadShowBusyIndicator || showBusyIndicator;
  }

  void _flushQueuedSnapshotLoad() {
    if (!mounted ||
        !_hasQueuedSnapshotLoad ||
        _isMutating ||
        _isSnapshotLoadInFlight) {
      return;
    }
    final showBusyIndicator = _queuedSnapshotLoadShowBusyIndicator;
    _hasQueuedSnapshotLoad = false;
    _queuedSnapshotLoadShowBusyIndicator = false;
    unawaited(_loadSnapshot(showBusyIndicator: showBusyIndicator));
  }

  Future<void> _openTextPreview(OpenCrayFileTreeNodeSnapshot entry) async {
    try {
      final preview = await widget.bridge.loadWorkspaceTextPreview(
        entry.relativePath,
      );
      if (!mounted) {
        return;
      }
      final result = await _showTextPreviewDialog(preview);
      if (!mounted || result != _TextPreviewDialogResult.edit) {
        return;
      }
      await _openTextEditor(entry.relativePath);
    } catch (error) {
      if (!mounted) {
        return;
      }
      _showToast(_userFacingErrorMessage(error));
    }
  }

  Future<void> _openImagePreview(OpenCrayFileTreeNodeSnapshot entry) async {
    try {
      final preview = await widget.bridge.loadWorkspaceImagePreview(
        entry.relativePath,
      );
      if (!mounted) {
        return;
      }
      await _showImagePreviewDialog(preview);
    } catch (error) {
      if (!mounted) {
        return;
      }
      _showToast(_userFacingErrorMessage(error));
    }
  }

  Future<void> _openTextEditor(
    String relativePath, {
    bool autofocus = false,
  }) async {
    try {
      final document = await widget.bridge.loadWorkspaceTextDocument(
        relativePath,
      );
      if (!mounted) {
        return;
      }
      await _showTextEditorDialog(document, autofocus: autofocus);
    } catch (error) {
      if (!mounted) {
        return;
      }
      _showToast(_userFacingErrorMessage(error));
    }
  }

  Future<bool> _saveTextDocument({
    required String relativePath,
    required String content,
  }) {
    return _runSnapshotMutation(
      () => widget.bridge.saveWorkspaceTextDocument(
        targetRelativePath: relativePath,
        content: content,
      ),
      applyState: _applySnapshot,
    );
  }

  Future<_TextPreviewDialogResult?> _showTextPreviewDialog(
    OpenCrayFileTextPreview preview,
  ) {
    return _showPreviewDialog<_TextPreviewDialogResult>(
      backdropKey: const ValueKey<String>('files-text-preview-backdrop'),
      child: Builder(
        builder: (dialogContext) => _TextPreviewDialog(
          key: const ValueKey<String>('files-text-preview-dialog'),
          bridge: widget.bridge,
          copy: widget.copy,
          preview: preview,
          onEdit: () =>
              Navigator.of(dialogContext).pop(_TextPreviewDialogResult.edit),
        ),
      ),
    );
  }

  Future<void> _showImagePreviewDialog(OpenCrayFileImagePreview preview) {
    return _showPreviewDialog<void>(
      backdropKey: const ValueKey<String>('files-image-preview-backdrop'),
      child: _ImagePreviewDialog(
        key: const ValueKey<String>('files-image-preview-dialog'),
        preview: preview,
      ),
    );
  }

  Future<void> _showTextEditorDialog(
    OpenCrayWorkspaceTextDocument document, {
    bool autofocus = false,
  }) {
    return _showPreviewDialog<void>(
      backdropKey: const ValueKey<String>('files-text-editor-backdrop'),
      barrierLabel: widget.copy.filesCancelAction,
      child: Builder(
        builder: (dialogContext) => _TextEditorDialog(
          key: const ValueKey<String>('files-text-editor-dialog'),
          copy: widget.copy,
          document: document,
          autofocus: autofocus,
          onClose: () => Navigator.of(dialogContext).pop(),
          onSave: (content) async {
            final didSave = await _saveTextDocument(
              relativePath: document.relativePath,
              content: content,
            );
            if (didSave && dialogContext.mounted) {
              Navigator.of(dialogContext).pop();
            }
            return didSave;
          },
        ),
      ),
    );
  }

  Future<_NewEntryIntent?> _promptForNewEntry() {
    return _showPreviewDialog<_NewEntryIntent>(
      backdropKey: const ValueKey<String>('files-create-entry-backdrop'),
      barrierLabel: widget.copy.filesCancelAction,
      child: Builder(
        builder: (dialogContext) => _CreateEntryDialog(
          key: const ValueKey<String>('files-create-entry-dialog'),
          copy: widget.copy,
          onCancel: () => Navigator.of(dialogContext).pop(),
          onCreate: (intent) => Navigator.of(dialogContext).pop(intent),
        ),
      ),
    );
  }

  Future<T?> _showPreviewDialog<T>({
    required Key backdropKey,
    required Widget child,
    String? barrierLabel,
  }) {
    return showGeneralDialog<T>(
      context: context,
      barrierDismissible: true,
      barrierLabel: barrierLabel ?? widget.copy.filesPreviewCloseAction,
      barrierColor: Colors.transparent,
      transitionDuration: OpenCrayMotion.quick,
      pageBuilder: (dialogContext, animation, secondaryAnimation) {
        final media = MediaQuery.of(dialogContext);
        return GestureDetector(
          onTap: () => Navigator.of(dialogContext).pop(),
          child: Material(
            color: Colors.transparent,
            child: Stack(
              fit: StackFit.expand,
              children: [
                ClipRect(
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
                    child: ColoredBox(
                      key: backdropKey,
                      color: const Color(0x26000000),
                    ),
                  ),
                ),
                SafeArea(
                  child: Center(
                    child: AnimatedPadding(
                      duration: OpenCrayMotion.resolve(
                        dialogContext,
                        OpenCrayMotion.quick,
                      ),
                      curve: OpenCrayMotion.enter,
                      padding: EdgeInsets.fromLTRB(
                        20,
                        24,
                        20,
                        24 + media.viewInsets.bottom,
                      ),
                      child: GestureDetector(onTap: () {}, child: child),
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
      transitionBuilder: (context, animation, secondaryAnimation, child) {
        final curved = CurvedAnimation(
          parent: animation,
          curve: OpenCrayMotion.enter,
          reverseCurve: OpenCrayMotion.exit,
        );
        final fade = FadeTransition(opacity: curved, child: child);
        if (OpenCrayMotion.reduce(context)) {
          return fade;
        }
        return ScaleTransition(
          scale: Tween<double>(begin: 0.96, end: 1).animate(curved),
          child: fade,
        );
      },
    );
  }

  Future<String?> _promptForName({
    required String title,
    required String confirmLabel,
    String initialValue = '',
  }) async {
    final controller = TextEditingController(text: initialValue);
    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: InputDecoration(hintText: widget.copy.filesNameFieldHint),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(widget.copy.filesCancelAction),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(controller.text.trim()),
            child: Text(confirmLabel),
          ),
        ],
      ),
    );
    controller.dispose();
    if (result == null || result.trim().isEmpty) {
      return null;
    }
    return result.trim();
  }

  void _showToast(String message) {
    final normalized = message.trim();
    if (normalized.isEmpty) {
      return;
    }
    unawaited(
      widget.bridge.showNativeToast(normalized).catchError((Object _) {}),
    );
  }

  String _userFacingErrorMessage(Object error) {
    if (error is PlatformException) {
      final message = error.message?.trim();
      if (message != null && message.isNotEmpty) {
        return message;
      }
    }
    final raw = '$error'.trim();
    if (raw.startsWith('Bad state: ')) {
      return raw.substring('Bad state: '.length);
    }
    if (raw.startsWith('PlatformException(')) {
      final segments = raw.split(', ');
      if (segments.length >= 2) {
        final message = segments[1].trim();
        if (message.isNotEmpty && message != 'null') {
          return message;
        }
      }
    }
    return raw;
  }

  OpenCrayFileTreeNodeSnapshot? _findNodeByPath(
    List<OpenCrayFileTreeNodeSnapshot> nodes,
    String targetRelativePath,
  ) {
    for (final node in nodes) {
      if (node.relativePath == targetRelativePath) {
        return node;
      }
      final nested = _findNodeByPath(node.children, targetRelativePath);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  String _normalizeDirectoryPath(
    OpenCrayFilesSnapshot snapshot,
    String candidatePath,
  ) {
    var current = candidatePath.trim();
    while (current.isNotEmpty) {
      final node = _findNodeByPath(snapshot.children, current);
      if (node != null && node.isDirectory) {
        return current;
      }
      current = _parentPath(current);
    }
    return '';
  }

  Set<String> _sanitizeSelectedPaths(
    OpenCrayFilesSnapshot snapshot,
    Set<String> selectedPaths,
  ) {
    return selectedPaths
        .where((path) => _findNodeByPath(snapshot.children, path) != null)
        .toSet();
  }

  _PendingTransfer? _sanitizePendingTransfer(
    OpenCrayFilesSnapshot snapshot,
    _PendingTransfer? pendingTransfer,
  ) {
    if (pendingTransfer == null) {
      return null;
    }
    final allExist = pendingTransfer.sourceRelativePaths.every(
      (path) => _findNodeByPath(snapshot.children, path) != null,
    );
    return allExist ? pendingTransfer : null;
  }
}

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
              fontSize: 30,
              height: 1.05,
              fontWeight: FontWeight.w600,
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
        color: isActive ? const Color(0xFFF9FBFF) : Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: isActive
              ? FilesFeatureScreen.accent.withValues(alpha: 0.28)
              : Colors.transparent,
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
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
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
                fontSize: 18,
                height: 1.2,
                fontWeight: FontWeight.w600,
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
        boxShadow: const [
          BoxShadow(
            color: Color(0x14000000),
            blurRadius: 16,
            offset: Offset(0, 8),
          ),
        ],
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
          child: _StateCard(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
            ),
          ),
        ),
      );
    }

    if (errorMessage != null && snapshot == null) {
      return SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        sliver: SliverToBoxAdapter(
          child: _StateCard(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    copy.filesLoadFailed,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: FilesFeatureScreen.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    errorMessage!,
                    style: const TextStyle(
                      fontSize: 14,
                      height: 1.35,
                      color: FilesFeatureScreen.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    }

    if (snapshot == null || entries.isEmpty) {
      final hasQuery = query.trim().isNotEmpty;
      return SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        sliver: SliverToBoxAdapter(
          child: _StateCard(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    hasQuery
                        ? copy.filesNoMatchesTitle
                        : copy.filesFolderEmptyTitle,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: FilesFeatureScreen.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    hasQuery
                        ? copy.filesNoMatchesBody(query)
                        : copy.filesFolderEmptyBody,
                    style: const TextStyle(
                      fontSize: 14,
                      height: 1.35,
                      color: FilesFeatureScreen.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
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
          borderRadius: BorderRadius.circular(18),
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
                color: Color(0x12000000),
                blurRadius: 18,
                offset: Offset(0, -6),
              ),
            ],
          ),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 8),
            child: Row(
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
                    key: const ValueKey<String>('files-toolbar-action-delete'),
                    icon: CupertinoIcons.delete,
                    label: copy.filesDeleteAction,
                    enabled: canDelete,
                    accentColor: FilesFeatureScreen.danger,
                    onTap: onDelete,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

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
          boxShadow: const [
            BoxShadow(
              color: Color(0x22000000),
              blurRadius: 30,
              offset: Offset(0, 18),
            ),
          ],
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
                      color: const Color(0xFFF8F8FA),
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
                                      imageBackgroundColor: const Color(
                                        0xFFEDEFF4,
                                      ),
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
          boxShadow: const [
            BoxShadow(
              color: Color(0x22000000),
              blurRadius: 30,
              offset: Offset(0, 18),
            ),
          ],
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
          boxShadow: const [
            BoxShadow(
              color: Color(0x22000000),
              blurRadius: 30,
              offset: Offset(0, 18),
            ),
          ],
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
                    color: const Color(0xFFF8F8FA),
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
              boxShadow: const [
                BoxShadow(
                  color: Color(0x18000000),
                  blurRadius: 24,
                  offset: Offset(0, 14),
                ),
              ],
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
                  decoration: const BoxDecoration(color: Color(0xFFF2F2F5)),
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

class _StateCard extends StatelessWidget {
  const _StateCard({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: FilesFeatureScreen.surface,
        borderRadius: BorderRadius.circular(18),
      ),
      child: child,
    );
  }
}

class _PendingTransfer {
  const _PendingTransfer({
    required this.sourceRelativePaths,
    required this.move,
  });

  final List<String> sourceRelativePaths;
  final bool move;
}

class _BreadcrumbSegment {
  const _BreadcrumbSegment({required this.label, required this.relativePath});

  final String label;
  final String? relativePath;
}

List<String> _pathSegments(String relativePath) {
  return relativePath
      .split('/')
      .map((segment) => segment.trim())
      .where((segment) => segment.isNotEmpty)
      .toList(growable: false);
}

String _parentPath(String relativePath) {
  final normalized = relativePath.trim();
  if (normalized.isEmpty || !normalized.contains('/')) {
    return '';
  }
  return normalized.substring(0, normalized.lastIndexOf('/'));
}

String _joinRelativePath(String parent, String name) {
  final normalizedParent = parent.trim();
  if (normalizedParent.isEmpty) {
    return name.trim();
  }
  return '$normalizedParent/${name.trim()}';
}

bool _supportsImagePreview(String name) {
  final normalizedName = name.trim().toLowerCase();
  final extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  return _imagePreviewExtensions.contains(extension);
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

bool _supportsTextDocumentName(String name) {
  final normalizedName = name.trim().toLowerCase();
  if (_textPreviewFileNames.contains(normalizedName)) {
    return true;
  }
  final extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  return _textPreviewExtensions.contains(extension);
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
      color: const Color(0xFFF0F1F5),
      borderRadius: BorderRadius.circular(12),
    ),
    blockSpacing: 14,
    blockquote: const TextStyle(
      fontSize: 13,
      height: 1.5,
      color: FilesFeatureScreen.textSecondary,
    ),
    blockquoteDecoration: BoxDecoration(
      color: const Color(0xFFF3F4F8),
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

const Set<String> _imagePreviewExtensions = <String>{
  'png',
  'jpg',
  'jpeg',
  'webp',
  'gif',
  'bmp',
  'heic',
  'heif',
  'svg',
};

const Set<String> _textPreviewFileNames = <String>{
  '.env',
  '.gitignore',
  '.gitattributes',
  'makefile',
  'readme',
  'readme.md',
  'license',
  'gradlew',
  'gradlew.bat',
};

const Set<String> _textPreviewExtensions = <String>{
  'txt',
  'md',
  'markdown',
  'json',
  'yaml',
  'yml',
  'xml',
  'csv',
  'log',
  'ini',
  'conf',
  'config',
  'properties',
  'toml',
  'dart',
  'kt',
  'kts',
  'java',
  'js',
  'ts',
  'tsx',
  'jsx',
  'css',
  'scss',
  'html',
  'htm',
  'sh',
  'bash',
  'zsh',
  'py',
  'sql',
};

extension on List<String> {
  String? get lastOrNull => isEmpty ? null : last;
}
