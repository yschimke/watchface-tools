package ee.schimke.watchface.push

import ee.schimke.watchface.push.push.WffSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushConstantsTest {
  @Test
  fun packagePrefixFollowsTheWatchFacePushNamingRule() {
    assertEquals("ee.schimke.watchface.push.watchfacepush.", PushConstants.PACKAGE_PREFIX)
    assertTrue(PushConstants.isValidFacePackage("ee.schimke.watchface.push.watchfacepush.aurora_2"))
    assertFalse(PushConstants.isValidFacePackage("ee.schimke.watchface.push.watchfacepush."))
    assertFalse(PushConstants.isValidFacePackage("ee.schimke.watchface.push.watchfacepush.a.b"))
    assertFalse(PushConstants.isValidFacePackage("com.other.watchfacepush.aurora"))
  }

  @Test
  fun wffVersionFollowsDocumentedApiLevels() {
    assertEquals(0, WffSupport.maxVersionForSdk(32))
    assertEquals(1, WffSupport.maxVersionForSdk(33))
    assertEquals(3, WffSupport.maxVersionForSdk(35))
    assertEquals(4, WffSupport.maxVersionForSdk(36))
  }
}
