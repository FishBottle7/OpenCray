import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

fun findCodexWorktreeParentRepoDir(start: File): File? {
    var cursor: File? = start.canonicalFile
    while (cursor != null) {
        if (cursor.name == ".codex-worktrees") {
            return cursor.parentFile
        }
        cursor = cursor.parentFile
    }
    return null
}

fun resolveEmbeddedPythonRuntimeDistDir(projectRoot: File): File {
    val localDir = projectRoot.resolve("../../tools/android_python_runtime_p4a/dist")
    if (localDir.exists()) {
        return localDir
    }
    return findCodexWorktreeParentRepoDir(projectRoot)
        ?.resolve("tools/android_python_runtime_p4a/dist")
        ?.takeIf(File::exists)
        ?: localDir
}

val embeddedPythonRuntimeDistDir = resolveEmbeddedPythonRuntimeDistDir(rootProject.projectDir)

val runtimeIsolationAndroidTestOnly = providers
    .gradleProperty("runtimeIsolationAndroidTestOnly")
    .map(String::toBoolean)
    .getOrElse(false)

android {
    namespace = "org.opencray.app"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    buildFeatures {
        aidl = true
    }

    packaging {
        jniLibs {
            // p4a unpacks libpybundle.so via nativeLibraryDir, so the runtime
            // payload must be extracted onto disk instead of loaded only from the APK.
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libpybundle.so")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "org.opencray.app"
        minSdk = 26
        targetSdk = 33
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../../../app/src/main/AndroidManifest.xml")
            java.srcDirs("../../../app/src/main/kotlin")
            aidl.srcDirs("../../../app/src/main/aidl")
            res.setSrcDirs(listOf("../../../app/src/main/res"))
            assets.srcDirs("../../../app/src/main/assets")
        }
        getByName("test") {
            java.srcDirs("../../../app/src/test/kotlin")
        }
        getByName("androidTest") {
            if (runtimeIsolationAndroidTestOnly) {
                java.setSrcDirs(listOf("../../../app/src/runtimeIsolationAndroidTest/kotlin"))
            } else {
                java.srcDirs(
                    "../../../app/src/androidTest/kotlin",
                    "../../../app/src/runtimeIsolationAndroidTest/kotlin",
                )
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    sourceSets.getByName("androidTest").kotlin.apply {
        if (runtimeIsolationAndroidTestOnly) {
            setSrcDirs(emptyList<String>())
        }
    }
}

val verifyReleaseManifestSecurity = tasks.register("verifyReleaseManifestSecurity") {
    val mergedManifest = layout.buildDirectory.file(
        "intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
    )
    dependsOn("processReleaseMainManifest")
    inputs.file(mergedManifest)
    doLast {
        val manifestFile = mergedManifest.get().asFile
        check(manifestFile.isFile) {
            "Release merged manifest was not generated: ${manifestFile.absolutePath}"
        }
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestFile)
        val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        check(application.getAttributeNS(androidNamespace, "debuggable") != "true") {
            "Release manifest must not be debuggable."
        }
        check(application.getAttributeNS(androidNamespace, "allowBackup") == "false") {
            "Release manifest must disable Android Auto Backup."
        }
        check(application.getAttributeNS(androidNamespace, "fullBackupContent").isNotBlank()) {
            "Release manifest must declare pre-Android 12 backup exclusions."
        }
        check(application.getAttributeNS(androidNamespace, "dataExtractionRules").isNotBlank()) {
            "Release manifest must declare Android 12+ data extraction exclusions."
        }
        check(application.getAttributeNS(androidNamespace, "networkSecurityConfig").isNotBlank()) {
            "Release manifest must keep the scoped loopback network security policy."
        }

        fun requirePrivateComponent(tagName: String, className: String) {
            val nodes = document.getElementsByTagName(tagName)
            val component = (0 until nodes.length)
                .asSequence()
                .map { index -> nodes.item(index) as org.w3c.dom.Element }
                .firstOrNull { element ->
                    element.getAttributeNS(androidNamespace, "name") == className
                }
                ?: error("Release manifest is missing $className.")
            check(component.getAttributeNS(androidNamespace, "exported") == "false") {
                "$className must not be exported in release builds."
            }
        }

        requirePrivateComponent("service", "org.opencray.app.ServiceOpencraypython")
        requirePrivateComponent("receiver", "com.opencray.app.ScheduledTaskRepairReceiver")
    }
}

tasks.matching { task ->
    task.name == "assembleRelease" || task.name == "bundleRelease"
}.configureEach {
    dependsOn(verifyReleaseManifestSecurity)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation(
        fileTree(
            mapOf(
                "dir" to embeddedPythonRuntimeDistDir,
                "include" to listOf("*.aar"),
            ),
        ),
    )
    implementation(project(":core"))
    implementation(project(":litertlm_bridge"))
    implementation(project(":llm"))
    implementation(project(":runtime"))
    implementation(project(":persistence"))
    implementation(project(":policy"))
    implementation(project(":skills"))
    implementation(project(":mcp"))
    implementation("com.getkeepsafe.relinker:relinker:1.4.5")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

flutter {
    source = "../.."
}
