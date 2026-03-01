rootProject.name = "freepath"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    includeBuild("build-logic")
}

include("freepath-util")
include("freepath-crypto")
include("freepath-contact")
include("freepath-transport")
include("freepath-transport-lan")
include("examples:transport-lan")
