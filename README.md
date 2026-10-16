# kmp-billing

A Kotlin Multiplatform wrapper around the app stores' in-app purchase APIs, behind one `BillingClient` interface.

| Store | Platform | Module | Client |
|---|---|---|---|
| Google Play Billing 9 | Android 6+ | `billing-google` | `GoogleBillingClient` |
| Amazon Appstore SDK 3 | Android 6+ | `billing-amazon` | `AmazonBillingClient` |
| StoreKit 2 | iOS 16+ | `billing-apple` | `AppleBillingClient` |
| StoreKit 2 | Desktop JVM on macOS 13+ | `billing-apple` | `AppleBillingClient` |
| Windows Store (`Windows.Services.Store`) | Desktop JVM on Windows 10+, x64 and ARM64 | `billing-windows` | `WindowsBillingClient` |

Linux isn't supported; use `UnsupportedBillingClient`, which reports the store as unavailable.

This version covers one-time purchases: non-consumables, such as unlocking content, and consumables, such as
in-game currency. Subscriptions aren't supported yet.

## Setup

The modules are published as `com.appmattus.billing:<module>:<version>`. Depend on `billing-core` from common code,
and on the store modules from each platform's source set:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.appmattus.billing:billing-core:<version>")
        }
        iosMain.dependencies {
            implementation("com.appmattus.billing:billing-apple:<version>")
        }
        jvmMain.dependencies {
            implementation("com.appmattus.billing:billing-apple:<version>")
            implementation("com.appmattus.billing:billing-windows:<version>")
        }
        commonTest.dependencies {
            implementation("com.appmattus.billing:billing-testing:<version>")
        }
    }
}
```

On Android, depend on `billing-google` or `billing-amazon` from the matching product flavour, so each build only
contains its store's SDK.

## Creating a client

Create one client per app, as each store allows only one purchase listener:

```kotlin
// Android
val billing: BillingClient = GoogleBillingClient(context, consumableProductIds = setOf("coins"))
val billing: BillingClient = AmazonBillingClient(context)

// iOS
val billing: BillingClient = AppleBillingClient()

// Desktop
val os = System.getProperty("os.name")
val billing: BillingClient = when {
    os.startsWith("Mac") -> AppleBillingClient()
    os.startsWith("Windows") -> WindowsBillingClient()
    else -> UnsupportedBillingClient()
}
```

Google Play doesn't record whether a product is consumable, so `GoogleBillingClient` needs the consumable products'
identifiers; every other product is treated as a non-consumable.

## Buying and delivering

```kotlin
val products = billing.getProducts(setOf("levels", "coins"))

when (val result = billing.purchase(host, "levels")) {
    is PurchaseResult.Success -> deliver(result.purchase)
    PurchaseResult.Pending -> showWaitingForApproval()
    PurchaseResult.AlreadyOwned -> billing.restorePurchases().forEach(::deliver)
    PurchaseResult.Cancelled -> Unit
    is PurchaseResult.Failed -> showError(result.error)
}

suspend fun deliver(purchase: Purchase) {
    if (purchase.state != PurchaseState.Purchased) return
    unlock(purchase.productId)
    billing.finishPurchase(purchase)
}
```

`host` is what the purchase flow is shown over: the `Activity` on Android, a `UIViewController` on iOS, and
`PurchaseHost(window)` on desktop, where the Windows Store needs the window to own its dialog.

A purchase stays unfinished until `finishPurchase` is called, which acknowledges it on Google Play, notifies Amazon
of its fulfilment, finishes the StoreKit transaction and reports a Windows Store consumable as fulfilled. Stores
refund or redeliver unfinished purchases, so deliver and finish every purchase you receive, including:

- on start-up, from `getPurchases()`, which returns owned non-consumables and unfinished consumables;
- from `purchaseUpdates`, which emits purchases that complete outside `purchase()`, such as an approved pending
  purchase or one made on another device.

Finishing a purchase more than once has no effect.

Call `restorePurchases()` when the user asks to restore their purchases. On StoreKit it syncs with the App Store,
which can ask the user to sign in; the other stores keep purchases in sync themselves.

Every call apart from `purchase` throws `BillingException` when it fails, with a `BillingError` describing why.

## Verifying purchases on a server

`Purchase.verificationData` holds what each store's server API needs:

| Store | `verificationData` | Verify with |
|---|---|---|
| Google Play | Purchase token | Google Play Developer API |
| Amazon | Receipt ID, plus `AmazonBillingClient.getUserId()` | Receipt Verification Service |
| StoreKit | Signed transaction (JWS) | App Store Server API, or by checking the JWS signature |
| Windows Store | None | Microsoft Store collections API |

## Store-specific notes

### Amazon Appstore

- Amazon reports a user cancelling a purchase as a failure, so both arrive as `PurchaseResult.Cancelled`.
- Prices come from Amazon already formatted. The App Tester can return unformatted prices such as `0.99`.
- Add the `AppstoreAuthenticationKey.pem` from the Amazon Developer Console to the app's assets.

### StoreKit

- On the JVM, `billing-apple` bundles a universal (arm64 and x86_64) dylib that it loads with JNA.
- StoreKit identifies the app by its bundle, so products and purchases only work from a signed `.app` with the bundle
  identifier registered in App Store Connect, such as a Compose Desktop app packaged for the Mac App Store.
- Test purchases with a StoreKit configuration file in Xcode, or with sandbox accounts.

### Windows Store

- The app must be packaged as MSIX and associated with its Store listing in Partner Center; unpackaged apps can't
  use the Store, and `isAvailable()` returns false.
- Products are identified by the add-on's product ID from Partner Center.
- The Windows Store has no transaction IDs, so `Purchase.id` is the add-on's Store ID.
- Store-managed consumables report their unfulfilled balance as `Purchase.quantity`; finishing fulfils all of it.

## Testing

`billing-testing` has `FakeBillingClient`, an in-memory store for testing code that uses a `BillingClient`:

```kotlin
val billing = FakeBillingClient(products = listOf(Product("levels", ProductType.NonConsumable, "Levels", "", "£1.99")))

billing.nextPurchaseResult = PurchaseResult.Cancelled
billing.emitPurchaseUpdate(purchase)
assertEquals(listOf(purchase), billing.finishedPurchases)
```

## How it's built

`billing-google` and `billing-amazon` wrap the stores' Android SDKs directly.

StoreKit and the Windows Store are reached through small native bridges that implement the C API in
[`billing-native-bridge/native/billing_bridge.h`](billing-native-bridge/native/billing_bridge.h), exchanging JSON
through a callback. `billing-native-bridge` holds the Kotlin side, shared by both:

- **StoreKit:** [`billing-apple/src/swift/StoreKitBridge.swift`](billing-apple/src/swift/StoreKitBridge.swift),
  compiled by `swiftc`. iOS links it statically through cinterop; the JVM loads it as a dylib with JNA. Building it
  needs a Mac with Xcode.
- **Windows Store:** [`billing-windows/native/WindowsStoreBridge.cpp`](billing-windows/native/WindowsStoreBridge.cpp),
  a C++/WinRT DLL built with CMake and the Visual C++ build tools for x64 and ARM64. Building it needs Windows; on
  other platforms the jar has no DLL and the client reports the store as unavailable.

## Releasing

Releases are published to Maven Central with the
[Gradle Maven Publish Plugin](https://github.com/vanniktech/gradle-maven-publish-plugin), configured in
`gradle.properties` and each module's `gradle.properties`. The version is the Git tag: pushing a tag builds and
tests everything, then the release job builds the StoreKit bridge on macOS, adds the Windows Store bridges from the
Windows job and runs `publishAndReleaseToMavenCentral`.

It needs two repository secrets:

- `GPG_SIGNING_SECRET_KEY_RING_FILE_BASE64`: the GPG secret key ring file, base64 encoded.
- `PUBLISHING_GRADLE_PROPERTIES`: a `gradle.properties` with the Central Portal user token and the signing key
  details:

  ```properties
  mavenCentralUsername=<token username>
  mavenCentralPassword=<token password>
  signing.keyId=<last 8 characters of the key ID>
  signing.password=<key password>
  ```

Builds without a tag have the version `unspecified`. Publishing to Maven Local signs releases too, so pass
`-PsignAllPublications=false` to try it without a signing key.

## Building

```shell
./gradlew detekt allTests                 # Common, JVM and iOS simulator tests
./gradlew testAndroidHostTest             # Google Play and Amazon tests
./gradlew publishToMavenLocal
```

## License

```
Copyright 2026 Appmattus Limited

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
