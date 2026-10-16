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

package com.appmattus.billing.google

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsResult
import com.appmattus.billing.BillingError
import com.appmattus.billing.BillingException
import com.appmattus.billing.Product
import com.appmattus.billing.ProductType
import com.appmattus.billing.Purchase
import com.appmattus.billing.PurchaseResult
import com.appmattus.billing.PurchaseState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.android.billingclient.api.BillingClient as PlayBillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode as Code
import com.android.billingclient.api.Purchase as PlayPurchase

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleBillingClientTest {

    private val dispatcher = StandardTestDispatcher()
    private val playClient = mockk<PlayBillingClient>(relaxed = true)
    private val activity = mockk<Activity>()
    private lateinit var listener: PurchasesUpdatedListener

    private var setupResult = result(Code.OK)
    private var connected = false

    private val client = GoogleBillingClient(
        consumableProductIds = setOf("coins"),
        mainDispatcher = dispatcher,
        createPlayClient = { listener ->
            this.listener = listener
            playClient
        },
        // Building real BillingFlowParams needs the Android framework
        billingFlowParams = { mockk() },
    )

    init {
        every { playClient.isReady } answers { connected }
        every { playClient.startConnection(any()) } answers {
            connected = setupResult.responseCode == Code.OK
            firstArg<BillingClientStateListener>().onBillingSetupFinished(setupResult)
        }
        every { playClient.launchBillingFlow(any(), any()) } returns result(Code.OK)
    }

    @Test
    fun isAvailableWhenPlayConnects() = runTest(dispatcher) {
        assertTrue(client.isAvailable())
    }

    @Test
    fun isUnavailableWhenPlayFailsToConnect() = runTest(dispatcher) {
        setupResult = result(Code.BILLING_UNAVAILABLE)

        assertFalse(client.isAvailable())
    }

    @Test
    fun connectsOnlyOnce() = runTest(dispatcher) {
        client.isAvailable()
        client.isAvailable()

        verify(exactly = 1) { playClient.startConnection(any()) }
    }

    @Test
    fun productsAreMappedFromTheirOneTimeOffer() = runTest(dispatcher) {
        givenProducts(productDetails("levels"), productDetails("coins"))

        val products = client.getProducts(setOf("levels", "coins"))

        assertEquals(
            listOf(
                Product(
                    "levels",
                    ProductType.NonConsumable,
                    "levels name",
                    "levels description",
                    "£1.99",
                    1_990_000,
                    "GBP"
                ),
                Product("coins", ProductType.Consumable, "coins name", "coins description", "£1.99", 1_990_000, "GBP"),
            ),
            products,
        )
    }

    @Test
    fun productsWithoutAOneTimeOfferAreLeftOut() = runTest(dispatcher) {
        givenProducts(productDetails("subscription", oneTimeOffer = false))

        assertTrue(client.getProducts(setOf("subscription")).isEmpty())
    }

    @Test
    fun noProductsAreQueriedForAnEmptySet() = runTest(dispatcher) {
        assertTrue(client.getProducts(emptySet()).isEmpty())

        verify(exactly = 0) { playClient.queryProductDetailsAsync(any(), any()) }
    }

    @Test
    fun failedProductQueriesThrow() = runTest(dispatcher) {
        givenProducts(result = result(Code.NETWORK_ERROR))

        val exception = assertFailsWith<BillingException> { client.getProducts(setOf("levels")) }

        assertEquals(BillingError.NetworkError, exception.error)
    }

    @Test
    fun aCompletedPurchaseSucceeds() = runTest(dispatcher) {
        givenProducts(productDetails("levels"))

        val result = purchase("levels") {
            listener.onPurchasesUpdated(result(Code.OK), mutableListOf(playPurchase("levels")))
        }

        assertEquals(PurchaseResult.Success(purchase("levels", isFinished = false)), result)
    }

    @Test
    fun aPendingPurchaseIsPending() = runTest(dispatcher) {
        givenProducts(productDetails("levels"))

        val result = purchase("levels") {
            listener.onPurchasesUpdated(
                result(Code.OK),
                mutableListOf(playPurchase("levels", state = PlayPurchase.PurchaseState.PENDING)),
            )
        }

        assertEquals(PurchaseResult.Pending, result)
    }

    @Test
    fun purchaseResponsesAreMapped() = runTest(dispatcher) {
        givenProducts(productDetails("levels"))
        val expected = mapOf(
            Code.USER_CANCELED to PurchaseResult.Cancelled,
            Code.ITEM_ALREADY_OWNED to PurchaseResult.AlreadyOwned,
            Code.NETWORK_ERROR to PurchaseResult.Failed(BillingError.NetworkError),
            Code.ERROR to PurchaseResult.Failed(BillingError.Unknown),
        )

        expected.forEach { (code, expectedResult) ->
            val result = purchase("levels") { listener.onPurchasesUpdated(result(code), null) }

            assertEquals(expectedResult, result, "Response code $code")
        }
    }

    @Test
    fun aBillingFlowThatFailsToLaunchFails() = runTest(dispatcher) {
        givenProducts(productDetails("levels"))
        every { playClient.launchBillingFlow(any(), any()) } returns result(Code.DEVELOPER_ERROR)

        assertEquals(PurchaseResult.Failed(BillingError.DeveloperError), client.purchase(activity, "levels"))
    }

    @Test
    fun unknownProductsFailToPurchase() = runTest(dispatcher) {
        givenProducts()

        val result = assertIs<PurchaseResult.Failed>(client.purchase(activity, "unknown"))

        assertEquals(BillingError.ProductUnavailable, result.error)
    }

    @Test
    fun purchaseFailsWhenPlayIsUnavailable() = runTest(dispatcher) {
        setupResult = result(Code.SERVICE_UNAVAILABLE)

        val result = assertIs<PurchaseResult.Failed>(client.purchase(activity, "levels"))

        assertEquals(BillingError.StoreUnavailable, result.error)
    }

    @Test
    fun purchasesOutsideAPurchaseFlowAreDeliveredAsUpdates() = runTest(dispatcher) {
        val update = async { client.purchaseUpdates.first() }
        runCurrent()

        listener.onPurchasesUpdated(result(Code.OK), mutableListOf(playPurchase("levels")))

        assertEquals(purchase("levels", isFinished = false), update.await())
    }

    @Test
    fun getPurchasesReturnsPurchasedAndPendingPurchases() = runTest(dispatcher) {
        givenPurchases(
            playPurchase("levels", acknowledged = true),
            playPurchase("coins", state = PlayPurchase.PurchaseState.PENDING),
            playPurchase("unknown", state = PlayPurchase.PurchaseState.UNSPECIFIED_STATE),
        )

        assertEquals(
            listOf(
                purchase("levels", isFinished = true),
                purchase("coins", ProductType.Consumable, PurchaseState.Pending),
            ),
            client.getPurchases(),
        )
    }

    @Test
    fun restorePurchasesQueriesPurchases() = runTest(dispatcher) {
        givenPurchases(playPurchase("levels"))

        assertEquals(listOf(purchase("levels", isFinished = false)), client.restorePurchases())
    }

    @Test
    fun failedPurchaseQueriesThrow() = runTest(dispatcher) {
        givenPurchases(result = result(Code.SERVICE_DISCONNECTED))

        assertEquals(
            BillingError.StoreUnavailable,
            assertFailsWith<BillingException> { client.getPurchases() }.error,
        )
    }

    @Test
    fun finishingANonConsumableAcknowledgesIt() = runTest(dispatcher) {
        val token = slot<com.android.billingclient.api.AcknowledgePurchaseParams>()
        every { playClient.acknowledgePurchase(capture(token), any()) } answers {
            secondArg<AcknowledgePurchaseResponseListener>().onAcknowledgePurchaseResponse(result(Code.OK))
        }

        client.finishPurchase(purchase("levels", isFinished = false))

        assertEquals("token-levels", token.captured.purchaseToken)
        verify(exactly = 0) { playClient.consumeAsync(any(), any()) }
    }

    @Test
    fun finishedNonConsumablesAreNotAcknowledgedAgain() = runTest(dispatcher) {
        client.finishPurchase(purchase("levels", isFinished = true))

        verify(exactly = 0) { playClient.acknowledgePurchase(any(), any()) }
    }

    @Test
    fun finishingAConsumableConsumesIt() = runTest(dispatcher) {
        val params = slot<com.android.billingclient.api.ConsumeParams>()
        every { playClient.consumeAsync(capture(params), any()) } answers {
            secondArg<ConsumeResponseListener>().onConsumeResponse(result(Code.OK), "token-coins")
        }

        client.finishPurchase(purchase("coins", ProductType.Consumable))

        assertEquals("token-coins", params.captured.purchaseToken)
    }

    @Test
    fun finishingAnAlreadyConsumedConsumableSucceeds() = runTest(dispatcher) {
        every { playClient.consumeAsync(any(), any()) } answers {
            secondArg<ConsumeResponseListener>().onConsumeResponse(result(Code.ITEM_NOT_OWNED), "token-coins")
        }

        client.finishPurchase(purchase("coins", ProductType.Consumable))
    }

    @Test
    fun pendingPurchasesCannotBeFinished() = runTest(dispatcher) {
        val exception = assertFailsWith<BillingException> {
            client.finishPurchase(purchase("levels", state = PurchaseState.Pending))
        }

        assertEquals(BillingError.DeveloperError, exception.error)
    }

    @Test
    fun closeEndsTheConnection() {
        client.close()

        verify { playClient.endConnection() }
    }

    private suspend fun TestScope.purchase(productId: String, respond: () -> Unit): PurchaseResult {
        val result = async { client.purchase(activity, productId) }
        runCurrent()
        respond()
        return result.await()
    }

    private fun givenProducts(vararg details: ProductDetails, result: BillingResult = result(Code.OK)) {
        every { playClient.queryProductDetailsAsync(any(), any()) } answers {
            secondArg<ProductDetailsResponseListener>().onProductDetailsResponse(
                result,
                QueryProductDetailsResult.create(details.toList(), emptyList()),
            )
        }
    }

    private fun givenPurchases(vararg purchases: PlayPurchase, result: BillingResult = result(Code.OK)) {
        every { playClient.queryPurchasesAsync(any<com.android.billingclient.api.QueryPurchasesParams>(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(result, purchases.toList())
        }
    }

    private fun productDetails(id: String, oneTimeOffer: Boolean = true) = mockk<ProductDetails>(relaxed = true) {
        every { productId } returns id
        every { productType } returns PlayBillingClient.ProductType.INAPP
        every { name } returns "$id name"
        every { description } returns "$id description"
        every { oneTimePurchaseOfferDetails } returns if (oneTimeOffer) {
            mockk(relaxed = true) {
                every { formattedPrice } returns "£1.99"
                every { priceAmountMicros } returns 1_990_000
                every { priceCurrencyCode } returns "GBP"
            }
        } else {
            null
        }
    }

    private fun playPurchase(
        productId: String,
        state: Int = PlayPurchase.PurchaseState.PURCHASED,
        acknowledged: Boolean = false,
    ) = mockk<PlayPurchase> {
        every { products } returns listOf(productId)
        every { purchaseState } returns state
        every { orderId } returns "order-$productId"
        every { purchaseToken } returns "token-$productId"
        every { quantity } returns 1
        every { purchaseTime } returns PURCHASE_TIME
        every { isAcknowledged } returns acknowledged
    }

    private fun purchase(
        productId: String,
        type: ProductType = ProductType.NonConsumable,
        state: PurchaseState = PurchaseState.Purchased,
        isFinished: Boolean = false,
    ) = Purchase(
        id = "order-$productId",
        productId = productId,
        productType = type,
        state = state,
        quantity = 1,
        purchaseTimeMillis = PURCHASE_TIME,
        verificationData = "token-$productId",
        isFinished = isFinished,
    )

    private fun result(code: Int) = BillingResult.newBuilder().setResponseCode(code).build()

    private companion object {
        const val PURCHASE_TIME = 1_700_000_000_000
    }
}
