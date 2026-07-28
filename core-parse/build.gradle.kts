// Captured text/extras -> Offer. Kept as an Android library because parsing
// realistically touches android.app.Notification / RemoteViews at its boundary;
// everything it produces is a plain Offer with no Android types leaking out.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ideliver.parse"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-model"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
