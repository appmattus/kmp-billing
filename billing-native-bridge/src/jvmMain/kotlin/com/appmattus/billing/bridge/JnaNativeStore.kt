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
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A [NativeStore] for a bridge library loaded with JNA. [window] gives the native window to purchase over. */
@InternalBillingApi
public class JnaNativeStore(
    libraryFile: File,
    private val window: (PurchaseHost) -> Pointer?,
) : NativeStore {

    private val library = Native.load(
        libraryFile.absolutePath,
        BridgeLibrary::class.java,
        mapOf(Library.OPTION_STRING_ENCODING to Charsets.UTF_8.name()),
    )

    // Held so it isn't garbage collected while the native code can call it
    private var callback: BridgeCallback? = null

    override fun start(callback: (requestId: Long, json: String) -> Unit) {
        val bridgeCallback = BridgeCallback { requestId, json -> callback(requestId, json.getString(0, UTF_8)) }
        this.callback = bridgeCallback
        library.billing_bridge_start(bridgeCallback)
    }

    override fun stop() {
        library.billing_bridge_stop()
        callback = null
    }

    override fun canMakePayments(requestId: Long): Unit = library.billing_bridge_can_make_payments(requestId)

    override fun getProducts(requestId: Long, productIdsJson: String): Unit =
        library.billing_bridge_get_products(requestId, productIdsJson)

    override fun purchase(requestId: Long, productId: String, host: PurchaseHost): Unit =
        library.billing_bridge_purchase(requestId, productId, window(host))

    override fun getPurchases(requestId: Long): Unit = library.billing_bridge_get_purchases(requestId)

    override fun restorePurchases(requestId: Long): Unit = library.billing_bridge_restore_purchases(requestId)

    override fun finishPurchase(requestId: Long, purchaseJson: String): Unit =
        library.billing_bridge_finish_purchase(requestId, purchaseJson)

    public companion object {
        private const val UTF_8 = "UTF-8"

        /**
         * Copies the library bundled at [resourcePath] to a temporary file so it can be loaded, or returns null if
         * it isn't bundled.
         */
        public fun extractLibrary(resourcePath: String, owner: Class<*>): File? {
            val stream = owner.getResourceAsStream(resourcePath) ?: return null
            val file = File.createTempFile("kmp-billing-", "-" + resourcePath.substringAfterLast('/'))
            file.deleteOnExit()
            stream.use { Files.copy(it, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            return file
        }
    }
}

@Suppress("FunctionNaming", "FunctionName")
private interface BridgeLibrary : Library {
    fun billing_bridge_start(callback: BridgeCallback)
    fun billing_bridge_stop()
    fun billing_bridge_can_make_payments(requestId: Long)
    fun billing_bridge_get_products(requestId: Long, productIdsJson: String)
    fun billing_bridge_purchase(requestId: Long, productId: String, window: Pointer?)
    fun billing_bridge_get_purchases(requestId: Long)
    fun billing_bridge_restore_purchases(requestId: Long)
    fun billing_bridge_finish_purchase(requestId: Long, purchaseJson: String)
}

private fun interface BridgeCallback : Callback {
    fun invoke(requestId: Long, json: Pointer)
}
