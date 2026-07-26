import 'dart:math';

final Random _openCrayLifecycleRandom = Random();

final String openCrayFlutterAppInstanceId = openCrayLifecycleId('flutter-app');

String openCrayLifecycleId(String prefix) {
  final int epochMs = DateTime.now().millisecondsSinceEpoch;
  final String randomSuffix = _openCrayLifecycleRandom
      .nextInt(0x7fffffff)
      .toRadixString(16)
      .padLeft(8, '0');
  return '$prefix-$epochMs-$randomSuffix';
}

Map<Object?, Object?> attachShellSnapshotClientLifecycle(
  Map<Object?, Object?> payload, {
  required String fallbackBridgeInstanceId,
}) => _attachRuntimeSnapshotClientLifecycle(
  payload,
  fallbackBridgeInstanceId: fallbackBridgeInstanceId,
);

Map<Object?, Object?> attachChatRuntimeSnapshotClientLifecycle(
  Map<Object?, Object?> payload, {
  required String fallbackBridgeInstanceId,
}) => _attachRuntimeSnapshotClientLifecycle(
  payload,
  fallbackBridgeInstanceId: fallbackBridgeInstanceId,
);

Map<Object?, Object?> attachChatSnapshotClientLifecycle(
  Map<Object?, Object?> payload, {
  required String fallbackBridgeInstanceId,
}) {
  final Object? runtimeActivity = payload['runtimeActivity'];
  if (runtimeActivity is! Map<Object?, Object?>) {
    return payload;
  }
  return <Object?, Object?>{
    ...payload,
    'runtimeActivity': _attachRuntimeSnapshotClientLifecycle(
      runtimeActivity,
      fallbackBridgeInstanceId: fallbackBridgeInstanceId,
    ),
  };
}

Map<Object?, Object?> _attachRuntimeSnapshotClientLifecycle(
  Map<Object?, Object?> payload, {
  required String fallbackBridgeInstanceId,
}) {
  final String bridgeInstanceId = _resolvedLifecycleId(
    payload['bridgeInstanceId'],
    fallbackBridgeInstanceId,
  );
  return <Object?, Object?>{
    ...payload,
    'flutterAppInstanceId': _resolvedLifecycleId(
      payload['flutterAppInstanceId'],
      openCrayFlutterAppInstanceId,
    ),
    'bridgeInstanceId': bridgeInstanceId,
    'bridgeEpoch': _resolvedLifecycleId(
      payload['bridgeEpoch'],
      bridgeInstanceId,
    ),
  };
}

String _resolvedLifecycleId(Object? rawValue, String fallback) {
  final String? normalized = (rawValue as String?)?.trim();
  if (normalized == null || normalized.isEmpty) {
    return fallback;
  }
  return normalized;
}
