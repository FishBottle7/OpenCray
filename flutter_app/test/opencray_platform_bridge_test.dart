import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/bridge/opencray_failure_bridge.dart';
import 'package:opencray/core/bridge/opencray_host_bridge_bootstrap.dart';
import 'package:opencray/core/bridge/opencray_platform_bridge.dart';
import 'package:opencray/core/bridge/opencray_seed_bridge.dart';
import 'package:opencray/core/models/opencray_chat_draft_attachment.dart';
import 'package:opencray/core/models/opencray_image_reference.dart';
import 'package:opencray/core/models/opencray_notification_settings.dart';

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

  test(
    'platform bridge sends rich clipboard payloads over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return null;
          });

      await bridge.copyRichTextToClipboard(
        plainText: 'Open https://opencray.dev/docs',
        htmlText: '<p>Open <a href="https://opencray.dev/docs">docs</a></p>',
      );

      expect(capturedCall.method, 'copyRichTextToClipboard');
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['plainText'], 'Open https://opencray.dev/docs');
      expect(
        arguments['htmlText'],
        '<p>Open <a href="https://opencray.dev/docs">docs</a></p>',
      );
    },
  );

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
            'openAiPromptCacheKeyStrategy': 'session',
            'openAiPromptCacheRetention': '24h',
            'anthropicPromptCachingEnabled': true,
            'anthropicPromptCacheTtl': '1h',
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
      openAiPromptCacheKeyStrategy: 'session',
      openAiPromptCacheRetention: '24h',
      anthropicPromptCachingEnabled: true,
      anthropicPromptCacheTtl: '1h',
    );

    expect(capturedCall.method, 'saveCustomLlmProvider');
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['selectedProviderOptionId'], 'custom');
    expect(arguments['providerName'], 'Acme');
    expect(arguments['openAiPromptCacheKeyStrategy'], 'session');
    expect(arguments['openAiPromptCacheRetention'], '24h');
    expect(arguments['anthropicPromptCachingEnabled'], true);
    expect(arguments['anthropicPromptCacheTtl'], '1h');
    expect(snapshot.selectedProviderOptionId, 'saved-custom');
    expect(snapshot.providerOptions.single.providerId, 'custom');
    expect(snapshot.openAiPromptCacheKeyStrategy, 'session');
    expect(snapshot.openAiPromptCacheRetention, '24h');
    expect(snapshot.anthropicPromptCachingEnabled, isTrue);
    expect(snapshot.anthropicPromptCacheTtl, '1h');
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

  test(
    'platform bridge preserves native bridge ids and injects flutter app ids into shell snapshots',
    () async {
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            if (call.method != 'loadShellSnapshot') {
              return null;
            }
            return <String, Object?>{
              'initialTab': 'chat',
              'hostLabel': 'HOST READY',
              'hostSummary': 'Detached runtime service active.',
              'isHostConnected': true,
              'bridgeInstanceId': 'native-bridge-1',
            };
          });

      final snapshot = await bridge.loadShellSnapshot();

      expect(snapshot.bridgeInstanceId, 'native-bridge-1');
      expect(snapshot.flutterAppInstanceId, isNotEmpty);
    },
  );

  test(
    'platform bridge loads sandbox preview embed config over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <String, Object?>{
              'previewUrl': 'https://3000-sb-preview.e2b.app/',
              'providerId': 'e2b',
              'headers': <String, Object?>{
                'E2B-Traffic-Access-Token': 'traffic-preview',
              },
              'sessionMatched': true,
              'accessTokenConfigured': true,
            };
          });

      final config = await bridge.resolveSandboxPreviewEmbedConfig(
        'https://3000-sb-preview.e2b.app/',
      );

      expect(capturedCall.method, 'resolveSandboxPreviewEmbedConfig');
      expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['previewUrl'], 'https://3000-sb-preview.e2b.app/');
      expect(config.providerId, 'e2b');
      expect(config.sessionMatched, isTrue);
      expect(config.headers['E2B-Traffic-Access-Token'], 'traffic-preview');
    },
  );

  test(
    'platform bridge loads settings image assets over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <Object?>[
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
            ];
          });

      final assets = await bridge.listSettingsImageAssets();

      expect(capturedCall.method, 'listSettingsImageAssets');
      expect(assets.single.assetId, 'settings-asset-1');
      expect(assets.single.sizeBytes, 2048);
    },
  );

  test(
    'platform bridge picks and imports settings image assets over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <Object?>[
              <String, Object?>{
                'assetId': 'settings-asset-picked',
                'displayName': 'avatar.png',
                'relativePath': 'settings-image-assets/avatar.png',
                'mimeType': 'image/png',
                'sha256':
                    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                'sizeBytes': 1024,
                'createdAtEpochMs': 1500,
              },
            ];
          });

      final assets = await bridge.pickSettingsImageAssets();

      expect(capturedCall.method, 'pickSettingsImageAssets');
      expect(assets.single.assetId, 'settings-asset-picked');
      expect(assets.single.displayName, 'avatar.png');
    },
  );

  test(
    'platform bridge imports settings image assets over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <Object?>[
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
            ];
          });

      final assets = await bridge.importSettingsImageAssets(<String>[
        'content://images/reference.png',
      ]);

      expect(capturedCall.method, 'importSettingsImageAssets');
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['uriStrings'], <String>[
        'content://images/reference.png',
      ]);
      expect(assets.single.assetId, 'settings-asset-2');
      expect(assets.single.relativePath, 'settings-image-assets/reference.png');
    },
  );

  test(
    'platform bridge loads soul visual identity over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <String, Object?>{
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
            };
          });

      final identity = await bridge.loadSoulVisualIdentity();

      expect(capturedCall.method, 'loadSoulVisualIdentity');
      expect(identity?.primaryPortrait?.refId, 'portrait-1');
      expect(identity?.referenceImages.single.refId, 'reference-1');
    },
  );

  test('platform bridge posts save soul primary portrait requests', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'portraitSummary': 'Calm expression with short dark hair.',
            'primaryPortrait': <String, Object?>{
              'refId': 'portrait-2',
              'role': 'portrait',
              'storageScope': 'agent_private',
              'relativePath': 'soul-assets/portrait/portrait-2.png',
              'summary': 'Updated front-facing portrait with steady gaze.',
              'caption': 'Front portrait',
              'createdAtEpochMs': 1500,
            },
            'referenceImages': const <Object?>[],
          };
        });

    final identity = await bridge.saveSoulPrimaryPortrait(
      const OpenCrayImageReferenceSource(
        sourceKind: OpenCrayImageReferenceSourceKind.settingsAsset,
        settingsAssetId: 'settings-asset-1',
        displayName: 'portrait.png',
        mimeType: 'image/png',
      ),
    );

    expect(capturedCall.method, 'saveSoulPrimaryPortrait');
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(
      (arguments['source'] as Map<Object?, Object?>)['settingsAssetId'],
      'settings-asset-1',
    );
    expect(identity?.primaryPortrait?.refId, 'portrait-2');
    expect(identity?.portraitSummary, 'Calm expression with short dark hair.');
  });

  test(
    'platform bridge loads memory image references over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <Object?>[
              <String, Object?>{
                'refId': 'memory-image-2',
                'role': 'evidence',
                'storageScope': 'workspace',
                'relativePath': 'memory-assets/sketch.png',
                'summary':
                    'Sketch captured during the earlier planning session.',
                'caption': 'Sketch',
                'createdAtEpochMs': 1600,
              },
            ];
          });

      final references = await bridge.listMemoryImageReferences('memory-2');

      expect(capturedCall.method, 'listMemoryImageReferences');
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['memoryId'], 'memory-2');
      expect(references.single.refId, 'memory-image-2');
      expect(references.single.caption, 'Sketch');
    },
  );

  test(
    'platform bridge posts memory image reference attachment requests',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <String, Object?>{
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
            };
          });

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

      expect(capturedCall.method, 'attachMemoryImageReference');
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['memoryId'], 'memory-1');
      expect(arguments['preferredMode'], 'copy_promote');
      expect(
        (arguments['source'] as Map<Object?, Object?>)['settingsAssetId'],
        'settings-asset-1',
      );
      expect(result?.recordVersion, 4);
      expect(result?.imageReferences.single.refId, 'memory-image-1');
    },
  );

  test(
    'platform bridge loads notification settings over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <String, Object?>{
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
            };
          });

      final snapshot = await bridge.loadNotificationSettings();

      expect(capturedCall.method, 'loadNotificationSettings');
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
    },
  );

  test(
    'platform bridge saves notification settings over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
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
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            final arguments = call.arguments as Map<Object?, Object?>;
            return <String, Object?>{
              'masterEnabled': arguments['masterEnabled'],
              'defaultDeliveryModeId': arguments['defaultDeliveryModeId'],
              'quietHoursEnabled': arguments['quietHoursEnabled'],
              'quietHoursStartMinutes': arguments['quietHoursStartMinutes'],
              'quietHoursEndMinutes': arguments['quietHoursEndMinutes'],
              'approvalRequestsEnabled': arguments['approvalRequestsEnabled'],
              'approvalReminderEnabled': arguments['approvalReminderEnabled'],
              'taskFinishedEnabled': arguments['taskFinishedEnabled'],
              'taskFailedEnabled': arguments['taskFailedEnabled'],
              'scheduledWakeEnabled': arguments['scheduledWakeEnabled'],
              'backgroundTaskPausedEnabled':
                  arguments['backgroundTaskPausedEnabled'],
              'serviceRecoveredEnabled': arguments['serviceRecoveredEnabled'],
            };
          });

      final snapshot = await bridge.saveNotificationSettings(settings);

      expect(capturedCall.method, 'saveNotificationSettings');
      expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['masterEnabled'], isFalse);
      expect(arguments['defaultDeliveryModeId'], 'critical');
      expect(arguments['quietHoursEnabled'], isFalse);
      expect(arguments['quietHoursStartMinutes'], 1320);
      expect(arguments['quietHoursEndMinutes'], 420);
      expect(arguments['approvalRequestsEnabled'], isTrue);
      expect(arguments['approvalReminderEnabled'], isFalse);
      expect(arguments['taskFinishedEnabled'], isTrue);
      expect(arguments['taskFailedEnabled'], isTrue);
      expect(arguments['scheduledWakeEnabled'], isTrue);
      expect(arguments['backgroundTaskPausedEnabled'], isFalse);
      expect(arguments['serviceRecoveredEnabled'], isTrue);
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
    },
  );

  test('platform bridge saves safety settings over the host channel', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          final arguments = call.arguments as Map<Object?, Object?>;
          return <String, Object?>{
            'automationModeId': 'dev',
            'rollbackJournalEnabled': true,
            'maxFilesPerBatch': 20,
            'maxAgentTurns': 0,
            'maxToolCalls': 0,
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
            'liveContextModeId': arguments['liveContextModeId'],
            'memoryToolsEnabled': arguments['memoryToolsEnabled'],
          };
        });

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

    expect(capturedCall.method, 'saveSafetySettings');
    expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['automationModeId'], 'dev');
    expect(arguments['maxAgentTurns'], 0);
    expect(arguments['maxToolCalls'], 0);
    expect(arguments['fileDeletesPolicyId'], 'block');
    expect(arguments['liveContextModeId'], 'no_soul');
    expect(arguments['memoryToolsEnabled'], false);
    expect(snapshot.automationModeId, 'dev');
    expect(snapshot.maxAgentTurns, 0);
    expect(snapshot.maxToolCalls, 0);
    expect(snapshot.fileDeletesPolicyId, 'block');
    expect(snapshot.liveContextModeId, 'no_soul');
    expect(snapshot.memoryToolsEnabled, false);
  });

  test('platform bridge loads strong background snapshots', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
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
          };
        });

    final snapshot = await bridge.loadStrongBackgroundSnapshot();

    expect(capturedCall.method, 'loadStrongBackgroundSnapshot');
    expect(snapshot.available, isTrue);
    expect(snapshot.tierId, 'strong_background');
    expect(snapshot.setupComplete, isTrue);
    expect(snapshot.notifications.configured, isTrue);
    expect(snapshot.exactAlarms.accessGranted, isTrue);
    expect(snapshot.batteryOptimization.exempt, isTrue);
    expect(snapshot.actions.single.id, 'open_notification_settings');
    expect(snapshot.runtimeServiceConnectionState?.binderAvailable, isTrue);
  });

  test('platform bridge posts strong background actions', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'source': 'strong-background-action',
            'actionId': 'open_notification_settings',
            'available': true,
            'launched': true,
          };
        });

    final result = await bridge.performStrongBackgroundAction(
      'open_notification_settings',
    );

    expect(capturedCall.method, 'performStrongBackgroundAction');
    expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['actionId'], 'open_notification_settings');
    expect(result.actionId, 'open_notification_settings');
    expect(result.available, isTrue);
    expect(result.launched, isTrue);
  });

  test(
    'platform bridge authorizes external access over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return true;
          });

      final granted = await bridge.authorizeExternalAccessLocation(
        'photo_library',
      );

      expect(capturedCall.method, 'authorizeExternalAccessLocation');
      expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['locationId'], 'photo_library');
      expect(granted, isTrue);
    },
  );

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

  test(
    'platform bridge inspects skill sources over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <String, Object?>{
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
            };
          });

      final inspection = await bridge.inspectSkillSource('roin-orca/skills');

      expect(capturedCall.method, 'inspectSkillSource');
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['sourceRef'], 'roin-orca/skills');
      expect(inspection.sourceType, 'remote_github');
      expect(inspection.candidates.single.name, 'find-skills');
    },
  );

  test(
    'platform bridge sends selected skill installs over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return 'Installed review-skills.';
          });

      final message = await bridge.installSkillSource(
        'roin-orca/skills',
        selectedSkillName: 'review-skills',
      );

      expect(capturedCall.method, 'installSkillSource');
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['sourceRef'], 'roin-orca/skills');
      expect(arguments['selectedSkillName'], 'review-skills');
      expect(message, 'Installed review-skills.');
    },
  );

  test(
    'platform bridge sends batch skill installs over the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return 'Installed 2 skills.';
          });

      final message = await bridge.installSkillSourceBatch(
        'roin-orca/skills',
        selectedSkillNames: const <String>['find-skills', 'review-skills'],
      );

      expect(capturedCall.method, 'installSkillSourceBatch');
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['sourceRef'], 'roin-orca/skills');
      expect(arguments['selectedSkillNames'], <String>[
        'find-skills',
        'review-skills',
      ]);
      expect(message, 'Installed 2 skills.');
    },
  );

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

  test('platform bridge loads voice playback sources', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'name': 'voice-note.m4a',
            'relativePath':
                '.opencray/chat-media/session-1/hash/voice-note.m4a',
            'localFilePath': '/workspace/session-1/voice-note.m4a',
            'mimeType': 'audio/mp4',
            'durationMs': 4200,
          };
        });

    final source = await bridge.loadWorkspaceVoicePlaybackSource(
      '.opencray/chat-media/session-1/hash/voice-note.m4a',
    );

    expect(capturedCall.method, 'loadWorkspaceVoicePlaybackSource');
    expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(
      arguments['relativePath'],
      '.opencray/chat-media/session-1/hash/voice-note.m4a',
    );
    expect(source.name, 'voice-note.m4a');
    expect(source.localFilePath, '/workspace/session-1/voice-note.m4a');
    expect(source.mimeType, 'audio/mp4');
    expect(source.durationMs, 4200);
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
    'platform bridge forwards media save requests to the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <String, Object?>{
              'displayName': 'voice-note.m4a',
              'collection': 'recordings',
              'uri': 'content://media/audio/42',
            };
          });

      final saved = await bridge.saveWorkspaceMediaAttachment(
        relativePath: '.opencray/chat-media/session-1/hash/voice-note.m4a',
        kind: 'voice',
      );

      expect(capturedCall.method, 'saveWorkspaceMediaAttachment');
      expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(
        arguments['relativePath'],
        '.opencray/chat-media/session-1/hash/voice-note.m4a',
      );
      expect(arguments['kind'], 'voice');
      expect(saved.displayName, 'voice-note.m4a');
      expect(saved.collection, 'recordings');
      expect(saved.uri, 'content://media/audio/42');
    },
  );

  test(
    'platform bridge forwards open file requests to the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return null;
          });

      await bridge.openWorkspaceEntry(
        '.opencray/chat-media/session-1/hash/report.pdf',
      );

      expect(capturedCall.method, 'openWorkspaceEntry');
      expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(
        arguments['relativePath'],
        '.opencray/chat-media/session-1/hash/report.pdf',
      );
    },
  );

  test(
    'platform bridge forwards external uri requests to the host channel',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return null;
          });

      await bridge.openExternalUri('https://opencray.dev/docs');

      expect(capturedCall.method, 'openExternalUri');
      expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['uri'], 'https://opencray.dev/docs');
    },
  );

  test(
    'platform bridge parses memory maintenance fields from chat runtime snapshots',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <String, Object?>{
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
            };
          });

      final snapshot = await bridge.loadChatRuntimeSnapshot();

      expect(capturedCall.method, 'loadChatRuntimeSnapshot');
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
            ?.encryptedContentCount,
        1,
      );
      expect(
        snapshot.activeRuns.single.durableCompaction?.tokenThresholdTriggered,
        isTrue,
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

  test('platform bridge loads memory debug snapshots', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
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
          };
        });

    final snapshot = await bridge.loadMemoryDebugSnapshot();

    expect(capturedCall.method, 'loadMemoryDebugSnapshot');
    expect(snapshot.workspaceId, 'workspace-main');
    expect(snapshot.records.single.id, 'memory-user');
    expect(snapshot.records.single.preferenceValue, 'Xiao Bai');
  });

  test('platform bridge searches projected memory debug corpus', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
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
          };
        });

    final snapshot = await bridge.searchMemoryDebug(query: 'xiao bai');

    expect(capturedCall.method, 'searchMemoryDebug');
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['query'], 'xiao bai');
    expect(arguments['maxResults'], 4);
    expect(arguments['minScore'], 1);
    expect(snapshot.query, 'xiao bai');
    expect(snapshot.results.single.recordId, 'memory-user');
    expect(snapshot.results.single.path, 'MEMORY.md');
  });

  test('platform bridge loads projected memory debug slices', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'sessionId': 'session-1',
            'workspaceId': 'workspace-main',
            'observedAtEpochMs': 5000,
            'path': 'MEMORY.md',
            'text': 'User prefers Chinese replies.',
            'startLine': 5,
            'endLine': 5,
            'totalLineCount': 12,
            'recordIds': <Object?>['memory-user'],
          };
        });

    final snapshot = await bridge.getMemoryDebugSlice(
      path: 'MEMORY.md',
      fromLine: 5,
      lines: 1,
    );

    expect(capturedCall.method, 'getMemoryDebugSlice');
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['path'], 'MEMORY.md');
    expect(arguments['fromLine'], 5);
    expect(arguments['lines'], 1);
    expect(snapshot.path, 'MEMORY.md');
    expect(snapshot.recordIds, <String>['memory-user']);
    expect(snapshot.text, 'User prefers Chinese replies.');
  });

  test('platform bridge loads memory debug link snapshots', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
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
          };
        });

    final snapshot = await bridge.loadMemoryDebugLinksSnapshot();

    expect(capturedCall.method, 'loadMemoryDebugLinksSnapshot');
    expect(snapshot.records.single.recordId, 'memory-user');
    expect(snapshot.records.single.sourceRun?.runId, 'run-memory-origin-1');
    expect(snapshot.records.single.promptRecalls.single.score, 420);
    expect(
      snapshot.records.single.toolRetrievals.single.toolName,
      'memory_search',
    );
    expect(snapshot.records.single.maintenanceActions.single.action, 'written');
  });

  test('platform bridge loads soul debug snapshots', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return <String, Object?>{
            'sessionId': 'session-1',
            'workspaceId': 'workspace-main',
            'observedAtEpochMs': 5000,
            'storedSoul': <String, Object?>{
              'agentId': 'app-shell-personalization',
              'presetName': 'STEADY',
            },
            'baseSoul': <String, Object?>{
              'presetName': 'STEADY',
              'tone': 'steady',
            },
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
          };
        });

    final snapshot = await bridge.loadSoulDebugSnapshot();

    expect(capturedCall.method, 'loadSoulDebugSnapshot');
    expect(snapshot.effectiveSoul?.displayName, 'Xiao Bai');
    expect(snapshot.overlayRecords.single.id, 'memory-user');
    expect(snapshot.fieldSources.single.sourceType, 'memory_overlay');
    expect(snapshot.fieldSources.single.sourceScope, 'user');
    expect(snapshot.fieldSources.single.sourceDetail, 'Durable preference');
    expect(snapshot.interactionPreferenceDebug?.preferredNaming, 'A-Cheng');
    expect(snapshot.relationshipStateDebug?.derivedAddressStyle, 'intimate');
    expect(snapshot.relationshipStateDebug?.recentNegativeGuardActive, isFalse);
  });

  test('platform bridge applies memory debug actions', () async {
    late MethodCall capturedCall;
    const bridge = OpenCrayPlatformBridge();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          capturedCall = call;
          return null;
        });

    await bridge.applyMemoryDebugAction(
      recordId: 'memory-user',
      actionId: 'suppress',
    );

    expect(capturedCall.method, 'applyMemoryDebugAction');
    expect(capturedCall.arguments, <String, Object?>{
      'recordId': 'memory-user',
      'actionId': 'suppress',
    });
  });

  test(
    'platform bridge preserves attachment references when submitting chat messages',
    () async {
      late MethodCall capturedCall;
      const bridge = OpenCrayPlatformBridge();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            capturedCall = call;
            return <String, Object?>{
              'sessionId': 'session-1',
              'runId': 'run-1',
              'taskId': 'task-1',
              'acceptedAtEpochMs': 123,
            };
          });

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

      expect(capturedCall.method, 'submitChatMessage');
      expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
      final arguments = capturedCall.arguments as Map<Object?, Object?>;
      expect(arguments['text'], 'Reuse prior references');
      expect(arguments['attachments'], <Object?>[
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
