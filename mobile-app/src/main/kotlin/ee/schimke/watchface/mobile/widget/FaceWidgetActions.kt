package ee.schimke.watchface.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The face widget's private actions: the minute tick ([FaceWidgetTicker]) and the launcher's answer
 * to a pin request ([FaceWidgetProvider.pin]). Not exported, so only this app's own pending intents
 * reach it: another app can't set a widget's face or make it render.
 */
class FaceWidgetActions : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      FaceWidgetTicker.ACTION_TICK ->
        FaceWidgetProvider.refresh(
          this,
          context,
          FaceWidgetProvider.allIds(context),
          network = false,
        )
      FaceWidgetProvider.ACTION_PINNED -> {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val face = FaceWidgetProvider.faceOf(intent) ?: return
        if (id == -1) return
        FaceWidgetStore(context).setFace(id, face)
        FaceWidgetTicker.start(context)
        FaceWidgetProvider.refresh(this, context, intArrayOf(id), network = true)
      }
    }
  }
}
