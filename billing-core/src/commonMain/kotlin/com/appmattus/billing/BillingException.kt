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

public class BillingException(
    public val error: BillingError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: error.name, cause)

public enum class BillingError {
    /** The store isn't available on this device or platform, or couldn't be connected to. */
    StoreUnavailable,

    /** The user isn't allowed to make purchases, for example because of parental controls. */
    NotAllowed,

    /** The product doesn't exist in the store, or can't be bought in the user's region. */
    ProductUnavailable,

    NetworkError,

    /** The store reported the app is set up or called incorrectly. */
    DeveloperError,

    /** The purchase couldn't be verified as coming from the store. */
    VerificationFailed,

    Unknown,
}
