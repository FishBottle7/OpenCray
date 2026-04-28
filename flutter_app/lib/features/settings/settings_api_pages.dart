part of 'settings_feature.dart';

class _ApiIntegrationsSettingsPage extends StatelessWidget {
  const _ApiIntegrationsSettingsPage({
    super.key,
    required this.onBack,
    required this.backLabel,
    required this.onOpenPage,
  });

  final VoidCallback onBack;
  final String backLabel;
  final ValueChanged<SettingsPage> onOpenPage;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: onBack, label: backLabel),
          const SizedBox(height: 8),
          const Text(
            'API Integrations',
            style: _SettingsTextStyles.pageTitleSubpage,
          ),
          const SizedBox(height: 8),
          const Text(
            'Choose where OpenCray connects for search, media, and cloud execution.',
            style: _SettingsTextStyles.subtitle,
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _SettingsNavigationRow(
                  title: 'Network & Search',
                  subtitle: 'Search slots, priority, and API keys',
                  onTap: () => onOpenPage(SettingsPage.networkSearch),
                ),
                const Divider(height: 1, color: OpenCrayColors.divider),
                _SettingsNavigationRow(
                  title: 'Media & Speech',
                  subtitle: 'Image and voice APIs, plus STT route',
                  onTap: () => onOpenPage(SettingsPage.mediaSpeech),
                ),
                const Divider(height: 1, color: OpenCrayColors.divider),
                _SettingsNavigationRow(
                  title: 'Sandbox Providers',
                  subtitle: 'Cloud execution backends, API keys, and routing',
                  onTap: () => onOpenPage(SettingsPage.sandboxProviders),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Routing rules', style: _SettingsTextStyles.cardTitle),
                SizedBox(height: 8),
                Text(
                  'Search keeps ordered slots. Media uses external APIs, while sandbox providers add optional cloud environments without removing local execution.',
                  style: _SettingsTextStyles.body,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SandboxProvidersSettingsPage extends StatefulWidget {
  const _SandboxProvidersSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
    required this.onOpenPage,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;
  final ValueChanged<SettingsPage> onOpenPage;

  @override
  State<_SandboxProvidersSettingsPage> createState() =>
      _SandboxProvidersSettingsPageState();
}

class _SandboxProvidersSettingsPageState
    extends State<_SandboxProvidersSettingsPage> {
  SandboxSettingsSnapshot? _snapshot;
  String? _loadError;

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
              key: ValueKey<String>('settings-sandbox-providers-loading'),
            )
          : _SettingsLoadErrorCard(
              title: 'Sandbox Providers',
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
          const Text(
            'Sandbox Providers',
            style: _SettingsTextStyles.pageTitleSubpage,
          ),
          const SizedBox(height: 8),
          const Text(
            'Keep local execution available while enabling remote sandboxes when needed.',
            style: _SettingsTextStyles.subtitle,
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _SettingsNavigationRow(
                  title: 'E2B',
                  subtitle: _providerStatusLine(snapshot),
                  onTap: () => widget.onOpenPage(SettingsPage.sandboxE2b),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Execution environments',
                  style: _SettingsTextStyles.cardTitle,
                ),
                SizedBox(height: 8),
                Text(
                  'Cloud providers extend Python and future command execution without replacing the on-device runtime.',
                  style: _SettingsTextStyles.body,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadSandboxSettings();
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

  String _providerStatusLine(SandboxSettingsSnapshot snapshot) {
    if (snapshot.enabled && snapshot.apiKeyConfigured) {
      return 'Configured for dual local/cloud execution';
    }
    if (snapshot.enabled) {
      return 'Enabled, but the E2B API key still needs to be added';
    }
    return 'Disabled by default; local execution remains available';
  }
}

class _SandboxE2bSettingsPage extends StatefulWidget {
  const _SandboxE2bSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_SandboxE2bSettingsPage> createState() =>
      _SandboxE2bSettingsPageState();
}

class _SandboxE2bSettingsPageState extends State<_SandboxE2bSettingsPage> {
  SandboxSettingsSnapshot? _snapshot;
  String? _loadError;
  bool _isSaving = false;
  bool _hasQueuedSave = false;
  Timer? _saveDebounce;

  late final TextEditingController _apiKeyController;
  late final TextEditingController _templateIdController;
  late final TextEditingController _idleTimeoutController;
  late final TextEditingController _startupTimeoutController;
  late final TextEditingController _requestTimeoutController;

  @override
  void initState() {
    super.initState();
    _apiKeyController = TextEditingController();
    _templateIdController = TextEditingController();
    _idleTimeoutController = TextEditingController();
    _startupTimeoutController = TextEditingController();
    _requestTimeoutController = TextEditingController();
    _load();
  }

  @override
  void dispose() {
    _saveDebounce?.cancel();
    _apiKeyController.dispose();
    _templateIdController.dispose();
    _idleTimeoutController.dispose();
    _startupTimeoutController.dispose();
    _requestTimeoutController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-sandbox-e2b-loading'),
            )
          : _SettingsLoadErrorCard(
              title: 'E2B',
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
          const Text('E2B', style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          const Text(
            'Add an API key and choose how OpenCray routes to the cloud sandbox.',
            style: _SettingsTextStyles.subtitle,
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Remote Linux sandbox',
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                const Text(
                  'Enable E2B for cloud execution while keeping local tools available on this device.',
                  style: _SettingsTextStyles.body,
                ),
                const SizedBox(height: 12),
                _SandboxToggleRow(
                  title: 'Enable E2B',
                  subtitle:
                      'Allow OpenCray to route Python and future execution tasks into E2B.',
                  value: snapshot.enabled,
                  onChanged: (value) {
                    _updateSnapshot(snapshot.copyWith(enabled: value));
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _PrototypeField(
                  label: 'E2B API key',
                  controller: _apiKeyController,
                  hintText: 'e2b_...',
                  obscureText: true,
                  trailing: _apiKeyController.text.trim().isEmpty
                      ? null
                      : _FieldClearButton(
                          buttonKey: const ValueKey<String>(
                            'settings-sandbox-api-key-clear',
                          ),
                          onTap: _clearApiKey,
                        ),
                  onChanged: (_) {
                    setState(() {});
                    _scheduleSave();
                  },
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.apiKeyConfigured
                      ? 'Stored securely and only used for sandbox calls.'
                      : 'Add the provider key here before switching runs into the cloud environment.',
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
                const Text(
                  'Execution defaults',
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 12),
                _SandboxSegmentedField(
                  label: 'Default backend',
                  options: const <String>['local', 'sandbox'],
                  selectedValue: _backendForUi(snapshot.defaultBackend),
                  labelBuilder: (value) => switch (value) {
                    'sandbox' => 'Run in cloud',
                    _ => 'Run locally',
                  },
                  onSelected: (value) {
                    _updateSnapshot(snapshot.copyWith(defaultBackend: value));
                  },
                ),
                const SizedBox(height: 12),
                _SandboxSegmentedField(
                  label: 'Session mode',
                  options: const <String>['ephemeral', 'sticky'],
                  selectedValue: _sessionModeForUi(snapshot.sessionMode),
                  labelBuilder: (value) => switch (value) {
                    'sticky' => 'Sticky',
                    _ => 'Ephemeral',
                  },
                  onSelected: (value) {
                    _updateSnapshot(snapshot.copyWith(sessionMode: value));
                  },
                ),
                const SizedBox(height: 12),
                _SandboxSegmentedField(
                  label: 'Timeout action',
                  options: const <String>['kill', 'pause'],
                  selectedValue: _timeoutActionForUi(snapshot.timeoutAction),
                  labelBuilder: (value) => switch (value) {
                    'pause' => 'Pause',
                    _ => 'Kill',
                  },
                  onSelected: (value) {
                    _updateSnapshot(snapshot.copyWith(timeoutAction: value));
                  },
                ),
                const SizedBox(height: 12),
                _SandboxToggleRow(
                  title: 'Auto resume',
                  subtitle:
                      'Reconnect to sticky sessions when the runtime starts again.',
                  value: snapshot.autoResume,
                  onChanged: (value) {
                    _updateSnapshot(snapshot.copyWith(autoResume: value));
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Session & limits',
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Template ID',
                  controller: _templateIdController,
                  hintText: 'default',
                  onChanged: (_) => _scheduleSave(),
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Idle timeout (minutes)',
                  controller: _idleTimeoutController,
                  hintText: '15',
                  keyboardType: TextInputType.number,
                  onChanged: (_) => _scheduleSave(),
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Startup timeout (ms)',
                  controller: _startupTimeoutController,
                  hintText: '30000',
                  keyboardType: TextInputType.number,
                  onChanged: (_) => _scheduleSave(),
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Request timeout (ms)',
                  controller: _requestTimeoutController,
                  hintText: '300000',
                  keyboardType: TextInputType.number,
                  onChanged: (_) => _scheduleSave(),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadSandboxSettings();
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = null;
        _applySnapshot(snapshot);
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

  void _applySnapshot(SandboxSettingsSnapshot snapshot) {
    _snapshot = snapshot;
    _apiKeyController.text = snapshot.e2bApiKey;
    _templateIdController.text = snapshot.templateId;
    _idleTimeoutController.text = snapshot.idleTimeoutMinutes.toString();
    _startupTimeoutController.text = snapshot.startupTimeoutMs.toString();
    _requestTimeoutController.text = snapshot.requestTimeoutMs.toString();
  }

  void _updateSnapshot(SandboxSettingsSnapshot snapshot) {
    setState(() {
      _snapshot = snapshot;
    });
    _scheduleSave();
  }

  void _clearApiKey() {
    final snapshot = _snapshot;
    if (snapshot == null || _apiKeyController.text.isEmpty) {
      return;
    }
    _apiKeyController.clear();
    _updateSnapshot(snapshot.copyWith(e2bApiKey: '', apiKeyConfigured: false));
  }

  void _scheduleSave() {
    _saveDebounce?.cancel();
    _saveDebounce = Timer(const Duration(milliseconds: 260), _saveNow);
  }

  Future<void> _saveNow() async {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return;
    }
    if (_isSaving) {
      _hasQueuedSave = true;
      return;
    }
    _saveDebounce?.cancel();
    final request = snapshot.copyWith(
      templateId: _templateIdController.text.trim(),
      e2bApiKey: _apiKeyController.text.trim(),
      apiKeyConfigured: _apiKeyController.text.trim().isNotEmpty,
      idleTimeoutMinutes: _readInt(
        _idleTimeoutController.text,
        snapshot.idleTimeoutMinutes,
      ),
      startupTimeoutMs: _readInt(
        _startupTimeoutController.text,
        snapshot.startupTimeoutMs,
      ),
      requestTimeoutMs: _readInt(
        _requestTimeoutController.text,
        snapshot.requestTimeoutMs,
      ),
    );
    setState(() {
      _isSaving = true;
      _snapshot = request;
    });
    try {
      final saved = await widget.facade.saveSandboxSettings(request);
      if (!mounted) {
        return;
      }
      setState(() {
        _applySnapshot(saved);
      });
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(error.toString().replaceFirst('Exception: ', '')),
          ),
        );
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

  int _readInt(String rawValue, int fallback) =>
      int.tryParse(rawValue.trim()) ?? fallback;

  String _backendForUi(String rawValue) =>
      rawValue == 'sandbox' ? 'sandbox' : 'local';

  String _sessionModeForUi(String rawValue) =>
      rawValue == 'sticky' ? 'sticky' : 'ephemeral';

  String _timeoutActionForUi(String rawValue) =>
      rawValue == 'pause' ? 'pause' : 'kill';
}

class _MediaSpeechSettingsPage extends StatefulWidget {
  const _MediaSpeechSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_MediaSpeechSettingsPage> createState() =>
      _MediaSpeechSettingsPageState();
}

class _MediaSpeechSettingsPageState extends State<_MediaSpeechSettingsPage> {
  MediaSpeechConfigSnapshot? _snapshot;
  String? _loadError;
  bool _isSaving = false;
  bool _hasQueuedSave = false;
  Timer? _saveDebounce;

  late final TextEditingController _imageProviderController;
  late final TextEditingController _imageBaseUrlController;
  late final TextEditingController _imageEndpointController;
  late final TextEditingController _imageModelController;
  late final TextEditingController _imageAuthProtocolController;
  late final TextEditingController _imageApiKeyController;
  late final TextEditingController _videoProviderController;
  late final TextEditingController _videoBaseUrlController;
  late final TextEditingController _videoEndpointController;
  late final TextEditingController _videoModelController;
  late final TextEditingController _videoAuthProtocolController;
  late final TextEditingController _videoApiKeyController;
  late final TextEditingController _voiceProviderController;
  late final TextEditingController _voiceBaseUrlController;
  late final TextEditingController _voiceEndpointController;
  late final TextEditingController _voiceModelController;
  late final TextEditingController _voicePresetController;
  late final TextEditingController _voiceAuthProtocolController;
  late final TextEditingController _voiceApiKeyController;
  late final TextEditingController _sttProviderController;
  late final TextEditingController _sttBaseUrlController;
  late final TextEditingController _sttEndpointController;
  late final TextEditingController _sttModelController;
  late final TextEditingController _sttAuthProtocolController;
  late final TextEditingController _sttApiKeyController;

  @override
  void initState() {
    super.initState();
    _imageProviderController = TextEditingController();
    _imageBaseUrlController = TextEditingController();
    _imageEndpointController = TextEditingController();
    _imageModelController = TextEditingController();
    _imageAuthProtocolController = TextEditingController();
    _imageApiKeyController = TextEditingController();
    _videoProviderController = TextEditingController();
    _videoBaseUrlController = TextEditingController();
    _videoEndpointController = TextEditingController();
    _videoModelController = TextEditingController();
    _videoAuthProtocolController = TextEditingController();
    _videoApiKeyController = TextEditingController();
    _voiceProviderController = TextEditingController();
    _voiceBaseUrlController = TextEditingController();
    _voiceEndpointController = TextEditingController();
    _voiceModelController = TextEditingController();
    _voicePresetController = TextEditingController();
    _voiceAuthProtocolController = TextEditingController();
    _voiceApiKeyController = TextEditingController();
    _sttProviderController = TextEditingController();
    _sttBaseUrlController = TextEditingController();
    _sttEndpointController = TextEditingController();
    _sttModelController = TextEditingController();
    _sttAuthProtocolController = TextEditingController();
    _sttApiKeyController = TextEditingController();
    _load();
  }

  @override
  void dispose() {
    _saveDebounce?.cancel();
    _imageProviderController.dispose();
    _imageBaseUrlController.dispose();
    _imageEndpointController.dispose();
    _imageModelController.dispose();
    _imageAuthProtocolController.dispose();
    _imageApiKeyController.dispose();
    _videoProviderController.dispose();
    _videoBaseUrlController.dispose();
    _videoEndpointController.dispose();
    _videoModelController.dispose();
    _videoAuthProtocolController.dispose();
    _videoApiKeyController.dispose();
    _voiceProviderController.dispose();
    _voiceBaseUrlController.dispose();
    _voiceEndpointController.dispose();
    _voiceModelController.dispose();
    _voicePresetController.dispose();
    _voiceAuthProtocolController.dispose();
    _voiceApiKeyController.dispose();
    _sttProviderController.dispose();
    _sttBaseUrlController.dispose();
    _sttEndpointController.dispose();
    _sttModelController.dispose();
    _sttAuthProtocolController.dispose();
    _sttApiKeyController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-media-speech-loading'),
            )
          : _SettingsLoadErrorCard(
              title: 'Media & Speech',
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
          _MediaSpeechServiceCard(
            title: 'Image generation',
            child: Column(
              children: [
                _PrototypeField(
                  label: 'Provider',
                  controller: _imageProviderController,
                  hintText: 'Fal AI',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        imageGeneration: _snapshot!.imageGeneration.copyWith(
                          provider: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Base URL',
                  controller: _imageBaseUrlController,
                  hintText: 'https://api.fal.ai',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        imageGeneration: _snapshot!.imageGeneration.copyWith(
                          baseUrl: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Endpoint',
                  controller: _imageEndpointController,
                  hintText: '/v1/images',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        imageGeneration: _snapshot!.imageGeneration.copyWith(
                          endpoint: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Model',
                  controller: _imageModelController,
                  hintText: 'flux-pro',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        imageGeneration: _snapshot!.imageGeneration.copyWith(
                          model: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Auth protocol',
                  controller: _imageAuthProtocolController,
                  hintText: 'bearer',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        imageGeneration: _snapshot!.imageGeneration.copyWith(
                          authProtocol: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'API key',
                  controller: _imageApiKeyController,
                  hintText: 'sk_...',
                  obscureText: true,
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        imageGeneration: _snapshot!.imageGeneration.copyWith(
                          apiKey: value,
                        ),
                      ),
                    );
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _MediaSpeechServiceCard(
            title: 'Video generation',
            child: Column(
              children: [
                _PrototypeField(
                  label: 'Provider',
                  controller: _videoProviderController,
                  hintText: 'Runway',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        videoGeneration: _snapshot!.videoGeneration.copyWith(
                          provider: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Base URL',
                  controller: _videoBaseUrlController,
                  hintText: 'https://api.runwayml.com',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        videoGeneration: _snapshot!.videoGeneration.copyWith(
                          baseUrl: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Endpoint',
                  controller: _videoEndpointController,
                  hintText: '/v1/videos',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        videoGeneration: _snapshot!.videoGeneration.copyWith(
                          endpoint: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Model',
                  controller: _videoModelController,
                  hintText: 'gen4_turbo',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        videoGeneration: _snapshot!.videoGeneration.copyWith(
                          model: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Auth protocol',
                  controller: _videoAuthProtocolController,
                  hintText: 'bearer',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        videoGeneration: _snapshot!.videoGeneration.copyWith(
                          authProtocol: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'API key',
                  controller: _videoApiKeyController,
                  hintText: 'sk_...',
                  obscureText: true,
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        videoGeneration: _snapshot!.videoGeneration.copyWith(
                          apiKey: value,
                        ),
                      ),
                    );
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _MediaSpeechServiceCard(
            title: 'Voice generation',
            child: Column(
              children: [
                _PrototypeField(
                  label: 'Provider',
                  controller: _voiceProviderController,
                  hintText: 'OpenAI TTS',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        voiceGeneration: _snapshot!.voiceGeneration.copyWith(
                          provider: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Base URL',
                  controller: _voiceBaseUrlController,
                  hintText: 'https://api.openai.com',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        voiceGeneration: _snapshot!.voiceGeneration.copyWith(
                          baseUrl: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Endpoint',
                  controller: _voiceEndpointController,
                  hintText: '/v1/audio/speech',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        voiceGeneration: _snapshot!.voiceGeneration.copyWith(
                          endpoint: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Model',
                  controller: _voiceModelController,
                  hintText: 'tts-1',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        voiceGeneration: _snapshot!.voiceGeneration.copyWith(
                          model: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Voice preset',
                  controller: _voicePresetController,
                  hintText: 'alloy · calm',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        voiceGeneration: _snapshot!.voiceGeneration.copyWith(
                          voicePreset: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'Auth protocol',
                  controller: _voiceAuthProtocolController,
                  hintText: 'bearer',
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        voiceGeneration: _snapshot!.voiceGeneration.copyWith(
                          authProtocol: value,
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: 'API key',
                  controller: _voiceApiKeyController,
                  hintText: 'sk_...',
                  obscureText: true,
                  onChanged: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        voiceGeneration: _snapshot!.voiceGeneration.copyWith(
                          apiKey: value,
                        ),
                      ),
                    );
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _MediaSpeechServiceCard(
            title: 'Speech-to-text',
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _InteractiveSegmentedSelector(
                  labels: const <String>['external_api', 'on_device_model'],
                  selectedId: snapshot.sttRoute.id,
                  labelBuilder: (value) {
                    switch (value) {
                      case 'external_api':
                        return 'External API';
                      case 'on_device_model':
                      default:
                        return 'On-device Model';
                    }
                  },
                  onSelected: (value) {
                    _updateSnapshot(
                      _snapshot!.copyWith(
                        sttRoute: mediaSpeechSttRouteFromId(value),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _MediaSpeechInfoField(
                  label: 'Selected route',
                  value: snapshot.sttRoute == MediaSpeechSttRoute.externalApi
                      ? 'External API'
                      : 'On-device Model',
                ),
                if (snapshot.sttRoute == MediaSpeechSttRoute.externalApi) ...[
                  const SizedBox(height: 12),
                  _PrototypeField(
                    label: 'Provider',
                    controller: _sttProviderController,
                    hintText: 'OpenAI Whisper',
                    onChanged: (value) {
                      _updateSnapshot(
                        _snapshot!.copyWith(
                          externalStt: _snapshot!.externalStt.copyWith(
                            provider: value,
                          ),
                        ),
                      );
                    },
                  ),
                  const SizedBox(height: 12),
                  _PrototypeField(
                    label: 'Base URL',
                    controller: _sttBaseUrlController,
                    hintText: 'https://api.openai.com',
                    onChanged: (value) {
                      _updateSnapshot(
                        _snapshot!.copyWith(
                          externalStt: _snapshot!.externalStt.copyWith(
                            baseUrl: value,
                          ),
                        ),
                      );
                    },
                  ),
                  const SizedBox(height: 12),
                  _PrototypeField(
                    label: 'Endpoint',
                    controller: _sttEndpointController,
                    hintText: '/v1/audio/transcriptions',
                    onChanged: (value) {
                      _updateSnapshot(
                        _snapshot!.copyWith(
                          externalStt: _snapshot!.externalStt.copyWith(
                            endpoint: value,
                          ),
                        ),
                      );
                    },
                  ),
                  const SizedBox(height: 12),
                  _PrototypeField(
                    label: 'Model',
                    controller: _sttModelController,
                    hintText: 'whisper-1',
                    onChanged: (value) {
                      _updateSnapshot(
                        _snapshot!.copyWith(
                          externalStt: _snapshot!.externalStt.copyWith(
                            model: value,
                          ),
                        ),
                      );
                    },
                  ),
                  const SizedBox(height: 12),
                  _PrototypeField(
                    label: 'Auth protocol',
                    controller: _sttAuthProtocolController,
                    hintText: 'bearer',
                    onChanged: (value) {
                      _updateSnapshot(
                        _snapshot!.copyWith(
                          externalStt: _snapshot!.externalStt.copyWith(
                            authProtocol: value,
                          ),
                        ),
                      );
                    },
                  ),
                  const SizedBox(height: 12),
                  _PrototypeField(
                    label: 'API key',
                    controller: _sttApiKeyController,
                    hintText: 'sk_...',
                    obscureText: true,
                    onChanged: (value) {
                      _updateSnapshot(
                        _snapshot!.copyWith(
                          externalStt: _snapshot!.externalStt.copyWith(
                            apiKey: value,
                          ),
                        ),
                      );
                    },
                  ),
                ] else ...[
                  const SizedBox(height: 12),
                  _MediaSpeechInfoField(
                    label: 'Model package',
                    value: snapshot.onDeviceModel.modelPackage,
                  ),
                  const SizedBox(height: 12),
                  _MediaSpeechInfoField(
                    label: 'Download',
                    value: snapshot.onDeviceModel.downloadStatus,
                  ),
                ],
              ],
            ),
          ),
          if (_isSaving) ...[
            const SizedBox(height: 12),
            const Text(
              'Saving media settings…',
              style: _SettingsTextStyles.selectionMeta,
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadMediaSpeechConfig();
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = null;
        _applySnapshot(snapshot);
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

  void _applySnapshot(MediaSpeechConfigSnapshot snapshot) {
    _snapshot = snapshot;
    _syncController(
      _imageProviderController,
      snapshot.imageGeneration.provider,
    );
    _syncController(_imageBaseUrlController, snapshot.imageGeneration.baseUrl);
    _syncController(
      _imageEndpointController,
      snapshot.imageGeneration.endpoint,
    );
    _syncController(_imageModelController, snapshot.imageGeneration.model);
    _syncController(
      _imageAuthProtocolController,
      snapshot.imageGeneration.authProtocol,
    );
    _syncController(_imageApiKeyController, snapshot.imageGeneration.apiKey);
    _syncController(
      _videoProviderController,
      snapshot.videoGeneration.provider,
    );
    _syncController(_videoBaseUrlController, snapshot.videoGeneration.baseUrl);
    _syncController(
      _videoEndpointController,
      snapshot.videoGeneration.endpoint,
    );
    _syncController(_videoModelController, snapshot.videoGeneration.model);
    _syncController(
      _videoAuthProtocolController,
      snapshot.videoGeneration.authProtocol,
    );
    _syncController(_videoApiKeyController, snapshot.videoGeneration.apiKey);
    _syncController(
      _voiceProviderController,
      snapshot.voiceGeneration.provider,
    );
    _syncController(_voiceBaseUrlController, snapshot.voiceGeneration.baseUrl);
    _syncController(
      _voiceEndpointController,
      snapshot.voiceGeneration.endpoint,
    );
    _syncController(_voiceModelController, snapshot.voiceGeneration.model);
    _syncController(
      _voicePresetController,
      snapshot.voiceGeneration.voicePreset,
    );
    _syncController(
      _voiceAuthProtocolController,
      snapshot.voiceGeneration.authProtocol,
    );
    _syncController(_voiceApiKeyController, snapshot.voiceGeneration.apiKey);
    _syncController(_sttProviderController, snapshot.externalStt.provider);
    _syncController(_sttBaseUrlController, snapshot.externalStt.baseUrl);
    _syncController(_sttEndpointController, snapshot.externalStt.endpoint);
    _syncController(_sttModelController, snapshot.externalStt.model);
    _syncController(
      _sttAuthProtocolController,
      snapshot.externalStt.authProtocol,
    );
    _syncController(_sttApiKeyController, snapshot.externalStt.apiKey);
  }

  void _syncController(TextEditingController controller, String value) {
    if (controller.text == value) {
      return;
    }
    controller.value = controller.value.copyWith(
      text: value,
      selection: TextSelection.collapsed(offset: value.length),
      composing: TextRange.empty,
    );
  }

  void _updateSnapshot(MediaSpeechConfigSnapshot snapshot) {
    setState(() {
      _snapshot = snapshot;
    });
    _scheduleSave();
  }

  void _scheduleSave() {
    _saveDebounce?.cancel();
    _saveDebounce = Timer(const Duration(milliseconds: 350), _saveNow);
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
      final updatedSnapshot = await widget.facade.saveMediaSpeechConfig(
        snapshot,
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _applySnapshot(updatedSnapshot);
      });
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(error.toString().replaceFirst('Exception: ', '')),
          ),
        );
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
}

class _SettingsNavigationRow extends StatelessWidget {
  const _SettingsNavigationRow({
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 14),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: _SettingsTextStyles.rowTitle),
                  const SizedBox(height: 4),
                  Text(subtitle, style: _SettingsTextStyles.rowSubtitle),
                ],
              ),
            ),
            const SizedBox(width: 12),
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

class _MediaSpeechServiceCard extends StatelessWidget {
  const _MediaSpeechServiceCard({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 12),
          child,
        ],
      ),
    );
  }
}

class _MediaSpeechInfoField extends StatelessWidget {
  const _MediaSpeechInfoField({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: _SettingsTextStyles.fieldLabel),
        const SizedBox(height: 6),
        _PrototypeFieldSurface(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    value,
                    style: _SettingsTextStyles.fieldValue,
                    strutStyle: _SettingsTextStyles.fieldValueStrut,
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

class _SandboxToggleRow extends StatelessWidget {
  const _SandboxToggleRow({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
  });

  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: _SettingsTextStyles.rowTitle),
              const SizedBox(height: 4),
              Text(subtitle, style: _SettingsTextStyles.rowSubtitle),
            ],
          ),
        ),
        const SizedBox(width: 12),
        _PrototypeSwitch(value: value, onChanged: onChanged),
      ],
    );
  }
}

class _SandboxSegmentedField extends StatelessWidget {
  const _SandboxSegmentedField({
    required this.label,
    required this.options,
    required this.selectedValue,
    required this.labelBuilder,
    required this.onSelected,
  });

  final String label;
  final List<String> options;
  final String selectedValue;
  final String Function(String value) labelBuilder;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: _SettingsTextStyles.fieldLabel),
        const SizedBox(height: 6),
        _InteractiveSegmentedSelector(
          labels: options,
          selectedId: selectedValue,
          labelBuilder: labelBuilder,
          onSelected: onSelected,
        ),
      ],
    );
  }
}
