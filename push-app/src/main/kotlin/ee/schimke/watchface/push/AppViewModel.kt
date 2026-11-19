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

  /**
   * Not linked: the watch lists the server's public designs anonymously. Signing in is optional and
   * adds the account's own designs.
   */
  data object Browsing : Session

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
  /** Diagnostics, one line per event; the emulator test asserts on them in logcat. */
  private val log: (String) -> Unit = {},
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

  /** Bumped by each [refresh]; a listing that finishes after a newer one started is dropped. */
  private var refreshGeneration = 0

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
        // No token for this server: browse its public designs rather than asking to sign in.
        _session.value = if (account != null) Session.Linked(account) else Session.Browsing
        load(keepContent = false)
      }
    }
  }

  /** The token to send: the linked account's, or none while browsing public designs. */
  private fun currentToken(): Result<String?> =
    when (val s = session.value) {
      is Session.Linked -> Result.success(s.account.deviceToken)
      Session.Browsing -> Result.success(null)
      Session.Loading -> Result.failure(IllegalStateException("no session"))
    }

  fun startLink() {
    val server = _server.value ?: return
    linkJob?.cancel()
    _link.value = LinkState.Requesting
    linkJob = viewModelScope.launch { linkController.link(server).collect { _link.value = it } }
  }

  /**
   * Stops the device-code attempt when the person leaves the sign-in screen: an approval landing
   * later would link the watch behind their back. Opening the screen again starts a fresh one.
   */
  fun cancelLink() {
    linkJob?.cancel()
    linkJob = null
    // The cancelled code is dead: reopening the screen must not show it while a new one is fetched.
    _link.value = LinkState.Requesting
  }

  /**
   * Switches to [input] (normalized, see [CatalogServers.normalize]); false if it is not a usable
   * server address. Only offered while unlinked, so no request is in flight with the old server.
   */
  fun selectServer(input: String): Boolean {
    val server = CatalogServers.normalize(input, allowInsecureServers) ?: return false
    // The same server emits no change to restart a listing, so cancelling would strand it loading.
    if (server == _server.value) return true
    cancelTokenWork()
    viewModelScope.launch { servers.select(server) }
    return true
  }

  /**
   * Debug builds and tests only: link to [server] with a token issued elsewhere (an emulator run
   * against a live server, where nobody can approve a device code).
   */
  fun importToken(token: String, server: String) {
    val normalized = CatalogServers.normalize(server, allowInsecureServers) ?: return
    viewModelScope.launch { tokens.save(LinkedAccount(token, null, normalized)) }
  }

  fun unlink() {
    cancelTokenWork()
    viewModelScope.launch { tokens.clear() }
    _install.value = InstallUi.Idle
  }

  /**
   * Lists the designs again. The current list stays on screen until the new one arrives: swapping
   * in a spinner would lose the person's place in it.
   */
  fun refresh() = load(keepContent = true)

  /**
   * Refreshes the list every [intervalMillis] while any listed design is being built, so it turns
   * installable on its own. Run it only while the list is visible (the UI ties it to the
   * lifecycle): nothing polls in the background.
   */
  suspend fun pollWhileBuilding(intervalMillis: Long = BUILD_POLL_MILLIS) {
    while (true) {
      kotlinx.coroutines.delay(intervalMillis)
      val ready = _designs.value as? DesignsState.Ready ?: continue
      if (ready.designs.any { it.isBuilding }) {
        log("poll builds=${ready.designs.filter { it.isBuilding }.joinToString(",") { it.id }}")
        refresh()
      }
    }
  }

  companion object {
    const val BUILD_POLL_MILLIS: Long = 30_000
  }

  /** [keepContent] false for a new session or server, whose list must not show the old one. */
  private fun load(keepContent: Boolean) {
    val token =
      currentToken().getOrElse {
        return
      }
    if (!keepContent || _designs.value !is DesignsState.Ready) _designs.value = DesignsState.Loading
    val generation = ++refreshGeneration
    launchWithToken {
      val next =
        try {
          val designs = api.listDesigns(token)
          val push = loadPushState()
          log("list designs=${designs.joinToString(",") { it.id }} anonymous=${token == null}")
          log("state ${push.describe()}")
          DesignsState.Ready(designs, push)
        } catch (e: ApiException) {
          log("list failed status=${e.status} anonymous=${token == null}")
          when {
            e.isUnauthorized && token != null -> {
              tokens.clear()
              DesignsState.Loading
            }
            e.isUnauthorized || e.status == 404 ->
              DesignsState.Error("This server has no public faces. Sign in for your designs.")
            else -> DesignsState.Error(e.message ?: "Could not load designs")
          }
        }
      // A revoked token clears itself above, which starts the anonymous listing: keep that one.
      if (generation == refreshGeneration) _designs.value = next
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
    val token =
      currentToken().getOrElse {
        return
      }
    if (_install.value is InstallUi.Working) return
    _install.value = InstallUi.Working(design.id)
    launchWithToken {
      val result = installFlow.install(token, design, confirmReplace, activate)
      log("install design=${design.id} result=${result.javaClass.simpleName} $result")
      // Anonymous 401: a private design; only a linked watch's 401 means its token was revoked.
      if (result is InstallResult.DownloadFailed && result.unauthorized && token != null) {
        tokens.clear()
      }
      _install.value = InstallUi.Finished(design.id, result)
      val push = loadPushState()
      log("state ${push.describe()}")
      _designs.update { state ->
        if (state is DesignsState.Ready) state.copy(push = push) else state
      }
    }
  }

  /**
   * Makes an already installed design the active face (needs the runtime permission) and reports
   * the outcome to the server as a second install event.
   */
  fun activate(designId: String, slotId: String) {
    val token = currentToken().getOrNull()
    launchWithToken {
      val previous = _install.value as? InstallUi.Finished
      val success = previous?.result as? InstallResult.Success
      val face = success?.face?.takeIf { it.slotId == slotId }
      val activation =
        if (face != null) installFlow.activate(token, designId, face)
        else
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

private fun PushState?.describe(): String =
  if (this == null) "unsupported"
  else
    "remaining=$remainingSlots installed=" +
      installed.joinToString(",") { "${it.packageName}:${it.versionCode}:${it.slotId}" }
