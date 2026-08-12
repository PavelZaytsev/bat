package bat.worker

import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.ast.Json
import zio.test.*

object WorkerEvidenceSpec extends ZIOSpecDefault:
  private val Initial = WorkspacePrecondition(
    unsafe(WorkspaceRevision.from(0L)),
    unsafe(WorkspaceFingerprint.from("1" * 64))
  )
  private val ImageDigest = "9" * 64

  def spec =
    suite("trusted worker evidence")(
      test("distinguishes a full suite from a focused test selector") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            ledger <- WorkerLedger.open(
              control,
              unsafe(RunId.from("run-evidence-scope")),
              Initial
            )
            full = operation(
              "full-suite",
              "a" * 64,
              "java-build-v1:maven_test:full"
            )
            focused = operation(
              "focused-test",
              "b" * 64,
              "java-build-v1:maven_test:selector=com.acme.CacheTest#evictsEntry"
            )
            fullResult <- ledger.execute(full)(completed())
            focusedResult <- ledger.execute(focused)(completed())
            fullEvidence <- TrustedEvidenceMaterializer.materialize(
              fullResult.receipt,
              Initial
            )
            focusedEvidence <- TrustedEvidenceMaterializer.materialize(
              focusedResult.receipt,
              Initial
            )
          yield assertTrue(
            stringField(fullEvidence, "command").contains(
              "java-build-v1:maven_test:full"
            ),
            stringField(focusedEvidence, "command").contains(
              "java-build-v1:maven_test:selector=com.acme.CacheTest#evictsEntry"
            ),
            stringField(fullEvidence, "policy").contains("java-v1"),
            stringField(focusedEvidence, "policy").contains("java-v1"),
            stringField(fullEvidence, "image_sha256").contains(ImageDigest),
            stringField(focusedEvidence, "image_sha256").contains(
              ImageDigest
            ),
            stringField(fullEvidence, "request_sha256").contains("a" * 64),
            stringField(focusedEvidence, "request_sha256").contains("b" * 64),
            fullResult.receipt.requestIdentity !=
              focusedResult.receipt.requestIdentity
          )
        }
      },
      test("rejects evidence from a different workspace revision") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            ledger <- WorkerLedger.open(
              control,
              unsafe(RunId.from("run-evidence-workspace")),
              Initial
            )
            result <- ledger.execute(
              operation(
                "focused-workspace",
                "c" * 64,
                "java-build-v1:maven_test:selector=CacheTest"
              )
            )(completed())
            other = WorkspacePrecondition(
              unsafe(WorkspaceRevision.from(1L)),
              Initial.fingerprint
            )
            evidence <- TrustedEvidenceMaterializer
              .materialize(result.receipt, other)
              .either
          yield assertTrue(
            evidence.left.toOption.exists(
              _.code == "receipt_workspace_mismatch"
            )
          )
        }
      },
      test("rejects non-verification and non-exited receipts") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            ledger <- WorkerLedger.open(
              control,
              unsafe(RunId.from("run-evidence-kind")),
              Initial
            )
            nonVerification <- ledger.execute(
              operation(
                "not-verification",
                "d" * 64,
                "worker-request-v1:read",
                WorkerOperationKind.Read
              )
            )(completed())
            timedOut <- ledger.execute(
              operation(
                "timed-out",
                "e" * 64,
                "java-build-v1:maven_test:full"
              )
            )(completed(CommandOutcome.TimedOut))
            wrongKind <- TrustedEvidenceMaterializer
              .materialize(nonVerification.receipt, Initial)
              .either
            wrongOutcome <- TrustedEvidenceMaterializer
              .materialize(timedOut.receipt, Initial)
              .either
          yield assertTrue(
            wrongKind.left.toOption.exists(
              _.code == "receipt_not_verification"
            ),
            wrongOutcome.left.toOption.exists(
              _.code == "receipt_not_successful_process"
            )
          )
        }
      }
    ) @@ TestAspect.sequential

  private def operation(
      id: String,
      digest: String,
      identity: String,
      kind: WorkerOperationKind = WorkerOperationKind.MavenTest
  ): WorkerOperation =
    unsafe(
      WorkerOperation.make(
        unsafe(OperationId.from(id)),
        kind,
        digest,
        identity,
        Initial,
        "java-v1",
        Some(ImageDigest)
      )
    )

  private def completed(
      outcome: CommandOutcome = CommandOutcome.Exited(0)
  ): UIO[CompletedOperation] =
    ZIO.succeed(
      CompletedOperation(
        unsafe(
          CommandObservation.make(
            outcome,
            Chunk.empty,
            Chunk.empty,
            sha256(Array.emptyByteArray),
            sha256(Array.emptyByteArray),
            0L,
            0L,
            1L
          )
        ),
        Initial.fingerprint
      )
    )

  private def stringField(value: Json.Obj, name: String): Option[String] =
    value.fields.collectFirst { case (`name`, Json.Str(text)) => text }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory("bat-worker-evidence-"))
    )(deleteRecursively)

  private def deleteRecursively(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path) then
        val stream = Files.walk(path)
        try
          stream
            .iterator()
            .asScala
            .toList
            .sortBy(_.getNameCount)
            .reverse
            .foreach(candidate => {
              val _ = Files.deleteIfExists(candidate)
            })
        finally stream.close()
    }.ignore

  private def unsafe[A](value: Either[WorkerError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )
