# XenoRush GP

An F-Zero-inspired futuristic combat racer for Android, with on-track power-ups, vehicle
weapons, a 1-10 upgrade scale, 30 tracks, 22 vehicles, XRP-powered unlocks, optional Google
sign-in with cloud save. Online multiplayer (2-5 players) is built but shown as "coming soon".

> **Status: development prototype (v1.6).** The APK builds cleanly and the game logic is
> covered by an automated JVM test suite (full simulated race, live XRPL mainnet
> queries, live XRP price, save merging, and a simulated two-device multiplayer match).
> It has NOT been run on a real Android device yet. Google sign-in, cloud save and
> multiplayer need a Firebase project (see below) and have not been tested against a live
> Firebase backend. Payments go to the merchant address set in `Xrp.kt` on XRP mainnet.

## Features

- **Pseudo-3D racing engine** (SNES F-Zero style): curves, hills, rumble strips, neon void
- **Landscape** layout (works in either landscape direction) for the race and every menu
- **Rearview mirror** at the top middle of the race screen: shows racers, missiles, rockets
  and mines behind you; the frame flashes red with MISSILE LOCK when a missile is chasing you
- **30 tracks**: 5 free, 25 in the **Track Pack**
- **22 vehicles**: 5 free, 17 paid, progressively better stats
- **Power-ups on track**: Shield (+50 shield), Reflect (bounces projectiles back at the
  shooter), Speed boost, Repair (+35 hull), Ammo (+30 bullets, +1 rocket), Missile (random
  guided missile), Mines (+1). On top of the fixed power-ups, every race places **8 random
  Shield and Reflect drops** (4 of each) at new spots on the course. All power-ups come back
  every lap.
- **Upgrades 1-10** per vehicle, 7 stats: Engine, Thrusters, Stabilizers, Guns, Rockets,
  Missiles, Mines. Levels **1-5** can be bought with credits earned by winning races.
  Levels **6-10** are paid unlocks ($0.99 in XRP per level). The $0.99 upgrade can also be
  used on levels 1-5 as a shortcut.
- **5-racer single-player races** (you + 4 AI): 3 laps, lap records, first-win bonuses.
  AI weapon levels rise with track difficulty (level 1 on 1-star tracks up to level 5).
- **Multiplayer: coming soon.** The home screen button shows "COMING SOON"; the online code
  (2-5 players) stays in the project for a later release.
- **Google sign-in + cloud save** across devices
- **Updates screen** (what's new + check for updates) and **Credits screen**
  (Beam Galactic INC, support: support@beamgalactic.com)

## Music and sound effects

Menu music loops on the title screen and every menu. Race music stays silent through the
start countdown, then plays a shuffled track from the ten bundled songs and moves on to
another when that one ends (never the same song twice in a row). It fades out on the
results screen or when the player quits, and the menu loop comes back. Music pauses when
the app is backgrounded.

Sound effects (engine, boost, weapons, hits, pickups) are CC0 recordings played
through SoundPool. Each ship has its own gun. Variant "a" is the default for the
other slots; the b and c takes are in the APK so a slot can be swapped in one line.
A missing file is silent. Credits are in [docs/CREDITS.md](docs/CREDITS.md).

Settings has music and SFX on/off plus a volume step for each. The slot map and the
per-ship gun table are in [docs/AUDIO.md](docs/AUDIO.md).

Spotify can't be used for this: its developer policy prohibits using the Spotify Platform
in games (https://developer.spotify.com/policy). Use your own master files instead.

## Combat

Every vehicle has **100 hull** and up to **100 shield**. Shield absorbs damage before hull;
a racer at 0 hull is destroyed.

| Weapon | Max carried | Damage (upgrade lvl 1 -> 10) | Notes |
|---|---|---|---|
| Guns (bullets) | 100 | 5 -> 15 hp per bullet | Guns upgrade. Hold GUN to auto-fire |
| Unguided rockets | 4 | 20 -> 40 hp | Rockets upgrade. Fly straight ahead |
| Seeker missile (guided) | 2 missiles total | 55 -> 75 hp | Missiles upgrade. Locks onto the nearest racer ahead |
| Hunter missile (guided) | (shared) | 55 -> 75 hp | Locks onto the racer one place ahead of you in the standings |
| Leader missile (guided) | (shared) | 55 -> 75 hp | Locks onto the race leader (2nd place if you are leading); flies past everyone else |
| Mines | 3 | 40 -> 60 hp | Mines upgrade. Dropped behind you |

Reflect sends projectiles back; a reflected guided missile re-locks onto whoever fired it.
AI racers use guns, rockets, missiles and mines too, but hold fire for the first 8 seconds.

## Pricing (USD, paid in XRP at the live rate)

| Item | Price |
|---|---|
| One upgrade level (any stat, any owned vehicle; required for levels 6-10) | $0.99 |
| Each paid vehicle (17) | $2.50 |
| Track Pack (all 25 paid tracks) | $9.99 |

Prices live in `Pricing.kt`. When a player starts a purchase, the game reads the live
XRP/USD rate and locks that XRP amount into a 30-minute invoice, rounded up to the next
0.01 XRP. Price sources, in order: the XRP Ledger's own XRP/RLUSD AMM pool (same public node
used for payment checks), then CoinGecko, then Kraken. When offline, prices show
"XRP N/A offline" and checkout is disabled.

## Offline-first

- **Single player never needs an internet connection.** Racing, garage, credit upgrades,
  and saving all work offline. Progress is saved on the device after every change.
- Online-only features show **N/A** when there is no connection: XRP prices, checkout and
  the wallet payment check, Google sign-in, cloud sync, and multiplayer ("Multiplayer needs
  an internet connection").
- Firebase is never contacted unless the player is signed in and online (background cloud
  sync, at most once a minute) or opens Account / Multiplayer.

## Building

Requirements: Android Studio (or Gradle 8.5 + JDK 17 + Android SDK 34).

```
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Install on a device: `adb install app-debug.apk` (enable "install from unknown sources").

For a Play-Store-style release build, create a keystore and configure `app/build.gradle.kts`
signing, then `./gradlew assembleRelease`.

## XRP payments — how it works

1. `MERCHANT_ADDRESS` in `app/src/main/java/com/denoya/xenorush/Xrp.kt` is set to
   `rhP6JEgUcBhBwXyoEQwaG6YqcsE9gSJoaN`. It is developer-controlled and not player-editable,
   so players cannot redirect payments to themselves.
2. Payments are verified on **XRP mainnet only**. The network toggle is locked
   (`ALLOW_TESTNET = false`) so nobody can unlock content with free testnet XRP.
3. Players buy a vehicle, an upgrade level, or the Track Pack. The game creates an invoice:
   - unique **destination tag** per invoice
   - exact XRP amount (USD price converted at the live rate, locked for the invoice)
   - 30-minute expiry
4. The player pays from **any wallet** (Xaman, XRP Toolkit, exchange withdrawal) to your
   address with that destination tag.
5. The game polls the public XRPL JSON-RPC API and verifies the payment:
   - validated Payment transaction
   - correct destination and destination tag
   - **delivered amount** (not just requested) covers the price
   - XRP only (IOU payments rejected), no invoice-hash reuse

USD prices are defined in `Pricing.kt` and are easy to change.

### Testing payments

- For development only: set `ALLOW_TESTNET = true` and point `MERCHANT_ADDRESS` at a
  **testnet** account (funds from https://faucet.altnet.rippletest.net/). Set it back to
  `false` before distributing a build.
- Testnet RPC: `https://s.altnet.rippletest.net:51234/` • Mainnet RPC: `https://s1.ripple.com:51234/`

### Security notes (important)

- The game **never** asks for private keys or seed phrases — payments go wallet-to-wallet.
- Entitlements are stored **on-device** and in the player's own cloud save. A rooted or
  modified client could bypass unlock checks or edit its cloud save. For a commercial launch,
  move invoice creation + verification to a small backend (for example a Firebase Cloud
  Function) that writes entitlements the client cannot edit. The verification logic in `Xrp.kt` is written so
  it can be lifted to a server as-is.
- **Store policy**: Distribution and store-payment-policy review has not been completed.
  Google Play generally requires digital goods to be sold through Play Billing; check the
  current Play policy and each alternative store's rules before distributing - do not
  assume XRP payments are permitted anywhere without verification.


## Google sign-in, cloud save and multiplayer setup (Firebase)

These features use a free Firebase project that you own. Without it the game builds and runs
normally; Account shows "Online features not configured".

1. Go to https://console.firebase.google.com and create a project.
2. **Add an Android app** with package name `com.denoya.xenorush`.
3. Add the **SHA-1** fingerprint of the signing key. Debug builds of this project are signed
   with the committed key `keystore/xenorush-debug.jks`, so the fingerprint is the same on
   every machine:
   ```
   SHA-1:   E4:0B:06:16:52:81:6E:58:15:F9:AE:13:8E:DF:CB:35:24:C4:B1:39
   SHA-256: 8E:F1:BB:16:83:A9:58:B4:91:67:3A:F0:41:05:F4:76:33:7B:81:B4:EA:81:B3:4E:5A:4A:C5:9D:7D:84:66:30
   ```
   (Check it yourself with `keytool -list -v -keystore keystore/xenorush-debug.jks -storepass xenorush`.)
4. **Authentication** > Sign-in method > enable **Google**.
5. **Firestore Database** > create database, then paste `firebase/firestore.rules` into Rules.
6. **Realtime Database** > create database, then paste `firebase/database.rules.json` into Rules.
7. Download **google-services.json** (after steps 4-6, so it includes the web client ID and
   database URL) and put it at `app/google-services.json`. Rebuild. The Google Services
   Gradle plugin is applied automatically when that file exists.

For a public release, create your own private release key, add its SHA-1 in Firebase too,
and never commit it.

### How cloud save works

- Saved in Firestore at `users/{uid}`; only the signed-in player can read or write it.
- Synced on sign-in, after purchases, from the Account screen, and in the background
  (at most once a minute when signed in and online).
- Merge rules (`SaveMerge` in `Model.kt`): owned vehicles/tracks are combined, upgrade levels
  take the highest, used payment hashes are combined, best laps take the fastest, and credits
  come from whichever device changed most recently. Settings stay per-device.

### How multiplayer works

- Quick match on Firebase Realtime Database. Players join an open room or create one.
- The race starts when 4 players are in, or 15 seconds after the second player joins. The
  green light uses the server clock, so every device starts together.
- Track: the host's selected track if every player owns it, otherwise a random free track.
- Each device simulates only its own vehicle and publishes its position 10 times a second.
  Other racers are smoothed proxies. Bullets, rockets, missiles and mines are sent as events, and each
  device applies damage only to its own racer (shield / reflect are decided by the victim).
- Requires sign-in and an internet connection. Leaving or disconnecting removes you from
  the room.
- Multiplayer uses the client's own state (no anti-cheat server). Fine for friends and
  testing; a public competitive launch would need server-side validation.

## Project layout

```
app/src/main/java/com/denoya/xenorush/
├── MainActivity.kt   — fullscreen host activity
├── GameView.kt       — SurfaceView + 60fps game loop
├── Game.kt           — state machine, menus, shop, payment flow, race lifecycle
├── Render.kt         — pseudo-3D renderer, HUD, touch controls
├── Race.kt           — race simulation: physics, AI, power-ups, weapons, laps
├── TrackGen.kt       — deterministic procedural track builder (30 tracks)
├── Catalog.kt        — 22 vehicles, 30 tracks, upgrade costs
├── Pricing.kt        — USD prices, XRP conversion, item ids
├── PriceOracle.kt    — live XRP/USD (XRPL AMM, CoinGecko, Kraken)
├── Model.kt          — save model, JSON codec, cloud merge rules
├── Save.kt           — local save file
├── Net.kt            — connectivity check
├── Cloud.kt          — Google sign-in (Credential Manager + Firebase Auth), Firestore save
├── Music.kt / MusicLogic.kt — menu loop + shuffled race music (assets/music/)
├── Sfx.kt / SfxLogic.kt — SoundPool effects and the engine loop (assets/sfx/)
├── MpSession.kt      — multiplayer matchmaking, host logic, race link (pure Kotlin)
├── MpFirebase.kt     — multiplayer transport on Firebase Realtime Database
├── Xrp.kt            — XRPL JSON-RPC client + payment verification
└── Util.kt           — math helpers, constants
```

## Controls

- Bottom-left: ◀ ▶ steering
- Bottom-right: GAS (large), BRK (brake), GUN (hold to auto-fire), special (fires the
  selected rocket / missile / mine), WPN (cycles the special weapon)
- Tap the pause button (top right) during a race to pause

## Verified by automated tests

A JVM test harness (copies of the pure-Kotlin files) passes 217/217 checks. v1.6 added menu/race music selection and the sound-effect cues (boost, pickup, gun, rocket, missile, bullet/rocket/missile/mine hits). v1.5 added: the rearview mirror camera (cars behind
show up centred, left/right the right way round like a real mirror, closer cars bigger,
nothing ahead or out of range, wrap-around at the finish line, bends behind you, farthest-first
draw order). v1.4 added: music shuffle (each song once per cycle, no
back-to-back repeats, single-song loop, empty folder = silence), audio file detection,
song titles from file names, and the music on/off setting. v1.3 added: rocket/missile/mine damage scaling (20-40, 55-75,
40-60) checked in flight at levels 1 and 10, the level-5 credit cap, new stats in saves,
old-save loading and cloud merge, paid upgrade ids for the new stats, random Shield and
Reflect drops on all 30 courses (random per race, shared track data unchanged), drop
pickup, power-ups returning each lap, and 5-racer races. v1.2 added: bullet damage scaling 5-15, ammo caps (100 bullets,
4 rockets, 2 missiles), rocket 40 hp and missile 75 hp hits, shield-before-hull damage,
each guided missile picking the right target (nearest ahead / next place / leader, flying
past other racers), reflected missiles re-locking onto the shooter, mine hits, AI combat,
and a full race the player survives. v1.1 added:
USD-to-XRP conversion that never underpays, price parsing for all three sources plus a live
fetch, item-id validation, save round-trip and corrupt-save recovery, every cloud merge rule,
and a simulated two-device multiplayer match over an in-memory transport (matchmaking,
15-second fill window, single host start, track fallback, identical grid, position sync,
remote damage only on the victim's device, reflected shots and lasers hurting the shooter,
and disconnect handling). Earlier checks include: a full 3-lap race with
power-up pickups and weapon fire; projectile reflection bouncing back to damage the
original shooter; laser damage; AI staying on the road; parsing of real account_tx
envelope responses from XRPL mainnet; and rejection of underpaid/wrong-tag/wrong-destination/
IOU/failed/reused/stale payments. The APK builds cleanly with `assembleDebug`, both with and
without `google-services.json`.

### Running the tests

`jvm-tests/` holds copies of the pure-Kotlin game files plus the test suite. After editing
game code, copy the changed files from `app/src/main/java/com/denoya/xenorush/` into
`jvm-tests/src/main/kotlin/`, then run `gradle run` in `jvm-tests/` (needs JDK 17 and network
access for the live XRPL/price checks).
