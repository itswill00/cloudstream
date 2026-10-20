# Releasing CloudStream

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
source /root/keys/env.sh          # signing key and password
./gradlew assemblePrereleaseRelease
```

The output is `app/build/outputs/apk/prerelease/release/app-prerelease-release.apk`.
Without the environment variables the build still works, but the APK is unsigned
and cannot be installed.

The build helper of this repository does the same and keeps the logs:

```bash
source /root/keys/env.sh
./build.sh release
```

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
