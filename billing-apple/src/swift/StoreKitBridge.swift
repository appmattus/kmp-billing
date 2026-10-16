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

// The StoreKit 2 implementation of billing-native-bridge/native/billing_bridge.h, shared by iOS (linked statically
// through Kotlin/Native cinterop) and macOS (loaded as a dynamic library by JNA on the JVM).

import Foundation
import StoreKit

public typealias BillingBridgeCallback = @convention(c) (Int64, UnsafePointer<CChar>) -> Void

// MARK: - C API

@_cdecl("billing_bridge_start")
public func billingBridgeStart(_ callback: BillingBridgeCallback) {
    Bridge.shared.start(callback)
}

@_cdecl("billing_bridge_stop")
public func billingBridgeStop() {
    Bridge.shared.stop()
}

@_cdecl("billing_bridge_can_make_payments")
public func billingBridgeCanMakePayments(_ requestId: Int64) {
    Bridge.shared.reply(requestId) { AppStore.canMakePayments }
}

@_cdecl("billing_bridge_get_products")
public func billingBridgeGetProducts(_ requestId: Int64, _ productIdsJson: UnsafePointer<CChar>) {
    let json = String(cString: productIdsJson)
    Bridge.shared.reply(requestId) {
        let ids = try JSONDecoder().decode([String].self, from: Data(json.utf8))
        return try await Product.products(for: ids).compactMap(BridgeProduct.init)
    }
}

@_cdecl("billing_bridge_purchase")
public func billingBridgePurchase(_ requestId: Int64, _ productId: UnsafePointer<CChar>, _ window: UnsafeMutableRawPointer?) {
    let id = String(cString: productId)
    Bridge.shared.reply(requestId) { try await Bridge.shared.purchase(id) }
}

@_cdecl("billing_bridge_get_purchases")
public func billingBridgeGetPurchases(_ requestId: Int64) {
    Bridge.shared.reply(requestId) { await Bridge.shared.purchases() }
}

@_cdecl("billing_bridge_restore_purchases")
public func billingBridgeRestorePurchases(_ requestId: Int64) {
    Bridge.shared.reply(requestId) {
        try await AppStore.sync()
        return await Bridge.shared.purchases()
    }
}

@_cdecl("billing_bridge_finish_purchase")
public func billingBridgeFinishPurchase(_ requestId: Int64, _ purchaseJson: UnsafePointer<CChar>) {
    let json = String(cString: purchaseJson)
    Bridge.shared.reply(requestId) {
        let purchase = try JSONDecoder().decode(BridgePurchase.self, from: Data(json.utf8))
        try await Bridge.shared.finish(purchase)
        return Optional<Bool>.none
    }
}

// MARK: - Bridge

final class Bridge: @unchecked Sendable {
    static let shared = Bridge()

    private let lock = NSLock()
    private var callback: BillingBridgeCallback?
    private var updates: Task<Void, Never>?

    func start(_ callback: @escaping BillingBridgeCallback) {
        lock.lock()
        self.callback = callback
        updates?.cancel()
        updates = Task.detached { [weak self] in
            // Transactions that complete outside purchase(), such as Ask to Buy approvals and purchases on other devices
            for await verification in Transaction.updates {
                guard let self, case .verified(let transaction) = verification else { continue }
                if let purchase = BridgePurchase(transaction, jws: verification.jwsRepresentation, isFinished: false) {
                    self.send(0, Response(result: purchase, error: nil))
                }
            }
        }
        lock.unlock()
    }

    func stop() {
        lock.lock()
        updates?.cancel()
        updates = nil
        callback = nil
        lock.unlock()
    }

    /** Runs [body] off the caller's thread and sends its result, or its error, as the response to [requestId]. */
    func reply<T: Encodable>(_ requestId: Int64, _ body: @escaping @Sendable () async throws -> T) {
        Task.detached {
            do {
                self.send(requestId, Response(result: try await body(), error: nil))
            } catch {
                self.send(requestId, Response<T>(result: nil, error: BridgeError(error)))
            }
        }
    }

    func purchase(_ productId: String) async throws -> BridgePurchaseResult {
        guard let product = try await Product.products(for: [productId]).first else {
            throw BridgeError(code: "productUnavailable", message: "Unknown product \(productId)")
        }

        switch try await product.purchase() {
        case .success(let verification):
            guard case .verified(let transaction) = verification,
                  let purchase = BridgePurchase(transaction, jws: verification.jwsRepresentation, isFinished: false)
            else {
                throw BridgeError(code: "verificationFailed", message: "The purchase couldn't be verified")
            }
            return BridgePurchaseResult(status: "success", purchase: purchase)
        case .pending:
            return BridgePurchaseResult(status: "pending", purchase: nil)
        case .userCancelled:
            return BridgePurchaseResult(status: "cancelled", purchase: nil)
        @unknown default:
            throw BridgeError(code: "unknown", message: "Unknown purchase result")
        }
    }

    /** Owned non-consumables and unfinished consumables. */
    func purchases() async -> [BridgePurchase] {
        var unfinished = [UInt64: BridgePurchase]()
        for await verification in Transaction.unfinished {
            guard case .verified(let transaction) = verification,
                  let purchase = BridgePurchase(transaction, jws: verification.jwsRepresentation, isFinished: false)
            else { continue }
            unfinished[transaction.id] = purchase
        }

        var purchases = Array(unfinished.values)
        for await verification in Transaction.currentEntitlements {
            guard case .verified(let transaction) = verification, unfinished[transaction.id] == nil,
                  let purchase = BridgePurchase(transaction, jws: verification.jwsRepresentation, isFinished: true)
            else { continue }
            purchases.append(purchase)
        }
        return purchases.sorted { $0.id < $1.id }
    }

    func finish(_ purchase: BridgePurchase) async throws {
        guard let id = UInt64(purchase.id) else {
            throw BridgeError(code: "developerError", message: "Invalid transaction ID \(purchase.id)")
        }
        for await verification in Transaction.unfinished {
            if case .verified(let transaction) = verification, transaction.id == id {
                await transaction.finish()
                return
            }
        }
        // Already finished
    }

    private func send<T: Encodable>(_ requestId: Int64, _ response: Response<T>) {
        let json: String
        if let data = try? JSONEncoder().encode(response) {
            json = String(decoding: data, as: UTF8.self)
        } else {
            json = #"{"error":{"code":"unknown","message":"Couldn't encode the response"}}"#
        }

        lock.lock()
        let callback = self.callback
        lock.unlock()
        json.withCString { callback?(requestId, $0) }
    }
}

// MARK: - JSON

struct Response<T: Encodable>: Encodable {
    let result: T?
    let error: BridgeError?
}

struct BridgeError: Error, Encodable {
    let code: String
    let message: String?

    init(code: String, message: String?) {
        self.code = code
        self.message = message
    }

    init(_ error: Error) {
        if let error = error as? BridgeError {
            self = error
        } else if let error = error as? StoreKitError {
            self.init(storeKitError: error)
        } else if let error = error as? Product.PurchaseError {
            self.init(purchaseError: error)
        } else {
            self.init(code: "unknown", message: error.localizedDescription)
        }
    }

    private init(storeKitError error: StoreKitError) {
        let code: String
        switch error {
        case .networkError: code = "networkError"
        case .notAvailableInStorefront: code = "productUnavailable"
        case .notEntitled: code = "notAllowed"
        default: code = "unknown"
        }
        self.init(code: code, message: error.localizedDescription)
    }

    private init(purchaseError error: Product.PurchaseError) {
        let code: String
        switch error {
        case .purchaseNotAllowed: code = "notAllowed"
        case .productUnavailable: code = "productUnavailable"
        default: code = "developerError"
        }
        self.init(code: code, message: error.localizedDescription)
    }
}

struct BridgeProduct: Encodable {
    let id: String
    let type: String
    let title: String
    let description: String
    let formattedPrice: String
    let priceAmountMicros: Int64
    let currencyCode: String

    init?(_ product: Product) {
        guard let type = bridgeProductType(product.type) else { return nil }
        id = product.id
        self.type = type
        title = product.displayName
        description = product.description
        formattedPrice = product.displayPrice
        priceAmountMicros = NSDecimalNumber(decimal: product.price * 1_000_000).int64Value
        currencyCode = product.priceFormatStyle.currencyCode
    }
}

struct BridgePurchase: Codable {
    let id: String
    let productId: String
    let productType: String
    let state: String
    let quantity: Int
    let purchaseTimeMillis: Int64?
    let verificationData: String?
    let isFinished: Bool

    init?(_ transaction: Transaction, jws: String, isFinished: Bool) {
        guard transaction.revocationDate == nil, let type = bridgeProductType(transaction.productType) else {
            return nil
        }
        id = String(transaction.id)
        productId = transaction.productID
        productType = type
        // StoreKit only creates a transaction once a purchase is paid for
        state = "purchased"
        quantity = transaction.purchasedQuantity
        purchaseTimeMillis = Int64(transaction.purchaseDate.timeIntervalSince1970 * 1000)
        verificationData = jws
        self.isFinished = isFinished
    }
}

struct BridgePurchaseResult: Encodable {
    let status: String
    let purchase: BridgePurchase?
}

private func bridgeProductType(_ type: Product.ProductType) -> String? {
    switch type {
    case .consumable: return "consumable"
    case .nonConsumable: return "nonConsumable"
    // Subscriptions aren't supported yet
    default: return nil
    }
}
