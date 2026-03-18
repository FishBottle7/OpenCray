import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';

void main() {
  test('chat run snapshot parses structured memory trace from map payload', () {
    final snapshot = OpenCrayChatRunSnapshot.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'runId': 'run-memory',
      'taskId': 'task-memory',
      'acceptedAtEpochMs': 1000,
      'updatedAtEpochMs': 2000,
      'attempt': 1,
      'isTerminal': true,
      'memoryTrace': <Object?, Object?>{
        'matchedRecordCount': 2,
        'injectedRecordCount': 1,
        'omittedRecordCount': 1,
        'queryTerms': <Object?>['chinese', 'gradle'],
        'selected': <Object?>[
          <Object?, Object?>{
            'id': 'memory-user',
            'score': 420,
            'matchedTerms': <Object?>['chinese'],
          },
        ],
        'omitted': <Object?>[
          <Object?, Object?>{'id': 'memory-project', 'reason': 'max_records'},
        ],
        'filteredCounts': <Object?, Object?>{'scope_mismatch': 1, 'expired': 2},
      },
    });

    final trace = snapshot.memoryTrace;

    expect(trace, isNotNull);
    expect(trace!.matchedRecordCount, 2);
    expect(trace.injectedRecordCount, 1);
    expect(trace.omittedRecordCount, 1);
    expect(trace.queryTerms, <String>['chinese', 'gradle']);
    expect(trace.selected.single.id, 'memory-user');
    expect(trace.selected.single.score, 420);
    expect(trace.selected.single.matchedTerms, <String>['chinese']);
    expect(trace.omitted.single.reason, 'max_records');
    expect(trace.filteredCounts['scope_mismatch'], 1);
    expect(trace.filteredCounts['expired'], 2);
  });

  test('chat run snapshot parses live context trace from map payload', () {
    final snapshot = OpenCrayChatRunSnapshot.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'runId': 'run-live-context',
      'taskId': 'task-live-context',
      'acceptedAtEpochMs': 1000,
      'updatedAtEpochMs': 2000,
      'attempt': 1,
      'isTerminal': true,
      'liveContext': <Object?, Object?>{
        'mode': 'no_soul',
        'soulEnabled': false,
        'memoryRecallEnabled': true,
      },
    });

    final liveContext = snapshot.liveContext;

    expect(liveContext, isNotNull);
    expect(liveContext!.mode, 'no_soul');
    expect(liveContext.soulEnabled, isFalse);
    expect(liveContext.memoryRecallEnabled, isTrue);
  });
}
