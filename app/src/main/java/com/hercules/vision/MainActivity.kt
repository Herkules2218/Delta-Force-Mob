package com.hercules.vision
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
class MainActivity : Activity() {
 private val prefs by lazy { getSharedPreferences("vision", MODE_PRIVATE) }
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val page=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(38,52,38,28); setBackgroundColor(Color.rgb(11,19,32)) }
  fun label(s:String,size:Float=17f)=TextView(this).apply { text=s; textSize=size; setTextColor(Color.WHITE); setPadding(0,12,0,12) }
  page.addView(label("✛ HERCULES VISION",27f)); page.addView(label("v0.1 • Допоміжний приціл без Root",15f))
  val status=label("Дозвіл на відображення: перевірка…",14f);page.addView(status)
  page.addView(Button(this).apply { text="Надати дозвіл поверх інших програм";setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } })
  page.addView(label("Розмір прицілу"));val size=SeekBar(this).apply { max=100; progress=prefs.getInt("size",45) };page.addView(size)
  page.addView(label("Прозорість"));val alpha=SeekBar(this).apply { max=100; progress=prefs.getInt("alpha",90) };page.addView(alpha)
  fun update(){prefs.edit().putInt("size",size.progress).putInt("alpha",alpha.progress).apply();sendBroadcast(Intent("com.hercules.vision.UPDATE").setPackage(packageName))}
  val listener=object:SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s:SeekBar?,p:Int,fromUser:Boolean){if(fromUser)update()};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){} }
  size.setOnSeekBarChangeListener(listener);alpha.setOnSeekBarChangeListener(listener)
  page.addView(Button(this).apply { text="Увімкнути приціл";setOnClickListener { if(Settings.canDrawOverlays(this@MainActivity)) startService(Intent(this@MainActivity,CrosshairService::class.java)) else Toast.makeText(this@MainActivity,"Спочатку надай дозвіл",Toast.LENGTH_LONG).show() } })
  page.addView(Button(this).apply { text="Вимкнути приціл";setOnClickListener { stopService(Intent(this@MainActivity,CrosshairService::class.java)) } })
  page.addView(label("Приціл можна пересувати пальцем. Ця версія не зчитує екран гри та не керує її камерою.",14f))
  val scroll=ScrollView(this);scroll.addView(page);setContentView(scroll)
  status.text=if(Settings.canDrawOverlays(this))"Дозвіл на відображення: надано" else "Дозвіл на відображення: не надано"
 }
}