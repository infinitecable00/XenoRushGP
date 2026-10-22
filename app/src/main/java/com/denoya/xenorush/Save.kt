package com.denoya.xenorush

import android.content.Context
import java.io.File

/** Local save file. Works fully offline; the cloud copy is optional (see Cloud.kt). */
class SaveManager(context: Context) {
    private val file: File = File(context.filesDir, "xenorush_save.json")
    private val tmp: File = File(context.filesDir, "xenorush_save.json.tmp")

    fun load(): SaveData = try {
        if (file.exists()) SaveCodec.fromJson(file.readText()) else SaveData()
    } catch (_: Exception) { SaveData() }

    fun save(sd: SaveData) {
        val text = SaveCodec.toJson(sd)
        synchronized(this) {
            try {
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
            } catch (_: Exception) {
                // best-effort persistence
            }
        }
    }
}
