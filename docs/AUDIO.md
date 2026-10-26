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

`SoundPool` loads each slot at startup. It tries `ogg`, then `wav`, then `mp3`,
and keeps the first one it can open. The shipped files are short generated
placeholders (a tone or a noise burst) so the hooks can be heard before the
final recordings exist. To replace one, add the `.ogg` (or `.mp3`) with the
same base name. The `.ogg` wins over the `.wav`, so the placeholder can stay
until you delete it.

| Slot | File to replace | Triggers |
|---|---|---|
| Boost | `sfx_boost.ogg` | Player drives over a **Speed** pad. There is no separate boost button. Also plays the pickup sound. |
| Engine loop | `sfx_engine.ogg` | After GO, while the player's ship is alive. Loops. Pitch and volume rise with speed, and rise again while a boost is active. Stops on pause, finish, destroy, or quit. |
| Pickup | `sfx_pickup.ogg` | Any power-up the player collects (shield, reflect, speed, repair, ammo, missile, mine). |
| Gun fire | `sfx_gun.ogg` | `Race.fireGun` — the player's held GUN button and AI gun bursts. |
| Missile fire | `sfx_missile.ogg` | Seeker, Hunter, or Leader missile, player or AI. |
| Rocket fire | `sfx_rocket.ogg` | Unguided rocket, player or AI. |
| Bullet hit | `sfx_bullet_hit.ogg` | A bullet connects with any ship, player or AI. A light two-ping placeholder stands in for a metallic tink. Shield absorbs count as hits. |
| Missile hit | `sfx_missile_hit.ogg` | A guided missile connects, plus the explosion. Player or AI. |
| Rocket hit | `sfx_rocket_hit.ogg` | A rocket connects, plus the explosion. Player or AI. |
| Mine hit | `sfx_mine_hit.ogg` | A dropped mine detonates when a ship (player or AI) drives into it. |

Placeholder paths today:

```
app/src/main/assets/sfx/sfx_boost.wav
app/src/main/assets/sfx/sfx_engine.wav
app/src/main/assets/sfx/sfx_pickup.wav
app/src/main/assets/sfx/sfx_gun.wav
app/src/main/assets/sfx/sfx_missile.wav
app/src/main/assets/sfx/sfx_rocket.wav
app/src/main/assets/sfx/sfx_bullet_hit.wav
app/src/main/assets/sfx/sfx_missile_hit.wav
app/src/main/assets/sfx/sfx_rocket_hit.wav
app/src/main/assets/sfx/sfx_mine_hit.wav
```

The engine placeholder is a one-second loop. A real recording should also loop
cleanly: `SoundPool` plays it with loop = -1 and changes rate with speed.

Cues are emitted inside `Race` (`sfxCues`) for both the local player and AI
ships, then played from `Game.drainSfx`. In multiplayer, a shot that connects
with a remote ship plays on the shooter's device; the victim's device plays it
again when that device applies the damage.

A reflected shot does not play a hit until it hits someone. The countdown
beeps (3, 2, 1, GO) and the weapon-cycle click are still the small built-in
tones, and they follow the same SFX mute and volume.

Settings:

- **SFX** — on/off for effects, the engine, and the countdown beeps.
- **SFX VOLUME** — 100%, 75%, 50%, 25%, 0%.

`SoundPool` is paused with `autoPause()` when the app backgrounds, resumed
with `autoResume()` on return, and `release()`d in `onDestroy`.

## Not hooked

These were not in the slot list, so they stay silent (the countdown still beeps,
as above):

- Dropping a mine (only the detonation has a slot)
- Reflect
- Brake
- Ship destroyed
- Steering

There is no separate booster control. Boost is the on-track Speed power-up.
