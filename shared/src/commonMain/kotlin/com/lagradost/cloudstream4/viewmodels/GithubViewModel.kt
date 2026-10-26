package com.lagradost.cloudstream4.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.state.ActionHandler
import com.lagradost.cloudstream4.state.DefaultStateContainer
import com.lagradost.cloudstream4.state.SingleActiveQuery
import com.lagradost.cloudstream4.state.StateContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class GithubState(
    val dialog: GithubDialog? = null,
)

@Immutable
sealed class GithubUpdateDialogState {
    data class Error(val error: Throwable) : GithubUpdateDialogState()
    data class DownloadProgress(val progress: Long, val total: Long?) : GithubUpdateDialogState()
    object Loading : GithubUpdateDialogState()
    object NoUpdateFound : GithubUpdateDialogState()
    data class UpdateFound(
        val file: GithubFile,
        val newSha: String?,
        val oldSha: String?,
    ) : GithubUpdateDialogState()
}

@Immutable
data class GithubDialog(
    val isPrerelease: Boolean,
    val isFromUser: Boolean,
    val state: GithubUpdateDialogState,
)

/** GitHub file update package */
@Immutable
data class GithubFile(
    /** File digest, sha:xxx */
    val digest: String?,
    /** File url for download */
    val downloadUrl: String,
    /** Filename without the extension */
    val displayName: String,
    /** Changelog, aka the commit message */
    val changeLog: String,
    /** Name of the tag, aka unique release name like vX.X.X or pre-release */
    val tagName: String,
    /** Version inside the tag or the file name, e.g. "4.9.1" */
    val versionName: String? = null,
    /** Unique node id */
    val nodeId: String,
)

@Immutable
sealed class GithubAction {
    object AutoSearchForUpdate : GithubAction()
    object SearchForUpdate : GithubAction()
    object Dismiss : GithubAction()
    data class SkipThisUpdate(val file: GithubFile) : GithubAction()
    data class Update(val file: GithubFile) : GithubAction()
    data class SkipUpdate(val file: GithubFile) : GithubAction()
}

interface AppUpdater {
    @Throws
    suspend fun update(
        settings: AppSettings,
        url: String,
        digest: DigestPair?,
        downloadProgress: (Long, Long?) -> Unit,
    )
}

interface GithubRepository {
    @Throws
    suspend fun getRelease(
        userName: String,
        repository: String,
        prerelease: Boolean,
        prereleaseTag: String,
        contentType: String,
    ): GithubFile

    @Throws
    suspend fun getSha(
        userName: String,
        repository: String,
        tag: String,
    ): String
}

/** The digest pair to verify that a file is correctly downloaded */
data class DigestPair(
    val algorithm: String,
    val digest: ByteArray,
) {
    companion object {
        // "sha256:XXXX" or "sha256-XXXX" -> "sha256", bytearray(XXXX)
        fun parse(digestPair: String?): DigestPair? {
            if (digestPair == null) return null

            var split = digestPair.split(":", limit = 2)
            if (split.size != 2) {
                split = digestPair.split("-", limit = 2)
            }
            if (split.size != 2) {
                return null
            }

            val digestAlgorithm = split.getOrNull(0) ?: return null
            val digestByteArray = safe { split.getOrNull(1)?.hexToByteArray() } ?: return null

            return DigestPair(digestAlgorithm, digestByteArray)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val other = other as? DigestPair ?: return false

        if (algorithm != other.algorithm) return false
        if (!digest.contentEquals(other.digest)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + digest.contentHashCode()
        return result
    }
}

/**
 * Cross-platform downloader for updates served by GitHub.
 *
 * To allow this Cross-platform behavior work we split up the UI from the Viewmodel,
 * and the Viewmodel from the installer.
 * ```
 * UI:          Renders the current viewmodel state
 * Viewmodel:   Searches for updates on GitHub
 * AppUpdater:  Installs the update from the raw GitHub url
 *
 * UI -(onAction)-> Viewmodel -(invokes)-> AppUpdater
 *   <---(state)---/        <--(callback)--/
 * ```
 * */
class GithubViewModel(
    val remoteUserName: String,
    val remoteRepository: String,
    val remotePrereleaseTag: String,
    val remoteContentType: String,
    val versionName: String,
    val isPrerelease: Boolean,
    val isDebug: Boolean,
    val buildSha: String,
    val settings: AppSettings,
    val updater: AppUpdater,
    val repository: GithubRepository,
) : ViewModel(), StateContainer<GithubState> by DefaultStateContainer(GithubState()),
    ActionHandler<GithubAction> {
    private val updateDispatcher = SingleActiveQuery(Dispatchers.IO)

    override fun onAction(action: GithubAction) {
        when (action) {
            GithubAction.SearchForUpdate -> {
                ioSafe {
                    searchForUpdate(prerelease = isPrerelease, fromUser = true)
                }
            }

            GithubAction.Dismiss -> {
                viewModelScope.launch {
                    updateDispatcher.cancel()
                    updateState { copy(dialog = null) }
                }
            }

            is GithubAction.SkipThisUpdate -> {
                settings.updates.skipUpdate.set(action.file.nodeId)
            }

            GithubAction.AutoSearchForUpdate -> {
                if (!isDebug && settings.updates.showAppUpdates.get()) {
                    ioSafe {
                        searchForUpdate(prerelease = isPrerelease, fromUser = false)
                    }
                }
            }

            is GithubAction.SkipUpdate -> {
                settings.updates.skipUpdate.set(action.file.nodeId)
            }

            is GithubAction.Update -> {
                ioSafe {
                    installUpdate(action.file.downloadUrl, action.file.digest)
                }
            }
        }
    }

    /** Cancel the old update, and catch possible errors from the block and show as a new state */
    private suspend fun dispatchUpdate(block: /* @Throws */ suspend () -> Unit) {
        updateDispatcher.launch {
            try {
                block()
            } catch (t: Throwable) {
                // If it was canceled ignore it as we probably launched another update check
                if (!isActive) {
                    return@launch
                }
                // Otherwise we display the error
                updateState {
                    copy(dialog = dialog?.copy(state = GithubUpdateDialogState.Error(t)))
                }
            }
        }
    }

    private suspend fun installUpdate(url: String, digestPair: String?) = dispatchUpdate {
        updater.update(
            settings = settings,
            url = url,
            digest = DigestPair.parse(digestPair),
        ) { progress, total ->
            updateState {
                copy(
                    dialog = dialog?.copy(
                        state = GithubUpdateDialogState.DownloadProgress(
                            progress = progress,
                            total = total
                        )
                    )
                )
            }
        }
        updateState {
            copy(
                dialog = null
            )
        }
    }

    private suspend fun searchForUpdate(
        prerelease: Boolean,
        fromUser: Boolean,
    ) = dispatchUpdate {
        val baseDialog = GithubDialog(
            isPrerelease = prerelease,
            isFromUser = fromUser,
            state = GithubUpdateDialogState.Loading
        )

        updateState {
            copy(dialog = baseDialog)
        }

        // The prerelease flavour used to compare the build commit against the sha of the
        // "pre-release" tag. That tag does not exist here, and even when a tag resolves it
        // points at the version-bump commit, which never equals the commit an apk was built
        // from. Every check therefore reported a new update forever. Compare versions
        // instead, and keep the sha only for display.
        var oldSha: String? = null
        var newSha: String? = null
        if (prerelease) {
            oldSha = buildSha.take(7)
            newSha = try {
                repository.getSha(
                    tag = remotePrereleaseTag,
                    userName = remoteUserName,
                    repository = remoteRepository,
                ).take(7)
            } catch (t: Throwable) {
                // A missing tag is not an error, the release lookup below handles it.
                null
            }
        }

        val release = repository.getRelease(
            prerelease = prerelease,
            userName = remoteUserName,
            repository = remoteRepository,
            prereleaseTag = remotePrereleaseTag,
            contentType = remoteContentType
        )

        // Compared by version, not by file name or commit: the asset is
        // "cloudstream-4.9.1-arm64-v8a" while the installed version is "4.9.1", and the tag
        // points at the version-bump commit rather than the one the apk was built from.
        if (!UpdateVersion.isUpdate(versionName, release.versionName)) {
            updateState {
                copy(dialog = baseDialog.copy(state = GithubUpdateDialogState.NoUpdateFound))
            }
            return@dispatchUpdate
        }

        // If this was automated, and we have pressed "skip this update" then check the node-id
        if (!fromUser && release.nodeId == settings.updates.skipUpdate.get()) {
            updateState {
                copy(dialog = baseDialog.copy(state = GithubUpdateDialogState.NoUpdateFound))
            }
            return@dispatchUpdate
        }

        updateState {
            copy(
                dialog = baseDialog.copy(
                    state = GithubUpdateDialogState.UpdateFound(
                        file = release,
                        newSha = newSha,
                        oldSha = oldSha
                    )
                )
            )
        }
    }
}