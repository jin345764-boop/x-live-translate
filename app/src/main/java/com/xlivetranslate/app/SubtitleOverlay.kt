package com.xlivetranslate.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.*
import android.widget.*

/**
 * Floating caption window. Drag the top handle to move, drag the bottom-right corner (◢) to resize,
 * A−/A+ change the font size. Position, size and font size are remembered in SharedPreferences.
 */
class SubtitleOverlay(private val c:Context, private val cfg:Config, stop:()->Unit, mic:()->Unit) {
 private val wm=c.getSystemService(WindowManager::class.java)
 private val dm=c.resources.displayMetrics
 private val frame=FrameLayout(c)
 private val root=LinearLayout(c).apply { orientation=LinearLayout.VERTICAL; setPadding(18,10,18,12); setBackgroundColor(Color.argb((cfg.get("opacity","80").toIntOrNull()?:80).coerceIn(20,100)*255/100,15,22,32)) }
 private fun line(color:Int,lines:Int)=TextView(c).apply { setTextColor(color);maxLines=lines;ellipsize=android.text.TextUtils.TruncateAt.END }
 private val prevEn=line(Color.argb(110,255,255,255),1)
 private val prevZh=line(Color.argb(110,110,230,200),1)
 private val english=line(Color.WHITE,2)
 private val chinese=line(Color.rgb(110,230,200),2)
 private var size=(cfg.get("size","20").toFloatOrNull()?:20f).coerceIn(12f,36f)
 private val captions=LinearLayout(c).apply { orientation=LinearLayout.VERTICAL }
 private val minW=200.dp(); private val minH=150.dp()
 private fun pref(k:String,d:Int)=cfg.get(k,"").toIntOrNull() ?: d
 private val params=WindowManager.LayoutParams(
  pref("ov_w",(dm.widthPixels*0.94).toInt()).coerceIn(minW,dm.widthPixels),
  pref("ov_h",maxOf(minH,(dm.heightPixels*0.26).toInt())).coerceIn(minH,dm.heightPixels),
  WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply {
   gravity=Gravity.TOP or Gravity.START
   x=pref("ov_x",(dm.widthPixels*0.03).toInt()).coerceIn(0,(dm.widthPixels-minW).coerceAtLeast(0))
   y=pref("ov_y",(dm.heightPixels*0.68).toInt()).coerceIn(0,(dm.heightPixels-minH).coerceAtLeast(0))
  }
 private fun savePos() { cfg.set("ov_x",params.x.toString());cfg.set("ov_y",params.y.toString());cfg.set("ov_w",params.width.toString());cfg.set("ov_h",params.height.toString()) }
 init {
  val controls=LinearLayout(c)
  fun b(label:String,action:()->Unit) { controls.addView(Button(c).apply { text=label; textSize=12f; setPadding(3,0,3,0); minimumWidth=0; minWidth=0; setOnClickListener { action() } },LinearLayout.LayoutParams(0,44.dp(),1f)) }
  var visible=true
  b("隐藏") { visible=!visible; captions.visibility=if(visible) View.VISIBLE else View.GONE }
  b("A−") { size=(size-2).coerceAtLeast(12f); font() }
  b("A+") { size=(size+2).coerceAtMost(40f); font() }
  b("麦克风") { mic() }; b("停止") { stop() }
  val handle=TextView(c).apply { text="⠿  X 实时翻译 · 拖动此处移动"; setTextColor(Color.LTGRAY); setPadding(0,10,0,10) }
  var downX=0f; var downY=0f; var x=0; var y=0
  handle.setOnTouchListener { _,e -> when(e.action) {
   MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; x=params.x;y=params.y }
   MotionEvent.ACTION_MOVE -> { params.x=(x+e.rawX-downX).toInt().coerceIn(0,(dm.widthPixels-60.dp()).coerceAtLeast(0));params.y=(y+e.rawY-downY).toInt().coerceIn(0,(dm.heightPixels-60.dp()).coerceAtLeast(0));wm.updateViewLayout(frame,params) }
   MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> savePos()
  };true }
  captions.addView(prevEn,LinearLayout.LayoutParams(-1,-2));captions.addView(prevZh,LinearLayout.LayoutParams(-1,-2))
  captions.addView(english,LinearLayout.LayoutParams(-1,0,1f));captions.addView(chinese,LinearLayout.LayoutParams(-1,0,1f))
  root.addView(handle);root.addView(controls);root.addView(captions,LinearLayout.LayoutParams(-1,0,1f))
  frame.addView(root,FrameLayout.LayoutParams(-1,-1))
  // Bottom-right corner handle: drag to resize width and height.
  val grip=TextView(c).apply { text="◢"; textSize=22f; setTextColor(Color.WHITE); gravity=Gravity.CENTER; setBackgroundColor(Color.argb(150,60,80,110)) }
  var w0=0;var h0=0
  grip.setOnTouchListener { _,e -> when(e.action) {
   MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; w0=params.width;h0=params.height }
   MotionEvent.ACTION_MOVE -> { params.width=(w0+e.rawX-downX).toInt().coerceIn(minW,(dm.widthPixels-params.x).coerceAtLeast(minW));params.height=(h0+e.rawY-downY).toInt().coerceIn(minH,(dm.heightPixels-params.y).coerceAtLeast(minH));wm.updateViewLayout(frame,params) }
   MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> savePos()
  };true }
  frame.addView(grip,FrameLayout.LayoutParams(44.dp(),44.dp(),Gravity.BOTTOM or Gravity.END))
  // Keep as many caption lines as fit the current height.
  val relayout=View.OnLayoutChangeListener { v,_,t,_,bm,_,ot,_,ob -> if(bm-t!=ob-ot) fit(v as TextView) }
  english.addOnLayoutChangeListener(relayout);chinese.addOnLayoutChangeListener(relayout)
  font();wm.addView(frame,params)
 }
 private fun fit(t:TextView) { val lh=t.lineHeight.coerceAtLeast(1);val n=((t.height-t.paddingTop-t.paddingBottom)/lh).coerceIn(1,8);if(t.maxLines!=n) t.post { t.maxLines=n } }
 private fun font() { english.textSize=size; chinese.textSize=size; prevEn.textSize=size*0.7f; prevZh.textSize=size*0.7f; cfg.set("size",size.toInt().toString()); english.post { fit(english);fit(chinese) } }
 private fun tail(t:String,n:Int)=if(t.length>n) "…"+t.takeLast(n) else t
 fun setPrev(en:String,zh:String) { prevEn.text=tail(en,120);prevZh.text=tail(zh,60) }
 fun show(en0:String,zh0:String) { val en=tail(en0,220);val zh=tail(zh0,110); if(english.text.toString()!=en) english.text=en;if(chinese.text.toString()!=zh) chinese.text=zh }
 fun close() { if(frame.isAttachedToWindow) wm.removeView(frame) }
 private fun Int.dp()=(this*dm.density).toInt()
}
