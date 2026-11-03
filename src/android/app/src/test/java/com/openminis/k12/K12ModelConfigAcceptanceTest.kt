package com.openminis.k12

import com.openminis.app.data.model.ProviderType
import com.openminis.k12.device.DeviceSigner
import com.openminis.k12.device.HttpResponse
import com.openminis.k12.device.HttpTransport
import com.openminis.k12.device.SyncClient
import com.openminis.k12.device.SyncConfig
import com.openminis.k12.model.K12ModelRuntime
import com.openminis.k12.model.K12ModelSync
import com.openminis.k12.model.LocalModelSnapshot
import com.openminis.k12.model.MemoryModelSecretStore
import com.openminis.k12.model.ModelLog
import com.openminis.k12.model.SyncOutcome
import com.openminis.k12.model.TabletModelSpec
import com.openminis.k12.model.UpstreamModelPort
import com.openminis.k12.model.heartbeatModelVersion
import com.openminis.k12.model.pullTabletModel
import com.openminis.k12.model.resolveProviderType
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WP-B-9. Server-managed tablet model config. Synthetic values only.
 */
class K12ModelConfigAcceptanceTest {
    private val secret = "synthetic-tablet-key-9f3a"

    @After
    fun resetRuntime() {
        K12ModelRuntime.resetForTest()
    }

    @Test
    fun heartbeatCarriesVersionAndDropsKey() {
        val seen = mutableListOf<String>()
        val client = client { url ->
            seen.add(url)
            val body = JSONObject()
                .put("server_time", "2026-11-03T00:00:00Z")
                .put("tablet_model_version", 4)
                .put("api_key", secret)
                .put("config", JSONObject().put("api_key", secret).put("daily_time_limit_minutes", 40))
            HttpResponse(200, emptyMap(), body.toString().toByteArray())
        }
        val result = client.heartbeat(0, "1.0.0-rc.2", 0)
        assertEquals(4L, result.tabletModelVersion)
        assertEquals(40, result.config["daily_time_limit_minutes"])
        assertFalse(result.config.containsKey("api_key"))
        assertFalse(result.toString().contains(secret))
        assertEquals(4L, heartbeatModelVersion("""{"tablet_model_version":4,"api_key":"$secret"}"""))
        assertFalse(SyncClient.ALLOWED_CONFIG_KEYS.contains("api_key"))
        assertTrue(seen.none { it.contains(secret) })
    }

    @Test
    fun fetchAppliesEncryptedRecordAndSettingsStayReadOnly() {
        val store = MemoryModelSecretStore()
        val upstream = FakeUpstream(LocalModelSnapshot("local-group", "local-vision", listOf("local-1")))
        val log = ModelLog()
        K12ModelRuntime.installForTest(store, upstream)
        var fetches = 0
        val client = client { url ->
            when {
                url.endsWith("/api/v1/device/heartbeat") -> json(
                    """{"server_time":"2026-11-03T00:00:00Z","tablet_model_version":3}""",
                )
                url.endsWith("/api/v1/device/tablet-model") -> {
                    fetches++
                    json(
                        """{"version":3,"cleared":false,"provider_type":"openAI","base_url":"https://example.invalid/v1","model_name":"chat-model","vision_model_name":"vision-model","api_key":"$secret"}""",
                    )
                }
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
        }
        val applied = client.pullTabletModel(0, Version.NAME, store, upstream, log)
        assertTrue(applied is SyncOutcome.Applied)
        assertEquals(1, fetches)
        assertEquals(secret, store.load()?.apiKey)
        assertEquals("https://example.invalid/v1", upstream.applied.single().baseUrl)
        assertEquals("vision-model", upstream.applied.single().visionModelName)
        val page = K12ModelRuntime.pageStatus()
        assertTrue(page.managed)
        assertTrue(page.readOnly)
        assertTrue(K12ModelRuntime.editsLocked())
        assertEquals("已设置", page.maskedKeyLabel())
        assertFalse(page.toString().contains(secret))
        assertFalse(store.load().toString().contains(secret))
        assertTrue(log.lines.isNotEmpty())
        assertTrue(log.lines.none { it.contains(secret) })

        val again = client.pullTabletModel(0, Version.NAME, store, upstream, log)
        assertTrue(again is SyncOutcome.Unchanged)
        assertEquals(1, fetches)
    }

    @Test
    fun clearRestoresPriorLocalConfigAndUnpairWipesKey() {
        val store = MemoryModelSecretStore()
        val upstream = FakeUpstream(LocalModelSnapshot("local-group", "local-vision", listOf("local-1")))
        val log = ModelLog()
        val first = K12ModelSync.note(1, store, upstream, {
            body(1, cleared = false, vision = "vision-model")
        }, log)
        assertTrue(first is SyncOutcome.Applied)
        upstream.snapshot = LocalModelSnapshot("other-group", null, listOf("other"))
        val second = K12ModelSync.note(2, store, upstream, {
            body(2, cleared = false, vision = "")
        }, log)
        assertTrue(second is SyncOutcome.Applied)
        assertEquals("local-group", store.load()?.priorDefaultGroupId)

        val cleared = K12ModelSync.note(3, store, upstream, {
            com.openminis.k12.model.ModelFetch.Body("""{"version":3,"cleared":true,"api_key":"$secret"}""")
        }, log)
        assertTrue(cleared is SyncOutcome.Cleared)
        assertEquals("local-group", upstream.restored?.defaultGroupId)
        assertEquals(listOf("local-1"), upstream.restored?.enabledInstanceIds)
        assertTrue(store.load()?.apiKey.isNullOrEmpty())
        assertFalse(K12ModelSync.pageStatus(store).managed)
        assertFalse(store.load().toString().contains(secret))
        assertTrue(log.lines.none { it.contains(secret) })

        K12ModelSync.note(4, store, upstream, { body(4, cleared = false, vision = "vision-model") }, log)
        assertFalse(store.load()?.apiKey.isNullOrEmpty())
        K12ModelRuntime.installForTest(store, upstream)
        K12ModelRuntime.onUnpairOrReset()
        assertNull(store.load())
        assertFalse(K12ModelRuntime.managed())
        assertFalse(K12ModelRuntime.editsLocked())
    }

    @Test
    fun revokedAndPendingResponsesDoNotStoreTheKey() {
        val store = MemoryModelSecretStore()
        val upstream = FakeUpstream(LocalModelSnapshot(null, null, emptyList()))
        val denied = K12ModelSync.note(1, store, upstream, {
            com.openminis.k12.model.ModelFetch.Denied(401)
        }, null)
        assertTrue(denied is SyncOutcome.Denied)
        assertEquals(401, (denied as SyncOutcome.Denied).code)
        assertNull(store.load())
        assertTrue(upstream.applied.isEmpty())

        val forbidden = K12ModelSync.note(1, store, upstream, {
            com.openminis.k12.model.ModelFetch.Denied(403)
        }, null)
        assertTrue(forbidden is SyncOutcome.Denied)
        assertNull(store.load())
    }

    @Test
    fun providerTypeHasNoDefaultVendor() {
        assertEquals(ProviderType.openAI, resolveProviderType("openAI"))
        assertEquals(ProviderType.openAI, resolveProviderType("openai"))
        assertEquals(ProviderType.anthropic, resolveProviderType("Anthropic"))
        assertEquals(ProviderType.unsupported, resolveProviderType(""))
        assertEquals(ProviderType.unsupported, resolveProviderType("not-a-vendor"))
    }

    @Test
    fun keystoreStoreUsesAndroidKeystoreAndDoesNotLog() {
        val src = repoFile("src/android/app/src/main/java/com/openminis/k12/model/KeystoreModelSecretStore.kt").readText()
        assertTrue(src.contains("EncryptedPrefsFactory"))
        assertTrue(src.contains("k12_tablet_model"))
        assertFalse(src.contains("Log."))
        assertFalse(src.contains("println"))
        val port = repoFile("src/android/app/src/main/java/com/openminis/k12/model/ProviderRepositoryModelPort.kt").readText()
        assertTrue(port.contains("saveApiKey"))
        assertTrue(port.contains("addInstance") || port.contains("updateInstance"))
        assertFalse(port.contains("Log."))
        assertFalse(port.contains("println"))
        val workflow = repoFile(".github/workflows/android.yml").readText()
        assertTrue(workflow.contains("K12ModelConfigAcceptanceTest"))
    }

    private fun body(version: Int, cleared: Boolean, vision: String) =
        com.openminis.k12.model.ModelFetch.Body(
            """{"version":$version,"cleared":$cleared,"provider_type":"openAI","base_url":"https://example.invalid/v1","model_name":"chat-model","vision_model_name":"$vision","api_key":"$secret"}""",
        )

    private fun json(text: String) = HttpResponse(200, emptyMap(), text.toByteArray())

    private fun client(handler: (String) -> HttpResponse): SyncClient {
        val transport = object : HttpTransport {
            override fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                val joined = headers.values.joinToString(" ")
                assertFalse(joined.contains(secret))
                if (body != null) assertFalse(String(body, Charsets.UTF_8).contains(secret))
                return handler(url)
            }
        }
        return SyncClient(SyncConfig("https://example.invalid", "dev-1"), DeviceSigner.MockSigner("k"), transport)
    }

    private fun repoFile(name: String): File = listOf(
        File(name),
        File("../../$name"),
        File("../../../$name"),
    ).first { it.exists() }

    private class FakeUpstream(var snapshot: LocalModelSnapshot) : UpstreamModelPort {
        val applied = mutableListOf<TabletModelSpec>()
        var restored: LocalModelSnapshot? = null
        var removed = 0
        override fun capture(): LocalModelSnapshot = snapshot
        override fun apply(spec: TabletModelSpec) {
            applied.add(spec)
        }
        override fun restore(snapshot: LocalModelSnapshot) {
            restored = snapshot
        }
        override fun removeManaged() {
            removed++
        }
    }
}
