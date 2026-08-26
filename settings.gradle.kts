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

include(":app")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:datastore")
include(":core:designsystem")
include(":core:player")
