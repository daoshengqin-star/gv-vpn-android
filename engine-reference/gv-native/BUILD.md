# Historical native-wrapper build record

This file records the earlier Windows build. Current Linux preparation and
the pinned source-build workflow are documented in ../../fdroid/REPRODUCIBILITY.md.
The historical archive and build receipts referenced below remain in the private
project evidence, outside this source repository.

Upstream: AndroidLibXrayLite v26.9.30. Xray-core remains pinned to b26a91de4f32 and the original go.mod/go.sum dependencies. The original complete upstream archive is retained one directory above.

Local changes are limited to libv2ray_gv_verify.go and CoreController cancellation fields / StopLoop cancellation in libv2ray_main.go. Verification performs one HTTPS request through core.Dial, with normal certificate verification, no redirects, HTTP 200/204 required, a 4.5-second native context deadline, and cancellation that closes the pending network request. The original MeasureDelay benchmark API is preserved.

Rebuilt with Go 1.27.2, Android NDK r29, JDK 21, pinned gomobile/gobind golang.org/x/mobile v0.0.0-20260908204917-8b95e45f8d3e, Android API 28.

The native build uses:

```text
gomobile bind -v -target android/arm64,android/amd64,android/arm -androidapi 28 -trimpath -ldflags="-s -w -buildid= -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" -o libv2ray-gv.aar ./
```

Install gomobile and gobind at the exact pinned version; the current gomobile init command requests gobind@latest, so the pinned tools were installed directly and GOPATH/pkg/gomobile was prepared for bind instead. Build-tool dependencies are isolated from Android runtime dependencies. Exact runtime module ZIPs were verified against upstream go.sum and seeded into the isolated cache.

Temporary GODEBUG=tlsmlkem=0 was used only by Windows Go tooling to retrieve build metadata after TLS compatibility errors. It is not an Android app environment variable, linker setting, source-code TLS override, or saved runtime default.

build-receipt.json contains the AAR SHA256 and per-ABI ELF LOAD plus RELRO-end alignment results. All three ABIs retain RELRO protection and pass 16KB checks. The unmodified upstream AAR had 16KB LOAD alignment on 64-bit ABIs but a nonzero RELRO-end remainder. No security protection was disabled to correct the alignment.

Android runtime acceptance is tracked separately in qa; a successful native rebuild or HTTPS direct control is not proof of a working system VPN.
