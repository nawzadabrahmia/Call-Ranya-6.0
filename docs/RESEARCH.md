# Call Ranya – research design notes (for the article)

Working title: **Call Ranya: An Infrastructure-Independent, End-to-End Encrypted Store-Carry-Forward Communication System
for Camps, Villages and Disasters in the Kurdistan Region of Iraq**

Author: Nawzad Rasul Mohammed, University of Raparin.

---

## 1. Problem and motivation

When an earthquake, flood or landslide hits, or in a refugee/IDP camp or a mountain village without coverage, there is often
**no mobile signal, no internet and no server**. Phones still have Wi-Fi radios, many people carry phones, and someone can
usually turn on a hotspot or a battery-powered router. These form **small, disconnected "islands"** of connectivity, and people
move between them (to a water point, the clinic, the camp office, the next village).

Call Ranya uses these islands in two ways:

1. **Inside an island:** direct, end-to-end encrypted calls (WebRTC/Opus), video, chat, files, voice rooms, check-ins and roll calls.
2. **Between islands:** an **offline relay network** (delay-tolerant networking). Phones keep encrypted "bundles" for others and
   hand them over whenever they meet another phone. People become the carriers ("data mules").

## 2. Contributions (candidate novelty claims – check each against the literature review)

| # | Contribution | Where in the code |
|---|--------------|-------------------|
| C1 | DTN over **existing Wi-Fi/hotspot islands** (no Wi-Fi Direct/Bluetooth mesh, no root, standard Android) | `Relay.kt`, `Discovery.kt` |
| C2 | **Hash-locked delivery receipts**: `ah = SHA-256(ackSecret)` is public, `ackSecret` is only inside the encrypted payload. The recipient's tombstone `{id, ackSecret}` both purges copies network-wide ("vaccine") and proves end-to-end delivery to the sender – carriers can neither forge nor read it | `Relay.applyTomb`, `deliverToMe` |
| C3 | **Clock-free lifetime**: expiry and latency use accumulated bundle *age* (cf. RFC 9171 Bundle Age), because phones without internet often have wrong clocks | `Bundle.ageNow`, `maintain` |
| C4 | **Recipient privacy for carriers**: unicast bundles carry `tag = SHA-256("CR-tag"‖recipientKey‖id)` instead of a phone number/code | `Relay.tag` |
| C5 | **Safety semantics in the DTN layer**: priority classes (SOS > safety check-in/roll call > announcements > messages), signed public SOS with optional location, and a trust model for announcements (QR-verified or team contacts) | `Relay`, `Safety`, `RelayActivity` |
| C6 | **Hardened pairwise channel**: SIGMA-style transcript signatures (x=3) negotiated backward-compatibly, with **protocol-version pinning** per contact against downgrade | `Crypto.kt` `SecureChannel` |
| C7 | **Local-context design**: English primary with Kurdish (Sorani) and Arabic RTL; one-QR organisation setup; works on cheap Android phones | `L.kt`, `Qr.kt` |
| C8 | **Built-in, privacy-preserving measurement** (opt-in CSV log) so field trials need no extra tools | `Relay.logRaw`, `exportCsv` |

Related work to cite and compare: epidemic routing (Vahdat & Becker 2000), Spray-and-Wait (Spyropoulos et al. 2005),
PRoPHET (Lindgren et al. 2003), DTN architecture RFC 4838 and Bundle Protocol v7 RFC 9171, throwboxes (Zhao et al. 2006),
Serval Project/Rhizome, Briar (Bramble sync), Bridgefy, FireChat, Jami, Linphone, Zello; SIGMA (Krawczyk 2003),
Noise protocol framework. Be precise about which ideas are combined vs. new: C2 and C3 should be checked carefully against
DTN security literature (e.g. BPSec RFC 9172) before calling them novel.

## 3. Protocol summary

**Bundle** (signed fields): `id` (128-bit random) · `k` class · `o` origin identity key · `t` origin clock (informational) ·
`life` lifetime · `mh` max hops · `to` recipient tag or empty (broadcast) · `ah` ack hash · `p` payload · `s` ECDSA P-256 signature.
Mutable, unsigned: `h` hops, `a` age.

| Class | Priority | Lifetime | Max hops | Payload |
|-------|----------|----------|----------|---------|
| `sos` | 0 | 24 h | 12 | signed plaintext: name, code, note, optional GPS |
| `safe` | 1 | 72 h | 10 | encrypted check-in / roll call |
| `ann` | 2 | 1 h – 7 days (sender chooses) | 16 | signed plaintext announcement |
| `msg` | 3 | 7 days | 8 | encrypted text or file ≤ 150 KB |

**Unicast encryption:** `eph` ephemeral P-256; `K = HKDF(ECDH(eph, recipientKey), salt=id, info="CR-relay-v1"‖ephPub‖recipientKey)`;
`p = ephPub ‖ AES-256-GCM(K, inner)`. The inner JSON holds sender code/name, message id, content and `ackSecret`.

**Encounter:** every 8 s a phone broadcasts `{t:"rly", r:pseudonym, d:digest}` (pseudonym rotates hourly). If the digests differ,
the phone with the smaller pseudonym connects (TCP 45455, anonymous handshake). Exchange is strictly alternating:
`SV{ids, tombstones}` → `SV{ids, tombstones, want}` → bundles → `WANT{ids}` → bundles → `END`.
Budget per encounter: 500 bundles / 8 MB, most urgent first. Cool-down 45 s per address.

**Storage:** 3 000 bundles / 25 MB (relay station: 20 000 / 200 MB). Eviction: others' bundles first, lowest priority,
most hops, oldest. Per-origin quota 300. Tombstones kept 9 days (max 20 000; 3 000 advertised per encounter).

## 4. Threat model (state it explicitly in the paper)

- **Carriers are curious but not trusted**: cannot read unicast content (AES-GCM), cannot learn the recipient unless they know the
  recipient's key, cannot forge receipts, cannot modify bundles (signature), cannot impersonate a sender (signature + TOFU/QR keys).
- **What carriers do learn**: the origin's public key, class, size, timing, hop count. (Discuss: sealed-sender would hide the origin
  but removes per-origin quotas → spam. Trade-off.)
- **Flooding/spam**: limited by per-origin quota, hop limits, lifetimes and priority eviction; Sybil keys remain a limitation.
- **Forward secrecy**: sender-side FS for relayed messages (ephemeral key); no recipient-side FS (a stolen recipient key decrypts old
  bundles still in the network). Direct calls/messages have full FS (ephemeral-ephemeral ECDH).
- **SOS/announcements are public by design**; a fake announcement shows "⚠ unverified sender" unless signed by a trusted key.
- **Device compromise / stolen phone** is out of scope (identity key is Keystore-wrapped, backups off).

## 5. Evaluation plan

### E1 – Micro-benchmarks (lab, 2–4 phones)
Handshake time (x=2 vs x=3), sync time vs. number of bundles (10…3 000), throughput, CPU, **battery drain over 24 h with relay on/off
and relay station on** (Battery Historian / `adb shell dumpsys batterystats`), call quality (latency, MOS proxy) on Wi-Fi and hotspot.

### E2 – Campus field trial (the core result)
Three or more "islands" (hotspots or routers in different buildings, not connected to each other or the internet).
20–40 volunteers with the app, research statistics ON, move as in a normal day for 1–2 weeks.
Scripted traffic: each participant sends N messages/day to people in other islands; coordinators post announcements; scheduled roll calls.

Metrics (all derivable from the CSV logs, no clock sync needed):
- **Delivery ratio** = delivered / created (match `create` on sender with `deliver` on recipient by bundle prefix)
- **Delivery latency** = `age_ms` of the `deliver` event; distribution (median, P90)
- **Receipt return time** = `age_ms` of the `acked` event on the sender
- **Hop count** distribution; **overhead** = number of `fwd` events per delivered bundle
- **Storage occupancy** over time; evictions/expiries; encounter frequency and duration
- SOS/announcement **coverage**: fraction of participants that received each broadcast and how fast

### E3 – Emulation at scale (village/camp scenarios)
Run the real relay code in many JVM processes (see `research/relay-emulation/`) with synthetic mobility (islands, schedules)
for 50–200 nodes. Vary: % of people with the app, number of relay stations, lifetimes, hop limits. Compare with "direct only"
(no relay) as the baseline; optionally compare with Spray-and-Wait by limiting copies.

### E4 – Usability and acceptance
System Usability Scale (SUS) in English, Kurdish and Arabic; short interview on trust in announcements and willingness to carry
messages for others; task-completion time for "send SOS", "answer a roll call", "post an announcement".

### Ethics
Informed consent; research statistics are opt-in; logs contain only random IDs, rotating pseudonyms, sizes, times and hop counts
(no names, codes, phone numbers, locations or content); participants export and hand over their own CSV.

## 6. CSV log schema (`Community board → Research statistics → Export CSV`)

`study_id, time_ms, event, bundle, class, hops, age_ms, bytes, peer, extra`

| event | meaning | notes |
|-------|---------|-------|
| `create` | this phone created a bundle | |
| `store` | accepted a bundle from another phone as carrier | `peer` = pseudonym |
| `fwd` | sent a bundle to another phone | `hops` = hops before this transfer |
| `deliver` | a bundle addressed to this phone arrived | `age_ms` = end-to-end latency |
| `acked` | this phone's bundle was confirmed delivered | `age_ms` = time since creation (local clock) |
| `expire` / `evict` | dropped (lifetime / storage cap) | |
| `encounter` | one sync session | `hops` = bundles sent, `age_ms` = duration, `bytes` = bytes sent, `extra` = bundles received |

`bundle` is the first 10 hex characters of the random bundle id, the same on every phone, so logs from all participants can be
joined. `time_ms` is the local clock (for ordering only).

## 7. Suggested article structure

1. Introduction – disasters, camps, villages in KRI; connectivity islands; research questions
2. Related work – DTN, mesh messengers, disaster apps, secure messaging
3. System design – architecture, direct communication, relay network, safety features, localisation
4. Security design – threat model, cryptography, handshake hardening, receipts, privacy
5. Implementation – Android (Kotlin/Compose, WebRTC), Windows (Electron), no server
6. Evaluation – E1–E4
7. Discussion – limitations (client isolation on some networks, Android background limits, metadata, Sybil, adoption), ethics
8. Conclusion and future work – Wi-Fi Aware/Bluetooth LE transport, iOS, routing with contact history (PRoPHET), Bloom-filter summaries

## 8. Known limitations (be honest in the paper)

- Needs Wi-Fi with device-to-device traffic allowed (some campus networks use client isolation) or a phone hotspot.
- Pure epidemic routing → overhead grows with density; hop limits/quotas mitigate but do not optimise it.
- The Windows app does not take part in the relay yet (it talks directly and ignores relay traffic).
- Android vendors (Xiaomi, Oppo, …) may stop background services; the app guides users to fix this but it is not guaranteed.
- No sealed sender; carriers see the origin's public key and the class.
