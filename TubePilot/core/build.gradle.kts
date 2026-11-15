plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tubepilot.core"
}

dependencies {
    api(libs.coroutines.core)
    api(libs.coroutines.android)
    api(libs.serialization.json)
    api(libs.okhttp)
    api("javax.inject:javax.inject:1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
