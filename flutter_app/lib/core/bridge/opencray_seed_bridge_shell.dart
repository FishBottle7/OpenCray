part of 'opencray_seed_bridge.dart';

mixin _SeedBridgeShellDomain on _SeedBridgeDeps {
  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() async => _snapshot;

  @override
  Stream<OpenCrayShellSnapshot> watchShellSnapshot() async* {
    yield _snapshot;
    yield* _controller.stream;
  }

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) async {
    final tab = _parseTab(selectedTab);
    final settingsPage = tab == OpenCrayTab.settings
        ? settingsPageFromRouteId(settingsSubpage ?? 'home')
        : SettingsPage.home;
    _snapshot = _snapshot.copyWith(
      initialTab: tab,
      initialSettingsPage: settingsPage,
    );
    _controller.add(_snapshot);
  }
}
