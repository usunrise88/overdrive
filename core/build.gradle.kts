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
            // StateFlow is part of the public ride API, so consumers need coroutines on their classpath.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Cross-language contract tests read the shared fixtures from the monorepo's contracts/ directory.
val contractsDir = layout.projectDirectory.dir("../contracts")

// Opt-in oracle for the FIT writer: with `-PfitOracleJar=/path/to/fit.jar`, GarminFitOracleTest decodes our files with
// Garmin's decoder. The jar is not a dependency of this build and is never committed or fetched by it (docs/protocols/
// fit.md section 7); the test loads it from this path at run time. Without the property that test is skipped, as in CI.
val fitOracleJar = providers.gradleProperty("fitOracleJar")

tasks.named<Test>("jvmTest") {
    systemProperty("overdrive.contracts.dir", contractsDir.asFile.absolutePath)
    inputs.dir(contractsDir.dir("fixtures")).withPropertyName("contractFixtures")
    fitOracleJar.orNull?.let { jar ->
        systemProperty("overdrive.fit.oracle.jar", jar)
        inputs.file(jar).withPropertyName("fitOracleJar")
    }
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
