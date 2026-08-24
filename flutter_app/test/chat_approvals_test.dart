import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

void main() {
  testWidgets(
    'approval card replaces the composer with a bottom glass surface',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      await tester.pumpWidget(
        buildChatHarness(
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
      expect(find.text(copy.chatApprovalToolLabel), findsOneWidget);
      expect(find.text('Bash'), findsOneWidget);
      expect(find.text(copy.chatApprovalWorkingDirectoryLabel), findsOneWidget);
      expect(find.text(copy.chatApprovalReasonLabel), findsOneWidget);
      expect(
        find.text('Check repository state before editing.'),
        findsOneWidget,
      );

      await tester.ensureVisible(find.text('Approve'));
      await tester.tap(find.text('Approve'));
      await tester.pump();

      expect(find.text(copy.chatApprovalDecisionApproved), findsOneWidget);

      await tester.pumpAndSettle();

      expect(bridge.approvedApprovalIds, <String>['run-approval-1']);
    },
  );

  testWidgets('high-risk approval exposes structured non-color risk cues', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        pendingApprovals: const <OpenCrayChatPendingApprovalSnapshot>[
          OpenCrayChatPendingApprovalSnapshot(
            runId: 'run-approval-risk-1',
            taskId: 'task-approval-risk-1',
            title: 'High-risk approval required',
            body: 'Edit src/main.dart',
            approveLabel: 'Approve',
            rejectLabel: 'Reject',
            isHighRisk: true,
            toolName: 'Edit',
            requestSummary: 'Edit src/main.dart',
            pathDetails: <String>['src/main.dart'],
            workingDirectory: '.',
            reason: 'Apply the requested UI change.',
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

    final riskCard = find.byKey(
      const ValueKey<String>('chat-approval-card-run-approval-risk-1'),
    );
    expect(riskCard, findsOneWidget);
    expect(
      find.descendant(
        of: riskCard,
        matching: find.text(copy.chatHighRiskApproval),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: riskCard,
        matching: find.text(copy.chatApprovalToolLabel),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: riskCard,
        matching: find.text(copy.chatApprovalPathsLabel),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(of: riskCard, matching: find.text('src/main.dart')),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: riskCard,
        matching: find.text(copy.chatApprovalReasonLabel),
      ),
      findsOneWidget,
    );
  });

  testWidgets(
    'host-backed approval surface appears as soon as a pending approval snapshot arrives',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      addTearDown(snapshots.close);
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(),
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
        hostChatSnapshot(
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
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
      await tester.pump();

      expect(bridge.approvedApprovalIds, <String>['run-approval-stack-1']);
      expect(bridge.rejectedApprovalIds, isEmpty);
      expect(find.text('Approved'), findsOneWidget);

      await tester.pump(const Duration(milliseconds: 800));

      expect(
        find.byKey(
          const ValueKey<String>('chat-approval-card-run-approval-stack-1'),
        ),
        findsNothing,
      );
      expect(
        find.byKey(
          const ValueKey<String>('chat-approval-card-run-approval-stack-2'),
        ),
        findsOneWidget,
      );
    },
  );
}
