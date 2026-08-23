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

part 'opencray_platform_bridge_shell.dart';
part 'opencray_platform_bridge_files.dart';
part 'opencray_platform_bridge_agents.dart';
part 'opencray_platform_bridge_settings.dart';
part 'opencray_platform_bridge_llm.dart';
part 'opencray_platform_bridge_personalization.dart';
part 'opencray_platform_bridge_mcp_safety.dart';
part 'opencray_platform_bridge_skills.dart';
part 'opencray_platform_bridge_chat.dart';

class OpenCrayPlatformBridge extends _PlatformBridgeDeps
    with
        _PlatformBridgeShellDomain,
        _PlatformBridgeFilesDomain,
        _PlatformBridgeAgentsDomain,
        _PlatformBridgeSettingsDomain,
        _PlatformBridgeLlmDomain,
        _PlatformBridgePersonalizationDomain,
        _PlatformBridgeMcpSafetyDomain,
        _PlatformBridgeSkillsDomain,
        _PlatformBridgeChatDomain
    implements OpenCrayHostBridge {
  const OpenCrayPlatformBridge();
}

abstract class _PlatformBridgeDeps implements OpenCrayHostBridge {
  const _PlatformBridgeDeps();
}

final String _fallbackBridgeInstanceId = openCrayLifecycleId(
  'platform-bridge',
);

const MethodChannel _methodChannel = MethodChannel(
  'com.opencray.host/methods',
);
const EventChannel _shellSnapshotChannel = EventChannel(
  'com.opencray.host/shell_snapshot',
);
const EventChannel _settingsOverviewChannel = EventChannel(
  'com.opencray.host/settings_overview',
);
const EventChannel _skillsSnapshotChannel = EventChannel(
  'com.opencray.host/skills_snapshot',
);
const EventChannel _chatSnapshotChannel = EventChannel(
  'com.opencray.host/chat_snapshot',
);
const EventChannel _chatRuntimeSnapshotChannel = EventChannel(
  'com.opencray.host/chat_runtime_snapshot',
);
const EventChannel _liveAssistantDraftChannel = EventChannel(
  'com.opencray.host/live_assistant_draft',
);
const EventChannel _runtimeEventDeltaChannel = EventChannel(
  'com.opencray.host/runtime_event_delta',
);

Future<Map<Object?, Object?>> _invokeMap(
  String method, {
  Map<String, Object?>? arguments,
}) async {
  final payload = await _methodChannel.invokeMethod<Object?>(
    method,
    arguments,
  );
  return _requireMap(payload);
}

Future<Map<Object?, Object?>?> _invokeNullableMap(
  String method, {
  Map<String, Object?>? arguments,
}) async {
  final payload = await _methodChannel.invokeMethod<Object?>(
    method,
    arguments,
  );
  if (payload == null) {
    return null;
  }
  return _requireMap(payload);
}

Future<List<Object?>> _invokeList(
  String method, {
  Map<String, Object?>? arguments,
}) async {
  final payload = await _methodChannel.invokeMethod<Object?>(
    method,
    arguments,
  );
  return _requireList(payload);
}

Map<Object?, Object?> _requireMap(Object? payload) {
  final map = payload as Map<Object?, Object?>?;
  if (map == null) {
    throw const FormatException('Expected a map payload from host bridge.');
  }
  return map;
}

OpenCrayShellSnapshot _parseShellSnapshot(
  Map<Object?, Object?> payload,
) {
  return OpenCrayShellSnapshot.fromMap(
    attachShellSnapshotClientLifecycle(
      payload,
      fallbackBridgeInstanceId: _fallbackBridgeInstanceId,
    ),
    defaultHostSummary: 'Flutter shell is attached to a host bridge.',
  );
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
