
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/models/opencray_chat_draft_attachment.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

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
      final current = hostChatSnapshot(
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
      final incoming = hostChatSnapshot(
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
      final current = hostChatSnapshot(
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
      final incoming = hostChatSnapshot(
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
    'shouldReplaceObservedChatSnapshot accepts same-version approval clears',
    () {
      final current = hostChatSnapshot(
        updatedAtEpochMs: 2000,
        pendingApprovals: const <OpenCrayChatPendingApprovalSnapshot>[
          OpenCrayChatPendingApprovalSnapshot(
            runId: 'run-approval-clear',
            taskId: 'task-approval-clear',
            title: 'Approval required',
            body: 'Write note.txt',
            approveLabel: 'Approve',
            rejectLabel: 'Reject',
            isHighRisk: false,
          ),
        ],
      );
      final incoming = hostChatSnapshot(updatedAtEpochMs: 2000);

      expect(shouldReplaceObservedChatSnapshot(current, incoming), isTrue);
    },
  );

  test(
    'shouldReplaceObservedChatSnapshot accepts same-version todo clears',
    () {
      final current = hostChatSnapshot(
        updatedAtEpochMs: 2000,
        todos: const <OpenCrayChatTodoSnapshot>[
          OpenCrayChatTodoSnapshot(
            content: 'Inspect runtime updates',
            status: 'in_progress',
            activeForm: 'Inspecting runtime updates',
          ),
        ],
        todoState: 'active',
      );
      final incoming = hostChatSnapshot(
        updatedAtEpochMs: 2000,
        todos: const <OpenCrayChatTodoSnapshot>[],
        todoState: 'empty',
      );

      expect(shouldReplaceObservedChatSnapshot(current, incoming), isTrue);
    },
  );

  test(
    'shouldReplaceObservedChatSnapshot accepts same-version drawer content changes',
    () {
      final current = hostChatSnapshot(
        updatedAtEpochMs: 2000,
        drawer: const OpenCrayChatDrawerSnapshot(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: <OpenCrayChatSessionItemSnapshot>[
            OpenCrayChatSessionItemSnapshot(
              sessionId: 'session-drawer-1',
              title: 'Old title',
              preview: 'Old preview',
              meta: '1 message',
              isSelected: true,
            ),
          ],
        ),
      );
      final incoming = hostChatSnapshot(
        updatedAtEpochMs: 2000,
        drawer: const OpenCrayChatDrawerSnapshot(
          eyebrow: 'Recent sessions',
          title: 'Recent sessions',
          ctaLabel: 'New session',
          sessions: <OpenCrayChatSessionItemSnapshot>[
            OpenCrayChatSessionItemSnapshot(
              sessionId: 'session-drawer-1',
              title: 'New title',
              preview: 'New preview',
              meta: '2 messages',
              isSelected: true,
              unreadCount: 1,
            ),
          ],
        ),
      );

      expect(shouldReplaceObservedChatSnapshot(current, incoming), isTrue);
    },
  );

  test(
    'shouldReplaceObservedChatSnapshot accepts same-version message removals when content changes',
    () {
      final current = hostChatSnapshot(
        updatedAtEpochMs: 2000,
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'message-keep',
            kind: 'outbound',
            text: 'Keep this message.',
            createdAtEpochMs: 1000,
          ),
          OpenCrayChatMessageSnapshot(
            messageId: 'message-delete',
            kind: 'inbound',
            text: 'Delete this message.',
            createdAtEpochMs: 1100,
          ),
        ],
      );
      final incoming = hostChatSnapshot(
        updatedAtEpochMs: 2000,
        messages: const <OpenCrayChatMessageSnapshot>[
          OpenCrayChatMessageSnapshot(
            messageId: 'message-keep',
            kind: 'outbound',
            text: 'Keep this message.',
            createdAtEpochMs: 1000,
          ),
        ],
      );

      expect(shouldReplaceObservedChatSnapshot(current, incoming), isTrue);
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
    'shouldReplaceObservedRuntimeSnapshot accepts same-version authoritative clears',
    () {
      const current = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2200,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-clear-authoritative-1',
            taskId: 'task-clear-authoritative-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2200,
            attempt: 1,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'tool_result',
            runId: 'run-clear-authoritative-1',
            taskId: 'task-clear-authoritative-1',
            emittedAtEpochMs: 2200,
            toolName: 'Read',
            contentPreview: 'Old run detail.',
          ),
        ],
      );
      const incoming = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2200,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        retainedRuns: <OpenCrayChatRunSnapshot>[],
        subAgents: <OpenCrayChatSubAgentSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[],
      );

      expect(shouldReplaceObservedRuntimeSnapshot(current, incoming), isTrue);
      expect(resolveChatRuntimeSnapshot(current, incoming), incoming);
    },
  );

  test(
    'shouldReplaceObservedRuntimeSnapshot accepts explicit delta clears without a newer timestamp',
    () {
      const current = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2200,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-delta-clear-authoritative-1',
            taskId: 'task-delta-clear-authoritative-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 2200,
            attempt: 1,
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      const patched = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 2200,
        activeRuns: <OpenCrayChatRunSnapshot>[],
        retainedRuns: <OpenCrayChatRunSnapshot>[],
        subAgents: <OpenCrayChatSubAgentSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
        liveAssistantDrafts: <OpenCrayChatLiveAssistantDraftSnapshot>[],
      );

      expect(patched.activeRuns, isEmpty);
      expect(patched.retainedRuns, isEmpty);
      expect(shouldReplaceObservedRuntimeSnapshot(current, patched), isTrue);
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
    'shouldReplaceObservedRuntimeSnapshot accepts same-version inspector detail changes',
    () {
      const current = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 3000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-inspector-detail-1',
            taskId: 'task-inspector-detail-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            pendingMessageId: 'pending-inspector-detail-1',
            llmDiagnostics: OpenCrayChatRunLlmDiagnosticsSnapshot(
              lastSuccessfulToolName: 'WebFetch',
            ),
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-inspector-detail-1',
            taskId: 'task-inspector-detail-1',
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
            runId: 'run-inspector-detail-1',
            taskId: 'task-inspector-detail-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 3000,
            attempt: 1,
            pendingMessageId: 'pending-inspector-detail-1',
            llmDiagnostics: OpenCrayChatRunLlmDiagnosticsSnapshot(
              lastSuccessfulToolName: 'ResponsesWebSearch',
            ),
            isTerminal: false,
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[
          OpenCrayChatRuntimeEventSnapshot(
            kind: 'lifecycle',
            runId: 'run-inspector-detail-1',
            taskId: 'task-inspector-detail-1',
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
      final current = hostChatSnapshot(
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
      final incoming = hostChatSnapshot(
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

  test(
    'resolveChatRuntimeSnapshot keeps same-version newer inspector details',
    () {
      const embedded = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-inspector-detail-1',
            taskId: 'task-inspector-detail-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 5000,
            attempt: 1,
            isTerminal: false,
            llmDiagnostics: OpenCrayChatRunLlmDiagnosticsSnapshot(
              lastSuccessfulToolName: 'webfetch',
            ),
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );
      const streamed = OpenCrayChatRuntimeSnapshot(
        sessionId: 'session-1',
        updatedAtEpochMs: 5000,
        activeRuns: <OpenCrayChatRunSnapshot>[
          OpenCrayChatRunSnapshot(
            sessionId: 'session-1',
            runId: 'run-inspector-detail-1',
            taskId: 'task-inspector-detail-1',
            acceptedAtEpochMs: 1000,
            updatedAtEpochMs: 5000,
            attempt: 1,
            isTerminal: false,
            llmDiagnostics: OpenCrayChatRunLlmDiagnosticsSnapshot(
              lastSuccessfulToolName: 'final_answer',
            ),
          ),
        ],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

      final OpenCrayChatRuntimeSnapshot resolved = resolveChatRuntimeSnapshot(
        embedded,
        streamed,
      )!;

      expect(
        resolved.activeRuns.single.llmDiagnostics?.lastSuccessfulToolName,
        'final_answer',
      );
      expect(shouldReplaceObservedRuntimeSnapshot(embedded, streamed), isTrue);
    },
  );
}
