import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val s8AgentRuntimeAssetDir = layout.buildDirectory.dir("generated/s8-agent-runtime/assets")
val copyS8AgentRuntimeAssets by tasks.registering(Copy::class) {
    from(
        rootProject.file("tools/slm/s8_agent_tools_v3.json"),
        rootProject.file("tools/slm/s8_agent_runtime_skills_v3.json"),
    )
    into(s8AgentRuntimeAssetDir)
}
val s813QualificationAssetDir = layout.buildDirectory.dir("generated/s813-qualification/assets")
val copyS813QualificationAssets by tasks.registering(Copy::class) {
    from(
        rootProject.file("tools/slm/s8_agent_fixture.json"),
        rootProject.file("tools/slm/s8_agent_runtime_fixture_v3.json"),
        rootProject.file("tools/slm/s8_agent_v3_qualification_fixture.json"),
        rootProject.file("tools/slm/s8_agent_v3_qualification_scoring.json"),
        rootProject.file("tools/slm/s8_agent_dev.json"),
        rootProject.file("tools/slm/s8_agent_heldout.json"),
        rootProject.file("tools/slm/s8_agent_tools.json"),
        rootProject.file("tools/slm/s8_agent_tools_v3.json"),
        rootProject.file("tools/slm/s8_agent_skills.json"),
        rootProject.file("tools/slm/s8_agent_runtime_skills_v3.json"),
        rootProject.file("tools/slm/s8_agent_scoring.json"),
    )
    into(s813QualificationAssetDir)
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

    buildTypes {
        debug {
            val targetPackage = project.findProperty("S812_ANDROID_TARGET_PACKAGE")?.toString()
                ?: project.findProperty("S813_ANDROID_TARGET_PACKAGE")?.toString()
            if (targetPackage == "io.github.triet4p.aegisf.s812evidence" ||
                targetPackage == "io.github.triet4p.aegisf.s813evidence"
            ) {
                applicationIdSuffix = if (targetPackage.endsWith(".s812evidence")) {
                    ".s812evidence"
                } else {
                    ".s813evidence"
                }
            }
        }
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

// Live-gate propagation for the provider smoke tests: the Gradle daemon does not
// reliably inherit ad-hoc shell exports on every host, so these flags reach the test JVM through
// an explicit project property or the daemon environment. S8.5's historical protocol gate stays
// environment-based because its test intentionally reads that variable. Gate flags, package IDs
// and attempt IDs only; never pass credentials here.
tasks.withType<Test> {
    listOf(
        "AEGIS_S811_LIVE_SMOKE",
        "S811_ATTEMPT",
    ).forEach { name ->
        val value = project.findProperty(name)?.toString() ?: System.getenv(name)
        if (!value.isNullOrBlank()) systemProperty(name, value)
    }
    project.findProperty("AEGIS_S85_LIVE_PROTOCOL_SMOKE")?.toString()
        ?.takeIf { it == "1" }
        ?.let { environment("AEGIS_S85_LIVE_PROTOCOL_SMOKE", it) }
}

android.sourceSets.getByName("main").assets.srcDir(s8AgentRuntimeAssetDir.get().asFile)
android.sourceSets.getByName("androidTest").assets.srcDir(s813QualificationAssetDir.get().asFile)
tasks.named("preBuild").configure {
    dependsOn(copyS8AgentRuntimeAssets)
    dependsOn(copyS813QualificationAssets)
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
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
