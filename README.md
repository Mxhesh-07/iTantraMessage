# iTantra Message

Offline-first peer-to-peer text messaging over raw Bluetooth. No server, no account, no phone
number, and **no internet permission in the release build** — the kernel refuses socket
creation for a process without it, so the app cannot open a network connection even if some
merged-in library asked it to.

Two devices, a few metres apart, Bluetooth on. That is the whole deployment.

---

## Status

| | |
|---|---|
| version | 0.1.0 |
| unit tests | **74 passing**, 0 failures |
| release APK | 1.28 MB, no `INTERNET` |
| offline gate | passing, with a live negative control |
| **two-device run** | **not done** |

The last row is the important one. Everything in this repository is verified by a command
whose output is reproducible. Nothing has been observed on two handsets exchanging a message,
so every runtime figure is written `NOT MEASURED` rather than estimated. See
`docs/LIMITATIONS.md` §3 for the full list.

---

## Build and verify

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
| `docs/TESTING.md` | the 74 tests, what they protect, what is untested |
| `docs/COLOR.md` | palette and computed contrast ratios |
| `docs/BUILD.md` | toolchain, versions, and why each is what it is |

Claims in the docs are marked **[ENFORCED]** (a command verifies it) or **[BY DESIGN]** (true
of the code, but nothing checks it). That distinction is the point of the two markers.

---

## How it is put together

```
ui/            Compose screens + ViewModels, one Activity
domain/        Envelope, EnvelopeCodec, ConversationId, MessageRepository
data/nearby/   NearbyManager, ByteLink + Reassembler, RfcommTransport, BleGattTransport
data/crypto/   EncryptionManager — Keystore AES-GCM
data/database/ Room entities, DAO, migrations
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