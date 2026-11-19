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
}
