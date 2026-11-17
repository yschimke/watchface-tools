package ee.schimke.watchface.push.push

/**
 * The Watch Face Format version a device renders. Google documents the mapping (v1 = API 33, v2 =
 * 34, v3 = 35, v4 = 36 / Wear OS 6) but no runtime query, so it is derived from the SDK level.
 * Anything above API 36 is treated as v5, which is an assumption to revisit once Google documents a
 * query.
 */
object WffSupport {
  const val MAX_KNOWN = 5

  fun maxVersionForSdk(sdkInt: Int): Int =
    when {
      sdkInt >= 37 -> 5
      sdkInt == 36 -> 4
      sdkInt == 35 -> 3
      sdkInt == 34 -> 2
      sdkInt == 33 -> 1
      else -> 0
    }
}
