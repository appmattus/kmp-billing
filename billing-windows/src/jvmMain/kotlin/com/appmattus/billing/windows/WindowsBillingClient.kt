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

package com.appmattus.billing.windows

import com.appmattus.billing.BillingClient
import com.appmattus.billing.bridge.JnaNativeStore
import com.appmattus.billing.bridge.NativeBridgeBillingClient
import com.appmattus.billing.bridge.NativeStore
import com.appmattus.billing.bridge.UnavailableNativeStore
import com.sun.jna.Native

/**
 * The Windows Store (`Windows.Services.Store`), for desktop apps on Windows 10 and later, x64 or ARM64. Elsewhere
 * every call fails with [com.appmattus.billing.BillingError.StoreUnavailable].
 *
 * The app must be packaged (MSIX) and associated with its Store listing. Products are identified by the add-on's
 * product ID from Partner Center. The Windows Store has no transaction IDs, so [com.appmattus.billing.Purchase.id]
 * is the add-on's Store ID and there's no verification data.
 *
 * Pass the app's window in the [com.appmattus.billing.PurchaseHost] so the purchase dialog has an owner.
 */
public class WindowsBillingClient private constructor(client: BillingClient) : BillingClient by client {
    public constructor() : this(NativeBridgeBillingClient(windowsStoreNativeStore))
}

// Loaded once, as the bridge has a single callback
private val windowsStoreNativeStore: NativeStore by lazy {
    if (!System.getProperty("os.name").startsWith("Windows")) {
        return@lazy UnavailableNativeStore("The Windows Store is only available on Windows")
    }
    val resourceDir = when (System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "win32-x86-64"
        "aarch64", "arm64" -> "win32-aarch64"
        else -> return@lazy UnavailableNativeStore("The Windows Store bridge supports x64 and ARM64")
    }
    val library = JnaNativeStore.extractLibrary(
        "/com/appmattus/billing/windows/$resourceDir/WindowsStoreBridge.dll",
        WindowsBillingClient::class.java,
    ) ?: return@lazy UnavailableNativeStore("The Windows Store bridge isn't bundled; it's built on Windows")

    JnaNativeStore(library, window = { host -> host.window?.let(Native::getWindowPointer) })
}
