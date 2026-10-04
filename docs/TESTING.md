# TESTING.md

What is tested, what is not, and what each test is actually protecting.

**95 unit tests, 0 failures.** No instrumentation tests. No completed two-device run. That summary is
the most important thing in this file; everything below is detail.

---

## 1. Running

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest
```

HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`
XML: `app/build/test-results/testDebugUnitTest/`

| suite | tests | what it covers |
|---|---|---|
| `EnvelopeCodecTest` | 28 | wire format, hostile input, offset pinning |
| `ReassemblerTest` | 22 | stream framing, resynchronisation, chunk splitting |
| `DeliveryStatusTest` | 13 | the state machine, as two properties |
| `ConversationIdTest` | 11 | both ends deriving the same conversation key |

### 1.1 And two gates that are not tests

```bash
bash scripts/check_offline.sh   # the release APK has no INTERNET; the debug one does
python scripts/check_docs.py    # every number in docs/ matches the code
```

Neither is a unit test, and both have caught things tests could not:

- `check_docs.py` found `colors.xml` claiming four contrast ratios that were all wrong, and
  found `ERROR_HANDLING.md` asserting a delivery confirmation the protocol does not have. No
  compiler reads `docs/`, and no unit test reads a comment.
- `check_offline.sh` is the only thing standing between a merged dependency declaring
  `INTERNET` and the project's central claim quietly becoming false.

Both were verified against deliberately broken inputs. See `BUILD.md` §2.2.

---

## 2. Why these four

These are the components where a bug is **silent**. Each produces wrong behaviour with no
crash, no stack trace, and often no visible error:

- a framing bug drops messages or merges two;
- a codec bug corrupts fields without failing;
- a state-machine bug resends a delivered message or strands one forever;
- a conversation-key bug files messages under a conversation nobody is looking at.

Everything else in the app is either visible immediately (a wrong string, a wrong colour) or
needs a device to test at all.

The framing and codec suites are pure JVM by construction: `EnvelopeCodec` and `Reassembler`
take and return `ByteArray`. They were written to take no Android dependency so they could be
tested without a device, and that constraint is why they are testable at all.

---

## 3. What the tests found

Not decoration — four real defects, all found by these suites, all fixed.

### 3.1 `messageId` truncated to 96 bits

The wire field was 12 bytes for a 16-byte UUID, so the receiver did not get back the id the
sender assigned:

```
sent:     f47ac10b-58cc-4372-a567-0e02b2c3d479
received: f47ac10b-58cc-4372-0000-0000b2c3d479
```

An ack or resend keyed on the stored id disagrees with the sender's record. Worse, two
messages agreeing in the surviving 96 bits produce the **same** id, the unique index rejects
the second as a duplicate, and it is silently never stored — one bubble where two were sent.

Message loss, inside the component whose job is to prevent message loss. Fixed by using all 16
bytes; `encode` now **throws** on a non-UUID id rather than packing it.

### 3.2 Device ids truncated to 6 bytes

`DeviceIdentity` produces `IT-%06X` — `IT-` plus six hex digits, **9 characters**. The field
was 6 and the encoder did `id.take(6)`, so `IT-1A2B3C` and `IT-1A2B3D` went on the wire as
byte-identical `IT-1A2`. Two different phones were the same device as far as the protocol was
concerned.

Fixed to 9 bytes with the same rule: over-long ids throw.

### 3.3 `droppedBytes` counted delivered frames

`dropFromFront` was used both to consume a good frame and to discard garbage, and incremented
the diagnostic counter on both paths. So the figure tracked the total byte count of all
traffic — roughly the payload length of every message ever sent.

Read as "bytes dropped" that looks like catastrophic stream corruption on a perfectly healthy
link, which is worse than having no counter at all. Split into `consume()` (silent) and
`discard()` (counted).

### 3.4 `ConversationId.parse` accepted nonsense

```kotlin
val peer = parts.firstOrNull { it != selfId } ?: return null
```

- `parse("IT-AAAAAA|IT-BBBBBB", selfId=<unrelated>)` returned `IT-AAAAAA` as the peer — a
  confidently wrong answer where the caller needed to know it had the wrong `selfId`.
- `parse("IT-1A2B3C|", …)` returned an **empty** peer. That matches no rows, so the chat
  screen would open empty and look like the messages had been deleted.

Now requires exactly one part to equal `selfId` and rejects empty parts.

### 3.5 And two test bugs worth recording

Both would have passed while asserting nothing.

- **"Every chunk split" ran 64 times instead of 2^38.** `1 shl 38` overflows an `Int` to
  `1 shl 6`. The test passed. A `chunkings > 100` guard added afterwards is what caught it.
  The exhaustive test is now sized to 12 cut points → 4096 genuinely distinct chunkings.
- **"The header is mostly binary" failed at 25 of 47 printable.** The assertion was wrong, not
  the code: `IT-1A2B3C` is ASCII and a UUID's bytes are printable about half the time.
  Replaced with `every field sits at its documented offset`, which tests what actually matters.

---

## 4. What the suites protect

### 4.1 `EnvelopeCodecTest` — 28

Mostly **negative**. Each takes a valid frame, corrupts one field, asserts rejection. The
receive path is fed bytes from a radio by anyone in range, and the only safe response to
malformed input is to drop it.

Rejected: short frame, empty frame, wrong magic, unknown version, unknown type, payload length
beyond the frame, payload length beyond the ceiling, truncated payload. Plus the boundary —
a payload just under the limit still encodes.

The offset test pins every field to the byte in the documented table. A field written one byte
early would read a plausible timestamp out of the middle of the id and the only symptom would
be messages that silently fail to decrypt. Nothing else can see this: both ends read the same
constants.

### 4.2 `ReassemblerTest` — 22

The property under test:

> For any splitting of a byte stream into chunks, feeding those chunks in order must yield
> exactly the frames that were written.

Both transports violate chunk-boundary assumptions in production: RFCOMM hands over whatever
the socket buffered; BLE delivers one MTU per notification, so a 200-byte message is nine
callbacks. A test feeding whole frames at once would pass against an implementation broken for
every real radio.

Coverage: single frame, several frames in one chunk, one byte at a time, partial header,
partial length, empty payload, leading garbage, garbage between frames, all-zero garbage,
`ITM1` inside a payload, over-ceiling length, largest permitted frame, buffer growth past the
initial 2048 bytes, MTU-sized feeding of a 9000-byte frame, `reset`, empty feed.

**Exhaustive** over all 4096 cut patterns of a 13-byte stream, plus every 2- and 3-way split
of a longer representative stream containing an empty frame, a frame larger than any BLE MTU,
and a payload containing the magic.

### 4.3 `DeliveryStatusTest` — 13

Asserted as two properties rather than as a list of edges, because a list checked against
itself passes while an edge is missing:

1. **Safety** — a terminal state is final. Nothing moves out of `DELIVERED` or `FAILED`. If
   `DELIVERED` could fall back to `SENDING`, a late retry would resend a message the peer had
   acknowledged and the user would see it twice.
2. **Liveness** — every non-terminal state can reach a terminal state, checked by brute-force
   reachability. If it cannot, a message can be stranded: stored, never sent, never failed.

The liveness helper had a bug of its own — it did not seed the start state, so a terminal start
reported an empty reachable set and the test claimed a *delivered* message was stranded. Fixed
with a comment explaining the trap.

### 4.4 `ConversationIdTest` — 11

Both devices derive the same key; each knows which id is the peer; parse recovers the peer
from either end; rejected: wrong part count, self-chat key, key naming neither device, empty
part.

Plus a test that the separator is safe — asserted rather than assumed, because
`parse("a|b|c")` returns null if a device id ever contained `|`, and that would silently stop
every conversation resolving. If `DeviceIdentity`'s format changes, this fails with an
explanation instead of in the field.

### 4.5 `PeerHelloTest` — 34

Added after the two-phone run, because `PeerHello.kt:45` documented a property as "asserted in
`PeerHelloTest`" and **no such file existed**. A source comment claiming a test that is not
there is worse than no comment: it reads as coverage and provides none.

The four collision tests are the reason the file exists. `HELLO:` and the envelope magic must
never be confusable, and nothing at runtime enforces it — there is no dispatch table that would
catch an edit to `EnvelopeCodec.MAGIC`. Covered in both directions: a hello never decodes as an
envelope, an envelope is never reported as a hello, and the envelope's first two bytes are
pinned to `MAGIC`/`VERSION`.

**The guard was proved able to fail.** `MAGIC` was temporarily changed from `0x49` to `0x48`
so that it collided with the `HELLO` prefix; `hello prefix cannot begin an envelope` failed,
2 tests failed in the suite, the build exited non-zero. The constant was then restored. A guard
test that has never been shown to fail is an assumption with a test-shaped wrapper.

Also covered: round-trip over every hex digit the generator can mint (exhaustive, not sampled),
and rejection of lowercase hex, wrong lengths, non-hex characters, a trailing newline, a trailing
NUL, non-UTF-8 id bytes, an over-long id, and — the important one — **an id containing `|`**,
which would otherwise let an untrusted peer name a conversation that resolves to a third device.

---

### 4.6 `WhisperDecodeInstrumentedTest` — 4, on a device

The only tests in the project that run outside the JVM, and they exist because of a specific
failure worth recording.

`FakeOfflineSpeechRecognizer` returned the literal string `"Hello, voice test"`. It was wired in
through a `USE_REAL_NEURAL = false` flag, and **every unit test stayed green** while a microphone
button in the UI produced that hardcoded sentence. The tests were not weak; they were pointed at
the wrong thing. A mock of the recogniser cannot tell you whether the recogniser works, and
mocking it was exactly what made the fake invisible.

sherpa-onnx is JNI. It needs an ARM CPU, the real 153 MB model on a real filesystem, and
Android's asset pipeline. None of that exists on the JVM, so these are `androidx.test`
instrumented tests against the real engine:

| test | what it asserts |
|---|---|
| `bundledModelExtractsAndVerifies` | the model extracts from assets to `filesDir` and every SHA-256 matches |
| `decodesRealSpeechToRealWords` | real audio in, real words out, and the expected tokens are present |
| `everySupportedLanguageBuildsASession` | all ten language codes build a recogniser that reports ready |
| `silenceDecodesToEmptyRatherThanThrowing` | silence yields an empty transcript instead of aborting the process |

The decode test feeds an 11.00 s, 16 kHz mono PCM16 clip of a human voice from whisper.cpp's
sample set, through the same `pushFrame`/`endUtterance` path `SherpaSpeechRecognizer` uses. On a
realme RMX2020 (Android 11, API 30) all four pass, and the transcript is:

```
And so my fellow Americans asked not what your country can do for you,
ask what you can do for your country.
```

**These tests are not in `gates.yml` and cannot be.** They need a device, and CI has none. That
is a real limitation of the project's verification story and it is why `LIMITATIONS.md` §4 lists
it.

The first version of these tests also had a bug worth recording, because it is the same shape of
error as the fake recogniser: the WAV fixture lives in the *androidTest* APK, but the test asked
the app under test for it, so it threw `FileNotFoundException` and the failure read like a
missing fixture rather than a wrong context. `InstrumentationRegistry.getInstrumentation().context`
is the right one.

---

## 5. Not tested

| area | why | consequence |
|---|---|---|
| `EncryptionManager` | needs the Android Keystore, absent on the JVM | **largest untested area.** A bug here is the most damaging possible. |
| Room / DAO | needs a device or Robolectric | unique-index dedup and `compareAndSet` transitions are unverified |
| Both transports | need two radios | see §7 |
| `NearbyManager` scanning | needs a radio | permission paths are compile-checked only |
| ViewModels | no coroutine test fixtures wired | UI state transitions unverified |
| Compose UI | needs a device or Compose test rule | layout and accessibility untested |
| **Live microphone capture** | needs `RECORD_AUDIO` granted | `AudioRecord` capture and the `ChatViewModel` → `SherpaSpeechRecognizer` hand-off are unverified. `RECORD_AUDIO` could not be granted programmatically on the test handset — ColorOS rejects both `pm grant` and `appops set` — and the permission dialog is only reachable from a blocker that needs a second peer. See §4.6 for what *is* covered. |
| **STT accuracy** | needs a labelled corpus per language | word error rate for all ten languages is `NOT MEASURED`. The tests prove the recogniser produces words, not that it produces *correct* words. |

`androidTest` has **4 instrumented tests** (§4.6) and Robolectric is not in the local
dependency cache; adding it would break the offline-reproducible build (`BUILD.md` §3.5).

### 5.1 The gap that matters most

`EncryptionManager` is untested. Everything in `SECURITY.md` about it is read from the source.

A unit test with an injected `SecretKey` would cover the envelope format, generation selection,
`rotate()` and `pruneRetired()` - everything except the Keystore round-trip itself. That is
most of the file, and it is straightforward. It is not written, and this is the honest state
rather than a claim that the code is simple enough not to need it.

### 5.2 A crash that only an *old* phone could find

The most serious defect in this document, and the clearest argument for testing across a
range of Android versions rather than on the newest device available.

`DeviceIdentity.randomId()` read:

```kotlin
value = random.nextInt(1, 0x1000000)
```

`java.util.Random.nextInt(origin, bound)` is a **Java 17** method, present from **Android API
34**. The project sets `minSdk = 24` and does not enable core library desugaring, so the call
compiled without complaint and then killed the process at runtime on every device below
Android 14:

```
java.lang.NoSuchMethodError: No virtual method nextInt(II)I in class Ljava/security/SecureRandom
```

`FATAL EXCEPTION: main`, unrecoverable, reproducible on every tap that touched a peer.

**Why every automated gate passed.** The build succeeded, every unit test passed, both script
gates exited 0, and `aapt` confirmed the APK declared no `INTERNET`. All of that is true and
all of it is irrelevant: nothing in this repository executes the code path. Lint does not check
for API-level method availability on a JDK method reached through `java.util.Random`, and no
test constructs a `DeviceIdentity`, which needs a `Context`.

**Why the two-phone run found it.** The test phones were an **API 35** Galaxy A14 and an
**API 30** realme Narzo 10A. The A14 worked perfectly and would have done so forever. The
API 30 phone crashed on the first tap. One modern device validates nothing about `minSdk 24`;
the claim is a statement about devices that were never in the room.

Fixed to `1 + random.nextInt(0x1000000)` -- the single-argument `nextInt(bound)` is API 1. The
offset moved into the addition rather than into a method call that did not exist yet.

The general lesson: **`minSdk` is a promise about devices you are not holding.** Verifying it
means testing on the oldest supported version, or enabling core library desugaring and being
explicit that the range is a compatibility shim rather than real platform support.

### 5.3 A defect that only two phones could find

Also found by running the release build on two handsets, and this one is the more serious of
the two, because no amount of single-device testing would have surfaced it.

**`MessageRepository` and `HomeViewModel` disagreed about what a conversation is called.**

`HomeViewModel.connect` built the conversation key as `ConversationId.of(identity.id,
device.address)` -- a device id and a Bluetooth **MAC address**. `MessageRepository` built the
same key from two **device ids**, using `envelope.senderId` on the receive path and the id the
sender passed to `send()` on the send path.

Those two keys never match. The observable result:

- the chat screen opened on a conversation with no rows in it, forever;
- every message the user sent was written under the repository's key, which nothing read;
- the send reported success at every layer.

No error, no crash, no log line. Messages were being sent, stored, and delivered to a
conversation key that no screen would ever query.

**Why neither kind of test would have caught it.** A unit test would have passed, because
`ConversationId.of` is correct in isolation -- it faithfully sorts and joins whatever two ids it
is given. The defect was in *the argument*, one layer up, and only visible as an invariant
across two call sites in two layers. Neither the unit tests nor a single-device run can express
"these two places must agree", because the disagreement only becomes observable when a value
written by one is read by the other.

**Fixed** by exchanging device ids instead of inferring them, in `PeerHello`:

- `PeerHello` -- a handshake frame, deliberately *not* an `Envelope` (an envelope has no
  meaningful `receiverId` before the peer is known, and its payload is ciphertext under a key
  that does not exist yet). Distinguished by the `HELLO:` prefix, which cannot collide with an
  envelope because envelope magic is `0x49 0x01` and the prefix begins `0x48`.
- `PeerIdentity` -- persists which device id belongs to which MAC address.
- `MessageRepository.peerDeviceId` -- publishes the announced id; resets on every `attach`.
- `HomeViewModel` -- waits for that id before navigating, bounded by `IDENTIFY_TIMEOUT_MS`, and
  falls back to the persisted registry when it already knows the peer.

The deeper reason a handshake is unavoidable: the device id *cannot* be derived from anything
both ends can see. Hashing the MAC would avoid the exchange, and is impossible anyway --
`BluetoothAdapter.getAddress()` is a hidden API from Android 12, so a modern handset cannot
read its own address. The id is random and private by construction, so nobody else knows it
until it is sent.

#### 5.1.1 And this gap has already cost a real defect

This section is not hypothetical. The first defect found by running the release build on two
physical phones lived in exactly the code this gap describes, and could not have been caught by
any JVM test.

Startup warm-up in `AppContainer.warmUp()` called `EncryptionManager.keyFingerprint()` and then
logged `generated encryption key, generation N`. `keyFingerprint()` is a **reader**: it returns
null when there is no key and never creates one. On a handset with an empty Keystore the app
therefore generated nothing, and logged

```
generated encryption key, generation null
```

- a false statement, with the null generation honestly reporting that no key existed.

Messaging still worked, because `encrypt()` mints generation 1 inline. What did not work was
the entire purpose of warm-up: the Keystore cost, and the risk of generation failing on a
locked device, stayed on the user's first **Send** instead of moving to idle startup. The log
line asserted the opposite of what had happened.

Fixed by adding `EncryptionManager.ensureKey()`, which generates if absent and returns the
generation it can read back, and calling that instead. Verified on both handsets
(Samsung Galaxy A14 5G, API 35; realme Narzo 10A, API 30), both now logging `generation 1`.

Device serials and Bluetooth MAC addresses are deliberately absent from this document. They
were here, and they were removed: a serial is a hardware identifier that identifies the physical
device, and `docs/SECURITY.md` §4 argues at length that this app never touches one. Naming
them in the test log would have made that argument look careless rather than principled. Model
names and API levels are enough to reproduce every observation below.

Two lessons recorded rather than quietly fixed:

1. **A log line is a claim.** This project refuses to publish a number it has not measured;
  it should equally refuse to log an event it has not observed. That log line was the only
  evidence the bug was detectable at all.
2. **Reader and writer looked interchangeable.** `hasKey()` (reads) was called to decide
  whether to *generate*, and the generate step was a different method that only read. The
  compiler cannot catch "you checked instead of acted", which is why the compiler passing
  proved nothing here.

---

## 6. Manual test plan

Requires **two phones**, paired **with each other** in Android Settings → Bluetooth. An app
cannot initiate pairing; Android requires user interaction at the system level.

### 6.1 Basic

1. Install on both. Launch. Both should show each other in the device list.
2. Tap the peer. Chat opens with the peer's display name.
3. Send text. It should appear as queued, then sent.
4. Send from the other phone. It should appear.

### 6.2 Offline

5. Put **both** in aeroplane mode, Bluetooth manually on.
6. Send. It should queue, not error, and stay queued.
7. Repeat, filling a conversation with several queued messages.
8. Leave aeroplane mode. Queued messages should deliver.

This is the load-bearing offline test: Bluetooth is local radio and needs no aeroplane-mode
exemption, but the point stands that **no** network path is involved at any step.

### 6.3 Failure

9. Kill the app on the receiving phone. Send. Queue should hold.
10. Move the phones out of range mid-send. Should go to `FAILED` with a readable reason, after
    the retry budget — not hang at "sending".
11. Deny the Bluetooth permission. A clear message should appear on arrival, not on first tap.
12. Turn Bluetooth off with the app open. Same.
13. Clear app data on one phone, send from the other. The history should report itself
    unreadable rather than appearing empty.

### 6.4 Performance

**NOT MEASURED.** To be recorded on a low-end device:

- cold start, warm start
- scroll frame times with 100 / 1000 messages
- send-to-visible latency for a short and a long message
- idle battery over 24 h with the app backgrounded
- memory footprint

Record the device model and Android version with each figure. **No result has been recorded
for any of these**, and no estimate appears in any document in this project.

---

## 7. Two-device testing: what has run, and what has not

Every device-dependent item in §5 needs two phones. That run has **started** and has already paid
for itself twice — see §5.2 (a fatal crash on Android 11) and §5.3 (a conversation-key mismatch).
Neither was reachable any other way. But it has not finished.

### 7.1 Observed on hardware

Release build, two handsets, both bonded: a Samsung Galaxy A14 5G (SM-A146B, **API 35**) and a
realme Narzo 10A (RMX2020, **API 30**). Serials and MACs are omitted deliberately — see §5.2.

| observation | evidence |
|---|---|
| both phones open, no crash on launch | `FATAL EXCEPTION` absent from logcat on both |
| both install and declare no `INTERNET` | `dumpsys package` on both, on-device |
| both accept incoming RFCOMM | `RfcommServer: listening on 00001101-...` on both |
| the identification handshake completes | `PeerIdentity: peer 00:11:22:33:44:55 announced IT-D1EAA1` |
| device ids are minted per device | `ItantraMessageApp: generated encryption key, generation 1` on both |
| the bundled speech model extracts and verifies | `ModelStore: extracted …/files/whisper-base, 153 MB verified` on a realme RMX2020, API 30 |
| Whisper transcribes real speech to real words | measured on this handset: `DECODED in 5151ms: [And so my fellow Americans asked not what your country can do for you, …]` — 9/9 expected words |
| all ten dictation languages build a recogniser | `SherpaNeural: recognition language now hi` through `… or`, then `WhisperDecodeTest: language or ok` |
| silence does not crash the process | `WhisperDecodeTest: silence produced []` |

That last row of the first table is the important one: the A14's device id was received,
validated and persisted by the realme. The handshake is confirmed working **across two real
phones**, which is more than has been true of anything else in this project.

The speech rows are single-device, not two-device: Whisper needs no peer. They are recorded
here because §7.1 is the only place in this document where hardware observation is listed, and
putting them anywhere else would split the record of what has actually been seen.

### 7.2 Not yet observed

- **a message crossing the link in either direction.** No envelope has been confirmed delivered.
- **payload decryption on a real peer.** Envelope encode/decode and the framing are unit-tested;
  the AES-GCM path is `NOT MEASURED` (§5.1).
- **persistence across an app restart** (Room read-back on a real device).
- **queue retry** when the peer is absent and returns.
- **the live microphone path.** `RECORD_AUDIO` could not be granted programmatically on the
  realme — ColorOS API 30 rejects both `pm grant` and `appops set` — and the permission dialog
  is only reachable from the nearby-permission blocker, which needs a second peer present. The
  iQOO does allow `pm grant` and this is straightforwardly testable there; it has not been done.
- **any latency or throughput figure for messaging.** These stay `NOT MEASURED` unless timed
  with a stopwatch. Nothing in this document should be read as a performance claim. The one
  measured runtime figure in the project is the Whisper decode in §4.6.

### 7.3 Why a PC or emulator cannot substitute

- **A PC Bluetooth adapter cannot be a BLE peripheral peer.** The Realtek adapter is internal
  and central-only. A PC can *observe* BLE but cannot *be* one.
- **An Android emulator cannot be a BLE peer.** No Bluetooth radio.

So the test is two handsets, not a phone and a laptop.

### 7.4 What the two-phone run taught about testing generally

Both defects in §5.2 and §5.3 were found by a **release build on two devices of different Android
versions**, and neither was found by:

- the compiler,
- 95 unit tests,
- `check_offline.sh`,
- `check_docs.py`,
- `aapt` confirming the APK declares no `INTERNET`.

Every one of those was green and every one of them was irrelevant to the failure. A green gate
suite is evidence about the gates' subjects, not about the app.

The one methodological lesson worth generalising: **`minSdk` is a promise about devices you are
not holding.** Testing on the newest phone available validates nothing about the oldest version
you claim to support, and on this project the oldest phone was the only one that crashed.