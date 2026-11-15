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
rootProject.name = "kanade-ime"
include(":core", ":zenz", ":whisper", ":native-whisper", ":native-zenz-v80", ":native-whisper-v80", ":dictool", ":desktop", ":app")
