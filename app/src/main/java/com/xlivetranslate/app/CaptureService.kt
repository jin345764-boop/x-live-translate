package com.xlivetranslate.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.*
import android.os.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class CaptureService: Service() {
 private val main=Handler(Looper.getMainLooper())
 private var overlay:SubtitleOverlay?=null
 private var projection:MediaProjection?=null
 @Volatile private var active=false
 @Volatile private var record:AudioRecord?=null
 private var audioThread:Thread?=null
 private var translator:Translation?=null
 private val generation=AtomicLong()
 private val queue=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(1),ThreadPoolExecutor.DiscardOldestPolicy())
 private var en="正在准备英文识别…"
 private var zh=""
 private var lastFinal=""
 private var session:Session?=null
 private var tBuilder:TranscriptBuilder?=null
 private var tTranslator:Translation?=null
 private val tExec=Executors.newSingleThreadExecutor()
 private var shownId=0L
 private var hasTranslation=false
 private companion object { const val PARTIAL_INTERVAL_MS=450L;const val MAX_UTTERANCE_MS=3500L;const val MAX_UTTERANCE_WORDS=13 }
 override fun onBind(i:Intent?) : IBinder? = null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="stop") { stopSelf();return START_NOT_STICKY }
  if(active || intent==null) { if(intent==null) stopSelf(); return START_NOT_STICKY }
  val cfg=Config(this); val mic=cfg.source=="麦克风"
  try {
   val nm=getSystemService(NotificationManager::class.java)
   nm.createNotificationChannel(NotificationChannel("capture","实时翻译状态",NotificationManager.IMPORTANCE_LOW))
   startForeground(1,notification("正在启动${if(mic) "麦克风" else "内部音频"}捕获"),if(mic) { if(Build.VERSION.SDK_INT>=30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0 } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
   active=true;Status.running=true
   overlay=SubtitleOverlay(this,cfg,{stopSelf()},{
    startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    update("请回主界面点击“切换到麦克风模式并开始”")
   })
   if(!mic) {
    @Suppress("DEPRECATION") val data=intent.getParcelableExtra<Intent>("projection") ?: error("缺少录屏授权，请重新开始")
    projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("code",0),data)
    projection!!.registerCallback(object:MediaProjection.Callback() { override fun onStop() { if(active) { update("录屏授权已终止");stopSelf() } } },main)
   }
   translator=Translation(cfg)
   tTranslator=Translation(cfg)
   val sess=Session.create(this);session=sess
   tBuilder=TranscriptBuilder { seg -> transcribe(sess,seg) }
   audioThread=Thread({ capture(mic) },"audio-vosk").also { it.start() }
  } catch(e:Exception) { update("启动失败：${e.message}");stopSelf() }
  return START_NOT_STICKY
 }
 private fun notification(text:String):Notification {
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val stop=PendingIntent.getService(this,1,Intent(this,CaptureService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
  return Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("X 实时翻译").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"停止",stop).build()).build()
 }
 private fun update(s:String) {
  Status.message=s
  main.post { if(active) { zh=s;hasTranslation=false;overlay?.show(en,zh);getSystemService(NotificationManager::class.java).notify(1,notification(s)) } }
 }
 private fun capture(mic:Boolean) {
  var model:Model?=null;var recognizer:Recognizer?=null;var local:AudioRecord?=null
  try {
   model=Model(Config.model(this).absolutePath);recognizer=Recognizer(model,16000f);recognizer.setWords(true)
   val format=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
   val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
   check(minimum>0) { "设备不支持 16kHz 单声道录音" }
   if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) throw SecurityException("麦克风权限已被撤销，请重新授权")
   val builder=AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum*2,6400))
   if(mic) builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
   else builder.setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(projection!!).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build())
   local=builder.build();record=local
   check(local.state==AudioRecord.STATE_INITIALIZED) { "音频初始化失败，可尝试麦克风模式" }
   if(!active) return
   local.startRecording();check(local.recordingState==AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音" }
   update("正在聆听英语；${if(mic) "请使用外放" else "请播放 X 视频"}")
   val data=ByteArray(3200)
   var lastSound=SystemClock.elapsedRealtime();var lastPartial=0L;var warned=false;var errors=0
   var samples=0L;var uttStart=0L;var lastSubmitText="";var lastSubmitWords=0;var lastSubmitAt=0L
   while(active) {
    val n=local.read(data,0,data.size,AudioRecord.READ_BLOCKING)
    if(!active) break
    if(n<0) { errors++;if(errors>5) error("音频读取失败（$n），请停止后重试或切换麦克风");Thread.sleep(40);continue }
    errors=0
    samples+=n/2
    val audioSec=samples/16000.0
    val now=SystemClock.elapsedRealtime()
    var energy=0L
    for(i in 0 until n-1 step 2) { val sample=((data[i].toInt() and 255) or (data[i+1].toInt() shl 8)).toShort().toInt(); energy+=abs(sample) }
    if(n>0 && energy/(n/2).coerceAtLeast(1)>70) {
     lastSound=now
     if(warned) { warned=false;update("已检测到声音，正在识别…") }
    }
    if(now-lastSound>10000 && !warned) { warned=true;update(if(mic) "持续无声：请检查麦克风权限、外放音量及距离" else "持续无声：X 可能禁止音频捕获，或视频已静音。点击“麦克风”回主界面切换模式") }
    if(n==0) { Thread.sleep(20);continue }
    if(recognizer.acceptWaveForm(data,n)) {
     val json=recognizer.result;val text=JSONObject(json).optString("text")
     try { tBuilder?.add(json,audioSec,true) } catch(_:Exception) {}
     if(text.isNotBlank() && text!=lastFinal) { lastFinal=text;submit(text) }
     uttStart=0L;lastSubmitText="";lastSubmitWords=0
    } else if(now-lastPartial>=150) {
     val text=JSONObject(recognizer.partialResult).optString("partial");lastPartial=now
     if(text.isNotBlank()) {
      if(uttStart==0L) { uttStart=now;if(lastFinal.isNotBlank()) rollover(generation.get()) }
      val words=text.trim().split(Regex("\\s+")).size
      if(now-uttStart>=MAX_UTTERANCE_MS || words>=MAX_UTTERANCE_WORDS) {
       // Long speech without a pause: force the recognizer to commit what it has so far.
       val fjson=recognizer.finalResult
       try { tBuilder?.add(fjson,audioSec,false) } catch(_:Exception) {}
       val forced=JSONObject(fjson).optString("text").ifBlank { text }
       if(forced!=lastFinal) { lastFinal=forced;submit(forced) }
       uttStart=0L;lastSubmitText="";lastSubmitWords=0
      } else {
       main.post { if(active) { en=text;overlay?.show(en,zh) } }
       // Live translation of the partial text: throttled, only when changed and grown enough.
       if(text!=lastSubmitText && now-lastSubmitAt>=PARTIAL_INTERVAL_MS && (words-lastSubmitWords>=1 || now-lastSubmitAt>=900)) {
        lastSubmitText=text;lastSubmitWords=words;lastSubmitAt=now;submit(text)
       }
      }
     }
    }
   }
   // Stopped: flush the last words so the transcript is complete.
   try { val last=recognizer.finalResult;tBuilder?.add(last,samples/16000.0,true) } catch(_:Exception) {}
  } catch(e:Exception) { if(active) { update("捕获/识别失败：${e.message}"); main.post { stopSelf() } } }
  finally { try { tBuilder?.flush() } catch(_:Exception) {};try { local?.stop() } catch(_:Exception) {};local?.release();record=null;recognizer?.close();model?.close() }
 }
 // Transcript sentences (pause-based) are translated in full, in the background, in order.
 private fun transcribe(sess:Session,seg:Seg) {
  val idx=sess.add(seg)
  try { tExec.execute {
   for(attempt in 0..2) {
    try { val z=tTranslator?.translate(seg.en) ?: return@execute;sess.setZh(idx,z);return@execute } catch(e:Exception) { try { Thread.sleep(1500L*(attempt+1)) } catch(_:InterruptedException) { return@execute } }
   }
  } } catch(_:RejectedExecutionException) {}
 }
 // Start of a new sentence: the finished one moves to the faded line above; current lines are cleared.
 private fun rollover(rid:Long) {
  main.post { if(active) { shownId=maxOf(shownId,rid);overlay?.setPrev(en,if(hasTranslation) zh else "");en="";zh="";hasTranslation=false;overlay?.show(en,zh) } }
 }
 private fun submit(text:String) {
  val id=generation.incrementAndGet()
  main.post { if(active) { en=text;if(!hasTranslation) zh="正在翻译…";overlay?.show(en,zh) } }
  queue.execute {
   try {
    val translated=translator?.translate(text) ?: return@execute
    // Show any result newer than what is on screen, so slow translations never starve the display;
    // older (stale) results that arrive late are ignored.
    main.post { if(active && id>shownId) { shownId=id;zh=translated;hasTranslation=true;overlay?.show(en,zh);Status.message="正在翻译：$text" } }
   } catch(e:Exception) { main.post { if(active && id>=shownId) update("翻译失败：${e.message}") } }
  }
 }
 override fun onDestroy() {
  active=false;Status.running=false;generation.incrementAndGet()
  try { record?.stop() } catch(_:Exception) {}
  projection?.stop();projection=null
  overlay?.close();overlay=null
  queue.shutdownNow()
  // Let the worker release its recognizer/model; never free native handles while reading.
  val engine=translator;val tEngine=tTranslator;val sess=session
  Thread {
   try { audioThread?.join(3000);queue.awaitTermination(3,TimeUnit.SECONDS) } catch(_:InterruptedException) {}
   engine?.close()
   // Let pending transcript translations finish (up to 60 s) before releasing the translator.
   tExec.shutdown()
   try { tExec.awaitTermination(60,TimeUnit.SECONDS) } catch(_:InterruptedException) {}
   tEngine?.close()
   if(sess!=null && sess.count()==0) sess.delete()
  }.start()
  stopForeground(STOP_FOREGROUND_REMOVE)
  super.onDestroy()
 }
}
