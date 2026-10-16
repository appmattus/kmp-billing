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

import com.appmattus.billing.bridge.JnaNativeStore
import com.appmattus.billing.bridge.NativeStore
import com.appmattus.billing.bridge.UnavailableNativeStore

private const val LIBRARY = "/com/appmattus/billing/apple/libStoreKitBridge.dylib"

internal actual fun storeKitNativeStore(): NativeStore = storeKitStore

// Loaded once, as each copy of the library would register the same Objective-C classes
private val storeKitStore: NativeStore by lazy {
    if (!System.getProperty("os.name").startsWith("Mac")) {
        return@lazy UnavailableNativeStore("StoreKit is only available on macOS")
    }
    val library = JnaNativeStore.extractLibrary(LIBRARY, AppleBillingClient::class.java)
        ?: return@lazy UnavailableNativeStore("The StoreKit bridge isn't bundled; it's built on macOS")

    // StoreKit finds the window to show the purchase in itself
    JnaNativeStore(library, window = { null })
}
