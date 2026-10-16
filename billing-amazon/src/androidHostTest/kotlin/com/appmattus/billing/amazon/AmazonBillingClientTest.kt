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

package com.appmattus.billing.amazon

import android.app.Activity
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.internal.model.ProductBuilder
import com.amazon.device.iap.internal.model.ProductDataResponseBuilder
import com.amazon.device.iap.internal.model.PurchaseResponseBuilder
import com.amazon.device.iap.internal.model.PurchaseUpdatesResponseBuilder
import com.amazon.device.iap.internal.model.ReceiptBuilder
import com.amazon.device.iap.internal.model.UserDataBuilder
import com.amazon.device.iap.internal.model.UserDataResponseBuilder
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.Receipt
import com.amazon.device.iap.model.RequestId
import com.amazon.device.iap.model.UserDataResponse
import com.appmattus.billing.BillingError
import com.appmattus.billing.BillingException
import com.appmattus.billing.Product
import com.appmattus.billing.ProductType
import com.appmattus.billing.Purchase
import com.appmattus.billing.PurchaseResult
import com.appmattus.billing.PurchaseState
import io.mockk.mockk
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.amazon.device.iap.model.ProductType as AmazonProductType

@OptIn(ExperimentalCoroutinesApi::class)
class AmazonBillingClientTest {

    private val purchasing = FakeAmazonPurchasing()
    private val client = AmazonBillingClient(purchasing)
    private val activity = mockk<Activity>()

    @Test
    fun registersItsListener() {
        assertTrue(purchasing.isListenerRegistered)
    }

    @Test
    fun isAvailableWhenUserDataLoads() = runTest {
        val available = start { client.isAvailable() }

        purchasing.listener.onUserDataResponse(userDataResponse(UserDataResponse.RequestStatus.SUCCESSFUL))

        assertTrue(available.await())
    }

    @Test
    fun isUnavailableWhenTheAppstoreIsNotSupported() = runTest {
        val available = start { client.isAvailable() }

        purchasing.listener.onUserDataResponse(userDataResponse(UserDataResponse.RequestStatus.NOT_SUPPORTED))

        assertFalse(available.await())
    }

    @Test
    fun getUserIdReturnsTheAmazonUser() = runTest {
        val userId = start { client.getUserId() }

        purchasing.listener.onUserDataResponse(userDataResponse(UserDataResponse.RequestStatus.SUCCESSFUL))

        assertEquals("amazon-user", userId.await())
    }

    @Test
    fun productsAreMapped() = runTest {
        val products = start { client.getProducts(setOf("levels", "coins", "subscription")) }

        purchasing.listener.onProductDataResponse(
            productDataResponse(
                ProductDataResponse.RequestStatus.SUCCESSFUL,
                product("levels", AmazonProductType.ENTITLED),
                product("coins", AmazonProductType.CONSUMABLE),
                product("subscription", AmazonProductType.SUBSCRIPTION),
            )
        )

        assertEquals(
            listOf(
                Product("levels", ProductType.NonConsumable, "levels title", "levels description", "£0.99"),
                Product("coins", ProductType.Consumable, "coins title", "coins description", "£0.99"),
            ),
            products.await(),
        )
        assertEquals(listOf(setOf("levels", "coins", "subscription")), purchasing.productDataRequests)
    }

    @Test
    fun failedProductRequestsThrow() = runTest {
        val products = start { runCatching { client.getProducts(setOf("levels")) } }

        purchasing.listener.onProductDataResponse(productDataResponse(ProductDataResponse.RequestStatus.FAILED))

        assertEquals(BillingError.Unknown, assertIs<BillingException>(products.await().exceptionOrNull()).error)
    }

    @Test
    fun noProductsAreRequestedForAnEmptySet() = runTest {
        assertTrue(client.getProducts(emptySet()).isEmpty())
        assertTrue(purchasing.productDataRequests.isEmpty())
    }

    @Test
    fun aSuccessfulPurchaseSucceeds() = runTest {
        val result = start { client.purchase(activity, "levels") }

        purchasing.listener.onPurchaseResponse(
            purchaseResponse(PurchaseResponse.RequestStatus.SUCCESSFUL, receipt("levels", AmazonProductType.ENTITLED))
        )

        assertEquals(PurchaseResult.Success(purchase("levels")), result.await())
        assertEquals(listOf("levels"), purchasing.purchaseRequests)
    }

    @Test
    fun purchaseStatusesAreMapped() = runTest {
        val expected = mapOf(
            PurchaseResponse.RequestStatus.PENDING to PurchaseResult.Pending,
            PurchaseResponse.RequestStatus.ALREADY_PURCHASED to PurchaseResult.AlreadyOwned,
            PurchaseResponse.RequestStatus.FAILED to PurchaseResult.Cancelled,
            PurchaseResponse.RequestStatus.NOT_SUPPORTED to PurchaseResult.Failed(BillingError.StoreUnavailable),
        )

        expected.forEach { (status, expectedResult) ->
            val result = start { client.purchase(activity, "levels") }

            purchasing.listener.onPurchaseResponse(purchaseResponse(status))

            assertEquals(expectedResult, result.await(), status.name)
        }
    }

    @Test
    fun invalidSkusFailAsUnavailable() = runTest {
        val result = start { client.purchase(activity, "unknown") }

        purchasing.listener.onPurchaseResponse(purchaseResponse(PurchaseResponse.RequestStatus.INVALID_SKU))

        assertEquals(BillingError.ProductUnavailable, assertIs<PurchaseResult.Failed>(result.await()).error)
    }

    @Test
    fun purchasesThisClientDidNotStartAreDeliveredAsUpdates() = runTest {
        val update = start { client.purchaseUpdates.first() }

        purchasing.listener.onPurchaseResponse(
            purchaseResponse(
                PurchaseResponse.RequestStatus.SUCCESSFUL,
                receipt("levels", AmazonProductType.ENTITLED),
                requestId = RequestId(),
            )
        )

        assertEquals(purchase("levels"), update.await())
    }

    @Test
    fun getPurchasesReadsEveryPageFromTheStart() = runTest {
        val purchases = start { client.getPurchases() }

        purchasing.listener.onPurchaseUpdatesResponse(
            purchaseUpdatesResponse(hasMore = true, receipt("levels", AmazonProductType.ENTITLED))
        )
        runCurrent()
        purchasing.listener.onPurchaseUpdatesResponse(
            purchaseUpdatesResponse(hasMore = false, receipt("coins", AmazonProductType.CONSUMABLE))
        )

        assertEquals(listOf(purchase("levels"), purchase("coins", ProductType.Consumable)), purchases.await())
        assertEquals(listOf(true, false), purchasing.purchaseUpdatesRequests)
    }

    @Test
    fun cancelledAndSubscriptionReceiptsAreLeftOut() = runTest {
        val purchases = start { client.restorePurchases() }

        purchasing.listener.onPurchaseUpdatesResponse(
            purchaseUpdatesResponse(
                hasMore = false,
                receipt("refunded", AmazonProductType.ENTITLED, cancelled = true),
                receipt("subscription", AmazonProductType.SUBSCRIPTION),
            )
        )

        assertTrue(purchases.await().isEmpty())
    }

    @Test
    fun failedPurchaseUpdatesThrow() = runTest {
        val purchases = start { runCatching { client.getPurchases() } }

        purchasing.listener.onPurchaseUpdatesResponse(
            purchaseUpdatesResponse(hasMore = false, status = PurchaseUpdatesResponse.RequestStatus.NOT_SUPPORTED)
        )

        assertEquals(
            BillingError.StoreUnavailable,
            assertIs<BillingException>(purchases.await().exceptionOrNull()).error,
        )
    }

    @Test
    fun finishingAPurchaseFulfilsIt() = runTest {
        client.finishPurchase(purchase("levels"))

        assertEquals(listOf("receipt-levels" to FulfillmentResult.FULFILLED), purchasing.fulfilments)
    }

    @Test
    fun pendingPurchasesCannotBeFinished() = runTest {
        val exception = assertFailsWith<BillingException> {
            client.finishPurchase(purchase("levels").copy(state = PurchaseState.Pending))
        }

        assertEquals(BillingError.DeveloperError, exception.error)
        assertTrue(purchasing.fulfilments.isEmpty())
    }

    /** Starts [block], letting it make its request so the test can respond to it. */
    private fun <T> TestScope.start(block: suspend () -> T): Deferred<T> =
        async { block() }.also { runCurrent() }

    private fun userDataResponse(status: UserDataResponse.RequestStatus) = UserDataResponseBuilder()
        .setRequestId(purchasing.lastRequestId)
        .setRequestStatus(status)
        .setUserData(UserDataBuilder().setUserId("amazon-user").setMarketplace("GB").build())
        .build()

    private fun productDataResponse(
        status: ProductDataResponse.RequestStatus,
        vararg products: com.amazon.device.iap.model.Product,
    ) = ProductDataResponseBuilder()
        .setRequestId(purchasing.lastRequestId)
        .setRequestStatus(status)
        .setProductData(products.associateBy { it.sku })
        .setUnavailableSkus(emptySet())
        .build()

    private fun product(sku: String, type: AmazonProductType) = ProductBuilder()
        .setSku(sku)
        .setProductType(type)
        .setTitle("$sku title")
        .setDescription("$sku description")
        .setPrice("£0.99")
        .setSmallIconUrl("https://example.com/$sku.png")
        .build()

    private fun purchaseResponse(
        status: PurchaseResponse.RequestStatus,
        receipt: Receipt? = null,
        requestId: RequestId = purchasing.lastRequestId,
    ) = PurchaseResponseBuilder()
        .setRequestId(requestId)
        .setRequestStatus(status)
        .setUserData(UserDataBuilder().setUserId("amazon-user").setMarketplace("GB").build())
        .setReceipt(receipt)
        .build()

    private fun purchaseUpdatesResponse(
        hasMore: Boolean,
        vararg receipts: Receipt,
        status: PurchaseUpdatesResponse.RequestStatus = PurchaseUpdatesResponse.RequestStatus.SUCCESSFUL,
    ) = PurchaseUpdatesResponseBuilder()
        .setRequestId(purchasing.lastRequestId)
        .setRequestStatus(status)
        .setUserData(UserDataBuilder().setUserId("amazon-user").setMarketplace("GB").build())
        .setReceipts(receipts.toList())
        .setHasMore(hasMore)
        .build()

    private fun receipt(sku: String, type: AmazonProductType, cancelled: Boolean = false) = ReceiptBuilder()
        .setReceiptId("receipt-$sku")
        .setSku(sku)
        .setProductType(type)
        .setPurchaseDate(Date(PURCHASE_TIME))
        .setCancelDate(if (cancelled) Date(PURCHASE_TIME + 1) else null)
        .build()

    private fun purchase(productId: String, type: ProductType = ProductType.NonConsumable) = Purchase(
        id = "receipt-$productId",
        productId = productId,
        productType = type,
        state = PurchaseState.Purchased,
        purchaseTimeMillis = PURCHASE_TIME,
        verificationData = "receipt-$productId",
    )

    private class FakeAmazonPurchasing : AmazonPurchasing {
        lateinit var listener: PurchasingListener
        val isListenerRegistered get() = ::listener.isInitialized

        lateinit var lastRequestId: RequestId
        val productDataRequests = mutableListOf<Set<String>>()
        val purchaseRequests = mutableListOf<String>()
        val purchaseUpdatesRequests = mutableListOf<Boolean>()
        val fulfilments = mutableListOf<Pair<String, FulfillmentResult>>()

        override fun registerListener(listener: PurchasingListener) {
            this.listener = listener
        }

        override fun getUserData() = newRequest()

        override fun getProductData(skus: Set<String>) = newRequest().also { productDataRequests += skus }

        override fun purchase(sku: String) = newRequest().also { purchaseRequests += sku }

        override fun getPurchaseUpdates(reset: Boolean) = newRequest().also { purchaseUpdatesRequests += reset }

        override fun notifyFulfillment(receiptId: String, result: FulfillmentResult) {
            fulfilments += receiptId to result
        }

        private fun newRequest() = RequestId().also { lastRequestId = it }
    }

    private companion object {
        const val PURCHASE_TIME = 1_700_000_000_000
    }
}
