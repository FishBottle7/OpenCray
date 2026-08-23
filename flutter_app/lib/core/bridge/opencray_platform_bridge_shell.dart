part of 'opencray_platform_bridge.dart';

mixin _PlatformBridgeShellDomain on _PlatformBridgeDeps {
  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() async =>
      _parseShellSnapshot(await _invokeMap('loadShellSnapshot'));

  @override
  Stream<OpenCrayShellSnapshot> watchShellSnapshot() => _shellSnapshotChannel
      .receiveBroadcastStream()
      .map(_requireMap)
      .map(_parseShellSnapshot);

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) => _methodChannel.invokeMethod<void>(
    'saveShellDestination',
    <String, Object?>{
      'selectedTab': selectedTab,
      'settingsSubpage': settingsSubpage,
    },
  );
}
