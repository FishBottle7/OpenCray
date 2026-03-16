import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

fun resolveFlutterSdkPath(): String? {
  val propertyFiles = listOf(
    rootProject.file("flutter_app/android/local.properties"),
    rootProject.file("flutter_app/.android/local.properties"),
    rootProject.file("local.properties"),
  )
  propertyFiles.forEach { propertiesFile ->
    if (!propertiesFile.exists()) {
      return@forEach
    }
    val properties = Properties()
    propertiesFile.inputStream().use(properties::load)
    properties.getProperty("flutter.sdk")
      ?.takeIf(String::isNotBlank)
      ?.let { return it }
  }
  return System.getenv("FLUTTER_ROOT")?.takeIf(String::isNotBlank)
}

fun resolveFlutterJar() =
  resolveFlutterSdkPath()
    ?.let(::file)
    ?.resolve("bin/cache/artifacts/engine/android-arm64/flutter.jar")

val hasFlutterModule = rootProject.file("flutter_app/.android/include_flutter.groovy").exists()
val flutterJar = resolveFlutterJar()

android {
  namespace = "org.opencray.app"
  compileSdk = 36

  defaultConfig {
    applicationId = "org.opencray.app"
    minSdk = 26
    targetSdk = 33
    versionCode = 1
    versionName = "1.0.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
  }

  buildTypes {
    getByName("debug") {
      isDebuggable = true
      isMinifyEnabled = false
    }
    getByName("release") {
      isMinifyEnabled = false
    }
  }
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation("androidx.core:core-ktx:1.10.1")
  if (hasFlutterModule) {
    implementation(project(":flutter"))
  } else {
    implementation(files(flutterJar ?: error("Flutter SDK jar was not found for host compilation.")))
  }
  // Baseline wiring to existing modules
  implementation(project(":core"))
  implementation(project(":llm"))
  implementation(project(":runtime"))
  implementation(project(":persistence"))
  implementation(project(":skills"))
  implementation(project(":mcp"))
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")
  androidTestImplementation("androidx.test.ext:junit:1.1.5")
  androidTestImplementation("androidx.test:runner:1.5.2")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
