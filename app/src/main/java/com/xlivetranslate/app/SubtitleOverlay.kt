package com.xlivetranslate.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.*
import android.widget.*

class SubtitleOverlay(private val c:Context, cfg:Config, stop:()->Unit, mic:()->Unit) {
 private val wm=c.getSystemService(WindowManager::class.java)
 private val root=LinearLayout(c).apply { orientation=LinearLayout.VERTICAL; setPadding(18,10,18,12); setBackgroundColor(Color.argb((cfg.get("opacity","80").toIntOrNull()?:80).coerceIn(20,100)*255/100,15,22,32)) }
 private fun line(color:Int,lines:Int)=TextView(c).apply { setTextColor(color);maxLines=lines;ellipsize=android.text.TextUtils.TruncateAt.END }
 private val prevEn=line(Color.argb(110,255,255,255),1)
 private val prevZh=line(Color.argb(110,110,230,200),1)
 private val english=line(Color.WHITE,2)
 private val chinese=line(Color.rgb(110,230,200),2)
 private var size=(cfg.get("size","20").toFloatOrNull()?:20f).coerceIn(12f,36f)
 private val params=WindowManager.LayoutParams((c.resources.displayMetrics.widthPixels*0.94).toInt(),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=(c.resources.displayMetrics.widthPixels*0.03).toInt(); y=(c.resources.displayMetrics.heightPixels*0.70).toInt() }
 init {
  val controls=LinearLayout(c)
  fun b(label:String,action:()->Unit) { controls.addView(Button(c).apply { text=label; textSize=12f; setPadding(3,0,3,0); minimumWidth=0; minWidth=0; setOnClickListener { action() } },LinearLayout.LayoutParams(0,44.dp(c),1f)) }
  var visible=true
  b("隐藏") { visible=!visible; english.visibility=if(visible) View.VISIBLE else View.GONE; chinese.visibility=english.visibility; prevEn.visibility=english.visibility; prevZh.visibility=english.visibility }
  b("A−") { size=(size-2).coerceAtLeast(12f); font() }
  b("A+") { size=(size+2).coerceAtMost(36f); font() }
  b("麦克风") { mic() }; b("停止") { stop() }
  val handle=TextView(c).apply { text="⠿  X 实时翻译 · 拖动此处"; setTextColor(Color.LTGRAY); setPadding(0,10,0,10) }
  var downX=0f; var downY=0f; var x=0; var y=0
  handle.setOnTouchListener { _,e -> when(e.action) { MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; x=params.x;y=params.y }; MotionEvent.ACTION_MOVE -> { params.x=(x+e.rawX-downX).toInt();params.y=(y+e.rawY-downY).toInt().coerceAtLeast(0);wm.updateViewLayout(root,params) } };true }
  root.addView(handle);root.addView(controls);root.addView(prevEn);root.addView(prevZh);root.addView(english);root.addView(chinese);font();wm.addView(root,params)
 }
 private fun font() { english.textSize=size; chinese.textSize=size; prevEn.textSize=size*0.7f; prevZh.textSize=size*0.7f }
 private fun tail(t:String,n:Int)=if(t.length>n) "…"+t.takeLast(n) else t
 fun setPrev(en:String,zh:String) { prevEn.text=tail(en,90);prevZh.text=tail(zh,45) }
 fun show(en0:String,zh0:String) { val en=tail(en0,110);val zh=tail(zh0,55); if(english.text.toString()!=en) english.text=en;if(chinese.text.toString()!=zh) chinese.text=zh }
 fun close() { if(root.isAttachedToWindow) wm.removeView(root) }
 private fun Int.dp(c:Context)=(this*c.resources.displayMetrics.density).toInt()
}
