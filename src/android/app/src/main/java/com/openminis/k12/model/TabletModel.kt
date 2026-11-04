package com.openminis.k12.model

import com.openminis.k12.device.SyncClient
import org.json.JSONObject

/**
 * Server-delivered tablet chat-model settings (ADR-0015).
 * The API key is never part of [toString] or log lines.
 */
data class TabletModelSpec(
    val version: Long,
    val cleared: Boolean,
    val providerType: String,
    val baseUrl: String,
    val modelName: String,
    val visionModelName: String,
    val apiKey: String,
) {
    override fun toString(): String =
        "TabletModelSpec(version=$version,cleared=$cleared,providerType=$providerType,baseUrl=$baseUrl,model=$modelName,vision=$visionModelName,apiKeySet=${apiKey.isNotEmpty()})"
}

data class LocalModelSnapshot(
    val defaultGroupId: String?,
    val visionGroupId: String?,
    val enabledInstanceIds: List<String>,
)

data class StoredTabletModel(
    val version: Long,
    val cleared: Boolean,
    val providerType: String,
    val baseUrl: String,
    val modelName: String,
    val visionModelName: String,
    val apiKey: String,
    val priorCaptured: Boolean,
    val priorDefaultGroupId: String?,
    val priorVisionGroupId: String?,
    val priorEnabledInstanceIds: List<String>,
) {
    fun redactedLine(): String =
        "tablet-model version=$version cleared=$cleared provider=$providerType model=$modelName vision=$visionModelName apiKeySet=${apiKey.isNotEmpty()}"

    override fun toString(): String = redactedLine()
}

interface ModelSecretStore {
    fun load(): StoredTabletModel?
    fun save(value: StoredTabletModel)
    fun wipe()
}

interface UpstreamModelPort {
    fun capture(): LocalModelSnapshot
    fun apply(spec: TabletModelSpec)
    fun restore(snapshot: LocalModelSnapshot)
    fun removeManaged()
}

class MemoryModelSecretStore : ModelSecretStore {
    var current: StoredTabletModel? = null
    override fun load(): StoredTabletModel? = current
    override fun save(value: StoredTabletModel) {
        current = value
    }
    override fun wipe() {
        current = null
    }
}

sealed class ModelFetch {
    data class Body(val json: String) : ModelFetch()
    data class Denied(val code: Int) : ModelFetch()
    data class Failed(val code: Int) : ModelFetch()
}

sealed class SyncOutcome {
    data object Unchanged : SyncOutcome()
    data object Applied : SyncOutcome()
    data object Cleared : SyncOutcome()
    data class Denied(val code: Int) : SyncOutcome()
    data object Failed : SyncOutcome()
}

data class ModelPageStatus(
    val managed: Boolean,
    val readOnly: Boolean,
    val providerType: String,
    val baseUrl: String,
    val modelName: String,
    val visionModelName: String,
    val apiKeySet: Boolean,
    val version: Long,
) {
    fun maskedKeyLabel(): String = if (apiKeySet) "已设置" else "未设置"

    override fun toString(): String =
        "ModelPageStatus(managed=$managed,readOnly=$readOnly,provider=$providerType,model=$modelName,version=$version,apiKeySet=$apiKeySet)"
}

class ModelLog {
    val lines = mutableListOf<String>()
    fun info(message: String) {
        lines.add(message)
    }
}

fun parseTabletModel(json: String): TabletModelSpec? {
    return try {
        val obj = JSONObject(json)
        if (!obj.has("version")) return null
        val cleared = obj.optBoolean("cleared", false)
        TabletModelSpec(
            version = obj.optLong("version", 0L),
            cleared = cleared,
            providerType = obj.optString("provider_type", ""),
            baseUrl = obj.optString("base_url", ""),
            modelName = obj.optString("model_name", ""),
            visionModelName = obj.optString("vision_model_name", ""),
            apiKey = if (cleared) "" else obj.optString("api_key", ""),
        )
    } catch (_: Exception) {
        null
    }
}

/** Version carried on the heartbeat. A key in that body is not read. */
fun heartbeatModelVersion(json: String): Long {
    return try {
        JSONObject(json).optLong("tablet_model_version", 0L)
    } catch (_: Exception) {
        0L
    }
}

object K12ModelSync {
    fun note(
        announcedVersion: Long,
        store: ModelSecretStore,
        upstream: UpstreamModelPort,
        fetch: () -> ModelFetch,
        log: ModelLog? = null,
        force: Boolean = false,
    ): SyncOutcome {
        val applied = store.load()?.version ?: 0L
        if (!force && announcedVersion == applied) return SyncOutcome.Unchanged
        return when (val fetched = fetch()) {
            is ModelFetch.Denied -> SyncOutcome.Denied(fetched.code)
            is ModelFetch.Failed -> SyncOutcome.Failed
            is ModelFetch.Body -> applyBody(fetched.json, store, upstream, log)
        }
    }

    fun applyBody(
        json: String,
        store: ModelSecretStore,
        upstream: UpstreamModelPort,
        log: ModelLog? = null,
    ): SyncOutcome {
        val spec = parseTabletModel(json) ?: return SyncOutcome.Failed
        val current = store.load()
        if (spec.cleared) {
            if (current != null && current.priorCaptured && !current.cleared) {
                upstream.restore(current.snapshot())
            } else {
                upstream.removeManaged()
            }
            val stored = emptyStored(spec.version)
            store.save(stored)
            log?.info(stored.redactedLine())
            return SyncOutcome.Cleared
        }
        if (spec.providerType.isBlank() || spec.baseUrl.isBlank() || spec.modelName.isBlank() || spec.apiKey.isEmpty()) {
            return SyncOutcome.Failed
        }
        val prior = if (current != null && current.priorCaptured && !current.cleared) {
            current.snapshot()
        } else {
            upstream.capture()
        }
        upstream.apply(spec)
        val stored = StoredTabletModel(
            version = spec.version,
            cleared = false,
            providerType = spec.providerType,
            baseUrl = spec.baseUrl,
            modelName = spec.modelName,
            visionModelName = spec.visionModelName,
            apiKey = spec.apiKey,
            priorCaptured = true,
            priorDefaultGroupId = prior.defaultGroupId,
            priorVisionGroupId = prior.visionGroupId,
            priorEnabledInstanceIds = prior.enabledInstanceIds,
        )
        store.save(stored)
        log?.info(stored.redactedLine())
        return SyncOutcome.Applied
    }

    fun unpair(store: ModelSecretStore, upstream: UpstreamModelPort) {
        val current = store.load()
        if (current != null && current.priorCaptured && !current.cleared) {
            upstream.restore(current.snapshot())
        } else {
            upstream.removeManaged()
        }
        store.wipe()
    }

    fun pageStatus(store: ModelSecretStore): ModelPageStatus {
        val stored = store.load()
        val managed = stored != null && !stored.cleared && stored.version > 0L &&
            stored.providerType.isNotBlank() && stored.apiKey.isNotEmpty()
        return ModelPageStatus(
            managed = managed,
            readOnly = managed,
            providerType = if (managed) stored!!.providerType else "",
            baseUrl = if (managed) stored!!.baseUrl else "",
            modelName = if (managed) stored!!.modelName else "",
            visionModelName = if (managed) stored!!.visionModelName else "",
            apiKeySet = managed,
            version = if (managed) stored!!.version else 0L,
        )
    }

    private fun emptyStored(version: Long) = StoredTabletModel(
        version = version,
        cleared = true,
        providerType = "",
        baseUrl = "",
        modelName = "",
        visionModelName = "",
        apiKey = "",
        priorCaptured = false,
        priorDefaultGroupId = null,
        priorVisionGroupId = null,
        priorEnabledInstanceIds = emptyList(),
    )
}

private fun StoredTabletModel.snapshot() = LocalModelSnapshot(
    defaultGroupId = priorDefaultGroupId,
    visionGroupId = priorVisionGroupId,
    enabledInstanceIds = priorEnabledInstanceIds,
)

/**
 * Heartbeat, then fetch the full config only when the announced version changed.
 * The heartbeat body is not a place to read an API key.
 */
fun SyncClient.pullTabletModel(
    configVersionApplied: Int,
    appVersion: String,
    store: ModelSecretStore,
    upstream: UpstreamModelPort,
    log: ModelLog? = null,
): SyncOutcome {
    val beat = heartbeat(configVersionApplied, appVersion, dailyUsedMinutes = 0)
    return K12ModelSync.note(beat.tabletModelVersion, store, upstream, {
        val resp = fetchTabletModel()
        when (resp.code) {
            200 -> ModelFetch.Body(resp.bodyText)
            401, 403 -> ModelFetch.Denied(resp.code)
            else -> ModelFetch.Failed(resp.code)
        }
    }, log)
}
