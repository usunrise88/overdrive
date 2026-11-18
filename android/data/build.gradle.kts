plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.overdrive.data"
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
        unitTests.all {
            // The JDK's HttpURLConnection refuses the verb PATCH that Android's accepts: the transport's tests write it into the
            // connection with reflection (JdkPatch), which the JVM allows only with this.
            it.jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
        }
    }
}

dependencies {
    api(libs.overdrive.core)
    // Compiled against, not shipped by this module: the ride app uses `data` and has no use for Health Connect. The companion adds the
    // library to its own dependencies, and the adapter is only ever loaded there.
    compileOnly(libs.androidx.health.connect.client)

    testImplementation(libs.androidx.health.connect.client)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
