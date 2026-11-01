package fr.coach.running

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification du dimanche soir : bilan de la charge de la semaine suivante (texte préparé par la page). */
class WeeklyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val text = ctx.getSharedPreferences("weekly", Context.MODE_PRIVATE).getString("text", "") ?: ""
        if (text.isEmpty()) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("weekly", "Bilan hebdomadaire", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 2, open, PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, "weekly")
            .setContentTitle("Coach Running · semaine prochaine")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }
}
