package bat.bdr

import bat.protocol.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.ast.Json
import zio.test.*

object BdrSessionSpec extends ZIOSpecDefault:
  private val EngineSourceRoot =
    Path.of("/trusted/bat-engine").toAbsolutePath.normalize
  private val EngineEntryPoint = Path.of("bin", "fake-bdr")
  private val EngineArgv =
    Chunk(EngineSourceRoot.resolve(EngineEntryPoint).toString)
  private val EngineCommit = "0123456789abcdef0123456789abcdef01234567"
  private val Actor = "bat-test"
  private val StatePath = Path.of(".bdr", "progress.yaml")
  private val RunState = "refactoring"

  private final case class TrackerIdentity(
      repository: String,
      baseSha: String,
      headSha: String,
      runId: String,
      maxFixedPointPasses: Int,
      maxPhaseAttempts: Int,
      githubProjection: String = "off",
      revision: Long = 0L
  )

  private object TrackerIdentity:
    def from(initialization: BdrInitialization): TrackerIdentity =
      TrackerIdentity(
        initialization.repository,
        initialization.baseSha,
        initialization.headSha,
        initialization.runId,
        initialization.maxFixedPointPasses,
        initialization.maxPhaseAttempts
      )

  private final case class CompletionMutation(
      revision: Long,
      runState: String,
      tracker: String
  )

  def spec =
    suite("BdrSession")(
      test("validates every security-relevant configuration input") {
        val repository = Path.of("repository")
        val invalid = Chunk(
          BdrConfig.make(
            Chunk.empty,
            repository,
            engineCommit = EngineCommit,
            engineSourceRoot = EngineSourceRoot,
            engineEntryPoint = EngineEntryPoint
          ),
          BdrConfig.make(
            Chunk(""),
            repository,
            engineCommit = EngineCommit,
            engineSourceRoot = EngineSourceRoot,
            engineEntryPoint = EngineEntryPoint
          ),
          BdrConfig.make(
            EngineArgv,
            null,
            engineCommit = EngineCommit,
            engineSourceRoot = EngineSourceRoot,
            engineEntryPoint = EngineEntryPoint
          ),
          BdrConfig.make(
            EngineArgv,
            repository,
            statePath = Path.of("/outside/progress.yaml"),
            engineCommit = EngineCommit,
            engineSourceRoot = EngineSourceRoot,
            engineEntryPoint = EngineEntryPoint
          ),
          BdrConfig.make(
            EngineArgv,
            repository,
            statePath = Path.of("..", "progress.yaml"),
            engineCommit = EngineCommit,
            engineSourceRoot = EngineSourceRoot,
            engineEntryPoint = EngineEntryPoint
          ),
          BdrConfig.make(
            EngineArgv,
            repository,
            commandTimeout = Duration.Zero,
            engineCommit = EngineCommit,
            engineSourceRoot = EngineSourceRoot,
            engineEntryPoint = EngineEntryPoint
          ),
          BdrConfig.make(
            EngineArgv,
            repository,
            commandTimeout = Duration.Infinity,
            engineCommit = EngineCommit,
            engineSourceRoot = EngineSourceRoot,
            engineEntryPoint = EngineEntryPoint
          ),
          BdrConfig.make(
            EngineArgv,
            repository,
            actor = " ",
            engineCommit = EngineCommit,
            engineSourceRoot = EngineSourceRoot,
            engineEntryPoint = EngineEntryPoint
          ),
          BdrConfig.make(
            EngineArgv,
            repository,
            engineCommit = "latest",
            engineSourceRoot = EngineSourceRoot,
            engineEntryPoint = EngineEntryPoint
          )
        )
        val codes = invalid.map(_.left.toOption.map(_.code))
        val valid = BdrConfig.make(
          EngineArgv,
          repository,
          actor = Actor,
          engineCommit = EngineCommit,
          engineSourceRoot = EngineSourceRoot,
          engineEntryPoint = EngineEntryPoint
        )

        ZIO.succeed(
          assertTrue(
            codes == Chunk(
              Some("invalid_engine_command"),
              Some("invalid_engine_command"),
              Some("invalid_repository"),
              Some("invalid_state_path"),
              Some("invalid_state_path"),
              Some("invalid_timeout"),
              Some("invalid_timeout"),
              Some("invalid_actor"),
              Some("invalid_engine_commit")
            ),
            valid.isRight
          )
        )
      },
      test("validates immutable initialization pins and limits") {
        val valid = BdrInitialization.make(
          baseSha = "0" * 40,
          headSha = "1" * 40,
          repository = "bat/example-java-six-phase",
          runId = "BAT-TOY-001"
        )
        val invalid = Chunk(
          BdrInitialization.make(
            "base",
            "1" * 40,
            "bat/example",
            "BAT-TOY"
          ),
          BdrInitialization.make(
            "0" * 40,
            "head",
            "bat/example",
            "BAT-TOY"
          ),
          BdrInitialization.make(
            "0" * 40,
            "0" * 40,
            "bat/example",
            "BAT-TOY"
          ),
          BdrInitialization.make(
            "0" * 40,
            "1" * 40,
            "bad repository name",
            "BAT-TOY"
          ),
          BdrInitialization.make(
            "0" * 40,
            "1" * 40,
            "bat/example",
            "bad run id"
          ),
          BdrInitialization.make(
            "0" * 40,
            "1" * 40,
            "bat/example",
            "BAT-TOY",
            maxFixedPointPasses = 0
          ),
          BdrInitialization.make(
            "0" * 40,
            "1" * 40,
            "bat/example",
            "BAT-TOY",
            maxPhaseAttempts = 101
          )
        )

        assertTrue(
          valid.isRight,
          invalid.map(_.left.toOption.map(_.code)) == Chunk(
            Some("invalid_base_sha"),
            Some("invalid_head_sha"),
            Some("invalid_commit_range"),
            Some("invalid_repository_identity"),
            Some("invalid_run_id"),
            Some("invalid_fixed_point_limit"),
            Some("invalid_phase_attempt_limit")
          )
        )
      },
      test("initializes a pinned tracker before opening the live session") {
        ZIO.scoped {
          for
            repository <- temporaryDirectory("bat-bdr-initialize-")
            initialization <- ZIO.fromEither(
              BdrInitialization.make(
                "0" * 40,
                "1" * 40,
                "bat/example-java-six-phase",
                "BAT-TOY-001"
              )
            )
            runner <- RecordingRunner.make { invocation =>
              verb(invocation) match
                case "init" =>
                  writeTrackerText(
                    repository,
                    initializedTrackerJson(
                      TrackerIdentity.from(initialization)
                    )
                  ).orDie *>
                    ZIO.succeed(
                      success(
                        """{"initialized":".bdr/progress.yaml","revision":0,"run_id":"BAT-TOY-001"}"""
                      )
                    )
                case "check" =>
                  ZIO.succeed(success(checkJson(0L, "refactoring")))
                case "status" =>
                  ZIO.succeed(success(statusJson(0L, "refactoring")))
                case other => unexpected(other)
            }
            config <- makeConfig(repository)
            session <- BdrSession.initialize(
              config,
              initialization,
              runner,
              AcceptingEngineVerifier
            )
            state <- session.current
            calls <- runner.calls
          yield assertTrue(
            state.revision.value == 0L,
            calls.map(invocation => verb(invocation)) == Chunk(
              "init",
              "check",
              "status"
            ),
            calls.head.command == initializeCommand(initialization),
            calls.head.input.isEmpty
          )
        }
      },
      test("rejects a tracker that changes BAT-owned initialization identity") {
        val attempt =
          for
            initialization <- ZIO.fromEither(
              BdrInitialization.make(
                "0" * 40,
                "1" * 40,
                "bat/example-java-six-phase",
                "BAT-TOY-001",
                maxFixedPointPasses = 4,
                maxPhaseAttempts = 5
              )
            )
            expected = TrackerIdentity.from(initialization)
            mismatches = Chunk(
              expected.copy(repository = "bat/wrong"),
              expected.copy(baseSha = "2" * 40),
              expected.copy(headSha = "3" * 40),
              expected.copy(runId = "BAT-WRONG"),
              expected.copy(maxFixedPointPasses = 3),
              expected.copy(maxPhaseAttempts = 3),
              expected.copy(githubProjection = "outbox"),
              expected.copy(revision = 1L)
            )
            results <- ZIO.foreach(mismatches) { persisted =>
              ZIO.scoped {
                for
                  repository <- temporaryDirectory("bat-bdr-init-binding-")
                  runner <- RecordingRunner.make { invocation =>
                    verb(invocation) match
                      case "init" =>
                        writeTrackerText(
                          repository,
                          initializedTrackerJson(persisted)
                        ).orDie *>
                          ZIO.succeed(
                            success(
                              """{"initialized":".bdr/progress.yaml","revision":0,"run_id":"BAT-TOY-001"}"""
                            )
                          )
                      case "check" =>
                        ZIO.succeed(
                          success(
                            checkJson(persisted.revision, "refactoring")
                          )
                        )
                      case "status" =>
                        ZIO.succeed(
                          success(
                            statusJson(persisted.revision, "refactoring")
                          )
                        )
                      case other => unexpected(other)
                  }
                  config <- makeConfig(repository)
                  result <- BdrSession
                    .initialize(
                      config,
                      initialization,
                      runner,
                      AcceptingEngineVerifier
                    )
                    .either
                yield result
              }
            }
          yield assertTrue(
            results.forall(result =>
              errorCode(result).contains("initialization_identity_mismatch")
            )
          )

        attempt
      },
      test("accepts a checkpoint only when check, status, and tracker agree") {
        ZIO.scoped {
          for
            repository <- temporaryRepository(7L, RunState)
            runner <- RecordingRunner.make(fixedSnapshot(7L, RunState))
            config <- makeConfig(repository)
            session <- resume(config, runner)
            state <- session.current
            calls <- runner.calls
          yield assertTrue(
            session.engineCommit == EngineCommit,
            session.actor == Actor,
            state.repository == repository.toAbsolutePath.normalize,
            state.statePath == StatePath,
            state.revision.value == 7L,
            state.runState == RunState,
            field(state.nextAction, "kind").contains(Json.Str("continue")),
            state.view.stateDigest.matches("[0-9a-f]{64}"),
            calls.map(_.command) == Chunk(checkCommand, statusCommand),
            calls.forall(_.cwd == repository.toAbsolutePath.normalize),
            calls.forall(_.input.isEmpty)
          )
        }
      },
      test("rejects check/status and tracker disagreements as stale") {
        ZIO.scoped {
          for
            statusMismatchRepository <- temporaryRepository(4L, RunState)
            statusMismatchRunner <- RecordingRunner.make { invocation =>
              verb(invocation) match
                case "check"  => ZIO.succeed(success(checkJson(4L, RunState)))
                case "status" => ZIO.succeed(success(statusJson(5L, RunState)))
                case other    => unexpected(other)
            }
            statusConfig <- makeConfig(statusMismatchRepository)
            statusResult <- resume(statusConfig, statusMismatchRunner).either
            trackerMismatchRepository <- temporaryRepository(4L, "paused")
            trackerMismatchRunner <- RecordingRunner.make(
              fixedSnapshot(4L, RunState)
            )
            trackerConfig <- makeConfig(trackerMismatchRepository)
            trackerResult <- resume(trackerConfig, trackerMismatchRunner).either
          yield assertTrue(
            errorCode(statusResult).contains("stale_bdr_state"),
            errorCode(trackerResult).contains("stale_bdr_state")
          )
        }
      },
      test(
        "apply owns revision and actor, preserves operation state, and refreshes exactly +1"
      ) {
        ZIO.scoped {
          for
            repository <- temporaryRepository(10L, RunState)
            revision <- Ref.make(10L)
            runner <- RecordingRunner.make { invocation =>
              verb(invocation) match
                case "check" =>
                  revision.get.map(value => success(checkJson(value, RunState)))
                case "status" =>
                  revision.get.map(value =>
                    success(statusJson(value, RunState))
                  )
                case "apply" =>
                  for
                    next <- revision.updateAndGet(_ + 1L)
                    _ <- writeTracker(repository, next, RunState).orDie
                  yield success(applyJson(next))
                case other => unexpected(other)
            }
            config <- makeConfig(repository)
            session <- resume(config, runner)
            before <- session.current
            operationState = Json.Obj(
              Chunk(
                "phase" -> number(4L),
                "slice" -> Json.Str("streaming-cache")
              )
            )
            operation = Json.Obj(
              Chunk(
                "action" -> Json.Str("advance"),
                "state" -> operationState
              )
            )
            expectedInput <- ZIO.fromEither(
              StrictJson.canonical(operation, "test operation")
            )
            result <- session.apply(operation)
            after <- session.current
            calls <- runner.calls
            applyCall = calls(2)
            supplied <- ZIO.fromEither(
              StrictJson.parseObject(
                applyCall.input.getOrElse(""),
                "captured apply input"
              )
            )
            reservedResult <- session
              .apply(Json.Obj(Chunk("actor" -> Json.Str("intruder"))))
              .either
            callsAfterReserved <- runner.calls
          yield assertTrue(
            applyCall.command == applyCommand(10L),
            applyCall.input.contains(expectedInput),
            field(supplied, "state").contains(operationState),
            field(supplied, "actor").isEmpty,
            field(supplied, "expected_revision").isEmpty,
            revisionField(result).contains(11L),
            before.revision.value == 10L,
            after.revision.value == 11L,
            before.view.stateDigest != after.view.stateDigest,
            calls.map(invocation => verb(invocation)) == Chunk(
              "check",
              "status",
              "apply",
              "check",
              "status"
            ),
            errorCode(reservedResult).contains("reserved_operation_field"),
            callsAfterReserved.size == calls.size
          )
        }
      },
      test("rejects an apply response that skips a revision") {
        ZIO.scoped {
          for
            repository <- temporaryRepository(2L, RunState)
            runner <- RecordingRunner.make { invocation =>
              verb(invocation) match
                case "check"  => ZIO.succeed(success(checkJson(2L, RunState)))
                case "status" => ZIO.succeed(success(statusJson(2L, RunState)))
                case "apply"  => ZIO.succeed(success(applyJson(4L)))
                case other    => unexpected(other)
            }
            config <- makeConfig(repository)
            session <- resume(config, runner)
            result <- session.apply(simpleOperation).either
            current <- session.current
          yield assertTrue(
            errorCode(result).contains("invalid_revision_advance"),
            current.revision.value == 2L
          )
        }
      },
      test(
        "rejects an accepted mutation when its refreshed checkpoint is stale"
      ) {
        ZIO.scoped {
          for
            repository <- temporaryRepository(2L, RunState)
            runner <- RecordingRunner.make { invocation =>
              verb(invocation) match
                case "check"  => ZIO.succeed(success(checkJson(2L, RunState)))
                case "status" => ZIO.succeed(success(statusJson(2L, RunState)))
                case "apply"  => ZIO.succeed(success(applyJson(3L)))
                case other    => unexpected(other)
            }
            config <- makeConfig(repository)
            session <- resume(config, runner)
            result <- session.apply(simpleOperation).either
            current <- session.current
          yield assertTrue(
            errorCode(result).contains("stale_bdr_state"),
            current.revision.value == 2L
          )
        }
      },
      test("maps nonzero and malformed engine output to safe failures") {
        ZIO.scoped {
          for
            failedRepository <- temporaryRepository(0L, RunState)
            failedRunner <- RecordingRunner.make(_ =>
              ZIO.succeed(
                ProcessResult(
                  23,
                  "ignored".getBytes(StandardCharsets.UTF_8),
                  "secret stderr".getBytes(StandardCharsets.UTF_8)
                )
              )
            )
            failedConfig <- makeConfig(failedRepository)
            failed <- resume(failedConfig, failedRunner).either
            malformedRepository <- temporaryRepository(0L, RunState)
            malformedRunner <- RecordingRunner.make(_ =>
              ZIO.succeed(success("{not-json"))
            )
            malformedConfig <- makeConfig(malformedRepository)
            malformed <- resume(malformedConfig, malformedRunner).either
          yield assertTrue(
            errorCode(failed).contains("bdr_check_failed"),
            failed.left.toOption.exists(
              _.safeMessage == "bdr_check failed with exit code 23"
            ),
            errorCode(malformed).contains("protocol_violation"),
            malformed.left.toOption.exists(
              _.safeMessage.contains("bdr_check output is not strict JSON")
            ),
            failed.left.toOption.forall(
              !_.safeMessage.contains("secret stderr")
            )
          )
        }
      },
      test("requires an engine identity attestation before invoking BDR") {
        ZIO.scoped {
          for
            repository <- temporaryRepository(0L, RunState)
            runner <- RecordingRunner.make(fixedSnapshot(0L, RunState))
            config <- makeConfig(repository)
            verifier = new EngineIdentityVerifier:
              def verify(config: BdrConfig): IO[BatError, Unit] =
                ZIO.fail(
                  BatError.BdrFailure(
                    "engine_identity_mismatch",
                    "BDR engine does not match the pinned source identity"
                  )
                )
            result <- resume(config, runner, verifier).either
            calls <- runner.calls
          yield assertTrue(
            errorCode(result).contains("engine_identity_mismatch"),
            calls.isEmpty
          )
        }
      },
      test("Git verifier binds the entry point to an exact clean commit") {
        ZIO.scoped {
          for
            engineRoot <- temporaryDirectory("bat-bdr-engine-")
            targetRepository <- temporaryRepository(0L, RunState)
            entryPoint = Path.of("bin", "bdr")
            entry = engineRoot.resolve(entryPoint)
            _ <- ZIO.attemptBlocking {
              val _ = Files.createDirectories(entry.getParent)
              val _ = Files.writeString(
                entry,
                "#!/bin/sh\nexit 0\n",
                StandardCharsets.UTF_8
              )
            }
            _ <- git(engineRoot, "init")
            _ <- git(engineRoot, "add", entryPoint.toString)
            _ <- git(
              engineRoot,
              "-c",
              "user.name=BAT Test",
              "-c",
              "user.email=bat@example.invalid",
              "commit",
              "-m",
              "pin engine"
            )
            commit <- git(engineRoot, "rev-parse", "HEAD").map(_.trim)
            config <- ZIO.fromEither(
              BdrConfig.make(
                Chunk(entry.toString),
                targetRepository,
                commandTimeout = 5.seconds,
                actor = Actor,
                engineCommit = commit,
                engineSourceRoot = engineRoot,
                engineEntryPoint = entryPoint
              )
            )
            clean <- GitEngineIdentityVerifier.verify(config).either
            _ <- ZIO.attemptBlocking(
              Files.writeString(
                entry,
                "#!/bin/sh\nexit 1\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING
              )
            )
            dirty <- GitEngineIdentityVerifier.verify(config).either
            wrongCommit <- ZIO.fromEither(
              BdrConfig.make(
                Chunk(entry.toString),
                targetRepository,
                commandTimeout = 5.seconds,
                actor = Actor,
                engineCommit = "0000000000000000000000000000000000000000",
                engineSourceRoot = engineRoot,
                engineEntryPoint = entryPoint
              )
            )
            mismatch <- GitEngineIdentityVerifier
              .verify(wrongCommit)
              .either
          yield assertTrue(
            clean == Right(()),
            errorCode(dirty).contains("engine_identity_mismatch"),
            errorCode(mismatch).contains("engine_identity_mismatch")
          )
        }
      },
      test("rejects an intermediate symlink in the tracker path") {
        ZIO.scoped {
          for
            repository <- temporaryDirectory("bat-bdr-symlink-repository-")
            outside <- temporaryRepository(1L, RunState)
            _ <- ZIO.attemptBlocking(
              Files.createSymbolicLink(
                repository.resolve(".bdr"),
                outside.resolve(".bdr")
              )
            )
            runner <- RecordingRunner.make(fixedSnapshot(1L, RunState))
            config <- makeConfig(repository)
            result <- resume(config, runner).either
          yield assertTrue(errorCode(result).contains("tracker_read_failed"))
        }
      },
      test(
        "invalidates an ambiguous mutation and recovers only through checkpoint"
      ) {
        ZIO.scoped {
          for
            repository <- temporaryRepository(5L, RunState)
            revision <- Ref.make(5L)
            corrupt <- Ref.make(false)
            runner <- RecordingRunner.make { invocation =>
              verb(invocation) match
                case "check" =>
                  revision.get.map(value => success(checkJson(value, RunState)))
                case "status" =>
                  revision.get.zip(corrupt.get).map { case (value, isCorrupt) =>
                    success(
                      statusJson(
                        if isCorrupt then value + 1L else value,
                        RunState
                      )
                    )
                  }
                case "apply" =>
                  writeTracker(repository, 6L, RunState)
                    .mapError(_ =>
                      BatError.BdrFailure(
                        "test_write_failed",
                        "test tracker write failed"
                      )
                    ) *>
                    revision.set(6L) *>
                    corrupt.set(true) *>
                    ZIO.succeed(success("{malformed"))
                case other => unexpected(other)
            }
            config <- makeConfig(repository)
            session <- resume(config, runner)
            result <- session.apply(simpleOperation).either
            invalidCurrent <- session.current.either
            callsBeforeRetry <- runner.calls
            blockedRetry <- session.apply(simpleOperation).either
            callsAfterRetry <- runner.calls
            _ <- corrupt.set(false)
            recovered <- session.checkpoint
            current <- session.current
          yield assertTrue(
            errorCode(result).contains("bdr_session_invalidated"),
            errorCode(invalidCurrent).contains("bdr_session_invalidated"),
            errorCode(blockedRetry).contains("bdr_session_invalidated"),
            callsAfterRetry == callsBeforeRetry,
            recovered.revision.value == 6L,
            current.revision.value == 6L
          )
        }
      },
      test("times out the whole process tree and closes inherited pipes") {
        ZIO.scoped {
          for
            cwd <- temporaryDirectory("bat-bdr-process-")
            pidFile = cwd.resolve("child.pid")
            started <- Clock.nanoTime
            result <- JdkProcessRunner
              .run(
                Chunk(
                  "/bin/sh",
                  "-c",
                  "sleep 30 & child=$!; printf '%s' \"$child\" > child.pid; sleep 0.1; exit 0"
                ),
                cwd,
                None,
                300.millis
              )
              .either
            elapsed <- Clock.nanoTime.map(_ - started)
            childPid <- ZIO.attemptBlocking(
              Files.readString(pidFile, StandardCharsets.UTF_8).trim.toLong
            )
            _ <- ZIO.sleep(100.millis)
            childAlive <- ZIO.succeed {
              val handle = ProcessHandle.of(childPid)
              handle.isPresent && handle.get().isAlive
            }
          yield assertTrue(
            errorCode(result).contains("process_timeout"),
            elapsed < 3.seconds.toNanos,
            !childAlive
          )
        }
      } @@ TestAspect.withLiveClock,
      test("runs the pinned audit summary command without stdin") {
        ZIO.scoped {
          for
            repository <- temporaryRepository(8L, RunState)
            runner <- RecordingRunner.make { invocation =>
              verb(invocation) match
                case "check" =>
                  ZIO.succeed(success(checkJson(8L, RunState)))
                case "status" =>
                  ZIO.succeed(success(statusJson(8L, RunState)))
                case "audit" =>
                  ZIO.succeed(success("""{"issues":3,"status":"ok"}"""))
                case other => unexpected(other)
            }
            config <- makeConfig(repository)
            session <- resume(config, runner)
            summary <- session.auditSummary
            calls <- runner.calls
            audit = calls.last
          yield assertTrue(
            audit.command == auditCommand,
            audit.cwd == repository.toAbsolutePath.normalize,
            audit.input.isEmpty,
            audit.timeout == 5.seconds,
            field(summary.asInstanceOf[Json.Obj], "status").contains(
              Json.Str("ok")
            )
          )
        }
      },
      test("accepts an ineligible completion result without mutating state") {
        ZIO.scoped {
          for
            repository <- temporaryRepository(8L, "verifying")
            runner <- RecordingRunner.make { invocation =>
              verb(invocation) match
                case "check" =>
                  ZIO.succeed(success(checkJson(8L, "verifying")))
                case "status" =>
                  ZIO.succeed(success(statusJson(8L, "verifying")))
                case "completion-check" =>
                  ZIO.succeed(
                    ProcessResult(
                      1,
                      """{"eligible":false,"current_state":"verifying","revision":8,"blockers":[{"rule":"V008","message":"delivery missing"}],"next":null}"""
                        .getBytes(StandardCharsets.UTF_8),
                      Array.emptyByteArray
                    )
                  )
                case other => unexpected(other)
            }
            config <- makeConfig(repository)
            session <- resume(config, runner)
            before <- session.current
            result <- session.completionCheck
            after <- session.current
            calls <- runner.calls
          yield assertTrue(
            field(result, "eligible").contains(Json.Bool(false)),
            after == before,
            calls.map(invocation => verb(invocation)) == Chunk(
              "check",
              "status",
              "completion-check",
              "check",
              "status"
            ),
            calls(2).command == completionCommand,
            calls(2).input.isEmpty
          )
        }
      },
      test(
        "invalidates completion when revision, run state, or tracker identity drifts"
      ) {
        val mutations = Chunk(
          CompletionMutation(
            revision = 9L,
            runState = "verifying",
            tracker = trackerJson(9L, "verifying")
          ),
          CompletionMutation(
            revision = 8L,
            runState = "paused",
            tracker = trackerJson(8L, "paused")
          ),
          CompletionMutation(
            revision = 8L,
            runState = "verifying",
            tracker =
              """{"revision":8,"run":{"state":"verifying"},"checkpoints":{"CP-0001":{"identity":"changed"}}}"""
          )
        )

        ZIO
          .foreach(mutations) { mutation =>
            ZIO.scoped {
              for
                repository <- temporaryRepository(8L, "verifying")
                observedRevision <- Ref.make(8L)
                observedRunState <- Ref.make("verifying")
                runner <- RecordingRunner.make { invocation =>
                  verb(invocation) match
                    case "check" =>
                      observedRevision.get
                        .zip(observedRunState.get)
                        .map { case (revision, runState) =>
                          success(checkJson(revision, runState))
                        }
                    case "status" =>
                      observedRevision.get
                        .zip(observedRunState.get)
                        .map { case (revision, runState) =>
                          success(statusJson(revision, runState))
                        }
                    case "completion-check" =>
                      writeTrackerText(repository, mutation.tracker).orDie *>
                        observedRevision.set(mutation.revision) *>
                        observedRunState.set(mutation.runState) *>
                        ZIO.succeed(
                          ProcessResult(
                            1,
                            """{"eligible":false,"current_state":"verifying","revision":8,"blockers":[{"rule":"V008","message":"delivery missing"}],"next":null}"""
                              .getBytes(StandardCharsets.UTF_8),
                            Array.emptyByteArray
                          )
                        )
                    case other => unexpected(other)
                }
                config <- makeConfig(repository)
                session <- resume(config, runner)
                result <- session.completionCheck.either
                invalidCurrent <- session.current.either
                callsBeforeRetry <- runner.calls
                blockedRetry <- session.completionCheck.either
                callsAfterRetry <- runner.calls
              yield assertTrue(
                errorCode(result).contains("bdr_session_invalidated"),
                errorCode(invalidCurrent).contains("bdr_session_invalidated"),
                errorCode(blockedRetry).contains("bdr_session_invalidated"),
                callsAfterRetry == callsBeforeRetry
              )
            }
          }
          .map(results => results.reduce(_ && _))
      }
    )

  private def makeConfig(repository: Path): IO[BatError, BdrConfig] =
    ZIO.fromEither(
      BdrConfig.make(
        EngineArgv,
        repository,
        statePath = StatePath,
        commandTimeout = 5.seconds,
        actor = Actor,
        engineCommit = EngineCommit,
        engineSourceRoot = EngineSourceRoot,
        engineEntryPoint = EngineEntryPoint
      )
    )

  private val AcceptingEngineVerifier = new EngineIdentityVerifier:
    def verify(config: BdrConfig): IO[BatError, Unit] = ZIO.unit

  private def resume(
      config: BdrConfig,
      runner: ProcessRunner,
      verifier: EngineIdentityVerifier = AcceptingEngineVerifier
  ): IO[BatError, BdrSession] =
    BdrSession.resume(config, runner, verifier)

  private def fixedSnapshot(
      revision: Long,
      runState: String
  ): Invocation => IO[BatError, ProcessResult] =
    invocation =>
      verb(invocation) match
        case "check"  => ZIO.succeed(success(checkJson(revision, runState)))
        case "status" =>
          ZIO.succeed(success(statusJson(revision, runState)))
        case other => unexpected(other)

  private def simpleOperation: Json.Obj =
    Json.Obj(Chunk("action" -> Json.Str("advance")))

  private def checkJson(
      revision: Long,
      runState: String,
      valid: Boolean = true
  ): String =
    s"""{"valid":$valid,"revision":$revision,"run_state":"$runState"}"""

  private def statusJson(revision: Long, runState: String): String =
    s"""{"revision":$revision,"run_state":"$runState","next":{"kind":"continue"}}"""

  private def applyJson(revision: Long): String =
    s"""{"accepted":true,"revision":$revision}"""

  private def trackerJson(revision: Long, runState: String): String =
    s"""{"revision":$revision,"run":{"state":"$runState"}}"""

  private def initializedTrackerJson(identity: TrackerIdentity): String =
    s"""{"revision":${identity.revision},"source":{"repository":"${identity.repository}","base_sha":"${identity.baseSha}","starting_head_sha":"${identity.headSha}"},"policy":{"github_projection":"${identity.githubProjection}","max_fixed_point_passes":${identity.maxFixedPointPasses},"max_phase_attempts":${identity.maxPhaseAttempts}},"run":{"id":"${identity.runId}","state":"refactoring"}}"""

  private def success(json: String): ProcessResult =
    ProcessResult(
      exitCode = 0,
      stdout = json.getBytes(StandardCharsets.UTF_8),
      stderr = Array.emptyByteArray
    )

  private def unexpected(verb: String): IO[BatError, ProcessResult] =
    ZIO.fail(
      BatError.BdrFailure(
        "unexpected_test_command",
        s"unexpected test command: $verb"
      )
    )

  private def verb(invocation: Invocation): String =
    invocation.command.drop(EngineArgv.size).headOption.getOrElse("")

  private def checkCommand: Chunk[String] =
    EngineArgv ++ Chunk("check", "--state", StatePath.toString, "--json")

  private def statusCommand: Chunk[String] =
    EngineArgv ++ Chunk("status", "--state", StatePath.toString, "--next")

  private def applyCommand(expectedRevision: Long): Chunk[String] =
    EngineArgv ++ Chunk(
      "apply",
      "--state",
      StatePath.toString,
      "--expected-revision",
      expectedRevision.toString,
      "--actor",
      Actor,
      "-"
    )

  private def auditCommand: Chunk[String] =
    EngineArgv ++ Chunk(
      "audit",
      "--state",
      StatePath.toString,
      "--summary"
    )

  private def initializeCommand(
      initialization: BdrInitialization
  ): Chunk[String] =
    EngineArgv ++ Chunk(
      "init",
      "--state",
      StatePath.toString,
      "--base-sha",
      initialization.baseSha,
      "--head-sha",
      initialization.headSha,
      "--repository",
      initialization.repository,
      "--run-id",
      initialization.runId,
      "--github-mode",
      "off",
      "--max-fixed-point-passes",
      initialization.maxFixedPointPasses.toString,
      "--max-phase-attempts",
      initialization.maxPhaseAttempts.toString,
      "--actor",
      Actor
    )

  private def completionCommand: Chunk[String] =
    EngineArgv ++ Chunk(
      "completion-check",
      "--state",
      StatePath.toString
    )

  private def field(obj: Json.Obj, name: String): Option[Json] =
    obj.fields.collectFirst { case (`name`, value) => value }

  private def revisionField(obj: Json.Obj): Option[Long] =
    field(obj, "revision").collect { case Json.Num(value) =>
      value.longValueExact()
    }

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))

  private def errorCode[A](result: Either[BatError, A]): Option[String] =
    result.left.toOption.map(_.code)

  private def temporaryRepository(
      revision: Long,
      runState: String
  ): ZIO[Scope, Throwable, Path] =
    for
      repository <- temporaryDirectory("bat-bdr-session-")
      _ <- writeTracker(repository, revision, runState)
    yield repository

  private def temporaryDirectory(prefix: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix))
    )(deleteRecursively)

  private def git(root: Path, arguments: String*): Task[String] =
    ZIO.attemptBlockingInterrupt {
      val process = new ProcessBuilder(
        (Seq("git", "-C", root.toString) ++ arguments)*
      ).redirectErrorStream(true).start()
      val output = process.getInputStream.readAllBytes()
      val exit = process.waitFor()
      if exit != 0 then
        throw new IllegalStateException(
          s"git test setup failed with exit $exit: ${new String(output, StandardCharsets.UTF_8)}"
        )
      new String(output, StandardCharsets.UTF_8)
    }

  private def writeTracker(
      repository: Path,
      revision: Long,
      runState: String
  ): Task[Unit] =
    writeTrackerText(repository, trackerJson(revision, runState))

  private def writeTrackerText(
      repository: Path,
      text: String
  ): Task[Unit] =
    ZIO.attemptBlocking {
      val tracker = repository.resolve(StatePath)
      val _ = Files.createDirectories(tracker.getParent)
      val _ = Files.writeString(
        tracker,
        text,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
    }

  private def deleteRecursively(repository: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(repository) then
        val stream = Files.walk(repository)
        try
          stream
            .iterator()
            .asScala
            .toList
            .sortBy(_.getNameCount)
            .reverse
            .foreach(path =>
              val _ = Files.deleteIfExists(path)
            )
        finally stream.close()
    }.ignore

  private final case class Invocation(
      command: Chunk[String],
      cwd: Path,
      input: Option[String],
      timeout: Duration
  )

  private final class RecordingRunner(
      callsRef: Ref[Chunk[Invocation]],
      respond: Invocation => IO[BatError, ProcessResult]
  ) extends ProcessRunner:
    def calls: UIO[Chunk[Invocation]] = callsRef.get

    def run(
        command: Chunk[String],
        cwd: Path,
        input: Option[String],
        timeout: Duration
    ): IO[BatError, ProcessResult] =
      val invocation = Invocation(command, cwd, input, timeout)
      callsRef.update(_ :+ invocation) *> respond(invocation)

  private object RecordingRunner:
    def make(
        respond: Invocation => IO[BatError, ProcessResult]
    ): UIO[RecordingRunner] =
      Ref.make(Chunk.empty[Invocation]).map(new RecordingRunner(_, respond))
