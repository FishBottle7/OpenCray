// Top-level Gradle build file. Configures repositories and classpaths for AGP and Kotlin.
buildscript {
  repositories {
    google()
    mavenCentral()
  }
  dependencies {
    // Android Gradle Plugin for library/app modules
    classpath("com.android.tools.build:gradle:8.12.0")
    // Kotlin plugin for Android
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
  }
}

allprojects {
  repositories {
    google()
    mavenCentral()
  }
}

tasks.register("clean", Delete::class) {
  delete(layout.buildDirectory)
}


subprojects {
  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
  }
}
