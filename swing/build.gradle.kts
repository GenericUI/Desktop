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

    sourceSets {
        commonMain.dependencies {
            api(project(":desktop:common"))
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.sqlite.jdbc)

            // Optional LWJGL OpenGL ES / OpenGL dependencies for Canvas3D (GLContext) on Java/Swing
            // Downstream projects opt in via the GenericUI Gradle Plugin (`useLwjglGL = true`) or explicit runtime dependencies
            compileOnly(libs.lwjgl.core)
            compileOnly(libs.lwjgl.jawt)
            compileOnly(libs.lwjgl.egl)
            compileOnly(libs.lwjgl.opengles)
            compileOnly(libs.lwjgl.opengl)

            // Optional Vulkan library dependencies for Canvas3D (VKContext) on Java/Swing
            // Downstream projects opt in via the GenericUI Gradle Plugin (`useVulkan = true`) or explicit runtime dependencies
            compileOnly(libs.lwjgl.vulkan)
            compileOnly(libs.lwjgl.shaderc)
            compileOnly(libs.lwjgl.vma)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
