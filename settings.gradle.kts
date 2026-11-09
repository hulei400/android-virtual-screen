pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Xposed API (compileOnly; the framework provides it at runtime).
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "android-virtual-screen"
include(":app")
include(":device-fixture")
