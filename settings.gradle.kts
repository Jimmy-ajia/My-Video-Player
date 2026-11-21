pluginManagement {
    repositories {
        // Chinese mirror — replace with your own stable mirror if needed
        maven { url = uri("https://mirrors.doujo.com/maven") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Chinese mirror — replace with your own stable mirror if needed
        maven { url = uri("https://mirrors.doujo.com/maven") }
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "Player"
include(":app")
