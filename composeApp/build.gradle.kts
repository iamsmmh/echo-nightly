import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)

    @Suppress("UnstableApiUsage")
    androidLibrary {
        namespace = "dev.brahmkshatriya.echo.player"
        compileSdk = 36
        minSdk = 24
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            // Host integrations/tests use the same platform storage implementation.
            export(project(":shared"))
            // Business logic lives in :shared/:core/:domain/:data/:extensions/:player
            // and is linked into this framework transitively.
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":common"))
            api(project(":shared"))
            api(project(":core"))
            api(project(":domain"))
            api(project(":data"))
            api(project(":extensions"))
            api(project(":player"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
