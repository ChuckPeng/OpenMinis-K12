package com.openminis.k12.graph

import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * Runtime-only knowledge graph (ADR-0011). NC blobs never ship in the APK;
 * only a synthetic sample may live under assets for CI. License must be
 * CC BY-NC-SA 4.0 on real downloads.
 */
data class GraphManifestEntry(
    val id: String,
    val path: String,
    val url: String,
    val sha256: String,
    val license: String,
)

data class GraphNode(
    val id: String,
    val title: String,
    val prereqIds: List<String> = emptyList(),
)

data class KnowledgeGraph(
    val nodes: Map<String, GraphNode>,
) {
    fun prereqClosure(nodeId: String, maxDepth: Int = 8): List<String> {
        val start = nodes[nodeId] ?: return emptyList()
        val seen = linkedSetOf<String>()
        val q = ArrayDeque<Pair<String, Int>>()
        for (p in start.prereqIds) q.add(p to 1)
        while (q.isNotEmpty()) {
            val (id, depth) = q.removeFirst()
            if (id in seen || depth > maxDepth) continue
            seen += id
            val node = nodes[id] ?: continue
            for (p in node.prereqIds) q.add(p to depth + 1)
        }
        return seen.toList()
    }

    fun unmappedLabel(): String = "未映射"
}

object GraphFormats {
    const val REQUIRED_LICENSE = "CC BY-NC-SA 4.0"

    /** Minimal line protocol used by the synthetic sample (not NC format). */
    fun parseSampleGraph(text: String): KnowledgeGraph {
        val nodes = mutableMapOf<String, GraphNode>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // id|title|prereq1,prereq2
            val parts = line.split('|')
            if (parts.size < 2) continue
            val id = parts[0].trim()
            val title = parts[1].trim()
            val prereqs = parts.getOrNull(2)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            nodes[id] = GraphNode(id, title, prereqs)
        }
        return KnowledgeGraph(nodes)
    }

    fun parseManifest(text: String): List<GraphManifestEntry> {
        val out = mutableListOf<GraphManifestEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // id|path|url|sha256|license
            val p = line.split('|')
            if (p.size < 5) continue
            out += GraphManifestEntry(
                id = p[0].trim(),
                path = p[1].trim(),
                url = p[2].trim(),
                sha256 = p[3].trim().lowercase(),
                license = p[4].trim(),
            )
        }
        return out
    }

    fun sha256Hex(bytes: ByteArray): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
        return dig.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Downloads (or copies) graph blobs into a private directory after sha256 + license checks.
 */
class GraphStore(private val root: File) {
    var graph: KnowledgeGraph? = null
        private set

    fun installFromBytes(entry: GraphManifestEntry, bytes: ByteArray): Result<File> {
        if (entry.license != GraphFormats.REQUIRED_LICENSE) {
            return Result.failure(IllegalArgumentException("license must be ${GraphFormats.REQUIRED_LICENSE}"))
        }
        val actual = GraphFormats.sha256Hex(bytes)
        if (actual != entry.sha256.lowercase()) {
            return Result.failure(IllegalArgumentException("sha256 mismatch"))
        }
        val dest = File(root, entry.path)
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        graph = GraphFormats.parseSampleGraph(bytes.toString(Charsets.UTF_8))
        return Result.success(dest)
    }

    fun loadExisting(entry: GraphManifestEntry): Boolean {
        val dest = File(root, entry.path)
        if (!dest.isFile) return false
        val bytes = dest.readBytes()
        if (GraphFormats.sha256Hex(bytes) != entry.sha256.lowercase()) return false
        graph = GraphFormats.parseSampleGraph(bytes.toString(Charsets.UTF_8))
        return true
    }

    fun prereqBacktrack(nodeId: String): List<String> {
        val g = graph ?: return emptyList()
        if (nodeId !in g.nodes) return listOf(g.unmappedLabel())
        return g.prereqClosure(nodeId)
    }
}

object GraphCatalog {
    /** In-app constant per T-52 — real NC URLs are owner-configured later. */
    const val MANIFEST_ASSET = "k12/graph/manifest.sample.txt"
    const val SAMPLE_GRAPH_ASSET = "k12/graph/sample-graph.txt"
}
