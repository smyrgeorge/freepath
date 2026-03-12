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
include("freepath-crypto")
include("freepath-database")
include("freepath-util")
include("freepath-wasm")
include("freepath-libp2p")
