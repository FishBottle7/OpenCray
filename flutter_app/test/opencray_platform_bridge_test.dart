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
}
