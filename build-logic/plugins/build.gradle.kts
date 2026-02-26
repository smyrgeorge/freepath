plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("multiplatform") {
            id = "io.github.smyrgeorge.freepath.multiplatform"
            implementationClass = "io.github.smyrgeorge.freepath.multiplatform.MultiplatformConventions"
        }
        create("multiplatform.binaries") {
            id = "io.github.smyrgeorge.freepath.multiplatform.binaries"
            implementationClass = "io.github.smyrgeorge.freepath.multiplatform.MultiplatformBinariesConventions"
        }
        create("multiplatform.jvm") {
            id = "io.github.smyrgeorge.freepath.multiplatform.jvm"
            implementationClass = "io.github.smyrgeorge.freepath.multiplatform.MultiplatformJvmConventions"
        }
        create("dokka") {
            id = "io.github.smyrgeorge.freepath.dokka"
            implementationClass = "io.github.smyrgeorge.freepath.dokka.DokkaConventions"
        }
    }
}

dependencies {
    compileOnly(libs.gradle.kotlin.plugin)
    compileOnly(libs.gradle.dokka.plugin)
}