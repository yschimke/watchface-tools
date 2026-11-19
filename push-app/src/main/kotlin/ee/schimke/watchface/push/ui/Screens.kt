package ee.schimke.watchface.push.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
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

/**
 * Signing in with a device code, opened from the public list; swiping it away goes back there, so
 * signing in stays optional.
 */
@Composable
fun LinkScreen(state: LinkState, server: String?, onRetry: () -> Unit, onChangeServer: () -> Unit) {
  // Back to the header (and the QR below it) for each new code, even one that repeats the last.
  val list =
    rememberTopAnchoredListState(
      contentKey =
        when (state) {
          LinkState.Requesting -> null
          is LinkState.Waiting -> "code:${state.code}"
          else -> state.javaClass.simpleName
        },
      repinAfterReload = true,
    )
  val spec = rememberTransformationSpec()
  // After an expired, declined or failed code, a new code is the screen's primary action.
  val retryLabel =
    when (state) {
      LinkState.Expired -> "New code"
      LinkState.Denied -> "Try again"
      is LinkState.Failed -> "Retry"
      else -> null
    }
  ScreenScaffold(
    list,
    edgeButton = { retryLabel?.let { EdgeButton(onClick = onRetry) { Text(it) } } },
  ) { padding ->
    TransformingLazyColumn(state = list, contentPadding = padding) {
      item(key = "header") { Header("Link your account", spec) }
      when (state) {
        LinkState.Requesting ->
          item(key = "requesting") {
            CircularProgressIndicator(Modifier.transformedItem(this, spec))
          }
        is LinkState.Waiting -> {
          item(key = "qr") {
            Box(Modifier.fillMaxWidth().transformedItem(this, spec), Alignment.Center) {
              QrCode(state.verificationUrl, Modifier.size(112.dp))
            }
          }
          item(key = "code") {
            BodyText(
              state.code,
              spec,
              style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            )
          }
          item(key = "how") {
            BodyText(
              "Scan, or open the site's Link page and enter this code.",
              spec,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        is LinkState.Linked ->
          item(key = "linked") { BodyText("Linked ${state.accountName.orEmpty()}", spec) }
        LinkState.Expired -> item(key = "status") { BodyText("The code expired.", spec) }
        LinkState.Denied ->
          item(key = "status") { BodyText("Linking was declined on the site.", spec) }
        is LinkState.Failed -> item(key = "status") { BodyText(state.message, spec) }
      }
      item(key = "server") {
        Button(
          onClick = onChangeServer,
          modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
          colors = ButtonDefaults.filledTonalButtonColors(),
          transformation = SurfaceTransformation(spec),
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

/** A run of the list: [title] null for the designs, else the catalog's name. */
data class DesignSection(val title: String?, val designs: List<Design>)

/**
 * The designs first (untitled: the screen header names them), then one section per catalog, in the
 * server's order.
 */
fun designSections(designs: List<Design>): List<DesignSection> {
  val (catalog, own) = designs.partition { it.isCatalog }
  val groups =
    catalog
      .groupBy { it.catalog?.system ?: "" }
      .map { (system, faces) ->
        DesignSection(faces.first().catalog?.title ?: system.ifEmpty { "Catalog" }, faces)
      }
  return listOfNotNull(DesignSection(null, own).takeIf { own.isNotEmpty() }) + groups
}

/** Credit lines for a catalog face's detail screen; empty for a design. */
fun attributionLines(design: Design): List<String> {
  if (!design.isCatalog) return emptyList()
  val a = design.attribution
  return listOfNotNull(
    (design.catalog?.title ?: design.catalog?.system)?.let { "From $it" },
    a?.license?.let { "License: $it" },
    (a?.sourceUrl ?: a?.repoUrl)?.let { "Source: " + it.removePrefix("https://") },
  )
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
  /** False while browsing public designs: the list offers signing in instead of unlinking. */
  linked: Boolean = true,
  onSignIn: () -> Unit = {},
  onChangeServer: () -> Unit = {},
) {
  // Opens at the header, and again once the designs first arrive (see the function).
  val list =
    rememberTopAnchoredListState(contentKey = if (state is DesignsState.Loading) null else "loaded")
  val spec = rememberTransformationSpec()
  ScreenScaffold(
    list,
    edgeButton = {
      // Refresh is the list's primary action; Retry after a failed load.
      when (state) {
        is DesignsState.Ready -> EdgeButton(onClick = onRefresh) { Text("Refresh") }
        is DesignsState.Error -> EdgeButton(onClick = onRefresh) { Text("Retry") }
        DesignsState.Loading -> {}
      }
    },
  ) { padding ->
    TransformingLazyColumn(state = list, contentPadding = padding) {
      item(key = "header") { Header(if (linked) "Your designs" else "Public faces", spec) }
      if (!pushSupported) {
        item(key = "unsupported") {
          BodyText(
            Messages.blocked(BlockReason.PUSH_UNSUPPORTED, 0, deviceWff),
            spec,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      when (state) {
        DesignsState.Loading ->
          item(key = "loading") {
            Box(Modifier.fillMaxWidth().transformedItem(this, spec), Alignment.Center) {
              CircularProgressIndicator()
            }
          }
        is DesignsState.Error -> item(key = "error") { BodyText(state.message, spec) }
        is DesignsState.Ready -> {
          if (state.designs.isEmpty()) {
            item(key = "empty") {
              BodyText(
                if (linked) "Nothing published yet. Publish a design from the site."
                else "No public faces yet.",
                spec,
              )
            }
          }
          state.push?.let { push ->
            item(key = "slots") {
              val total = push.installed.size + push.remainingSlots
              BodyText(
                "$total face slot${if (total == 1) "" else "s"}: installing another design " +
                  if (total == 1) "replaces the current one." else "uses a free slot first.",
                spec,
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
          // Keys must be unique or the column throws: one row per id, sections by position.
          val sections = designSections(state.designs.distinctBy { it.id })
          for ((sectionIndex, section) in sections.withIndex()) {
            section.title?.let { title ->
              item(key = "section:$sectionIndex") { Header(title, spec) }
            }
            items(
              section.designs.size,
              key = { index -> "design:${section.designs[index].id}" },
            ) { index ->
              val design = section.designs[index]
              TitleCard(
                onClick = { onOpen(design) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
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
      }
      if (linked) {
        item(key = "unlink") {
          Button(
            onClick = onUnlink,
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            colors = ButtonDefaults.filledTonalButtonColors(),
            transformation = SurfaceTransformation(spec),
            label = { Text("Unlink") },
            secondaryLabel = {
              val who = listOfNotNull(accountName, server?.let(CatalogServers::label))
              if (who.isNotEmpty()) Text(who.joinToString(" · "))
            },
          )
        }
      } else {
        item(key = "sign-in") {
          Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
            label = { Text("Sign in for your designs") },
            secondaryLabel = { Text("Optional: adds your own") },
          )
        }
        item(key = "server") {
          Button(
            onClick = onChangeServer,
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            colors = ButtonDefaults.filledTonalButtonColors(),
            transformation = SurfaceTransformation(spec),
            label = { Text("Server") },
            secondaryLabel = { server?.let { Text(CatalogServers.label(it)) } },
          )
        }
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
  val list = rememberTopAnchoredListState()
  val spec = rememberTransformationSpec()
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
  val attribution = attributionLines(design)
  val description = design.description
  // The result is added at the end while the person is at the Install button: bring it into view.
  val resultIndex =
    4 +
      (if (description != null) 1 else 0) +
      attribution.size +
      (if (blocked != null) 1 else 0) +
      (if (plan is InstallPlan.ReplaceOther) 1 else 0) +
      (if (working) 1 else 0)
  LaunchedEffect(finished) { if (finished != null) list.animateScrollToItem(resultIndex) }
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
      // The title first, so the screen opens on it; the preview follows.
      item(key = "header") { Header(design.title, spec) }
      item(key = "preview") {
        Box(Modifier.fillMaxWidth().transformedItem(this, spec), Alignment.Center) {
          PreviewImage(design, Modifier.size(96.dp))
        }
      }
      item(key = "version") {
        BodyText(
          "Version ${design.versionName ?: design.version} · requires WFF v${design.wffVersion}",
          spec,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      item(key = "status") {
        BodyText(
          statusLabel(design, push, deviceWff),
          spec,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      description?.let {
        item(key = "description") { BodyText(it, spec, style = MaterialTheme.typography.bodySmall) }
      }
      items(attribution.size, key = { "attribution:$it" }) { index ->
        BodyText(
          attribution[index],
          spec,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      blocked?.let {
        item(key = "blocked") {
          BodyText(
            Messages.blocked(it.reason, design.wffVersion, deviceWff),
            spec,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
      if (plan is InstallPlan.ReplaceOther) {
        item(key = "replace") {
          BodyText(
            "Your watch has room for one face from this app. Installing replaces " +
              plan.slot.packageName.substringAfterLast('.') +
              ".",
            spec,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      if (working) {
        item(key = "working") {
          Box(Modifier.fillMaxWidth().transformedItem(this, spec), Alignment.Center) {
            CircularProgressIndicator()
          }
        }
      }
      finished?.let { done ->
        item(key = "result") {
          BodyText(
            Messages.result(done.result, design.wffVersion, deviceWff),
            spec,
            color =
              if (done.result is InstallResult.Success) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.error,
          )
        }
        val success = done.result as? InstallResult.Success
        if (success != null && success.activation == Activation.NotRequested) {
          item(key = "activate") {
            Button(
              onClick = { onRequestActivation(success.face.slotId) },
              modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
              transformation = SurfaceTransformation(spec),
              label = { Text("Set as watch face") },
            )
          }
        }
        item(key = "dismiss") {
          Button(
            onClick = onDismissResult,
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            colors = ButtonDefaults.filledTonalButtonColors(),
            transformation = SurfaceTransformation(spec),
            label = { Text("OK") },
          )
        }
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
