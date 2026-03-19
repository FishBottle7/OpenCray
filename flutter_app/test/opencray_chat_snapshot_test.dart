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

  test('chat message snapshot parses createdAtEpochMs from map payload', () {
    final snapshot = OpenCrayChatMessageSnapshot.fromMap(<Object?, Object?>{
      'messageId': 'message-1',
      'kind': 'inbound',
      'text': 'Hello from OpenCray.',
      'createdAtEpochMs': 1700000000000,
      'isEphemeral': true,
    });

    expect(snapshot.messageId, 'message-1');
    expect(snapshot.createdAtEpochMs, 1700000000000);
    expect(snapshot.isEphemeral, isTrue);
  });

  test('chat message snapshot parses attachment payloads', () {
    final snapshot = OpenCrayChatMessageSnapshot.fromMap(<Object?, Object?>{
      'messageId': 'message-attachments',
      'kind': 'inbound',
      'text': '',
      'attachments': <Object?>[
        <Object?, Object?>{
          'attachmentId': 'attachment-image-1',
          'kind': 'image',
          'displayName': 'diagram.png',
          'localPath': '.opencray/chat-media/session-1/hash/diagram.png',
          'mimeType': 'image/png',
          'sizeBytes': 1024,
          'contentSha256': 'abc123',
        },
        <Object?, Object?>{
          'attachmentId': 'attachment-audio-1',
          'kind': 'voice',
          'displayName': 'voice-note.m4a',
          'localPath': '.opencray/chat-media/session-1/hash/voice-note.m4a',
          'mimeType': 'audio/mp4',
          'durationMs': 4200,
          'waveformBars': <Object?>[12, 40, 88],
          'transcriptText': 'Voice summary',
        },
      ],
    });

    expect(snapshot.attachments, hasLength(2));
    expect(snapshot.attachments.first.kind, 'image');
    expect(snapshot.attachments.first.displayName, 'diagram.png');
    expect(
      snapshot.attachments.first.localPath,
      contains('.opencray/chat-media'),
    );
    expect(snapshot.attachments.first.sizeBytes, 1024);
    expect(snapshot.attachments.first.contentSha256, 'abc123');
    expect(snapshot.attachments.last.kind, 'voice');
    expect(snapshot.attachments.last.durationMs, 4200);
    expect(snapshot.attachments.last.waveformBars, <int>[12, 40, 88]);
    expect(snapshot.attachments.last.transcriptText, 'Voice summary');
  });

  test('chat session item snapshot parses lastMessageAtEpochMs', () {
    final snapshot = OpenCrayChatSessionItemSnapshot.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'title': 'Recent session',
      'preview': 'Latest message preview',
      'meta': '2 messages',
      'isSelected': false,
      'lastMessageAtEpochMs': 1700000001000,
      'unreadCount': 3,
    });

    expect(snapshot.sessionId, 'session-1');
    expect(snapshot.lastMessageAtEpochMs, 1700000001000);
    expect(snapshot.unreadCount, 3);
  });

  test('chat runtime event snapshot parses subagent payload', () {
    final snapshot =
        OpenCrayChatRuntimeEventSnapshot.fromMap(<Object?, Object?>{
          'kind': 'subagent',
          'runId': 'run-parent',
          'taskId': 'task-parent',
          'emittedAtEpochMs': 1700000002000,
          'phase': 'completed',
          'label': 'Inspect README',
          'childRunId': 'run-child',
          'childTaskId': 'task-child',
          'subagentType': 'researcher',
          'contextMode': 'minimal',
          'depth': 1,
          'text': 'README says hello.',
        });

    expect(snapshot.kind, 'subagent');
    expect(snapshot.phase, 'completed');
    expect(snapshot.label, 'Inspect README');
    expect(snapshot.childRunId, 'run-child');
    expect(snapshot.childTaskId, 'task-child');
    expect(snapshot.subagentType, 'researcher');
    expect(snapshot.contextMode, 'minimal');
    expect(snapshot.depth, 1);
    expect(snapshot.text, 'README says hello.');
  });

  test('chat snapshot parses todo payloads', () {
    final snapshot = OpenCrayChatSnapshot.fromMap(<Object?, Object?>{
      'screenTitle': 'Chat',
      'modeLabel': 'SAFE',
      'sessionButtonLabel': 'Sessions',
      'composerPlaceholder': 'Message OpenCray',
      'summary': <Object?, Object?>{
        'title': 'Session',
        'badge': '1 message',
        'body': 'Reply in progress',
      },
      'messages': <Object?>[],
      'drawer': <Object?, Object?>{
        'eyebrow': 'Recent sessions',
        'title': 'Recent sessions',
        'ctaLabel': 'New session',
        'sessions': <Object?>[],
      },
      'isInputEnabled': true,
      'todos': <Object?>[
        <Object?, Object?>{
          'content': 'Review chat composer layout',
          'status': 'pending',
        },
        <Object?, Object?>{
          'content': 'Highlight active todo text',
          'status': 'in_progress',
          'activeForm': 'Highlighting active todo text',
        },
        <Object?, Object?>{
          'content': 'Approve Pencil prototype',
          'status': 'completed',
        },
      ],
    });

    expect(snapshot.todos, hasLength(3));
    expect(snapshot.todos.first.content, 'Review chat composer layout');
    expect(snapshot.todos.first.status, 'pending');
    expect(snapshot.todos[1].activeForm, 'Highlighting active todo text');
    expect(snapshot.todos.last.status, 'completed');
  });
}
