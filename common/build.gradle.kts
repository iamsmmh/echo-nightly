import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("org.jetbrains.dokka") version "2.0.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)

    @Suppress("UnstableApiUsage")
    androidLibrary {
        namespace = "echo.common"
        compileSdk = 36
        minSdk = 24
    }
    jvm()

    // Apple targets so the extension API and models can be consumed by the
    // Compose Multiplatform iOS application.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.bundles.kotlinx)
                api(libs.kotlinx.datetime)
            }
        }

        // `jvmCommon` is a shared directory (not a hierarchical source set) that is
        // compiled into both the Android and JVM targets. It keeps the JVM-only
        // extension helpers (okhttp bridges, java.io.File based APIs) source
        // compatible for existing Echo extensions while staying out of iOS builds.
        val jvmCommonDir = "src/jvmCommon/kotlin"
        val androidMain by getting {
            kotlin.srcDir(jvmCommonDir)
            dependencies {
                api(libs.okhttp)
                api(libs.protobuf.java)
            }
        }
        val jvmMain by getting {
            kotlin.srcDir(jvmCommonDir)
            dependencies {
                api(libs.okhttp)
                api(libs.protobuf.java)
            }
        }
        // NOTE: src/iosMain sources are wired automatically by the default
        // hierarchy template (iosArm64/iosX64/iosSimulatorArm64); do not
        // reference the source set here — it does not exist yet at this
        // point of evaluation.
    }
}

// build.gradle.kts

mavenPublishing {
    publishToMavenCentral(true)
    signAllPublications()

    coordinates("dev.brahmkshatriya.echo", "common", "1.0.0")

    pom {
        name = "Echo common library"
        description = "A common library for echo extensions."
        inceptionYear = "2025"
        url = "https://github.com/brahmkshatriya/echo"
        licenses {
            license {
                name = "Unabandon Public License"
                url = "https://github.com/brahmkshatriya/echo/blob/main/LICENSE.md"
                distribution = "https://github.com/brahmkshatriya/echo/blob/main/LICENSE.md"
            }
        }
        developers {
            developer {
                id = "brahmkshatriya"
                name = "Shivam"
                url = "https://github.com/brahmkshatriya/"
            }
        }
        scm {
            url = "https://github.com/brahmkshatriya/echo/"
            connection = "scm:git:git://github.com/brahmkshatriya/echo.git"
            developerConnection = "scm:git:ssh://git@github.com/brahmkshatriya/echo.git"
        }
    }
}

dokka {
    moduleName.set("common")
    moduleVersion.set("1.0")
    dokkaSourceSets.commonMain {
        includes.from("README.md")
        sourceLink {
            localDirectory.set(file("src/main/java"))
            remoteUrl("https://github.com/brahmkshatriya/echo/tree/main/common/src/main/java")
            remoteLineSuffix.set("#L")
        }
    }
    pluginsConfiguration.html {
        customStyleSheets.from("styles.css")
        footerMessage.set("made by <a style=\"color: inherit; text-decoration: underline;\" href=\"https://github.com/brahmkshatriya\">@brahmkshatriya</a>")
    }
}
