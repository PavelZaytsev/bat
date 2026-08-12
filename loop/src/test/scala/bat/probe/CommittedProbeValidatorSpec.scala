package bat.probe

import bat.protocol.StrictJson

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.util.HexFormat

import scala.jdk.CollectionConverters.*

import zio.ZIO
import zio.json.ast.Json
import zio.test.*

object CommittedProbeValidatorSpec extends ZIOSpecDefault:
  private val ProbeRoot =
    Path.of("benchmarks", "probes").toAbsolutePath.normalize
  private val RunDirectory = "gpt-oss-20b-exo-single-node-001"

  def spec: Spec[TestEnvironment, Any] =
    suite("committed deployment probe validator")(
      test("accepts the exact committed evidence inventory") {
        val result = CommittedProbeValidator.validate(ProbeRoot)
        assertTrue(
          result.exists(_.runs == 1),
          result.exists(_.runIds == Set("exo-m1pro-20b-live-002"))
        )
      },
      test("rejects an unindexed orphan entry") {
        withFixture { root =>
          ZIO.attemptBlocking(
            Files.writeString(root.resolve("orphan.txt"), "orphan")
          ) *> validate(root).map(result =>
            assertTrue(result.left.exists(_.code == "invalid_probe_inventory"))
          )
        }
      },
      test("rejects symbolic links anywhere in the inventory") {
        withFixture { root =>
          ZIO.attemptBlocking(
            Files.createSymbolicLink(
              root.resolve("linked-readme"),
              Path.of("README.md")
            )
          ) *> validate(root).map(result =>
            assertTrue(
              result.left.exists(
                _.code == "unsafe_probe_inventory_entry"
              )
            )
          )
        }
      },
      test("rejects duplicate run IDs in the authoritative index") {
        withFixture { root =>
          val second = "gpt-oss-20b-exo-single-node-002"
          for
            _ <- ZIO.attemptBlocking(
              copyTree(root.resolve(RunDirectory), root.resolve(second))
            )
            _ <- rewriteJson(root.resolve("index.json")) { index =>
              val runs = arrayField(index, "runs")
              val duplicate = Json.Obj(
                zio.Chunk(
                  "directory" -> Json.Str(second),
                  "run_id" -> Json.Str("exo-m1pro-20b-live-002")
                )
              )
              replace(index, "runs", Json.Arr(runs :+ duplicate))
            }
            result <- validate(root)
          yield assertTrue(
            result.left.exists(_.code == "invalid_probe_inventory")
          )
        }
      },
      test("rejects standalone evidence that differs from the result") {
        withFixture { root =>
          val trace = run(root).resolve("safe-trace.json")
          ZIO.attemptBlocking(
            Files.writeString(trace, Files.readString(trace) + " ")
          ) *> validate(root).map(result =>
            assertTrue(
              result.left.exists(_.code == "invalid_committed_probe")
            )
          )
        }
      },
      test("rejects a digest that does not bind the standalone bytes") {
        withFixture { root =>
          for
            _ <- rewriteResult(root)(result =>
              replace(
                result,
                "safe_trace_sha256",
                Json.Str("0" * 64)
              )
            )
            result <- validate(root)
          yield assertTrue(
            result.left.exists(_.code == "probe_digest_mismatch")
          )
        }
      },
      test("rejects unknown telemetry fields despite valid JSON") {
        withFixture { root =>
          for
            _ <- rewriteBoundTelemetry(root)(telemetry =>
              Json.Obj(telemetry.fields :+ ("unexpected" -> Json.Bool(true)))
            )
            result <- validate(root)
          yield assertTrue(
            result.left.exists(_.code == "invalid_probe_telemetry")
          )
        }
      },
      test("rejects deployment drift between result and telemetry") {
        withFixture { root =>
          for
            _ <- rewriteResult(root) { result =>
              val deployment = objectField(result, "deployment")
              replace(
                result,
                "deployment",
                replace(
                  deployment,
                  "model_id",
                  Json.Str("openai/gpt-oss-120b")
                )
              )
            }
            result <- validate(root)
          yield assertTrue(
            result.left.exists(_.code == "probe_document_mismatch")
          )
        }
      },
      test("rejects a verdict that contradicts terminal telemetry") {
        withFixture { root =>
          for
            _ <- rewriteResult(root)(result =>
              replace(
                replace(
                  result,
                  "verdict",
                  Json.Str("incompatible")
                ),
                "reason_code",
                Json.Str("wire_incompatible")
              )
            )
            result <- validate(root)
          yield assertTrue(
            result.left.exists(
              _.code == "invalid_probe_result_semantics"
            )
          )
        }
      },
      test("rejects an absolute path hidden in otherwise bound evidence") {
        withFixture { root =>
          for
            _ <- rewriteBoundTrace(root) { trace =>
              val events = arrayField(trace, "events")
              val terminal = events.last.asInstanceOf[Json.Obj]
              val bdr = objectField(terminal, "bdr")
              val next = objectField(bdr, "next_action")
              val changed = replace(
                terminal,
                "bdr",
                replace(
                  bdr,
                  "next_action",
                  replace(next, "reason", Json.Str("/private/work/source"))
                )
              )
              replace(
                trace,
                "events",
                Json.Arr(events.dropRight(1) :+ changed)
              )
            }
            result <- validate(root)
          yield assertTrue(
            result.left.exists(_.code == "unsafe_probe_artifact_value")
          )
        }
      },
      test("rejects a hostname hidden in otherwise bound evidence") {
        withFixture { root =>
          for
            _ <- rewriteBoundTrace(root) { trace =>
              val events = arrayField(trace, "events")
              val terminal = events.last.asInstanceOf[Json.Obj]
              val bdr = objectField(terminal, "bdr")
              val next = objectField(bdr, "next_action")
              val changed = replace(
                terminal,
                "bdr",
                replace(
                  bdr,
                  "next_action",
                  replace(next, "reason", Json.Str("model.internal.example"))
                )
              )
              replace(
                trace,
                "events",
                Json.Arr(events.dropRight(1) :+ changed)
              )
            }
            result <- validate(root)
          yield assertTrue(
            result.left.exists(_.code == "unsafe_probe_artifact_value")
          )
        }
      },
      test("rejects summary arithmetic that was edited by hand") {
        withFixture { root =>
          for
            _ <- rewriteBoundTelemetry(root) { telemetry =>
              val summary = objectField(telemetry, "summary")
              replace(
                telemetry,
                "summary",
                replace(summary, "model_turns", Json.Num(BigDecimal(99)))
              )
            }
            result <- validate(root)
          yield assertTrue(
            result.left.exists(_.code == "invalid_probe_telemetry")
          )
        }
      }
    )

  private def validate(root: Path) =
    ZIO.succeed(CommittedProbeValidator.validate(root))

  private def withFixture(
      use: Path => ZIO[Any, Throwable, TestResult]
  ): ZIO[Any, Throwable, TestResult] =
    ZIO.scoped {
      ZIO
        .acquireRelease(
          ZIO.attemptBlocking {
            val temporary = Files.createTempDirectory("bat-probe-validator-")
            val destination = temporary.resolve("probes")
            copyTree(ProbeRoot, destination)
            temporary -> destination
          }
        )(value => ZIO.attemptBlocking(deleteTree(value._1)).orDie)
        .flatMap { case (_, destination) =>
          use(destination)
        }
    }

  private def rewriteBoundTelemetry(
      root: Path
  )(change: Json.Obj => Json.Obj): ZIO[Any, Throwable, Unit] =
    val telemetryPath = run(root).resolve("telemetry.json")
    for
      telemetry <- readJson(telemetryPath)
      changed = change(telemetry)
      text = canonical(changed)
      _ <- ZIO.attemptBlocking(Files.writeString(telemetryPath, text))
      _ <- rewriteResult(root)(result =>
        replace(
          replace(result, "telemetry", changed),
          "telemetry_sha256",
          Json.Str(sha256(text))
        )
      )
    yield ()

  private def rewriteBoundTrace(
      root: Path
  )(change: Json.Obj => Json.Obj): ZIO[Any, Throwable, Unit] =
    val tracePath = run(root).resolve("safe-trace.json")
    for
      trace <- readJson(tracePath)
      changed = change(trace)
      // Safe traces use their typed field order rather than sorted canonical
      // order. This mutation is intentionally sorted; the redaction scan must
      // reject it before encoding checks become relevant.
      text = canonical(changed)
      _ <- ZIO.attemptBlocking(Files.writeString(tracePath, text))
      _ <- rewriteResult(root)(result =>
        replace(
          replace(result, "safe_trace", changed),
          "safe_trace_sha256",
          Json.Str(sha256(text))
        )
      )
    yield ()

  private def rewriteResult(
      root: Path
  )(change: Json.Obj => Json.Obj): ZIO[Any, Throwable, Unit] =
    rewriteJson(run(root).resolve("result.json"))(change)

  private def rewriteJson(
      path: Path
  )(change: Json.Obj => Json.Obj): ZIO[Any, Throwable, Unit] =
    for
      value <- readJson(path)
      changed = change(value)
      _ <- ZIO.attemptBlocking(Files.writeString(path, canonical(changed)))
    yield ()

  private def readJson(path: Path): ZIO[Any, Throwable, Json.Obj] =
    ZIO.attemptBlocking {
      StrictJson
        .parseObject(Files.readString(path), "validator test")
        .fold(
          error => throw IllegalArgumentException(error.safeMessage),
          identity
        )
    }

  private def canonical(value: Json): String =
    StrictJson
      .canonical(value, "validator test")
      .fold(
        error => throw IllegalArgumentException(error.safeMessage),
        identity
      )

  private def objectField(value: Json.Obj, name: String): Json.Obj =
    value.fields
      .collectFirst { case (`name`, child: Json.Obj) => child }
      .getOrElse(throw IllegalArgumentException(s"missing object $name"))

  private def arrayField(value: Json.Obj, name: String): zio.Chunk[Json] =
    value.fields
      .collectFirst { case (`name`, Json.Arr(children)) => children }
      .getOrElse(throw IllegalArgumentException(s"missing array $name"))

  private def replace(
      value: Json.Obj,
      name: String,
      replacement: Json
  ): Json.Obj =
    if !value.fields.exists(_._1 == name) then
      throw IllegalArgumentException(s"missing field $name")
    Json.Obj(value.fields.map {
      case (`name`, _) => name -> replacement
      case other       => other
    })

  private def run(root: Path): Path = root.resolve(RunDirectory)

  private def sha256(value: String): String =
    HexFormat
      .of()
      .formatHex(
        MessageDigest
          .getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8))
      )

  private def copyTree(source: Path, destination: Path): Unit =
    val stream = Files.walk(source)
    try
      stream.iterator().asScala.foreach { path =>
        val target = destination.resolve(source.relativize(path))
        if Files.isDirectory(path) then
          val _ = Files.createDirectories(target)
        else
          val _ = Files.copy(
            path,
            target,
            StandardCopyOption.COPY_ATTRIBUTES
          )
      }
    finally stream.close()

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)(using Ordering.Int.reverse)
          .foreach(path => Files.deleteIfExists(path))
      finally stream.close()
