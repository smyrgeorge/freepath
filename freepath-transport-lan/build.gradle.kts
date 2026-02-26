plugins {
    id("io.github.smyrgeorge.freepath.multiplatform")
}

// Each test class runs in its own JVM so that JmDNS multicast-socket state from one
// class (e.g. LanLinkAdapterMdnsTest) does not pollute subsequent classes.
tasks.withType<Test> {
    forkEvery = 1
}

kotlin {
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        commonMain {
            dependencies {
                api(project(":freepath-transport"))
                implementation(libs.log4k)
                implementation(libs.ktor.network)
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
                implementation("org.jmdns:jmdns:3.6.3")
            }
        }
    }
}

// Fat JAR for Docker: bundles DemoApp and all runtime dependencies into a single executable JAR.
tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Assembles a self-contained JAR for Docker deployment (entry point: DemoApp)"
    archiveClassifier.set("fat")
    manifest {
        attributes["Main-Class"] = "io.github.smyrgeorge.freepath.transport.lan.DemoApp"
    }
    // Unpack the module JAR (compiled classes + resources) first, then overlay all runtime deps.
    val jvmJarTask = tasks.named<Jar>("jvmJar")
    dependsOn(jvmJarTask)
    from(jvmJarTask.map { zipTree(it.archiveFile) })
    from(configurations.getByName("jvmRuntimeClasspath").map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Strip code-signing metadata from merged signed JARs (e.g. BouncyCastle).
    // Keeping .SF/.RSA/.DSA/.EC files causes a SecurityException at startup because
    // the signatures no longer match the merged JAR contents.
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
}
