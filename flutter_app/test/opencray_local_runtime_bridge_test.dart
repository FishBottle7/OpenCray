import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/bridge/opencray_host_bridge_bootstrap.dart';
import 'package:opencray/core/bridge/opencray_local_runtime_bridge.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/models/opencray_chat_draft_attachment.dart';
import 'package:opencray/core/models/opencray_image_reference.dart';
import 'package:opencray/core/models/opencray_notification_settings.dart';

void main() {
  HttpServer? server;
  Future<void> Function(HttpRequest request)? requestHandler;

  Future<void> handleRequest(HttpRequest request) async {
    final handler = requestHandler;
    if (handler == null) {
      request.response.statusCode = HttpStatus.notFound;
      await request.response.close();
      return;
    }
    await handler(request);
  }

  Future<void> writeJson(
    HttpRequest request,
    Object? body, {
    int statusCode = HttpStatus.ok,
  }) async {
    request.response.statusCode = statusCode;
    request.response.headers.contentType = ContentType.json;
    request.response.write(jsonEncode(body));
    await request.response.close();
  }

  Future<Map<String, Object?>> readJsonBody(HttpRequest request) async {
    final payload = await utf8.decoder.bind(request).join();
    return (jsonDecode(payload) as Map<Object?, Object?>)
        .cast<String, Object?>();
  }

  String baseUrl() => 'http://127.0.0.1:${server!.port}/';

  setUp(() async {
    requestHandler = null;
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server!.listen(handleRequest);
  });

  tearDown(() async {
    await server?.close(force: true);
    server = null;
    requestHandler = null;
  });

  test('local runtime bridge loads shell snapshot over http', () async {
    requestHandler = (request) async {
      expect(request.method, 'GET');
      expect(request.uri.path, '/v1/shell_snapshot');
      await writeJson(request, <String, Object?>{
        'initialTab': 'settings',
        'hostLabel': 'LOCAL RUNTIME',
        'hostSummary': 'Loopback runtime is attached.',
        'isHostConnected': true,
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.loadShellSnapshot();

    expect(snapshot.hostLabel, 'LOCAL RUNTIME');
    expect(snapshot.hostSummary, 'Loopback runtime is attached.');
    expect(snapshot.isHostConnected, isTrue);
  });

  test(
    'local runtime bridge injects flutter and bridge lifecycle ids into shell snapshots',
    () async {
      requestHandler = (request) async {
        expect(request.method, 'GET');
        expect(request.uri.path, '/v1/shell_snapshot');
        await writeJson(request, <String, Object?>{
          'initialTab': 'chat',
          'hostLabel': 'LOCAL RUNTIME',
          'hostSummary': 'Loopback runtime is attached.',
          'isHostConnected': true,
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final snapshot = await bridge.loadShellSnapshot();

      expect(snapshot.flutterAppInstanceId, isNotEmpty);
      expect(snapshot.bridgeInstanceId, isNotEmpty);
    },
  );

  test('local runtime bridge loads files snapshot over http', () async {
    requestHandler = (request) async {
      expect(request.method, 'GET');
      expect(request.uri.path, '/v1/files_snapshot');
      await writeJson(request, <String, Object?>{
        'rootName': 'agent-workspace',
        'rootPath': '/tmp/agent-workspace',
        'availableBytes': 4096,
        'directoryCount': 1,
        'fileCount': 1,
        'entryCount': 2,
        'isTruncated': false,
        'children': <Object?>[
          <String, Object?>{
            'name': 'docs',
            'relativePath': 'docs',
            'isDirectory': true,
            'childCount': 1,
            'sizeBytes': null,
            'isTruncated': false,
            'children': <Object?>[
              <String, Object?>{
                'name': 'report.md',
                'relativePath': 'docs/report.md',
                'isDirectory': false,
                'childCount': 0,
                'sizeBytes': 1024,
                'isTruncated': false,
                'children': const <Object?>[],
              },
            ],
          },
        ],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.loadFilesSnapshot();

    expect(snapshot.rootPath, '/tmp/agent-workspace');
    expect(snapshot.children.single.name, 'docs');
    expect(snapshot.children.single.children.single.sizeBytes, 1024);
  });

  test(
    'local runtime bridge loads sandbox preview embed config over http',
    () async {
      late Map<String, Object?> capturedBody;
      requestHandler = (request) async {
        expect(request.method, 'POST');
        expect(request.uri.path, '/v1/resolve_sandbox_preview_embed_config');
        capturedBody = await readJsonBody(request);
        await writeJson(request, <String, Object?>{
          'previewUrl': 'https://3000-sb-preview.e2b.app/',
          'providerId': 'e2b',
          'headers': <String, Object?>{
            'E2B-Traffic-Access-Token': 'traffic-preview',
          },
          'sessionMatched': true,
          'accessTokenConfigured': true,
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final config = await bridge.resolveSandboxPreviewEmbedConfig(
        'https://3000-sb-preview.e2b.app/',
      );

      expect(capturedBody['previewUrl'], 'https://3000-sb-preview.e2b.app/');
      expect(config.providerId, 'e2b');
      expect(config.sessionMatched, isTrue);
      expect(config.headers['E2B-Traffic-Access-Token'], 'traffic-preview');
    },
  );

  test('local runtime bridge loads text preview over http', () async {
    requestHandler = (request) async {
      expect(request.method, 'GET');
      expect(request.uri.path, '/v1/workspace_text_preview');
      expect(request.uri.queryParameters['relativePath'], 'docs/report.md');
      await writeJson(request, <String, Object?>{
        'name': 'report.md',
        'relativePath': 'docs/report.md',
        'content': '# Report\n\nPreview body',
        'isTruncated': true,
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final preview = await bridge.loadWorkspaceTextPreview('docs/report.md');

    expect(preview.name, 'report.md');
    expect(preview.relativePath, 'docs/report.md');
    expect(preview.isTruncated, isTrue);
  });

  test('local runtime bridge posts twin import probe requests', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/probe_twin_import_source');
      capturedBody = await readJsonBody(request);
      await writeJson(request, <String, Object?>{
        'filePath': '/tmp/chatlab.jsonl',
        'fileName': 'chatlab.jsonl',
        'fileExtension': 'jsonl',
        'sourceMode': 'chat_history',
        'formatKey': 'chatlab_jsonl',
        'formatLabel': 'ChatLab JSONL',
        'confidence': 'high',
        'usesExistingImporter': true,
        'needsManualSelection': false,
        'notes': <Object?>[
          'Detected ChatLab JSONL using header/member/message records.',
        ],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.probeTwinImportSource('/tmp/chatlab.jsonl');

    expect(capturedBody['filePath'], '/tmp/chatlab.jsonl');
    expect(snapshot.fileName, 'chatlab.jsonl');
    expect(snapshot.sourceMode, 'chat_history');
    expect(snapshot.formatKey, 'chatlab_jsonl');
    expect(snapshot.usesExistingImporter, isTrue);
    expect(snapshot.needsManualSelection, isFalse);
  });

  test('local runtime bridge loads text documents over http', () async {
    requestHandler = (request) async {
      expect(request.method, 'GET');
      expect(request.uri.path, '/v1/workspace_text_document');
      expect(request.uri.queryParameters['relativePath'], 'docs/report.md');
      await writeJson(request, <String, Object?>{
        'name': 'report.md',
        'relativePath': 'docs/report.md',
        'content': '# Report\n\nEditable body',
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final document = await bridge.loadWorkspaceTextDocument('docs/report.md');

    expect(document.name, 'report.md');
    expect(document.relativePath, 'docs/report.md');
    expect(document.content, '# Report\n\nEditable body');
  });

  test('local runtime bridge posts create text file requests', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/create_workspace_text_file');
      capturedBody = await readJsonBody(request);
      await writeJson(request, <String, Object?>{
        'rootName': 'agent-workspace',
        'rootPath': '/tmp/agent-workspace',
        'availableBytes': 4096,
        'directoryCount': 0,
        'fileCount': 1,
        'entryCount': 1,
        'isTruncated': false,
        'children': <Object?>[
          <String, Object?>{
            'name': 'notes.txt',
            'relativePath': 'notes.txt',
            'isDirectory': false,
            'childCount': 0,
            'sizeBytes': 0,
            'isTruncated': false,
            'children': const <Object?>[],
          },
        ],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.createWorkspaceTextFile(
      parentRelativePath: '',
      name: 'notes.txt',
    );

    expect(capturedBody['parentRelativePath'], '');
    expect(capturedBody['name'], 'notes.txt');
    expect(snapshot.children.single.relativePath, 'notes.txt');
  });

  test('local runtime bridge posts save text document requests', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/save_workspace_text_document');
      capturedBody = await readJsonBody(request);
      await writeJson(request, <String, Object?>{
        'rootName': 'agent-workspace',
        'rootPath': '/tmp/agent-workspace',
        'availableBytes': 4096,
        'directoryCount': 0,
        'fileCount': 1,
        'entryCount': 1,
        'isTruncated': false,
        'children': <Object?>[
          <String, Object?>{
            'name': 'notes.txt',
            'relativePath': 'notes.txt',
            'isDirectory': false,
            'childCount': 0,
            'sizeBytes': 11,
            'isTruncated': false,
            'children': const <Object?>[],
          },
        ],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.saveWorkspaceTextDocument(
      targetRelativePath: 'notes.txt',
      content: 'hello world',
    );

    expect(capturedBody['targetRelativePath'], 'notes.txt');
    expect(capturedBody['content'], 'hello world');
    expect(snapshot.children.single.sizeBytes, 11);
  });

  test('local runtime bridge loads image preview over http', () async {
    requestHandler = (request) async {
      expect(request.method, 'GET');
      expect(request.uri.path, '/v1/workspace_image_preview');
      expect(request.uri.queryParameters['relativePath'], 'images/cover.png');
      await writeJson(request, <String, Object?>{
        'name': 'cover.png',
        'relativePath': 'images/cover.png',
        'mimeType': 'image/png',
        'width': 1,
        'height': 1,
        'bytesBase64': _tinyPngBase64,
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final preview = await bridge.loadWorkspaceImagePreview('images/cover.png');

    expect(preview.name, 'cover.png');
    expect(preview.relativePath, 'images/cover.png');
    expect(preview.mimeType, 'image/png');
    expect(preview.width, 1);
    expect(preview.height, 1);
    expect(preview.bytes, base64Decode(_tinyPngBase64));
  });

  test(
    'local runtime bridge reports attachment picking as unsupported',
    () async {
      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());

      expect(
        () => bridge.pickChatAttachments(
          kind: OpenCrayChatDraftAttachmentKind.file,
        ),
        throwsA(
          isA<UnsupportedError>().having(
            (error) => error.message,
            'message',
            'Adding attachments is unavailable in local runtime mode.',
          ),
        ),
      );
    },
  );

  test('local runtime bridge loads settings image assets over http', () async {
    requestHandler = (request) async {
      expect(request.method, 'GET');
      expect(request.uri.path, '/v1/settings_image_assets');
      await writeJson(request, <Object?>[
        <String, Object?>{
          'assetId': 'settings-asset-1',
          'displayName': 'portrait.png',
          'relativePath': 'settings-image-assets/portrait.png',
          'mimeType': 'image/png',
          'sha256':
              'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
          'sizeBytes': 2048,
          'createdAtEpochMs': 1200,
        },
      ]);
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final assets = await bridge.listSettingsImageAssets();

    expect(assets.single.assetId, 'settings-asset-1');
    expect(assets.single.displayName, 'portrait.png');
    expect(assets.single.sizeBytes, 2048);
  });

  test(
    'local runtime bridge leaves interactive settings image picking to the platform host',
    () async {
      var requested = false;
      requestHandler = (request) async {
        requested = true;
        await writeJson(request, const <Object?>[]);
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final assets = await bridge.pickSettingsImageAssets();

      expect(assets, isEmpty);
      expect(requested, isFalse);
    },
  );

  test('local runtime bridge imports settings image assets over http', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/import_settings_image_assets');
      capturedBody = await readJsonBody(request);
      await writeJson(request, <Object?>[
        <String, Object?>{
          'assetId': 'settings-asset-2',
          'displayName': 'reference.png',
          'relativePath': 'settings-image-assets/reference.png',
          'mimeType': 'image/png',
          'sha256':
              'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
          'sizeBytes': 4096,
          'createdAtEpochMs': 1400,
        },
      ]);
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final assets = await bridge.importSettingsImageAssets(<String>[
      'content://images/reference.png',
    ]);

    expect(capturedBody['uriStrings'], <Object?>[
      'content://images/reference.png',
    ]);
    expect(assets.single.assetId, 'settings-asset-2');
    expect(assets.single.relativePath, 'settings-image-assets/reference.png');
  });

  test('local runtime bridge loads soul visual identity over http', () async {
    requestHandler = (request) async {
      expect(request.method, 'GET');
      expect(request.uri.path, '/v1/soul_visual_identity');
      await writeJson(request, <String, Object?>{
        'portraitSummary': 'Calm expression with short dark hair.',
        'primaryPortrait': <String, Object?>{
          'refId': 'portrait-1',
          'role': 'portrait',
          'storageScope': 'agent_private',
          'relativePath': 'soul-assets/portrait/portrait-1.png',
          'summary': 'Front-facing portrait with a calm expression.',
          'caption': 'Primary portrait',
          'createdAtEpochMs': 1200,
        },
        'referenceImages': <Object?>[
          <String, Object?>{
            'refId': 'reference-1',
            'role': 'reference',
            'storageScope': 'agent_private',
            'relativePath': 'soul-assets/reference/reference-1.png',
            'summary': 'Three-quarter portrait under warm light.',
            'caption': 'Warm light',
            'createdAtEpochMs': 1300,
          },
        ],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final identity = await bridge.loadSoulVisualIdentity();

    expect(identity?.portraitSummary, 'Calm expression with short dark hair.');
    expect(identity?.primaryPortrait?.refId, 'portrait-1');
    expect(identity?.referenceImages.single.refId, 'reference-1');
  });

  test(
    'local runtime bridge posts save soul reference image requests',
    () async {
      late Map<String, Object?> capturedBody;
      requestHandler = (request) async {
        expect(request.method, 'POST');
        expect(request.uri.path, '/v1/save_soul_reference_image');
        capturedBody = await readJsonBody(request);
        await writeJson(request, <String, Object?>{
          'portraitSummary': 'Calm expression with short dark hair.',
          'primaryPortrait': <String, Object?>{
            'refId': 'portrait-1',
            'role': 'portrait',
            'storageScope': 'agent_private',
            'relativePath': 'soul-assets/portrait/portrait-1.png',
            'summary': 'Front-facing portrait with a calm expression.',
            'caption': 'Primary portrait',
            'createdAtEpochMs': 1200,
          },
          'referenceImages': <Object?>[
            <String, Object?>{
              'refId': 'reference-2',
              'role': 'reference',
              'storageScope': 'agent_private',
              'relativePath': 'soul-assets/reference/reference-2.png',
              'summary': 'Red outfit reference image.',
              'caption': 'Red outfit',
              'createdAtEpochMs': 1400,
            },
          ],
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final identity = await bridge.saveSoulReferenceImage(
        refId: 'reference-2',
        source: const OpenCrayImageReferenceSource(
          sourceKind: OpenCrayImageReferenceSourceKind.settingsAsset,
          settingsAssetId: 'settings-asset-2',
          displayName: 'reference.png',
          mimeType: 'image/png',
        ),
      );

      expect(capturedBody['refId'], 'reference-2');
      expect(
        (capturedBody['source'] as Map<String, Object?>)['settingsAssetId'],
        'settings-asset-2',
      );
      expect(identity?.referenceImages.single.refId, 'reference-2');
      expect(identity?.referenceImages.single.caption, 'Red outfit');
    },
  );

  test(
    'local runtime bridge posts memory image reference attachment requests',
    () async {
      late Map<String, Object?> capturedBody;
      requestHandler = (request) async {
        expect(request.method, 'POST');
        expect(request.uri.path, '/v1/attach_memory_image_reference');
        capturedBody = await readJsonBody(request);
        await writeJson(request, <String, Object?>{
          'memoryId': 'memory-1',
          'recordVersion': 4,
          'updatedAtEpochMs': 9000,
          'imageReferences': <Object?>[
            <String, Object?>{
              'refId': 'memory-image-1',
              'role': 'evidence',
              'storageScope': 'workspace',
              'relativePath': 'memory-assets/whiteboard.png',
              'summary': 'Whiteboard photo from the planning session.',
              'caption': 'Whiteboard',
              'createdAtEpochMs': 4200,
            },
          ],
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final result = await bridge.attachMemoryImageReference(
        memoryId: 'memory-1',
        preferredMode: 'copy_promote',
        source: const OpenCrayImageReferenceSource(
          sourceKind: OpenCrayImageReferenceSourceKind.settingsAsset,
          settingsAssetId: 'settings-asset-1',
          displayName: 'whiteboard.png',
          mimeType: 'image/png',
        ),
      );

      expect(capturedBody['memoryId'], 'memory-1');
      expect(capturedBody['preferredMode'], 'copy_promote');
      expect(
        (capturedBody['source'] as Map<String, Object?>)['settingsAssetId'],
        'settings-asset-1',
      );
      expect(result?.recordVersion, 4);
      expect(result?.imageReferences.single.refId, 'memory-image-1');
    },
  );

  test('local runtime bridge loads voice playback sources over http', () async {
    requestHandler = (request) async {
      expect(request.method, 'GET');
      expect(request.uri.path, '/v1/workspace_voice_playback_source');
      expect(
        request.uri.queryParameters['relativePath'],
        '.opencray/chat-media/session-1/hash/voice-note.m4a',
      );
      await writeJson(request, <String, Object?>{
        'name': 'voice-note.m4a',
        'relativePath': '.opencray/chat-media/session-1/hash/voice-note.m4a',
        'localFilePath': '/workspace/session-1/voice-note.m4a',
        'mimeType': 'audio/mp4',
        'durationMs': 4200,
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final source = await bridge.loadWorkspaceVoicePlaybackSource(
      '.opencray/chat-media/session-1/hash/voice-note.m4a',
    );

    expect(source.name, 'voice-note.m4a');
    expect(
      source.relativePath,
      '.opencray/chat-media/session-1/hash/voice-note.m4a',
    );
    expect(source.localFilePath, '/workspace/session-1/voice-note.m4a');
    expect(source.mimeType, 'audio/mp4');
    expect(source.durationMs, 4200);
  });

  test(
    'local runtime bridge posts validation requests and parses results',
    () async {
      late Map<String, Object?> capturedBody;
      requestHandler = (request) async {
        expect(request.method, 'POST');
        expect(request.uri.path, '/v1/validate_llm_config');
        capturedBody = await readJsonBody(request);
        await writeJson(request, <String, Object?>{
          'isSuccess': true,
          'message': 'Validated against local runtime.',
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final result = await bridge.validateLlmConfig(
        providerId: 'openai',
        protocol: 'openai',
        baseUrl: 'https://api.openai.com/v1',
        apiKey: 'secret',
        model: 'gpt-4o-mini',
        reasoningEffort: 'medium',
      );

      expect(capturedBody['providerId'], 'openai');
      expect(capturedBody['protocol'], 'openai');
      expect(capturedBody['model'], 'gpt-4o-mini');
      expect(result.isSuccess, isTrue);
      expect(result.message, 'Validated against local runtime.');
    },
  );

  test(
    'local runtime bridge posts safety settings and parses results',
    () async {
      late Map<String, Object?> capturedBody;
      requestHandler = (request) async {
        expect(request.method, 'POST');
        expect(request.uri.path, '/v1/save_safety_settings');
        capturedBody = await readJsonBody(request);
        await writeJson(request, <String, Object?>{
          'automationModeId': capturedBody['automationModeId'],
          'rollbackJournalEnabled': true,
          'maxFilesPerBatch': 20,
          'maxAgentTurns': capturedBody['maxAgentTurns'],
          'maxToolCalls': capturedBody['maxToolCalls'],
          'undoWindowHours': 24,
          'fileChangesPolicyId': 'inherit',
          'fileDeletesPolicyId': capturedBody['fileDeletesPolicyId'],
          'shellCommandsPolicyId': 'ask',
          'externalAccessModeId': 'select_paths',
          'locations': <Object?>[
            <String, Object?>{'id': 'photo_library', 'enabled': true},
            <String, Object?>{'id': 'downloads', 'enabled': true},
            <String, Object?>{'id': 'documents', 'enabled': false},
            <String, Object?>{'id': 'recordings', 'enabled': false},
          ],
          'workspaceAccessProfileId': 'work',
          'readOnlyOutsideWorkspace': true,
          'liveContextModeId': capturedBody['liveContextModeId'],
          'memoryToolsEnabled': capturedBody['memoryToolsEnabled'],
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final snapshot = await bridge.saveSafetySettings(
        automationModeId: 'dev',
        rollbackJournalEnabled: true,
        maxFilesPerBatch: 20,
        maxAgentTurns: 0,
        maxToolCalls: 0,
        undoWindowHours: 24,
        fileChangesPolicyId: 'inherit',
        fileDeletesPolicyId: 'block',
        shellCommandsPolicyId: 'ask',
        externalAccessModeId: 'select_paths',
        photoLibraryEnabled: true,
        downloadsEnabled: true,
        documentsEnabled: false,
        recordingsEnabled: false,
        workspaceAccessProfileId: 'work',
        readOnlyOutsideWorkspace: true,
        liveContextModeId: 'no_soul',
        memoryToolsEnabled: false,
      );

      expect(capturedBody['automationModeId'], 'dev');
      expect(capturedBody['maxAgentTurns'], 0);
      expect(capturedBody['maxToolCalls'], 0);
      expect(capturedBody['fileDeletesPolicyId'], 'block');
      expect(capturedBody['liveContextModeId'], 'no_soul');
      expect(capturedBody['memoryToolsEnabled'], false);
      expect(snapshot.automationModeId, 'dev');
      expect(snapshot.maxAgentTurns, 0);
      expect(snapshot.maxToolCalls, 0);
      expect(snapshot.fileDeletesPolicyId, 'block');
      expect(snapshot.liveContextModeId, 'no_soul');
      expect(snapshot.memoryToolsEnabled, false);
    },
  );

  test('local runtime bridge loads notification settings over http', () async {
    requestHandler = (request) async {
      expect(request.method, 'GET');
      expect(request.uri.path, '/v1/notification_settings');
      await writeJson(request, <String, Object?>{
        'masterEnabled': true,
        'defaultDeliveryModeId': 'time_sensitive',
        'quietHoursEnabled': true,
        'quietHoursStartMinutes': 1380,
        'quietHoursEndMinutes': 480,
        'approvalRequestsEnabled': true,
        'approvalReminderEnabled': true,
        'taskFinishedEnabled': false,
        'taskFailedEnabled': true,
        'scheduledWakeEnabled': true,
        'backgroundTaskPausedEnabled': true,
        'serviceRecoveredEnabled': false,
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.loadNotificationSettings();

    expect(snapshot.masterEnabled, isTrue);
    expect(snapshot.defaultDeliveryModeId, 'time_sensitive');
    expect(snapshot.quietHoursEnabled, isTrue);
    expect(snapshot.quietHoursStartMinutes, 1380);
    expect(snapshot.quietHoursEndMinutes, 480);
    expect(snapshot.approvalRequestsEnabled, isTrue);
    expect(snapshot.approvalReminderEnabled, isTrue);
    expect(snapshot.taskFinishedEnabled, isFalse);
    expect(snapshot.taskFailedEnabled, isTrue);
    expect(snapshot.scheduledWakeEnabled, isTrue);
    expect(snapshot.backgroundTaskPausedEnabled, isTrue);
    expect(snapshot.serviceRecoveredEnabled, isFalse);
  });

  test('local runtime bridge posts notification settings over http', () async {
    late Map<String, Object?> capturedBody;
    const settings = OpenCrayNotificationSettingsSnapshot(
      masterEnabled: false,
      defaultDeliveryModeId: 'critical',
      quietHoursEnabled: false,
      quietHoursStartMinutes: 1320,
      quietHoursEndMinutes: 420,
      approvalRequestsEnabled: true,
      approvalReminderEnabled: false,
      taskFinishedEnabled: true,
      taskFailedEnabled: true,
      scheduledWakeEnabled: true,
      backgroundTaskPausedEnabled: false,
      serviceRecoveredEnabled: true,
    );
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/save_notification_settings');
      capturedBody = await readJsonBody(request);
      await writeJson(request, <String, Object?>{
        'masterEnabled': capturedBody['masterEnabled'],
        'defaultDeliveryModeId': capturedBody['defaultDeliveryModeId'],
        'quietHoursEnabled': capturedBody['quietHoursEnabled'],
        'quietHoursStartMinutes': capturedBody['quietHoursStartMinutes'],
        'quietHoursEndMinutes': capturedBody['quietHoursEndMinutes'],
        'approvalRequestsEnabled': capturedBody['approvalRequestsEnabled'],
        'approvalReminderEnabled': capturedBody['approvalReminderEnabled'],
        'taskFinishedEnabled': capturedBody['taskFinishedEnabled'],
        'taskFailedEnabled': capturedBody['taskFailedEnabled'],
        'scheduledWakeEnabled': capturedBody['scheduledWakeEnabled'],
        'backgroundTaskPausedEnabled':
            capturedBody['backgroundTaskPausedEnabled'],
        'serviceRecoveredEnabled': capturedBody['serviceRecoveredEnabled'],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.saveNotificationSettings(settings);

    expect(capturedBody['masterEnabled'], isFalse);
    expect(capturedBody['defaultDeliveryModeId'], 'critical');
    expect(capturedBody['quietHoursEnabled'], isFalse);
    expect(capturedBody['quietHoursStartMinutes'], 1320);
    expect(capturedBody['quietHoursEndMinutes'], 420);
    expect(capturedBody['approvalRequestsEnabled'], isTrue);
    expect(capturedBody['approvalReminderEnabled'], isFalse);
    expect(capturedBody['taskFinishedEnabled'], isTrue);
    expect(capturedBody['taskFailedEnabled'], isTrue);
    expect(capturedBody['scheduledWakeEnabled'], isTrue);
    expect(capturedBody['backgroundTaskPausedEnabled'], isFalse);
    expect(capturedBody['serviceRecoveredEnabled'], isTrue);
    expect(snapshot.masterEnabled, isFalse);
    expect(snapshot.defaultDeliveryModeId, 'critical');
    expect(snapshot.quietHoursEnabled, isFalse);
    expect(snapshot.quietHoursStartMinutes, 1320);
    expect(snapshot.quietHoursEndMinutes, 420);
    expect(snapshot.approvalRequestsEnabled, isTrue);
    expect(snapshot.approvalReminderEnabled, isFalse);
    expect(snapshot.taskFinishedEnabled, isTrue);
    expect(snapshot.taskFailedEnabled, isTrue);
    expect(snapshot.scheduledWakeEnabled, isTrue);
    expect(snapshot.backgroundTaskPausedEnabled, isFalse);
    expect(snapshot.serviceRecoveredEnabled, isTrue);
  });

  test(
    'local runtime bridge loads strong background snapshots over http',
    () async {
      requestHandler = (request) async {
        expect(request.method, 'GET');
        expect(request.uri.path, '/v1/strong_background_snapshot');
        await writeJson(request, <String, Object?>{
          'source': 'strong-background',
          'available': true,
          'tierId': 'strong_background',
          'setupComplete': true,
          'recommendedActionIds': const <Object?>[],
          'notifications': <String, Object?>{
            'permissionRequired': true,
            'permissionGranted': true,
            'enabled': true,
            'configured': true,
          },
          'exactAlarms': <String, Object?>{
            'accessRequired': true,
            'accessGranted': true,
            'configured': true,
          },
          'batteryOptimization': <String, Object?>{
            'supported': true,
            'exempt': true,
            'configured': true,
          },
          'actions': <Object?>[
            <String, Object?>{
              'id': 'open_notification_settings',
              'available': true,
              'recommended': false,
            },
          ],
          'runtimeServiceConnectionState': <String, Object?>{
            'phase': 'binder_connected',
            'binderAvailable': true,
          },
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final snapshot = await bridge.loadStrongBackgroundSnapshot();

      expect(snapshot.available, isTrue);
      expect(snapshot.tierId, 'strong_background');
      expect(snapshot.setupComplete, isTrue);
      expect(snapshot.notifications.configured, isTrue);
      expect(snapshot.exactAlarms.accessGranted, isTrue);
      expect(snapshot.batteryOptimization.exempt, isTrue);
      expect(snapshot.actions.single.id, 'open_notification_settings');
      expect(snapshot.runtimeServiceConnectionState?.binderAvailable, isTrue);
    },
  );

  test(
    'local runtime bridge posts strong background actions over http',
    () async {
      late Map<String, Object?> capturedBody;
      requestHandler = (request) async {
        expect(request.method, 'POST');
        expect(request.uri.path, '/v1/perform_strong_background_action');
        capturedBody = await readJsonBody(request);
        await writeJson(request, <String, Object?>{
          'source': 'strong-background-action',
          'actionId': capturedBody['actionId'],
          'available': true,
          'launched': true,
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final result = await bridge.performStrongBackgroundAction(
        'open_notification_settings',
      );

      expect(capturedBody['actionId'], 'open_notification_settings');
      expect(result.actionId, 'open_notification_settings');
      expect(result.available, isTrue);
      expect(result.launched, isTrue);
    },
  );

  test('local runtime bridge posts save custom provider requests', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/save_custom_llm_provider');
      capturedBody = await readJsonBody(request);
      await writeJson(request, <String, Object?>{
        'localeTag': 'en',
        'enabled': true,
        'providerId': 'custom',
        'selectedProviderOptionId': 'saved-custom',
        'protocol': 'anthropic',
        'providerOptions': <Object?>[
          <String, Object?>{
            'id': 'saved-custom',
            'providerId': 'custom',
            'title': 'Acme',
            'subtitle': 'Regional fallback',
            'defaultBaseUrl': 'https://api.acme.example/v1',
            'defaultModel': 'claude-3-7-sonnet',
            'protocol': 'anthropic',
            'apiKey': 'secret',
            'isCustom': true,
          },
        ],
        'providerName': 'Acme',
        'providerNotes': 'Regional fallback',
        'baseUrl': 'https://api.acme.example/v1',
        'apiKey': 'secret',
        'model': 'claude-3-7-sonnet',
        'reasoningEffort': 'high',
        'systemPrompt': '',
        'openAiPromptCacheKeyStrategy': 'session',
        'openAiPromptCacheRetention': '24h',
        'anthropicPromptCachingEnabled': true,
        'anthropicPromptCacheTtl': '1h',
        'helperText': 'Helper text',
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.saveCustomLlmProvider(
      selectedProviderOptionId: 'custom',
      protocol: 'anthropic',
      providerName: 'Acme',
      providerNotes: 'Regional fallback',
      baseUrl: 'https://api.acme.example/v1',
      apiKey: 'secret',
      model: 'claude-3-7-sonnet',
      reasoningEffort: 'high',
      systemPrompt: '',
      openAiPromptCacheKeyStrategy: 'session',
      openAiPromptCacheRetention: '24h',
      anthropicPromptCachingEnabled: true,
      anthropicPromptCacheTtl: '1h',
    );

    expect(capturedBody['selectedProviderOptionId'], 'custom');
    expect(capturedBody['providerNotes'], 'Regional fallback');
    expect(capturedBody['openAiPromptCacheKeyStrategy'], 'session');
    expect(capturedBody['openAiPromptCacheRetention'], '24h');
    expect(capturedBody['anthropicPromptCachingEnabled'], true);
    expect(capturedBody['anthropicPromptCacheTtl'], '1h');
    expect(snapshot.selectedProviderOptionId, 'saved-custom');
    expect(snapshot.providerOptions.single.protocol, 'anthropic');
    expect(snapshot.openAiPromptCacheKeyStrategy, 'session');
    expect(snapshot.openAiPromptCacheRetention, '24h');
    expect(snapshot.anthropicPromptCachingEnabled, isTrue);
    expect(snapshot.anthropicPromptCacheTtl, '1h');
  });

  test('local runtime bridge posts share requests over http', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/share_workspace_entries');
      capturedBody = await readJsonBody(request);
      await writeJson(request, null);
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    await bridge.shareWorkspaceEntries(<String>['docs/report.md', 'todo.txt']);

    expect(capturedBody['relativePaths'], <Object?>[
      'docs/report.md',
      'todo.txt',
    ]);
  });

  test('local runtime bridge posts media save requests over http', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/save_workspace_media_attachment');
      capturedBody = await readJsonBody(request);
      await writeJson(request, <String, Object?>{
        'displayName': 'voice-note.m4a',
        'collection': 'recordings',
        'uri': 'content://media/audio/42',
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final saved = await bridge.saveWorkspaceMediaAttachment(
      relativePath: '.opencray/chat-media/session-1/hash/voice-note.m4a',
      kind: 'voice',
    );

    expect(
      capturedBody['relativePath'],
      '.opencray/chat-media/session-1/hash/voice-note.m4a',
    );
    expect(capturedBody['kind'], 'voice');
    expect(saved.displayName, 'voice-note.m4a');
    expect(saved.collection, 'recordings');
    expect(saved.uri, 'content://media/audio/42');
  });

  test('local runtime bridge posts open file requests over http', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/open_workspace_entry');
      capturedBody = await readJsonBody(request);
      await writeJson(request, null);
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    await bridge.openWorkspaceEntry(
      '.opencray/chat-media/session-1/hash/voice-note.m4a',
    );

    expect(
      capturedBody['relativePath'],
      '.opencray/chat-media/session-1/hash/voice-note.m4a',
    );
  });

  test('local runtime bridge posts external uri requests over http', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/open_external_uri');
      capturedBody = await readJsonBody(request);
      await writeJson(request, null);
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    await bridge.openExternalUri('https://opencray.dev/docs');

    expect(capturedBody['uri'], 'https://opencray.dev/docs');
  });

  test(
    'local runtime bridge preserves attachment references when submitting chat messages',
    () async {
      late Map<String, Object?> capturedBody;
      requestHandler = (request) async {
        expect(request.method, 'POST');
        expect(request.uri.path, '/v1/submit_chat_message');
        capturedBody = await readJsonBody(request);
        await writeJson(request, <String, Object?>{
          'sessionId': 'session-1',
          'runId': 'run-1',
          'taskId': 'task-1',
          'acceptedAtEpochMs': 123,
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      await bridge.submitChatMessage(
        'Reuse prior references',
        attachments: const <OpenCrayChatDraftAttachment>[
          OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.file,
            displayName: 'diagram.png',
            artifactId: 'artifact-diagram-1',
          ),
          OpenCrayChatDraftAttachment(
            kind: OpenCrayChatDraftAttachmentKind.file,
            displayName: 'report.pdf',
            chatAttachmentId: 'chat-attachment-1',
          ),
        ],
      );

      expect(capturedBody['text'], 'Reuse prior references');
      expect(capturedBody['attachments'], <Object?>[
        <String, Object?>{
          'kind': 'file',
          'displayName': 'diagram.png',
          'relativePath': '',
          'artifactId': 'artifact-diagram-1',
          'mimeType': null,
          'sizeBytes': null,
        },
        <String, Object?>{
          'kind': 'file',
          'displayName': 'report.pdf',
          'relativePath': '',
          'chatAttachmentId': 'chat-attachment-1',
          'mimeType': null,
          'sizeBytes': null,
        },
      ]);
    },
  );

  test('local runtime bridge inspects skill sources over http', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/inspect_skill_source');
      capturedBody = await readJsonBody(request);
      await writeJson(request, <String, Object?>{
        'sourceType': 'remote_github',
        'sourceRef': 'roin-orca/skills',
        'sourcePath': 'https://github.com/roin-orca/skills',
        'resolvedRevision': 'main',
        'resolvedCommitSha': 'deadbeef',
        'candidates': <Object?>[
          <String, Object?>{
            'name': 'find-skills',
            'description': 'Discover skills.',
            'relativePath': 'skills/find-skills/SKILL.md',
          },
        ],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final inspection = await bridge.inspectSkillSource('roin-orca/skills');

    expect(capturedBody['sourceRef'], 'roin-orca/skills');
    expect(inspection.sourceType, 'remote_github');
    expect(inspection.candidates.single.name, 'find-skills');
  });

  test(
    'local runtime bridge posts selected skill installs over http',
    () async {
      late Map<String, Object?> capturedBody;
      requestHandler = (request) async {
        expect(request.method, 'POST');
        expect(request.uri.path, '/v1/install_skill_source');
        capturedBody = await readJsonBody(request);
        await writeJson(request, 'Installed review-skills.');
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final message = await bridge.installSkillSource(
        'roin-orca/skills',
        selectedSkillName: 'review-skills',
      );

      expect(capturedBody['sourceRef'], 'roin-orca/skills');
      expect(capturedBody['selectedSkillName'], 'review-skills');
      expect(message, 'Installed review-skills.');
    },
  );

  test('local runtime bridge posts batch skill installs over http', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/install_skill_source_batch');
      capturedBody = await readJsonBody(request);
      await writeJson(request, 'Installed 2 skills.');
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final message = await bridge.installSkillSourceBatch(
      'roin-orca/skills',
      selectedSkillNames: const <String>['find-skills', 'review-skills'],
    );

    expect(capturedBody['sourceRef'], 'roin-orca/skills');
    expect(capturedBody['selectedSkillNames'], <Object?>[
      'find-skills',
      'review-skills',
    ]);
    expect(message, 'Installed 2 skills.');
  });

  test(
    'local runtime bridge parses memory maintenance fields from chat runtime snapshots',
    () async {
      requestHandler = (request) async {
        expect(request.method, 'GET');
        expect(request.uri.path, '/v1/chat_runtime_snapshot');
        await writeJson(request, <String, Object?>{
          'sessionId': 'session-1',
          'activeRuns': <Object?>[
            <String, Object?>{
              'sessionId': 'session-1',
              'runId': 'run-memory-write-1',
              'taskId': 'task-memory-write-1',
              'acceptedAtEpochMs': 1000,
              'updatedAtEpochMs': 2500,
              'attempt': 1,
              'isTerminal': false,
              'memoryFlush': <String, Object?>{
                'outcome': 'written',
                'triggerStage': 'pre_compaction',
                'executionMode': 'inline',
                'contextWindowTokens': 128000,
                'autoCompactTokenLimit': 115200,
                'estimatedReplayTokens': 116000,
                'tokenThresholdTriggered': true,
                'candidateCount': 2,
                'writtenRecordCount': 1,
                'writtenKinds': <Object?>['user_preference'],
                'writtenRecordIds': <Object?>['memory-user-1'],
              },
              'bootstrap': <String, Object?>{
                'mode': 'full',
                'visibleFileCount': 2,
                'injectedFileCount': 2,
                'truncatedFileCount': 1,
                'files': <Object?>[
                  <String, Object?>{
                    'name': 'AGENTS.md',
                    'relativePath': 'AGENTS.md',
                    'sourceCharCount': 42,
                    'injectedCharCount': 42,
                    'truncated': false,
                  },
                ],
              },
              'durableCompaction': <String, Object?>{
                'compactedThisRun': true,
                'triggerStage': 'pre_compaction',
                'executionMode': 'inline',
                'contextWindowTokens': 128000,
                'autoCompactTokenLimit': 115200,
                'estimatedReplayTokens': 116000,
                'tokenThresholdTriggered': true,
                'sourceTranscriptMessageCount': 18,
                'retainedTranscriptMessageCount': 12,
                'latestCompactedMessageCount': 6,
                'includedSummaryCount': 1,
                'totalSummaryCount': 1,
                'remoteCompaction': <String, Object?>{
                  'requested': true,
                  'supported': true,
                  'used': true,
                  'triggerStage': 'pre_compaction',
                  'outputItemCount': 2,
                  'compactionItemCount': 1,
                  'encryptedContentCount': 1,
                },
              },
              'skillInventory': <String, Object?>{
                'visibleSkillCount': 2,
                'injectedSkillCount': 2,
                'implicitSkillCount': 1,
                'skills': <Object?>[
                  <String, Object?>{
                    'name': 'ui-ux-pro-max',
                    'relativePath': 'skills/ui-ux-pro-max/SKILL.md',
                    'invocationControl': 'manual',
                    'userInvocable': true,
                    'executionContext': 'shared',
                  },
                ],
              },
              'activeSkill': <String, Object?>{
                'name': 'ui-ux-pro-max',
                'relativePath': 'skills/ui-ux-pro-max/SKILL.md',
                'activationSource': 'skill_read',
                'pinned': true,
                'toolRestrictionEnabled': true,
                'allowedToolKeys': <Object?>['read', 'write'],
              },
              'lastEvent': <String, Object?>{
                'kind': 'memory_write',
                'runId': 'run-memory-write-1',
                'taskId': 'task-memory-write-1',
                'emittedAtEpochMs': 2500,
                'writtenRecordIds': <Object?>['memory-user-1'],
                'writtenKinds': <Object?>['user_preference'],
                'resolvedRecordIds': <Object?>['commitment-done-1'],
                'reaffirmedRecordIds': <Object?>['commitment-keep-1'],
                'expiredRecordIds': <Object?>['commitment-old-1'],
              },
            },
          ],
          'events': <Object?>[
            <String, Object?>{
              'kind': 'memory_retrieval',
              'runId': 'run-memory-read-1',
              'taskId': 'task-memory-read-1',
              'emittedAtEpochMs': 2400,
              'toolName': 'memory_search',
              'operation': 'search',
              'queryTerms': <Object?>['gradle', 'wrapper'],
              'recordIds': <Object?>['memory-user-1'],
              'paths': <Object?>['memory/2024-03-11.md'],
              'lineRanges': <Object?>['5-8'],
            },
            <String, Object?>{
              'kind': 'memory_write',
              'runId': 'run-memory-write-1',
              'taskId': 'task-memory-write-1',
              'emittedAtEpochMs': 2500,
              'writtenRecordIds': <Object?>['memory-user-1'],
              'writtenKinds': <Object?>['user_preference'],
              'resolvedRecordIds': <Object?>['commitment-done-1'],
              'reaffirmedRecordIds': <Object?>['commitment-keep-1'],
              'expiredRecordIds': <Object?>['commitment-old-1'],
            },
          ],
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final snapshot = await bridge.loadChatRuntimeSnapshot();

      expect(snapshot.sessionId, 'session-1');
      expect(snapshot.events.first.kind, 'memory_retrieval');
      expect(snapshot.events.first.recordIds, <String>['memory-user-1']);
      expect(snapshot.events[1].kind, 'memory_write');
      expect(snapshot.events[1].writtenRecordIds, <String>['memory-user-1']);
      expect(snapshot.events[1].writtenKinds, <String>['user_preference']);
      expect(snapshot.events[1].reaffirmedRecordIds, <String>[
        'commitment-keep-1',
      ]);
      expect(snapshot.activeRuns.single.memoryFlush?.outcome, 'written');
      expect(
        snapshot.activeRuns.single.memoryFlush?.triggerStage,
        'pre_compaction',
      );
      expect(snapshot.activeRuns.single.memoryFlush?.executionMode, 'inline');
      expect(
        snapshot.activeRuns.single.memoryFlush?.contextWindowTokens,
        128000,
      );
      expect(
        snapshot.activeRuns.single.memoryFlush?.autoCompactTokenLimit,
        115200,
      );
      expect(
        snapshot.activeRuns.single.memoryFlush?.estimatedReplayTokens,
        116000,
      );
      expect(
        snapshot.activeRuns.single.memoryFlush?.tokenThresholdTriggered,
        isTrue,
      );
      expect(snapshot.activeRuns.single.bootstrap?.mode, 'full');
      expect(
        snapshot.activeRuns.single.bootstrap?.files.single.name,
        'AGENTS.md',
      );
      expect(
        snapshot.activeRuns.single.durableCompaction?.compactedThisRun,
        isTrue,
      );
      expect(
        snapshot.activeRuns.single.durableCompaction?.triggerStage,
        'pre_compaction',
      );
      expect(
        snapshot.activeRuns.single.durableCompaction?.executionMode,
        'inline',
      );
      expect(
        snapshot.activeRuns.single.durableCompaction?.contextWindowTokens,
        128000,
      );
      expect(
        snapshot.activeRuns.single.durableCompaction?.autoCompactTokenLimit,
        115200,
      );
      expect(
        snapshot.activeRuns.single.durableCompaction?.estimatedReplayTokens,
        116000,
      );
      expect(
        snapshot.activeRuns.single.durableCompaction?.tokenThresholdTriggered,
        isTrue,
      );
      expect(
        snapshot.activeRuns.single.durableCompaction?.remoteCompaction?.used,
        isTrue,
      );
      expect(
        snapshot
            .activeRuns
            .single
            .durableCompaction
            ?.remoteCompaction
            ?.triggerStage,
        'pre_compaction',
      );
      expect(
        snapshot
            .activeRuns
            .single
            .durableCompaction
            ?.remoteCompaction
            ?.outputItemCount,
        2,
      );
      expect(
        snapshot.activeRuns.single.skillInventory?.skills.single.name,
        'ui-ux-pro-max',
      );
      expect(snapshot.activeRuns.single.activeSkill?.name, 'ui-ux-pro-max');
      expect(snapshot.activeRuns.single.activeSkill?.pinned, isTrue);
      expect(snapshot.activeRuns.single.activeSkill?.allowedToolKeys, <String>[
        'read',
        'write',
      ]);
      expect(snapshot.activeRuns.single.lastEvent?.expiredRecordIds, <String>[
        'commitment-old-1',
      ]);
    },
  );

  test('local runtime bridge loads memory debug snapshots', () async {
    requestHandler = (request) async {
      expect(request.uri.path, '/v1/memory_debug_snapshot');
      await writeJson(request, <String, Object?>{
        'sessionId': 'session-1',
        'workspaceId': 'workspace-main',
        'observedAtEpochMs': 5000,
        'records': <Object?>[
          <String, Object?>{
            'id': 'memory-user',
            'content': 'User prefers Chinese replies.',
            'kind': 'user_preference',
            'scope': 'user',
            'status': 'active',
            'preferenceKey': 'agent_display_name',
            'preferenceValue': 'Xiao Bai',
            'isExpired': false,
          },
        ],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.loadMemoryDebugSnapshot();

    expect(snapshot.sessionId, 'session-1');
    expect(snapshot.workspaceId, 'workspace-main');
    expect(snapshot.records.single.id, 'memory-user');
    expect(snapshot.records.single.preferenceValue, 'Xiao Bai');
  });

  test('local runtime bridge searches projected memory debug corpus', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/memory_debug_search');
      capturedBody = await readJsonBody(request);
      await writeJson(request, <String, Object?>{
        'sessionId': 'session-1',
        'workspaceId': 'workspace-main',
        'observedAtEpochMs': 5000,
        'query': 'xiao bai',
        'queryTerms': <Object?>['xiao', 'bai'],
        'corpusFileCount': 2,
        'results': <Object?>[
          <String, Object?>{
            'recordId': 'memory-user',
            'path': 'MEMORY.md',
            'startLine': 5,
            'endLine': 5,
            'score': 420,
            'matchedTerms': <Object?>['xiao', 'bai'],
            'kind': 'user_preference',
            'scope': 'user',
            'status': 'active',
            'snippet': 'User prefers Chinese replies.',
          },
        ],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.searchMemoryDebug(query: 'xiao bai');

    expect(capturedBody['query'], 'xiao bai');
    expect(capturedBody['maxResults'], 4);
    expect(capturedBody['minScore'], 1);
    expect(snapshot.query, 'xiao bai');
    expect(snapshot.results.single.recordId, 'memory-user');
    expect(snapshot.results.single.path, 'MEMORY.md');
  });

  test('local runtime bridge loads projected memory debug slices', () async {
    late Map<String, Object?> capturedBody;
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/memory_debug_slice');
      capturedBody = await readJsonBody(request);
      await writeJson(request, <String, Object?>{
        'sessionId': 'session-1',
        'workspaceId': 'workspace-main',
        'observedAtEpochMs': 5000,
        'path': 'MEMORY.md',
        'text': 'User prefers Chinese replies.',
        'startLine': 5,
        'endLine': 5,
        'totalLineCount': 12,
        'recordIds': <Object?>['memory-user'],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.getMemoryDebugSlice(
      path: 'MEMORY.md',
      fromLine: 5,
      lines: 1,
    );

    expect(capturedBody['path'], 'MEMORY.md');
    expect(capturedBody['fromLine'], 5);
    expect(capturedBody['lines'], 1);
    expect(snapshot.path, 'MEMORY.md');
    expect(snapshot.recordIds, <String>['memory-user']);
    expect(snapshot.text, 'User prefers Chinese replies.');
  });

  test('local runtime bridge loads memory debug link snapshots', () async {
    requestHandler = (request) async {
      expect(request.uri.path, '/v1/memory_debug_links_snapshot');
      await writeJson(request, <String, Object?>{
        'sessionId': 'session-1',
        'workspaceId': 'workspace-main',
        'observedAtEpochMs': 5000,
        'records': <Object?>[
          <String, Object?>{
            'recordId': 'memory-user',
            'sourceSessionId': 'session-1',
            'sourceTaskId': 'task-memory-origin-1',
            'sourceRun': <String, Object?>{
              'sessionId': 'session-1',
              'runId': 'run-memory-origin-1',
              'taskId': 'task-memory-origin-1',
              'acceptedAtEpochMs': 2000,
              'updatedAtEpochMs': 2200,
              'executionStatus': 'success',
            },
            'promptRecalls': <Object?>[
              <String, Object?>{
                'occurredAtEpochMs': 4600,
                'run': <String, Object?>{
                  'sessionId': 'session-1',
                  'runId': 'run-memory',
                  'taskId': 'task-memory',
                  'acceptedAtEpochMs': 1000,
                  'updatedAtEpochMs': 2400,
                },
                'score': 420,
                'matchedTerms': <Object?>['chinese'],
              },
            ],
            'toolRetrievals': <Object?>[
              <String, Object?>{
                'occurredAtEpochMs': 1600,
                'run': <String, Object?>{
                  'sessionId': 'session-1',
                  'runId': 'run-memory',
                  'taskId': 'task-memory',
                  'acceptedAtEpochMs': 1000,
                  'updatedAtEpochMs': 2400,
                },
                'toolName': 'memory_search',
                'operation': 'search',
                'queryTerms': <Object?>['name'],
              },
            ],
            'maintenanceActions': <Object?>[
              <String, Object?>{
                'action': 'written',
                'occurredAtEpochMs': 2200,
                'run': <String, Object?>{
                  'sessionId': 'session-1',
                  'runId': 'run-memory-origin-1',
                  'taskId': 'task-memory-origin-1',
                  'acceptedAtEpochMs': 2000,
                  'updatedAtEpochMs': 2200,
                },
              },
            ],
          },
        ],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.loadMemoryDebugLinksSnapshot();

    expect(snapshot.sessionId, 'session-1');
    expect(snapshot.records.single.recordId, 'memory-user');
    expect(snapshot.records.single.sourceRun?.runId, 'run-memory-origin-1');
    expect(snapshot.records.single.promptRecalls.single.score, 420);
    expect(
      snapshot.records.single.toolRetrievals.single.toolName,
      'memory_search',
    );
    expect(snapshot.records.single.maintenanceActions.single.action, 'written');
  });

  test('local runtime bridge loads soul debug snapshots', () async {
    requestHandler = (request) async {
      expect(request.uri.path, '/v1/soul_debug_snapshot');
      await writeJson(request, <String, Object?>{
        'sessionId': 'session-1',
        'workspaceId': 'workspace-main',
        'observedAtEpochMs': 5000,
        'storedSoul': <String, Object?>{
          'agentId': 'app-shell-personalization',
          'presetName': 'STEADY',
        },
        'baseSoul': <String, Object?>{'presetName': 'STEADY', 'tone': 'steady'},
        'effectiveSoul': <String, Object?>{
          'presetName': 'STEADY',
          'displayName': 'Xiao Bai',
          'tone': 'warm',
        },
        'interactionPreferenceDebug': <String, Object?>{
          'scope': 'user',
          'snapshotRecordId': 'interaction-state',
          'preferredNaming': 'A-Cheng',
          'preferredAddressStyle': 'friendly',
          'derivedRelationshipStyle': 'warm',
          'state': <String, Object?>{
            'warmth': <String, Object?>{
              'offset': 1,
              'higherSupport': 2,
              'lowerSupport': 0,
            },
            'formality': <String, Object?>{
              'offset': -1,
              'higherSupport': 0,
              'lowerSupport': 2,
            },
            'initiative': <String, Object?>{
              'offset': 0,
              'higherSupport': 0,
              'lowerSupport': 0,
            },
            'addressStyle': <String, Object?>{
              'selectedStyle': 'friendly',
              'neutralSupport': 0,
              'friendlySupport': 2,
              'intimateSupport': 0,
            },
            'preferredNaming': 'A-Cheng',
            'preferredNamingSupport': 2,
          },
        },
        'relationshipStateDebug': <String, Object?>{
          'scope': 'user',
          'snapshotRecordId': 'relationship-state',
          'state': <String, Object?>{
            'familiarity': 66,
            'trust': 74,
            'safety': 76,
            'intimacyPermission': 61,
            'playfulnessPermission': 44,
            'affectionTendency': 34,
            'reciprocity': 49,
          },
          'recentNegativeGuardActive': false,
          'supportiveStyleUnlocked': true,
          'supportiveStyleChecks': <Object?>[
            <String, Object?>{
              'key': 'trust',
              'passed': true,
              'currentValue': 74,
              'threshold': 25,
            },
          ],
          'warmToneUnlocked': true,
          'warmToneChecks': <Object?>[
            <String, Object?>{
              'key': 'intimacy_permission',
              'passed': true,
              'currentValue': 61,
              'threshold': 25,
            },
          ],
          'derivedAddressStyle': 'intimate',
          'friendlyAddressChecks': <Object?>[],
          'intimateAddressChecks': <Object?>[
            <String, Object?>{
              'key': 'recent_negative_guard_inactive',
              'passed': true,
              'actualBoolean': true,
              'expectedBoolean': true,
            },
          ],
          'intimacyPermissionBand': 'warm',
          'playfulnessPermissionBand': 'familiar',
          'highIntimacyBehaviorAllowed': true,
          'highIntimacyChecks': <Object?>[],
          'playfulAffectionAllowed': true,
          'playfulAffectionChecks': <Object?>[],
        },
        'overlayRecords': <Object?>[
          <String, Object?>{
            'id': 'memory-user',
            'content': 'Call the agent Xiao Bai.',
            'kind': 'user_preference',
            'scope': 'user',
            'status': 'active',
          },
        ],
        'fieldSources': <Object?>[
          <String, Object?>{
            'field': 'displayName',
            'value': 'Xiao Bai',
            'sourceType': 'memory_overlay',
            'sourceLabel': 'user memory',
            'recordId': 'memory-user',
            'preferenceKey': 'agent_display_name',
            'sourceScope': 'user',
            'sourceDetail': 'Durable preference',
          },
        ],
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    final snapshot = await bridge.loadSoulDebugSnapshot();

    expect(snapshot.sessionId, 'session-1');
    expect(snapshot.effectiveSoul?.displayName, 'Xiao Bai');
    expect(snapshot.overlayRecords.single.id, 'memory-user');
    expect(snapshot.fieldSources.single.sourceType, 'memory_overlay');
    expect(snapshot.fieldSources.single.sourceScope, 'user');
    expect(snapshot.fieldSources.single.sourceDetail, 'Durable preference');
    expect(snapshot.interactionPreferenceDebug?.preferredNaming, 'A-Cheng');
    expect(snapshot.relationshipStateDebug?.derivedAddressStyle, 'intimate');
    expect(snapshot.relationshipStateDebug?.recentNegativeGuardActive, isFalse);
  });

  test('local runtime bridge applies memory debug actions', () async {
    requestHandler = (request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/v1/memory_debug_action');
      final body = await readJsonBody(request);
      expect(body['recordId'], 'memory-user');
      expect(body['actionId'], 'suppress');
      await writeJson(request, <String, Object?>{
        'recordId': 'memory-user',
        'action': 'suppress',
        'applied': true,
      });
    };

    final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
    await bridge.applyMemoryDebugAction(
      recordId: 'memory-user',
      actionId: 'suppress',
    );
  });

  test(
    'local runtime connector returns null when loopback runtime is unavailable',
    () async {
      final unavailablePort = server!.port;
      await server!.close(force: true);
      server = null;

      final bridge = await OpenCrayLocalRuntimeBridgeConnector(
        baseUrl: 'http://127.0.0.1:$unavailablePort/',
        requestTimeout: const Duration(milliseconds: 100),
      ).connect();

      expect(bridge, isNull);
    },
  );

  test(
    'bootstrap prefers local runtime connector before later connectors',
    () async {
      requestHandler = (request) async {
        if (request.uri.path == '/v1/shell_snapshot') {
          await writeJson(request, <String, Object?>{
            'initialTab': 'chat',
            'hostLabel': 'LOCAL RUNTIME',
            'hostSummary': 'Loopback runtime is attached.',
            'isHostConnected': true,
          });
          return;
        }
        request.response.statusCode = HttpStatus.notFound;
        await request.response.close();
      };
      final fallbackConnector = _RecordingConnector(OpenCraySeedBridge());

      final bridge = await OpenCrayHostBridgeBootstrap.bootstrap(
        connectors: <OpenCrayHostBridgeConnector>[
          OpenCrayLocalRuntimeBridgeConnector(baseUrl: baseUrl()),
          fallbackConnector,
        ],
        allowSeedFallback: false,
      );

      expect(bridge, isA<OpenCrayLocalRuntimeBridge>());
      expect(fallbackConnector.wasCalled, isFalse);
    },
  );
}

class _RecordingConnector implements OpenCrayHostBridgeConnector {
  _RecordingConnector(this.bridge);

  final OpenCrayHostBridge bridge;
  bool wasCalled = false;

  @override
  Future<OpenCrayHostBridge?> connect() async {
    wasCalled = true;
    return bridge;
  }
}

const String _tinyPngBase64 =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wn1yt4AAAAASUVORK5CYII=';
