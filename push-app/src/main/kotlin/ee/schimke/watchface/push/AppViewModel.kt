package ee.schimke.watchface.push

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ee.schimke.watchface.push.data.ApiException
import ee.schimke.watchface.push.data.CatalogServers
import ee.schimke.watchface.push.data.Design
import ee.schimke.watchface.push.data.PushApi
import ee.schimke.watchface.push.link.LinkController
import ee.schimke.watchface.push.link.LinkState
import ee.schimke.watchface.push.link.LinkedAccount
import ee.schimke.watchface.push.link.ServerStore
import ee.schimke.watchface.push.link.TokenStore
import ee.schimke.watchface.push.push.Activation
import ee.schimke.watchface.push.push.InstallFlow
import ee.schimke.watchface.push.push.InstallResult
import ee.schimke.watchface.push.push.PushFailure
import ee.schimke.watchface.push.push.PushState
import ee.schimke.watchface.push.push.WatchFacePusher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Session {
  data object Loading : Session

  data object Unlinked : Session

  data class Linked(val account: LinkedAccount) : Session
}

sealed interface DesignsState {
  data object Loading : DesignsState

  data class Ready(val designs: List<Design>, val push: PushState?) : DesignsState

  data class Error(val message: String) : DesignsState
}

sealed interface InstallUi {
  data object Idle : InstallUi

  data class Working(val designId: String) : InstallUi

  data class Finished(val designId: String, val result: InstallResult) : InstallUi
}

class AppViewModel(
  private val api: PushApi,
  private val tokens: TokenStore,
  private val pusher: WatchFacePusher,
  private val installFlow: InstallFlow,
  private val linkController: LinkController,
  private val servers: ServerStore,
  /** Points [api] at a server; called before any request goes to it. */
  private val switchServer: (String) -> Unit,
  /** Known servers offered in the picker, the build's default first. */
  val serverPresets: List<String>,
  /** Debug builds accept plain-http servers (a server on the developer's machine). */
  private val allowInsecureServers: Boolean = false,
) : ViewModel() {
  private val _server = MutableStateFlow<String?>(null)

  /** The catalog server in use; null until the stored choice has loaded. */
  val server: StateFlow<String?> = _server.asStateFlow()

  private val _session = MutableStateFlow<Session>(Session.Loading)
  val session: StateFlow<Session> = _session.asStateFlow()

  private val _link = MutableStateFlow<LinkState>(LinkState.Requesting)
  val link: StateFlow<LinkState> = _link.asStateFlow()

  private val _designs = MutableStateFlow<DesignsState>(DesignsState.Loading)
  val designs: StateFlow<DesignsState> = _designs.asStateFlow()

  private val _install = MutableStateFlow<InstallUi>(InstallUi.Idle)
  val install: StateFlow<InstallUi> = _install.asStateFlow()

  val pushSupported: Boolean
    get() = pusher.isSupported

  val wffVersion: Int
    get() = pusher.wffVersion

  private var linkJob: Job? = null

  /** Work that sends the device token: listing designs and installs (download + report). */
  private val tokenJobs = mutableSetOf<Job>()

  private fun launchWithToken(block: suspend () -> Unit): Job =
    viewModelScope
      .launch { block() }
      .also { job ->
        tokenJobs += job
        job.invokeOnCompletion { tokenJobs -= job }
      }

  /**
   * Stops everything that would send a device token, before the token is dropped or the API is
   * pointed at another server. All of it runs on the main dispatcher, as the switch does, so a
   * cancelled job cannot resume and reach [api] again: its next suspension point throws.
   */
  private fun cancelTokenWork() {
    linkJob?.cancel()
    tokenJobs.toList().forEach { it.cancel() }
    if (_install.value is InstallUi.Working) _install.value = InstallUi.Idle
  }

  init {
    viewModelScope.launch {
      combine(servers.server, tokens.account, ::Pair).collect { (server, stored) ->
        if (server != _server.value) {
          cancelTokenWork()
          switchServer(server)
          _server.value = server
        }
        // A token is only used with the server that issued it; another server's reads as unlinked.
        val account = stored?.takeIf { it.server == server }
        _session.value = if (account == null) Session.Unlinked else Session.Linked(account)
        if (account != null) refresh() else _designs.value = DesignsState.Loading
      }
    }
  }

  fun startLink() {
    val server = _server.value ?: return
    linkJob?.cancel()
    _link.value = LinkState.Requesting
    linkJob = viewModelScope.launch { linkController.link(server).collect { _link.value = it } }
  }

  /**
   * Switches to [input] (normalized, see [CatalogServers.normalize]); false if it is not a usable
   * server address. Only offered while unlinked, so no request is in flight with the old server.
   */
  fun selectServer(input: String): Boolean {
    val server = CatalogServers.normalize(input, allowInsecureServers) ?: return false
    cancelTokenWork()
    viewModelScope.launch { servers.select(server) }
    return true
  }

  fun unlink() {
    cancelTokenWork()
    viewModelScope.launch { tokens.clear() }
    _install.value = InstallUi.Idle
  }

  fun refresh() {
    val account = (session.value as? Session.Linked)?.account ?: return
    _designs.value = DesignsState.Loading
    launchWithToken {
      _designs.value =
        try {
          DesignsState.Ready(api.listDesigns(account.deviceToken), loadPushState())
        } catch (e: ApiException) {
          if (e.isUnauthorized) {
            tokens.clear()
            DesignsState.Loading
          } else DesignsState.Error(e.message ?: "Could not load designs")
        }
    }
  }

  private suspend fun loadPushState(): PushState? =
    if (!pusher.isSupported) null
    else
      try {
        pusher.state()
      } catch (e: PushFailure) {
        null
      }

  fun installDesign(design: Design, confirmReplace: Boolean, activate: Boolean) {
    val account = (session.value as? Session.Linked)?.account ?: return
    if (_install.value is InstallUi.Working) return
    _install.value = InstallUi.Working(design.id)
    launchWithToken {
      val result = installFlow.install(account.deviceToken, design, confirmReplace, activate)
      if (result is InstallResult.DownloadFailed && result.unauthorized) tokens.clear()
      _install.value = InstallUi.Finished(design.id, result)
      val push = loadPushState()
      _designs.update { state ->
        if (state is DesignsState.Ready) state.copy(push = push) else state
      }
    }
  }

  /** Makes an already installed design the active face (needs the runtime permission). */
  fun activate(designId: String, slotId: String) {
    viewModelScope.launch {
      val previous = _install.value as? InstallUi.Finished
      val success = previous?.result as? InstallResult.Success
      val activation =
        try {
          pusher.setActive(slotId)
          Activation.Active
        } catch (e: PushFailure) {
          Activation.Manual(e.kind)
        }
      if (success != null) {
        _install.value = InstallUi.Finished(designId, success.copy(activation = activation))
      }
    }
  }

  fun dismissInstallResult() {
    _install.value = InstallUi.Idle
  }
}
