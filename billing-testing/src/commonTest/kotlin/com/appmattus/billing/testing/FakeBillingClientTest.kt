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

package com.appmattus.billing.testing

import com.appmattus.billing.BillingError
import com.appmattus.billing.BillingException
import com.appmattus.billing.Product
import com.appmattus.billing.ProductType
import com.appmattus.billing.Purchase
import com.appmattus.billing.PurchaseResult
import com.appmattus.billing.PurchaseState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FakeBillingClientTest {

    private val levels = product("levels", ProductType.NonConsumable)
    private val coins = product("coins", ProductType.Consumable)
    private val client = FakeBillingClient(listOf(levels, coins))

    private val host = testPurchaseHost()

    @Test
    fun getProductsReturnsOnlyTheRequestedProducts() = runTest {
        assertEquals(listOf(coins), client.getProducts(setOf("coins", "unknown")))
    }

    @Test
    fun purchasingAddsThePurchase() = runTest {
        val result = assertIs<PurchaseResult.Success>(client.purchase(host, "levels"))

        assertEquals("levels", result.purchase.productId)
        assertEquals(PurchaseState.Purchased, result.purchase.state)
        assertEquals(listOf(result.purchase), client.getPurchases())
    }

    @Test
    fun anOwnedNonConsumableCannotBeBoughtAgain() = runTest {
        client.purchase(host, "levels")

        assertEquals(PurchaseResult.AlreadyOwned, client.purchase(host, "levels"))
    }

    @Test
    fun aConsumableCanBeBoughtAgainOnceFinished() = runTest {
        val first = assertIs<PurchaseResult.Success>(client.purchase(host, "coins")).purchase
        assertEquals(PurchaseResult.AlreadyOwned, client.purchase(host, "coins"))

        client.finishPurchase(first)

        assertTrue(client.getPurchases().isEmpty())
        assertIs<PurchaseResult.Success>(client.purchase(host, "coins"))
    }

    @Test
    fun finishingANonConsumableKeepsItOwned() = runTest {
        val purchase = assertIs<PurchaseResult.Success>(client.purchase(host, "levels")).purchase

        client.finishPurchase(purchase)

        assertEquals(listOf(purchase.copy(isFinished = true)), client.getPurchases())
        assertEquals(listOf(purchase), client.finishedPurchases)
    }

    @Test
    fun unknownProductsFailToPurchase() = runTest {
        assertEquals(PurchaseResult.Failed(BillingError.ProductUnavailable), client.purchase(host, "unknown"))
    }

    @Test
    fun nextPurchaseResultIsReturnedOnce() = runTest {
        client.nextPurchaseResult = PurchaseResult.Cancelled

        assertEquals(PurchaseResult.Cancelled, client.purchase(host, "levels"))
        assertIs<PurchaseResult.Success>(client.purchase(host, "levels"))
    }

    @Test
    fun failureIsThrownFromStoreCalls() = runTest {
        client.failure = BillingException(BillingError.NetworkError)

        assertEquals(BillingError.NetworkError, assertFailsWith<BillingException> { client.getPurchases() }.error)
        assertEquals(BillingError.NetworkError, assertFailsWith<BillingException> { client.restorePurchases() }.error)
    }

    @Test
    fun purchaseUpdatesAreDeliveredAndOwned() = runTest {
        val purchase = Purchase("approved", "levels", ProductType.NonConsumable, PurchaseState.Purchased)
        var received: Purchase? = null
        launch { received = client.purchaseUpdates.first() }
        runCurrent()

        client.emitPurchaseUpdate(purchase)
        runCurrent()

        assertEquals(purchase, received)
        assertEquals(listOf(purchase), client.getPurchases())
    }

    @Test
    fun restoresAreCounted() = runTest {
        client.restorePurchases()

        assertEquals(1, client.restoreCount)
    }

    private fun product(id: String, type: ProductType) = Product(id, type, id, "", "£0.99")
}
