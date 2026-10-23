package com.denoya.xenorush

import org.json.JSONObject
import kotlin.math.ceil

/**
 * Store pricing. Prices are fixed in US dollars and paid in XRP at the live rate.
 * The XRP amount is locked into the invoice when it is created (30-minute window).
 */
object Pricing {
    const val UPGRADE_USD_CENTS = 99        // one upgrade level (+1, max 10)
    const val VEHICLE_USD_CENTS = 250       // each paid vehicle (17 total)
    const val TRACK_PACK_USD_CENTS = 999    // all 25 paid tracks
    const val TRACK_PACK_ID = "trackpack"

    val STATS = Catalog.STATS

    sealed class Item {
        data class Upgrade(val vehicleId: Int, val stat: String) : Item()
        data class Vehicle(val vehicleId: Int) : Item()
        object TrackPack : Item()
    }

    fun upgradeId(vehicleId: Int, stat: String) = "u${vehicleId}_$stat"
    fun vehicleId(vehicleId: Int) = "v$vehicleId"

    /** Parses an invoice itemId. Returns null for anything unknown or out of range. */
    fun parse(itemId: String): Item? {
        try {
            if (itemId == TRACK_PACK_ID) return Item.TrackPack
            if (itemId.startsWith("v")) {
                val id = itemId.substring(1).toInt()
                return if (id in Catalog.FREE_VEHICLES until Catalog.VEHICLES.size) Item.Vehicle(id) else null
            }
            if (itemId.startsWith("u")) {
                val parts = itemId.substring(1).split("_")
                if (parts.size != 2) return null
                val id = parts[0].toInt()
                if (id !in Catalog.VEHICLES.indices || parts[1] !in STATS) return null
                return Item.Upgrade(id, parts[1])
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun usdCents(item: Item): Int = when (item) {
        is Item.Upgrade -> UPGRADE_USD_CENTS
        is Item.Vehicle -> VEHICLE_USD_CENTS
        Item.TrackPack -> TRACK_PACK_USD_CENTS
    }

    /** USD cents -> XRP drops at [xrpUsd] dollars per XRP, rounded UP to the next 0.01 XRP. */
    fun dropsFor(usdCents: Int, xrpUsd: Double): Long {
        require(xrpUsd > 0.0)
        val xrp = usdCents / 100.0 / xrpUsd
        val hundredths = ceil(xrp * 100.0 - 1e-9).toLong().coerceAtLeast(1L)
        return hundredths * 10_000L
    }

    fun fmtUsd(cents: Int): String = "$" + (cents / 100) + "." + String.format("%02d", cents % 100)

    /** "$2.50 (~1.92 XRP)" or "$2.50 (XRP N/A offline)". */
    fun label(usdCents: Int, xrpUsd: Double?): String =
        if (xrpUsd == null || xrpUsd <= 0.0) "${fmtUsd(usdCents)} (XRP N/A offline)"
        else "${fmtUsd(usdCents)} (~${fmtXrpShort(dropsFor(usdCents, xrpUsd))})"

    fun fmtXrpShort(drops: Long): String {
        val h = drops / 10_000L
        return "${h / 100}.${String.format("%02d", h % 100)} XRP"
    }

    fun displayName(item: Item): String = when (item) {
        is Item.Upgrade -> "${Catalog.VEHICLES[item.vehicleId].name} " + when (item.stat) {
            "e" -> "Engine +1"; "a" -> "Thrusters +1"; "g" -> "Stabilizers +1"
            "r" -> "Rockets +1"; "m" -> "Missiles +1"; "n" -> "Mines +1"; else -> "Guns +1"
        }
        is Item.Vehicle -> Catalog.VEHICLES[item.vehicleId].name
        Item.TrackPack -> "Track Pack (25 tracks)"
    }

    // ---- price parsing (pure; network calls are in PriceOracle) ----

    /** XRPL amm_info result for the XRP/RLUSD pool: price = RLUSD in pool / XRP in pool. */
    fun parseAmmPrice(json: JSONObject): Double? = try {
        val amm = json.getJSONObject("result").getJSONObject("amm")
        val drops = amm.getString("amount").toDouble()
        val usd = amm.getJSONObject("amount2").getString("value").toDouble()
        val p = usd / (drops / 1_000_000.0)
        if (sane(p)) p else null
    } catch (_: Exception) { null }

    fun parseCoinGecko(json: JSONObject): Double? = try {
        val p = json.getJSONObject("ripple").getDouble("usd"); if (sane(p)) p else null
    } catch (_: Exception) { null }

    fun parseKraken(json: JSONObject): Double? = try {
        val res = json.getJSONObject("result")
        val k = res.keys().next()
        val p = res.getJSONObject(k).getJSONArray("c").getString(0).toDouble()
        if (sane(p)) p else null
    } catch (_: Exception) { null }

    fun sane(p: Double) = p.isFinite() && p > 0.01 && p < 10_000.0
}
