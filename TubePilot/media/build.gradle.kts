plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tubepilot.media"
}

dependencies {
    api(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
