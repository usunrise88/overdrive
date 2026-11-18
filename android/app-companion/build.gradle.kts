plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release builds: version and signing come from the build environment, so no key material ever
// touches the repo. Without ANDROID_KEYSTORE_FILE the release APK is simply unsigned.
//   -PoverdriveVersionName=1.2.3 -PoverdriveVersionCode=42
//   ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
val keystoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE")

android {
    namespace = "app.overdrive.companion"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()
    defaultConfig {
        applicationId = "app.overdrive.companion"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = providers.gradleProperty("overdriveVersionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("overdriveVersionName").getOrElse("0.0.0-dev")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            // Robolectric (screenshot and container tests) needs the merged resources and manifest.
            isIncludeAndroidResources = true
            all {
                // Roborazzi writes the screenshots to build/shots (never committed); no golden comparison.
                it.systemProperty("roborazzi.test.record", "true")
                // The JDK's HttpURLConnection refuses the verb PATCH that Android's accepts: the container tests that send the
                // profile to a local service write it into the connection with reflection (JdkPatch), which needs this.
                it.jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
            }
        }
    }

    signingConfigs {
        if (keystoreFile.isPresent) {
            create("release") {
                storeFile = file(keystoreFile.get())
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystoreFile.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":ui-kit"))
    implementation(project(":data"))
    implementation(libs.overdrive.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
