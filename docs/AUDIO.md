# Audio

XenoRush GP plays menu music, shuffled race music, and sound effects from files
bundled in the APK. Nothing is streamed from the network. Missing effect files
are skipped, so a race never crashes because a sound is absent.

Music uses one `MediaPlayer` (`Music.kt`). Effects use `SoundPool` (`Sfx.kt`).
Which track to play, and which effect a race event emits, is decided in pure
Kotlin (`MusicLogic.kt`, `SfxLogic.kt`, `Race.kt`) so the JVM test suite can
check it without a device.

## Where the files live

Both sets live under `assets/`, not `res/raw`. The music player already opens
files with `AssetFileDescriptor`, and `app/build.gradle.kts` marks
`ogg`, `wav`, `mp3`, `m4a`, `aac`, and `flac` as uncompressed so `MediaPlayer`
and `SoundPool` can stream them straight out of the APK.

| What | Folder |
|---|---|
| Menu and race music | `app/src/main/assets/music/` |
| Sound effects | `app/src/main/assets/sfx/` |

## Music

| Screen | What plays |
|---|---|
| Title, main menu, garage, tracks, shop, settings, and the other front-end screens | `music_menu_black_hoodie_thoughts.ogg`, looping |
| Race countdown (3, 2, 1, GO) | silence — menu music fades out, race music has not started |
| Race, after the countdown, until the finish | a random `music_race_*.ogg` track |
| Pause menu | the current race track, paused |
| Results, or quit to the menu | race music fades out; menu music starts again on the menus |
| App backgrounded | music pauses; it resumes when the app returns (still paused if the pause menu is up) |

The ten race tracks are:

- `music_race_black_spiral.ogg`
- `music_race_shadows.ogg`
- `music_race_night_loop.ogg`
- `music_race_midnight_spiral.ogg`
- `music_race_in_the_darkness.ogg`
- `music_race_hypnotic.ogg`
- `music_race_grin_in_the_dark.ogg`
- `music_race_fall_back.ogg`
- `music_race_black_hoodie.ogg`
- `music_race_graveyard.ogg`

Shuffle rules (`ShuffleQueue`): every track plays once before any track repeats,
and the same track never plays twice in a row, including when a new race starts
or when the list wraps. If a track ends before the race does, the next one
starts on its own. A fresh race calls `MusicPlayer.newRace()` so it does not
continue the previous race's song.

The "NOW PLAYING" line uses the file name: `music_race_black_spiral.ogg` shows
as "Black Spiral".

Settings (device-local, not part of cloud save):

- **RACE MUSIC** — on/off. Off fades the music out and stays silent on the menus too.
- **MUSIC VOLUME** — 100%, 75%, 50%, 25%, 0%. 100% is the game's full level
  (`MusicConfig.VOLUME`, 0.8).

`MediaPlayer` is released in `Activity.onDestroy`.

## Sound effects

`SoundPool` loads every candidate at startup (`SfxMap.allFiles`, 32 OGG files).
Variant **a** is the shipped choice for each one-shot slot and
for the engine. Variants **b** and **c** stay in the APK. Swap one by editing
a single entry in `SfxMap.DEFAULT` (or `SfxMap.ENGINE` for the loop) in
`SfxLogic.kt`. A missing file stays silent.

Sources and licenses are in [CREDITS.md](CREDITS.md). Every file is CC0.

| Slot | Default file | Also bundled | Triggers |
|---|---|---|---|
| Boost | `sfx_booster_a.ogg` | `_b`, `_c` | BST button, and a **Speed** pad. The pad also plays the pickup sound. |
| Engine loop | `sfx_ship_accel_loop_a.ogg` | `_b`, `_c` | After GO, while the player's ship is alive. Loops (`loop = -1`). Pitch and volume rise with speed, and rise again while a boost is active. Only the player's engine loops. Stops on pause, finish, destroy, or quit. |
| Pickup | `sfx_powerup_pickup_a.ogg` | `_b`, `_c` | Any power-up the player collects (shield, reflect, speed, repair, ammo, missile, mine). |
| Gun fire | per ship, below | `sfx_gun_fire_{a,b,c}.ogg` | `Race.fireGun` — the player's held GUN button and AI gun bursts. |
| Missile fire | `sfx_missile_fire_a.ogg` | `_b`, `_c` | Seeker, Hunter, or Leader missile, player or AI. |
| Rocket fire | `sfx_rocket_fire_a.ogg` | `_b`, `_c` | Unguided rocket, player or AI. |
| Bullet hit | `sfx_gun_bullet_hit_a.ogg`, then `_b` | `_c` | A bullet connects with any ship, player or AI. `_b` plays about 60 ms later (rate 1.04, a bit quieter) so a single hit is a double tink. Shield absorbs count as hits. |
| Missile hit | `sfx_missile_hit_a.ogg` | `_b`, `_c` | A guided missile connects, plus the explosion. Player or AI. |
| Rocket hit | `sfx_rocket_hit_a.ogg` | `_b`, `_c` | A rocket connects, plus the explosion. Player or AI. |
| Mine hit | `sfx_prox_mine_hit_a.ogg` | `_b`, `_c` | A dropped mine detonates when a ship (player or AI) drives into it. |
| Countdown tick | `sfx_countdown_tick.ogg` | — | One short ping as the on-screen **3**, **2**, and **1** each appear. Same file and pitch every second. |
| Countdown GO | `sfx_countdown_go.ogg` | — | A different, longer tone when **GO!** appears. Race music starts as that word ends and the countdown hits 0. |

The three engine loops are about 4.65 s with a crossfaded seam. `SoundPool`
changes the playback rate with speed (about 0.72 at a standstill, up to about
1.38 at top speed while boosting).

### Recharge strip

Both sounds reuse candidates that are already in the APK. Change `SfxMap.PIT_LOOP`
or `SfxMap.PIT_FULL` to another name from the table above to swap one. There is
no separate pit file. Only the local player is audible.

| Slot | File | How it plays |
|---|---|---|
| Recharge hum | `sfx_booster_c.ogg` | Loops while the shield is filling on the strip. Gain `SfxMap.PIT_GAIN` (0.40), rate `SfxMap.PIT_RATE` (0.70), so the layered phaser-plus-thruster reads as an energy hum. Stops when the ship leaves the lane or the shield hits full. |
| Shield full | `sfx_powerup_pickup_b.ogg` | One-shot chime on the frame the strip tops the shield off. Gain `SfxMap.PIT_FULL_GAIN` (0.88), rate `SfxMap.PIT_FULL_RATE` (1.28), higher than the pickup blip (`sfx_powerup_pickup_a` at rate 1). |

The hum does not play if the shield is already full, and the chime does not play for a shield that was already full. Neither sound is tied to hull.

### Countdown

`sfx_countdown_tick` and `sfx_countdown_go` are the lights. Replace either file in `assets/sfx/` to change the tone. The tick is a short gun sample pitched up (`SfxMap.COUNTDOWN_TICK_RATE` 1.45). GO is a rocket impact pitched up (`SfxMap.COUNTDOWN_GO_RATE` 1.20). Both follow the SFX mute and the SFX volume. The weapon-cycle click is still the small built-in beep.

The announcer speaks the same numbers, under the ping. See [Announcer](#announcer).

### Barrier

A scrape, a crash, and a destroyed ship reuse clips that are already loaded. There is no extra file.

| Event | Slot | File |
|---|---|---|
| Scrape | `RaceSfx.SCRAPE` | `sfx_gun_bullet_hit` (variant A) |
| Crash | `RaceSfx.CRASH` | `sfx_rocket_hit` (variant A) |
| Destroyed | `RaceSfx.EXPLODE` | `sfx_prox_mine_hit` (variant A) |

### Announcer

Clips live in `app/src/main/assets/sfx/announcer/` (`vo_<voice>_<line>.ogg`). This build includes Kenney's male and female lines (see [CREDITS.md](CREDITS.md)). `vo_robot_one` through `vo_robot_five`, `vo_robot_go`, `vo_rockk_turbo`, and `vo_rockk_victory` play when those files are added to the same folder. Settings **ANNOUNCER** cycles Robot, Female, Male, and Off. Robot is the default. Off, the SFX mute, and SFX volume 0 all silence the voice. Music ducks to `Announcer.DUCK` (0.55) while a line is speaking. Two lines never play at once: the lights replace whatever is speaking, other calls keep one queued line, and a boost line is dropped if anything is already speaking.

| Call | When | Robot | Female / Male |
|---|---|---|---|
| three, two, one, go | With the on-screen numbers and the countdown ping | `vo_robot_*` | `vo_kenney_female_*` / `vo_kenney_male_*` |
| final_round | The player starts the last lap | male Kenney clip | that voice |
| you_win | Player finishes in 1st. `congratulations` if `you_win` is missing | male Kenney clip | that voice |
| you_lose | Player finishes outside 1st. `game_over` if `you_lose` is missing | male Kenney clip | that voice |
| new_highscore | Player sets a best lap, including the first one | male Kenney clip | that voice |
| power_up | Player collects a power-up. Silent again for 8 seconds | male Kenney clip | that voice |
| hurry_up | Player is last when the final lap starts. Queued behind final_round | male Kenney clip | that voice |
| turbo | Player boost (button or speed pad), at most every 18 seconds, and only if nothing is speaking | `vo_rockk_turbo` (or `nitro`) | same Rockk clip |

Rockk's countdown clip is not used. Ready and set are not played: the lights start on 3, and the words stay locked to that clock. AI ships are not announced.

### Per-ship guns

There are three gun recordings and 22 ships. `ShipGuns.TABLE` in `SfxLogic.kt`
is the only place to edit this. Each row is the ship name (same string as
`Catalog`) plus a file variant and a playback rate. Rate 1.00 is the
recording; higher is sharper. `sfx_gun_fire_c` is the short sample (about
0.15 s), so it covers the ships that should stay crisp at the gun's 0.12 s
repeat. A name that is not in the table falls back to variant **a** at rate 1.00.

| Ship | File | Rate |
|---|---|---|
| Sprint MK-I | `sfx_gun_fire_c` | 1.00 |
| Vega Runner | `sfx_gun_fire_a` | 0.90 |
| Kestrel D2 | `sfx_gun_fire_b` | 1.08 |
| Pulsar GT | `sfx_gun_fire_c` | 0.86 |
| Ion Drifter | `sfx_gun_fire_a` | 1.12 |
| Falcon V | `sfx_gun_fire_b` | 0.84 |
| Nova Sting | `sfx_gun_fire_c` | 1.14 |
| Crimson Dart | `sfx_gun_fire_a` | 0.78 |
| Zephyr R9 | `sfx_gun_fire_b` | 1.22 |
| Hellion X | `sfx_gun_fire_c` | 1.28 |
| Tempest MK-III | `sfx_gun_fire_a` | 1.26 |
| Quasar Blade | `sfx_gun_fire_b` | 0.96 |
| Specter Wraith | `sfx_gun_fire_c` | 0.74 |
| Vortex Prime | `sfx_gun_fire_a` | 1.40 |
| Ember Fang | `sfx_gun_fire_b` | 1.36 |
| Onyx Phantom | `sfx_gun_fire_c` | 1.20 |
| Solaris Apex | `sfx_gun_fire_a` | 1.00 |
| Hydra Dominator | `sfx_gun_fire_b` | 1.14 |
| Nemesis Zero | `sfx_gun_fire_c` | 0.94 |
| Aurora Titan | `sfx_gun_fire_a` | 0.84 |
| Eclipse Omega | `sfx_gun_fire_b` | 0.78 |
| Xenon Deity | `sfx_gun_fire_c` | 1.36 |

### Mixing

`SfxMix` keeps a volley from clipping:

- Concurrent copies are capped: guns and bullet hits 4, missile/rocket fire and the three explosions 2, boost and pickup 2. The player can still play when the cap is full (up to two extra). Extra AI copies are dropped. Cues are sorted player-first, then nearest, so a far ship loses the slot.
- Each extra copy of the same slot plays quieter (full, then 0.70, then 0.48).
- Slot gains sit under 1.0 (guns 0.48, bullet hits 0.55, missile and rocket fire 0.62, explosions 0.58, boost and pickup 0.80).
- AI volume starts at 0.82 and falls with distance along the track. The player is always full.
- Explosions duck the engine to 0.58 and outrank other sounds if the 16-stream pool is full.
- The second bullet tink bypasses the concurrency cap so it is not dropped, and it still occupies the mix window.

Cues are emitted inside `Race` (`sfxCues`) for both the local player and AI
ships, then played from `Game.drainSfx`. In multiplayer, a shot that connects
with a remote ship plays on the shooter's device; the victim's device plays it
again when that device applies the damage.

A reflected shot does not play a hit until it hits someone. The weapon-cycle
click is still the small built-in tone, and it follows the same SFX mute and volume.

Settings:

- **SFX** — on/off for effects, the engine, and the countdown beeps.
- **SFX VOLUME** — 100%, 75%, 50%, 25%, 0%.

`SoundPool` is paused with `autoPause()` when the app backgrounds, resumed
with `autoResume()` on return, and `release()`d in `onDestroy`.

## Not hooked

These stay silent:

- Dropping a mine (only the detonation has a slot)
- Reflect
- Brake
- Steering

Boost is the BST button and the on-track Speed pad. Both play the boost slot. A destroyed ship plays the explosion slot above, then respawns at the start of the lap it was on.
