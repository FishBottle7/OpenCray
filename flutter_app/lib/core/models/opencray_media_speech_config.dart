class OpenCrayMediaProviderConfigSnapshot {
  const OpenCrayMediaProviderConfigSnapshot({
    required this.provider,
    required this.baseUrl,
    required this.endpoint,
    required this.model,
    this.authProtocol = 'bearer',
    this.apiKey = '',
  });

  final String provider;
  final String baseUrl;
  final String endpoint;
  final String model;
  final String authProtocol;
  final String apiKey;

  factory OpenCrayMediaProviderConfigSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayMediaProviderConfigSnapshot(
      provider: map['provider'] as String? ?? '',
      baseUrl: map['baseUrl'] as String? ?? '',
      endpoint: map['endpoint'] as String? ?? '',
      model: map['model'] as String? ?? '',
      authProtocol: map['authProtocol'] as String? ?? 'bearer',
      apiKey: map['apiKey'] as String? ?? '',
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'provider': provider,
    'baseUrl': baseUrl,
    'endpoint': endpoint,
    'model': model,
    'authProtocol': authProtocol,
    'apiKey': apiKey,
  };
}

class OpenCrayVoiceProviderConfigSnapshot {
  const OpenCrayVoiceProviderConfigSnapshot({
    required this.provider,
    required this.baseUrl,
    required this.endpoint,
    this.model = 'tts-1',
    required this.voicePreset,
    this.authProtocol = 'bearer',
    this.apiKey = '',
  });

  final String provider;
  final String baseUrl;
  final String endpoint;
  final String model;
  final String voicePreset;
  final String authProtocol;
  final String apiKey;

  factory OpenCrayVoiceProviderConfigSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayVoiceProviderConfigSnapshot(
      provider: map['provider'] as String? ?? '',
      baseUrl: map['baseUrl'] as String? ?? '',
      endpoint: map['endpoint'] as String? ?? '',
      model: map['model'] as String? ?? 'tts-1',
      voicePreset: map['voicePreset'] as String? ?? '',
      authProtocol: map['authProtocol'] as String? ?? 'bearer',
      apiKey: map['apiKey'] as String? ?? '',
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'provider': provider,
    'baseUrl': baseUrl,
    'endpoint': endpoint,
    'model': model,
    'voicePreset': voicePreset,
    'authProtocol': authProtocol,
    'apiKey': apiKey,
  };
}

class OpenCrayOnDeviceSttConfigSnapshot {
  const OpenCrayOnDeviceSttConfigSnapshot({
    required this.modelPackage,
    required this.downloadStatus,
  });

  final String modelPackage;
  final String downloadStatus;

  factory OpenCrayOnDeviceSttConfigSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayOnDeviceSttConfigSnapshot(
      modelPackage: map['modelPackage'] as String? ?? '',
      downloadStatus: map['downloadStatus'] as String? ?? '',
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'modelPackage': modelPackage,
    'downloadStatus': downloadStatus,
  };
}

class OpenCrayMediaSpeechConfigSnapshot {
  const OpenCrayMediaSpeechConfigSnapshot({
    required this.localeTag,
    required this.title,
    required this.subtitle,
    required this.imageGeneration,
    this.videoGeneration = const OpenCrayMediaProviderConfigSnapshot(
      provider: '',
      baseUrl: '',
      endpoint: '',
      model: '',
    ),
    required this.voiceGeneration,
    required this.sttRouteId,
    required this.externalStt,
    required this.onDeviceModel,
  });

  final String localeTag;
  final String title;
  final String subtitle;
  final OpenCrayMediaProviderConfigSnapshot imageGeneration;
  final OpenCrayMediaProviderConfigSnapshot videoGeneration;
  final OpenCrayVoiceProviderConfigSnapshot voiceGeneration;
  final String sttRouteId;
  final OpenCrayMediaProviderConfigSnapshot externalStt;
  final OpenCrayOnDeviceSttConfigSnapshot onDeviceModel;

  factory OpenCrayMediaSpeechConfigSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayMediaSpeechConfigSnapshot(
      localeTag: map['localeTag'] as String? ?? 'en',
      title: map['title'] as String? ?? 'Media & Speech',
      subtitle: map['subtitle'] as String? ?? '',
      imageGeneration: OpenCrayMediaProviderConfigSnapshot.fromMap(
        map['imageGeneration'] as Map<Object?, Object?>? ??
            const <Object?, Object?>{},
      ),
      videoGeneration: OpenCrayMediaProviderConfigSnapshot.fromMap(
        map['videoGeneration'] as Map<Object?, Object?>? ??
            const <Object?, Object?>{},
      ),
      voiceGeneration: OpenCrayVoiceProviderConfigSnapshot.fromMap(
        map['voiceGeneration'] as Map<Object?, Object?>? ??
            const <Object?, Object?>{},
      ),
      sttRouteId: map['sttRouteId'] as String? ?? 'on_device_model',
      externalStt: OpenCrayMediaProviderConfigSnapshot.fromMap(
        map['externalStt'] as Map<Object?, Object?>? ??
            const <Object?, Object?>{},
      ),
      onDeviceModel: OpenCrayOnDeviceSttConfigSnapshot.fromMap(
        map['onDeviceModel'] as Map<Object?, Object?>? ??
            const <Object?, Object?>{},
      ),
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'localeTag': localeTag,
    'title': title,
    'subtitle': subtitle,
    'imageGeneration': imageGeneration.toMap(),
    'videoGeneration': videoGeneration.toMap(),
    'voiceGeneration': voiceGeneration.toMap(),
    'sttRouteId': sttRouteId,
    'externalStt': externalStt.toMap(),
    'onDeviceModel': onDeviceModel.toMap(),
  };
}
