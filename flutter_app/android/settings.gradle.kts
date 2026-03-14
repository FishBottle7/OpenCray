pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.12.0" apply false
    id("com.android.library") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}

include(":app")
include(":core")
include(":filesystem")
include(":llm")
include(":mcp")
include(":persistence")
include(":policy")
include(":runtime")
include(":skills")

project(":core").projectDir = file("../../core")
project(":filesystem").projectDir = file("../../filesystem")
project(":llm").projectDir = file("../../llm")
project(":mcp").projectDir = file("../../mcp")
project(":persistence").projectDir = file("../../persistence")
project(":policy").projectDir = file("../../policy")
project(":runtime").projectDir = file("../../runtime")
project(":skills").projectDir = file("../../skills")
