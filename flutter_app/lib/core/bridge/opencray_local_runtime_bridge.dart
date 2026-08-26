import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

import '../models/opencray_chat_draft_attachment.dart';
import '../models/opencray_chat_snapshot.dart';
import '../models/opencray_agent_snapshot.dart';
import '../models/opencray_debug_snapshot.dart';
import '../models/opencray_file_image_preview.dart';
import '../models/opencray_file_text_preview.dart';
import '../models/opencray_file_voice_playback_source.dart';
import '../models/opencray_files_snapshot.dart';
import '../models/opencray_image_reference.dart';
import '../models/opencray_llm_config.dart';
import '../models/opencray_llm_validation.dart';
import '../models/opencray_media_speech_config.dart';
import '../models/opencray_mcp_settings.dart';
import '../models/opencray_network_search_config.dart';
import '../models/opencray_notification_settings.dart';
import '../models/opencray_personalization_config.dart';
import '../models/opencray_sandbox_preview_embed_config.dart';
import '../models/opencray_sandbox_settings.dart';
import '../models/opencray_safety_settings.dart';
import '../models/opencray_scheduled_tasks.dart';
import '../models/opencray_settings_snapshot.dart';
import '../models/opencray_shell_snapshot.dart';
import '../models/opencray_skills_snapshot.dart';
import '../models/opencray_strong_background.dart';
import '../models/opencray_twin_import_source_probe.dart';
import '../models/opencray_workspace_text_document.dart';
import 'opencray_bridge_lifecycle.dart';
import 'opencray_host_bridge.dart';
part 'opencray_local_runtime_bridge_shell.dart';
part 'opencray_local_runtime_bridge_files.dart';
part 'opencray_local_runtime_bridge_agents.dart';
part 'opencray_local_runtime_bridge_settings.dart';
part 'opencray_local_runtime_bridge_llm.dart';
part 'opencray_local_runtime_bridge_personalization.dart';
part 'opencray_local_runtime_bridge_mcp_safety.dart';
part 'opencray_local_runtime_bridge_skills.dart';
part 'opencray_local_runtime_bridge_chat.dart';

class _LocalRuntimeRealtimeEnvelope {
  const _LocalRuntimeRealtimeEnvelope({
    required this.kind,
    required this.payload,
  });

  final String kind;
  final Map<Object?, Object?> payload;
}

const int _watchFailureThreshold = 8;
const Duration _watchBackoffInitialDelay = Duration(milliseconds: 250);
const Duration _watchBackoffMaxDelay = Duration(seconds: 5);

Duration _watchBackoffDelay(
  int failureCount,
  Duration initialDelay,
  Duration maxDelay,
) {
  var shift = failureCount - 1;
  if (shift < 0) {
    shift = 0;
  }
  if (shift > 16) {
    shift = 16;
  }
  return Duration(
    milliseconds: (initialDelay.inMilliseconds << shift).clamp(
      1,
      maxDelay.inMilliseconds,
    ),
  );
}

class OpenCrayLocalRuntimeBridge extends _LocalRuntimeBridgeDeps
    with
        _LocalRuntimeBridgeShellDomain,
        _LocalRuntimeBridgeFilesDomain,
        _LocalRuntimeBridgeAgentsDomain,
        _LocalRuntimeBridgeSettingsDomain,
        _LocalRuntimeBridgeLlmDomain,
        _LocalRuntimeBridgePersonalizationDomain,
        _LocalRuntimeBridgeMcpSafetyDomain,
        _LocalRuntimeBridgeSkillsDomain,
        _LocalRuntimeBridgeChatDomain
    implements OpenCrayHostBridge {
  OpenCrayLocalRuntimeBridge({
    required String baseUrl,
    this.requestTimeout = const Duration(milliseconds: 800),
    this.pollInterval = const Duration(seconds: 2),
    this.watchFailureThreshold = _watchFailureThreshold,
    this.watchBackoffInitialDelay = _watchBackoffInitialDelay,
    this.watchBackoffMaxDelay = _watchBackoffMaxDelay,
  }) : _baseUri = _normalizeBaseUri(baseUrl) {
    _runtimeRealtimeController =
        StreamController<_LocalRuntimeRealtimeEnvelope>.broadcast(
          onListen: _startRuntimeRealtimePolling,
          onCancel: _stopRuntimeRealtimePolling,
        );
  }

  @override
  final Uri _baseUri;
  @override
  final Duration requestTimeout;
  @override
  final Duration pollInterval;
  @override
  final int watchFailureThreshold;
  @override
  final Duration watchBackoffInitialDelay;
  @override
  final Duration watchBackoffMaxDelay;
  @override
  final String _bridgeInstanceId = openCrayLifecycleId('local-runtime-bridge');
  @override
  late final StreamController<_LocalRuntimeRealtimeEnvelope>
  _runtimeRealtimeController;
  StreamSubscription<_LocalRuntimeRealtimeEnvelope>?
  _runtimeRealtimeSourceSubscription;

  void _startRuntimeRealtimePolling() {
    if (_runtimeRealtimeSourceSubscription != null) {
      return;
    }
    _runtimeRealtimeSourceSubscription = _watchRuntimeRealtimeEnvelopes()
        .listen(
          _runtimeRealtimeController.add,
          onError: _runtimeRealtimeController.addError,
        );
  }

  void _stopRuntimeRealtimePolling() {
    final StreamSubscription<_LocalRuntimeRealtimeEnvelope>? subscription =
        _runtimeRealtimeSourceSubscription;
    _runtimeRealtimeSourceSubscription = null;
    unawaited(subscription?.cancel());
  }

  Stream<_LocalRuntimeRealtimeEnvelope>
  _watchRuntimeRealtimeEnvelopes() async* {
    String sessionId = '';
    String streamInstanceId = '';
    int afterSequence = 0;
    while (true) {
      try {
        final String requestedSessionId = sessionId;
        final String requestedStreamInstanceId = streamInstanceId;
        final int requestedAfterSequence = afterSequence;
        final Map<Object?, Object?> response = await _getMap(
          'v1/chat_runtime_events',
          queryParameters: <String, String>{
            'sessionId': sessionId,
            'streamInstanceId': streamInstanceId,
            'afterSequence': afterSequence.toString(),
            'timeoutMs': '15000',
          },
          timeout: const Duration(seconds: 17),
        );
        final Map<Object?, Object?> payload = _requireMap(response['payload']);
        final String nextSessionId =
            (response['sessionId'] ?? payload['sessionId']) as String? ?? '';
        final String nextStreamInstanceId =
            (response['streamInstanceId'] ?? payload['streamInstanceId'])
                as String? ??
            '';
        final int nextSequence = switch (response['sequence'] ??
            response['lastSequence'] ??
            payload['sequence'] ??
            payload['lastSequence']) {
          int value => value,
          num value => value.toInt(),
          final Object? value => int.tryParse(value?.toString() ?? '') ?? 0,
        };
        final String normalizedNextSessionId = nextSessionId.trim();
        if (normalizedNextSessionId.isNotEmpty) {
          if (sessionId != normalizedNextSessionId) {
            afterSequence = 0;
          }
          sessionId = normalizedNextSessionId;
        }
        if (nextStreamInstanceId.trim().isNotEmpty) {
          if (streamInstanceId != nextStreamInstanceId.trim()) {
            afterSequence = 0;
          }
          streamInstanceId = nextStreamInstanceId.trim();
        }
        if (nextSequence > afterSequence) {
          afterSequence = nextSequence;
        }
        final String kind = response['kind'] as String? ?? '';
        final bool isSameOrderedCursor =
            requestedSessionId.isNotEmpty &&
            requestedStreamInstanceId.isNotEmpty &&
            normalizedNextSessionId == requestedSessionId &&
            nextStreamInstanceId.trim() == requestedStreamInstanceId &&
            nextSequence > requestedAfterSequence;
        if (kind == 'snapshot' && isSameOrderedCursor) {
          yield _LocalRuntimeRealtimeEnvelope(
            kind: 'delta',
            payload: <Object?, Object?>{
              ...attachChatRuntimeSnapshotClientLifecycle(
                payload,
                fallbackBridgeInstanceId: _bridgeInstanceId,
              ),
              'sequence': nextSequence,
              'lastSequence': nextSequence,
              'eventId':
                  'runtime-snapshot-$streamInstanceId-$normalizedNextSessionId-$nextSequence',
              'runPatchMode': 'snapshot',
            },
          );
        } else if (kind == 'draft' || kind == 'delta') {
          yield _LocalRuntimeRealtimeEnvelope(
            kind: kind,
            payload: attachChatRuntimeSnapshotClientLifecycle(
              payload,
              fallbackBridgeInstanceId: _bridgeInstanceId,
            ),
          );
        }
      } catch (_) {
        await Future<void>.delayed(const Duration(milliseconds: 250));
      }
    }
  }

  static Uri _normalizeBaseUri(String rawBaseUrl) {
    final trimmed = rawBaseUrl.trim();
    final withTrailingSlash = trimmed.endsWith('/') ? trimmed : '$trimmed/';
    return Uri.parse(withTrailingSlash);
  }
}

abstract class _LocalRuntimeBridgeDeps implements OpenCrayHostBridge {
  Uri get _baseUri;

  Duration get requestTimeout;

  Duration get pollInterval;

  int get watchFailureThreshold;

  Duration get watchBackoffInitialDelay;

  Duration get watchBackoffMaxDelay;

  String get _bridgeInstanceId;

  StreamController<_LocalRuntimeRealtimeEnvelope> get _runtimeRealtimeController;

  OpenCrayShellSnapshot _parseShellSnapshot(Map<Object?, Object?> payload) {
    return OpenCrayShellSnapshot.fromMap(
      attachShellSnapshotClientLifecycle(
        payload,
        fallbackBridgeInstanceId: _bridgeInstanceId,
      ),
      defaultHostSummary:
          'Flutter shell is attached to a local runtime bridge.',
    );
  }

  Stream<T> _watchMap<T>(
    Future<Map<Object?, Object?>> Function() loader,
    T Function(Map<Object?, Object?> payload) parser,
  ) async* {
    String? previousSignature;
    var consecutiveFailures = 0;
    while (true) {
      try {
        final payload = await loader();
        final signature = jsonEncode(_normalizeJson(payload));
        if (signature != previousSignature) {
          final parsed = parser(payload);
          previousSignature = signature;
          yield parsed;
        }
        consecutiveFailures = 0;
        await Future<void>.delayed(pollInterval);
      } catch (error, stackTrace) {
        consecutiveFailures += 1;
        if (consecutiveFailures >= watchFailureThreshold) {
          Error.throwWithStackTrace(error, stackTrace);
        }
        await Future<void>.delayed(
          _watchBackoffDelay(
            consecutiveFailures,
            watchBackoffInitialDelay,
            watchBackoffMaxDelay,
          ),
        );
      }
    }
  }

  Future<Map<Object?, Object?>> _getMap(
    String path, {
    Map<String, String>? queryParameters,
    Duration? timeout,
  }) async => _requireMap(
    await _requestJson(
      'GET',
      path,
      queryParameters: queryParameters,
      timeout: timeout,
    ),
  );

  Future<Map<Object?, Object?>> _postMap(
    String path, [
    Map<String, Object?>? body,
  ]) async => _requireMap(await _requestJson('POST', path, body: body));

  Future<String?> _postNullableString(
    String path, [
    Map<String, Object?>? body,
  ]) async {
    final payload = await _requestJson('POST', path, body: body);
    if (payload == null) {
      return null;
    }
    return payload as String?;
  }

  Future<String?> _getNullableString(
    String path, {
    Map<String, String>? queryParameters,
  }) async {
    final payload = await _requestJson(
      'GET',
      path,
      queryParameters: queryParameters,
    );
    if (payload == null) {
      return null;
    }
    return payload as String?;
  }

  Future<void> _postVoid(String path, [Map<String, Object?>? body]) async {
    await _requestJson('POST', path, body: body);
  }

  Future<Object?> _getJson(
    String path, {
    Map<String, String>? queryParameters,
  }) => _requestJson('GET', path, queryParameters: queryParameters);

  Future<Object?> _requestJson(
    String method,
    String path, {
    Map<String, String>? queryParameters,
    Map<String, Object?>? body,
    Duration? timeout,
  }) async {
    final Duration effectiveTimeout = timeout ?? requestTimeout;
    final client = HttpClient();
    client.connectionTimeout = effectiveTimeout;
    try {
      final request = await _openRequest(
        client,
        method,
        path,
        queryParameters: queryParameters,
      ).timeout(effectiveTimeout);
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      if (body != null) {
        request.headers.contentType = ContentType.json;
        request.write(jsonEncode(body));
      }
      final response = await request.close().timeout(effectiveTimeout);
      final responseBody = await response.transform(utf8.decoder).join();
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw HttpException(
          'Local runtime returned HTTP ${response.statusCode}${responseBody.isEmpty ? '' : ': $responseBody'}',
          uri: request.uri,
        );
      }
      if (responseBody.trim().isEmpty) {
        return null;
      }
      return jsonDecode(responseBody);
    } finally {
      client.close(force: true);
    }
  }

  Future<HttpClientRequest> _openRequest(
    HttpClient client,
    String method,
    String path, {
    Map<String, String>? queryParameters,
  }) {
    final resolved = _baseUri
        .resolve(path)
        .replace(
          queryParameters: queryParameters?.isEmpty == true
              ? null
              : queryParameters,
        );
    switch (method) {
      case 'POST':
        return client.postUrl(resolved);
      case 'GET':
      default:
        return client.getUrl(resolved);
    }
  }
}

List<Object?> _requireList(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <Object?>[];
  }
  return list;
}

List<String>? _listOfStrings(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return null;
  }
  return list.map((value) => value as String? ?? '').toList(growable: false);
}

Map<Object?, Object?> _requireMap(Object? payload) {
  final map = payload as Map<Object?, Object?>?;
  if (map == null) {
    throw const FormatException('Expected a map payload from local runtime.');
  }
  return map;
}

Object? _normalizeJson(Object? value) {
  if (value is Map<Object?, Object?>) {
    final entries = value.entries.toList()
      ..sort((left, right) => '${left.key}'.compareTo('${right.key}'));
    return <String, Object?>{
      for (final entry in entries) '${entry.key}': _normalizeJson(entry.value),
    };
  }
  if (value is List<Object?>) {
    return value.map(_normalizeJson).toList(growable: false);
  }
  return value;
}

OpenCraySettingsOverviewSnapshot _parseSettingsOverview(
  Map<Object?, Object?> payload,
) {
  return OpenCraySettingsOverviewSnapshot(
    eyebrow: payload['eyebrow'] as String? ?? '',
    title: payload['title'] as String? ?? '',
    subtitle: payload['subtitle'] as String? ?? '',
    deviceTitle: payload['deviceTitle'] as String? ?? '',
    deviceSummary: payload['deviceSummary'] as String? ?? '',
    entries: (_requireList(payload['entries']))
        .map(_requireMap)
        .map(
          (entry) => OpenCraySettingsHomeEntrySnapshot(
            routeId: entry['routeId'] as String? ?? 'home',
            title: entry['title'] as String? ?? '',
          ),
        )
        .toList(growable: false),
  );
}

OpenCraySettingsDetailSnapshot _parseSettingsDetail(
  Map<Object?, Object?> payload,
) {
  return OpenCraySettingsDetailSnapshot(
    routeId: payload['routeId'] as String? ?? 'home',
    title: payload['title'] as String? ?? '',
    subtitle: payload['subtitle'] as String? ?? '',
    sections: (_requireList(payload['sections']))
        .map(_requireMap)
        .map(
          (section) => OpenCraySettingsSectionSnapshot(
            title: section['title'] as String? ?? '',
            helperText: section['helperText'] as String?,
            rows: (_requireList(section['rows']))
                .map(_requireMap)
                .map(_parseSettingsRow)
                .toList(growable: false),
            segmentedOptions: _listOfStrings(section['segmentedOptions']),
            segmentedIndex: section['segmentedIndex'] as int?,
            inlinePanelText: section['inlinePanelText'] as String?,
            backgroundTone: _parseBackgroundTone(
              section['backgroundTone'] as String?,
            ),
          ),
        )
        .toList(growable: false),
  );
}

OpenCraySettingsRowSnapshot _parseSettingsRow(
  Map<Object?, Object?> payload,
) {
  switch (_parseTrailingKind(payload['trailingKind'] as String?)) {
    case OpenCraySettingsRowTrailingKind.chevron:
      return OpenCraySettingsRowSnapshot.chevron(
        title: payload['title'] as String? ?? '',
        subtitle: payload['subtitle'] as String?,
      );
    case OpenCraySettingsRowTrailingKind.toggle:
      return OpenCraySettingsRowSnapshot.toggle(
        title: payload['title'] as String? ?? '',
        subtitle: payload['subtitle'] as String?,
        toggleValue: payload['toggleValue'] as bool? ?? false,
      );
    case OpenCraySettingsRowTrailingKind.value:
      return OpenCraySettingsRowSnapshot.value(
        title: payload['title'] as String? ?? '',
        valueLabel: payload['valueLabel'] as String? ?? '',
      );
  }
}

OpenCraySettingsSectionBackgroundTone _parseBackgroundTone(
  String? rawValue,
) {
  switch (rawValue) {
    case 'danger':
      return OpenCraySettingsSectionBackgroundTone.danger;
    case 'surface':
    default:
      return OpenCraySettingsSectionBackgroundTone.surface;
  }
}

OpenCraySettingsRowTrailingKind _parseTrailingKind(String? rawValue) {
  switch (rawValue) {
    case 'toggle':
      return OpenCraySettingsRowTrailingKind.toggle;
    case 'value':
      return OpenCraySettingsRowTrailingKind.value;
    case 'chevron':
    default:
      return OpenCraySettingsRowTrailingKind.chevron;
  }
}
