plugins {
    id("io.github.smyrgeorge.freepath.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        commonMain {
            dependencies {
                implementation(libs.log4k)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.bignum)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.log4k.slf4j)
                implementation(libs.bouncycastle)
            }
        }
    }
}
