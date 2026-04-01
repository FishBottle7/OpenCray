import java.io.File
import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

fun parsePythonRequirementsLock(file: File): List<String> =
  if (!file.exists()) {
    emptyList()
  } else {
    file.readLines()
      .map { line -> line.substringBefore("#").trim() }
      .filter(String::isNotBlank)
      .distinct()
  }

fun jsonStringLiteral(value: String): String =
  "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"") + "\""

fun renderFallbackPythonRuntimeManifestJson(packages: List<String>): String {
  val manifestPackages = packages.filter { packageName -> packageName != "python3" }
  val packageEntries = manifestPackages.joinToString(separator = ",\n    ") { packageName ->
    jsonStringLiteral(packageName)
  }
  return """
    {
      "schemaVersion": 1,
      "runtimeBackend": "p4a",
      "packageInstallPolicy": "preinstalled_only",
      "supportsDynamicInstall": false,
      "interpreter": "python3",
      "packages": [
        $packageEntries
      ],
      "notes": [
        "Generated from tools/android_python_runtime_p4a/requirements.lock because no dist manifest was present during app build."
      ]
    }
  """.trimIndent() + "\n"
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
val generatedPythonRuntimeManifestAssetsDir = layout.buildDirectory.dir("generated/assets/pythonRuntimeManifest")
val distPythonRuntimeManifest = rootProject.file("tools/android_python_runtime_p4a/dist/python-runtime-manifest.json")
val requirementsLockFile = rootProject.file("tools/android_python_runtime_p4a/requirements.lock")
val generatePythonRuntimeManifestAsset = tasks.register("generatePythonRuntimeManifestAsset") {
  inputs.files(distPythonRuntimeManifest, requirementsLockFile)
  outputs.file(generatedPythonRuntimeManifestAssetsDir.map { directory ->
    directory.file("python-runtime/python-runtime-manifest.json")
  })
  doLast {
    val outputFile = generatedPythonRuntimeManifestAssetsDir.get()
      .file("python-runtime/python-runtime-manifest.json")
      .asFile
    outputFile.parentFile.mkdirs()
    if (distPythonRuntimeManifest.exists()) {
      outputFile.writeText(distPythonRuntimeManifest.readText())
    } else {
      outputFile.writeText(
        renderFallbackPythonRuntimeManifestJson(
          parsePythonRequirementsLock(requirementsLockFile),
        ),
      )
    }
  }
}

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
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_11.toString()
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

  sourceSets.getByName("main").assets.srcDir(generatedPythonRuntimeManifestAssetsDir)
}

tasks.named("preBuild").configure {
  dependsOn(generatePythonRuntimeManifestAsset)
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.core:core-ktx:1.10.1")
  implementation("androidx.work:work-runtime-ktx:2.9.1")
  implementation(
    fileTree(
      mapOf(
        "dir" to rootProject.file("tools/android_python_runtime_p4a/dist"),
        "include" to listOf("*.aar"),
      ),
    ),
  )
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
  implementation("com.caverock:androidsvg-aar:1.4")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")
  androidTestImplementation("androidx.test.ext:junit:1.1.5")
  androidTestImplementation("androidx.test:runner:1.5.2")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
