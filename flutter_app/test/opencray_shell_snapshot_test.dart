import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/app/opencray_tabs.dart';
import 'package:opencray/core/models/opencray_shell_snapshot.dart';

void main() {
  test('shell snapshot parses runtime diagnostics from map payload', () {
    final snapshot = OpenCrayShellSnapshot.fromMap(<Object?, Object?>{
      'initialTab': 'chat',
      'localeTag': 'en',
      'hostLabel': 'HOST READY',
      'hostSummary': 'Detached runtime service active.',
      'isHostConnected': true,
      'flutterAppInstanceId': 'flutter-app-1',
      'bridgeInstanceId': 'bridge-1',
      'localRuntimeServerState': <Object?, Object?>{
        'phase': 'listening',
        'bindAddress': '127.0.0.1',
        'requestedPort': 42617,
        'listeningPort': 42617,
        'lastStartAttemptAtEpochMs': 1200,
        'lastStartedAtEpochMs': 1300,
        'changedAtEpochMs': 1300,
      },
      'hostLifecycle': <Object?, Object?>{
        'processStartId': 'process-1',
        'processStartedAtEpochMs': 1000,
        'hostInstanceId': 'host-ui-1',
        'runtimeOwnerId': 'owner-service-1',
        'hostCreatedAtEpochMs': 2000,
      },
      'runtimeOwnerLifecycle': <Object?, Object?>{
        'processStartId': 'process-1',
        'processStartedAtEpochMs': 1000,
        'hostInstanceId': 'host-service-1',
        'runtimeOwnerId': 'owner-service-1',
        'hostCreatedAtEpochMs': 1500,
      },
      'runtimeOwnerWorkSummary': <Object?, Object?>{
        'hasActiveWork': true,
        'trackedSessionCount': 2,
        'activeRunCount': 1,
        'activeSessionCount': 1,
        'activeSessionIds': <Object?>['session-1'],
        'pendingWorkSessionIds': <Object?>['session-1'],
        'liveManagedProcessSessionIds': <Object?>['session-1'],
      },
      'runtimeServiceLifecycle': <Object?, Object?>{
        'processStartId': 'process-1',
        'processStartedAtEpochMs': 1000,
        'serviceInstanceId': 'runtime-service-1',
        'serviceCreatedAtEpochMs': 1400,
      },
      'runtimeServiceWorkState': <Object?, Object?>{
        'phase': 'active_work',
        'hasActiveWork': true,
        'keepAliveRequired': true,
        'keepAliveReason': 'active_run',
        'changedAtEpochMs': 2300,
        'activeSinceEpochMs': 1500,
      },
      'runtimeServiceKeepAliveState': <Object?, Object?>{
        'phase': 'active_work',
        'idleGraceMs': 30000,
        'stopScheduled': false,
        'hasSeenStartCommand': true,
        'lastStartId': 7,
        'lastStartCommandAtEpochMs': 1450,
        'changedAtEpochMs': 2300,
      },
      'runtimeServiceConnectionState': <Object?, Object?>{
        'phase': 'bound',
        'transport': 'binder',
        'serviceStartRequested': true,
        'bindingRequested': true,
        'binderAvailable': true,
      },
    }, defaultHostSummary: 'fallback summary');

    expect(snapshot.initialTab, OpenCrayTab.chat);
    expect(snapshot.flutterAppInstanceId, 'flutter-app-1');
    expect(snapshot.bridgeInstanceId, 'bridge-1');
    expect(snapshot.localRuntimeServerState, isNotNull);
    expect(snapshot.localRuntimeServerState!.phase, 'listening');
    expect(snapshot.localRuntimeServerState!.listeningPort, 42617);
    expect(snapshot.hostLifecycle, isNotNull);
    expect(snapshot.hostLifecycle!.hostInstanceId, 'host-ui-1');
    expect(snapshot.runtimeOwnerLifecycle, isNotNull);
    expect(snapshot.runtimeOwnerLifecycle!.hostInstanceId, 'host-service-1');
    expect(snapshot.runtimeOwnerWorkSummary, isNotNull);
    expect(snapshot.runtimeOwnerWorkSummary!.activeRunCount, 1);
    expect(snapshot.runtimeOwnerWorkSummary!.pendingWorkSessionIds, <String>[
      'session-1',
    ]);
    expect(snapshot.runtimeServiceLifecycle, isNotNull);
    expect(
      snapshot.runtimeServiceLifecycle!.serviceInstanceId,
      'runtime-service-1',
    );
    expect(snapshot.runtimeServiceWorkState, isNotNull);
    expect(snapshot.runtimeServiceWorkState!.keepAliveReason, 'active_run');
    expect(snapshot.runtimeServiceKeepAliveState, isNotNull);
    expect(snapshot.runtimeServiceKeepAliveState!.lastStartId, 7);
    expect(snapshot.runtimeServiceConnectionState, isNotNull);
    expect(snapshot.runtimeServiceConnectionState!.transport, 'binder');
    expect(snapshot.runtimeServiceConnectionState!.binderAvailable, isTrue);
  });
}
