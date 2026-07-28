// Pure Kotlin/JVM. No Android dependencies — this is the shared seam every
// other module hangs off, so it must stay free of platform types. Nothing in
// here should ever know how an Offer was captured.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}
