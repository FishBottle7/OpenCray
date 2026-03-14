import 'package:flutter/widgets.dart';

import 'app/opencray_app.dart';
import 'core/bridge/opencray_host_bridge_bootstrap.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final bridge = await OpenCrayHostBridgeBootstrap.bootstrap();
  runApp(OpenCrayApp(bridge: bridge));
}
