pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central first: avoids Maven Central rate limits (HTTP 429) on CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google-hosted mirror of Maven Central first: avoids Maven Central rate limits (HTTP 429) on CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "TubePilot"

include(
    ":app",
    ":core",
    ":data",
    ":llm",
    ":youtube",
    ":media",
    ":skills",
    ":agents",
    ":pipeline",
    ":testing",
)
