// Cable Club for Android. The app is built from the same sources as the PC version (../src, ../web): see
// app/build.gradle.kts and ../build-android.ps1.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CableClub"
include(":app")
