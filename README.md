# Call Ranya – calls, messages and SOS with no internet, no SIM and no server

Free, end-to-end encrypted **voice/video calls, chat, safety check-ins and SOS** between phones on any Wi-Fi network or
phone hotspot — for **villages without coverage, refugee and IDP camps, campuses and disasters** (earthquakes, floods,
landslides) where the mobile network and internet are down.
When two people are **not** on the same network, other phones carry their encrypted messages for them
(**offline relay network**, new in 6.0). Made at the University of Raparin, Ranya, Kurdistan Region.

English is the primary language; Kurdish (Sorani) and Arabic are available with full right-to-left layout.

## Updating from UoR Call 5.x – no uninstall needed
Call Ranya 6.0 keeps the same package (`edu.uor.lancall`) and the same signing key (`app/uorcall.keystore`), and
`versionCode` goes up to 6, so Android installs it **as an update over UoR Call**: contacts, chats, history and security
keys are kept. Just install the new APK on top. (Keep `app/uorcall.keystore` in the repo – a different key would force an uninstall.)
The Windows app also keeps its data folder (`%APPDATA%\UoR Call`).

## Build the APK on GitHub
1. Upload **everything in this folder** to your repo (including the hidden `.github` folder and `app/uorcall.keystore`).
2. **Actions → Build Call Ranya** runs on every push (or press *Run workflow*).
3. Download `Call-Ranya.apk` from **Releases** (direct link) or from the run's *Artifacts*.
   Windows: `Call-Ranya-Portable.exe` and `Call-Ranya-Setup.exe`.

## First run on each phone
1. Install, open, allow **Microphone** and **Notifications** (and Location if you want it in SOS/check-ins).
2. Settings → *Make sure calls always ring*: fix every red item (battery optimisation, full-screen ringing).
3. Xiaomi / Redmi / Huawei / Oppo / Vivo: also enable **Autostart** and set battery to **No restrictions**.

## What's new in 6.0 – Call Ranya: the offline relay network (research version)
**Store-carry-forward messaging (delay-tolerant networking).** Wi-Fi networks and hotspots are small "islands";
people walk between them. Every phone keeps a small encrypted store and, whenever it meets another Call Ranya phone on any
network, the two exchange what the other is missing. A message, check-in or roll-call answer therefore reaches someone who was
never on the same network as the sender (e.g. camp office → water point → village).
- Automatic: if a chat message or check-in cannot be delivered directly, it goes into the relay (📡 *via relay*), and turns into
  📡✓✓ when the hash-locked delivery receipt comes back. Photos/files up to 150 KB can travel too.
- **End-to-end encrypted** (ephemeral ECDH P-256 → HKDF → AES-256-GCM per message). Carriers cannot read it, and cannot tell who it
  is for unless they already know the recipient's key (recipient tags).
- **Unforgeable delivery receipts**: only the real recipient can reveal the ack secret; carriers verify it and delete their copies.
- **Community board** (Team tab → 📡): **SOS to everyone nearby** (signed, readable by any rescuer, with location if shared) and
  **announcements** (water, food, clinic, meeting times) that spread phone to phone; ✅ verified when the sender is a QR-verified or team contact.
- "Need help" in the Team tab now also sends this SOS – even if you have no team yet.
- Works with clocks that are wrong (common with no internet): lifetimes use the message's *age in the network*, not the clock.
- Priorities (SOS > safety > announcements > messages), hop limits, per-sender quotas and a storage cap protect the network.
  **Relay station** mode stores much more (a phone kept charging at a clinic, school, mosque or checkpoint).
- Relay sessions are anonymous (throw-away key, no name/code) and beacons use a pseudonym that changes every hour.
- **Research statistics** (opt-in, Community board → 🔬): anonymous log of creations, hops, delays, encounters and receipts,
  exported as CSV for the study. No names, codes, numbers, locations or content.

**Security hardening**
- Stronger handshake between 6.0 phones: both sides sign the whole transcript (both keys, both codes, role), which stops identity-misbinding;
  once a contact has used it, a downgrade is refused. Older phones and the Windows app still connect with the previous handshake.
- The identity private key is now wrapped by a key inside the **Android Keystore**; app backups are disabled.
- Safety numbers are longer (96 bits, e.g. `4F2A 91C0 7B3E D455 9A01 33CF`) on Android and Windows.
- Joining an organisation QR no longer changes your language (English stays the default).

Tested before delivery: core code compiled with Kotlin 2.0 against Android 14; handshake tests (6.0↔6.0, 5.x→6.0, downgrade refused)
and a 3-phone store-carry-forward scenario (A → mule B → C, receipts back to A, no duplicates) run on the real relay code.
Compose screens could not be built here – please build with GitHub Actions and test on real phones.

## What's new in 5.0 (UoR Call) – Windows app + remote support
**Windows desktop app** (`desktop/`, Electron). GitHub Actions now builds three files on every push:
`UoR-Call.apk`, **`UoR-Call-Portable.exe`** (no install – run from anywhere, even a USB stick) and `UoR-Call-Setup.exe`.
- Same 8-digit IDs, contacts, encrypted chat + files, voice/video calls, screen sharing, Team (check-in, SOS, roll call),
  internet mode and Kurdish/Arabic/English with light/dark themes. Works together with the Android app.
- Keeps running in the system tray (closing the window does not stop calls); optional start with Windows.
- Portable data lives in `%APPDATA%\UoR Call`.

**Remote support (consent-based)** – on Android and Windows, on local Wi-Fi and over the internet:
- The helper taps 🛟 on a contact. The other person sees who is asking and chooses **View only** or **View & control** (or declines).
- Android asks its own screen-capture permission; control needs the user to switch on
  *Settings → Accessibility → UoR Call remote support* once (Android shows its own warning).
- While a session runs: a red banner / always-on-top bar with **Stop**, visible for the whole session.
- Never unattended, nothing recorded, peer-to-peer and encrypted (DTLS-SRTP + DTLS data channel).
- Windows control uses a tiny PowerShell helper (`input.ps1`) that runs only while control is allowed.
  Note: Windows UAC prompts (secure desktop) cannot be controlled remotely – by design.
- Helping from a phone: tap / long-press / swipe on the remote screen, Back / Home / Recents, type text.

**Tested before delivery** (in the build environment):
- Encryption compatibility Windows ⇄ Android (handshake, encrypted signalling, file transfer, internet-message crypto).
- Real Windows-app build receiving an encrypted message + SOS from a simulated Android phone over the network.
- All screens rendered in English, Kurdish (RTL) and Arabic (RTL), light and dark – see `docs/screenshots/`.
- Not testable here: real phone cameras/screens, real Windows input injection – please test on real devices.

## What's new in 4.0
- **Internet chat**: text, photos, videos and files (≤25 MB) reach people who are not on the same Wi-Fi.
  End-to-end encrypted (ECDH between identity keys + AES-256-GCM); if the phone is off, messages wait up to 14 days.
  Run the updated `supabase/setup.sql` (the "Release 4" part) once.
- **Team tab** (🛡):
  - **I'm safe / I need help** check-ins to your team, with optional GPS location (works offline)
  - **SOS alarm**: "Need help" sounds an alarm on teammates' phones, even in Do Not Disturb
  - **Roll call**: a coordinator asks "Are you safe?"; members answer from the notification with one tap;
    live dashboard shows ✅ safe / 🆘 help / ⏳ no answer, with map links
- **Voice rooms** (walkie-talkie): hold-to-talk or hands-free, encrypted, invite with QR or from a chat. Local Wi-Fi only.
- Contacts ⋮ → **Add to team**. Organisation setup QR adds everyone to the team automatically.
- Calls tab now holds history + keypad.

## What's new in 3.0
- **Video calls on local Wi-Fi** (and over the internet): front/back camera, voice-only answer, switch voice ↔ video during a call
- **Screen sharing** during any call (Android asks the user's permission each time; a red banner shows while sharing)
- **Internet calling (free)**: if the person isn't on the same Wi-Fi, the call goes over the internet automatically.
  Same 8-digit code = internet ID. Signalling is signed with each phone's identity key; audio/video are peer-to-peer (DTLS-SRTP)
- New engine: **WebRTC** (the engine inside WhatsApp/Meet) – Opus audio, echo cancellation, adaptive video quality

### Enable internet mode (optional, ~10 minutes, free)
1. Create a free project at supabase.com → **SQL Editor** → paste and run `supabase/setup.sql`.
2. Project Settings → API: copy the **Project URL** and the **anon public** key.
3. In your GitHub repo: Settings → Secrets and variables → Actions → **New repository secret**:
   - `SUPABASE_URL` = `https://xxxx.supabase.co`
   - `SUPABASE_KEY` = the anon public key
4. Recommended – TURN relay (needed on many mobile networks / strict routers). Free tiers exist at metered.ca
   (Open Relay) or Cloudflare Realtime TURN. Add secrets:
   - `TURN_URLS` = e.g. `turn:global.relay.metered.ca:80,turn:global.relay.metered.ca:443?transport=tcp`
   - `TURN_USER`, `TURN_PASS`
5. Re-run the GitHub Action. Settings → *Internet calling* shows 🟢 when online.

Without these secrets the app works exactly as before on local Wi-Fi.

In Supabase → Realtime settings, keep "Allow public access" enabled (private-channels-only must be OFF).

## What's new in 2.0
- **Registration**: name + phone number required; random **8-digit code** (e.g. `4827 1936`)
- **Privacy**: hidden by default — others must dial your exact code or phone number. Opt in to "Show me as available".
  "Who can call me: everyone / contacts only", block list, phone number shared only if you allow it
- **End-to-end encryption**: ECDSA P-256 identity keys, ECDH per call/message (forward secrecy), AES-256-GCM for
  signalling, voice packets and files. Safety numbers + "key changed" warnings
- **QR pairing** (adds contact + verifies encryption face to face) and **one-QR organisation setup profile**
- **Chats**: text, photos, videos and any file up to 1 GB, delivery ticks, automatic retry when the person comes online
- **Kurdish (Sorani), Arabic and English** with full right-to-left layout; **light / dark / system** theme
- **Lock screen**: Android call-style notification, wakes the screen, answer without unlocking

## Features (1.0)
- Unique code per phone (editable), automatic discovery of phones on the same Wi-Fi
- Ringing with system ringtone + vibration, full-screen answer screen over the lock screen
- Always-on foreground service, CPU/Wi-Fi wake locks, auto-start after reboot and after updates
- Contacts (online now + saved), keypad dialling by code or IP, call history with missed calls
- Mute, loudspeaker, call timer, proximity sensor (screen off at the ear), echo cancellation & noise suppression
- Busy detection, Do-Not-Disturb, missed-call notification, duplicate-code warning
- Works on a phone hotspot too (no router needed)

## How it works
| Port | Protocol | Purpose |
|------|----------|---------|
| 45454 | UDP broadcast | "I am here" announcement every 2 s |
| 45455 | TCP | encrypted handshake, call signalling, messages & files |
| dynamic | UDP | WebRTC voice/video (DTLS-SRTP), negotiated per call |
| 45457 | UDP | voice rooms (AES-256-GCM, µ-law 16 kHz) |
| 45454 | UDP broadcast | relay beacons `rly` (pseudonym + store digest, every 8 s) |
| 45455 | TCP | anonymous relay sync (summary vectors, bundles, receipts) |

## If phones can't see each other
Many campus networks enable **client isolation** (phones cannot talk to each other) or block broadcast.
- Try adding the other phone manually (Contacts → ➕ → its IP shown on its home screen).
- If manual IP also fails, ask IT to allow device-to-device traffic on ports 45454–45456,
  or use a dedicated router / a phone hotspot.
- Both phones must be on the same network (same SSID / subnet).
