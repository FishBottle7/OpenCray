import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_file_image_preview.dart';
import 'package:opencray/core/models/opencray_sandbox_settings.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

void main() {
  testWidgets(
    'run traces stay under their own turn instead of collecting under the latest message',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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

  testWidgets('run status line opens a full-screen inspector on double tap', (
    tester,
  ) async {
    await tester.pumpWidget(buildChatHarness());
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

  testWidgets('run inspector collapses long tool results to three lines', (
    tester,
  ) async {
    final String longResult = List<String>.generate(
      6,
      (index) => 'tool result line ${index + 1}',
    ).join('\n');
    await tester.pumpWidget(
      buildChatHarness(
        runTraces: <ChatRunTraceData>[
          ChatRunTraceData(
            runId: 'run-long-tool-result',
            taskId: 'task-long-tool-result',
            label: 'Read',
            body: longResult,
            history: <ChatRunTraceHistoryEntry>[
              ChatRunTraceHistoryEntry(
                label: 'Read',
                body: 'Read long output',
                inspectorCallParts: const <ChatRunTraceInspectorTextPart>[
                  ChatRunTraceInspectorTextPart(
                    text: 'Read',
                    semantic: ChatRunTraceInspectorTextSemantic.action,
                  ),
                  ChatRunTraceInspectorTextPart(text: ' long-output.txt'),
                ],
                inspectorResultBody: longResult,
              ),
            ],
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    await openRunTraceFullscreen(
      tester,
      find.byKey(const ValueKey<String>('chat-run-trace-run-long-tool-result')),
    );

    const toggleKey = ValueKey<String>(
      'chat-run-inspector-result-run-long-tool-result-main-0-toggle',
    );
    const rotationKey = ValueKey<String>(
      'chat-run-inspector-result-run-long-tool-result-main-0-rotation',
    );
    const collapsedKey = ValueKey<String>(
      'chat-run-inspector-result-run-long-tool-result-main-0-collapsed',
    );
    const expandedKey = ValueKey<String>(
      'chat-run-inspector-result-run-long-tool-result-main-0-expanded',
    );
    expect(find.byKey(toggleKey), findsOneWidget);
    final Text collapsed = tester.widget<Text>(find.byKey(collapsedKey));
    expect(collapsed.maxLines, 3);
    expect(collapsed.overflow, TextOverflow.ellipsis);
    expect(collapsed.data, contains('tool result line 3'));
    expect(collapsed.data, isNot(contains('tool result line 4')));
    expect(collapsed.data, endsWith('...'));
    AnimatedRotation rotation = tester.widget<AnimatedRotation>(
      find.byKey(rotationKey),
    );
    expect(rotation.turns, 0);

    await tester.tap(find.byKey(toggleKey));
    await tester.pumpAndSettle();

    rotation = tester.widget<AnimatedRotation>(find.byKey(rotationKey));
    expect(rotation.turns, 0.5);
    expect(
      find.descendant(
        of: find.byKey(expandedKey),
        matching: find.textContaining('tool result line 6', findRichText: true),
      ),
      findsWidgets,
    );
  });

  testWidgets('run inspector collapses long agent replies to three lines', (
    tester,
  ) async {
    final String longReply = List<String>.generate(
      5,
      (index) => 'agent reply line ${index + 1}',
    ).join('\n');
    await tester.pumpWidget(
      buildChatHarness(
        runTraces: <ChatRunTraceData>[
          ChatRunTraceData(
            runId: 'run-long-agent-reply',
            taskId: 'task-long-agent-reply',
            label: 'Assistant',
            body: longReply,
            history: <ChatRunTraceHistoryEntry>[
              ChatRunTraceHistoryEntry(label: 'Assistant', body: longReply),
            ],
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();

    await openRunTraceFullscreen(
      tester,
      find.byKey(const ValueKey<String>('chat-run-trace-run-long-agent-reply')),
    );

    const toggleKey = ValueKey<String>(
      'chat-run-inspector-body-run-long-agent-reply-main-0-toggle',
    );
    const collapsedKey = ValueKey<String>(
      'chat-run-inspector-body-run-long-agent-reply-main-0-collapsed',
    );
    const expandedKey = ValueKey<String>(
      'chat-run-inspector-body-run-long-agent-reply-main-0-expanded',
    );
    expect(find.byKey(toggleKey), findsOneWidget);
    final Text collapsed = tester.widget<Text>(find.byKey(collapsedKey));
    expect(collapsed.maxLines, 3);
    expect(collapsed.overflow, TextOverflow.ellipsis);
    expect(collapsed.data, contains('agent reply line 3'));
    expect(collapsed.data, isNot(contains('agent reply line 4')));
    expect(collapsed.data, endsWith('...'));

    await tester.tap(find.byKey(toggleKey));
    await tester.pumpAndSettle();

    expect(
      find.descendant(
        of: find.byKey(expandedKey),
        matching: find.textContaining('agent reply line 5', findRichText: true),
      ),
      findsWidgets,
    );
  });

  testWidgets('anchored retry action renders inside the assistant bubble', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
          'chat-bubble-runtime-process-task-interrupt-inline-proc-interrupt-inline',
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
    await openRunTraceFullscreen(tester, bubbleFinder);

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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
    await openRunTraceFullscreen(tester, bubbleFinder);

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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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

      expect(findStreamedText('Streaming answer in progress'), findsWidgets);

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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
        const Color(0xFF2563EB),
        const Color(0xFF101828),
        const Color(0xFF7C3AED),
        const Color(0xFF101828),
        const Color(0xFF179457),
      ]);
    },
  );

  testWidgets(
    'chat ui renders detached projected subagent traces without a visible parent run',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
              hasActiveExecution: true,
              mailboxMessageCount: 2,
              mailboxPendingMessageCount: 1,
              mailboxLastDeliveredMessageId: 'mailbox-detached-1',
              hasPendingApprovalResume: true,
              pendingApprovalToolName: 'Read',
              pendingApprovalChildRunId: 'child-run-detached-1',
              pendingApprovalChildTaskId: 'child-task-detached-1',
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
      await openRunTraceFullscreen(tester, bubbleFinder);

      final fullscreenFinder = find.byKey(
        const ValueKey<String>(
          'chat-run-trace-fullscreen-child-run-detached-1',
        ),
      );
      expect(fullscreenFinder, findsOneWidget);
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
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Execution: active'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining('Approval: pending resume (Read)'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Approval child: run child-run-detached-1 / task child-task-detached-1',
          ),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      await openRunTraceFullscreen(tester, bubbleFinder);

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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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

      await openRunTraceFullscreen(tester, bubbleFinder);

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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
      await openRunTraceFullscreen(tester, bubbleFinder);

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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
      await openRunTraceFullscreen(tester, runTraceFinder);

      final fullscreenFinder = find.byKey(
        const ValueKey<String>('chat-run-trace-fullscreen-run-chronology-1'),
      );
      final double processY = topYForDescendantText(
        tester,
        fullscreenFinder,
        'ready on http://localhost:3000',
      );
      final double planningY = topYForDescendantText(
        tester,
        fullscreenFinder,
        'Planning update before tool result.',
      );
      final double resultY = topYForDescendantText(
        tester,
        fullscreenFinder,
        'README contents after reading file.',
      );
      final double attachmentY = topYForDescendantText(
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
                executionMode: 'inline',
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
                executionMode: 'inline',
                sourceTranscriptMessageCount: 18,
                retainedTranscriptMessageCount: 12,
                latestCompactedMessageCount: 6,
                includedSummaryCount: 1,
                totalSummaryCount: 1,
                totalCompactedMessageCount: 6,
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
          matching: find.textContaining('Mode: inline'),
        ),
        findsAtLeastNWidgets(2),
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
          matching: find.textContaining(
            'Remote compaction: used, supported, requested, trigger pre_compaction',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: fullscreenFinder,
          matching: find.textContaining(
            'Remote compaction details: output 2, compaction 1, encrypted 1',
          ),
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
    await tester.pumpWidget(buildChatHarness());
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
    await tester.pumpWidget(buildChatHarness());
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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

      await openRunTraceFullscreen(tester, bubbleFinder);

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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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

      await openRunTraceFullscreen(tester, bubbleFinder);

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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
      await openRunTraceFullscreen(tester, bubbleFinder);

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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
          await tester.drag(chatScrollView(), const Offset(0, -240));
          await tester.pumpAndSettle();
          scrollAttempts += 1;
        }
        expect(bubbleFinder, findsOneWidget);
        await tester.ensureVisible(bubbleFinder);
        await openRunTraceFullscreen(tester, bubbleFinder);

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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(),
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
        'docs/diagram.png': fakeImagePreview(
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
    await openRunTraceFullscreen(tester, bubbleFinder);

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
}
