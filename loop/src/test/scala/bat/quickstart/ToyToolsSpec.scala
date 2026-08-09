package bat.quickstart

import bat.controller.*
import bat.protocol.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ToyToolsSpec extends ZIOSpecDefault:
  private final case class WorkspaceToken(
      revision: Long,
      fingerprint: String
  )

  private final case class OpenToy(
      materialized: MaterializedToy,
      workspace: ToyWorkspace,
      registry: ToolRegistry
  )

  def spec =
    suite("strict Java six-phase toy tools")(
      test("exports the closed tool inventory with least authority") {
        ZIO.scoped {
          for
            scratch <- temporaryDirectory("bat-toy-tools-schema-")
            opened <- openToy(scratch)
            tools = opened.workspace.tools
            schemas = tools
              .map(tool => tool.definition.name -> tool.definition.parameters)
              .toMap
            testSchema = schemas("toy_run_tests")
            registry = opened.registry
          yield assertTrue(
            tools.map(tool => tool.definition.name -> tool.authority) == Chunk(
              "toy_workspace_state" -> ToolAuthority.ReadOnly,
              "toy_read_file" -> ToolAuthority.ReadOnly,
              "toy_search" -> ToolAuthority.ReadOnly,
              "toy_git_diff" -> ToolAuthority.ReadOnly,
              "toy_apply_patch" -> ToolAuthority.Writer,
              "toy_run_tests" -> ToolAuthority.ReadOnly,
              "toy_git_commit" -> ToolAuthority.Writer
            ),
            registry.allStrict,
            tools.forall(tool => closedObject(tool.definition.parameters)),
            propertyNames(schemas("toy_workspace_state")).isEmpty,
            requiredNames(schemas("toy_workspace_state")).isEmpty,
            propertyNames(schemas("toy_read_file")) == Set("path"),
            requiredNames(schemas("toy_read_file")) == Set("path"),
            propertyNames(schemas("toy_search")) == Set("query"),
            requiredNames(schemas("toy_search")) == Set("query"),
            propertyNames(schemas("toy_git_diff")).isEmpty,
            requiredNames(schemas("toy_git_diff")).isEmpty,
            propertyNames(schemas("toy_apply_patch")) == Set(
              "patch",
              "reverse",
              "expected_revision",
              "expected_fingerprint"
            ),
            requiredNames(schemas("toy_apply_patch")) == propertyNames(
              schemas("toy_apply_patch")
            ),
            propertyNames(testSchema) == Set(
              "suite",
              "expected_revision",
              "expected_fingerprint"
            ),
            requiredNames(testSchema) == propertyNames(testSchema),
            enumStrings(property(testSchema, "suite")) == Set(
              "baseline",
              "expose",
              "saturate",
              "falsify",
              "final"
            ),
            propertyNames(schemas("toy_git_commit")) == Set(
              "message",
              "expected_revision",
              "expected_fingerprint"
            ),
            requiredNames(schemas("toy_git_commit")) == propertyNames(
              schemas("toy_git_commit")
            ),
            registry.definitionsFor(RunMode.Audit).map(_.name) == Chunk(
              "toy_workspace_state",
              "toy_read_file",
              "toy_search",
              "toy_git_diff",
              "toy_run_tests"
            )
          )
        }
      },
      test("returns bounded public verification evidence without raw output") {
        ZIO.scoped {
          for
            scratch <- temporaryDirectory("bat-toy-tools-baseline-")
            opened <- openToy(scratch)
            state <- execute(
              opened.registry,
              "state-1",
              "toy_workspace_state",
              obj(),
              RunMode.Audit
            )
            stateObject = objectOutput(state)
            initial = workspaceToken(stateObject)
            baseline <- execute(
              opened.registry,
              "test-1",
              "toy_run_tests",
              testArguments("baseline", initial),
              RunMode.Audit
            )
            result = objectOutput(baseline)
            count <- opened.workspace.testInvocationCount
            encoded = result.toJson
          yield assertTrue(
            !state.isError,
            fieldNames(stateObject) == Set("workspace"),
            initial.revision == 0L,
            initial.fingerprint.matches("[0-9a-f]{64}"),
            !baseline.isError,
            fieldNames(result) == Set(
              "exit_code",
              "assertion_failed",
              "failure_fingerprint",
              "output_digest",
              "suite",
              "workspace"
            ),
            longField(result, "exit_code").contains(0L),
            booleanField(result, "assertion_failed").contains(false),
            field(result, "failure_fingerprint").contains(Json.Null),
            stringField(result, "suite").contains("baseline"),
            stringField(result, "output_digest").exists(
              _.matches("sha256:[0-9a-f]{64}")
            ),
            workspaceToken(result) == initial,
            count == 1,
            !encoded.contains("PASS IngressGatewayPublicTest"),
            !encoded.contains("AssertionError"),
            !encoded.contains("external corporate-looking sender")
          )
        }
      },
      test("keeps reads, search, and diff inside the actor-visible tree") {
        ZIO.scoped {
          for
            scratch <- temporaryDirectory("bat-toy-tools-read-")
            opened <- openToy(scratch)
            state <- execute(
              opened.registry,
              "state-read",
              "toy_workspace_state",
              obj(),
              RunMode.Audit
            )
            token = workspaceToken(objectOutput(state))
            read <- execute(
              opened.registry,
              "read-router",
              "toy_read_file",
              obj(
                "path" -> Json.Str(
                  "src/main/java/dev/bat/examples/ingress/MessageRouter.java"
                )
              ),
              RunMode.Audit
            )
            search <- execute(
              opened.registry,
              "search-suffix",
              "toy_search",
              obj("query" -> Json.Str("endsWith")),
              RunMode.Audit
            )
            diff <- execute(
              opened.registry,
              "diff-clean",
              "toy_git_diff",
              obj(),
              RunMode.Audit
            )
            traversal <- execute(
              opened.registry,
              "read-traversal",
              "toy_read_file",
              obj("path" -> Json.Str("../reference/repair.patch")),
              RunMode.Audit
            )
            gitRead <- execute(
              opened.registry,
              "read-git",
              "toy_read_file",
              obj("path" -> Json.Str(".git/config")),
              RunMode.Audit
            )
            encoded = Chunk(read, search, diff)
              .map(_.output.toJson)
              .mkString
          yield assertTrue(
            !read.isError,
            !search.isError,
            !diff.isError,
            encoded.contains("MessageRouter.java"),
            encoded.contains("endsWith"),
            !encoded.contains("oracle/"),
            !encoded.contains("reference/"),
            !encoded.contains(".git/"),
            workspaceToken(objectOutput(read)) == token,
            workspaceToken(objectOutput(search)) == token,
            workspaceToken(objectOutput(diff)) == token,
            traversal.isError,
            gitRead.isError
          )
        }
      },
      test("enforces exact mutation tokens and rejects protected patch paths") {
        ZIO.scoped {
          for
            scratch <- temporaryDirectory("bat-toy-tools-preconditions-")
            opened <- openToy(scratch)
            initial <- currentToken(opened.registry, "state-preconditions")
            expose <- readFixture("reference/phases/expose.patch")
            wrongFingerprint <- execute(
              opened.registry,
              "apply-wrong-fingerprint",
              "toy_apply_patch",
              patchArguments(
                expose,
                reverse = false,
                initial.copy(fingerprint = "0" * 64)
              ),
              RunMode.FullWriter
            )
            afterWrong <- currentToken(opened.registry, "state-after-wrong")
            protectedPatch =
              """diff --git a/oracle/Private.java b/oracle/Private.java
                |new file mode 100644
                |--- /dev/null
                |+++ b/oracle/Private.java
                |@@ -0,0 +1 @@
                |+final class Private {}
                |""".stripMargin
            protectedResult <- execute(
              opened.registry,
              "apply-protected",
              "toy_apply_patch",
              patchArguments(protectedPatch, reverse = false, initial),
              RunMode.FullWriter
            )
            afterProtected <- currentToken(
              opened.registry,
              "state-after-protected"
            )
            applied <- execute(
              opened.registry,
              "apply-expose",
              "toy_apply_patch",
              patchArguments(expose, reverse = false, initial),
              RunMode.FullWriter
            )
            exposed = workspaceToken(objectOutput(applied))
            stale <- execute(
              opened.registry,
              "apply-stale",
              "toy_apply_patch",
              patchArguments(expose, reverse = false, initial),
              RunMode.FullWriter
            )
            finalState <- currentToken(opened.registry, "state-after-stale")
          yield assertTrue(
            wrongFingerprint.isError,
            afterWrong == initial,
            protectedResult.isError,
            afterProtected == initial,
            !Files.exists(opened.materialized.repository.resolve("oracle")),
            !applied.isError,
            fieldNames(objectOutput(applied)) == Set("workspace"),
            exposed.revision == initial.revision + 1L,
            exposed.fingerprint != initial.fingerprint,
            stale.isError,
            finalState == exposed,
            errorCode(wrongFingerprint).exists(
              _.matches("[a-z][a-z0-9_-]{0,63}")
            ),
            errorCode(protectedResult).exists(
              _.matches("[a-z][a-z0-9_-]{0,63}")
            ),
            errorCode(stale).exists(_.matches("[a-z][a-z0-9_-]{0,63}"))
          )
        }
      },
      test(
        "runs red-green-counterfactual workflow and commits a full delivery SHA"
      ) {
        ZIO.scoped {
          for
            scratch <- temporaryDirectory("bat-toy-tools-repair-")
            opened <- openToy(scratch)
            initial <- currentToken(opened.registry, "state-repair")
            repair <- readFixture("reference/repair.patch")
            repairedOutput <- execute(
              opened.registry,
              "apply-repair",
              "toy_apply_patch",
              patchArguments(repair, reverse = false, initial),
              RunMode.FullWriter
            )
            repaired = workspaceToken(objectOutput(repairedOutput))
            green <- execute(
              opened.registry,
              "test-saturate",
              "toy_run_tests",
              testArguments("saturate", repaired),
              RunMode.Audit
            )
            falsify <- readFixture("reference/phases/falsify.patch")
            counterfactualOutput <- execute(
              opened.registry,
              "apply-falsify",
              "toy_apply_patch",
              patchArguments(falsify, reverse = false, repaired),
              RunMode.FullWriter
            )
            counterfactual = workspaceToken(objectOutput(counterfactualOutput))
            red <- execute(
              opened.registry,
              "test-falsify",
              "toy_run_tests",
              testArguments("falsify", counterfactual),
              RunMode.Audit
            )
            restoredOutput <- execute(
              opened.registry,
              "restore-falsify",
              "toy_apply_patch",
              patchArguments(falsify, reverse = true, counterfactual),
              RunMode.FullWriter
            )
            restored = workspaceToken(objectOutput(restoredOutput))
            finalTest <- execute(
              opened.registry,
              "test-final",
              "toy_run_tests",
              testArguments("final", restored),
              RunMode.Audit
            )
            committed <- execute(
              opened.registry,
              "commit-repair",
              "toy_git_commit",
              obj(
                "message" -> Json.Str(
                  "Route explicit ingress authority to scanner policy"
                ),
                "expected_revision" -> number(restored.revision),
                "expected_fingerprint" -> Json.Str(restored.fingerprint)
              ),
              RunMode.FullWriter
            )
            commitObject = objectOutput(committed)
            committedToken = workspaceToken(commitObject)
            reportedHead = stringField(commitObject, "head_sha").getOrElse("")
            finalHead <- opened.workspace.finalHead
            finalPatchDigest <- opened.workspace.finalPatchDigest
            testCount <- opened.workspace.testInvocationCount
            status <- ToyRuntime.git(
              opened.materialized.repository,
              Seq(
                "status",
                "--porcelain=v1",
                "--untracked-files=all",
                "--",
                "src"
              )
            )
            redJson = objectOutput(red)
            expectedRedFingerprint =
              s"sha256:${ToyRuntime.sha256("external corporate-looking sender: expected REJECTED but was ACCEPTED".getBytes(StandardCharsets.UTF_8))}"
            evidenceJson = Chunk(green, red, finalTest)
              .map(_.output.toJson)
              .mkString
          yield assertTrue(
            !repairedOutput.isError,
            repaired.revision == initial.revision + 1L,
            longField(objectOutput(green), "exit_code").contains(0L),
            booleanField(objectOutput(green), "assertion_failed")
              .contains(false),
            !counterfactualOutput.isError,
            counterfactual.revision == repaired.revision + 1L,
            longField(redJson, "exit_code").contains(1L),
            booleanField(redJson, "assertion_failed").contains(true),
            stringField(redJson, "failure_fingerprint")
              .contains(expectedRedFingerprint),
            !restoredOutput.isError,
            restored.revision == counterfactual.revision + 1L,
            longField(objectOutput(finalTest), "exit_code").contains(0L),
            booleanField(objectOutput(finalTest), "assertion_failed")
              .contains(false),
            !committed.isError,
            fieldNames(commitObject) == Set("head_sha", "workspace"),
            reportedHead.matches("[0-9a-f]{40}|[0-9a-f]{64}"),
            reportedHead != opened.materialized.headSha,
            finalHead == reportedHead,
            committedToken.revision == restored.revision + 1L,
            committedToken.fingerprint != restored.fingerprint,
            finalPatchDigest.matches("[0-9a-f]{64}"),
            testCount == 3,
            String(status.output, StandardCharsets.UTF_8).isBlank,
            !evidenceJson.contains("AssertionError"),
            !evidenceJson.contains("expected REJECTED but was ACCEPTED")
          )
        }
      }
    ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

  private def openToy(scratch: Path): IO[BatError, OpenToy] =
    for
      materialized <- ToyRepository.materialize(
        fixtureRoot,
        scratch.resolve("repository")
      )
      workspace <- ToyWorkspace.open(materialized)
      registry <- ZIO.fromEither(ToolRegistry.make(workspace.tools))
    yield OpenToy(materialized, workspace, registry)

  private def currentToken(
      registry: ToolRegistry,
      id: String
  ): IO[BatError, WorkspaceToken] =
    execute(
      registry,
      id,
      "toy_workspace_state",
      obj(),
      RunMode.Audit
    ).map(output => workspaceToken(objectOutput(output)))

  private def execute(
      registry: ToolRegistry,
      id: String,
      name: String,
      arguments: Json.Obj,
      mode: RunMode
  ): IO[BatError, FunctionOutput] =
    registry.execute(call(id, name, arguments), mode)

  private def call(
      id: String,
      name: String,
      arguments: Json.Obj
  ): FunctionCall =
    unsafe(
      FunctionCall.make(
        unsafe(CallId.from(id)),
        name,
        arguments
      )
    )

  private def testArguments(
      suite: String,
      token: WorkspaceToken
  ): Json.Obj =
    obj(
      "suite" -> Json.Str(suite),
      "expected_revision" -> number(token.revision),
      "expected_fingerprint" -> Json.Str(token.fingerprint)
    )

  private def patchArguments(
      patch: String,
      reverse: Boolean,
      token: WorkspaceToken
  ): Json.Obj =
    obj(
      "patch" -> Json.Str(patch),
      "reverse" -> Json.Bool(reverse),
      "expected_revision" -> number(token.revision),
      "expected_fingerprint" -> Json.Str(token.fingerprint)
    )

  private def objectOutput(output: FunctionOutput): Json.Obj =
    output.output match
      case value: Json.Obj => value
      case _ => throw new IllegalStateException("expected object tool output")

  private def workspaceToken(value: Json.Obj): WorkspaceToken =
    field(value, "workspace") match
      case Some(workspace: Json.Obj) =>
        WorkspaceToken(
          longField(workspace, "revision").getOrElse(
            throw new IllegalStateException("missing workspace revision")
          ),
          stringField(workspace, "fingerprint").getOrElse(
            throw new IllegalStateException("missing workspace fingerprint")
          )
        )
      case _ => throw new IllegalStateException("missing workspace object")

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def fieldNames(value: Json.Obj): Set[String] =
    value.fields.map(_._1).toSet

  private def stringField(value: Json.Obj, name: String): Option[String] =
    field(value, name).collect { case Json.Str(result) => result }

  private def booleanField(value: Json.Obj, name: String): Option[Boolean] =
    field(value, name).collect { case Json.Bool(result) => result }

  private def longField(value: Json.Obj, name: String): Option[Long] =
    field(value, name).collect { case Json.Num(result) =>
      result.longValueExact()
    }

  private def errorCode(output: FunctionOutput): Option[String] =
    output.output match
      case value: Json.Obj => stringField(value, "error")
      case _               => None

  private def closedObject(schema: Json.Obj): Boolean =
    field(schema, "type").contains(Json.Str("object")) &&
      field(schema, "additionalProperties").contains(Json.Bool(false))

  private def propertyNames(schema: Json.Obj): Set[String] =
    field(schema, "properties") match
      case Some(value: Json.Obj) => value.fields.map(_._1).toSet
      case _                     => Set.empty

  private def requiredNames(schema: Json.Obj): Set[String] =
    field(schema, "required") match
      case Some(Json.Arr(values)) =>
        values.collect { case Json.Str(name) => name }.toSet
      case _ => Set.empty

  private def property(schema: Json.Obj, name: String): Json.Obj =
    field(schema, "properties")
      .collect { case value: Json.Obj => value }
      .flatMap(value => field(value, name)) match
      case Some(value: Json.Obj) => value
      case _                     => Json.Obj(Chunk.empty)

  private def enumStrings(schema: Json.Obj): Set[String] =
    field(schema, "enum") match
      case Some(Json.Arr(values)) =>
        values.collect { case Json.Str(value) => value }.toSet
      case _ => Set.empty

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def readFixture(relative: String): Task[String] =
    ZIO.attemptBlocking(
      Files.readString(fixtureRoot.resolve(relative), StandardCharsets.UTF_8)
    )

  private def fixtureRoot: Path =
    @tailrec
    def find(directory: Path): Path =
      val candidate = directory.resolve("examples/java-six-phase")
      if Files.isRegularFile(candidate.resolve("manifest.json")) then candidate
      else
        val parent = directory.getParent
        if parent == null then
          throw new IllegalStateException("examples/java-six-phase not found")
        find(parent)

    find(Path.of("").toAbsolutePath.normalize)

  private def temporaryDirectory(prefix: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix))
    )(deleteRecursively)

  private def deleteRecursively(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
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

  private def unsafe[A](value: Either[BatError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )
