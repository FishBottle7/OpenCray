enum OpenCrayTab {
  chat('chat', 'Chat'),
  skills('skills', 'Skills'),
  files('files', 'Files'),
  settings('settings', 'Settings');

  const OpenCrayTab(this.routeSegment, this.label);

  final String routeSegment;
  final String label;

  String get routeName => '/$routeSegment';

  static OpenCrayTab fromRouteName(String? routeName) {
    for (final tab in OpenCrayTab.values) {
      if (routeName == tab.routeName) {
        return tab;
      }
    }
    return OpenCrayTab.chat;
  }
}
