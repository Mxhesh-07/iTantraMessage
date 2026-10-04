# TECH_STACK.md

The technologies this project uses, and — just as importantly — which of them are actually
running.

This file previously listed Emergency SOS, a Telemetry HUD, Silero VAD, Zipformer,
IndicConformer, VITS and Piper as if they were part of the app. None of them were: each is
either a stub, or a file that no other code references. That was rewritten rather than deleted,
because a stack document that overstates is worse than no stack document, and because the
distinction between "written" and "wired" is the most useful thing this repository has to say.

Read the status column. `IMPLEMENTED` means code exists **and** something calls it.

---

## Status legend

| Status | Meaning |
|---|---|
| **IMPLEMENTED** | Written, referenced, and reachable from a screen |
| **PARTIAL** | Reachable, but incomplete or unreachable in practice |
| **RESERVED** | Written, deliberately not reachable yet |
| **NOT IMPLEMENTED** | Not attempted |

---

## Core

| Technology | Version / value | Status |
|---|---|---|
| Kotlin | JVM target 17 | IMPLEMENTED |
| Jetpack Compose + Material 3 | BOM `2025.06.01` | IMPLEMENTED |
| MVVM (`ViewModel` + `StateFlow`) | — | IMPLEMENTED |
| Single Activity + Navigation Compose | `navigation-compose:2.9.0` | IMPLEMENTED |
| `minSdk` / `targetSdk` / `compileSdk` | 24 / 36 / 36 | IMPLEMENTED |
| Manual DI (`AppContainer`) | — | IMPLEMENTED |

## Local storage

| Technology | Version | Status |
|---|---|---|
| Room (SQLite) | `2.7.2` via KSP | IMPLEMENTED |
| DataStore Preferences | `1.1.7` | IMPLEMENTED |

Messages are stored as **ciphertext**, not plaintext. `docs/SECURITY.md` explains why.

## Networking — offline-first, peer-to-peer

| Technology | Status |
|---|---|
| Bluetooth RFCOMM (SPP) over a bonded peer | IMPLEMENTED |
| BLE / GATT | IMPLEMENTED |
| `ByteLink` transport abstraction (3 methods) | IMPLEMENTED |
| Wi-Fi Direct | **PARTIAL** — transport and `connect()` written and called from `NearbyManager.openLink()`, but no peer discovery and no UI entry point, so it cannot be reached by a user |
| `TransportManager` (adaptive transport selection) | **NOT IMPLEMENTED** — the file exists in `core/networking/` and nothing references it |
| iTP binary protocol (`ItpPacket`, magic `0x54`) | **NOT IMPLEMENTED** — superseded. `core/networking/itp/` is unreferenced. The live framing layer is `Envelope` / `EnvelopeCodec` in `domain/model/`, magic `0x49` (`'I'`), 47-byte header. |
| Mesh / multi-hop routing | **NOT IMPLEMENTED** — and deliberately so; `docs/LIMITATIONS.md` §1.1 |

There is **no** `INTERNET` permission in the release build, and no networking library. The
kernel refuses socket creation for a process without it.

## Security and crypto

| Component | Status |
|---|---|
| Android Keystore, AES-256-GCM, at rest (`EncryptionManager`) | IMPLEMENTED |
| Android Keystore, RSA identity key pair (`IdentityKey`) | IMPLEMENTED |
| Per-conversation AES-256-GCM on the wire (`SessionCrypto`) | IMPLEMENTED |
| HKDF-SHA256, RFC 5869 (`Hkdf`) | IMPLEMENTED, verified against RFC test vectors |
| Four-frame handshake, duplicate/replay rejection | IMPLEMENTED |
| Random 24-bit local device id, no hardware identifier | IMPLEMENTED |
| **ECDH key agreement** | **NOT IMPLEMENTED** — replaced by RSA. Commit `e357c56`, "one handset cannot do ECDH". Any document still citing ECDH is wrong. |
| `PacketIdCache` (TTL-bounded dedup) | **NOT IMPLEMENTED** — superseded by a `UNIQUE` index on `message_id` in Room |
| `Backoff` (jittered backoff utility) | **NOT IMPLEMENTED** — superseded by `MessageRepository.backoffMs` |
| Secure delete / key attestation | NOT IMPLEMENTED |

## Speech — offline

This is the section that was most wrong before, and it was wrong in the worst way: it claimed a
stub was the only thing present while a voice button existed in the UI. It has since been
rewritten against code that actually runs.

| Component | Status |
|---|---|
| `NeuralEngine` interface (VAD, STT partial/final, TTS, streaming) | IMPLEMENTED as an interface |
| 16 kHz mono 16-bit PCM, `AudioFormat` | IMPLEMENTED |
| `EnergyVad` — RMS energy threshold | **RESERVED** — written, nothing references it. Silence handling instead relies on Whisper returning an empty transcript. |
| `NeuralEngineFactory` | IMPLEMENTED — returns `SherpaOnnxNeuralEngine`. The `USE_REAL_NEURAL` flag and the stub path are **deleted from the code**, not disabled; the object survives only for its KDoc explaining why they went. |
| `SherpaOnnxNeuralEngine` | **IMPLEMENTED** — builds a real `OfflineRecognizer` via `newFromFile`, decodes real audio. Constructed with a **null AssetManager**, because passing one makes sherpa-onnx resolve model paths as assets and abort the process. |
| `ModelStore` | **IMPLEMENTED** — extracts the bundled model to `filesDir` and verifies SHA-256 per file before the recogniser is built |
| `SpeechRecognizer` interface + streaming (`pushFrame`, `endUtterance`, `resetStream`) | IMPLEMENTED |
| `SherpaSpeechRecognizer` | **IMPLEMENTED** — `AudioRecord` capture into the Whisper decoder, non-streaming, mutex-serialised decode |
| `AndroidMicRecorder` | **IMPLEMENTED** — real `AudioRecord`, 16 kHz mono PCM16, 320-sample frames |
| `FakeOfflineSpeechRecognizer` | **DELETED.** It returned fixed strings and is no longer in the source tree. |
| Platform `TextToSpeech` (`AndroidTextToSpeechEngine`) | IMPLEMENTED |
| `Language` — English + 9 Indic languages | IMPLEMENTED as a mapping |
| **Whisper base int8 via sherpa-onnx** | **IMPLEMENTED AND MEASURED** — 4 instrumented tests pass on a realme RMX2020 |
| **Silero VAD (INT8 ONNX)** | **NOT IMPLEMENTED** |
| **Zipformer / IndicConformer (streaming ASR)** | **NOT IMPLEMENTED** |
| **VITS / Piper (neural TTS)** | **NOT IMPLEMENTED** |
| Word error rate, per language | **NOT MEASURED** |
| App-process RAM, unload time | **NOT MEASURED** in the UI (624 MB and 179 MB were read in the instrumented test process; see `PERFORMANCE.md` §1) |
| `LanguageModelCache` (`core/neural/`) | **NOT IMPLEMENTED** — unreferenced |

`app/src/main/assets/models/` holds the real Whisper base int8 model: 160,609,290 bytes across
`base-encoder.int8.onnx`, `base-decoder.int8.onnx` and `base-tokens.txt`, with SHA-256 digests
recorded in that directory's README and enforced by `ModelStore`.

**The app now draws voice controls, and they do something.** `NeuralEngineFactory.isRealEngineAvailable()`
returns `true`, so the microphone button is live. Whisper is non-streaming, so there is no
partial transcript to render while the user is still speaking — the transcript appears when they
release, and it goes into the composer as a **draft to review**, never as an automatic send. That
last choice is deliberate: without a measured word error rate, sending dictated text without a
human reading it is a message-delivery risk this project will not take on its own recogniser's
confidence.

## Audio, PTT and background work

| Component | Status |
|---|---|
| PTT vs continuous mode **state** (`ChatViewModel.toggleVoiceMode`) | IMPLEMENTED — genuinely toggles |
| PTT capture path wired to a live recogniser | **PARTIAL** — `beginVoiceCapture`/`finishVoiceCapture` reach the real engine; PTT as a distinct capture policy from continuous mode is still not distinguished |
| **Volume key hook** | **NOT IMPLEMENTED** — no `KEYCODE_VOLUME_*` handling anywhere in the project |
| `ItantraVoiceService` (foreground service) | **RESERVED** — written, unreferenced, not in the manifest |
| Wake lock / vibrate | Permissions declared in release; no code currently uses them |

## Reliability

| Component | Status |
|---|---|
| Durable send queue in Room; `PENDING → SENT → DELIVERED → FAILED` | IMPLEMENTED |
| Capped exponential backoff | IMPLEMENTED |
| Bounded retries (`MAX_RETRIES` 3) then terminal `FAILED` | IMPLEMENTED |
| Duplicate rejection by full-UUID message id | IMPLEMENTED |
| Strict frame validation; length checked before allocation | IMPLEMENTED |
| Bounded outbound size | IMPLEMENTED |
| **Emergency SOS** (alarm stream, max volume, wake lock) | **NOT IMPLEMENTED** — `EmergencyAlertManager` exists in `core/emergency/` and nothing references it |
| **Telemetry** (STT/TTS/wire/E2E/RTF/RAM/BW) | **NOT IMPLEMENTED** — `Telemetry` exists in `core/telemetry/` and nothing references it. There is **no HUD**. |
| `TacticalHomeScreen`, `SettingsScreen`, `LanguagePicker` | **RESERVED** — written, unreachable from `MainActivity` |

## Build, test and packaging

| Item | Value |
|---|---|
| Gradle + KSP, R8 minify + resource shrinking (release) | IMPLEMENTED |
| Release APK | 196,626,175 bytes, no `INTERNET` |
| Debug APK | 214,334,743 bytes, has `INTERNET` (the negative control) |
| Unit tests | 211 per variant, 422 executions, 0 failures |
| Instrumented tests | 4, passing on a real handset; need a device, not in the `gates.yml` unit gate |
| Doc gate | `scripts/check_docs.py` |
| Offline gate | `scripts/check_offline.sh` |
| Native code / ABIs | `libsherpa-onnx-jni.so` and `libonnxruntime.so` for **arm64-v8a and armeabi-v7a**, 34,655,644 bytes total. `abiFilters` is set; the APK is a single universal artifact, not a split set. |

## Dependencies

**One third-party runtime dependency exists**, and this line used to claim there were none:

| artifact | why | licence |
|---|---|---|
| `com.bihe0832.android:lib-sherpa-onnx:6.25.21` | offline Whisper decoding for 10 languages | Apache-2.0 |

There is still **no analytics SDK, no crash reporter, no ad library and no network library**, and
the release build still has no `INTERNET`. What changed is that 34,655,644 bytes of the shipped
artifact is now third-party native code running in the app's process, which a reviewer cannot read
line by line. `docs/SECURITY.md` §7 states what that costs and what was verified about it.

## Principles this stack is built around

1. **100% offline.** No `INTERNET` permission in the release build.
2. **No Play Services.** Discovery and connection use raw platform Bluetooth APIs.
3. **Only the link is transport-specific.** Everything above `ByteLink` is identical whichever
   radio carried the bytes.
4. **A missing feature is absent, not faked.** Where a capability is not implemented, the UI
   does not offer it. `docs/LIMITATIONS.md` says why. The corollary, learned the hard way: a
   feature that *is* offered must genuinely work, which is why the fake recogniser was deleted
   rather than kept behind a flag.
5. **Unmeasured is written `NOT MEASURED`,** never estimated.

## Reference

For the full project picture — overview, DFD, CFD, feasibility, impact and references — see
`PRESENTATION.md`.