package com.lagradost.cloudstream3

import com.lagradost.cloudstream4.viewmodels.UpdateVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater asks GitHub for several resources that can answer with an error body.
 *
 * `GET /releases/tags/pre-release` and `GET /git/ref/tags/pre-release` both return
 *
 * ```
 * HTTP 404 {"message": "Not Found"}
 * ```
 *
 * Parsing that as a release or a tag threw `MissingFieldException: Fields [...] are
 * required ...`, which showed up in the log on every startup. These tests pin the rules
 * the fallback relies on.
 */
class UpdateFallbackTest {
    /** A body that is not a release must not be treated as one. */
    @Test
    fun `an error body has no version`() {
        assertNull(UpdateVersion.nameOf("""{"message":"Not Found"}"""))
        assertNull(UpdateVersion.nameOf(""))
        assertNull(UpdateVersion.nameOf(null))
    }

    /** The newest of a list is the one with the greatest version, drafts already removed. */
    @Test
    fun `the newest release wins regardless of list order`() {
        val tags = listOf("v4.9.1", "v4.9.3", "v4.9.2")
        val newest = tags.maxByOrNull { UpdateVersion.weightOf(it) ?: Int.MIN_VALUE }
        assertEquals("v4.9.3", newest)
    }

    /** A release whose version cannot be read must not be chosen as the newest. */
    @Test
    fun `an unreadable tag is never the newest`() {
        val tags = listOf("v4.9.3", "nightly", "pre-release")
        val newest = tags.maxByOrNull { UpdateVersion.weightOf(it) ?: Int.MIN_VALUE }
        assertEquals("v4.9.3", newest)
    }

    /** The real asset name of this fork parses to the real version. */
    @Test
    fun `the release asset name parses`() {
        assertEquals("4.9.3", UpdateVersion.nameOf("cloudstream-4.9.3-arm64-v8a.apk"))
    }

    /** Already up to date: no dialog. */
    @Test
    fun `being on the newest version is not an update`() {
        val offered = UpdateVersion.nameOf("v4.9.3")
        assertFalse(UpdateVersion.isUpdate("4.9.3-PRE", offered))
    }

    /** One version behind: offer it. */
    @Test
    fun `being one version behind is an update`() {
        val offered = UpdateVersion.nameOf("v4.9.3")
        assertTrue(UpdateVersion.isUpdate("4.9.2-PRE", offered))
    }
}
