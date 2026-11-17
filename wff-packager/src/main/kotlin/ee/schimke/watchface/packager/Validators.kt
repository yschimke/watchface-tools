package ee.schimke.watchface.packager

import com.google.android.wearable.watchface.validator.client.DwfValidatorFactory
import com.google.wear.watchface.dfx.memory.EvaluationSettings
import com.google.wear.watchface.dfx.memory.ImageProcessor
import com.google.wear.watchface.dfx.memory.`ImageProcessor$ImageData` as ImageData
import com.google.wear.watchface.dfx.memory.`ImageProcessor$ImageReader` as ImageReader
import com.google.wear.watchface.dfx.memory.MemoryFootprint
import com.google.wear.watchface.dfx.memory.TestFailedException
import com.google.wear.watchface.dfx.memory.WatchFaceLayoutEvaluator
import com.samsung.watchface.WatchFaceXmlValidator
import com.samsung.watchface.`WatchFaceXmlValidator$WatchFaceFormatValidationException` as WatchFaceFormatValidationException
import ee.schimke.watchface.wff.WffVersion
import java.io.File
import java.io.InputStream
import java.io.StringReader
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Outcome of the official Watch Face Push validator. */
public class PushValidation(
  /** Set only when [failures] is empty, and only by the official validator. */
  public val validationToken: String?,
  public val failures: List<PackagingDiagnostic>,
  public val passedChecks: List<String> = emptyList(),
)

/**
 * Wrapper around Google's Watch Face Push validator
 * (`com.google.android.wearable.watchface.validator:validator-push`). The token it returns is the
 * only token the push API accepts; nothing else in this module may fabricate one.
 */
public fun interface PushValidator {
  public fun validate(apk: File, marketplacePackage: String): PushValidation
}

/** Google's memory-footprint evaluator (`play-validations/memory-footprint`). */
public fun interface MemoryFootprintEvaluator {
  public fun evaluate(apk: File, wffVersion: WffVersion): MemoryFootprintResult
}

public class MemoryFootprintResult(
  public val footprint: MemoryFootprintReport,
  /** Non-null when the footprint exceeds Google's active/ambient limits. */
  public val limitFailure: String?,
)

/** Google's WFF XSD validator (`third_party/wff`, bundled in `validator-push`). */
public fun interface WffFormatValidator {
  /** Returns error messages; empty if [watchFaceXml] is valid WFF at [version]. */
  public fun validate(watchFaceXml: String, version: WffVersion): List<String>
}

/** Everything validation needs; swap members for test doubles. */
public class Validators(
  public val push: PushValidator,
  public val memory: MemoryFootprintEvaluator,
  public val format: WffFormatValidator,
) {
  public companion object {
    /** The official Google tooling, from the `validator-push` artifact on Google Maven. */
    public fun google(): Validators =
      Validators(GooglePushValidator, GoogleMemoryFootprint, GoogleWffValidator)
  }
}

public object GoogleWffValidator : WffFormatValidator {
  override fun validate(watchFaceXml: String, version: WffVersion): List<String> =
    try {
      val dom =
        DocumentBuilderFactory.newInstance()
          .apply {
            isNamespaceAware = true
            // The XML may come from untrusted authors: no DOCTYPE, no external entities or DTDs.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(
              "http://apache.org/xml/features/nonvalidating/load-external-dtd",
              false,
            )
            isXIncludeAware = false
            isExpandEntityReferences = false
          }
          .newDocumentBuilder()
          .parse(InputSource(StringReader(watchFaceXml)))
      WatchFaceXmlValidator().validateOrThrow(dom, version.number.toString())
      emptyList()
    } catch (e: WatchFaceFormatValidationException) {
      listOf(e.message ?: "invalid Watch Face Format")
    } catch (e: Exception) {
      listOf("WFF validator failed: $e")
    }
}

public object GooglePushValidator : PushValidator {
  override fun validate(apk: File, marketplacePackage: String): PushValidation {
    val result =
      try {
        DwfValidatorFactory.create().validate(apk, marketplacePackage)
      } catch (e: Exception) {
        return PushValidation(
          null,
          listOf(error("push-validator", "Validator crashed: $e")),
        )
      }
    val failures =
      result.failures().map {
        error("push-validator", "[${it.category()}] ${it.name()}: ${it.failureMessage()}")
      }
    return PushValidation(
      validationToken = if (failures.isEmpty()) result.validationToken() else null,
      failures = failures,
      passedChecks = result.successes().map { "${it.category()}/${it.name()}" },
    )
  }
}

public object GoogleMemoryFootprint : MemoryFootprintEvaluator {
  override fun evaluate(apk: File, wffVersion: WffVersion): MemoryFootprintResult {
    // Settings Google's README suggests as representative of Play review.
    val settings =
      EvaluationSettings.parseFromArguments(
          "--watch-face",
          apk.path,
          "--schema-version",
          wffVersion.number.toString(),
          "--ambient-limit-mb",
          "10",
          "--active-limit-mb",
          "100",
          "--apply-v1-offload-limitations",
        )
        .orElseThrow { PackagingException("memory-footprint", "Bad evaluation settings") }
    val footprints =
      try {
        WatchFaceLayoutEvaluator.evaluate(settings, JvmImages)
      } catch (e: TestFailedException) {
        throw PackagingException("memory-footprint", e.message ?: "memory evaluation failed")
      }
    val max =
      footprints.reduceOrNull { a, b -> MemoryFootprint.max(a, b) }
        ?: throw PackagingException("memory-footprint", "Watch face has no layouts to evaluate")
    val limitFailure =
      try {
        footprints.forEach { it.validate(settings) }
        null
      } catch (e: TestFailedException) {
        e.message
      }
    return MemoryFootprintResult(
      MemoryFootprintReport(max.totalBytes, max.maxActiveBytes, max.maxAmbientBytes),
      limitFailure,
    )
  }

  /** Same approach as Google's `JvmImageProcessor`, using ImageIO (+ TwelveMonkeys WebP). */
  private object JvmImages : ImageProcessor {
    override fun createImageReader(
      stream: InputStream,
      imageName: String,
    ): ImageReader? {
      val input = ImageIO.createImageInputStream(stream)
      val readers = ImageIO.getImageReaders(input)
      if (!readers.hasNext()) return null
      val reader = readers.next()
      reader.input = input
      val images = reader.getNumImages(true)
      return object : ImageReader {
        override fun getWidth(imageIndex: Int) = reader.getWidth(imageIndex)

        override fun getHeight(imageIndex: Int) = reader.getHeight(imageIndex)

        override fun getNumImages(): Int = images

        override fun read(imageIndex: Int): ImageData {
          // A decoder failure over-estimates as a white image rather than failing, like Google's.
          val image = runCatching { reader.read(imageIndex) }.getOrNull()
          return object : ImageData {
            override fun getWidth(): Int = image?.width ?: reader.getWidth(imageIndex)

            override fun getHeight(): Int = image?.height ?: reader.getHeight(imageIndex)

            override fun getRgb(x: Int, y: Int): Int = image?.getRGB(x, y) ?: -1
          }
        }
      }
    }
  }
}
