plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

android {
  namespace = "org.opencray.llm"
  compileSdk = 33
  defaultConfig {
    minSdk = 24
 // targetSdk removed (deprecated); property migrated to plugin defaults
  }
  buildTypes {
    getByName("debug") {
      isMinifyEnabled = false
    }
    getByName("release") {
      isMinifyEnabled = false
    }
  }
  testOptions {
    unitTests.all {
      it.testLogging.showStandardStreams = true
    }
  }
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(project(":core"))
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  testImplementation("junit:junit:4.13.2")
}
