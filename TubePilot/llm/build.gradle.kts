plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tubepilot.llm"
}

dependencies {
    api(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.anthropic.java)
    implementation(libs.mediapipe.genai)
    testImplementation(libs.okhttp.mockwebserver)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
