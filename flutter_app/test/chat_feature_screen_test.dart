import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_debug_snapshot.dart';
import 'package:opencray/features/chat/chat_feature.dart';

void main() {
  test(
    'resolveChatRuntimeSnapshot prefers the settled snapshot when versions tie',
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

      expect(resolved, same(embedded));
      expect(
        runtimeSnapshotVersion(embedded),
        runtimeSnapshotVersion(streamed),
      );
    },
  );

  testWidgets('running card opens a full-screen view on double tap', (
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
    expect(find.textContaining('Read README.md lines 5-6'), findsWidgets);
    expect(find.textContaining('"file_path": "README.md"'), findsOneWidget);
    expect(
      find.textContaining(
        'Project uses the Gradle wrapper from the repo root.',
      ),
      findsOneWidget,
    );
  });

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
          matching: find.textContaining('"file_path": "README.md"'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('"offset": 5'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('"limit": 2'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Use .\\\\gradlew.bat test to run JVM tests.',
          ),
        ),
        findsOneWidget,
      );
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
        chatSnapshot: _hostChatSnapshot(),
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

      expect(find.text(copy.chatRunWaitingApprovalLabel), findsOneWidget);
      expect(
        find.textContaining('Command: git status --short'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Approval is required before Bash can run.'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'host-mapped run trace shows public progress summaries in compact and full-screen views',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      const progressEvent = OpenCrayChatRuntimeEventSnapshot(
        kind: 'progress',
        runId: 'run-progress-1',
        taskId: 'task-progress-1',
        emittedAtEpochMs: 2200,
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
              messageId: 'runtime-progress-run-progress-1-2200',
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
              lastEvent: progressEvent,
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
            progressEvent,
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
      expect(find.text('Planning'), findsOneWidget);
      expect(
        find.byKey(
          const ValueKey<String>(
            'chat-bubble-runtime-progress-run-progress-1-2200',
          ),
        ),
        findsOneWidget,
      );

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-progress-1'),
      );
      final center = tester.getCenter(bubbleFinder);

      await tester.tapAt(center);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tapAt(center);
      await tester.pumpAndSettle();

      final fullscreenScrollFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-scroll-run-progress-1',
        ),
      );

      expect(
        find.descendant(
          of: fullscreenScrollFinder,
          matching: find.text('Planning'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenScrollFinder,
          matching: find.textContaining(
            'Scanning README and Gradle files before choosing the next tool.',
          ),
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

      expect(
        find.textContaining(
          'Memory maintenance: wrote 1 record, resolved 1 record, reaffirmed 1 record, expired 1 record',
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining('Resolved: commitment-done-1'),
        findsOneWidget,
      );

      final bubbleFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-run-memory-write-1'),
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

  testWidgets('running card body scrolls independently', (tester) async {
    await tester.pumpWidget(_buildChatHarness());
    await tester.pumpAndSettle();

    final bubbleFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-run-1'),
    );
    final scrollableFinder = find.descendant(
      of: bubbleFinder,
      matching: find.byType(Scrollable),
    );
    final scrollableStateBefore = tester.state<ScrollableState>(
      scrollableFinder,
    );

    expect(scrollableStateBefore.position.pixels, 0);

    await tester.drag(
      find.byKey(const ValueKey<String>('chat-run-trace-scroll-run-1')),
      const Offset(0, -180),
    );
    await tester.pumpAndSettle();

    final scrollableStateAfter = tester.state<ScrollableState>(
      scrollableFinder,
    );
    expect(scrollableStateAfter.position.pixels, greaterThan(0));
  });

  testWidgets('full-screen running card body scrolls independently', (
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

    final fullscreenScrollFinder = find.byKey(
      const ValueKey<String>('chat-run-trace-fullscreen-scroll-run-1'),
    );
    final fullscreenScrollableFinder = find.descendant(
      of: find.byKey(const ValueKey<String>('chat-run-trace-fullscreen-run-1')),
      matching: find.byType(Scrollable),
    );
    final scrollableStateBefore = tester.state<ScrollableState>(
      fullscreenScrollableFinder,
    );

    expect(scrollableStateBefore.position.pixels, 0);

    await tester.drag(fullscreenScrollFinder, const Offset(0, -220));
    await tester.pumpAndSettle();

    final scrollableStateAfter = tester.state<ScrollableState>(
      fullscreenScrollableFinder,
    );
    expect(scrollableStateAfter.position.pixels, greaterThan(0));
  });

  testWidgets(
    'approval card is rendered inline with the running trace instead of at the top',
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

      expect(find.text(copy.chatPendingApprovalsTitle), findsNothing);
      expect(find.text('Approval required'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('chat-run-trace-run-1')),
        findsOneWidget,
      );
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

      expect(find.text('Approval required'), findsOneWidget);
      expect(find.text('Bash'), findsOneWidget);
      expect(find.text('Request'), findsOneWidget);
      expect(find.text('git status --short'), findsOneWidget);
      expect(find.text('Working directory'), findsOneWidget);
      expect(find.text('.'), findsOneWidget);
      expect(find.text('Agent reason'), findsOneWidget);
      expect(
        find.text('Check repository state before editing.'),
        findsOneWidget,
      );
      expect(
        find.text('Approval is required before Bash can run.'),
        findsOneWidget,
      );

      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();

      expect(bridge.approvedApprovalIds, <String>['run-approval-1']);
    },
  );

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
}

Widget _buildChatHarness({
  List<ChatPendingApprovalData> pendingApprovals =
      const <ChatPendingApprovalData>[],
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
          messages: const <ChatMessageData>[
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
                      'Read README.md lines 5-6\n\n{\n  "file_path": "README.md",\n  "offset": 5,\n  "limit": 2\n}',
                ),
                ChatRunTraceHistoryEntry(
                  label: 'Read',
                  body:
                      'Read README.md lines 5-6\n\nProject uses the Gradle wrapper from the repo root.\nUse .\\\\gradlew.bat test to run JVM tests.',
                ),
              ],
            ),
          ],
          pendingApprovals: pendingApprovals,
          composer: ChatComposerState(
            placeholder: copy.chatComposerPlaceholder,
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

OpenCrayChatSnapshot _hostChatSnapshot({
  List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals =
      const <OpenCrayChatPendingApprovalSnapshot>[],
  List<OpenCrayChatMessageSnapshot>? messages,
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
    drawer: OpenCrayChatDrawerSnapshot(
      eyebrow: 'Recent sessions',
      title: 'Recent sessions',
      ctaLabel: 'New session',
      sessions: <OpenCrayChatSessionItemSnapshot>[],
    ),
    isInputEnabled: true,
    pendingApprovals: pendingApprovals,
  );
}

class _FakeChatBridge implements OpenCrayHostBridge {
  _FakeChatBridge({required this.chatSnapshot, required this.runtimeSnapshot});

  final OpenCrayChatSnapshot chatSnapshot;
  final OpenCrayChatRuntimeSnapshot runtimeSnapshot;
  final List<String> approvedApprovalIds = <String>[];
  final List<String> rejectedApprovalIds = <String>[];

  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async => chatSnapshot;

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() =>
      const Stream<OpenCrayChatSnapshot>.empty();

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      runtimeSnapshot;

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() =>
      const Stream<OpenCrayChatRuntimeSnapshot>.empty();

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
  Future<void> approveChatApproval(String approvalId) async {
    approvedApprovalIds.add(approvalId);
  }

  @override
  Future<void> rejectChatApproval(String approvalId) async {
    rejectedApprovalIds.add(approvalId);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
