import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            freeCompilerArgs.set(listOf("-Xjsr305=strict"))
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        jvmMain {
            dependencies {
                implementation(project(":freepath-transport-lan"))
                implementation(project(":freepath-util"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.log4k.slf4j)
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
