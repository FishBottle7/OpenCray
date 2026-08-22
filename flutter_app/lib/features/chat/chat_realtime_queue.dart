part of 'chat_feature_screen.dart';

enum _QueuedRealtimeKind { runtimeDelta, liveDraft }

class _QueuedRealtimeEnvelope {
  const _QueuedRealtimeEnvelope.delta({
    required this.value,
    required this.arrivalOrdinal,
  }) : kind = _QueuedRealtimeKind.runtimeDelta;

  const _QueuedRealtimeEnvelope.draft({
    required this.value,
    required this.arrivalOrdinal,
  }) : kind = _QueuedRealtimeKind.liveDraft;

  final Object value;
  final _QueuedRealtimeKind kind;
  final int arrivalOrdinal;

  int get sequence => switch (value) {
    OpenCrayChatRuntimeEventDelta delta => delta.sequence,
    OpenCrayChatLiveAssistantDraftEvent event => event.sequence ?? 0,
    _ => 0,
  };

  String get sessionId => switch (value) {
    OpenCrayChatRuntimeEventDelta delta => delta.sessionId.trim(),
    OpenCrayChatLiveAssistantDraftEvent event => event.sessionId.trim(),
    _ => '',
  };

  String get streamInstanceId => switch (value) {
    OpenCrayChatRuntimeEventDelta delta => delta.streamInstanceId?.trim() ?? '',
    OpenCrayChatLiveAssistantDraftEvent event =>
      event.streamInstanceId?.trim() ?? '',
    _ => '',
  };

  String get bridgeEpoch => switch (value) {
    OpenCrayChatRuntimeEventDelta delta => delta.bridgeEpoch?.trim() ?? '',
    OpenCrayChatLiveAssistantDraftEvent event => event.bridgeEpoch?.trim() ?? '',
    _ => '',
  };

  String get eventId => switch (value) {
    OpenCrayChatRuntimeEventDelta delta => delta.eventId?.trim() ?? '',
    OpenCrayChatLiveAssistantDraftEvent event => event.eventId?.trim() ?? '',
    _ => '',
  };

  String get deduplicationKey =>
      '${kind.name}|$sessionId|$streamInstanceId|$bridgeEpoch|$sequence|$eventId';
}
