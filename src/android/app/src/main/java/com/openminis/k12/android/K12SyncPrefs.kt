package com.openminis.k12.android

import android.content.Context
import com.openminis.k12.device.PairedEndpoint
import com.openminis.k12.device.PendingDiagnostic
import com.openminis.k12.device.PendingStudyEvent
import com.openminis.k12.device.DiagnosticOutbox
import com.openminis.k12.device.StudyEventOutbox
import com.openminis.k12.device.diagnosticAllowed
import org.json.JSONArray
import org.json.JSONObject

/**
 * Paired endpoint, applied config version, and unacknowledged uploads.
 * Does not store the tablet model API key.
 */
class K12SyncPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun loadEndpoint(): PairedEndpoint? {
        val base = prefs.getString(KEY_BASE, null) ?: return null
        val device = prefs.getString(KEY_DEVICE, null) ?: return null
        if (base.isBlank() || device.isBlank()) return null
        return PairedEndpoint(
            baseUrl = base,
            deviceId = device,
            childId = prefs.getString(KEY_CHILD, "") ?: "",
            certFingerprint = prefs.getString(KEY_CERT, null),
        )
    }

    fun saveEndpoint(endpoint: PairedEndpoint) {
        prefs.edit()
            .putString(KEY_BASE, endpoint.baseUrl)
            .putString(KEY_DEVICE, endpoint.deviceId)
            .putString(KEY_CHILD, endpoint.childId)
            .putString(KEY_CERT, endpoint.certFingerprint)
            .apply()
    }

    fun appliedVersion(): Int = prefs.getInt(KEY_VERSION, 0)

    fun setAppliedVersion(version: Int) {
        prefs.edit().putInt(KEY_VERSION, version).apply()
    }

    val events: StudyEventOutbox = EventBox()
    val diagnostics: DiagnosticOutbox = DiagBox()

    private inner class EventBox : StudyEventOutbox {
        override fun add(event: PendingStudyEvent) = addEvent(event)
        override fun pending(): List<PendingStudyEvent> = pendingEvents()
        override fun ack(ids: Collection<String>) = ackEvents(ids)
        override fun clear() = clearQueues()
    }

    private inner class DiagBox : DiagnosticOutbox {
        override fun add(item: PendingDiagnostic): Boolean = addDiagnostic(item)
        override fun pending(): List<PendingDiagnostic> = pendingDiagnostics()
        override fun ack(id: String) = ackDiagnostic(id)
        override fun drop(id: String) = ackDiagnostic(id)
        override fun clear() = clearQueues()
    }

    private fun addEvent(event: PendingStudyEvent) {
        synchronized(lock) {
            val items = readEvents()
            if (items.any { it.eventId == event.eventId }) return
            val seq = if (event.seq > 0) event.seq else (items.maxOfOrNull { it.seq } ?: 0) + 1
            writeEvents(items + event.copy(seq = seq))
        }
    }

    private fun pendingEvents(): List<PendingStudyEvent> = synchronized(lock) { readEvents() }

    private fun ackEvents(ids: Collection<String>) {
        val drop = ids.toSet()
        synchronized(lock) {
            writeEvents(readEvents().filterNot { it.eventId in drop })
        }
    }

    private fun clearQueues() {
        synchronized(lock) {
            prefs.edit().remove(KEY_EVENTS).remove(KEY_DIAG).commit()
        }
    }

    private fun addDiagnostic(item: PendingDiagnostic): Boolean {
        if (!diagnosticAllowed(item.json)) return false
        synchronized(lock) {
            val items = readDiagnostics()
            if (items.any { it.id == item.id }) return true
            writeDiagnostics(items + item)
        }
        return true
    }

    private fun pendingDiagnostics(): List<PendingDiagnostic> = synchronized(lock) { readDiagnostics() }

    private fun ackDiagnostic(id: String) {
        synchronized(lock) { writeDiagnostics(readDiagnostics().filterNot { it.id == id }) }
    }

    fun clearAll() {
        synchronized(lock) { prefs.edit().clear().commit() }
    }

    private fun readEvents(): List<PendingStudyEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { index ->
                val obj = arr.getJSONObject(index)
                val payloadObj = obj.optJSONObject("payload") ?: JSONObject()
                val payload = mutableMapOf<String, Any?>()
                for (key in payloadObj.keys()) payload[key] = payloadObj.get(key)
                PendingStudyEvent(
                    eventId = obj.getString("event_id"),
                    seq = obj.optInt("seq", index + 1),
                    type = obj.getString("type"),
                    occurredAtMs = obj.optLong("occurred_at", 0L),
                    payload = payload,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeEvents(items: List<PendingStudyEvent>) {
        val arr = JSONArray()
        items.forEach { event ->
            val payload = JSONObject()
            event.payload.forEach { (key, value) -> if (value != null) payload.put(key, value) }
            arr.put(
                JSONObject()
                    .put("event_id", event.eventId)
                    .put("seq", event.seq)
                    .put("type", event.type)
                    .put("occurred_at", event.occurredAtMs)
                    .put("payload", payload),
            )
        }
        prefs.edit().putString(KEY_EVENTS, arr.toString()).commit()
    }

    private fun readDiagnostics(): List<PendingDiagnostic> {
        val raw = prefs.getString(KEY_DIAG, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { index ->
                val obj = arr.getJSONObject(index)
                PendingDiagnostic(obj.getString("id"), obj.getString("json"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeDiagnostics(items: List<PendingDiagnostic>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().put("id", item.id).put("json", item.json))
        }
        prefs.edit().putString(KEY_DIAG, arr.toString()).commit()
    }

    private companion object {
        const val NAME = "k12_sync"
        const val KEY_BASE = "base_url"
        const val KEY_DEVICE = "device_id"
        const val KEY_CHILD = "child_id"
        const val KEY_CERT = "cert"
        const val KEY_VERSION = "config_version"
        const val KEY_EVENTS = "events"
        const val KEY_DIAG = "diagnostics"
    }
}
