plugins {
    id("io.github.smyrgeorge.freepath.multiplatform")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android)
}

kotlin {
    android {
        namespace = "io.github.smyrgeorge.freepath.transport"
        compileSdk = 36
        minSdk = 26
    }

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
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.bouncycastle)
            }
        }
        jvmMain {
            dependsOn(jvmAndroidMain)
            dependencies {
                implementation(libs.log4k.slf4j)
            }
        }
        androidMain {
            dependsOn(jvmAndroidMain)
        }
    }
}
