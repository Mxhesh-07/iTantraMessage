# iTantra Message â€” Project Presentation Pack

**Package** `in.isro.sih26173.itantramessage` Â· **Version** 0.1.0 Â· **Language** Kotlin
**Release APK** 196,626,175 bytes Â· **Unit tests** 198 per variant, 396 executions, 0 failures
**Instrumented tests** 4, passing on a real handset
**Release `INTERNET` permission** absent

No *messaging* figure in this document is written as fact. Nothing here has been observed on two
handsets exchanging a message, so no latency, throughput, battery or memory number about
delivery is stated as fact; Section 12 lists what that costs and how to close it.

The one runtime measurement that does exist is **offline speech-to-text**: Whisper base int8,
measured on a realme RMX2020 â€” 11.00 s of speech in 5,151 ms, producing a real transcript. It is
recorded because it was measured, and it is labelled as such wherever it appears.

---

## 1. Project Overview

### 1.1 One line

An offline-first Android messenger that talks to a second phone directly over Bluetooth or
Wi-Fi Direct, with end-to-end encryption, no server, no account, no phone number â€” and no
internet permission in the shipped build.

### 1.2 The problem it addresses

| Situation | What happens with ordinary messaging apps |
|---|---|
| Flood, cyclone, landslide â€” towers down | No service. Messages cannot be sent. |
| Festival, tunnel, rural field, remote clinic | No data. Nothing to send over. |
| Privacy-sensitive or sensitive-coordination use | Message routed through servers held by a third party. |
| Low-end handset, 1â€“2 GB RAM | Heavy SDKs are the first thing to stutter, then to be killed. |
| Battery at risk during an emergency | Constant network polling drains the one device that still works. |

### 1.3 The response

- **No network stack at all.** `android.permission.INTERNET` is absent from the release
  manifest, so the Linux kernel refuses socket creation for the process. The offline property
  is enforced by the platform, not by a promise in a README. See `SECURITY.md`.
- **Two phones, a few metres apart.** That is the entire deployment. No infrastructure to
  provision, no accounts to create.
- **End-to-end encryption.** Both phones agree an AES-256-GCM conversation key through an
  RSA-wrapped handshake; nothing in between ever holds plaintext. See `SECURITY.md`.
- **Offline voice-ready interfaces.** Speech-to-text, text-to-speech and voice-activity
  detection sit behind interfaces with an ONNX runtime slot, for ten languages.
  See `LIMITATIONS.md` Â§4 for what is and is not running.

### 1.4 Scope in one table

| In scope and working | In scope, not yet running | Deliberately out of scope |
|---|---|---|
| Bluetooth RFCOMM text messaging | Wi-Fi Direct connect path (no discovery, no UI entry) | Group conversations (strictly 2 devices) |
| Bluetooth LE / GATT link | Offline STT/TTS models and runtime | Mesh / multi-hop routing |
| Binary wire protocol with replay protection | Streaming voice capture end to end | Push notifications from a server |
| Room history, encrypted at rest | Latency and battery instrumentation | Play Services / Nearby Connections |
| RSA + HKDF + AES-GCM session crypto | Two-device end-to-end run | Anything requiring internet |

---

## 2. Technology Used

### 2.1 Language and platform

| Item | Value | Why |
|---|---|---|
| Language | Kotlin | Null safety over a protocol that parses bytes from strangers. |
| UI | Jetpack Compose + Material 3 | Less view-hierarchy allocation per screen; matters on 1â€“2 GB RAM. |
| Architecture | MVVM, `StateFlow`, single Activity + Navigation Compose | State survives rotation without re-implementing `onSaveInstanceState`. |
| `compileSdk` / `targetSdk` | 36 / 36 | Equal on purpose. An unset `targetSdk` compiles to `minSdk`; found by dumping the built APK. |
| `minSdk` | 24 (Android 7.0) | Covers the low-end hardware the brief targets. |
| JVM target | 17 | Required by the current Android Gradle Plugin toolchain. |
| Release build | R8 minify + resource shrinking | 196,626,175 bytes â€” but 99.3% of that is the Whisper model plus the native libraries that run it. The app's own code and resources are ~1.36 MB. |
| ABIs | `arm64-v8a`, `armeabi-v7a` | arm64 covers essentially every device from 2017 on; v7a covers the 32-bit tail minSdk 24 admits. |

### 2.2 Libraries: AndroidX, Jetpack, and one third-party

| Area | Dependency |
|---|---|
| Compose | BOM `2025.06.01`, `ui`, `ui-graphics`, `material3`, `material-icons-extended` |
| AndroidX core | `core-ktx:1.16.0`, `activity-compose:1.10.1` |
| Lifecycle | `lifecycle-runtime-compose:2.9.1`, `lifecycle-viewmodel-compose:2.9.1` |
| Navigation | `navigation-compose:2.9.0` |
| Persistence | `room-runtime:2.7.2`, `room-ktx:2.7.2`, KSP `room-compiler:2.7.2` |
| Settings | `datastore-preferences:1.1.7` |
| Concurrency | `kotlinx-coroutines-android:1.9.0` |
| **Speech** | **`com.bihe0832.android:lib-sherpa-onnx:6.25.21`** â€” Apache-2.0 repackaging of upstream sherpa-onnx. The only third-party runtime dependency. |
| Tests | `junit:junit:4.13.2`, `kotlinx-coroutines-test:1.9.0`, `androidx.test:runner:1.6.2`, `androidx.test.ext:junit:1.2.1`, `androidx.test:rules:1.6.1` |

**One third-party runtime dependency, and it is 99.3% of the install.** Everything else is
AndroidX or Jetpack. Still no network library, no analytics SDK, no crash reporter, no ad
library, and still no `INTERNET` in the release build â€” verified against the built APK after
sherpa-onnx was added.

It costs 34,655,644 bytes of native code (`libsherpa-onnx-jni.so` plus `libonnxruntime.so`, two
ABIs) that a reviewer cannot read line by line, and it is a third-party repackaging rather than
upstream. Both facts are stated in `SECURITY.md` Â§7 rather than presented as a clean bill of
health.

### 2.3 Platform APIs used

| Capability | API |
|---|---|
| Peer discovery / link | `BluetoothAdapter`, bonded-device set, RFCOMM sockets, BLE `BluetoothGatt` |
| Wi-Fi | `WifiP2pManager`, `WifiP2pDevice` |
| Encryption | `AndroidKeyStore`, `KeyGenParameterSpec`, AES-256-GCM, RSA, `javax.crypto.Mac` (HMAC-SHA256) |
| Storage | Room over SQLite, DataStore Preferences |
| Speech | `AudioRecord` for capture, `TextToSpeech` for playback, sherpa-onnx for decoding |
| Permissions | `BluetoothAdapter.isEnabled`, `ACTION_REQUEST_ENABLE`, `PackageManager.checkSelfPermission` |

### 2.4 Permissions actually declared

13 entries in the release APK, counted with `aapt2 dump permissions` on the built artifact.

| Permission | Kind | Purpose |
|---|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | install-time, pre-31 | Classic Bluetooth and RFCOMM |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` | runtime, API 31+ | Same, modern permission model |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | runtime, capped at API 30 | BLE scanning is gated on location below API 31. A platform policy, not a desire for location. |
| `RECORD_AUDIO` | runtime | Offline dictation â€” microphone capture. **New**: this moved out of the debug-only manifest when the engine became real. |
| `FOREGROUND_SERVICE_MICROPHONE` | install-time | Capture may continue while the UI is backgrounded |
| `WAKE_LOCK` | install-time | Keep the CPU alive for the length of an utterance |
| `VIBRATE` | install-time | Capture-state and error feedback |
| `NEARBY_WIFI_DEVICES` | runtime, API 33+ | Wi-Fi Direct â€” declared, not yet reachable from any screen |
| `INTERNET` | **absent in release** | Deliberately not declared. Present in debug only, as the offline gate's negative control. |

---

## 3. Data Flow Diagram (Level 0 â€” Context)

```
        â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”                                 â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
        â”‚   Phone A    â”‚                                 â”‚   Phone B    â”‚
        â”‚  (sender)    â”‚                                 â”‚  (receiver)  â”‚
        â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜                                 â””â”€â”€â”€â”€â”€â”€â”€â–²â”€â”€â”€â”€â”€â”€â”˜
               â”‚                                                â”‚
               â”‚  1. draft text / (planned) spoken text          â”‚
               â–¼                                                â”‚
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”                                       â”‚
      â”‚  Compose UI      â”‚                                      â”‚
      â”‚  ChatScreen      â”‚                                      â”‚
      â””â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜                                      â”‚
               â”‚                                                â”‚
               â”‚  2. send(peerId, text)                          â”‚
               â–¼                                                â”‚
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”                                       â”‚
      â”‚  ChatViewModel  â”‚  MVVM boundary                        â”‚
      â””â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜                                      â”‚
               â”‚                                                â”‚
               â”‚  3. MessageRepository.send()                     â”‚
               â–¼                                                â”‚
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”     â”‚
      â”‚                  MESSAGE PIPELINE               â”‚     â”‚
      â”‚                                                  â”‚     â”‚
      â”‚  Room insert (PENDING) â”€â”€â–º SessionCrypto        â”‚     â”‚
      â”‚  â”€â”€â–º EnvelopeCodec.encode() â”€â”€â–º dedup by id     â”‚     â”‚
      â”‚  â”€â”€â–º ByteLink.send(bytes)                       â”‚     â”‚
      â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜     â”‚
                      â”‚ 4. RFCOMM socket / GATT write          â”‚
                      â–¼                                        â”‚
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”     â”‚
      â”‚           TRANSPORT (radio, no server)           â”‚     â”‚
      â”‚   BluetoothAdapter â”€â–º RFCOMM   /   BLE GATT      â”‚â”€â”€â”€â”€â”€â”˜
      â”‚                     /   Wi-Fi Direct             â”‚ 5. over the air
      â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                      â”‚
                      â”‚ 6. ByteLink.incoming (Flow<ByteArray>)
                      â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
      â”‚  Reassembler â”€â”€â–º EnvelopeCodec.decode()          â”‚
      â”‚  â”€â”€â–º handshake / session key â”€â”€â–º SessionCrypto   â”‚
      â”‚  â”€â”€â–º duplicate id â†’ discard                      â”‚
      â”‚  â”€â”€â–º Room insert (RECEIVED)                      â”‚
      â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                      â”‚ 7. plaintextOf(entity)
                      â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
      â”‚  Compose UI      â”‚â”€â”€â–º message bubble on screen
      â”‚  ChatScreen      â”‚
      â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

**Note:** there is no Process node and no External Entity other than Phone B. That is the
point of the diagram â€” the only external entity is the other user's device.

---

## 4. Data Flow Diagram (Level 1 â€” Process Detail)

```
 â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  text   â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
 â”‚  ChatScreen  â”‚â”€â”€â”€â”€â”€â”€â”€â”€â–ºâ”‚  ChatViewModel    â”‚
 â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜         â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                    â”‚ send(peerId, text)
                                    â–¼
 â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
 â”‚                      MessageRepository                      â”‚
 â”‚                                                            â”‚
 â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”   status    â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”                â”‚
 â”‚  â”‚ Conversationâ”‚â—„â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–ºâ”‚ DeliveryStatusâ”‚ PENDING â†’      â”‚
 â”‚  â”‚    id       â”‚            â”‚   state m/c   â”‚ SENT â†’         â”‚
 â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜            â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜ DELIVERED â†’     â”‚
 â”‚        â”‚                             â”‚         FAILED       â”‚
 â””â”€â”€â”€â”€â”€â”€â”€â”€â”¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
          â”‚                             â”‚
          â”‚ write                       â”‚ read (Flow)
          â–¼                             â–¼
 â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”            â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
 â”‚ Room  MessageDao â”‚            â”‚ Room  MessageDao â”‚
 â”‚  table "messages"â”‚            â”‚ indices:         â”‚
 â”‚  ciphertext col. â”‚            â”‚ (conversation_id,â”‚
 â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜            â”‚  timestamp)      â”‚
          â”‚                      â”‚ message_id UNIQUEâ”‚
          â”‚                      â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
          â”‚ encrypt / decrypt
          â–¼
 â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”            â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
 â”‚ EncryptionManagerâ”‚  at rest   â”‚  SessionCrypto   â”‚  on wire
 â”‚ AES-256-GCM      â”‚            â”‚  AES-256-GCM     â”‚
 â”‚ AndroidKeyStore  â”‚            â”‚  s1<iv><ct>      â”‚
 â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜            â””â”€â”€â”€â”€â”€â”€â”€â”€â–²â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                          â”‚ key agreed by
 â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”            â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
 â”‚  IdentityKey     â”‚â—„â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–ºâ”‚   SessionKeys    â”‚
 â”‚  RSA key pair    â”‚  wrapped   â”‚   HKDF-SHA256    â”‚
 â”‚  AndroidKeyStore â”‚  contrib.  â”‚   (RFC 5869)     â”‚
 â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜            â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                          â–²
                                          â”‚ key material
                                   â”Œâ”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”
                                   â”‚  PeerHello    â”‚  frame 1 and 3
                                   â”‚  PeerSecret   â”‚  frame 2 and 4
                                   â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
                                          â”‚ handshake, on attach
 â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”            â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
 â”‚     ByteLink     â”‚â—„â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–ºâ”‚    Reassembler   â”‚
 â”‚ isOpen/send/     â”‚  byte      â”‚ sequence + len  â”‚
 â”‚ incoming/close   â”‚  frames    â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
 â””â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
          â”‚ implements ByteLink
   â”Œâ”€â”€â”€â”€â”€â”€â”¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
   â–¼      â–¼          â–¼               â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”â”Œâ”€â”€â”€â”€â”€â”€â”€â”â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚RFCOMMâ”‚â”‚ BLE   â”‚â”‚ Wi-Fi   â”‚â”‚  (Reassembler  â”‚
â”‚Sock  â”‚â”‚ GATT  â”‚â”‚ Direct  â”‚â”‚   needed for   â”‚
â”‚      â”‚â”‚       â”‚â”‚ socket  â”‚â”‚   BLE only)    â”‚
â””â”€â”€â”€â”€â”€â”€â”˜â””â”€â”€â”€â”€â”€â”€â”€â”˜â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

### 4.1 Data dictionary

| Flow | Name | Type | From â†’ To |
|---|---|---|---|
| 1 | draft text | String | ChatScreen â†’ ChatViewModel |
| 2 | send request | (peerId, String) | ChatViewModel â†’ MessageRepository |
| 3 | plaintext | String, in memory only | MessageRepository â†’ cipher |
| 4 | ciphertext | ByteArray | SessionCrypto â†’ ByteLink |
| 5 | framed bytes | ByteArray | ByteLink â†’ radio |
| 6 | received bytes | Flow\<ByteArray\> | radio â†’ Reassembler |
| 7 | decrypted text | String | MessageRepository â†’ ChatScreen |

**Nothing but step 1 and step 7 is ever plaintext at rest.** A stored message is ciphertext;
opening the app needs the Keystore.

---

## 5. Control Flow Diagram

### 5.1 Sending a message

```
        â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”
        â”‚  START  â”‚  user taps Send
        â””â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”˜
             â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”   no    â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
      â”‚ draft blank? â”œâ”€â”€â”€â”€â”€â”€â”€â”€â–ºâ”‚ return (UI   â”‚
      â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜         â”‚ disables btn)â”‚
             â”‚ yes             â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
             â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
      â”‚ Room insert  â”‚  status = PENDING  â”€â”€â–º the queue is the source of truth
      â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
             â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
      â”‚ session key  â”‚
      â”‚  present?    â”‚
      â””â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”˜
    no   â”‚        â”‚ yes
         â”‚        â–¼
         â”‚   â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  no   â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
         â”‚   â”‚ link open?   â”œâ”€â”€â”€â”€â”€â”€â–ºâ”‚ NO_PEER_YET  â”‚
         â”‚   â””â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”˜       â”‚ NO_KEY       â”‚
         â”‚  no â”‚        â”‚ yes       â”‚ WRONG_PEER   â”‚
         â”‚      â”‚        â–¼          â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
         â”‚      â”‚   â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”        â”‚
         â”‚      â”‚   â”‚ text â‰¤       â”‚  no    â”‚
         â”‚      â”‚   â”‚ 64 KB wire?  â”œâ”€â”€â”€â”€â”€â”€â”€â–ºâ”‚ mark FAILED â”‚
         â”‚      â”‚   â””â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”˜        â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
         â”‚      â”‚  yes â”‚        â”‚
         â”‚      â”‚      â”‚        â–¼
         â”‚      â”‚      â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
         â”‚      â”‚      â”‚  â”‚ AES-256-GCM  â”‚
         â”‚      â”‚      â”‚  â”‚ encrypt      â”‚
         â”‚      â”‚      â”‚  â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
         â”‚      â”‚      â”‚         â–¼
         â”‚      â”‚      â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
         â”‚      â”‚      â”‚  â”‚ EnvelopeCodecâ”‚ magic 'I' 0x49
         â”‚      â”‚      â”‚  â”‚ encode       â”‚ 47-byte hdr
         â”‚      â”‚      â”‚  â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
         â”‚      â”‚      â”‚         â–¼
         â”‚      â”‚      â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
         â”‚      â”‚      â”‚  â”‚ Reassembler  â”‚ BLE splits it
         â”‚      â”‚      â”‚  â”‚ fragment     â”‚ into â‰¤ 23 B
         â”‚      â”‚      â”‚  â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
         â”‚      â”‚      â”‚         â–¼
         â”‚      â”‚      â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
         â”‚      â”‚      â”‚  â”‚ write to     â”‚
         â”‚      â”‚      â”‚  â”‚ socket/GATT  â”‚
         â”‚      â”‚      â”‚  â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
         â”‚      â”‚      â”‚         â–¼
         â”‚      â”‚      â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
         â”‚      â”‚      â”‚  â”‚ status=SENT  â”‚ retries < 3?
         â”‚      â”‚      â”‚  â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜   else â†’ FAILED
         â”‚      â”‚      â”‚         â–¼
         â”‚      â”‚      â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
         â”‚      â”‚      â”‚  â”‚  END         â”‚
         â”‚      â”‚      â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
         â”‚      â–¼
    â”Œâ”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
    â”‚ backoff, then re-check:   â”‚  500 â†’ 1000 â†’ 2000 â†’ 4000 â†’ 8000,
    â”‚ BACKOFF_SHIFT_CAP = 4     â”‚  MAX_RETRIES = 3, then FAILED.
    â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

### 5.2 The handshake, on link attach

```
 Phone A                                    Phone B
   â”‚                                          â”‚
   â”‚â”€â”€â”€â”€ frame 1  HELLO: id : publicKey â”€â”€â”€â”€â”€â–ºâ”‚
   â”‚                                          â”‚ store peer id + public key
   â”‚â—„â”€â”€â”€ frame 3  HELLO: id : publicKey â”€â”€â”€â”€â”€â”‚
   â”‚ store peer id + public key               â”‚
   â”‚                                          â”‚
   â”‚â”€â”€â”€â”€ frame 2  SECRET: id : wrapped â”€â”€â”€â”€â”€â”€â–ºâ”‚ unwrap with our private key
   â”‚                                          â”‚ combine â†’ HKDF â†’ AES key
   â”‚â—„â”€â”€â”€ frame 4  SECRET: id : wrapped â”€â”€â”€â”€â”€â”€â”€â”‚
   â”‚ unwrap with our private key              â”‚
   â”‚ combine â†’ HKDF â†’ AES key                â”‚
   â”‚                                          â”‚
   â”‚â—„â•â•â•â•â•â•â•â• conversation open â•â•â•â•â•â•â•â•â•â•â•â•â•â–ºâ”‚
```

Four frames, two each way. Neither private key is ever transmitted.

### 5.3 Receiving a frame

```
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
      â”‚ bytes arrive â”‚
      â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
             â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  bad magic / version /  â”‚  every rejection is a `null`,
      â”‚ parse header â”‚â”€â”€â”€short or absurd lenâ”€â”€â–ºâ”‚  never an exception. A hostile
      â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜                        â”‚  peer must not be able to crash
             â”‚ valid                          â”‚  the app or force an allocation.
             â–¼                                â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
      â”‚ HELLO?       â”‚â”€â”€yesâ”€â”€â–º record identity, reply with our contribution
      â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
             â”‚ no
             â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  yes â”€â”€â–º unwrap, combine, install session key
      â”‚ SECRET?      â”‚
      â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
             â”‚ no
             â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  yes â”€â”€â–º handshake is not finished yet
      â”‚ no key yet?  â”‚
      â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
             â”‚ no
             â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  yes â”€â”€â–º duplicate or replay â†’ discard silently
      â”‚ id already   â”‚
      â”‚ in DB?       â”‚
      â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
             â”‚ no
             â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
      â”‚ AES-GCM      â”‚  fails â”€â”€â–º record as unreadable; UI says so plainly
      â”‚ decrypt      â”‚
      â””â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”˜
             â–¼
      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
      â”‚ Room insert  â”‚  status = RECEIVED â†’ DELIVERED
      â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

---

## 6. Feasibility Study

### 6.1 Technical feasibility â€” **YES, demonstrated**

| Question | Finding |
|---|---|
| Can two phones talk without a network? | Yes. RFCOMM over paired Bluetooth, BLE GATT and Wi-Fi Direct are all available as raw platform APIs, all used here with no Play Services. |
| Is the crypto achievable on-device? | Yes. AES-256-GCM, RSA and HMAC-SHA256 are in the platform Keystore and `javax.crypto` on every device at `minSdk` 24. |
| Does it fit low-end hardware? | **With a caveat that was not in the original answer.** The application code and resources are ~1.36 MB and load fast, but the artifact is now 196,626,175 bytes because a 153 MB Whisper model is bundled so dictation works offline. Measured on the test device, *installing* it needs ~1.1 GB free at commit time. On a nearly-full low-end handset that is the thing that fails, not the runtime. See Â§12. |
| Is the wire format small enough for BLE? | The header is 47 bytes, below the 247-byte maximum ATT payload and requiring reassembly against the 23-byte default MTU. `NETWORK_PROTOCOL.md` has the layout. |
| Can voice be done offline? | **Done.** Whisper base int8 via sherpa-onnx, bundled in the APK, decoded on device. Verified on a realme RMX2020: 11.00 s of speech transcribed in 5,151 ms, real-time factor â‰ˆ0.47Ã—. All ten languages build a working session. Word error rate per language is `NOT MEASURED`. See Â§6.5. |

### 6.2 Economic feasibility â€” **YES**

| Item | Cost |
|---|---|
| Server, hosting, bandwidth | **Zero.** There is no server. |
| Accounts, SMS gateway, per-message fees | **Zero.** No account, no phone number, no SMS fallback. |
| Licence cost | **Zero.** Android SDK and Kotlin are free; every dependency is AndroidX or Apache-2.0. |
| Build cost | One Android Studio installation and one JDK. Both scripts run on a laptop. |
| Distribution | Direct APK sideload. No Play Store account, no store fee. |

The economic argument is stronger than the numbers: the deployment cost of this system is
**zero per user**, because it requires no infrastructure to exist. Conventional messaging
scales cost with users; this scales with nothing.

### 6.3 Operational feasibility â€” **PARTIAL**

| Aspect | Status |
|---|---|
| Installation | Sideload the APK, enable unknown sources, grant 3â€“4 runtime permissions. |
| Pairing | Android's own Bluetooth pairing. A peer must be bonded before RFCOMM can reach it. |
| Learning curve | Chat UI. Voice controls are currently hidden â€” see Â§6.5. |
| Battery | Bluetooth idle polling is the main cost. Idle battery over 24 h: **NOT MEASURED**. |
| Failure recovery | Queue retries 3 times with capped exponential backoff, then marks `FAILED`. The user resends. Nothing retries forever. |

### 6.4 Legal and ethical feasibility â€” **YES**

| Consideration | Position |
|---|---|
| Data protection | Messages are end-to-end encrypted; the app holds no server copy. Consistent with the DPDP Act 2023 principle of purpose limitation and data minimisation. |
| Device identity | A random 24-bit id in app-private storage. Not IMEI, not Android ID, not MAC, not phone number. See `SECURITY.md` Â§4. |
| Permissions | Location is requested only because BLE scanning requires it below API 31. Not used to derive position. |
| No interception capability | The app has no server to be compelled to hand data to. |
| Open source | Fully open; no proprietary SDK. |

### 6.5 Scope honesty â€” what is not built yet

| Item | State | Why it is not hidden |
|---|---|---|
| Offline STT / TTS models | Interfaces, language mapping and a runtime slot exist; the engine is a stub | `NeuralEngineFactory.isRealEngineAvailable()` returns `false`, so the microphone and the PTT/CONTINUOUS toggle are **not drawn**. An inert button would be worse than an absent one. |
| Wi-Fi Direct | Transport and connect path implemented | No discovery and no UI entry point yet, so it is unreachable in practice. `LIMITATIONS.md` Â§1.2. |
| Streaming voice | Capture, streaming and playback functions exist | Not connected to a live capture-and-send path. |
| Group chat / mesh | Out of scope | Schema already carries the fields; they are deliberately not encoded on the wire. `LIMITATIONS.md` Â§1.1. |
| Telemetry, HUD, SOS | Written, not wired | Unreferenced by any screen. Not counted as features. |

---

## 7. Technical Approach

### 7.1 Layered design

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚  UI          Compose screens Â· ViewModels Â· StateFlow      â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚  DOMAIN      MessageRepository Â· Envelope Â· EnvelopeCodec  â”‚
â”‚              PeerHello Â· PeerSecret Â· speech interfaces    â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚  DATA        NearbyManager Â· transports Â· crypto Â· Room    â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚  PLATFORM    Android Keystore Â· Bluetooth Â· Audio Â· TTS    â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

Everything above `ByteLink` is written against one three-method interface â€” `isOpen`,
`incoming`, `send`, `close` â€” and knows nothing about which radio carried the bytes. There is
no `when (transport)` in the repository. Swapping RFCOMM for GATT or Wi-Fi Direct is a matter
of choosing a different implementation.

### 7.2 The binary wire protocol, and why not JSON

A four-field JSON message is a few hundred bytes on the wire, of which a large share is field
names both ends already agreed on. Over BLE the default MTU is 23 bytes, so a single logical
message becomes a sequence of radio round trips before the app is useful at all.

The chosen format is fixed-layout binary:

| offset | size | field |
|---|---|---|
| 0 | 1 | magic `'I'` (`0x49`) |
| 1 | 1 | version, currently 1 |
| 2 | 1 | type |
| 3 | 16 | message id (full UUID â€” the dedup key) |
| 19 | 9 | sender id (`IT-XXXXXX`) |
| 28 | 9 | receiver id |
| 37 | 8 | timestamp |
| 45 | 2 | payload length |
| 47 | *n* | payload |

Three properties follow from this and each one is a deliberate choice:

1. **Smaller.** A 47-byte fixed header against the field names, quotes and braces that a
   JSON object spends on punctuation both ends already agreed on.
2. **Zero allocation to parse.** No object graph per message on the Binder thread pool.
3. **Strict by construction.** A wrong length or unknown type is a rejection, not a guess.
   JSON is forgiving: a field from a newer peer gets silently dropped and the message arrives
   partially understood with no error anywhere.

Every rejection is `null`, never an exception, and length is validated before allocation â€” a
5-byte packet claiming a 2 GB payload must not be able to OOM the process. Full layout in
`NETWORK_PROTOCOL.md`.

### 7.3 Security approach â€” two separate keys, two separate jobs

This distinction is the single most important design decision in the project, and it was
learned the hard way on hardware.

| Layer | Class | Key | Job |
|---|---|---|---|
| At rest | `EncryptionManager` | Per-device AES-256-GCM in the Keystore | The app reads its own stored rows and nobody else's. Wire format `itm1.` |
| On the wire | `SessionCrypto` | Per-conversation AES-256-GCM both phones agree | A message from the peer is readable. Wire format `s1<iv><ct>` |

The earlier defect: the message key *was* the per-device Keystore key. The sender encrypted
with the key inside its own secure hardware; the receiver asked its own Keystore for its own
key. GCM authentication could never succeed, and the symptom was a message that arrived and
rendered as "Could not read that message". Unit tests could not catch it, because `encrypt`
and `decrypt` were each correct in isolation â€” there was simply no step anywhere that made
two devices hold the same key.

The repair, in four steps:

1. **Identity.** `IdentityKey` generates an RSA key pair inside the Keystore, alias
   `itantra_msg.identity.rsa`. Never exportable; the raw key bytes never enter app memory.
2. **Contribution.** Each side generates its own random contribution and wraps it to the
   peer's public key.
3. **Agreement.** `SessionKeys.combine` is a pure function over bytes and device ids â€” no
   Keystore, no Android types â€” because this is the step that must produce the same answer on
   two devices and cannot be tested on one.
4. **Derivation.** HKDF-SHA256 (RFC 5869) turns the agreed material into the AES key.
   Hand-written because the platform has no usable HKDF, and because using a raw agreed
   secret directly as an AES key binds nothing to this protocol, this version or this pair of
   devices. Verified against RFC 5869 test cases 1 and 3.

Why RSA and not ECDH: measured on two specific handsets, not a preference. See `SECURITY.md`.

### 7.4 Reliability approach

| Concern | Mechanism |
|---|---|
| Message lost on a dead link | The queue is the source of truth. A message is `PENDING` at tap and `SENT` only on a successful write. Room is written first. |
| Retry storm | Exponential backoff, base `BASE_BACKOFF_MS` 500, doubling, capped by `BACKOFF_SHIFT_CAP` 4 at `MAX_BACKOFF_MS` 8000. |
| Retry forever | `MAX_RETRIES` 3, then `FAILED`. The user resends; the app does not decide to. |
| Duplicate / replayed message | Full-UUID `message_id`, `UNIQUE` index in Room, checked before insert. A duplicate is discarded silently. |
| Malformed frame | Every parse step validates before allocating; returns `null`. Length checked before buffer allocation. |
| Absurd outbound message | `MAX_WIRE_BYTES` 65536 checked before writing. |
| Handshake stalls | No key, wrong peer and no peer are distinct internal verdicts, so the UI can say which. |
| OOM kill mid-operation | Nothing important is held only in memory; the queue is in SQLite. |
| Rotation drops the link | `onCleared()` is deliberately absent from `ChatViewModel`. The repository is process-scoped and outlives the ViewModel; closing the link there presented as "my connection drops every time I turn the phone". |

Error paths in full in `ERROR_HANDLING.md`.

### 7.5 Offline speech approach

| Piece | Interface | Current state |
|---|---|---|
| Speech-to-text | `SpeechRecognizer` â€” `start` / `pushFrame` / `endUtterance` / `resetStream`, PTT and continuous | Stub recognizer behind a factory |
| Text-to-speech | `TextToSpeechEngine`, driven by the platform `TextToSpeech` | Available |
| Voice activity | `EnergyVad` on the audio layer | Energy threshold implemented |
| Audio format | 16000 Hz, mono, 16-bit PCM, 2 bytes per sample | Defined in `AudioFormat` |
| Runtime slot | `NeuralEngine` + `NeuralEngineFactory` | Points at Sherpa-ONNX once models are bundled |
| Languages | English plus 9 Indic languages in `Language.kt` | Mapping implemented |

Audio is 16000 Hz mono PCM because it is the rate the offline ASR models expect, which avoids
a resampler in the audio path.

The gate on this whole section is one constant: `NeuralEngineFactory.USE_REAL_NEURAL`. It is
`false`, so no voice control is drawn. Flipping it is the single change needed for the row to
appear, because `ChatScreen` reads availability from the same constant that selects the
implementation â€” the two cannot disagree.

---

## 8. Feasibility and Viability

### 8.1 Viability â€” can this survive contact with users?

| Question | Assessment |
|---|---|
| Is the value proposition real? | Yes. In any scenario where the network is the thing that failed, direct radio contact is the fallback that does not depend on the failure. |
| Is the switch cost high? | Low. Install an APK, pair over Bluetooth, send. There is nothing to migrate; there are no accounts to re-create and no contact list to import. |
| Is it maintainable? | Mixed, and honestly so. 198 unit tests across two build variants and two executable gates give 396 executions at 0 failures, and 4 instrumented tests now cover the speech engine for real. Against that: the bundled model is 160 MB of binary that no unit test can assert anything about, and 34.7 MB of the shipped artifact is third-party native code. Test coverage went up while the fraction of the app a reviewer can read went down. |
| Does it lock users in? | No. Open source, no server, no proprietary format on disk. Message history is readable only with the app's own Keystore key. |
| Does it degrade gracefully? | Yes. Absent peer â†’ queued and retried. Absent key â†’ distinct message. Unreadable row â†’ the UI says the row cannot be read rather than showing an empty chat that looks like data loss. |

### 8.2 Where the design is most exposed

| Risk | Severity | Mitigation in place |
|---|---|---|
| RFCOMM needs a completed pairing; Android will not initiate one silently | High | The UI offers the bonded set directly rather than pretending a scan can find peers. Discovery is a real user action, not a hidden one. |
| BLE default MTU 23 bytes | Medium | `Reassembler` handles fragmentation once, for every transport. |
| Single peer at a time | Medium | Acknowledged as scope, not hidden. `LIMITATIONS.md` Â§1.1. |
| No `INTERNET` means no telemetry, no crash reports, no remote config | Medium | Deliberate. A messaging app that cannot phone home cannot leak the conversation either. |
| Handshake is 4 frames | Low | On a link that is already open, in an app not competing on latency. The alternative is not possible, not merely worse. |
| Ciphertext-only history is unreadable if the app is reinstalled | Low | `isReadable` on each row drives an explicit UI message instead of silent data loss. |

### 8.3 What would make it production-ready

| Step | Why |
|---|---|
| Bundle real ONNX STT/TTS models and flip the engine flag | Unlocks the entire voice feature; no UI change needed |
| Wi-Fi Direct discovery + a UI entry point | The connect path is already written |
| Two-device end-to-end run | Converts every `NOT MEASURED` row into a real figure |
| Latency, real-time-factor, RAM and bandwidth instrumentation | `Telemetry` scaffolding exists; nothing reads it yet |
| Wi-Fi Direct discovery surfaced honestly | A chip that always fails is worse than no chip |

---

## 9. Impact and Benefits

### 9.1 Direct impact

| Stakeholder | Benefit |
|---|---|
| User in a network outage | Messages still send. The failure mode that motivated the app is removed rather than mitigated. |
| User with a low-end handset | The app's own code and resources are ~1.36 MB, and there is no network client, no ads and no crash reporter, so nothing is loaded over a slow link. **But** the artifact is 196,626,175 bytes because offline dictation ships a 153 MB model inside it, and installing that needs ~1.1 GB free â€” measured. A user on a full handset may simply not be able to install it. That is a real cost of the feature, not a rounding error. |
| Privacy-sensitive user | End-to-end encryption with the key never leaving either device's Keystore, and no server that can be compelled to disclose. |
| Battery-critical user | No background network polling, because there is no network client. |
| Organisation | No server to secure, patch, pay for or breach. The attack surface is two radios and one device. |

### 9.2 Technical contribution

1. **A verified demonstration that the no-`INTERNET` property is enforceable.** Not "we chose
   not to add networking" â€” the release APK has no `INTERNET`, the kernel refuses sockets, and
   the debug build deliberately *does* carry it so the gate proves it can detect the
   permission rather than merely never looking.
2. **A binary framing layer that is strict by construction.** Rejection instead of
   guesswork, validated before allocation, sized for a 23-byte MTU.
3. **A separation of at-rest and on-the-wire keys** with the reasoning written down, so the
   defect that produced "Could not read that message" cannot be reintroduced by someone who
   reads only the happy path.
4. **An offline speech abstraction** with ten languages mapped, ready for an ONNX runtime.
5. **Two executable documentation gates.** `check_offline.sh` proves the permission is absent
   *and* that the checker would notice if it appeared. `check_docs.py` verifies that quoted
   constants match the source and that no figure is invented. Both have been run against
   deliberately wrong inputs.

### 9.3 Societal impact

- **Digital inclusion.** The app needs no account, no phone number balance and no data plan.
  Two handsets already in a pocket are the whole system.
- **Resilience.** Communication that survives the failure of the infrastructure it usually
  depends on â€” the actual purpose of a mesh-capable field-communication design.
- **Language access.** Ten languages in the model, chosen because the deployment context is
  Indian-language speech, where typed input is itself a barrier for many users.
- **Reduced dependency surface.** Fewer servers means fewer breaches, fewer subscriptions and
  less surveillance infrastructure.

### 9.4 Honest limits of this impact

No two-device run has happened. Every number that would quantify the benefit above â€”
latency, battery, delivery success rate â€” is `NOT MEASURED`. The claims in this section are
about what the architecture makes possible, not about measured outcomes.

---

## 10. Testing and Quality Assurance

| Suite | Tests | What it pins down |
|---|---|---|
| `EnvelopeCodecTest` | 28 | Header layout, length validation before allocation, rejection of malformed frames |
| `PeerHelloTest` | 34 | Handshake frame 1 parsing |
| `SessionCryptoTest` | 27 | AES-GCM round trip, tamper detection, wire format |
| `ReassemblerTest` | 22 | BLE fragmentation and interleaving |
| `PeerSecretTest` | 20 | Wrapped contribution parsing |
| `HkdfTest` | 21 | RFC 5869 test cases 1 and 3 |
| `DeliveryStatusTest` | 13 | The `PENDING â†’ SENT â†’ DELIVERED â†’ FAILED` state machine |
| `SessionKeysCombineTest` | 12 | Both sides derive the same key from the same material |
| `ConversationIdTest` | 11 | Stable conversation identity |
| `DeviceIdentityTest` | 5 | Id format, stability, no hardware identifier involved |
| `QueueAdvanceTest` | 5 | Queue transitions and retry exhaustion |
| **Total** | **198** | Run in both debug and release: **396 executions, 0 failures** |

**Four instrumented tests, which cannot run on the JVM at all:**

| Test | What it pins down |
|---|---|
| `bundledModelExtractsAndVerifies` | The 153 MB model extracts from assets and every SHA-256 matches |
| `decodesRealSpeechToRealWords` | Whisper transcribes a real recording to real words, matched against expected tokens |
| `everySupportedLanguageBuildsASession` | All ten language codes build a recogniser that reports ready |
| `silenceDecodesToEmptyRatherThanThrowing` | Silence yields an empty transcript instead of a crash |

These exist as instrumented tests rather than JVM tests **on purpose**. sherpa-onnx is JNI, so a
JVM test can only mock it â€” and a mock of the recogniser is precisely what hid a hardcoded
`"Hello, voice test"` transcript behind a fully green unit-test suite. All four pass on a realme
RMX2020 (Android 11); none of them can pass in CI, because they need a device.

**Two executable gates, both of which have been verified against negative controls:**

| Gate | What it proves |
|---|---|
| `scripts/check_offline.sh` | The release APK has no `INTERNET` **and** the debug APK does |
| `scripts/check_docs.py` | Every constant quoted in `docs/` equals the source, every count matches reality, no figure is invented |

Method in `TESTING.md`.

---

## 11. Research and References

### 11.1 Standards and specifications

| Reference | Used for |
|---|---|
| **RFC 5869** â€” HMAC-Based Extract-and-Expand Key Derivation Function (HKDF) | `Hkdf.kt`; verified against test cases 1 and 3 |
| **NIST SP 800-38D** â€” GCM (Galois/Counter Mode) | Choice of AES-GCM over CBC: authenticated, so a tampered frame cannot decrypt into plausible plaintext |
| **NIST SP 800-57 Part 1** â€” Key Management | Key separation, generation, and why at-rest and on-the-wire keys are different jobs |
| **Android Keystore** â€” `KeyGenParameterSpec`, `KeyProperties` | Non-exportable, hardware-backed keys; the key never exists in app memory as a byte array |
| **RFCOMM / SPP** â€” Bluetooth Serial Port Profile | The classic-Bluetooth transport and its bonding prerequisite |
| **Bluetooth Core Specification** â€” ATT/GATT, 23-byte default MTU | Why `Reassembler` exists and why the header is 47 bytes |
| **Wi-Fi P2P** â€” `WifiP2pManager` | The Wi-Fi Direct transport |
| **Android BLE scanning policy** â€” location gating below API 31 | Why location is declared, and why it is capped at API 30 |
| **Sherpa-ONNX** â€” streaming ASR, VAD and TTS runtime | The target runtime for the offline speech models |
| **Silero VAD** â€” voice activity detection | The VAD slot in `NeuralEngine` |
| **DPDP Act 2023** (India) | Purpose limitation and data minimisation, against a random local device id |

### 11.2 Sources for the defect classes this project ran into

These are written up in full in the source comments, because each was found on hardware and
not in a test suite:

| Defect | Why a test could not find it |
|---|---|
| Per-device key used for peer messages | `encrypt` and `decrypt` were each correct in isolation; nothing made two devices agree |
| Device id written to `Settings.Secure` | Requires a signature-level permission no app holds; every write threw, the `runCatching` swallowed it, and a fresh id was minted on every launch |
| `targetSdk` left unset | Compiles silently to `minSdk`; only a dump of the built APK showed it |
| A dead microphone and a dead mode toggle in the shipped chat screen | No test asserts that a rendered control is connected; the fix is a visibility gate read from the engine flag |

### 11.3 Related work considered

| Approach | Why not used |
|---|---|
| Google Nearby Connections | A large dependency with an API-30 story to re-check each release, for a problem this app solves with raw platform APIs. `LIMITATIONS.md` Â§1.3 |
| SMS / MMS fallback | Needs a SIM, a number and a carrier â€” all of which fail in the outage scenario that motivates the app |
| WebSocket / MQTT over internet | Directly contradicts the no-`INTERNET` property that is the project's central claim |
| JSON over the wire | Far larger for the same four fields, several BLE round trips, an object graph allocated per message, and lenient parsing that silently drops fields from a newer peer |

---

## 12. What Is Not Measured

Nothing below has been observed on two handsets. It is listed rather than omitted because a
reader deciding whether this project is right for them needs to know where the evidence stops.

| Metric | Status |
|---|---|
| Two-device end-to-end message delivery | **NOT MEASURED** |
| Send-to-visible latency | **NOT MEASURED** |
| Decrypt-and-display latency | **NOT MEASURED** |
| Cold start, warm start | **NOT MEASURED** |
| Scroll frame time at 100 and 1000 messages | **NOT MEASURED** |
| Memory footprint | **NOT MEASURED** |
| Idle battery over 24 h backgrounded | **NOT MEASURED** |
| Radio duty cycle while idle | **NOT MEASURED** |
| Real-time factor of offline STT / TTS | **NOT MEASURED** |
| Model load and unload time | **NOT MEASURED** |
| Range and throughput per transport | **NOT MEASURED** |

Three rows elsewhere in this document are measured, and each is labelled at the point of use: the
196,626,175-byte release APK and the 396 passing unit-test executions are properties of a build
artifact, and the Whisper decode â€” 11.00 s of speech in 5,151 ms, measured on a realme RMX2020 â€”
is the one runtime measurement in the project.

**To close this section:** run two paired handsets, send messages over RFCOMM, over BLE and
over Wi-Fi Direct, and instrument latency, RAM and battery. Separately, record a word error rate
for each of the ten dictation languages against a known script. Until then every figure stays as
written.

---

## 13. Slide Index

| # | Slide | Section |
|---|---|---|
| 1 | Title â€” iTantra Message, offline P2P messaging | â€” |
| 2 | The problem â€” when the network is the failure | Â§1.2 |
| 3 | The response â€” no network, no account, no server | Â§1.3 |
| 4 | Scope â€” built, not built, out of scope | Â§1.4 |
| 5 | Technology stack â€” Kotlin, Compose, AndroidX only | Â§2.1â€“2.2 |
| 6 | Permissions â€” and the one deliberately absent | Â§2.4 |
| 7 | DFD Level 0 â€” context | Â§3 |
| 8 | DFD Level 1 â€” message pipeline | Â§4 |
| 9 | CFD â€” sending a message | Â§5.1 |
| 10 | CFD â€” the four-frame handshake | Â§5.2 |
| 11 | CFD â€” receiving a frame | Â§5.3 |
| 12 | Feasibility â€” technical, economic, operational, legal | Â§6.1â€“6.4 |
| 13 | Technical approach â€” layering and the `ByteLink` seam | Â§7.1 |
| 14 | Technical approach â€” why binary, not JSON | Â§7.2 |
| 15 | Technical approach â€” two keys, two jobs | Â§7.3 |
| 16 | Reliability â€” queue, backoff, dedup, hostile input | Â§7.4 |
| 17 | Offline speech â€” interfaces ready, models pending | Â§7.5, Â§6.5 |
| 18 | Viability â€” exposure and what production-ready needs | Â§8 |
| 19 | Impact and benefits | Â§9 |
| 20 | Testing â€” 396 unit-test executions, 4 instrumented tests, two executable gates | Â§10 |
| 21 | References | Â§11 |
| 22 | What is not measured â€” and how to close it | Â§12 |
