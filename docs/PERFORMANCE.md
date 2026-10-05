# PERFORMANCE.md

**Almost nothing in this document is measured. Everything that is not measured says so, and
says `NOT MEASURED` rather than carrying an estimate.**

The exceptions are the APK sizes and the offline speech-to-text decode, which *were* measured on
a real handset and are recorded below with the hardware and the command that produced them.
Inventing the rest would make this file worse than an empty one.

---

## 1. Status

| metric | status |
|---|---|
| cold start | **NOT MEASURED** |
| warm start | **NOT MEASURED** |
| APK size (release) | **196,626,175 bytes** — measured (`ls` on `app-release.apk`) |
| APK size (debug) | **214,261,177 bytes** — measured (`ls` on `app-debug.apk`) |
| APK size (androidTest) | **748,175 bytes** — measured |
| STT decode, 11.00 s of speech | **5,151–6,545 ms** over three runs — measured, realme RMX2020, real-time factor 0.47–0.60× |
| STT peak process RSS | **624–663 MB** over three runs — measured, but see the caveat below |
| send-to-visible latency | **NOT MEASURED** |
| decrypt-and-display latency | **NOT MEASURED** |
| scroll frame time, 100 messages | **NOT MEASURED** |
| scroll frame time, 1000 messages | **NOT MEASURED** |
| memory footprint of the UI | **NOT MEASURED** |
| idle battery, 24 h backgrounded | **NOT MEASURED** |
| radio duty cycle while idle | **NOT MEASURED** |
| STT word error rate, per language | **NOT MEASURED** |
| speech model extraction time, first run | **NOT MEASURED** |
| model load-to-first-result latency | **NOT MEASURED** |

**The RSS figure needs its caveat read with it.** 624–663 MB was read from the *instrumented test*
process, which contains ART, the JUnit runner and the test's own buffers alongside the
recogniser, so it **overstates** what the app itself costs. For scale: the same process sat at
179 MB while cycling all ten languages and fell to 105 MB after release, which is the part of
the figure that is attributable to the engine. The app's own steady-state footprint in the UI
is **NOT MEASURED**.

All size figures and the decode figures were refreshed on 2026-10-05 from
`app/build/outputs/apk/` and from logcat during `WhisperDecodeInstrumentedTest`. The sizes
grew by roughly 150× when the Whisper base int8 model was bundled into the APK — every size
number in this repository predating that change was wrong, and a stale number is the same
class of error as a fabricated one.

**Why the decode and RSS figures are ranges, not single numbers.** They were first recorded as
one sample each: 5,151 ms and 624 MB. Re-running the same test on the same handset with the
same audio gave 5,497 ms and 627 MB, then 6,545 ms and 663 MB. A 27% spread across three runs
of an identical workload means a single sample was never a measurement of the decode time — it
was a measurement of one run, and quoting it alone overstated the precision by an order of
magnitude more than the figure deserved. The ranges above are the honest form; `NOT MEASURED`
would have been the other honest answer, and range is only preferable because there are three
real samples behind it. Peak RSS varies with how much the allocator has kept from the previous
run, which is why it tracks the decode time rather than moving independently.

**Why the size gate now checks all three APKs.** The CI gate originally compared only the
release APK against the figure in this table. That is the artifact that ships, so it was a
reasonable first choice — and it still let the debug and androidTest figures drift stale
together, because nothing re-derived them. Both were caught on 2026-10-05 only by rebuilding
them by hand and comparing. The gate now checks all three, because a table that reports three
sizes should be able to prove all three.

**Why nothing else runtime has been measured:** it requires two paired handsets, and a message
has not yet been delivered end to end. See `TESTING.md` §7.

---

## 2. Why a low-end device is the constraint

The brief asks for small and low-end hardware. That reframes performance: the question is not
"is this fast" but "does this stay responsive on a phone with 1–2 GB of RAM and a weak
SoC", and the failure mode there is not a slow frame — it is an **OOM kill**, which loses the
process and any unsaved state.

Two consequences:

- **Background work must be interruptible.** An OOM kill ends the process mid-operation, so
  anything not already persisted is lost. That is why `retryCount` is a database column and
  not a field.
- **Nothing unbounded may be held in memory.** A conversation is read from SQLite per screen,
  not accumulated.

---

## 3. Decisions made for performance, and their rationale

### 3.1 The release APK is 196,626,175 bytes, and 82% of it is the speech model

Measured, and the composition is worth stating plainly because it reverses the previous
trade-off in this project: until Whisper was bundled the release artifact was 1,364,843 bytes
and size was a design constraint worth optimising. It is now dominated by a file that cannot be
optimised away without losing offline dictation.

The size is reproducible, which is worth saying because an APK size is exactly the kind of number
that drifts without anyone noticing: three independent `clean assembleRelease` runs produced
196,626,175 bytes every time. An earlier measurement in this project read 1,472 bytes lower, a
difference of 0.0007%, and its cause was never established. That is also why the CI gate accepts
a quoted figure within 5% rather than demanding an exact match — a gate that fails on a correct
build teaches people to ignore red, and every failure this project has actually had was far
outside that band.

| contributor | bytes | share |
|---|---:|---:|
| `assets/models/whisper-base/base-decoder.int8.onnx` | 130,672,026 | 66.5% |
| `assets/models/whisper-base/base-encoder.int8.onnx` | 29,120,534 | 14.8% |
| native libraries, both ABIs (`libsherpa-onnx-jni`, `libonnxruntime`, 2 AndroidX) | 34,655,644 | 17.6% |
| `assets/models/whisper-base/base-tokens.txt` | 816,730 | 0.4% |
| everything else — classes, resources, Room schema, Compose | ~1,361,241 | 0.7% |

So roughly **99.3%** of the artifact is the model plus the two native libraries that run it, and
the application's own code and resources are about 1.36 MB — the same size the whole APK used to
be. Nothing about the app grew. One dependency did.

What this means for a low-end handset is a genuine cost, stated rather than minimised:

- **Install needs real free space.** Measured on the test device, installing the 214 MB debug
  build required roughly **1.1 GB free** at the time of the `PackageInstaller` commit — about
  5× the APK size. It failed at 1.01 GB free and succeeded at 1.22 GB. On a phone at 96% full
  this is the difference between "works" and `INSTALL_FAILED_INSUFFICIENT_STORAGE`.
- **First use costs 153 MB of writes.** The model is extracted to `filesDir` on first dictation,
  not at install, so a user who never taps the microphone never pays it.
- **The model is stored uncompressed.** `androidResources.noCompress += "onnx"` lets it be
  memory-mapped rather than inflated into RAM, which is why the APK is ~187 MB larger than the
  compressed size and why the model is the top entry under "app data" rather than under
  "downloaded".

The remaining size decisions, all still true and now a rounding error against the model:

- **No Play Services.** `play-services-nearby` alone would have been a large fraction of the old
  1.36 MB. Rejected on size and API-30 risk — see `LIMITATIONS.md` §1.3.
- **No `appcompat`.** Pure Compose with one Activity. Adding it for a MaterialComponents theme
  would pull the entire Views Material library.
- **No image assets.** The launcher icon is a vector. No bitmaps, no photo attachments, no
  bundled fonts.
- **Compose BOM** rather than individual artifact pins, so the compiler, runtime and BOM stay
  consistent.
- **int8 quantisation, not float.** The int8 encoder/decoder pair is 160,609,290 bytes against
  roughly 291 MB for the float32 pair — a 1.8× saving, bought with some accuracy loss that this
  project has **NOT MEASURED**.

### 3.2 Small screen first, not scaled down

The brief asks for small-screen UI. That drove structure, not just sizing:

- **`LazyColumn` for the chat list.** Materialised is the alternative and it builds every
  bubble in the conversation at once. 1000 messages is ~1000 composables, each with a
  measured layout — that is the OOM scenario in §2.
- **No continuous scanning.** `BleScanner` uses `callbackFlow`, so the scan lives exactly as
  long as something collects it. When the Home screen stops collecting — screen off,
  navigation away, app backgrounded — `awaitClose` runs and the radio stops. There is no code
  path that leaves the scan running.
- **No heavy animation.** No shared-element transitions, no animated gradients, no
  infinite transitions. A continuous animation on a weak GPU keeps the compositor busy and
  costs battery for nothing.
- **Minimum SDK 24, no compatibility shims.** Capping at Android 7.0 removes the
  `legacy`/`v23`/`v27` resource directories, which is both less code and a smaller APK.

### 3.3 Database indexes exist for specific queries

`MessageEntity` declares three, each for a named query rather than by habit:

| index | serves |
|---|---|
| `(conversation_id, timestamp)` | the chat screen's newest-last read — without it, a full table scan plus a sort per query |
| `(message_id)` **unique** | deduplication on receive, enforced by the database rather than a racy check |
| `(status, timestamp)` | the retry sweep, oldest-first |

The unique index is also a correctness mechanism, not just a speed one. See
`ERROR_HANDLING.md` §2.1.

### 3.4 Encryption is not the bottleneck it appears to be

AES-GCM on a phone with a crypto extension does roughly 1 GB/s. A 4 KB message is
microseconds. **This is a general property of AES-GCM, not a measurement of this app** — no
latency number is claimed.

The cost that is *not* free is CPU while the app is on a tight budget, which is why the retry
backoff exists and why `retryCount` is persisted: a device that is genuinely unreachable must
not be retried in a tight loop.

### 3.5 `MAX_FRAME_BYTES` is 64 KB, not unbounded

A corrupt or hostile length must not make the reassembler buffer a gigabyte. Over-ceiling
frames are **dropped and the stream resynchronises**, not held — holding would be a memory
leak that only appears under attack.

---

## 4. How to measure, when hardware allows

Recording method with each figure. A number without a method is a rumour.

### 4.1 Startup

```powershell
adb shell am force-stop in.isro.sih26173.itantramessage
adb shell am start -W in.isro.sih26173.itantramessage/.MainActivity
```

`-W` reports `TotalTime`, `WaitTime`, `Complete`. Run at least 5 times, report median and
range. Cold means after `force-stop`, not after a swipe-away.

### 4.2 Frame times

```powershell
adb shell dumpsys gfxinfo in.isro.sih26173.itantramessage
```

`Janky frames` as a percentage over a scripted scroll of 100 and 1000 messages. Report the
device model — the number means nothing without it.

### 4.3 Memory

```powershell
adb shell dumpsys meminfo in.isro.sih26173.itantramessage
```

Report `TOTAL PSS`. The number that matters is on a 1–2 GB device under pressure, not on a
flagship.

### 4.4 Battery

```powershell
adb shell dumpsys batterystats --reset
# 24 h with the app backgrounded, then:
adb shell dumpsys batterystats in.isro.sih26173.itantramessage
```

Report mAh consumed over 24 h, screen off, Bluetooth on, app never foregrounded. **This is the
test that most directly validates §3.2** — if the radio is not stopping when nothing collects
the scan, it shows here.

### 4.5 End-to-end latency

Needs two paired phones with `adb logcat` on both. Timestamp at send on A, at display on B,
and report the difference. Record both device models and whether they were connected by
RFCOMM or GATT — the transports have very different characteristics and a single number would
hide that.

---

## 5. What would make this document real

1. Two handsets, paired with each other.
2. One low-end device (1–2 GB RAM, Android 7–9 would be ideal — it is the minSdk floor).
3. Run §4.1–4.5, recording device model and method with each figure.

Until then every runtime row stays `NOT MEASURED`, and `LIMITATIONS.md` §3 carries the same
list so the gap is visible from two directions.