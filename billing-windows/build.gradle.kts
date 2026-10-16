/*
 * Copyright 2026 Appmattus Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id(libs.plugins.gradleMavenPublishPlugin.get().pluginId)
}

// The Windows Store bridge in native/, a C++/WinRT DLL built with CMake and the Visual C++ build tools for x64 and
// ARM64, then bundled in the jar for JNA to load. It can only be built on Windows; elsewhere the jar has no DLL and
// WindowsBillingClient reports the store as unavailable.
val isWindows = OperatingSystem.current().isWindows
val nativeSource = layout.projectDirectory.dir("native")
val jvmResources = layout.buildDirectory.dir("generated/windowsStore/jvmResources")

val windowsBridges = listOf("x64" to "win32-x86-64", "ARM64" to "win32-aarch64").map { (platform, resourceDir) ->
    val buildDir = layout.buildDirectory.dir("windowsStore/$platform")

    val configure = tasks.register<Exec>("configureWindowsStoreBridge$platform") {
        group = "build"
        description = "Configures the Windows Store bridge for $platform"
        onlyIf { isWindows }
        inputs.dir(nativeSource)
        outputs.dir(buildDir)
        commandLine(
            "cmake",
            "-S",
            nativeSource.asFile.absolutePath,
            "-B",
            buildDir.get().asFile.absolutePath,
            "-A",
            platform,
            "-DBRIDGE_HEADER_DIR=${project(":billing-native-bridge").file("native").absolutePath}",
        )
    }

    val compile = tasks.register<Exec>("compileWindowsStoreBridge$platform") {
        group = "build"
        description = "Compiles the Windows Store bridge for $platform"
        onlyIf { isWindows }
        dependsOn(configure)
        inputs.dir(nativeSource)
        inputs.file(project(":billing-native-bridge").file("native/billing_bridge.h"))
        outputs.file(buildDir.map { it.file("Release/WindowsStoreBridge.dll") })
        commandLine("cmake", "--build", buildDir.get().asFile.absolutePath, "--config", "Release")
    }

    tasks.register<Copy>("bundleWindowsStoreBridge$platform") {
        onlyIf { isWindows }
        dependsOn(compile)
        from(buildDir.map { it.file("Release/WindowsStoreBridge.dll") })
        into(jvmResources.map { it.dir("com/appmattus/billing/windows/$resourceDir") })
    }
}

kotlin {
    explicitApi()

    jvm()

    jvmToolchain(17)

    sourceSets {
        all {
            languageSettings.optIn("com.appmattus.billing.bridge.InternalBillingApi")
        }
        jvmMain {
            resources.srcDir(jvmResources)
            dependencies {
                api(project(":billing-core"))
                implementation(project(":billing-native-bridge"))
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.named("jvmProcessResources") { dependsOn(windowsBridges) }
