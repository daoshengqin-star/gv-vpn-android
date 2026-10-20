# Linux reproducible-build preparation for GV VPN Android

This source is the 0.1.24 / code 26 candidate for package `com.globalvillager.mobile`.
The installed launcher label is `GV VPN` in every supported language.
It is not an F-Droid inclusion decision or a public binary release.

Pinned inputs: Go 1.27.2, Temurin JDK 21.0.12.1+1, Android NDK
29.0.14206865 (r29), Android API 36, build-tools 35.0.0 selected by the
pinned Android Gradle Plugin, Gradle 8.13,
Android Gradle Plugin 8.10.1, and x/mobile
v0.0.0-20260908204917-8b95e45f8d3e. The Gradle distribution checksum
is enforced in gradle/wrapper/gradle-wrapper.properties. Runtime Go dependencies
and their checksums are in engine-reference/gv-native/go.mod and go.sum.

Set JAVA_HOME, ANDROID_HOME, ANDROID_NDK_HOME and put the pinned Go executable
on PATH. Use an isolated GOPATH, GOCACHE and GRADLE_USER_HOME. Then run:

    sh fdroid/native-from-source.sh
    sh gradlew --no-daemon -PgvFdroidUnsigned=true :app:assembleRelease

For a prepared/scanned Git checkout, use the same source-export profile as the
upstream release, with the checksum-pinned Gradle 8.13 executable on PATH:

    python3 fdroid/build-prepared-export.py

This copies the current prepared tree without .git or generated caches. It does
not restore files removed by F-Droid's preparation or scanner. Native compilation
runs only in the build phase, after scanning. Record the full public Git commit
in F-Droid metadata. The output is fdroid-output/app-universal-release-unsigned.apk.

The native script builds gomobile and gobind from checksum-verified pinned
module source. Its small, auditable change in prepare-native-tools.py makes
generated go.mod local replacement paths relative. TMPDIR is inside each
checkout so both the build input and module provenance work at different
absolute checkout roots. It preserves module names, versions and checksums.
It does not remove Go build information, reuse a prebuilt native library,
disable TLS verification or weaken ELF protections.

The fresh AAR is generated under app/libs and excluded from Git.
The universal unsigned APK is app/build/outputs/apk/release/
app-universal-release-unsigned.apk. An unsigned APK is not a deployable release.

Reproduce from two fresh checkouts at different absolute paths and compare the
entire unsigned APK byte for byte. Company signing is a separate, local-only
step. Never upload a signing key or signing configuration. F-Droid upstream
signature verification additionally requires a verified versioned public binary,
an allowed signing-key fingerprint and F-Droid's own reproducible build result.

The GPL-3.0 application, LGPL-3.0 AndroidLibXrayLite wrapper, MPL-2.0 Xray-core,
and other module/data licenses retain their notices under app/src/main/assets/legal.
The geoip/geosite files are non-executable routing datasets, not native binaries.
Their pinned hashes and derivation records are retained. They are not claimed to
have been regenerated from the upstream editable lists in this preparation.

The service requires an existing operator account and entitlement. Backend source
is not part of this Android repository. TetheredNet and NonFreeNet disclosures
must be included in any F-Droid proposal. Inclusion, maintenance policy, official
scanner results, official build-server verification and device acceptance are
separate gates and must be reported separately.

When signing locally with Android build-tools 35, preserve the already verified
ZIP alignment with `apksigner sign --alignment-preserved true`. Verify the signed
APK and its 16 KB alignment afterwards. Do not disable APK integrity checks.

