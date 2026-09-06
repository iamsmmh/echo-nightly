plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)

    @Suppress("UnstableApiUsage")
    androidLibrary {
        namespace = "dev.brahmkshatriya.echo.shared"
        compileSdk = 36
        minSdk = 24
    }

    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":common"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.security.crypto)
        }
        jvmMain.dependencies {
            // JVM/desktop actuals use java.net + java.nio only - no extra deps.
        }
        iosMain.dependencies {
        }
    }
}
