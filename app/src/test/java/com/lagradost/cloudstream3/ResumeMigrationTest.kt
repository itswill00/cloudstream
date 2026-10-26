package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.ResumeMigrationAction
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.resumeMigrationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the resume-watching migration.
 *
 * The bug: every start rewrote the entry with `episodeId = null`, so the saved position
 * was lost on each launch and restored backups could not keep it either.
 */
class ResumeMigrationTest {
    private fun resume(
        parentId: Int = 1,
        episodeId: Int? = null,
        episode: Int? = 3,
        season: Int? = 1,
        updateTime: Long = 1_000L,
        isFromDownload: Boolean = false,
    ) = DownloadObjects.ResumeWatching(
        parentId = parentId,
        episodeId = episodeId,
        episode = episode,
        season = season,
        updateTime = updateTime,
        isFromDownload = isFromDownload,
    )

    @Test
    fun `copies the old entry when the new layout has nothing`() {
        val old = resume(episodeId = 42, episode = 7, season = 2, updateTime = 5_000L)
        val action = resumeMigrationAction(old, existing = null)

        assertTrue(action is ResumeMigrationAction.Copy)
        action as ResumeMigrationAction.Copy
        assertEquals(42, action.episodeId)
        assertEquals(7, action.episode)
        assertEquals(2, action.season)
        assertEquals(5_000L, action.updateTime)
    }

    @Test
    fun `keeps the existing entry so a real episode id is not overwritten with null`() {
        val old = resume(episodeId = null)
        val existing = resume(episodeId = 99)

        assertEquals(ResumeMigrationAction.KeepExisting, resumeMigrationAction(old, existing))
    }

    @Test
    fun `keeps the existing entry even when it has no episode id`() {
        val old = resume(episodeId = 1)
        val existing = resume(episodeId = null)

        // The new-format entry is authoritative; migrating again must not change it.
        assertEquals(ResumeMigrationAction.KeepExisting, resumeMigrationAction(old, existing))
    }

    @Test
    fun `an old entry without an episode id still migrates once`() {
        val old = resume(episodeId = null)
        val action = resumeMigrationAction(old, existing = null)

        assertTrue(action is ResumeMigrationAction.Copy)
        action as ResumeMigrationAction.Copy
        assertEquals(null, action.episodeId)
    }
}
