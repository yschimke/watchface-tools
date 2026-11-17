package ee.schimke.watchface.push

import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.data.HttpPushApi
import ee.schimke.watchface.push.push.InstalledFace
import ee.schimke.watchface.push.push.PushFailure
import ee.schimke.watchface.push.push.PushFailureKind
import ee.schimke.watchface.push.push.PushState
import ee.schimke.watchface.push.push.WatchFacePusher
import java.io.File
import okhttp3.OkHttpClient

const val FAKE_HOST = "fake.test"

fun fakeServerApi(server: FakePushServer = FakePushServer(FAKE_HOST)) =
  server to HttpPushApi("https://$FAKE_HOST", OkHttpClient.Builder().addInterceptor(server).build())

/** In-memory Watch Face Push with the same slot rules as Wear OS 6 (configurable slot count). */
class FakePusher(
  val slotLimit: Int = 1,
  override val isSupported: Boolean = true,
  override val wffVersion: Int = 4,
  var permissionGranted: Boolean = true,
) : WatchFacePusher {
  val slots = mutableListOf<InstalledFace>()
  val pushedBytes = mutableListOf<ByteArray>()
  var nextFailure: PushFailure? = null
  var activationFailure: PushFailure? = null
  var activeSlot: String? = null
  private var nextId = 1

  override fun canSetActive() = permissionGranted

  override suspend fun state() = PushState(slots.toList(), slotLimit - slots.size)

  private fun maybeFail() {
    nextFailure?.let {
      nextFailure = null
      throw it
    }
  }

  override suspend fun add(apk: File, token: String): InstalledFace {
    maybeFail()
    if (slots.size >= slotLimit) throw PushFailure(PushFailureKind.SLOT_LIMIT_REACHED)
    pushedBytes += apk.readBytes()
    // Real packages come from the APK; the fake derives it from the token the server issued.
    val pkg = PushConstants.PACKAGE_PREFIX + token.removePrefix("fake-validation-token-")
    return InstalledFace("slot${nextId++}", pkg, versionOf(token)).also { slots += it }
  }

  override suspend fun update(slotId: String, apk: File, token: String): InstalledFace {
    maybeFail()
    val index = slots.indexOfFirst { it.slotId == slotId }
    if (index < 0) throw PushFailure(PushFailureKind.INVALID_SLOT)
    pushedBytes += apk.readBytes()
    val pkg = PushConstants.PACKAGE_PREFIX + token.removePrefix("fake-validation-token-")
    return InstalledFace(slotId, pkg, versionOf(token)).also { slots[index] = it }
  }

  private fun versionOf(token: String) =
    FakePushServer(FAKE_HOST).designs.first { it.validationToken == token }.version

  override suspend fun remove(slotId: String) {
    slots.removeAll { it.slotId == slotId }
  }

  override suspend fun setActive(slotId: String) {
    activationFailure?.let { throw it }
    activeSlot = slotId
  }

  override suspend fun isActive(packageName: String) = slots.any {
    it.packageName == packageName && it.slotId == activeSlot
  }
}
