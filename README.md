# conveltkit-android

Android SDK home for ConveltKit.

## Status

This repository is scaffold-only in this slice.

- No Google Play BillingClient integration yet.
- No purchase-token upload flow yet.
- No production-ready Android billing behavior yet.

## Naming and version contract

- Public SDK type: `ConveltKit`
- Android namespace/package: `ai.aureuma.conveltkit`
- Future Maven coordinate: `ai.aureuma.conveltkit:conveltkit-android:<convelt-version>`
- Canonical version source: `convelt/Cargo.toml` `[workspace.package].version`

Version metadata is generated from Convelt:

- `gradle.properties` (`conveltKitVersion=...`)
- `src/main/kotlin/ai/aureuma/conveltkit/ConveltKitVersion.kt`

## Future dependency and import shape

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
