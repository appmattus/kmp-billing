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

import com.appmattus.billing.BillingError
import com.appmattus.billing.Product
import com.appmattus.billing.ProductType
import com.appmattus.billing.Purchase
import com.appmattus.billing.PurchaseState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// The JSON described in native/billing_bridge.h

internal val bridgeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
internal data class BridgeResponse(
    val result: JsonElement? = null,
    val error: BridgeError? = null,
)

@Serializable
internal data class BridgeError(val code: String, val message: String? = null) {
    fun toBillingError(): BillingError = when (code) {
        "storeUnavailable" -> BillingError.StoreUnavailable
        "notAllowed" -> BillingError.NotAllowed
        "productUnavailable" -> BillingError.ProductUnavailable
        "networkError" -> BillingError.NetworkError
        "developerError" -> BillingError.DeveloperError
        "verificationFailed" -> BillingError.VerificationFailed
        else -> BillingError.Unknown
    }
}

@Serializable
internal enum class BridgeProductType(val productType: ProductType) {
    @SerialName("consumable")
    Consumable(ProductType.Consumable),

    @SerialName("nonConsumable")
    NonConsumable(ProductType.NonConsumable),
    ;

    companion object {
        fun of(productType: ProductType) = entries.first { it.productType == productType }
    }
}

@Serializable
internal data class BridgeProduct(
    val id: String,
    val type: BridgeProductType,
    val title: String,
    val description: String,
    val formattedPrice: String,
    val priceAmountMicros: Long? = null,
    val currencyCode: String? = null,
) {
    fun toProduct() = Product(id, type.productType, title, description, formattedPrice, priceAmountMicros, currencyCode)
}

@Serializable
internal enum class BridgePurchaseState(val state: PurchaseState) {
    @SerialName("purchased")
    Purchased(PurchaseState.Purchased),

    @SerialName("pending")
    Pending(PurchaseState.Pending),
    ;

    companion object {
        fun of(state: PurchaseState) = entries.first { it.state == state }
    }
}

@Serializable
internal data class BridgePurchase(
    val id: String,
    val productId: String,
    val productType: BridgeProductType,
    val state: BridgePurchaseState,
    val quantity: Int = 1,
    val purchaseTimeMillis: Long? = null,
    val verificationData: String? = null,
    val isFinished: Boolean = false,
) {
    fun toPurchase() = Purchase(
        id = id,
        productId = productId,
        productType = productType.productType,
        state = state.state,
        quantity = quantity,
        purchaseTimeMillis = purchaseTimeMillis,
        verificationData = verificationData,
        isFinished = isFinished,
    )

    companion object {
        fun of(purchase: Purchase) = BridgePurchase(
            id = purchase.id,
            productId = purchase.productId,
            productType = BridgeProductType.of(purchase.productType),
            state = BridgePurchaseState.of(purchase.state),
            quantity = purchase.quantity,
            purchaseTimeMillis = purchase.purchaseTimeMillis,
            verificationData = purchase.verificationData,
            isFinished = purchase.isFinished,
        )
    }
}

@Serializable
internal data class BridgePurchaseResult(
    val status: String,
    val purchase: BridgePurchase? = null,
)
