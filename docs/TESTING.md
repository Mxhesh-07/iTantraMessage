# TESTING.md

What is tested, what is not, and what each test is actually protecting.

**74 unit tests, 0 failures.** No instrumentation tests. No two-device run. That summary is
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

`androidTest` has a runner configured and **no tests in it**. Robolectric is not in the local
dependency cache and adding it would break the offline-reproducible build (`BUILD.md` §3.5).

### 5.1 The gap that matters most

`EncryptionManager` is untested. Everything in `SECURITY.md` about it is read from the source.

A unit test with an injected `SecretKey` would cover the envelope format, generation selection,
`rotate()` and `pruneRetired()` - everything except the Keystore round-trip itself. That is
most of the file, and it is straightforward. It is not written, and this is the honest state
rather than a claim that the code is simple enough not to need it.

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
(`RZCW31EBSZD`, API 35; `4TKNBADMCIWSNBYD`, API 30), both now logging `generation 1`.

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

## 7. Two-device testing is blocked, and honestly

Every device-dependent item above needs two phones paired to each other. That has not happened
for this app.

Two constraints shaped everything measurable in the sibling Track A project, and both apply
here:

- **A PC Bluetooth adapter cannot be a BLE peripheral peer.** The Realtek adapter is internal
  and central-only. A PC can *observe* BLE but cannot *be* one.
- **An Android emulator cannot be a BLE peer.** No Bluetooth radio.

So the test is two handsets, not a phone and a laptop. Until that runs, `NOT MEASURED` is the
accurate entry for end-to-end delivery, and it says so in `LIMITATIONS.md` §3.