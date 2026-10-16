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

/** Records each call and answers it with [respond], as a native bridge would on its callback. */
class FakeNativeStore : NativeStore {

    val calls = mutableListOf<String>()
    var isStarted = false
        private set

    /** Returns the JSON response for a call, or null to leave it unanswered. */
    var respond: (call: String) -> String? = { """{"result":null}""" }

    private var callback: ((Long, String) -> Unit)? = null

    /** Sends [json] to the client as the response to [requestId]. */
    fun reply(requestId: Long, json: String) {
        callback?.invoke(requestId, json)
    }

    /** Sends [json] to the client as a purchase update. */
    fun sendUpdate(json: String) = reply(requestId = 0, json = json)

    override fun start(callback: (requestId: Long, json: String) -> Unit) {
        this.callback = callback
        isStarted = true
    }

    override fun stop() {
        callback = null
        isStarted = false
    }

    override fun canMakePayments(requestId: Long) = call(requestId, "canMakePayments")

    override fun getProducts(requestId: Long, productIdsJson: String) = call(requestId, "getProducts $productIdsJson")

    override fun purchase(requestId: Long, productId: String, host: PurchaseHost) = call(
        requestId,
        "purchase $productId"
    )

    override fun getPurchases(requestId: Long) = call(requestId, "getPurchases")

    override fun restorePurchases(requestId: Long) = call(requestId, "restorePurchases")

    override fun finishPurchase(requestId: Long, purchaseJson: String) = call(requestId, "finishPurchase $purchaseJson")

    private fun call(requestId: Long, call: String) {
        calls += call
        respond(call)?.let { callback?.invoke(requestId, it) }
    }
}
