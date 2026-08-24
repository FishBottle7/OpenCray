import 'package:opencray/app/opencray_tabs.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_debug_snapshot.dart';
import 'package:opencray/core/models/opencray_image_reference.dart';
import 'package:opencray/core/models/opencray_shell_snapshot.dart';
import 'package:opencray/features/settings/settings.dart';

const List<LlmOnDeviceModelOption> defaultOnDeviceLlmModels =
    <LlmOnDeviceModelOption>[
      LlmOnDeviceModelOption(
        id: 'gemma-4-e2b-it',
        title: 'Gemma 4 E2B',
        subtitle: 'Instruction-tuned Gemma 4 E2B for LiteRT-LM.',
        sizeLabel: '2.58 GB',
        fileSizeBytes: 2583085056,
        installState: 'ready',
        downloadedBytes: 2583085056,
        downloadBytesPerSecond: 0,
        sha256Verified: true,
      ),
      LlmOnDeviceModelOption(
        id: 'gemma-4-e4b-it',
        title: 'Gemma 4 E4B',
        subtitle: 'Instruction-tuned Gemma 4 E4B for LiteRT-LM.',
        sizeLabel: '3.65 GB',
        fileSizeBytes: 3654467584,
        installState: 'not_downloaded',
      ),
    ];

FakeSettingsFacade buildSettingsFacade() {
  return FakeSettingsFacade(
    llmConfig: const LlmConfigSnapshot(
      localeTag: 'en',
      enabled: false,
      providerId: 'openai',
      selectedProviderOptionId: 'openai',
      protocol: 'openai',
      providerOptions: <LlmProviderOption>[
        LlmProviderOption(
          id: 'openai',
          providerId: 'openai',
          title: 'OpenAI',
          subtitle: 'Official OpenAI-compatible endpoint.',
          defaultBaseUrl: 'https://api.openai.com/v1',
          defaultModel: 'gpt-4o-mini',
          protocol: 'openai',
          apiKey: '',
          isCustom: false,
        ),
      ],
      providerName: 'OpenAI',
      providerNotes: '',
      baseUrl: 'https://api.openai.com/v1',
      apiKey: '',
      model: 'gpt-4o-mini',
      reasoningEffort: 'medium',
      systemPrompt: '',
      helperText: 'Helper text',
      onDeviceModels: defaultOnDeviceLlmModels,
    ),
    validationResult: const LlmValidationResult(
      isSuccess: true,
      message: 'Validated.',
    ),
  );
}

FakeDebugBridge buildDebugBridge({
  List<List<OpenCraySettingsImageAsset>> pickedSettingsImageAssetBatches =
      const <List<OpenCraySettingsImageAsset>>[],
}) {
  return FakeDebugBridge(
    pickedSettingsImageAssetBatches: pickedSettingsImageAssetBatches,
    shellSnapshot: const OpenCrayShellSnapshot(
      initialTab: OpenCrayTab.chat,
      localeTag: 'en',
      hostLabel: 'HOST READY',
      hostSummary: 'Detached runtime service active.',
      isHostConnected: true,
      localRuntimeServerState: OpenCrayLocalRuntimeServerStateSnapshot(
        phase: 'listening',
        bindAddress: '127.0.0.1',
        requestedPort: 42617,
        listeningPort: 42617,
        lastStartAttemptAtEpochMs: 1200,
        lastStartedAtEpochMs: 1300,
        changedAtEpochMs: 1300,
      ),
      hostLifecycle: OpenCrayHostLifecycleSnapshot(
        processStartId: 'process-1',
        processStartedAtEpochMs: 1000,
        hostInstanceId: 'host-ui-1',
        runtimeOwnerId: 'owner-service-1',
        hostCreatedAtEpochMs: 2000,
      ),
      runtimeOwnerLifecycle: OpenCrayHostLifecycleSnapshot(
        processStartId: 'process-1',
        processStartedAtEpochMs: 1000,
        hostInstanceId: 'host-service-1',
        runtimeOwnerId: 'owner-service-1',
        hostCreatedAtEpochMs: 1500,
      ),
      runtimeOwnerWorkSummary: OpenCrayRuntimeOwnerWorkSummarySnapshot(
        hasActiveWork: true,
        trackedSessionCount: 2,
        activeRunCount: 1,
        activeSessionCount: 1,
        activeSessionIds: <String>['session-1'],
        pendingWorkSessionIds: <String>['session-1'],
        liveManagedProcessSessionIds: <String>['session-1'],
      ),
      runtimeServiceLifecycle: OpenCrayRuntimeServiceLifecycleSnapshot(
        processStartId: 'process-1',
        processStartedAtEpochMs: 1000,
        serviceInstanceId: 'runtime-service-1',
        serviceCreatedAtEpochMs: 1400,
      ),
      runtimeServiceWorkState: OpenCrayRuntimeServiceWorkStateSnapshot(
        phase: 'active_work',
        hasActiveWork: true,
        keepAliveRequired: true,
        keepAliveReason: 'active_run',
        changedAtEpochMs: 2300,
        activeSinceEpochMs: 1500,
      ),
      runtimeServiceKeepAliveState:
          OpenCrayRuntimeServiceKeepAliveStateSnapshot(
            phase: 'active_work',
            idleGraceMs: 30000,
            stopScheduled: false,
            hasSeenStartCommand: true,
            lastStartId: 7,
            lastStartCommandAtEpochMs: 1450,
            changedAtEpochMs: 2300,
          ),
      runtimeServiceConnectionState:
          OpenCrayRuntimeServiceConnectionStateSnapshot(
            phase: 'bound',
            transport: 'binder',
            serviceStartRequested: true,
            bindingRequested: true,
            binderAvailable: true,
          ),
    ),
    runtimeSnapshot: const OpenCrayChatRuntimeSnapshot(
      sessionId: 'session-1',
      activeRuns: <OpenCrayChatRunSnapshot>[
        OpenCrayChatRunSnapshot(
          sessionId: 'session-1',
          runId: 'run-memory',
          taskId: 'task-memory',
          acceptedAtEpochMs: 1000,
          updatedAtEpochMs: 2400,
          attempt: 1,
          executionOrdinal: 2,
          executionKind: 'checkpoint_resume',
          isTerminal: true,
          executionStatus: 'success',
          taskState: 'completed',
          responseFormat: 'json_final',
        ),
      ],
      subAgents: <OpenCrayChatSubAgentSnapshot>[
        OpenCrayChatSubAgentSnapshot(
          parentRunId: 'run-memory',
          parentTaskId: 'task-memory',
          childRunId: 'child-run-detached-memory',
          childTaskId: 'child-task-detached-memory',
          label: 'Inspect detached recovery',
          subagentType: 'researcher',
          contextMode: 'minimal',
          depth: 1,
          phase: 'resumed',
          status: 'background_running',
          executionState: 'background_running',
          continuationKind: 'background_resume',
          resumable: true,
          summary: 'Detached child runtime is still running in the background.',
          startedAtEpochMs: 1750,
          updatedAtEpochMs: 2350,
          eventCount: 0,
          hasActiveExecution: true,
          mailboxMessageCount: 2,
          mailboxPendingMessageCount: 1,
          mailboxLastDeliveredMessageId: 'mailbox-memory-1',
          hasPendingApprovalResume: true,
          pendingApprovalToolName: 'Read',
          pendingApprovalChildRunId: 'child-run-detached-memory',
          pendingApprovalChildTaskId: 'child-task-detached-memory',
        ),
      ],
      events: <OpenCrayChatRuntimeEventSnapshot>[
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'memory_retrieval',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 1600,
          operation: 'search',
          queryTerms: <String>['chinese', 'gradle'],
          resultCount: 2,
        ),
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'tool_call',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 1700,
          toolName: 'Read',
          toolReason: 'Inspect workspace instructions before planning.',
          argumentsJson:
              '{"file_path":"workspace/AGENTS.md","offset":1,"limit":14}',
        ),
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'tool_result',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 1800,
          toolName: 'Read',
          toolStatus: 'success',
          contentPreview: 'Repository guidelines for mobile work.',
          resultMetadata: <String, String>{
            'filePath': 'workspace/AGENTS.md',
            'returnedLineCount': '14',
            'totalLineCount': '56',
          },
        ),
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'memory_write',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 2200,
          writtenRecordIds: <String>['memory-user', 'commitment-1'],
          writtenKinds: <String>['user_preference', 'task_commitment'],
          resolvedRecordIds: <String>['memory-project'],
          reaffirmedRecordIds: <String>['commitment-1'],
          expiredRecordIds: <String>['memory-old'],
        ),
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'memory_flush',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 2300,
          writtenRecordIds: <String>['memory-user'],
          writtenKinds: <String>['user_preference'],
        ),
        OpenCrayChatRuntimeEventSnapshot(
          kind: 'final',
          runId: 'run-memory',
          taskId: 'task-memory',
          emittedAtEpochMs: 2400,
          status: 'success',
        ),
      ],
    ),
    runSnapshots: <String, OpenCrayChatRunSnapshot>{
      'run-memory': const OpenCrayChatRunSnapshot(
        sessionId: 'session-1',
        runId: 'run-memory',
        taskId: 'task-memory',
        acceptedAtEpochMs: 1000,
        updatedAtEpochMs: 2400,
        attempt: 1,
        executionOrdinal: 2,
        executionKind: 'checkpoint_resume',
        isTerminal: true,
        executionStatus: 'success',
        taskState: 'completed',
        responseFormat: 'json_final',
        recoveryPlan: OpenCrayChatRunRecoveryPlanSnapshot(
          action: 'resume_from_checkpoint',
          reasonCode: 'durable_general_resume_checkpoint',
          summary:
              'Resumed from durable checkpoint instead of rerunning from task input.',
          safeToAutoResume: true,
          requiresUserAction: false,
          checkpointKind: 'general_resume',
          journalTailKind: 'tool_result',
        ),
        diagnostics: OpenCrayChatRunDiagnosticsSnapshot(
          recoveryReason: 'host_restart_inflight_task_interrupted',
        ),
        llmDiagnostics: OpenCrayChatRunLlmDiagnosticsSnapshot(
          providerResponseShape: 'openai_tool_calls',
          nativeToolCallRequested: true,
          nativeToolCallObserved: true,
          parsedToolCallObserved: true,
          fallbackParserAttempted: false,
          fallbackParserSucceeded: false,
          responsesContinuationRecoveryCount: 1,
          responsesContinuationRecoveryLastReason:
              'responses_restored_replay_required',
          localContinuationUsedCount: 0,
          localContinuationFallbackCount: 1,
          localContinuationLastMode: 'full_rebuild',
          localContinuationLastReason: 'user_setting_changed',
          responsesPendingContextUpdateCount: 1,
          responsesPendingContextUpdateHash: 'hash-dynamic-context',
          toolCallEventEmitted: true,
          toolResultEventEmitted: true,
          contextCacheBreakReason: 'user_setting_changed',
          lastSuccessfulToolName: 'EchoProbe',
        ),
        liveContext: OpenCrayChatRunLiveContextSnapshot(
          mode: 'no_soul',
          soulEnabled: false,
          memoryRecallEnabled: true,
        ),
        contextBudget: OpenCrayChatRunContextBudgetSnapshot(
          applied: true,
          pressureMode: 'EMERGENCY',
          selectedPreset: 'balanced',
          effectivePreset: 'dev',
          presetSource: 'raw',
          presetDiverged: true,
          sourcePreset: 'expanded',
          sourceTranscriptMaxMessages: 16,
          sourceInjectedMemoryMaxRecords: 6,
          sourceMemoryRecallMaxRecords: 8,
          sourceBootstrapMaxChars: 4800,
          sourceSkillInventoryMaxSkills: 12,
          sourceActiveSkillMaxChars: 4800,
          sourceRecentObservationMaxEntries: 6,
          sourceMemoryFlushMaxToolObservations: 12,
          contextWindowTokens: 900,
          reservedOutputTokens: 256,
          safetyMarginTokens: 96,
          hardInputTokens: 548,
          targetInputTokens: 512,
          emergencyInputTokens: 548,
          unresolvedOverflow: true,
          fullLayerCount: 4,
          compactLayerCount: 2,
          minimalLayerCount: 1,
          omittedLayerCount: 1,
          reducedLayerNames: <String>['Working State', 'Conversation'],
          omittedLayerNames: <String>['Retrieved Memory'],
          layers: <OpenCrayChatRunContextBudgetLayerSnapshot>[
            OpenCrayChatRunContextBudgetLayerSnapshot(
              id: 'WORKING_STATE',
              name: 'Working State',
              priorityClass: 'OPTIONAL_SUPPORT_CONTEXT',
              retentionPriority: 70,
              estimatedTokensBefore: 220,
              estimatedTokensAfter: 120,
              finalState: 'compact',
              omitted: false,
              reduced: true,
              appliedOperators: <String>['reduce_working_state_compact'],
            ),
            OpenCrayChatRunContextBudgetLayerSnapshot(
              id: 'CONVERSATION',
              name: 'Conversation',
              priorityClass: 'RECENT_REPLAY',
              retentionPriority: 110,
              estimatedTokensBefore: 420,
              estimatedTokensAfter: 180,
              finalState: 'minimal',
              omitted: false,
              reduced: true,
              appliedOperators: <String>['reduce_conversation_window_minimal'],
            ),
            OpenCrayChatRunContextBudgetLayerSnapshot(
              id: 'RETRIEVED_MEMORY',
              name: 'Retrieved Memory',
              priorityClass: 'BOUNDED_DURABLE_RECALL',
              retentionPriority: 90,
              estimatedTokensBefore: 48,
              estimatedTokensAfter: 0,
              finalState: 'omitted',
              omitted: true,
              reduced: false,
              appliedOperators: <String>['omit_layer'],
            ),
          ],
          layerSummary:
              'WORKING_STATE:compact:20;CONVERSATION:minimal:120;RETRIEVED_MEMORY:omitted:48',
        ),
        memoryFlush: OpenCrayChatRunMemoryFlushSnapshot(
          outcome: 'written',
          executionMode: 'inline',
          omittedMessageCount: 6,
          omittedCharCount: 2100,
          signature: 'user-intro|tool-scan|workspace-facts',
          candidateCount: 2,
          writtenRecordCount: 1,
          writtenKinds: <String>['user_preference'],
          writtenRecordIds: <String>['memory-user'],
        ),
        bootstrap: OpenCrayChatRunBootstrapSnapshot(
          mode: 'full',
          visibleFileCount: 3,
          injectedFileCount: 2,
          omittedFileCount: 1,
          truncatedFileCount: 1,
          files: <OpenCrayChatRunBootstrapFileSnapshot>[
            OpenCrayChatRunBootstrapFileSnapshot(
              name: 'AGENTS.md',
              relativePath: 'workspace/AGENTS.md',
              sourceCharCount: 900,
              injectedCharCount: 520,
              truncated: true,
            ),
            OpenCrayChatRunBootstrapFileSnapshot(
              name: 'PROJECT.md',
              relativePath: 'workspace/PROJECT.md',
              sourceCharCount: 480,
              injectedCharCount: 480,
              truncated: false,
            ),
          ],
        ),
        durableCompaction: OpenCrayChatRunDurableCompactionSnapshot(
          compactedThisRun: true,
          executionMode: 'inline',
          sourceTranscriptMessageCount: 18,
          retainedTranscriptMessageCount: 7,
          latestCompactedMessageCount: 9,
          includedSummaryCount: 1,
          omittedSummaryCount: 2,
          totalSummaryCount: 3,
          totalCompactedMessageCount: 14,
          latestCompactedAtEpochMs: 1950,
          remoteCompaction: OpenCrayChatRunRemoteCompactionSnapshot(
            requested: true,
            supported: true,
            used: true,
            triggerStage: 'pre_compaction',
            outputItemCount: 2,
            compactionItemCount: 1,
            encryptedContentCount: 1,
          ),
        ),
        memoryTrace: OpenCrayChatRunMemoryTraceSnapshot(
          matchedRecordCount: 2,
          injectedRecordCount: 1,
          omittedRecordCount: 1,
          queryTerms: <String>['chinese', 'gradle'],
          selected: <OpenCrayChatRunMemorySelectedSnapshot>[
            OpenCrayChatRunMemorySelectedSnapshot(
              id: 'memory-user',
              score: 420,
              matchedTerms: <String>['chinese'],
            ),
          ],
          omitted: <OpenCrayChatRunMemoryOmittedSnapshot>[
            OpenCrayChatRunMemoryOmittedSnapshot(
              id: 'memory-project',
              reason: 'max_records',
            ),
          ],
          filteredCounts: <String, int>{'scope_mismatch': 1, 'expired': 2},
        ),
        skillInventory: OpenCrayChatRunSkillInventorySnapshot(
          visibleSkillCount: 3,
          injectedSkillCount: 2,
          omittedSkillCount: 1,
          implicitSkillCount: 0,
          invalidSkillCount: 0,
          omittedTraceSkillCount: 1,
          skills: <OpenCrayChatRunVisibleSkillSnapshot>[
            OpenCrayChatRunVisibleSkillSnapshot(
              name: 'memory-link',
              relativePath: 'skills/memory-link/SKILL.md',
              invocationControl: 'manual',
              userInvocable: true,
              executionContext: 'inline',
            ),
            OpenCrayChatRunVisibleSkillSnapshot(
              name: 'repo-planner',
              relativePath: 'skills/repo-planner/SKILL.md',
              invocationControl: 'agent',
              userInvocable: false,
              executionContext: 'inline',
            ),
          ],
        ),
        activeSkill: OpenCrayChatRunActiveSkillSnapshot(
          name: 'memory-link',
          relativePath: 'skills/memory-link/SKILL.md',
          invocationControl: 'manual',
          executionContext: 'inline',
          activationSource: 'skill_read',
          toolRestrictionEnabled: true,
          truncated: false,
          allowedToolKeys: <String>['skill_read', 'memory_search'],
        ),
      ),
    },
    memorySnapshot: const OpenCrayMemoryDebugSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      records: <OpenCrayMemoryDebugRecordSnapshot>[
        OpenCrayMemoryDebugRecordSnapshot(
          id: 'memory-user',
          content: 'User prefers Chinese replies.',
          kind: 'user_preference',
          scope: 'user',
          status: 'active',
          source: 'user_input',
          sourceSessionId: 'session-1',
          updatedAtEpochMs: 4200,
          lastConfirmedAtEpochMs: 4200,
          preferenceKey: 'agent_display_name',
          preferenceValue: 'Xiao Bai',
          preferenceTemporality: 'durable',
          tags: <String>['kind:user_preference', 'scope:user'],
          extensions: <String, String>{
            'kind': 'user_preference',
            'scope': 'user',
            'status': 'active',
          },
        ),
        OpenCrayMemoryDebugRecordSnapshot(
          id: 'commitment-1',
          content: 'Finish context-management backlog.',
          kind: 'task_commitment',
          scope: 'session',
          status: 'open',
          source: 'assistant_output',
          sourceSessionId: 'session-1',
          sourceTaskId: 'task-memory',
          updatedAtEpochMs: 4100,
          lastConfirmedAtEpochMs: 4100,
          ttlMs: 1209600000,
          tags: <String>['kind:task_commitment', 'scope:session'],
          extensions: <String, String>{
            'kind': 'task_commitment',
            'scope': 'session',
            'status': 'open',
          },
        ),
        OpenCrayMemoryDebugRecordSnapshot(
          id: 'memory-old',
          content: 'Old workspace preference.',
          kind: 'user_preference',
          scope: 'workspace',
          status: 'active',
          source: 'user_input',
          sourceSessionId: 'session-0',
          workspaceId: 'workspace-main',
          updatedAtEpochMs: 1000,
          lastConfirmedAtEpochMs: 1000,
          ttlMs: 1000,
          isExpired: true,
          tags: <String>['kind:user_preference', 'scope:workspace'],
          extensions: <String, String>{
            'kind': 'user_preference',
            'scope': 'workspace',
            'status': 'active',
          },
        ),
      ],
    ),
    linksSnapshot: const OpenCrayMemoryDebugLinksSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      records: <OpenCrayMemoryDebugLinksEntrySnapshot>[
        OpenCrayMemoryDebugLinksEntrySnapshot(
          recordId: 'memory-user',
          sourceSessionId: 'session-1',
          sourceTaskId: 'task-memory-origin-1',
          sourceRun: OpenCrayDebugRunLinkSnapshot(
            sessionId: 'session-1',
            runId: 'run-memory-origin-1',
            taskId: 'task-memory-origin-1',
            acceptedAtEpochMs: 2000,
            updatedAtEpochMs: 2200,
            executionStatus: 'success',
            lifecycleState: 'completed',
          ),
          promptRecalls: <OpenCrayMemoryPromptRecallLinkSnapshot>[
            OpenCrayMemoryPromptRecallLinkSnapshot(
              occurredAtEpochMs: 4600,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-memory',
                taskId: 'task-memory',
                acceptedAtEpochMs: 1000,
                updatedAtEpochMs: 2400,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
              score: 420,
              matchedTerms: <String>['chinese'],
            ),
          ],
          toolRetrievals: <OpenCrayMemoryToolRetrievalLinkSnapshot>[
            OpenCrayMemoryToolRetrievalLinkSnapshot(
              occurredAtEpochMs: 1600,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-memory',
                taskId: 'task-memory',
                acceptedAtEpochMs: 1000,
                updatedAtEpochMs: 2400,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
              toolName: 'memory_search',
              operation: 'search',
              query: 'what name should I call the agent',
              queryTerms: <String>['name', 'agent'],
              paths: <String>['memory/2024-03-11.md'],
              lineRanges: <String>['5-8'],
            ),
          ],
          maintenanceActions: <OpenCrayMemoryMaintenanceActionLinkSnapshot>[
            OpenCrayMemoryMaintenanceActionLinkSnapshot(
              action: 'written',
              occurredAtEpochMs: 2200,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-memory-origin-1',
                taskId: 'task-memory-origin-1',
                acceptedAtEpochMs: 2000,
                updatedAtEpochMs: 2200,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
            ),
            OpenCrayMemoryMaintenanceActionLinkSnapshot(
              action: 'flush_written',
              occurredAtEpochMs: 2300,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-memory',
                taskId: 'task-memory',
                acceptedAtEpochMs: 1000,
                updatedAtEpochMs: 2400,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
            ),
          ],
        ),
        OpenCrayMemoryDebugLinksEntrySnapshot(
          recordId: 'memory-style',
          sourceSessionId: 'session-1',
          sourceTaskId: 'task-memory-style-1',
          sourceRun: OpenCrayDebugRunLinkSnapshot(
            sessionId: 'session-1',
            runId: 'run-memory-style-1',
            taskId: 'task-memory-style-1',
            acceptedAtEpochMs: 3000,
            updatedAtEpochMs: 3250,
            executionStatus: 'success',
            lifecycleState: 'completed',
          ),
          maintenanceActions: <OpenCrayMemoryMaintenanceActionLinkSnapshot>[
            OpenCrayMemoryMaintenanceActionLinkSnapshot(
              action: 'written',
              occurredAtEpochMs: 3250,
              run: OpenCrayDebugRunLinkSnapshot(
                sessionId: 'session-1',
                runId: 'run-memory-style-1',
                taskId: 'task-memory-style-1',
                acceptedAtEpochMs: 3000,
                updatedAtEpochMs: 3250,
                executionStatus: 'success',
                lifecycleState: 'completed',
              ),
            ),
          ],
        ),
      ],
    ),
    memorySearchSnapshot: const OpenCrayMemoryDebugSearchSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      queryTerms: <String>['xiao', 'bai'],
      corpusFileCount: 2,
      results: <OpenCrayMemoryDebugSearchResultSnapshot>[
        OpenCrayMemoryDebugSearchResultSnapshot(
          recordId: 'memory-user',
          path: 'MEMORY.md',
          startLine: 5,
          endLine: 5,
          score: 420,
          matchedTerms: <String>['xiao', 'bai'],
          kind: 'user_preference',
          scope: 'user',
          status: 'active',
          snippet:
              '- [memory-user] kind=user_preference scope=user status=active confirmed_at=2026-03-19 content=User prefers Chinese replies.',
        ),
      ],
    ),
    memorySliceSnapshot: const OpenCrayMemoryDebugSliceSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      path: 'MEMORY.md',
      text:
          '- [memory-user] kind=user_preference scope=user status=active confirmed_at=2026-03-19 content=User prefers Chinese replies.',
      startLine: 5,
      endLine: 5,
      totalLineCount: 12,
      recordIds: <String>['memory-user'],
    ),
    soulSnapshot: const OpenCraySoulDebugSnapshot(
      sessionId: 'session-1',
      workspaceId: 'workspace-main',
      observedAtEpochMs: 5000,
      storedSoul: OpenCrayStoredSoulRecordSnapshot(
        agentId: 'app-shell-personalization',
        displayName: 'Night Shift',
        presetName: 'STEADY',
        customGuidance: 'Keep replies calm and concrete.',
        recordVersion: 2,
        createdAtEpochMs: 1000,
        updatedAtEpochMs: 4300,
        extensions: <String, String>{
          'preset': 'STEADY',
          'tone': 'steady',
          'verbosity': 'balanced',
          'user_relationship_style': 'collaborative',
          'risk_tolerance': 'conservative',
          'tool_use_bias': 'verify_first',
        },
      ),
      baseSoul: OpenCraySoulProfileDebugSnapshot(
        presetName: 'STEADY',
        displayName: 'Night Shift',
        customGuidance: 'Keep replies calm and concrete.',
        tone: 'steady',
        verbosity: 'balanced',
        userRelationshipStyle: 'collaborative',
        riskTolerance: 'conservative',
        toolUseBias: 'verify_first',
        escalationRules: <String>[
          'Ask before risky workspace or environment changes.',
        ],
        forbiddenBehaviors: <String>[
          'Do not fabricate workspace facts when local evidence is required.',
        ],
        collaborationPreferences: <String>[
          'Keep reasoning concrete and implementation-first.',
        ],
      ),
      effectiveSoul: OpenCraySoulProfileDebugSnapshot(
        presetName: 'STEADY',
        displayName: 'Xiao Bai',
        voice: 'warm and gentle',
        customGuidance: 'Keep replies calm and concrete.',
        tone: 'warm',
        verbosity: 'balanced',
        userRelationshipStyle: 'supportive',
        riskTolerance: 'conservative',
        toolUseBias: 'verify_first',
        escalationRules: <String>[
          'Ask before risky workspace or environment changes.',
        ],
        forbiddenBehaviors: <String>[
          'Do not fabricate workspace facts when local evidence is required.',
        ],
        collaborationPreferences: <String>[
          'Keep reasoning concrete and implementation-first.',
        ],
      ),
      interactionPreferenceDebug: OpenCrayInteractionPreferenceDebugSnapshot(
        scope: 'user',
        snapshotRecordId: 'interaction-state',
        preferredNaming: 'A-Cheng',
        preferredAddressStyle: 'friendly',
        derivedRelationshipStyle: 'warm',
        state: OpenCrayInteractionPreferenceStateSnapshot(
          warmth: OpenCrayPreferenceAxisStateSnapshot(
            offset: 1,
            higherSupport: 2,
          ),
          formality: OpenCrayPreferenceAxisStateSnapshot(
            offset: -1,
            lowerSupport: 2,
          ),
          initiative: OpenCrayPreferenceAxisStateSnapshot(),
          addressStyle: OpenCrayPreferredAddressStateSnapshot(
            selectedStyle: 'friendly',
            friendlySupport: 2,
          ),
          preferredNaming: 'A-Cheng',
          preferredNamingSupport: 2,
        ),
      ),
      relationshipStateDebug: OpenCrayRelationshipStateDebugSnapshot(
        scope: 'user',
        snapshotRecordId: 'relationship-state',
        state: OpenCrayRelationshipStateSnapshot(
          familiarity: 66,
          trust: 74,
          safety: 76,
          intimacyPermission: 61,
          playfulnessPermission: 44,
          affectionTendency: 34,
          reciprocity: 49,
        ),
        recentNegativeGuardActive: false,
        supportiveStyleUnlocked: true,
        supportiveStyleChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'trust',
            passed: true,
            currentValue: 74,
            threshold: 25,
          ),
        ],
        warmToneUnlocked: true,
        warmToneChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'intimacy_permission',
            passed: true,
            currentValue: 61,
            threshold: 25,
          ),
        ],
        derivedAddressStyle: 'intimate',
        friendlyAddressChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'trust',
            passed: true,
            currentValue: 74,
            threshold: 35,
          ),
        ],
        intimateAddressChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'recent_negative_guard_inactive',
            passed: true,
            actualBoolean: true,
            expectedBoolean: true,
          ),
        ],
        intimacyPermissionBand: 'warm',
        playfulnessPermissionBand: 'familiar',
        highIntimacyBehaviorAllowed: true,
        highIntimacyChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'intimacy_permission',
            passed: true,
            currentValue: 61,
            threshold: 50,
          ),
        ],
        playfulAffectionAllowed: true,
        playfulAffectionChecks: <OpenCraySoulGateCheckSnapshot>[
          OpenCraySoulGateCheckSnapshot(
            key: 'playfulness_permission',
            passed: true,
            currentValue: 44,
            threshold: 35,
          ),
        ],
      ),
      overlayRecords: <OpenCrayMemoryDebugRecordSnapshot>[
        OpenCrayMemoryDebugRecordSnapshot(
          id: 'memory-user',
          content: 'Call the agent Xiao Bai and keep the tone warmer.',
          kind: 'user_preference',
          scope: 'user',
          status: 'active',
          source: 'user_input',
          sourceSessionId: 'session-1',
          updatedAtEpochMs: 4200,
          lastConfirmedAtEpochMs: 4200,
          preferenceKey: 'agent_display_name',
          preferenceValue: 'Xiao Bai',
          preferenceTemporality: 'durable',
          extensions: <String, String>{'soul_display_name': 'Xiao Bai'},
        ),
        OpenCrayMemoryDebugRecordSnapshot(
          id: 'memory-style',
          content: 'Keep a warmer tone.',
          kind: 'user_preference',
          scope: 'session',
          status: 'active',
          source: 'user_input',
          sourceSessionId: 'session-1',
          updatedAtEpochMs: 4250,
          lastConfirmedAtEpochMs: 4250,
          preferenceKey: 'agent_style_profile',
          preferenceValue: 'warm',
          preferenceTemporality: 'session',
          extensions: <String, String>{
            'soul_tone': 'warm',
            'soul_voice': 'warm and gentle',
            'soul_user_relationship_style': 'supportive',
          },
        ),
      ],
      fieldSources: <OpenCraySoulFieldSourceSnapshot>[
        OpenCraySoulFieldSourceSnapshot(
          field: 'presetName',
          value: 'STEADY',
          sourceType: 'stored_soul',
          sourceLabel: 'stored soul preset',
        ),
        OpenCraySoulFieldSourceSnapshot(
          field: 'displayName',
          value: 'Xiao Bai',
          sourceType: 'memory_overlay',
          sourceLabel: 'user memory',
          recordId: 'memory-user',
          preferenceKey: 'agent_display_name',
        ),
        OpenCraySoulFieldSourceSnapshot(
          field: 'tone',
          value: 'warm',
          sourceType: 'memory_overlay',
          sourceLabel: 'session memory',
          recordId: 'memory-style',
          preferenceKey: 'agent_style_profile',
        ),
        OpenCraySoulFieldSourceSnapshot(
          field: 'voice',
          value: 'warm and gentle',
          sourceType: 'memory_overlay',
          sourceLabel: 'session memory',
          recordId: 'memory-style',
          preferenceKey: 'agent_style_profile',
        ),
      ],
    ),
  );
}

ScheduledTaskDetails copyScheduledTaskDetails(
  ScheduledTaskDetails task, {
  bool? enabled,
}) => ScheduledTaskDetails(
  scheduleId: task.scheduleId,
  sessionId: task.sessionId,
  title: task.title,
  enabled: enabled ?? task.enabled,
  triggerKind: task.triggerKind,
  triggerSummary: task.triggerSummary,
  prompt: task.prompt,
  nextTriggerAtEpochMs: task.nextTriggerAtEpochMs,
  snoozedUntilEpochMs: task.snoozedUntilEpochMs,
  conflictPolicy: task.conflictPolicy,
  foregroundNotificationRequired: task.foregroundNotificationRequired,
  notifyOnQueued: task.notifyOnQueued,
  notifyOnApproval: task.notifyOnApproval,
  notifyOnCompletion: task.notifyOnCompletion,
  notifyOnInterruption: task.notifyOnInterruption,
  createdAtEpochMs: task.createdAtEpochMs,
  updatedAtEpochMs: task.updatedAtEpochMs,
);

class FakeSettingsFacade implements SettingsFacade {
  FakeSettingsFacade({
    required this.llmConfig,
    required this.validationResult,
    this.onSaveLlmConfig,
    this.onSaveNotificationSettings,
    this.onLoadScheduledTasks,
    PersonalizationConfigSnapshot? personalizationConfig,
    SettingsOverviewSnapshot? overviewSnapshot,
  }) : personalizationConfig =
           personalizationConfig ??
           const PersonalizationConfigSnapshot(
             title: 'Personalization',
             subtitle: 'Test snapshot',
             introTitle: 'Intro',
             introBody: 'Intro body',
             introHelper: 'Intro helper',
             presetsTitle: 'Presets',
             presetsHelper: 'Pick a preset',
             presets: <PersonalizationPresetOption>[
               PersonalizationPresetOption(
                 id: 'steady',
                 title: 'Steady',
                 summary: 'Direct and calm',
                 voice: 'Quiet',
                 status: 'Selected',
                 isSelected: true,
               ),
             ],
             selectedPresetId: 'steady',
             customOverlayTitle: 'Overlay',
             customOverlayHelper: 'Helper',
             customLabelHint: 'Label',
             customLabelHelper: 'Label helper',
             customGuidanceHint: 'Guidance',
             customGuidanceHelper: 'Guidance helper',
             customLabel: '',
             customGuidance: '',
             behaviorDefaultsTitle: 'Behavior defaults',
             appLanguageTitle: 'App language',
             appLanguageOptions: <PersonalizationLanguageOption>[
               PersonalizationLanguageOption(
                 id: 'en',
                 title: 'English',
                 isSelected: true,
               ),
               PersonalizationLanguageOption(
                 id: 'zh-CN',
                 title: '中文',
                 isSelected: false,
               ),
             ],
             selectedAppLanguageId: 'en',
             livePreviewTitle: 'Preview',
             livePreviewName: 'Steady',
             livePreviewSummary: 'Direct and calm',
             queueTitle: 'Queue',
             queueBody: 'Idle',
             queueIsIdle: true,
             lastResetTitle: 'Last reset',
             lastResetMessage: '',
             resetActions: <PersonalizationResetAction>[
               PersonalizationResetAction(
                 scopeId: 'memory',
                 title: 'Reset memory',
                 scopeBody: 'Scope',
                 retainBody: 'Retain',
                 confirmationToken: 'RESET MEMORY',
                 inputHint: 'Type RESET MEMORY',
                 disabledGuidance: 'Disabled',
                 typeExactGuidance: 'Type exact token',
                 armedGuidance: 'Armed',
                 isInputEnabled: true,
               ),
             ],
           ),
       overviewSnapshot =
           overviewSnapshot ??
           const SettingsOverviewSnapshot(
             eyebrow: 'APP SHELL',
             title: 'Settings',
             subtitle: 'Access, providers, and personal defaults.',
             deviceTitle: 'OpenCray on this device',
             deviceSummary: 'API routes: Search + Media',
             entries: <SettingsHomeEntrySnapshot>[],
           );

  NetworkSearchConfigSnapshot networkSearchConfig =
      const NetworkSearchConfigSnapshot(
        localeTag: 'en',
        title: 'Network & Search',
        subtitle: 'Add API keys here. Enabled slots run top to bottom.',
        slots: <NetworkSearchSlotSnapshot>[
          NetworkSearchSlotSnapshot(
            id: 'slot-1',
            providerId: 'exa',
            label: 'Primary Exa',
            baseUrl: '',
            model: '',
            apiKey: 'sk_live_demo',
            enabled: true,
          ),
          NetworkSearchSlotSnapshot(
            id: 'slot-2',
            providerId: 'tavily',
            label: 'Tavily Backup',
            baseUrl: '',
            model: '',
            apiKey: 'tvly-demo',
            enabled: true,
          ),
        ],
      );
  MediaSpeechConfigSnapshot mediaSpeechConfig = const MediaSpeechConfigSnapshot(
    localeTag: 'en',
    title: 'Media & Speech',
    subtitle: 'Configure media APIs and STT routing.',
    imageGeneration: MediaProviderConfigSnapshot(
      provider: 'Fal AI',
      baseUrl: 'https://api.fal.ai',
      endpoint: '/v1/images',
      model: 'flux-pro',
      authProtocol: 'bearer',
      apiKey: '',
    ),
    videoGeneration: MediaProviderConfigSnapshot(
      provider: 'Runway',
      baseUrl: 'https://api.runwayml.com',
      endpoint: '/v1/videos',
      model: 'gen4_turbo',
      authProtocol: 'bearer',
      apiKey: '',
    ),
    voiceGeneration: VoiceProviderConfigSnapshot(
      provider: 'OpenAI TTS',
      baseUrl: 'https://api.openai.com',
      endpoint: '/v1/audio/speech',
      model: 'tts-1',
      voicePreset: 'alloy · calm',
      authProtocol: 'bearer',
      apiKey: '',
    ),
    sttRoute: MediaSpeechSttRoute.onDeviceModel,
    externalStt: MediaProviderConfigSnapshot(
      provider: 'OpenAI Whisper',
      baseUrl: 'https://api.openai.com',
      endpoint: '/v1/audio/transcriptions',
      model: 'whisper-1',
      authProtocol: 'bearer',
      apiKey: '',
    ),
    onDeviceModel: OnDeviceSttConfigSnapshot(
      modelPackage: 'Whisper Small',
      downloadStatus: 'Not downloaded · 1.4 GB',
    ),
  );
  LlmConfigSnapshot llmConfig;
  final LlmValidationResult validationResult;
  final Future<void> Function()? onSaveLlmConfig;
  final Future<NotificationSettingsSnapshot> Function(
    NotificationSettingsSnapshot snapshot,
  )?
  onSaveNotificationSettings;
  final Future<ScheduledTasksSnapshot> Function()? onLoadScheduledTasks;
  final PersonalizationConfigSnapshot personalizationConfig;
  final McpSettingsSnapshot mcpSettings = const McpSettingsSnapshot(
    title: 'MCP',
    subtitle: 'Test snapshot',
    masterTitle: 'Enable MCP',
    masterSummary: 'Summary',
    masterEnabled: true,
    summaryLine: '1 server',
    serversTitle: 'Servers',
    serversHelper: 'Helper',
    masterDisabledTitle: 'Disabled',
    masterDisabledBody: 'Turn it on',
    servers: <McpServerSnapshot>[
      McpServerSnapshot(
        id: 'filesystem',
        title: 'Filesystem',
        statusLabel: 'Enabled',
        statusTone: 'positive',
        trustLine: 'Trust',
        authLine: 'Auth',
        readinessLine: 'Ready',
        transportLine: 'Transport',
        exposureLine: 'Exposure',
        guidance: 'Guidance',
        actionLabel: 'Disable',
        actionTurnsOn: false,
        isActionEnabled: true,
      ),
    ],
  );
  SafetySettingsSnapshot safetySettings = const SafetySettingsSnapshot(
    automationMode: SafetyAutomationMode.auto,
    rollbackJournalEnabled: true,
    maxFilesPerBatch: 20,
    undoWindowHours: 24,
    fileChangesPolicy: ToolPolicyOverride.inherit,
    fileDeletesPolicy: ToolPolicyOverride.inherit,
    shellCommandsPolicy: ToolPolicyOverride.inherit,
    externalAccessMode: ExternalAccessMode.selectPaths,
    locations: <SafetyLocationSetting>[
      SafetyLocationSetting(id: 'photo_library', enabled: true),
      SafetyLocationSetting(id: 'downloads', enabled: true),
      SafetyLocationSetting(id: 'documents', enabled: false),
      SafetyLocationSetting(id: 'recordings', enabled: false),
    ],
    workspaceAccessProfile: WorkspaceAccessProfile.work,
    readOnlyOutsideWorkspace: true,
  );
  SandboxSettingsSnapshot sandboxSettings = const SandboxSettingsSnapshot(
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
  NotificationSettingsSnapshot notificationSettings =
      const NotificationSettingsSnapshot(
        masterEnabled: true,
        defaultDeliveryMode: NotificationDeliveryMode.all,
        quietHoursEnabled: true,
        quietHoursStartMinutes: 23 * 60,
        quietHoursEndMinutes: 8 * 60,
        approvalRequestsEnabled: true,
        approvalReminderEnabled: true,
        taskFinishedEnabled: true,
        taskFailedEnabled: true,
        scheduledWakeEnabled: true,
        backgroundTaskPausedEnabled: true,
        serviceRecoveredEnabled: true,
      );
  ScheduledTasksSnapshot scheduledTasks = const ScheduledTasksSnapshot(
    tasks: <ScheduledTaskSummary>[],
    totalCount: 0,
    enabledCount: 0,
  );
  ScheduledTaskDetailSnapshot? scheduledTaskDetail;
  final List<bool> scheduledTaskEnabledRequests = <bool>[];
  int scheduledTaskRunNowCallCount = 0;
  int scheduledTaskSnoozeCallCount = 0;
  StrongBackgroundSnapshot strongBackgroundSnapshot =
      const StrongBackgroundSnapshot(
        source: 'strong-background',
        available: true,
        tier: StrongBackgroundTier.baseline,
        setupComplete: false,
        recommendedActionIds: <StrongBackgroundActionId>[
          StrongBackgroundActionId.openNotificationSettings,
        ],
        notifications: StrongBackgroundNotificationsSnapshot(
          permissionRequired: true,
          permissionGranted: false,
          enabled: false,
          configured: false,
        ),
        exactAlarms: StrongBackgroundExactAlarmSnapshot(
          accessRequired: true,
          accessGranted: false,
          configured: false,
        ),
        batteryOptimization: StrongBackgroundBatteryOptimizationSnapshot(
          supported: true,
          exempt: false,
          configured: false,
        ),
        actions: <StrongBackgroundActionSnapshot>[
          StrongBackgroundActionSnapshot(
            id: StrongBackgroundActionId.openNotificationSettings,
            available: true,
            recommended: true,
          ),
        ],
      );
  final Map<String, bool> authorizationResponses = <String, bool>{};
  final List<String> authorizationRequests = <String>[];
  final List<StrongBackgroundActionId> strongBackgroundActionRequests =
      <StrongBackgroundActionId>[];
  final SettingsOverviewSnapshot overviewSnapshot;
  int saveCallCount = 0;
  int validationCallCount = 0;
  int notificationSaveCallCount = 0;
  final List<NotificationSettingsSnapshot> notificationSaveRequests =
      <NotificationSettingsSnapshot>[];
  int sandboxSaveCallCount = 0;
  int safetySaveCallCount = 0;

  @override
  Future<SettingsOverviewSnapshot> loadOverview() async => overviewSnapshot;

  @override
  Stream<SettingsOverviewSnapshot> watchOverview() =>
      Stream<SettingsOverviewSnapshot>.empty();

  @override
  Future<SettingsDetailSnapshot> loadDetail(
    SettingsPage page,
  ) async => SettingsDetailSnapshot(
    page: page,
    title: switch (page) {
      SettingsPage.notificationsBackground => 'Notifications & Background',
      SettingsPage.eventAlerts => 'Event Alerts',
      SettingsPage.privacyTelemetry => 'Privacy & Telemetry',
      SettingsPage.aboutVersion => 'About & Version',
      _ => '',
    },
    subtitle: switch (page) {
      SettingsPage.notificationsBackground =>
        'Control alerts, service visibility, and wakeups.',
      SettingsPage.eventAlerts =>
        'Choose which app events can publish a new alert.',
      SettingsPage.privacyTelemetry =>
        'Review what stays on device and what diagnostic signals are shared.',
      SettingsPage.aboutVersion => 'Build information and app diagnostics.',
      _ => '',
    },
    sections: page == SettingsPage.aboutVersion
        ? const <SettingsSectionSnapshot>[
            SettingsSectionSnapshot(
              title: 'Version',
              rows: <SettingsRowSnapshot>[
                SettingsRowSnapshot.value(
                  title: 'Installed version',
                  valueLabel: '1.0.0',
                ),
              ],
            ),
          ]
        : page == SettingsPage.privacyTelemetry
        ? const <SettingsSectionSnapshot>[
            SettingsSectionSnapshot(
              title: 'Diagnostics',
              rows: <SettingsRowSnapshot>[
                SettingsRowSnapshot.toggle(
                  title: 'Share crash diagnostics',
                  subtitle: 'Include app and runtime failure summaries only.',
                  toggleValue: false,
                ),
              ],
            ),
          ]
        : const <SettingsSectionSnapshot>[],
  );

  @override
  Future<NotificationSettingsSnapshot> loadNotificationSettings() async =>
      notificationSettings;

  @override
  Future<NotificationSettingsSnapshot> saveNotificationSettings(
    NotificationSettingsSnapshot snapshot,
  ) async {
    notificationSaveCallCount += 1;
    notificationSaveRequests.add(snapshot);
    notificationSettings =
        await onSaveNotificationSettings?.call(snapshot) ?? snapshot;
    return notificationSettings;
  }

  @override
  Future<ScheduledTasksSnapshot> loadScheduledTasks() async {
    final loader = onLoadScheduledTasks;
    return loader == null ? scheduledTasks : loader();
  }

  @override
  Future<ScheduledTaskDetailSnapshot> loadScheduledTask(
    String scheduleId,
  ) async {
    final detail = scheduledTaskDetail;
    if (detail == null || detail.task.scheduleId != scheduleId) {
      throw StateError('Scheduled task $scheduleId was not found.');
    }
    return detail;
  }

  @override
  Future<ScheduledTaskActionResult> updateScheduledTaskEnabled({
    required String scheduleId,
    required bool enabled,
  }) async {
    scheduledTaskEnabledRequests.add(enabled);
    final updatedTasks = scheduledTasks.tasks
        .map(
          (task) => task.scheduleId == scheduleId
              ? ScheduledTaskSummary(
                  scheduleId: task.scheduleId,
                  sessionId: task.sessionId,
                  title: task.title,
                  enabled: enabled,
                  triggerKind: task.triggerKind,
                  triggerSummary: task.triggerSummary,
                  nextTriggerAtEpochMs: task.nextTriggerAtEpochMs,
                  snoozedUntilEpochMs: task.snoozedUntilEpochMs,
                )
              : task,
        )
        .toList(growable: false);
    scheduledTasks = ScheduledTasksSnapshot(
      tasks: updatedTasks,
      totalCount: scheduledTasks.totalCount,
      enabledCount: updatedTasks.where((task) => task.enabled).length,
    );
    final detail = scheduledTaskDetail;
    if (detail != null && detail.task.scheduleId == scheduleId) {
      scheduledTaskDetail = ScheduledTaskDetailSnapshot(
        task: copyScheduledTaskDetails(detail.task, enabled: enabled),
        recentRuns: detail.recentRuns,
        totalRunCount: detail.totalRunCount,
      );
    }
    return ScheduledTaskActionResult(
      action: 'update_enabled',
      scheduleId: scheduleId,
      title: detail?.task.title ?? '',
      enabled: enabled,
    );
  }

  @override
  Future<ScheduledTaskActionResult> runScheduledTaskNow(
    String scheduleId,
  ) async {
    scheduledTaskRunNowCallCount += 1;
    return ScheduledTaskActionResult(
      action: 'run_now',
      scheduleId: scheduleId,
      title: scheduledTaskDetail?.task.title ?? '',
      scheduleRunId: 'schedule-run-$scheduledTaskRunNowCallCount',
    );
  }

  @override
  Future<ScheduledTaskActionResult> snoozeScheduledTask({
    required String scheduleId,
    int durationMinutes = 15,
  }) async {
    scheduledTaskSnoozeCallCount += 1;
    return ScheduledTaskActionResult(
      action: 'snooze',
      scheduleId: scheduleId,
      title: scheduledTaskDetail?.task.title ?? '',
      snoozedUntilEpochMs: DateTime.now()
          .add(Duration(minutes: durationMinutes))
          .millisecondsSinceEpoch,
    );
  }

  @override
  Future<StrongBackgroundSnapshot> loadStrongBackgroundSnapshot() async =>
      strongBackgroundSnapshot;

  @override
  Future<StrongBackgroundActionResult> performStrongBackgroundAction(
    StrongBackgroundActionId actionId,
  ) async {
    strongBackgroundActionRequests.add(actionId);
    return StrongBackgroundActionResult(
      source: 'strong-background-action',
      actionId: actionId,
      available: true,
      launched: true,
    );
  }

  @override
  Future<NetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      networkSearchConfig;

  @override
  Future<NetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<NetworkSearchSlotSnapshot> slots,
  ) async {
    networkSearchConfig = NetworkSearchConfigSnapshot(
      localeTag: networkSearchConfig.localeTag,
      title: networkSearchConfig.title,
      subtitle: networkSearchConfig.subtitle,
      slots: slots,
    );
    return networkSearchConfig;
  }

  @override
  Future<MediaSpeechConfigSnapshot> loadMediaSpeechConfig() async =>
      mediaSpeechConfig;

  @override
  Future<MediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    MediaSpeechConfigSnapshot snapshot,
  ) async {
    mediaSpeechConfig = snapshot;
    return mediaSpeechConfig;
  }

  @override
  Future<SandboxSettingsSnapshot> loadSandboxSettings() async =>
      sandboxSettings;

  @override
  Future<SandboxSettingsSnapshot> saveSandboxSettings(
    SandboxSettingsSnapshot snapshot,
  ) async {
    sandboxSaveCallCount += 1;
    sandboxSettings = snapshot.copyWith(
      apiKeyConfigured:
          snapshot.e2bApiKey.trim().isNotEmpty || snapshot.apiKeyConfigured,
    );
    return sandboxSettings;
  }

  @override
  Future<LlmConfigSnapshot> loadLlmConfig() async => llmConfig;

  @override
  Future<LlmConfigSnapshot> saveLlmConfig({
    required bool enabled,
    bool? streamingEnabled,
    String providerMode = 'cloud',
    required String providerId,
    required String selectedProviderOptionId,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
    String? openAiPromptCacheKeyStrategy,
    String? openAiPromptCacheRetention,
    bool? anthropicPromptCachingEnabled,
    String? anthropicPromptCacheTtl,
    String selectedOnDeviceModelId = 'gemma-4-e2b-it',
    int onDeviceMaxContextWindow = 32768,
    int onDeviceMaxTokens = 4096,
    int onDeviceTopK = 40,
    double onDeviceTopP = 0.95,
    double onDeviceTemperature = 0.70,
    String onDeviceAccelerator = 'gpu',
    bool onDeviceThinkingEnabled = false,
    bool onDeviceLiteModeEnabled = false,
    String? contextBudgetPreset,
    int? contextBudgetReservedOutputTokens,
    int? contextBudgetSafetyMarginTokens,
    double? contextBudgetEffectiveInputPercent,
    int? contextWindowTokensOverride,
  }) async {
    saveCallCount += 1;
    await onSaveLlmConfig?.call();
    final hasExplicitContextBudgetPayload = contextBudgetPreset != null;
    llmConfig = LlmConfigSnapshot(
      localeTag: llmConfig.localeTag,
      enabled: enabled,
      streamingEnabled: streamingEnabled ?? llmConfig.streamingEnabled,
      providerMode: providerMode,
      providerId: providerId,
      selectedProviderOptionId: selectedProviderOptionId,
      protocol: protocol,
      providerOptions: llmConfig.providerOptions,
      providerName: providerName,
      providerNotes: providerNotes,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
      helperText: llmConfig.helperText,
      openAiPromptCacheKeyStrategy:
          openAiPromptCacheKeyStrategy ??
          llmConfig.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention:
          openAiPromptCacheRetention ?? llmConfig.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled:
          anthropicPromptCachingEnabled ??
          llmConfig.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl:
          anthropicPromptCacheTtl ?? llmConfig.anthropicPromptCacheTtl,
      manualContextWindowTokens:
          contextWindowTokensOverride ?? llmConfig.manualContextWindowTokens,
      resolvedContextWindowTokens:
          contextWindowTokensOverride ?? llmConfig.resolvedContextWindowTokens,
      onDeviceModels: llmConfig.onDeviceModels,
      selectedOnDeviceModelId: selectedOnDeviceModelId,
      onDeviceMaxContextWindow: onDeviceMaxContextWindow,
      onDeviceMaxTokens: onDeviceMaxTokens,
      onDeviceTopK: onDeviceTopK,
      onDeviceTopP: onDeviceTopP,
      onDeviceTemperature: onDeviceTemperature,
      onDeviceAccelerator: onDeviceAccelerator,
      onDeviceThinkingEnabled: onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled: onDeviceLiteModeEnabled,
      contextBudgetPreset: contextBudgetPreset ?? llmConfig.contextBudgetPreset,
      contextBudgetReservedOutputTokens: hasExplicitContextBudgetPayload
          ? contextBudgetReservedOutputTokens
          : llmConfig.contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens: hasExplicitContextBudgetPayload
          ? contextBudgetSafetyMarginTokens
          : llmConfig.contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent: hasExplicitContextBudgetPayload
          ? contextBudgetEffectiveInputPercent
          : llmConfig.contextBudgetEffectiveInputPercent,
    );
    return llmConfig;
  }

  @override
  Future<LlmConfigSnapshot> saveCustomLlmProvider({
    required String selectedProviderOptionId,
    bool? streamingEnabled,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
    String? openAiPromptCacheKeyStrategy,
    String? openAiPromptCacheRetention,
    bool? anthropicPromptCachingEnabled,
    String? anthropicPromptCacheTtl,
    int? contextWindowTokensOverride,
  }) async {
    saveCallCount += 1;
    final savedOptionId = selectedProviderOptionId == 'custom'
        ? 'saved-custom'
        : selectedProviderOptionId;
    final savedOption = LlmProviderOption(
      id: savedOptionId,
      providerId: 'custom',
      title: providerName,
      subtitle: providerNotes,
      defaultBaseUrl: baseUrl,
      defaultModel: model,
      protocol: protocol,
      apiKey: apiKey,
      isCustom: true,
    );
    llmConfig = LlmConfigSnapshot(
      localeTag: llmConfig.localeTag,
      enabled:
          baseUrl.isNotEmpty &&
          (apiKey.isNotEmpty ||
              llmEndpointAllowsBlankApiKeyForTest(
                protocol: protocol,
                baseUrl: baseUrl,
              )),
      streamingEnabled: streamingEnabled ?? llmConfig.streamingEnabled,
      providerMode: 'cloud',
      providerId: 'custom',
      selectedProviderOptionId: savedOptionId,
      protocol: protocol,
      providerOptions: <LlmProviderOption>[
        for (final option in llmConfig.providerOptions)
          if (option.id != savedOptionId) option,
        savedOption,
      ],
      providerName: providerName,
      providerNotes: providerNotes,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
      helperText: llmConfig.helperText,
      openAiPromptCacheKeyStrategy:
          openAiPromptCacheKeyStrategy ??
          llmConfig.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention:
          openAiPromptCacheRetention ?? llmConfig.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled:
          anthropicPromptCachingEnabled ??
          llmConfig.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl:
          anthropicPromptCacheTtl ?? llmConfig.anthropicPromptCacheTtl,
      manualContextWindowTokens:
          contextWindowTokensOverride ?? llmConfig.manualContextWindowTokens,
      resolvedContextWindowTokens:
          contextWindowTokensOverride ?? llmConfig.resolvedContextWindowTokens,
      onDeviceModels: llmConfig.onDeviceModels,
      selectedOnDeviceModelId: llmConfig.selectedOnDeviceModelId,
      onDeviceMaxContextWindow: llmConfig.onDeviceMaxContextWindow,
      onDeviceMaxTokens: llmConfig.onDeviceMaxTokens,
      onDeviceTopK: llmConfig.onDeviceTopK,
      onDeviceTopP: llmConfig.onDeviceTopP,
      onDeviceTemperature: llmConfig.onDeviceTemperature,
      onDeviceAccelerator: llmConfig.onDeviceAccelerator,
      onDeviceThinkingEnabled: llmConfig.onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled: llmConfig.onDeviceLiteModeEnabled,
      contextBudgetPreset: llmConfig.contextBudgetPreset,
      contextBudgetReservedOutputTokens:
          llmConfig.contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens:
          llmConfig.contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent:
          llmConfig.contextBudgetEffectiveInputPercent,
    );
    return llmConfig;
  }

  @override
  Future<LlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    int? contextWindowTokensOverride,
  }) async {
    validationCallCount += 1;
    return validationResult;
  }

  @override
  Future<LlmConfigSnapshot> downloadOnDeviceLlmModel(String modelId) async {
    llmConfig = llmConfig.copyWith(
      onDeviceModels: llmConfig.onDeviceModels
          .map(
            (option) => option.id == modelId
                ? LlmOnDeviceModelOption(
                    id: option.id,
                    title: option.title,
                    subtitle: option.subtitle,
                    sizeLabel: option.sizeLabel,
                    fileSizeBytes: option.fileSizeBytes,
                    installState: 'ready',
                    downloadedBytes: option.fileSizeBytes,
                    downloadBytesPerSecond: 0,
                    sha256Verified: true,
                    isSelected: option.isSelected,
                    lastError: null,
                  )
                : option,
          )
          .toList(growable: false),
    );
    return llmConfig;
  }

  @override
  Future<LlmConfigSnapshot> cancelOnDeviceLlmModelDownload(
    String modelId,
  ) async => llmConfig;

  @override
  Future<LlmConfigSnapshot> deleteOnDeviceLlmModel(String modelId) async {
    llmConfig = llmConfig.copyWith(
      onDeviceModels: llmConfig.onDeviceModels
          .map(
            (option) => option.id == modelId
                ? LlmOnDeviceModelOption(
                    id: option.id,
                    title: option.title,
                    subtitle: option.subtitle,
                    sizeLabel: option.sizeLabel,
                    fileSizeBytes: option.fileSizeBytes,
                    installState: 'not_downloaded',
                    downloadedBytes: 0,
                    downloadBytesPerSecond: 0,
                    sha256Verified: false,
                    isSelected: option.isSelected,
                    lastError: null,
                  )
                : option,
          )
          .toList(growable: false),
    );
    return llmConfig;
  }

  @override
  Future<PersonalizationConfigSnapshot> loadPersonalizationConfig() async =>
      personalizationConfig;

  @override
  Future<PersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async => personalizationConfig;

  @override
  Future<PersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async => personalizationConfig;

  @override
  Future<PersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async => personalizationConfig;

  @override
  Future<McpSettingsSnapshot> loadMcpSettings() async => mcpSettings;

  @override
  Future<McpSettingsSnapshot> setMcpMasterEnabled(bool enabled) async =>
      mcpSettings;

  @override
  Future<McpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  }) async => mcpSettings;

  @override
  Future<SafetySettingsSnapshot> loadSafetySettings() async => safetySettings;

  @override
  Future<bool> authorizeExternalAccessLocation(String locationId) async {
    authorizationRequests.add(locationId);
    return authorizationResponses[locationId] ?? true;
  }

  @override
  Future<SafetySettingsSnapshot> saveSafetySettings(
    SafetySettingsSnapshot snapshot,
  ) async {
    safetySaveCallCount += 1;
    safetySettings = snapshot;
    return safetySettings;
  }
}

bool llmEndpointAllowsBlankApiKeyForTest({
  required String protocol,
  required String baseUrl,
}) {
  final normalizedProtocol = protocol.trim().toLowerCase();
  if (normalizedProtocol != 'openai' &&
      normalizedProtocol != 'openai_responses') {
    return false;
  }
  final host = (Uri.tryParse(baseUrl.trim())?.host ?? '').trim().toLowerCase();
  if (host.isEmpty) {
    return false;
  }
  if (host == 'localhost' ||
      host == 'localhost.localdomain' ||
      host == '0.0.0.0' ||
      host == '::1' ||
      host == '10.0.2.2' ||
      host == 'host.docker.internal' ||
      host.endsWith('.local')) {
    return true;
  }
  if (host.startsWith('127.') ||
      host.startsWith('10.') ||
      host.startsWith('192.168.')) {
    return true;
  }
  if (!host.startsWith('172.')) {
    return false;
  }
  final parts = host.split('.');
  if (parts.length < 2) {
    return false;
  }
  final secondOctet = int.tryParse(parts[1]);
  return secondOctet != null && secondOctet >= 16 && secondOctet <= 31;
}

class FakeDebugBridge extends OpenCraySeedBridge {
  FakeDebugBridge({
    required this.shellSnapshot,
    required this.runtimeSnapshot,
    required Map<String, OpenCrayChatRunSnapshot> runSnapshots,
    required this.memorySnapshot,
    required this.linksSnapshot,
    required this.memorySearchSnapshot,
    required this.memorySliceSnapshot,
    required this.soulSnapshot,
    List<List<OpenCraySettingsImageAsset>> pickedSettingsImageAssetBatches =
        const <List<OpenCraySettingsImageAsset>>[],
  }) : _runSnapshots = runSnapshots,
       _pickedSettingsImageAssetBatches = pickedSettingsImageAssetBatches
           .map(
             (batch) =>
                 List<OpenCraySettingsImageAsset>.from(batch, growable: false),
           )
           .toList(growable: true),
       _currentRuntimeSnapshot = runtimeSnapshot,
       _currentMemorySnapshot = memorySnapshot,
       _currentLinksSnapshot = linksSnapshot,
       _currentMemorySearchSnapshot = memorySearchSnapshot,
       _currentMemorySliceSnapshot = memorySliceSnapshot,
       _seedSoulSnapshot = soulSnapshot,
       _currentSoulSnapshot = soulSnapshot,
       super(initialSnapshot: shellSnapshot);

  final OpenCrayShellSnapshot shellSnapshot;
  final OpenCrayChatRuntimeSnapshot runtimeSnapshot;
  final Map<String, OpenCrayChatRunSnapshot> _runSnapshots;
  final OpenCrayMemoryDebugSnapshot memorySnapshot;
  final OpenCrayMemoryDebugLinksSnapshot linksSnapshot;
  final OpenCrayMemoryDebugSearchSnapshot memorySearchSnapshot;
  final OpenCrayMemoryDebugSliceSnapshot memorySliceSnapshot;
  final OpenCraySoulDebugSnapshot soulSnapshot;
  final List<List<OpenCraySettingsImageAsset>> _pickedSettingsImageAssetBatches;
  final OpenCrayChatRuntimeSnapshot _currentRuntimeSnapshot;
  OpenCrayMemoryDebugSnapshot _currentMemorySnapshot;
  OpenCrayMemoryDebugLinksSnapshot _currentLinksSnapshot;
  final OpenCrayMemoryDebugSearchSnapshot _currentMemorySearchSnapshot;
  final OpenCrayMemoryDebugSliceSnapshot _currentMemorySliceSnapshot;
  final OpenCraySoulDebugSnapshot _seedSoulSnapshot;
  OpenCraySoulDebugSnapshot _currentSoulSnapshot;
  int pickSettingsImageAssetsCallCount = 0;
  String? lastMemorySearchQuery;
  String? lastMemorySlicePath;
  int? lastMemorySliceFromLine;
  int? lastMemorySliceLines;
  String? lastMemoryActionRecordId;
  String? lastMemoryActionId;
  final List<OpenCrayTab> persistedShellTabs = <OpenCrayTab>[];
  final List<String?> persistedSettingsRouteIds = <String?>[];

  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() async => shellSnapshot;

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) async {
    persistedShellTabs.add(
      OpenCrayTab.values.firstWhere(
        (tab) =>
            selectedTab == tab.routeSegment || selectedTab == tab.routeName,
        orElse: () => OpenCrayTab.chat,
      ),
    );
    persistedSettingsRouteIds.add(settingsSubpage);
  }

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      _currentRuntimeSnapshot;

  @override
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async =>
      _runSnapshots[runId];

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      _currentMemorySnapshot;

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async => _currentLinksSnapshot;

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      _currentSoulSnapshot;

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async {
    pickSettingsImageAssetsCallCount += 1;
    if (_pickedSettingsImageAssetBatches.isEmpty) {
      return const <OpenCraySettingsImageAsset>[];
    }
    return _pickedSettingsImageAssetBatches.removeAt(0);
  }

  @override
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async {
    lastMemorySearchQuery = query;
    return OpenCrayMemoryDebugSearchSnapshot(
      sessionId: _currentMemorySearchSnapshot.sessionId,
      workspaceId: _currentMemorySearchSnapshot.workspaceId,
      observedAtEpochMs: _currentMemorySearchSnapshot.observedAtEpochMs,
      query: query,
      queryTerms: _currentMemorySearchSnapshot.queryTerms,
      corpusFileCount: _currentMemorySearchSnapshot.corpusFileCount,
      results: _currentMemorySearchSnapshot.results,
    );
  }

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async {
    lastMemorySlicePath = path;
    lastMemorySliceFromLine = fromLine;
    lastMemorySliceLines = lines;
    return _currentMemorySliceSnapshot;
  }

  @override
  Future<void> applyMemoryDebugAction({
    required String recordId,
    required String actionId,
  }) async {
    lastMemoryActionRecordId = recordId;
    lastMemoryActionId = actionId;
    final records = _currentMemorySnapshot.records
        .map(
          (record) => record.id == recordId
              ? _applyMemoryRecordAction(record: record, actionId: actionId)
              : record,
        )
        .toList(growable: false);
    _currentMemorySnapshot = OpenCrayMemoryDebugSnapshot(
      sessionId: _currentMemorySnapshot.sessionId,
      workspaceId: _currentMemorySnapshot.workspaceId,
      observedAtEpochMs: _currentMemorySnapshot.observedAtEpochMs + 1,
      records: records,
    );
    final occurredAtEpochMs = _currentLinksSnapshot.observedAtEpochMs + 1;
    final run = OpenCrayDebugRunLinkSnapshot(
      sessionId: _currentMemorySnapshot.sessionId,
      runId: 'run-memory-debug-$actionId',
      taskId: 'task-memory-debug-$actionId',
      acceptedAtEpochMs: occurredAtEpochMs,
      updatedAtEpochMs: occurredAtEpochMs,
      executionStatus: 'success',
      lifecycleState: 'completed',
    );
    final existingEntry = _currentLinksSnapshot.records
        .where((entry) => entry.recordId == recordId)
        .cast<OpenCrayMemoryDebugLinksEntrySnapshot?>()
        .firstWhere((entry) => entry != null, orElse: () => null);
    final nextEntry = OpenCrayMemoryDebugLinksEntrySnapshot(
      recordId: recordId,
      sourceSessionId:
          existingEntry?.sourceSessionId ?? _currentMemorySnapshot.sessionId,
      sourceTaskId: existingEntry?.sourceTaskId ?? '',
      sourceRun: existingEntry?.sourceRun,
      promptRecalls:
          existingEntry?.promptRecalls ??
          const <OpenCrayMemoryPromptRecallLinkSnapshot>[],
      toolRetrievals:
          existingEntry?.toolRetrievals ??
          const <OpenCrayMemoryToolRetrievalLinkSnapshot>[],
      maintenanceActions: <OpenCrayMemoryMaintenanceActionLinkSnapshot>[
        ...?existingEntry?.maintenanceActions,
        OpenCrayMemoryMaintenanceActionLinkSnapshot(
          action: actionId == 'suppress' ? 'suppressed' : 'reaffirmed',
          occurredAtEpochMs: occurredAtEpochMs,
          run: run,
        ),
      ],
    );
    final nextLinkEntries = <OpenCrayMemoryDebugLinksEntrySnapshot>[
      for (final entry in _currentLinksSnapshot.records)
        if (entry.recordId == recordId) nextEntry else entry,
      if (existingEntry == null) nextEntry,
    ];
    _currentLinksSnapshot = OpenCrayMemoryDebugLinksSnapshot(
      sessionId: _currentLinksSnapshot.sessionId,
      workspaceId: _currentLinksSnapshot.workspaceId,
      observedAtEpochMs: occurredAtEpochMs,
      records: nextLinkEntries,
    );
    final seedFieldSources = _seedSoulSnapshot.fieldSources;
    final seedOverlayRecords = _seedSoulSnapshot.overlayRecords;
    final nextFieldSources = actionId == 'suppress'
        ? _currentSoulSnapshot.fieldSources
              .where((source) => source.recordId != recordId)
              .toList(growable: false)
        : <OpenCraySoulFieldSourceSnapshot>[
            ..._currentSoulSnapshot.fieldSources,
            ...seedFieldSources.where(
              (source) =>
                  source.recordId == recordId &&
                  !_currentSoulSnapshot.fieldSources.contains(source),
            ),
          ];
    final nextOverlayRecords = actionId == 'suppress'
        ? _currentSoulSnapshot.overlayRecords
              .where((record) => record.id != recordId)
              .toList(growable: false)
        : <OpenCrayMemoryDebugRecordSnapshot>[
            ..._currentSoulSnapshot.overlayRecords,
            ...seedOverlayRecords.where(
              (overlay) =>
                  overlay.id == recordId &&
                  !_currentSoulSnapshot.overlayRecords.contains(overlay),
            ),
          ];
    _currentSoulSnapshot = OpenCraySoulDebugSnapshot(
      sessionId: _currentSoulSnapshot.sessionId,
      workspaceId: _currentSoulSnapshot.workspaceId,
      observedAtEpochMs: _currentSoulSnapshot.observedAtEpochMs + 1,
      storedSoul: _currentSoulSnapshot.storedSoul,
      baseSoul: _currentSoulSnapshot.baseSoul,
      effectiveSoul: _currentSoulSnapshot.effectiveSoul,
      overlayRecords: nextOverlayRecords,
      fieldSources: nextFieldSources,
      interactionPreferenceDebug:
          _currentSoulSnapshot.interactionPreferenceDebug,
      relationshipStateDebug: _currentSoulSnapshot.relationshipStateDebug,
    );
  }

  OpenCrayMemoryDebugRecordSnapshot _applyMemoryRecordAction({
    required OpenCrayMemoryDebugRecordSnapshot record,
    required String actionId,
  }) {
    const nextEpochMs = 6001;
    switch (actionId) {
      case 'suppress':
        return OpenCrayMemoryDebugRecordSnapshot(
          id: record.id,
          content: record.content,
          kind: record.kind,
          scope: record.scope,
          status: 'resolved',
          source: record.source,
          sourceSessionId: record.sourceSessionId,
          sourceTaskId: record.sourceTaskId,
          workspaceId: record.workspaceId,
          preferenceKey: record.preferenceKey,
          preferenceValue: record.preferenceValue,
          preferenceTemporality: record.preferenceTemporality,
          createdAtEpochMs: record.createdAtEpochMs,
          updatedAtEpochMs: nextEpochMs,
          lastConfirmedAtEpochMs: record.lastConfirmedAtEpochMs,
          resolvedAtEpochMs: nextEpochMs,
          ttlMs: record.ttlMs,
          isExpired: record.isExpired,
          recordVersion: record.recordVersion + 1,
          resolutionReason: 'operator_suppressed',
          supersededBy: record.supersededBy,
          tags: record.tags,
          extensions: record.extensions,
        );
      case 'reaffirm':
        return OpenCrayMemoryDebugRecordSnapshot(
          id: record.id,
          content: record.content,
          kind: record.kind,
          scope: record.scope,
          status: record.kind == 'task_commitment' ? 'open' : 'active',
          source: record.source,
          sourceSessionId: record.sourceSessionId,
          sourceTaskId: record.sourceTaskId,
          workspaceId: record.workspaceId,
          preferenceKey: record.preferenceKey,
          preferenceValue: record.preferenceValue,
          preferenceTemporality: record.preferenceTemporality,
          createdAtEpochMs: record.createdAtEpochMs,
          updatedAtEpochMs: nextEpochMs,
          lastConfirmedAtEpochMs: nextEpochMs,
          resolvedAtEpochMs: null,
          ttlMs: record.ttlMs,
          isExpired: record.isExpired,
          recordVersion: record.recordVersion + 1,
          resolutionReason: '',
          supersededBy: '',
          tags: record.tags,
          extensions: record.extensions,
        );
      default:
        throw StateError('Unsupported memory debug action $actionId');
    }
  }
}
