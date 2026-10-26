package com.lagradost.cloudstream3.ui.settings

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.viewmodels.GithubFile
import com.lagradost.cloudstream4.viewmodels.GithubRepository
import com.lagradost.cloudstream4.viewmodels.UpdateVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.FileNotFoundException

object GithubReleases : GithubRepository {
    @Serializable
    private data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
        @JsonProperty("digest") @SerialName("digest") val digest: String? = null, // sha256:..., may be null
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("created_at") @SerialName("created_at") val createdAt: String, // YYYY-MM-DDTHH:MM:SSZ
        // A draft release has no published asset, it must never be offered as an update.
        @JsonProperty("draft") @SerialName("draft") val draft: Boolean = false,
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        //@JsonProperty("type") @SerialName("type") val type: String,
        ///@JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        //@JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    /** The repository that is checked for new versions, set in the build config. */
    val APK_USERNAME = BuildConfig.UPDATE_REPO_OWNER
    val APK_REPOSITORY = BuildConfig.UPDATE_REPO_NAME
    const val APK_PRERELEASE = "pre-release"
    const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"

    private val defaultHeaders = mapOf("Accept" to "application/vnd.github.v3+json")

    @Throws
    override suspend fun getSha(
        userName: String,
        repository: String,
        tag: String,
    ): String {
        // A missing tag returns {"message": "Not Found"}, which does not deserialize as a
        // GithubTag and threw a MissingFieldException on every startup update check.
        val byTag = parseOrNull {
            app.get(
                url = "https://api.github.com/repos/$userName/$repository/git/ref/tags/$tag",
                headers = defaultHeaders
            ).parsed<GithubTag>().githubObject.sha
        }
        if (byTag != null) return byTag

        // Fall back to the newest published release
        val fallbackTag = newestTag(userName, repository) ?: throw FileNotFoundException()
        return parseOrNull {
            app.get(
                url = "https://api.github.com/repos/$userName/$repository/git/ref/tags/$fallbackTag",
                headers = defaultHeaders
            ).parsed<GithubTag>().githubObject.sha
        } ?: throw FileNotFoundException()
    }

    /** The releases of the repository, a draft has no published asset and is not returned. */
    /**
     * Parses a GitHub response body, returning null instead of throwing on an error body.
     *
     * GitHub answers a missing resource with `{"message": "Not Found"}`. Feeding that to a
     * serializer produced `MissingFieldException: Fields [...] are required ...` on every
     * update check, which is what the log showed. Every call site goes through here so an
     * error body can never be mistaken for a release or a tag again.
     */
    private suspend inline fun <reified T> parseOrNull(block: () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            null
        }

    private suspend fun getReleasesRaw(
        userName: String,
        repository: String,
    ): List<GithubRelease> = parseOrNull {
        app.get(
            url = "https://api.github.com/repos/$userName/$repository/releases",
            headers = defaultHeaders
        ).parsed<Array<GithubRelease>>()
    }?.filter { !it.draft }.orEmpty()

    /**
     * The tag of the newest release, used when the requested prerelease tag does not exist.
     *
     * The prerelease channel is a rolling git tag in upstream, but this fork publishes
     * versioned releases instead. Asking for a tag that is not there returned
     * `{"message": "Not Found"}`, which then failed to deserialize as GithubTag and
     * surfaced as a MissingFieldException on every update check.
     */
    private suspend fun newestTag(userName: String, repository: String): String? =
        getReleasesRaw(userName, repository).maxByOrNull { it.createdAt }?.tagName

    @Throws
    override suspend fun getRelease(
        userName: String,
        repository: String,
        prerelease: Boolean,
        prereleaseTag: String,
        contentType: String,
    ): GithubFile {
        val latestRelease = if (prerelease) {
            // Prefer the requested prerelease tag, but fall back to the newest published
            // release so a missing tag does not break the whole update check.
            parseOrNull {
                app.get(
                    url = "https://api.github.com/repos/$userName/$repository/releases/tags/$prereleaseTag",
                    headers = defaultHeaders
                ).parsed<GithubRelease>()
            } ?: getReleasesRaw(userName, repository).maxByOrNull { it.createdAt }
            ?: throw FileNotFoundException()
        } else {
            // The latest release is the most recent non-prerelease, non-draft release.
            // GitHub answers 404 when the repository has none.
            parseOrNull {
                app.get(
                    url = "https://api.github.com/repos/$userName/$repository/releases/latest",
                    headers = defaultHeaders
                ).parsed<GithubRelease>()
            } ?: getReleasesRaw(userName, repository).maxByOrNull { it.createdAt }
            ?: throw FileNotFoundException()
        }

        // Find the first correct asset, given that we might have other binaries we release in the same version
        val foundAsset = latestRelease.assets.firstOrNull { asset ->
            asset.contentType == contentType
        } ?: latestRelease.assets.firstOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true)
        }

        if (foundAsset == null) {
            throw FileNotFoundException()
        }

        return GithubFile(
            digest = foundAsset.digest,
            downloadUrl = foundAsset.browserDownloadUrl,
            displayName = foundAsset.name.substringBeforeLast("."),
            changeLog = latestRelease.body,
            tagName = latestRelease.tagName,
            versionName = UpdateVersion.nameOf(latestRelease.tagName)
                ?: UpdateVersion.nameOf(foundAsset.name),
            nodeId = latestRelease.nodeId
        )
    }
}