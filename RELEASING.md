# Releasing CloudStream

## 0. Test the fix before releasing it

Every change should be tested on a real device before a release is tagged. The
`build.sh` script builds the app and uploads the APK to the test group on
Telegram:

```bash
./build.sh release --send                        # build and send
./build.sh release --send --message "fixed X"    # with a note
./build.sh release --send --no-build             # send the last build
```

The build is for arm64 only, that keeps the APK at around 45 MB, below the
50 MB upload limit of the Telegram API. If it is still too big, the script
splits it into parts.

The bot token and the chat id are read from a file outside of the repository:

```
# /root/keys/telegram-bot.env
TELEGRAM_BOT_TOKEN=123456:ABCDEF
TELEGRAM_CHAT_ID=-1001234567890
```

The bot has to be able to send documents in the group, promote it to admin if
the upload is rejected.

## 1. Prepare the release

The version lives in the version catalog and is used for both flavours:

```toml
# gradle/libs.versions.toml
versionCode = "69"
versionName = "4.8.1"
```

`versionCode` has to be increased for every release, otherwise the app cannot be
updated in place. The `prerelease` flavour overrides the version code with a
timestamp, so it can always be installed over an older build.

Commit the bump on its own, the same way the previous releases did it:

```bash
git add gradle/libs.versions.toml
git commit -s -m "Bump to 4.8.1"
```

## 2. Build

```bash
./build.sh release
```

The output is `app/build/outputs/apk/prerelease/release/app-prerelease-release.apk`.
The script loads the signing key from `KEYS_ENV_FILE` (`/root/keys/env.sh`), so
it does not have to be sourced by hand. Without the key the build still works,
but the APK is unsigned and cannot be installed.

## 3. Tag

```bash
git tag -a v4.8.1 -m "CloudStream 4.8.1 - <summary>"
git push origin refs/heads/master:refs/heads/master
git push origin refs/tags/v4.8.1:refs/tags/v4.8.1
```

Always use an explicit refspec when pushing, `push.default=upstream` is
configured globally and would otherwise push to the upstream branch.

## 4. Publish the APK

```bash
gh release create v4.8.1 app/build/outputs/apk/prerelease/release/app-prerelease-release.apk \
    --title "CloudStream 4.8.1" --notes-file RELEASE_NOTES.md
```

The name of the asset matters, the in app updater reads the version from it and
expects `<version>.apk`, for example `4.8.1.apk`.

## The in app updater

The updater checks the repository that is set in the build config:

| | |
|---|---|
| `UPDATE_REPO_OWNER` | `itswill00`, override with `-PupdateRepoOwner=...` |
| `UPDATE_REPO_NAME` | `cloudstream`, override with `-PupdateRepoName=...` |

The stable flavour looks at the latest release, the pre-release flavour at the
`pre-release` release and compares the commit hash of the `pre-release` **tag**
with the one that is built into the app. The tag has to be a lightweight tag
that points at the commit the APK was built from, an annotated tag would report
the tag object instead of the commit and the updater would offer an update
forever.

The upstream CI moves that tag on every push, the workflows cannot run in this
fork, so it has to be updated by hand whenever the pre-release is published:

```bash
git tag -f pre-release HEAD
git push --force origin refs/tags/pre-release:refs/tags/pre-release
gh release upload pre-release app/build/outputs/apk/prerelease/release/app-prerelease-release.apk \
    --clobber
```

## The description of the pre-release

The description of the `pre-release` release is not a one liner. It has to say
what changed and how to test it, the people that test the build have to know
what to look at.

```bash
./build.sh notes              # write the description and put it on GitHub
./build.sh notes --dry-run    # only show it
```

The script writes the description from the commits:

| Part | What is in it |
|---|---|
| The header | The commit and the date of the build, the name and the size of the APK |
| **What is new** | The commits since the last build, grouped into new, fixes and build changes |
| **Everything since the last release** | The complete list since the last stable release, for the people that did not test the previous build |
| **How to test** | How to install the build and where to report the problems |
| The note about AI | That the build was written with AI and how it was checked |

Every pre-release gets a description, it is not optional:

```bash
./build.sh release --send      # build and send the APK to the test group
./build.sh notes               # update the description of the release
```

Both in one go:

```bash
./build.sh release --send notes
```

The script knows the commit of the last build, it reads it from the
description of the release, so it only writes what is new.

## Signing key

| | |
|---|---|
| Keystore | `/root/keys/cloudstream-release.jks` (PKCS12, RSA 4096, SHA256withRSA) |
| Password | `/root/keys/keystore-password.txt` |
| Alias | `cloudstream` |
| Valid until | 2054-03-07 |
| SHA-256 | `b6385d6b4e9b916170524c69551755388d2ac494431073a015cbbd7fbf26d53c` |

The keystore is deliberately **not** part of this repository. The build looks for
it in `$HOME/work/_temp/keystore/`, a copy is kept there for the build.

> **Back up the keystore and the password.** Without them the app can no longer
> be updated in place, every user would have to uninstall the old version first.

## Continuous integration

The workflows in `.github/workflows` build the official pre-release and the
archive build. They need the `GH_APP_ID` / `GH_APP_KEY` secrets of the
`recloudstream/secrets` repository, so they only run in the upstream
organisation. In a fork they fail with
`Error: Input required and not supplied: app_id`, and the release has to be
built locally as described above.
