rootProject.name = "OpenCray"
include(":app", ":core", ":runtime", ":skills", ":mcp", ":llm", ":persistence", ":policy", ":filesystem")

val flutterModuleScript = file("flutter_app/.android/include_flutter.groovy")
if (flutterModuleScript.exists()) {
  apply(from = flutterModuleScript)
}
