package com.xlivetranslate.app

import android.content.Context
import java.io.File

class Config(c: Context) {
 val prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
 fun get(k: String, d: String = "") = prefs.getString(k, d) ?: d
 fun set(k: String, v: String) { prefs.edit().putString(k,v).apply() }
 val modelUrl get() = get("modelUrl", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")
 val source get() = get("source", "内部音频")
 val engine get() = get("engine", "ML Kit 离线")
 companion object { fun model(c: Context) = File(c.filesDir, "vosk-model") }
}
object Status {
 @Volatile var message = "尚未启动"
 @Volatile var running = false
 @Volatile var downloading = false
}
