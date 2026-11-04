package com.openminis.k12.model

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.openminis.app.data.repository.ProviderRepository

/**
 * Process-wide tablet-model config. Upstream tests never call [install],
 * so provider screens stay editable until a server config is applied.
 */
object K12ModelRuntime {
    private val lock = Any()
    private var store: ModelSecretStore = MemoryModelSecretStore()
    private var upstream: UpstreamModelPort = NoopPort

    val revision = mutableStateOf(0)

    fun install(context: Context, repo: ProviderRepository) {
        synchronized(lock) {
            store = KeystoreModelSecretStore(context.applicationContext)
            upstream = ProviderRepositoryModelPort(repo)
        }
        bump()
    }

    fun managed(): Boolean = pageStatus().managed

    fun editsLocked(): Boolean = managed()

    fun pageStatus(): ModelPageStatus = synchronized(lock) { K12ModelSync.pageStatus(store) }

    fun noteHeartbeatVersion(
        version: Long,
        fetch: () -> ModelFetch,
        log: ModelLog? = null,
        force: Boolean = false,
    ): SyncOutcome {
        val outcome = synchronized(lock) { K12ModelSync.note(version, store, upstream, fetch, log, force) }
        bump()
        return outcome
    }

    fun onUnpairOrReset() {
        synchronized(lock) { K12ModelSync.unpair(store, upstream) }
        bump()
    }

    internal fun installForTest(secretStore: ModelSecretStore, port: UpstreamModelPort) {
        synchronized(lock) {
            store = secretStore
            upstream = port
        }
        bump()
    }

    internal fun resetForTest() {
        synchronized(lock) {
            store = MemoryModelSecretStore()
            upstream = NoopPort
        }
        bump()
    }

    private fun bump() {
        revision.value = revision.value + 1
    }

    private object NoopPort : UpstreamModelPort {
        override fun capture(): LocalModelSnapshot = LocalModelSnapshot(null, null, emptyList())
        override fun apply(spec: TabletModelSpec) = Unit
        override fun restore(snapshot: LocalModelSnapshot) = Unit
        override fun removeManaged() = Unit
    }
}
