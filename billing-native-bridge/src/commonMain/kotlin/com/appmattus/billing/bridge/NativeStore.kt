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

import com.appmattus.billing.PurchaseHost

/**
 * A native store bridge's C API, from native/billing_bridge.h. Each call returns straight away and its JSON response
 * arrives through the callback passed to [start].
 */
@InternalBillingApi
public interface NativeStore {
    public fun start(callback: (requestId: Long, json: String) -> Unit)
    public fun stop()
    public fun canMakePayments(requestId: Long)
    public fun getProducts(requestId: Long, productIdsJson: String)
    public fun purchase(requestId: Long, productId: String, host: PurchaseHost)
    public fun getPurchases(requestId: Long)
    public fun restorePurchases(requestId: Long)
    public fun finishPurchase(requestId: Long, purchaseJson: String)
}

/** A [NativeStore] for when the bridge can't run, such as StoreKit on Windows. Every call fails with [message]. */
@InternalBillingApi
public class UnavailableNativeStore(private val message: String) : NativeStore {

    private var callback: ((Long, String) -> Unit)? = null

    override fun start(callback: (requestId: Long, json: String) -> Unit) {
        this.callback = callback
    }

    override fun stop() {
        callback = null
    }

    override fun canMakePayments(requestId: Long): Unit = fail(requestId)
    override fun getProducts(requestId: Long, productIdsJson: String): Unit = fail(requestId)
    override fun purchase(requestId: Long, productId: String, host: PurchaseHost): Unit = fail(requestId)
    override fun getPurchases(requestId: Long): Unit = fail(requestId)
    override fun restorePurchases(requestId: Long): Unit = fail(requestId)
    override fun finishPurchase(requestId: Long, purchaseJson: String): Unit = fail(requestId)

    private fun fail(requestId: Long) {
        callback?.invoke(
            requestId,
            bridgeJson.encodeToString(BridgeResponse(error = BridgeError("storeUnavailable", message)))
        )
    }
}
