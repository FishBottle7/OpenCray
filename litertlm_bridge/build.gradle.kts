plugins {
  id("com.android.library")
}

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("module-build/litertlm_bridge"))

android {
  namespace = "org.opencray.litertlmbridge"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildTypes {
    getByName("debug") {
      isMinifyEnabled = false
    }
    getByName("release") {
      isMinifyEnabled = false
    }
  }
}

dependencies {
  compileOnly("com.google.ai.edge.litertlm:litertlm-android:0.10.0")
  runtimeOnly("com.google.ai.edge.litertlm:litertlm-android:0.10.0")
}
