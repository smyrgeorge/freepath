rootProject.name = "freepath"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include("examples:transport-lan")

include("freepath-app:composeApp")
include("freepath-app:androidApp")
include("freepath-contact")
include("freepath-crypto")
include("freepath-database")
include("freepath-transport")
include("freepath-transport-lan")
include("freepath-util")
