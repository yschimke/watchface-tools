package ee.schimke.watchface.wff

/** Watch Face Format versions, as declared by `com.google.wear.watchface.format.version`. */
public enum class WffVersion(public val number: Int) {
  V1(1),
  V2(2),
  V3(3),
  V4(4);

  public companion object {
    public val LATEST: WffVersion = V4

    public fun fromNumber(n: Int): WffVersion? = entries.firstOrNull { it.number == n }
  }
}
