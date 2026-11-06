package com.quietchapter.narrative

import org.json.JSONObject

/** Bounded sidecar decoder. A valid document is not proof its asset is currently selected. */
object NarrativeTimelineCodec {
    fun decode(text: String): NarrativeTimeline? = runCatching {
        require(text.length <= 2_000_000)
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1)
        require(root.getString("analysisVersion") == "quietchapter-narrative-1")
        require(root.getString("status") in listOf("READY_PARTIAL", "BASIC_ONLY"))
        val chapters = root.getJSONArray("chapters")
        val annotations = root.getJSONArray("annotations")
        require(chapters.length() in 1..1_000 && annotations.length() <= 20_000)
        NarrativeTimeline(root.getString("assetSha256"), root.getString("language"), root.getLong("durationMs"),
            root.getString("analysisVersion"), (0 until chapters.length()).map {
                val c = chapters.getJSONObject(it)
                NarrativeChapter(c.getString("id"), c.getLong("startMs"), c.getLong("endMs"))
            }, (0 until annotations.length()).map {
                val a = annotations.getJSONObject(it)
                NarrativeAnnotation(a.getString("id"), a.getString("chapterId"), a.getLong("startMs"), a.getLong("endMs"),
                    NarrativeKind.valueOf(a.getString("kind")), NarrativeSource.valueOf(a.getString("source")),
                    NarrativeReliability.valueOf(a.getString("reliability")), a.optString("text").takeIf { value -> value.isNotBlank() })
            }).takeIf { it.valid }
    }.getOrNull()
}
