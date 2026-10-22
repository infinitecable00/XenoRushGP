package com.denoya.xenorush

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Client-side XRP Ledger payment verification.
 *
 * SECURITY NOTES (read before shipping):
 * 1. Entitlements are stored on-device; a modified client can bypass local unlock checks.
 *    For a commercial launch, move invoice creation + verification to a backend and bind
 *    entitlements to player accounts. The verification logic here is written so it can be
 *    lifted to a server as-is.
 * 2. The merchant address is a DEVELOPER constant below (not player-editable), so players
 *    cannot redirect payments to themselves to farm unlocks.
 * 3. The game NEVER asks for private keys or seed phrases. Players pay from their own
 *    wallet to the merchant address with a unique destination tag per invoice.
 */
object Xrp {
    // CONFIGURE (developer only): the receiving wallet for player payments. Empty = XRP
    // purchases disabled until you set it. Never ship a seed/private key here - address only.
    const val MERCHANT_ADDRESS = ""

    const val MAINNET_RPC = "https://s1.ripple.com:51234/"
    const val TESTNET_RPC = "https://s.altnet.rippletest.net:51234/"
    const val INVOICE_TTL_MS = 30L * 60L * 1000L

    // XRPL transaction "date" is seconds since the Ripple epoch (2000-01-01).
    private const val RIPPLE_EPOCH_MS = 946684800000L

    fun rpcUrl(mainnet: Boolean): String = if (mainnet) MAINNET_RPC else TESTNET_RPC

    private fun post(url: String, body: String): JSONObject {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.setRequestProperty("Content-Type", "application/json")
        var os: OutputStream? = null
        try {
            os = conn.outputStream
            os.write(body.toByteArray(Charsets.UTF_8))
            os.flush()
            val code = conn.responseCode
            val reader = BufferedReader(InputStreamReader(if (code in 200..299) conn.inputStream else conn.errorStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) sb.append(line)
            reader.close()
            return JSONObject(sb.toString())
        } finally {
            os?.close()
            conn.disconnect()
        }
    }

    /** Returns the latest validated ledger index, or -1 on error. Never throws. */
    fun ledgerIndex(rpcUrl: String): Long {
        return try {
            val r = post(rpcUrl, """{"method":"ledger","params":[{"ledger_index":"validated"}]}""")
            val result = r.optJSONObject("result") ?: return -1
            if (result.optString("status") != "success") return -1
            val li = result.opt("ledger_index") ?: return -1
            when (li) {
                is Long -> li
                is Int -> li.toLong()
                is String -> li.toLongOrNull() ?: -1L
                else -> -1L
            }
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Look up validated transactions credited to [address] from [ledgerMin] onward,
     * following pagination (up to 5 pages). Each element is normalized to a FLAT object:
     * the tx fields plus "meta" and "validated". Never throws.
     */
    fun accountTxs(rpcUrl: String, address: String, ledgerMin: Long): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var marker: Any? = null
        try {
            for (page in 0 until 5) {
                val params = JSONObject()
                    .put("account", address)
                    .put("forward", true)
                    .put("ledger_index_min", if (ledgerMin > 0) ledgerMin else -1)
                    .put("ledger_index_max", -1)
                    .put("limit", 200)
                if (marker != null) params.put("marker", marker)
                val r = post(rpcUrl, """{"method":"account_tx","params":[$params]}""")
                val result = r.optJSONObject("result") ?: return out
                if (result.optString("status") != "success") return out
                val txs = result.optJSONArray("transactions") ?: return out
                for (i in 0 until txs.length()) {
                    val t = txs.optJSONObject(i) ?: continue
                    if (!t.optBoolean("validated", false)) continue
                    out.add(normalizeTxEnvelope(t))
                }
                marker = result.opt("marker")
                if (marker == null) break
            }
        } catch (_: Exception) {
            // return whatever we collected so far
        }
        return out
    }

    /**
     * account_tx elements come as envelopes: {"tx": {...}, "meta": {...}, "validated": bool}.
     * Older servers return flat objects. Normalize both to a flat tx with "meta"/"validated".
     */
    private fun normalizeTxEnvelope(t: JSONObject): JSONObject {
        val inner = t.optJSONObject("tx")
        return if (inner != null) {
            inner.put("meta", t.opt("meta") ?: JSONObject())
            inner.put("validated", t.optBoolean("validated", false))
            inner
        } else {
            t
        }
    }

    /** Fetch a single transaction by hash. Returns null on error/not found. Never throws. */
    fun txByHash(rpcUrl: String, hash: String): JSONObject? {
        return try {
            val r = post(rpcUrl, """{"method":"tx","params":[{"transaction":"$hash"}]}""")
            val result = r.optJSONObject("result") ?: return null
            if (result.optString("status") != "success") return null
            if (!result.optBoolean("validated", false)) return null
            result
        } catch (_: Exception) {
            null
        }
    }

    data class PaymentCheck(
        val ok: Boolean,
        val reason: String = "",
        val txHash: String = "",
        val deliveredDrops: Long = 0
    )

    /**
     * Verify a (flat, validated) payment transaction satisfies the invoice:
     * - Payment type, successful result (tesSUCCESS), validated
     * - correct destination and destination tag
     * - delivered amount (meta.delivered_amount) >= required drops, in XRP (not IOU)
     * - tx date (Ripple epoch) within the invoice window
     * - tx hash not already used
     */
    fun verifyPayment(txJson: JSONObject, invoice: Invoice, merchant: String, usedHashes: Set<String>): PaymentCheck {
        val hash = txJson.optString("hash", "")
        if (hash.isEmpty()) return PaymentCheck(false, "no hash")
        if (!txJson.optBoolean("validated", false)) return PaymentCheck(false, "not validated")
        if (usedHashes.contains(hash)) return PaymentCheck(false, "already used")
        if (txJson.optString("TransactionType") != "Payment")
            return PaymentCheck(false, "not a payment")
        if (txJson.optString("Destination") != merchant)
            return PaymentCheck(false, "wrong destination")
        if (txJson.optLong("DestinationTag", -1L) != invoice.destTag)
            return PaymentCheck(false, "wrong destination tag")

        val meta = txJson.optJSONObject("meta") ?: return PaymentCheck(false, "no meta")
        if (meta.optString("TransactionResult") != "tesSUCCESS")
            return PaymentCheck(false, "tx failed: ${meta.optString("TransactionResult")}")

        val delivered = meta.opt("delivered_amount") ?: return PaymentCheck(false, "no delivered amount")
        var drops = -1L
        if (delivered is String) {
            val s = delivered as String
            if (s == "unavailable") return PaymentCheck(false, "amount unavailable")
            drops = s.toLongOrNull() ?: return PaymentCheck(false, "bad amount")
        } else {
            return PaymentCheck(false, "not XRP") // IOU payment
        }
        if (drops < invoice.amountDrops) return PaymentCheck(false, "underpaid: $drops < ${invoice.amountDrops}")

        // Ripple epoch date within [invoice start - 15min grace, expiry + 1h]
        val txTimeMs = txJson.optLong("date", 0L) * 1000L + RIPPLE_EPOCH_MS
        if (txTimeMs < invoice.createdAt - 15L * 60L * 1000L) return PaymentCheck(false, "tx too old")
        if (txTimeMs > invoice.expiresAt + 60L * 60L * 1000L) return PaymentCheck(false, "tx after expiry")

        return PaymentCheck(true, txHash = hash, deliveredDrops = drops)
    }
}
