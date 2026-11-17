package ee.schimke.watchface.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PublishedVersionsTest {
  private val manifest = """{ "modules": { "wff-core": "0.1.0", "wff-packager": "0.1.1" } }"""

  @Test
  fun `a module in the publish set takes the tag`() {
    assertEquals("0.2.0", PublishedVersions.resolve("wff-core", "0.2.0", setOf("wff-core"), manifest))
  }

  @Test
  fun `a module outside the publish set keeps the version it last published at`() {
    assertEquals("0.1.1", PublishedVersions.resolve("wff-packager", "0.2.0", setOf("x"), manifest))
  }

  @Test
  fun `no publish set at all means every module takes the tag`() {
    assertEquals("0.2.0", PublishedVersions.resolve("wff-packager", "0.2.0", null, manifest))
  }

  @Test
  fun `an empty publish set is a real answer, not a missing one`() {
    assertEquals("0.1.0", PublishedVersions.resolve("wff-core", "0.2.0", emptySet(), manifest))
  }

  @Test
  fun `a skipped module with no recorded version fails rather than taking the tag`() {
    val failure =
      assertFailsWith<IllegalStateException> {
        PublishedVersions.resolve("newcomer", "0.2.0", emptySet(), manifest)
      }
    assertEquals(true, failure.message!!.contains("newcomer"))
  }

  @Test
  fun `the publish set property parses, and absent is not empty`() {
    assertNull(PublishedVersions.parsePublishSet(null))
    assertEquals(emptySet(), PublishedVersions.parsePublishSet(""))
    assertEquals(setOf("a", "b"), PublishedVersions.parsePublishSet(" a , b ,, "))
  }
}
