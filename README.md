# iTantra Message

[![gates](https://github.com/Mxhesh-07/iTantraMessage/actions/workflows/gates.yml/badge.svg)](https://github.com/Mxhesh-07/iTantraMessage/actions/workflows/gates.yml)

Offline-first peer-to-peer text messaging over raw Bluetooth. No server, no account, no phone
number, and **no internet permission in the release build** — the kernel refuses socket
creation for a process without it, so the app cannot open a network connection even if some
merged-in library asked it to.

Two devices, a few metres apart, Bluetooth on. That is the whole deployment.

Apache-2.0 licensed. Copyright 2026 iTantra Message contributors.

---

## Status

| | |
|---|---|
| version | 0.1.0 |
| unit tests | **396 passing**, 0 failures (198 methods × debug + release) |
| instrumented tests | **4 passing** on a real handset — real Whisper decode of real speech, all 10 languages |
| release APK | 196,626,175 bytes, no `INTERNET` |
| offline gate | passing, with a live negative control |
| two-device run | **partial** — RFCOMM link and the identity handshake are confirmed working across two real handsets. **A message has not yet been delivered end to end.** |

That last row is the important one, and the distinction in it is deliberate. What has been
observed: both phones install and declare no `INTERNET`, both accept an incoming RFCOMM socket,
and the identification handshake completes across the link. What has not: a message composed on
one handset and read on the other. Until that happens, every runtime figure — latency, battery,
delivery success — is written `NOT MEASURED` rather than estimated. See `docs/LIMITATIONS.md` §3
for the full list and `docs/TESTING.md` §7 for what was observed.

Everything else in this repository is verified by a command whose output is reproducible, and
the gates run on every push.

### Speech-to-text is measured, not asserted

Offline dictation uses **Whisper base (int8)** through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx),
which is the only open-source offline engine found that covers all ten languages in this app,
Kannada and Malayalam included. The model ships inside the APK and is extracted and SHA-256
verified on first use.

On a realme RMX2020 (Android 11, 2.8 GB RAM) `app/src/androidTest/.../WhisperDecodeInstrumentedTest`
passes all four tests, and the decode test produces an actual transcript:

```
pushed 176000 samples (11.00 s)
DECODED in 5151ms: [And so my fellow Americans asked not what your country can do for you,
                    ask what you can do for your country.]
matched 9/9 expected words
```

That is the microphone path, the decoder and the recogniser wrapper, exercised against a
recording of a human voice. The transcript is in the log because it was produced by the model,
not by the test. **Word error rate for the nine Indian languages is `NOT MEASURED`** — see
`docs/LIMITATIONS.md` §3.

---

## Build and verify

A fresh clone needs **Git LFS**, because the Whisper model is 153.2 MiB and
`base-decoder.int8.onnx` is over GitHub's 100 MiB per-file limit. Without it the
`.onnx` files arrive as pointer stubs and the build packages a broken app - verify
with `git lfs fsck`, and see `docs/BUILD.md` §1.3. The whole rest of the
repository is 267 KiB.

`JAVA_HOME` is not set in this environment, so every command needs it:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

.\gradlew.bat --no-daemon --console=plain testDebugUnitTest assembleRelease assembleDebug
bash scripts/check_offline.sh      # exit 0 = pass
python scripts/check_docs.py       # exit 0 = pass
```

Three gates, all of which must exit 0:

| gate | what it proves |
|---|---|
| `testDebugUnitTest` | the codec, the framer, the state machine and the conversation key behave |
| `check_offline.sh` | the release APK has no `INTERNET` **and** the debug one does |
| `check_docs.py` | every number in `docs/` matches the code, and no figure is invented |

Both scripts have been verified against negative controls — deliberately broken inputs that
make them fail. A check that cannot fail proves nothing. `SECURITY.md` §1.1 explains why the
offline gate requires the debug build to *contain* the permission.

---

## What it does

- Send text between two paired Android phones over **RFCOMM** (the default) or BLE GATT.
- Messages are stored **before** they are sent, and retried with backoff until they are
  delivered or the retry budget is spent. Nothing is dropped on a failure.
- Bodies are encrypted with **AES-GCM under an Android Keystore key**, and stored encrypted,
  so the only path from a database row to readable text goes through the crypto layer.
- Deduplication is a **unique index** in the database, not an application check — a check
  would be racy.
- Device identity is a locally-derived `IT-8F3A21` plus a display name. No account, no
  number, nothing to register.
- **Offline dictation** with Whisper base (int8), bundled in the APK and decoded on device.
  No audio leaves the phone and there is no network permission to send it over. The transcript
  lands in the composer as a draft to review, never as an automatic send.

## What it does not

No mesh routing, no group chat, no Wi-Fi Direct, no Play Services, **no delivery
acknowledgement**, no forward secrecy, no app lock, no dark theme. Each has a stated reason
and a trade-off in `docs/LIMITATIONS.md`.

The absence of acks is the one worth knowing about before a demo: a bubble showing "Sent"
means *this phone handed the bytes to Bluetooth*, not *the other phone received them*.

---

## Documentation

| | |
|---|---|
| `docs/SECURITY.md` | threat model, Keystore, permissions, and what is **not** protected |
| `docs/NETWORK_PROTOCOL.md` | the wire format, byte for byte |
| `docs/ERROR_HANDLING.md` | failure behaviour, retry, logging |
| `docs/LIMITATIONS.md` | what is missing, and the `NOT MEASURED` list |
| `docs/PERFORMANCE.md` | measured versus unmeasured, and how to measure |
| `docs/TESTING.md` | the test suites, what they protect, what is untested |
| `docs/COLOR.md` | palette and computed contrast ratios |
| `docs/BUILD.md` | toolchain, versions, and why each is what it is |
| `docs/TECH_STACK.md` | every technology, and crucially which are actually running |
| `docs/PRESENTATION.md` | project pack: overview, tech stack, DFD, CFD, feasibility, impact, references |

Claims in the docs are marked **[ENFORCED]** (a command verifies it) or **[BY DESIGN]** (true
of the code, but nothing checks it). That distinction is the point of the two markers.

Read `docs/TECH_STACK.md` before trusting any feature list, including the one above. It carries
a status column, because this project has accumulated code that is written but not reachable,
and a stack document that cannot tell the two apart is marketing.

---

## How it is put together

```
ui/            Compose screens + ViewModels, one Activity
domain/        Envelope, EnvelopeCodec, ConversationId, MessageRepository
domain/speech/ SpeechRecognizer + TextToSpeechEngine interfaces, Language (10 locales)
domain/audio/  AudioFormat (16 kHz mono PCM 16-bit), EnergyVad
data/nearby/   NearbyManager, ByteLink + Reassembler, RfcommTransport, BleGattTransport,
               WifiDirectTransport
data/crypto/   EncryptionManager (at rest), SessionCrypto (on the wire), IdentityKey,
               SessionKeys, Hkdf
data/database/ Room entities, DAO, migrations
data/device/   DeviceIdentity (random 24-bit id), PeerIdentity registry
core/          NeuralEngine abstraction + factory, SherpaOnnxNeuralEngine (Whisper via
               sherpa-onnx), ModelStore (extract + SHA-256), packet layer, emergency alerts
```

Two framing layers, kept apart on purpose:

```
text → Envelope (47-byte header) → Reassembler ("ITM1" + length) → ByteLink → radio
```

The transports know nothing about messages and the codec knows nothing about Bluetooth.
Framing lives above the transport because the two deliver bytes incompatibly — RFCOMM hands
over whatever the socket buffered, BLE delivers one MTU per notification — and a transport
that framed its own messages would frame them differently per transport, so encryption and
dedup would have to be written twice and would behave differently.

`data/nearby/ByteLink.kt` is the seam. Everything above it is written against that interface,
which is why the repository contains no `when (transport)`.

---

## Requirements

- Android 7.0 (API 24) and above; built and gated against API 36.
- Two phones, **paired with each other** in Android Settings → Bluetooth. An app cannot
  initiate pairing — Android requires user interaction at the system level — so RFCOMM
  connects to already-bonded devices and BLE discovery finds the rest.

RFCOMM is the default because it needs no advertising, only bonding. BLE GATT is implemented
and selectable; advertising was not observed working off-device during testing on the sibling
project, which is one of the reasons RFCOMM is the default. See `docs/LIMITATIONS.md` §2.3.