import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spotless)
}

group = "app.overdrive"
version = "0.1.0-SNAPSHOT"

kotlin {
    explicitApi()

    // JVM only for now: the Android modules consume this as a plain JVM library. Nothing here may
    // import Android or any other platform API (spec §7.1), which keeps an iOS port a matter of
    // adding targets later. No iOS target is declared because iOS is a non-goal (spec §1).
    jvm {
        compilerOptions {
            // Android modules compile for Java 17; stay compatible with them whatever JDK runs the build.
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Cross-language contract tests read the shared fixtures from the monorepo's contracts/ directory.
val contractsDir = layout.projectDirectory.dir("../contracts")

tasks.named<Test>("jvmTest") {
    systemProperty("overdrive.contracts.dir", contractsDir.asFile.absolutePath)
    inputs.dir(contractsDir.dir("fixtures")).withPropertyName("contractFixtures")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
