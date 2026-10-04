package io.ltverdict.web

import io.ltverdict.core.ResourceSnapshotV1
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DecodedSnapshot(
    val snapshot: ResourceSnapshotV1,
    val semanticSha256: String,
)

internal class SnapshotCache {
    private val lock = Mutex()
    private var key: String? = null
    private var value: DecodedSnapshot? = null

    suspend fun <T> use(
        key: String,
        decode: () -> DecodedSnapshot,
        block: (DecodedSnapshot) -> T,
    ): T =
        lock.withLock {
            if (this.key == key) value?.let { return@withLock block(it) }
            value = null
            this.key = null
            val decoded = decode()
            this.key = key
            value = decoded
            block(decoded)
        }
}
