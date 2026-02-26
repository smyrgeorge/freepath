rootProject.name = "freepath"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    includeBuild("build-logic")
}

include(":freepath-transport")
include(":freepath-transport-lan")
