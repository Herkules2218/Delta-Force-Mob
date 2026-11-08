package com.hercules.vision
import android.app.Service
import android.content.*
import android.graphics.*
import android.os.IBinder
import android.view.*
class CrosshairService:Service(){
 private lateinit var wm:WindowManager
 private var view:View?=null
 private lateinit var params:WindowManager.LayoutParams
 private val prefs by lazy { getSharedPreferences("vision",MODE_PRIVATE) }
 private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){view?.invalidate()}}
 override fun onBind(i:Intent?):IBinder?=null
 override fun onCreate(){super.onCreate();wm=getSystemService(WINDOW_SERVICE) as WindowManager
  params=WindowManager.LayoutParams(180,180,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,android.graphics.PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=200;y=400}
  view=object:View(this){val p=Paint(3).apply{color=Color.rgb(75,255,145);strokeWidth=5f;strokeCap=Paint.Cap.ROUND;style=Paint.Style.STROKE}
   override fun onDraw(c:Canvas){super.onDraw(c);val r=18f+prefs.getInt("size",45)*.52f;p.alpha=(prefs.getInt("alpha",90).coerceIn(5,100)*255/100);val x=width/2f;val y=height/2f;c.drawLine(x-r,y,x-9,y,p);c.drawLine(x+9,y,x+r,y,p);c.drawLine(x,y-r,x,y-9,p);c.drawLine(x,y+9,x,y+r,p);c.drawCircle(x,y,2f,p)}
   var downX=0f;var downY=0f;var startX=0;var startY=0
   override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;startX=params.x;startY=params.y;return true};MotionEvent.ACTION_MOVE->{params.x=startX+(e.rawX-downX).toInt();params.y=startY+(e.rawY-downY).toInt();wm.updateViewLayout(this,params);return true};MotionEvent.ACTION_UP->return true};return true}
  }
  wm.addView(view,params)
  val filter=IntentFilter("com.hercules.vision.UPDATE")
  if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(receiver,filter)
 }
 override fun onDestroy(){try{unregisterReceiver(receiver)}catch(_:Exception){};view?.let{wm.removeView(it)};view=null;super.onDestroy()}
}