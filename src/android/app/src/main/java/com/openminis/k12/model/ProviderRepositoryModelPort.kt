package com.openminis.k12.model

import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository

object ManagedModelIds {
    const val INSTANCE = "k12-server-managed"
    const val CHAT_ENTRY = "k12-server-chat-entry"
    const val VISION_ENTRY = "k12-server-vision-entry"
    const val CHAT_GROUP = "k12-server-chat-group"
    const val VISION_GROUP = "k12-server-vision-group"
}

/**
 * Maps a parent-supplied type string onto the existing provider enum.
 * An unknown string stays [ProviderType.unsupported]. There is no default vendor.
 */
fun resolveProviderType(raw: String): ProviderType {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ProviderType.unsupported
    ProviderType.entries.firstOrNull { it.name == trimmed }?.let { return it }
    ProviderType.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
    ProviderType.entries.firstOrNull { it.displayName.equals(trimmed, ignoreCase = true) }?.let { return it }
    return ProviderType.unsupported
}

/**
 * Applies a delivered config through [ProviderRepository] only.
 * Does not touch provider protocol implementations.
 */
class ProviderRepositoryModelPort(
    private val repo: ProviderRepository,
) : UpstreamModelPort {
    override fun capture(): LocalModelSnapshot {
        repo.ensureConfigLoaded()
        val enabled = repo.config.value.instances
            .filter { it.id != ManagedModelIds.INSTANCE && it.isEnabled }
            .map { it.id }
        return LocalModelSnapshot(
            defaultGroupId = repo.defaultPrimaryGroupId,
            visionGroupId = repo.visionGroupId,
            enabledInstanceIds = enabled,
        )
    }

    override fun apply(spec: TabletModelSpec) {
        repo.ensureConfigLoaded()
        val instance = ProviderInstance(
            id = ManagedModelIds.INSTANCE,
            label = "家长配置",
            providerType = resolveProviderType(spec.providerType),
            credentialType = ProviderCredential.apiKey,
            isEnabled = true,
            customBaseURL = spec.baseUrl,
        )
        if (repo.instance(ManagedModelIds.INSTANCE) == null) {
            repo.addInstance(instance)
        } else {
            repo.updateInstance(instance)
        }
        repo.saveApiKey(ManagedModelIds.INSTANCE, spec.apiKey)

        val separateVision = spec.visionModelName.isNotBlank() && spec.visionModelName != spec.modelName
        val chatInputs = if (separateVision) listOf("text") else listOf("text", "image")
        val keepModels = buildSet {
            add(spec.modelName)
            if (separateVision) add(spec.visionModelName)
        }
        upsertEntry(ManagedModelIds.CHAT_ENTRY, spec.modelName, chatInputs)
        if (separateVision) {
            upsertEntry(ManagedModelIds.VISION_ENTRY, spec.visionModelName, listOf("text", "image"))
        }
        // saveConfig rewrites each entry id to "{instance}/{model}". Comparing
        // against the pre-save uuid deletes the entry that was just written.
        staleManagedEntryIds(repo.config.value.modelEntries, ManagedModelIds.INSTANCE, keepModels)
            .forEach { repo.removeEntry(it) }

        val chatId = liveEntryId(spec.modelName)
        val visionMembers = if (separateVision) {
            mutableListOf(liveEntryId(spec.visionModelName))
        } else {
            mutableListOf(chatId)
        }
        upsertGroup(ManagedModelIds.CHAT_GROUP, "讲题", mutableListOf(chatId))
        upsertGroup(ManagedModelIds.VISION_GROUP, "拍题", visionMembers)
        repo.defaultPrimaryGroupId = ManagedModelIds.CHAT_GROUP
        repo.visionGroupId = ManagedModelIds.VISION_GROUP

        val others = repo.config.value.instances
            .filter { it.id != ManagedModelIds.INSTANCE && it.isEnabled }
            .map { it.id }
        others.forEach { id ->
            val current = repo.instance(id) ?: return@forEach
            repo.updateInstance(current.copy(isEnabled = false))
        }
    }

    override fun restore(snapshot: LocalModelSnapshot) {
        repo.ensureConfigLoaded()
        removeManaged()
        snapshot.enabledInstanceIds.forEach { id ->
            val current = repo.instance(id) ?: return@forEach
            if (!current.isEnabled) {
                repo.updateInstance(current.copy(isEnabled = true))
            }
        }
        repo.defaultPrimaryGroupId = snapshot.defaultGroupId
        repo.visionGroupId = snapshot.visionGroupId
    }

    override fun removeManaged() {
        repo.ensureConfigLoaded()
        if (repo.instance(ManagedModelIds.INSTANCE) != null) {
            repo.removeInstance(ManagedModelIds.INSTANCE)
        }
        repo.deleteApiKey(ManagedModelIds.INSTANCE)
    }

    private fun upsertEntry(legacyId: String, modelId: String, inputs: List<String>) {
        val composite = compositeEntryKey(ManagedModelIds.INSTANCE, modelId)
        val existing = repo.config.value.modelEntries.firstOrNull {
            it.providerInstanceId == ManagedModelIds.INSTANCE &&
                (it.id == legacyId || it.id == composite || it.baseModel.id == modelId)
        }
        val entry = ModelEntry(
            providerInstanceId = ManagedModelIds.INSTANCE,
            baseModel = LLMModel(
                id = modelId,
                displayName = modelId,
                provider = "k12",
                inputModalities = inputs,
                outputModalities = listOf("text"),
            ),
            isCustom = true,
            uuid = existing?.id ?: composite,
        )
        if (existing != null) repo.updateEntry(entry) else repo.addEntry(entry)
    }

    private fun liveEntryId(modelId: String): String {
        val composite = compositeEntryKey(ManagedModelIds.INSTANCE, modelId)
        return repo.config.value.modelEntries.firstOrNull {
            it.providerInstanceId == ManagedModelIds.INSTANCE &&
                (it.id == composite || it.baseModel.id == modelId)
        }?.id ?: composite
    }

    private fun upsertGroup(id: String, name: String, members: MutableList<String>) {
        val group = ModelGroup(id = id, name = name, memberEntryIds = members)
        val exists = repo.config.value.modelGroups.any { it.id == id }
        if (exists) repo.updateGroup(group) else repo.addGroup(group)
    }

}

/**
 * Entry ids that belong to the managed instance but are not one of the models
 * in the delivered config. Matched on the model id, because persistence
 * replaces the uuid this port first assigned.
 */
internal fun staleManagedEntryIds(
    entries: List<ModelEntry>,
    instanceId: String,
    keepModelIds: Set<String>,
): List<String> = entries
    .filter { it.providerInstanceId == instanceId && it.baseModel.id !in keepModelIds }
    .map { it.id }
