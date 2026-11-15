plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    linuxX64()
    linuxArm64()
    mingwX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":desktop:nativeCommon"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
