import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_host_bridge.dart';
import 'package:opencray/core/bridge/opencray_host_bridge_bootstrap.dart';
import 'package:opencray/core/bridge/opencray_local_runtime_bridge.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';

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
        });
      };

      final bridge = OpenCrayLocalRuntimeBridge(baseUrl: baseUrl());
      final snapshot = await bridge.saveSafetySettings(
        automationModeId: 'dev',
        rollbackJournalEnabled: true,
        maxFilesPerBatch: 20,
        maxAgentTurns: 0,
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
      );

      expect(capturedBody['automationModeId'], 'dev');
      expect(capturedBody['maxAgentTurns'], 0);
      expect(capturedBody['fileDeletesPolicyId'], 'block');
      expect(snapshot.automationModeId, 'dev');
      expect(snapshot.maxAgentTurns, 0);
      expect(snapshot.fileDeletesPolicyId, 'block');
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
    );

    expect(capturedBody['selectedProviderOptionId'], 'custom');
    expect(capturedBody['providerNotes'], 'Regional fallback');
    expect(snapshot.selectedProviderOptionId, 'saved-custom');
    expect(snapshot.providerOptions.single.protocol, 'anthropic');
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
