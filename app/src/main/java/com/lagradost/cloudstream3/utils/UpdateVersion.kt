package com.lagradost.cloudstream3.utils

/**
 * Version parsing for the in-app updater.
 *
 * Kept as pure functions so they can be unit tested without an Android Context.
 *
 * The updater used to match the version with `(.*?((\d+)\.(\d+)\.(\d+))\.apk)`, which only
 * accepts a name that ends in ".apk" right after the version. An asset called
 * "cloudstream-4.9.0-arm64-v8a.apk" did not match, so every release was silently treated as
 * "no update available". The version is now read from the tag first and from the asset name
 * as a fallback, with no constraint on the suffix.
 */
object UpdateVersion {
    private val versionRegex = Regex("""(\d+)\.(\d+)\.(\d+)""")

    /** Turns a version triple into a number that compares correctly. */
    fun weight(major: Int, minor: Int, patch: Int): Int =
        major * 100_000_000 + minor * 10_000 + patch

    /** The version inside [text] as a comparable number, or null when there is none. */
    fun weightOf(text: String?): Int? {
        if (text == null) return null
        val match = versionRegex.find(text) ?: return null
        val (major, minor, patch) = match.destructured
        return weight(major.toInt(), minor.toInt(), patch.toInt())
    }

    /** The version inside [text] as "major.minor.patch", or null when there is none. */
    fun nameOf(text: String?): String? {
        if (text == null) return null
        return versionRegex.find(text)?.value
    }

    /**
     * True when [installed] is older than [offered]. A version that cannot be read is never
     * considered an update, which is what the callers rely on.
     */
    fun isUpdate(installed: String?, offered: String?): Boolean {
        val installedWeight = weightOf(installed) ?: return false
        val offeredWeight = weightOf(offered) ?: return false
        return installedWeight < offeredWeight
    }
}
