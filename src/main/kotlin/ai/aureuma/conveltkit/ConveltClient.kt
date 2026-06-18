package ai.aureuma.conveltkit

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.UUID

fun interface ConveltBearerTokenProvider {
    suspend fun bearerToken(): String?
}

class ConveltClient internal constructor(
    private val configuration: ConveltConfiguration,
    private val outboxStore: ConveltOutboxStore = InMemoryConveltOutboxStore(),
    private val httpClient: OkHttpClient = OkHttpClient(),
    applicationContext: Context? = null,
    private val billingGateway: ConveltBillingGateway? = applicationContext?.let(::PlayBillingGateway),
) {
    private val latestSnapshotByCustomerId = linkedMapOf<UUID, ConveltEntitlementSnapshot>()
    private val _observedPurchases = MutableStateFlow<List<ConveltObservedPurchase>>(emptyList())
    private var boundExternalUserId: String? = null
    private var boundCustomerId: UUID? = null
    private var boundEmailHash: String? = null
    private var boundInstallationId: UUID? = null
    private var authorizationBearerTokenProvider: ConveltBearerTokenProvider? = null
    private var pendingUploads: List<ConveltPendingGooglePurchaseUpload> = emptyList()
    private var outboxLoaded = false

    val observedPurchases: StateFlow<List<ConveltObservedPurchase>> = _observedPurchases.asStateFlow()

    fun bindSignedInUser(externalUserId: String?, emailHashOrNull: String? = null) {
        boundExternalUserId = externalUserId?.trim()?.takeIf { it.isNotEmpty() }
        boundEmailHash = emailHashOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun bindResolvedUserIdentity(identity: ConveltUserIdentity?) {
        boundCustomerId = identity?.customerId
        boundExternalUserId = identity?.externalUserId?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun bindAuthorizationBearerToken(tokenProvider: ConveltBearerTokenProvider?) {
        authorizationBearerTokenProvider = tokenProvider
    }

    suspend fun bootstrap(installationId: UUID): ConveltBootstrapResponse {
        boundInstallationId = installationId
        val response: ConveltBootstrapResponse = request(
            path = "/v1/client/bootstrap",
            method = "POST",
            body = BootstrapRequestPayload(
                appEnvironmentId = configuration.appEnvironmentId,
                appCode = configuration.appCode,
                bundleId = configuration.packageName,
                appVersion = configuration.appVersion,
                buildNumber = configuration.buildNumber,
                installationId = installationId,
                sdkKey = configuration.publicSdkKey,
                sdkVersion = configuration.sdkVersion,
                supportedContractVersions = listOf("1.0.0"),
            ),
        )
        response.snapshot?.let { latestSnapshotByCustomerId[it.customerId] = it }
        return response
    }

    suspend fun startPurchaseObserver() {
        val gateway = billingGateway ?: throw ConveltClientError.StoreClientUnavailable
        gateway.startPurchaseObserver { purchases ->
            _observedPurchases.value = purchases
        }
        _observedPurchases.value = gateway.queryOwnedPurchases(ConveltBillingProductType.SUBS)
    }

    fun stopPurchaseObserver() {
        billingGateway?.stop()
        _observedPurchases.value = emptyList()
    }

    suspend fun queryProducts(
        productIds: List<String>,
        productType: ConveltBillingProductType = ConveltBillingProductType.SUBS,
    ): List<ConveltBillingProduct> {
        val gateway = billingGateway ?: throw ConveltClientError.StoreClientUnavailable
        return gateway.queryProducts(productIds = productIds, productType = productType)
    }

    suspend fun launchPurchase(
        activity: Activity,
        product: ConveltBillingProduct,
        selectedOfferToken: String? = product.defaultOfferToken,
    ): ConveltBillingLaunchResult {
        val gateway = billingGateway ?: throw ConveltClientError.StoreClientUnavailable
        return gateway.launchPurchase(
            activity = activity,
            product = product,
            selectedOfferToken = selectedOfferToken,
            obfuscatedAccountId = boundCustomerId?.let { obfuscatedIdentifier("google_play_account", it.toString()) },
            obfuscatedProfileId = boundExternalUserId?.let { obfuscatedIdentifier("google_play_profile", it) },
        )
    }

    suspend fun enqueueGooglePurchaseUpload(upload: ConveltGooglePurchaseUpload) {
        assertUserBindingMatches(upload.externalUserId)
        ensureOutboxLoaded()
        pendingUploads = pendingUploads
            .filterNot { it.upload.idempotencyKey == upload.idempotencyKey }
            .plus(
                ConveltPendingGooglePurchaseUpload(
                    queuedAtEpochMs = System.currentTimeMillis(),
                    upload = upload,
                ),
            )
        outboxStore.save(pendingUploads)
    }

    suspend fun drainPendingPurchaseUploads(): List<ConveltOutcomeResponse> =
        drainPendingPurchaseUploadsDetailed().map { it.outcome }

    suspend fun uploadGooglePurchase(upload: ConveltGooglePurchaseUpload): ConveltOutcomeResponse {
        assertUserBindingMatches(upload.externalUserId)

        val outcome: ConveltOutcomeResponse = request(
            path = "/v1/client/google/purchases",
            method = "POST",
            body = upload.copy(
                externalUserId = upload.externalUserId ?: boundExternalUserId,
            ),
            idempotencyKey = upload.idempotencyKey,
        )
        outcome.snapshot?.let { latestSnapshotByCustomerId[it.customerId] = it }
        return outcome
    }

    suspend fun syncEntitlements(
        customerId: UUID,
        externalUserId: String? = null,
        reason: String,
    ): ConveltOutcomeResponse {
        val requestExternalUserId = externalUserId ?: boundExternalUserId
        assertUserBindingMatches(requestExternalUserId)
        val outcome: ConveltOutcomeResponse = request(
            path = "/v1/client/entitlements/sync",
            method = "POST",
            body = SyncEntitlementsRequestPayload(
                appEnvironmentId = configuration.appEnvironmentId,
                customerId = customerId,
                externalUserId = requestExternalUserId,
                reason = reason,
            ),
        )
        outcome.snapshot?.let { latestSnapshotByCustomerId[it.customerId] = it }
        return outcome
    }

    suspend fun syncEntitlements(identity: ConveltUserIdentity, reason: String): ConveltOutcomeResponse =
        syncEntitlements(
            customerId = identity.customerId,
            externalUserId = identity.externalUserId,
            reason = reason,
        )

    suspend fun currentEntitlements(customerId: UUID): ConveltEntitlementSnapshot? {
        val url = configuration.baseUrl.newBuilder()
            .addPathSegments("v1/client/entitlements/current")
            .addQueryParameter("app_environment_id", configuration.appEnvironmentId.toString())
            .addQueryParameter("customer_id", customerId.toString())
            .apply {
                boundExternalUserId?.let { addQueryParameter("external_user_id", it) }
            }
            .build()
        val snapshot: ConveltEntitlementSnapshot? = request(url = url, method = "GET")
        snapshot?.let { latestSnapshotByCustomerId[it.customerId] = it }
        return snapshot
    }

    fun cachedEntitlements(customerId: UUID): ConveltEntitlementSnapshot? =
        latestSnapshotByCustomerId[customerId]

    suspend fun pendingUploadCount(): Int {
        ensureOutboxLoaded()
        return pendingUploads.size
    }

    suspend fun restorePurchases(customerId: UUID): List<ConveltOutcomeResponse> =
        syncPurchases(customerId = customerId, reason = "restore_purchases")

    suspend fun restorePurchases(identity: ConveltUserIdentity): List<ConveltOutcomeResponse> =
        restorePurchases(customerId = identity.customerId)

    suspend fun syncPurchases(customerId: UUID, reason: String): List<ConveltOutcomeResponse> {
        boundCustomerId = customerId
        collectOwnedGooglePlayPurchases(customerId)
        val outcomes = drainPendingPurchaseUploads().toMutableList()
        outcomes += syncEntitlements(customerId = customerId, reason = reason)
        return outcomes
    }

    suspend fun syncPurchases(identity: ConveltUserIdentity, reason: String): List<ConveltOutcomeResponse> {
        bindResolvedUserIdentity(identity)
        return syncPurchases(customerId = identity.customerId, reason = reason)
    }

    companion object {
        fun stableGooglePurchaseUploadIdempotencyKey(
            appEnvironmentId: UUID,
            packageName: String,
            productId: String,
            purchaseToken: String,
        ): String {
            val raw = listOf(
                "google_purchase",
                appEnvironmentId.toString().lowercase(),
                packageName.trim(),
                productId.trim(),
                purchaseToken.trim(),
            ).joinToString(":")
            return "google-purchase-${sha256Hex(raw)}"
        }

        private fun cancelledOutcome(): ConveltOutcomeResponse =
            ConveltOutcomeResponse(
                outcome = "store_user_cancelled",
                displayClass = "cancelled",
                retryable = false,
                readyToFinish = true,
            )

        private fun pendingApprovalOutcome(): ConveltOutcomeResponse =
            ConveltOutcomeResponse(
                outcome = "store_pending_approval",
                displayClass = "pending",
                retryable = true,
                readyToFinish = false,
            )

        private fun retryingOutcome(failureReason: String? = null): ConveltOutcomeResponse =
            ConveltOutcomeResponse(
                outcome = "verification_retry_scheduled",
                displayClass = "retrying",
                retryable = true,
                readyToFinish = false,
                failureReason = failureReason,
            )

        private fun terminalFailureOutcome(failureReason: String? = null): ConveltOutcomeResponse =
            ConveltOutcomeResponse(
                outcome = "verification_failed_terminal",
                displayClass = "terminal",
                retryable = false,
                readyToFinish = true,
                failureReason = failureReason,
            )

        private fun outcomeForClientError(error: ConveltClientError): ConveltOutcomeResponse =
            when (error) {
                is ConveltClientError.HttpStatus -> {
                    when {
                        error.statusCode >= 500 -> retryingOutcome(error.errorCode ?: "backend_unavailable")
                        error.statusCode == 429 -> retryingOutcome(error.errorCode ?: "rate_limited")
                        else -> terminalFailureOutcome(error.errorCode ?: "client_http_${error.statusCode}")
                    }
                }
                ConveltClientError.InvalidRequest,
                ConveltClientError.UserIdentityUnavailable,
                ConveltClientError.UserBindingMismatch,
                -> terminalFailureOutcome("client_request_invalid")
                ConveltClientError.InvalidResponse,
                ConveltClientError.DecodeFailed,
                -> retryingOutcome("backend_invalid_response")
                ConveltClientError.StoreClientUnavailable -> retryingOutcome("store_client_unavailable")
                ConveltClientError.NoPurchasesFound -> ConveltOutcomeResponse(
                    outcome = "no_purchases_found",
                    displayClass = "inactive",
                    retryable = false,
                    readyToFinish = true,
                )
                ConveltClientError.PurchaseCancelled -> cancelledOutcome()
                ConveltClientError.PendingApproval -> pendingApprovalOutcome()
            }

        private fun sha256Hex(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    private suspend fun collectOwnedGooglePlayPurchases(customerId: UUID) {
        val gateway = billingGateway ?: return
        val purchases = gateway.queryOwnedPurchases(ConveltBillingProductType.SUBS)
        _observedPurchases.value = purchases
        if (purchases.isEmpty()) {
            return
        }
        val installationId = boundInstallationId ?: throw ConveltClientError.InvalidRequest
        purchases.forEach { purchase ->
            purchase.productIds.distinct().forEach { productId ->
                enqueueGooglePurchaseUpload(
                    ConveltGooglePurchaseUpload(
                        appEnvironmentId = configuration.appEnvironmentId,
                        installationId = installationId,
                        customerId = customerId,
                        externalUserId = boundExternalUserId,
                        packageName = configuration.packageName,
                        productId = productId,
                        purchaseToken = purchase.purchaseToken,
                        orderId = purchase.orderId,
                        environment = configuration.environment,
                        idempotencyKey = stableGooglePurchaseUploadIdempotencyKey(
                            appEnvironmentId = configuration.appEnvironmentId,
                            packageName = configuration.packageName,
                            productId = productId,
                            purchaseToken = purchase.purchaseToken,
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun drainPendingPurchaseUploadsDetailed(): List<ConveltProcessedPurchaseUpload> {
        ensureOutboxLoaded()
        if (pendingUploads.isEmpty()) {
            return emptyList()
        }

        val processed = mutableListOf<ConveltProcessedPurchaseUpload>()
        val remaining = mutableListOf<ConveltPendingGooglePurchaseUpload>()

        pendingUploads.forEachIndexed { index, pending ->
            try {
                val outcome = uploadGooglePurchase(pending.upload)
                processed += ConveltProcessedPurchaseUpload(pending.upload, outcome)
                if (outcome.retryable && !outcome.readyToFinish) {
                    remaining += pending
                    if (index + 1 < pendingUploads.size) {
                        remaining += pendingUploads.subList(index + 1, pendingUploads.size)
                    }
                    outboxStore.save(remaining)
                    pendingUploads = remaining
                    return processed
                }
            } catch (error: ConveltClientError) {
                val outcome = outcomeForClientError(error)
                processed += ConveltProcessedPurchaseUpload(pending.upload, outcome)
                if (outcome.retryable && !outcome.readyToFinish) {
                    remaining += pending
                    if (index + 1 < pendingUploads.size) {
                        remaining += pendingUploads.subList(index + 1, pendingUploads.size)
                    }
                    outboxStore.save(remaining)
                    pendingUploads = remaining
                    return processed
                }
            } catch (_: Throwable) {
                remaining += pending
                if (index + 1 < pendingUploads.size) {
                    remaining += pendingUploads.subList(index + 1, pendingUploads.size)
                }
                outboxStore.save(remaining)
                pendingUploads = remaining
                return processed
            }
        }

        pendingUploads = remaining
        outboxStore.save(remaining)
        return processed
    }

    private suspend inline fun <reified Response, reified Body : Any> request(
        path: String,
        method: String,
        body: Body,
        idempotencyKey: String? = null,
    ): Response = request(
        url = configuration.baseUrl.newBuilder().addPathSegments(path.removePrefix("/")).build(),
        method = method,
        body = body,
        idempotencyKey = idempotencyKey,
    )

    private suspend inline fun <reified Response, reified Body : Any> request(
        url: HttpUrl,
        method: String,
        body: Body,
        idempotencyKey: String? = null,
    ): Response = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .method(
                method,
                conveltJson.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE),
            )
            .header("Content-Type", "application/json")
            .header("x-convelt-sdk-key", configuration.publicSdkKey)
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        authorizationBearerTokenProvider?.bearerToken()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.header("Authorization", "Bearer $it") }

        httpClient.newCall(builder.build()).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ConveltClientError.HttpStatus(response.code, extractErrorCode(payload))
            }
            try {
                conveltJson.decodeFromString<Response>(payload)
            } catch (_: Throwable) {
                throw ConveltClientError.DecodeFailed
            }
        }
    }

    private suspend inline fun <reified Response> request(
        url: HttpUrl,
        method: String,
    ): Response = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .method(method, null)
            .header("x-convelt-sdk-key", configuration.publicSdkKey)
        authorizationBearerTokenProvider?.bearerToken()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.header("Authorization", "Bearer $it") }

        httpClient.newCall(builder.build()).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ConveltClientError.HttpStatus(response.code, extractErrorCode(payload))
            }
            try {
                conveltJson.decodeFromString<Response>(payload)
            } catch (_: Throwable) {
                throw ConveltClientError.DecodeFailed
            }
        }
    }

    private suspend fun ensureOutboxLoaded() {
        if (outboxLoaded) {
            return
        }
        pendingUploads = outboxStore.load()
        outboxLoaded = true
    }

    private fun assertUserBindingMatches(uploadExternalUserId: String?) {
        val bound = boundExternalUserId ?: return
        val requested = uploadExternalUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (bound != requested) {
            throw ConveltClientError.UserBindingMismatch
        }
    }

    private fun extractErrorCode(payload: String): String? {
        val marker = "\"error\""
        val start = payload.indexOf(marker)
        if (start < 0) {
            return null
        }
        val colon = payload.indexOf(':', start)
        if (colon < 0) {
            return null
        }
        val quoteStart = payload.indexOf('"', colon + 1)
        if (quoteStart < 0) {
            return null
        }
        val quoteEnd = payload.indexOf('"', quoteStart + 1)
        if (quoteEnd < 0) {
            return null
        }
        return payload.substring(quoteStart + 1, quoteEnd).trim().ifEmpty { null }
    }

    private fun obfuscatedIdentifier(scope: String, raw: String): String {
        val normalized = raw.trim()
        if (normalized.isEmpty()) {
            return ""
        }
        return sha256Hex("$scope:$normalized")
    }
}

sealed class ConveltClientError(message: String? = null) : IllegalStateException(message) {
    data object InvalidRequest : ConveltClientError()
    data object InvalidResponse : ConveltClientError()
    data class HttpStatus(val statusCode: Int, val errorCode: String?) : ConveltClientError()
    data object UserIdentityUnavailable : ConveltClientError()
    data object UserBindingMismatch : ConveltClientError()
    data object DecodeFailed : ConveltClientError()
    data object StoreClientUnavailable : ConveltClientError()
    data object NoPurchasesFound : ConveltClientError()
    data object PurchaseCancelled : ConveltClientError()
    data object PendingApproval : ConveltClientError()
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private data class ConveltProcessedPurchaseUpload(
    val upload: ConveltGooglePurchaseUpload,
    val outcome: ConveltOutcomeResponse,
)

@Serializable
private data class BootstrapRequestPayload(
    @SerialName("app_environment_id")
    @Serializable(with = UUIDAsStringSerializer::class)
    val appEnvironmentId: UUID,
    @SerialName("app_code")
    val appCode: String,
    @SerialName("bundle_id")
    val bundleId: String,
    @SerialName("app_version")
    val appVersion: String,
    @SerialName("build_number")
    val buildNumber: String,
    @SerialName("installation_id")
    @Serializable(with = UUIDAsStringSerializer::class)
    val installationId: UUID,
    @SerialName("sdk_key")
    val sdkKey: String,
    @SerialName("sdk_version")
    val sdkVersion: String?,
    @SerialName("supported_contract_versions")
    val supportedContractVersions: List<String>,
)

@Serializable
private data class SyncEntitlementsRequestPayload(
    @SerialName("app_environment_id")
    @Serializable(with = UUIDAsStringSerializer::class)
    val appEnvironmentId: UUID,
    @SerialName("customer_id")
    @Serializable(with = UUIDAsStringSerializer::class)
    val customerId: UUID,
    @SerialName("external_user_id")
    val externalUserId: String? = null,
    @SerialName("reason")
    val reason: String,
)
