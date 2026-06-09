import '../../app/opencray_tabs.dart';
import '../../features/settings/settings_models.dart';
import 'opencray_chat_snapshot.dart' show OpenCrayHostLifecycleSnapshot;

class OpenCrayShellSnapshot {
  const OpenCrayShellSnapshot({
    required this.initialTab,
    this.initialSettingsPage = SettingsPage.home,
    required this.localeTag,
    required this.hostLabel,
    required this.hostSummary,
    required this.isHostConnected,
    this.flutterAppInstanceId,
    this.bridgeInstanceId,
    this.localRuntimeServerState,
    this.hostLifecycle,
    this.runtimeOwnerLifecycle,
    this.runtimeOwnerWorkSummary,
    this.runtimeServiceLifecycle,
    this.runtimeServiceWorkState,
    this.runtimeServiceKeepAliveState,
    this.runtimeServiceConnectionState,
  });

  final OpenCrayTab initialTab;
  final SettingsPage initialSettingsPage;
  final String localeTag;
  final String hostLabel;
  final String hostSummary;
  final bool isHostConnected;
  final String? flutterAppInstanceId;
  final String? bridgeInstanceId;
  final OpenCrayLocalRuntimeServerStateSnapshot? localRuntimeServerState;
  final OpenCrayHostLifecycleSnapshot? hostLifecycle;
  final OpenCrayHostLifecycleSnapshot? runtimeOwnerLifecycle;
  final OpenCrayRuntimeOwnerWorkSummarySnapshot? runtimeOwnerWorkSummary;
  final OpenCrayRuntimeServiceLifecycleSnapshot? runtimeServiceLifecycle;
  final OpenCrayRuntimeServiceWorkStateSnapshot? runtimeServiceWorkState;
  final OpenCrayRuntimeServiceKeepAliveStateSnapshot?
  runtimeServiceKeepAliveState;
  final OpenCrayRuntimeServiceConnectionStateSnapshot?
  runtimeServiceConnectionState;

  factory OpenCrayShellSnapshot.fromMap(
    Map<Object?, Object?> map, {
    required String defaultHostSummary,
  }) {
    final rawHostLifecycle = map['hostLifecycle'];
    final rawLocalRuntimeServerState = map['localRuntimeServerState'];
    final rawRuntimeOwnerLifecycle = map['runtimeOwnerLifecycle'];
    final rawRuntimeOwnerWorkSummary = map['runtimeOwnerWorkSummary'];
    final rawRuntimeServiceLifecycle = map['runtimeServiceLifecycle'];
    final rawRuntimeServiceWorkState = map['runtimeServiceWorkState'];
    final rawRuntimeServiceKeepAliveState = map['runtimeServiceKeepAliveState'];
    final rawRuntimeServiceConnectionState =
        map['runtimeServiceConnectionState'];
    return OpenCrayShellSnapshot(
      initialTab: _parseTab(map['initialTab'] as String?),
      initialSettingsPage: _parseSettingsPage(
        map['initialSettingsRouteId'] as String?,
      ),
      localeTag: map['localeTag'] as String? ?? 'en',
      hostLabel: map['hostLabel'] as String? ?? 'HOST READY',
      hostSummary: map['hostSummary'] as String? ?? defaultHostSummary,
      isHostConnected: map['isHostConnected'] as bool? ?? true,
      flutterAppInstanceId: map['flutterAppInstanceId'] as String?,
      bridgeInstanceId: map['bridgeInstanceId'] as String?,
      localRuntimeServerState:
          rawLocalRuntimeServerState is Map<Object?, Object?>
          ? OpenCrayLocalRuntimeServerStateSnapshot.fromMap(
              rawLocalRuntimeServerState,
            )
          : null,
      hostLifecycle: rawHostLifecycle is Map<Object?, Object?>
          ? OpenCrayHostLifecycleSnapshot.fromMap(rawHostLifecycle)
          : null,
      runtimeOwnerLifecycle: rawRuntimeOwnerLifecycle is Map<Object?, Object?>
          ? OpenCrayHostLifecycleSnapshot.fromMap(rawRuntimeOwnerLifecycle)
          : null,
      runtimeOwnerWorkSummary:
          rawRuntimeOwnerWorkSummary is Map<Object?, Object?>
          ? OpenCrayRuntimeOwnerWorkSummarySnapshot.fromMap(
              rawRuntimeOwnerWorkSummary,
            )
          : null,
      runtimeServiceLifecycle:
          rawRuntimeServiceLifecycle is Map<Object?, Object?>
          ? OpenCrayRuntimeServiceLifecycleSnapshot.fromMap(
              rawRuntimeServiceLifecycle,
            )
          : null,
      runtimeServiceWorkState:
          rawRuntimeServiceWorkState is Map<Object?, Object?>
          ? OpenCrayRuntimeServiceWorkStateSnapshot.fromMap(
              rawRuntimeServiceWorkState,
            )
          : null,
      runtimeServiceKeepAliveState:
          rawRuntimeServiceKeepAliveState is Map<Object?, Object?>
          ? OpenCrayRuntimeServiceKeepAliveStateSnapshot.fromMap(
              rawRuntimeServiceKeepAliveState,
            )
          : null,
      runtimeServiceConnectionState:
          rawRuntimeServiceConnectionState is Map<Object?, Object?>
          ? OpenCrayRuntimeServiceConnectionStateSnapshot.fromMap(
              rawRuntimeServiceConnectionState,
            )
          : null,
    );
  }

  OpenCrayShellSnapshot copyWith({
    OpenCrayTab? initialTab,
    SettingsPage? initialSettingsPage,
    String? localeTag,
    String? hostLabel,
    String? hostSummary,
    bool? isHostConnected,
    String? flutterAppInstanceId,
    String? bridgeInstanceId,
    OpenCrayLocalRuntimeServerStateSnapshot? localRuntimeServerState,
    OpenCrayHostLifecycleSnapshot? hostLifecycle,
    OpenCrayHostLifecycleSnapshot? runtimeOwnerLifecycle,
    OpenCrayRuntimeOwnerWorkSummarySnapshot? runtimeOwnerWorkSummary,
    OpenCrayRuntimeServiceLifecycleSnapshot? runtimeServiceLifecycle,
    OpenCrayRuntimeServiceWorkStateSnapshot? runtimeServiceWorkState,
    OpenCrayRuntimeServiceKeepAliveStateSnapshot? runtimeServiceKeepAliveState,
    OpenCrayRuntimeServiceConnectionStateSnapshot?
    runtimeServiceConnectionState,
  }) {
    return OpenCrayShellSnapshot(
      initialTab: initialTab ?? this.initialTab,
      initialSettingsPage: initialSettingsPage ?? this.initialSettingsPage,
      localeTag: localeTag ?? this.localeTag,
      hostLabel: hostLabel ?? this.hostLabel,
      hostSummary: hostSummary ?? this.hostSummary,
      isHostConnected: isHostConnected ?? this.isHostConnected,
      flutterAppInstanceId: flutterAppInstanceId ?? this.flutterAppInstanceId,
      bridgeInstanceId: bridgeInstanceId ?? this.bridgeInstanceId,
      localRuntimeServerState:
          localRuntimeServerState ?? this.localRuntimeServerState,
      hostLifecycle: hostLifecycle ?? this.hostLifecycle,
      runtimeOwnerLifecycle:
          runtimeOwnerLifecycle ?? this.runtimeOwnerLifecycle,
      runtimeOwnerWorkSummary:
          runtimeOwnerWorkSummary ?? this.runtimeOwnerWorkSummary,
      runtimeServiceLifecycle:
          runtimeServiceLifecycle ?? this.runtimeServiceLifecycle,
      runtimeServiceWorkState:
          runtimeServiceWorkState ?? this.runtimeServiceWorkState,
      runtimeServiceKeepAliveState:
          runtimeServiceKeepAliveState ?? this.runtimeServiceKeepAliveState,
      runtimeServiceConnectionState:
          runtimeServiceConnectionState ?? this.runtimeServiceConnectionState,
    );
  }
}

class OpenCrayRuntimeOwnerWorkSummarySnapshot {
  const OpenCrayRuntimeOwnerWorkSummarySnapshot({
    this.hasActiveWork = false,
    this.trackedSessionCount = 0,
    this.activeRunCount = 0,
    this.activeSessionCount = 0,
    this.activeSessionIds = const <String>[],
    this.pendingWorkSessionIds = const <String>[],
    this.liveManagedProcessSessionIds = const <String>[],
  });

  final bool hasActiveWork;
  final int trackedSessionCount;
  final int activeRunCount;
  final int activeSessionCount;
  final List<String> activeSessionIds;
  final List<String> pendingWorkSessionIds;
  final List<String> liveManagedProcessSessionIds;

  factory OpenCrayRuntimeOwnerWorkSummarySnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayRuntimeOwnerWorkSummarySnapshot(
      hasActiveWork: map['hasActiveWork'] as bool? ?? false,
      trackedSessionCount: map['trackedSessionCount'] as int? ?? 0,
      activeRunCount: map['activeRunCount'] as int? ?? 0,
      activeSessionCount: map['activeSessionCount'] as int? ?? 0,
      activeSessionIds: _listOfStrings(map['activeSessionIds']),
      pendingWorkSessionIds: _listOfStrings(map['pendingWorkSessionIds']),
      liveManagedProcessSessionIds: _listOfStrings(
        map['liveManagedProcessSessionIds'],
      ),
    );
  }
}

class OpenCrayLocalRuntimeServerStateSnapshot {
  const OpenCrayLocalRuntimeServerStateSnapshot({
    this.phase,
    this.bindAddress,
    this.requestedPort,
    this.listeningPort,
    this.lastStartAttemptAtEpochMs,
    this.lastStartedAtEpochMs,
    this.failureReason,
    this.changedAtEpochMs,
  });

  final String? phase;
  final String? bindAddress;
  final int? requestedPort;
  final int? listeningPort;
  final int? lastStartAttemptAtEpochMs;
  final int? lastStartedAtEpochMs;
  final String? failureReason;
  final int? changedAtEpochMs;

  factory OpenCrayLocalRuntimeServerStateSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayLocalRuntimeServerStateSnapshot(
      phase: map['phase'] as String?,
      bindAddress: map['bindAddress'] as String?,
      requestedPort: map['requestedPort'] as int?,
      listeningPort: map['listeningPort'] as int?,
      lastStartAttemptAtEpochMs: map['lastStartAttemptAtEpochMs'] as int?,
      lastStartedAtEpochMs: map['lastStartedAtEpochMs'] as int?,
      failureReason: map['failureReason'] as String?,
      changedAtEpochMs: map['changedAtEpochMs'] as int?,
    );
  }
}

class OpenCrayRuntimeServiceLifecycleSnapshot {
  const OpenCrayRuntimeServiceLifecycleSnapshot({
    this.processStartId,
    this.processStartedAtEpochMs,
    this.serviceInstanceId,
    this.serviceCreatedAtEpochMs,
  });

  final String? processStartId;
  final int? processStartedAtEpochMs;
  final String? serviceInstanceId;
  final int? serviceCreatedAtEpochMs;

  factory OpenCrayRuntimeServiceLifecycleSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayRuntimeServiceLifecycleSnapshot(
      processStartId: map['processStartId'] as String?,
      processStartedAtEpochMs: map['processStartedAtEpochMs'] as int?,
      serviceInstanceId: map['serviceInstanceId'] as String?,
      serviceCreatedAtEpochMs: map['serviceCreatedAtEpochMs'] as int?,
    );
  }
}

class OpenCrayRuntimeServiceWorkStateSnapshot {
  const OpenCrayRuntimeServiceWorkStateSnapshot({
    this.phase,
    this.hasActiveWork = false,
    this.keepAliveRequired = false,
    this.keepAliveReason,
    this.changedAtEpochMs,
    this.activeSinceEpochMs,
    this.idleSinceEpochMs,
  });

  final String? phase;
  final bool hasActiveWork;
  final bool keepAliveRequired;
  final String? keepAliveReason;
  final int? changedAtEpochMs;
  final int? activeSinceEpochMs;
  final int? idleSinceEpochMs;

  factory OpenCrayRuntimeServiceWorkStateSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayRuntimeServiceWorkStateSnapshot(
      phase: map['phase'] as String?,
      hasActiveWork: map['hasActiveWork'] as bool? ?? false,
      keepAliveRequired: map['keepAliveRequired'] as bool? ?? false,
      keepAliveReason: map['keepAliveReason'] as String?,
      changedAtEpochMs: map['changedAtEpochMs'] as int?,
      activeSinceEpochMs: map['activeSinceEpochMs'] as int?,
      idleSinceEpochMs: map['idleSinceEpochMs'] as int?,
    );
  }
}

class OpenCrayRuntimeServiceKeepAliveStateSnapshot {
  const OpenCrayRuntimeServiceKeepAliveStateSnapshot({
    this.phase,
    this.idleGraceMs,
    this.stopScheduled = false,
    this.stopDeadlineEpochMs,
    this.hasSeenStartCommand = false,
    this.lastStartId,
    this.lastStartCommandAtEpochMs,
    this.lastStopRequestAtEpochMs,
    this.lastStopSucceeded,
    this.changedAtEpochMs,
  });

  final String? phase;
  final int? idleGraceMs;
  final bool stopScheduled;
  final int? stopDeadlineEpochMs;
  final bool hasSeenStartCommand;
  final int? lastStartId;
  final int? lastStartCommandAtEpochMs;
  final int? lastStopRequestAtEpochMs;
  final bool? lastStopSucceeded;
  final int? changedAtEpochMs;

  factory OpenCrayRuntimeServiceKeepAliveStateSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayRuntimeServiceKeepAliveStateSnapshot(
      phase: map['phase'] as String?,
      idleGraceMs: map['idleGraceMs'] as int?,
      stopScheduled: map['stopScheduled'] as bool? ?? false,
      stopDeadlineEpochMs: map['stopDeadlineEpochMs'] as int?,
      hasSeenStartCommand: map['hasSeenStartCommand'] as bool? ?? false,
      lastStartId: map['lastStartId'] as int?,
      lastStartCommandAtEpochMs: map['lastStartCommandAtEpochMs'] as int?,
      lastStopRequestAtEpochMs: map['lastStopRequestAtEpochMs'] as int?,
      lastStopSucceeded: map['lastStopSucceeded'] as bool?,
      changedAtEpochMs: map['changedAtEpochMs'] as int?,
    );
  }
}

class OpenCrayRuntimeServiceConnectionStateSnapshot {
  const OpenCrayRuntimeServiceConnectionStateSnapshot({
    this.phase,
    this.transport,
    this.serviceStartRequested = false,
    this.bindingRequested = false,
    this.binderAvailable = false,
    this.fallbackReason,
  });

  final String? phase;
  final String? transport;
  final bool serviceStartRequested;
  final bool bindingRequested;
  final bool binderAvailable;
  final String? fallbackReason;

  factory OpenCrayRuntimeServiceConnectionStateSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayRuntimeServiceConnectionStateSnapshot(
      phase: map['phase'] as String?,
      transport: map['transport'] as String?,
      serviceStartRequested: map['serviceStartRequested'] as bool? ?? false,
      bindingRequested: map['bindingRequested'] as bool? ?? false,
      binderAvailable: map['binderAvailable'] as bool? ?? false,
      fallbackReason: map['fallbackReason'] as String?,
    );
  }
}

OpenCrayTab _parseTab(String? rawValue) {
  switch (rawValue) {
    case 'skills':
      return OpenCrayTab.skills;
    case 'files':
      return OpenCrayTab.files;
    case 'settings':
      return OpenCrayTab.settings;
    case 'chat':
    default:
      return OpenCrayTab.chat;
  }
}

SettingsPage _parseSettingsPage(String? rawValue) {
  if (rawValue == null || rawValue.trim().isEmpty) {
    return SettingsPage.home;
  }
  return settingsPageFromRouteId(rawValue);
}

List<String> _listOfStrings(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <String>[];
  }
  return list.map((value) => value as String? ?? '').toList(growable: false);
}
