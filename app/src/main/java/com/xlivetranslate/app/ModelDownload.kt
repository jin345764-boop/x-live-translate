package com.xlivetranslate.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object ModelDownload {
 fun start(c: Context) {
  if (Status.downloading || Status.running) return
  Status.downloading = true
  Thread {
   val zip = File(c.cacheDir,"model.zip")
   val stage = File(c.filesDir,"model-staging")
   try {
    val cfg = Config(c)
    val urls = listOf(cfg.modelUrl, "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").distinct()
    var success = false
    var last: Exception? = null
    for (url in urls) {
     try {
      require(url.startsWith("https://")) { "模型 URL 必须使用 HTTPS" }
      Status.message = "正在连接模型下载源…"
      OkHttpClient.Builder().readTimeout(90,TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).build()).execute().use { r ->
       check(r.isSuccessful) { "下载 HTTP ${r.code}" }
       val body = r.body ?: error("空下载内容")
       body.byteStream().use { input -> zip.outputStream().use { out ->
        val buf = ByteArray(32768); var total = 0L
        while (true) { val n = input.read(buf); if(n<0) break; total += n; check(total < 300L*1024*1024) { "压缩包过大" }; out.write(buf,0,n)
         Status.message = if(body.contentLength()>0) "模型下载 ${total*100/body.contentLength()}%" else "模型下载 ${total/1024} KB"
        }
       } }
      }
      success = true; break
     } catch(e: Exception) { last = e }
    }
    check(success) { last?.message ?: "下载失败" }
    Status.message = "正在解压模型…"; stage.deleteRecursively(); stage.mkdirs()
    var expanded = 0L
    ZipInputStream(zip.inputStream().buffered()).use { input ->
     while(true) {
      val entry = input.nextEntry ?: break
      val target = File(stage,entry.name)
      check(target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "不安全的压缩路径" }
      if(entry.isDirectory) target.mkdirs() else {
       target.parentFile?.mkdirs()
       target.outputStream().use { out -> val buf=ByteArray(32768); while(true) { val n=input.read(buf); if(n<0) break; expanded+=n; check(expanded<600L*1024*1024) { "解压内容过大" }; out.write(buf,0,n) } }
      }
     }
    }
    val root = stage.walkTopDown().firstOrNull { it.isDirectory && File(it,"am/final.mdl").isFile && File(it,"conf/model.conf").isFile } ?: error("不是有效的 Vosk 模型")
    // Validate the complete model before replacing an existing installation.
    org.vosk.Model(root.absolutePath).close()
    val dest = Config.model(c); dest.deleteRecursively(); check(root.renameTo(dest)) { "安装模型失败" }
    Status.message = "英文模型已就绪"
   } catch(e: Exception) { Status.message = "模型下载失败：${e.message}；可在设置更换 HTTPS 下载源" }
   finally { zip.delete(); stage.deleteRecursively(); Status.downloading=false }
  }.start()
 }
}
