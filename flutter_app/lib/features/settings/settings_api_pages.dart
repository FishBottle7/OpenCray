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
            'Choose where OpenCray connects for search, media generation, and speech services.',
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
                  'Search keeps ordered slots. Media uses external APIs, while STT can switch between a hosted API and an on-device model package.',
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
  late final TextEditingController _voiceProviderController;
  late final TextEditingController _voiceBaseUrlController;
  late final TextEditingController _voiceEndpointController;
  late final TextEditingController _voicePresetController;
  late final TextEditingController _sttProviderController;
  late final TextEditingController _sttBaseUrlController;
  late final TextEditingController _sttEndpointController;
  late final TextEditingController _sttModelController;

  @override
  void initState() {
    super.initState();
    _imageProviderController = TextEditingController();
    _imageBaseUrlController = TextEditingController();
    _imageEndpointController = TextEditingController();
    _imageModelController = TextEditingController();
    _voiceProviderController = TextEditingController();
    _voiceBaseUrlController = TextEditingController();
    _voiceEndpointController = TextEditingController();
    _voicePresetController = TextEditingController();
    _sttProviderController = TextEditingController();
    _sttBaseUrlController = TextEditingController();
    _sttEndpointController = TextEditingController();
    _sttModelController = TextEditingController();
    _load();
  }

  @override
  void dispose() {
    _saveDebounce?.cancel();
    _imageProviderController.dispose();
    _imageBaseUrlController.dispose();
    _imageEndpointController.dispose();
    _imageModelController.dispose();
    _voiceProviderController.dispose();
    _voiceBaseUrlController.dispose();
    _voiceEndpointController.dispose();
    _voicePresetController.dispose();
    _sttProviderController.dispose();
    _sttBaseUrlController.dispose();
    _sttEndpointController.dispose();
    _sttModelController.dispose();
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
      _voiceProviderController,
      snapshot.voiceGeneration.provider,
    );
    _syncController(_voiceBaseUrlController, snapshot.voiceGeneration.baseUrl);
    _syncController(
      _voiceEndpointController,
      snapshot.voiceGeneration.endpoint,
    );
    _syncController(
      _voicePresetController,
      snapshot.voiceGeneration.voicePreset,
    );
    _syncController(_sttProviderController, snapshot.externalStt.provider);
    _syncController(_sttBaseUrlController, snapshot.externalStt.baseUrl);
    _syncController(_sttEndpointController, snapshot.externalStt.endpoint);
    _syncController(_sttModelController, snapshot.externalStt.model);
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
