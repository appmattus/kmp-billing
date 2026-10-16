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

package com.appmattus.billing.amazon

import android.content.Context
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.RequestId

/** The Appstore SDK's static [PurchasingService], behind an interface so it can be replaced in tests. */
internal interface AmazonPurchasing {
    fun registerListener(listener: PurchasingListener)
    fun getUserData(): RequestId
    fun getProductData(skus: Set<String>): RequestId
    fun purchase(sku: String): RequestId
    fun getPurchaseUpdates(reset: Boolean): RequestId
    fun notifyFulfillment(receiptId: String, result: FulfillmentResult)
}

internal class AppstorePurchasing(private val context: Context) : AmazonPurchasing {

    override fun registerListener(listener: PurchasingListener) {
        PurchasingService.enablePendingPurchases()
        PurchasingService.registerListener(context, listener)
    }

    override fun getUserData(): RequestId = PurchasingService.getUserData()

    override fun getProductData(skus: Set<String>): RequestId = PurchasingService.getProductData(skus)

    override fun purchase(sku: String): RequestId = PurchasingService.purchase(sku)

    override fun getPurchaseUpdates(reset: Boolean): RequestId = PurchasingService.getPurchaseUpdates(reset)

    override fun notifyFulfillment(receiptId: String, result: FulfillmentResult) =
        PurchasingService.notifyFulfillment(receiptId, result)
}
