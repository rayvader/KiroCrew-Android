package io.github.rayvader.kirocrew.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseHistoryTest {

    private fun release(tag: String, body: String?, pre: Boolean = false, draft: Boolean = false) =
        JSONObject().put("tag_name", tag).put("prerelease", pre).put("draft", draft)
            .put("body", body ?: JSONObject.NULL)

    private fun notes(vararg lines: String) = "## What's new\n\n" + lines.joinToString("\n") { "- $it" }

    @Test
    fun keepsRealReleasesNewestFirst() {
        val json = JSONArray()
            .put(release("v2.0.9", notes("Nine")))
            .put(release("v2.0.10", notes("Ten a", "Ten b")))
            .put(release("v1.0.50", notes("Old")))
            .toString()
        val list = ReleaseHistory.parse(json)
        assertEquals(listOf("2.0.10", "2.0.9", "1.0.50"), list.map { it.version })
        assertEquals(listOf("Ten a", "Ten b"), list[0].notes)
    }

    @Test
    fun skipsBranchBuildsDraftsAndEmptyNotes() {
        val json = JSONArray()
            .put(release("v2.0.11", notes("Branch"), pre = true))
            .put(release("v2.0.12", notes("Draft"), draft = true))
            .put(release("v2.0.13", "## What's new\n\nChangelog unavailable for this build."))
            .put(release("v2.0.14", null))
            .put(release("nightly", notes("Bad tag")))
            .put(release("v2.0.15", notes("Real")))
            .toString()
        assertEquals(listOf("2.0.15"), ReleaseHistory.parse(json).map { it.version })
    }

    @Test
    fun capsTheList() {
        val all = JSONArray()
        for (i in 1..40) all.put(release("v2.0.$i", notes("Change $i")))
        val list = ReleaseHistory.parse(all.toString())
        assertEquals(ReleaseHistory.MAX, list.size)
        assertEquals("2.0.40", list.first().version)
    }

    @Test
    fun badJsonGivesNothing() {
        assertTrue(ReleaseHistory.parse("not json").isEmpty())
        assertTrue(ReleaseHistory.parse("{}").isEmpty())
    }
}
