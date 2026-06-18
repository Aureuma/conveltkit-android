package ai.aureuma.conveltkit

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ConveltKitTest {
    @Test
    fun sdkIdentityIsStable() {
        assertEquals("ConveltKit", ConveltKit.sdkName)
        assertEquals(ConveltKitVersion.value, ConveltKit.sdkVersion)
    }

    @Test
    fun stableGooglePurchaseIdempotencyKeyMatchesServerContract() {
        val appEnvironmentId = UUID.fromString("8aa059a8-9906-4d3f-8081-389ad2c6bfd3")

        val key = ConveltClient.stableGooglePurchaseUploadIdempotencyKey(
            appEnvironmentId = appEnvironmentId,
            packageName = "ai.lingospeak.one",
            productId = "ai.lingospeak.pro.annual",
            purchaseToken = "purchase-token-1",
        )

        assertEquals(
            "google-purchase-c6e2972f92596d57c93742936bbbac559e03a644ab5e6175608839236d5c3978",
            key,
        )
    }

    @Test
    fun stableGooglePurchaseIdempotencyKeyTrimsFields() {
        val appEnvironmentId = UUID.randomUUID()

        val normalized = ConveltClient.stableGooglePurchaseUploadIdempotencyKey(
            appEnvironmentId = appEnvironmentId,
            packageName = "ai.lingospeak.one",
            productId = "ai.lingospeak.pro.annual",
            purchaseToken = "purchase-token-1",
        )
        val withWhitespace = ConveltClient.stableGooglePurchaseUploadIdempotencyKey(
            appEnvironmentId = appEnvironmentId,
            packageName = " ai.lingospeak.one ",
            productId = " ai.lingospeak.pro.annual ",
            purchaseToken = " purchase-token-1 ",
        )

        assertEquals(normalized, withWhitespace)
    }

    @Test
    fun bootstrapSendsCurrentConveltContractPayload() = runTest {
        val server = MockWebServer()
        val appEnvironmentId = UUID.randomUUID()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "contract_version": "1.0.0",
                      "config_version": "test-config-v1",
                      "app_environment_id": "$appEnvironmentId",
                      "placement": "default",
                      "product_ids": ["ai.lingospeak.pro.annual"],
                      "entitlement_keys": ["pro"]
                    }
                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val client = ConveltKit.createClient(
                configuration = ConveltConfiguration(
                    baseUrl = server.url("/"),
                    publicSdkKey = "test-sdk-key",
                    appEnvironmentId = appEnvironmentId,
                    environment = ConveltEnvironment.SANDBOX,
                    appCode = "lingospeak",
                    packageName = "ai.lingospeak.one",
                    appVersion = "6.8.101",
                    buildNumber = "6080101",
                    sdkVersion = ConveltKitVersion.value,
                ),
            )

            val response = client.bootstrap(UUID.fromString("11111111-1111-4111-8111-111111111111"))
            val request = server.takeRequest()
            val body = request.body.readUtf8()

            assertEquals("/v1/client/bootstrap", request.path)
            assertEquals("test-sdk-key", request.getHeader("x-convelt-sdk-key"))
            assertEquals("1.0.0", response.contractVersion)
            assertTrue(body.contains(""""app_environment_id":"$appEnvironmentId""""))
            assertTrue(body.contains(""""bundle_id":"ai.lingospeak.one""""))
            assertTrue(body.contains(""""supported_contract_versions":["1.0.0"]"""))
            assertFalse(body.contains("appIdentifier"))
            assertFalse(body.contains(""""store""""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun googlePurchaseUploadSendsServerContractBody() = runTest {
        val server = MockWebServer()
        val appEnvironmentId = UUID.randomUUID()
        val installationId = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        val idempotencyKey = "google-purchase-1"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "outcome": "access_granted",
                      "display_class": "access_granted",
                      "retryable": false,
                      "ready_to_finish": true
                    }
                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val client = ConveltKit.createClient(
                configuration = ConveltConfiguration(
                    baseUrl = server.url("/"),
                    publicSdkKey = "test-sdk-key",
                    appEnvironmentId = appEnvironmentId,
                    environment = ConveltEnvironment.SANDBOX,
                    appCode = "lingospeak",
                    packageName = "ai.lingospeak.one",
                    appVersion = "6.8.101",
                    buildNumber = "6080101",
                ),
            )

            val outcome = client.uploadGooglePurchase(
                ConveltGooglePurchaseUpload(
                    appEnvironmentId = appEnvironmentId,
                    installationId = installationId,
                    customerId = customerId,
                    externalUserId = "user-1",
                    packageName = "ai.lingospeak.one",
                    productId = "ai.lingospeak.pro.annual",
                    purchaseToken = "purchase-token-1",
                    orderId = "GPA.0000-0000-0000-00000",
                    environment = ConveltEnvironment.SANDBOX,
                    idempotencyKey = idempotencyKey,
                ),
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()

            assertEquals("/v1/client/google/purchases", request.path)
            assertEquals("test-sdk-key", request.getHeader("x-convelt-sdk-key"))
            assertEquals(idempotencyKey, request.getHeader("Idempotency-Key"))
            assertEquals("access_granted", outcome.outcome)
            assertTrue(body.contains(""""app_environment_id":"$appEnvironmentId""""))
            assertTrue(body.contains(""""installation_id":"$installationId""""))
            assertTrue(body.contains(""""customer_id":"$customerId""""))
            assertTrue(body.contains(""""package_name":"ai.lingospeak.one""""))
            assertTrue(body.contains(""""purchase_token":"purchase-token-1""""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun outcomeDecodesCurrentSnapshotShape() {
        val appEnvironmentId = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        val payload = """
            {
              "outcome": "access_granted",
              "display_class": "access_granted",
              "retryable": false,
              "ready_to_finish": true,
              "snapshot": {
                "kid": "kid-1",
                "iss": "convelt",
                "aud": "lingospeak",
                "customer_id": "$customerId",
                "external_user_id": "user-1",
                "app_environment_id": "$appEnvironmentId",
                "environment": "sandbox",
                "snapshot_version": 1,
                "projection_schema_version": 1,
                "config_version": "test-config-v1",
                "signature_algorithm": "hmac-sha256",
                "signature": "signature",
                "issued_at": "2026-06-18T00:00:00Z",
                "expires_at": "2026-06-19T00:00:00Z",
                "entitlements": [{
                  "key": "pro",
                  "provider_state": "subscribed",
                  "access_state": "active",
                  "active": true,
                  "product_id": "ai.lingospeak.pro.annual",
                  "renews_at": null,
                  "expires_at": null,
                  "is_sandbox": true,
                  "ownership_status": "owned_by_current_user",
                  "verification": "verified"
                }]
              }
            }
        """.trimIndent()

        val response = conveltJson.decodeFromString<ConveltOutcomeResponse>(payload)

        assertEquals(ConveltResolvedOutcome.Active, response.resolvedOutcome)
        assertEquals("kid-1", response.snapshot?.kid)
        assertEquals("test-config-v1", response.snapshot?.configVersion)
        assertEquals("pro", response.snapshot?.entitlements?.single()?.key)
    }
}
