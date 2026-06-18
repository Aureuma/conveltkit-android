package ai.aureuma.conveltkit

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

interface ConveltOutboxStore {
    suspend fun load(): List<ConveltPendingGooglePurchaseUpload>
    suspend fun save(entries: List<ConveltPendingGooglePurchaseUpload>)
}

class FileConveltOutboxStore(
    private val file: File,
) : ConveltOutboxStore {
    override suspend fun load(): List<ConveltPendingGooglePurchaseUpload> = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext emptyList()
        }
        val raw = file.readText()
        if (raw.isBlank()) {
            return@withContext emptyList()
        }
        conveltJson.decodeFromString(ListSerializer(ConveltPendingGooglePurchaseUpload.serializer()), raw)
    }

    override suspend fun save(entries: List<ConveltPendingGooglePurchaseUpload>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val payload = conveltJson.encodeToString(
            ListSerializer(ConveltPendingGooglePurchaseUpload.serializer()),
            entries,
        )
        file.writeText(payload)
    }

    companion object {
        fun defaultFile(context: Context): File =
            File(File(context.filesDir, "convelt"), "purchase-outbox.json")
    }
}

class InMemoryConveltOutboxStore : ConveltOutboxStore {
    private var entries: List<ConveltPendingGooglePurchaseUpload> = emptyList()

    override suspend fun load(): List<ConveltPendingGooglePurchaseUpload> = entries

    override suspend fun save(entries: List<ConveltPendingGooglePurchaseUpload>) {
        this.entries = entries
    }
}
