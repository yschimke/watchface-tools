package ee.schimke.watchface.push.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Catalog servers: any site that serves `docs/PUSH_API.md`. The build supplies a default and a
 * short list of known servers (`-Ppush.baseUrl`, `-Ppush.servers`); a person can also enter another
 * one on the watch.
 */
object CatalogServers {
  /**
   * The canonical form of [input] (`https://host[:port][/path]`, no trailing slash), or null when
   * it is not a usable server address. A missing scheme means https. Plain http is refused unless
   * [allowInsecure] (debug builds, for a local server), because the device token travels in every
   * request. User info, a query or a fragment are refused rather than silently dropped.
   */
  fun normalize(input: String, allowInsecure: Boolean = false): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" && !(allowInsecure && url.scheme == "http")) return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.query != null || url.fragment != null) return null
    if (!url.host.contains('.') && url.host != "localhost") return null
    return url.render()
  }

  /** What to show for a server: its host, plus port and path when they are not the default. */
  fun label(server: String): String {
    val url = server.toHttpUrlOrNull() ?: return server
    return url.render().substringAfter("://")
  }

  /** What each known site lists: each site's faces stay on their own site. */
  private val NAMES =
    mapOf("https://ui.coo.ee" to "Your designs", "https://preview.coo.ee" to "Sample faces")

  /** A known site's name ("Your designs"), or null for any other server. */
  fun name(server: String): String? = NAMES[normalize(server)]

  /** "Your designs (ui.coo.ee)" for a known site, else just [label]. */
  fun displayName(server: String): String {
    val host = label(normalize(server) ?: server)
    return name(server)?.let { "$it ($host)" } ?: host
  }

  /** The known servers, default first, without duplicates or entries that do not normalize. */
  fun presets(default: String, known: List<String>, allowInsecure: Boolean = false): List<String> =
    (listOf(default) + known).mapNotNull { normalize(it, allowInsecure) }.distinct()

  private fun HttpUrl.render(): String {
    val port = if (port == HttpUrl.defaultPort(scheme)) "" else ":$port"
    val path = encodedPath.trimEnd('/')
    return "$scheme://$host$port$path"
  }
}
