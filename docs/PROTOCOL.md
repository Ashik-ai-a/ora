# Airbox wire protocol, version 1

This is the contract both apps implement. The Kotlin reference implementation is in
`core/`. Write a Swift port against this document plus the JSON examples, then test it
against the Kotlin CLI (`airbox receive` / `airbox send`).

## 1. Transport security

- TLS 1.3 only, **mutual** authentication.
- Each device has one long-term ECDSA P-256 key with a self-signed X.509 certificate.
  The subject is `CN=airbox` and carries no names or IDs.
- **Fingerprint** = lowercase hex SHA-256 of the certificate DER (64 characters).
- TLS accepts any certificate. Trust is decided by the Airbox session (§3) before any
  file metadata is exchanged. A sender that holds a pinned fingerprint (QR link or trust
  store) must abort if the peer's fingerprint differs.

## 2. Framing

Big-endian: `type:u8 | length:u32 | payload[length]`

| type | name | payload | limit |
|---|---|---|---|
| 1 | CONTROL | UTF-8 JSON message | 1 MiB |
| 2 | DATA | raw bytes of the current file | negotiated `chunkBytes` (≤ 4 MiB) |

Receivers must check `length` against the limit **before** allocating memory.

## 3. Session

```
Sender (initiator)                         Receiver
  hello  ───────────────────────────────▶
         ◀─────────────────────────────── hello
         ◀─────────────────────────────── auth_status {trusted}
  unless (receiver trusts sender AND sender pinned the receiver's fingerprint), one of:
    pair_token {token} ─────────────────▶            (token from QR / deep link)
         ◀─────────────────────────────── pair_result {confirmed}
  or
    sas_commit {commitment} ────────────▶
         ◀─────────────────────────────── sas_nonce {nonce}
    sas_reveal {nonce} ─────────────────▶            (receiver checks the commitment)
         [both show the 6-digit code; users compare]
         ◀─────────────────────────────── pair_result {receiver confirmed}
    pair_result {sender confirmed} ─────▶
  offer {transferId, files[]} ──────────▶            (validated, storage checked, user asked)
         ◀─────────────────────────────── accept {transferId, resumeOffsets, chunkBytes}
                                          | reject {transferId, code}
  for each file not yet done:
    file_start {index, offset} ─────────▶
    DATA × n ───────────────────────────▶
    file_end {index, sha256} ───────────▶            (sha256 of the WHOLE file)
         ◀─────────────────────────────── file_result {index, ok, code?}
  complete ─────────────────────────────▶
```

Pairing is mutual. A receiver that already trusts the sender must still accept a
`pair_token` or `sas_commit` instead of an `offer`, because a sender that connected by
address hasn't verified the receiver and must not trust an `auth_status{trusted:true}`
that a man in the middle could forge. An untrusted sender that sends `offer` straight
away gets `cancel{PEER_NOT_TRUSTED}`.

Either side may send `cancel {code}` at any time. The sender may send `pause` at a chunk
boundary. The receiver keeps its partial data, and a later session with the same
`transferId` resumes.

### Messages (JSON, discriminator `type`)

```json
{"type":"hello","protocolVersion":1,"sessionId":"5b0c…","deviceName":"Ana's iPhone","platform":"IOS","maxChunkBytes":262144}
{"type":"auth_status","trusted":false}
{"type":"pair_token","token":"q4Jx…"}
{"type":"sas_commit","commitment":"<base64url SHA-256>"}
{"type":"sas_nonce","nonce":"<base64url 32 bytes>"}
{"type":"sas_reveal","nonce":"<base64url 32 bytes>"}
{"type":"pair_result","confirmed":true}
{"type":"offer","transferId":"Zk3…","files":[{"index":0,"name":"IMG_0001.HEIC","size":2483011,"mimeType":"image/heic","lastModifiedEpochMs":1729830000000}]}
{"type":"accept","transferId":"Zk3…","resumeOffsets":{"0":1048576},"chunkBytes":262144}
{"type":"reject","transferId":"Zk3…","code":"REJECTED_BY_RECEIVER"}
{"type":"file_start","index":0,"offset":1048576}
{"type":"file_end","index":0,"sha256":"<hex>"}
{"type":"file_result","index":0,"ok":true,"code":null}
{"type":"pause"}
{"type":"complete"}
{"type":"cancel","code":"CANCELLED_LOCALLY"}
```

Unknown JSON fields **must** be ignored. Unknown `type` values are a protocol violation in
v1. A breaking change bumps `protocolVersion`.

Error codes are the names in `ErrorCode` (`core/.../transfer/AirboxException.kt`).

### SAS computation

```
commitment = base64url( SHA256("airbox-sas-commit-v1" || nI) )
h          = SHA256("airbox-sas-code-v1" || fpI || fpR || nI || nR)     # fp as ASCII hex
code       = (big-endian u32 of h[0..4]) mod 1_000_000, zero-padded to 6 digits
```

I = initiator (sender), R = responder (receiver). Nonces are 32 random bytes.

### Pairing link (QR code / deep link)

`airbox://pair?v=1&h=<host>&p=<port>&fp=<fingerprint>&t=<token>&n=<device name>`

The token is 128 random bits, single-use, expires after 2–10 minutes, and all tokens are
revoked after 5 bad attempts.

## 4. Receiver rules

- Validate the offer: contiguous indexes from 0, non-negative sizes, at most 10,000 files,
  a total-size cap, and a valid `transferId` (`[A-Za-z0-9_-]{8,64}`).
- Check free space (minus a safety reserve) **before** asking the user.
- Never use the sender's file name as a path. Sanitize it (strip directories, traversal,
  control and bidi-override characters, reserved names; cap at 200 bytes UTF-8). Never
  overwrite: `name (1).ext`.
- Write to a partial file that is invisible to the user. Hash while writing (re-hash the
  existing prefix when resuming). Publish atomically only after the SHA-256 matches.
- On a hash mismatch, delete the partial and answer `file_result{ok:false}`. The sender
  retries that file once from offset 0.
- Cancel or reject: delete partials. Lost connection or pause: keep partials. Purge stale
  ones after 48 hours.

## 5. Discovery records (LAN)

DNS-SD service `_airbox._tcp`. TXT record: `v=1`, `id=<random, rotated each time
discoverable mode is switched on>`, `n=<device name>`, `p=android|ios|desktop`. Don't
advertise the certificate fingerprint, because it is a stable identifier that would let
anyone track the device. Discoverable mode is temporary (default 10 minutes) and visible
in the UI.
