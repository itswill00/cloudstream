package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.BackupUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the backup key filter.
 *
 * The filter used to be a substring test against a hand maintained list, so deleting one
 * repository could drop unrelated keys and a renamed key ("backup_dir_path_key" vs the
 * real "backup_dir_key") silently stopped being excluded — leaking a local file path into
 * a backup that is meant to be shareable.
 */
class BackupKeyFilterTest {
    private fun transferable(key: String) = BackupUtils.run { key.isTransferable() }

    @Test
    fun `the real backup dir key is not transferred`() {
        // The key that is actually written by the settings screen.
        assertFalse(transferable("backup_dir_key"))
    }

    @Test
    fun `tokens are not transferred`() {
        assertFalse(transferable("anilist_token"))
        assertFalse(transferable("mal_token"))
        assertFalse(transferable("simkl_token"))
    }

    @Test
    fun `download paths are not transferred`() {
        assertFalse(transferable("download_path_key"))
        assertFalse(transferable("download_path_key_visual"))
    }

    @Test
    fun `normal settings are transferred`() {
        assertTrue(transferable("resize_mode"))
        assertTrue(transferable("automatic_backup_key"))
        assertTrue(transferable("result_sort"))
    }

    @Test
    fun `a key that merely contains an excluded name is still transferred`() {
        // With the old substring test this was dropped from the backup by accident.
        assertTrue(transferable("x_backup_dir_key_y"))
        assertTrue(transferable("my_anilist_token_backup"))
    }
}
