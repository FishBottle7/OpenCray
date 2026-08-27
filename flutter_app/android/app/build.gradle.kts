import java.io.File
import java.util.Properties

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

val runtimeIsolationAndroidTestOnly = providers
    .gradleProperty("runtimeIsolationAndroidTestOnly")
    .map(String::toBoolean)
    .getOrElse(false)

data class OpencrayReleaseSigningCredentials(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun loadOpencrayPropertiesFile(file: File): Properties =
    Properties().apply { file.inputStream().use(::load) }

fun resolveOpencrayReleaseSigningCredentials(
    androidProjectDir: File,
): Pair<OpencrayReleaseSigningCredentials, String>? {
    val envNames = listOf(
        "OPENCRAY_SIGN_STORE_FILE",
        "OPENCRAY_SIGN_STORE_PASSWORD",
        "OPENCRAY_SIGN_KEY_ALIAS",
        "OPENCRAY_SIGN_KEY_PASSWORD",
    )
    val envValues = envNames.map { name -> System.getenv(name)?.takeIf { it.isNotBlank() } }
    if (envValues.all { it != null }) {
        return OpencrayReleaseSigningCredentials(
            envValues[0]!!,
            envValues[1]!!,
            envValues[2]!!,
            envValues[3]!!,
        ) to "environment variables"
    }
    val propertiesFile = androidProjectDir.resolve("keystore.properties")
    if (propertiesFile.isFile) {
        val properties = loadOpencrayPropertiesFile(propertiesFile)
        val values = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .map { key -> properties.getProperty(key)?.takeIf { it.isNotBlank() } }
        if (values.all { it != null }) {
            return OpencrayReleaseSigningCredentials(
                values[0]!!,
                values[1]!!,
                values[2]!!,
                values[3]!!,
            ) to propertiesFile.absolutePath
        }
    }
    return null
}

val opencrayAllowDebugSignedRelease = providers
    .gradleProperty("opencrayAllowDebugSignedRelease")
    .map(String::toBoolean)
    .getOrElse(false)

val opencrayReleaseSigning = resolveOpencrayReleaseSigningCredentials(rootProject.projectDir)

val opencrayRequestedReleaseBuild = gradle.startParameter.taskNames.any { taskName ->
    val lowered = taskName.lowercase()
    lowered.contains("release") && !lowered.contains("verifyreleasesigning")
}

if (opencrayReleaseSigning == null && opencrayRequestedReleaseBuild && !opencrayAllowDebugSignedRelease) {
    throw GradleException(
        """
        Release signing credentials are missing; refusing to produce a debug-signed release APK.

        Provide credentials either via environment variables:
          OPENCRAY_SIGN_STORE_FILE, OPENCRAY_SIGN_STORE_PASSWORD, OPENCRAY_SIGN_KEY_ALIAS, OPENCRAY_SIGN_KEY_PASSWORD
        or via flutter_app/android/keystore.properties:
          storeFile=..., storePassword=..., keyAlias=..., keyPassword=

        Local development escape hatch: -PopencrayAllowDebugSignedRelease=true (or build-apk.ps1 -AllowDebugSigned).
        """.trimIndent(),
    )
}

android {
    namespace = "org.opencray.app"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    buildFeatures {
        aidl = true
    }

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

    signingConfigs {
        if (opencrayReleaseSigning != null) {
            create("opencrayRelease") {
                val credentials = opencrayReleaseSigning.first
                val declaredStoreFile = File(credentials.storeFile)
                storeFile = if (declaredStoreFile.isAbsolute) {
                    declaredStoreFile
                } else {
                    rootProject.projectDir.resolve(credentials.storeFile)
                }
                storePassword = credentials.storePassword
                keyAlias = credentials.keyAlias
                keyPassword = credentials.keyPassword
            }
        }
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
            when {
                opencrayReleaseSigning != null -> {
                    signingConfig = signingConfigs.getByName("opencrayRelease")
                }
                opencrayAllowDebugSignedRelease -> {
                    logger.warn(
                        "WARNING: falling back to the Android debug certificate for the release build " +
                            "(-PopencrayAllowDebugSignedRelease=true); do not distribute this artifact.",
                    )
                    signingConfig = signingConfigs.getByName("debug")
                }
                else -> {
                    logger.warn(
                        "WARNING: release signing credentials are missing; release outputs stay unsigned " +
                            "and assembleRelease will be blocked by the credential gate.",
                    )
                }
            }
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../../../app/src/main/AndroidManifest.xml")
            java.srcDirs("../../../app/src/main/kotlin")
            aidl.srcDirs("../../../app/src/main/aidl")
            res.setSrcDirs(listOf("../../../app/src/main/res"))
            assets.srcDirs("../../../app/src/main/assets")
        }
        getByName("test") {
            java.srcDirs("../../../app/src/test/kotlin")
        }
        getByName("androidTest") {
            if (runtimeIsolationAndroidTestOnly) {
                java.setSrcDirs(listOf("../../../app/src/runtimeIsolationAndroidTest/kotlin"))
            } else {
                java.srcDirs(
                    "../../../app/src/androidTest/kotlin",
                    "../../../app/src/runtimeIsolationAndroidTest/kotlin",
                )
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    sourceSets.getByName("androidTest").kotlin.apply {
        if (runtimeIsolationAndroidTestOnly) {
            setSrcDirs(emptyList<String>())
        }
    }
}

val verifyReleaseManifestSecurity = tasks.register("verifyReleaseManifestSecurity") {
    val mergedManifest = layout.buildDirectory.file(
        "intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
    )
    dependsOn("processReleaseMainManifest")
    inputs.file(mergedManifest)
    doLast {
        val manifestFile = mergedManifest.get().asFile
        check(manifestFile.isFile) {
            "Release merged manifest was not generated: ${manifestFile.absolutePath}"
        }
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestFile)
        val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        check(application.getAttributeNS(androidNamespace, "debuggable") != "true") {
            "Release manifest must not be debuggable."
        }
        check(application.getAttributeNS(androidNamespace, "allowBackup") == "false") {
            "Release manifest must disable Android Auto Backup."
        }
        check(application.getAttributeNS(androidNamespace, "fullBackupContent").isNotBlank()) {
            "Release manifest must declare pre-Android 12 backup exclusions."
        }
        check(application.getAttributeNS(androidNamespace, "dataExtractionRules").isNotBlank()) {
            "Release manifest must declare Android 12+ data extraction exclusions."
        }
        check(application.getAttributeNS(androidNamespace, "networkSecurityConfig").isNotBlank()) {
            "Release manifest must keep the scoped loopback network security policy."
        }

        fun requirePrivateComponent(tagName: String, className: String) {
            val nodes = document.getElementsByTagName(tagName)
            val component = (0 until nodes.length)
                .asSequence()
                .map { index -> nodes.item(index) as org.w3c.dom.Element }
                .firstOrNull { element ->
                    element.getAttributeNS(androidNamespace, "name") == className
                }
                ?: error("Release manifest is missing $className.")
            check(component.getAttributeNS(androidNamespace, "exported") == "false") {
                "$className must not be exported in release builds."
            }
        }

        requirePrivateComponent("service", "org.opencray.app.ServiceOpencraypython")
        requirePrivateComponent("receiver", "com.opencray.app.ScheduledTaskRepairReceiver")
    }
}

tasks.matching { task ->
    task.name == "assembleRelease" || task.name == "bundleRelease"
}.configureEach {
    dependsOn(verifyReleaseManifestSecurity)
}

fun resolveOpencrayAndroidSdkDir(androidProjectDir: File): File? {
    System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    val localPropertiesFile = androidProjectDir.resolve("local.properties")
    if (localPropertiesFile.isFile) {
        val properties = loadOpencrayPropertiesFile(localPropertiesFile)
        properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    }
    System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    return null
}

fun compareOpencrayVersionedDirectoryNames(left: String, right: String): Int {
    fun segments(value: String): List<Int> = value.split('.').mapNotNull { it.toIntOrNull() }
    val leftSegments = segments(left)
    val rightSegments = segments(right)
    for (index in 0 until maxOf(leftSegments.size, rightSegments.size)) {
        val leftValue = leftSegments.getOrElse(index) { 0 }
        val rightValue = rightSegments.getOrElse(index) { 0 }
        if (leftValue != rightValue) {
            return leftValue.compareTo(rightValue)
        }
    }
    return left.compareTo(right)
}

fun resolveOpencrayApksignerExecutable(sdkDir: File): File? {
    val buildToolsDir = sdkDir.resolve("build-tools")
    if (!buildToolsDir.isDirectory) {
        return null
    }
    val versionPattern = Regex("\\d+(\\.\\d+)*")
    val candidateDir = buildToolsDir.listFiles { file -> file.isDirectory }.orEmpty()
        .filter { dir ->
            versionPattern.matches(dir.name) &&
                (dir.resolve("apksigner.bat").isFile || dir.resolve("apksigner").isFile)
        }
        .sortedWith { left, right -> compareOpencrayVersionedDirectoryNames(right.name, left.name) }
        .firstOrNull() ?: return null
    return candidateDir.resolve("apksigner.bat").takeIf(File::isFile)
        ?: candidateDir.resolve("apksigner")
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Rejects release APK artifacts signed with the Android debug certificate."
    val apkOutputDir = layout.buildDirectory.dir("outputs/apk/release")
    inputs.dir(apkOutputDir)
    doLast {
        if (opencrayAllowDebugSignedRelease) {
            logger.warn(
                "WARNING: skipping debug certificate verification because " +
                    "-PopencrayAllowDebugSignedRelease=true.",
            )
            return@doLast
        }

        fun normalizedSha256(value: String): String = value.replace(":", "").uppercase()

        fun runCommand(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "Command failed with exit code $exitCode: ${command.joinToString(" ")}\n$output"
            }
            return output
        }

        val apkDirectory = apkOutputDir.get().asFile
        val apkFile = apkDirectory.walkTopDown()
            .filter { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            .minByOrNull { it.name }
            ?: error("No release APK found under ${apkDirectory.absolutePath}.")

        val sdkDir = resolveOpencrayAndroidSdkDir(rootProject.projectDir)
            ?: error(
                "ANDROID_HOME or local.properties sdk.dir is required to locate apksigner for " +
                    "verifyReleaseSigning.",
            )
        val apksignerExecutable = resolveOpencrayApksignerExecutable(sdkDir)
            ?: error("apksigner was not found under ${sdkDir.resolve("build-tools").absolutePath}.")

        val usesBatchWrapper = apksignerExecutable.absolutePath.endsWith(".bat", ignoreCase = true)
        val apksignerOutput = if (usesBatchWrapper) {
            runCommand(
                "cmd.exe",
                "/c",
                apksignerExecutable.absolutePath,
                "verify",
                "--print-certs",
                apkFile.absolutePath,
            )
        } else {
            runCommand(
                apksignerExecutable.absolutePath,
                "verify",
                "--print-certs",
                apkFile.absolutePath,
            )
        }
        val apkDigest = Regex("certificate SHA-256 digest:\\s*([0-9A-Fa-f:]+)")
            .find(apksignerOutput)?.groupValues?.get(1)
            ?: error("Could not read the certificate digest from apksigner output:\n$apksignerOutput")

        val debugKeystoreFile = File(System.getProperty("user.home"))
            .resolve(".android").resolve("debug.keystore")
        check(debugKeystoreFile.isFile) {
            "Debug keystore was not found at ${debugKeystoreFile.absolutePath}."
        }
        val keytoolOutput = runCommand(
            "keytool",
            "-list",
            "-v",
            "-keystore",
            debugKeystoreFile.absolutePath,
            "-storepass",
            "android",
        )
        val debugDigest = Regex("^\\s*SHA256:\\s*([0-9A-Fa-f:]+)", RegexOption.MULTILINE)
            .find(keytoolOutput)?.groupValues?.get(1)
            ?: error("Could not read the debug certificate digest from keytool output.")

        check(normalizedSha256(apkDigest) != normalizedSha256(debugDigest)) {
            "The release APK is signed with the Android debug certificate: ${apkFile.absolutePath}"
        }
        logger.lifecycle(
            "verifyReleaseSigning: ${apkFile.name} is not signed with the Android debug certificate.",
        )
    }
}

tasks.matching { task -> task.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyReleaseSigning)
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
