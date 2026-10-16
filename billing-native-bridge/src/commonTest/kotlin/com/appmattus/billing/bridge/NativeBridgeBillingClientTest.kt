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

package com.appmattus.billing.bridge

import com.appmattus.billing.BillingError
import com.appmattus.billing.BillingException
import com.appmattus.billing.Product
import com.appmattus.billing.ProductType
import com.appmattus.billing.Purchase
import com.appmattus.billing.PurchaseResult
import com.appmattus.billing.PurchaseState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NativeBridgeBillingClientTest {

    private val store = FakeNativeStore()
    private val client = NativeBridgeBillingClient(store)
    private val host = testPurchaseHost()

    @Test
    fun startsTheStore() {
        assertTrue(store.isStarted)
    }

    @Test
    fun isAvailableWhenTheUserCanMakePayments() = runTest {
        store.respond = { """{"result":true}""" }

        assertTrue(client.isAvailable())
    }

    @Test
    fun isUnavailableWhenTheStoreFails() = runTest {
        store.respond = { """{"error":{"code":"storeUnavailable","message":"No store"}}""" }

        assertFalse(client.isAvailable())
    }

    @Test
    fun productsAreDecoded() = runTest {
        store.respond = {
            """{"result":[
                {"id":"levels","type":"nonConsumable","title":"Levels","description":"More levels",
                 "formattedPrice":"£1.99","priceAmountMicros":1990000,"currencyCode":"GBP","extra":1},
                {"id":"coins","type":"consumable","title":"Coins","description":"100 coins","formattedPrice":"£0.99"}
            ]}"""
        }

        val products = client.getProducts(setOf("levels", "coins"))

        assertEquals(
            listOf(
                Product("levels", ProductType.NonConsumable, "Levels", "More levels", "£1.99", 1_990_000, "GBP"),
                Product("coins", ProductType.Consumable, "Coins", "100 coins", "£0.99"),
            ),
            products,
        )
        assertEquals(listOf("""getProducts ["levels","coins"]"""), store.calls)
    }

    @Test
    fun noProductsAreRequestedForAnEmptySet() = runTest {
        assertTrue(client.getProducts(emptySet()).isEmpty())
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun errorsAreThrownAsBillingExceptions() = runTest {
        val codes = mapOf(
            "storeUnavailable" to BillingError.StoreUnavailable,
            "notAllowed" to BillingError.NotAllowed,
            "productUnavailable" to BillingError.ProductUnavailable,
            "networkError" to BillingError.NetworkError,
            "developerError" to BillingError.DeveloperError,
            "verificationFailed" to BillingError.VerificationFailed,
            "somethingNew" to BillingError.Unknown,
        )

        codes.forEach { (code, error) ->
            store.respond = { """{"error":{"code":"$code","message":"Failed"}}""" }

            val exception = assertFailsWith<BillingException> { client.getPurchases() }

            assertEquals(error, exception.error, code)
            assertEquals("Failed", exception.message)
        }
    }

    @Test
    fun invalidJsonIsAnUnknownError() = runTest {
        store.respond = { "not json" }

        assertEquals(BillingError.Unknown, assertFailsWith<BillingException> { client.getPurchases() }.error)
    }

    @Test
    fun aSuccessfulPurchaseIsDecoded() = runTest {
        store.respond = { """{"result":{"status":"success","purchase":$PURCHASE_JSON}}""" }

        assertEquals(PurchaseResult.Success(purchase), client.purchase(host, "levels"))
        assertEquals(listOf("purchase levels"), store.calls)
    }

    @Test
    fun purchaseStatusesAreDecoded() = runTest {
        val statuses = mapOf(
            "pending" to PurchaseResult.Pending,
            "cancelled" to PurchaseResult.Cancelled,
            "alreadyOwned" to PurchaseResult.AlreadyOwned,
        )

        statuses.forEach { (status, result) ->
            store.respond = { """{"result":{"status":"$status"}}""" }

            assertEquals(result, client.purchase(host, "levels"), status)
        }
    }

    @Test
    fun purchaseErrorsAreFailures() = runTest {
        store.respond = { """{"error":{"code":"networkError","message":"Offline"}}""" }

        assertEquals(PurchaseResult.Failed(BillingError.NetworkError, "Offline"), client.purchase(host, "levels"))
    }

    @Test
    fun purchasesAreDecoded() = runTest {
        store.respond = { """{"result":[$PURCHASE_JSON]}""" }

        assertEquals(listOf(purchase), client.getPurchases())
        assertEquals(listOf(purchase), client.restorePurchases())
        assertEquals(listOf("getPurchases", "restorePurchases"), store.calls)
    }

    @Test
    fun finishingSendsThePurchase() = runTest {
        client.finishPurchase(purchase)

        val sent = store.calls.single().removePrefix("finishPurchase ")
        assertEquals(purchase, bridgeJson.decodeFromString<BridgePurchase>(sent).toPurchase())
    }

    @Test
    fun responsesCanArriveLater() = runTest {
        store.respond = { null }
        val available = async { client.isAvailable() }
        runCurrent()
        assertFalse(available.isCompleted)

        store.reply(requestId = 1, json = """{"result":true}""")

        assertTrue(available.await())
    }

    @Test
    fun purchaseUpdatesAreDecoded() = runTest {
        val update = async { client.purchaseUpdates.first() }
        runCurrent()

        store.sendUpdate("""{"result":$PURCHASE_JSON}""")

        assertEquals(purchase, update.await())
    }

    @Test
    fun invalidPurchaseUpdatesAreIgnored() = runTest {
        val update = async { client.purchaseUpdates.first() }
        runCurrent()

        store.sendUpdate("not json")
        store.sendUpdate("""{"result":$PURCHASE_JSON}""")

        assertEquals(purchase, update.await())
    }

    @Test
    fun closeStopsTheStoreAndFailsWaitingRequests() = runTest {
        store.respond = { null }
        val purchases = async { runCatching { client.getPurchases() } }
        runCurrent()

        client.close()

        assertFalse(store.isStarted)
        assertEquals(
            BillingError.StoreUnavailable,
            assertIs<BillingException>(purchases.await().exceptionOrNull()).error,
        )
    }

    @Test
    fun unavailableStoreFailsEveryCall() = runTest {
        val unavailable = NativeBridgeBillingClient(UnavailableNativeStore("Not on this platform"))

        assertFalse(unavailable.isAvailable())
        val exception = assertFailsWith<BillingException> { unavailable.getPurchases() }
        assertEquals(BillingError.StoreUnavailable, exception.error)
        assertEquals("Not on this platform", exception.message)
    }

    private val purchase = Purchase(
        id = "1000",
        productId = "levels",
        productType = ProductType.NonConsumable,
        state = PurchaseState.Purchased,
        quantity = 1,
        purchaseTimeMillis = 1_700_000_000_000,
        verificationData = "jws",
        isFinished = false,
    )

    private companion object {
        const val PURCHASE_JSON = """{"id":"1000","productId":"levels","productType":"nonConsumable",
            "state":"purchased","quantity":1,"purchaseTimeMillis":1700000000000,"verificationData":"jws",
            "isFinished":false}"""
    }
}
