package com.pody.mmi

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.webkit.*
import android.content.ComponentName
import android.media.*
import android.media.session.*
import android.provider.MediaStore
import android.view.KeyEvent
import android.content.pm.PackageManager
import android.graphics.*
import android.speech.tts.TextToSpeech
import android.util.Base64
import org.json.*
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val h = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this); setContentView(web)
        tts = TextToSpeech(this) { tts?.language = java.util.Locale("es", "ES") }
        web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW; setGeolocationEnabled(true) }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(o: String, c: GeolocationPermissions.Callback) = c.invoke(o, true, false)
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb; startActivityForResult(p.createIntent(), 77); return true }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                !(r.url.scheme == "http" || r.url.scheme == "https" || r.url.scheme == "file")
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1)
            if (!Settings.canDrawOverlays(this))
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        h.post(object : Runnable { override fun run() {
            web.evaluateJavascript("window.onObd&&onObd(${Obd.speed},${Obd.rpm},${Obd.temp},'${Obd.status}','${Obd.volt}',${Obd.amb})", null)
            h.postDelayed(this, 1000) } })
    }

    // nav=true -> pantalla completa (navegación). nav=false -> ventana pequeña arriba a la derecha
    private fun launchApp(pkg: String, nav: Boolean) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (nav || Build.VERSION.SDK_INT < 24) { startActivity(i); return }
        val dm = resources.displayMetrics
        i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val o = ActivityOptions.makeBasic().setLaunchBounds(Rect(dm.widthPixels * 62 / 100, dm.heightPixels * 12 / 100, dm.widthPixels, dm.heightPixels * 60 / 100))
        startActivity(i, o.toBundle())
    }

    override fun onActivityResult(r: Int, res: Int, d: Intent?) {
        if (r == 77) { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, d)); fileCb = null }
        else super.onActivityResult(r, res, d) }

    private fun ctrl(): MediaController? = try {
        val l = (getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager).getActiveSessions(ComponentName(this, MediaListener::class.java))
        l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull()
    } catch (e: Exception) { null }

    inner class Bridge {
        @JavascriptInterface fun mediaAccess(): Boolean = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        @JavascriptInterface fun openNotifAccess() { runOnUiThread { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        @JavascriptInterface fun mediaInfo(): String {
            val c = ctrl() ?: return "{}"; val m = c.metadata
            return JSONObject().put("t", m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "").put("a", m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
                .put("pl", c.playbackState?.state == PlaybackState.STATE_PLAYING).toString() }
        @JavascriptInterface fun mediaCmd(x: String) {
            val t = ctrl()?.transportControls
            if (t != null) { val pl = ctrl()?.playbackState?.state == PlaybackState.STATE_PLAYING
                when (x) { "next" -> t.skipToNext(); "prev" -> t.skipToPrevious(); else -> if (pl) t.pause() else t.play() }
            } else {   // sin sesión visible: tecla multimedia (AVRCP -> móvil por Bluetooth)
                val k = when (x) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k)) } }
        @JavascriptInterface fun action(n: String) { runOnUiThread {
            val i = when (n) { "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA); "phone" -> Intent(Intent.ACTION_DIAL)
                "bt" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS); else -> Intent(Settings.ACTION_SETTINGS) }
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} } }
        @JavascriptInterface fun speak(t: String) { tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "a") }
        @JavascriptInterface fun getApps(): String {
            val pm = packageManager; val arr = JSONArray()
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                if (r.activityInfo.packageName == packageName) continue
                val bmp = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888); val d = r.loadIcon(pm)
                d.setBounds(0, 0, 72, 72); d.draw(Canvas(bmp))
                val bo = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, bo)
                arr.put(JSONObject().put("p", r.activityInfo.packageName).put("l", r.loadLabel(pm).toString()).put("i", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)))
            }
            return arr.toString() }
        @JavascriptInterface fun launch(pkg: String, nav: Boolean) { runOnUiThread { launchApp(pkg, nav) } }
        @JavascriptInterface fun toggleOverlay() { runOnUiThread {
            val s = Intent(this@MainActivity, CarService::class.java)
            if (Obd.on) stopService(s) else if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s) } }
    }

    override fun onBackPressed() { if (web.canGoBack()) web.goBack() }
}
