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
    alias(libs.plugins.android.kotlin.multiplatform.library)
    id(libs.plugins.gradleMavenPublishPlugin.get().pluginId)
}

kotlin {
    explicitApi()

    android {
        namespace = "com.appmattus.billing.amazon"
        compileSdk = 37
        minSdk = 23

        withHostTest {}
    }

    jvmToolchain(17)

    sourceSets {
        androidMain.dependencies {
            api(project(":billing-core"))
            implementation(libs.amazon.appstore.sdk)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.mockk)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    // The Appstore SDK's classes have no stack map frames, which the JVM's verifier rejects. Android doesn't use it.
    jvmArgs("-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote")
}
