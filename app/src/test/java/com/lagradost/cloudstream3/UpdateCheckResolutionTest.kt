package com.lagradost.cloudstream3

import com.lagradost.cloudstream4.viewmodels.UpdateVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "check for updates" button resolves everything from the release list.
 *
 * These tests pin the resolution rules so a change in the shape of the GitHub response
 * cannot silently make the button do nothing.
 */
class UpdateCheckResolutionTest {
    /** The installed prerelease reports "4.9.6-PRE", the release is tagged "v4.9.6". */
    @Test
    fun `the flavour suffix does not hide the version`() {
        assertEquals("4.9.6", UpdateVersion.nameOf("4.9.6-PRE"))
        assertEquals("4.9.6", UpdateVersion.nameOf("v4.9.6"))
    }

    /** Same version means no dialog. */
    @Test
    fun `an installed version equal to the release is not an update`() {
        assertFalse(UpdateVersion.isUpdate("4.9.6-PRE", "4.9.6"))
    }

    /** A newer release means the dialog appears. */
    @Test
    fun `a newer release is an update`() {
        assertTrue(UpdateVersion.isUpdate("4.9.6-PRE", "4.9.7"))
    }

    /** An older release must never be offered, so the button cannot downgrade. */
    @Test
    fun `an older release is not an update`() {
        assertFalse(UpdateVersion.isUpdate("4.10.0-PRE", "4.9.9"))
    }

    /**
     * A release body that is not a release (an error payload) yields no version, which is
     * how the callers detect "nothing to offer" instead of throwing.
     */
    @Test
    fun `an error body has no version`() {
        assertNull(UpdateVersion.nameOf("""{"message":"Not Found"}"""))
        assertNull(UpdateVersion.nameOf(""))
        assertNull(UpdateVersion.nameOf(null))
    }

    /** The release asset of this fork parses to the same version as its tag. */
    @Test
    fun `tag and asset agree`() {
        assertEquals(
            UpdateVersion.nameOf("v4.9.6"),
            UpdateVersion.nameOf("cloudstream-4.9.6-arm64-v8a.apk"),
        )
    }

    /** A weight exists for every real version string, so comparisons never fall through. */
    @Test
    fun `real versions produce a weight`() {
        assertNotNull(UpdateVersion.weightOf("4.9.6"))
        assertNotNull(UpdateVersion.weightOf("4.9.6-PRE"))
        assertNotNull(UpdateVersion.weightOf("v4.9.6"))
    }
}
