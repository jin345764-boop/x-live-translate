package com.xlivetranslate.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** One transcript sentence, split by the speaker's natural pauses. Times are ms of audio since capture start. */
class Seg(val startMs: Long, val endMs: Long, val en: String, var zh: String = "")

/** One capture session (start -> stop). Persisted as JSON in filesDir/sessions so it survives service stop. */
class Session(val id: Long, private val file: File) {
 val segs = ArrayList<Seg>()
 val title: String get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(id))
 val durationMs: Long get() = synchronized(this) { segs.lastOrNull()?.endMs ?: 0L }
 @Synchronized fun add(s: Seg): Int { segs.add(s); save(); return segs.size - 1 }
 @Synchronized fun setZh(i: Int, zh: String) { if (i in segs.indices) { segs[i].zh = zh; save() } }
 @Synchronized fun count() = segs.size
 @Synchronized fun snapshot(): List<Seg> = segs.map { Seg(it.startMs, it.endMs, it.en, it.zh) }
 @Synchronized fun save() {
  val a = JSONArray()
  segs.forEach { a.put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("en", it.en).put("zh", it.zh)) }
  val tmp = File(file.parentFile, file.name + ".tmp")
  tmp.writeText(JSONObject().put("id", id).put("segs", a).toString())
  tmp.renameTo(file)
 }
 fun delete() { file.delete() }
 companion object {
  private fun dir(c: Context) = File(c.filesDir, "sessions").also { it.mkdirs() }
  fun create(c: Context): Session { val id = System.currentTimeMillis(); return Session(id, File(dir(c), "$id.json")) }
  fun list(c: Context): List<Session> = (dir(c).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
   try {
    val o = JSONObject(f.readText()); val s = Session(o.getLong("id"), f); val a = o.getJSONArray("segs")
    for (i in 0 until a.length()) { val j = a.getJSONObject(i); s.segs.add(Seg(j.getLong("s"), j.getLong("e"), j.getString("en"), j.optString("zh"))) }
    s
   } catch (e: Exception) { null }
  }.sortedByDescending { it.id }
 }
}

/**
 * Builds transcript sentences from Vosk word timestamps. Sentences end at a pause longer than GAP seconds,
 * at a natural (silence) final result, or when very long. Forced display splits (finalResult mid-speech)
 * do NOT end a sentence, so no words are lost or cut in the transcript.
 */
class TranscriptBuilder(private val onSentence: (Seg) -> Unit) {
 private class W(val t: String, val s: Double, val e: Double)
 private val pending = ArrayList<W>()
 private var lastEnd = -1.0
 private var base = 0.0
 companion object { const val GAP = 0.6; const val MAX_WORDS = 40 }

 /** [json] is a Vosk result/finalResult JSON; [audioSec] the audio time fed so far; [natural] true if it ended on silence. */
 fun add(json: String, audioSec: Double, natural: Boolean) {
  val o = JSONObject(json)
  val arr = o.optJSONArray("result")
  val words = ArrayList<W>()
  if (arr != null) for (i in 0 until arr.length()) { val w = arr.getJSONObject(i); val t = w.optString("word"); if (t.isNotBlank()) words.add(W(t, w.optDouble("start"), w.optDouble("end"))) }
  if (words.isEmpty()) {
   o.optString("text").trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { words.add(W(it, audioSec, audioSec)) }
  }
  if (words.isNotEmpty()) {
   // Vosk times are normally continuous across results; if they restart, re-anchor to the audio clock.
   if (lastEnd >= 0 && words.first().s + base < lastEnd - 0.3) base = audioSec - words.last().e
   for (w in words) {
    val s = w.s + base; val e = maxOf(w.e + base, s)
    if (pending.isNotEmpty() && (s - pending.last().e > GAP || pending.size >= MAX_WORDS)) flush()
    pending.add(W(w.t, s, e)); lastEnd = e
   }
  }
  if (natural) flush()
 }

 fun flush() {
  if (pending.isEmpty()) return
  val text = pending.joinToString(" ") { it.t }.replaceFirstChar { it.uppercase() }
  val seg = Seg((pending.first().s * 1000).toLong(), (pending.last().e * 1000).toLong(), text)
  pending.clear()
  onSentence(seg)
 }
}

object Exporter {
 val formats = arrayOf("中英双语", "中文", "英文")
 val types = arrayOf("txt", "srt")
 private fun ts(ms: Long): String { val h = ms / 3600000; val m = ms / 60000 % 60; val s = ms / 1000 % 60; return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000) }
 private fun zhOf(s: Seg) = s.zh.ifBlank { "（未翻译）" }

 fun build(session: Session, format: String, type: String): String {
  val segs = session.snapshot(); val sb = StringBuilder()
  if (type == "srt") {
   segs.forEachIndexed { i, s ->
    var end = maxOf(s.endMs, s.startMs + 800); if (i + 1 < segs.size && segs[i + 1].startMs > s.startMs + 200) end = minOf(end, segs[i + 1].startMs)
    sb.append(i + 1).append('\n').append(ts(s.startMs)).append(" --> ").append(ts(end)).append('\n')
    when (format) { "中文" -> sb.append(zhOf(s)); "英文" -> sb.append(s.en); else -> sb.append(s.en).append('\n').append(zhOf(s)) }
    sb.append("\n\n")
   }
  } else {
   sb.append("X 实时翻译逐字稿  ").append(session.title).append("  （").append(format).append("）\n\n")
   segs.forEach { s ->
    when (format) { "中文" -> sb.append(zhOf(s)).append('\n'); "英文" -> sb.append(s.en).append('\n'); else -> sb.append(s.en).append('\n').append(zhOf(s)).append("\n\n") }
   }
  }
  return sb.toString()
 }

 fun fileName(session: Session, format: String, type: String): String {
  val tag = when (format) { "中文" -> "中文"; "英文" -> "英文"; else -> "中英" }
  return "X逐字稿_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(session.id)) + "_$tag.$type"
 }

 /** Saves into Downloads/X实时翻译 via MediaStore (no storage permission needed). Returns display path or throws. */
 fun saveToDownloads(c: Context, name: String, text: String, type: String): String {
  val v = ContentValues().apply {
   put(MediaStore.MediaColumns.DISPLAY_NAME, name)
   put(MediaStore.MediaColumns.MIME_TYPE, if (type == "srt") "application/x-subrip" else "text/plain")
   put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/X实时翻译")
  }
  val uri: Uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("无法创建文件")
  c.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("无法写入文件")
  return "下载/X实时翻译/$name"
 }
}
