pluginManagement {
    repositories {
        google()
        maven {
            url = uri("https://maven.aliyun.com/repository/central")
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Prefer a Central mirror for Kotlin compiler artifacts. Maven Central's
        // redirect for kotlin-compiler-embeddable intermittently returns HTTP 504
        // in GitHub Actions, preventing tests and emulator jobs from starting.
        maven {
            url = uri("https://maven.aliyun.com/repository/central")
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "KivoraMusicPlayer"
include(":app")
