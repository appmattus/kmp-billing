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

package com.appmattus.billing

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnsupportedBillingClientTest {

    private val client = UnsupportedBillingClient()

    @Test
    fun isNeverAvailable() = runTest {
        assertFalse(client.isAvailable())
    }

    @Test
    fun hasNoPurchaseUpdates() = runTest {
        assertTrue(client.purchaseUpdates.toList().isEmpty())
    }

    @Test
    fun storeCallsThrowStoreUnavailable() = runTest {
        val calls = listOf<suspend () -> Unit>(
            { client.getProducts(setOf("product")) },
            { client.getPurchases() },
            { client.restorePurchases() },
            { client.finishPurchase(purchase) },
        )

        calls.forEach { call ->
            assertEquals(BillingError.StoreUnavailable, assertFailsWith<BillingException> { call() }.error)
        }
    }

    private val purchase = Purchase(
        id = "purchase",
        productId = "product",
        productType = ProductType.NonConsumable,
        state = PurchaseState.Purchased,
    )
}
