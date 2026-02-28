rootProject.name = "freepath"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    includeBuild("build-logic")
}

include(":freepath-transport-crypto")
include(":freepath-transport")
include(":freepath-transport-lan")
include(":freepath-transport-lan-demo")
