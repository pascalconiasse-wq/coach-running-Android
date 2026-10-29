package fr.coach.running

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** État partagé entre le service (qui tourne écran éteint) et la page. */
object TrackState {
    val points = ArrayList<DoubleArray>()      // t (ms), lat, lon, altitude, précision, FC
    @Volatile var running = false
    @Volatile var distM = 0.0
    @Volatile var recapM = 0                   // 0 = pas de récap, sinon 1000 ou 5000
    @Volatile var maxPace = 0                  // s/km : alerte si on court plus vite (0 = aucune)
    @Volatile var maxHr = 0                    // bpm : alerte si dépassé (0 = aucune)
    @Volatile var hr = 0
    @Volatile var nextRecap = 0.0
    @Volatile var lastAlertPace = 0L
    @Volatile var lastAlertHr = 0L

    fun reset() {
        synchronized(points) { points.clear() }
        distM = 0.0; hr = 0; nextRecap = 0.0; lastAlertPace = 0L; lastAlertHr = 0L
    }

    @Suppress("DEPRECATION")
    fun buzz(ctx: Context, pattern: LongArray) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) { }
    }
}

/** Service au premier plan : continue d'enregistrer le GPS (et de faire vibrer) quand l'écran est éteint. */
class TrackService : Service() {
    private var lm: LocationManager? = null
    private var wl: PowerManager.WakeLock? = null
    private var last: Location? = null
    private val recent = ArrayList<DoubleArray>()   // t (ms), distance cumulée
    private var lastNotif = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) { onFix(l) }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) { }
        override fun onProviderEnabled(p: String) { }
        override fun onProviderDisabled(p: String) { }
    }

    override fun onBind(i: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("track", "Séance en cours", NotificationManager.IMPORTANCE_LOW))
        val n = notif("Séance en cours")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "coach:track")
            wl?.acquire(6 * 60 * 60 * 1000L)
        } catch (e: Exception) { }
        last = null
        recent.clear()
        TrackState.running = true
        try {
            lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) { }
        return START_NOT_STICKY
    }

    private fun onFix(l: Location) {
        if (!l.hasAccuracy() || l.accuracy > 30f) return
        val prev = last
        if (prev != null) {
            val d = l.distanceTo(prev).toDouble()
            val dt = (l.time - prev.time) / 1000.0
            if (dt <= 0.0 || d < maxOf(3.0, l.accuracy / 4.0) || d / dt > 25.0) return
            TrackState.distM += d
        }
        last = l
        synchronized(TrackState.points) {
            TrackState.points.add(
                doubleArrayOf(
                    l.time.toDouble(), l.latitude, l.longitude,
                    if (l.hasAltitude()) l.altitude else Double.NaN,
                    l.accuracy.toDouble(), TrackState.hr.toDouble()
                )
            )
        }
        val now = System.currentTimeMillis()
        recent.add(doubleArrayOf(l.time.toDouble(), TrackState.distM))
        while (recent.size > 2 && l.time - recent[0][0] > 15000) recent.removeAt(0)

        // Alerte d'allure : plus rapide que la limite fixée pour la séance
        val mp = TrackState.maxPace
        if (mp > 0 && recent.size >= 2) {
            val dt = (recent[recent.size - 1][0] - recent[0][0]) / 1000.0
            val dd = recent[recent.size - 1][1] - recent[0][1]
            if (dt >= 8.0 && dd / dt > 0.6) {
                val pace = dt / dd * 1000.0
                if (pace < mp && now - TrackState.lastAlertPace > 20000) {
                    TrackState.lastAlertPace = now
                    TrackState.buzz(this, longArrayOf(0, 500, 150, 500, 150, 500))
                }
            }
        }
        // Récap tous les 1 ou 5 km : deux vibrations courtes
        val rm = TrackState.recapM
        if (rm > 0 && TrackState.distM >= TrackState.nextRecap) {
            while (TrackState.nextRecap <= TrackState.distM) TrackState.nextRecap += rm
            TrackState.buzz(this, longArrayOf(0, 150, 120, 150))
        }
        if (now - lastNotif > 10000) {
            lastNotif = now
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, notif("Séance en cours · " + String.format("%.2f", TrackState.distM / 1000.0) + " km"))
        }
    }

    private fun notif(text: String): Notification {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "track")
            .setContentTitle("Coach Running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) { }
        TrackState.running = false
        super.onDestroy()
    }
}

/** Cardio Bluetooth : ceinture ou montre qui diffuse la FC (service standard 0x180D). */
class HrClient(
    private val ctx: Context,
    private val onHr: (Int) -> Unit,
    private val onMsg: (String) -> Unit
) {
    private val hrSvc = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val hrChr = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val ccc = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var stopped = false

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onMsg("Recherche Bluetooth impossible (code " + errorCode + ").")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        stopped = false
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val ad = mgr.adapter
        if (ad == null || !ad.isEnabled) { onMsg("Active le Bluetooth du téléphone."); return }
        scanner = ad.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(hrSvc)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        onMsg("Recherche d'une ceinture ou d'une montre qui diffuse la FC…")
        try {
            scanner?.startScan(listOf(filter), settings, scanCb)
        } catch (e: Exception) {
            scanning = false
            onMsg("Recherche Bluetooth impossible : autorise « Appareils à proximité ».")
            return
        }
        handler.postDelayed({
            if (scanning) {
                stopScan()
                onMsg("Aucun appareil trouvé : active la diffusion de la FC sur la montre (ou mets la ceinture), puis réessaie.")
            }
        }, 15000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanning = false
        try { scanner?.stopScan(scanCb) } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun connect(d: BluetoothDevice) {
        onMsg("Connexion à " + (d.name ?: "l'appareil") + "…")
        gatt = d.connectGatt(ctx, false, gattCb)
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !stopped) {
                onMsg("Cardio déconnecté, reconnexion…")
                handler.postDelayed({ try { if (!stopped) g.connect() } catch (e: Exception) { } }, 3000)
            }
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(hrSvc)?.getCharacteristic(hrChr)
            if (c == null) { onMsg("Cet appareil ne diffuse pas la FC."); return }
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(ccc)
            if (d != null) {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
            onMsg("")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            parse(c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            parse(value)
        }
    }

    private fun parse(v: ByteArray?) {
        if (v == null || v.size < 2) return
        val flags = v[0].toInt()
        val bpm = if ((flags and 1) == 0) {
            v[1].toInt() and 0xFF
        } else if (v.size >= 3) {
            (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        } else 0
        if (bpm > 0) onHr(bpm)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopped = true
        stopScan()
        try { gatt?.disconnect() } catch (e: Exception) { }
        try { gatt?.close() } catch (e: Exception) { }
        gatt = null
    }
}

/** Pont JavaScript "AndroidTrack" : démarre/arrête le suivi, relit les points, gère le cardio. */
class TrackBridge(
    private val act: ComponentActivity,
    private val web: WebView,
    private val askPerms: (Array<String>, () -> Unit) -> Unit
) {
    private var hr: HrClient? = null

    private fun msg(t: String) {
        web.post { web.evaluateJavascript("window.onNativeMsg && onNativeMsg(" + JSONObject.quote(t) + ")", null) }
    }

    @JavascriptInterface
    fun start(recapM: Int, maxPace: Int, maxHr: Int) {
        TrackState.reset()
        TrackState.recapM = recapM
        TrackState.maxPace = maxPace
        TrackState.maxHr = maxHr
        TrackState.nextRecap = recapM.toDouble()
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        act.runOnUiThread { askPerms(perms.toTypedArray()) { launch() } }
    }

    private fun launch() {
        val ok = ContextCompat.checkSelfPermission(act, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) { msg("Autorise la position précise pour suivre la séance."); return }
        try {
            ContextCompat.startForegroundService(act, Intent(act, TrackService::class.java))
        } catch (e: Exception) {
            msg("Suivi impossible : " + e.message)
        }
    }

    @JavascriptInterface
    fun stop() {
        act.runOnUiThread {
            try { act.stopService(Intent(act, TrackService::class.java)) } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun setLimits(recapM: Int, maxPace: Int, maxHr: Int) {
        TrackState.recapM = recapM
        TrackState.maxPace = maxPace
        TrackState.maxHr = maxHr
        TrackState.nextRecap = if (recapM > 0) (Math.floor(TrackState.distM / recapM) + 1) * recapM else 0.0
    }

    @JavascriptInterface
    fun running(): Boolean = TrackState.running

    @JavascriptInterface
    fun count(): Int = synchronized(TrackState.points) { TrackState.points.size }

    /** Points depuis l'indice donné : [[t, lat, lon, altitude|null, précision, FC], …] */
    @JavascriptInterface
    fun points(since: Int): String {
        val out = JSONArray()
        synchronized(TrackState.points) {
            var i = if (since < 0) 0 else since
            while (i < TrackState.points.size) {
                val p = TrackState.points[i]
                val a = JSONArray()
                a.put(p[0].toLong()).put(p[1]).put(p[2])
                if (p[3].isNaN()) a.put(JSONObject.NULL) else a.put(p[3])
                a.put(p[4]).put(p[5].toInt())
                out.put(a)
                i++
            }
        }
        return out.toString()
    }

    @JavascriptInterface
    fun clear() { TrackState.reset() }

    @JavascriptInterface
    fun connectHr() {
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        act.runOnUiThread {
            askPerms(perms.toTypedArray()) {
                hr?.stop()
                hr = HrClient(act, { bpm ->
                    TrackState.hr = bpm
                    val mh = TrackState.maxHr
                    val now = System.currentTimeMillis()
                    if (mh > 0 && bpm > mh && now - TrackState.lastAlertHr > 20000) {
                        TrackState.lastAlertHr = now
                        TrackState.buzz(act, longArrayOf(0, 300, 120, 300, 120, 300))
                    }
                    web.post { web.evaluateJavascript("window.onNativeHr && onNativeHr(" + bpm + ")", null) }
                }, { t -> msg(t) })
                hr?.start()
            }
        }
    }

    @JavascriptInterface
    fun disconnectHr() {
        act.runOnUiThread { hr?.stop(); hr = null }
    }
}
