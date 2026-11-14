package app.pitstop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.pitstop.agent.*
import java.time.LocalDate

/** Cold start of the head unit. Reuse the real maintenance history and only post while parked. */
class StartupReminder : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !context.getSharedPreferences("pitstop-mode", 0).getBoolean("startup", false)) return
        val saved = AgentStore(context, "history-v3").load()
        val spec = ScheduleCatalog.spec(saved.specId) ?: return
        val sample = saved.samples.maxByOrNull { it.date } ?: return
        if (!saved.setupDone || saved.demo) return
        val today = LocalDate.now()
        val profile = DrivingAnalyzer.analyze(saved.trips, today, sample.km, null)
        val situation = Situation(today, sample.km, saved.samples, spec, saved.items, profile.detected + saved.declared,
            emptyList(), emptyList(), emptyList(), profile.scores)
        val message = when (val action = Planner.decide(situation)) {
            is AgentAction.VisitDay -> "예약해 둔 정비 계획을 확인해 주세요."
            is AgentAction.TooLate -> "${action.rule.name} 정비 시점을 확인해 주세요."
            else -> return
        }
        val car = runCatching { Car.createCar(context) }.getOrNull() ?: return
        val parked = try {
            val manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            manager?.currentCarUxRestrictions?.isRequiresDistractionOptimization == false
        } catch (_: Exception) { false } finally { car.disconnect() }
        if (!parked) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("maintenance", "출발 전 정비 계획", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, Notification.Builder(context, "maintenance").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("피트스톱").setContentText(message).setContentIntent(open).setAutoCancel(true)
            .setTimeoutAfter(60000).build())
    }
}
