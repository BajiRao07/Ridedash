# RideDash – turn an old Android phone into a bike dashboard

Big-button, dark dashboard for an old phone mounted on a bike:
speed (GPS), map, music controls, messages (read aloud + quick reply),
incoming call answer/reject and 3 favourite contacts.

Runs on **Android 5.0 (API 21) and newer**.

## How it works

| Phone | Mode in Setup | What it does |
|---|---|---|
| Old phone on the bike | **Bike dashboard** | Shows everything, acts as home screen |
| Your main phone (with SIM) | **Main phone** | Sends calls, messages and music to the bike phone over Bluetooth, and carries out your taps (answer, reply, next song) |

If the old phone has its own SIM you can skip the main phone completely.

## Security

- **In-app pairing with a 6-digit code.** P-256 ECDH key exchange with a
  commitment; both phones show the same code and the user confirms it on each.
  A man-in-the-middle has a 1-in-a-million chance and is caught by mismatching codes.
- **Encrypted link.** Encrypted, bonded Bluetooth sockets, plus AES-256-GCM on
  every message with per-connection keys and replay protection.
- **Only the paired phone is accepted**; anything else is disconnected.
- **Role-limited commands.** The bike phone only accepts display data; the main
  phone only accepts media/call/reply/dial commands, with input validation.
- **Pairing key protected by Android Keystore** (Android 6+).
- **No cloud backup or device transfer** of app data; **no unencrypted network traffic**.
- Messages are never written to storage and nothing is sent to any server.

## Build the APK

### Option A – Android Studio (easiest)
1. Install Android Studio (Ladybug or newer).
2. File → Open → select the `RideDash` folder. Let Gradle sync.
3. Build → Build APK(s). APK: `app/build/outputs/apk/debug/app-debug.apk`.

### Option B – Command line (Linux)
```bash
export ANDROID_HOME=$HOME/Android/Sdk      # needs platform 35 + build-tools
./gradlew assembleDebug
```

### Option C – No install at all (GitHub Actions)
Push this folder to a GitHub repo. The workflow in `.github/workflows/build.yml`
builds the APK; download it from the run's **Artifacts** section.

## Install on the phones
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
or copy the APK to the phone and open it (allow "Install unknown apps").

## First-time setup
1. Pair the two phones in Bluetooth settings.
2. **Bike phone:** Setup → *Bike dashboard* → allow permissions → set as home
   screen → **Start pairing (3 min)**.
3. **Main phone:** Setup → *Main phone* → allow permissions → **Choose bike phone**
   → **Pair with bike phone**.
4. Both phones show a 6-digit code. Tap **MATCH** on both only if they are identical.
5. Tap **Save and start** on both. They show "securely connected".

## Test checklist
- [ ] Speed changes while walking/riding (GPS needs open sky)
- [ ] Play music on main phone → title shows on bike phone, ▶ / II / ◀◀ / ▶▶ work
- [ ] Send yourself a WhatsApp message → appears, read aloud, quick reply works
- [ ] Call the main phone → big ANSWER / REJECT screen on bike phone
- [ ] Favourite contact button starts a call from the main phone
- [ ] MAP shows your location; NAVIGATE opens Google Maps in two-wheeler mode
- [ ] Switch Bluetooth off and on → link reconnects by itself

## Before publishing on Google Play
- **Map tiles:** the free openstreetmap.org tile server is not allowed for heavy
  app traffic. Switch to a provider such as MapTiler, Stadia Maps or Thunderforest
  (change `TileSourceFactory.MAPNIK` in `MainActivity.ensureMap`).
- Raise `targetSdk` to what Play currently requires.
- Create a signing key and build a release bundle (`./gradlew bundleRelease`).
- Write a privacy policy (the app reads notifications – Play requires a
  prominent disclosure and policy URL).
- Don't use "CarPlay" in the name or listing.
