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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A [BillingClient] for platforms without a supported store, such as Linux. Nothing can be bought. */
public class UnsupportedBillingClient : BillingClient {

    override val purchaseUpdates: Flow<Purchase> = emptyFlow()

    override suspend fun isAvailable(): Boolean = false

    override suspend fun getProducts(productIds: Set<String>): List<Product> = unavailable()

    override suspend fun purchase(host: PurchaseHost, productId: String): PurchaseResult =
        PurchaseResult.Failed(BillingError.StoreUnavailable, MESSAGE)

    override suspend fun getPurchases(): List<Purchase> = unavailable()

    override suspend fun restorePurchases(): List<Purchase> = unavailable()

    override suspend fun finishPurchase(purchase: Purchase): Unit = unavailable()

    override fun close(): Unit = Unit

    private fun unavailable(): Nothing = throw BillingException(BillingError.StoreUnavailable, MESSAGE)

    private companion object {
        const val MESSAGE = "No store is supported on this platform"
    }
}
