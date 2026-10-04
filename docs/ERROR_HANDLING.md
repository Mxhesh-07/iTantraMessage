# ERROR_HANDLING.md

What the app does when something goes wrong, and the rule that governs all of it.

---

## 0. What is NOT MEASURED

**No two-device run has happened**, so:

- **NOT MEASURED** — whether any of the §3 error strings actually appears on screen in a
  real failure. They are written by hand from the platform's documented behaviour, not
  transcribed from a screenshot.
- **NOT MEASURED** — the retry backoff curve under a real radio outage. `MAX_RETRIES` and the
  backoff constants exist in `MessageRepository`, but how long a message takes to reach
  `FAILED`, and what the user sees while it waits, has not been observed.
- **NOT MEASURED** — what Android actually reports for a refused RFCOMM connect on each
  vendor's stack. The text in §4 was observed on **iTantra (the Flutter sibling project)**,
  against three bonded handsets. iTantra Message reuses the same phrasing, but on its own
  transports it has not been confirmed.

The last one is the most likely to be wrong. Vendor stacks word these differently, and the
whole point of §4 is that the specific text matters — so carrying over a string that has not
been seen on this app's code path is exactly the kind of inherited assumption §4 warns about.

---

## 1. The rule

**No stack trace ever reaches the user.**

The user cannot act on `java.io.IOException: read failed, socket might closed or timeout,
read ret: -1 at android.bluetooth...`. They can act on "That phone is out of range. Try again
when it's closer." So every user-visible error is a written sentence, and the technical detail
goes to a debug-only log.

This is not a formatting preference. The alternative leaks file paths, class names and
sometimes key material into a screenshot that ends up in a bug report. The technical text is
still produced — it is in the log, tagged, where a developer can reach it.

---

## 2. Errors as values, not exceptions

Connection and send paths return `Result<T>`. They do not throw.

Connecting fails for ordinary, expected reasons: the peer left range, the radio was switched
off mid-connect, pairing was removed, the device went away between listing and tapping. Those
are **values**. An exception would have to be caught by every caller, and the one that forgot
would crash the app on a Bluetooth hiccup — which on a phone is indistinguishable, to the user,
from the app being broken.

`NearbyManager.openLink` is the single entry point, and it converts the two failure kinds the
caller must distinguish:

```kotlin
val a = adapter ?: return Result.failure(IllegalStateException("this device has no Bluetooth adapter"))
if (!a.isEnabled) return Result.failure(IllegalStateException("Bluetooth is switched off"))
```

A caller cannot pass a wrong adapter, because it cannot see one. An earlier version of this
code let the ViewModel hold the adapter and it passed a literal `null` straight into
`RfcommTransport.connect`, so **every** RFCOMM connection failed with "no Bluetooth adapter",
on every phone, with no visible cause. That is the class of mistake the manager exists to make
impossible.

### 2.1 Where exceptions are still correct

Two places, both deliberate:

- **`EnvelopeCodec.encode` throws on a malformed identifier.** This is a *programmer* error —
  a message id that is not a UUID means the caller's invariant is broken. Silently packing it
  is what caused the truncation bugs in `NETWORK_PROTOCOL.md` §2.1. Fail at the boundary,
  visibly, during development.
- **Message-id uniqueness is enforced by a Room unique index**, not by an application check.
  A check would be racy; a constraint cannot be.

---

## 3. The error surface

`NearbyError` carries a message written for a person plus a cause for the log.

| condition | what the user is told |
|---|---|
| Bluetooth off | "Turn on Bluetooth to find nearby phones." |
| No adapter | "This device has no Bluetooth adapter." |
| Permission missing (API 31+) | "iTantra Message needs permission to find nearby phones." |
| Permission missing (API ≤ 30) | "Turn on location to find nearby phones. Android requires it for Bluetooth scanning." |
| Connect refused | the transport's measured text, verbatim — see §4 |
| Peer out of range | "That phone is out of range. Try again when it's closer." |
| Undecryptable message | "This message can't be decrypted on this phone." |
| History unreadable (key gone) | stated plainly, not as an empty chat |
| Wi-Fi Direct tapped | "Wi-Fi Direct transport is declared in the manifest but not implemented." |

### 3.1 Blocking conditions are surfaced on arrival

`HomeUiState.blocker` is set from `NearbyManager.currentBlocker()` **in the ViewModel
constructor**, not on first action.

A user whose Bluetooth is switched off should be told that on opening the app, not after
pressing a button that cannot work. Waiting for an action means the first thing they see is a
failure, and a failure with no preceding context reads as "this app is broken".

---

## 4. Measured failure text is kept verbatim

When RFCOMM connect fails, the message includes the platform's own words, because they are
specific in a way that a paraphrase is not:

```
RFCOMM connect to 00:11:22:33:44:55 failed:
read failed, socket might closed or timeout, read ret: -1
```

The address in this transcript is redacted to a synthetic one. The real observation used a
handset's Bluetooth MAC, and a MAC is a hardware identifier that survives a factory reset —
publishing it in a repository whose entire argument is that this app never handles one would be
a poor showing. Nothing else in the string is altered, because the rest is the point.

This is the string observed on hardware against three bonded devices, and it is reproduced
rather than smoothed into "connection failed". "Connection failed" tells a user nothing and a
developer nothing; the specific text distinguishes a refusal from a timeout from a peer that
simply is not there, which are three different problems with three different fixes.

`RfcommTransport` calls `cancelDiscovery()` both before and after connecting. Leaving a scan
running during an RFCOMM connect reliably starves the connection attempt on Android, and the
symptom is a timeout that looks like a peer problem.

### 4.1 A timeout that could not time out

`RfcommTransport.connect()` was written as:

```kotlin
val opened = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
    runCatching { openSocket() }
}
```

This reads like a bounded connect and was not one. `BluetoothSocket.connect()` is a blocking
`java.net.Socket.connect()` and `runCatching` is not a suspension point, so the coroutine never
suspended inside the `withTimeoutOrNull` block. Cancellation in coroutines is cooperative and is
only observable at a suspension point, so the timeout could not fire until `connect()` returned
on its own.

Measured on a realme Narzo 10A, 2026-10-04: tapping a bonded peer that was not listening and
not running the app left the row spinning with `isConnecting` stuck true, for over 40 seconds
and counting, and the failure line in `HomeViewModel.connect()` was never reached. The only way
out was leaving the screen, because `HomeUiState.connectingTo` disables the rows.

The fix runs the blocking dial on its own thread and makes the socket cancellable:

- the candidate socket is published in a `@Volatile` field **before** `connect()` is called, so
  a timeout can see a socket that is still mid-dial;
- cancellation closes that field and then interrupts the thread;
- the worker is a daemon, so a blocked connect is never the reason the process stays alive;
- `close()` clears the same field, so leaving the screen mid-dial releases the thread too.

Closing the socket is what unblocks the pending `connect()`; interrupting alone does not,
because the native connect does not honour it. That is why cancellation closes rather than
merely interrupts, and why `runInterruptible` was not used.

Re-verified on the same handset after the change: the same tap now returns and surfaces the
platform's own text —

```
HomeViewModel: connect to 00:11:22:33:44:55 failed: read failed, socket might closed or
timeout, read ret: -1 (check: both phones are on, Bluetooth is on for both, and the two
devices are paired with each other in Settings > Bluetooth)
```

The address is redacted here too, for the reason given in §4.

— and the rows become tappable again.

The general lesson, and the reason it is written down: **a timeout wrapped around blocking code
is not a timeout.** It is an annotation. Any `withTimeout` in this codebase has to be checked
for a real suspension point inside it.

---

## 5. Retry, and when it gives up

A failed send does not become a silent hole.

```
OUTBOUND   PENDING ──▶ SENDING ──▶ SENT           (stops here, see §5.2)
              │           │
              │           └──▶ FAILED            retry budget exhausted
              └──▶ FAILED

INBOUND    (arrives) ──▶ DELIVERED                set directly on insert
```

- `retryCount` is **persisted**, not in memory. An in-memory counter resets when the process
  dies, which turns a genuinely unreachable device into an infinite retry loop — exactly the
  battery drain the brief asks to avoid.
- `FAILED` is reachable from every non-terminal state, so a transport that discovers it cannot
  deliver can record that without a special case at the call site.
- `FAILED` and `DELIVERED` are **final**. A terminal state never moves. If `DELIVERED` could
  fall back to `SENDING`, a late retry would resend a message the peer already acknowledged and
  the user would see it twice.
- The message stays in the database as `FAILED` with its `lastError`. It is never deleted, so a
  message that could not be delivered is still visible rather than gone.

Pinned by `DeliveryStatusTest`, including a brute-force reachability check that every
non-terminal state can still reach a terminal one — otherwise a message could be stranded,
stored, never sent and never failed, and "no message is lost" would be a lie.

### 5.1 The one transition that must not exist

`PENDING → DELIVERED` is forbidden. `SENT` means *the transport took the bytes*;
`DELIVERED` means *a message was received*. Allowing the first to imply the second would let
a message be marked delivered by a phone that merely agreed to try.

`PENDING → SENT` **is** allowed — a first attempt can succeed before anything observes the
message as in-flight, and forcing a step that did not happen would be asserting fiction.

### 5.2 `DELIVERED` means different things in each direction, and that is a gap

Stated plainly because the state name invites a wrong assumption.

**Inbound**, a message is inserted as `DELIVERED`. It arrived on a live radio link, decrypted,
and is in the database. That is a real arrival.

**Outbound**, a message reaches `SENT` and stays there. `SENT` means the transport accepted
the bytes. **There is no acknowledgement protocol** — `MessageType` has one entry, `TEXT`, so
there is no ack frame to send and no reply to wait for. Nothing ever moves an outbound message
to `DELIVERED`.

The honest consequence: **the sender cannot know whether a message arrived.** A bubble showing
"Sent" means "this phone handed it to Bluetooth", not "the other phone got it". The peer may
have gone out of range in the instant after the write succeeded, or cleared the app, or simply
been switched off.

This is the largest functional gap in the app and it is not hidden:

- Implementing it needs a second `MessageType` and a reply path in `MessageRepository.acceptFrame`.
- Cost: one extra frame per message, one extra database write on the receiving phone, and a
  status that can lag reality if the ack is lost — which is arguably worse than an honest
  "sent" if the UI cannot express "sent, delivery unconfirmed".

Recorded in `LIMITATIONS.md` §1.6.

---

## 6. Logging

| what | where |
|---|---|
| technical detail, failure causes | `Log`, **debug builds only** |
| user-facing text | `stringResource` |
| persisted `lastError` | Settings > Storage diagnostics |

`lastError` never contains a stack trace or a ciphertext fragment. Ciphertext is excluded
because the diagnostics screen is a place a user might screenshot, and an exception message
that happens to include a payload would put recoverable plaintext-adjacent material in an
image.

Plaintext, ciphertext, keys and IVs are never logged, in release or debug. There is no
debug-only exception to this rule — a log that is safe in development and unsafe in a build
someone keeps is a log that eventually is.

---

## 7. Failure modes that are silent, and why

Some failures produce no message at all, deliberately.

| silent case | why |
|---|---|
| A frame is dropped by the framer (over ceiling, or magic mismatch) | Counted in `Reassembler.droppedBytes` and logged. Showing the user "a message was corrupted" for radio-level noise would train them to ignore errors. |
| A duplicate `messageId` is refused | The correct outcome for a duplicate is one bubble, not an error. Logged at debug. |
| `localBluetoothAddress()` returns null | Falls back to showing this phone in its own list. See `LIMITATIONS.md` §2.2 — a cosmetic miss beats a wrong identifier on screen. |

Each has a diagnostic available and is documented, so "silent" means *not shown to the user*,
not *unobservable*.