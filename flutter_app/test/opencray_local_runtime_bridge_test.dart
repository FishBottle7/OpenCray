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
      );

      expect(capturedBody['automationModeId'], 'dev');
      expect(capturedBody['maxAgentTurns'], 0);
      expect(capturedBody['maxToolCalls'], 0);
      expect(capturedBody['fileDeletesPolicyId'], 'block');
      expect(snapshot.automationModeId, 'dev');
      expect(snapshot.maxAgentTurns, 0);
      expect(snapshot.maxToolCalls, 0);
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
