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

import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id(libs.plugins.gradleMavenPublishPlugin.get().pluginId)
}

// The StoreKit 2 bridge in src/swift, compiled with swiftc. iOS links it statically through cinterop; the JVM bundles
// a universal macOS dylib and loads it with JNA. Both need a Mac with Xcode to build.
val swiftSource = layout.projectDirectory.file("src/swift/StoreKitBridge.swift")
val swiftOutput = layout.buildDirectory.dir("storekit")

fun registerSwiftLibrary(name: String, sdk: String, target: String, isStatic: Boolean, output: Provider<RegularFile>) =
    tasks.register<Exec>(name) {
        group = "build"
        description = "Compiles the StoreKit bridge for $target"
        onlyIf { HostManager.hostIsMac }
        inputs.file(swiftSource)
        outputs.file(output)
        doFirst { output.get().asFile.parentFile.mkdirs() }
        commandLine(
            buildList {
                addAll(listOf("xcrun", "--sdk", sdk, "swiftc", "-swift-version", "5", "-parse-as-library"))
                add("-emit-library")
                if (isStatic) add("-static")
                addAll(listOf("-O", "-module-name", "StoreKitBridge", "-target", target))
                // The back-deployment libraries live in Xcode's toolchain rather than the SDK, so apps linking the
                // bridge wouldn't find them, and the bridge uses none of the features they add
                addAll(listOf("-runtime-compatibility-version", "none"))
                if (!isStatic) addAll(listOf("-Xlinker", "-install_name", "-Xlinker", "@rpath/libStoreKitBridge.dylib"))
                addAll(listOf("-o", output.get().asFile.absolutePath, swiftSource.asFile.absolutePath))
            }
        )
    }

val macosLibraries = listOf("arm64", "x86_64").map { arch ->
    registerSwiftLibrary(
        name = "compileStoreKitBridgeMacos${arch.replaceFirstChar(Char::uppercase)}",
        sdk = "macosx",
        target = "$arch-apple-macos13.0",
        isStatic = false,
        output = swiftOutput.map { it.file("macos-$arch/libStoreKitBridge.dylib") },
    )
}

val jvmResources = layout.buildDirectory.dir("generated/storekit/jvmResources")
val universalMacosLibrary = tasks.register<Exec>("assembleStoreKitBridgeMacos") {
    group = "build"
    description = "Combines the macOS StoreKit bridges into a universal library for the JVM"
    onlyIf { HostManager.hostIsMac }
    dependsOn(macosLibraries)
    val output = jvmResources.map { it.file("com/appmattus/billing/apple/libStoreKitBridge.dylib") }
    inputs.files(macosLibraries)
    outputs.file(output)
    doFirst { output.get().asFile.parentFile.mkdirs() }
    commandLine(
        listOf("lipo", "-create", "-output", output.get().asFile.absolutePath) +
            macosLibraries.map { library -> library.get().outputs.files.singleFile.absolutePath }
    )
}

kotlin {
    explicitApi()

    listOf(
        Triple(iosArm64(), "iphoneos", "arm64-apple-ios16.0"),
        Triple(iosSimulatorArm64(), "iphonesimulator", "arm64-apple-ios16.0-simulator"),
    ).forEach { (target, sdk, swiftTarget) ->
        val targetName = target.name.replaceFirstChar(Char::uppercase)
        val libraryDir = swiftOutput.map { it.dir(target.name) }
        val swiftLibrary = registerSwiftLibrary(
            name = "compileStoreKitBridge$targetName",
            sdk = sdk,
            target = swiftTarget,
            isStatic = true,
            output = libraryDir.map { it.file("libStoreKitBridge.a") },
        )

        target.compilations.getByName("main").cinterops.create("storekit") {
            definitionFile.set(file("src/nativeInterop/cinterop/storekit.def"))
            includeDirs(project(":billing-native-bridge").file("native"))
            extraOpts("-libraryPath", libraryDir.get().asFile.absolutePath)
        }
        tasks.named("cinteropStorekit$targetName") { dependsOn(swiftLibrary) }
    }

    jvm()

    jvmToolchain(17)

    sourceSets {
        all {
            languageSettings.optIn("com.appmattus.billing.bridge.InternalBillingApi")
        }
        commonMain.dependencies {
            api(project(":billing-core"))
            implementation(project(":billing-native-bridge"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain {
            resources.srcDir(jvmResources)
        }
    }
}

tasks.named("jvmProcessResources") { dependsOn(universalMacosLibrary) }
