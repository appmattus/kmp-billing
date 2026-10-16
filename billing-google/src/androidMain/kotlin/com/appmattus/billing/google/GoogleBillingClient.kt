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

import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import com.android.billingclient.api.BillingClient as PlayBillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode as Code
import com.android.billingclient.api.Purchase as PlayPurchase

/**
 * Google Play Billing.
 *
 * Play doesn't record whether a product is consumable, so pass the identifiers of the consumable products as
 * [consumableProductIds]; every other product is treated as a non-consumable. [Purchase.verificationData] is the
 * purchase token.
 */
public class GoogleBillingClient internal constructor(
    private val consumableProductIds: Set<String>,
    private val mainDispatcher: CoroutineDispatcher,
    createPlayClient: (PurchasesUpdatedListener) -> PlayBillingClient,
    private val billingFlowParams: (ProductDetails) -> BillingFlowParams = ::billingFlowParams,
) : BillingClient {

    public constructor(context: Context, consumableProductIds: Set<String> = emptySet()) : this(
        consumableProductIds = consumableProductIds,
        mainDispatcher = Dispatchers.Main.immediate,
        createPlayClient = { listener ->
            PlayBillingClient.newBuilder(context.applicationContext)
                .setListener(listener)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build()
        },
    )

    private val updates = MutableSharedFlow<Purchase>(extraBufferCapacity = UPDATE_BUFFER)
    private val playClient = createPlayClient(::onPurchasesUpdated)
    private val connectionMutex = Mutex()
    private val purchaseMutex = Mutex()
    private val productDetails = ConcurrentHashMap<String, ProductDetails>()

    @Volatile
    private var purchaseInProgress: CompletableDeferred<PurchasesUpdate>? = null

    override val purchaseUpdates: Flow<Purchase> = updates

    override suspend fun isAvailable(): Boolean = try {
        connect()
        true
    } catch (_: BillingException) {
        false
    }

    override suspend fun getProducts(productIds: Set<String>): List<Product> {
        if (productIds.isEmpty()) return emptyList()
        connect()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(PlayBillingClient.ProductType.INAPP)
                        .build()
                }
            )
            .build()

        val (result, details) = suspendCancellableCoroutine { continuation ->
            playClient.queryProductDetailsAsync(params) { result, details ->
                continuation.resume(result to details.productDetailsList)
            }
        }
        result.throwIfFailed()

        return details.mapNotNull { productDetails ->
            this.productDetails[productDetails.productId] = productDetails
            productDetails.toProduct()
        }
    }

    override suspend fun purchase(host: PurchaseHost, productId: String): PurchaseResult = try {
        connect()
        val details = productDetails[productId]
            ?: getProducts(setOf(productId)).let { productDetails[productId] }
            ?: return PurchaseResult.Failed(BillingError.ProductUnavailable, "Unknown product $productId")

        purchaseMutex.withLock { launchPurchase(host, productId, details) }
    } catch (exception: BillingException) {
        PurchaseResult.Failed(exception.error, exception.message)
    }

    private suspend fun launchPurchase(host: PurchaseHost, productId: String, details: ProductDetails): PurchaseResult {
        val params = billingFlowParams(details)

        val update = CompletableDeferred<PurchasesUpdate>()
        purchaseInProgress = update
        try {
            val launch = withContext(mainDispatcher) { playClient.launchBillingFlow(host, params) }
            if (launch.responseCode != Code.OK) return launch.toFailure()

            return update.await().toPurchaseResult(productId)
        } finally {
            purchaseInProgress = null
        }
    }

    override suspend fun getPurchases(): List<Purchase> {
        connect()

        val params = QueryPurchasesParams.newBuilder().setProductType(PlayBillingClient.ProductType.INAPP).build()
        val (result, purchases) = suspendCancellableCoroutine { continuation ->
            playClient.queryPurchasesAsync(params) { result, purchases -> continuation.resume(result to purchases) }
        }
        result.throwIfFailed()

        return purchases.flatMap { it.toPurchases() }
    }

    // Google Play keeps purchases in sync itself
    override suspend fun restorePurchases(): List<Purchase> = getPurchases()

    override suspend fun finishPurchase(purchase: Purchase) {
        if (purchase.state == PurchaseState.Pending) {
            throw BillingException(BillingError.DeveloperError, "Pending purchases can't be finished")
        }
        val token = purchase.verificationData
            ?: throw BillingException(BillingError.DeveloperError, "The purchase has no purchase token")
        connect()

        if (purchase.productType == ProductType.Consumable) {
            val result = suspendCancellableCoroutine { continuation ->
                val params = ConsumeParams.newBuilder().setPurchaseToken(token).build()
                playClient.consumeAsync(params) { result, _ -> continuation.resume(result) }
            }
            // Already consumed
            if (result.responseCode != Code.ITEM_NOT_OWNED) result.throwIfFailed()
        } else if (!purchase.isFinished) {
            val result = suspendCancellableCoroutine { continuation ->
                val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
                playClient.acknowledgePurchase(params) { result -> continuation.resume(result) }
            }
            result.throwIfFailed()
        }
    }

    override fun close() {
        playClient.endConnection()
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<PlayPurchase>?) {
        val update = PurchasesUpdate(result, purchases.orEmpty())
        val inProgress = purchaseInProgress
        if (inProgress != null && inProgress.complete(update)) return

        if (result.responseCode == Code.OK) {
            update.purchases.flatMap { it.toPurchases() }.forEach { updates.tryEmit(it) }
        }
    }

    private suspend fun connect() {
        if (playClient.isReady) return
        connectionMutex.withLock {
            if (playClient.isReady) return

            val result = suspendCancellableCoroutine { continuation ->
                playClient.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (continuation.isActive) continuation.resume(result)
                    }

                    // Play reconnects automatically
                    override fun onBillingServiceDisconnected() = Unit
                })
            }
            result.throwIfFailed()
        }
    }

    private fun ProductDetails.toProduct(): Product? {
        val offer = oneTimePurchaseOfferDetails ?: return null
        return Product(
            id = productId,
            type = productType(productId),
            title = name,
            description = description,
            formattedPrice = offer.formattedPrice,
            priceAmountMicros = offer.priceAmountMicros,
            currencyCode = offer.priceCurrencyCode,
        )
    }

    private fun PlayPurchase.toPurchases(): List<Purchase> {
        val state = when (purchaseState) {
            PlayPurchase.PurchaseState.PURCHASED -> PurchaseState.Purchased
            PlayPurchase.PurchaseState.PENDING -> PurchaseState.Pending
            else -> return emptyList()
        }
        return products.map { productId ->
            Purchase(
                id = orderId ?: purchaseToken,
                productId = productId,
                productType = productType(productId),
                state = state,
                quantity = quantity,
                purchaseTimeMillis = purchaseTime,
                verificationData = purchaseToken,
                isFinished = isAcknowledged,
            )
        }
    }

    private fun productType(productId: String) =
        if (productId in consumableProductIds) ProductType.Consumable else ProductType.NonConsumable

    private fun PurchasesUpdate.toPurchaseResult(productId: String): PurchaseResult = when (result.responseCode) {
        Code.OK -> {
            val purchase = purchases.flatMap { it.toPurchases() }.firstOrNull { it.productId == productId }
            when (purchase?.state) {
                PurchaseState.Purchased -> PurchaseResult.Success(purchase)
                PurchaseState.Pending -> PurchaseResult.Pending
                null -> PurchaseResult.Failed(BillingError.Unknown, "Google Play returned no purchase for $productId")
            }
        }

        Code.USER_CANCELED -> PurchaseResult.Cancelled
        Code.ITEM_ALREADY_OWNED -> PurchaseResult.AlreadyOwned
        else -> result.toFailure()
    }

    private data class PurchasesUpdate(val result: BillingResult, val purchases: List<PlayPurchase>)

    private companion object {
        const val UPDATE_BUFFER = 64
    }
}

private fun billingFlowParams(details: ProductDetails): BillingFlowParams = BillingFlowParams.newBuilder()
    .setProductDetailsParamsList(
        listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build())
    )
    .build()

internal fun BillingResult.toError(): BillingError = when (responseCode) {
    Code.SERVICE_DISCONNECTED, Code.SERVICE_UNAVAILABLE, Code.BILLING_UNAVAILABLE -> BillingError.StoreUnavailable
    Code.NETWORK_ERROR -> BillingError.NetworkError
    Code.ITEM_UNAVAILABLE -> BillingError.ProductUnavailable
    Code.DEVELOPER_ERROR, Code.FEATURE_NOT_SUPPORTED -> BillingError.DeveloperError
    else -> BillingError.Unknown
}

private fun BillingResult.toFailure() = PurchaseResult.Failed(toError(), debugMessage.ifEmpty { null })

private fun BillingResult.throwIfFailed() {
    if (responseCode != Code.OK) throw BillingException(toError(), debugMessage.ifEmpty { null })
}
