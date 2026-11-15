plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tubepilot.skills"
}

dependencies {
    api(project(":core"))
    api(project(":llm"))
    api(project(":youtube"))
    api(project(":media"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
