import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    mingwX64 {
        binaries.all {
            linkerOpts(
                "-lole32",
                "-lcomdlg32",
                "-lgdi32",
                "-luser32",
                "-lws2_32",
                "-lsecur32",
                "-lcrypt32",
                "-lwinhttp",
                "-lshell32",
                "-luuid",
                "-lwindowscodecs",
                "-liphlpapi",
                "-lcomctl32",
                "-luxtheme",
                "-ldwmapi",
                "-lmsimg32"
            )
        }
    }

    macosArm64()

    listOf(linuxX64(), linuxArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("kdeAbi") {
            defFile(project.file("src/nativeInterop/cinterop/kde_abi.def"))
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":system"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val jvmMain by getting {
            dependsOn(commonMain)
            dependencies {
                api(project(":jvm"))
                implementation(libs.kotlinx.coroutinesSwing)
                implementation(libs.sqlite.jdbc)
                compileOnly(libs.lwjgl.core)
                compileOnly(libs.lwjgl.jawt)
                compileOnly(libs.lwjgl.egl)
                compileOnly(libs.lwjgl.opengles)
                compileOnly(libs.lwjgl.opengl)
                compileOnly(libs.lwjgl.vulkan)
                compileOnly(libs.lwjgl.shaderc)
                compileOnly(libs.lwjgl.vma)
            }
        }

        val nativeDesktopMain by creating {
            dependsOn(commonMain)
        }

        val mingwMain by creating {
            dependsOn(nativeDesktopMain)
        }

        val mingwX64Main by getting {
            dependsOn(mingwMain)
        }

        val macosMain by creating {
            dependsOn(nativeDesktopMain)
            dependencies {
                api(project(":apple"))
            }
        }

        val macosArm64Main by getting {
            dependsOn(macosMain)
        }

        val linuxMain by creating {
            dependsOn(nativeDesktopMain)
        }

        val linuxX64Main by getting {
            dependsOn(linuxMain)
        }

        val linuxArm64Main by getting {
            dependsOn(linuxMain)
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        val jvmTest by getting {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.kotlin.testJunit)
            }
        }

        val nativeDesktopTest by creating {
            dependsOn(commonTest)
        }

        val mingwTest by creating {
            dependsOn(nativeDesktopTest)
        }

        val mingwX64Test by getting {
            dependsOn(mingwTest)
        }

        val macosTest by creating {
            dependsOn(nativeDesktopTest)
        }

        val macosArm64Test by getting {
            dependsOn(macosTest)
        }

        val linuxTest by creating {
            dependsOn(nativeDesktopTest)
        }

        val linuxX64Test by getting {
            dependsOn(linuxTest)
        }

        val linuxArm64Test by getting {
            dependsOn(linuxTest)
        }
    }
}
