package ee.schimke.watchface.push.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
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
      when (val s = session) {
        Session.Loading -> CircularProgressIndicator()
        Session.Unlinked -> UnlinkedNav(vm)
        is Session.Linked -> LinkedNav(vm, s.account.accountName, s.account.server)
      }
    }
  }
}

/** Linking, and choosing the server to link with; the server can't change while linked. */
@Composable
private fun UnlinkedNav(vm: AppViewModel) {
  val nav = rememberSwipeDismissableNavController()
  val server by vm.server.collectAsStateWithLifecycle()
  SwipeDismissableNavHost(navController = nav, startDestination = "link") {
    composable("link") {
      val link by vm.link.collectAsStateWithLifecycle()
      // A new code whenever the server changes: a code belongs to the server that issued it.
      LaunchedEffect(server) { vm.startLink() }
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
}

@Composable
private fun LinkedNav(vm: AppViewModel, accountName: String?, server: String) {
  val nav = rememberSwipeDismissableNavController()
  val designs by vm.designs.collectAsStateWithLifecycle()
  val install by vm.install.collectAsStateWithLifecycle()
  var pendingActivation by remember { mutableStateOf<Pair<String, String>?>(null) }
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
        onOpen = { nav.navigate("design/${it.id}") },
        onRefresh = vm::refresh,
        onUnlink = vm::unlink,
      )
    }
    composable(
      "design/{id}",
      arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) { entry ->
      val id = entry.arguments?.getString("id")
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
