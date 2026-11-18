package ee.schimke.watchface.push.link

import ee.schimke.watchface.push.data.ApiException
import ee.schimke.watchface.push.data.DeviceCode
import ee.schimke.watchface.push.data.DeviceCodeRequest
import ee.schimke.watchface.push.data.PushApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface LinkState {
  data object Requesting : LinkState

  /** Show [code] and a QR of [verificationUrl]; the user approves on the site. */
  data class Waiting(val code: String, val verificationUrl: String) : LinkState

  data class Linked(val accountName: String?) : LinkState

  data object Expired : LinkState

  data object Denied : LinkState

  data class Failed(val message: String) : LinkState
}

/** Device-code flow: request a code, poll until approved, persist the token. */
class LinkController(
  private val api: PushApi,
  private val tokens: TokenStore,
  private val deviceName: String,
  private val wearOsApi: Int,
  private val now: () -> Long = { System.currentTimeMillis() },
) {
  /**
   * Cold flow; collect it to run one linking attempt against [server] (the server [api] talks to
   * now), cancel the collector to abort. The token is saved as belonging to [server].
   */
  fun link(server: String): Flow<LinkState> = flow {
    emit(LinkState.Requesting)
    val code: DeviceCode =
      try {
        api.createDeviceCode(DeviceCodeRequest(deviceName, wearOsApi))
      } catch (e: ApiException) {
        emit(LinkState.Failed(e.message ?: "Could not reach the server"))
        return@flow
      }
    emit(LinkState.Waiting(code.code, code.verificationUrl))
    val deadline = now() + code.expiresInSeconds * 1000L
    val intervalMs = code.pollIntervalSeconds.coerceAtLeast(1) * 1000L
    while (true) {
      delay(intervalMs)
      if (now() >= deadline) {
        emit(LinkState.Expired)
        return@flow
      }
      val response =
        try {
          api.poll(code.code, code.pollSecret)
        } catch (e: ApiException) {
          // 404/410: code unknown or expired. Network blips: keep polling until the deadline.
          if (e.status == 404 || e.status == 410) {
            emit(LinkState.Expired)
            return@flow
          }
          continue
        }
      when (response.status) {
        "approved" -> {
          val token = response.deviceToken
          if (token.isNullOrEmpty()) {
            emit(LinkState.Failed("Server approved the code without a token"))
          } else {
            tokens.save(LinkedAccount(token, response.accountName, server))
            emit(LinkState.Linked(response.accountName))
          }
          return@flow
        }
        "denied" -> {
          emit(LinkState.Denied)
          return@flow
        }
        "expired" -> {
          emit(LinkState.Expired)
          return@flow
        }
        else -> Unit // pending
      }
    }
  }
}
