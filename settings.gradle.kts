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

rootProject.name = "ClearTune"

include(
    ":app",
    ":core:model",
    ":core:contracts",
    ":core:designsystem",
    ":core:testing",
    ":core:database",
    ":core:network",
    ":data:local",
    ":data:webdav",
    ":data:download",
    ":playback",
    ":feature:library",
    ":feature:sources",
    ":feature:downloads",
    ":feature:player",
    ":feature:playlists",
    ":feature:settings",
)
