package ee.schimke.watchface.push.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import ee.schimke.watchface.push.DesignsState
import ee.schimke.watchface.push.InstallUi
import ee.schimke.watchface.push.data.CatalogServers
import ee.schimke.watchface.push.data.Design
import ee.schimke.watchface.push.link.LinkState
import ee.schimke.watchface.push.push.Activation
import ee.schimke.watchface.push.push.BlockReason
import ee.schimke.watchface.push.push.InstallPlan
import ee.schimke.watchface.push.push.InstallPlanner
import ee.schimke.watchface.push.push.InstallResult
import ee.schimke.watchface.push.push.PushState

@Composable
fun LinkScreen(
  state: LinkState,
  server: String?,
  onRetry: () -> Unit,
  onChangeServer: () -> Unit,
) {
  val list = rememberTransformingLazyColumnState()
  ScreenScaffold(list) { padding ->
    TransformingLazyColumn(state = list, contentPadding = padding) {
      item { ListHeader { Text("Link your account") } }
      when (state) {
        LinkState.Requesting -> item { CircularProgressIndicator() }
        is LinkState.Waiting -> {
          item { QrCode(state.verificationUrl, Modifier.size(112.dp)) }
          item {
            Text(
              state.code,
              style = MaterialTheme.typography.titleLarge,
              fontFamily = FontFamily.Monospace,
              textAlign = TextAlign.Center,
            )
          }
          item {
            Text(
              "Scan, or open the site's Link page and enter this code.",
              style = MaterialTheme.typography.bodySmall,
              textAlign = TextAlign.Center,
            )
          }
        }
        is LinkState.Linked -> item { Text("Linked ${state.accountName.orEmpty()}") }
        LinkState.Expired -> {
          item { Text("The code expired.", textAlign = TextAlign.Center) }
          item { Button(onClick = onRetry, label = { Text("New code") }) }
        }
        LinkState.Denied -> {
          item { Text("Linking was declined on the site.", textAlign = TextAlign.Center) }
          item { Button(onClick = onRetry, label = { Text("Try again") }) }
        }
        is LinkState.Failed -> {
          item { Text(state.message, textAlign = TextAlign.Center) }
          item { Button(onClick = onRetry, label = { Text("Retry") }) }
        }
      }
      item {
        Button(
          onClick = onChangeServer,
          colors = ButtonDefaults.filledTonalButtonColors(),
          label = { Text("Server") },
          secondaryLabel = { server?.let { Text(CatalogServers.label(it)) } },
        )
      }
    }
  }
}

@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
  val matrix =
    remember(content) {
      QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    }
  Canvas(modifier.clip(RoundedCornerShape(8.dp)).aspectRatio(1f)) {
    drawRect(Color.White)
    val cell = size.width / matrix.width
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
      if (matrix[x, y]) {
        drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
      }
    }
  }
}

fun statusLabel(design: Design, push: PushState?, deviceWff: Int): String =
  when {
    design.wffVersion > deviceWff -> "Needs WFF v${design.wffVersion}"
    push != null && InstallPlanner.hasUpdate(design, push) -> "Update available"
    push != null && InstallPlanner.isInstalled(design, push) -> "Installed"
    else -> "v${design.versionName ?: design.version}"
  }

@Composable
fun DesignListScreen(
  state: DesignsState,
  accountName: String?,
  server: String?,
  deviceWff: Int,
  pushSupported: Boolean,
  onOpen: (Design) -> Unit,
  onRefresh: () -> Unit,
  onUnlink: () -> Unit,
) {
  val list = rememberTransformingLazyColumnState()
  ScreenScaffold(list) { padding ->
    TransformingLazyColumn(state = list, contentPadding = padding) {
      item { ListHeader { Text("Your designs") } }
      if (!pushSupported) {
        item {
          Text(
            Messages.blocked(BlockReason.PUSH_UNSUPPORTED, 0, deviceWff),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
          )
        }
      }
      when (state) {
        DesignsState.Loading -> item { CircularProgressIndicator() }
        is DesignsState.Error -> {
          item { Text(state.message, textAlign = TextAlign.Center) }
          item { Button(onClick = onRefresh, label = { Text("Retry") }) }
        }
        is DesignsState.Ready -> {
          if (state.designs.isEmpty()) {
            item {
              Text(
                "Nothing published yet. Publish a design from the site.",
                textAlign = TextAlign.Center,
              )
            }
          }
          state.push?.let { push ->
            item {
              val total = push.installed.size + push.remainingSlots
              Text(
                "$total face slot${if (total == 1) "" else "s"}: installing another design " +
                  if (total == 1) "replaces the current one." else "uses a free slot first.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
              )
            }
          }
          items(state.designs.size) { index ->
            val design = state.designs[index]
            TitleCard(
              onClick = { onOpen(design) },
              title = { Text(design.title) },
              subtitle = { Text(statusLabel(design, state.push, deviceWff)) },
            ) {
              Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PreviewImage(design, Modifier.size(96.dp))
              }
            }
          }
        }
      }
      item { Spacer(Modifier.height(4.dp)) }
      if (state is DesignsState.Ready) {
        item {
          Button(
            onClick = onRefresh,
            colors = ButtonDefaults.filledTonalButtonColors(),
            label = { Text("Refresh") },
            secondaryLabel = { Text("Check for new designs") },
          )
        }
      }
      item {
        Button(
          onClick = onUnlink,
          colors = ButtonDefaults.filledTonalButtonColors(),
          label = { Text("Unlink") },
          secondaryLabel = {
            val who = listOfNotNull(accountName, server?.let(CatalogServers::label))
            if (who.isNotEmpty()) Text(who.joinToString(" · "))
          },
        )
      }
    }
  }
}

@Composable
fun DesignDetailScreen(
  design: Design,
  push: PushState?,
  deviceWff: Int,
  pushSupported: Boolean,
  install: InstallUi,
  onInstall: (confirmReplace: Boolean) -> Unit,
  onRequestActivation: (slotId: String) -> Unit,
  onDismissResult: () -> Unit,
) {
  val list = rememberTransformingLazyColumnState()
  val plan =
    push?.let { InstallPlanner.plan(design, it, pushSupported, deviceWff) }
      ?: if (!pushSupported) InstallPlan.Blocked(BlockReason.PUSH_UNSUPPORTED) else null
  var confirming by remember { mutableStateOf(false) }
  val working = install is InstallUi.Working && install.designId == design.id
  val finished = (install as? InstallUi.Finished)?.takeIf { it.designId == design.id }
  val blocked = plan as? InstallPlan.Blocked
  val actionLabel =
    when {
      working -> "Installing…"
      plan is InstallPlan.UpdateInstalled ->
        if (InstallPlanner.hasUpdate(design, push!!)) "Update" else "Reinstall"
      plan is InstallPlan.ReplaceOther -> if (confirming) "Replace now" else "Install"
      else -> "Install"
    }
  ScreenScaffold(
    list,
    edgeButton = {
      if (blocked == null && plan != null) {
        EdgeButton(
          onClick = {
            if (plan is InstallPlan.ReplaceOther && !confirming) confirming = true
            else {
              confirming = false
              onInstall(plan is InstallPlan.ReplaceOther)
            }
          },
          enabled = !working,
        ) {
          Text(actionLabel)
        }
      }
    },
  ) { padding ->
    TransformingLazyColumn(state = list, contentPadding = padding) {
      item { PreviewImage(design, Modifier.size(96.dp)) }
      item { ListHeader { Text(design.title) } }
      item {
        Text(
          "Version ${design.versionName ?: design.version} · requires WFF v${design.wffVersion}",
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
        )
      }
      design.description?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
      item {
        Text(
          statusLabel(design, push, deviceWff),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      blocked?.let {
        item {
          Text(
            Messages.blocked(it.reason, design.wffVersion, deviceWff),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
          )
        }
      }
      if (plan is InstallPlan.ReplaceOther) {
        item {
          Text(
            "Your watch has room for one face from this app. Installing replaces " +
              plan.slot.packageName.substringAfterLast('.') +
              ".",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
          )
        }
      }
      if (working) item { CircularProgressIndicator() }
      finished?.let { done ->
        item {
          Text(
            Messages.result(done.result, design.wffVersion, deviceWff),
            textAlign = TextAlign.Center,
            color =
              if (done.result is InstallResult.Success) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.error,
          )
        }
        val success = done.result as? InstallResult.Success
        if (success != null && success.activation == Activation.NotRequested) {
          item {
            Button(
              onClick = { onRequestActivation(success.face.slotId) },
              label = { Text("Set as watch face") },
            )
          }
        }
        item { Button(onClick = onDismissResult, label = { Text("OK") }) }
      }
    }
  }
}

/**
 * Overrides how previews load, e.g. with a bundled painter in screenshot tests and Compose
 * previews. Null means "load [Design.previewUrl] with Coil".
 */
val LocalPreviewPainter = compositionLocalOf<((Design) -> Painter)?> { null }

/**
 * A design's preview, cropped to a circle: the faces are round, and the square render's corners are
 * not part of what the watch shows.
 */
@Composable
fun PreviewImage(design: Design, modifier: Modifier = Modifier) {
  val shaped = modifier.aspectRatio(1f).clip(CircleShape)
  val override = LocalPreviewPainter.current
  if (override != null) {
    Image(override(design), "${design.title} preview", shaped, contentScale = ContentScale.Crop)
  } else {
    AsyncImage(
      model = design.previewUrl,
      contentDescription = "${design.title} preview",
      contentScale = ContentScale.Crop,
      modifier = shaped,
    )
  }
}
