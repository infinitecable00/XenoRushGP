import com.denoya.xenorush.*
import kotlin.random.Random

fun runMusicTests(check: (String, Boolean) -> Unit) {
    val songs = (1..7).map { "s$it.mp3" }
    val q = ShuffleQueue(songs, Random(42))
    val plays = (0 until 70).map { q.next()!! }
    check("music: every song plays once per cycle", plays.chunked(7).all { it.toSet().size == 7 })
    check("music: never the same song twice in a row", plays.zipWithNext().none { (a, b) -> a == b })
    check("music: order is random", plays.chunked(7).map { it }.toSet().size > 1)
    check("music: different races get different songs", ShuffleQueue(songs, Random(1)).next() != ShuffleQueue(songs, Random(2)).next() || ShuffleQueue(songs, Random(3)).next() != ShuffleQueue(songs, Random(4)).next())
    val one = ShuffleQueue(listOf("only.mp3"))
    check("music: single song loops", one.next() == "only.mp3" && one.next() == "only.mp3")
    check("music: no songs -> silence", ShuffleQueue(emptyList<String>()).next() == null)
    check("music: audio file types", listOf("a.mp3", "b.OGG", "c.m4a", "d.wav", "e.flac", "f.aac").all { MusicConfig.isAudio(it) } && !MusicConfig.isAudio("README.txt"))
    check("music: titles from file names", MusicConfig.titleOf("03_Neon_Overdrive.mp3") == "Neon Overdrive" &&
        MusicConfig.titleOf("InfiniteCable - Star Road.ogg") == "Star Road" && MusicConfig.titleOf("12. Hyper Drift.m4a") == "Hyper Drift")
    val sd = SaveData(); sd.music = false
    check("music: on/off setting saved", !SaveCodec.fromJson(SaveCodec.toJson(sd)).music && SaveCodec.fromJson("{}").music)
}
