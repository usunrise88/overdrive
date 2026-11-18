plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.overdrive.ble"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()
    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            // Robolectric (the adapter-state receiver, the permission check) needs the merged manifest.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(libs.overdrive.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
