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

  test('chat runtime snapshot parses host lifecycle and run diagnostics', () {
    final snapshot = OpenCrayChatRuntimeSnapshot.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'flutterAppInstanceId': 'flutter-app-1',
      'bridgeInstanceId': 'bridge-1',
      'updatedAtEpochMs': 2200,
      'hostLifecycle': <Object?, Object?>{
        'processStartId': 'process-1',
        'processStartedAtEpochMs': 1500,
        'hostInstanceId': 'host-1',
        'runtimeOwnerId': 'owner-1',
        'hostCreatedAtEpochMs': 2000,
      },
      'activeRuns': <Object?>[
        <Object?, Object?>{
          'sessionId': 'session-1',
          'runId': 'run-restore',
          'taskId': 'task-restore',
          'acceptedAtEpochMs': 1000,
          'updatedAtEpochMs': 2100,
          'attempt': 1,
          'isTerminal': true,
          'diagnostics': <Object?, Object?>{
            'processStartId': 'process-1',
            'hostInstanceId': 'host-1',
            'runtimeOwnerId': 'owner-1',
            'submissionSource': 'chat_user_message',
            'recoveryReason': 'host_restart_inflight_task_interrupted',
            'queueRestoreEpochMs': 2050,
            'previousLifecycleState': 'running',
            'restoredFromDurableStore': true,
          },
          'recoveryPlan': <Object?, Object?>{
            'action': 'resume_from_checkpoint',
            'reasonCode': 'durable_general_resume_checkpoint',
            'summary':
                'The run has a durable general resume checkpoint and should continue from that checkpoint instead of rerunning from task input.',
            'safeToAutoResume': true,
            'requiresUserAction': false,
            'checkpointKind': 'general_resume',
            'journalTailKind': 'tool_result',
          },
        },
      ],
      'events': <Object?>[],
    });

    expect(snapshot.updatedAtEpochMs, 2200);
    expect(snapshot.hostLifecycle, isNotNull);
    expect(snapshot.flutterAppInstanceId, 'flutter-app-1');
    expect(snapshot.bridgeInstanceId, 'bridge-1');
    expect(snapshot.hostLifecycle!.processStartId, 'process-1');
    expect(snapshot.hostLifecycle!.hostInstanceId, 'host-1');
    expect(snapshot.hostLifecycle!.hostCreatedAtEpochMs, 2000);

    final diagnostics = snapshot.activeRuns.single.diagnostics;
    expect(diagnostics, isNotNull);
    expect(diagnostics!.submissionSource, 'chat_user_message');
    expect(
      diagnostics.recoveryReason,
      'host_restart_inflight_task_interrupted',
    );
    expect(diagnostics.queueRestoreEpochMs, 2050);
    expect(diagnostics.previousLifecycleState, 'running');
    expect(diagnostics.restoredFromDurableStore, isTrue);

    final recoveryPlan = snapshot.activeRuns.single.recoveryPlan;
    expect(recoveryPlan, isNotNull);
    expect(recoveryPlan!.action, 'resume_from_checkpoint');
    expect(recoveryPlan.reasonCode, 'durable_general_resume_checkpoint');
    expect(recoveryPlan.safeToAutoResume, isTrue);
    expect(recoveryPlan.requiresUserAction, isFalse);
    expect(recoveryPlan.checkpointKind, 'general_resume');
    expect(recoveryPlan.journalTailKind, 'tool_result');
  });

  test('chat snapshot parses top-level updatedAtEpochMs', () {
    final snapshot = OpenCrayChatSnapshot.fromMap(<Object?, Object?>{
      'screenTitle': 'Chat',
      'modeLabel': 'SAFE',
      'sessionButtonLabel': 'Sessions',
      'composerPlaceholder': 'Message OpenCray',
      'updatedAtEpochMs': 3100,
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
    });

    expect(snapshot.updatedAtEpochMs, 3100);
  });

  test('chat runtime snapshot parses projected subagent payloads', () {
    final snapshot = OpenCrayChatRuntimeSnapshot.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'activeRuns': <Object?>[],
      'retainedRuns': <Object?>[],
      'subAgents': <Object?>[
        <Object?, Object?>{
          'parentRunId': 'run-parent',
          'parentTaskId': 'task-parent',
          'childRunId': 'run-child',
          'childTaskId': 'task-child',
          'label': 'Inspect README',
          'subagentType': 'researcher',
          'contextMode': 'minimal',
          'contextModeSource': 'policy_profile_override',
          'depth': 1,
          'phase': 'resumed',
          'status': 'background_running',
          'executionState': 'background_running',
          'continuationKind': 'background_resume',
          'resumable': true,
          'requiresUserAction': false,
          'isHighRisk': false,
          'summary':
              'Delegated child runtime is still running in the background.',
          'startedAtEpochMs': 1800,
          'updatedAtEpochMs': 2400,
          'eventCount': 0,
          'hasActiveExecution': true,
          'mailboxMessageCount': 2,
          'mailboxPendingMessageCount': 1,
          'mailboxLastDeliveredMessageId': 'mailbox-1',
          'hasPendingApprovalResume': true,
          'pendingApprovalToolName': 'Read',
          'pendingApprovalIsHighRisk': false,
          'pendingApprovalChildRunId': 'run-child',
          'pendingApprovalChildTaskId': 'task-child',
        },
      ],
      'events': <Object?>[],
    });

    final subAgent = snapshot.subAgents.single;

    expect(subAgent.parentRunId, 'run-parent');
    expect(subAgent.parentTaskId, 'task-parent');
    expect(subAgent.childRunId, 'run-child');
    expect(subAgent.childTaskId, 'task-child');
    expect(subAgent.label, 'Inspect README');
    expect(subAgent.subagentType, 'researcher');
    expect(subAgent.contextMode, 'minimal');
    expect(subAgent.contextModeSource, 'policy_profile_override');
    expect(subAgent.depth, 1);
    expect(subAgent.phase, 'resumed');
    expect(subAgent.status, 'background_running');
    expect(subAgent.executionState, 'background_running');
    expect(subAgent.continuationKind, 'background_resume');
    expect(subAgent.resumable, isTrue);
    expect(subAgent.requiresUserAction, isFalse);
    expect(subAgent.isHighRisk, isFalse);
    expect(
      subAgent.summary,
      'Delegated child runtime is still running in the background.',
    );
    expect(subAgent.startedAtEpochMs, 1800);
    expect(subAgent.updatedAtEpochMs, 2400);
    expect(subAgent.eventCount, 0);
    expect(subAgent.hasActiveExecution, isTrue);
    expect(subAgent.mailboxMessageCount, 2);
    expect(subAgent.mailboxPendingMessageCount, 1);
    expect(subAgent.mailboxLastDeliveredMessageId, 'mailbox-1');
    expect(subAgent.hasPendingApprovalResume, isTrue);
    expect(subAgent.pendingApprovalToolName, 'Read');
    expect(subAgent.pendingApprovalIsHighRisk, isFalse);
    expect(subAgent.pendingApprovalChildRunId, 'run-child');
    expect(subAgent.pendingApprovalChildTaskId, 'task-child');
  });

  test('chat run snapshot parses execution fields from map payload', () {
    final snapshot = OpenCrayChatRunSnapshot.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'runId': 'run-exec',
      'taskId': 'task-exec',
      'acceptedAtEpochMs': 1000,
      'updatedAtEpochMs': 2000,
      'attempt': 1,
      'executionOrdinal': 2,
      'executionId': 'exec-2',
      'executionKind': 'approval_resume',
      'pendingExecutionKind': 'checkpoint_resume',
      'isTerminal': false,
    });

    expect(snapshot.executionOrdinal, 2);
    expect(snapshot.executionId, 'exec-2');
    expect(snapshot.executionKind, 'approval_resume');
    expect(snapshot.pendingExecutionKind, 'checkpoint_resume');
  });

  test('chat run snapshot parses managed process payloads', () {
    final snapshot = OpenCrayChatRunSnapshot.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'runId': 'run-process',
      'taskId': 'task-process',
      'acceptedAtEpochMs': 1000,
      'updatedAtEpochMs': 2300,
      'attempt': 1,
      'isTerminal': false,
      'managedProcessIds': <Object?>['proc-live'],
      'runningManagedProcessCount': 1,
      'hasLiveManagedProcesses': true,
      'managedProcesses': <Object?>[
        <Object?, Object?>{
          'processId': 'proc-live',
          'status': 'running',
          'command': 'npm',
          'args': <Object?>['run', 'dev'],
          'workingDirectory': '.',
          'startedAtEpochMs': 1200,
          'updatedAtEpochMs': 2300,
          'stdoutPreview': 'ready on http://localhost:3000',
          'stdoutTruncated': false,
          'stderrPreview': '',
          'stderrTruncated': false,
        },
      ],
    });

    expect(snapshot.managedProcessIds, <String>['proc-live']);
    expect(snapshot.runningManagedProcessCount, 1);
    expect(snapshot.hasLiveManagedProcesses, isTrue);
    expect(snapshot.managedProcesses, hasLength(1));
    expect(snapshot.managedProcesses.single.processId, 'proc-live');
    expect(snapshot.managedProcesses.single.command, 'npm');
    expect(snapshot.managedProcesses.single.args, <String>['run', 'dev']);
    expect(
      snapshot.managedProcesses.single.stdout,
      'ready on http://localhost:3000',
    );
    expect(
      snapshot.managedProcesses.single.stdoutPreview,
      'ready on http://localhost:3000',
    );
  });

  test('chat run snapshot parses full managed process output when present', () {
    final snapshot = OpenCrayChatRunSnapshot.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'runId': 'run-process-full',
      'taskId': 'task-process-full',
      'acceptedAtEpochMs': 1000,
      'updatedAtEpochMs': 2300,
      'attempt': 1,
      'isTerminal': false,
      'managedProcesses': <Object?>[
        <Object?, Object?>{
          'processId': 'proc-live',
          'status': 'running',
          'command': 'npm',
          'args': <Object?>['run', 'dev'],
          'workingDirectory': '.',
          'startedAtEpochMs': 1200,
          'updatedAtEpochMs': 2300,
          'stdout': 'booting\nready on http://localhost:3000',
          'stdoutPreview': 'ready on http://localhost:3000',
          'stdoutTruncated': true,
          'stderr': 'warn: deprecated dependency\nwatching for changes',
          'stderrPreview': 'watching for changes',
          'stderrTruncated': true,
        },
      ],
    });

    expect(
      snapshot.managedProcesses.single.stdout,
      'booting\nready on http://localhost:3000',
    );
    expect(
      snapshot.managedProcesses.single.stdoutPreview,
      'ready on http://localhost:3000',
    );
    expect(
      snapshot.managedProcesses.single.stderr,
      'warn: deprecated dependency\nwatching for changes',
    );
    expect(
      snapshot.managedProcesses.single.stderrPreview,
      'watching for changes',
    );
  });

  test('chat run snapshot parses final attachments', () {
    final snapshot = OpenCrayChatRunSnapshot.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'runId': 'run-final-attachments',
      'taskId': 'task-final-attachments',
      'acceptedAtEpochMs': 1000,
      'updatedAtEpochMs': 2300,
      'attempt': 1,
      'isTerminal': true,
      'finalAttachments': <Object?>[
        <Object?, Object?>{
          'attachmentId': 'artifact-diagram',
          'kind': 'image',
          'displayName': 'diagram.png',
          'localPath': '.opencray/chat-media/session-1/diagram.png',
          'mimeType': 'image/png',
        },
      ],
    });

    expect(snapshot.finalAttachments, hasLength(1));
    expect(snapshot.finalAttachments.single.attachmentId, 'artifact-diagram');
    expect(snapshot.finalAttachments.single.kind, 'image');
    expect(snapshot.finalAttachments.single.displayName, 'diagram.png');
    expect(
      snapshot.finalAttachments.single.localPath,
      '.opencray/chat-media/session-1/diagram.png',
    );
    expect(snapshot.finalAttachments.single.mimeType, 'image/png');
  });

  test(
    'chat run snapshot preserves trace history across approval resume while keeping event matching scoped',
    () {
      const run = OpenCrayChatRunSnapshot(
        sessionId: 'session-1',
        runId: 'run-1',
        taskId: 'task-1',
        acceptedAtEpochMs: 1000,
        updatedAtEpochMs: 2200,
        attempt: 1,
        executionOrdinal: 2,
        executionId: 'exec-2',
        executionKind: 'approval_resume',
        isTerminal: false,
      );
      const previousExecution = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-1',
        taskId: 'task-1',
        executionId: 'exec-1',
        executionOrdinal: 1,
        executionKind: 'initial',
        emittedAtEpochMs: 1800,
        text: 'Old execution commentary.',
      );
      const currentExecution = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-1',
        taskId: 'task-1',
        executionId: 'exec-2',
        executionOrdinal: 2,
        executionKind: 'approval_resume',
        emittedAtEpochMs: 2200,
        text: 'Current execution commentary.',
      );

      final scoped = run.scopeRuntimeEvents(
        const <OpenCrayChatRuntimeEventSnapshot>[
          previousExecution,
          currentExecution,
        ],
      );

      expect(scoped, <OpenCrayChatRuntimeEventSnapshot>[
        previousExecution,
        currentExecution,
      ]);
      expect(run.matchesRuntimeEvent(previousExecution), isFalse);
      expect(run.matchesRuntimeEvent(currentExecution), isTrue);
    },
  );

  test(
    'chat run snapshot keeps untagged live history visible while execution id is still pending',
    () {
      const run = OpenCrayChatRunSnapshot(
        sessionId: 'session-1',
        runId: 'run-pending-1',
        taskId: 'task-pending-1',
        acceptedAtEpochMs: 1000,
        updatedAtEpochMs: 2200,
        attempt: 1,
        pendingExecutionKind: 'initial',
        isTerminal: false,
      );
      const livePhase = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-pending-1',
        taskId: 'task-pending-1',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        text: 'Streaming planning update.',
      );

      final scoped = run.scopeRuntimeEvents(
        const <OpenCrayChatRuntimeEventSnapshot>[livePhase],
      );

      expect(scoped, <OpenCrayChatRuntimeEventSnapshot>[livePhase]);
      expect(run.matchesRuntimeEvent(livePhase), isTrue);
    },
  );

  test(
    'chat run snapshot scopes events by stable task id when run id drifts',
    () {
      const run = OpenCrayChatRunSnapshot(
        sessionId: 'session-1',
        runId: 'run-current-1',
        taskId: 'task-stable-1',
        acceptedAtEpochMs: 1000,
        updatedAtEpochMs: 2200,
        attempt: 1,
        isTerminal: false,
      );
      const taskOnlyEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: '',
        taskId: 'task-stable-1',
        emittedAtEpochMs: 2100,
        toolName: 'Read',
        contentPreview: 'Task-only event should stay visible.',
      );
      const shiftedRunEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-old-1',
        taskId: 'task-stable-1',
        emittedAtEpochMs: 2200,
        toolName: 'Read',
        contentPreview: 'Shifted run id event should stay visible.',
      );

      final scoped = run.scopeRuntimeEvents(
        const <OpenCrayChatRuntimeEventSnapshot>[
          taskOnlyEvent,
          shiftedRunEvent,
        ],
      );

      expect(scoped, <OpenCrayChatRuntimeEventSnapshot>[
        taskOnlyEvent,
        shiftedRunEvent,
      ]);
      expect(run.matchesRuntimeEvent(taskOnlyEvent), isTrue);
      expect(run.matchesRuntimeEvent(shiftedRunEvent), isTrue);
    },
  );

  test('chat run submission parses lifecycle diagnostics from map payload', () {
    final submission = OpenCrayChatRunSubmission.fromMap(<Object?, Object?>{
      'sessionId': 'session-1',
      'runId': 'run-1',
      'taskId': 'task-1',
      'acceptedAtEpochMs': 1000,
      'diagnostics': <Object?, Object?>{
        'processStartId': 'process-1',
        'hostInstanceId': 'host-1',
        'runtimeOwnerId': 'owner-1',
        'submissionSource': 'chat_queued_follow_up',
      },
    });

    expect(submission.sessionId, 'session-1');
    expect(submission.diagnostics, isNotNull);
    expect(submission.diagnostics!.processStartId, 'process-1');
    expect(submission.diagnostics!.submissionSource, 'chat_queued_follow_up');
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
      'todoState': 'archived_completed',
      'todoHideDelayMs': 3200,
      'todoCompletedAtEpochMs': 1700000003000,
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
    expect(snapshot.todoState, 'archived_completed');
    expect(snapshot.todoHideDelayMs, 3200);
    expect(snapshot.todoCompletedAtEpochMs, 1700000003000);
  });
}
