rootProject.name = "OpenCray"
include(":app", ":core", ":runtime", ":skills", ":mcp", ":llm", ":persistence", ":policy", ":filesystem")
apply(from = file("flutter_app/.android/include_flutter.groovy"))
