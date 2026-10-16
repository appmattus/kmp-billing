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

import com.appmattus.billing.BillingClient
import com.appmattus.billing.BillingError
import com.appmattus.billing.BillingException
import com.appmattus.billing.Product
import com.appmattus.billing.ProductType
import com.appmattus.billing.Purchase
import com.appmattus.billing.PurchaseHost
import com.appmattus.billing.PurchaseResult
import com.appmattus.billing.PurchaseState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * An in-memory [BillingClient] for tests. Purchases succeed unless [nextPurchaseResult] is set, and behave as the
 * stores do: owned non-consumables can't be bought again, and neither can consumables until they're finished.
 */
public class FakeBillingClient(products: List<Product> = emptyList()) : BillingClient {

    /** The store's catalogue. */
    public val products: MutableList<Product> = products.toMutableList()

    /** What [getPurchases] returns: owned non-consumables and unfinished consumables. */
    public val purchases: MutableList<Purchase> = mutableListOf()

    /** Every purchase passed to [finishPurchase], in order. */
    public val finishedPurchases: MutableList<Purchase> = mutableListOf()

    public var available: Boolean = true

    /** Returned by the next [purchase] call instead of buying the product, then cleared. */
    public var nextPurchaseResult: PurchaseResult? = null

    /** When set, every call apart from [purchase] throws it, as a store does when it's unreachable. */
    public var failure: BillingException? = null

    /** How many times [restorePurchases] has been called. */
    public var restoreCount: Int = 0
        private set

    public var isClosed: Boolean = false
        private set

    private val updates = MutableSharedFlow<Purchase>(extraBufferCapacity = Int.MAX_VALUE)
    private var nextPurchaseId = 1

    override val purchaseUpdates: Flow<Purchase> = updates

    /** Delivers [purchase] through [purchaseUpdates] and adds it to [purchases], as an approved pending purchase is. */
    public fun emitPurchaseUpdate(purchase: Purchase) {
        purchases.removeAll { it.id == purchase.id }
        purchases += purchase
        updates.tryEmit(purchase)
    }

    override suspend fun isAvailable(): Boolean = available

    override suspend fun getProducts(productIds: Set<String>): List<Product> {
        throwFailure()
        return products.filter { it.id in productIds }
    }

    override suspend fun purchase(host: PurchaseHost, productId: String): PurchaseResult {
        nextPurchaseResult?.let { result ->
            nextPurchaseResult = null
            return result
        }

        val product = products.firstOrNull { it.id == productId }
        return when {
            !available -> PurchaseResult.Failed(BillingError.StoreUnavailable)
            product == null -> PurchaseResult.Failed(BillingError.ProductUnavailable)
            purchases.any { it.productId == productId } -> PurchaseResult.AlreadyOwned
            else -> PurchaseResult.Success(buy(product))
        }
    }

    override suspend fun getPurchases(): List<Purchase> {
        throwFailure()
        return purchases.toList()
    }

    override suspend fun restorePurchases(): List<Purchase> {
        throwFailure()
        restoreCount++
        return purchases.toList()
    }

    override suspend fun finishPurchase(purchase: Purchase) {
        throwFailure()
        finishedPurchases += purchase
        if (purchase.productType == ProductType.Consumable) {
            purchases.removeAll { it.id == purchase.id }
        } else {
            val index = purchases.indexOfFirst { it.id == purchase.id }
            if (index >= 0) purchases[index] = purchases[index].copy(isFinished = true)
        }
    }

    override fun close() {
        isClosed = true
    }

    private fun buy(product: Product): Purchase = Purchase(
        id = "fake-purchase-${nextPurchaseId++}",
        productId = product.id,
        productType = product.type,
        state = PurchaseState.Purchased,
    ).also { purchases += it }

    private fun throwFailure() {
        failure?.let { throw it }
    }
}
