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

/*
 * The C API each native store bridge implements: StoreKit (Swift) for iOS and macOS, and the Windows Store
 * (C++/WinRT) for Windows. Kotlin calls it through cinterop on iOS and JNA on the JVM.
 *
 * Every call starts a request and returns straight away. Its response arrives later, on any thread, through the
 * callback passed to billing_bridge_start, as UTF-8 JSON with the call's request ID:
 *
 *   {"result": <value>}                                   on success; "result" is left out when there's no value
 *   {"error": {"code": "<code>", "message": "<text>"}}    on failure
 *
 * Error codes: storeUnavailable, notAllowed, productUnavailable, networkError, developerError,
 * verificationFailed and unknown.
 *
 * Purchases that complete outside billing_bridge_purchase arrive with request ID 0 and a purchase as the result.
 *
 * Values:
 *   product:  {"id", "type": "consumable" | "nonConsumable", "title", "description", "formattedPrice",
 *              "priceAmountMicros" (optional), "currencyCode" (optional)}
 *   purchase: {"id", "productId", "productType": "consumable" | "nonConsumable", "state": "purchased" | "pending",
 *              "quantity", "purchaseTimeMillis" (optional), "verificationData" (optional), "isFinished"}
 *   purchase result: {"status": "success", "purchase": <purchase>} or {"status": "pending" | "cancelled" |
 *                    "alreadyOwned"}
 */
#ifndef BILLING_BRIDGE_H
#define BILLING_BRIDGE_H

#include <stdint.h>

#if defined(_WIN32)
#define BILLING_BRIDGE_EXPORT __declspec(dllexport)
#else
#define BILLING_BRIDGE_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*billing_bridge_callback)(int64_t request_id, const char *json);

/** Sets the callback for every response and starts listening for purchase updates. */
BILLING_BRIDGE_EXPORT void billing_bridge_start(billing_bridge_callback callback);

/** Stops listening for purchase updates and drops the callback. */
BILLING_BRIDGE_EXPORT void billing_bridge_stop(void);

/** Result: true if the user is allowed to make purchases. */
BILLING_BRIDGE_EXPORT void billing_bridge_can_make_payments(int64_t request_id);

/** product_ids_json is a JSON array of product identifiers. Result: an array of products. */
BILLING_BRIDGE_EXPORT void billing_bridge_get_products(int64_t request_id, const char *product_ids_json);

/** window is the native window to show the purchase over (HWND on Windows), or NULL. Result: a purchase result. */
BILLING_BRIDGE_EXPORT void billing_bridge_purchase(int64_t request_id, const char *product_id, void *window);

/** Result: an array of purchases: owned non-consumables and unfinished consumables. */
BILLING_BRIDGE_EXPORT void billing_bridge_get_purchases(int64_t request_id);

/** Syncs with the store first, which can ask the user to sign in. Result: an array of purchases. */
BILLING_BRIDGE_EXPORT void billing_bridge_restore_purchases(int64_t request_id);

/** purchase_json is a purchase, as returned by the bridge. No result. */
BILLING_BRIDGE_EXPORT void billing_bridge_finish_purchase(int64_t request_id, const char *purchase_json);

#ifdef __cplusplus
}
#endif

#endif /* BILLING_BRIDGE_H */
