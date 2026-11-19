package ee.schimke.watchface.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import ee.schimke.composeai.rcwidget.launcher.RcPlatformWidgets
import ee.schimke.watchface.mobile.BuildConfig
import ee.schimke.watchface.mobile.MainActivity
import ee.schimke.watchface.mobile.R
import ee.schimke.watchface.mobile.data.FaceRenderer
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * A published watch face on the home screen, as a Remote Compose launcher widget
 * (`RemoteViews.DrawInstructions`, Android 16+; Android 17, API 37, adds system colours).
 *
 * Each widget shows the face chosen for it ([FaceWidgetStore]) at the size the launcher gives it,
 * compiled once per size by the renderer ([FaceRenderer]) and kept on disk. The minute tick
 * ([FaceWidgetTicker]) pushes the kept document again so the hands move; only a new face or a new
 * size touches the network.
 */
class FaceWidgetProvider : AppWidgetProvider() {

  override fun onReceive(context: Context, intent: Intent) {
    // This receiver is exported, as the launcher needs: it takes only the system's widget and
    // clock broadcasts. The app's own actions (the pin callback, the minute tick) go to
    // [FaceWidgetActions], which no other app can reach.
    when (intent.action) {
      Intent.ACTION_TIME_CHANGED,
      Intent.ACTION_TIMEZONE_CHANGED -> refresh(this, context, allIds(context), network = false)
      else -> super.onReceive(context, intent)
    }
  }

  override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
    refresh(this, context, appWidgetIds, network = true)
  }

  override fun onAppWidgetOptionsChanged(
    context: Context,
    manager: AppWidgetManager,
    appWidgetId: Int,
    newOptions: Bundle,
  ) {
    refresh(this, context, intArrayOf(appWidgetId), network = true)
  }

  override fun onEnabled(context: Context) = FaceWidgetTicker.start(context)

  override fun onDisabled(context: Context) = FaceWidgetTicker.stop(context)

  override fun onDeleted(context: Context, appWidgetIds: IntArray) =
    FaceWidgetStore(context).remove(appWidgetIds)

  companion object {
    private const val TAG = "FacesWidget"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val http by lazy {
      OkHttpClient.Builder()
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    }

    /** The side of the square a widget of [widthDp] by [heightDp] draws, in px, in 32 px steps. */
    fun sizePx(widthDp: Int, heightDp: Int, density: Float): Int {
      val side = (min(widthDp, heightDp).coerceAtLeast(1) * density).roundToInt()
      return (((side + 31) / 32) * 32).coerceIn(FaceRenderer.MIN_PX, MAX_WIDGET_PX)
    }

    const val ACTION_PINNED = "ee.schimke.watchface.mobile.PINNED"
    private const val EXTRA_SERVER = "server"
    private const val EXTRA_FACE = "face"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_VERSION = "version"

    /**
     * Asks the launcher to add a widget showing [face]; it shows it once the person accepts. False
     * when the launcher can't pin widgets.
     */
    fun pin(context: Context, face: WidgetFace): Boolean {
      val manager = AppWidgetManager.getInstance(context)
      if (!manager.isRequestPinAppWidgetSupported) return false
      val callback =
        Intent(context, FaceWidgetActions::class.java)
          .setAction(ACTION_PINNED)
          .putExtra(EXTRA_SERVER, face.server)
          .putExtra(EXTRA_FACE, face.faceId)
          .putExtra(EXTRA_TITLE, face.title)
          .putExtra(EXTRA_VERSION, face.version)
      // Mutable: the launcher fills in the new widget's id.
      val pending =
        PendingIntent.getBroadcast(
          context,
          face.faceId.hashCode(),
          callback,
          PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
      return manager.requestPinAppWidget(
        ComponentName(context, FaceWidgetProvider::class.java),
        null,
        pending,
      )
    }

    internal fun faceOf(intent: Intent): WidgetFace? {
      val server = intent.getStringExtra(EXTRA_SERVER) ?: return null
      val face = intent.getStringExtra(EXTRA_FACE) ?: return null
      return WidgetFace(
        server,
        face,
        intent.getStringExtra(EXTRA_TITLE) ?: face,
        intent.getLongExtra(EXTRA_VERSION, 0),
      )
    }

    /** How long after a failed render a tick tries the network again. */
    const val RETRY_MS = 5 * 60_000L

    /** Large enough for a tablet's widget; past it the document's images outgrow a push. */
    const val MAX_WIDGET_PX = 640

    /** Sets widget [widgetId]'s face and draws it, fetching its document if it isn't kept. */
    fun show(context: Context, widgetId: Int, face: WidgetFace) {
      FaceWidgetStore(context).setFace(widgetId, face)
      FaceWidgetTicker.start(context)
      // Through the provider's own update, under goAsync: the configuring activity finishes
      // straight away, and the process must live until the widget is drawn.
      context.sendBroadcast(
        Intent(context, FaceWidgetProvider::class.java)
          .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
          .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId)),
      )
    }

    /** Updates [ids] under [receiver]'s goAsync, so the process lives until they are drawn. */
    internal fun refresh(
      receiver: BroadcastReceiver,
      context: Context,
      ids: IntArray,
      network: Boolean,
    ) {
      if (ids.isEmpty()) return
      val pending = receiver.goAsync()
      val app = context.applicationContext
      scope.launch {
        try {
          // A broadcast has ~10 s; leave the rest for the launcher.
          withTimeoutOrNull(8_000) { lock.withLock { for (id in ids) update(app, id, network) } }
        } finally {
          pending.finish()
        }
      }
    }

    fun allIds(context: Context): IntArray =
      AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, FaceWidgetProvider::class.java))

    private suspend fun update(context: Context, widgetId: Int, network: Boolean) {
      val manager = AppWidgetManager.getInstance(context)
      val store = FaceWidgetStore(context)
      val face = store.face(widgetId)
      if (face == null) {
        manager.updateAppWidget(widgetId, message(context, widgetId, R.string.widget_choose))
        return
      }
      val options = manager.getAppWidgetOptions(widgetId)
      // Portrait: the narrowest width and the tallest height a launcher gives the widget.
      val size =
        sizePx(
          options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110),
          options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110),
          context.resources.displayMetrics.density,
        )
      var document = store.document(face, size)
      // A tick doesn't touch the network, except to retry a render that failed (a phone that
      // was offline when the widget was added), at most every RETRY_MS.
      val now = System.currentTimeMillis()
      if (document == null && (network || store.retryDue(widgetId, now, RETRY_MS))) {
        store.attempted(widgetId, now)
        document =
          runCatching {
            FaceRenderer(BuildConfig.RENDERER_URL, http).render(face.server, face.faceId, size)
          }
            .onFailure { Log.w(TAG, "couldn't render ${face.faceId} at $size px", it) }
            .getOrNull()
            ?.bytes
            ?.also {
              store.putDocument(face, size, it)
              store.attempted(widgetId, null)
            }
      }
      if (document == null) {
        // Keep what the launcher shows on a tick; say so only when there's nothing yet.
        if (network)
          manager.updateAppWidget(widgetId, message(context, widgetId, R.string.widget_loading))
        return
      }
      val views =
        if (RcPlatformWidgets.isSupported) {
          runCatching { RcPlatformWidgets.remoteViews(document) }
            .onFailure { Log.w(TAG, "couldn't publish ${face.faceId}", it) }
            .getOrNull()
        } else null
      // A face draws no tap actions; the launcher's "reconfigure" chooses another face.
      manager.updateAppWidget(
        widgetId,
        views ?: message(context, widgetId, R.string.widget_loading),
      )
    }

    private fun message(context: Context, widgetId: Int, text: Int) =
      RemoteViews(context.packageName, R.layout.face_widget_message).apply {
        setTextViewText(R.id.face_widget_message, context.getString(text))
        setOnClickPendingIntent(R.id.face_widget_message, open(context, widgetId))
      }

    /** Tapping a widget opens the app to choose its face. */
    private fun open(context: Context, widgetId: Int): PendingIntent =
      PendingIntent.getActivity(
        context,
        widgetId,
        Intent(context, MainActivity.Configure::class.java)
          .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
  }
}
