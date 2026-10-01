# PERFORMANCE.md

**No performance figure in this document is measured. Every one is `NOT MEASURED`.**

That is the whole document. The design decisions below are the ones made *for* performance,
with the reasoning recorded so a later measurement can confirm or contradict them. Inventing
numbers would make this file worse than an empty one.

---

## 1. Status

| metric | status |
|---|---|
| cold start | **NOT MEASURED** |
| warm start | **NOT MEASURED** |
| APK size (release) | **1.28 MB** — measured |
| APK size (debug) | **17.74 MB** — measured |
| send-to-visible latency | **NOT MEASURED** |
| decrypt-and-display latency | **NOT MEASURED** |
| scroll frame time, 100 messages | **NOT MEASURED** |
| scroll frame time, 1000 messages | **NOT MEASURED** |
| memory footprint | **NOT MEASURED** |
| idle battery, 24 h backgrounded | **NOT MEASURED** |
| radio duty cycle while idle | **NOT MEASURED** |

Two rows are measured because they are properties of the build output rather than of runtime,
and they were produced by `ls` on a built artifact.

**Why nothing runtime has been measured:** it requires two paired handsets and a low-end
device, and neither run has happened. See `TESTING.md` §7.

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

### 3.1 Release APK is 1.28 MB

Measured. R8 plus resource shrinking account for the ~14× difference from the 17.74 MB debug
build.

Contributors, in order of size:

- **No Play Services.** `play-services-nearby` alone would have been a large fraction of this.
  Rejected on size and API-30 risk — see `LIMITATIONS.md` §1.3.
- **No `appcompat`.** Pure Compose with one Activity. Adding it for a MaterialComponents theme
  would pull the entire Views Material library.
- **No image assets.** The launcher icon is a vector. No bitmaps, no photo attachments, no
  bundled fonts.
- **Compose BOM** rather than individual artifact pins, so the compiler, runtime and BOM stay
  consistent.

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