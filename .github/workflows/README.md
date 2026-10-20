# No workflows in this fork

The workflows of the upstream repository are not used here.

They need a GitHub App and a set of API keys that only exist in the upstream
organisation, so every run failed here. The fork is built on a server instead,
see [BUILDING.md](../BUILDING.md) and
[RELEASING.md](../RELEASING.md):

```bash
./build.sh release --abi arm64-v8a     # build a signed APK
./tools/send-test-apk.sh               # build it and send it to the test group
```

A test build is uploaded to the test group on Telegram, tested on a device and
only then a release is tagged.
