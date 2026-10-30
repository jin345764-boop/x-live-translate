package com.xlivetranslate.app

import android.Manifest
import android.app.*
import android.os.*
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*

class MainActivity: Activity() {
 private lateinit var cfg: Config
 private lateinit var status: TextView
 private lateinit var permissions: TextView
 private val handler=Handler(Looper.getMainLooper())
 private val refresh=object: Runnable { override fun run() {
  status.text="${Status.message}\n英文模型：${if(Config.model(this@MainActivity).exists()) "已安装" else "未安装"}"
  permissions.text="悬浮窗：${if(Settings.canDrawOverlays(this@MainActivity)) "已授权" else "未授权"}　麦克风：${if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) "已授权" else "未授权"}\n通知：${if(getSystemService(NotificationManager::class.java).areNotificationsEnabled()) "已开启" else "未开启"}；录屏授权每次开始时申请"
  handler.postDelayed(this,500)
 } }
 override fun onCreate(state: Bundle?) { super.onCreate(state); cfg=Config(this); main() }
 private fun layout(title: String): LinearLayout {
  val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,24,28,24) }
  val scroll=ScrollView(this); scroll.addView(column); setContentView(scroll)
  column.addView(TextView(this).apply { text=title; textSize=26f; setPadding(0,8,0,24) }); return column
 }
 private fun button(column: LinearLayout, title: String, action: ()->Unit) { column.addView(Button(this).apply { text=title; setOnClickListener { action() } }) }
 private fun main() {
  val col=layout("X 实时翻译")
  col.addView(TextView(this).apply { text="将英文视频语音显示为双语悬浮字幕。请先安装英文模型并完成授权。" })
  permissions=TextView(this); col.addView(permissions)
  button(col,"1 · 授予悬浮窗权限") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
  button(col,"2 · 授予麦克风与通知权限") { requestPermissions(if(Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO),1) }
  button(col,"通知系统设置") { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)) }
  status=TextView(this).apply { textSize=17f; setPadding(0,20,0,20) }; col.addView(status)
  button(col,"3 · 下载英文小模型（约 40 MB）") { ModelDownload.start(applicationContext) }
  button(col,"开始翻译 / 申请录屏授权") { begin() }
  button(col,"停止翻译") { stopService(Intent(this,CaptureService::class.java)) }
  button(col,"切换到麦克风模式并开始") { switchToMic() }
  button(col,"设置") { settings() }
  handler.removeCallbacks(refresh); handler.post(refresh)
 }
 private fun switchToMic() {
  cfg.set("source","麦克风")
  stopService(Intent(this,CaptureService::class.java))
  var attempts=0
  val waitForStop=object: Runnable { override fun run() {
   if(isFinishing || isDestroyed) return
   if(!Status.running) begin() else if(attempts++<25) handler.postDelayed(this,200) else toast("服务停止较慢，请稍后点击开始")
  } }
  handler.postDelayed(waitForStop,200)
 }
 private fun begin() {
  if(Status.running) { toast("服务正在运行，请先停止后再切换配置"); return }
  if(Status.downloading) { toast("请等待模型安装完成"); return }
  if(!Config.model(this).exists()) { toast("请先下载英文模型"); return }
  if(!Settings.canDrawOverlays(this)) { toast("请先授予悬浮窗权限"); return }
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { toast("请先授予麦克风权限"); return }
  if(cfg.source=="麦克风") launch(null,0) else startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),20)
 }
 private fun launch(data: Intent?, code: Int) {
  try { startForegroundService(Intent(this,CaptureService::class.java).putExtra("projection",data).putExtra("code",code)) } catch(e:Exception) { toast("启动失败：${e.message}") }
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==20 && resultCode==RESULT_OK && data!=null) launch(data,resultCode) else if(requestCode==20) toast("已取消录屏授权") }
 private fun toast(s:String) { Toast.makeText(this,s,Toast.LENGTH_LONG).show() }
 private fun settings() {
  handler.removeCallbacks(refresh)
  val col=layout("设置")
  col.addView(TextView(this).apply { text="停止后重新开始，所有设置才会生效。API 密钥保存在应用私有 SharedPreferences，卸载会清除。" })
  fun choice(key:String,title:String,values:Array<String>,default:String) {
   col.addView(TextView(this).apply { text=title })
   col.addView(Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,values); setSelection(values.indexOf(cfg.get(key,default)).coerceAtLeast(0)); onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
    override fun onNothingSelected(p:AdapterView<*>?) {}
    override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long) { cfg.set(key,values[pos]) }
   } })
  }
  choice("source","音频来源",arrayOf("内部音频","麦克风"),"内部音频")
  choice("engine","翻译引擎",arrayOf("ML Kit 离线","OpenAI 兼容 API"),"ML Kit 离线")
  col.addView(TextView(this).apply { text="识别引擎：Vosk 离线（英语，16 kHz）" })
  val fields=mutableListOf<Pair<String,EditText>>()
  fun field(key:String,title:String,default:String="",secret:Boolean=false) {
   col.addView(TextView(this).apply { text=title })
   val edit=EditText(this).apply { setText(cfg.get(key,default)); inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT; isSingleLine=true }
   fields.add(key to edit); col.addView(edit)
  }
  field("size","字幕字号（12–36）","20")
  field("opacity","背景不透明度（20–100）","80")
  field("base","API Base URL（HTTPS，通常含 /v1）")
  field("model","API 模型名称")
  field("key","API Key（本地接口可留空）",secret=true)
  field("modelUrl","模型 ZIP 自定义 HTTPS 源；失败后尝试官方源",cfg.modelUrl)
  button(col,"保存并返回") { fields.forEach { (k,v)->cfg.set(k,v.text.toString().trim()) }; main() }
 }
 override fun onDestroy() { handler.removeCallbacks(refresh); super.onDestroy() }
}
