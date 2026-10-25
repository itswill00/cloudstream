package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData

/**
 * The repositories that ship with the app, so the user does not have to type a
 * URL by hand before they can pick a provider.
 *
 * They are stored under [RepositoryManager.PREBUILT_REPOSITORIES], which the
 * rest of the app already appends to the user repositories everywhere a
 * repository list is shown. The UI marks them as prebuilt (no delete button),
 * so a user cannot remove them by accident.
 */
object BuiltinRepositories {

    /** Mega repository, it aggregates the community repositories. */
    private val MEGA_REPO = RepositoryData(
        name = "Mega repository",
        url = "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json",
        iconUrl = null,
        description = "Add all repositories easily.",
    )

    /** Everything that is installed without asking the user. */
    val all: Array<RepositoryData> = arrayOf(MEGA_REPO)

    /**
     * Writes [all] to the preference read by
     * [RepositoryManager.PREBUILT_REPOSITORIES].
     *
     * It has to run before [RepositoryManager.PREBUILT_REPOSITORIES] is touched
     * for the first time, because that value is a `lazy` that caches whatever it
     * read, so a later write would be ignored by everything that already looked
     * at it. This is called from `CloudStreamApp.onCreate`, which is early enough.
     *
     * The existing value is kept when it is already there, so an update never
     * overwrites what a previous version of the app installed.
     */
    fun install(context: Context?, force: Boolean = false) {
        if (context == null) return
        val existing = getKey<Array<RepositoryData>>(KEY)
        if (!force && !existing.isNullOrEmpty()) return
        setKey(KEY, all)
    }

    private const val KEY = "PREBUILT_REPOSITORIES"
}
