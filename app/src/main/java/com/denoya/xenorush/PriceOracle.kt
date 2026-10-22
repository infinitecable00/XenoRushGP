package com.denoya.xenorush

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live XRP/USD price. Primary source is the XRP Ledger itself (the XRP/RLUSD AMM pool,
 * read from the same public node used for payment checks). CoinGecko and Kraken are fallbacks.
 * All calls are blocking - call from a background thread. Never throws.
 */
object PriceOracle {
    private const val RLUSD_HEX = "524C555344000000000000000000000000000000"
    private const val RLUSD_ISSUER = "rMxCKbEDwqr76QuheSUMdEGf4B9xJ8m5De"
    private const val CACHE_MS = 60_000L

    @Volatile var lastPrice: Double? = null
        private set
    @Volatile var lastFetch: Long = 0L
        private set

    fun cachedFresh(): Double? = if (System.currentTimeMillis() - lastFetch < 10 * 60_000L) lastPrice else null

    fun fetch(force: Boolean = false): Double? {
        if (!force && lastPrice != null && System.currentTimeMillis() - lastFetch < CACHE_MS) return lastPrice
        val p = fromXrpl() ?: fromCoinGecko() ?: fromKraken()
        if (p != null) { lastPrice = p; lastFetch = System.currentTimeMillis() }
        return p
    }

    private fun fromXrpl(): Double? = try {
        val body = """{"method":"amm_info","params":[{"asset":{"currency":"XRP"},"asset2":{"currency":"$RLUSD_HEX","issuer":"$RLUSD_ISSUER"},"ledger_index":"validated"}]}"""
        Pricing.parseAmmPrice(JSONObject(http(Xrp.MAINNET_RPC, body)))
    } catch (_: Exception) { null }

    private fun fromCoinGecko(): Double? = try {
        Pricing.parseCoinGecko(JSONObject(http("https://api.coingecko.com/api/v3/simple/price?ids=ripple&vs_currencies=usd", null)))
    } catch (_: Exception) { null }

    private fun fromKraken(): Double? = try {
        Pricing.parseKraken(JSONObject(http("https://api.kraken.com/0/public/Ticker?pair=XRPUSD", null)))
    } catch (_: Exception) { null }

    private fun http(url: String, postBody: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            if (postBody != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(postBody.toByteArray()) }
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
