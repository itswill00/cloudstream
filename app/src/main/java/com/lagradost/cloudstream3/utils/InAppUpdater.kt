package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream4.viewmodels.UpdateVersion
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

object InAppUpdater {
    /** The repository that is checked for new versions, set in the build config. */
    private val githubUserName = BuildConfig.UPDATE_REPO_OWNER
    private val githubRepo = BuildConfig.UPDATE_REPO_NAME

    private const val PRERELEASE_PACKAGE_NAME = "com.lagradost.cloudstream3.prerelease"
    private const val LOG_TAG = "InAppUpdater"
    private const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"

    @Serializable
    private data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        // A draft release has no published assets, it must never be offered as an update.
        @JsonProperty("draft") @SerialName("draft") val draft: Boolean = false,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        @JsonProperty("type") @SerialName("type") val type: String,
        @JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    @Serializable
    private data class Update(
        @JsonProperty("shouldUpdate") @SerialName("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") @SerialName("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") @SerialName("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") @SerialName("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") @SerialName("updateNodeId") val updateNodeId: String?,
    )

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        return try {
            when {
                // No updates on debug version
                BuildConfig.DEBUG -> Update(false, null, null, null, null)
                BuildConfig.FLAVOR == "prerelease" || installPrerelease -> getPreReleaseUpdate()
                else -> getReleaseUpdate()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            Update(false, null, null, null, null)
        }
    }

    // The version is taken either from the tag name or from the name of the apk asset, so
    // a release does not break just because its asset is named differently. The old regex
    // only accepted "*.apk" and silently skipped everything else, which made the updater
    // report "no update" forever for any release with e.g. "-arm64-v8a" in the file name.
    /** The version of a release, from the tag or else from the name of its apk. */
    private fun GithubRelease.version(): String? =
        UpdateVersion.nameOf(tagName) ?: UpdateVersion.nameOf(apkAsset()?.name)

    /** The apk of a release, never some other asset that happens to be attached to it. */
    private fun GithubRelease.apkAsset(): GithubAsset? =
        assets.firstOrNull { it.contentType == APK_CONTENT_TYPE }
            ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    private suspend fun Activity.getReleaseUpdate(): Update {
        val url = "https://api.github.com/repos/$githubUserName/$githubRepo/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val response = parseJson<Array<GithubRelease>>(
            app.get(url, headers = headers).text
        ).toList()

        // A draft release has no published asset and must not be offered. A release without
        // a readable version cannot be compared, so it is dropped instead of sorting as
        // "newest" the way a null compareBy key did before.
        val found = response
            .filter { !it.prerelease && !it.draft }
            .mapNotNull { release ->
                val asset = release.apkAsset() ?: return@mapNotNull null
                val weight = UpdateVersion.weightOf(release.version()) ?: return@mapNotNull null
                Triple(release, asset, weight)
            }
            .maxByOrNull { it.third }

        if (found == null) {
            return Update(false, null, null, null, null)
        }

        val (release, foundAsset, _) = found
        val shouldUpdate = foundAsset.browserDownloadUrl.isNotBlank() &&
                UpdateVersion.isUpdate(installedVersionName(), release.version())

        return Update(
            shouldUpdate,
            foundAsset.browserDownloadUrl,
            release.version(),
            release.body,
            release.nodeId
        )
    }

    private fun Activity.installedVersionName(): String? =
        packageName?.let { packageManager.getPackageInfo(it, 0) }?.versionName

    /**
     * Update check for the prerelease flavour.
     *
     * It used to compare the local commit against the `pre-release` git tag. That tag is not
     * guaranteed to exist (this fork tags real versions now), and a missing tag made the
     * whole check throw and report "no update". It also picked `lastOrNull` of the release
     * list, which is not ordered, so it could offer an older build.
     *
     * The version code is what actually changes on every prerelease build, so compare that.
     */
    private suspend fun Activity.getPreReleaseUpdate(): Update {
        val releaseUrl = "https://api.github.com/repos/$githubUserName/$githubRepo/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val response = parseJson<Array<GithubRelease>>(
            app.get(releaseUrl, headers = headers).text
        ).toList()

        // Prefer the rolling "pre-release" release, fall back to any latest non-draft one.
        val found = response
            .filter { !it.draft }
            .filter { it.prerelease || it.tagName == "pre-release" }
            .firstOrNull { it.tagName == "pre-release" }
            ?: response.filter { !it.draft }.firstOrNull()

        val foundAsset = found?.apkAsset()
        if (foundAsset == null) {
            return Update(false, null, null, null, null)
        }

        val updateVersion = found.version()

        // Fall back to the commit hash when no version can be read, so an
        // unversioned build still offers an update.
        val shouldUpdate = if (updateVersion != null) {
            UpdateVersion.isUpdate(installedVersionName(), updateVersion)
        } else {
            val tagUrl =
                "https://api.github.com/repos/$githubUserName/$githubRepo/git/ref/tags/${found.tagName}"
            val updateCommitHash = parseJson<GithubTag>(
                app.get(tagUrl, headers = headers).text
            ).githubObject.sha.trim().take(7)
            Log.d(LOG_TAG, "Fetched GitHub tag: $updateCommitHash")
            currentCommitHash() != updateCommitHash
        }

        Log.d(LOG_TAG, "prerelease check: version=$updateVersion shouldUpdate=$shouldUpdate")

        return Update(
            shouldUpdate,
            foundAsset.browserDownloadUrl,
            updateVersion ?: found.nodeId,
            found.body,
            found.nodeId
        )
    }

    private val updateLock = Mutex()

    private suspend fun Activity.downloadUpdate(url: String): Boolean {
        val appUpdateName = "CloudStream"
        val appUpdateSuffix = "apk"
        var downloadedFile: File? = null
        try {
            Log.d(LOG_TAG, "Downloading update: $url")

            // Delete all old updates
            this.cacheDir.listFiles()?.filter {
                it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
            }?.forEach { deleteFileOnExit(it) }

            // Create the file inside cacheDir, so that the cleanup above actually finds it.
            // File.createTempFile without a directory writes to the system temp dir, which
            // was never cleaned and left every downloaded update behind.
            downloadedFile = File.createTempFile(appUpdateName, ".$appUpdateSuffix", cacheDir)

            updateLock.withLock {
                // use {} so the sink is closed even when the download throws.
                downloadedFile.sink().buffer().use { sink ->
                    sink.writeAll(app.get(url).body.source())
                }

                if (downloadedFile.length() <= 0L) {
                    throw IOException("The downloaded update is empty")
                }

                openApk(this, Uri.fromFile(downloadedFile))
            }

            return true
        } catch (e: Exception) {
            logError(e)
            // A failed or partial download must not stay on disk.
            downloadedFile?.let { deleteFileOnExit(it) }
            return false
        }
    }

    private fun openApk(context: Context, uri: Uri) = safe {
        val path = uri.path ?: return@safe
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", File(path)
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            data = contentUri
        }
        context.startActivity(installIntent)
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {
        val isInstalled = try {
            packageManager.getPackageInfo(PRERELEASE_PACKAGE_NAME, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        }

        if (isInstalled) {
            showToast(R.string.prerelease_already_installed)
        } else if (!runAutoUpdate(checkAutoUpdate = false, installPrerelease = true)) {
            showToast(R.string.prerelease_install_failed)
        }
    }


    /**
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     */
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false
    ): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val autoUpdateEnabled =
            settingsManager.getBoolean(getString(R.string.auto_update_key), true)
        if (checkAutoUpdate && !autoUpdateEnabled) {
            return false
        }

        val update = getAppUpdate(installPrerelease)
        if (!update.shouldUpdate || update.updateURL == null) {
            return false
        }

        // Check if update should be skipped
        val updateNodeId = settingsManager.getString(
            getString(R.string.skip_update_key), ""
        )

        // Skips the update if its an automatic update and the update is skipped
        // This allows updating manually
        if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
            return false
        }

        runOnUiThread {
            safe {
                val currentVersion = packageName?.let {
                    packageManager.getPackageInfo(it, 0)
                }

                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(
                    getString(R.string.new_update_format).format(
                        currentVersion?.versionName, update.updateVersion
                    )
                )

                val logRegex = Regex("\\[(.*?)]\\((.*?)\\)")
                val sanitizedChangelog = update.changelog?.replace(logRegex) { matchResult ->
                    matchResult.groupValues[1]
                } // Sanitized because it looks cluttered

                builder.setMessage(sanitizedChangelog)
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        // Forcefully start any delayed installations
                        if (ApkInstaller.delayedInstaller?.startInstallation() == true) return@setPositiveButton

                        showToast(R.string.download_started, Toast.LENGTH_LONG)

                        // Check if the setting hasn't been changed
                        if (settingsManager.getInt(
                                getString(R.string.apk_installer_key), -1
                            ) == -1
                        ) {
                            // Set to legacy installer if using MIUI
                            if (isMiUi()) {
                                settingsManager.edit {
                                    putInt(getString(R.string.apk_installer_key), 1)
                                }
                            }
                        }

                        val currentInstaller = settingsManager.getInt(
                            getString(R.string.apk_installer_key), 1
                        )

                        when (currentInstaller) {
                            // New method
                            0 -> {
                                val intent = PackageInstallerService.Companion.getIntent(
                                    this@runAutoUpdate, update.updateURL
                                )
                                ContextCompat.startForegroundService(
                                    this@runAutoUpdate, intent
                                )
                            }
                            // Legacy
                            1 -> {
                                ioSafe {
                                    if (!downloadUpdate(update.updateURL)) {
                                        runOnUiThread {
                                            showToast(
                                                R.string.download_failed, Toast.LENGTH_LONG
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }

                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun getSystemProperty(propName: String): String? = try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        BufferedReader(InputStreamReader(p.inputStream), 1024).use {
            it.readLine()
        }
    } catch (_: IOException) {
        null
    }
}
