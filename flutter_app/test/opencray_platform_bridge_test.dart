import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_failure_bridge.dart';
import 'package:opencray/core/bridge/opencray_host_bridge_bootstrap.dart';
import 'package:opencray/core/bridge/opencray_platform_bridge.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methodChannel = MethodChannel('com.opencray.host/methods');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, null);
  });

  test(
    'bootstrap falls back to seed bridge only when plugin is missing',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, null);

      final bridge = await OpenCrayHostBridgeBootstrap.bootstrap();

      expect(bridge, isA<OpenCraySeedBridge>());
    },
  );

  test(
    'bootstrap returns failure bridge when seed fallback is disabled',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, null);

      final bridge = await OpenCrayHostBridgeBootstrap.bootstrap(
        allowSeedFallback: false,
      );

      expect(bridge, isA<OpenCrayFailureBridge>());
    },
  );

  test('bootstrap returns failure bridge on host platform errors', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          throw PlatformException(
            code: 'HOST_INIT_FAILED',
            message: 'Host runtime initialization failed.',
          );
        });

    final bridge = await OpenCrayHostBridgeBootstrap.bootstrap();

    expect(bridge, isA<OpenCrayFailureBridge>());
  });

  test('platform bridge connector returns a live platform bridge', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          if (call.method == 'loadShellSnapshot') {
            return <String, Object?>{
              'initialTab': 'chat',
              'hostLabel': 'HOST CONNECTED',
              'hostSummary': 'Android host bridge is attached.',
              'isHostConnected': true,
            };
          }
          return null;
        });

    final bridge = await const OpenCrayPlatformBridgeConnector().connect();

    expect(bridge, isA<OpenCrayPlatformBridge>());
  });

  test('platform bridge sends protocol when validating llm config', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{'isSuccess': true, 'message': 'Validated.'};
        });

    final result = await bridge.validateLlmConfig(
      providerId: 'custom',
      protocol: 'anthropic',
      baseUrl: 'https://api.anthropic.com',
      apiKey: 'secret',
      model: 'claude-3-7-sonnet',
      reasoningEffort: 'xhigh',
    );

    expect(capturedCall.method, 'validateLlmConfig');
    expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['providerId'], 'custom');
    expect(arguments['protocol'], 'anthropic');
    expect(arguments['reasoningEffort'], 'xhigh');
    expect(result.isSuccess, isTrue);
    expect(result.message, 'Validated.');
  });

  test('platform bridge sends save custom provider requests', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
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
          };
        });

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

    expect(capturedCall.method, 'saveCustomLlmProvider');
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['selectedProviderOptionId'], 'custom');
    expect(arguments['providerName'], 'Acme');
    expect(snapshot.selectedProviderOptionId, 'saved-custom');
    expect(snapshot.providerOptions.single.providerId, 'custom');
  });

  test('platform bridge loads files snapshot payloads', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'rootName': 'agent-workspace',
            'rootPath': '/data/user/0/com.opencray/files/agent-workspace',
            'availableBytes': 2048,
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
                    'sizeBytes': 512,
                    'isTruncated': false,
                    'children': const <Object?>[],
                  },
                ],
              },
            ],
          };
        });

    final snapshot = await bridge.loadFilesSnapshot();

    expect(capturedCall.method, 'loadFilesSnapshot');
    expect(snapshot.rootName, 'agent-workspace');
    expect(snapshot.children.single.name, 'docs');
    expect(
      snapshot.children.single.children.single.relativePath,
      'docs/report.md',
    );
  });

  test('platform bridge saves safety settings over the host channel', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'automationModeId': 'dev',
            'rollbackJournalEnabled': true,
            'maxFilesPerBatch': 20,
            'undoWindowHours': 24,
            'fileChangesPolicyId': 'inherit',
            'fileDeletesPolicyId': 'block',
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
          };
        });

    final snapshot = await bridge.saveSafetySettings(
      automationModeId: 'dev',
      rollbackJournalEnabled: true,
      maxFilesPerBatch: 20,
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

    expect(capturedCall.method, 'saveSafetySettings');
    expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['automationModeId'], 'dev');
    expect(arguments['fileDeletesPolicyId'], 'block');
    expect(snapshot.automationModeId, 'dev');
    expect(snapshot.fileDeletesPolicyId, 'block');
  });

  test('platform bridge loads text preview payloads', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'name': 'report.md',
            'relativePath': 'docs/report.md',
            'content': '# Report\n\nPreview body',
            'isTruncated': false,
          };
        });

    final preview = await bridge.loadWorkspaceTextPreview('docs/report.md');

    expect(capturedCall.method, 'loadWorkspaceTextPreview');
    expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['relativePath'], 'docs/report.md');
    expect(preview.name, 'report.md');
    expect(preview.content, '# Report\n\nPreview body');
    expect(preview.isTruncated, isFalse);
  });

  test('platform bridge loads text document payloads', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'name': 'report.md',
            'relativePath': 'docs/report.md',
            'content': '# Report\n\nEditable body',
          };
        });

    final document = await bridge.loadWorkspaceTextDocument('docs/report.md');

    expect(capturedCall.method, 'loadWorkspaceTextDocument');
    expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['relativePath'], 'docs/report.md');
    expect(document.name, 'report.md');
    expect(document.content, '# Report\n\nEditable body');
  });

  test('platform bridge posts create text file requests', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'rootName': 'agent-workspace',
            'rootPath': '/tmp/agent-workspace',
            'availableBytes': 2048,
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
          };
        });

    final snapshot = await bridge.createWorkspaceTextFile(
      parentRelativePath: '',
      name: 'notes.txt',
    );

    expect(capturedCall.method, 'createWorkspaceTextFile');
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['parentRelativePath'], '');
    expect(arguments['name'], 'notes.txt');
    expect(snapshot.children.single.relativePath, 'notes.txt');
  });

  test('platform bridge posts save text document requests', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'rootName': 'agent-workspace',
            'rootPath': '/tmp/agent-workspace',
            'availableBytes': 2048,
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
          };
        });

    final snapshot = await bridge.saveWorkspaceTextDocument(
      targetRelativePath: 'notes.txt',
      content: 'hello world',
    );

    expect(capturedCall.method, 'saveWorkspaceTextDocument');
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['targetRelativePath'], 'notes.txt');
    expect(arguments['content'], 'hello world');
    expect(snapshot.children.single.sizeBytes, 11);
  });

  test('platform bridge loads image preview payloads', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'name': 'cover.png',
            'relativePath': 'images/cover.png',
            'mimeType': 'image/png',
            'width': 1,
            'height': 1,
            'bytesBase64':
                'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wn1yt4AAAAASUVORK5CYII=',
          };
        });

    final preview = await bridge.loadWorkspaceImagePreview('images/cover.png');

    expect(capturedCall.method, 'loadWorkspaceImagePreview');
    expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['relativePath'], 'images/cover.png');
    expect(preview.name, 'cover.png');
    expect(preview.mimeType, 'image/png');
    expect(preview.width, 1);
    expect(preview.height, 1);
    expect(preview.bytes, base64Decode(_tinyPngBase64));
  });

  test('platform bridge forwards share requests to the host channel', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return null;
        });

    await bridge.shareWorkspaceEntries(<String>['docs/report.md', 'todo.txt']);

    expect(capturedCall.method, 'shareWorkspaceEntries');
    expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['relativePaths'], <String>['docs/report.md', 'todo.txt']);
  });

  test(
    'platform bridge forwards native toast requests to the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return null;
          });

      await bridge.showNativeToast('Press back again to exit');

      expect(capturedCall.method, 'showNativeToast');
      expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['message'], 'Press back again to exit');
    },
  );
}

const String _tinyPngBase64 =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wn1yt4AAAAASUVORK5CYII=';
