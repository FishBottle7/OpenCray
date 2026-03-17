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
    );

    expect(capturedCall.method, 'saveSafetySettings');
    expect(capturedCall.arguments, isA<Map<Object?, Object?>>());
    final arguments = capturedCall.arguments as Map<Object?, Object?>;
    expect(arguments['automationModeId'], 'dev');
    expect(arguments['maxAgentTurns'], 0);
    expect(arguments['maxToolCalls'], 0);
    expect(arguments['fileDeletesPolicyId'], 'block');
    expect(snapshot.automationModeId, 'dev');
    expect(snapshot.maxAgentTurns, 0);
    expect(snapshot.maxToolCalls, 0);
    expect(snapshot.fileDeletesPolicyId, 'block');
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
                    'sourceTranscriptMessageCount': 18,
                    'retainedTranscriptMessageCount': 12,
                    'latestCompactedMessageCount': 6,
                    'includedSummaryCount': 1,
                    'totalSummaryCount': 1,
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
        snapshot.activeRuns.single.skillInventory?.skills.single.name,
        'ui-ux-pro-max',
      );
      expect(snapshot.activeRuns.single.activeSkill?.name, 'ui-ux-pro-max');
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
              },
            ],
          };
        });

    final snapshot = await bridge.loadSoulDebugSnapshot();

    expect(capturedCall.method, 'loadSoulDebugSnapshot');
    expect(snapshot.effectiveSoul?.displayName, 'Xiao Bai');
    expect(snapshot.overlayRecords.single.id, 'memory-user');
    expect(snapshot.fieldSources.single.sourceType, 'memory_overlay');
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
