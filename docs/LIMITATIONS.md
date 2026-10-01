# LIMITATIONS.md

What this app does not do, why, and what was measured versus assumed.

Written to be read by someone deciding whether the app is right for them. A list of wins is
marketing; this is the other half of `SECURITY.md`.

Anything not run on a device is written `NOT MEASURED`. No estimate appears as a measurement.

---

## 1. Not implemented, on purpose

### 1.1 No mesh routing

The domain model carries `ttl`, `hopCount`, `sourceId` and `destinationId` so that adding
routing later is a schema change rather than a migration.

**They are not encoded on the wire.** Encoding fields nothing reads is a claim the protocol does
not yet make, and every consumer of the format would have to carry dead bytes forever.

Consequence: this is strictly **one peer at a time**. A third phone cannot be reached, and a
group conversation does not exist. Two-device chat only.

### 1.2 No Wi-Fi Direct

`NEARBY_WIFI_DEVICES` is declared in the manifest; there is no implementation.
`NearbyManager.openLink` returns a `Result.failure` saying so rather than an entry that
silently does nothing.

A button that appears to work and does nothing is worse than an absent button, and the absence
of the transport is the honest state. The permission is declared so that adding the transport
later is a code change rather than a manifest change.

### 1.3 No Nearby Connections / Play Services

Deliberate. `play-services-nearby` would have provided discovery and connection management,
but:

- it is a large dependency for a feature this app implements with raw Bluetooth APIs,
- it has an API-30 story worth checking on every release, and
- the brief asks for a small app that works on low-end hardware.

The AARs were inspected and declare no `INTERNET`, so Nearby would have been compatible with
the offline guarantee. It was rejected on dependency size and risk, not on principle. The
trade is that discovery and connection logic is hand-written here, which is more code to
maintain.

### 1.4 No group chat, no accounts, no phone numbers

No account, no server, no phone number is collected or required. The device id is derived from
`Settings.Secure.ANDROID_ID` and the display name is typed by the user.

This is good for privacy and it is also a limitation: there is no way to find a specific person
you do not already have in your bonded list.

### 1.5 No forward secrecy

Messages are encrypted under a long-lived device key. Compromise of the Keystore at any point
exposes the history encrypted under the current generation, subject only to the 7-day grace
period (`SECURITY.md` §3.1).

A ratchet would fix this. It would also make the app unable to read its own history after a
process restart unless state is persisted carefully, which is a real cost for a chat app on a
phone. Named as the single largest cryptographic gap.

### 1.6 No attachments, and no delivery confirmation

Text only. `MessageType` has exactly one entry, `TEXT`.

**There is also no acknowledgement protocol**, which is the significant half:

| direction | final state | means |
|---|---|---|
| inbound | `DELIVERED` | arrived over the radio, decrypted, stored |
| outbound | `SENT` | **this phone handed the bytes to Bluetooth** |

An outbound message never reaches `DELIVERED`, so **the sender cannot know whether a message
arrived**. The peer may have left range in the instant after the write succeeded, or cleared
the app, or been switched off, and the bubble will still read "Sent" forever.

Implementing it needs a second `MessageType` and a reply path in
`MessageRepository.acceptFrame` — one extra frame per message, one extra database write on the
receiver, and a status that can itself lag if the ack is lost. The last point is why it is a
real design decision rather than a gap to close on sight: a "delivery unconfirmed" state the
UI cannot express well may be worse than an honest "sent".

See `ERROR_HANDLING.md` §5.2.

### 1.7 No app-level lock

Anyone holding an unlocked phone can read the chat history. The Keystore key is
non-exportable but is used on the user's behalf without a prompt.

Named in `SECURITY.md` §6 as not protected. Adding a lock screen is straightforward and is not
done here — it is a product decision about friction, not a technical blocker.

---

## 2. Known behavioural limitations

### 2.1 A mid-frame loss stalls the stream until reconnect

The single most significant one. Once the framer reads a length it is committed: if a link
drops half-way through a message, the stale length absorbs subsequent messages as though they
were its own body, and nothing further is delivered.

Recovery is at the boundary — `MessageRepository.attach()` installs a fresh `Reassembler` on
every successful connect. So the user recovers by reconnecting, and the conversation visibly
shows as disconnected while it is stalled.

Rejected alternatives and their costs: a per-frame checksum plus retry protocol (4 bytes per
message), or an idle timeout (discards good messages on a slow radio — the one failure mode
this app must not have).

See `NETWORK_PROTOCOL.md` §3.3. Pinned by `a partial frame then a full frame stalls until reset`,
which asserts the limitation rather than pretending otherwise.

### 2.2 Self-listing cannot be filtered on API 31+

`BluetoothAdapter.getAddress()` is a hidden API from API 31, and the supported substitute
(`Settings.Secure.ANDROID_ID`) is emphatically not a Bluetooth address.

So on API 31+ this phone may appear in its own device list, and tapping it opens a
conversation with itself. The filter applies where the platform will answer and not otherwise.

The alternative — displaying a different identifier under the label "Bluetooth address" — would
be a lie on screen. A cosmetic duplicate row is the better failure.

### 2.3 BLE advertising was not observed off-device

GATT is implemented and selectable, and is **not** the default. During Track A testing on
iTantra (the Flutter sibling project), a phone in `hosting` mode confirmed
`onAdvertisingSetStarted` at the stack level, but a PC with a Realtek adapter scanned for 40
seconds and saw **44 other BLE devices and 0 sightings of the phone**, with 0 UUID hits. The
advert payload was legal at 21 of 31 bytes.

Logcat pointed at vendor-stack starvation by Nearby Sharing, but the cause was not established.
So GATT discovery is **NOT MEASURED** as working from phone to phone.

RFCOMM is the default for a reason that has nothing to do with elegance: it requires no
advertising, only bonding, which Android guarantees.

### 2.4 Device id is 24 bits

`IT-%06X` is the brief's specified format and gives 24 bits. Ample for two people choosing each
other from a list; too little for a dense environment — 24 bits collides in the low tens of
thousands of installs in one area.

Mitigated by explicit selection from a bonded/scan list rather than auto-connect. Widening the
format is a wire-format change and was not made unilaterally.

### 2.5 Vendor stacks vary, and one is known bad

The Realtek adapter cannot be a BLE peripheral peer: it is internal and central-only. An
Android emulator cannot be a BLE peer either.

This is why the two-device test needs two handsets and not a phone and a PC. It is a testing
constraint, not an app limitation, but it has shaped every measurement in this project.

---

## 3. NOT MEASURED

Stated literally rather than estimated. Each is a real gap.

| item | status |
|---|---|
| End-to-end message delivery over RF | **NOT MEASURED** — no two iTantra Message devices have exchanged a message |
| Throughput | **NOT MEASURED** |
| Encryption round-trip latency | **NOT MEASURED** |
| Cold start time | **NOT MEASURED** |
| Memory footprint | **NOT MEASURED** |
| Battery drain over 24 h idle | **NOT MEASURED** |
| UI frame times / jank on a low-end device | **NOT MEASURED** |
| Behaviour on Android 7.0 (minSdk 24) | **NOT MEASURED** — compiles and is packaged, never run |
| Behaviour with a large history (1000+ messages) | **NOT MEASURED** |
| Behaviour when the Keystore key is invalidated | **NOT MEASURED** |
| Retry backoff under a real radio outage | **NOT MEASURED** |

`PERFORMANCE.md` carries the same list with the reason each is unmeasured. No figure appears
in any document in this project that was not produced by a command whose output is
reproducible.

---

## 4. Testing gaps

- **No instrumentation tests.** `androidTest` has a runner configured and no tests in it. Room,
  the Keystore and the Bluetooth stack all need a device, so the 74 JVM tests cover pure logic
  only: framing, codec, state machine, conversation keys.
- **No Robolectric.** Unavailable in the local dependency cache, and adding it would break the
  project's offline-reproducible build.
- **`EncryptionManager` is untested.** It needs the Android Keystore, which does not exist on
  the JVM. Its behaviour is unverified, which is the largest single untested area in the app
  and the one where a bug would be most damaging.
- **No two-device run.** Everything above.

## 5. Build constraints

Versions are pinned to what is present in the local Gradle cache, so the build reproduces with
no network. The trade is that upgrading any dependency requires a network fetch first. This is
recorded in `BUILD.md` with the full version list and the reasoning.