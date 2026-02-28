plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("dokka") {
            id = "io.github.smyrgeorge.freepath.dokka"
            implementationClass = "io.github.smyrgeorge.freepath.dokka.DokkaConventions"
        }
    }
}

dependencies {
    compileOnly(libs.gradle.dokka.plugin)
}
