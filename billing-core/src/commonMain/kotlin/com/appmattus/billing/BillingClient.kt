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

/**
 * A store's in-app purchases, behind one API for every platform.
 *
 * Every call that fails throws [BillingException], apart from [purchase], which reports failures in its
 * [PurchaseResult] as cancelling or deferring a purchase is an expected outcome.
 *
 * A purchase stays unfinished until [finishPurchase] is called once its content has been delivered. Stores refund
 * or redeliver purchases that are never finished, so deliver and finish purchases from [purchase],
 * [purchaseUpdates] and [getPurchases] alike.
 */
public interface BillingClient : AutoCloseable {

    /**
     * Purchases that complete outside [purchase], such as a pending purchase that is later approved, or one made
     * on another device or in the store app.
     */
    public val purchaseUpdates: Flow<Purchase>

    /** Whether the store is available and this user is allowed to make purchases. */
    public suspend fun isAvailable(): Boolean

    /** The products with the given identifiers. Identifiers the store doesn't know are left out. */
    public suspend fun getProducts(productIds: Set<String>): List<Product>

    /** Shows the store's purchase flow for [productId] over [host]. */
    public suspend fun purchase(host: PurchaseHost, productId: String): PurchaseResult

    /** The user's owned non-consumables, plus any consumables that haven't been finished. */
    public suspend fun getPurchases(): List<Purchase>

    /**
     * Syncs purchases with the store, then returns them as [getPurchases] does. Call this when the user asks to
     * restore purchases, as on some stores it asks the user to sign in.
     */
    public suspend fun restorePurchases(): List<Purchase>

    /**
     * Tells the store the purchase's content has been delivered, consuming a consumable so it can be bought again.
     * Finishing a purchase more than once has no effect.
     */
    public suspend fun finishPurchase(purchase: Purchase)
}
