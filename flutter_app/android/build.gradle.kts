allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val androidJvmVersion = JavaVersion.VERSION_11
val localAndroidProjects =
    setOf(
        ":app",
        ":core",
        ":filesystem",
        ":litertlm_bridge",
        ":llm",
        ":mcp",
        ":persistence",
        ":policy",
        ":runtime",
        ":skills",
    )

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

subprojects {
    if (project.path in localAndroidProjects) {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
