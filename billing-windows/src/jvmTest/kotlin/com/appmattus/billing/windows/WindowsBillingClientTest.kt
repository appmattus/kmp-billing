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

package com.appmattus.billing.windows

import com.appmattus.billing.BillingError
import com.appmattus.billing.BillingException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Calls the real Windows Store bridge on Windows. Tests run unpackaged, without a Store listing, so these check the
 * DLL loads, answers and returns valid JSON rather than that anything can be bought.
 */
class WindowsBillingClientTest {

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    @Test
    fun theBridgeAnswersIsAvailable() = runTest {
        WindowsBillingClient().use { client ->
            val available = client.isAvailable()
            println("Windows Store available: $available")
            if (!isWindows) assertFalse(available)
        }
    }

    @Test
    fun getPurchasesAnswersWithPurchasesOrAStoreError() = runTest {
        WindowsBillingClient().use { client ->
            val result = runCatching { client.getPurchases() }
            println("Windows Store purchases: $result")
            result.exceptionOrNull()?.let { assertIs<BillingException>(it) }
        }
    }

    @Test
    fun theStoreIsUnavailableOffWindows() = runTest {
        if (isWindows) return@runTest

        WindowsBillingClient().use { client ->
            val exception = assertFailsWith<BillingException> { client.getProducts(setOf("levels")) }
            assertEquals(BillingError.StoreUnavailable, exception.error)
        }
    }
}
