plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "org.opencray.ui"
  compileSdk = 33
  defaultConfig {
    minSdk = 26
 // targetSdk removed (deprecated); property migrated to plugin defaults
  }
  buildTypes {
    getByName("debug") { isMinifyEnabled = false }
    getByName("release") { isMinifyEnabled = false }
  }
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation("androidx.core:core-ktx:1.10.1")
  implementation(project(":core"))
  implementation(project(":filesystem"))
  implementation(project(":skills"))
}
