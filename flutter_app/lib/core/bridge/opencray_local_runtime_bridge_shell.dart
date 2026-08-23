part of 'opencray_local_runtime_bridge.dart';

mixin _LocalRuntimeBridgeShellDomain on _LocalRuntimeBridgeDeps {
  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() async =>
      _parseShellSnapshot(await _getMap('v1/shell_snapshot'));

  @override
  Stream<OpenCrayShellSnapshot> watchShellSnapshot() =>
      _watchMap(() => _getMap('v1/shell_snapshot'), _parseShellSnapshot);

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) => _postVoid('v1/save_shell_destination', <String, Object?>{
    'selectedTab': selectedTab,
    'settingsSubpage': settingsSubpage,
  });
}
