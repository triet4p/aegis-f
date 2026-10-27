import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val s8AgentRuntimeAssetDir = layout.buildDirectory.dir("generated/s8-agent-runtime/assets")
val copyS8AgentRuntimeAssets by tasks.registering(Copy::class) {
    from(
        rootProject.file("tools/slm/s8_agent_tools_v2.json"),
        rootProject.file("tools/slm/s8_agent_runtime_skills.json"),
    )
    into(s8AgentRuntimeAssetDir)
}


android {
    namespace = "io.github.triet4p.aegisf"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.triet4p.aegisf"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Live-gate propagation for the S8.11 one-shot smoke: the Gradle daemon does not reliably
// inherit ad-hoc shell exports on every host, so the gate reaches the test JVM through an
// explicit project property (command line `-P`) or the daemon environment, whichever is
// present. Env remains the documented primary gate; `-P` is the fallback launcher on hosts
// where daemon env inheritance is blocked. Values are gate flags and attempt ids only —
// never credentials.
// AFTER S8.11, Main may extend the list to other live gates or remove this block.
tasks.withType<Test> {
    listOf("AEGIS_S811_LIVE_SMOKE", "S811_ATTEMPT").forEach { name ->
        val value = project.findProperty(name)?.toString() ?: System.getenv(name)
        if (!value.isNullOrBlank()) systemProperty(name, value)
    }
}

android.sourceSets.getByName("main").assets.srcDir(s8AgentRuntimeAssetDir.get().asFile)
tasks.named("preBuild").configure {
    dependsOn(copyS8AgentRuntimeAssets)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Room writes one exported schema per database version; those files are the
// reference for every future migration.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Ledger persistence: Room runtime is the single source of stored records.
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // UI coroutines (screen state, Room Flow collection, suspend writes from dialogs).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // On-device English category decisions: ONNX Runtime Android session (CPU) plus the
    // app's own exact tokenizer/sequence/calibration port (S4.2). The AAR's arm64 JNI
    // libraries are 16 KB-aligned from 1.22.0, covering both the phone's 4096-byte pages
    // and newer 16 KB devices without a separate ABI build.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // Host-side (Robolectric) tests run against the real Room/SQLite database.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
