// Pure Kotlin/JVM. No Android dependencies — this is the only module where a
// silent bug costs the user money directly, so it stays testable in plain JUnit
// against synthetic offers.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core-model"))

    testImplementation(libs.kotlin.test.junit)
}
