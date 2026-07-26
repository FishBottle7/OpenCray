import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';

void main() {
  test('runtime envelope parses stream identity and snapshot watermark', () {
    final snapshot = OpenCrayChatRuntimeSnapshot.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'streamInstanceId': 'stream-7',
      'lastSequence': 42,
      'bridgeEpoch': 'bridge-epoch-3',
      'activeRuns': const <Object?>[],
      'events': const <Object?>[],
    });

    expect(snapshot.streamInstanceId, 'stream-7');
    expect(snapshot.lastSequence, 42);
    expect(snapshot.bridgeEpoch, 'bridge-epoch-3');
  });

  test('runtime delta and event parse ordering and execution identities', () {
    final delta = OpenCrayChatRuntimeEventDelta.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'streamInstanceId': 'stream-7',
      'sequence': 43,
      'lastSequence': 43,
      'eventId': 'envelope-43',
      'executionId': 'execution-retry-2',
      'bridgeEpoch': 'bridge-epoch-3',
      'events': <Object?>[
        <Object?, Object?>{
          'kind': 'assistant_phase',
          'runId': 'run-1',
          'taskId': 'task-1',
          'executionId': 'execution-retry-2',
          'eventId': 'event-43',
          'emittedAtEpochMs': 1000,
        },
      ],
      'totalLength': 1,
    });

    expect(delta.streamInstanceId, 'stream-7');
    expect(delta.sequence, 43);
    expect(delta.lastSequence, 43);
    expect(delta.eventId, 'envelope-43');
    expect(delta.executionId, 'execution-retry-2');
    expect(delta.bridgeEpoch, 'bridge-epoch-3');
    expect(delta.events.single.eventId, 'event-43');
    expect(delta.events.single.executionId, 'execution-retry-2');
  });

  test('live drafts parse stream and execution ordering identities', () {
    final snapshot =
        OpenCrayChatLiveAssistantDraftSnapshot.fromMap(<Object?, Object?>{
          'runId': 'run-1',
          'taskId': 'task-1',
          'executionId': 'execution-retry-2',
          'streamInstanceId': 'stream-7',
          'sequence': 9,
          'lastSequence': 9,
          'eventId': 'draft-event-9',
          'bridgeEpoch': 'bridge-epoch-3',
          'pendingMessageId': 'pending-1',
          'text': 'chunk',
          'updatedAtEpochMs': 1000,
        });
    final event =
        OpenCrayChatLiveAssistantDraftEvent.fromMap(<Object?, Object?>{
          'sessionId': 'session-1',
          'runId': 'run-1',
          'taskId': 'task-1',
          'executionId': 'execution-retry-2',
          'streamInstanceId': 'stream-7',
          'sequence': 9,
          'lastSequence': 9,
          'eventId': 'draft-event-9',
          'bridgeEpoch': 'bridge-epoch-3',
          'pendingMessageId': 'pending-1',
          'text': 'chunk',
          'updatedAtEpochMs': 1000,
        });

    expect(snapshot.executionId, 'execution-retry-2');
    expect(snapshot.streamInstanceId, 'stream-7');
    expect(snapshot.sequence, 9);
    expect(snapshot.lastSequence, 9);
    expect(snapshot.eventId, 'draft-event-9');
    expect(snapshot.bridgeEpoch, 'bridge-epoch-3');
    expect(event.executionId, 'execution-retry-2');
    expect(event.streamInstanceId, 'stream-7');
    expect(event.sequence, 9);
    expect(event.lastSequence, 9);
    expect(event.eventId, 'draft-event-9');
    expect(event.bridgeEpoch, 'bridge-epoch-3');
  });
}
