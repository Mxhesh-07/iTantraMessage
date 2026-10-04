# BUILD.md

How to build iTantra Message, and why the build is configured the way it is.

---

## 1. Prerequisites

| | version | why |
|---|---|---|
| JDK | **21** | required by AGP 8.13; the Android Studio JBR works |
| Android SDK | compileSdk **36** | `platforms/android-36` |
| Gradle | **8.14** (via the wrapper) | `-all` distribution, already unpacked locally |
| AGP | **8.13.1** | |
| Kotlin | **2.2.20** | KSP 2.2.20-2.0.4 |
| Git LFS | **3.x** | **only for a fresh `git clone`** — see 1.3 |

### 1.3 Cloning needs Git LFS, and that is a deliberate cost

The speech model is committed through Git LFS:

```
$ git lfs ls-files
0b8fb1304b * app/src/main/assets/models/whisper-base/base-encoder.int8.onnx
9759d21738 * app/src/main/assets/models/whisper-base/base-decoder.int8.onnx
```

Those two objects are **153.2 MiB**. The rest of the repository — all the source,
every document and every test — is **267 KiB**.

**Why LFS rather than committing the files.** `base-decoder.int8.onnx` is
130,672,026 bytes. GitHub rejects any blob over 100 MiB, so committing it directly
makes `git push` fail outright. That is a hard limit, not a warning.

**Why LFS rather than leaving the model out.** The alternative was to not commit
the model at all and document a download step. That was rejected: it would leave a
fresh clone unable to build an app that can actually dictate, which is the one
capability this project exists to demonstrate. A model fetched from a URL is also
not verifiable at build time — `ModelStore` verifies SHA-256 on device, but a
clone that arrives without the model fails much later and less clearly than one
that fails on the missing LFS checkout.

**The cost, stated plainly:** anyone cloning needs `git-lfs` installed, or the
`.onnx` files arrive as 130-byte pointer files and Gradle packages a broken app.
GitHub's own web UI and clone integration handle LFS transparently, so this bites
only local command-line clones.

**How to tell whether a clone is intact** — the LFS SHA-256 is the same digest
recorded in the README beside the model files, so it is a real check rather than a
reassuring one:

```bash
git lfs fsck                      # verifies every LFS object against its pointer
ls -l app/src/main/assets/models/whisper-base/
# base-decoder.int8.onnx  130672026   <- not 130 bytes, which would be a pointer
```

A build that packages a pointer file still succeeds. The instrumented test
`bundledModelExtractsAndVerifies` is what catches it, because `ModelStore` compares
the extracted file against the expected SHA-256 and discards a mismatch.

`base-tokens.txt` (817 KB) and the test WAV (352 KB) are ordinary git files, not
LFS, so they can be read and diffed without an LFS client.

### 1.1 `JAVA_HOME` is not set in this environment

`javac`, `gradle` and `JAVA_HOME` are **not** on `PATH` here. Every build must set it:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --no-daemon --console=plain :app:assembleRelease
```

`--no-daemon` keeps a single-use JVM from lingering; `--console=plain` keeps Gradle's progress
bar out of the log so errors can be grepped.

### 1.2 The SDK path comes from `local.properties`

```
sdk.dir=C\:\\Android\\Sdk
```

Backslash-escaped, because it is a `java.util.Properties` file.

`scripts/check_offline.sh` reads **this file** rather than `ANDROID_SDK_ROOT`. Two reasons,
both learned the hard way:

- `bash` on this machine is `C:\Windows\system32\bash.exe` — **WSL**, which does not inherit
  Windows environment variables. `ANDROID_SDK_ROOT` set in PowerShell is invisible inside it.
- The SDK is at `C:\Android\Sdk`, a non-standard location that no conventional-install
  heuristic finds.

Reading the same file Gradle reads means the gate can never inspect a different SDK than the
one that produced the APK. The script also translates a `C:/...` path to `/mnt/c/...` when
running under WSL, and tries both.

---

## 2. The commands

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# compile only, fastest inner loop
.\gradlew.bat --no-daemon --console=plain :app:compileDebugKotlin

# unit tests
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest

# both APKs
.\gradlew.bat --no-daemon --console=plain assembleRelease assembleDebug

# the offline gate
bash scripts/check_offline.sh
```

Measured 2026-10-01: full clean build plus tests, **2 m 5 s**; inner-loop
`compileDebugKotlin`, under 30 s incremental.

### 2.1 Reading Kotlin errors out of a Gradle log

PowerShell's `Select-String` mangles the long `e:` lines. De-wrap with:

```powershell
(Get-Content $log) | Where-Object { $_ -match '^e: ' } | ForEach-Object {
  $t = $_.Trim()
  if ($t -match 'itantramessage[/\\](.+?):(\d+):(\d+)\s+(.*)$') {
    "  $($Matches[1]):$($Matches[2]):$($Matches[3])  $($Matches[4])"
  } else { "  $t" }
}
```

### 2.2 Gates that must pass

```powershell
.\gradlew.bat testDebugUnitTest    # exit 0, 198 tests (396 executions: debug + release)
.\gradlew.bat connectedDebugAndroidTest   # exit 0, 4 instrumented tests, needs a real device
bash scripts/check_offline.sh      # exit 0  -- NOT exit 2
python scripts/check_docs.py       # exit 0
```

`check_offline.sh` exit codes: `0` pass · `1` fail · `2` inconclusive · `3` cannot verify.
Only `0` counts. See `SECURITY.md` §1.

`check_docs.py` exit codes: `0` verified · `1` a claim is wrong · `2` a file it needs is
missing. It reads the constants, test counts, permissions and cross-references out of the
source and fails if a document disagrees, and it flags any performance figure in any document
that cites no measurement — including `PERFORMANCE.md`'s own exemptions being the only place a
runtime figure may appear unmeasured.

Both scripts have been run against negative controls: a fabricated latency in a doc, a changed
constant in the source, `INTERNET` added to the main manifest, `INTERNET` removed from the
debug one, and a required document deleted. Each produces the expected non-zero exit. A check
that cannot fail is not a check.

### 2.3 What a doc check catches that a build cannot

`check_docs.py` exists because a stale or invented number is invisible to every other gate
here. Gradle does not read `docs/`. `aapt` does not read `docs/`. And a comment in a source
file claiming "12.0:1 contrast" is true as far as the compiler is concerned.

It has already caught real errors in this repository:

- `colors.xml` claimed contrast ratios of 12.0, 8.6, 7.3 and 7.4. Computing them gave 16.62,
  8.57, 9.08 and 13.33. All four pairs passed AA either way, so the effect was harmless —
  and it was still worth fixing, because the next person to check those numbers would have
  assumed they had been checked.
- `ERROR_HANDLING.md` described `DELIVERED` as "the peer confirmed". `MessageType` has exactly
  one entry, `TEXT`, so there is no ack frame and nothing ever confirms anything.

Both were invisible to the build, the tests and the offline gate.

---

## 3. Build configuration decisions

### 3.1 No `INTERNET`, and how

`INTERNET` is declared **only** in `app/src/debug/AndroidManifest.xml`. The release manifest
does not contain it and no dependency contributes it.

A dependency that merged in `INTERNET` would break the project's central guarantee without
touching a line of application code. That is why the gate inspects the built artifact rather
than the source. See `SECURITY.md` §1.2 for diagnosis.

### 3.2 `targetSdk = 36`, and a bug this caught

An earlier version left `targetSdk` unset with a comment claiming it would "default to the
current platform". **That was wrong**, and `aapt dump badging` on the built APK is what caught
it:

```
sdkVersion:'24'      targetSdkVersion:'24'
```

An unset `targetSdk` compiles to `minSdk` — 24 — not to anything newer.

The consequence was not cosmetic. `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are only runtime
permissions when `targetSdk >= 31`, so the entire API-31+ permission branch in
`NearbyManager` and `MainActivity` was **unreachable**. The app would have requested the API-30
permission set, with location, on a modern phone.

`targetSdk = compileSdk = 36`. Keeping them equal means one edit when the SDK is bumped.
`targetSdk` must not exceed `compileSdk`.

### 3.3 `BUILD_FACTS` lives in `defaultConfig`

```kotlin
defaultConfig {
    buildConfigField(
        "String", "BUILD_FACTS",
        "\"transport=raw-bluetooth(no-play-services) offline_gated=true\"",
    )
}
```

Baked into the APK so a reviewer can read the transport and offline posture out of the
artifact instead of taking it from documentation. One string constant.

**It must be inside `defaultConfig`.** A `buildConfigField` at the `android {}` top level does
not resolve — that is a configuration-time failure, not a compile error, so it is worth stating.

`buildFeatures { buildConfig = true }` is required from AGP 8; `BuildConfig` is not generated by
default any more.

### 3.4 Release is minified and shrunk

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-optimize.txt"), "proguard-rules.pro")
    signingConfig = signingConfigs.getByName("debug")
}
```

Measured 2026-10-05: **196,624,703 bytes** release, 214,334,743 debug. The ~1.09× difference is
R8 plus resource shrinking; debug is unminified with full tooling.

The size is dominated by a bundled asset, not by code: `assets/models/whisper-base/` is
160,609,290 bytes and the two native libraries that run it are 34,655,644, together 99.3% of the
artifact. The application's own code and resources come to roughly 1.36 MB. See
`PERFORMANCE.md` §3.1 for the full composition and for what it costs to install.

Release is signed with the **debug** key. That is a deliberate placeholder for a demo build
and must be replaced with a real key before any distribution — stated here because a debug-signed
release APK is otherwise easy to mistake for a shippable one.

`abiFilters` is restricted to `arm64-v8a` and `armeabi-v7a`. arm64 covers essentially every
device from 2017 on; the 32-bit ABI covers the tail that minSdk 24 still admits. Shipping both
adds 13,705,540 bytes that most users never execute, and it was kept because a messaging app
that cannot run on a cheap 32-bit phone has failed its own brief.

### 3.5 Dependency versions come from the local cache

Every version was chosen from what is already in `~/.gradle/caches`, so the build reproduces
with no network. AGP 8.13.1 · Kotlin 2.2.20 · KSP 2.2.20-2.0.4 · Gradle 8.14 · compileSdk 36
· minSdk 24 · Compose BOM 2025.06.01 · Room 2.7.2 · core-ktx 1.16.0 · activity-compose 1.10.1
· lifecycle 2.9.1 · coroutines 1.9.0 · navigation-compose 2.9.0.

One dependency is not from that cache and is worth naming on its own:

- `com.bihe0832.android:lib-sherpa-onnx:6.25.21` — an Android repackaging of the upstream
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) JNI library, Apache-2.0. It is the
  **only third-party runtime dependency** in this project, added for offline speech-to-text
  because it is the only offline engine found that covers all ten languages here, Kannada and
  Malayalam included. It ships `libsherpa-onnx-jni.so` and depends on `libonnxruntime.so` for
  arm64-v8a and armeabi-v7a: 34,655,644 bytes of native code in total. What that means for
  supply chain and for the reviewer's ability to audit this app is written up in `SECURITY.md`
  §7 and §8 rather than buried here.

The trade: **upgrading any dependency requires a network fetch first.** That is the cost of a
build that works on a machine with no connectivity, which is the condition this project is
demonstrating.

Deliberately **not** included:

- `security-crypto` — deprecated. Keystore work goes through `KeyGenerator` /
  `KeyGenParameterSpec` directly, which is what it wrapped anyway.
- `appcompat` — the app is pure Compose with one Activity. Adding it for the sake of a
  MaterialComponents theme would pull in the whole Views Material library.
- `play-services-nearby` — see `LIMITATIONS.md` §1.3.

### 3.6 Repositories

`RepositoriesMode.FAIL_ON_PROJECT_REPOS` in `settings.gradle.kts`. A `repositories {}` block in
a module is a configuration **error**, not a warning — so a dependency quietly coming from
somewhere unexpected fails the build.

### 3.7 Gradle wrapper has no SHA pin

`gradle-wrapper.properties` points at `gradle-8.14-all.zip` with **no
`distributionSha256Sum`**.

The `-all` distribution is already unpacked in the local cache, so the wrapper does not
download. Adding a checksum that was never verified against a download would be a checksum of
nothing — worse than no checksum, because it looks verified. If this build is ever moved to a
machine that must download the distribution, add the real hash then, from the Gradle
release, and note it here.

---

## 4. Source layout

```
app/src/main/java/in/isro/sih26173/itantramessage/
  MainActivity.kt              single Activity, NavHost, permission launcher hoisted here
  ItantraMessageApp.kt         composition root; AppContainer holds the process-scoped repo
  data/
    crypto/EncryptionManager   Keystore AES-GCM, generations in the alias
    database/                  Room: MessageEntity, MessageDao, AppDatabase
    device/DeviceIdentity      IT-%06X, 24 random bits in app-private prefs
    nearby/                    NearbyManager, ByteLink+Reassembler, RfcommTransport, BleGattTransport
  domain/
    model/                     Envelope, EnvelopeCodec, ConversationId
    repository/MessageRepository  send queue, retry pump, frame accept + dedup
  ui/
    home/                      HomeScreen, HomeViewModel
    chat/                      ChatScreen, ChatViewModel
    theme/Theme.kt
```

### 4.1 Two decisions worth knowing before editing

**`AppContainer` is process-scoped, not per-ViewModel.** The repository and its link live in
the composition root. Two repositories means two receive loops, and the second would steal the
link from the first — messages would arrive at whichever ViewModel happened to be constructed
last.

**`ConversationId` is a data class of `(value, peer)`, not a `String`.** A chat route takes a
conversation value *and* a peer id; two separate `String` parameters compile cleanly when
swapped, then file every message under the wrong conversation. One type makes the swap a
compile error.

---

## 5. Install

```powershell
$adb = "…\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\release\app-release.apk
```

Pair the two phones with **each other** in Android Settings → Bluetooth before using RFCOMM.
Android will not initiate pairing from an app: it requires user interaction at the system
level. A driver that "discovers" an unpaired peer and then fails to connect is behaving
correctly.

---

## 6. Current build state

Measured 2026-10-05, version 0.1.0:

| | |
|---|---|
| `assembleRelease` | exit 0, 196,624,703 bytes |
| `assembleDebug` | exit 0, 214,334,743 bytes |
| `assembleDebugAndroidTest` | exit 0, 7,587,766 bytes |
| `testDebugUnitTest` | exit 0, **198 tests**, 0 failures |
| `testReleaseUnitTest` | exit 0, **198 tests**, 0 failures |
| `connectedDebugAndroidTest` | exit 0, **4 instrumented tests** — on a realme RMX2020, API 30 |
| `check_offline.sh` | exit 0 — release clean, debug positive |
| `check_docs.py` | exit 0 |
| `targetSdk` / `minSdk` | 36 / 24 |
| release permissions | 13 declared, **0** `INTERNET` |
| debug permissions | 14 declared, 1 `INTERNET` (the negative control) |

**No two-device run has been performed.** See `LIMITATIONS.md` §3.

The instrumented tests are the only ones that need a device and the only ones that exercise the
speech model. They are `androidx.test` rather than JVM tests on purpose: sherpa-onnx is a JNI
library, and a JVM test that mocked it would have passed against a stub returning canned text —
which is precisely the bug this project spent most of its history concealing. See `TESTING.md` §4.6.