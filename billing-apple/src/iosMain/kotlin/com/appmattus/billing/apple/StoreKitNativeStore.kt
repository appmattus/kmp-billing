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

package com.appmattus.billing.apple

import com.appmattus.billing.PurchaseHost
import com.appmattus.billing.apple.storekit.billing_bridge_can_make_payments
import com.appmattus.billing.apple.storekit.billing_bridge_finish_purchase
import com.appmattus.billing.apple.storekit.billing_bridge_get_products
import com.appmattus.billing.apple.storekit.billing_bridge_get_purchases
import com.appmattus.billing.apple.storekit.billing_bridge_purchase
import com.appmattus.billing.apple.storekit.billing_bridge_restore_purchases
import com.appmattus.billing.apple.storekit.billing_bridge_start
import com.appmattus.billing.apple.storekit.billing_bridge_stop
import com.appmattus.billing.bridge.NativeStore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlin.concurrent.Volatile

internal actual fun storeKitNativeStore(): NativeStore = StoreKitNativeStore

// The Swift bridge has one callback, which C can only call as a top-level function
@Volatile
private var bridgeCallback: ((Long, String) -> Unit)? = null

@OptIn(ExperimentalForeignApi::class)
private fun onResponse(requestId: Long, json: CPointer<ByteVar>?) {
    json?.let { bridgeCallback?.invoke(requestId, it.toKString()) }
}

/** The Swift bridge, linked statically. */
@OptIn(ExperimentalForeignApi::class)
private object StoreKitNativeStore : NativeStore {

    override fun start(callback: (requestId: Long, json: String) -> Unit) {
        bridgeCallback = callback
        billing_bridge_start(staticCFunction(::onResponse))
    }

    override fun stop() {
        billing_bridge_stop()
        bridgeCallback = null
    }

    override fun canMakePayments(requestId: Long) = billing_bridge_can_make_payments(requestId)

    override fun getProducts(requestId: Long, productIdsJson: String) =
        billing_bridge_get_products(requestId, productIdsJson)

    // StoreKit finds the scene to show the purchase in itself
    override fun purchase(requestId: Long, productId: String, host: PurchaseHost) =
        billing_bridge_purchase(requestId, productId, null)

    override fun getPurchases(requestId: Long) = billing_bridge_get_purchases(requestId)

    override fun restorePurchases(requestId: Long) = billing_bridge_restore_purchases(requestId)

    override fun finishPurchase(requestId: Long, purchaseJson: String) =
        billing_bridge_finish_purchase(requestId, purchaseJson)
}
