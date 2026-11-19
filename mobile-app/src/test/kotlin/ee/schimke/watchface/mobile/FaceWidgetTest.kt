package ee.schimke.watchface.mobile

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.watchface.mobile.widget.FaceWidgetProvider
import ee.schimke.watchface.mobile.widget.FaceWidgetStore
import ee.schimke.watchface.mobile.widget.FaceWidgetTicker
import ee.schimke.watchface.mobile.widget.WidgetFace
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FaceWidgetTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun anotherAppCantChooseAWidgetsFace() {
    // The exported provider ignores the app's private pin action.
    val forged =
      android.content
        .Intent(FaceWidgetProvider.ACTION_PINNED)
        .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, 42)
        .putExtra("server", "https://evil.example")
        .putExtra("face", "x")
    FaceWidgetProvider().onReceive(context, forged)
    assertNull(FaceWidgetStore(context).face(42))
    // The receiver that does take it is not exported.
    val info =
      context.packageManager.getReceiverInfo(
        android.content.ComponentName(
          context,
          ee.schimke.watchface.mobile.widget.FaceWidgetActions::class.java,
        ),
        android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS,
      )
    assertEquals(false, info.exported)
  }

  @Test
  fun theTickLandsOnTheNextWholeMinute() {
    assertEquals(120_000L, FaceWidgetTicker.nextMinute(60_000L))
    assertEquals(120_000L, FaceWidgetTicker.nextMinute(119_999L))
    assertEquals(60_000L, FaceWidgetTicker.nextMinute(0L))
  }

  @Test
  fun aWidgetDrawsTheSquareItFitsIn32PxSteps() {
    // A 2x2 cell on a 2.625x phone: ~150 dp square.
    assertEquals(416, FaceWidgetProvider.sizePx(150, 180, 2.625f))
    assertEquals(64, FaceWidgetProvider.sizePx(10, 10, 1f))
    assertEquals(FaceWidgetProvider.MAX_WIDGET_PX, FaceWidgetProvider.sizePx(600, 600, 3f))
  }

  @Test
  fun aFailedRenderIsRetriedByATickAfterTheBackoff() {
    val store = FaceWidgetStore(context)
    assertEquals(false, store.retryDue(9, 1_000_000, FaceWidgetProvider.RETRY_MS))
    store.attempted(9, 1_000_000)
    assertEquals(false, store.retryDue(9, 1_000_000 + 60_000, FaceWidgetProvider.RETRY_MS))
    assertEquals(
      true,
      store.retryDue(9, 1_000_000 + FaceWidgetProvider.RETRY_MS, FaceWidgetProvider.RETRY_MS),
    )
    store.attempted(9, null)
    assertEquals(false, store.retryDue(9, Long.MAX_VALUE, FaceWidgetProvider.RETRY_MS))
  }

  @Test
  fun aWidgetsFaceAndItsDocumentsAreKeptUntilItsRemoved() {
    val store = FaceWidgetStore(context)
    val aurora = WidgetFace("https://ui.coo.ee", "catalog:wear-os-samples/Aurora", "Aurora")
    val pulse = WidgetFace("https://ui.coo.ee", "pulse", "Pulse")
    store.setFace(7, aurora)
    store.setFace(8, pulse)
    store.putDocument(aurora, 416, byteArrayOf(1))
    store.putDocument(pulse, 416, byteArrayOf(2))
    assertEquals(aurora, store.face(7))
    assertContentEquals(byteArrayOf(1), store.document(aurora, 416))
    assertNull(store.document(aurora, 448))

    store.remove(intArrayOf(7))
    assertNull(store.face(7))
    assertNull(store.document(aurora, 416))
    assertContentEquals(byteArrayOf(2), store.document(pulse, 416))
    assertEquals(1, File(context.filesDir, "faces").listFiles()!!.size)
  }

  @Test
  fun aNewPublishedVersionIsANewDocument() {
    val store = FaceWidgetStore(context)
    val v1 = WidgetFace("https://ui.coo.ee", "aurora", "Aurora", version = 1)
    store.setFace(3, v1)
    store.putDocument(v1, 416, byteArrayOf(1))
    val v2 = v1.copy(version = 2)
    store.setFace(3, v2)
    assertEquals(v2, store.face(3))
    assertNull(store.document(v2, 416))
    assertNull(store.document(v1, 416))
  }
}
