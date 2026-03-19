plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "org.opencray.app"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
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

dependencies {
    implementation(kotlin("stdlib"))
    implementation("androidx.core:core-ktx:1.10.1")
    implementation(
        fileTree(
            mapOf(
                "dir" to rootProject.file("../../tools/android_python_runtime_p4a/dist"),
                "include" to listOf("*.aar"),
            ),
        ),
    )
    implementation(project(":core"))
    implementation(project(":llm"))
    implementation(project(":runtime"))
    implementation(project(":persistence"))
    implementation(project(":skills"))
    implementation(project(":mcp"))
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
