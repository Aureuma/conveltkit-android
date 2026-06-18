# conveltkit-android

Android SDK home for ConveltKit.

## Status

This repository owns the source for the Android ConveltKit SDK.

- Google Play BillingClient product lookup, purchase launch, and purchase observer wiring are implemented.
- Google purchase-token uploads target Convelt `POST /v1/client/google/purchases`.
- Bootstrap and client requests serialize to the Convelt `1.0.0` API contract.
- Publication is local/release-train driven until a signed Maven distribution workflow is added.

## Naming and version contract

- Public SDK type: `ConveltKit`
- Android namespace/package: `ai.aureuma.conveltkit`
- Maven coordinate: `ai.aureuma.conveltkit:conveltkit-android:<convelt-version>`
- Canonical version source: `convelt/Cargo.toml` `[workspace.package].version`

Version metadata is generated from Convelt:

- `gradle.properties` (`conveltKitVersion=...`)
- `src/main/kotlin/ai/aureuma/conveltkit/ConveltKitVersion.kt`

## Dependency and import shape

```kotlin
dependencies {
    implementation("ai.aureuma.conveltkit:conveltkit-android:<convelt-version>")
}
```

```kotlin
import ai.aureuma.conveltkit.ConveltKit
```

## Contract source of truth

Convelt API contract is defined in the Convelt repo:

- `https://github.com/Aureuma/convelt/blob/main/docs/api-contract.md`

This repo does not define an independent API contract.
