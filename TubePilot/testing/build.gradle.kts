plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// In-memory fakes shared by the unit tests of every module (used as testImplementation only).
android {
    namespace = "com.tubepilot.testing"
}

dependencies {
    api(project(":core"))
    api(project(":llm"))
    api(libs.coroutines.test)
}
