plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "org.opencray.app"
  compileSdk = 33

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
  // Baseline wiring to existing modules
  implementation(project(":core"))
  implementation(project(":llm"))
  implementation(project(":runtime"))
  implementation(project(":ui"))
  implementation(project(":persistence"))
  implementation(project(":skills"))
  implementation(project(":mcp"))
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test.ext:junit:1.1.5")
  androidTestImplementation("androidx.test:runner:1.5.2")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
