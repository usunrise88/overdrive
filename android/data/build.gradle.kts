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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
