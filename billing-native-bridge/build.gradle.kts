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

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id(libs.plugins.gradleMavenPublishPlugin.get().pluginId)
}

// The client shared by the StoreKit and Windows Store modules, which implement the C API in native/billing_bridge.h
kotlin {
    explicitApi()

    iosArm64()
    iosSimulatorArm64()

    jvm()

    jvmToolchain(17)

    sourceSets {
        all {
            languageSettings.optIn("com.appmattus.billing.bridge.InternalBillingApi")
        }
        commonMain.dependencies {
            api(project(":billing-core"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            api(libs.jna)
        }
    }
}
