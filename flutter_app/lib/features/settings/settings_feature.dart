import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/design/opencray_tokens.dart';
import 'settings_facade.dart';
import 'settings_models.dart';

class SettingsFeatureScreen extends StatefulWidget {
  const SettingsFeatureScreen({
    super.key,
    required this.facade,
    this.initialPage = SettingsPage.home,
    this.standalone = false,
  });

  final SettingsPage initialPage;
  final SettingsFacade facade;
  final bool standalone;

  @override
  State<SettingsFeatureScreen> createState() => _SettingsFeatureScreenState();
}

class _SettingsFeatureScreenState extends State<SettingsFeatureScreen> {
  late SettingsPage _page = widget.initialPage;
  final Map<SettingsPage, SettingsDetailSnapshot> _detailCache =
      <SettingsPage, SettingsDetailSnapshot>{};
  SettingsOverviewSnapshot? _overview;
  StreamSubscription<SettingsOverviewSnapshot>? _overviewSubscription;

  @override
  void initState() {
    super.initState();
    _loadOverview();
    _overviewSubscription = widget.facade.watchOverview().listen((overview) {
      if (!mounted) {
        return;
      }
      setState(() {
        _overview = overview;
      });
    });
    if (!_usesDedicatedPage(_page) && _page != SettingsPage.home) {
      _loadDetail(_page);
    }
  }

  @override
  void dispose() {
    _overviewSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final defaultTextStyle =
        Theme.of(context).textTheme.bodyMedium ??
        const TextStyle(
          fontSize: 14,
          height: 20 / 14,
          color: OpenCrayColors.textSecondary,
        );
    final content = DefaultTextStyle(
      style: defaultTextStyle,
      child: SafeArea(
        bottom: false,
        child: AnimatedSwitcher(
          duration: const Duration(milliseconds: 180),
          child: _buildCurrentPage(context),
        ),
      ),
    );
    if (widget.standalone) {
      return Scaffold(
        backgroundColor: OpenCrayColors.shellBackground,
        body: content,
      );
    }
    return Material(color: OpenCrayColors.shellBackground, child: content);
  }

  Widget _buildCurrentPage(BuildContext context) {
    final onBack = widget.standalone
        ? () => Navigator.of(context).pop()
        : () => setState(() => _page = SettingsPage.home);
    final backLabel = _overview?.title ?? '';
    switch (_page) {
      case SettingsPage.home:
        final overview = _overview;
        if (overview == null) {
          return const _SettingsLoading(
            key: ValueKey<String>('settings-loading'),
          );
        }
        return _SettingsHome(
          key: const ValueKey<String>('settings-home'),
          snapshot: overview,
          onOpenPage: _openPage,
        );
      case SettingsPage.llm:
        return _LlmSettingsPage(
          key: const ValueKey<String>('settings-llm-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.personalization:
        return _PersonalizationSettingsPage(
          key: const ValueKey<String>('settings-personalization-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.mcp:
        return _McpSettingsPage(
          key: const ValueKey<String>('settings-mcp-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.workspaceAccess:
      case SettingsPage.privacyTelemetry:
      case SettingsPage.safetyLimits:
      case SettingsPage.aboutVersion:
        final detailSnapshot = _detailCache[_page];
        if (detailSnapshot == null) {
          return const _SettingsLoading(
            key: ValueKey<String>('settings-detail-loading'),
          );
        }
        return _SettingsDetailPage(
          key: ValueKey<String>('settings-${_page.name}'),
          snapshot: detailSnapshot,
          onBack: onBack,
          backLabel: backLabel,
        );
    }
  }

  void _openPage(SettingsPage page) {
    if (!widget.standalone && page != SettingsPage.home) {
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (context) => SettingsFeatureScreen(
            facade: widget.facade,
            initialPage: page,
            standalone: true,
          ),
        ),
      );
      return;
    }
    setState(() {
      _page = page;
    });
    if (!_usesDedicatedPage(page) && page != SettingsPage.home) {
      _loadDetail(page);
    }
  }

  Future<void> _loadOverview() async {
    final overview = await widget.facade.loadOverview();
    if (!mounted) {
      return;
    }
    setState(() {
      _overview = overview;
    });
  }

  Future<void> _loadDetail(SettingsPage page) async {
    if (_detailCache.containsKey(page)) {
      return;
    }
    final detail = await widget.facade.loadDetail(page);
    if (!mounted) {
      return;
    }
    setState(() {
      _detailCache[page] = detail;
    });
  }

  bool _usesDedicatedPage(SettingsPage page) =>
      page == SettingsPage.llm ||
      page == SettingsPage.personalization ||
      page == SettingsPage.mcp;
}

class _SettingsHome extends StatelessWidget {
  const _SettingsHome({
    super.key,
    required this.snapshot,
    required this.onOpenPage,
  });

  final SettingsOverviewSnapshot snapshot;
  final ValueChanged<SettingsPage> onOpenPage;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(snapshot.eyebrow, style: _SettingsTextStyles.eyebrow),
          const SizedBox(height: 8),
          Text(snapshot.title, style: _SettingsTextStyles.pageTitle),
          const SizedBox(height: 8),
          Text(snapshot.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 20),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.deviceTitle,
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(snapshot.deviceSummary, style: _SettingsTextStyles.body),
              ],
            ),
          ),
          const SizedBox(height: 16),
          ...snapshot.entries.map(
            (SettingsHomeEntrySnapshot item) => _HomeEntryRow(
              title: item.title,
              selected: false,
              onTap: () => onOpenPage(item.page),
            ),
          ),
        ],
      ),
    );
  }
}

class _SettingsLoading extends StatelessWidget {
  const _SettingsLoading({super.key});

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: SizedBox(
        width: 24,
        height: 24,
        child: CircularProgressIndicator(strokeWidth: 2),
      ),
    );
  }
}

class _SettingsDetailPage extends StatelessWidget {
  const _SettingsDetailPage({
    super.key,
    required this.snapshot,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsDetailSnapshot snapshot;
  final VoidCallback onBack;
  final String backLabel;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: onBack, label: backLabel),
          const SizedBox(height: 8),
          Text(snapshot.title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(snapshot.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
          ...snapshot.sections.map(
            (SettingsSectionSnapshot section) => Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: _SettingsCard(
                backgroundColor:
                    section.backgroundTone ==
                        SettingsSectionBackgroundTone.danger
                    ? OpenCrayColors.dangerSurface
                    : Colors.white,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (section.title.isNotEmpty)
                      Text(section.title, style: _SettingsTextStyles.cardTitle),
                    if (section.title.isNotEmpty &&
                        section.segmentedOptions == null)
                      const SizedBox(height: 8),
                    if (section.segmentedOptions != null) ...[
                      const SizedBox(height: 12),
                      _SegmentedSelector(
                        labels: section.segmentedOptions!,
                        selectedIndex: section.segmentedIndex ?? 0,
                      ),
                    ],
                    if (section.helperText != null) ...[
                      const SizedBox(height: 12),
                      Text(
                        section.helperText!,
                        style: _SettingsTextStyles.body,
                      ),
                    ],
                    if (section.inlinePanelText != null) ...[
                      const SizedBox(height: 12),
                      DecoratedBox(
                        decoration: BoxDecoration(
                          color: const Color(0xFFF4F4F7),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.all(12),
                          child: Text(
                            section.inlinePanelText!,
                            style: _SettingsTextStyles.bodyStrong,
                          ),
                        ),
                      ),
                    ],
                    if (section.rows.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      for (
                        int index = 0;
                        index < section.rows.length;
                        index++
                      ) ...[
                        _DetailRow(row: section.rows[index]),
                        if (index < section.rows.length - 1)
                          const Divider(
                            height: 1,
                            color: OpenCrayColors.divider,
                          ),
                      ],
                    ],
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _LlmSettingsPage extends StatefulWidget {
  const _LlmSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_LlmSettingsPage> createState() => _LlmSettingsPageState();
}

class _LlmSettingsPageState extends State<_LlmSettingsPage> {
  static const List<String> _protocolOptions = <String>['openai', 'anthropic'];
  static const List<String> _reasoningOptions = <String>[
    'low',
    'medium',
    'high',
    'xhigh',
  ];

  final TextEditingController _providerNameController = TextEditingController();
  final TextEditingController _providerNotesController =
      TextEditingController();
  final TextEditingController _baseUrlController = TextEditingController();
  final TextEditingController _apiKeyController = TextEditingController();
  final TextEditingController _modelController = TextEditingController();
  final TextEditingController _systemPromptController = TextEditingController();
  final FocusNode _providerNameFocusNode = FocusNode();
  final FocusNode _providerNotesFocusNode = FocusNode();
  final FocusNode _baseUrlFocusNode = FocusNode();
  final FocusNode _apiKeyFocusNode = FocusNode();
  final FocusNode _modelFocusNode = FocusNode();
  final FocusNode _systemPromptFocusNode = FocusNode();

  LlmConfigSnapshot? _snapshot;
  String _providerId = 'custom';
  String _protocol = 'openai';
  String _reasoningEffort = 'medium';
  bool _isApplyingSnapshot = false;
  bool _isSavingDraft = false;
  bool _hasQueuedSave = false;
  bool _isValidating = false;
  Completer<void>? _activeSaveCompleter;

  @override
  void initState() {
    super.initState();
    _registerAutosaveFocusNode(_providerNameFocusNode);
    _registerAutosaveFocusNode(_providerNotesFocusNode);
    _registerAutosaveFocusNode(_baseUrlFocusNode);
    _registerAutosaveFocusNode(_apiKeyFocusNode);
    _registerAutosaveFocusNode(_modelFocusNode);
    _registerAutosaveFocusNode(_systemPromptFocusNode);
    _load();
  }

  @override
  void dispose() {
    _providerNameController.dispose();
    _providerNotesController.dispose();
    _baseUrlController.dispose();
    _apiKeyController.dispose();
    _modelController.dispose();
    _systemPromptController.dispose();
    _providerNameFocusNode.dispose();
    _providerNotesFocusNode.dispose();
    _baseUrlFocusNode.dispose();
    _apiKeyFocusNode.dispose();
    _modelFocusNode.dispose();
    _systemPromptFocusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return const _SettingsLoading(
        key: ValueKey<String>('settings-llm-loading'),
      );
    }
    final selectedProvider = _selectedProviderFor(snapshot);
    final selectedProtocol = _draftProtocolFor(selectedProvider);
    final optionsLabel = '${snapshot.providerOptions.length} options';
    final showsReasoning =
        selectedProtocol == 'anthropic' ||
        _modelController.text.toLowerCase().contains('gpt');
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: widget.onBack, label: widget.backLabel),
          const SizedBox(height: 8),
          const Text('LLM', style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          const Text(
            'Select providers, routing, and response defaults.',
            style: _SettingsTextStyles.subtitle,
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Primary provider',
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 10),
                _PrototypeSelectionRow(
                  title: selectedProvider.title,
                  trailingLabel: optionsLabel,
                  onTap: _openProviderSheet,
                ),
                const SizedBox(height: 12),
                Text(
                  selectedProvider.isCustom
                      ? 'Best for larger provider lists.'
                      : 'Best when you have many providers configured.',
                  style: _SettingsTextStyles.body,
                ),
                if (selectedProvider.isCustom) ...[
                  const SizedBox(height: 12),
                  const Divider(height: 1, color: OpenCrayColors.divider),
                  const SizedBox(height: 12),
                  const Text(
                    'API protocol',
                    style: _SettingsTextStyles.fieldLabel,
                  ),
                  const SizedBox(height: 8),
                  _PrototypeSelectionRow(
                    title: _protocolTitle(selectedProtocol),
                    trailingLabel: '${_protocolOptions.length} options',
                    compact: true,
                    onTap: _openProtocolSheet,
                  ),
                  const SizedBox(height: 12),
                  _PrototypeField(
                    label: 'Provider name',
                    controller: _providerNameController,
                    focusNode: _providerNameFocusNode,
                    hintText: 'Acme Inference',
                    keyboardType: TextInputType.visiblePassword,
                  ),
                  const SizedBox(height: 12),
                  _PrototypeField(
                    label: 'Notes',
                    controller: _providerNotesController,
                    focusNode: _providerNotesFocusNode,
                    hintText: 'Regional fallback',
                    keyboardType: TextInputType.visiblePassword,
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Expanded(
                      child: Text(
                        'Connection',
                        style: _SettingsTextStyles.cardTitle,
                      ),
                    ),
                    _HeaderActionChip(
                      label: _isValidating
                          ? 'Validating…'
                          : (_isSavingDraft ? 'Saving…' : 'Validate Model'),
                      onTap: _isValidating ? null : _validateLlmConfig,
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                _PrototypeField(
                  label: 'Base URL',
                  controller: _baseUrlController,
                  focusNode: _baseUrlFocusNode,
                  hintText: 'https://api.openai.com/v1',
                  keyboardType: TextInputType.url,
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'API key',
                  controller: _apiKeyController,
                  focusNode: _apiKeyFocusNode,
                  hintText: 'Required for remote providers',
                  obscureText: true,
                  keyboardType: TextInputType.visiblePassword,
                  trailingText: _apiKeyController.text.trim().isEmpty
                      ? null
                      : 'Stored locally',
                  onChanged: (_) => setState(() {}),
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Model name',
                  controller: _modelController,
                  focusNode: _modelFocusNode,
                  hintText: 'gpt-4o-mini',
                  keyboardType: TextInputType.visiblePassword,
                  onChanged: (_) => setState(() {}),
                ),
                if (showsReasoning) ...[
                  const SizedBox(height: 12),
                  _PrototypeSelectionField(
                    label: 'Reasoning effort',
                    title: _reasoningEffortTitle(_reasoningEffort),
                    trailingLabel: selectedProtocol == 'anthropic'
                        ? 'Anthropic thinking enabled'
                        : 'GPT model detected',
                    onTap: _openReasoningSheet,
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Advanced prompt',
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Prompt override',
                  controller: _systemPromptController,
                  focusNode: _systemPromptFocusNode,
                  hintText:
                      'Leave empty to use the default OpenCray system prompt',
                  minLines: 5,
                  maxLines: 8,
                ),
                const SizedBox(height: 12),
                const Text(
                  'Changes save automatically when a field loses focus.',
                  style: _SettingsTextStyles.body,
                ),
                const SizedBox(height: 8),
                Text(snapshot.helperText, style: _SettingsTextStyles.body),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _load() async {
    final snapshot = await widget.facade.loadLlmConfig();
    if (!mounted) {
      return;
    }
    setState(() {
      _applySnapshot(snapshot);
    });
  }

  void _applyProvider(LlmProviderOption option) {
    setState(() {
      _providerId = option.id;
      if (!option.isCustom) {
        _protocol = 'openai';
      }
      if (_providerNameController.text.trim().isEmpty ||
          _providerNameController.text.trim() ==
              _snapshot?.providerName.trim()) {
        _providerNameController.text = option.title;
      }
      if (option.defaultBaseUrl.isNotEmpty) {
        _baseUrlController.text = option.defaultBaseUrl;
      }
      if (option.defaultModel.isNotEmpty) {
        _modelController.text = option.defaultModel;
      }
    });
    unawaited(_saveDraft());
  }

  Future<void> _validateLlmConfig() async {
    FocusScope.of(context).unfocus();
    await _saveDraft();
    if (_baseUrlController.text.trim().isEmpty) {
      _showMessage('Base URL is required to validate the model.');
      return;
    }
    if (_modelController.text.trim().isEmpty) {
      _showMessage('Model is required to validate the model.');
      return;
    }
    setState(() {
      _isValidating = true;
    });
    try {
      final validationResult = await widget.facade.validateLlmConfig(
        providerId: _providerId,
        protocol: _draftProtocol(),
        baseUrl: _baseUrlController.text,
        apiKey: _apiKeyController.text,
        model: _modelController.text,
        reasoningEffort: _reasoningEffort,
      );
      if (!mounted) {
        return;
      }
      _showMessage(validationResult.message);
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isValidating = false;
        });
      }
    }
  }

  Future<void> _openProviderSheet() async {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return;
    }
    final selected = await showModalBottomSheet<LlmProviderOption>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: DecoratedBox(
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.all(Radius.circular(22)),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 18, 16, 14),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Center(
                      child: Container(
                        width: 40,
                        height: 4,
                        margin: const EdgeInsets.only(bottom: 16),
                        decoration: BoxDecoration(
                          color: OpenCrayColors.divider,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    const Text(
                      'Primary provider',
                      style: _SettingsTextStyles.cardTitle,
                    ),
                    const SizedBox(height: 12),
                    for (final option in snapshot.providerOptions)
                      InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => Navigator.of(context).pop(option),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Row(
                            children: [
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      option.title,
                                      style: _SettingsTextStyles.rowTitle,
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      option.subtitle,
                                      style: _SettingsTextStyles.rowSubtitle,
                                    ),
                                  ],
                                ),
                              ),
                              if (option.id == _providerId)
                                const Icon(
                                  Icons.check_rounded,
                                  color: OpenCrayColors.primary,
                                  size: 18,
                                ),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
    if (selected != null) {
      _applyProvider(selected);
    }
  }

  Future<void> _openReasoningSheet() async {
    final selected = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: DecoratedBox(
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.all(Radius.circular(22)),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 18, 16, 14),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Center(
                      child: Container(
                        width: 40,
                        height: 4,
                        margin: const EdgeInsets.only(bottom: 16),
                        decoration: BoxDecoration(
                          color: OpenCrayColors.divider,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    const Text(
                      'Reasoning effort',
                      style: _SettingsTextStyles.cardTitle,
                    ),
                    const SizedBox(height: 12),
                    for (final option in _reasoningOptions)
                      InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => Navigator.of(context).pop(option),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  _reasoningEffortTitle(option),
                                  style: _SettingsTextStyles.rowTitle,
                                ),
                              ),
                              if (option == _reasoningEffort)
                                const Icon(
                                  Icons.check_rounded,
                                  color: OpenCrayColors.primary,
                                  size: 18,
                                ),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
    if (selected != null && mounted) {
      setState(() => _reasoningEffort = selected);
      unawaited(_saveDraft());
    }
  }

  Future<void> _openProtocolSheet() async {
    final selected = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: DecoratedBox(
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.all(Radius.circular(22)),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 18, 16, 14),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Center(
                      child: Container(
                        width: 40,
                        height: 4,
                        margin: const EdgeInsets.only(bottom: 16),
                        decoration: BoxDecoration(
                          color: OpenCrayColors.divider,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    const Text(
                      'API protocol',
                      style: _SettingsTextStyles.cardTitle,
                    ),
                    const SizedBox(height: 12),
                    for (final option in _protocolOptions)
                      InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => Navigator.of(context).pop(option),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  _protocolTitle(option),
                                  style: _SettingsTextStyles.rowTitle,
                                ),
                              ),
                              if (option == _protocol)
                                const Icon(
                                  Icons.check_rounded,
                                  color: OpenCrayColors.primary,
                                  size: 18,
                                ),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
    if (selected != null && mounted) {
      setState(() => _protocol = selected);
      unawaited(_saveDraft());
    }
  }

  void _registerAutosaveFocusNode(FocusNode focusNode) {
    focusNode.addListener(() {
      if (!focusNode.hasFocus) {
        unawaited(_saveDraft());
      }
    });
  }

  LlmProviderOption _selectedProviderFor(LlmConfigSnapshot snapshot) {
    if (snapshot.providerOptions.isEmpty) {
      return const LlmProviderOption(
        id: 'custom',
        title: 'Custom provider',
        subtitle: 'No provider presets are available.',
        defaultBaseUrl: '',
        defaultModel: '',
        isCustom: true,
      );
    }
    return snapshot.providerOptions.firstWhere(
      (option) => option.id == _providerId,
      orElse: () => snapshot.providerOptions.first,
    );
  }

  void _applySnapshot(LlmConfigSnapshot snapshot) {
    _isApplyingSnapshot = true;
    _snapshot = snapshot;
    _providerId = snapshot.providerId;
    _protocol = snapshot.protocol;
    _providerNameController.text = snapshot.providerName;
    _providerNotesController.text = snapshot.providerNotes;
    _baseUrlController.text = snapshot.baseUrl;
    _apiKeyController.text = snapshot.apiKey;
    _modelController.text = snapshot.model;
    _reasoningEffort = snapshot.reasoningEffort;
    _systemPromptController.text = snapshot.systemPrompt;
    _isApplyingSnapshot = false;
  }

  bool _hasDraftChanges() {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return false;
    }
    return _providerId != snapshot.providerId ||
        _draftProtocol() != snapshot.protocol ||
        _providerNameController.text != snapshot.providerName ||
        _providerNotesController.text != snapshot.providerNotes ||
        _baseUrlController.text != snapshot.baseUrl ||
        _apiKeyController.text != snapshot.apiKey ||
        _modelController.text != snapshot.model ||
        _reasoningEffort != snapshot.reasoningEffort ||
        _systemPromptController.text != snapshot.systemPrompt;
  }

  bool _draftIsConfigured() =>
      _baseUrlController.text.trim().isNotEmpty &&
      _apiKeyController.text.trim().isNotEmpty;

  Future<void> _saveDraft() async {
    if (_snapshot == null || _isApplyingSnapshot || !_hasDraftChanges()) {
      return;
    }
    if (_isSavingDraft) {
      _hasQueuedSave = true;
      await _activeSaveCompleter?.future;
      if (!_hasDraftChanges()) {
        return;
      }
    }
    final completer = Completer<void>();
    _activeSaveCompleter = completer;
    if (mounted) {
      setState(() {
        _isSavingDraft = true;
      });
    }
    try {
      final savedSnapshot = await widget.facade.saveLlmConfig(
        enabled: _draftIsConfigured(),
        providerId: _providerId,
        protocol: _draftProtocol(),
        providerName: _providerNameController.text,
        providerNotes: _providerNotesController.text,
        baseUrl: _baseUrlController.text,
        apiKey: _apiKeyController.text,
        model: _modelController.text,
        reasoningEffort: _reasoningEffort,
        systemPrompt: _systemPromptController.text,
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _applySnapshot(savedSnapshot);
      });
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      completer.complete();
      if (identical(_activeSaveCompleter, completer)) {
        _activeSaveCompleter = null;
      }
      if (mounted) {
        setState(() {
          _isSavingDraft = false;
        });
      } else {
        _isSavingDraft = false;
      }
      if (_hasQueuedSave) {
        _hasQueuedSave = false;
        unawaited(_saveDraft());
      }
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  String _draftProtocol() {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _protocol;
    }
    return _draftProtocolFor(_selectedProviderFor(snapshot));
  }

  String _draftProtocolFor(LlmProviderOption provider) =>
      provider.isCustom ? _protocol : 'openai';

  String _protocolTitle(String protocol) {
    switch (protocol) {
      case 'anthropic':
        return 'Anthropic';
      default:
        return 'OpenAI compatible';
    }
  }

  String _reasoningEffortTitle(String reasoningEffort) {
    if (reasoningEffort == 'xhigh') {
      return 'XHigh';
    }
    return '${reasoningEffort[0].toUpperCase()}${reasoningEffort.substring(1)}';
  }
}

class _PersonalizationSettingsPage extends StatefulWidget {
  const _PersonalizationSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_PersonalizationSettingsPage> createState() =>
      _PersonalizationSettingsPageState();
}

class _PersonalizationSettingsPageState
    extends State<_PersonalizationSettingsPage> {
  final TextEditingController _customLabelController = TextEditingController();
  final TextEditingController _customGuidanceController =
      TextEditingController();
  final TextEditingController _memoryResetController = TextEditingController();
  final TextEditingController _soulResetController = TextEditingController();

  PersonalizationConfigSnapshot? _snapshot;
  String? _loadError;
  String _selectedPresetId = 'steady';
  bool _isSaving = false;
  bool _isChangingLanguage = false;
  bool _hasQueuedSave = false;
  bool _isApplyingSnapshot = false;
  String? _activeResetScopeId;
  Timer? _saveDebounce;

  @override
  void initState() {
    super.initState();
    _customLabelController.addListener(_scheduleSave);
    _customGuidanceController.addListener(_scheduleSave);
    _memoryResetController.addListener(_refreshResetCards);
    _soulResetController.addListener(_refreshResetCards);
    _load();
  }

  @override
  void dispose() {
    _saveDebounce?.cancel();
    _customLabelController.dispose();
    _customGuidanceController.dispose();
    _memoryResetController.dispose();
    _soulResetController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-personalization-loading'),
            )
          : _SettingsLoadErrorCard(
              title: 'Personalization',
              message: _loadError!,
              onBack: widget.onBack,
              backLabel: widget.backLabel,
              onRetry: _load,
            );
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: widget.onBack, label: widget.backLabel),
          const SizedBox(height: 8),
          Text(snapshot.title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(snapshot.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(snapshot.introTitle, style: _SettingsTextStyles.cardTitle),
                const SizedBox(height: 8),
                Text(snapshot.introBody, style: _SettingsTextStyles.body),
                const SizedBox(height: 8),
                Text(snapshot.introHelper, style: _SettingsTextStyles.body),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.presetsTitle,
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(snapshot.presetsHelper, style: _SettingsTextStyles.body),
                const SizedBox(height: 12),
                for (final preset in snapshot.presets) ...[
                  _PresetOptionCard(
                    preset: preset,
                    isSelected: preset.id == _selectedPresetId,
                    onTap: () => _selectPreset(preset.id),
                  ),
                  if (preset != snapshot.presets.last)
                    const SizedBox(height: 10),
                ],
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.customOverlayTitle,
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.customOverlayHelper,
                  style: _SettingsTextStyles.body,
                ),
                const SizedBox(height: 14),
                _PrototypeField(
                  label: snapshot.customLabelHint,
                  controller: _customLabelController,
                  hintText: snapshot.customLabelHint,
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.customLabelHelper,
                  style: _SettingsTextStyles.body,
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: snapshot.customGuidanceHint,
                  controller: _customGuidanceController,
                  hintText: snapshot.customGuidanceHint,
                  minLines: 4,
                  maxLines: 7,
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.customGuidanceHelper,
                  style: _SettingsTextStyles.body,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.behaviorDefaultsTitle,
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 12),
                _SettingsPickerRow(
                  title: snapshot.appLanguageTitle,
                  value: _selectedLanguageTitle(snapshot),
                  isBusy: _isChangingLanguage || _isSaving,
                  onTap: (_isChangingLanguage || _isSaving)
                      ? null
                      : _openAppLanguageSheet,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.livePreviewTitle,
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 12),
                Text(
                  snapshot.livePreviewName,
                  style: _SettingsTextStyles.bodyStrong,
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.livePreviewSummary,
                  style: _SettingsTextStyles.body,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(snapshot.queueTitle, style: _SettingsTextStyles.cardTitle),
                const SizedBox(height: 10),
                Text(snapshot.queueBody, style: _SettingsTextStyles.body),
              ],
            ),
          ),
          if (snapshot.lastResetMessage.isNotEmpty) ...[
            const SizedBox(height: 16),
            _SettingsCard(
              backgroundColor: const Color(0xFFF4F8FF),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    snapshot.lastResetTitle,
                    style: _SettingsTextStyles.cardTitle,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    snapshot.lastResetMessage,
                    style: _SettingsTextStyles.body,
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 16),
          for (final action in snapshot.resetActions) ...[
            _DangerResetCard(
              action: action,
              controller: _controllerForScope(action.scopeId),
              isBusy: _activeResetScopeId == action.scopeId,
              onChanged: () => setState(() {}),
              onReset: () async {
                await _runReset(action.scopeId);
              },
            ),
            if (action != snapshot.resetActions.last)
              const SizedBox(height: 16),
          ],
        ],
      ),
    );
  }

  TextEditingController _controllerForScope(String scopeId) {
    if (scopeId == 'soul') {
      return _soulResetController;
    }
    return _memoryResetController;
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadPersonalizationConfig();
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = null;
      });
      _applySnapshot(snapshot);
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = error.toString().replaceFirst('Exception: ', '');
      });
    }
  }

  void _applySnapshot(PersonalizationConfigSnapshot snapshot) {
    _isApplyingSnapshot = true;
    _customLabelController.text = snapshot.customLabel;
    _customGuidanceController.text = snapshot.customGuidance;
    setState(() {
      _snapshot = snapshot;
      _selectedPresetId = snapshot.selectedPresetId;
    });
    _isApplyingSnapshot = false;
  }

  void _refreshResetCards() {
    if (mounted) {
      setState(() {});
    }
  }

  void _scheduleSave() {
    if (_isApplyingSnapshot || _snapshot == null) {
      return;
    }
    _saveDebounce?.cancel();
    _saveDebounce = Timer(const Duration(milliseconds: 350), () {
      _saveNow();
    });
  }

  void _selectPreset(String presetId) {
    if (_selectedPresetId == presetId) {
      return;
    }
    setState(() {
      _selectedPresetId = presetId;
    });
    _saveNow();
  }

  String _selectedLanguageTitle(PersonalizationConfigSnapshot snapshot) {
    final selected = snapshot.appLanguageOptions.firstWhere(
      (candidate) => candidate.id == snapshot.selectedAppLanguageId,
      orElse: () => snapshot.appLanguageOptions.isNotEmpty
          ? snapshot.appLanguageOptions.first
          : const PersonalizationLanguageOption(
              id: '',
              title: '',
              isSelected: false,
            ),
    );
    return selected.title;
  }

  Future<void> _openAppLanguageSheet() async {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return;
    }
    final selected = await showModalBottomSheet<PersonalizationLanguageOption>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: DecoratedBox(
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.all(Radius.circular(22)),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 18, 16, 14),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Center(
                      child: Container(
                        width: 40,
                        height: 4,
                        margin: const EdgeInsets.only(bottom: 16),
                        decoration: BoxDecoration(
                          color: OpenCrayColors.divider,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    Text(
                      snapshot.appLanguageTitle,
                      style: _SettingsTextStyles.cardTitle,
                    ),
                    const SizedBox(height: 12),
                    for (final option in snapshot.appLanguageOptions)
                      InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => Navigator.of(context).pop(option),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  option.title,
                                  style: _SettingsTextStyles.rowTitle,
                                ),
                              ),
                              if (option.id == snapshot.selectedAppLanguageId)
                                const Icon(
                                  Icons.check_rounded,
                                  color: OpenCrayColors.primary,
                                  size: 18,
                                ),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
    if (selected == null || selected.id == snapshot.selectedAppLanguageId) {
      return;
    }
    await _saveNow();
    if (!mounted) {
      return;
    }
    setState(() {
      _isChangingLanguage = true;
    });
    try {
      final updated = await widget.facade.setAppLanguage(selected.id);
      if (mounted) {
        _applySnapshot(updated);
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isChangingLanguage = false;
        });
      }
    }
  }

  Future<void> _saveNow() async {
    _saveDebounce?.cancel();
    final snapshot = _snapshot;
    if (snapshot == null) {
      return;
    }
    if (_isSaving) {
      _hasQueuedSave = true;
      return;
    }
    setState(() {
      _isSaving = true;
    });
    try {
      final updated = await widget.facade.savePersonalizationConfig(
        presetId: _selectedPresetId,
        customLabel: _customLabelController.text,
        customGuidance: _customGuidanceController.text,
      );
      if (mounted) {
        _applySnapshot(updated);
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isSaving = false;
        });
        if (_hasQueuedSave) {
          _hasQueuedSave = false;
          _saveNow();
        }
      }
    }
  }

  Future<void> _runReset(String scopeId) async {
    if (_activeResetScopeId != null) {
      return;
    }
    setState(() {
      _activeResetScopeId = scopeId;
    });
    try {
      final snapshot = await widget.facade.runPersonalizationReset(scopeId);
      if (mounted) {
        _controllerForScope(scopeId).clear();
        _applySnapshot(snapshot);
        if (snapshot.lastResetMessage.isNotEmpty) {
          _showMessage(snapshot.lastResetMessage);
        }
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _activeResetScopeId = null;
        });
      }
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}

class _McpSettingsPage extends StatefulWidget {
  const _McpSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_McpSettingsPage> createState() => _McpSettingsPageState();
}

class _McpSettingsPageState extends State<_McpSettingsPage> {
  McpSettingsSnapshot? _snapshot;
  String? _loadError;
  bool _isUpdatingMaster = false;
  String? _activeServerId;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-mcp-loading'),
            )
          : _SettingsLoadErrorCard(
              title: 'MCP',
              message: _loadError!,
              onBack: widget.onBack,
              backLabel: widget.backLabel,
              onRetry: _load,
            );
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: widget.onBack, label: widget.backLabel),
          const SizedBox(height: 8),
          Text(snapshot.title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(snapshot.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            snapshot.masterTitle,
                            style: _SettingsTextStyles.cardTitle,
                          ),
                          const SizedBox(height: 8),
                          Text(
                            snapshot.masterSummary,
                            style: _SettingsTextStyles.body,
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    _PrototypeSwitch(
                      value: snapshot.masterEnabled,
                      onChanged: _isUpdatingMaster ? null : _toggleMaster,
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  snapshot.summaryLine,
                  style: _SettingsTextStyles.bodyStrong,
                ),
              ],
            ),
          ),
          if (!snapshot.masterEnabled &&
              (snapshot.masterDisabledTitle.isNotEmpty ||
                  snapshot.masterDisabledBody.isNotEmpty)) ...[
            const SizedBox(height: 16),
            _SettingsCard(
              backgroundColor: const Color(0xFFFFF7E8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (snapshot.masterDisabledTitle.isNotEmpty)
                    Text(
                      snapshot.masterDisabledTitle,
                      style: _SettingsTextStyles.cardTitle,
                    ),
                  if (snapshot.masterDisabledBody.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Text(
                      snapshot.masterDisabledBody,
                      style: _SettingsTextStyles.body,
                    ),
                  ],
                ],
              ),
            ),
          ],
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.serversTitle,
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(snapshot.serversHelper, style: _SettingsTextStyles.body),
              ],
            ),
          ),
          const SizedBox(height: 16),
          for (final server in snapshot.servers) ...[
            _McpServerCard(
              server: server,
              isBusy: _activeServerId == server.id,
              onAction: () async {
                await _toggleServer(server);
              },
            ),
            if (server != snapshot.servers.last) const SizedBox(height: 16),
          ],
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadMcpSettings();
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = null;
        _snapshot = snapshot;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = error.toString().replaceFirst('Exception: ', '');
      });
    }
  }

  Future<void> _toggleMaster(bool enabled) async {
    setState(() {
      _isUpdatingMaster = true;
    });
    try {
      final snapshot = await widget.facade.setMcpMasterEnabled(enabled);
      if (mounted) {
        setState(() {
          _snapshot = snapshot;
        });
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isUpdatingMaster = false;
        });
      }
    }
  }

  Future<void> _toggleServer(McpServerSnapshot server) async {
    if (_activeServerId != null) {
      return;
    }
    setState(() {
      _activeServerId = server.id;
    });
    try {
      final snapshot = await widget.facade.setMcpServerEnabled(
        serverId: server.id,
        enabled: server.actionTurnsOn,
      );
      if (mounted) {
        setState(() {
          _snapshot = snapshot;
        });
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _activeServerId = null;
        });
      }
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}

class _SettingsLoadErrorCard extends StatelessWidget {
  const _SettingsLoadErrorCard({
    required this.title,
    required this.message,
    required this.onBack,
    required this.backLabel,
    required this.onRetry,
  });

  final String title;
  final String message;
  final VoidCallback onBack;
  final String backLabel;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: onBack, label: backLabel),
          const SizedBox(height: 8),
          Text(title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 16),
          _SettingsCard(
            backgroundColor: OpenCrayColors.dangerSurface,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Unable to load this page',
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(message, style: _SettingsTextStyles.body),
                const SizedBox(height: 12),
                _HeaderActionChip(label: 'Retry', onTap: onRetry),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _PresetOptionCard extends StatelessWidget {
  const _PresetOptionCard({
    required this.preset,
    required this.isSelected,
    required this.onTap,
  });

  final PersonalizationPresetOption preset;
  final bool isSelected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: isSelected ? const Color(0xFFF2F7FF) : const Color(0xFFF7F7FA),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: isSelected ? OpenCrayColors.primary : Colors.transparent,
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      preset.title,
                      style: _SettingsTextStyles.rowTitle,
                    ),
                  ),
                  _SettingsStatusPill(
                    label: preset.status,
                    tone: isSelected ? 'active' : 'neutral',
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(preset.summary, style: _SettingsTextStyles.body),
              const SizedBox(height: 8),
              Text(preset.voice, style: _SettingsTextStyles.bodyStrong),
              const SizedBox(height: 8),
              Text(preset.status, style: _SettingsTextStyles.body),
            ],
          ),
        ),
      ),
    );
  }
}

class _DangerResetCard extends StatelessWidget {
  const _DangerResetCard({
    required this.action,
    required this.controller,
    required this.isBusy,
    required this.onChanged,
    required this.onReset,
  });

  final PersonalizationResetAction action;
  final TextEditingController controller;
  final bool isBusy;
  final VoidCallback onChanged;
  final Future<void> Function() onReset;

  @override
  Widget build(BuildContext context) {
    final isArmed =
        action.isInputEnabled && controller.text == action.confirmationToken;
    final guidance = !action.isInputEnabled
        ? action.disabledGuidance
        : (isArmed ? action.armedGuidance : action.typeExactGuidance);
    return _SettingsCard(
      backgroundColor: OpenCrayColors.dangerSurface,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(action.title, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          Text(action.scopeBody, style: _SettingsTextStyles.body),
          const SizedBox(height: 8),
          Text(action.retainBody, style: _SettingsTextStyles.body),
          const SizedBox(height: 12),
          _PrototypeField(
            label: action.inputHint,
            controller: controller,
            hintText: action.confirmationToken,
            enabled: action.isInputEnabled && !isBusy,
            onChanged: (_) => onChanged(),
          ),
          const SizedBox(height: 10),
          Text(guidance, style: _SettingsTextStyles.body),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: isBusy || !isArmed
                  ? null
                  : () {
                      onReset();
                    },
              style: FilledButton.styleFrom(
                backgroundColor: OpenCrayColors.dangerText,
                foregroundColor: Colors.white,
                disabledBackgroundColor: const Color(0xFFE1E2E7),
                disabledForegroundColor: OpenCrayColors.textSecondary,
                padding: const EdgeInsets.symmetric(vertical: 12),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (isBusy) ...[
                    const SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                      ),
                    ),
                    const SizedBox(width: 10),
                  ],
                  Text(action.title),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _McpServerCard extends StatelessWidget {
  const _McpServerCard({
    required this.server,
    required this.isBusy,
    required this.onAction,
  });

  final McpServerSnapshot server;
  final bool isBusy;
  final Future<void> Function() onAction;

  @override
  Widget build(BuildContext context) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(server.title, style: _SettingsTextStyles.cardTitle),
                    const SizedBox(height: 8),
                    _SettingsStatusPill(
                      label: server.statusLabel,
                      tone: server.statusTone,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              _HeaderActionChip(
                label: server.actionLabel,
                onTap: !server.isActionEnabled || isBusy
                    ? null
                    : () {
                        onAction();
                      },
              ),
            ],
          ),
          const SizedBox(height: 14),
          Text(server.trustLine, style: _SettingsTextStyles.bodyStrong),
          const SizedBox(height: 6),
          Text(server.authLine, style: _SettingsTextStyles.body),
          const SizedBox(height: 6),
          Text(server.readinessLine, style: _SettingsTextStyles.body),
          const SizedBox(height: 6),
          Text(server.transportLine, style: _SettingsTextStyles.body),
          const SizedBox(height: 6),
          Text(server.exposureLine, style: _SettingsTextStyles.bodyStrong),
          const SizedBox(height: 10),
          Text(server.guidance, style: _SettingsTextStyles.body),
        ],
      ),
    );
  }
}

class _SettingsStatusPill extends StatelessWidget {
  const _SettingsStatusPill({required this.label, required this.tone});

  final String label;
  final String tone;

  @override
  Widget build(BuildContext context) {
    final Color backgroundColor;
    final Color textColor;
    switch (tone) {
      case 'active':
      case 'positive':
      case 'success':
        backgroundColor = const Color(0xFFE8F8EE);
        textColor = OpenCrayColors.success;
        break;
      case 'warning':
      case 'attention':
      case 'caution':
        backgroundColor = const Color(0xFFFFF3E4);
        textColor = const Color(0xFF9C5F00);
        break;
      case 'danger':
      case 'blocked':
        backgroundColor = const Color(0xFFFFEAED);
        textColor = OpenCrayColors.dangerText;
        break;
      default:
        backgroundColor = const Color(0xFFF1F2F5);
        textColor = OpenCrayColors.textSecondary;
        break;
    }
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        child: Text(
          label,
          style: _SettingsTextStyles.valueChip.copyWith(color: textColor),
        ),
      ),
    );
  }
}

class _BackLink extends StatelessWidget {
  const _BackLink({required this.onTap, required this.label});

  final VoidCallback onTap;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: InkWell(
        borderRadius: BorderRadius.circular(999),
        onTap: onTap,
        child: Padding(
          padding: EdgeInsets.symmetric(vertical: 4),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              const Icon(
                Icons.arrow_back_ios_new_rounded,
                size: 14,
                color: OpenCrayColors.primary,
              ),
              if (label.trim().isNotEmpty) ...[
                const SizedBox(width: 6),
                Text(label, style: _SettingsTextStyles.actionChip),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _SettingsPickerRow extends StatelessWidget {
  const _SettingsPickerRow({
    required this.title,
    required this.value,
    required this.onTap,
    this.isBusy = false,
  });

  final String title;
  final String value;
  final VoidCallback? onTap;
  final bool isBusy;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          children: [
            Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
            if (isBusy) ...[
              const SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: OpenCrayColors.primary,
                ),
              ),
              const SizedBox(width: 10),
            ],
            DecoratedBox(
              decoration: BoxDecoration(
                color: const Color(0xFFF3F4F7),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 5,
                ),
                child: Text(value, style: _SettingsTextStyles.valueChip),
              ),
            ),
            const SizedBox(width: 6),
            const Icon(
              Icons.chevron_right_rounded,
              size: 18,
              color: OpenCrayColors.textTertiary,
            ),
          ],
        ),
      ),
    );
  }
}

class _PrototypeSelectionRow extends StatelessWidget {
  const _PrototypeSelectionRow({
    required this.title,
    this.trailingLabel,
    this.onTap,
    this.compact = false,
  });

  final String title;
  final String? trailingLabel;
  final VoidCallback? onTap;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: _PrototypeFieldSurface(
        child: SizedBox(
          height: compact ? 44 : 52,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
            child: Row(
              children: [
                Expanded(
                  child: Text(title, style: _SettingsTextStyles.fieldValue),
                ),
                if (trailingLabel != null) ...[
                  Text(
                    trailingLabel!,
                    style: _SettingsTextStyles.selectionMeta,
                  ),
                  const SizedBox(width: 6),
                  const Text('›', style: _SettingsTextStyles.selectionChevron),
                ] else ...[
                  const Spacer(),
                  const Text('›', style: _SettingsTextStyles.selectionChevron),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PrototypeFieldSurface extends StatelessWidget {
  const _PrototypeFieldSurface({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFF7F7FA),
        borderRadius: BorderRadius.circular(12),
      ),
      child: child,
    );
  }
}

class _PrototypeSwitch extends StatelessWidget {
  const _PrototypeSwitch({required this.value, required this.onChanged});

  final bool value;
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    return Switch(
      value: value,
      onChanged: onChanged,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      thumbColor: const WidgetStatePropertyAll<Color>(Colors.white),
      trackColor: WidgetStateProperty.resolveWith<Color>((states) {
        if (states.contains(WidgetState.selected)) {
          return OpenCrayColors.success;
        }
        return const Color(0xFFD8DAE3);
      }),
      trackOutlineColor: const WidgetStatePropertyAll<Color>(
        Colors.transparent,
      ),
      trackOutlineWidth: const WidgetStatePropertyAll<double>(0),
    );
  }
}

class _PrototypeField extends StatelessWidget {
  const _PrototypeField({
    required this.label,
    required this.controller,
    this.focusNode,
    required this.hintText,
    this.enabled = true,
    this.obscureText = false,
    this.minLines = 1,
    this.maxLines = 1,
    this.trailingText,
    this.onChanged,
    this.keyboardType,
  });

  final String label;
  final TextEditingController controller;
  final FocusNode? focusNode;
  final String hintText;
  final bool enabled;
  final bool obscureText;
  final int minLines;
  final int maxLines;
  final String? trailingText;
  final ValueChanged<String>? onChanged;
  final TextInputType? keyboardType;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: _SettingsTextStyles.fieldLabel),
        const SizedBox(height: 6),
        _PrototypeFieldSurface(
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: minLines == 1 ? 44 : 44),
            child: Row(
              crossAxisAlignment: minLines == 1
                  ? CrossAxisAlignment.center
                  : CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: TextField(
                    controller: controller,
                    focusNode: focusNode,
                    enabled: enabled,
                    onChanged: onChanged,
                    obscureText: obscureText,
                    autocorrect: false,
                    enableSuggestions: false,
                    enableIMEPersonalizedLearning: false,
                    spellCheckConfiguration:
                        const SpellCheckConfiguration.disabled(),
                    smartDashesType: SmartDashesType.disabled,
                    smartQuotesType: SmartQuotesType.disabled,
                    keyboardType:
                        keyboardType ??
                        (maxLines == 1
                            ? TextInputType.visiblePassword
                            : TextInputType.multiline),
                    minLines: minLines,
                    maxLines: maxLines,
                    style: _SettingsTextStyles.fieldValue.copyWith(
                      fontWeight: obscureText ? FontWeight.w500 : null,
                    ),
                    strutStyle: _SettingsTextStyles.fieldValueStrut,
                    decoration: InputDecoration(
                      hintText: hintText,
                      hintStyle: _SettingsTextStyles.fieldValue.copyWith(
                        color: OpenCrayColors.textTertiary,
                        fontWeight: FontWeight.w400,
                      ),
                      filled: true,
                      fillColor: Colors.transparent,
                      isCollapsed: true,
                      contentPadding: EdgeInsets.fromLTRB(
                        12,
                        minLines == 1 ? 14 : 12,
                        12,
                        minLines == 1 ? 14 : 12,
                      ),
                      border: InputBorder.none,
                    ),
                  ),
                ),
                if (trailingText != null) ...[
                  const SizedBox(width: 8),
                  Padding(
                    padding: const EdgeInsets.only(right: 12),
                    child: Text(
                      trailingText!,
                      style: _SettingsTextStyles.selectionMeta,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _PrototypeSelectionField extends StatelessWidget {
  const _PrototypeSelectionField({
    required this.label,
    required this.title,
    this.trailingLabel,
    this.onTap,
  });

  final String label;
  final String title;
  final String? trailingLabel;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: _SettingsTextStyles.fieldLabel),
        const SizedBox(height: 6),
        _PrototypeSelectionRow(
          title: title,
          trailingLabel: trailingLabel,
          compact: true,
          onTap: onTap,
        ),
      ],
    );
  }
}

class _HeaderActionChip extends StatelessWidget {
  const _HeaderActionChip({required this.label, required this.onTap});

  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final textStyle = Theme.of(context).textTheme.labelMedium?.copyWith(
      fontSize: 12,
      height: 16 / 12,
      fontWeight: FontWeight.w600,
      letterSpacing: 0,
      color: OpenCrayColors.primary,
    );
    return InkWell(
      borderRadius: BorderRadius.circular(999),
      onTap: onTap,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: const Color(0xFFF2F7FF),
          borderRadius: BorderRadius.circular(999),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
          child: Text(
            label,
            style: textStyle ?? _SettingsTextStyles.actionChip,
          ),
        ),
      ),
    );
  }
}

class _SettingsCard extends StatelessWidget {
  const _SettingsCard({
    required this.child,
    this.backgroundColor = Colors.white,
  });

  final Widget child;
  final Color backgroundColor;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Padding(padding: const EdgeInsets.all(16), child: child),
    );
  }
}

class _SegmentedSelector extends StatelessWidget {
  const _SegmentedSelector({required this.labels, required this.selectedIndex});

  final List<String> labels;
  final int selectedIndex;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFECEEF3),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.all(4),
        child: Row(
          children: [
            for (int index = 0; index < labels.length; index++)
              Expanded(
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 160),
                  curve: Curves.easeOutCubic,
                  decoration: BoxDecoration(
                    color: index == selectedIndex
                        ? Colors.white
                        : Colors.transparent,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    labels[index],
                    textAlign: TextAlign.center,
                    style: _SettingsTextStyles.valueChip.copyWith(
                      color: index == selectedIndex
                          ? OpenCrayColors.textPrimary
                          : OpenCrayColors.textSecondary,
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

class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.row});

  final SettingsRowSnapshot row;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(row.title, style: _SettingsTextStyles.rowTitle),
                if (row.subtitle != null) ...[
                  const SizedBox(height: 4),
                  Text(row.subtitle!, style: _SettingsTextStyles.rowSubtitle),
                ],
              ],
            ),
          ),
          const SizedBox(width: 12),
          if (row.trailingKind == SettingsRowTrailingKind.chevron)
            const Icon(
              Icons.chevron_right_rounded,
              size: 18,
              color: OpenCrayColors.textTertiary,
            )
          else if (row.trailingKind == SettingsRowTrailingKind.toggle)
            _PrototypeSwitch(value: row.toggleValue ?? false, onChanged: (_) {})
          else
            DecoratedBox(
              decoration: BoxDecoration(
                color: const Color(0xFFF3F4F7),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 5,
                ),
                child: Text(
                  row.valueLabel ?? '',
                  style: _SettingsTextStyles.valueChip,
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _HomeEntryRow extends StatelessWidget {
  const _HomeEntryRow({
    required this.title,
    required this.onTap,
    this.selected = false,
  });

  final String title;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            Expanded(
              child: Text(
                title,
                style: _SettingsTextStyles.homeRow.copyWith(
                  color: selected
                      ? OpenCrayColors.primary
                      : OpenCrayColors.textPrimary,
                ),
              ),
            ),
            Icon(
              Icons.chevron_right_rounded,
              size: 18,
              color: selected
                  ? OpenCrayColors.primary
                  : OpenCrayColors.textTertiary,
            ),
          ],
        ),
      ),
    );
  }
}

class _SettingsTextStyles {
  const _SettingsTextStyles._();

  static const TextStyle eyebrow = TextStyle(
    fontSize: 12,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: OpenCrayColors.textSecondary,
  );

  static const TextStyle pageTitle = TextStyle(
    fontSize: 28,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle pageTitleSubpage = TextStyle(
    fontSize: 28,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle subtitle = TextStyle(
    fontSize: 14,
    height: 1.35,
    color: OpenCrayColors.textSecondary,
  );

  static const TextStyle cardTitle = TextStyle(
    fontSize: 17,
    height: 1.25,
    fontWeight: FontWeight.w600,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle body = TextStyle(
    fontSize: 13,
    height: 1.35,
    color: OpenCrayColors.textSecondary,
  );

  static const TextStyle bodyStrong = TextStyle(
    fontSize: 15,
    height: 1.3,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle fieldLabel = TextStyle(
    fontSize: 13,
    height: 18 / 13,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textSecondary,
  );

  static const TextStyle fieldValue = TextStyle(
    fontSize: 15,
    height: 20 / 15,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textPrimary,
  );

  static const StrutStyle fieldValueStrut = StrutStyle(
    fontSize: 15,
    height: 20 / 15,
    forceStrutHeight: true,
  );

  static const TextStyle rowTitle = TextStyle(
    fontSize: 15,
    height: 1.25,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle rowSubtitle = TextStyle(
    fontSize: 13,
    height: 1.35,
    color: OpenCrayColors.textSecondary,
  );

  static const TextStyle valueChip = TextStyle(
    fontSize: 11,
    height: 14 / 11,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle selectionMeta = TextStyle(
    fontSize: 12,
    height: 16 / 12,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textTertiary,
  );

  static const TextStyle selectionChevron = TextStyle(
    fontSize: 16,
    height: 1.0,
    fontWeight: FontWeight.w500,
    color: Color(0xFFC7C7CC),
  );

  static const TextStyle actionChip = TextStyle(
    fontSize: 12,
    height: 16 / 12,
    fontWeight: FontWeight.w600,
    color: OpenCrayColors.primary,
  );

  static const TextStyle homeRow = TextStyle(
    fontSize: 16,
    height: 1.2,
    fontWeight: FontWeight.w500,
  );
}
