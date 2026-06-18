package ai.aureuma.conveltkit

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal interface ConveltBillingGateway {
    suspend fun startPurchaseObserver(onPurchasesUpdated: (List<ConveltObservedPurchase>) -> Unit)

    suspend fun queryProducts(
        productIds: List<String>,
        productType: ConveltBillingProductType,
    ): List<ConveltBillingProduct>

    suspend fun queryOwnedPurchases(
        productType: ConveltBillingProductType,
    ): List<ConveltObservedPurchase>

    suspend fun launchPurchase(
        activity: Activity,
        product: ConveltBillingProduct,
        selectedOfferToken: String?,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?,
    ): ConveltBillingLaunchResult

    fun stop()
}

internal class PlayBillingGateway(
    applicationContext: Context,
) : ConveltBillingGateway, PurchasesUpdatedListener {
    private val billingClient = BillingClient.newBuilder(applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build(),
        )
        .enableAutoServiceReconnection()
        .build()
    private var purchasesUpdatedListener: ((List<ConveltObservedPurchase>) -> Unit)? = null

    override suspend fun startPurchaseObserver(
        onPurchasesUpdated: (List<ConveltObservedPurchase>) -> Unit,
    ) {
        purchasesUpdatedListener = onPurchasesUpdated
        ensureReady()
    }

    override suspend fun queryProducts(
        productIds: List<String>,
        productType: ConveltBillingProductType,
    ): List<ConveltBillingProduct> {
        ensureReady()
        if (productIds.isEmpty()) {
            return emptyList()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.distinct().map { productId ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(productType.toBillingProductType())
                        .build()
                },
            )
            .build()

        return suspendCancellableCoroutine { continuation ->
            billingClient.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(emptyList())
                    return@queryProductDetailsAsync
                }
                continuation.resume(
                    queryProductDetailsResult.productDetailsList.map { productDetails ->
                        productDetails.toConveltBillingProduct(productType)
                    },
                )
            }
        }
    }

    override suspend fun queryOwnedPurchases(
        productType: ConveltBillingProductType,
    ): List<ConveltObservedPurchase> {
        ensureReady()
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(productType.toBillingProductType())
            .build()
        return suspendCancellableCoroutine { continuation ->
            billingClient.queryPurchasesAsync(params, PurchasesResponseListener { billingResult, purchases ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(emptyList())
                    return@PurchasesResponseListener
                }
                continuation.resume(purchases.map(Purchase::toConveltObservedPurchase))
            })
        }
    }

    override suspend fun launchPurchase(
        activity: Activity,
        product: ConveltBillingProduct,
        selectedOfferToken: String?,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?,
    ): ConveltBillingLaunchResult = withContext(Dispatchers.Main) {
        ensureReady()
        val productDetails = product.productDetails
            ?: throw IllegalStateException("Billing product details are missing")
        val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
        selectedOfferToken?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(productDetailsParamsBuilder::setOfferToken)

        val paramsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))

        obfuscatedAccountId?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(paramsBuilder::setObfuscatedAccountId)
        obfuscatedProfileId?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(paramsBuilder::setObfuscatedProfileId)

        val billingResult = billingClient.launchBillingFlow(activity, paramsBuilder.build())
        ConveltBillingLaunchResult(
            responseCode = billingResult.responseCode,
            debugMessage = billingResult.debugMessage.orEmpty(),
        )
    }

    override fun stop() {
        purchasesUpdatedListener = null
        billingClient.endConnection()
    }

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            return
        }
        purchasesUpdatedListener?.invoke(purchases.orEmpty().map(Purchase::toConveltObservedPurchase))
    }

    private suspend fun ensureReady() {
        if (billingClient.isReady) {
            return
        }
        suspendCancellableCoroutine<Unit> { continuation ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() = Unit

                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        continuation.resume(Unit)
                    } else {
                        continuation.cancel(
                            IllegalStateException(
                                "Billing setup failed: ${billingResult.responseCode} ${billingResult.debugMessage}",
                            ),
                        )
                    }
                }
            })
        }
    }
}

private fun ConveltBillingProductType.toBillingProductType(): String =
    when (this) {
        ConveltBillingProductType.SUBS -> BillingClient.ProductType.SUBS
    }

private fun ProductDetails.toConveltBillingProduct(
    productType: ConveltBillingProductType,
): ConveltBillingProduct {
    val offers = subscriptionOfferDetails.orEmpty().map { offer ->
        val firstPricingPhase = offer.pricingPhases.pricingPhaseList.firstOrNull()
        ConveltBillingOffer(
            offerToken = offer.offerToken,
            basePlanId = offer.basePlanId,
            offerId = offer.offerId,
            offerTags = offer.offerTags,
            priceFormatted = firstPricingPhase?.formattedPrice,
            billingPeriod = firstPricingPhase?.billingPeriod,
        )
    }
    return ConveltBillingProduct(
        productId = productId,
        productType = productType,
        title = title,
        description = description,
        offers = offers,
        defaultOfferToken = offers.firstOrNull()?.offerToken,
        productDetails = this,
    )
}

private fun Purchase.toConveltObservedPurchase(): ConveltObservedPurchase =
    ConveltObservedPurchase(
        purchaseToken = purchaseToken,
        orderId = orderId,
        productIds = products,
        purchaseState = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> ConveltPurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> ConveltPurchaseState.PENDING
            else -> ConveltPurchaseState.UNSPECIFIED
        },
        isAcknowledged = isAcknowledged,
    )
