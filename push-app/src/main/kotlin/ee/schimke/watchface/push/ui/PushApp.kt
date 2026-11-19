package ee.schimke.watchface.push.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import ee.schimke.watchface.push.AppViewModel
import ee.schimke.watchface.push.DesignsState
import ee.schimke.watchface.push.Session
import ee.schimke.watchface.push.push.RealWatchFacePusher

@Composable
fun PushApp(vm: AppViewModel) {
  MaterialTheme {
    AppScaffold {
      val session by vm.session.collectAsStateWithLifecycle()
      // Each session gets its own back stack: linking lands on the account's list, unlinking on
      // the public one.
      when (val s = session) {
        Session.Loading -> LoadingScreen()
        Session.Browsing -> DesignsNav(vm, accountName = null, linked = false)
        is Session.Linked -> DesignsNav(vm, s.account.accountName, linked = true)
      }
    }
  }
}

/**
 * The design list and detail. Not [linked], the list shows the server's public faces and offers
 * signing in (the device-code screen, swiped away back to the list) and choosing the server; the
 * server can't change while linked.
 */
@Composable
private fun DesignsNav(vm: AppViewModel, accountName: String?, linked: Boolean) {
  val nav = rememberSwipeDismissableNavController()
  val server by vm.server.collectAsStateWithLifecycle()
  val designs by vm.designs.collectAsStateWithLifecycle()
  val install by vm.install.collectAsStateWithLifecycle()
  var pendingActivation by remember { mutableStateOf<Pair<String, String>?>(null) }
  var openId by rememberSaveable { mutableStateOf<String?>(null) }
  // While the app is visible, re-list every 30 s as long as a face is building; never in the
  // background.
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(vm, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollWhileBuilding() }
  }
  val permission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      val pending = pendingActivation
      if (pending != null) {
        if (granted) vm.activate(pending.first, pending.second)
      }
    }
  SwipeDismissableNavHost(navController = nav, startDestination = "list") {
    composable("list") {
      DesignListScreen(
        state = designs,
        accountName = accountName,
        server = server,
        deviceWff = vm.wffVersion,
        pushSupported = vm.pushSupported,
        onOpen = {
          // Catalog ids contain `/`, so the id is kept here rather than in the route.
          openId = it.id
          nav.navigate("design")
        },
        onRefresh = vm::refresh,
        onUnlink = vm::unlink,
        linked = linked,
        onSignIn = { nav.navigate("link") },
        onChangeServer = { nav.navigate("server") },
      )
    }
    if (!linked) {
      composable("link") {
        val link by vm.link.collectAsStateWithLifecycle()
        // A new code whenever the server changes: a code belongs to the server that issued it.
        LaunchedEffect(server) { vm.startLink() }
        // Swiped back to the list: no approval may link the watch after the person left.
        DisposableEffect(Unit) { onDispose { vm.cancelLink() } }
        LinkScreen(
          link,
          server = server,
          onRetry = vm::startLink,
          onChangeServer = { nav.navigate("server") },
        )
      }
      composable("server") {
        ServerScreen(
          current = server,
          presets = vm.serverPresets,
          onSelect = vm::selectServer,
          onDone = { nav.popBackStack() },
        )
      }
    }
    composable("design") {
      val id = openId
      val ready = designs as? DesignsState.Ready
      val design = ready?.designs?.firstOrNull { it.id == id }
      if (design != null) {
        DesignDetailScreen(
          design = design,
          push = ready.push,
          deviceWff = vm.wffVersion,
          pushSupported = vm.pushSupported,
          install = install,
          onInstall = { replace -> vm.installDesign(design, replace, activate = true) },
          onRequestActivation = { slot ->
            pendingActivation = design.id to slot
            permission.launch(RealWatchFacePusher.SET_ACTIVE_PERMISSION)
          },
          onDismissResult = vm::dismissInstallResult,
        )
      }
    }
  }
}

/** Before the stored session loads: a non-scrolling screen, so the time text stays clear. */
@Composable
private fun LoadingScreen() {
  ScreenScaffold { padding ->
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
  }
}
