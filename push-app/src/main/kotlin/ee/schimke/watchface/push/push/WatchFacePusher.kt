package ee.schimke.watchface.push.push

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.wear.watchfacepush.WatchFacePushManager
import androidx.wear.watchfacepush.WatchFacePushManager.AddWatchFaceException
import androidx.wear.watchfacepush.WatchFacePushManager.SetWatchFaceAsActiveException
import androidx.wear.watchfacepush.WatchFacePushManager.UpdateWatchFaceException
import androidx.wear.watchfacepush.WatchFacePushManagerFactory
import java.io.File

data class InstalledFace(val slotId: String, val packageName: String, val versionCode: Long)

data class PushState(val installed: List<InstalledFace>, val remainingSlots: Int)

/** Why a push call failed, independent of which library exception carried it. */
enum class PushFailureKind {
  SLOT_LIMIT_REACHED,
  INVALID_VALIDATION_TOKEN,
  INVALID_PACKAGE_NAME,
  MALFORMED_APK,
  UNEXPECTED_CONTENT,
  INVALID_SLOT,
  PERMISSION_MISSING,
  MAX_ACTIVATION_ATTEMPTS,
  UNKNOWN,
}

class PushFailure(val kind: PushFailureKind, message: String? = null, cause: Throwable? = null) :
  Exception(message ?: kind.name, cause)

/** Narrow seam over the Watch Face Push API so the flows can be tested with a fake. */
interface WatchFacePusher {
  /** False when the platform has no Watch Face Push (below Wear OS 6). */
  val isSupported: Boolean

  /** Highest WFF version this watch renders. */
  val wffVersion: Int

  /** True when SET_PUSHED_WATCH_FACE_AS_ACTIVE is granted. */
  fun canSetActive(): Boolean

  suspend fun state(): PushState

  suspend fun add(apk: File, token: String): InstalledFace

  suspend fun update(slotId: String, apk: File, token: String): InstalledFace

  suspend fun remove(slotId: String)

  suspend fun setActive(slotId: String)

  suspend fun isActive(packageName: String): Boolean
}

class RealWatchFacePusher(private val context: Context, override val wffVersion: Int) :
  WatchFacePusher {
  private val manager: WatchFacePushManager by lazy {
    WatchFacePushManagerFactory.createWatchFacePushManager(context)
  }

  override val isSupported: Boolean
    get() = WatchFacePushManagerFactory.isSupported()

  override fun canSetActive(): Boolean =
    context.checkSelfPermission(SET_ACTIVE_PERMISSION) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED

  override suspend fun state(): PushState =
    try {
      val response = manager.listWatchFaces()
      PushState(
        response.installedWatchFaceDetails.map {
          InstalledFace(it.slotId, it.packageName, it.versionCode)
        },
        response.remainingSlotCount,
      )
    } catch (e: WatchFacePushManager.ListWatchFacesException) {
      throw PushFailure(PushFailureKind.UNKNOWN, e.message, e)
    }

  override suspend fun add(apk: File, token: String): InstalledFace =
    open(apk).use { fd ->
      try {
        manager.addWatchFace(fd, token).toInstalled()
      } catch (e: AddWatchFaceException) {
        throw PushFailure(
          when (e.errorCode) {
            AddWatchFaceException.ERROR_SLOT_LIMIT_REACHED -> PushFailureKind.SLOT_LIMIT_REACHED
            AddWatchFaceException.ERROR_INVALID_VALIDATION_TOKEN ->
              PushFailureKind.INVALID_VALIDATION_TOKEN
            AddWatchFaceException.ERROR_INVALID_PACKAGE_NAME -> PushFailureKind.INVALID_PACKAGE_NAME
            AddWatchFaceException.ERROR_MALFORMED_WATCHFACE_APK -> PushFailureKind.MALFORMED_APK
            AddWatchFaceException.ERROR_UNEXPECTED_CONTENT -> PushFailureKind.UNEXPECTED_CONTENT
            else -> PushFailureKind.UNKNOWN
          },
          e.message,
          e,
        )
      }
    }

  override suspend fun update(slotId: String, apk: File, token: String): InstalledFace =
    open(apk).use { fd ->
      try {
        manager.updateWatchFace(slotId, fd, token).toInstalled()
      } catch (e: UpdateWatchFaceException) {
        throw PushFailure(
          when (e.errorCode) {
            UpdateWatchFaceException.ERROR_INVALID_VALIDATION_TOKEN ->
              PushFailureKind.INVALID_VALIDATION_TOKEN
            UpdateWatchFaceException.ERROR_INVALID_PACKAGE_NAME ->
              PushFailureKind.INVALID_PACKAGE_NAME
            UpdateWatchFaceException.ERROR_MALFORMED_WATCHFACE_APK -> PushFailureKind.MALFORMED_APK
            UpdateWatchFaceException.ERROR_UNEXPECTED_CONTENT -> PushFailureKind.UNEXPECTED_CONTENT
            UpdateWatchFaceException.ERROR_INVALID_SLOT_ID -> PushFailureKind.INVALID_SLOT
            else -> PushFailureKind.UNKNOWN
          },
          e.message,
          e,
        )
      }
    }

  override suspend fun remove(slotId: String) {
    try {
      manager.removeWatchFace(slotId)
    } catch (e: WatchFacePushManager.RemoveWatchFaceException) {
      throw PushFailure(
        if (e.errorCode == WatchFacePushManager.RemoveWatchFaceException.ERROR_INVALID_SLOT_ID)
          PushFailureKind.INVALID_SLOT
        else PushFailureKind.UNKNOWN,
        e.message,
        e,
      )
    }
  }

  override suspend fun setActive(slotId: String) {
    try {
      manager.setWatchFaceAsActive(slotId)
    } catch (e: SetWatchFaceAsActiveException) {
      throw PushFailure(
        when (e.errorCode) {
          SetWatchFaceAsActiveException.ERROR_MISSING_PERMISSION ->
            PushFailureKind.PERMISSION_MISSING
          SetWatchFaceAsActiveException.ERROR_MAXIMUM_ATTEMPTS_REACHED ->
            PushFailureKind.MAX_ACTIVATION_ATTEMPTS
          SetWatchFaceAsActiveException.ERROR_INVALID_SLOT_ID -> PushFailureKind.INVALID_SLOT
          else -> PushFailureKind.UNKNOWN
        },
        e.message,
        e,
      )
    }
  }

  override suspend fun isActive(packageName: String): Boolean =
    try {
      manager.isWatchFaceActive(packageName)
    } catch (e: WatchFacePushManager.IsWatchFaceActiveException) {
      false
    }

  private fun open(file: File) =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

  private fun WatchFacePushManager.WatchFaceDetails.toInstalled() =
    InstalledFace(slotId, packageName, versionCode)

  companion object {
    const val SET_ACTIVE_PERMISSION = "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE"
  }
}
