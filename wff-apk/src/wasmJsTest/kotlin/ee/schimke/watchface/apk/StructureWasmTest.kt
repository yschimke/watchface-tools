package ee.schimke.watchface.apk

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.test.Test

/** Runs a suspending body to completion and hands the test framework the promise for it. */
internal fun runAsync(block: suspend () -> Unit): Promise<JsAny?> = Promise { resolve, reject ->
  block.startCoroutine(
    Continuation(EmptyCoroutineContext) { result ->
      result.fold(
        onSuccess = { resolve(null) },
        onFailure = { reject((it.message ?: it.toString()).toJsString()) },
      )
    },
  )
}

class StructureWasmTest {
  @Test
  fun structure(): Promise<JsAny?> = runAsync {
    for ((name, case) in ApkStructureCases.cases) {
      try {
        case()
      } catch (e: Throwable) {
        throw AssertionError("$name: ${e.message}", e)
      }
    }
  }
}
