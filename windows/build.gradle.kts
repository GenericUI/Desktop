plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    mingwX64 {
        binaries.all {
            linkerOpts("-lole32", "-lcomdlg32", "-lgdi32", "-luser32")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":common"))
            api(project(":desktop:nativeCommon"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
