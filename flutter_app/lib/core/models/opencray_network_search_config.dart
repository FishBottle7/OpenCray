class OpenCrayNetworkSearchSlotSnapshot {
  const OpenCrayNetworkSearchSlotSnapshot({
    required this.id,
    required this.providerId,
    required this.label,
    required this.baseUrl,
    required this.model,
    required this.apiKey,
    required this.enabled,
  });

  final String id;
  final String providerId;
  final String label;
  final String baseUrl;
  final String model;
  final String apiKey;
  final bool enabled;

  factory OpenCrayNetworkSearchSlotSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayNetworkSearchSlotSnapshot(
      id: map['id'] as String? ?? '',
      providerId: map['providerId'] as String? ?? 'exa',
      label: map['label'] as String? ?? '',
      baseUrl: map['baseUrl'] as String? ?? '',
      model: map['model'] as String? ?? '',
      apiKey: map['apiKey'] as String? ?? '',
      enabled: map['enabled'] as bool? ?? true,
    );
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'id': id,
    'providerId': providerId,
    'label': label,
    'baseUrl': baseUrl,
    'model': model,
    'apiKey': apiKey,
    'enabled': enabled,
  };
}

class OpenCrayNetworkSearchConfigSnapshot {
  const OpenCrayNetworkSearchConfigSnapshot({
    required this.localeTag,
    required this.title,
    required this.subtitle,
    required this.slots,
  });

  final String localeTag;
  final String title;
  final String subtitle;
  final List<OpenCrayNetworkSearchSlotSnapshot> slots;

  factory OpenCrayNetworkSearchConfigSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawSlots = map['slots'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayNetworkSearchConfigSnapshot(
      localeTag: map['localeTag'] as String? ?? 'en',
      title: map['title'] as String? ?? 'Network & Search',
      subtitle: map['subtitle'] as String? ?? '',
      slots: rawSlots
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayNetworkSearchSlotSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}
