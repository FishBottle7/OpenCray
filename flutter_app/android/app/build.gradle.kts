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

android {
    namespace = "org.opencray.app"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

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
            res.srcDirs("../../../app/src/main/res")
            assets.srcDirs("../../../app/src/main/assets")
        }
        getByName("test") {
            java.srcDirs("../../../app/src/test/kotlin")
        }
        getByName("androidTest") {
            java.srcDirs("../../../app/src/androidTest/kotlin")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
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
