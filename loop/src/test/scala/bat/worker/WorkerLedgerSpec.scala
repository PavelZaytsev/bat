package bat.worker

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object WorkerLedgerSpec extends ZIOSpecDefault:
  private val Initial = workspace(0L, "1")
  private val Changed = fingerprint("2")
  private val ImageDigest = "9" * 64

  def spec =
    suite("persistent worker operation ledger")(
      test("replays an identical operation without repeating its effect") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            ledger <- WorkerLedger.open(control, runId("run-replay"), Initial)
            executions <- Ref.make(0)
            operation = readOperation("read-one", Initial)
            effect = executions
              .update(_ + 1)
              .as(
                completed(Initial.fingerprint, "trusted stdout")
              )
            first <- ledger.execute(operation)(effect)
            replay <- ledger.execute(operation)(effect)
            count <- executions.get
          yield assertTrue(
            !first.replayed,
            replay.replayed,
            count == 1,
            replay.receipt == first.receipt,
            utf8(replay.stdout) == "trusted stdout",
            replay.stderr.isEmpty
          )
        }
      },
      test("replays a receipt and bounded artifacts after reopening") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            operation = readOperation("read-persisted", Initial)
            first <- ZIO.scoped {
              WorkerLedger
                .open(control, runId("run-persisted"), Initial)
                .flatMap(
                  _.execute(operation)(
                    ZIO.succeed(completed(Initial.fingerprint, "persisted"))
                  )
                )
            }
            executions <- Ref.make(0)
            replay <- ZIO.scoped {
              WorkerLedger
                .open(control, runId("run-persisted"), Initial)
                .flatMap(
                  _.execute(operation)(
                    executions
                      .update(_ + 1)
                      .as(
                        completed(Initial.fingerprint, "wrong")
                      )
                  )
                )
            }
            count <- executions.get
          yield assertTrue(
            !first.replayed,
            replay.replayed,
            replay.receipt == first.receipt,
            utf8(replay.stdout) == "persisted",
            count == 0
          )
        }
      },
      test("rejects operation ID reuse with changed content before execution") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            ledger <- WorkerLedger.open(control, runId("run-conflict"), Initial)
            original = readOperation("same-id", Initial, request = "a")
            conflicting = readOperation("same-id", Initial, request = "b")
            _ <- ledger.execute(original)(
              ZIO.succeed(completed(Initial.fingerprint, "first"))
            )
            executions <- Ref.make(0)
            result <- ledger
              .execute(conflicting)(
                executions
                  .update(_ + 1)
                  .as(
                    completed(Initial.fingerprint, "second")
                  )
              )
              .either
            count <- executions.get
          yield assertTrue(
            errorCode(result).contains("operation_id_conflict"),
            count == 0
          )
        }
      },
      test(
        "isolates reused provider call IDs across controller attempts"
      ) {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            run = runId("run-attempt-isolation")
            firstAttempt = unsafe(AttemptId.from("attempt-1"))
            resumedAttempt = unsafe(AttemptId.from("attempt-2"))
            firstId = OperationId.derive(
              run,
              firstAttempt,
              "provider-call-1",
              "worker_git_status"
            )
            resumedId = OperationId.derive(
              run,
              resumedAttempt,
              "provider-call-1",
              "worker_git_status"
            )
            ledger <- WorkerLedger.open(control, run, Initial)
            executions <- Ref.make(Chunk.empty[String])
            first <- ledger.execute(
              readOperation(firstId.value, Initial, request = "a")
            )(
              executions
                .update(_ :+ "first")
                .as(completed(Initial.fingerprint, "first"))
            )
            resumed <- ledger.execute(
              readOperation(resumedId.value, Initial, request = "b")
            )(
              executions
                .update(_ :+ "resumed")
                .as(completed(Initial.fingerprint, "resumed"))
            )
            observed <- executions.get
          yield assertTrue(
            firstId != resumedId,
            !first.replayed,
            !resumed.replayed,
            first.receipt.operationId == firstId,
            resumed.receipt.operationId == resumedId,
            utf8(resumed.stdout) == "resumed",
            observed == Chunk("first", "resumed")
          )
        }
      },
      test("rejects stale workspace revisions before executing an effect") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            ledger <- WorkerLedger.open(control, runId("run-stale"), Initial)
            stale = readOperation("stale-read", workspace(1L, "1"))
            executions <- Ref.make(0)
            result <- ledger
              .execute(stale)(
                executions
                  .update(_ + 1)
                  .as(
                    completed(Initial.fingerprint, "must not run")
                  )
              )
              .either
            count <- executions.get
          yield assertTrue(
            errorCode(result).contains("stale_workspace_revision"),
            count == 0
          )
        }
      },
      test("advances a mutation exactly once and rejects its old basis") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            ledger <- WorkerLedger.open(control, runId("run-mutation"), Initial)
            mutation = patchOperation("patch-one", Initial)
            result <- ledger.execute(mutation)(
              ZIO.succeed(completed(Changed, "patched"))
            )
            stale = readOperation("old-basis", Initial)
            staleResult <- ledger
              .execute(stale)(
                ZIO.succeed(completed(Initial.fingerprint, "stale"))
              )
              .either
            current = WorkspacePrecondition(
              unsafe(WorkspaceRevision.from(1L)),
              Changed
            )
            currentResult <- ledger.execute(
              readOperation("new-basis", current)
            )(
              ZIO.succeed(completed(Changed, "current"))
            )
          yield assertTrue(
            result.receipt.beforeRevision.value == 0L,
            result.receipt.afterRevision.value == 1L,
            result.receipt.beforeFingerprint == Initial.fingerprint,
            result.receipt.afterFingerprint == Changed,
            errorCode(staleResult).contains("stale_workspace_revision"),
            !currentResult.replayed
          )
        }
      },
      test(
        "fails closed on a durable pending intent across retries and reopen"
      ) {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            operation = readOperation("pending-one", Initial)
            firstAndRetry <- ZIO.scoped {
              for
                ledger <- WorkerLedger.open(
                  control,
                  runId("run-pending"),
                  Initial
                )
                first <- ledger
                  .execute(operation)(
                    ZIO.fail(
                      WorkerError.ToolFailure(
                        "simulated_crash",
                        "simulated effect failure"
                      )
                    )
                  )
                  .either
                retry <- ledger
                  .execute(operation)(
                    ZIO.succeed(completed(Initial.fingerprint, "retry"))
                  )
                  .either
              yield first -> retry
            }
            reopened <- ZIO.scoped {
              WorkerLedger
                .open(control, runId("run-pending"), Initial)
                .flatMap(
                  _.execute(operation)(
                    ZIO.succeed(completed(Initial.fingerprint, "retry"))
                  )
                )
                .either
            }
          yield assertTrue(
            errorCode(firstAndRetry._1).contains("simulated_crash"),
            errorCode(firstAndRetry._2).contains("indeterminate_operation"),
            errorCode(reopened).contains("indeterminate_operation")
          )
        }
      },
      test(
        "authentically recovers an interrupted read-only intent and retries the same bound ID"
      ) {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            run = runId("run-read-recovery")
            operation = readOperation("recover-read", Initial)
            _ <- ZIO.scoped {
              WorkerLedger
                .open(control, run, Initial)
                .flatMap(
                  _.execute(operation)(simulatedCrash).either.unit
                )
            }
            executions <- Ref.make(0)
            recoveredAndRetried <- ZIO.scoped {
              for
                ledger <- WorkerLedger.open(control, run, Initial)
                pending <- ledger.pendingOperation
                _ <- ZIO.foreachDiscard(pending)(operation =>
                  ledger.recoverInterruptedReadOnly(
                    operation,
                    Initial.fingerprint
                  )
                )
                current <- ledger.currentWorkspace
                retry <- ledger.execute(operation)(
                  executions
                    .update(_ + 1)
                    .as(completed(Initial.fingerprint, "recovered"))
                )
              yield (pending, current, retry)
            }
            replay <- ZIO.scoped {
              WorkerLedger
                .open(control, run, Initial)
                .flatMap(
                  _.execute(operation)(
                    executions
                      .update(_ + 1)
                      .as(completed(Initial.fingerprint, "must not run"))
                  )
                )
            }
            count <- executions.get
          yield assertTrue(
            recoveredAndRetried._1.contains(operation),
            recoveredAndRetried._2 == Initial,
            !recoveredAndRetried._3.replayed,
            replay.replayed,
            utf8(replay.stdout) == "recovered",
            count == 1
          )
        }
      },
      test("keeps a recovered operation ID permanently bound to its input") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            run = runId("run-recovery-binding")
            operation = readOperation("bound-recovery", Initial, request = "a")
            _ <- ZIO.scoped {
              WorkerLedger
                .open(control, run, Initial)
                .flatMap(
                  _.execute(operation)(simulatedCrash).either.unit
                )
            }
            conflict <- ZIO.scoped {
              for
                ledger <- WorkerLedger.open(control, run, Initial)
                pending <- ledger.pendingOperation
                _ <- ZIO.foreachDiscard(pending)(operation =>
                  ledger.recoverInterruptedReadOnly(
                    operation,
                    Initial.fingerprint
                  )
                )
                result <- ledger
                  .execute(
                    readOperation(
                      "bound-recovery",
                      Initial,
                      request = "b"
                    )
                  )(ZIO.succeed(completed(Initial.fingerprint, "wrong")))
                  .either
              yield result
            }
          yield assertTrue(
            errorCode(conflict).contains("operation_id_conflict")
          )
        }
      },
      test("rejects an unauthenticated read-only recovery record on reopen") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            run = runId("run-recovery-tamper")
            operation = readOperation("tampered-recovery", Initial)
            _ <- ZIO.scoped {
              for
                ledger <- WorkerLedger.open(control, run, Initial)
                _ <- ledger.execute(operation)(simulatedCrash).either
                pending <- ledger.pendingOperation
                _ <- ZIO.foreachDiscard(pending)(operation =>
                  ledger.recoverInterruptedReadOnly(
                    operation,
                    Initial.fingerprint
                  )
                )
              yield ()
            }
            ledgerPath = control.resolve(run.value).resolve("operations.log")
            _ <- ZIO.attemptBlocking {
              val lines = Files
                .readString(ledgerPath, StandardCharsets.US_ASCII)
                .linesIterator
                .toVector
              val recoveryIndex = lines.indexWhere(_.startsWith("R|"))
              if recoveryIndex < 0 then
                throw new IllegalStateException("recovery record missing")
              val fields = lines(recoveryIndex).split("\\|", -1)
              fields(9) = "2" * 64
              val tampered = lines.updated(
                recoveryIndex,
                fields.mkString("|")
              )
              val _ = Files.writeString(
                ledgerPath,
                tampered.mkString("\n") + "\n",
                StandardCharsets.US_ASCII,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            reopened <- ZIO.scoped(
              WorkerLedger.open(control, run, Initial).either
            )
          yield assertTrue(errorCode(reopened).contains("invalid_ledger"))
        }
      },
      test("never recovers an interrupted mutating intent") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            run = runId("run-mutation-recovery")
            operation = patchOperation("pending-patch", Initial)
            _ <- ZIO.scoped {
              WorkerLedger
                .open(control, run, Initial)
                .flatMap(
                  _.execute(operation)(simulatedCrash).either.unit
                )
            }
            recovery <- ZIO.scoped {
              for
                ledger <- WorkerLedger.open(control, run, Initial)
                pending <- ledger.pendingOperation
                result <- ZIO
                  .foreachDiscard(pending)(operation =>
                    ledger.recoverInterruptedReadOnly(
                      operation,
                      Initial.fingerprint
                    )
                  )
                  .either
                current <- ledger.currentWorkspace.either
              yield result -> current
            }
            ledgerText <- ZIO.attemptBlocking(
              Files.readString(
                control.resolve(run.value).resolve("operations.log"),
                StandardCharsets.US_ASCII
              )
            )
          yield assertTrue(
            errorCode(recovery._1).contains("indeterminate_operation"),
            errorCode(recovery._2).contains("indeterminate_operation"),
            !ledgerText.linesIterator.exists(_.startsWith("R|"))
          )
        }
      },
      test("does not recover a read-only intent after workspace drift") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            run = runId("run-read-recovery-drift")
            operation = readOperation("drifted-read", Initial)
            _ <- ZIO.scoped {
              WorkerLedger
                .open(control, run, Initial)
                .flatMap(
                  _.execute(operation)(simulatedCrash).either.unit
                )
            }
            recovery <- ZIO.scoped {
              for
                ledger <- WorkerLedger.open(control, run, Initial)
                pending <- ledger.pendingOperation
                result <- ZIO
                  .foreachDiscard(pending)(operation =>
                    ledger.recoverInterruptedReadOnly(operation, Changed)
                  )
                  .either
                current <- ledger.currentWorkspace.either
              yield result -> current
            }
          yield assertTrue(
            errorCode(recovery._1).contains("indeterminate_operation"),
            errorCode(recovery._2).contains("indeterminate_operation")
          )
        }
      },
      test("rejects unexpected authoring changes from a read-only operation") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            ledger <- WorkerLedger.open(
              control,
              runId("run-read-mutation"),
              Initial
            )
            result <- ledger
              .execute(readOperation("bad-read", Initial))(
                ZIO.succeed(completed(Changed, "changed"))
              )
              .either
          yield assertTrue(
            errorCode(result).contains("unexpected_workspace_mutation")
          )
        }
      },
      test(
        "binds receipts to full output, preview, workspace, policy, and image"
      ) {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            ledger <- WorkerLedger.open(control, runId("run-binding"), Initial)
            operation = patchOperation("bound-patch", Initial)
            stdout = "full-output".getBytes(StandardCharsets.UTF_8)
            preview = Chunk.fromArray(stdout.take(4))
            observation = unsafe(
              CommandObservation.make(
                CommandOutcome.Exited(0),
                preview,
                Chunk.empty,
                sha256(stdout),
                sha256(Array.emptyByteArray),
                stdout.length.toLong,
                0L,
                17L
              )
            )
            result <- ledger.execute(operation)(
              ZIO.succeed(CompletedOperation(observation, Changed))
            )
            receipt = result.receipt
          yield assertTrue(
            receipt.policyId == "worker-policy-v1",
            receipt.requestIdentity == "worker-request-v1:patch",
            receipt.imageDigest.exists(_.value == ImageDigest),
            receipt.stdoutDigest.value == sha256(stdout),
            receipt.stdoutBytes == stdout.length.toLong,
            receipt.stdoutPreviewBytes == 4,
            receipt.stdoutPreviewDigest.value == sha256(stdout.take(4)),
            utf8(result.stdout) == "full",
            receipt.beforeFingerprint == Initial.fingerprint,
            receipt.afterFingerprint == Changed
          )
        }
      },
      test("detects ledger authentication corruption on reopen") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            run = runId("run-corrupt-ledger")
            operation = readOperation("read-corrupt", Initial)
            _ <- ZIO.scoped {
              WorkerLedger
                .open(control, run, Initial)
                .flatMap(
                  _.execute(operation)(
                    ZIO.succeed(completed(Initial.fingerprint, "output"))
                  )
                )
            }
            ledgerPath = control.resolve(run.value).resolve("operations.log")
            _ <- ZIO.attemptBlocking {
              val bytes = Files.readAllBytes(ledgerPath)
              bytes(0) = (bytes(0) ^ 1).toByte
              val _ = Files.write(
                ledgerPath,
                bytes,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            reopened <- ZIO.scoped(
              WorkerLedger.open(control, run, Initial).either
            )
          yield assertTrue(errorCode(reopened).contains("invalid_ledger"))
        }
      },
      test("authenticates the canonical request identity across reopen") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            run = runId("run-request-identity")
            operation = readOperation("read-identity", Initial)
            original <- ZIO.scoped {
              WorkerLedger
                .open(control, run, Initial)
                .flatMap(
                  _.execute(operation)(
                    ZIO.succeed(completed(Initial.fingerprint, "output"))
                  )
                )
            }
            replay <- ZIO.scoped {
              WorkerLedger
                .open(control, run, Initial)
                .flatMap(_.lookup(operation.id))
            }
            ledgerPath = control.resolve(run.value).resolve("operations.log")
            _ <- ZIO.attemptBlocking {
              val text = Files.readString(ledgerPath, StandardCharsets.US_ASCII)
              val tampered = text.replace(
                "worker-request-v1:read",
                "worker-request-v1:search"
              )
              if tampered == text then
                throw new IllegalStateException("request identity missing")
              val _ = Files.writeString(
                ledgerPath,
                tampered,
                StandardCharsets.US_ASCII,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            reopened <- ZIO.scoped(
              WorkerLedger.open(control, run, Initial).either
            )
          yield assertTrue(
            original.receipt.requestIdentity == "worker-request-v1:read",
            replay.exists(
              _.requestIdentity == "worker-request-v1:read"
            ),
            errorCode(reopened).contains("invalid_ledger")
          )
        }
      },
      test("detects tampered receipt artifacts before replay") {
        ZIO.scoped {
          for
            control <- temporaryDirectory
            run = runId("run-corrupt-artifact")
            operation = readOperation("artifact-one", Initial)
            _ <- ZIO.scoped {
              WorkerLedger
                .open(control, run, Initial)
                .flatMap(
                  _.execute(operation)(
                    ZIO.succeed(completed(Initial.fingerprint, "original"))
                  )
                )
            }
            artifact = control
              .resolve(run.value)
              .resolve(s"${operation.id.value}.stdout")
            _ <- ZIO.attemptBlocking {
              val _ = Files.writeString(
                artifact,
                "tampered",
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            replay <- ZIO.scoped {
              WorkerLedger
                .open(control, run, Initial)
                .flatMap(
                  _.execute(operation)(
                    ZIO.succeed(completed(Initial.fingerprint, "ignored"))
                  )
                )
                .either
            }
          yield assertTrue(
            errorCode(replay).contains("invalid_output_artifact")
          )
        }
      }
    ) @@ TestAspect.sequential

  private def readOperation(
      id: String,
      basis: WorkspacePrecondition,
      request: String = "a"
  ): WorkerOperation =
    operation(
      id,
      WorkerOperationKind.Read,
      basis,
      request,
      image = None
    )

  private def patchOperation(
      id: String,
      basis: WorkspacePrecondition
  ): WorkerOperation =
    operation(
      id,
      WorkerOperationKind.Patch,
      basis,
      "b",
      image = Some(ImageDigest)
    )

  private def operation(
      id: String,
      kind: WorkerOperationKind,
      basis: WorkspacePrecondition,
      request: String,
      image: Option[String]
  ): WorkerOperation =
    unsafe(
      WorkerOperation.make(
        unsafe(OperationId.from(id)),
        kind,
        request * 64,
        s"worker-request-v1:${kind.wire}",
        basis,
        "worker-policy-v1",
        image
      )
    )

  private def completed(
      after: WorkspaceFingerprint,
      stdout: String
  ): CompletedOperation =
    val stdoutBytes = stdout.getBytes(StandardCharsets.UTF_8)
    val observation = unsafe(
      CommandObservation.make(
        CommandOutcome.Exited(0),
        Chunk.fromArray(stdoutBytes),
        Chunk.empty,
        sha256(stdoutBytes),
        sha256(Array.emptyByteArray),
        stdoutBytes.length.toLong,
        0L,
        1L
      )
    )
    CompletedOperation(observation, after)

  private def simulatedCrash: IO[WorkerError, CompletedOperation] =
    ZIO.fail(
      WorkerError.ToolFailure(
        "simulated_crash",
        "simulated effect failure"
      )
    )

  private def workspace(
      revision: Long,
      fingerprintCharacter: String
  ): WorkspacePrecondition =
    WorkspacePrecondition(
      unsafe(WorkspaceRevision.from(revision)),
      fingerprint(fingerprintCharacter)
    )

  private def fingerprint(character: String): WorkspaceFingerprint =
    unsafe(WorkspaceFingerprint.from(character * 64))

  private def runId(value: String): RunId = unsafe(RunId.from(value))

  private def temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory("bat-worker-ledger-"))
    )(deleteRecursively)

  private def utf8(bytes: Chunk[Byte]): String =
    new String(bytes.toArray, StandardCharsets.UTF_8)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def errorCode[A](value: Either[WorkerError, A]): Option[String] =
    value.left.toOption.map(_.code)

  private def unsafe[A](value: Either[WorkerError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

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
