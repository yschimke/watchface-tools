package ee.schimke.watchface.apk

import java.util.concurrent.CountDownLatch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test

internal fun runAsync(block: suspend () -> Unit) {
  var failure: Throwable? = null
  val done = CountDownLatch(1)
  block.startCoroutine(
    Continuation(EmptyCoroutineContext) {
      failure = it.exceptionOrNull()
      done.countDown()
    },
  )
  done.await()
  failure?.let { throw it }
}

class StructureJvmTest {
  @Test
  fun structure() = runAsync {
    for ((name, case) in ApkStructureCases.cases) {
      try {
        case()
      } catch (e: Throwable) {
        throw AssertionError("$name: ${e.message}", e)
      }
    }
  }
}
