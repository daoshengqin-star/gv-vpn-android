# GV VPN Android client

Android application ID: `com.globalvillager.mobile`. Source candidate: 0.1.24 / code 26.
All installed launcher labels are **GV VPN** in English, Simplified Chinese and
Traditional Chinese. The client is GPL-3.0; third-party components and datasets
retain their own licenses and notices.

This is client source and reproducible-build preparation. It does not represent
F-Droid submission, inclusion, approval, or a public binary release. The service
requires an existing operator account and entitlement. Backend source is not
included; any F-Droid proposal must disclose TetheredNet and NonFreeNet.

## Build

See [the pinned Linux instructions](fdroid/REPRODUCIBILITY.md).

    sh fdroid/native-from-source.sh
    sh gradlew --no-daemon -PgvFdroidUnsigned=true :app:assembleRelease

For the F-Droid prepared/scanned checkout use `python3 fdroid/build-prepared-export.py`
with checksum-pinned Gradle 8.13 on PATH. This builds the current prepared source
export after scanning and records its public commit in the external recipe.

The native library is built from pinned, checksum-verified source. No prebuilt
AAR, signing secrets, local SDK paths, private reviewer credentials or QA
instrumentation is tracked. The unsigned option rejects company signing-file
and diagnostic-library properties, and disables external purchase links.
Default release builds still require a separate private signing configuration.
Do not publish or install an unsigned APK as a signed production release.

Using the company signature across channels also requires full reproducible-build
verification. Compiling successfully does not establish runtime acceptance or
F-Droid eligibility. Older screenshots are not relabelled as this candidate.
Three-language metadata, routing dataset hashes, derivation notes and third-party
licenses accompany the source. Historical native build notes describe the earlier
Windows build; private evidence receipts and archived packages remain separate.
