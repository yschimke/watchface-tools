package ee.schimke.watchface.mobile.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Moves the hands: a launcher draws a widget's document as of its last push, evaluating the clock
 * then, so the app pushes each widget's cached document again at the start of every minute. No
 * network, no re-render: the same bytes, a new time.
 *
 * An inexact repeating alarm in RTC (not a wake-up alarm): a sleeping phone's screen is off, so
 * there is nothing to update until it wakes, and the first tick after it does catches up. Inexact
 * alarms can land late under Doze; the widget then shows the time of the last tick that did.
 */
object FaceWidgetTicker {
  const val ACTION_TICK = "ee.schimke.watchface.mobile.TICK"
  private const val MINUTE_MS = 60_000L

  /** The first whole minute after [nowMillis]. */
  fun nextMinute(nowMillis: Long): Long = (nowMillis / MINUTE_MS + 1) * MINUTE_MS

  fun start(context: Context, nowMillis: Long = System.currentTimeMillis()) {
    context
      .getSystemService(AlarmManager::class.java)
      .setRepeating(AlarmManager.RTC, nextMinute(nowMillis), MINUTE_MS, tick(context))
  }

  fun stop(context: Context) {
    context.getSystemService(AlarmManager::class.java).cancel(tick(context))
  }

  private fun tick(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      0,
      Intent(context, FaceWidgetActions::class.java).setAction(ACTION_TICK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
