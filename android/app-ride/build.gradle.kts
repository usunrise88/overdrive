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
    namespace = "app.overdrive.ride"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()
    defaultConfig {
        applicationId = "app.overdrive.ride"
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
    implementation(project(":ble"))
    implementation(project(":data"))
    implementation(libs.overdrive.core)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
}
