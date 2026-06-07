import 'dart:async';
import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_math_fork/flutter_math.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_agent_snapshot.dart';
import 'package:opencray/core/models/opencray_chat_draft_attachment.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_debug_snapshot.dart';
import 'package:opencray/core/models/opencray_file_image_preview.dart';
import 'package:opencray/core/models/opencray_image_reference.dart';
import 'package:opencray/core/models/opencray_sandbox_preview_embed_config.dart';
import 'package:opencray/core/models/opencray_sandbox_settings.dart';
import 'package:opencray/core/models/opencray_file_text_preview.dart';
import 'package:opencray/core/models/opencray_file_voice_playback_source.dart';
import 'package:opencray/core/models/opencray_strong_background.dart';
import 'package:opencray/features/chat/chat_feature.dart';
import 'package:opencray/features/chat/chat_voice_playback.dart';

void main() {
  test(
    'resolveChatRuntimeSnapshot prefers the thicker snapshot when versions tie',
    () {
      final embedded = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[],
        events: const <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'assistant',
            runId: 'run-1',
            taskId: 'task-1',
            emittedAtEpochMs: 3000,
          ),
        ],
      );
      final streamed = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-1',
            taskId: 'task-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: false,
          ),
        ],
        events: const <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'assistant',
            runId: 'run-1',
            taskId: 'task-1',
            emittedAtEpochMs: 3000,
          ),
        ],
      );

      final resolved = resolveChatRuntimeSnapshot(embedded, streamed);

      expect(resolved!.activeRuns, hasLength(1));
      expect(resolved.events, hasLength(1));
      expect(
        runtimeSnapshotVersion(embedded),
        runtimeSnapshotVersion(streamed),
      );
    },
  );

  test(
    'resolveChatRuntimeSnapshot prefers the streamed snapshot when host changes',
    () {
      final embedded = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[],
        events: const <OpenCrayChatRuntimeEventSnapshot>[],
        hostLifecycle: const OpenCrayHostLifecycleSnapshot(
          hostInstanceId: 'host-1',
          hostCreatedAtEpochMs: 4000,
        ),
      );
      final streamed = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[],
        events: const <OpenCrayChatRuntimeEventSnapshot>[],
        hostLifecycle: const OpenCrayHostLifecycleSnapshot(
          hostInstanceId: 'host-2',
          hostCreatedAtEpochMs: 4000,
        ),
      );

      final resolved = resolveChatRuntimeSnapshot(embedded, streamed);

      expect(resolved!.hostLifecycle!.hostInstanceId, 'host-2');
    },
  );

  test(
    'resolveChatRuntimeSnapshotForSession ignores snapshots from other sessions',
    () {
      const embedded = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-2',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      const streamed = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-cross-session-1',
            taskId: 'task-cross-session-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

      final resolved = resolveChatRuntimeSnapshotForSession(
        expectedSessionId: 'session-2',
        embedded: embedded,
        streamed: streamed,
      );

      expect(resolved, isNotNull);
      expect(resolved!.sessionId, 'session-2');
      expect(resolved.activeRuns, isEmpty);
      expect(resolved.retainedRuns, isEmpty);
    },
  );

  test('runtimeSnapshotVersion tracks projected subagent updates', () {
    const snapshot = OpenCrayChatRuntimeSnapshot(
      sessionId: 'session-1',
      activeRuns: <OpenCrayChatRunSnapshot>[],
      subAgents: <OpenCrayChatSubAgentSnapshot>[
        OpenCrayChatSubAgentSnapshot(
          parentRunId: 'run-parent',
          parentTaskId: 'task-parent',
          childRunId: 'child-run-detached-1',
          childTaskId: 'child-task-detached-1',
          label: 'Inspect README',
          subagentType: 'researcher',
          contextMode: 'minimal',
          depth: 1,
          phase: 'resumed',
          status: 'background_running',
          executionState: 'background_running',
          continuationKind: 'background_resume',
          resumable: true,
          summary:
              'Delegated child runtime is still running in the background.',
          startedAtEpochMs: 1800,
          updatedAtEpochMs: 4200,
          eventCount: 0,
        ),
      ],
      events: <OpenCrayChatRuntimeEventSnapshot>[],
    );

    expect(runtimeSnapshotVersion(snapshot), 4200);
  });

  test('runtimeSnapshotVersion tracks live assistant draft updates', () {
    const snapshot = OpenCrayChatRuntimeSnapshot(
      sessionId: 'session-1',
      activeRuns: <OpenCrayChatRunSnapshot>[],
      events: <OpenCrayChatRuntimeEventSnapshot>[],
      liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
        OpenCrayChatLiveAssistantDraftSnapshot(
          runId: 'run-1',
          taskId: 'task-1',
          pendingMessageId: 'pending-1',
          text: 'Streaming answer',
          updatedAtEpochMs: 5300,
        ),
      ],
    );

    expect(runtimeSnapshotVersion(snapshot), 5300);
  });

  test(
    'chatFeatureStatesEquivalent ignores recreated but unchanged UI data',
    () {
      const left = ChatFeatureState(
        variant: ChatPrototypeVariant.main,
        screenTitle: 'Chat',
        summary: ChatSessionSummary(
          title: 'Session',
          badge: 'SAFE',
          body: 'Summary',
        ),
        messages: <ChatMessageData>[
          ChatMessageData(
            messageId: 'message-1',
            kind: ChatMessageKind.inbound,
            text: 'Streamed answer',
            meta: 'now',
            createdAtEpochMs: 1000,
            isEphemeral: true,
            attachments: <ChatMessageAttachmentData>[
              ChatMessageAttachmentData(
                attachmentId: 'attachment-1',
                kind: ChatAttachmentKind.file,
                displayName: 'notes.txt',
                localPath: 'workspace/notes.txt',
                mimeType: 'text/plain',
                sizeBytes: 12,
              ),
            ],
          ),
        ],
        runTraces: <ChatRunTraceData>[
          ChatRunTraceData(
            runId: 'run-1',
            taskId: 'task-1',
            label: 'Searching',
            body: 'Looking for sources',
            history: <ChatRunTraceHistoryEntry>[
              ChatRunTraceHistoryEntry(
                label: 'Call',
                body: 'Searching docs',
                inspectorActorId: 'main',
                inspectorActorLabel: 'Main',
                inspectorCallParts: <ChatRunTraceInspectorTextPart>[
                  ChatRunTraceInspectorTextPart(
                    text: 'search docs',
                    semantic: ChatRunTraceInspectorTextSemantic.action,
                  ),
                ],
                inspectorCallDetail: 'query=streaming',
                inspectorResultBody: 'done',
              ),
            ],
            isHighRisk: true,
            canInterrupt: true,
            retryLabel: 'Retry',
            previewCard: ChatRunTracePreviewCardData(
              url: 'https://example.com',
              status: ChatRunTracePreviewStatus.ready,
              port: 443,
              path: '/docs',
              provider: 'browser',
              httpStatusCode: 200,
              message: 'OK',
            ),
            sessionCard: ChatRunTraceSandboxSessionCardData(
              sessionPresent: true,
              source: ChatRunTraceSandboxSessionSource.activeMemory,
              lifecycleStatus: ChatRunTraceSandboxSessionLifecycleStatus.active,
              provider: 'e2b',
              sandboxId: 'sandbox-1',
              sandboxDomain: 'example.dev',
              templateId: 'template-1',
              updatedAtEpochMs: 2000,
              sessionLastActivityAtEpochMs: 2100,
              sessionStaleAfterEpochMs: 2200,
              lastPreviewUrl: 'https://preview.example.com',
              lastPreviewProbeStatus: ChatRunTracePreviewStatus.ready,
              lastPreviewProbeObservedAtEpochMs: 2300,
              lastPreviewProbeSource: 'runtime',
              autoRefreshAfterMs: 5000,
              previewCandidatePorts: <int>[3000, 8080],
              runningRequestIds: <String>['request-1'],
            ),
          ),
        ],
        composer: ChatComposerState(
          placeholder: 'Message OpenCray',
          todos: <ChatTodoItemData>[
            ChatTodoItemData(
              content: 'Write docs',
              status: ChatTodoStatus.inProgress,
              activeForm: 'Writing docs',
            ),
          ],
          attachments: <ChatAttachmentData>[
            ChatAttachmentData(
              id: 'draft-1',
              kind: ChatAttachmentKind.file,
              label: 'spec.md',
              detail: '12 KB',
              accentColor: Colors.blue,
              draftAttachment: OpenCrayChatDraftAttachment(
                kind: OpenCrayChatDraftAttachmentKind.file,
                displayName: 'spec.md',
                relativePath: 'docs/spec.md',
                mimeType: 'text/markdown',
                sizeBytes: 12000,
              ),
            ),
          ],
          selectedCommand: '/plan',
          commandOptions: <ChatCommandOptionData>[
            ChatCommandOptionData(label: '/plan', description: 'Plan the work'),
          ],
          addActions: <ChatAddActionData>[
            ChatAddActionData(label: 'Attach file', icon: Icons.attach_file),
          ],
          showAddMenu: true,
        ),
        drawer: ChatSessionsDrawerState(
          eyebrow: 'Recent sessions',
          title: 'Sessions',
          ctaLabel: 'New session',
          sessions: <ChatSessionListItemData>[
            ChatSessionListItemData(
              sessionId: 'session-1',
              title: 'Session',
              preview: 'Preview',
              meta: 'now',
              isSelected: true,
              lastMessageAtEpochMs: 2400,
              unreadCount: 1,
            ),
          ],
        ),
        pendingApprovals: <ChatPendingApprovalData>[
          ChatPendingApprovalData(
            runId: 'run-1',
            taskId: 'task-1',
            title: 'Approve',
            body: 'Need approval',
            approveLabel: 'Approve',
            rejectLabel: 'Reject',
            isHighRisk: true,
            supportsSessionApproval: true,
            approveForSessionLabel: 'Allow for session',
            toolName: 'LS',
            requestSummary: 'List files',
            primaryDetail: 'workspace',
            pathDetails: <String>['/workspace'],
            workingDirectory: '/workspace',
            reason: 'Need context',
            message: 'Allow access',
          ),
        ],
        modeLabel: 'SAFE',
        drawerOpen: true,
        sessionButtonLabel: 'Sessions',
        emptyThreadHeight: 260,
        isInputEnabled: false,
      );

      final right = ChatFeatureState(
        variant: ChatPrototypeVariant.main,
        screenTitle: 'Chat',
        summary: const ChatSessionSummary(
          title: 'Session',
          badge: 'SAFE',
          body: 'Summary',
        ),
        messages: const <ChatMessageData>[
          ChatMessageData(
            messageId: 'message-1',
            kind: ChatMessageKind.inbound,
            text: 'Streamed answer',
            meta: 'now',
            createdAtEpochMs: 1000,
            isEphemeral: true,
            attachments: <ChatMessageAttachmentData>[
              ChatMessageAttachmentData(
                attachmentId: 'attachment-1',
                kind: ChatAttachmentKind.file,
                displayName: 'notes.txt',
                localPath: 'workspace/notes.txt',
                mimeType: 'text/plain',
                sizeBytes: 12,
              ),
            ],
          ),
        ],
        runTraces: const <ChatRunTraceData>[
          ChatRunTraceData(
            runId: 'run-1',
            taskId: 'task-1',
            label: 'Searching',
            body: 'Looking for sources',
            history: <ChatRunTraceHistoryEntry>[
              ChatRunTraceHistoryEntry(
                label: 'Call',
                body: 'Searching docs',
                inspectorActorId: 'main',
                inspectorActorLabel: 'Main',
                inspectorCallParts: <ChatRunTraceInspectorTextPart>[
                  ChatRunTraceInspectorTextPart(
                    text: 'search docs',
                    semantic: ChatRunTraceInspectorTextSemantic.action,
                  ),
                ],
                inspectorCallDetail: 'query=streaming',
                inspectorResultBody: 'done',
              ),
            ],
            isHighRisk: true,
            canInterrupt: true,
            retryLabel: 'Retry',
            previewCard: ChatRunTracePreviewCardData(
              url: 'https://example.com',
              status: ChatRunTracePreviewStatus.ready,
              port: 443,
              path: '/docs',
              provider: 'browser',
              httpStatusCode: 200,
              message: 'OK',
            ),
            sessionCard: ChatRunTraceSandboxSessionCardData(
              sessionPresent: true,
              source: ChatRunTraceSandboxSessionSource.activeMemory,
              lifecycleStatus: ChatRunTraceSandboxSessionLifecycleStatus.active,
              provider: 'e2b',
              sandboxId: 'sandbox-1',
              sandboxDomain: 'example.dev',
              templateId: 'template-1',
              updatedAtEpochMs: 2000,
              sessionLastActivityAtEpochMs: 2100,
              sessionStaleAfterEpochMs: 2200,
              lastPreviewUrl: 'https://preview.example.com',
              lastPreviewProbeStatus: ChatRunTracePreviewStatus.ready,
              lastPreviewProbeObservedAtEpochMs: 2300,
              lastPreviewProbeSource: 'runtime',
              autoRefreshAfterMs: 5000,
              previewCandidatePorts: <int>[3000, 8080],
              runningRequestIds: <String>['request-1'],
            ),
          ),
        ],
        composer: ChatComposerState(
          placeholder: 'Message OpenCray',
          todos: <ChatTodoItemData>[
            ChatTodoItemData(
              content: 'Write docs',
              status: ChatTodoStatus.inProgress,
              activeForm: 'Writing docs',
            ),
          ],
          attachments: <ChatAttachmentData>[
            ChatAttachmentData(
              id: 'draft-1',
              kind: ChatAttachmentKind.file,
              label: 'spec.md',
              detail: '12 KB',
              accentColor: Colors.blue,
              draftAttachment: OpenCrayChatDraftAttachment(
                kind: OpenCrayChatDraftAttachmentKind.file,
                displayName: 'spec.md',
                relativePath: 'docs/spec.md',
                mimeType: 'text/markdown',
                sizeBytes: 12000,
              ),
            ),
          ],
          selectedCommand: '/plan',
          commandOptions: <ChatCommandOptionData>[
            ChatCommandOptionData(label: '/plan', description: 'Plan the work'),
          ],
          addActions: <ChatAddActionData>[
            ChatAddActionData(label: 'Attach file', icon: Icons.attach_file),
          ],
          showAddMenu: true,
        ),
        drawer: const ChatSessionsDrawerState(
          eyebrow: 'Recent sessions',
          title: 'Sessions',
          ctaLabel: 'New session',
          sessions: <ChatSessionListItemData>[
            ChatSessionListItemData(
              sessionId: 'session-1',
              title: 'Session',
              preview: 'Preview',
              meta: 'now',
              isSelected: true,
              lastMessageAtEpochMs: 2400,
              unreadCount: 1,
            ),
          ],
        ),
        pendingApprovals: const <ChatPendingApprovalData>[
          ChatPendingApprovalData(
            runId: 'run-1',
            taskId: 'task-1',
            title: 'Approve',
            body: 'Need approval',
            approveLabel: 'Approve',
            rejectLabel: 'Reject',
            isHighRisk: true,
            supportsSessionApproval: true,
            approveForSessionLabel: 'Allow for session',
            toolName: 'LS',
            requestSummary: 'List files',
            primaryDetail: 'workspace',
            pathDetails: <String>['/workspace'],
            workingDirectory: '/workspace',
            reason: 'Need context',
            message: 'Allow access',
          ),
        ],
        modeLabel: 'SAFE',
        drawerOpen: true,
        sessionButtonLabel: 'Sessions',
        emptyThreadHeight: 260,
        isInputEnabled: false,
      );

      expect(chatFeatureStatesEquivalent(left, right), isTrue);
    },
  );

  test('chatMessagesEquivalent detects streamed text changes', () {
    const left = <ChatMessageData>[
      ChatMessageData(
        messageId: 'pending-1',
        kind: ChatMessageKind.inbound,
        text: 'First chunk',
        createdAtEpochMs: 1000,
        isEphemeral: true,
      ),
    ];
    const right = <ChatMessageData>[
      ChatMessageData(
        messageId: 'pending-1',
        kind: ChatMessageKind.inbound,
        text: 'First chunk and more',
        createdAtEpochMs: 1000,
        isEphemeral: true,
      ),
    ];

    expect(chatMessagesEquivalent(left, right), isFalse);
  });

  test(
    'shouldReplaceObservedChatSnapshot accepts same-version newer snapshots that prune message tails',
    () {
      final current = _hostChatSnapshot(
        updatedAtEpochMs: 1000,
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Inspect the workspace.',
            createdAtEpochMs: 1000,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'message-1',
            kind: 'inbound',
            text: 'Planning',
            createdAtEpochMs: 2000,
            isEphemeral: true,
          ),
        ],
      );
      final incoming = _hostChatSnapshot(
        updatedAtEpochMs: 2000,
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Inspect the workspace.',
            createdAtEpochMs: 1000,
          ),
        ],
      );

      expect(shouldReplaceObservedChatSnapshot(current, incoming), isTrue);
    },
  );

  test(
    'shouldReplaceObservedChatSnapshot rejects same-version pruned snapshots without a newer update timestamp',
    () {
      final current = _hostChatSnapshot(
        updatedAtEpochMs: 2000,
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Inspect the workspace.',
            createdAtEpochMs: 1000,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'message-1',
            kind: 'inbound',
            text: 'Planning',
            createdAtEpochMs: 2000,
            isEphemeral: true,
          ),
        ],
      );
      final incoming = _hostChatSnapshot(
        updatedAtEpochMs: 2000,
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Inspect the workspace.',
            createdAtEpochMs: 1000,
          ),
        ],
      );

      expect(shouldReplaceObservedChatSnapshot(current, incoming), isFalse);
    },
  );

  test(
    'shouldReplaceObservedRuntimeSnapshot ignores thinner snapshots at the same version',
    () {
      const assistantPhase = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-1',
        taskId: 'task-1',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        isFinal: false,
        stage: 'Planning',
        text: 'Inspecting the project layout.',
      );
      const current = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2200,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-1',
            taskId: 'task-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2200,
            attempt: 1,
            isTerminal: false,
            lastEvent: assistantPhase,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-1',
            taskId: 'task-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          assistantPhase,
        ],
      );
      const incoming = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2200,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-1',
            taskId: 'task-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2200,
            attempt: 1,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-1',
            taskId: 'task-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
        ],
      );

      expect(shouldReplaceObservedRuntimeSnapshot(current, incoming), isFalse);
    },
  );

  test(
    'shouldReplaceObservedRuntimeSnapshot accepts process details hidden behind a draft version',
    () {
      const current = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-process-hidden-1',
            taskId: 'task-process-hidden-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2200,
            attempt: 1,
            pendingMessageId: 'pending-process-hidden-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-process-hidden-1',
            taskId: 'task-process-hidden-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
        ],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
          OpenCrayChatLiveAssistantDraftSnapshot(
            runId: 'run-process-hidden-1',
            taskId: 'task-process-hidden-1',
            pendingMessageId: 'pending-process-hidden-1',
            text: 'Streaming final answer',
            updatedAtEpochMs: 5000,
          ),
        ],
      );
      const incoming = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-process-hidden-1',
            taskId: 'task-process-hidden-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            pendingMessageId: 'pending-process-hidden-1',
            managedProcessIds: <String>['proc-hidden'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-hidden',
                status: 'running',
                command: 'npm',
                args: <String>['run', 'dev'],
                processStarted: true,
                startedAtEpochMs: 2400,
                updatedAtEpochMs: 3000,
                stdoutPreview: 'ready on http://localhost:3000',
              ),
            ],
            runningManagedProcessCount: 1,
            hasLiveManagedProcesses: true,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-process-hidden-1',
            taskId: 'task-process-hidden-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
        ],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
          OpenCrayChatLiveAssistantDraftSnapshot(
            runId: 'run-process-hidden-1',
            taskId: 'task-process-hidden-1',
            pendingMessageId: 'pending-process-hidden-1',
            text: 'Streaming final answer',
            updatedAtEpochMs: 5000,
          ),
        ],
      );

      expect(runtimeSnapshotVersion(current), runtimeSnapshotVersion(incoming));
      expect(shouldReplaceObservedRuntimeSnapshot(current, incoming), isTrue);
    },
  );

  test(
    'shouldReplaceObservedRuntimeSnapshot accepts same-version process output changes',
    () {
      const current = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 3000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-process-output-1',
            taskId: 'task-process-output-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            pendingMessageId: 'pending-process-output-1',
            managedProcessIds: <String>['proc-output'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-output',
                status: 'running',
                command: 'npm',
                args: <String>['run', 'dev'],
                processStarted: true,
                startedAtEpochMs: 2000,
                updatedAtEpochMs: 3000,
                stdoutPreview: 'alpha output',
              ),
            ],
            runningManagedProcessCount: 1,
            hasLiveManagedProcesses: true,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-process-output-1',
            taskId: 'task-process-output-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
        ],
      );
      const incoming = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 3000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-process-output-1',
            taskId: 'task-process-output-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            pendingMessageId: 'pending-process-output-1',
            managedProcessIds: <String>['proc-output'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-output',
                status: 'running',
                command: 'npm',
                args: <String>['run', 'dev'],
                processStarted: true,
                startedAtEpochMs: 2000,
                updatedAtEpochMs: 3000,
                stdoutPreview: 'bravo output',
              ),
            ],
            runningManagedProcessCount: 1,
            hasLiveManagedProcesses: true,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-process-output-1',
            taskId: 'task-process-output-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
        ],
      );

      expect(runtimeSnapshotVersion(current), runtimeSnapshotVersion(incoming));
      expect(shouldReplaceObservedRuntimeSnapshot(current, incoming), isTrue);
    },
  );

  test(
    'resolveChatRuntimeSnapshot merges process details with newer drafts',
    () {
      const embedded = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 6000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-merge-process-1',
            taskId: 'task-merge-process-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2200,
            attempt: 1,
            pendingMessageId: 'pending-merge-process-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
          OpenCrayChatLiveAssistantDraftSnapshot(
            runId: 'run-merge-process-1',
            taskId: 'task-merge-process-1',
            pendingMessageId: 'pending-merge-process-1',
            text: 'Newer final draft',
            updatedAtEpochMs: 6000,
          ),
        ],
      );
      const streamed = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 3000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-merge-process-1',
            taskId: 'task-merge-process-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            pendingMessageId: 'pending-merge-process-1',
            managedProcessIds: <String>['proc-merge'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-merge',
                status: 'running',
                command: 'npm',
                args: <String>['run', 'dev'],
                processStarted: true,
                startedAtEpochMs: 2400,
                updatedAtEpochMs: 3000,
                stdoutPreview: 'ready on http://localhost:3000',
              ),
            ],
            runningManagedProcessCount: 1,
            hasLiveManagedProcesses: true,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

      final resolved = resolveChatRuntimeSnapshot(embedded, streamed);

      expect(resolved!.liveAssistantDrafts.single.text, 'Newer final draft');
      expect(
        resolved.activeRuns.single.managedProcesses.single.processId,
        'proc-merge',
      );
    },
  );

  test(
    'resolveChatRuntimeSnapshot accepts terminal state at the same version',
    () {
      const current = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-terminal-merge-1',
            taskId: 'task-terminal-merge-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 5000,
            attempt: 1,
            pendingMessageId: 'pending-terminal-merge-1',
            managedProcessIds: <String>['proc-terminal'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-terminal',
                status: 'running',
                command: 'npm',
                args: <String>['run', 'dev'],
                processStarted: true,
                startedAtEpochMs: 2000,
                updatedAtEpochMs: 5000,
                stdoutPreview: 'ready on http://localhost:3000',
              ),
            ],
            runningManagedProcessCount: 1,
            hasLiveManagedProcesses: true,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      const incoming = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        retainedRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-terminal-merge-1',
            taskId: 'task-terminal-merge-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 5000,
            attempt: 1,
            pendingMessageId: 'pending-terminal-merge-1',
            isTerminal: true,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

      final resolved = resolveChatRuntimeSnapshot(current, incoming);

      expect(shouldReplaceObservedRuntimeSnapshot(current, incoming), isTrue);
      expect(resolved!.activeRuns, isEmpty);
      expect(resolved.retainedRuns.single.isTerminal, isTrue);
    },
  );

  test(
    'resolveChatRuntimeSnapshot accepts explicit retry continuation from terminal state',
    () {
      const current = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        retainedRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-retry-merge-1',
            taskId: 'task-retry-merge-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 5000,
            attempt: 1,
            executionOrdinal: 1,
            executionId: 'old-execution',
            executionKind: 'initial',
            pendingMessageId: 'pending-retry-merge-1',
            lifecycleState: 'failed',
            taskState: 'failed',
            executionStatus: 'failed',
            errorCode: 'RESTART_REQUIRES_EXPLICIT_RETRY',
            errorMessage: 'Retry explicitly before continuing.',
            responseFormat: 'markdown',
            finalAttachments: <OpenCrayChatAttachmentSnapshot>[
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'old-attachment',
                displayName: 'old-result.txt',
              ),
            ],
            managedProcessIds: <String>['old-process'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'old-process',
                status: 'exited',
                command: 'npm',
                startedAtEpochMs: 2000,
                updatedAtEpochMs: 5000,
                stdoutPreview: 'old process output',
              ),
            ],
            lastEvent: OpenCrayChatRuntimeEventSnapshot(
              kind: 'assistant_phase',
              runId: 'run-retry-merge-1',
              taskId: 'task-retry-merge-1',
              emittedAtEpochMs: 5000,
              executionId: 'old-execution',
              executionOrdinal: 1,
              executionKind: 'initial',
              isFinal: true,
              text: 'Old terminal answer.',
            ),
            isTerminal: true,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      const incoming = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-retry-merge-1',
            taskId: 'task-retry-merge-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 4999,
            attempt: 1,
            pendingMessageId: 'pending-retry-merge-1',
            pendingExecutionKind: 'retry',
            lifecycleState: 'queued',
            taskState: 'queued',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

      final resolved = resolveChatRuntimeSnapshot(current, incoming);

      expect(shouldReplaceObservedRuntimeSnapshot(current, incoming), isTrue);
      expect(resolved!.retainedRuns, isEmpty);
      expect(resolved.activeRuns.single.isTerminal, isFalse);
      expect(resolved.activeRuns.single.pendingExecutionKind, 'retry');
      expect(resolved.activeRuns.single.lifecycleState, 'queued');
      expect(resolved.activeRuns.single.executionStatus, isNull);
      expect(resolved.activeRuns.single.errorCode, isNull);
      expect(resolved.activeRuns.single.errorMessage, isNull);
      expect(resolved.activeRuns.single.executionId, isNull);
      expect(resolved.activeRuns.single.executionKind, isNull);
      expect(resolved.activeRuns.single.executionOrdinal, 0);
      expect(resolved.activeRuns.single.responseFormat, isNull);
      expect(resolved.activeRuns.single.finalAttachments, isEmpty);
      expect(resolved.activeRuns.single.managedProcesses, isEmpty);
      expect(resolved.activeRuns.single.lastEvent, isNull);
    },
  );

  test(
    'resolveChatRuntimeSnapshot preserves process details when terminal update is thin',
    () {
      const running = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-terminal-thin-1',
            taskId: 'task-terminal-thin-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 5000,
            attempt: 1,
            pendingMessageId: 'pending-terminal-thin-1',
            managedProcessIds: <String>['proc-terminal-thin'],
            managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
              OpenCrayChatManagedProcessSnapshot(
                processId: 'proc-terminal-thin',
                status: 'running',
                command: 'npm',
                args: <String>['run', 'dev'],
                processStarted: true,
                startedAtEpochMs: 2400,
                updatedAtEpochMs: 5000,
                stdoutPreview: 'ready on http://localhost:3000',
              ),
            ],
            runningManagedProcessCount: 1,
            hasLiveManagedProcesses: true,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      const terminal = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        retainedRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-terminal-thin-1',
            taskId: 'task-terminal-thin-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 5000,
            attempt: 1,
            pendingMessageId: 'pending-terminal-thin-1',
            isTerminal: true,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

      final resolved = resolveChatRuntimeSnapshot(running, terminal);

      expect(resolved!.activeRuns, isEmpty);
      expect(resolved.retainedRuns.single.isTerminal, isTrue);
      expect(resolved.retainedRuns.single.runningManagedProcessCount, 0);
      expect(resolved.retainedRuns.single.hasLiveManagedProcesses, isFalse);
      expect(
        resolved.retainedRuns.single.managedProcesses.single.processId,
        'proc-terminal-thin',
      );
    },
  );

  test(
    'resolveChatRuntimeSnapshot preserves richer event details when timestamps tie',
    () {
      const rich = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-rich-event-1',
            taskId: 'task-rich-event-1',
            emittedAtEpochMs: 5000,
            toolName: 'Read',
            content: 'full file contents',
            contentPreview: 'full file',
          ),
        ],
      );
      const thin = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-rich-event-1',
            taskId: 'task-rich-event-1',
            emittedAtEpochMs: 5000,
            toolName: 'Read',
          ),
        ],
      );

      final resolved = resolveChatRuntimeSnapshot(rich, thin);

      expect(resolved!.events.single.content, 'full file contents');
      expect(resolved.events.single.contentPreview, 'full file');
    },
  );

  test('shouldReplaceObservedRuntimeSnapshot accepts session switches', () {
    const current = OpenCrayChatRuntimeSnapshot(
      sessionId: 'session-1',
      updatedAtEpochMs: 5000,
      activeRuns: <OpenCrayChatRunSnapshot>[
        OpenCrayChatRunSnapshot(
          sessionId: 'session-1',
          runId: 'run-session-switch-1',
          taskId: 'task-session-switch-1',
          acceptedAtEpochMs: 1000,
          updatedAtEpochMs: 5000,
          attempt: 1,
          pendingMessageId: 'pending-session-switch-1',
          isTerminal: false,
        ),
      ],
      events: <OpenCrayChatRuntimeEventSnapshot>[],
    );
    const incoming = OpenCrayChatRuntimeSnapshot(
      sessionId: 'session-2',
      updatedAtEpochMs: 1000,
      activeRuns: <OpenCrayChatRunSnapshot>[],
      events: <OpenCrayChatRuntimeEventSnapshot>[],
    );

    expect(shouldReplaceObservedRuntimeSnapshot(current, incoming), isTrue);
  });

  test(
    'shouldReplaceObservedChatSnapshot accepts embedded runtime detail when versions tie',
    () {
      final current = _hostChatSnapshot(
        updatedAtEpochMs: 5000,
        runtimeActivity: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 5000,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-chat-runtime-tie-1',
              taskId: 'task-chat-runtime-tie-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 5000,
              attempt: 1,
              pendingMessageId: 'pending-chat-runtime-tie-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Start the dev server.',
            createdAtEpochMs: 1000,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-chat-runtime-tie-1',
            kind: 'inbound',
            text: 'Thinking',
            createdAtEpochMs: 1100,
          ),
        ],
      );
      final incoming = _hostChatSnapshot(
        updatedAtEpochMs: 5000,
        runtimeActivity: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 5000,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-chat-runtime-tie-1',
              taskId: 'task-chat-runtime-tie-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 5000,
              attempt: 1,
              pendingMessageId: 'pending-chat-runtime-tie-1',
              managedProcessIds: <String>['proc-chat-runtime-tie'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-chat-runtime-tie',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  processStarted: true,
                  startedAtEpochMs: 2400,
                  updatedAtEpochMs: 5000,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Start the dev server.',
            createdAtEpochMs: 1000,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-chat-runtime-tie-1',
            kind: 'inbound',
            text: 'Thinking',
            createdAtEpochMs: 1100,
          ),
        ],
      );

      expect(shouldReplaceObservedChatSnapshot(current, incoming), isTrue);
    },
  );

  testWidgets(
    'same-version streamed runtime overrides a thinner embedded runtime when mapping UI',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      const commentaryEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-streamed-thicker-1',
        taskId: 'task-streamed-thicker-1',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        isFinal: false,
        stage: 'Planning',
        text: 'Inspecting the project layout.',
      );
      const embeddedRuntime = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2200,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 2200,
          runtimeActivity: embeddedRuntime,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-streamed-thicker-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1000,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-streamed-thicker-1',
              taskId: 'task-streamed-thicker-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              pendingMessageId: 'pending-streamed-thicker-1',
              isTerminal: false,
              lastEvent: commentaryEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-streamed-thicker-1',
              taskId: 'task-streamed-thicker-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            commentaryEvent,
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(
          const ValueKey<String>('chat-run-trace-run-streamed-thicker-1'),
        ),
        findsOneWidget,
      );
      expect(find.text('Inspecting the project layout.'), findsWidgets);
    },
  );

  testWidgets(
    'newer thin chat snapshots do not cover runtime projected process bubbles',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final chatSnapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(chatSnapshots.close);
      addTearDown(runtimeSnapshots.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the dev server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-process-stream-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1000,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        chatSnapshotStream: chatSnapshots.stream,
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 3200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-process-stream-1',
              taskId: 'task-process-stream-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3200,
              attempt: 1,
              pendingMessageId: 'pending-process-stream-1',
              managedProcessIds: <String>['proc-stream'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-stream',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  processStarted: true,
                  startedAtEpochMs: 2400,
                  updatedAtEpochMs: 3200,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream'), findsOneWidget);
      expect(
        find.textContaining('ready on http://localhost:3000'),
        findsWidgets,
      );

      const thinRuntimeWithNewDraft = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 6000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-process-stream-1',
            taskId: 'task-process-stream-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3200,
            attempt: 1,
            pendingMessageId: 'pending-process-stream-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
          OpenCrayChatLiveAssistantDraftSnapshot(
            runId: 'run-process-stream-1',
            taskId: 'task-process-stream-1',
            pendingMessageId: 'pending-process-stream-1',
            text: 'Streaming answer after the process starts.',
            updatedAtEpochMs: 6000,
          ),
        ],
      );
      chatSnapshots.add(
        _hostChatSnapshot(
          updatedAtEpochMs: 6000,
          runtimeActivity: thinRuntimeWithNewDraft,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the dev server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-process-stream-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream'), findsOneWidget);
      expect(
        find.textContaining('ready on http://localhost:3000'),
        findsWidgets,
      );
      expect(
        find.text('Streaming answer after the process starts.'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'late older chat snapshots do not roll back newer message bubbles',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(updatedAtEpochMs: 1000),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1000,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        chatSnapshotStream: snapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      snapshots.add(
        _hostChatSnapshot(
          updatedAtEpochMs: 2000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'message-1',
              kind: 'inbound',
              text: 'Planning\n\nInspecting the project layout.',
              createdAtEpochMs: 2000,
              isEphemeral: true,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Planning'), findsOneWidget);
      expect(find.text('Inspecting the project layout.'), findsOneWidget);

      snapshots.add(_hostChatSnapshot(updatedAtEpochMs: 1500));
      await tester.pumpAndSettle();

      expect(find.text('Planning'), findsOneWidget);
      expect(find.text('Inspecting the project layout.'), findsOneWidget);
    },
  );

  testWidgets(
    'late older runtime snapshots do not roll back projected process bubbles or inspector history',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      const assistantPhase = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-progress-1',
        taskId: 'task-progress-1',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        isFinal: false,
        stage: 'Planning',
        text: 'Inspecting the project layout.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1000,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
              lastEvent: assistantPhase,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            assistantPhase,
          ],
        ),
      );
      await tester.pumpAndSettle();

      final String projectedBubbleMessageId =
          'runtime-assistant-commentary-run-progress-1--1-Planning-2200-${javaStringHashCode('Inspecting the project layout.')}';
      final projectedBubble = find.byKey(
        ValueKey<String>('chat-bubble-$projectedBubbleMessageId'),
      );
      expect(projectedBubble, findsOneWidget);

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-progress-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await _openRunTraceFullscreen(tester, runTraceFinder);
      expect(find.text('Inspecting the project layout.'), findsWidgets);
      Navigator.of(tester.element(find.byType(Scaffold))).pop();
      await tester.pumpAndSettle();

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1500,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1500,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(projectedBubble, findsOneWidget);
      await _openRunTraceFullscreen(tester, runTraceFinder);
      expect(find.text('Inspecting the project layout.'), findsWidgets);
    },
  );

  testWidgets(
    'live assistant drafts replace the pending thinking bubble in place',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a long summary.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-1',
              taskId: 'task-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-1',
              taskId: 'task-1',
              pendingMessageId: 'pending-1',
              text: 'Streaming answer in progress',
              updatedAtEpochMs: 1300,
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final pendingBubble = find.byKey(
        const ValueKey<String>('chat-bubble-pending-1'),
      );
      expect(pendingBubble, findsOneWidget);
      expect(
        find.descendant(
          of: pendingBubble,
          matching: find.text('Streaming answer in progress'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(of: pendingBubble, matching: find.text('Thinking')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'run traces stay under their own turn instead of collecting under the latest message',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'First request',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Second request',
              createdAtEpochMs: 2000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-2',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 2100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-turn-1',
              taskId: 'task-turn-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1300,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-turn-2',
              taskId: 'task-turn-2',
              acceptedAtEpochMs: 2000,
              updatedAtEpochMs: 2300,
              attempt: 1,
              pendingMessageId: 'pending-2',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-turn-1',
              taskId: 'task-turn-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-turn-2',
              taskId: 'task-turn-2',
              emittedAtEpochMs: 2000,
              phase: 'start',
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final pendingOneFinder = find.byKey(
        const ValueKey<String>('chat-bubble-pending-1'),
      );
      final secondOutboundFinder = find.byKey(
        const ValueKey<String>('chat-bubble-message-2-outbound'),
      );
      final traceOneFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-turn-1'),
      );
      final traceTwoFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-turn-2'),
      );

      expect(pendingOneFinder, findsOneWidget);
      expect(secondOutboundFinder, findsOneWidget);
      expect(traceOneFinder, findsOneWidget);
      expect(traceTwoFinder, findsOneWidget);

      final double pendingOneTop = tester.getTopLeft(pendingOneFinder).dy;
      final double traceOneTop = tester.getTopLeft(traceOneFinder).dy;
      final double secondOutboundTop = tester
          .getTopLeft(secondOutboundFinder)
          .dy;
      final double traceTwoTop = tester.getTopLeft(traceTwoFinder).dy;

      expect(traceOneTop, lessThan(pendingOneTop));
      expect(traceOneTop, lessThan(secondOutboundTop));
      expect(traceTwoTop, greaterThan(secondOutboundTop));
    },
  );

  testWidgets(
    'live assistant drafts still render a chat bubble when the pending placeholder is missing',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a long summary.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-1',
              taskId: 'task-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-1',
              taskId: 'task-1',
              pendingMessageId: 'pending-1',
              text: 'Streaming answer in progress',
              updatedAtEpochMs: 1300,
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final pendingBubble = find.byKey(
        const ValueKey<String>('chat-bubble-pending-1'),
      );
      expect(pendingBubble, findsOneWidget);
      expect(
        find.descendant(
          of: pendingBubble,
          matching: find.text('Streaming answer in progress'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'live assistant draft events update the chat bubble without a runtime snapshot refresh',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final draftEvents =
          StreamController<OpenCrayChatLiveAssistantDraftEvent>.broadcast();
      addTearDown(draftEvents.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a long summary.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        liveAssistantDraftEventStream: draftEvents.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-1',
          taskId: 'task-1',
          pendingMessageId: 'pending-1',
          text: 'First streamed chunk',
          updatedAtEpochMs: 1300,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('First streamed chunk'), findsOneWidget);

      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-1',
          taskId: 'task-1',
          pendingMessageId: 'pending-1',
          text: 'First streamed chunk and more',
          updatedAtEpochMs: 1400,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('First streamed chunk'), findsNothing);
      expect(find.text('First streamed chunk and more'), findsOneWidget);
    },
  );

  testWidgets(
    'live assistant draft events coalesce to the latest visible text without rolling back',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final draftEvents =
          StreamController<OpenCrayChatLiveAssistantDraftEvent>.broadcast();
      addTearDown(draftEvents.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a concise report.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-coalesce-1',
              taskId: 'task-coalesce-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-coalesce-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        liveAssistantDraftEventStream: draftEvents.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-coalesce-1',
          taskId: 'task-coalesce-1',
          pendingMessageId: 'pending-coalesce-1',
          text: 'Complete streamed answer.',
          updatedAtEpochMs: 1500,
        ),
      );
      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-coalesce-1',
          taskId: 'task-coalesce-1',
          pendingMessageId: 'pending-coalesce-1',
          text: 'Complete',
          updatedAtEpochMs: 1400,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Complete streamed answer.'), findsOneWidget);
      expect(find.text('Complete'), findsNothing);
    },
  );

  testWidgets('runtime deltas clear stale live assistant draft bubbles', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    const activeRun = OpenCrayChatRunSnapshot(
      sessionId: 'session-1',
      runId: 'run-1',
      taskId: 'task-1',
      acceptedAtEpochMs: 1000,
      updatedAtEpochMs: 1200,
      attempt: 1,
      pendingMessageId: 'pending-1',
      isTerminal: false,
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Write a long summary.',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
          OpenCrayChatLiveAssistantDraftSnapshot(
            runId: 'run-1',
            taskId: 'task-1',
            pendingMessageId: 'pending-1',
            text: 'Streaming answer in progress',
            updatedAtEpochMs: 1300,
          ),
        ],
        updatedAtEpochMs: 1300,
      ),
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Streaming answer in progress'), findsOneWidget);

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 1,
        activeRuns: <OpenCrayChatRunSnapshot>[activeRun],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        totalLength: 0,
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[],
        updatedAtEpochMs: 1500,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Streaming answer in progress'), findsNothing);
  });

  testWidgets(
    'live assistant draft events do not recreate a pending bubble after commentary is projected',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final draftEvents =
          StreamController<OpenCrayChatLiveAssistantDraftEvent>.broadcast();
      addTearDown(draftEvents.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'assistant_phase',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 2200,
              phase: 'commentary',
              isFinal: false,
              turn: 0,
              stage: 'Planning',
              text:
                  'Scanning README and Gradle files before choosing the next tool.',
            ),
          ],
        ),
        liveAssistantDraftEventStream: draftEvents.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-progress-1',
          taskId: 'task-progress-1',
          pendingMessageId: 'pending-1',
          text: 'Streaming answer in progress',
          updatedAtEpochMs: 2300,
        ),
      );
      await tester.pumpAndSettle();

      final commentaryBubbleText = find.descendant(
        of: find.byWidgetPredicate((widget) {
          final Key? key = widget.key;
          return key is ValueKey<String> &&
              key.value.startsWith('chat-bubble-') &&
              key.value != 'chat-bubble-pending-1';
        }),
        matching: find.textContaining(
          'Scanning README and Gradle files before choosing the next tool.',
        ),
      );
      expect(commentaryBubbleText, findsOneWidget);
      expect(
        find.descendant(
          of: find.byKey(const ValueKey<String>('chat-bubble-pending-1')),
          matching: find.text('Streaming answer in progress'),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'runtime event deltas update the open inspector without a runtime snapshot refresh',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-1',
              taskId: 'task-delta-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-delta-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-delta-1',
              taskId: 'task-delta-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsBeforeDelta =
          bridge.loadChatRuntimeSnapshotCallCount;

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-delta-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await _openRunTraceFullscreen(tester, runTraceFinder);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-delta-1'),
      );

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          totalLength: 2,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-delta-1',
              taskId: 'task-delta-1',
              emittedAtEpochMs: 1200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsBeforeDelta,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Read', findRichText: true),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('README.md', findRichText: true),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'runtime event deltas refresh the open inspector when only managed process output changes',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-process-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-process-1',
              taskId: 'task-delta-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-delta-process-1',
              managedProcessIds: <String>['proc-delta-process-1'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-delta-process-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1050,
                  updatedAtEpochMs: 1100,
                  stdoutPreview: 'starting dev server',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-delta-process-1',
              taskId: 'task-delta-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsBeforeDelta =
          bridge.loadChatRuntimeSnapshotCallCount;

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-delta-process-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await _openRunTraceFullscreen(tester, runTraceFinder);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-delta-process-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'starting dev server',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'ready on http://localhost:3000',
            findRichText: true,
          ),
        ),
        findsNothing,
      );

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 1,
          totalLength: 1,
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-process-1',
              taskId: 'task-delta-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1400,
              attempt: 1,
              pendingMessageId: 'pending-delta-process-1',
              managedProcessIds: <String>['proc-delta-process-1'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-delta-process-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1050,
                  updatedAtEpochMs: 1400,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          updatedAtEpochMs: 1400,
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsBeforeDelta,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'ready on http://localhost:3000',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'runtime event deltas ignore totalLength mismatches when sequence is contiguous',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-sequence-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-sequence-1',
              taskId: 'task-delta-sequence-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-delta-sequence-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-delta-sequence-1',
              taskId: 'task-delta-sequence-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsBeforeDelta =
          bridge.loadChatRuntimeSnapshotCallCount;

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 1,
          totalLength: 99,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-delta-sequence-1',
              taskId: 'task-delta-sequence-1',
              emittedAtEpochMs: 1200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsBeforeDelta,
      );
      expect(find.text('Thinking'), findsNothing);
      expect(
        find.byKey(
          const ValueKey<String>('chat-run-trace-run-delta-sequence-1'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('runtime event deltas resync when sequence jumps', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final runtimeEventDeltas =
        StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
    addTearDown(runtimeEventDeltas.close);
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        updatedAtEpochMs: 1000,
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Read the README.',
            createdAtEpochMs: 1000,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-delta-gap-1',
            kind: 'inbound',
            text: 'Thinking',
            createdAtEpochMs: 1100,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 1100,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-delta-gap-1',
            taskId: 'task-delta-gap-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 1100,
            attempt: 1,
            pendingMessageId: 'pending-delta-gap-1',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-delta-gap-1',
            taskId: 'task-delta-gap-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
        ],
      ),
      runtimeEventDeltaStream: runtimeEventDeltas.stream,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final int runtimeSnapshotLoadsBeforeDelta =
        bridge.loadChatRuntimeSnapshotCallCount;

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 1,
        totalLength: 2,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_call',
            runId: 'run-delta-gap-1',
            taskId: 'task-delta-gap-1',
            emittedAtEpochMs: 1200,
            toolName: 'Read',
            argumentsJson: '{"file_path":"README.md"}',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    runtimeEventDeltas.add(
      const OpenCrayChatRuntimeEventDelta(
        sessionId: 'session-1',
        sequence: 3,
        totalLength: 3,
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-delta-gap-1',
            taskId: 'task-delta-gap-1',
            emittedAtEpochMs: 1300,
            toolName: 'Read',
            contentPreview: 'README body loaded from disk.',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    expect(
      bridge.loadChatRuntimeSnapshotCallCount,
      greaterThan(runtimeSnapshotLoadsBeforeDelta),
    );
  });

  testWidgets(
    'runtime event deltas continue after a resync snapshot is ignored',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-resync-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-resync-1',
              taskId: 'task-delta-resync-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-delta-resync-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-delta-resync-1',
              taskId: 'task-delta-resync-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 1,
          totalLength: 2,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-delta-resync-1',
              taskId: 'task-delta-resync-1',
              emittedAtEpochMs: 1200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 3,
          totalLength: 3,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-delta-resync-1',
              taskId: 'task-delta-resync-1',
              emittedAtEpochMs: 1300,
              toolName: 'Read',
              contentPreview: 'stale delta missed by resync.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsAfterResync =
          bridge.loadChatRuntimeSnapshotCallCount;

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          sequence: 4,
          totalLength: 4,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-delta-resync-1',
              taskId: 'task-delta-resync-1',
              emittedAtEpochMs: 1400,
              toolName: 'Read',
              contentPreview: 'README body loaded after resync.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsAfterResync,
      );
      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-delta-resync-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await _openRunTraceFullscreen(tester, runTraceFinder);
      expect(
        find.descendant(
          of: find.byKey(
            const ValueKey<String>(
              'chat-run-trace-fullscreen-run-delta-resync-1',
            ),
          ),
          matching: find.textContaining(
            'README body loaded after resync.',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'runtime event deltas create run traces without a runtime snapshot refresh',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-create-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsBeforeDelta =
          bridge.loadChatRuntimeSnapshotCallCount;

      const runTraceFinder = ValueKey<String>(
        'chat-run-trace-run-delta-create-1',
      );
      expect(find.byKey(runTraceFinder), findsNothing);

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          totalLength: 1,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-create-1',
              taskId: 'task-delta-create-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-delta-create-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-delta-create-1',
              taskId: 'task-delta-create-1',
              emittedAtEpochMs: 1200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsBeforeDelta,
      );
      final runTrace = find.byKey(runTraceFinder);
      expect(runTrace, findsOneWidget);
      expect(find.text('Thinking'), findsNothing);
      await _openRunTraceFullscreen(tester, runTrace);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-delta-create-1'),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('README.md', findRichText: true),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'runtime event deltas update grouped inspector entries without a runtime snapshot refresh',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeEventDeltas =
          StreamController<OpenCrayChatRuntimeEventDelta>.broadcast();
      addTearDown(runtimeEventDeltas.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Read the README.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-delta-grouped-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-delta-grouped-1',
              taskId: 'task-delta-grouped-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-delta-grouped-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-delta-grouped-1',
              taskId: 'task-delta-grouped-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-delta-grouped-1',
              taskId: 'task-delta-grouped-1',
              emittedAtEpochMs: 1200,
              toolName: 'Read',
              argumentsJson: '{"file_path":"README.md"}',
            ),
          ],
        ),
        runtimeEventDeltaStream: runtimeEventDeltas.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final int runtimeSnapshotLoadsBeforeDelta =
          bridge.loadChatRuntimeSnapshotCallCount;

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-delta-grouped-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await _openRunTraceFullscreen(tester, runTraceFinder);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-delta-grouped-1'),
      );

      runtimeEventDeltas.add(
        const OpenCrayChatRuntimeEventDelta(
          sessionId: 'session-1',
          totalLength: 3,
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-delta-grouped-1',
              taskId: 'task-delta-grouped-1',
              emittedAtEpochMs: 1300,
              toolName: 'Read',
              contentPreview: 'README body loaded from disk.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        bridge.loadChatRuntimeSnapshotCallCount,
        runtimeSnapshotLoadsBeforeDelta,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'README body loaded from disk.',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'live draft events keep projected process bubbles and terminal process status',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      final chatSnapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      final draftEvents =
          StreamController<OpenCrayChatLiveAssistantDraftEvent>.broadcast();
      addTearDown(runtimeSnapshots.close);
      addTearDown(chatSnapshots.close);
      addTearDown(draftEvents.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-live-process-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-live-process-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        chatSnapshotStream: chatSnapshots.stream,
        runtimeSnapshotStream: runtimeSnapshots.stream,
        liveAssistantDraftEventStream: draftEvents.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-live-process-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await _openRunTraceFullscreen(tester, runTraceFinder);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-live-process-1'),
      );

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2600,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2600,
              attempt: 1,
              pendingMessageId: 'pending-live-process-1',
              managedProcessIds: <String>['proc-live-process'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-live-process',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 2600,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              emittedAtEpochMs: 1700,
              toolName: 'Bash',
              argumentsJson: '{"cmd":"npm run dev"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-live-process'), findsOneWidget);
      expect(
        find.textContaining('ready on http://localhost:3000'),
        findsWidgets,
      );
      expect(fullscreenFinder, findsOneWidget);
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Bash', findRichText: true),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.text('Process proc-live-process', findRichText: true),
        ),
        findsOneWidget,
      );
      final liveProcessBubbleFinder = find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-process-run-live-process-1-proc-live-process',
        ),
      );
      final liveDraftBubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-pending-live-process-1'),
      );
      expect(liveProcessBubbleFinder, findsOneWidget);
      expect(liveDraftBubbleFinder, findsNothing);

      draftEvents.add(
        const OpenCrayChatLiveAssistantDraftEvent(
          sessionId: 'session-1',
          runId: 'run-live-process-1',
          taskId: 'task-live-process-1',
          pendingMessageId: 'pending-live-process-1',
          text: 'The dev server is ready; I am checking the result.',
          updatedAtEpochMs: 3200,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-live-process'), findsOneWidget);
      expect(
        find.text('The dev server is ready; I am checking the result.'),
        findsOneWidget,
      );
      expect(liveProcessBubbleFinder, findsOneWidget);
      expect(liveDraftBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(liveProcessBubbleFinder).dy,
        lessThan(tester.getTopLeft(liveDraftBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.text('Process proc-live-process', findRichText: true),
        ),
        findsOneWidget,
      );

      chatSnapshots.add(
        _hostChatSnapshot(
          updatedAtEpochMs: 3600,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-live-process-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-live-process'), findsOneWidget);
      expect(
        find.text('The dev server is ready; I am checking the result.'),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Bash', findRichText: true),
        ),
        findsWidgets,
      );

      const terminalToolResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-live-process-1',
        taskId: 'task-live-process-1',
        emittedAtEpochMs: 4100,
        toolName: 'Bash',
        contentPreview: 'server finished successfully',
      );
      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 4200,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          retainedRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 4200,
              attempt: 1,
              pendingMessageId: 'pending-live-process-1',
              managedProcessIds: <String>['proc-live-process'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-live-process',
                  status: 'success',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 4200,
                  finishedAtEpochMs: 4200,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 0,
              hasLiveManagedProcesses: false,
              isTerminal: true,
              lastEvent: terminalToolResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-live-process-1',
              taskId: 'task-live-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            terminalToolResult,
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.descendant(of: runTraceFinder, matching: find.text('RUNNING')),
        findsNothing,
      );
      expect(
        find.descendant(of: runTraceFinder, matching: find.text('FINISHED')),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('status: finished', findRichText: true),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'runtime patches existing projected process bubbles and anchors the status line above the agent group',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'user-runtime-anchor-1',
              kind: 'outbound',
              text: 'Start the preview server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'runtime-process-run-runtime-anchor-1-proc-1',
              kind: 'inbound',
              text: 'Process proc-1\n\nrunning: npm run dev\n\nstale output',
              createdAtEpochMs: 1200,
              isEphemeral: true,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-runtime-anchor-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1300,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1700,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-runtime-anchor-1',
              taskId: 'task-runtime-anchor-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1700,
              attempt: 1,
              pendingMessageId: 'pending-runtime-anchor-1',
              managedProcessIds: <String>['proc-1'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1200,
                  updatedAtEpochMs: 1700,
                  stdoutPreview: 'fresh output from runtime',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-runtime-anchor-1',
              taskId: 'task-runtime-anchor-1',
              emittedAtEpochMs: 1400,
              toolName: 'WebFetch',
              contentPreview: 'Fetched page content.',
            ),
          ],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-runtime-anchor-1',
              taskId: 'task-runtime-anchor-1',
              pendingMessageId: 'pending-runtime-anchor-1',
              text: 'The preview server is running.',
              updatedAtEpochMs: 1700,
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-runtime-anchor-1'),
      );
      final processBubbleFinder = find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-process-run-runtime-anchor-1-proc-1',
        ),
      );
      final draftBubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-pending-runtime-anchor-1'),
      );

      expect(runTraceFinder, findsOneWidget);
      expect(processBubbleFinder, findsOneWidget);
      expect(draftBubbleFinder, findsOneWidget);
      expect(find.text('WRITING REPLY'), findsOneWidget);
      expect(find.textContaining('fresh output from runtime'), findsWidgets);
      expect(find.textContaining('stale output'), findsNothing);

      expect(
        tester.getTopLeft(runTraceFinder).dy,
        lessThan(tester.getTopLeft(processBubbleFinder).dy),
      );
      expect(
        tester.getTopLeft(processBubbleFinder).dy,
        lessThan(tester.getTopLeft(draftBubbleFinder).dy),
      );
    },
  );

  testWidgets(
    'streamed assistant snapshots keep process bubbles and update the open inspector',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      final chatSnapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      addTearDown(chatSnapshots.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          updatedAtEpochMs: 1000,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-stream-process-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 1100,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-stream-process-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        chatSnapshotStream: chatSnapshots.stream,
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-stream-process-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await _openRunTraceFullscreen(tester, runTraceFinder);
      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-stream-process-1',
        ),
      );

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              pendingMessageId: 'pending-stream-process-1',
              managedProcessIds: <String>['proc-stream-process'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-stream-process',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 2200,
                  stdoutPreview: 'alpha output',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1700,
              toolName: 'Bash',
              argumentsJson: '{"cmd":"npm run dev"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream-process'), findsOneWidget);
      expect(find.textContaining('alpha output'), findsWidgets);
      final streamProcessBubbleFinder = find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-process-run-stream-process-1-proc-stream-process',
        ),
      );
      final streamDraftBubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-pending-stream-process-1'),
      );
      expect(streamProcessBubbleFinder, findsOneWidget);
      expect(streamDraftBubbleFinder, findsNothing);
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Bash', findRichText: true),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('alpha output', findRichText: true),
        ),
        findsOneWidget,
      );

      chatSnapshots.add(
        _hostChatSnapshot(
          updatedAtEpochMs: 2600,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-stream-process-1',
              kind: 'inbound',
              text: 'The server process is up; I am checking the result.',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream-process'), findsOneWidget);
      expect(
        find.text('The server process is up; I am checking the result.'),
        findsOneWidget,
      );
      expect(find.textContaining('alpha output'), findsWidgets);
      expect(streamProcessBubbleFinder, findsOneWidget);
      expect(streamDraftBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(streamProcessBubbleFinder).dy,
        lessThan(tester.getTopLeft(streamDraftBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('alpha output', findRichText: true),
        ),
        findsOneWidget,
      );

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              pendingMessageId: 'pending-stream-process-1',
              managedProcessIds: <String>['proc-stream-process'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-stream-process',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 2200,
                  stdoutPreview: 'bravo output',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1700,
              toolName: 'Bash',
              argumentsJson: '{"cmd":"npm run dev"}',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream-process'), findsOneWidget);
      expect(find.textContaining('alpha output'), findsNothing);
      expect(find.textContaining('bravo output'), findsWidgets);
      expect(streamProcessBubbleFinder, findsOneWidget);
      expect(streamDraftBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(streamProcessBubbleFinder).dy,
        lessThan(tester.getTopLeft(streamDraftBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('bravo output', findRichText: true),
        ),
        findsOneWidget,
      );

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 3200,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3200,
              attempt: 1,
              pendingMessageId: 'pending-stream-process-1',
              managedProcessIds: <String>['proc-stream-process'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-stream-process',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 3200,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_call',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 1700,
              toolName: 'Bash',
              argumentsJson: '{"cmd":"npm run dev"}',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-stream-process-1',
              taskId: 'task-stream-process-1',
              emittedAtEpochMs: 3100,
              toolName: 'Bash',
              contentPreview: 'background process is still running',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream-process'), findsOneWidget);
      expect(find.textContaining('bravo output'), findsNothing);
      expect(
        find.textContaining('ready on http://localhost:3000'),
        findsWidgets,
      );
      expect(streamProcessBubbleFinder, findsOneWidget);
      expect(streamDraftBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(streamProcessBubbleFinder).dy,
        lessThan(tester.getTopLeft(streamDraftBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'ready on http://localhost:3000',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'background process is still running',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );

      chatSnapshots.add(
        _hostChatSnapshot(
          updatedAtEpochMs: 3600,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the development server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-stream-process-1',
              kind: 'inbound',
              text: 'The server process is up and the preview is reachable.',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Process proc-stream-process'), findsOneWidget);
      expect(
        find.text('The server process is up and the preview is reachable.'),
        findsOneWidget,
      );
      expect(
        find.textContaining('ready on http://localhost:3000'),
        findsWidgets,
      );
      expect(streamProcessBubbleFinder, findsOneWidget);
      expect(streamDraftBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(streamProcessBubbleFinder).dy,
        lessThan(tester.getTopLeft(streamDraftBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'ready on http://localhost:3000',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'live assistant drafts do not overwrite a settled assistant reply',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a long summary.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'The final answer is ready.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-1',
              taskId: 'task-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-1',
              taskId: 'task-1',
              pendingMessageId: 'pending-1',
              text: 'Streaming answer in progress',
              updatedAtEpochMs: 1300,
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final settledBubble = find.byKey(
        const ValueKey<String>('chat-bubble-pending-1'),
      );
      expect(settledBubble, findsOneWidget);
      expect(
        find.descendant(
          of: settledBubble,
          matching: find.text('The final answer is ready.'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: settledBubble,
          matching: find.text('Streaming answer in progress'),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'live assistant drafts do not surface tool call payloads as chat bubbles',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-1',
              taskId: 'task-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1200,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-1',
              taskId: 'task-1',
              pendingMessageId: 'pending-1',
              text:
                  '{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}',
              updatedAtEpochMs: 1300,
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('"tool_name"'), findsNothing);
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-pending-1')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'projected assistant phases suppress competing live drafts while runtime is ahead',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              isTerminal: false,
              pendingMessageId: 'pending-1',
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'assistant_phase',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 2200,
              phase: 'commentary',
              isFinal: false,
              turn: 0,
              stage: 'Planning',
              text:
                  'Scanning README and Gradle files before choosing the next tool.',
            ),
          ],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              pendingMessageId: 'pending-1',
              text: 'Streaming answer in progress',
              updatedAtEpochMs: 2300,
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final phaseFinder = find.descendant(
        of: find.byWidgetPredicate((widget) {
          final Key? key = widget.key;
          return key is ValueKey<String> &&
              key.value.startsWith('chat-bubble-') &&
              key.value != 'chat-bubble-pending-1';
        }),
        matching: find.textContaining(
          'Scanning README and Gradle files before choosing the next tool.',
        ),
      );
      final pendingFinder = find.descendant(
        of: find.byKey(const ValueKey<String>('chat-bubble-pending-1')),
        matching: find.text('Streaming answer in progress'),
      );

      expect(phaseFinder, findsOneWidget);
      expect(pendingFinder, findsNothing);
    },
  );

  testWidgets(
    'projected assistant phases stay visible when the run becomes retained before chat catches up',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              isTerminal: false,
              pendingMessageId: 'pending-1',
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'assistant_phase',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 2200,
              phase: 'commentary',
              isFinal: false,
              turn: 0,
              stage: 'Planning',
              text:
                  'Scanning README and Gradle files before choosing the next tool.',
            ),
          ],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.descendant(
          of: find.byWidgetPredicate((widget) {
            final Key? key = widget.key;
            return key is ValueKey<String> &&
                key.value.startsWith('chat-bubble-');
          }),
          matching: find.textContaining(
            'Scanning README and Gradle files before choosing the next tool.',
          ),
        ),
        findsOneWidget,
      );

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          retainedRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2400,
              attempt: 1,
              isTerminal: false,
              pendingMessageId: 'pending-1',
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'assistant_phase',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 2200,
              phase: 'commentary',
              isFinal: false,
              turn: 0,
              stage: 'Planning',
              text:
                  'Scanning README and Gradle files before choosing the next tool.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.descendant(
          of: find.byWidgetPredicate((widget) {
            final Key? key = widget.key;
            return key is ValueKey<String> &&
                key.value.startsWith('chat-bubble-');
          }),
          matching: find.textContaining(
            'Scanning README and Gradle files before choosing the next tool.',
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'persisted assistant phases keep their own bubble when a later phase arrives',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const firstEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-progress-2',
        taskId: 'task-progress-2',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        isFinal: false,
        turn: 0,
        stage: 'Planning',
        text: 'Scanning README and Gradle files before choosing the next tool.',
      );
      const secondEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-progress-2',
        taskId: 'task-progress-2',
        emittedAtEpochMs: 2300,
        phase: 'commentary',
        isFinal: false,
        turn: 1,
        stage: 'Planning',
        text: 'Checking the tests after the first pass.',
      );
      final String firstMessageId =
          'runtime-assistant-commentary-run-progress-2-0-Planning-2200-${javaStringHashCode(firstEvent.text!)}';
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: <OpenCrayChatMessageSnapshot>[
            const OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: firstMessageId,
              kind: 'inbound',
              text:
                  'Planning\n\nScanning README and Gradle files before choosing the next tool.',
              isEphemeral: true,
            ),
            const OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-2',
              taskId: 'task-progress-2',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2300,
              attempt: 1,
              isTerminal: false,
              pendingMessageId: 'pending-1',
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[firstEvent, secondEvent],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleScope = find.byWidgetPredicate((widget) {
        final Key? key = widget.key;
        return key is ValueKey<String> &&
            key.value.startsWith('chat-bubble-') &&
            key.value != 'chat-bubble-pending-1';
      });
      final firstBubbleText = find.descendant(
        of: bubbleScope,
        matching: find.textContaining(
          'Scanning README and Gradle files before choosing the next tool.',
        ),
      );
      final secondBubbleText = find.descendant(
        of: bubbleScope,
        matching: find.textContaining(
          'Checking the tests after the first pass.',
        ),
      );

      expect(firstBubbleText, findsOneWidget);
      expect(secondBubbleText, findsOneWidget);
      expect(
        tester.getTopLeft(firstBubbleText).dy,
        lessThan(tester.getTopLeft(secondBubbleText).dy),
      );
    },
  );

  testWidgets('host rebuild stays silent in chat ui', (tester) async {
    final runtimeSnapshots =
        StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
    addTearDown(runtimeSnapshots.close);
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        hostLifecycle: OpenCrayHostLifecycleSnapshot(
          hostInstanceId: 'host-1',
          hostCreatedAtEpochMs: 1000,
        ),
      ),
      chatSnapshotStream: Stream<OpenCrayChatSnapshot>.empty(),
      runtimeSnapshotStream: runtimeSnapshots.stream,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
            bridge: bridge,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    runtimeSnapshots.add(
      const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        hostLifecycle: OpenCrayHostLifecycleSnapshot(
          hostInstanceId: 'host-2',
          hostCreatedAtEpochMs: 1000,
        ),
      ),
    );

    await tester.pumpAndSettle();

    expect(
      find.text(
        'Runtime host rebuilt. Interrupted runs now require an explicit restart.',
      ),
      findsNothing,
    );
  });

  testWidgets('run status line opens a full-screen inspector on double tap', (
    tester,
  ) async {
    await tester.pumpWidget(_buildChatHarness());
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-1'),
    );
    final center = tester.getCenter(bubbleFinder);

    await tester.tapAt(center);
    await tester.pump(const Duration(milliseconds: 40));
    await tester.tapAt(center);
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-run-trace-fullscreen-run-1')),
      findsOneWidget,
    );
    expect(
      find.textContaining('Read README.md lines 5-6', findRichText: true),
      findsWidgets,
    );
    expect(find.textContaining('"file_path": "README.md"'), findsNothing);
    expect(
      find.descendant(
        of: find.byKey(
          const ValueKey<String>('chat-run-trace-fullscreen-run-1'),
        ),
        matching: find.textContaining(
          'Project uses the Gradle wrapper from the repo root.',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
  });

  testWidgets('anchored retry action renders inside the assistant bubble', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Continue this run.',
            createdAtEpochMs: 1000,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'pending-retry-inline',
            kind: 'inbound',
            text: 'The run paused before continuing.',
            createdAtEpochMs: 1100,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-retry-inline',
            taskId: 'task-retry-inline',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2200,
            lifecycleState: 'suspended',
            errorCode: 'LLM_RETRY_EXHAUSTED_AWAITING_RESUME',
            attempt: 1,
            pendingMessageId: 'pending-retry-inline',
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-retry-inline',
            taskId: 'task-retry-inline',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
        ],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final statusFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-retry-inline'),
    );
    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-pending-retry-inline'),
    );
    expect(statusFinder, findsOneWidget);
    expect(bubbleFinder, findsOneWidget);
    expect(
      tester.getTopLeft(statusFinder).dy,
      lessThan(tester.getTopLeft(bubbleFinder).dy),
    );
    expect(
      find.descendant(
        of: bubbleFinder,
        matching: find.text(copy.chatRunResumeAction),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: statusFinder,
        matching: find.text(copy.chatRunResumeAction),
      ),
      findsNothing,
    );
  });

  testWidgets(
    'anchored running status stays above process and final bubbles while composer owns interrupt',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the server.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-interrupt-inline',
              kind: 'inbound',
              text: 'The server is running; I am checking the preview.',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 2400,
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-interrupt-inline',
              taskId: 'task-interrupt-inline',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2400,
              attempt: 1,
              pendingMessageId: 'pending-interrupt-inline',
              managedProcessIds: <String>['proc-interrupt-inline'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-interrupt-inline',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 2400,
                  stdoutPreview: 'ready',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-interrupt-inline',
              taskId: 'task-interrupt-inline',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final statusFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-interrupt-inline'),
      );
      final processBubbleFinder = find.byKey(
        const ValueKey<String>(
          'chat-bubble-runtime-process-run-interrupt-inline-proc-interrupt-inline',
        ),
      );
      final finalBubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-pending-interrupt-inline'),
      );
      final interruptFinder = find.byKey(
        const ValueKey<String>('chat-composer-interrupt-button'),
      );
      expect(statusFinder, findsOneWidget);
      expect(processBubbleFinder, findsOneWidget);
      expect(finalBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(statusFinder).dy,
        lessThan(tester.getTopLeft(processBubbleFinder).dy),
      );
      expect(
        tester.getTopLeft(statusFinder).dy,
        lessThan(tester.getTopLeft(finalBubbleFinder).dy),
      );
      expect(
        tester.getTopLeft(processBubbleFinder).dy,
        lessThan(tester.getTopLeft(finalBubbleFinder).dy),
      );
      expect(
        find.descendant(
          of: statusFinder,
          matching: find.text(copy.chatRunInterruptAction),
        ),
        findsNothing,
      );
      expect(interruptFinder, findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-composer-send-button')),
        findsNothing,
      );
      expect(
        find.descendant(
          of: finalBubbleFinder,
          matching: find.text(copy.chatRunInterruptAction),
        ),
        findsNothing,
      );

      await tester.tap(interruptFinder);
      await tester.pumpAndSettle();
      final sliderFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-interrupt-slider-run-interrupt-inline',
        ),
      );
      expect(sliderFinder, findsOneWidget);

      await tester.tapAt(const Offset(8, 8));
      await tester.pumpAndSettle();
      expect(sliderFinder, findsNothing);
      expect(bridge.cancelledRunIds, isEmpty);

      await tester.enterText(find.byType(TextField), 'continue after this');
      await tester.pumpAndSettle();
      expect(interruptFinder, findsNothing);
      expect(
        find.byKey(const ValueKey<String>('chat-composer-send-button')),
        findsOneWidget,
      );
      await tester.enterText(find.byType(TextField), '');
      await tester.pumpAndSettle();
      expect(interruptFinder, findsOneWidget);

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          updatedAtEpochMs: 3600,
          activeRuns: <OpenCrayChatRunSnapshot>[],
          retainedRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-interrupt-inline',
              taskId: 'task-interrupt-inline',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3600,
              attempt: 1,
              pendingMessageId: 'pending-interrupt-inline',
              managedProcessIds: <String>['proc-interrupt-inline'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-interrupt-inline',
                  status: 'success',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  processStarted: true,
                  startedAtEpochMs: 1800,
                  updatedAtEpochMs: 3600,
                  finishedAtEpochMs: 3600,
                  stdoutPreview: 'ready',
                ),
              ],
              runningManagedProcessCount: 0,
              hasLiveManagedProcesses: false,
              isTerminal: true,
              lastEvent: OpenCrayChatRuntimeEventSnapshot(
                kind: 'tool_result',
                runId: 'run-interrupt-inline',
                taskId: 'task-interrupt-inline',
                emittedAtEpochMs: 3600,
                toolName: 'Bash',
                contentPreview: 'server finished',
              ),
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-interrupt-inline',
              taskId: 'task-interrupt-inline',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-interrupt-inline',
              taskId: 'task-interrupt-inline',
              emittedAtEpochMs: 3600,
              toolName: 'Bash',
              contentPreview: 'server finished',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(interruptFinder, findsNothing);
      expect(
        find.byKey(const ValueKey<String>('chat-composer-send-button')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: statusFinder, matching: find.text('FINISHED')),
        findsOneWidget,
      );
      expect(processBubbleFinder, findsOneWidget);
      expect(finalBubbleFinder, findsOneWidget);
      expect(
        tester.getTopLeft(statusFinder).dy,
        lessThan(tester.getTopLeft(processBubbleFinder).dy),
      );
      expect(
        tester.getTopLeft(statusFinder).dy,
        lessThan(tester.getTopLeft(finalBubbleFinder).dy),
      );
      expect(
        tester.getTopLeft(processBubbleFinder).dy,
        lessThan(tester.getTopLeft(finalBubbleFinder).dy),
      );
    },
  );

  testWidgets('cloud mode shows sandbox preview card on the run trace', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final Map<String, Object?> clipboardState = <String, Object?>{};
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (
          MethodCall methodCall,
        ) async {
          switch (methodCall.method) {
            case 'Clipboard.setData':
              final Map<Object?, Object?> arguments =
                  methodCall.arguments as Map<Object?, Object?>;
              clipboardState['text'] = arguments['text'];
              return null;
            case 'Clipboard.getData':
              final Object? text = clipboardState['text'];
              return text == null ? null : <String, Object?>{'text': text};
          }
          return null;
        });
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null);
    });
    const previewUrl = 'https://3000-sb-preview.e2b.app/health';
    const previewEvent = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-preview-cloud',
      taskId: 'task-preview-cloud',
      emittedAtEpochMs: 2000,
      toolName: 'sandbox_preview_open',
      contentPreview: 'Sandbox preview is available.',
      resultMetadata: <String, String>{
        'previewUrl': previewUrl,
        'previewProbeStatus': 'ready',
        'previewPort': '3000',
        'previewPath': '/health',
        'sandboxProvider': 'e2b',
        'previewProbeHttpStatus': '200',
      },
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-preview-cloud',
            taskId: 'task-preview-cloud',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: true,
            lastEvent: previewEvent,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-preview-cloud',
            taskId: 'task-preview-cloud',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          previewEvent,
        ],
      ),
      sandboxSettings: const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'e2b',
        defaultBackend: 'sandbox',
        sessionMode: 'ephemeral',
        autoResume: false,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: '',
        apiKeyConfigured: false,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>('chat-run-trace-preview-card-run-preview-cloud'),
      ),
      findsOneWidget,
    );
    expect(bridge.resolveSandboxPreviewEmbedConfigCallCount, 1);
    expect(
      find.byKey(
        const ValueKey<String>(
          'chat-run-trace-preview-embedded-unavailable-run-preview-cloud',
        ),
      ),
      findsOneWidget,
    );
    expect(find.text('Sandbox Preview'), findsOneWidget);
    expect(find.text('Ready'), findsOneWidget);
    expect(find.textContaining(previewUrl), findsOneWidget);

    await tester.tap(
      find.byKey(
        const ValueKey<String>('chat-run-trace-preview-open-run-preview-cloud'),
      ),
    );
    await tester.pumpAndSettle();

    expect(bridge.openedExternalUris, <String>[previewUrl]);

    await tester.tap(
      find.byKey(
        const ValueKey<String>('chat-run-trace-preview-copy-run-preview-cloud'),
      ),
    );
    await tester.pumpAndSettle();

    final ClipboardData? clipboardData = await Clipboard.getData(
      Clipboard.kTextPlain,
    );
    expect(clipboardData?.text, previewUrl);
    expect(bridge.shownNativeToasts, contains(copy.chatRunPreviewCopied));
  });

  testWidgets('local mode hides sandbox preview card on the run trace', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    const previewEvent = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-preview-local',
      taskId: 'task-preview-local',
      emittedAtEpochMs: 2000,
      toolName: 'sandbox_preview_open',
      contentPreview: 'Sandbox preview is available.',
      resultMetadata: <String, String>{
        'previewUrl': 'https://3000-sb-preview.e2b.app/health',
        'previewProbeStatus': 'ready',
        'previewPort': '3000',
      },
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-preview-local',
            taskId: 'task-preview-local',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: true,
            lastEvent: previewEvent,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-preview-local',
            taskId: 'task-preview-local',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          previewEvent,
        ],
      ),
      sandboxSettings: const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'e2b',
        defaultBackend: 'local',
        sessionMode: 'ephemeral',
        autoResume: false,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: '',
        apiKeyConfigured: false,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>('chat-run-trace-preview-card-run-preview-local'),
      ),
      findsNothing,
    );
    expect(bridge.resolveSandboxPreviewEmbedConfigCallCount, 0);
    expect(find.text('Sandbox Preview'), findsNothing);
  });

  testWidgets('cloud mode shows sandbox preview inside the run inspector', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    const previewUrl = 'https://3000-sb-preview.e2b.app/health';
    const previewEvent = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-preview-cloud-fullscreen',
      taskId: 'task-preview-cloud-fullscreen',
      emittedAtEpochMs: 2000,
      toolName: 'sandbox_preview_open',
      contentPreview: 'Sandbox preview is available.',
      resultMetadata: <String, String>{
        'previewUrl': previewUrl,
        'previewProbeStatus': 'ready',
        'previewPort': '3000',
        'previewPath': '/health',
        'sandboxProvider': 'e2b',
        'previewProbeHttpStatus': '200',
      },
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-preview-cloud-fullscreen',
            taskId: 'task-preview-cloud-fullscreen',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: true,
            lastEvent: previewEvent,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-preview-cloud-fullscreen',
            taskId: 'task-preview-cloud-fullscreen',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          previewEvent,
        ],
      ),
      sandboxSettings: const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'e2b',
        defaultBackend: 'sandbox',
        sessionMode: 'ephemeral',
        autoResume: false,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: '',
        apiKeyConfigured: false,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-preview-cloud-fullscreen'),
    );
    await _openRunTraceFullscreen(tester, bubbleFinder);

    final fullscreenFinder = find.byKey(
      const ValueKey<String>(
        'chat-run-trace-fullscreen-run-preview-cloud-fullscreen',
      ),
    );
    expect(fullscreenFinder, findsOneWidget);
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.byKey(
          const ValueKey<String>(
            'chat-run-trace-fullscreen-preview-embedded-unavailable-run-preview-cloud-fullscreen',
          ),
        ),
      ),
      findsOneWidget,
    );
    expect(bridge.resolveSandboxPreviewEmbedConfigCallCount >= 2, isTrue);
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.byKey(
          const ValueKey<String>(
            'chat-run-trace-fullscreen-preview-card-run-preview-cloud-fullscreen',
          ),
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.byKey(
          const ValueKey<String>(
            'chat-run-trace-fullscreen-preview-url-run-preview-cloud-fullscreen',
          ),
        ),
      ),
      findsOneWidget,
    );

    final openButtonFinder = find.descendant(
      of: fullscreenFinder,
      matching: find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-preview-open-run-preview-cloud-fullscreen',
        ),
      ),
    );
    await tester.ensureVisible(openButtonFinder);
    await tester.pumpAndSettle();
    await tester.tap(openButtonFinder);
    await tester.pumpAndSettle();

    expect(bridge.openedExternalUris, <String>[previewUrl]);
  });

  testWidgets('local mode hides sandbox preview inside the run inspector', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    const previewEvent = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-preview-local-fullscreen',
      taskId: 'task-preview-local-fullscreen',
      emittedAtEpochMs: 2000,
      toolName: 'sandbox_preview_open',
      contentPreview: 'Sandbox preview is available.',
      resultMetadata: <String, String>{
        'previewUrl': 'https://3000-sb-preview.e2b.app/health',
        'previewProbeStatus': 'ready',
        'previewPort': '3000',
      },
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-preview-local-fullscreen',
            taskId: 'task-preview-local-fullscreen',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: true,
            lastEvent: previewEvent,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-preview-local-fullscreen',
            taskId: 'task-preview-local-fullscreen',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          previewEvent,
        ],
      ),
      sandboxSettings: const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'e2b',
        defaultBackend: 'local',
        sessionMode: 'ephemeral',
        autoResume: false,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: '',
        apiKeyConfigured: false,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-preview-local-fullscreen'),
    );
    await _openRunTraceFullscreen(tester, bubbleFinder);

    final fullscreenFinder = find.byKey(
      const ValueKey<String>(
        'chat-run-trace-fullscreen-run-preview-local-fullscreen',
      ),
    );
    expect(fullscreenFinder, findsOneWidget);
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.byKey(
          const ValueKey<String>(
            'chat-run-trace-fullscreen-preview-card-run-preview-local-fullscreen',
          ),
        ),
      ),
      findsNothing,
    );
    expect(bridge.resolveSandboxPreviewEmbedConfigCallCount, 0);
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.text('Sandbox Preview'),
      ),
      findsNothing,
    );
  });

  testWidgets('cloud mode shows sandbox session card on the run trace', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    const sessionEvent = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-session-cloud',
      taskId: 'task-session-cloud',
      emittedAtEpochMs: 2000,
      toolName: 'sandbox_session_info',
      contentPreview: 'Reusable cloud sandbox session is available.',
      resultMetadata: <String, String>{
        'sandboxProvider': 'e2b',
        'sandboxSessionPresent': 'true',
        'sandboxSessionSource': 'active_memory_and_persisted',
        'sandboxSessionLifecycleStatus': 'active',
        'sandboxId': 'sb-session',
        'sandboxDomain': 'e2b.app',
        'sandboxTemplateId': 'base',
        'sandboxSessionUpdatedAtEpochMs': '2000',
        'sandboxPreviewCandidatePorts': '3000,4173',
        'sandboxRunningRequestCount': '2',
        'sandboxRunningRequestIds': 'req-1,req-2',
      },
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-session-cloud',
            taskId: 'task-session-cloud',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: true,
            lastEvent: sessionEvent,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-session-cloud',
            taskId: 'task-session-cloud',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          sessionEvent,
        ],
      ),
      sandboxSettings: const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'e2b',
        defaultBackend: 'sandbox',
        sessionMode: 'sticky',
        autoResume: true,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: '',
        apiKeyConfigured: false,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>('chat-run-trace-session-card-run-session-cloud'),
      ),
      findsOneWidget,
    );
    expect(find.text('Cloud Session'), findsOneWidget);
    expect(find.text('Healthy'), findsOneWidget);
    expect(find.text('sb-session'), findsOneWidget);
    expect(find.textContaining('Active + Saved'), findsOneWidget);
    expect(find.textContaining('Ports 3000, 4173'), findsOneWidget);
    expect(find.textContaining('Running 2'), findsOneWidget);
  });

  testWidgets('local mode hides sandbox session card on the run trace', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    const sessionEvent = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-session-local',
      taskId: 'task-session-local',
      emittedAtEpochMs: 2000,
      toolName: 'sandbox_session_info',
      contentPreview: 'Reusable cloud sandbox session is available.',
      resultMetadata: <String, String>{
        'sandboxProvider': 'e2b',
        'sandboxSessionPresent': 'true',
        'sandboxSessionSource': 'active_memory',
        'sandboxId': 'sb-session-local',
      },
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-session-local',
            taskId: 'task-session-local',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: true,
            lastEvent: sessionEvent,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-session-local',
            taskId: 'task-session-local',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          sessionEvent,
        ],
      ),
      sandboxSettings: const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'e2b',
        defaultBackend: 'local',
        sessionMode: 'sticky',
        autoResume: true,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: '',
        apiKeyConfigured: false,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>('chat-run-trace-session-card-run-session-local'),
      ),
      findsNothing,
    );
    expect(find.text('Cloud Session'), findsNothing);
  });

  testWidgets('cloud mode shows sandbox session inside the run inspector', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    const sessionEvent = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-session-cloud-fullscreen',
      taskId: 'task-session-cloud-fullscreen',
      emittedAtEpochMs: 2000,
      toolName: 'sandbox_session_info',
      contentPreview: 'Reusable cloud sandbox session is available.',
      resultMetadata: <String, String>{
        'sandboxProvider': 'e2b',
        'sandboxSessionPresent': 'true',
        'sandboxSessionSource': 'active_memory_and_persisted',
        'sandboxSessionLifecycleStatus': 'stale',
        'sandboxId': 'sb-session-fullscreen',
        'sandboxDomain': 'e2b.app',
        'sandboxTemplateId': 'base',
        'sandboxSessionUpdatedAtEpochMs': '2000',
        'sandboxSessionLastActivityAtEpochMs': '1500',
        'sandboxSessionStaleAfterEpochMs': '3000',
        'sandboxPreviewCandidatePorts': '3000,4173',
        'sandboxRunningRequestCount': '2',
        'sandboxRunningRequestIds': 'req-1,req-2',
        'sandboxLastPreviewProbeStatus': 'ready',
        'sandboxLastPreviewProbeObservedAtEpochMs': '1800',
      },
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-session-cloud-fullscreen',
            taskId: 'task-session-cloud-fullscreen',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: true,
            lastEvent: sessionEvent,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-session-cloud-fullscreen',
            taskId: 'task-session-cloud-fullscreen',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          sessionEvent,
        ],
      ),
      sandboxSettings: const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'e2b',
        defaultBackend: 'sandbox',
        sessionMode: 'sticky',
        autoResume: true,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: '',
        apiKeyConfigured: false,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-session-cloud-fullscreen'),
    );
    final Offset openSpot = tester.getCenter(bubbleFinder);
    await tester.tapAt(openSpot);
    await tester.pump(const Duration(milliseconds: 40));
    await tester.tapAt(openSpot);
    await tester.pumpAndSettle();

    final fullscreenFinder = find.byKey(
      const ValueKey<String>(
        'chat-run-trace-fullscreen-run-session-cloud-fullscreen',
      ),
    );
    expect(fullscreenFinder, findsOneWidget);
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.byKey(
          const ValueKey<String>(
            'chat-run-trace-fullscreen-session-card-run-session-cloud-fullscreen',
          ),
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.text('Running requests'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(of: fullscreenFinder, matching: find.text('Stale')),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.textContaining('Last active'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.text('req-1, req-2'),
      ),
      findsOneWidget,
    );
  });

  testWidgets('local mode hides sandbox session inside the run inspector', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    const sessionEvent = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-session-local-fullscreen',
      taskId: 'task-session-local-fullscreen',
      emittedAtEpochMs: 2000,
      toolName: 'sandbox_session_info',
      contentPreview: 'Reusable cloud sandbox session is available.',
      resultMetadata: <String, String>{
        'sandboxProvider': 'e2b',
        'sandboxSessionPresent': 'true',
        'sandboxSessionSource': 'persisted',
        'sandboxId': 'sb-session-local-fullscreen',
      },
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-session-local-fullscreen',
            taskId: 'task-session-local-fullscreen',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: true,
            lastEvent: sessionEvent,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-session-local-fullscreen',
            taskId: 'task-session-local-fullscreen',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          sessionEvent,
        ],
      ),
      sandboxSettings: const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'e2b',
        defaultBackend: 'local',
        sessionMode: 'sticky',
        autoResume: true,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: '',
        apiKeyConfigured: false,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-session-local-fullscreen'),
    );
    final Offset openSpot = tester.getCenter(bubbleFinder);
    await tester.tapAt(openSpot);
    await tester.pump(const Duration(milliseconds: 40));
    await tester.tapAt(openSpot);
    await tester.pumpAndSettle();

    final fullscreenFinder = find.byKey(
      const ValueKey<String>(
        'chat-run-trace-fullscreen-run-session-local-fullscreen',
      ),
    );
    expect(fullscreenFinder, findsOneWidget);
    expect(
      find.descendant(
        of: fullscreenFinder,
        matching: find.byKey(
          const ValueKey<String>(
            'chat-run-trace-fullscreen-session-card-run-session-local-fullscreen',
          ),
        ),
      ),
      findsNothing,
    );
  });

  testWidgets('running card reveals and dismisses interrupt confirmation', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final lastEvent = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-interrupt-1',
      taskId: 'task-interrupt-1',
      emittedAtEpochMs: 3000,
      toolName: 'Read',
      contentPreview: 'README preview',
      resultMetadata: const <String, String>{'filePath': 'README.md'},
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-interrupt-1',
            taskId: 'task-interrupt-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            isTerminal: false,
            lastEvent: lastEvent,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          const OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-interrupt-1',
            taskId: 'task-interrupt-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          lastEvent,
        ],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-composer-interrupt-button')),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey<String>('chat-run-trace-interrupt-run-interrupt-1'),
      ),
      findsNothing,
    );

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-composer-interrupt-button')),
    );
    await tester.pumpAndSettle();

    expect(find.text('Slide left to interrupt'), findsOneWidget);

    await tester.tapAt(const Offset(8, 8));
    await tester.pumpAndSettle();

    expect(find.text('Slide left to interrupt'), findsNothing);
    expect(bridge.cancelledRunIds, isEmpty);
  });

  testWidgets('sliding the interrupt confirmation interrupts the run', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final lastEvent = OpenCrayChatRuntimeEventSnapshot(
      kind: 'assistant_phase',
      runId: 'run-interrupt-2',
      taskId: 'task-interrupt-2',
      emittedAtEpochMs: 3000,
      text: 'Reviewing the current workspace state.',
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-interrupt-2',
            taskId: 'task-interrupt-2',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            isTerminal: false,
            lastEvent: lastEvent,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          const OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-interrupt-2',
            taskId: 'task-interrupt-2',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          lastEvent,
        ],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-composer-interrupt-button')),
    );
    await tester.pumpAndSettle();

    final sliderFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-interrupt-slider-run-interrupt-2'),
    );

    await tester.drag(sliderFinder, const Offset(-700, 0));
    await tester.pumpAndSettle();

    expect(bridge.cancelledRunIds, <String>['run-interrupt-2']);
  });

  testWidgets(
    'interrupting while a live draft is visible stays stable after the runtime update',
    (tester) async {
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const streamingEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-stream-interrupt-1',
        taskId: 'task-stream-interrupt-1',
        emittedAtEpochMs: 3000,
        text: 'Streaming answer in progress',
      );
      const cancellationEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'interrupted',
        runId: 'run-stream-interrupt-1',
        taskId: 'task-stream-interrupt-1',
        emittedAtEpochMs: 3600,
        text:
            'Run interrupted. The agent is waiting for your next instruction.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Write a long summary.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stream-interrupt-1',
              taskId: 'task-stream-interrupt-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
              lastEvent: streamingEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-stream-interrupt-1',
              taskId: 'task-stream-interrupt-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            streamingEvent,
          ],
          liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[
            OpenCrayChatLiveAssistantDraftSnapshot(
              runId: 'run-stream-interrupt-1',
              taskId: 'task-stream-interrupt-1',
              pendingMessageId: 'pending-1',
              text: 'Streaming answer in progress',
              updatedAtEpochMs: 3100,
            ),
          ],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Streaming answer in progress'), findsWidgets);

      await tester.tap(
        find.byKey(const ValueKey<String>('chat-composer-interrupt-button')),
      );
      await tester.pumpAndSettle();

      await tester.drag(
        find.byKey(
          const ValueKey<String>(
            'chat-run-trace-interrupt-slider-run-stream-interrupt-1',
          ),
        ),
        const Offset(-700, 0),
      );
      await tester.pumpAndSettle();

      expect(bridge.cancelledRunIds, <String>['run-stream-interrupt-1']);

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-stream-interrupt-1',
              taskId: 'task-stream-interrupt-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3600,
              attempt: 1,
              pendingMessageId: 'pending-1',
              isTerminal: false,
              lastEvent: cancellationEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-stream-interrupt-1',
              taskId: 'task-stream-interrupt-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            cancellationEvent,
          ],
        ),
      );
      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.textContaining('Run interrupted.'), findsWidgets);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'host-mapped run trace shows tool parameters in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final toolCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-host-1',
        taskId: 'task-host-1',
        emittedAtEpochMs: 2000,
        toolName: 'Read',
        argumentsJson: '{"file_path":"README.md","offset":5,"limit":2}',
      );
      final toolResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-host-1',
        taskId: 'task-host-1',
        emittedAtEpochMs: 3000,
        toolName: 'Read',
        contentPreview:
            'Project uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
        resultMetadata: const <String, String>{
          'filePath': 'README.md',
          'offset': '5',
          'limit': '2',
          'returnedLineCount': '2',
          'totalLineCount': '12',
        },
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-host-1',
              taskId: 'task-host-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: false,
              lastEvent: toolResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            const OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-host-1',
              taskId: 'task-host-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            toolCall,
            toolResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('Read README.md lines 5-6'), findsOneWidget);
      expect(
        find.textContaining('Returned 2 lines from README.md'),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'Project uses the Gradle wrapper from the repo root.',
        ),
        findsOneWidget,
      );

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-host-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-host-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Read README.md lines 5-6',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Returned 2 lines from README.md',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('"file_path":"README.md"'),
        ),
        findsNothing,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Project uses the Gradle wrapper from the repo root.',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'full-screen inspector colors tool call semantics without changing outer trace text',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final toolCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-host-color-1',
        taskId: 'task-host-color-1',
        emittedAtEpochMs: 2000,
        toolName: 'Read',
        argumentsJson: '{"file_path":"README.md","offset":5,"limit":2}',
      );
      final toolResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-host-color-1',
        taskId: 'task-host-color-1',
        emittedAtEpochMs: 3000,
        toolName: 'Read',
        contentPreview:
            'Project uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
        resultMetadata: const <String, String>{
          'filePath': 'README.md',
          'offset': '5',
          'limit': '2',
          'returnedLineCount': '2',
          'totalLineCount': '12',
        },
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-host-color-1',
              taskId: 'task-host-color-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: false,
              lastEvent: toolResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            const OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-host-color-1',
              taskId: 'task-host-color-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            toolCall,
            toolResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('Read README.md lines 5-6'), findsOneWidget);

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-host-color-1'),
      );
      final Offset center = tester.getCenter(bubbleFinder);
      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-host-color-1'),
      );
      final Finder semanticRichTextFinder = find.descendant(
        of: fullscreenFinder,
        matching: find.byWidgetPredicate((widget) {
          if (widget is! RichText) {
            return false;
          }
          return widget.text.toPlainText() == 'Read README.md lines 5-6';
        }),
      );

      expect(semanticRichTextFinder, findsOneWidget);
      final RichText richText = tester.widget<RichText>(semanticRichTextFinder);
      final TextSpan rootSpan = richText.text as TextSpan;
      List<TextSpan> collectLeafSpans(InlineSpan span) {
        if (span is! TextSpan) {
          return const <TextSpan>[];
        }
        final List<InlineSpan>? children = span.children;
        if (children == null || children.isEmpty) {
          return <TextSpan>[span];
        }
        return children
            .expand<TextSpan>(collectLeafSpans)
            .toList(growable: false);
      }

      final List<TextSpan> spans = collectLeafSpans(rootSpan);

      expect(spans.map((span) => span.text).toList(), <String>[
        'Read',
        ' ',
        'README.md',
        ' ',
        'lines 5-6',
      ]);
      expect(spans.map((span) => span.style?.color).toList(), <Color?>[
        const Color(0xFF007AFF),
        const Color(0xFF111111),
        const Color(0xFF7C3AED),
        const Color(0xFF111111),
        const Color(0xFF16A34A),
      ]);
    },
  );

  testWidgets(
    'chat ui renders detached projected subagent traces without a visible parent run',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          retainedRuns: <OpenCrayChatRunSnapshot>[],
          subAgents: <OpenCrayChatSubAgentSnapshot>[
            OpenCrayChatSubAgentSnapshot(
              parentRunId: 'run-parent-detached-1',
              parentTaskId: 'task-parent-detached-1',
              childRunId: 'child-run-detached-1',
              childTaskId: 'child-task-detached-1',
              label: 'Inspect README',
              subagentType: 'researcher',
              contextMode: 'minimal',
              depth: 1,
              phase: 'resumed',
              status: 'background_running',
              executionState: 'background_running',
              continuationKind: 'background_resume',
              resumable: true,
              summary:
                  'Delegated child runtime is still running in the background.',
              startedAtEpochMs: 1800,
              updatedAtEpochMs: 2600,
              eventCount: 0,
              mailboxMessageCount: 2,
              mailboxPendingMessageCount: 1,
              mailboxLastDeliveredMessageId: 'mailbox-detached-1',
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-child-run-detached-1'),
      );

      expect(bubbleFinder, findsOneWidget);
      await _openRunTraceFullscreen(tester, bubbleFinder);

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-child-run-detached-1',
        ),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Researcher running in background: Inspect README',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Delegated child runtime is still running in the background.',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Mailbox: 1 pending / 2 total'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Last delivered: mailbox-detached-1'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'chat ui prefers projected subagent state when the event stream is stale',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const taskCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-subagent-durable-1',
        taskId: 'task-subagent-durable-1',
        emittedAtEpochMs: 2000,
        toolName: 'Task',
        argumentsJson:
            '{"description":"Inspect README","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-subagent-durable-1',
              taskId: 'task-subagent-durable-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2000,
              attempt: 1,
              isTerminal: false,
              lastEvent: taskCall,
            ),
          ],
          subAgents: <OpenCrayChatSubAgentSnapshot>[
            OpenCrayChatSubAgentSnapshot(
              parentRunId: 'run-subagent-durable-1',
              parentTaskId: 'task-subagent-durable-1',
              childRunId: 'child-run-durable-1',
              childTaskId: 'child-task-durable-1',
              label: 'Inspect README',
              subagentType: 'researcher',
              contextMode: 'minimal',
              depth: 1,
              phase: 'resumed',
              status: 'background_running',
              executionState: 'background_running',
              continuationKind: 'background_resume',
              resumable: true,
              summary:
                  'Delegated child runtime is still running in the background.',
              startedAtEpochMs: 1800,
              updatedAtEpochMs: 2600,
              eventCount: 0,
              mailboxMessageCount: 3,
              mailboxPendingMessageCount: 2,
              mailboxLastDeliveredMessageId: 'mailbox-durable-1',
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-subagent-durable-1',
              taskId: 'task-subagent-durable-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            taskCall,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-subagent-durable-1'),
      );

      expect(bubbleFinder, findsOneWidget);
      await _openRunTraceFullscreen(tester, bubbleFinder);

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-subagent-durable-1',
        ),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Researcher running in background: Inspect README',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Delegated child runtime is still running in the background.',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Mailbox: 2 pending / 3 total'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Last delivered: mailbox-durable-1'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows delegated Task and subagent lifecycle details',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const taskCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-subagent-1',
        taskId: 'task-subagent-1',
        emittedAtEpochMs: 2000,
        toolName: 'Task',
        argumentsJson:
            '{"description":"Inspect README","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}',
      );
      const subagentStarted = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-1',
        taskId: 'task-subagent-1',
        emittedAtEpochMs: 2500,
        phase: 'started',
        label: 'Inspect README',
        childRunId: 'subagent-run-1',
        childTaskId: 'subagent-task-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
      );
      const subagentResumed = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-1',
        taskId: 'task-subagent-1',
        emittedAtEpochMs: 2650,
        phase: 'resumed',
        label: 'Inspect README',
        childRunId: 'subagent-run-1',
        childTaskId: 'subagent-task-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
        text: 'Delegated child approval granted. The child will continue.',
      );
      const subagentCompleted = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-1',
        taskId: 'task-subagent-1',
        emittedAtEpochMs: 2800,
        phase: 'completed',
        label: 'Inspect README',
        childRunId: 'subagent-run-1',
        childTaskId: 'subagent-task-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
        text: 'README says hello.',
      );
      const taskResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-subagent-1',
        taskId: 'task-subagent-1',
        emittedAtEpochMs: 3200,
        toolName: 'Task',
        contentPreview: 'Child summary: README says hello.',
        resultMetadata: <String, String>{
          'delegationDescription': 'Inspect README',
          'delegationPromptPreview': 'Read README.md and summarize it.',
          'delegationSubagentType': 'researcher',
          'delegationContextMode': 'minimal',
          'delegationAllowedTools': 'Glob,Grep,LS,Read',
          'childExecutionStatus': 'success',
          'childTurnCount': '2',
          'childToolCallCount': '1',
          'childRunId': 'subagent-run-1',
          'childTaskId': 'subagent-task-1',
        },
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-subagent-1',
              taskId: 'task-subagent-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3200,
              attempt: 1,
              isTerminal: false,
              lastEvent: taskResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-subagent-1',
              taskId: 'task-subagent-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            taskCall,
            subagentStarted,
            subagentResumed,
            subagentCompleted,
            taskResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-subagent-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-subagent-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.text('Main agent'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.text('Researcher'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Delegate to Researcher: Inspect README',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Prompt: Read README.md and summarize it.',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Allowed tools: Glob, Grep, LS, Read'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Child summary: README says hello.'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Researcher started: Inspect README'),
        ),
        findsNothing,
      );
      expect(find.text('Researcher'), findsOneWidget);

      await tester.tap(find.text('Researcher'));
      await tester.pumpAndSettle();

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Researcher started: Inspect README'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Researcher resumed: Inspect README'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Researcher completed: Inspect README'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Summary: README says hello.'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Delegate to Researcher: Inspect README',
          ),
        ),
        findsNothing,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Prompt: Read README.md and summarize it.',
          ),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'compact run trace prefers resumed subagent preview over trailing approval result',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const taskCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-subagent-approval-1',
        taskId: 'task-subagent-approval-1',
        emittedAtEpochMs: 2000,
        toolName: 'Task',
        argumentsJson:
            '{"description":"Inspect README","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}',
      );
      const subagentStarted = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-approval-1',
        taskId: 'task-subagent-approval-1',
        emittedAtEpochMs: 2500,
        phase: 'started',
        label: 'Inspect README',
        childRunId: 'subagent-run-approval-1',
        childTaskId: 'subagent-task-approval-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
      );
      const subagentResumed = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-approval-1',
        taskId: 'task-subagent-approval-1',
        emittedAtEpochMs: 2650,
        phase: 'resumed',
        label: 'Inspect README',
        childRunId: 'subagent-run-approval-1',
        childTaskId: 'subagent-task-approval-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
        text: 'Delegated child approval granted. The child will continue.',
      );
      const approvalResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'approval_result',
        runId: 'run-subagent-approval-1',
        taskId: 'task-subagent-approval-1',
        emittedAtEpochMs: 2700,
        toolName: 'Read',
        status: 'approved',
        text: 'Approval granted. The agent is resuming.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-subagent-approval-1',
              taskId: 'task-subagent-approval-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2700,
              attempt: 1,
              isTerminal: false,
              lastEvent: subagentResumed,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-subagent-approval-1',
              taskId: 'task-subagent-approval-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            taskCall,
            subagentStarted,
            subagentResumed,
            approvalResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-subagent-approval-1'),
      );

      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.textContaining('Researcher resumed: Inspect README'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.textContaining(
            'Delegated child approval granted. The child will continue.',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.textContaining(
            'Approval granted. The agent is resuming.',
          ),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'subagent preview prefers execution state for background queued events',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const subagentQueued = OpenCrayChatRuntimeEventSnapshot(
        kind: 'subagent',
        runId: 'run-subagent-background-1',
        taskId: 'task-subagent-background-1',
        emittedAtEpochMs: 2500,
        phase: 'started',
        status: 'background_queued',
        label: 'Inspect README',
        childRunId: 'subagent-run-background-1',
        childTaskId: 'subagent-task-background-1',
        subagentType: 'researcher',
        contextMode: 'minimal',
        depth: 1,
        executionState: 'background_queued',
        continuationKind: 'background_resume',
        text: 'Waiting to continue in the background.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-subagent-background-1',
              taskId: 'task-subagent-background-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2500,
              attempt: 1,
              isTerminal: false,
              lastEvent: subagentQueued,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-subagent-background-1',
              taskId: 'task-subagent-background-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            subagentQueued,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-subagent-background-1'),
      );
      expect(bubbleFinder, findsOneWidget);

      final center = tester.getCenter(bubbleFinder);
      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-subagent-background-1',
        ),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Researcher queued in background: Inspect README',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Continuation: Resumes in background'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Summary: Waiting to continue in the background.',
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'Task result summary prefers child execution state over legacy status',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const taskCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-subagent-background-result-1',
        taskId: 'task-subagent-background-result-1',
        emittedAtEpochMs: 2000,
        toolName: 'Task',
        argumentsJson:
            '{"description":"Inspect README","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}',
      );
      const taskResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-subagent-background-result-1',
        taskId: 'task-subagent-background-result-1',
        emittedAtEpochMs: 3200,
        toolName: 'Task',
        contentPreview:
            'Child summary: README inspection continues in background.',
        resultMetadata: <String, String>{
          'delegationDescription': 'Inspect README',
          'delegationPromptPreview': 'Read README.md and summarize it.',
          'delegationSubagentType': 'researcher',
          'delegationContextMode': 'minimal',
          'delegationAllowedTools': 'Glob,Grep,LS,Read',
          'childExecutionState': 'background_running',
          'childExecutionStatus': 'FAILED',
          'childContinuationKind': 'background_resume',
          'childTurnCount': '2',
          'childToolCallCount': '1',
          'childRunId': 'subagent-run-background-result-1',
          'childTaskId': 'subagent-task-background-result-1',
        },
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-subagent-background-result-1',
              taskId: 'task-subagent-background-result-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3200,
              attempt: 1,
              isTerminal: false,
              lastEvent: taskResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-subagent-background-result-1',
              taskId: 'task-subagent-background-result-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            taskCall,
            taskResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining(
          'Researcher running in background. minimal context, 2 turns, 1 tool call.',
        ),
        findsOneWidget,
      );
      expect(find.textContaining('Researcher failed'), findsNothing);
    },
  );

  testWidgets('host-mapped read summary prefers stable result limit metadata', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final toolResult = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-host-read-limit-1',
      taskId: 'task-host-read-limit-1',
      emittedAtEpochMs: 3000,
      toolName: 'Read',
      contentPreview:
          'Project uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
      resultMetadata: const <String, String>{
        'filePath': 'README.md',
        'returnedLineCount': '2',
        'totalLineCount': '12',
        'resultLimitApplied': 'true',
        'resultTruncated': 'true',
        'resultLimitKind': 'read_byte_budget',
      },
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-host-read-limit-1',
            taskId: 'task-host-read-limit-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            isTerminal: false,
            lastEvent: toolResult,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          const OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-host-read-limit-1',
            taskId: 'task-host-read-limit-1',
            emittedAtEpochMs: 1000,
            phase: 'start',
          ),
          toolResult,
        ],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.textContaining('Returned 2 lines from README.md'),
      findsOneWidget,
    );
    expect(
      find.textContaining('Output truncated to the read budget.'),
      findsOneWidget,
    );
  });

  testWidgets(
    'host-mapped failed tool result fullscreen prefers full content over preview',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const toolResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-host-python-timeout-1',
        taskId: 'task-host-python-timeout-1',
        emittedAtEpochMs: 3000,
        toolName: 'python_exec',
        errorMessage:
            'Timed out waiting for the embedded Python runtime service to become ready.',
        content:
            'Embedded Python runtime timeout diagnostics:\n'
            'request: exists=true path=/data/user/0/org.opencray.app/files/python_runtime/requests/demo.json\n'
            'result: exists=false path=/data/user/0/org.opencray.app/files/python_runtime/results/demo.json\n'
            'service_ready: exists=true path=/data/user/0/org.opencray.app/files/python_runtime/service_state/service-ready.json\n'
            'service_state: exists=true path=/data/user/0/org.opencray.app/files/python_runtime/service_state/service-state.json',
        contentPreview:
            'Embedded Python runtime timeout diagnostics:\n'
            'request: exists=true path=/data/user/0/org.opencray.app/files/python_runtime/requests/demo.json\n'
            'result: exists=',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-host-python-timeout-1',
              taskId: 'task-host-python-timeout-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: false,
              lastEvent: toolResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-host-python-timeout-1',
              taskId: 'task-host-python-timeout-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            toolResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-host-python-timeout-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-host-python-timeout-1',
        ),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('service_ready: exists=true'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('service_state: exists=true'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped todo plan preview shows plan semantics instead of raw json',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const todoCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-host-todo-1',
        taskId: 'task-host-todo-1',
        emittedAtEpochMs: 2000,
        toolName: 'TodoWrite',
        argumentsJson:
            '{"todos":[{"content":"Inspect runtime continuation","status":"completed"},{"content":"Prepare final answer","status":"in_progress","activeForm":"Preparing final answer"},{"content":"Archive follow-up cleanup","status":"pending"}]}',
      );
      const todoResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-host-todo-1',
        taskId: 'task-host-todo-1',
        emittedAtEpochMs: 3000,
        toolName: 'TodoWrite',
        contentPreview:
            '[completed] Inspect runtime continuation\n[in_progress] Prepare final answer | active: Preparing final answer\n[pending] Archive follow-up cleanup',
        resultMetadata: <String, String>{
          'todoCount': '3',
          'mutated': 'true',
          'planChanged': 'true',
          'pendingTodoCount': '1',
          'inProgressTodoCount': '1',
          'completedTodoCount': '1',
          'addedTodoCount': '1',
          'removedTodoCount': '1',
          'statusChangedTodoCount': '1',
          'completedTodoDeltaCount': '1',
          'activeTodoChanged': 'true',
          'activeTodoContent': 'Prepare final answer',
        },
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-host-todo-1',
              taskId: 'task-host-todo-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: false,
              lastEvent: todoResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-host-todo-1',
              taskId: 'task-host-todo-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            todoCall,
            todoResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining(
          'Update 3 todo(s) (1 pending, 1 in progress, 1 completed), active: Prepare final answer',
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'Plan updated: completed 1, added 1, removed 1. Active now: Prepare final answer',
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          '[in_progress] Prepare final answer | active: Preparing final answer',
        ),
        findsWidgets,
      );
      expect(find.textContaining('"todos": ['), findsNothing);
    },
  );

  testWidgets(
    'compact running card keeps recent LS history details and results',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const lsCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-host-ls-1',
        taskId: 'task-host-ls-1',
        emittedAtEpochMs: 2000,
        toolName: 'LS',
        argumentsJson: '{"path":"src","max_entries":5}',
      );
      const lsResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-host-ls-1',
        taskId: 'task-host-ls-1',
        emittedAtEpochMs: 3000,
        toolName: 'LS',
        contentPreview: 'file\tsrc/main.dart\nfile\tsrc/app.dart',
        resultMetadata: <String, String>{'path': 'src', 'entryCount': '2'},
      );
      const grepCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-host-ls-1',
        taskId: 'task-host-ls-1',
        emittedAtEpochMs: 4000,
        toolName: 'Grep',
        argumentsJson: '{"pattern":"TODO","path":"src"}',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-host-ls-1',
              taskId: 'task-host-ls-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 4000,
              attempt: 1,
              isTerminal: false,
              lastEvent: grepCall,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-host-ls-1',
              taskId: 'task-host-ls-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            lsCall,
            lsResult,
            grepCall,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('List src'), findsWidgets);
      expect(find.textContaining('Listed 2 entries in src'), findsOneWidget);
      expect(find.textContaining('"path": "src"'), findsOneWidget);
      expect(find.textContaining('file\tsrc/main.dart'), findsOneWidget);
      expect(find.textContaining('Search "TODO" in src'), findsOneWidget);
    },
  );

  testWidgets(
    'compact running card shows approval request details from runtime history',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const approvalEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'approval_wait',
        runId: 'run-approval-1',
        taskId: 'task-approval-1',
        emittedAtEpochMs: 2200,
        status: 'required',
        toolName: 'Bash',
        isHighRisk: true,
        text:
            'High-risk approval required\n\nCommand: git status --short\nWorking directory: .\nAgent reason: Check repository state before editing.\n\nApproval is required before Bash can run.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          pendingApprovals: const <OpenCrayChatPendingApprovalSnapshot>[
            OpenCrayChatPendingApprovalSnapshot(
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              title: 'High-risk approval required',
              body:
                  'Command: git status --short\nWorking directory: .\nAgent reason: Check repository state before editing.\n\nApproval is required before Bash can run.',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: true,
              toolName: 'Bash',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              isTerminal: false,
              errorCode: 'HIGH_RISK_APPROVAL_REQUIRED',
              lastEvent: approvalEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            approvalEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final approvalBubble = find.byKey(
        const ValueKey<String>('chat-run-trace-run-approval-1'),
      );
      expect(
        find.descendant(
          of: approvalBubble,
          matching: find.textContaining('Command: git status --short'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: approvalBubble,
          matching: find.textContaining(
            'Approval is required before Bash can run.',
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'approved or cleared approvals stop overriding resumed tool previews',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const pythonToolCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-approval-python-1',
        taskId: 'task-approval-python-1',
        emittedAtEpochMs: 2000,
        toolName: 'python_exec',
        argumentsJson: '{"script_path":"scripts/analyze.py"}',
      );
      const pythonApprovalDeniedResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-approval-python-1',
        taskId: 'task-approval-python-1',
        emittedAtEpochMs: 2100,
        toolName: 'python_exec',
        errorCode: 'APPROVAL_REQUIRED',
        errorMessage: 'Approval is required before python_exec can run.',
        resultMetadata: <String, String>{'scriptPath': 'scripts/analyze.py'},
      );
      const pythonApprovalApproved = OpenCrayChatRuntimeEventSnapshot(
        kind: 'approval_result',
        runId: 'run-approval-python-1',
        taskId: 'task-approval-python-1',
        emittedAtEpochMs: 2200,
        toolName: 'python_exec',
        status: 'approved',
        text: 'Approval granted. The run is resuming.',
      );
      const readToolCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-approval-read-1',
        taskId: 'task-approval-read-1',
        emittedAtEpochMs: 3000,
        toolName: 'Read',
        argumentsJson: '{"file_path":"../private.txt","offset":10,"limit":3}',
      );
      const readApprovalDeniedResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-approval-read-1',
        taskId: 'task-approval-read-1',
        emittedAtEpochMs: 3100,
        toolName: 'Read',
        errorCode: 'APPROVAL_REQUIRED',
        errorMessage: 'Approval is required before Read can access this path.',
        resultMetadata: <String, String>{
          'filePath': '../private.txt',
          'offset': '10',
          'limit': '3',
        },
      );
      const editToolCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-approval-edit-1',
        taskId: 'task-approval-edit-1',
        emittedAtEpochMs: 4000,
        toolName: 'Edit',
        argumentsJson:
            '{"file_path":"README.md","old_string":"SAFE","new_string":"AUTO"}',
      );
      const editApprovalWait = OpenCrayChatRuntimeEventSnapshot(
        kind: 'approval_wait',
        runId: 'run-approval-edit-1',
        taskId: 'task-approval-edit-1',
        emittedAtEpochMs: 4100,
        toolName: 'Edit',
        status: 'required',
        text: 'Approval is required before Edit can modify README.md.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-approval-python-1',
              taskId: 'task-approval-python-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              isTerminal: false,
              errorCode: 'APPROVAL_REQUIRED',
              lastEvent: pythonApprovalApproved,
            ),
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-approval-read-1',
              taskId: 'task-approval-read-1',
              acceptedAtEpochMs: 1200,
              updatedAtEpochMs: 3100,
              attempt: 1,
              isTerminal: false,
              errorCode: 'APPROVAL_REQUIRED',
              lastEvent: readApprovalDeniedResult,
            ),
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-approval-edit-1',
              taskId: 'task-approval-edit-1',
              acceptedAtEpochMs: 1400,
              updatedAtEpochMs: 4100,
              attempt: 1,
              isTerminal: false,
              errorCode: 'APPROVAL_REQUIRED',
              lastEvent: editApprovalWait,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-approval-python-1',
              taskId: 'task-approval-python-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            pythonToolCall,
            pythonApprovalDeniedResult,
            pythonApprovalApproved,
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-approval-read-1',
              taskId: 'task-approval-read-1',
              emittedAtEpochMs: 1200,
              phase: 'start',
            ),
            readToolCall,
            readApprovalDeniedResult,
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-approval-edit-1',
              taskId: 'task-approval-edit-1',
              emittedAtEpochMs: 1400,
              phase: 'start',
            ),
            editToolCall,
            editApprovalWait,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final pythonBubble = find.byKey(
        const ValueKey<String>('chat-run-trace-run-approval-python-1'),
      );
      expect(
        find.descendant(
          of: pythonBubble,
          matching: find.textContaining('Run Python script scripts/analyze.py'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: pythonBubble,
          matching: find.textContaining(
            'Approval is required before python_exec can run.',
          ),
        ),
        findsNothing,
      );

      final readBubble = find.byKey(
        const ValueKey<String>('chat-run-trace-run-approval-read-1'),
      );
      expect(
        find.descendant(
          of: readBubble,
          matching: find.textContaining('Read ../private.txt lines 10-12'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: readBubble,
          matching: find.textContaining('Approval is required before Read'),
        ),
        findsNothing,
      );

      final editBubble = find.byKey(
        const ValueKey<String>('chat-run-trace-run-approval-edit-1'),
      );
      expect(
        find.descendant(
          of: editBubble,
          matching: find.textContaining('Edit README.md'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: editBubble,
          matching: find.textContaining(
            'Approval is required before Edit can modify README.md.',
          ),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'compact running card shows rejected approval as awaiting direction',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const approvalRejected = OpenCrayChatRuntimeEventSnapshot(
        kind: 'approval_result',
        runId: 'run-approval-rejected-1',
        taskId: 'task-approval-rejected-1',
        emittedAtEpochMs: 2400,
        status: 'rejected',
        toolName: 'Write',
        text: 'Approval rejected. Waiting for the next instruction.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-approval-rejected-1',
              taskId: 'task-approval-rejected-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2400,
              attempt: 1,
              isTerminal: false,
              lastEvent: approvalRejected,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-approval-rejected-1',
              taskId: 'task-approval-rejected-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            approvalRejected,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('Approval rejected'), findsOneWidget);
    },
  );

  testWidgets(
    'compact running card shows interrupted run as awaiting direction',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const cancellationEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'interrupted',
        runId: 'run-cancelled-1',
        taskId: 'task-cancelled-1',
        emittedAtEpochMs: 2500,
        toolName: 'Bash',
        text:
            'Run interrupted. The agent is waiting for your next instruction.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-cancelled-1',
              taskId: 'task-cancelled-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2500,
              attempt: 1,
              isTerminal: false,
              lastEvent: cancellationEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-cancelled-1',
              taskId: 'task-cancelled-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            cancellationEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('AWAITING'), findsOneWidget);
      expect(find.textContaining('Run interrupted.'), findsWidgets);
    },
  );

  testWidgets(
    'compact suspended card shows paused llm retry run as awaiting direction',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-llm-paused-1',
              taskId: 'task-llm-paused-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2600,
              lifecycleState: 'suspended',
              attempt: 1,
              errorCode: 'LLM_RETRY_EXHAUSTED_AWAITING_RESUME',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-llm-paused-1',
              taskId: 'task-llm-paused-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('AWAITING'), findsOneWidget);
      expect(find.text(copy.chatRunLlmRetryPausedBody), findsOneWidget);
      expect(find.text(copy.chatRunResumeAction), findsOneWidget);
      expect(find.text(copy.chatRunInterruptAction), findsNothing);
    },
  );

  testWidgets(
    'compact suspended card shows deferred approval decision as awaiting direction',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-approval-deferred-1',
              taskId: 'task-approval-deferred-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2600,
              lifecycleState: 'suspended',
              attempt: 1,
              isTerminal: false,
              recoveryPlan: OpenCrayChatRunRecoveryPlanSnapshot(
                action: 'resume_waiting_for_user',
                checkpointKind: 'approved_pending_resume',
              ),
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'approval_result',
              runId: 'run-approval-deferred-1',
              taskId: 'task-approval-deferred-1',
              emittedAtEpochMs: 1000,
              status: 'approved',
              text:
                  'Approval granted. The decision is recorded and will apply when you manually resume the run.',
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('AWAITING'), findsOneWidget);
      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-approval-deferred-1'),
      );
      expect(bubbleFinder, findsOneWidget);
      expect(find.text(copy.chatRunResumeAction), findsOneWidget);
      expect(find.text(copy.chatRunInterruptAction), findsNothing);

      await _openRunTraceFullscreen(tester, bubbleFinder);

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-approval-deferred-1',
        ),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            copy.chatRunApprovalDecisionDeferredBody,
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows public assistant phase summaries in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const assistantPhaseEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-progress-1',
        taskId: 'task-progress-1',
        emittedAtEpochMs: 2200,
        phase: 'commentary',
        isFinal: false,
        stage: 'Planning',
        text: 'Scanning README and Gradle files before choosing the next tool.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'runtime-assistant-commentary-run-progress-1-2200',
              kind: 'inbound',
              text:
                  'Planning\n\nScanning README and Gradle files before choosing the next tool.',
              isEphemeral: true,
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              isTerminal: false,
              lastEvent: assistantPhaseEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-progress-1',
              taskId: 'task-progress-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            assistantPhaseEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining(
          'Scanning README and Gradle files before choosing the next tool.',
        ),
        findsWidgets,
      );
      expect(find.text('Planning'), findsWidgets);
      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-bubble-runtime-assistant-commentary-run-progress-1-2200',
          ),
        ),
        findsOneWidget,
      );

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-progress-1'),
      );
      await _openRunTraceFullscreen(tester, bubbleFinder);

      final fullscreenScrollFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-scroll-run-progress-1',
        ),
      );

      expect(fullscreenScrollFinder, findsOneWidget);
    },
  );

  testWidgets(
    'approval resume keeps previous execution history in run trace while assistant bubbles stay scoped',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const previousExecutionPhase = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-resume-1',
        taskId: 'task-resume-1',
        executionId: 'exec-1',
        executionOrdinal: 1,
        executionKind: 'initial',
        emittedAtEpochMs: 2100,
        phase: 'commentary',
        isFinal: false,
        stage: 'Planning',
        text: 'Old execution commentary should stay hidden.',
      );
      const currentExecutionLifecycle = OpenCrayChatRuntimeEventSnapshot(
        kind: 'lifecycle',
        runId: 'run-resume-1',
        taskId: 'task-resume-1',
        executionId: 'exec-2',
        executionOrdinal: 2,
        executionKind: 'approval_resume',
        emittedAtEpochMs: 2200,
        phase: 'start',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Resume after approval.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-resume-1',
              taskId: 'task-resume-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2200,
              attempt: 1,
              executionOrdinal: 2,
              executionId: 'exec-2',
              executionKind: 'approval_resume',
              isTerminal: false,
              lastEvent: currentExecutionLifecycle,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            previousExecutionPhase,
            currentExecutionLifecycle,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-bubble-runtime-assistant-commentary-run-resume-1-2100',
          ),
        ),
        findsNothing,
      );
      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-resume-1'),
      );
      expect(runTraceFinder, findsOneWidget);

      final center = tester.getCenter(runTraceFinder);
      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenScrollFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-scroll-run-resume-1'),
      );
      expect(fullscreenScrollFinder, findsOneWidget);
      expect(
        find.descendant(
          of: fullscreenScrollFinder,
          matching: find.text('Old execution commentary should stay hidden.'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'assistant retry phases stay in run trace but do not project a chat bubble',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const retryEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-retry-1',
        taskId: 'task-retry-1',
        emittedAtEpochMs: 2250,
        phase: 'commentary',
        isFinal: false,
        stage: 'llm_retry',
        text:
            'LLM request failed with PROVIDER_TIMEOUT_FALLBACK_EXHAUSTED. Retrying in 15s (retry 1/5).',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Keep retrying if the provider flakes out.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-retry-1',
              taskId: 'task-retry-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2250,
              attempt: 1,
              isTerminal: false,
              lastEvent: retryEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-retry-1',
              taskId: 'task-retry-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            retryEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-bubble-runtime-assistant-commentary-run-retry-1-2250',
          ),
        ),
        findsNothing,
      );
      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-retry-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      expect(
        find.descendant(
          of: runTraceFinder,
          matching: find.textContaining('PROVIDER_TIMEOUT_FALLBACK_EXHAUSTED'),
        ),
        findsWidgets,
      );

      final center = tester.getCenter(runTraceFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenScrollFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-scroll-run-retry-1'),
      );
      expect(fullscreenScrollFinder, findsOneWidget);
      expect(
        find.descendant(
          of: fullscreenScrollFinder,
          matching: find.textContaining('PROVIDER_TIMEOUT_FALLBACK_EXHAUSTED'),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'fullscreen inspector preserves chronology across tool, process, and final attachment entries',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const toolCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-chronology-1',
        taskId: 'task-chronology-1',
        emittedAtEpochMs: 2000,
        toolName: 'Read',
        argumentsJson: '{"path":"README.md"}',
      );
      const planningEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'assistant_phase',
        runId: 'run-chronology-1',
        taskId: 'task-chronology-1',
        emittedAtEpochMs: 2100,
        phase: 'commentary',
        isFinal: false,
        stage: 'Planning',
        text: 'Planning update before tool result.',
      );
      const toolResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-chronology-1',
        taskId: 'task-chronology-1',
        emittedAtEpochMs: 2200,
        toolName: 'Read',
        content: 'README contents after reading file.',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Inspect the workspace.',
              createdAtEpochMs: 1000,
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-chronology-1',
              kind: 'inbound',
              text: 'Thinking',
              createdAtEpochMs: 1100,
            ),
          ],
        ),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-chronology-1',
              taskId: 'task-chronology-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 4000,
              attempt: 1,
              pendingMessageId: 'pending-chronology-1',
              managedProcessIds: <String>['proc-chronology-1'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-chronology-1',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  startedAtEpochMs: 2050,
                  updatedAtEpochMs: 2300,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: true,
              lastEvent: toolResult,
              finalAttachments: <OpenCrayChatAttachmentSnapshot>[
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'artifact-diagram',
                  kind: 'image',
                  displayName: 'diagram.png',
                  localPath: '.opencray/chat-media/session-1/diagram.png',
                  mimeType: 'image/png',
                ),
              ],
            ),
          ],
          events: const <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-chronology-1',
              taskId: 'task-chronology-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            toolCall,
            planningEvent,
            toolResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final runTraceFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-chronology-1'),
      );
      expect(runTraceFinder, findsOneWidget);
      await _openRunTraceFullscreen(tester, runTraceFinder);

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-chronology-1'),
      );
      final double processY = _topYForDescendantText(
        tester,
        fullscreenFinder,
        'ready on http://localhost:3000',
      );
      final double planningY = _topYForDescendantText(
        tester,
        fullscreenFinder,
        'Planning update before tool result.',
      );
      final double resultY = _topYForDescendantText(
        tester,
        fullscreenFinder,
        'README contents after reading file.',
      );
      final double attachmentY = _topYForDescendantText(
        tester,
        fullscreenFinder,
        'diagram.png',
      );

      expect(processY, lessThan(planningY));
      expect(planningY, lessThan(resultY));
      expect(resultY, lessThan(attachmentY));
    },
  );

  testWidgets(
    'host-mapped run trace shows applied supplements in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const supplementEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'supplement',
        runId: 'run-supplement-1',
        taskId: 'task-supplement-1',
        emittedAtEpochMs: 2500,
        turn: 1,
        entryId: 'supplement-1',
        text: 'Also check the tests before answering.',
        checkpoint: 'turn_start',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-supplement-1',
              taskId: 'task-supplement-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2500,
              attempt: 1,
              isTerminal: false,
              lastEvent: supplementEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-supplement-1',
              taskId: 'task-supplement-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            supplementEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-supplement-1'),
      );

      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.textContaining(
            'Also check the tests before answering.',
          ),
        ),
        findsOneWidget,
      );

      final center = tester.getCenter(bubbleFinder);
      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-supplement-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Also check the tests before answering.',
          ),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Applied at turn start'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace renders post-tool supplement checkpoints with a readable label',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const supplementEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'supplement',
        runId: 'run-supplement-tool-boundary',
        taskId: 'task-supplement-tool-boundary',
        emittedAtEpochMs: 2500,
        turn: 1,
        entryId: 'supplement-tool-boundary-1',
        text: 'Use the repository root as the workspace.',
        checkpoint: 'post_tool_pre_model',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-supplement-tool-boundary',
              taskId: 'task-supplement-tool-boundary',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2500,
              attempt: 1,
              isTerminal: false,
              lastEvent: supplementEvent,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-supplement-tool-boundary',
              taskId: 'task-supplement-tool-boundary',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            supplementEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-supplement-tool-boundary'),
      );

      final center = tester.getCenter(bubbleFinder);
      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-supplement-tool-boundary',
        ),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Applied after tool result'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows memory retrieval details in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const memoryEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'memory_retrieval',
        runId: 'run-memory-1',
        taskId: 'task-memory-1',
        emittedAtEpochMs: 2000,
        toolName: 'memory_search',
        operation: 'search',
        query: 'gradle wrapper repo root',
        queryTerms: <String>['gradle', 'wrapper', 'repo', 'root'],
        resultCount: 1,
        corpusFileCount: 1,
        paths: <String>['memory/2024-03-11.md'],
        lineRanges: <String>['5-8'],
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-memory-1',
              taskId: 'task-memory-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2000,
              attempt: 1,
              isTerminal: false,
              lastEvent: memoryEvent,
            ),
          ],
          events: const <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-memory-1',
              taskId: 'task-memory-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            memoryEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining('Search memory for "gradle wrapper repo root"'),
        findsOneWidget,
      );
      expect(find.textContaining('memory/2024-03-11.md#5-8'), findsOneWidget);

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-memory-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-memory-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Query terms: gradle, wrapper, repo, root',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('memory/2024-03-11.md#5-8'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows memory maintenance details in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const memoryEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'memory_write',
        runId: 'run-memory-write-1',
        taskId: 'task-memory-write-1',
        emittedAtEpochMs: 2500,
        writtenRecordIds: <String>['memory-user-1'],
        writtenKinds: <String>['user_preference'],
        resolvedRecordIds: <String>['commitment-done-1'],
        suppressedRecordIds: <String>['memory-muted-1'],
        reaffirmedRecordIds: <String>['commitment-keep-1'],
        expiredRecordIds: <String>['commitment-old-1'],
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-memory-write-1',
              taskId: 'task-memory-write-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2500,
              attempt: 1,
              isTerminal: false,
              lastEvent: memoryEvent,
            ),
          ],
          events: const <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-memory-write-1',
              taskId: 'task-memory-write-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            memoryEvent,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-memory-write-1'),
      );
      expect(bubbleFinder, findsOneWidget);
      expect(
        find.descendant(of: bubbleFinder, matching: find.text('MEMORY')),
        findsOneWidget,
      );

      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-memory-write-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Written: memory-user-1'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Kinds: user_preference'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Resolved: commitment-done-1'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Suppressed: memory-muted-1'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Reaffirmed: commitment-keep-1'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Expired: commitment-old-1'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows context setup traces in full-screen view',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const lifecycleEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'lifecycle',
        runId: 'run-context-1',
        taskId: 'task-context-1',
        emittedAtEpochMs: 1000,
        phase: 'start',
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-context-1',
              taskId: 'task-context-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: false,
              lastEvent: lifecycleEvent,
              liveContext: OpenCrayChatRunLiveContextSnapshot(
                mode: 'no_soul',
                soulEnabled: false,
                memoryRecallEnabled: true,
              ),
              memoryTrace: OpenCrayChatRunMemoryTraceSnapshot(
                matchedRecordCount: 2,
                injectedRecordCount: 1,
                omittedRecordCount: 1,
                queryTerms: <String>['gradle', 'wrapper'],
                selected: <OpenCrayChatRunMemorySelectedSnapshot>[
                  OpenCrayChatRunMemorySelectedSnapshot(
                    id: 'mem-workspace',
                    score: 420,
                    matchedTerms: <String>['gradle', 'wrapper'],
                  ),
                ],
                omitted: <OpenCrayChatRunMemoryOmittedSnapshot>[
                  OpenCrayChatRunMemoryOmittedSnapshot(
                    id: 'mem-old',
                    reason: 'max_records',
                  ),
                ],
              ),
              memoryFlush: OpenCrayChatRunMemoryFlushSnapshot(
                outcome: 'written',
                candidateCount: 2,
                writtenRecordCount: 1,
                writtenKinds: <String>['project_fact'],
                writtenRecordIds: <String>['mem-workspace'],
              ),
              bootstrap: OpenCrayChatRunBootstrapSnapshot(
                mode: 'full',
                visibleFileCount: 2,
                injectedFileCount: 2,
                truncatedFileCount: 1,
                files: <OpenCrayChatRunBootstrapFileSnapshot>[
                  OpenCrayChatRunBootstrapFileSnapshot(
                    name: 'AGENTS.md',
                    relativePath: 'AGENTS.md',
                    sourceCharCount: 42,
                    injectedCharCount: 42,
                    truncated: false,
                  ),
                  OpenCrayChatRunBootstrapFileSnapshot(
                    name: 'PROJECT.md',
                    relativePath: 'PROJECT.md',
                    sourceCharCount: 80,
                    injectedCharCount: 31,
                    truncated: true,
                  ),
                ],
              ),
              durableCompaction: OpenCrayChatRunDurableCompactionSnapshot(
                compactedThisRun: true,
                sourceTranscriptMessageCount: 18,
                retainedTranscriptMessageCount: 12,
                latestCompactedMessageCount: 6,
                includedSummaryCount: 1,
                totalSummaryCount: 1,
                totalCompactedMessageCount: 6,
              ),
              skillInventory: OpenCrayChatRunSkillInventorySnapshot(
                visibleSkillCount: 2,
                injectedSkillCount: 2,
                implicitSkillCount: 1,
                skills: <OpenCrayChatRunVisibleSkillSnapshot>[
                  OpenCrayChatRunVisibleSkillSnapshot(
                    name: 'ui-ux-pro-max',
                    relativePath: 'skills/ui-ux-pro-max/SKILL.md',
                    invocationControl: 'manual',
                    userInvocable: true,
                    executionContext: 'shared',
                  ),
                ],
              ),
              activeSkill: OpenCrayChatRunActiveSkillSnapshot(
                name: 'ui-ux-pro-max',
                relativePath: 'skills/ui-ux-pro-max/SKILL.md',
                activationSource: 'skill_read',
                toolRestrictionEnabled: true,
                allowedToolKeys: <String>['read', 'write'],
              ),
            ),
          ],
          events: const <OpenCrayChatRuntimeEventSnapshot>[lifecycleEvent],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-context-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-context-1'),
      );

      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Mode: no_soul'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Soul disabled'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Automatic memory recall enabled'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Mode: full'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('AGENTS.md (AGENTS.md)'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Query terms: gradle, wrapper'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Outcome: written'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Retained 12/18 transcript messages'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('ui-ux-pro-max'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Allowed tools: read, write'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('run status line replaces the compact scroll body', (
    tester,
  ) async {
    await tester.pumpWidget(_buildChatHarness());
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-1'),
    );
    final scrollableFinder = find.descendant(
      of: bubbleFinder,
      matching: find.byType(Scrollable),
    );
    expect(scrollableFinder, findsNothing);
    expect(
      find.descendant(of: bubbleFinder, matching: find.textContaining('Read')),
      findsOneWidget,
    );
  });

  testWidgets('full-screen running card body opens at the latest entry', (
    tester,
  ) async {
    await tester.pumpWidget(_buildChatHarness());
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-1'),
    );
    final center = tester.getCenter(bubbleFinder);

    await tester.tapAt(center);
    await tester.pump(const Duration(milliseconds: 40));
    await tester.tapAt(center);
    await tester.pumpAndSettle();

    final fullscreenScrollableFinder = find.descendant(
      of: find.byKey(const ValueKey<String>('chat-run-trace-fullscreen-run-1')),
      matching: find.byType(Scrollable),
    );
    final scrollableState = tester.state<ScrollableState>(
      fullscreenScrollableFinder,
    );

    expect(scrollableState.position.maxScrollExtent, greaterThan(0));
    expect(
      scrollableState.position.pixels,
      scrollableState.position.maxScrollExtent,
    );
  });

  testWidgets(
    'fullscreen inspector live updates while open and renders managed process entries',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              kind: 'outbound',
              text: 'Start the dev server.',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'pending-1',
              kind: 'inbound',
              text: 'Thinking',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-live-1',
              taskId: 'task-live-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1100,
              attempt: 1,
              pendingMessageId: 'pending-1',
              pendingExecutionKind: 'initial',
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-live-1',
              taskId: 'task-live-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-live-1'),
      );
      expect(bubbleFinder, findsOneWidget);

      await _openRunTraceFullscreen(tester, bubbleFinder);

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-live-1'),
      );
      expect(fullscreenFinder, findsOneWidget);

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-live-1',
              taskId: 'task-live-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2300,
              attempt: 1,
              pendingMessageId: 'pending-1',
              pendingExecutionKind: 'initial',
              managedProcessIds: <String>['proc-live'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-live',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  startedAtEpochMs: 1200,
                  updatedAtEpochMs: 2300,
                  stdoutPreview: 'ready on http://localhost:3000',
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-live-1',
              taskId: 'task-live-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'assistant_phase',
              runId: 'run-live-1',
              taskId: 'task-live-1',
              emittedAtEpochMs: 2200,
              phase: 'commentary',
              isFinal: false,
              stage: 'Planning',
              text:
                  'Scanning README and Gradle files before choosing the next tool.',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.descendant(of: fullscreenFinder, matching: find.text('Planning')),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Scanning README and Gradle files before choosing the next tool.',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.text('Process proc-live', findRichText: true),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('npm run dev', findRichText: true),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'ready on http://localhost:3000',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'process inspector renders full stdout and stderr instead of preview tails',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-process-full-output-1',
              taskId: 'task-process-full-output-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 2300,
              attempt: 1,
              pendingMessageId: 'pending-1',
              pendingExecutionKind: 'initial',
              managedProcessIds: <String>['proc-live'],
              managedProcesses: <OpenCrayChatManagedProcessSnapshot>[
                OpenCrayChatManagedProcessSnapshot(
                  processId: 'proc-live',
                  status: 'running',
                  command: 'npm',
                  args: <String>['run', 'dev'],
                  workingDirectory: '.',
                  startedAtEpochMs: 1200,
                  updatedAtEpochMs: 2300,
                  stdout: 'booting\nready on http://localhost:3000',
                  stdoutPreview: 'ready on http://localhost:3000',
                  stdoutTruncated: true,
                  stderr: 'warn: deprecated dependency\nwatching for changes',
                  stderrPreview: 'watching for changes',
                  stderrTruncated: true,
                ),
              ],
              runningManagedProcessCount: 1,
              hasLiveManagedProcesses: true,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-process-full-output-1',
              taskId: 'task-process-full-output-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-process-full-output-1'),
      );
      expect(bubbleFinder, findsOneWidget);

      await _openRunTraceFullscreen(tester, bubbleFinder);

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-process-full-output-1',
        ),
      );
      expect(fullscreenFinder, findsOneWidget);
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('booting', findRichText: true),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'warn: deprecated dependency',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('[output truncated]'),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'retained terminal runs stay visible and keep full tool content in fullscreen inspector',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const toolCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-retained-1',
        taskId: 'task-retained-1',
        emittedAtEpochMs: 2000,
        toolName: 'Read',
        argumentsJson: '{"file_path":"README.md"}',
      );
      const toolResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-retained-1',
        taskId: 'task-retained-1',
        emittedAtEpochMs: 3000,
        toolName: 'Read',
        content: 'README full content from retained run history.',
        contentPreview: 'README full content from retained run history.',
        resultMetadata: <String, String>{'filePath': 'README.md'},
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          retainedRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-retained-1',
              taskId: 'task-retained-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: true,
              lastEvent: toolResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-retained-1',
              taskId: 'task-retained-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            toolCall,
            toolResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-retained-1'),
      );
      expect(bubbleFinder, findsOneWidget);

      final center = tester.getCenter(bubbleFinder);
      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-retained-1'),
      );
      expect(fullscreenFinder, findsOneWidget);
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'README full content from retained run history.',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'host-mapped run trace consumes workspace tool aliases in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const toolCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-host-workspace-read-1',
        taskId: 'task-host-workspace-read-1',
        emittedAtEpochMs: 2000,
        toolName: 'workspace_read_file',
        argumentsJson: '{"path":"README.md","offset":5,"limit":2}',
      );
      const toolResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-host-workspace-read-1',
        taskId: 'task-host-workspace-read-1',
        emittedAtEpochMs: 3000,
        toolName: 'workspace_read_file',
        contentPreview:
            'Project uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
        resultMetadata: <String, String>{
          'filePath': 'README.md',
          'offset': '5',
          'limit': '2',
          'returnedLineCount': '2',
          'totalLineCount': '12',
        },
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-host-workspace-read-1',
              taskId: 'task-host-workspace-read-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: false,
              lastEvent: toolResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-host-workspace-read-1',
              taskId: 'task-host-workspace-read-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            toolCall,
            toolResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('Read README.md lines 5-6'), findsOneWidget);
      expect(
        find.textContaining('Returned 2 lines from README.md'),
        findsOneWidget,
      );
      expect(find.textContaining('workspace_read_file'), findsNothing);

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-host-workspace-read-1'),
      );
      await _openRunTraceFullscreen(tester, bubbleFinder);

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-host-workspace-read-1',
        ),
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Read README.md lines 5-6',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('workspace_read_file'),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'host-mapped run trace consumes preserved tool aliases in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const readCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-alias-read-1',
        taskId: 'task-alias-read-1',
        emittedAtEpochMs: 2000,
        toolName: 'read',
        argumentsJson: '{"path":"README.md","offset":5,"limit":2}',
      );
      const readResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-alias-read-1',
        taskId: 'task-alias-read-1',
        emittedAtEpochMs: 3000,
        toolName: 'read',
        contentPreview:
            'Project uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
        resultMetadata: <String, String>{
          'filePath': 'README.md',
          'offset': '5',
          'limit': '2',
          'returnedLineCount': '2',
          'totalLineCount': '12',
        },
      );
      const grepCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-alias-grep-1',
        taskId: 'task-alias-grep-1',
        emittedAtEpochMs: 4000,
        toolName: 'grep',
        argumentsJson: '{"pattern":"TODO","path":"lib"}',
      );
      const grepResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-alias-grep-1',
        taskId: 'task-alias-grep-1',
        emittedAtEpochMs: 5000,
        toolName: 'grep',
        contentPreview: 'lib/main.dart:12:// TODO\nlib/app.dart:8:// TODO',
        resultMetadata: <String, String>{
          'pattern': 'TODO',
          'path': 'lib',
          'matchCount': '2',
        },
      );
      const bashCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-alias-bash-1',
        taskId: 'task-alias-bash-1',
        emittedAtEpochMs: 6000,
        toolName: 'bash',
        argumentsJson: '{"command":"git status --short"}',
      );
      const bashResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-alias-bash-1',
        taskId: 'task-alias-bash-1',
        emittedAtEpochMs: 7000,
        toolName: 'bash',
        contentPreview:
            ' M flutter_app/lib/features/chat/chat_feature_screen.dart',
        resultMetadata: <String, String>{
          'commandSummary': 'git status --short',
        },
      );
      const webFetchCall = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_call',
        runId: 'run-alias-webfetch-1',
        taskId: 'task-alias-webfetch-1',
        emittedAtEpochMs: 8000,
        toolName: 'webfetch',
        argumentsJson: '{"url":"https://opencray.dev/docs"}',
      );
      const webFetchResult = OpenCrayChatRuntimeEventSnapshot(
        kind: 'tool_result',
        runId: 'run-alias-webfetch-1',
        taskId: 'task-alias-webfetch-1',
        emittedAtEpochMs: 9000,
        toolName: 'webfetch',
        contentPreview: '<html><title>OpenCray Docs</title></html>',
        resultMetadata: <String, String>{
          'requestedUrl': 'https://opencray.dev/docs',
        },
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-alias-read-1',
              taskId: 'task-alias-read-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: false,
              lastEvent: readResult,
            ),
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-alias-grep-1',
              taskId: 'task-alias-grep-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 5000,
              attempt: 1,
              isTerminal: false,
              lastEvent: grepResult,
            ),
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-alias-bash-1',
              taskId: 'task-alias-bash-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 7000,
              attempt: 1,
              isTerminal: false,
              lastEvent: bashResult,
            ),
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-alias-webfetch-1',
              taskId: 'task-alias-webfetch-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 9000,
              attempt: 1,
              isTerminal: false,
              lastEvent: webFetchResult,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-alias-read-1',
              taskId: 'task-alias-read-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            readCall,
            readResult,
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-alias-grep-1',
              taskId: 'task-alias-grep-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            grepCall,
            grepResult,
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-alias-bash-1',
              taskId: 'task-alias-bash-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            bashCall,
            bashResult,
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-alias-webfetch-1',
              taskId: 'task-alias-webfetch-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
            webFetchCall,
            webFetchResult,
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('Read README.md lines 5-6'), findsOneWidget);
      expect(
        find.textContaining('Returned 2 lines from README.md'),
        findsOneWidget,
      );
      expect(find.textContaining('Search "TODO" in lib'), findsOneWidget);
      expect(
        find.textContaining('Found 2 matches for "TODO" in lib'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Run command git status --short'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Fetch https://opencray.dev/docs'),
        findsOneWidget,
      );
      expect(find.textContaining('Calling tool: read'), findsNothing);
      expect(find.textContaining('Calling tool: grep'), findsNothing);
      expect(find.textContaining('Calling tool: bash'), findsNothing);
      expect(find.textContaining('Calling tool: webfetch'), findsNothing);

      Future<void> expectFullscreenTrace({
        required String runId,
        required List<String> expectedTexts,
      }) async {
        final bubbleFinder = find.byKey(
          ValueKey<String>('chat-run-trace-$runId'),
        );
        int scrollAttempts = 0;
        while (bubbleFinder.evaluate().isEmpty && scrollAttempts < 12) {
          await tester.drag(
            find.byType(SingleChildScrollView).first,
            const Offset(0, -240),
          );
          await tester.pumpAndSettle();
          scrollAttempts += 1;
        }
        expect(bubbleFinder, findsOneWidget);
        await tester.ensureVisible(bubbleFinder);
        await _openRunTraceFullscreen(tester, bubbleFinder);

        final fullscreenFinder = find.byKey(
          ValueKey<String>('chat-run-trace-fullscreen-$runId'),
        );
        expect(fullscreenFinder, findsOneWidget);
        for (final expectedText in expectedTexts) {
          expect(
            find.descendant(
              of: fullscreenFinder,
              matching: find.textContaining(expectedText, findRichText: true),
            ),
            findsOneWidget,
          );
        }

        await tester.tap(
          find.descendant(
            of: fullscreenFinder,
            matching: find.byIcon(Icons.close_rounded),
          ),
        );
        await tester.pumpAndSettle();
      }

      await expectFullscreenTrace(
        runId: 'run-alias-read-1',
        expectedTexts: <String>['Read README.md lines 5-6'],
      );
      await expectFullscreenTrace(
        runId: 'run-alias-grep-1',
        expectedTexts: <String>[
          'Search "TODO" in lib',
          'Found 2 matches for "TODO" in lib',
        ],
      );
      await expectFullscreenTrace(
        runId: 'run-alias-bash-1',
        expectedTexts: <String>['Run command git status --short'],
      );
      await expectFullscreenTrace(
        runId: 'run-alias-webfetch-1',
        expectedTexts: <String>['Fetch https://opencray.dev/docs'],
      );
    },
  );

  testWidgets(
    'retained terminal runs show final attachments in fullscreen inspector',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          retainedRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-retained-attachments',
              taskId: 'task-retained-attachments',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 3000,
              attempt: 1,
              isTerminal: true,
              finalAttachments: <OpenCrayChatAttachmentSnapshot>[
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'artifact-diagram',
                  kind: 'image',
                  displayName: 'diagram.png',
                  localPath: '.opencray/chat-media/session-1/diagram.png',
                  mimeType: 'image/png',
                ),
              ],
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-retained-attachments'),
      );
      expect(bubbleFinder, findsOneWidget);

      final center = tester.getCenter(bubbleFinder);
      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-run-retained-attachments',
        ),
      );
      expect(fullscreenFinder, findsOneWidget);
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('diagram.png', findRichText: true),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            '.opencray/chat-media/session-1/diagram.png',
            findRichText: true,
          ),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets(
    'approval card replaces the composer with a bottom glass surface',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      await tester.pumpWidget(
        _buildChatHarness(
          pendingApprovals: const <ChatPendingApprovalData>[
            ChatPendingApprovalData(
              runId: 'run-1',
              taskId: 'task-1',
              title: 'Approval required',
              body: 'Write note.txt?',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: false,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-approval-surface')),
        findsOneWidget,
      );
      expect(find.text('Approval required'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-1')),
        findsOneWidget,
      );
      expect(find.text(copy.chatComposerPlaceholder), findsNothing);
    },
  );

  testWidgets(
    'host-backed approval card shows tool name, concrete request details, and agent reason',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          pendingApprovals: const <OpenCrayChatPendingApprovalSnapshot>[
            OpenCrayChatPendingApprovalSnapshot(
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              title: 'Approval required',
              body:
                  'Command: git status --short\nWorking directory: .\nAgent reason: Check repository state before editing.\n\nApproval is required before Bash can run.',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: false,
              toolName: 'Bash',
              requestSummary: 'git status --short',
              primaryDetail: 'git status --short',
              workingDirectory: '.',
              reason: 'Check repository state before editing.',
              message: 'Approval is required before Bash can run.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[
            OpenCrayChatRunSnapshot(
              sessionId: 'session-1',
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              acceptedAtEpochMs: 1000,
              updatedAtEpochMs: 1001,
              attempt: 1,
              isTerminal: false,
            ),
          ],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'lifecycle',
              runId: 'run-approval-1',
              taskId: 'task-approval-1',
              emittedAtEpochMs: 1000,
              phase: 'start',
            ),
          ],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-approval-surface')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-approval-card-run-approval-1')),
        findsOneWidget,
      );
      expect(find.text('Approval required'), findsOneWidget);
      expect(find.text(copy.chatComposerPlaceholder), findsNothing);
      expect(find.text('git status --short'), findsOneWidget);
      expect(find.text('Working directory  .'), findsOneWidget);
      expect(
        find.text('Reason  Check repository state before editing.'),
        findsOneWidget,
      );

      await tester.ensureVisible(find.text('Approve'));
      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();

      expect(bridge.approvedApprovalIds, <String>['run-approval-1']);
    },
  );

  testWidgets(
    'host-backed approval surface appears as soon as a pending approval snapshot arrives',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      addTearDown(snapshots.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        chatSnapshotStream: snapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-approval-surface')),
        findsNothing,
      );
      expect(find.text(copy.chatComposerPlaceholder), findsOneWidget);

      snapshots.add(
        _hostChatSnapshot(
          pendingApprovals: const <OpenCrayChatPendingApprovalSnapshot>[
            OpenCrayChatPendingApprovalSnapshot(
              runId: 'run-approval-live-1',
              taskId: 'task-approval-live-1',
              title: 'Approval required',
              body:
                  'Command: git status --short\nWorking directory: .\nAgent reason: Check repository state before editing.\n\nApproval is required before Bash can run.',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: false,
              toolName: 'Bash',
              requestSummary: 'git status --short',
              primaryDetail: 'git status --short',
              workingDirectory: '.',
              reason: 'Check repository state before editing.',
              message: 'Approval is required before Bash can run.',
            ),
          ],
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-approval-surface')),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-approval-card-run-approval-live-1'),
        ),
        findsOneWidget,
      );
      expect(find.text(copy.chatComposerPlaceholder), findsNothing);
      expect(find.text('git status --short'), findsOneWidget);
    },
  );

  testWidgets(
    'multiple approvals render as a stacked queue and only the first one is actionable',
    (tester) async {
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          pendingApprovals: const <OpenCrayChatPendingApprovalSnapshot>[
            OpenCrayChatPendingApprovalSnapshot(
              runId: 'run-approval-stack-1',
              taskId: 'task-approval-stack-1',
              title: 'Approval required',
              body: 'Write lib/a.dart',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: false,
              requestSummary: 'Write lib/a.dart',
              reason: 'Patch the first file.',
            ),
            OpenCrayChatPendingApprovalSnapshot(
              runId: 'run-approval-stack-2',
              taskId: 'task-approval-stack-2',
              title: 'Approval required',
              body: 'Write lib/b.dart',
              approveLabel: 'Approve',
              rejectLabel: 'Reject',
              isHighRisk: false,
              requestSummary: 'Write lib/b.dart',
              reason: 'Patch the second file.',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-approval-stack')),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-approval-card-run-approval-stack-1'),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-approval-card-run-approval-stack-2'),
        ),
        findsOneWidget,
      );
      expect(find.text('Write lib/a.dart'), findsWidgets);
      expect(find.text('Write lib/b.dart'), findsWidgets);

      await tester.ensureVisible(find.text('Approve'));
      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();

      expect(bridge.approvedApprovalIds, <String>['run-approval-stack-1']);
      expect(bridge.rejectedApprovalIds, isEmpty);
    },
  );

  testWidgets('chat messages render timestamps and 8-minute time dividers', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final DateTime now = DateTime.now().toLocal();
    final DateTime firstAt = DateTime(now.year, now.month, now.day, 9, 0);
    final DateTime secondAt = firstAt.add(const Duration(minutes: 5));
    final DateTime thirdAt = secondAt.add(const Duration(minutes: 8));
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'message-1',
            kind: 'outbound',
            text: 'First message',
            createdAtEpochMs: firstAt.millisecondsSinceEpoch,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'message-2',
            kind: 'inbound',
            text: 'Second message',
            createdAtEpochMs: secondAt.millisecondsSinceEpoch,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'message-3',
            kind: 'outbound',
            text: 'Third message',
            createdAtEpochMs: thirdAt.millisecondsSinceEpoch,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-message-divider-message-1')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-message-divider-message-2')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-message-divider-message-3')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-message-time-message-1')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-message-time-message-2')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-message-time-message-3')),
      findsNothing,
    );
  });

  testWidgets('message menu stays open after a long press ends', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: <OpenCrayChatMessageSnapshot>[
          const OpenCrayChatMessageSnapshot(
            messageId: 'message-long-press',
            kind: 'inbound',
            text: 'Long press this message',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.longPress(
      find.byKey(const ValueKey<String>('chat-bubble-message-long-press')),
    );
    await tester.pumpAndSettle();

    expect(find.text(copy.chatMessageCopyAction), findsOneWidget);
    expect(find.text(copy.chatMessageDeleteAction), findsOneWidget);
  });

  testWidgets('message menu actions remain tappable after opening', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final Map<String, Object?> clipboardState = <String, Object?>{};
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (
          MethodCall methodCall,
        ) async {
          switch (methodCall.method) {
            case 'Clipboard.setData':
              final Map<Object?, Object?> arguments =
                  methodCall.arguments as Map<Object?, Object?>;
              clipboardState['text'] = arguments['text'];
              return null;
            case 'Clipboard.getData':
              final Object? text = clipboardState['text'];
              return text == null ? null : <String, Object?>{'text': text};
          }
          return null;
        });
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null);
    });

    const messageText = 'Long press this message';
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: <OpenCrayChatMessageSnapshot>[
          const OpenCrayChatMessageSnapshot(
            messageId: 'message-long-press-action',
            kind: 'inbound',
            text: messageText,
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.longPress(
      find.byKey(
        const ValueKey<String>('chat-bubble-message-long-press-action'),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>('chat-message-menu-message-long-press-action'),
      ),
      findsOneWidget,
    );

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-message-menu-action-copy')),
    );
    await tester.pumpAndSettle();

    final ClipboardData? clipboardData = await Clipboard.getData(
      Clipboard.kTextPlain,
    );
    expect(clipboardData?.text, messageText);
    expect(find.text(copy.chatMessageCopied), findsOneWidget);
    expect(
      find.byKey(
        const ValueKey<String>('chat-message-menu-message-long-press-action'),
      ),
      findsNothing,
    );
  });

  testWidgets(
    'host-backed message delete hides immediately and ignores stale snapshots',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      addTearDown(snapshots.close);
      OpenCrayChatSnapshot snapshotWithMessage({
        required int updatedAtEpochMs,
      }) {
        return _hostChatSnapshot(
          updatedAtEpochMs: updatedAtEpochMs,
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'message-delete-1',
              kind: 'inbound',
              text: 'Delete this message',
            ),
            OpenCrayChatMessageSnapshot(
              messageId: 'message-keep-1',
              kind: 'outbound',
              text: 'Keep this message',
            ),
          ],
        );
      }

      final bridge = _FakeChatBridge(
        chatSnapshot: snapshotWithMessage(updatedAtEpochMs: 1000),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        chatSnapshotStream: snapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-bubble-message-delete-1')),
        findsOneWidget,
      );
      await tester.longPress(
        find.byKey(const ValueKey<String>('chat-bubble-message-delete-1')),
      );
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const ValueKey<String>('chat-message-menu-action-delete')),
      );
      await tester.pumpAndSettle();

      expect(bridge.deletedMessageIds, <String>['message-delete-1']);
      expect(
        find.byKey(const ValueKey<String>('chat-bubble-message-delete-1')),
        findsNothing,
      );
      expect(find.text('Keep this message'), findsOneWidget);

      snapshots.add(snapshotWithMessage(updatedAtEpochMs: 2000));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-bubble-message-delete-1')),
        findsNothing,
      );
      expect(find.text('Keep this message'), findsOneWidget);
    },
  );

  testWidgets('selection mode preserves outbound bubble right edge', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(
            copy: copy,
            state: ChatFeatureState(
              variant: ChatPrototypeVariant.main,
              screenTitle: 'Chat',
              summary: const ChatSessionSummary(
                title: 'Session',
                badge: '2 messages',
                body: 'Selecting messages',
              ),
              messages: const <ChatMessageData>[
                ChatMessageData(
                  messageId: 'message-select-user',
                  kind: ChatMessageKind.outbound,
                  text: 'Keep me aligned to the right.',
                ),
                ChatMessageData(
                  messageId: 'message-select-assistant',
                  kind: ChatMessageKind.inbound,
                  text: 'Assistant reply',
                ),
              ],
              runTraces: const <ChatRunTraceData>[],
              composer: ChatComposerState(placeholder: 'Message OpenCray'),
              drawer: ChatSessionsDrawerState(
                eyebrow: 'Recent sessions',
                title: 'Recent sessions',
                ctaLabel: 'New session',
                sessions: const <ChatSessionListItemData>[],
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final userBubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-message-select-user'),
    );
    final double normalRight = tester.getTopRight(userBubbleFinder).dx;

    await tester.longPress(userBubbleFinder);
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(
        const ValueKey<String>('chat-message-menu-action-multiSelect'),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>('chat-message-row-message-select-user'),
      ),
      findsOneWidget,
    );
    expect(tester.getTopRight(userBubbleFinder).dx, closeTo(normalRight, 0.5));
    expect(
      tester
          .getTopRight(
            find.byKey(
              const ValueKey<String>('chat-message-row-bg-message-select-user'),
            ),
          )
          .dx,
      greaterThanOrEqualTo(normalRight),
    );
  });

  testWidgets(
    'editing an outbound voice attachment preserves its draft reference and metadata',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: <OpenCrayChatMessageSnapshot>[
            const OpenCrayChatMessageSnapshot(
              messageId: 'message-edit-attachment',
              kind: 'outbound',
              text: 'Resend this voice note',
              attachments: <OpenCrayChatAttachmentSnapshot>[
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'chat-voice-1',
                  kind: 'voice',
                  displayName: 'voice-note.m4a',
                  localPath: '.opencray/chat-media/session-1/voice-note.m4a',
                  mimeType: 'audio/m4a',
                  sizeBytes: 2048,
                  durationMs: 2300,
                  waveformBars: <int>[8, 16, 12],
                  transcriptText: 'voice note',
                ),
              ],
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        chatSnapshotStream: snapshots.stream,
        onRecallChatMessage: (sessionId, messageId) async {
          snapshots.add(
            _hostChatSnapshot(
              updatedAtEpochMs: 2_000,
              messages: const <OpenCrayChatMessageSnapshot>[],
            ),
          );
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.longPress(
        find.byKey(
          const ValueKey<String>('chat-bubble-message-edit-attachment'),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(
        find.byKey(const ValueKey<String>('chat-message-menu-action-edit')),
      );
      await tester.pumpAndSettle();

      expect(
        find.byWidgetPredicate(
          (widget) =>
              widget is EditableText &&
              widget.controller.text == 'Resend this voice note',
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-composer-attachment-.opencray/chat-media/session-1/voice-note.m4a',
          ),
        ),
        findsOneWidget,
      );

      await tester.showKeyboard(find.byType(TextField));
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(bridge.recalledMessageIds, <String>['message-edit-attachment']);
      expect(bridge.submittedAttachments, hasLength(1));
      expect(bridge.submittedAttachments.single, hasLength(1));
      final OpenCrayChatDraftAttachment submitted =
          bridge.submittedAttachments.single.single;
      expect(submitted.kind, OpenCrayChatDraftAttachmentKind.voice);
      expect(submitted.chatAttachmentId, 'chat-voice-1');
      expect(
        submitted.relativePath,
        '.opencray/chat-media/session-1/voice-note.m4a',
      );
      expect(submitted.durationMs, 2300);
      expect(submitted.waveformBars, <int>[8, 16, 12]);
      expect(submitted.transcriptText, 'voice note');

      await snapshots.close();
    },
  );

  testWidgets('tapping outside the composer dismisses chat input focus', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-focus',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byType(TextField));
    await tester.pump();
    TextField composerField = tester.widget<TextField>(find.byType(TextField));
    expect(composerField.focusNode?.hasFocus ?? false, isTrue);

    await tester.tapAt(const Offset(24, 24));
    await tester.pump();
    composerField = tester.widget<TextField>(find.byType(TextField));
    expect(composerField.focusNode?.hasFocus ?? false, isFalse);
  });

  testWidgets('session drawer shows unread dot and count badges', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildChatHarness(
        drawerOpen: true,
        drawer: const ChatSessionsDrawerState(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: <ChatSessionListItemData>[
            ChatSessionListItemData(
              sessionId: 'session-dot',
              title: 'Background dot',
              preview: 'Agent replied in the background.',
              meta: '2 messages',
              unreadCount: 1,
            ),
            ChatSessionListItemData(
              sessionId: 'session-count',
              title: 'Background count',
              preview: 'More than one reply arrived.',
              meta: '5 messages',
              unreadCount: 3,
            ),
          ],
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-session-unread-session-dot')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-session-unread-session-count')),
      findsOneWidget,
    );
    expect(find.text('3'), findsOneWidget);
  });

  testWidgets('host-backed session drawer shows recent message time labels', (
    tester,
  ) async {
    final DateTime now = DateTime.now().toLocal();
    final DateTime todayAt = DateTime(now.year, now.month, now.day, 14, 32);
    final DateTime yesterdayAt = todayAt.subtract(const Duration(days: 1));
    final DateTime weekdayAt = todayAt.subtract(const Duration(days: 3));
    final DateTime olderAt = todayAt.subtract(const Duration(days: 12));
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        drawer: OpenCrayChatDrawerSnapshot(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: <OpenCrayChatSessionItemSnapshot>[
            OpenCrayChatSessionItemSnapshot(
              sessionId: 'session-today',
              title: 'Today session',
              preview: 'A recent reply arrived.',
              meta: '2 messages',
              isSelected: false,
              lastMessageAtEpochMs: todayAt.millisecondsSinceEpoch,
            ),
            OpenCrayChatSessionItemSnapshot(
              sessionId: 'session-yesterday',
              title: 'Yesterday session',
              preview: 'A reply arrived yesterday.',
              meta: '5 messages',
              isSelected: false,
              lastMessageAtEpochMs: yesterdayAt.millisecondsSinceEpoch,
            ),
            OpenCrayChatSessionItemSnapshot(
              sessionId: 'session-weekday',
              title: 'Weekday session',
              preview: 'A reply arrived earlier this week.',
              meta: '8 messages',
              isSelected: false,
              lastMessageAtEpochMs: weekdayAt.millisecondsSinceEpoch,
            ),
            OpenCrayChatSessionItemSnapshot(
              sessionId: 'session-older',
              title: 'Older session',
              preview: 'A reply arrived earlier this month.',
              meta: '13 messages',
              isSelected: false,
              lastMessageAtEpochMs: olderAt.millisecondsSinceEpoch,
            ),
          ],
        ),
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
            bridge: bridge,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('Sessions'));
    await tester.pumpAndSettle();

    expect(find.text('14:32'), findsOneWidget);
    expect(find.text('Yesterday'), findsOneWidget);
    expect(find.text(_weekdayLabelFor(weekdayAt)), findsOneWidget);
    expect(find.text(_dateLabelFor(olderAt, now: now)), findsOneWidget);
    expect(find.text('2 messages'), findsNothing);
    expect(find.text('5 messages'), findsNothing);
    expect(find.text('8 messages'), findsNothing);
    expect(find.text('13 messages'), findsNothing);
  });

  testWidgets(
    'host-backed session delete updates drawer immediately and ignores stale snapshots',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      addTearDown(snapshots.close);
      OpenCrayChatSnapshot snapshotWithDeletedSession({
        required int updatedAtEpochMs,
      }) {
        return _hostChatSnapshot(
          updatedAtEpochMs: updatedAtEpochMs,
          drawer: OpenCrayChatDrawerSnapshot(
            eyebrow: 'Recent sessions',
            title: 'Recent sessions',
            ctaLabel: 'New session',
            sessions: const <OpenCrayChatSessionItemSnapshot>[
              OpenCrayChatSessionItemSnapshot(
                sessionId: 'session-delete',
                title: 'Delete session',
                preview: 'This session will be removed.',
                meta: '2 messages',
                isSelected: true,
              ),
              OpenCrayChatSessionItemSnapshot(
                sessionId: 'session-keep',
                title: 'Keep session',
                preview: 'This session should remain.',
                meta: '1 message',
                isSelected: false,
              ),
            ],
          ),
        );
      }

      final bridge = _FakeChatBridge(
        chatSnapshot: snapshotWithDeletedSession(updatedAtEpochMs: 1000),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-delete',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        chatSnapshotStream: snapshots.stream,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('Sessions'));
      await tester.pumpAndSettle();
      expect(find.text('Delete session'), findsOneWidget);
      expect(find.text('Keep session'), findsOneWidget);

      await tester.longPress(find.text('Delete session'));
      await tester.pumpAndSettle();
      await tester.tap(find.text(copy.filesDeleteAction).last);
      await tester.pumpAndSettle();

      expect(bridge.deletedSessionIds, <String>['session-delete']);
      expect(find.text('Delete session'), findsNothing);
      expect(find.text('Keep session'), findsOneWidget);

      snapshots.add(snapshotWithDeletedSession(updatedAtEpochMs: 2000));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.text('Delete session'), findsNothing);
      expect(find.text('Keep session'), findsOneWidget);
    },
  );

  testWidgets(
    'new session drawer action waits for host creation before closing the drawer',
    (tester) async {
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
      );
      bridge.createChatSessionCompleter = Completer<void>();

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('Sessions'));
      await tester.pumpAndSettle();
      expect(find.text('New session'), findsOneWidget);

      await tester.tap(find.text('New session'));
      await tester.pump();

      expect(bridge.createChatSessionCallCount, 1);
      expect(find.text('New session'), findsOneWidget);

      bridge.createChatSessionCompleter!.complete();
      await tester.pump();
      await tester.pump();

      expect(bridge.createChatSessionCallCount, 1);
      expect(find.text('New session'), findsNothing);
    },
  );

  testWidgets(
    'runtime environment selector uses English labels, shows icons, and saves cloud selection',
    (tester) async {
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'local',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Local'), findsOneWidget);

      await tester.tap(
        find.byKey(const ValueKey<String>('chat-runtime-environment-selector')),
      );
      await tester.pumpAndSettle();

      expect(find.text('Run locally'), findsOneWidget);
      expect(find.text('Run in cloud'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-runtime-menu-icon-local')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-runtime-menu-icon-cloud')),
        findsOneWidget,
      );

      await tester.tap(find.text('Run in cloud'));
      await tester.pumpAndSettle();

      expect(bridge.savedSandboxSettings.single.defaultBackend, 'sandbox');
      expect(find.text('Cloud'), findsOneWidget);
    },
  );

  testWidgets(
    'cloud mode auto refreshes sandbox session info after cloud runtime activity',
    (tester) async {
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-1',
              taskId: 'task-cloud-1',
              emittedAtEpochMs: 4200,
              toolName: 'python_exec',
              resultMetadata: const <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'sandbox',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(bridge.refreshSandboxSessionInfoCallCount, 0);

      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);
    },
  );

  testWidgets('local mode does not auto refresh sandbox session info', (
    tester,
  ) async {
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-cloud-1',
            taskId: 'task-cloud-1',
            emittedAtEpochMs: 4200,
            toolName: 'python_exec',
            resultMetadata: const <String, String>{'sandboxProvider': 'e2b'},
          ),
        ],
      ),
      sandboxSettings: const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: true,
        providerId: 'e2b',
        defaultBackend: 'local',
        sessionMode: 'ephemeral',
        autoResume: false,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: 'e2b_demo',
        apiKeyConfigured: true,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(
            copy: OpenCrayUiCopy.fromLocaleTag('en'),
            bridge: bridge,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.pump(chatSandboxSessionAutoRefreshDebounce * 2);
    await tester.pump();

    expect(bridge.refreshSandboxSessionInfoCallCount, 0);
  });

  testWidgets(
    'cloud mode auto refreshes sandbox session info from lifecycle metadata',
    (tester) async {
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-session-info-1',
              taskId: 'task-session-info-1',
              emittedAtEpochMs: 4200,
              toolName: 'sandbox_session_info',
              resultMetadata: const <String, String>{
                'sandboxProvider': 'e2b',
                'sandboxSessionPresent': 'true',
                'sandboxSessionSource': 'active_memory',
                'sandboxSessionLifecycleStatus': 'active',
                'sandboxSessionAutoRefreshAfterMs': '1200',
              },
            ),
          ],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'sandbox',
          sessionMode: 'sticky',
          autoResume: true,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(bridge.refreshSandboxSessionInfoCallCount, 0);

      await tester.pump(const Duration(milliseconds: 1200));
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);
    },
  );

  testWidgets(
    'local mode does not auto refresh sandbox session info from lifecycle metadata',
    (tester) async {
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-session-info-local',
              taskId: 'task-session-info-local',
              emittedAtEpochMs: 4200,
              toolName: 'sandbox_session_info',
              resultMetadata: const <String, String>{
                'sandboxProvider': 'e2b',
                'sandboxSessionPresent': 'true',
                'sandboxSessionSource': 'active_memory',
                'sandboxSessionLifecycleStatus': 'active',
                'sandboxSessionAutoRefreshAfterMs': '1200',
              },
            ),
          ],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'local',
          sessionMode: 'sticky',
          autoResume: true,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.pump(const Duration(milliseconds: 2400));
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 0);
    },
  );

  testWidgets(
    'switching from local to cloud triggers sandbox session auto refresh',
    (tester) async {
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-1',
              taskId: 'task-cloud-1',
              emittedAtEpochMs: 4200,
              toolName: 'python_exec',
              resultMetadata: const <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'local',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(bridge.refreshSandboxSessionInfoCallCount, 0);

      await tester.tap(
        find.byKey(const ValueKey<String>('chat-runtime-environment-selector')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Run in cloud'));
      await tester.pumpAndSettle();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);
    },
  );

  testWidgets(
    'switching to cloud skips auto refresh when newer sandbox session info already exists',
    (tester) async {
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: const <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-1',
              taskId: 'task-cloud-1',
              emittedAtEpochMs: 4300,
              toolName: 'sandbox_session_info',
            ),
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-1',
              taskId: 'task-cloud-1',
              emittedAtEpochMs: 4200,
              toolName: 'python_exec',
              resultMetadata: const <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'local',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(bridge.refreshSandboxSessionInfoCallCount, 0);

      await tester.tap(
        find.byKey(const ValueKey<String>('chat-runtime-environment-selector')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Run in cloud'));
      await tester.pumpAndSettle();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce * 2);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 0);
    },
  );

  testWidgets(
    'sandbox session auto refresh does not loop on the same anchor after failure',
    (tester) async {
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final runtimeSnapshot = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: const <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-cloud-1',
            taskId: 'task-cloud-1',
            emittedAtEpochMs: 4200,
            toolName: 'python_exec',
            resultMetadata: const <String, String>{'sandboxProvider': 'e2b'},
          ),
        ],
      );
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: runtimeSnapshot,
        runtimeSnapshotStream: runtimeSnapshots.stream,
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'sandbox',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );
      bridge.refreshSandboxSessionInfoError = StateError('refresh failed');

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);

      runtimeSnapshots.add(runtimeSnapshot);
      await tester.pumpAndSettle();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce * 2);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);
    },
  );

  testWidgets(
    'sandbox session auto refresh drains queued anchors after an in-flight refresh',
    (tester) async {
      final runtimeSnapshots =
          StreamController<OpenCrayChatRuntimeSnapshot>.broadcast();
      addTearDown(runtimeSnapshots.close);
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-1',
              taskId: 'task-cloud-1',
              emittedAtEpochMs: 4200,
              toolName: 'python_exec',
              resultMetadata: <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
        runtimeSnapshotStream: runtimeSnapshots.stream,
        sandboxSettings: const OpenCraySandboxSettingsSnapshot(
          localeTag: 'en',
          enabled: true,
          providerId: 'e2b',
          defaultBackend: 'sandbox',
          sessionMode: 'ephemeral',
          autoResume: false,
          idleTimeoutMinutes: 15,
          startupTimeoutMs: 30000,
          requestTimeoutMs: 300000,
          timeoutAction: 'kill',
          templateId: '',
          e2bApiKey: 'e2b_demo',
          apiKeyConfigured: true,
        ),
      );
      final firstRefreshCompleter = Completer<void>();
      final secondRefreshCompleter = Completer<void>();
      final thirdRefreshCompleter = Completer<void>();
      bridge.refreshSandboxSessionInfoCompleter = firstRefreshCompleter;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: OpenCrayUiCopy.fromLocaleTag('en'),
              bridge: bridge,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 1);

      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-2',
              taskId: 'task-cloud-2',
              emittedAtEpochMs: 4300,
              toolName: 'python_exec',
              resultMetadata: <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();
      runtimeSnapshots.add(
        const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[
            OpenCrayChatRuntimeEventSnapshot(
              kind: 'tool_result',
              runId: 'run-cloud-3',
              taskId: 'task-cloud-3',
              emittedAtEpochMs: 4400,
              toolName: 'python_exec',
              resultMetadata: <String, String>{'sandboxProvider': 'e2b'},
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      bridge.refreshSandboxSessionInfoCompleter = secondRefreshCompleter;
      firstRefreshCompleter.complete();
      await tester.pump();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 2);

      bridge.refreshSandboxSessionInfoCompleter = thirdRefreshCompleter;
      secondRefreshCompleter.complete();
      await tester.pump();
      await tester.pump(chatSandboxSessionAutoRefreshDebounce);
      await tester.pump();

      expect(bridge.refreshSandboxSessionInfoCallCount, 3);

      thirdRefreshCompleter.complete();
      await tester.pumpAndSettle();
    },
  );

  testWidgets(
    'host message renders image, voice, and file attachments in one bubble',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final playbackLog = _FakeVoicePlaybackLog();
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'assistant-media',
              kind: 'inbound',
              text: 'Here are the generated assets.',
              attachments: const <OpenCrayChatAttachmentSnapshot>[
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'image-1',
                  kind: 'image',
                  displayName: 'diagram-a.png',
                  localPath:
                      '.opencray/chat-media/session-1/hash-a/diagram-a.png',
                ),
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'image-2',
                  kind: 'image',
                  displayName: 'diagram-b.png',
                  localPath:
                      '.opencray/chat-media/session-1/hash-b/diagram-b.png',
                ),
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'voice-1',
                  kind: 'voice',
                  displayName: 'voice-note.m4a',
                  localPath:
                      '.opencray/chat-media/session-1/hash-c/voice-note.m4a',
                  durationMs: 4200,
                ),
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'file-1',
                  kind: 'file',
                  displayName: 'report.pdf',
                  localPath: '.opencray/chat-media/session-1/hash-d/report.pdf',
                  sizeBytes: 4096,
                ),
              ],
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        imagePreviews: <String, OpenCrayFileImagePreview>{
          '.opencray/chat-media/session-1/hash-a/diagram-a.png':
              _fakeImagePreview(
                name: 'diagram-a.png',
                relativePath:
                    '.opencray/chat-media/session-1/hash-a/diagram-a.png',
              ),
          '.opencray/chat-media/session-1/hash-b/diagram-b.png':
              _fakeImagePreview(
                name: 'diagram-b.png',
                relativePath:
                    '.opencray/chat-media/session-1/hash-b/diagram-b.png',
              ),
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: copy,
              bridge: bridge,
              voicePlaybackControllerFactory: () =>
                  _FakeVoicePlaybackController(playbackLog),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-bubble-assistant-media')),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-message-image-group-assistant-media'),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-message-image-attachment-image-1'),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-message-image-attachment-image-2'),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-message-attachment-voice-1')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-message-attachment-file-1')),
        findsOneWidget,
      );
      expect(find.text('Here are the generated assets.'), findsOneWidget);
      expect(find.text('voice-note.m4a'), findsOneWidget);
      expect(find.text('report.pdf'), findsOneWidget);
    },
  );

  testWidgets(
    'host message renders attachment markdown inline without duplicating bottom attachments',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final playbackLog = _FakeVoicePlaybackLog();
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'assistant-inline-media',
              kind: 'inbound',
              text:
                  'Here is the image inline.\n\n![diagram-a.png](attachment:image-inline-1)\n\nAnd the file card inline.\n\n[report.pdf](attachment:file-inline-1)\n\nDone.',
              attachments: const <OpenCrayChatAttachmentSnapshot>[
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'image-inline-1',
                  kind: 'image',
                  displayName: 'diagram-a.png',
                  localPath:
                      '.opencray/chat-media/session-1/hash-inline-a/diagram-a.png',
                ),
                OpenCrayChatAttachmentSnapshot(
                  attachmentId: 'file-inline-1',
                  kind: 'file',
                  displayName: 'report.pdf',
                  localPath:
                      '.opencray/chat-media/session-1/hash-inline-b/report.pdf',
                  sizeBytes: 4096,
                ),
              ],
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        imagePreviews: <String, OpenCrayFileImagePreview>{
          '.opencray/chat-media/session-1/hash-inline-a/diagram-a.png':
              _fakeImagePreview(
                name: 'diagram-a.png',
                relativePath:
                    '.opencray/chat-media/session-1/hash-inline-a/diagram-a.png',
              ),
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(
              copy: copy,
              bridge: bridge,
              voicePlaybackControllerFactory: () =>
                  _FakeVoicePlaybackController(playbackLog),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(
          const ValueKey<String>('chat-bubble-assistant-inline-media'),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-message-image-group-assistant-inline-media',
          ),
        ),
        findsNothing,
      );
      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-message-image-attachment-image-inline-1',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-message-attachment-file-inline-1'),
        ),
        findsOneWidget,
      );
      expect(find.text('Here is the image inline.'), findsOneWidget);
      expect(find.text('And the file card inline.'), findsOneWidget);
      expect(find.text('Done.'), findsOneWidget);
    },
  );

  testWidgets('host attachment tiles open workspace files on tap', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final playbackLog = _FakeVoicePlaybackLog();
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: <OpenCrayChatMessageSnapshot>[
          const OpenCrayChatMessageSnapshot(
            messageId: 'assistant-open-media',
            kind: 'inbound',
            text: '',
            attachments: <OpenCrayChatAttachmentSnapshot>[
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'voice-open-1',
                kind: 'voice',
                displayName: 'voice-note.m4a',
                localPath:
                    '.opencray/chat-media/session-1/hash-c/voice-note.m4a',
                durationMs: 4200,
              ),
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'file-open-1',
                kind: 'file',
                displayName: 'report.pdf',
                localPath: '.opencray/chat-media/session-1/hash-d/report.pdf',
                sizeBytes: 4096,
              ),
            ],
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      voicePlaybackSources: <String, OpenCrayFileVoicePlaybackSource>{
        '.opencray/chat-media/session-1/hash-c/voice-note.m4a':
            const OpenCrayFileVoicePlaybackSource(
              name: 'voice-note.m4a',
              relativePath:
                  '.opencray/chat-media/session-1/hash-c/voice-note.m4a',
              localFilePath: '/workspace/session-1/voice-note.m4a',
              mimeType: 'audio/mp4',
              durationMs: 4200,
            ),
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(
            copy: copy,
            bridge: bridge,
            voicePlaybackControllerFactory: () =>
                _FakeVoicePlaybackController(playbackLog),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(
        const ValueKey<String>('chat-message-attachment-voice-open-1'),
      ),
    );
    await tester.pump();
    await tester.tap(
      find.byKey(const ValueKey<String>('chat-message-attachment-file-open-1')),
    );
    await tester.pump();

    expect(bridge.loadedVoicePlaybackSources, <String>[
      '.opencray/chat-media/session-1/hash-c/voice-note.m4a',
    ]);
    expect(playbackLog.sourcePaths, <String>[
      '/workspace/session-1/voice-note.m4a',
    ]);
    expect(playbackLog.playCount, 1);
    expect(bridge.openedWorkspaceEntries, <String>[
      '.opencray/chat-media/session-1/hash-d/report.pdf',
    ]);
  });

  testWidgets('assistant message bubbles render markdown emphasis', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildChatHarness(
        messages: const <ChatMessageData>[
          ChatMessageData(
            messageId: 'assistant-markdown-bold',
            kind: ChatMessageKind.inbound,
            text: 'Alpha **Bold** Omega',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-bold'),
    );
    expect(bubbleFinder, findsOneWidget);

    final richTextFinder = find.descendant(
      of: bubbleFinder,
      matching: find.byWidgetPredicate((widget) {
        if (widget is! RichText) {
          return false;
        }
        return widget.text.toPlainText() == 'Alpha Bold Omega';
      }),
    );

    expect(richTextFinder, findsOneWidget);
    final RichText richText = tester.widget<RichText>(richTextFinder);
    final List<TextSpan> spans = _collectLeafTextSpans(richText.text);
    final TextSpan boldSpan = spans.firstWhere((span) => span.text == 'Bold');

    expect(boldSpan.style?.fontWeight, FontWeight.w700);
  });

  testWidgets(
    'assistant message bubbles render markdown tables inside scroll views',
    (tester) async {
      await tester.pumpWidget(
        _buildChatHarness(
          messages: const <ChatMessageData>[
            ChatMessageData(
              messageId: 'assistant-markdown-table',
              kind: ChatMessageKind.inbound,
              text:
                  '| Name | Value |\n'
                  '| --- | --- |\n'
                  '| Feature | Markdown table rendering |\n'
                  '| Scope | Chat bubbles |',
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-assistant-markdown-table'),
      );
      expect(bubbleFinder, findsOneWidget);
      expect(
        find.descendant(of: bubbleFinder, matching: find.byType(Table)),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: bubbleFinder,
          matching: find.byType(SingleChildScrollView),
        ),
        findsWidgets,
      );
    },
  );

  testWidgets('assistant message bubbles render latex formulas', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildChatHarness(
        messages: const <ChatMessageData>[
          ChatMessageData(
            messageId: 'assistant-markdown-formula',
            kind: ChatMessageKind.inbound,
            text: r'Quadratic root: $c = \pm\sqrt{a^2 + b^2}$',
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-formula'),
    );
    expect(bubbleFinder, findsOneWidget);
    expect(
      find.descendant(of: bubbleFinder, matching: find.byType(Math)),
      findsOneWidget,
    );
  });

  testWidgets('assistant message bubbles render workspace markdown images', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-markdown-image',
            kind: 'inbound',
            text: 'Diagram:\n\n![Architecture](docs/diagram.png)',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      imagePreviews: <String, OpenCrayFileImagePreview>{
        'docs/diagram.png': _fakeImagePreview(
          name: 'diagram.png',
          relativePath: 'docs/diagram.png',
        ),
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-image'),
    );
    expect(bubbleFinder, findsOneWidget);
    expect(
      find.descendant(of: bubbleFinder, matching: find.byType(Image)),
      findsOneWidget,
    );
  });

  testWidgets('run inspector renders markdown images from tool results', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    const toolCall = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_call',
      runId: 'run-inspector-markdown-image',
      taskId: 'task-inspector-markdown-image',
      emittedAtEpochMs: 1000,
      toolName: 'Read',
      argumentsJson: '{"file_path":"docs/report.md"}',
    );
    const toolResult = OpenCrayChatRuntimeEventSnapshot(
      kind: 'tool_result',
      runId: 'run-inspector-markdown-image',
      taskId: 'task-inspector-markdown-image',
      emittedAtEpochMs: 2000,
      toolName: 'Read',
      content: 'Preview image:\n\n![Diagram](docs/diagram.png)',
      contentPreview: 'Preview image:\n\n![Diagram](docs/diagram.png)',
      resultMetadata: <String, String>{'filePath': 'docs/report.md'},
    );
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-inspector-markdown-image',
            taskId: 'task-inspector-markdown-image',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2000,
            attempt: 1,
            isTerminal: false,
            lastEvent: toolResult,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-inspector-markdown-image',
            taskId: 'task-inspector-markdown-image',
            emittedAtEpochMs: 900,
            phase: 'start',
          ),
          toolCall,
          toolResult,
        ],
      ),
      imagePreviews: <String, OpenCrayFileImagePreview>{
        'docs/diagram.png': _fakeImagePreview(
          name: 'diagram.png',
          relativePath: 'docs/diagram.png',
        ),
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-inspector-markdown-image'),
    );
    expect(bubbleFinder, findsOneWidget);
    await _openRunTraceFullscreen(tester, bubbleFinder);

    final fullscreenFinder = find.byKey(
      const ValueKey<String>(
        'chat-run-trace-fullscreen-run-inspector-markdown-image',
      ),
    );
    expect(fullscreenFinder, findsOneWidget);
    expect(
      find.descendant(of: fullscreenFinder, matching: find.byType(Image)),
      findsOneWidget,
    );
  });

  testWidgets(
    'assistant message markdown images open the shared preview dialog',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'assistant-markdown-image-preview',
              kind: 'inbound',
              text: 'Diagram:\n\n![Architecture](docs/diagram.png)',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
        imagePreviews: <String, OpenCrayFileImagePreview>{
          'docs/diagram.png': _fakeImagePreview(
            name: 'diagram.png',
            relativePath: 'docs/diagram.png',
          ),
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-assistant-markdown-image-preview'),
      );
      await tester.tap(
        find.descendant(
          of: bubbleFinder,
          matching: find.byKey(
            const ValueKey<String>('opencray-markdown-image-tappable'),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(
          const ValueKey<String>('opencray-markdown-image-preview-dialog'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('assistant message markdown links preview workspace text files', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-markdown-link-preview',
            kind: 'inbound',
            text: 'Open [report.md](docs/report.md)',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      textPreviews: <String, OpenCrayFileTextPreview>{
        'docs/report.md': const OpenCrayFileTextPreview(
          name: 'report.md',
          relativePath: 'docs/report.md',
          content: '# Report\n\nPreview body',
          isTruncated: false,
        ),
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-link-preview'),
    );
    _activateRichTextLink(
      tester,
      find.descendant(
        of: bubbleFinder,
        matching: _findRichTextWithPlainText('Open report.md'),
      ),
    );
    await tester.pumpAndSettle();

    expect(bridge.loadedTextPreviews, <String>['docs/report.md']);
    expect(bridge.openedWorkspaceEntries, isEmpty);
    expect(bridge.openedExternalUris, isEmpty);
    expect(
      find.byKey(const ValueKey<String>('chat-text-preview-dialog')),
      findsOneWidget,
    );
    expect(find.textContaining('Preview body'), findsOneWidget);
  });

  testWidgets(
    'assistant message markdown links open non-preview workspace files',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          messages: const <OpenCrayChatMessageSnapshot>[
            OpenCrayChatMessageSnapshot(
              messageId: 'assistant-markdown-link-file',
              kind: 'inbound',
              text: 'Open [report.pdf](docs/report.pdf)',
            ),
          ],
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-bubble-assistant-markdown-link-file'),
      );
      _activateRichTextLink(
        tester,
        find.descendant(
          of: bubbleFinder,
          matching: _findRichTextWithPlainText('Open report.pdf'),
        ),
      );
      await tester.pumpAndSettle();

      expect(bridge.loadedTextPreviews, isEmpty);
      expect(bridge.openedWorkspaceEntries, <String>['docs/report.pdf']);
      expect(bridge.openedExternalUris, isEmpty);
    },
  );

  testWidgets('assistant message markdown links open external uris', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-markdown-link-external',
            kind: 'inbound',
            text: 'Visit [OpenCray docs](https://opencray.dev/docs)',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-link-external'),
    );
    _activateRichTextLink(
      tester,
      find.descendant(
        of: bubbleFinder,
        matching: _findRichTextWithPlainText('Visit OpenCray docs'),
      ),
    );
    await tester.pumpAndSettle();

    expect(bridge.loadedTextPreviews, isEmpty);
    expect(bridge.openedWorkspaceEntries, isEmpty);
    expect(bridge.openedExternalUris, <String>['https://opencray.dev/docs']);
  });

  testWidgets('assistant message markdown links open internal settings routes', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-markdown-link-settings',
            kind: 'inbound',
            text:
                'Remote LLM is not ready. Open [Settings -> LLM](/settings/llm).',
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        routes: <String, WidgetBuilder>{
          '/settings/llm': (_) =>
              const Scaffold(body: Text('LLM Settings Screen')),
        },
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-bubble-assistant-markdown-link-settings'),
    );
    _activateRichTextLink(
      tester,
      find.descendant(
        of: bubbleFinder,
        matching: _findRichTextWithPlainText(
          'Remote LLM is not ready. Open Settings -> LLM.',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('LLM Settings Screen'), findsOneWidget);
    expect(bridge.loadedTextPreviews, isEmpty);
    expect(bridge.openedWorkspaceEntries, isEmpty);
    expect(bridge.openedExternalUris, isEmpty);
  });

  testWidgets('voice attachment waveform seeks and transcript expands', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final playbackLog = _FakeVoicePlaybackLog();
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-voice-enhanced',
            kind: 'inbound',
            text: '',
            attachments: <OpenCrayChatAttachmentSnapshot>[
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'voice-enhanced-1',
                kind: 'voice',
                displayName: 'voice-note.m4a',
                localPath:
                    '.opencray/chat-media/session-1/hash-e/voice-note.m4a',
                durationMs: 4200,
                waveformBars: <int>[12, 28, 56, 72, 40, 88],
                transcriptText:
                    'Line one explains the change.\nLine two adds more detail about the update.',
              ),
            ],
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      voicePlaybackSources: <String, OpenCrayFileVoicePlaybackSource>{
        '.opencray/chat-media/session-1/hash-e/voice-note.m4a':
            const OpenCrayFileVoicePlaybackSource(
              name: 'voice-note.m4a',
              relativePath:
                  '.opencray/chat-media/session-1/hash-e/voice-note.m4a',
              localFilePath: '/workspace/session-1/voice-note.m4a',
              mimeType: 'audio/mp4',
              durationMs: 4200,
            ),
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(
            copy: copy,
            bridge: bridge,
            voicePlaybackControllerFactory: () =>
                _FakeVoicePlaybackController(playbackLog),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final transcriptFinder = find.byKey(
      const ValueKey<String>(
        'chat-message-attachment-transcript-voice-enhanced-1',
      ),
    );
    final toggleFinder = find.byKey(
      const ValueKey<String>(
        'chat-message-attachment-transcript-toggle-voice-enhanced-1',
      ),
    );
    final waveformFinder = find.byKey(
      const ValueKey<String>(
        'chat-message-attachment-waveform-voice-enhanced-1',
      ),
    );

    expect(waveformFinder, findsOneWidget);
    expect(toggleFinder, findsOneWidget);
    expect((tester.widget<Text>(transcriptFinder)).maxLines, 2);

    final Rect waveformRect = tester.getRect(waveformFinder);
    final TestGesture gesture = await tester.startGesture(
      Offset(waveformRect.left + 4, waveformRect.center.dy),
    );
    await gesture.moveTo(
      Offset(
        waveformRect.left + waveformRect.width * 0.75,
        waveformRect.center.dy,
      ),
    );
    await gesture.up();
    await tester.pump();

    expect(bridge.loadedVoicePlaybackSources, <String>[
      '.opencray/chat-media/session-1/hash-e/voice-note.m4a',
    ]);
    expect(playbackLog.sourcePaths, <String>[
      '/workspace/session-1/voice-note.m4a',
    ]);
    expect(
      playbackLog.seekPositions.last.inMilliseconds,
      inInclusiveRange(2800, 3400),
    );

    await tester.tap(toggleFinder);
    await tester.pump();

    expect((tester.widget<Text>(transcriptFinder)).maxLines, isNull);
    expect(find.text('Hide transcript'), findsOneWidget);
  });

  testWidgets('text file attachments open an internal preview on tap', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'assistant-text-preview',
            kind: 'inbound',
            text: '',
            attachments: <OpenCrayChatAttachmentSnapshot>[
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'file-preview-1',
                kind: 'file',
                displayName: 'report.md',
                localPath: '.opencray/chat-media/session-1/hash-d/report.md',
                sizeBytes: 128,
              ),
            ],
          ),
        ],
      ),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      textPreviews: <String, OpenCrayFileTextPreview>{
        '.opencray/chat-media/session-1/hash-d/report.md':
            const OpenCrayFileTextPreview(
              name: 'report.md',
              relativePath: '.opencray/chat-media/session-1/hash-d/report.md',
              content: '# Report\n\nPreview body',
              isTruncated: false,
            ),
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(
        const ValueKey<String>('chat-message-attachment-file-preview-1'),
      ),
    );
    await tester.pumpAndSettle();

    expect(bridge.loadedTextPreviews, <String>[
      '.opencray/chat-media/session-1/hash-d/report.md',
    ]);
    expect(bridge.openedWorkspaceEntries, isEmpty);
    expect(
      find.byKey(const ValueKey<String>('chat-text-preview-dialog')),
      findsOneWidget,
    );
    expect(find.textContaining('Preview body'), findsOneWidget);
  });

  testWidgets('composer hides todo chrome when todo list is empty', (
    tester,
  ) async {
    await tester.pumpWidget(_buildChatHarness());
    await tester.pumpAndSettle();

    expect(find.text('TODO'), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-surface')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsNothing,
    );
  });

  testWidgets(
    'composer renders todo glass surface with approved status styling',
    (tester) async {
      await tester.pumpWidget(
        _buildChatHarness(
          todos: const <ChatTodoItemData>[
            ChatTodoItemData(
              content: 'Review chat composer layout',
              status: ChatTodoStatus.pending,
            ),
            ChatTodoItemData(
              content: 'Highlight active todo text',
              status: ChatTodoStatus.inProgress,
              activeForm: 'Highlighting active todo text',
            ),
            ChatTodoItemData(
              content: 'Approve Pencil prototype',
              status: ChatTodoStatus.completed,
            ),
            ChatTodoItemData(
              content: 'Ship Flutter implementation',
              status: ChatTodoStatus.pending,
            ),
            ChatTodoItemData(
              content: 'Verify scrolling for overflow',
              status: ChatTodoStatus.pending,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('chat-composer-todo-surface')),
        findsOneWidget,
      );
      expect(find.text('TODO'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
        findsOneWidget,
      );

      final Size listSize = tester.getSize(
        find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      );
      expect(listSize.height, 130);

      final Text activeText = tester.widget<Text>(
        find.byKey(const ValueKey<String>('chat-composer-todo-text-1')),
      );
      final Text completedText = tester.widget<Text>(
        find.byKey(const ValueKey<String>('chat-composer-todo-text-2')),
      );
      final Container pendingIndicator = tester.widget<Container>(
        find.byKey(const ValueKey<String>('chat-composer-todo-indicator-0')),
      );
      final Container activeIndicator = tester.widget<Container>(
        find.byKey(const ValueKey<String>('chat-composer-todo-indicator-1')),
      );
      final Container completedIndicator = tester.widget<Container>(
        find.byKey(const ValueKey<String>('chat-composer-todo-indicator-2')),
      );

      final BoxDecoration pendingDecoration =
          pendingIndicator.decoration! as BoxDecoration;
      final BoxDecoration activeDecoration =
          activeIndicator.decoration! as BoxDecoration;
      final BoxDecoration completedDecoration =
          completedIndicator.decoration! as BoxDecoration;

      expect(find.text('Highlighting active todo text'), findsOneWidget);
      expect(activeText.style?.color, const Color(0xFF007AFF));
      expect(completedText.style?.decoration, TextDecoration.lineThrough);
      expect(pendingDecoration.color, Colors.transparent);
      expect(
        (pendingDecoration.border! as Border).top.color,
        const Color(0xFFD7D7DC),
      );
      expect(activeDecoration.color, Colors.transparent);
      expect(
        (activeDecoration.border! as Border).top.color,
        const Color(0xFF007AFF),
      );
      expect(completedDecoration.color, const Color(0xFFB8BDC7));
    },
  );

  testWidgets('composer todo list expands and collapses from the header', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildChatHarness(
        todos: const <ChatTodoItemData>[
          ChatTodoItemData(
            content: 'Review chat composer layout',
            status: ChatTodoStatus.pending,
          ),
          ChatTodoItemData(
            content: 'Highlight active todo text',
            status: ChatTodoStatus.inProgress,
            activeForm: 'Highlighting active todo text',
          ),
          ChatTodoItemData(
            content: 'Approve Pencil prototype',
            status: ChatTodoStatus.completed,
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsOneWidget,
    );
    AnimatedRotation rotation = tester.widget<AnimatedRotation>(
      find.descendant(
        of: find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
        matching: find.byType(AnimatedRotation),
      ),
    );
    expect(rotation.turns, 0.5);
    expect(find.text('Review chat composer layout'), findsOneWidget);
    expect(find.text('Highlighting active todo text'), findsOneWidget);
    expect(find.text('Approve Pencil prototype'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
    );
    await tester.pumpAndSettle();

    expect(find.text('TODO'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsNothing,
    );
    rotation = tester.widget<AnimatedRotation>(
      find.descendant(
        of: find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
        matching: find.byType(AnimatedRotation),
      ),
    );
    expect(rotation.turns, 0);
    expect(find.text('Review chat composer layout'), findsNothing);
    expect(find.text('Highlighting active todo text'), findsNothing);
    expect(find.text('Approve Pencil prototype'), findsNothing);

    await tester.tap(
      find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('chat-composer-todo-list')),
      findsOneWidget,
    );
    rotation = tester.widget<AnimatedRotation>(
      find.descendant(
        of: find.byKey(const ValueKey<String>('chat-composer-todo-chevron')),
        matching: find.byType(AnimatedRotation),
      ),
    );
    expect(rotation.turns, 0.5);
    expect(find.text('Review chat composer layout'), findsOneWidget);
    expect(find.text('Highlighting active todo text'), findsOneWidget);
    expect(find.text('Approve Pencil prototype'), findsOneWidget);
  });

  testWidgets(
    'archived completed todos auto-hide after the visibility window',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = _FakeChatBridge(
        chatSnapshot: _hostChatSnapshot(
          todos: const <OpenCrayChatTodoSnapshot>[
            OpenCrayChatTodoSnapshot(
              content: 'Review chat composer layout',
              status: 'completed',
            ),
            OpenCrayChatTodoSnapshot(
              content: 'Ship Flutter implementation',
              status: 'completed',
            ),
          ],
          todoState: 'archived_completed',
          todoHideDelayMs: 4000,
          todoCompletedAtEpochMs: 1700000003000,
        ),
        runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
          sessionId: 'session-1',
          activeRuns: <OpenCrayChatRunSnapshot>[],
          events: <OpenCrayChatRuntimeEventSnapshot>[],
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: OpenCrayChatFeature(copy: copy, bridge: bridge),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('TODO'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-composer-todo-list')),
        findsOneWidget,
      );

      await tester.pump(const Duration(seconds: 4));
      await tester.pump();

      expect(find.text('TODO'), findsNothing);
      expect(
        find.byKey(const ValueKey<String>('chat-composer-todo-list')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey<String>('chat-composer-todo-surface')),
        findsNothing,
      );
    },
  );

  testWidgets('composer picks and submits attachments without requiring text', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.pickedAttachmentsByKind[OpenCrayChatDraftAttachmentKind.image] =
        <OpenCrayChatDraftAttachment>[
          const OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.image,
            displayName: 'workspace-shot.png',
            relativePath: '.opencray/chat-drafts/workspace-shot.png',
            mimeType: 'image/png',
            sizeBytes: 2048,
          ),
        ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionImage));
    await tester.pumpAndSettle();

    expect(find.text('workspace-shot.png'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
    await tester.pumpAndSettle();

    expect(bridge.submittedMessages, <String>['']);
    expect(bridge.submittedAttachments, hasLength(1));
    expect(
      bridge.submittedAttachments.single.single.relativePath,
      '.opencray/chat-drafts/workspace-shot.png',
    );
    expect(find.text('workspace-shot.png'), findsNothing);
  });

  testWidgets('composer image attachments render a thumbnail preview card', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
      imagePreviews: <String, OpenCrayFileImagePreview>{
        '.opencray/chat-drafts/workspace-shot.png': _fakeImagePreview(
          name: 'workspace-shot.png',
          relativePath: '.opencray/chat-drafts/workspace-shot.png',
        ),
      },
    );
    bridge.pickedAttachmentsByKind[OpenCrayChatDraftAttachmentKind.image] =
        <OpenCrayChatDraftAttachment>[
          const OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.image,
            displayName: 'workspace-shot.png',
            relativePath: '.opencray/chat-drafts/workspace-shot.png',
            mimeType: 'image/png',
            sizeBytes: 2048,
          ),
        ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionImage));
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>(
          'chat-composer-image-preview-.opencray/chat-drafts/workspace-shot.png',
        ),
      ),
      findsOneWidget,
    );
  });

  testWidgets('composer deduplicates repeated attachments with feedback', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.pickedAttachmentsByKind[OpenCrayChatDraftAttachmentKind.file] =
        <OpenCrayChatDraftAttachment>[
          const OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.file,
            displayName: 'report.md',
            relativePath: '.opencray/chat-drafts/hash-a/report.md',
            mimeType: 'text/markdown',
            sizeBytes: 512,
          ),
          const OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.file,
            displayName: 'report.md',
            relativePath: '.opencray/chat-drafts/hash-a/report.md',
            mimeType: 'text/markdown',
            sizeBytes: 512,
          ),
        ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionFile));
    await tester.pumpAndSettle();

    expect(find.text('report.md'), findsOneWidget);
    expect(
      bridge.shownNativeToasts,
      contains('Ignored 1 duplicate attachment.'),
    );
  });

  testWidgets('composer enforces the image limit with feedback', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.pickedAttachmentsByKind[OpenCrayChatDraftAttachmentKind
        .image] = List<OpenCrayChatDraftAttachment>.generate(10, (int index) {
      final imageNumber = index + 1;
      return OpenCrayChatDraftAttachment(
        kind: OpenCrayChatDraftAttachmentKind.image,
        displayName: 'image-$imageNumber.png',
        relativePath:
            '.opencray/chat-drafts/hash-$imageNumber/image-$imageNumber.png',
        mimeType: 'image/png',
        sizeBytes: 1024 + imageNumber,
      );
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionImage));
    await tester.pumpAndSettle();

    expect(find.text('image-1.png'), findsOneWidget);
    expect(
      bridge.shownNativeToasts,
      contains('Each message supports up to 9 images. Skipped 1.'),
    );

    await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
    await tester.pumpAndSettle();

    expect(bridge.submittedAttachments.single, hasLength(9));
  });

  testWidgets('composer shows native feedback when attachment picking fails', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.pickChatAttachmentsError = StateError('picker failed');

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionFile));
    await tester.pumpAndSettle();

    expect(bridge.shownNativeToasts, contains('Unable to add attachment.'));
  });

  testWidgets('composer surfaces explicit attachment picking failures', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.pickChatAttachmentsError = UnsupportedError(
      'Adding attachments is unavailable in local runtime mode.',
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionFile));
    await tester.pumpAndSettle();

    expect(
      bridge.shownNativeToasts,
      contains('Adding attachments is unavailable in local runtime mode.'),
    );
  });

  testWidgets('composer shows native feedback when submit fails', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = _FakeChatBridge(
      chatSnapshot: _hostChatSnapshot(),
      runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      ),
    );
    bridge.submitChatMessageError = StateError('submit failed');
    bridge.pickedAttachmentsByKind[OpenCrayChatDraftAttachmentKind.file] =
        <OpenCrayChatDraftAttachment>[
          const OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.file,
            displayName: 'report.md',
            relativePath: '.opencray/chat-drafts/hash-a/report.md',
            mimeType: 'text/markdown',
            sizeBytes: 512,
          ),
        ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayChatFeature(copy: copy, bridge: bridge),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.text(copy.chatActionFile));
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
    await tester.pumpAndSettle();

    expect(bridge.shownNativeToasts, contains(copy.chatSubmitFailed));
  });
}

String _weekdayLabelFor(DateTime dateTime) {
  const List<String> weekdayLabels = <String>[
    'Mon',
    'Tue',
    'Wed',
    'Thu',
    'Fri',
    'Sat',
    'Sun',
  ];
  return weekdayLabels[dateTime.weekday - 1];
}

String _dateLabelFor(DateTime dateTime, {required DateTime now}) {
  const List<String> monthLabels = <String>[
    'Jan',
    'Feb',
    'Mar',
    'Apr',
    'May',
    'Jun',
    'Jul',
    'Aug',
    'Sep',
    'Oct',
    'Nov',
    'Dec',
  ];
  final String month = monthLabels[dateTime.month - 1];
  if (dateTime.year != now.year) {
    return '$month ${dateTime.day}, ${dateTime.year}';
  }
  return '$month ${dateTime.day}';
}

List<TextSpan> _collectLeafTextSpans(InlineSpan span) {
  if (span is! TextSpan) {
    return const <TextSpan>[];
  }
  final List<InlineSpan>? children = span.children;
  if (children == null || children.isEmpty) {
    return <TextSpan>[span];
  }
  return children
      .expand<TextSpan>(_collectLeafTextSpans)
      .toList(growable: false);
}

Finder _findRichTextWithPlainText(String text) =>
    find.byWidgetPredicate((widget) {
      if (widget is! RichText) {
        return false;
      }
      return widget.text.toPlainText() == text;
    });

void _activateRichTextLink(WidgetTester tester, Finder richTextFinder) {
  final RichText richText = tester.widget<RichText>(richTextFinder);
  final TapGestureRecognizer recognizer = _collectLeafTextSpans(
    richText.text,
  ).map((span) => span.recognizer).whereType<TapGestureRecognizer>().first;
  recognizer.onTap?.call();
}

Widget _buildChatHarness({
  List<ChatPendingApprovalData> pendingApprovals =
      const <ChatPendingApprovalData>[],
  List<ChatTodoItemData> todos = const <ChatTodoItemData>[],
  List<ChatMessageData>? messages,
  ChatSessionsDrawerState? drawer,
  bool drawerOpen = false,
}) {
  final copy = OpenCrayUiCopy.fromLocaleTag('en');
  final traceBody = List<String>.generate(
    40,
    (index) => 'Running line ${index + 1}: checking repository state.',
  ).join('\n');
  return MaterialApp(
    home: Scaffold(
      body: OpenCrayChatFeature(
        copy: copy,
        state: ChatFeatureState(
          variant: ChatPrototypeVariant.main,
          screenTitle: 'Chat',
          summary: const ChatSessionSummary(
            title: 'Session',
            badge: '1 message',
            body: 'Reply in progress',
          ),
          messages:
              messages ??
              const <ChatMessageData>[
                ChatMessageData(
                  kind: ChatMessageKind.outbound,
                  text: 'Inspect the workspace.',
                ),
              ],
          runTraces: <ChatRunTraceData>[
            ChatRunTraceData(
              runId: 'run-1',
              taskId: 'task-1',
              label: copy.chatRunWorkingLabel,
              body: traceBody,
              history: <ChatRunTraceHistoryEntry>[
                ChatRunTraceHistoryEntry(
                  label: copy.chatRunWorkingLabel,
                  body: copy.chatRunThinkingActive,
                ),
                ChatRunTraceHistoryEntry(
                  label: 'Read',
                  body:
                      'Read README.md lines 5-6\n  └ Project uses the Gradle wrapper from the repo root.\n  Use .\\\\gradlew.bat test to run JVM tests.',
                  inspectorCallParts: <ChatRunTraceInspectorTextPart>[
                    ChatRunTraceInspectorTextPart(
                      text: 'Read',
                      semantic: ChatRunTraceInspectorTextSemantic.action,
                    ),
                    ChatRunTraceInspectorTextPart(text: ' '),
                    ChatRunTraceInspectorTextPart(
                      text: 'README.md',
                      semantic: ChatRunTraceInspectorTextSemantic.target,
                    ),
                    ChatRunTraceInspectorTextPart(text: ' '),
                    ChatRunTraceInspectorTextPart(
                      text: 'lines 5-6',
                      semantic: ChatRunTraceInspectorTextSemantic.scope,
                    ),
                  ],
                  inspectorResultBody:
                      'Project uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
                ),
              ],
            ),
          ],
          pendingApprovals: pendingApprovals,
          composer: ChatComposerState(
            placeholder: copy.chatComposerPlaceholder,
            todos: todos,
          ),
          drawer:
              drawer ??
              const ChatSessionsDrawerState(
                eyebrow: 'Recent sessions',
                title: 'Recent sessions',
                ctaLabel: 'New session',
                sessions: <ChatSessionListItemData>[],
              ),
          drawerOpen: drawerOpen,
        ),
      ),
    ),
  );
}

Future<void> _openRunTraceFullscreen(
  WidgetTester tester,
  Finder bubbleFinder,
) async {
  final Iterable<GestureDetector> gestures = tester
      .widgetList<GestureDetector>(
        find.descendant(
          of: bubbleFinder,
          matching: find.byType(GestureDetector),
        ),
      )
      .where((gesture) => gesture.onDoubleTap != null);
  final GestureDetector detector = gestures.first;
  detector.onDoubleTap!.call();
  await tester.pumpAndSettle();
}

OpenCrayChatSnapshot _hostChatSnapshot({
  List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals =
      const <OpenCrayChatPendingApprovalSnapshot>[],
  List<OpenCrayChatTodoSnapshot> todos = const <OpenCrayChatTodoSnapshot>[],
  String todoState = 'empty',
  int? todoHideDelayMs,
  int? todoCompletedAtEpochMs,
  int updatedAtEpochMs = 0,
  List<OpenCrayChatMessageSnapshot>? messages,
  OpenCrayChatDrawerSnapshot? drawer,
  OpenCrayChatRuntimeSnapshot? runtimeActivity,
}) {
  return OpenCrayChatSnapshot(
    screenTitle: 'Chat',
    modeLabel: 'SAFE',
    sessionButtonLabel: 'Sessions',
    composerPlaceholder: 'Message OpenCray',
    summary: OpenCrayChatSummarySnapshot(
      title: 'Session',
      badge: '1 message',
      body: 'Reply in progress',
    ),
    messages:
        messages ??
        <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            kind: 'outbound',
            text: 'Inspect the workspace.',
          ),
        ],
    drawer:
        drawer ??
        OpenCrayChatDrawerSnapshot(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: <OpenCrayChatSessionItemSnapshot>[],
        ),
    isInputEnabled: true,
    todos: todos,
    todoState: todoState,
    todoHideDelayMs: todoHideDelayMs,
    todoCompletedAtEpochMs: todoCompletedAtEpochMs,
    pendingApprovals: pendingApprovals,
    runtimeActivity: runtimeActivity,
    updatedAtEpochMs: updatedAtEpochMs,
  );
}

double _topYForDescendantText(WidgetTester tester, Finder scope, String text) {
  final Finder finder = find.descendant(
    of: scope,
    matching: find.textContaining(text),
  );
  expect(finder, findsWidgets);
  return tester.getTopLeft(finder.first).dy;
}

class _FakeChatBridge implements OpenCrayHostBridge {
  _FakeChatBridge({
    required this.chatSnapshot,
    required this.runtimeSnapshot,
    this.imagePreviews = const <String, OpenCrayFileImagePreview>{},
    this.textPreviews = const <String, OpenCrayFileTextPreview>{},
    this.voicePlaybackSources =
        const <String, OpenCrayFileVoicePlaybackSource>{},
    this.chatSnapshotStream,
    this.runtimeSnapshotStream,
    this.liveAssistantDraftEventStream,
    this.runtimeEventDeltaStream,
    this.onRecallChatMessage,
    OpenCraySandboxSettingsSnapshot? sandboxSettings,
  }) : sandboxSettings =
           sandboxSettings ??
           const OpenCraySandboxSettingsSnapshot(
             localeTag: 'en',
             enabled: false,
             providerId: 'e2b',
             defaultBackend: 'local',
             sessionMode: 'ephemeral',
             autoResume: false,
             idleTimeoutMinutes: 15,
             startupTimeoutMs: 30000,
             requestTimeoutMs: 300000,
             timeoutAction: 'kill',
             templateId: '',
             e2bApiKey: '',
             apiKeyConfigured: false,
           );

  final OpenCrayChatSnapshot chatSnapshot;
  final OpenCrayChatRuntimeSnapshot runtimeSnapshot;
  final Map<String, OpenCrayFileImagePreview> imagePreviews;
  final Map<String, OpenCrayFileTextPreview> textPreviews;
  final Map<String, OpenCrayFileVoicePlaybackSource> voicePlaybackSources;
  final Stream<OpenCrayChatSnapshot>? chatSnapshotStream;
  final Stream<OpenCrayChatRuntimeSnapshot>? runtimeSnapshotStream;
  final Stream<OpenCrayChatLiveAssistantDraftEvent>?
  liveAssistantDraftEventStream;
  final Stream<OpenCrayChatRuntimeEventDelta>? runtimeEventDeltaStream;
  final Future<void> Function(String sessionId, String messageId)?
  onRecallChatMessage;
  OpenCraySandboxSettingsSnapshot sandboxSettings;
  final List<String> approvedApprovalIds = <String>[];
  final List<String> cancelledRunIds = <String>[];
  final List<String> rejectedApprovalIds = <String>[];
  final List<String> retriedRunIds = <String>[];
  final List<String> loadedTextPreviews = <String>[];
  final List<String> loadedVoicePlaybackSources = <String>[];
  final List<String> openedWorkspaceEntries = <String>[];
  final List<String> openedExternalUris = <String>[];
  final List<String> shownNativeToasts = <String>[];
  int createChatSessionCallCount = 0;
  Completer<void>? createChatSessionCompleter;
  final List<String> copiedSessionIds = <String>[];
  final List<String> deletedSessionIds = <String>[];
  final List<String> deletedMessageIds = <String>[];
  final List<String> recalledMessageIds = <String>[];
  final List<String> selectedSessionIds = <String>[];
  Object? pickChatAttachmentsError;
  Object? submitChatMessageError;
  final Map<OpenCrayChatDraftAttachmentKind, List<OpenCrayChatDraftAttachment>>
  pickedAttachmentsByKind =
      <OpenCrayChatDraftAttachmentKind, List<OpenCrayChatDraftAttachment>>{};
  final List<String> submittedMessages = <String>[];
  final List<List<OpenCrayChatDraftAttachment>> submittedAttachments =
      <List<OpenCrayChatDraftAttachment>>[];
  final List<OpenCraySandboxSettingsSnapshot> savedSandboxSettings =
      <OpenCraySandboxSettingsSnapshot>[];
  int loadChatSnapshotCallCount = 0;
  int loadChatRuntimeSnapshotCallCount = 0;
  int refreshSandboxSessionInfoCallCount = 0;
  int resolveSandboxPreviewEmbedConfigCallCount = 0;
  Completer<void>? refreshSandboxSessionInfoCompleter;
  Object? refreshSandboxSessionInfoError;
  Object? resolveSandboxPreviewEmbedConfigError;
  OpenCraySandboxPreviewEmbedConfig? sandboxPreviewEmbedConfig;

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) async {}

  @override
  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  ) async {
    final preview = imagePreviews[relativePath];
    if (preview != null) {
      return preview;
    }
    throw StateError('Missing image preview for $relativePath');
  }

  @override
  Future<OpenCraySandboxPreviewEmbedConfig> resolveSandboxPreviewEmbedConfig(
    String previewUrl,
  ) async {
    resolveSandboxPreviewEmbedConfigCallCount += 1;
    final error = resolveSandboxPreviewEmbedConfigError;
    if (error != null) {
      throw error;
    }
    return sandboxPreviewEmbedConfig ??
        OpenCraySandboxPreviewEmbedConfig(
          previewUrl: previewUrl,
          providerId: 'e2b',
          headers: const <String, String>{},
          sessionMatched: true,
          accessTokenConfigured: false,
        );
  }

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async {
    loadedTextPreviews.add(relativePath);
    final preview = textPreviews[relativePath];
    if (preview != null) {
      return preview;
    }
    throw StateError('Missing text preview for $relativePath');
  }

  @override
  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  ) async {
    loadedVoicePlaybackSources.add(relativePath);
    final source = voicePlaybackSources[relativePath];
    if (source != null) {
      return source;
    }
    throw StateError('Missing voice playback source for $relativePath');
  }

  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async {
    loadChatSnapshotCallCount += 1;
    return chatSnapshot;
  }

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() =>
      chatSnapshotStream ?? Stream<OpenCrayChatSnapshot>.empty();

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async {
    loadChatRuntimeSnapshotCallCount += 1;
    return runtimeSnapshot;
  }

  @override
  Future<OpenCraySandboxSettingsSnapshot> loadSandboxSettings() async =>
      sandboxSettings;

  @override
  Future<OpenCraySandboxSettingsSnapshot> saveSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  ) async {
    sandboxSettings = snapshot;
    savedSandboxSettings.add(snapshot);
    return sandboxSettings;
  }

  @override
  Future<OpenCrayStrongBackgroundSnapshot>
  loadStrongBackgroundSnapshot() async =>
      const OpenCrayStrongBackgroundSnapshot(
        source: 'strong-background',
        available: false,
        tierId: 'baseline',
        setupComplete: false,
        recommendedActionIds: <String>[],
        notifications: OpenCrayStrongBackgroundNotificationsSnapshot(),
        exactAlarms: OpenCrayStrongBackgroundExactAlarmSnapshot(),
        batteryOptimization:
            OpenCrayStrongBackgroundBatteryOptimizationSnapshot(),
        actions: <OpenCrayStrongBackgroundActionSnapshot>[],
      );

  @override
  Future<OpenCrayStrongBackgroundActionResult> performStrongBackgroundAction(
    String actionId,
  ) async => OpenCrayStrongBackgroundActionResult(
    source: 'strong-background-action',
    actionId: actionId,
    available: false,
    launched: false,
  );

  @override
  Future<List<OpenCraySettingsImageAsset>> listSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> importSettingsImageAssets(
    List<String> uriStrings,
  ) async => const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCrayAgentSnapshot>> listAgents() async =>
      const <OpenCrayAgentSnapshot>[];

  @override
  Future<OpenCrayAgentSnapshot?> loadActiveAgent() async => null;

  @override
  Future<OpenCrayAgentSnapshot> createAgent(
    OpenCrayAgentCreateRequest request,
  ) async => throw UnimplementedError();

  @override
  Future<OpenCrayAgentSnapshot?> selectAgent(String agentId) async => null;

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() =>
      runtimeSnapshotStream ?? Stream<OpenCrayChatRuntimeSnapshot>.empty();

  @override
  Stream<OpenCrayChatLiveAssistantDraftEvent> watchLiveAssistantDraftEvents() =>
      liveAssistantDraftEventStream ??
      Stream<OpenCrayChatLiveAssistantDraftEvent>.empty();

  @override
  Stream<OpenCrayChatRuntimeEventDelta> watchRuntimeEventDeltas() =>
      runtimeEventDeltaStream ?? Stream<OpenCrayChatRuntimeEventDelta>.empty();

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      const OpenCrayMemoryDebugSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        records: <OpenCrayMemoryDebugRecordSnapshot>[],
      );

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async =>
      const OpenCrayMemoryDebugLinksSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        records: <OpenCrayMemoryDebugLinksEntrySnapshot>[],
      );

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      const OpenCraySoulDebugSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        overlayRecords: <OpenCrayMemoryDebugRecordSnapshot>[],
        fieldSources: <OpenCraySoulFieldSourceSnapshot>[],
      );

  @override
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async => OpenCrayMemoryDebugSearchSnapshot(
    sessionId: '',
    observedAtEpochMs: 0,
    query: query,
  );

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async => OpenCrayMemoryDebugSliceSnapshot(
    sessionId: '',
    observedAtEpochMs: 0,
    path: path,
    startLine: fromLine ?? 1,
    endLine: (fromLine ?? 1) + lines - 1,
  );

  @override
  Future<void> approveChatApproval(String approvalId) async {
    approvedApprovalIds.add(approvalId);
  }

  @override
  Future<void> approveChatApprovalForSession(String approvalId) async {
    approvedApprovalIds.add(approvalId);
  }

  @override
  Future<void> rejectChatApproval(String approvalId) async {
    rejectedApprovalIds.add(approvalId);
  }

  @override
  Future<void> interruptChatRun(String runIdOrTaskId) async {
    cancelledRunIds.add(runIdOrTaskId);
  }

  @override
  Future<void> retryChatRun(String runIdOrTaskId) async {
    retriedRunIds.add(runIdOrTaskId);
  }

  @override
  Future<void> openWorkspaceEntry(String relativePath) async {
    openedWorkspaceEntries.add(relativePath);
  }

  @override
  Future<void> openExternalUri(String uri) async {
    openedExternalUris.add(uri);
  }

  @override
  Future<void> showNativeToast(String message) async {
    shownNativeToasts.add(message);
  }

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async {
    final error = pickChatAttachmentsError;
    if (error != null) {
      throw error;
    }
    return List<OpenCrayChatDraftAttachment>.of(
      pickedAttachmentsByKind[kind] ?? const <OpenCrayChatDraftAttachment>[],
    );
  }

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments =
        const <OpenCrayChatDraftAttachment>[],
  }) async {
    final error = submitChatMessageError;
    if (error != null) {
      throw error;
    }
    submittedMessages.add(text);
    submittedAttachments.add(List<OpenCrayChatDraftAttachment>.of(attachments));
    return const OpenCrayChatRunSubmission(
      sessionId: 'session-1',
      runId: 'run-1',
      taskId: 'task-1',
      acceptedAtEpochMs: 0,
    );
  }

  @override
  Future<void> refreshSandboxSessionInfo() async {
    refreshSandboxSessionInfoCallCount += 1;
    final completer = refreshSandboxSessionInfoCompleter;
    if (completer != null) {
      await completer.future;
    }
    final error = refreshSandboxSessionInfoError;
    if (error != null) {
      throw error;
    }
  }

  @override
  Future<void> createChatSession() async {
    createChatSessionCallCount += 1;
    final completer = createChatSessionCompleter;
    if (completer != null) {
      await completer.future;
    }
  }

  @override
  Future<void> copyChatSession(String sessionId) async {
    copiedSessionIds.add(sessionId);
  }

  @override
  Future<void> deleteChatSession(String sessionId) async {
    deletedSessionIds.add(sessionId);
  }

  @override
  Future<void> deleteChatMessage({
    required String sessionId,
    required String messageId,
  }) async {
    deletedMessageIds.add(messageId);
  }

  @override
  Future<void> recallChatMessage({
    required String sessionId,
    required String messageId,
  }) async {
    recalledMessageIds.add(messageId);
    final handler = onRecallChatMessage;
    if (handler != null) {
      await handler(sessionId, messageId);
    }
  }

  @override
  Future<void> selectChatSession(String sessionId) async {
    selectedSessionIds.add(sessionId);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FakeVoicePlaybackLog {
  final List<String> sourcePaths = <String>[];
  final List<Duration> seekPositions = <Duration>[];
  int playCount = 0;
  int pauseCount = 0;
}

class _FakeVoicePlaybackController implements ChatVoicePlaybackController {
  _FakeVoicePlaybackController(this.log);

  final _FakeVoicePlaybackLog log;
  final StreamController<ChatVoicePlaybackSnapshot> _snapshots =
      StreamController<ChatVoicePlaybackSnapshot>.broadcast();
  ChatVoicePlaybackSnapshot _state = const ChatVoicePlaybackSnapshot();

  @override
  ChatVoicePlaybackSnapshot get currentState => _state;

  @override
  Stream<ChatVoicePlaybackSnapshot> get snapshots => _snapshots.stream;

  @override
  Future<void> setSource({required String filePath}) async {
    log.sourcePaths.add(filePath);
    _state = _state.copyWith(
      duration: const Duration(milliseconds: 4200),
      clearError: true,
    );
    _snapshots.add(_state);
  }

  @override
  Future<void> play() async {
    log.playCount += 1;
    _state = _state.copyWith(isPlaying: true, clearError: true);
    _snapshots.add(_state);
  }

  @override
  Future<void> pause() async {
    log.pauseCount += 1;
    _state = _state.copyWith(isPlaying: false, clearError: true);
    _snapshots.add(_state);
  }

  @override
  Future<void> seek(Duration position) async {
    log.seekPositions.add(position);
    _state = _state.copyWith(position: position, clearError: true);
    _snapshots.add(_state);
  }

  @override
  Future<void> dispose() async {
    await _snapshots.close();
  }
}

OpenCrayFileImagePreview _fakeImagePreview({
  required String name,
  required String relativePath,
}) {
  return OpenCrayFileImagePreview(
    name: name,
    relativePath: relativePath,
    bytes: base64Decode(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wn7n8sAAAAASUVORK5CYII=',
    ),
    mimeType: 'image/png',
    width: 1,
    height: 1,
  );
}
