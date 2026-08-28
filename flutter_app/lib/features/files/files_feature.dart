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
import '../../core/design/opencray_controls.dart';
import '../../core/design/opencray_motion.dart';
import '../../core/design/opencray_tokens.dart';
import '../../core/design/opencray_widgets.dart';
import '../../core/widgets/opencray_image_bytes_view.dart';
import '../../core/widgets/opencray_markdown.dart';
part 'files_path_utils.dart';
part 'files_widgets.dart';
part 'files_dialogs.dart';

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

enum _FilesOperationState {
  copyReady,
  moveReady,
  pasting,
  deleting,
  done,
  failed,
}

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

  static const Color shellBackground = OpenCrayColors.shellBackground;
  static const Color surface = OpenCrayColors.surface;
  static const Color surfaceMuted = OpenCrayColors.surfaceMuted;
  static const Color surfacePressed = OpenCrayColors.primaryTint;
  static const Color textPrimary = OpenCrayColors.textPrimary;
  static const Color textSecondary = OpenCrayColors.textSecondary;
  static const Color textTertiary = OpenCrayColors.textTertiary;
  static const Color accent = OpenCrayColors.primary;
  static const Color danger = OpenCrayColors.danger;
  static const Color divider = OpenCrayColors.divider;

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
  _FilesOperationState? _operationState;
  Timer? _autoRefreshTimer;
  late AppLifecycleState _appLifecycleState;
  bool _isSnapshotLoadInFlight = false;
  bool _hasQueuedSnapshotLoad = false;
  bool _queuedSnapshotLoadShowBusyIndicator = false;

  bool get _hasPendingTransfer => _pendingTransfer != null;
  bool get _handlesBackPress =>
      _isSelectionMode || _hasPendingTransfer || _operationState != null;
  bool get _showsSelectionToolbar =>
      _isSelectionMode || _hasPendingTransfer || _operationState != null;

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
        (_isSelectionMode || _hasPendingTransfer || _operationState != null)) {
      setState(() {
        _isSelectionMode = false;
        _selectedPaths = <String>{};
        _pendingTransfer = null;
        _operationState = null;
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
                        height: _showsSelectionToolbar
                            ? (_operationState == null ? 118 : 154)
                            : 28,
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
                    operationState: _operationState,
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
      _operationState = null;
      _isSelectionMode = true;
      _selectedPaths = <String>{entry.relativePath};
    });
  }

  void _exitSelectionMode() {
    setState(() {
      _isSelectionMode = false;
      _selectedPaths = <String>{};
      _operationState = null;
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
      _operationState = null;
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
    _setOperationState(_FilesOperationState.deleting);
    final didDelete = await _runSnapshotMutation(
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
    if (!mounted) {
      return;
    }
    _setOperationState(
      didDelete ? _FilesOperationState.done : _FilesOperationState.failed,
    );
    _scheduleOperationStateClear();
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
      _operationState = move
          ? _FilesOperationState.moveReady
          : _FilesOperationState.copyReady;
    });
  }

  Future<void> _handlePasteTransfer() async {
    final pendingTransfer = _pendingTransfer;
    if (pendingTransfer == null) {
      return;
    }
    _setOperationState(_FilesOperationState.pasting);
    final didPaste = await _runSnapshotMutation(
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
    if (!mounted) {
      return;
    }
    _setOperationState(
      didPaste ? _FilesOperationState.done : _FilesOperationState.failed,
    );
    _scheduleOperationStateClear();
  }

  void _setOperationState(_FilesOperationState state) {
    if (!mounted) {
      return;
    }
    setState(() {
      _operationState = state;
    });
  }

  void _scheduleOperationStateClear() {
    Future<void>.delayed(
      OpenCrayMotion.resolve(context, OpenCrayMotion.panel),
      () {
        if (!mounted) {
          return;
        }
        setState(() {
          _operationState = null;
        });
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
        copy: widget.copy,
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
                      color: OpenCrayColors.scrim,
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
