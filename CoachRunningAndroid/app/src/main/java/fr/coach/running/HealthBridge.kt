package fr.coach.running

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** Lit les séances de sport dans Health Connect et les envoie à la page (window.onHealthData). */
class HealthBridge(
    private val act: ComponentActivity,
    private val web: WebView,
    private val ask: (Set<String>) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var since = 0L

    private val required = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class)
    )
    private val routesPermission = "android.permission.health.READ_EXERCISE_ROUTES"

    @JavascriptInterface
    fun sync(sinceMs: Long) {
        since = sinceMs
        scope.launch {
            try {
                when (HealthConnectClient.getSdkStatus(act)) {
                    HealthConnectClient.SDK_UNAVAILABLE ->
                        msg("Health Connect n'est pas disponible sur cet appareil.")
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                        msg("Mettez à jour Health Connect dans le Play Store.")
                    else -> {
                        val granted = HealthConnectClient.getOrCreate(act)
                            .permissionController.getGrantedPermissions()
                        if (granted.containsAll(required)) read()
                        else act.runOnUiThread { ask(required + routesPermission) }
                    }
                }
            } catch (e: Exception) {
                msg("Erreur : ${e.message}")
            }
        }
    }

    fun onPermissions(granted: Set<String>) {
        if (granted.containsAll(required)) scope.launch { read() }
        else msg("Autorisation refusée : activez-la dans Health Connect.")
    }

    private suspend fun read() {
        try {
            msg("Lecture de Health Connect…")
            val c = HealthConnectClient.getOrCreate(act)
            val now = Instant.now()
            val from = maxOf(since, now.minus(Duration.ofDays(30)).toEpochMilli())
            val sessions = c.readRecords(
                ReadRecordsRequest(
                    ExerciseSessionRecord::class,
                    TimeRangeFilter.between(Instant.ofEpochMilli(from), now)
                )
            ).records

            val out = JSONArray()
            for (s in sessions) {
                val tr = TimeRangeFilter.between(s.startTime, s.endTime)
                val agg = c.aggregate(
                    AggregateRequest(
                        setOf(
                            DistanceRecord.DISTANCE_TOTAL,
                            ElevationGainedRecord.ELEVATION_GAINED_TOTAL,
                            HeartRateRecord.BPM_AVG
                        ),
                        tr
                    )
                )
                val o = JSONObject()
                o.put("ts", s.startTime.toEpochMilli())
                o.put("sec", Duration.between(s.startTime, s.endTime).seconds)
                o.put("sport", sport(s.exerciseType))
                o.put("m", agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0)
                o.put("gain", agg[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters ?: 0.0)
                o.put("hrAvg", agg[HeartRateRecord.BPM_AVG] ?: 0L)
                // Parcours : disponible seulement si l'appli de la montre l'écrit et si l'accès est accordé.
                val route = (s.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.route
                if (route != null && route.size > 2) {
                    o.put("route", routeJson(route))
                    o.put("hrS", hrJson(c, tr))
                }
                out.put(o)
            }
            send(out.toString())
        } catch (e: Exception) {
            msg("Erreur de lecture : ${e.message}")
        }
    }

    private suspend fun hrJson(c: HealthConnectClient, tr: TimeRangeFilter): JSONArray {
        val all = c.readRecords(ReadRecordsRequest(HeartRateRecord::class, tr)).records.flatMap { it.samples }
        return thin(all.size) { i -> JSONArray().put(all[i].time.toEpochMilli()).put(all[i].beatsPerMinute) }
    }

    private fun routeJson(l: List<ExerciseRoute.Location>): JSONArray =
        thin(l.size) { i ->
            JSONArray()
                .put(l[i].time.toEpochMilli())
                .put(l[i].latitude)
                .put(l[i].longitude)
                .put(l[i].altitude?.inMeters ?: JSONObject.NULL)
        }

    /** Garde environ 300 points au maximum, répartis sur toute la sortie. */
    private fun thin(n: Int, f: (Int) -> JSONArray): JSONArray {
        val a = JSONArray()
        val step = maxOf(1, n / 300)
        var i = 0
        while (i < n) { a.put(f(i)); i += step }
        if (n > 0 && (n - 1) % step != 0) a.put(f(n - 1))
        return a
    }

    private fun sport(t: Int) = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "RUN_ROUTE"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "CYCLING"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "SWIMMING"
        else -> "OTHER"
    }

    private fun msg(t: String) = js("window.onHealthMsg && onHealthMsg(${JSONObject.quote(t)})")
    private fun send(json: String) = js("window.onHealthData(${JSONObject.quote(json)})")
    private fun js(code: String) { web.post { web.evaluateJavascript(code, null) } }
}
