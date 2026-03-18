class OpenCraySkillsSnapshot {
  const OpenCraySkillsSnapshot({
    required this.installedSkills,
    required this.installSources,
    required this.suggestedSkills,
  });

  final List<OpenCrayInstalledSkillSnapshot> installedSkills;
  final List<OpenCraySkillInstallSourceSnapshot> installSources;
  final List<OpenCraySuggestedSkillSnapshot> suggestedSkills;

  factory OpenCraySkillsSnapshot.fromMap(Map<Object?, Object?> payload) {
    return OpenCraySkillsSnapshot(
      installedSkills: _requireList(payload['installedSkills'])
          .map(_requireMap)
          .map(OpenCrayInstalledSkillSnapshot.fromMap)
          .toList(growable: false),
      installSources: _requireList(payload['installSources'])
          .map(_requireMap)
          .map(OpenCraySkillInstallSourceSnapshot.fromMap)
          .toList(growable: false),
      suggestedSkills: _requireList(payload['suggestedSkills'])
          .map(_requireMap)
          .map(OpenCraySuggestedSkillSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayInstalledSkillSnapshot {
  const OpenCrayInstalledSkillSnapshot({
    required this.id,
    required this.name,
    required this.description,
    required this.isEnabled,
    required this.canDelete,
    required this.sourceDirectoryPath,
  });

  final String id;
  final String name;
  final String description;
  final bool isEnabled;
  final bool canDelete;
  final String sourceDirectoryPath;

  factory OpenCrayInstalledSkillSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayInstalledSkillSnapshot(
      id: payload['id'] as String? ?? '',
      name: payload['name'] as String? ?? '',
      description: payload['description'] as String? ?? '',
      isEnabled: payload['isEnabled'] as bool? ?? false,
      canDelete: payload['canDelete'] as bool? ?? false,
      sourceDirectoryPath: payload['sourceDirectoryPath'] as String? ?? '',
    );
  }
}

class OpenCraySkillInstallSourceSnapshot {
  const OpenCraySkillInstallSourceSnapshot({
    required this.id,
    required this.title,
    required this.subtitle,
    required this.ctaLabel,
    required this.isAvailable,
  });

  final String id;
  final String title;
  final String subtitle;
  final String ctaLabel;
  final bool isAvailable;

  factory OpenCraySkillInstallSourceSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCraySkillInstallSourceSnapshot(
      id: payload['id'] as String? ?? '',
      title: payload['title'] as String? ?? '',
      subtitle: payload['subtitle'] as String? ?? '',
      ctaLabel:
          payload['ctaLabel'] as String? ??
          payload['actionLabel'] as String? ??
          '',
      isAvailable: payload['isAvailable'] as bool? ?? false,
    );
  }
}

class OpenCraySuggestedSkillSnapshot {
  const OpenCraySuggestedSkillSnapshot({
    required this.id,
    required this.name,
    required this.description,
    required this.sourceRef,
    required this.sourceLabel,
  });

  final String id;
  final String name;
  final String description;
  final String sourceRef;
  final String sourceLabel;

  factory OpenCraySuggestedSkillSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCraySuggestedSkillSnapshot(
      id: payload['id'] as String? ?? '',
      name: payload['name'] as String? ?? '',
      description: payload['description'] as String? ?? '',
      sourceRef:
          payload['sourceRef'] as String? ??
          payload['installRef'] as String? ??
          payload['id'] as String? ??
          '',
      sourceLabel: payload['sourceLabel'] as String? ?? '',
    );
  }
}

class OpenCraySkillInstructionsSnapshot {
  const OpenCraySkillInstructionsSnapshot({
    required this.id,
    required this.name,
    required this.description,
    required this.markdownBody,
    required this.sourceDirectoryPath,
    required this.isEnabled,
    required this.canDelete,
  });

  final String id;
  final String name;
  final String description;
  final String markdownBody;
  final String sourceDirectoryPath;
  final bool isEnabled;
  final bool canDelete;

  factory OpenCraySkillInstructionsSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCraySkillInstructionsSnapshot(
      id: payload['id'] as String? ?? '',
      name: payload['name'] as String? ?? '',
      description: payload['description'] as String? ?? '',
      markdownBody:
          payload['markdownBody'] as String? ??
          payload['body'] as String? ??
          '',
      sourceDirectoryPath: payload['sourceDirectoryPath'] as String? ?? '',
      isEnabled: payload['isEnabled'] as bool? ?? false,
      canDelete: payload['canDelete'] as bool? ?? false,
    );
  }
}

List<Object?> _requireList(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <Object?>[];
  }
  return list;
}

Map<Object?, Object?> _requireMap(Object? payload) {
  final map = payload as Map<Object?, Object?>?;
  if (map == null) {
    throw const FormatException('Expected a map payload from host bridge.');
  }
  return map;
}
