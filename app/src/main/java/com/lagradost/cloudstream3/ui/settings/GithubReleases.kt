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
        // Ask for the tag that is actually published instead of probing the requested one.
        // The requested tag ("pre-release") does not exist in this fork, and the 404 it
        // produced was logged as an error by the http layer on every start.
        val releases = getReleasesRaw(userName, repository)
        val release = releases.firstOrNull { it.tagName == tag }
            ?: releases.maxByOrNull { it.createdAt }
            ?: throw FileNotFoundException()

        return app.get(
            url = "https://api.github.com/repos/$userName/$repository/git/ref/tags/${release.tagName}",
            headers = defaultHeaders
        ).parsedSafe<GithubTag>()?.githubObject?.sha ?: throw FileNotFoundException()
    }

    /**
     * The releases of the repository, a draft has no published asset and is not returned.
     *
     * parsedSafe is used so an error body yields null instead of an exception.
     */
    private suspend fun getReleasesRaw(
        userName: String,
        repository: String,
    ): List<GithubRelease> = app.get(
        url = "https://api.github.com/repos/$userName/$repository/releases",
        headers = defaultHeaders
    ).parsedSafe<Array<GithubRelease>>()?.filter { !it.draft }.orEmpty()

    @Throws
    override suspend fun getRelease(
        userName: String,
        repository: String,
        prerelease: Boolean,
        prereleaseTag: String,
        contentType: String,
    ): GithubFile {
        // Resolve from the release list in one request instead of probing
        // /releases/tags/<tag> and /releases/latest first. Those endpoints answer 404 when
        // the tag does not exist or the repository has no non-prerelease release, and
        // every 404 was logged as an error by the http layer even though it is expected.
        val releases = getReleasesRaw(userName, repository)

        val latestRelease = if (prerelease) {
            releases.firstOrNull { it.tagName == prereleaseTag }
                ?: releases.maxByOrNull { it.createdAt }
                ?: throw FileNotFoundException()
        } else {
            // The newest published release that is not a prerelease
            releases.filter { !it.prerelease }.maxByOrNull { it.createdAt }
                ?: releases.maxByOrNull { it.createdAt }
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