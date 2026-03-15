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
