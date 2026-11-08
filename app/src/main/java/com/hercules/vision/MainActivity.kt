package com.hercules.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.content.Context
import android.widget.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("vision", MODE_PRIVATE) }
    private var statusText: TextView? = null
    private val requestCapture = 405

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 36, 34, 48)
            setBackgroundColor(Color.rgb(11, 19, 32))
        }
        fun label(value: String, size: Float = 16f): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(Color.WHITE)
                setPadding(0, 10, 0, 10)
            }
        page.addView(label("✛ HERCULES VISION", 27f))
        page.addView(label("v0.2 • Візуальне відстеження маркерів • без Root", 14f))
        statusText = label("Перевіряю дозвіл на Overlay…", 14f).also { page.addView(it) }

        page.addView(Button(this).apply {
            text = "1. Дозволити показ поверх гри"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })

        fun addSlider(title: String, key: String, default: Int) {
            val value = label("$title: $default%")
            val bar = SeekBar(this).apply { max = 100; progress = prefs.getInt(key, default) }
            value.text = "$title: ${bar.progress}%"
            page.addView(value)
            page.addView(bar)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "$title: $progress%"
                    if (fromUser) prefs.edit().putInt(key, progress).apply()
                }
                override fun onStartTrackingTouch(seek: SeekBar?) {}
                override fun onStopTrackingTouch(seek: SeekBar?) {}
            })
        }
        addSlider("Чутливість до червоного", "sensitivity", 60)
        addSlider("Плавність прицілу", "smoothing", 40)
        addSlider("Радіус пошуку (FOV)", "fov", 85)
        addSlider("Прозорість HUD", "alpha", 85)

        page.addView(label("Точка підказки (приблизна):"))
        val point = Spinner(this)
        val points = arrayOf("Голова", "Груди", "Живіт")
        point.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, points)
        point.setSelection(prefs.getInt("aim_point", 1))
        point.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt("aim_point", position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        page.addView(point)

        page.addView(Button(this).apply {
            text = "2. Запустити розпізнавання екрана"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Спочатку дозволь відображення поверх інших програм", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), requestCapture)
            }
        })
        page.addView(Button(this).apply {
            text = "Зупинити відстеження"
            setOnClickListener { stopService(Intent(this@MainActivity, CrosshairService::class.java)) }
        })
        page.addView(label("Червоні маркери: шукаємо. Зелені: ігноруємо. Підказка не переміщує зброю в Delta Force.", 14f))
        page.addView(label("Android запитає дозвіл на захоплення екрана. Під час роботи буде системне сповіщення. Для нового запуску потрібно погодитися знову.", 13f))

        val scroll = ScrollView(this)
        scroll.addView(page)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        statusText?.text = if (Settings.canDrawOverlays(this))
            "✓ Дозвіл на відображення є" else "⚠ Потрібен дозвіл на відображення поверх інших програм"
    }

    @Deprecated("Compatible with existing Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CrosshairService::class.java).apply {
                action = CrosshairService.ACTION_START
                putExtra(CrosshairService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CrosshairService.EXTRA_CAPTURE_DATA, data)
            }
            startForegroundService(service)
            Toast.makeText(this, "Відстеження запускається — повернися у гру", Toast.LENGTH_LONG).show()
        } else if (requestCode == requestCapture) {
            Toast.makeText(this, "Без дозволу Android зчитування екрана не працює", Toast.LENGTH_LONG).show()
        }
    }
}
