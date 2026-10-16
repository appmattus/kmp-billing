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

import com.appmattus.billing.BillingClient
import com.appmattus.billing.bridge.NativeBridgeBillingClient
import com.appmattus.billing.bridge.NativeStore

/**
 * StoreKit 2, for iOS 16+ and for desktop apps on macOS 13+. On the JVM it only works on macOS; elsewhere every
 * call fails with [com.appmattus.billing.BillingError.StoreUnavailable].
 *
 * StoreKit shares one transaction listener per app, so create a single instance. [com.appmattus.billing.Purchase.id]
 * is the transaction ID and [com.appmattus.billing.Purchase.verificationData] the signed transaction (JWS).
 */
public class AppleBillingClient private constructor(client: BillingClient) : BillingClient by client {
    public constructor() : this(NativeBridgeBillingClient(storeKitNativeStore()))
}

internal expect fun storeKitNativeStore(): NativeStore
