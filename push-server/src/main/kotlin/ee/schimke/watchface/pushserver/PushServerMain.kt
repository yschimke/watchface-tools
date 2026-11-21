package ee.schimke.watchface.pushserver

import ee.schimke.watchface.packager.AndroidSdk
import ee.schimke.watchface.packager.SigningConfig
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
  val env = System.getenv()
  var host = env["HOST"]?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
  var port = env["PORT"]?.toIntOrNull() ?: 8080
  var concurrency = env["PUSH_SERVER_CONCURRENCY"]?.toIntOrNull() ?: PushServer.DEFAULT_CONCURRENCY

  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--host" -> host = args.getOrNull(++i) ?: error("--host requires a value")
      "--port" ->
        port = args.getOrNull(++i)?.toIntOrNull() ?: error("--port requires an integer value")
      "--concurrency" ->
        concurrency =
          args.getOrNull(++i)?.toIntOrNull() ?: error("--concurrency requires an integer value")
      "-h",
      "--help" -> {
        println("watchface-push-server [--host 0.0.0.0] [--port 8080] [--concurrency 2]")
        return
      }
      else -> error("Unknown argument: ${args[i]}")
    }
    i++
  }

  val signing = PushServerSigning.fromEnvironment(env)
  val debugKeyDir = SigningConfig.defaultDebugKeyDirectory()
  val sdk = AndroidSdk.locate()
  val server =
    PushServer(
      host = host,
      port = port,
      signing = signing,
      debugKeyDirectory = debugKeyDir,
      concurrency = concurrency,
    )
  server.start()
  println(
    "watchface-push-server listening on http://$host:${server.boundPort} " +
      "(sdk=${sdk != null}, signing=${signing::class.simpleName})",
  )
  when (signing) {
    is PushServerSigning.Configured ->
      println("marketplace signing certificate SHA-256: ${signing.certificateSha256}")
    is PushServerSigning.Unconfigured -> System.err.println("note: ${signing.reason}")
    is PushServerSigning.Invalid -> System.err.println("warning: ${signing.reason}")
  }

  val latch = CountDownLatch(1)
  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        server.close()
        latch.countDown()
      },
    )
  latch.await()
}
