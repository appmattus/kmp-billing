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

package com.appmattus.billing.apple

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Calls the real StoreKit bridge: statically linked on iOS, and loaded with JNA on the JVM. Without a StoreKit
 * configuration there's nothing to buy, so these check the bridge is linked, answers and returns valid JSON.
 */
class AppleBillingClientTest {

    @Test
    fun theBridgeAnswersIsAvailable() = runTest {
        AppleBillingClient().use { client ->
            println("StoreKit available: ${client.isAvailable()}")
        }
    }

    @Test
    fun thereAreNoPurchasesWithoutAStoreKitConfiguration() = runTest {
        AppleBillingClient().use { client ->
            assertEquals(emptyList(), client.getPurchases())
        }
    }
}
