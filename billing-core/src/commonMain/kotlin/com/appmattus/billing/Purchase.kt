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

public data class Purchase(
    /** The store's identifier for this purchase. */
    val id: String,
    val productId: String,
    val productType: ProductType,
    val state: PurchaseState,
    val quantity: Int = 1,
    /** When the purchase was made, in milliseconds since the Unix epoch, when the store provides it. */
    val purchaseTimeMillis: Long? = null,
    /**
     * What a server needs to verify the purchase with the store, when the store provides it: the purchase token
     * on Google Play, the receipt ID on Amazon and the signed transaction (JWS) on StoreKit.
     */
    val verificationData: String? = null,
    /** Whether the purchase is known to have been finished already. */
    val isFinished: Boolean = false,
)

public enum class PurchaseState {
    /** Paid for. Deliver the content, then finish the purchase. */
    Purchased,

    /** Waiting for payment or approval, for example from a parent. Don't deliver the content yet. */
    Pending,
}
