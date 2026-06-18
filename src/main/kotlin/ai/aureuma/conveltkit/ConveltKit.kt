package ai.aureuma.conveltkit

object ConveltKit {
    const val sdkName: String = "ConveltKit"
    const val sdkVersion: String = ConveltKitVersion.value

    fun createClient(
        applicationContext: android.content.Context? = null,
        configuration: ConveltConfiguration,
        outboxStore: ConveltOutboxStore = InMemoryConveltOutboxStore(),
        httpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient(),
    ): ConveltClient = ConveltClient(
        applicationContext = applicationContext,
        configuration = configuration,
        outboxStore = outboxStore,
        httpClient = httpClient,
    )
}
