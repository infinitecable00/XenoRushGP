package com.denoya.xenorush

import org.json.JSONArray
import org.json.JSONObject

data class VehicleState(var owned: Boolean, var e: Int, var a: Int, var g: Int, var w: Int,
                        var r: Int = 1, var m: Int = 1, var n: Int = 1) {
    fun get(stat: String): Int = when (stat) { "e" -> e; "a" -> a; "g" -> g; "r" -> r; "m" -> m; "n" -> n; else -> w }
    fun set(stat: String, v: Int) { when (stat) { "e" -> e = v; "a" -> a = v; "g" -> g = v; "r" -> r = v; "m" -> m = v; "n" -> n = v; else -> w = v } }
    fun inc(stat: String) = set(stat, get(stat) + 1)
}
data class TrackState(var owned: Boolean, var bestLapMs: Long, var wins: Int)

data class Invoice(
    val itemId: String,          // "v5", "u3_e", "trackpack"
    val amountDrops: Long,
    val destTag: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val usdCents: Int = 0,
    val xrpUsd: Double = 0.0
)

class SaveData {
    var credits: Int = 500
    var selectedVehicle: Int = 0
    val vehicles = HashMap<Int, VehicleState>()
    val tracks = HashMap<Int, TrackState>()
    val usedTxHashes = HashSet<String>()
    var pendingInvoice: Invoice? = null
    var updatedAt: Long = 0L          // last local change (ms), used to pick the newer credits on merge
    // settings (device-local, never synced)
    var mainnet: Boolean = false
    var merchantAddress: String = ""
    var sound: Boolean = true
    var music: Boolean = true
    /** 0..100. Scales effects when [sound] is on. 0 is silent. */
    var sfxVolume: Int = 100
    /** 0..100. Scales music when [music] is on. 100 is [MusicConfig.VOLUME]. */
    var musicVolume: Int = 100

    fun vehicleState(id: Int): VehicleState = vehicles.getOrPut(id) {
        VehicleState(id < Catalog.FREE_VEHICLES, 1, 1, 1, 1)
    }

    fun trackState(id: Int): TrackState = tracks.getOrPut(id) {
        TrackState(id < Catalog.FREE_TRACKS, 0L, 0)
    }

    fun allVehiclesOwned(): Boolean = (0 until 22).all { vehicleState(it).owned }
    fun allTracksOwned(): Boolean = (0 until 30).all { trackState(it).owned }
    fun hasTrackPack(): Boolean = (Catalog.FREE_TRACKS until 30).all { trackState(it).owned }

    /** Replace progress with [o]'s progress, keeping this device's settings. */
    fun copyProgressFrom(o: SaveData) {
        credits = o.credits
        selectedVehicle = o.selectedVehicle
        vehicles.clear(); o.vehicles.forEach { (k, v) -> vehicles[k] = v.copy() }
        tracks.clear(); o.tracks.forEach { (k, t) -> tracks[k] = t.copy() }
        usedTxHashes.clear(); usedTxHashes.addAll(o.usedTxHashes)
        updatedAt = o.updatedAt
    }
}

/** JSON <-> SaveData. Pure so it is shared by local files, cloud saves and tests. */
object SaveCodec {
    fun toJson(sd: SaveData, includeLocal: Boolean = true): String {
        val json = JSONObject()
        json.put("version", 2)
        json.put("credits", sd.credits)
        json.put("selectedVehicle", sd.selectedVehicle)
        json.put("updatedAt", sd.updatedAt)
        val vs = JSONObject()
        for ((k, v) in sd.vehicles) {
            vs.put(k.toString(), JSONObject().put("owned", v.owned).put("e", v.e)
                .put("a", v.a).put("g", v.g).put("w", v.w).put("r", v.r).put("m", v.m).put("n", v.n))
        }
        json.put("vehicles", vs)
        val ts = JSONObject()
        for ((k, t) in sd.tracks) {
            ts.put(k.toString(), JSONObject().put("owned", t.owned).put("bestLap", t.bestLapMs).put("wins", t.wins))
        }
        json.put("tracks", ts)
        json.put("usedTx", JSONArray(sd.usedTxHashes.toList()))
        if (includeLocal) {
            sd.pendingInvoice?.let {
                json.put("pendingInvoice", JSONObject().put("itemId", it.itemId)
                    .put("amountDrops", it.amountDrops).put("destTag", it.destTag)
                    .put("createdAt", it.createdAt).put("expiresAt", it.expiresAt)
                    .put("usdCents", it.usdCents).put("xrpUsd", it.xrpUsd))
            }
            json.put("settings", JSONObject().put("mainnet", sd.mainnet)
                .put("merchant", sd.merchantAddress).put("sound", sd.sound).put("music", sd.music)
                .put("sfxVolume", sd.sfxVolume).put("musicVolume", sd.musicVolume))
        }
        return json.toString()
    }

    /** Never throws; a corrupt or empty string returns a fresh save. */
    fun fromJson(text: String?): SaveData {
        val sd = SaveData()
        if (text.isNullOrBlank()) return sd
        try {
            val json = JSONObject(text)
            sd.credits = json.optInt("credits", 500).coerceAtLeast(0)
            sd.selectedVehicle = json.optInt("selectedVehicle", 0).coerceIn(0, 21)
            sd.updatedAt = json.optLong("updatedAt", 0L)
            json.optJSONObject("vehicles")?.let { vs ->
                for (k in vs.keys()) {
                    val id = k.toIntOrNull() ?: continue
                    if (id !in 0..21) continue
                    val o = vs.getJSONObject(k)
                    fun lv(n: String) = o.optInt(n, 1).coerceIn(1, 10)
                    sd.vehicles[id] = VehicleState(o.optBoolean("owned", false) || id < Catalog.FREE_VEHICLES,
                        lv("e"), lv("a"), lv("g"), lv("w"), lv("r"), lv("m"), lv("n"))
                }
            }
            json.optJSONObject("tracks")?.let { ts ->
                for (k in ts.keys()) {
                    val id = k.toIntOrNull() ?: continue
                    if (id !in 0..29) continue
                    val o = ts.getJSONObject(k)
                    sd.tracks[id] = TrackState(o.optBoolean("owned", false) || id < Catalog.FREE_TRACKS,
                        o.optLong("bestLap", 0L), o.optInt("wins", 0))
                }
            }
            json.optJSONArray("usedTx")?.let { for (i in 0 until it.length()) sd.usedTxHashes.add(it.getString(i)) }
            json.optJSONObject("pendingInvoice")?.let { inv ->
                sd.pendingInvoice = Invoice(inv.getString("itemId"), inv.getLong("amountDrops"), inv.getLong("destTag"),
                    inv.getLong("createdAt"), inv.getLong("expiresAt"), inv.optInt("usdCents", 0), inv.optDouble("xrpUsd", 0.0))
            }
            json.optJSONObject("settings")?.let { st ->
                sd.mainnet = st.optBoolean("mainnet", false)
                sd.merchantAddress = st.optString("merchant", "")
                sd.sound = st.optBoolean("sound", true)
                sd.music = st.optBoolean("music", true)
                sd.sfxVolume = st.optInt("sfxVolume", 100).coerceIn(0, 100)
                sd.musicVolume = st.optInt("musicVolume", 100).coerceIn(0, 100)
            }
        } catch (_: Exception) {
            return SaveData()
        }
        return sd
    }
}

/**
 * Merge rules for cloud sync (local device save + cloud save -> result):
 *  - owned vehicles/tracks: union (a purchase on any device counts everywhere)
 *  - upgrade levels: max per stat
 *  - used transaction hashes: union (prevents re-using a payment on a second device)
 *  - wins: max; best lap: fastest non-zero
 *  - credits and selected vehicle: taken from whichever save was changed most recently
 *  - settings and pending invoice: always the local device's
 */
object SaveMerge {
    fun merge(local: SaveData, remote: SaveData): SaveData {
        val out = SaveData()
        val newer = if (remote.updatedAt > local.updatedAt) remote else local
        out.credits = newer.credits
        out.updatedAt = maxOf(local.updatedAt, remote.updatedAt)
        for (id in 0 until 22) {
            val a = local.vehicleState(id); val b = remote.vehicleState(id)
            val v = VehicleState(a.owned || b.owned, 1, 1, 1, 1)
            for (st in Catalog.STATS) v.set(st, maxOf(a.get(st), b.get(st)))
            out.vehicles[id] = v
        }
        for (id in 0 until 30) {
            val a = local.trackState(id); val b = remote.trackState(id)
            val best = listOf(a.bestLapMs, b.bestLapMs).filter { it > 0 }.minOrNull() ?: 0L
            out.tracks[id] = TrackState(a.owned || b.owned, best, maxOf(a.wins, b.wins))
        }
        out.usedTxHashes.addAll(local.usedTxHashes); out.usedTxHashes.addAll(remote.usedTxHashes)
        out.selectedVehicle = if (out.vehicleState(newer.selectedVehicle).owned) newer.selectedVehicle else 0
        out.pendingInvoice = local.pendingInvoice
        out.mainnet = local.mainnet; out.merchantAddress = local.merchantAddress; out.sound = local.sound; out.music = local.music
        out.sfxVolume = local.sfxVolume; out.musicVolume = local.musicVolume
        return out
    }
}
