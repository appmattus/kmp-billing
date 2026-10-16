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

// The Windows Store (Windows.Services.Store) implementation of billing-native-bridge/native/billing_bridge.h,
// loaded by JNA on the JVM. Products are identified by their Partner Center product ID, the add-on's
// InAppOfferToken, so they match the other stores' identifiers.

#include <windows.h>
#include <objbase.h>
#include <shobjidl_core.h>

#include <winrt/Windows.Data.Json.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Services.Store.h>

#include <algorithm>
#include <cstdio>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "billing_bridge.h"

using namespace winrt;
using namespace winrt::Windows::Data::Json;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Foundation::Collections;
using namespace winrt::Windows::Services::Store;

namespace {

std::mutex mutex;
billing_bridge_callback callback = nullptr;
StoreContext storeContext{nullptr};

constexpr wchar_t durable[] = L"Durable";

StoreContext context() {
    std::lock_guard lock(mutex);
    if (!storeContext) {
        storeContext = StoreContext::GetDefault();
    }
    return storeContext;
}

IIterable<hstring> productKinds() {
    return single_threaded_vector<hstring>({L"Durable", L"Consumable", L"UnmanagedConsumable"});
}

bool isDurable(StoreProduct const& product) {
    return product.ProductKind() == durable;
}

// MARK: Responses

void send(int64_t requestId, JsonObject const& response) {
    std::string json = to_string(response.Stringify());
    billing_bridge_callback current;
    {
        std::lock_guard lock(mutex);
        current = callback;
    }
    if (current) {
        current(requestId, json.c_str());
    }
}

void reply(int64_t requestId, IJsonValue const& result) {
    JsonObject response;
    if (result) {
        response.Insert(L"result", result);
    }
    send(requestId, response);
}

void fail(int64_t requestId, hstring const& code, hstring const& message) {
    JsonObject error;
    error.Insert(L"code", JsonValue::CreateStringValue(code));
    error.Insert(L"message", JsonValue::CreateStringValue(message));
    JsonObject response;
    response.Insert(L"error", error);
    send(requestId, response);
}

hstring describe(hresult const& code) {
    wchar_t text[32];
    swprintf_s(text, L"0x%08X", static_cast<uint32_t>(code.value));
    return hstring(L"Windows Store error ") + text;
}

hstring errorCode(hresult const& code) {
    switch (static_cast<uint32_t>(code.value)) {
        case 0x80072EE2:  // WININET_E_TIMEOUT
        case 0x80072EE7:  // WININET_E_NAME_NOT_RESOLVED
        case 0x80072EFD:  // WININET_E_CANNOT_CONNECT
        case 0x80072EFE:  // WININET_E_CONNECTION_ABORTED
            return L"networkError";
        default:
            return L"unknown";
    }
}

void failWithCode(int64_t requestId, hresult const& code) {
    fail(requestId, errorCode(code), describe(code));
}

void failWithError(int64_t requestId, hresult_error const& error) {
    fail(requestId, errorCode(error.code()), error.message().empty() ? describe(error.code()) : error.message());
}

// MARK: JSON

JsonObject productJson(StoreProduct const& product) {
    JsonObject json;
    json.Insert(L"id", JsonValue::CreateStringValue(product.InAppOfferToken()));
    json.Insert(L"type", JsonValue::CreateStringValue(isDurable(product) ? L"nonConsumable" : L"consumable"));
    json.Insert(L"title", JsonValue::CreateStringValue(product.Title()));
    json.Insert(L"description", JsonValue::CreateStringValue(product.Description()));
    json.Insert(L"formattedPrice", JsonValue::CreateStringValue(product.Price().FormattedPrice()));
    json.Insert(L"currencyCode", JsonValue::CreateStringValue(product.Price().CurrencyCode()));
    return json;
}

// The Windows Store has no transaction IDs, so a purchase is identified by the add-on's Store ID. Durables need no
// finishing, so they're always finished.
JsonObject purchaseJson(StoreProduct const& product, uint32_t quantity) {
    bool durableProduct = isDurable(product);
    JsonObject json;
    json.Insert(L"id", JsonValue::CreateStringValue(product.StoreId()));
    json.Insert(L"productId", JsonValue::CreateStringValue(product.InAppOfferToken()));
    json.Insert(L"productType", JsonValue::CreateStringValue(durableProduct ? L"nonConsumable" : L"consumable"));
    json.Insert(L"state", JsonValue::CreateStringValue(L"purchased"));
    json.Insert(L"quantity", JsonValue::CreateNumberValue(quantity));
    json.Insert(L"isFinished", JsonValue::CreateBooleanValue(durableProduct));
    return json;
}

JsonObject purchaseResult(hstring const& status, std::optional<JsonObject> const& purchased = std::nullopt) {
    JsonObject json;
    json.Insert(L"status", JsonValue::CreateStringValue(status));
    if (purchased) {
        json.Insert(L"purchase", *purchased);
    }
    return json;
}

// MARK: Store calls

IAsyncOperation<StoreProductQueryResult> associatedProducts() {
    StoreProductQueryResult result = co_await context().GetAssociatedStoreProductsAsync(productKinds());
    check_hresult(result.ExtendedError());
    co_return result;
}

IAsyncOperation<StoreProduct> findProduct(hstring productId) {
    StoreProductQueryResult result = co_await associatedProducts();
    for (auto const& entry : result.Products()) {
        if (entry.Value().InAppOfferToken() == productId) {
            co_return entry.Value();
        }
    }
    co_return nullptr;
}

/** Owned durables and unfulfilled consumables. */
IAsyncOperation<JsonArray> purchases() {
    StoreContext store = context();
    StoreProductQueryResult collection = co_await store.GetUserCollectionAsync(productKinds());
    check_hresult(collection.ExtendedError());

    JsonArray result;
    for (auto const& entry : collection.Products()) {
        StoreProduct product = entry.Value();
        uint32_t quantity = 1;
        if (product.ProductKind() == L"Consumable") {
            // Store-managed consumables track how many haven't been fulfilled
            StoreConsumableResult balance = co_await store.GetConsumableBalanceRemainingAsync(product.StoreId());
            if (balance.Status() != StoreConsumableStatus::Succeeded || balance.BalanceRemaining() == 0) {
                continue;
            }
            quantity = balance.BalanceRemaining();
        }
        result.Append(purchaseJson(product, quantity));
    }
    co_return result;
}

fire_and_forget canMakePayments(int64_t requestId) {
    co_await resume_background();
    try {
        // Fails when the app isn't installed from the Store or has no package identity
        StoreAppLicense license = co_await context().GetAppLicenseAsync();
        reply(requestId, JsonValue::CreateBooleanValue(license != nullptr));
    } catch (hresult_error const&) {
        reply(requestId, JsonValue::CreateBooleanValue(false));
    }
}

fire_and_forget getProducts(int64_t requestId, std::vector<hstring> productIds) {
    co_await resume_background();
    try {
        StoreProductQueryResult result = co_await associatedProducts();
        JsonArray products;
        for (auto const& entry : result.Products()) {
            StoreProduct product = entry.Value();
            if (std::find(productIds.begin(), productIds.end(), product.InAppOfferToken()) != productIds.end()) {
                products.Append(productJson(product));
            }
        }
        reply(requestId, products);
    } catch (hresult_error const& error) {
        failWithError(requestId, error);
    }
}

fire_and_forget purchase(int64_t requestId, hstring productId, HWND window) {
    co_await resume_background();
    try {
        StoreContext store = context();
        if (window) {
            // Desktop apps have no CoreWindow, so the purchase dialog needs an owner window. A context that's
            // already been given a window refuses another, which is harmless.
            store.as<IInitializeWithWindow>()->Initialize(window);
        }

        StoreProduct product = co_await findProduct(productId);
        if (!product) {
            fail(requestId, L"productUnavailable", L"Unknown product " + productId);
            co_return;
        }

        StorePurchaseResult result = co_await product.RequestPurchaseAsync();
        switch (result.Status()) {
            case StorePurchaseStatus::Succeeded:
                reply(requestId, purchaseResult(L"success", purchaseJson(product, 1)));
                break;
            case StorePurchaseStatus::AlreadyPurchased:
                reply(requestId, purchaseResult(L"alreadyOwned"));
                break;
            case StorePurchaseStatus::NotPurchased:
                reply(requestId, purchaseResult(L"cancelled"));
                break;
            case StorePurchaseStatus::NetworkError:
                fail(requestId, L"networkError", describe(result.ExtendedError()));
                break;
            default:
                failWithCode(requestId, result.ExtendedError());
                break;
        }
    } catch (hresult_error const& error) {
        failWithError(requestId, error);
    }
}

fire_and_forget getPurchases(int64_t requestId) {
    co_await resume_background();
    try {
        reply(requestId, co_await purchases());
    } catch (hresult_error const& error) {
        failWithError(requestId, error);
    }
}

fire_and_forget finishPurchase(int64_t requestId, hstring storeId, hstring productType, uint32_t quantity) {
    co_await resume_background();
    try {
        if (productType == L"nonConsumable") {
            // Durables need no fulfilment
            reply(requestId, nullptr);
            co_return;
        }

        GUID trackingId;
        check_hresult(CoCreateGuid(&trackingId));
        StoreConsumableResult result =
            co_await context().ReportConsumableFulfillmentAsync(storeId, quantity, guid(trackingId));
        switch (result.Status()) {
            case StoreConsumableStatus::Succeeded:
            // Already fulfilled
            case StoreConsumableStatus::InsufficentQuantity:
                reply(requestId, nullptr);
                break;
            case StoreConsumableStatus::NetworkError:
                fail(requestId, L"networkError", describe(result.ExtendedError()));
                break;
            default:
                failWithCode(requestId, result.ExtendedError());
                break;
        }
    } catch (hresult_error const& error) {
        failWithError(requestId, error);
    }
}

}  // namespace

// MARK: C API

extern "C" {

BILLING_BRIDGE_EXPORT void billing_bridge_start(billing_bridge_callback newCallback) {
    std::lock_guard lock(mutex);
    callback = newCallback;
}

BILLING_BRIDGE_EXPORT void billing_bridge_stop(void) {
    std::lock_guard lock(mutex);
    callback = nullptr;
}

BILLING_BRIDGE_EXPORT void billing_bridge_can_make_payments(int64_t request_id) {
    canMakePayments(request_id);
}

BILLING_BRIDGE_EXPORT void billing_bridge_get_products(int64_t request_id, const char *product_ids_json) {
    try {
        std::vector<hstring> productIds;
        for (auto const& value : JsonArray::Parse(to_hstring(std::string_view(product_ids_json)))) {
            productIds.push_back(value.GetString());
        }
        getProducts(request_id, std::move(productIds));
    } catch (hresult_error const& error) {
        fail(request_id, L"developerError", error.message());
    }
}

BILLING_BRIDGE_EXPORT void billing_bridge_purchase(int64_t request_id, const char *product_id, void *window) {
    purchase(request_id, to_hstring(std::string_view(product_id)), static_cast<HWND>(window));
}

BILLING_BRIDGE_EXPORT void billing_bridge_get_purchases(int64_t request_id) {
    getPurchases(request_id);
}

// The Windows Store keeps licences in sync itself
BILLING_BRIDGE_EXPORT void billing_bridge_restore_purchases(int64_t request_id) {
    getPurchases(request_id);
}

BILLING_BRIDGE_EXPORT void billing_bridge_finish_purchase(int64_t request_id, const char *purchase_json) {
    try {
        JsonObject json = JsonObject::Parse(to_hstring(std::string_view(purchase_json)));
        finishPurchase(
            request_id,
            json.GetNamedString(L"id"),
            json.GetNamedString(L"productType"),
            static_cast<uint32_t>(json.GetNamedNumber(L"quantity", 1)));
    } catch (hresult_error const& error) {
        fail(request_id, L"developerError", error.message());
    }
}

}  // extern "C"
