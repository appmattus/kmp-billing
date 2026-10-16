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

public sealed interface PurchaseResult {

    /** Deliver the purchase's content, then finish it with [BillingClient.finishPurchase]. */
    public data class Success(val purchase: Purchase) : PurchaseResult

    /**
     * The purchase is waiting for payment or approval. When it completes it arrives through
     * [BillingClient.purchaseUpdates].
     */
    public data object Pending : PurchaseResult

    /**
     * The user closed the purchase flow. Amazon reports cancelling and failing the same way, so both arrive here
     * on Amazon.
     */
    public data object Cancelled : PurchaseResult

    /** The user already owns this non-consumable, or hasn't finished an earlier purchase of this consumable. */
    public data object AlreadyOwned : PurchaseResult

    public data class Failed(val error: BillingError, val message: String? = null) : PurchaseResult
}
