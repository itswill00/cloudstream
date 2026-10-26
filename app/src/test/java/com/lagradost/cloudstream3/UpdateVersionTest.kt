package com.lagradost.cloudstream3

import com.lagradost.cloudstream4.viewmodels.UpdateVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the in-app updater version parsing.
 *
 * The updater used to require the asset to be named "...<version>.apk". An asset called
 * "cloudstream-4.9.0-arm64-v8a.apk" did not match, so every release was treated as "no
 * update available" and the updater never offered anything.
 */
class UpdateVersionTest {
    @Test
    fun `reads the version from a tag`() {
        assertEquals("4.9.0", UpdateVersion.nameOf("v4.9.0"))
        assertEquals("4.9.0", UpdateVersion.nameOf("4.9.0"))
    }

    @Test
    fun `reads the version from an asset name with an abi suffix`() {
        // This is the exact shape that broke the old regex.
        assertEquals("4.9.0", UpdateVersion.nameOf("cloudstream-4.9.0-arm64-v8a.apk"))
    }

    @Test
    fun `asset names with extra parts still parse`() {
        assertEquals("4.10.2", UpdateVersion.nameOf("CloudStream-4.10.2-universal.apk"))
        assertEquals("5.0.0", UpdateVersion.nameOf("cloudstream_5.0.0_arm64.apk"))
    }

    @Test
    fun `a name without a version has none`() {
        assertNull(UpdateVersion.nameOf("app-prerelease-release.apk"))
        assertNull(UpdateVersion.nameOf(null))
    }

    @Test
    fun `weights order by major then minor then patch`() {
        val v481 = UpdateVersion.weightOf("4.8.1")!!
        val v490 = UpdateVersion.weightOf("4.9.0")!!
        val v4100 = UpdateVersion.weightOf("4.10.0")!!
        val v5100 = UpdateVersion.weightOf("5.1.0")!!

        assertTrue(v481 < v490)
        // 4.10.0 must be newer than 4.9.0, a plain string compare would get this wrong.
        assertTrue(v490 < v4100)
        assertTrue(v4100 < v5100)
    }

    @Test
    fun `an update is offered only for a newer version`() {
        assertTrue(UpdateVersion.isUpdate("4.8.1", "4.9.0"))
        assertTrue(UpdateVersion.isUpdate("4.9.0-PRE", "4.10.0"))
        assertFalse(UpdateVersion.isUpdate("4.9.0", "4.9.0"))
        assertFalse(UpdateVersion.isUpdate("4.9.0", "4.8.1"))
    }

    @Test
    fun `the same version with a flavor suffix is not an update`() {
        // The prerelease flavour reports "4.9.1-PRE" while the release is tagged "4.9.1".
        // Comparing commit shas instead of versions offered this update forever.
        assertFalse(UpdateVersion.isUpdate("4.9.1-PRE", "4.9.1"))
        assertFalse(UpdateVersion.isUpdate("4.9.1", "4.9.1-PRE"))
        assertTrue(UpdateVersion.isUpdate("4.9.0-PRE", "4.9.1"))
        assertTrue(UpdateVersion.isUpdate("4.9.1-PRE", "4.10.0"))
    }

    @Test
    fun `an unreadable version never triggers an update`() {
        assertFalse(UpdateVersion.isUpdate(null, "4.9.0"))
        assertFalse(UpdateVersion.isUpdate("4.9.0", null))
        assertFalse(UpdateVersion.isUpdate("garbage", "4.9.0"))
        assertFalse(UpdateVersion.isUpdate("4.9.0", "not-a-version"))
    }
}
