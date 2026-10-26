package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.utils.downloader.DownloadObjects

/**
 * What the resume-watching migration should do with one old entry.
 *
 * Kept as a pure decision so it can be unit tested without an Android Context.
 * See [DataStoreHelper.migrateResumeWatching].
 */
sealed class ResumeMigrationAction {
    /** The new layout has no entry for this item, copy the old one over. */
    data class Copy(
        val episodeId: Int?,
        val episode: Int?,
        val season: Int?,
        val isFromDownload: Boolean,
        val updateTime: Long,
    ) : ResumeMigrationAction()

    /** The new layout already has an entry; it is more precise, keep it. */
    data object KeepExisting : ResumeMigrationAction()
}

/**
 * The migration used to always write `episodeId = null` and it ran on every start, so a
 * saved position was dropped on each launch and a backup could never keep it. Now an
 * existing (new-format) entry is never overwritten, and the old data is only used to
 * fill entries that are missing.
 */
fun resumeMigrationAction(
    old: DownloadObjects.ResumeWatching,
    existing: DownloadObjects.ResumeWatching?,
): ResumeMigrationAction {
    if (existing != null) return ResumeMigrationAction.KeepExisting

    return ResumeMigrationAction.Copy(
        episodeId = old.episodeId,
        episode = old.episode,
        season = old.season,
        isFromDownload = old.isFromDownload,
        updateTime = old.updateTime,
    )
}
