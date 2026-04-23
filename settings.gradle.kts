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

include("freepath-app:composeApp")
include("freepath-app:androidApp")
include("freepath-contact")
include("freepath-content")
include("freepath-core")
include("freepath-database")
include("freepath-libble")
include("freepath-libnet")
include("freepath-libp2p")
include("freepath-util")
