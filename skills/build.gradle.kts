plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "org.opencray.skills"
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

  testImplementation("junit:junit:4.13.2")
}
