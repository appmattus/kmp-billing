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

/** A product as listed in the store. */
public data class Product(
    /** The identifier the product was created with in the store. */
    val id: String,
    val type: ProductType,
    val title: String,
    val description: String,
    /** The price, formatted for display in the user's currency. */
    val formattedPrice: String,
    /** The price in millionths of [currencyCode], when the store provides it. */
    val priceAmountMicros: Long? = null,
    /** The ISO 4217 currency code, when the store provides it. */
    val currencyCode: String? = null,
)

public enum class ProductType {
    /** Bought once and owned forever, such as unlocking a level pack. */
    NonConsumable,

    /** Used up once delivered and can be bought again, such as in-game currency. */
    Consumable,
}
