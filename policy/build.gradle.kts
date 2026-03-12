plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "org.opencray.policy"
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
  implementation(project(":core"))
  testImplementation("junit:junit:4.13.2")
}
