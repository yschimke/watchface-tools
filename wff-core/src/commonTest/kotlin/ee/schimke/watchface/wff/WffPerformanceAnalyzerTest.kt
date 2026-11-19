package ee.schimke.watchface.wff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WffPerformanceAnalyzerTest {
  @Test
  fun cleanDigitalFaceWithBlackBackgroundHasNoWarnings() {
    val doc =
      WffXmlParser.parse(
        """
        <WatchFace width="450" height="450">
          <Scene backgroundColor="#ff000000">
            <DigitalClock x="0" y="0" width="450" height="450">
              <TimeText x="0" y="180" width="450" height="90" format="hh:mm">
                <Font family="SYNC_TO_DEVICE" size="48" color="#ffffffff"/>
              </TimeText>
            </DigitalClock>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      )

    val report = WffPerformanceAnalyzer.analyze(doc)
    assertEquals(WffUpdateCadence.MINUTE, report.interactiveCadence)
    assertEquals(WffUpdateCadence.MINUTE, report.ambientCadence)
    assertEquals(0, report.estimatedMaxFps)
    assertEquals(0, report.gyroCount)
    assertEquals(0, report.animatedImageCount)
    assertTrue(report.findings.isEmpty(), "Expected no findings, got ${report.findings}")
  }

  @Test
  fun sweepSecondHandVisibleInAmbientTriggersSweepHighFpsAndAmbientFindings() {
    val doc =
      WffXmlParser.parse(
        """
        <WatchFace width="450" height="450">
          <Scene backgroundColor="#ff101010">
            <AnalogClock x="0" y="0" width="450" height="450">
              <SecondHand resource="second_hand" x="220" y="20" width="10" height="205" pivotX="0.5" pivotY="1.0">
                <Sweep frequency="30"/>
              </SecondHand>
            </AnalogClock>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      )

    val report = WffPerformanceAnalyzer.analyze(doc)
    assertEquals(WffUpdateCadence.CONTINUOUS, report.interactiveCadence)
    assertEquals(WffUpdateCadence.CONTINUOUS, report.ambientCadence)
    assertEquals(30, report.estimatedMaxFps)
    assertEquals(1, report.ambientAnimatedElementCount)
    val codes = report.findings.map { it.code }.toSet()
    assertTrue(WffPerformanceCode.NON_BLACK_SCENE_BACKGROUND in codes)
    assertTrue(WffPerformanceCode.SWEEP_SECOND_HAND in codes)
    assertTrue(WffPerformanceCode.HIGH_FPS_ANIMATION in codes)
    assertTrue(WffPerformanceCode.AMBIENT_ANIMATION_VISIBLE in codes)
  }

  @Test
  fun tickingSecondHandHiddenInAmbientKeepsAmbientAtMinuteCadence() {
    val doc =
      WffXmlParser.parse(
        """
        <WatchFace width="450" height="450">
          <Scene backgroundColor="#ff000000">
            <AnalogClock x="0" y="0" width="450" height="450">
              <SecondHand resource="second_hand" x="220" y="20" width="10" height="205" pivotX="0.5" pivotY="1.0">
                <Variant mode="AMBIENT" target="alpha" value="0"/>
                <Tick/>
              </SecondHand>
            </AnalogClock>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      )

    val report = WffPerformanceAnalyzer.analyze(doc)
    assertEquals(WffUpdateCadence.SECOND, report.interactiveCadence)
    assertEquals(WffUpdateCadence.MINUTE, report.ambientCadence)
    assertEquals(1, report.estimatedMaxFps)
    assertEquals(0, report.ambientAnimatedElementCount)
    assertTrue(report.findings.isEmpty())
  }

  @Test
  fun detectsGyroAnimatedImageAndSubSecondExpressions() {
    val doc =
      WffXmlParser.parse(
        """
        <WatchFace width="450" height="450">
          <Scene backgroundColor="#ff000000">
            <Group x="0" y="0" width="450" height="450">
              <Variant mode="AMBIENT" target="alpha" value="0"/>
              <Gyro x="10" y="10"/>
              <PartAnimatedImage x="0" y="0" width="100" height="100" frameRate="24"/>
              <PartDraw x="0" y="0" width="100" height="100">
                <Transform target="angle" value="[MILLISECOND] * 0.36"/>
              </PartDraw>
            </Group>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      )

    val report = WffPerformanceAnalyzer.analyze(doc)
    assertEquals(WffUpdateCadence.CONTINUOUS, report.interactiveCadence)
    assertEquals(WffUpdateCadence.STATIC, report.ambientCadence)
    assertEquals(1, report.gyroCount)
    assertEquals(1, report.animatedImageCount)
    assertEquals(1, report.highFrequencyExpressionCount)
    assertEquals(0, report.ambientAnimatedElementCount)
    val codes = report.findings.map { it.code }.toSet()
    assertTrue(WffPerformanceCode.GYRO_SENSOR_USAGE in codes)
    assertTrue(WffPerformanceCode.HIGH_FPS_ANIMATION in codes)
    assertTrue(WffPerformanceCode.HIGH_FREQUENCY_EXPRESSION in codes)
    assertTrue(WffPerformanceCode.AMBIENT_ANIMATION_VISIBLE !in codes)
  }

  @Test
  fun detectsSecondsInTimeTextFormat() {
    val doc =
      WffXmlParser.parse(
        """
        <WatchFace width="450" height="450">
          <Scene backgroundColor="#ff000000">
            <DigitalClock x="0" y="0" width="450" height="450">
              <TimeText x="0" y="180" width="450" height="90" format="hh:mm:ss">
                <Font family="SYNC_TO_DEVICE" size="48" color="#ffffffff"/>
              </TimeText>
            </DigitalClock>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      )

    val report = WffPerformanceAnalyzer.analyze(doc)
    assertEquals(WffUpdateCadence.SECOND, report.interactiveCadence)
    assertEquals(WffUpdateCadence.SECOND, report.ambientCadence)
    assertEquals(1, report.estimatedMaxFps)
    assertEquals(1, report.ambientAnimatedElementCount)
    assertTrue(report.findings.any { it.code == WffPerformanceCode.AMBIENT_ANIMATION_VISIBLE })
  }

  @Test
  fun detectsHighFrameRateInNestedAnimatedImagesSequenceImages() {
    val doc =
      WffXmlParser.parse(
        """
        <WatchFace width="450" height="450">
          <Scene backgroundColor="#ff000000">
            <PartAnimatedImage x="0" y="0" width="100" height="100">
              <Variant mode="AMBIENT" target="alpha" value="0"/>
              <AnimatedImages>
                <SequenceImages frameRate="60">
                  <Image resource="frame_1"/>
                  <Image resource="frame_2"/>
                </SequenceImages>
              </AnimatedImages>
            </PartAnimatedImage>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      )

    val report = WffPerformanceAnalyzer.analyze(doc)
    assertEquals(WffUpdateCadence.CONTINUOUS, report.interactiveCadence)
    assertEquals(60, report.estimatedMaxFps)
    assertTrue(report.findings.any { it.code == WffPerformanceCode.HIGH_FPS_ANIMATION })
  }

  @Test
  fun detectsSyncToDeviceSweepFrequency() {
    val doc =
      WffXmlParser.parse(
        """
        <WatchFace width="450" height="450">
          <Scene backgroundColor="#ff000000">
            <AnalogClock x="0" y="0" width="450" height="450">
              <SecondHand resource="second_hand" x="220" y="20" width="10" height="205" pivotX="0.5" pivotY="1.0">
                <Variant mode="AMBIENT" target="alpha" value="0"/>
                <Sweep frequency="SYNC_TO_DEVICE"/>
              </SecondHand>
            </AnalogClock>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      )

    val report = WffPerformanceAnalyzer.analyze(doc)
    assertEquals(WffUpdateCadence.CONTINUOUS, report.interactiveCadence)
    assertEquals(WffUpdateCadence.MINUTE, report.ambientCadence)
    assertEquals(60, report.estimatedMaxFps)
    val codes = report.findings.map { it.code }.toSet()
    assertTrue(WffPerformanceCode.SWEEP_SECOND_HAND in codes)
    assertTrue(WffPerformanceCode.HIGH_FPS_ANIMATION in codes)
  }
}
