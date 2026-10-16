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

import com.appmattus.billing.BillingClient
import com.appmattus.billing.BillingError
import com.appmattus.billing.BillingException
import com.appmattus.billing.Product
import com.appmattus.billing.Purchase
import com.appmattus.billing.PurchaseHost
import com.appmattus.billing.PurchaseResult
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

/** A [BillingClient] for a native store bridge, which talks the JSON protocol in native/billing_bridge.h. */
@InternalBillingApi
public class NativeBridgeBillingClient(private val store: NativeStore) : BillingClient {

    private val lock = SynchronizedObject()
    private val requests = mutableMapOf<Long, CompletableDeferred<String>>()
    private var lastRequestId = 0L
    private val updates = MutableSharedFlow<Purchase>(extraBufferCapacity = UPDATE_BUFFER)

    override val purchaseUpdates: Flow<Purchase> = updates

    init {
        store.start(::onResponse)
    }

    override suspend fun isAvailable(): Boolean = try {
        decode<Boolean>(request { store.canMakePayments(it) })
    } catch (_: BillingException) {
        false
    }

    override suspend fun getProducts(productIds: Set<String>): List<Product> {
        if (productIds.isEmpty()) return emptyList()
        val ids = bridgeJson.encodeToString(productIds.toList())
        return decode<List<BridgeProduct>>(request { store.getProducts(it, ids) }).map { it.toProduct() }
    }

    override suspend fun purchase(host: PurchaseHost, productId: String): PurchaseResult = try {
        val result = decode<BridgePurchaseResult>(request { store.purchase(it, productId, host) })
        when (result.status) {
            "success" -> result.purchase?.let { PurchaseResult.Success(it.toPurchase()) }
                ?: PurchaseResult.Failed(BillingError.Unknown, "The store returned no purchase")

            "pending" -> PurchaseResult.Pending
            "cancelled" -> PurchaseResult.Cancelled
            "alreadyOwned" -> PurchaseResult.AlreadyOwned
            else -> PurchaseResult.Failed(BillingError.Unknown, "Unknown purchase status ${result.status}")
        }
    } catch (exception: BillingException) {
        PurchaseResult.Failed(exception.error, exception.message)
    }

    override suspend fun getPurchases(): List<Purchase> =
        decode<List<BridgePurchase>>(request { store.getPurchases(it) }).map { it.toPurchase() }

    override suspend fun restorePurchases(): List<Purchase> =
        decode<List<BridgePurchase>>(request { store.restorePurchases(it) }).map { it.toPurchase() }

    override suspend fun finishPurchase(purchase: Purchase) {
        val json = bridgeJson.encodeToString(BridgePurchase.of(purchase))
        request { store.finishPurchase(it, json) }
    }

    override fun close() {
        store.stop()
        val pending = synchronized(lock) { requests.values.toList().also { requests.clear() } }
        pending.forEach { it.completeExceptionally(BillingException(BillingError.StoreUnavailable, "Closed")) }
    }

    /** Starts a request and returns its result, throwing [BillingException] if the store reports an error. */
    private suspend fun request(start: (requestId: Long) -> Unit): JsonElement {
        val deferred = CompletableDeferred<String>()
        val requestId = synchronized(lock) {
            (++lastRequestId).also { requests[it] = deferred }
        }
        try {
            start(requestId)
            val response = try {
                bridgeJson.decodeFromString<BridgeResponse>(deferred.await())
            } catch (exception: SerializationException) {
                throw BillingException(BillingError.Unknown, "Invalid response from the store", exception)
            }
            response.error?.let { throw BillingException(it.toBillingError(), it.message) }
            return response.result ?: JsonNull
        } finally {
            synchronized(lock) { requests.remove(requestId) }
        }
    }

    private fun onResponse(requestId: Long, json: String) {
        if (requestId == UPDATE_REQUEST_ID) {
            onPurchaseUpdate(json)
        } else {
            synchronized(lock) { requests.remove(requestId) }?.complete(json)
        }
    }

    private fun onPurchaseUpdate(json: String) {
        val purchase = try {
            bridgeJson.decodeFromString<BridgeResponse>(json).result
                ?.let { bridgeJson.decodeFromJsonElement<BridgePurchase>(it) }
        } catch (_: SerializationException) {
            null
        }
        purchase?.let { updates.tryEmit(it.toPurchase()) }
    }

    private inline fun <reified T> decode(element: JsonElement): T = try {
        bridgeJson.decodeFromJsonElement(element)
    } catch (exception: SerializationException) {
        throw BillingException(BillingError.Unknown, "Invalid response from the store", exception)
    }

    private companion object {
        const val UPDATE_REQUEST_ID = 0L
        const val UPDATE_BUFFER = 64
    }
}
