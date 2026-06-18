package ai.aureuma.conveltkit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ConveltEnvironment {
    @SerialName("sandbox")
    SANDBOX,

    @SerialName("production")
    PRODUCTION,
}

data class ConveltConfiguration(
    val baseUrl: okhttp3.HttpUrl,
    val publicSdkKey: String,
    val appEnvironmentId: UUID,
    val environment: ConveltEnvironment,
    val appCode: String,
    val packageName: String,
    val appVersion: String,
    val buildNumber: String,
    val sdkVersion: String = ConveltKitVersion.value,
)

@Serializable
data class ConveltBootstrapResponse(
    @SerialName("contract_version")
    val contractVersion: String,
    @SerialName("config_version")
    val configVersion: String,
    @SerialName("app_environment_id")
    @Serializable(with = UUIDAsStringSerializer::class)
    val appEnvironmentId: UUID,
    @SerialName("placement")
    val placement: String,
    @SerialName("product_ids")
    val productIds: List<String>,
    @SerialName("entitlement_keys")
    val entitlementKeys: List<String>,
    @SerialName("snapshot")
    val snapshot: ConveltEntitlementSnapshot? = null,
)

@Serializable
data class ConveltEntitlementSnapshot(
    @SerialName("kid")
    val kid: String = "",
    @SerialName("iss")
    val iss: String = "",
    @SerialName("aud")
    val aud: String = "",
    @SerialName("customer_id")
    @Serializable(with = UUIDAsStringSerializer::class)
    val customerId: UUID,
    @SerialName("external_user_id")
    val externalUserId: String? = null,
    @SerialName("app_environment_id")
    @Serializable(with = UUIDAsStringSerializer::class)
    val appEnvironmentId: UUID,
    @SerialName("environment")
    val environment: ConveltEnvironment,
    @SerialName("snapshot_version")
    val snapshotVersion: Long,
    @SerialName("projection_schema_version")
    val projectionSchemaVersion: Int = 1,
    @SerialName("config_version")
    val configVersion: String = "",
    @SerialName("signature_algorithm")
    val signatureAlgorithm: String? = null,
    @SerialName("signature")
    val signature: String? = null,
    @SerialName("issued_at")
    val issuedAt: String,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("entitlements")
    val entitlements: List<ConveltEntitlementEntry>,
)

@Serializable
data class ConveltEntitlementEntry(
    @SerialName("key")
    val key: String,
    @SerialName("provider_state")
    val providerState: String,
    @SerialName("access_state")
    val accessState: String,
    @SerialName("active")
    val active: Boolean,
    @SerialName("product_id")
    val productId: String? = null,
    @SerialName("renews_at")
    val renewsAt: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("is_sandbox")
    val isSandbox: Boolean,
    @SerialName("ownership_status")
    val ownershipStatus: String,
    @SerialName("verification")
    val verification: String,
)

@Serializable
data class ConveltOutcomeResponse(
    @SerialName("outcome")
    val outcome: String,
    @SerialName("display_class")
    val displayClass: String,
    @SerialName("retryable")
    val retryable: Boolean,
    @SerialName("ready_to_finish")
    val readyToFinish: Boolean,
    @SerialName("failure_reason")
    val failureReason: String? = null,
    @SerialName("request_id")
    val requestId: String? = null,
    @SerialName("snapshot")
    val snapshot: ConveltEntitlementSnapshot? = null,
) {
    val resolvedOutcome: ConveltResolvedOutcome
        get() = when (outcome) {
            "access_granted", "already_active" -> ConveltResolvedOutcome.Active
            "already_processed" -> ConveltResolvedOutcome.AlreadyProcessed
            "store_user_cancelled" -> ConveltResolvedOutcome.PurchaseCancelled
            "no_purchases_found" -> ConveltResolvedOutcome.NoActivePurchases
            "store_pending_approval" -> ConveltResolvedOutcome.PendingApproval
            "billing_retry_in_progress", "verification_retry_scheduled" -> ConveltResolvedOutcome.Retrying
            "verification_failed_terminal", "ownership_conflict" -> ConveltResolvedOutcome.TerminalFailure
            else -> ConveltResolvedOutcome.Unknown(outcome)
        }
}

sealed interface ConveltResolvedOutcome {
    data object Active : ConveltResolvedOutcome
    data object AlreadyProcessed : ConveltResolvedOutcome
    data object PurchaseCancelled : ConveltResolvedOutcome
    data object NoActivePurchases : ConveltResolvedOutcome
    data object PendingApproval : ConveltResolvedOutcome
    data object Retrying : ConveltResolvedOutcome
    data object TerminalFailure : ConveltResolvedOutcome
    data class Unknown(val raw: String) : ConveltResolvedOutcome
}

data class ConveltUserIdentity(
    val externalUserId: String,
    val customerId: UUID,
)

@Serializable
data class ConveltGooglePurchaseUpload(
    @SerialName("app_environment_id")
    @Serializable(with = UUIDAsStringSerializer::class)
    val appEnvironmentId: UUID,
    @SerialName("installation_id")
    @Serializable(with = UUIDAsStringSerializer::class)
    val installationId: UUID,
    @SerialName("customer_id")
    @Serializable(with = UUIDAsStringSerializer::class)
    val customerId: UUID,
    @SerialName("external_user_id")
    val externalUserId: String? = null,
    @SerialName("package_name")
    val packageName: String,
    @SerialName("product_id")
    val productId: String,
    @SerialName("purchase_token")
    val purchaseToken: String,
    @SerialName("order_id")
    val orderId: String? = null,
    @SerialName("environment")
    val environment: ConveltEnvironment,
    @SerialName("idempotency_key")
    val idempotencyKey: String,
)

@Serializable
data class ConveltPendingGooglePurchaseUpload(
    @SerialName("queued_at_epoch_ms")
    val queuedAtEpochMs: Long,
    @SerialName("upload")
    val upload: ConveltGooglePurchaseUpload,
)

enum class ConveltBillingProductType {
    SUBS,
}

data class ConveltBillingOffer(
    val offerToken: String,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val offerTags: List<String> = emptyList(),
    val priceFormatted: String? = null,
    val billingPeriod: String? = null,
)

class ConveltBillingProduct internal constructor(
    val productId: String,
    val productType: ConveltBillingProductType,
    val title: String,
    val description: String,
    val offers: List<ConveltBillingOffer>,
    val defaultOfferToken: String?,
    internal val productDetails: com.android.billingclient.api.ProductDetails?,
)

enum class ConveltPurchaseState {
    PURCHASED,
    PENDING,
    UNSPECIFIED,
}

data class ConveltObservedPurchase(
    val purchaseToken: String,
    val orderId: String? = null,
    val productIds: List<String>,
    val purchaseState: ConveltPurchaseState,
    val isAcknowledged: Boolean,
)

data class ConveltBillingLaunchResult(
    val responseCode: Int,
    val debugMessage: String,
) {
    val launched: Boolean
        get() = responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK
}
