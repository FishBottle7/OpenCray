import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/models/opencray_chat_draft_attachment.dart';
import 'package:opencray/core/models/opencray_chat_snapshot.dart';
import 'package:opencray/core/models/opencray_file_image_preview.dart';
import 'package:opencray/core/models/opencray_file_text_preview.dart';
import 'package:opencray/core/models/opencray_file_voice_playback_source.dart';
import 'package:opencray/features/chat/chat_feature.dart';

import 'chat_screen_test_support.dart';

void main() {
  testWidgets(
    'editing an outbound voice attachment preserves its draft reference and metadata',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final snapshots = StreamController<OpenCrayChatSnapshot>.broadcast();
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
            hostChatSnapshot(
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

  testWidgets(
    'host message renders image, voice, and file attachments in one bubble',
    (tester) async {
      final copy = OpenCrayUiCopy.fromLocaleTag('en');
      final playbackLog = FakeVoicePlaybackLog();
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
              fakeImagePreview(
                name: 'diagram-a.png',
                relativePath:
                    '.opencray/chat-media/session-1/hash-a/diagram-a.png',
              ),
          '.opencray/chat-media/session-1/hash-b/diagram-b.png':
              fakeImagePreview(
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
                  FakeVoicePlaybackController(playbackLog),
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
      final playbackLog = FakeVoicePlaybackLog();
      final bridge = FakeChatBridge(
        chatSnapshot: hostChatSnapshot(
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
              fakeImagePreview(
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
                  FakeVoicePlaybackController(playbackLog),
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
    final playbackLog = FakeVoicePlaybackLog();
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
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
                FakeVoicePlaybackController(playbackLog),
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

  testWidgets('host attachment action buttons share and save workspace media', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
        messages: <OpenCrayChatMessageSnapshot>[
          const OpenCrayChatMessageSnapshot(
            messageId: 'assistant-actions-media',
            kind: 'inbound',
            text: '',
            attachments: <OpenCrayChatAttachmentSnapshot>[
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'image-action-1',
                kind: 'image',
                displayName: 'diagram.png',
                localPath: '.opencray/chat-media/session-1/hash-a/diagram.png',
              ),
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'voice-action-1',
                kind: 'voice',
                displayName: 'voice-note.m4a',
                localPath:
                    '.opencray/chat-media/session-1/hash-b/voice-note.m4a',
                durationMs: 4200,
              ),
              OpenCrayChatAttachmentSnapshot(
                attachmentId: 'file-action-1',
                kind: 'file',
                displayName: 'report.pdf',
                localPath: '.opencray/chat-media/session-1/hash-c/report.pdf',
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
        '.opencray/chat-media/session-1/hash-a/diagram.png': fakeImagePreview(
          name: 'diagram.png',
          relativePath: '.opencray/chat-media/session-1/hash-a/diagram.png',
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
        const ValueKey<String>('chat-message-attachment-share-file-action-1'),
      ),
    );
    await tester.pump();
    await tester.tap(
      find.byKey(
        const ValueKey<String>('chat-message-attachment-save-file-action-1'),
      ),
    );
    await tester.pump();
    await tester.tap(
      find.byKey(
        const ValueKey<String>('chat-message-attachment-save-voice-action-1'),
      ),
    );
    await tester.pump();
    await tester.tap(
      find.byKey(
        const ValueKey<String>(
          'chat-message-image-attachment-share-image-action-1',
        ),
      ),
    );
    await tester.pump();
    await tester.tap(
      find.byKey(
        const ValueKey<String>(
          'chat-message-image-attachment-save-image-action-1',
        ),
      ),
    );
    await tester.pump();

    expect(bridge.sharedWorkspaceEntries, <List<String>>[
      <String>['.opencray/chat-media/session-1/hash-c/report.pdf'],
      <String>['.opencray/chat-media/session-1/hash-a/diagram.png'],
    ]);
    expect(bridge.openedWorkspaceEntries, isEmpty);
    expect(bridge.savedWorkspaceMediaAttachments, hasLength(3));
    expect(
      bridge.savedWorkspaceMediaAttachments[0].relativePath,
      '.opencray/chat-media/session-1/hash-c/report.pdf',
    );
    expect(bridge.savedWorkspaceMediaAttachments[0].kind, 'file');
    expect(
      bridge.savedWorkspaceMediaAttachments[1].relativePath,
      '.opencray/chat-media/session-1/hash-b/voice-note.m4a',
    );
    expect(bridge.savedWorkspaceMediaAttachments[1].kind, 'voice');
    expect(
      bridge.savedWorkspaceMediaAttachments[2].relativePath,
      '.opencray/chat-media/session-1/hash-a/diagram.png',
    );
    expect(bridge.savedWorkspaceMediaAttachments[2].kind, 'image');
    expect(find.text(copy.chatAttachmentSavedToDownloads), findsOneWidget);
  });

  testWidgets('text file attachments open an internal preview on tap', (
    tester,
  ) async {
    final copy = OpenCrayUiCopy.fromLocaleTag('en');
    final bridge = FakeChatBridge(
      chatSnapshot: hostChatSnapshot(
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
}
