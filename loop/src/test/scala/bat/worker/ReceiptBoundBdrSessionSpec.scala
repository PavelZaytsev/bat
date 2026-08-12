package bat.worker

import bat.bdr.{BdrSession, ValidatedBdrState}
import bat.protocol.BatError

import zio.*
import zio.json.ast.Json
import zio.test.*

object ReceiptBoundBdrSessionSpec extends ZIOSpecDefault:
  def spec =
    suite("receipt-bound BDR session")(
      test(
        "materializes receipt references in every command evidence surface"
      ) {
        for
          captured <- Ref.make(Chunk.empty[Json.Obj])
          resolved <- Ref.make(Chunk.empty[String])
          delegate = recordingSession(captured)
          session = ReceiptBoundBdrSession.wrap(
            delegate,
            receiptId =>
              resolved.update(_ :+ receiptId).as(canonical(receiptId))
          )
          _ <- ZIO.foreachDiscard(protectedOperations) { operation =>
            session.apply(operation)
          }
          seen <- captured.get
          receiptIds <- resolved.get
        yield assertTrue(
          seen.length == protectedOperations.length,
          receiptIds == Chunk("baseline", "phase", "evidence", "fixed"),
          seen.indices.forall { index =>
            commandRecords(
              seen(index),
              expectedContainerNames(index)
            ).contains(
              Chunk(canonical(receiptIds(index)))
            )
          }
        )
      },
      test("materializes protected evidence inside a batch") {
        for
          captured <- Ref.make(Chunk.empty[Json.Obj])
          session = ReceiptBoundBdrSession.wrap(
            recordingSession(captured),
            receiptId => ZIO.succeed(canonical(receiptId))
          )
          _ <- session.apply(
            obj(
              "type" -> Json.Str("batch"),
              "operations" -> Json.Arr(
                obj(
                  "type" -> Json.Str("add_evidence"),
                  "evidence" -> obj(
                    "kind" -> Json.Str("test"),
                    "commands" -> receiptCommands("batch-receipt")
                  )
                ),
                obj(
                  "type" -> Json.Str("add_finding"),
                  "notes" -> Json.Str("ordinary operation data")
                )
              )
            )
          )
          seen <- captured.get
          children = seen.head.fields.collectFirst {
            case ("operations", Json.Arr(values)) => values
          }
          materialized = children.flatMap(_.headOption).collect {
            case child: Json.Obj => commandRecords(child, "evidence")
          }
        yield assertTrue(
          materialized.flatten.contains(Chunk(canonical("batch-receipt"))),
          children.flatMap(_.drop(1).headOption).exists {
            case child: Json.Obj =>
              child.fields.contains(
                "notes" -> Json.Str("ordinary operation data")
              )
            case _ => false
          }
        )
      },
      test("rejects literal and altered command records before BDR apply") {
        val literal = obj(
          "command" -> Json.Str("mvn test"),
          "exit_code" -> Json.Num(BigDecimal(0))
        )
        val altered = obj(
          "receipt_id" -> Json.Str("trusted-receipt"),
          "exit_code" -> Json.Num(BigDecimal(0))
        )
        for
          captured <- Ref.make(Chunk.empty[Json.Obj])
          resolves <- Ref.make(0)
          session = ReceiptBoundBdrSession.wrap(
            recordingSession(captured),
            receiptId => resolves.update(_ + 1).as(canonical(receiptId))
          )
          failures <- ZIO.foreach(Chunk(literal, altered)) { command =>
            session
              .apply(
                obj(
                  "type" -> Json.Str("set_baseline"),
                  "baseline" -> obj(
                    "usable" -> Json.Bool(true),
                    "commands" -> Json.Arr(command)
                  )
                )
              )
              .either
          }
          calls <- captured.get
          resolutionCount <- resolves.get
        yield assertTrue(
          failures.forall(
            _.left.toOption.exists(_.code == "untrusted_command_evidence")
          ),
          calls.isEmpty,
          resolutionCount == 0
        )
      },
      test("propagates private-ledger receipt failures and never applies") {
        val failures = Map(
          "unknown" -> WorkerError.LedgerFailure(
            "unknown_receipt",
            "unknown"
          ),
          "stale" -> WorkerError.LedgerFailure(
            "receipt_workspace_mismatch",
            "stale"
          ),
          "non-verification" -> WorkerError.LedgerFailure(
            "receipt_not_verification",
            "wrong kind"
          )
        )
        for
          captured <- Ref.make(Chunk.empty[Json.Obj])
          session = ReceiptBoundBdrSession.wrap(
            recordingSession(captured),
            receiptId => ZIO.fail(failures(receiptId))
          )
          results <- ZIO.foreach(
            Chunk.fromIterable(failures.keys.toList.sorted)
          ) { receiptId =>
            session
              .apply(
                obj(
                  "type" -> Json.Str("finish_phase"),
                  "gate" -> obj(
                    "commands" -> receiptCommands(receiptId)
                  )
                )
              )
              .either
          }
          calls <- captured.get
        yield assertTrue(
          results.flatMap(_.left.toOption.map(_.code)).toSet ==
            failures.values.map(_.code).toSet,
          calls.isEmpty
        )
      },
      test("rejects command keys outside exact receipt-reference locations") {
        val operations = Chunk(
          obj(
            "type" -> Json.Str("add_finding"),
            "finding" -> obj(
              "metadata" -> obj(
                "commands" -> receiptCommands("unknown-operation")
              )
            )
          ),
          obj(
            "type" -> Json.Str("add_evidence"),
            "evidence" -> obj(
              "kind" -> Json.Str("test"),
              "metadata" -> obj(
                "commands" -> receiptCommands("nested-drift")
              )
            )
          ),
          obj(
            "type" -> Json.Str("batch"),
            "commands" -> receiptCommands("batch-envelope"),
            "operations" -> Json.Arr(
              obj(
                "type" -> Json.Str("add_evidence"),
                "evidence" -> obj(
                  "kind" -> Json.Str("observation")
                )
              )
            )
          ),
          obj(
            "type" -> Json.Str("batch"),
            "operations" -> Json.Arr(
              Json.Arr(
                obj(
                  "commands" -> receiptCommands("non-object-child")
                )
              )
            )
          )
        )
        for
          captured <- Ref.make(Chunk.empty[Json.Obj])
          resolves <- Ref.make(0)
          session = ReceiptBoundBdrSession.wrap(
            recordingSession(captured),
            receiptId => resolves.update(_ + 1).as(canonical(receiptId))
          )
          results <- ZIO.foreach(operations)(session.apply(_).either)
          calls <- captured.get
          resolutionCount <- resolves.get
        yield assertTrue(
          results.forall(
            _.left.toOption.exists(_.code == "untrusted_command_evidence")
          ),
          calls.isEmpty,
          resolutionCount == 0
        )
      },
      test(
        "rejects model-authored controller authority directly and in batches"
      ) {
        val direct = obj(
          "type" -> Json.Str("add_evidence"),
          "evidence" -> obj(
            "id" -> Json.Str("E-HUMAN"),
            "kind" -> Json.Str("human_approval")
          )
        )
        val batch = obj(
          "type" -> Json.Str("batch"),
          "operations" -> Json.Arr(
            obj(
              "type" -> Json.Str("add_evidence"),
              "evidence" -> obj(
                "id" -> Json.Str("E-RESUME"),
                "kind" -> Json.Str("resume")
              )
            )
          )
        )
        for
          captured <- Ref.make(Chunk.empty[Json.Obj])
          session = ReceiptBoundBdrSession.wrap(
            recordingSession(captured),
            receiptId => ZIO.succeed(canonical(receiptId))
          )
          results <- ZIO.foreach(Chunk(direct, batch))(session.apply(_).either)
          calls <- captured.get
        yield assertTrue(
          results.forall(
            _.left.toOption.exists(_.code == "untrusted_controller_evidence")
          ),
          calls.isEmpty
        )
      },
      test("keeps GitHub projection disabled directly and in batches") {
        val direct = obj(
          "type" -> Json.Str("configure_github"),
          "mode" -> Json.Str("sync")
        )
        val batch = obj(
          "type" -> Json.Str("batch"),
          "operations" -> Json.Arr(
            obj(
              "type" -> Json.Str("map_issue"),
              "local_id" -> Json.Str("F-0001"),
              "issue" -> Json.Num(BigDecimal(42))
            )
          )
        )
        for
          captured <- Ref.make(Chunk.empty[Json.Obj])
          session = ReceiptBoundBdrSession.wrap(
            recordingSession(captured),
            receiptId => ZIO.succeed(canonical(receiptId))
          )
          results <- ZIO.foreach(Chunk(direct, batch))(session.apply(_).either)
          calls <- captured.get
        yield assertTrue(
          results.forall(
            _.left.toOption.exists(_.code == "disabled_production_operation")
          ),
          calls.isEmpty
        )
      }
    )

  private val protectedOperations: Chunk[Json.Obj] = Chunk(
    obj(
      "type" -> Json.Str("set_baseline"),
      "baseline" -> obj(
        "usable" -> Json.Bool(true),
        "commands" -> receiptCommands("baseline")
      )
    ),
    obj(
      "type" -> Json.Str("finish_phase"),
      "gate" -> obj("commands" -> receiptCommands("phase"))
    ),
    obj(
      "type" -> Json.Str("add_evidence"),
      "evidence" -> obj(
        "kind" -> Json.Str("verification"),
        "commands" -> receiptCommands("evidence")
      )
    ),
    obj(
      "type" -> Json.Str("record_fixed_point"),
      "pass" -> obj("commands" -> receiptCommands("fixed"))
    )
  )

  private val expectedContainerNames =
    Chunk("baseline", "gate", "evidence", "pass")

  private def receiptCommands(receiptId: String): Json.Arr =
    Json.Arr(obj("receipt_id" -> Json.Str(receiptId)))

  private def canonical(receiptId: String): Json.Obj =
    obj(
      "command" -> Json.Str(s"java-build-v1:maven_test:$receiptId"),
      "policy" -> Json.Str("java-v1"),
      "request_sha256" -> Json.Str("a" * 64),
      "exit_code" -> Json.Num(BigDecimal(0)),
      "artifact" -> Json.Str(
        s"bat-receipt:$receiptId;stdout-sha256:${"b" * 64};stderr-sha256:${"c" * 64}"
      )
    )

  private def commandRecords(
      operation: Json.Obj,
      containerName: String
  ): Option[Chunk[Json.Obj]] =
    for
      container <- operation.fields.collectFirst {
        case (`containerName`, value: Json.Obj) => value
      }
      commands <- container.fields.collectFirst {
        case ("commands", Json.Arr(values)) => values
      }
    yield commands.collect { case value: Json.Obj => value }

  private def recordingSession(
      captured: Ref[Chunk[Json.Obj]]
  ): BdrSession =
    new BdrSession:
      val engineCommit: String = "d" * 40
      val actor: String = "test"

      def current: IO[BatError, ValidatedBdrState] = ZIO.dieMessage("unused")
      def checkpoint: IO[BatError, ValidatedBdrState] =
        ZIO.dieMessage("unused")

      def apply(operation: Json.Obj): IO[BatError, Json.Obj] =
        captured.update(_ :+ operation).as(obj("accepted" -> Json.Bool(true)))

      def auditSummary: IO[BatError, Json] = ZIO.dieMessage("unused")
      def completionCheck: IO[BatError, Json.Obj] = ZIO.dieMessage("unused")

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))
