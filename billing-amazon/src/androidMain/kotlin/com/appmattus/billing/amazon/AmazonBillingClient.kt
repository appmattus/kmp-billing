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

import android.content.Context
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.Receipt
import com.amazon.device.iap.model.RequestId
import com.amazon.device.iap.model.UserDataResponse
import com.appmattus.billing.BillingClient
import com.appmattus.billing.BillingError
import com.appmattus.billing.BillingException
import com.appmattus.billing.Product
import com.appmattus.billing.ProductType
import com.appmattus.billing.Purchase
import com.appmattus.billing.PurchaseHost
import com.appmattus.billing.PurchaseResult
import com.appmattus.billing.PurchaseState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import com.amazon.device.iap.model.Product as AmazonProduct
import com.amazon.device.iap.model.ProductType as AmazonProductType

/**
 * The Amazon Appstore.
 *
 * The Appstore SDK allows one listener per app, so create a single instance. [Purchase.verificationData] is the
 * receipt ID; verifying it with Amazon's Receipt Verification Service also needs [getUserId].
 */
public class AmazonBillingClient internal constructor(
    private val purchasing: AmazonPurchasing,
) : BillingClient {

    public constructor(context: Context) : this(AppstorePurchasing(context.applicationContext))

    private val lock = Any()
    private val requests = mutableMapOf<String, CompletableDeferred<Any>>()
    private val updates = MutableSharedFlow<Purchase>(extraBufferCapacity = UPDATE_BUFFER)

    override val purchaseUpdates: Flow<Purchase> = updates

    init {
        purchasing.registerListener(Listener())
    }

    override suspend fun isAvailable(): Boolean {
        val response: UserDataResponse = request { purchasing.getUserData() }
        return response.requestStatus == UserDataResponse.RequestStatus.SUCCESSFUL
    }

    /** The Amazon user ID, which Amazon's Receipt Verification Service needs alongside the receipt ID. */
    public suspend fun getUserId(): String {
        val response: UserDataResponse = request { purchasing.getUserData() }
        if (response.requestStatus != UserDataResponse.RequestStatus.SUCCESSFUL) {
            throw BillingException(response.requestStatus.toError(), "Couldn't get the Amazon user")
        }
        return response.userData.userId
    }

    override suspend fun getProducts(productIds: Set<String>): List<Product> {
        if (productIds.isEmpty()) return emptyList()

        val response: ProductDataResponse = request { purchasing.getProductData(productIds) }
        if (response.requestStatus != ProductDataResponse.RequestStatus.SUCCESSFUL) {
            throw BillingException(response.requestStatus.toError(), "Couldn't get products")
        }
        return response.productData.values.mapNotNull { it.toProduct() }
    }

    override suspend fun purchase(host: PurchaseHost, productId: String): PurchaseResult {
        val response: PurchaseResponse = request { purchasing.purchase(productId) }
        return when (response.requestStatus) {
            PurchaseResponse.RequestStatus.SUCCESSFUL -> response.receipt.toPurchase()
                ?.let { PurchaseResult.Success(it) }
                ?: PurchaseResult.Failed(BillingError.Unknown, "Amazon returned an unsupported receipt")

            PurchaseResponse.RequestStatus.PENDING -> PurchaseResult.Pending
            PurchaseResponse.RequestStatus.ALREADY_PURCHASED -> PurchaseResult.AlreadyOwned
            PurchaseResponse.RequestStatus.INVALID_SKU ->
                PurchaseResult.Failed(BillingError.ProductUnavailable, "Unknown product $productId")

            PurchaseResponse.RequestStatus.NOT_SUPPORTED -> PurchaseResult.Failed(BillingError.StoreUnavailable)
            // Amazon reports the user cancelling as a failure
            PurchaseResponse.RequestStatus.FAILED -> PurchaseResult.Cancelled
            else -> PurchaseResult.Failed(BillingError.Unknown, response.requestStatus.name)
        }
    }

    override suspend fun getPurchases(): List<Purchase> {
        val receipts = mutableListOf<Receipt>()
        var reset = true
        do {
            val response: PurchaseUpdatesResponse = request { purchasing.getPurchaseUpdates(reset) }
            if (response.requestStatus != PurchaseUpdatesResponse.RequestStatus.SUCCESSFUL) {
                throw BillingException(response.requestStatus.toError(), "Couldn't get purchases")
            }
            receipts += response.receipts
            reset = false
        } while (response.hasMore())

        return receipts.filterNot { it.isCanceled }.mapNotNull { it.toPurchase() }.distinctBy { it.id }
    }

    // Asking for every purchase from the start is how the Appstore restores purchases
    override suspend fun restorePurchases(): List<Purchase> = getPurchases()

    override suspend fun finishPurchase(purchase: Purchase) {
        if (purchase.state == PurchaseState.Pending) {
            throw BillingException(BillingError.DeveloperError, "Pending purchases can't be finished")
        }
        purchasing.notifyFulfillment(purchase.id, FulfillmentResult.FULFILLED)
    }

    // The Appstore SDK has no way to unregister its listener
    override fun close(): Unit = Unit

    /** Starts a request and waits for the listener to receive its response. */
    private suspend inline fun <reified T : Any> request(start: () -> RequestId): T {
        val deferred = CompletableDeferred<Any>()
        // Holding the lock stops the response, which arrives on another thread, being handled before it's expected
        synchronized(lock) { requests[start().toString()] = deferred }
        return deferred.await() as T
    }

    /** Completes the request [requestId], returning false if this client didn't start it. */
    private fun respond(requestId: RequestId, response: Any): Boolean =
        synchronized(lock) { requests.remove(requestId.toString()) }?.complete(response) ?: false

    private inner class Listener : PurchasingListener {
        override fun onUserDataResponse(response: UserDataResponse) {
            respond(response.requestId, response)
        }

        override fun onProductDataResponse(response: ProductDataResponse) {
            respond(response.requestId, response)
        }

        override fun onPurchaseResponse(response: PurchaseResponse) {
            if (respond(response.requestId, response)) return

            // A purchase this client didn't start, such as an approved pending purchase
            if (response.requestStatus == PurchaseResponse.RequestStatus.SUCCESSFUL) {
                response.receipt.toPurchase()?.let { updates.tryEmit(it) }
            }
        }

        override fun onPurchaseUpdatesResponse(response: PurchaseUpdatesResponse) {
            respond(response.requestId, response)
        }
    }

    private companion object {
        const val UPDATE_BUFFER = 64
    }
}

private fun AmazonProduct.toProduct(): Product? = Product(
    id = sku,
    type = productType.toProductType() ?: return null,
    title = title,
    description = description,
    formattedPrice = price,
)

private fun Receipt.toPurchase(): Purchase? = Purchase(
    id = receiptId,
    productId = sku,
    productType = productType.toProductType() ?: return null,
    state = PurchaseState.Purchased,
    purchaseTimeMillis = purchaseDate?.time,
    verificationData = receiptId,
)

private fun AmazonProductType.toProductType(): ProductType? = when (this) {
    AmazonProductType.CONSUMABLE -> ProductType.Consumable
    AmazonProductType.ENTITLED -> ProductType.NonConsumable
    // Subscriptions aren't supported yet
    AmazonProductType.SUBSCRIPTION -> null
}

private fun Enum<*>.toError(): BillingError =
    if (name == "NOT_SUPPORTED") BillingError.StoreUnavailable else BillingError.Unknown
