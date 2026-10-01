# NETWORK_PROTOCOL.md

How two phones running iTantra Message exchange one message, byte for byte.

This document describes what the code does. Where a number appears it was either read out of
the source or measured on a device; where something has not been measured it says
`NOT MEASURED` rather than carrying an estimate.

---

## 1. Two layers, not one

A message crosses two framing layers, and conflating them is the usual source of bugs, so
they are kept apart deliberately.

```
  plaintext text
        |
        v
  Envelope          47-byte header + payload        <- message semantics
        |            (who, when, which message, type)
        v
  Reassembler       "ITM1" + u16 length + payload    <- stream framing
        |
        v
  ByteLink          opaque bytes                     <- the radio
```

`EnvelopeCodec` and `Reassembler` know nothing about Bluetooth. `RfcommTransport` and
`BleGattTransport` know nothing about messages. The repository is the only place both are
aware of each other.

**Why the split earns its keep.** The two transports deliver bytes with nothing in common:

| | RFCOMM | BLE GATT |
|---|---|---|
| Granularity | whatever the socket buffered | at most one MTU per notification |
| Message boundaries | unrelated to reads | never aligned to writes |
| Needs reassembly | no | yes |

A transport that framed its own messages would frame them differently per transport, so
encryption, deduplication and retry would have to be written twice and would behave
differently — which is exactly the class of bug where a message sends over one radio and
silently fails over the other. Keeping framing above the transport makes it identical by
construction.

---

## 2. The message envelope

Produced by `EnvelopeCodec.encode`, 47 bytes of header followed by the payload.

```
offset  size  field         notes
     0     1  magic         'I' (0x49)
     1     1  version       1
     2     1  type          MessageType ordinal
     3    16  messageId     UUID, 16 raw bytes, big-endian msb then lsb
    19     9  senderId      DeviceIdentity id, UTF-8, NUL padded
    28     9  receiverId    DeviceIdentity id, UTF-8, NUL padded
    37     8  timestamp     epoch millis, big-endian
    45     2  payloadLen    bytes of payload that follow, big-endian
    47     n  payload       UTF-8 text
```

Fixed widths are the point: a receiver walks to the payload without parsing anything, and
can reject a bad length before allocating for it.

### 2.1 Why the header is 47 bytes and not 37

Two identifier fields were too narrow in the first version of this format, and unit tests
found both. They are recorded here because the reasoning is the reusable part.

**`messageId` was 12 bytes.** A UUID is 16. Twelve bytes kept the high 8 and the low 4 and
dropped the middle, so the receiver did not get back the id the sender had assigned:

```
sent:     f47ac10b-58cc-4372-a567-0e02b2c3d479
received: f47ac10b-58cc-4372-0000-0000b2c3d479
```

Two things break at once. An acknowledgement or resend keyed on the stored id disagrees with
the sender's record. Worse, two genuinely different messages agreeing in the surviving 96
bits produce the *same* id, the unique index rejects the second as a duplicate, and it is
silently never stored — one bubble where two were sent, with no error anywhere. That is
message loss inside the component whose whole job is to prevent message loss.

**`senderId`/`receiverId` were 6 bytes.** `DeviceIdentity` produces `IT-%06X`, which is
`IT-` plus six hex digits — 9 characters. The field kept 6 and the encoder did
`id.take(6)`, so `IT-1A2B3C` and `IT-1A2B3D` went on the wire as byte-identical `IT-1A2`.
Two different phones were the same device as far as the protocol was concerned.

Both are now full width, and both encode paths **throw** rather than truncate. A shortened
identifier is worse than a rejected one: truncation produces a frame that is well-formed,
routed to the wrong peer or filed under the wrong message, and nothing anywhere reports it.

Pinned by `EnvelopeCodecTest`: `every field sits at its documented offset`, and the two
collision tests that reproduce each bug.

### 2.2 Rejection, not exception

`EnvelopeCodec.decode` returns `null` for anything it does not fully understand. It never
throws. These bytes come off a radio, so every field is attacker-influenced and the peer may
be running a different build.

Throws would force every caller to wrap the call, and the one that forgot would crash on
malformed input — a denial of service by anyone within radio range.

Rejected, in this order, each before any attacker-sized allocation:

| condition | check |
|---|---|
| fewer than 47 bytes | `bytes.size < HEADER_BYTES` |
| wrong magic | first byte `!= 0x49` |
| unknown version | `version != 1` |
| unknown type | `MessageType.fromOrdinal` returns null |
| payload beyond 64 KB | `payloadLength > MAX_PAYLOAD_BYTES` |
| payload longer than the frame | `bytes.size < HEADER_BYTES + payloadLength` |

A **trailing** byte is tolerated, not rejected. A BLE transport reassembles whatever the
radio delivered, so a padding byte is an ordinary outcome. Rejecting it would lose a message
for a reason that looks like corruption and is not.

---

## 3. Stream framing

Produced and consumed by `Reassembler`, above both transports.

```
0x49 0x54 0x4D 0x31   "ITM1"    magic
u16                   length    payload length
...                   payload
```

Header is 6 bytes. Ceiling `MAX_FRAME_BYTES` is 64 KB.

### 3.1 Why there is a magic at all

On BLE, notifications are not acknowledged at the link layer, so bytes are lost silently and
neither end is told. A length-only parser resynchronises onto a random offset and then reads
payload as header — producing plausible garbage indefinitely. Requiring a 4-byte magic before
trusting any length means a corrupted stream recovers at the next real message boundary.

Resynchronisation drops **one byte** at a time. That is not timidity: the magic can begin at
any offset, so a bulk skip would discard a real frame that started mid-chunk. Pinned by
`resynchronisation happens one byte at a time so an interior frame survives`.

The magic must match in full. "ITM1" can legitimately appear inside a payload, and a parser
that acted on any one of those bytes would cut the message in half.

### 3.2 The 64 KB ceiling

Far above any plausible message — 1 KB of text is about 1.4 KB on the wire. It exists so a
corrupt or hostile length cannot make the reassembler buffer a gigabyte.

A frame over the ceiling is **dropped and the stream resynchronises**, not held. Holding it
would be a memory leak that only appears under attack.

### 3.3 A mid-frame loss stalls the stream — known limitation

Once a length has been read the parser is committed. If a link drops half-way through a
message, the stale length absorbs the following messages as though they were its own body,
and nothing further is delivered until the link is re-established.

Recovery is at the boundary: `MessageRepository.attach()` installs a fresh `Reassembler` on
every successful connect, which is the right place because a link change is the one event
that guarantees the byte stream restarted.

The alternatives were rejected deliberately:

- **Per-frame checksum** — detects the truncation and lets the parser skip the bad body, at
  4 bytes per message plus a retry protocol.
- **Idle timeout** — recovers without either, but a slow radio against a tight timeout
  discards good messages, which is the one thing this app must not do.

For a two-party chat where a lost link is *visible* (the conversation shows disconnected) and
reconnected deliberately, buying faster recovery for a case the user can already see and fix
is not worth a retry protocol. Recorded in `LIMITATIONS.md` so the trade is visible rather
than discovered.

---

## 4. On-wire privacy

The payload field is UTF-8 text of the form `EncryptionManager` produces:
`itm1.<keyGeneration>.<iv>.<ciphertext>`, all Base64 except the format tag.

Plaintext is UTF-8 **before** AES-GCM, not after, so the ciphertext covers the bytes that
would actually be transmitted. The 12-byte IV is random per message and stored in the clear —
GCM requires it to be unique per key, and it carries no secret.

**NOT MEASURED** — no two-device message has been observed on a radio end to end. Everything
in this section is read from the source, not confirmed by a captured transmission.

---

## 5. What is deliberately absent

- **No mesh fields on the wire.** `TTL`, `hopCount`, `sourceId` and `destinationId` exist in
  the domain model so a later routing change is not a schema migration. They are not encoded,
  because encoding fields nothing reads is a claim the protocol does not yet make.
- **No acknowledgement.** `MessageType` has exactly one entry, `TEXT`. There is no ack frame,
  so an outbound message reaches `SENT` — the transport accepted the bytes — and stops there.
  The sender cannot know whether it arrived. `ERROR_HANDLING.md` §5.2 has the details.
- **No replay window.** Protection is by `messageId`: a message id already in the database is
  refused by a unique index rather than by a timestamp comparison, which cannot distinguish a
  duplicate from a genuinely old message without a shared clock. See `SECURITY.md`.
- **No transport-level authentication of the payload.** Bluetooth bonding authenticates the
  radio link. GCM authenticates the bytes. Neither proves the *user* at the other end is who
  the display name claims.