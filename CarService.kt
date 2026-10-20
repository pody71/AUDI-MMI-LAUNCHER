package com.pody.mmi

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.*
import java.io.IOException
import java.util.UUID

object Obd { @Volatile var speed = 0; @Volatile var rpm = 0; @Volatile var temp = 0
    @Volatile var volt = "--"; @Volatile var amb = 99
    @Volatile var status = "OBD desconectado"; @Volatile var on = false }

class GaugeView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val tick = object : Runnable { override fun run() { invalidate(); postDelayed(this, 150) } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((300 * d).toInt(), (130 * d).toInt())
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL; p.color = 0xCC000000.toInt(); cv.drawRoundRect(0f, 0f, w, h, 24f, 24f, p)
        val n = 24; val segW = (w - 24 * d) / n; val lit = (Obd.rpm / 7000f * n).toInt()
        for (k in 0 until n) {
            p.color = if (k >= lit) 0x33FFFFFF else if (k < 14) 0xFF22C55E.toInt() else if (k < 19) 0xFFFACC15.toInt() else 0xFFEF4444.toInt()
            cv.drawRoundRect(12 * d + k * segW, 10 * d, 12 * d + (k + 1) * segW - 3 * d, 26 * d, 4f, 4f, p)
        }
        p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        p.textSize = 72 * d; cv.drawText("${Obd.speed}", w / 2, 90 * d, p)
        p.textSize = 13 * d; p.color = 0xFF9CA3AF.toInt(); cv.drawText("km/h", w / 2, 106 * d, p)
        p.textSize = 14 * d; p.textAlign = Paint.Align.LEFT; p.color = Color.WHITE; cv.drawText("${Obd.rpm} rpm", 14 * d, 110 * d, p)
        p.textAlign = Paint.Align.RIGHT; p.color = if (Obd.temp > 105) 0xFFEF4444.toInt() else Color.WHITE
        cv.drawText("${Obd.temp}°C", w - 14 * d, 110 * d, p)
    }
}

class CarService : Service() {
    private var wm: WindowManager? = null
    private var gauge: GaugeView? = null
    @Volatile private var running = false
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val nb = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("mmi", "MMI", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "mmi") } else Notification.Builder(this)
        startForeground(1, nb.setContentTitle("Velocímetro OBD activo").setSmallIcon(android.R.drawable.ic_menu_compass).build())
        if (gauge == null) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            gauge = GaugeView(this)
            val lp = WindowManager.LayoutParams(-2, -2,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START; lp.x = 16; lp.y = 16
            try { wm!!.addView(gauge, lp) } catch (e: Exception) { Obd.status = "Falta permiso de superposición" }
        }
        if (!running) { running = true; Obd.on = true; Thread { loop() }.start() }
        return START_STICKY
    }

    private fun pid(r: String, tag: String, n: Int): Int? {
        val i = r.indexOf(tag); if (i < 0 || r.length < i + 4 + n * 2) return null
        return r.substring(i + 4, i + 4 + n * 2).toInt(16)
    }

    private fun loop() {
        while (running) {
            try {
                val ad = BluetoothAdapter.getDefaultAdapter()
                val devs = ad?.bondedDevices?.toList() ?: emptyList()
                val dev = devs.firstOrNull { val n = (it.name ?: "").lowercase(); n.contains("obd") || n.contains("elm") || n.contains("link") } ?: devs.firstOrNull()
                if (dev == null) { Obd.status = "Empareja el ELM327 en Ajustes > Bluetooth"; Thread.sleep(5000); continue }
                ad.cancelDiscovery()
                val s = dev.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                s.connect()
                val inp = s.inputStream; val out = s.outputStream
                fun cmd(c: String): String {
                    out.write((c + "\r").toByteArray()); out.flush()
                    val sb = StringBuilder()
                    while (true) { val b = inp.read(); if (b < 0) throw IOException(); if (b == '>'.code) break; sb.append(b.toChar()) }
                    return sb.toString().replace(Regex("\\s"), "")
                }
                for (c in listOf("ATZ", "ATE0", "ATL0", "ATSP0")) cmd(c)
                Obd.status = "OBD conectado"
                var k = 0
                while (running) {
                    pid(cmd("010D"), "410D", 1)?.let { Obd.speed = it }
                    pid(cmd("010C"), "410C", 2)?.let { Obd.rpm = it / 4 }
                    if (k++ % 10 == 0) {
                        pid(cmd("0105"), "4105", 1)?.let { Obd.temp = it - 40 }
                        pid(cmd("0146"), "4146", 1)?.let { Obd.amb = it - 40 }
                        Regex("[0-9]+\\.?[0-9]*").find(cmd("ATRV"))?.value?.let { Obd.volt = it }
                    }
                }
                s.close()
            } catch (e: Exception) { Obd.status = "OBD reconectando…"; Thread.sleep(3000) }
        }
    }

    override fun onDestroy() {
        running = false; Obd.on = false; Obd.status = "OBD desconectado"
        gauge?.let { try { wm?.removeView(it) } catch (_: Exception) {} }; gauge = null
        super.onDestroy()
    }
}
