package bat.quickstart

import bat.controller.*
import bat.protocol.*

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*
import scala.util.Try

import zio.*
import zio.json.ast.Json

/** Exact optimistic-concurrency token for the actor-visible toy workspace. */
final case class ToyWorkspaceToken(revision: Long, fingerprint: String)

/** Revision-checked workspace used by the scripted executable quickstart.
  *
  * Scripted inference receives only strict tools. It never receives the fixture
  * root, evaluator sources, reference patches, process output, or a network
  * remote. This local harness is not an OS sandbox and must not execute patches
  * authored by an untrusted provider backend; live models use the OCI worker.
  */
private[quickstart] final class ToyWorkspace private (
    val materialized: MaterializedToy,
    state: Ref[ToyWorkspaceToken],
    invocations: Ref[Long],
    mutex: Semaphore
):
  val tools: Chunk[Tool] = ToyTools.all(this)

  def testInvocationCount: UIO[Long] = invocations.get

  def finalHead: IO[BatError, String] =
    lockedCurrent(_ => ToyRuntime.head(materialized.repository))

  def finalPatchDigest: IO[BatError, String] =
    lockedCurrent { _ =>
      for
        head <- ToyRuntime.head(materialized.repository)
        patch <- ToyRuntime.git(
          materialized.repository,
          Seq(
            "diff",
            "--no-ext-diff",
            "--no-textconv",
            "--binary",
            s"${materialized.headSha}..$head",
            "--",
            "src"
          )
        )
      yield ToyRuntime.sha256(patch.output)
    }

  private[quickstart] def workspaceState: IO[BatError, ToyWorkspaceToken] =
    lockedCurrent(ZIO.succeed(_))

  private[quickstart] def readFile(
      path: String
  ): IO[BatError, (String, ToyWorkspaceToken)] =
    lockedCurrent { current =>
      ToyWorkspace
        .readVisibleFile(materialized.repository, path)
        .map(_ -> current)
    }

  private[quickstart] def search(
      query: String
  ): IO[BatError, (Chunk[ToySearchMatch], ToyWorkspaceToken)] =
    lockedCurrent { current =>
      ToyWorkspace
        .searchVisibleFiles(materialized.repository, query)
        .map(_ -> current)
    }

  private[quickstart] def gitDiff: IO[BatError, (String, ToyWorkspaceToken)] =
    lockedCurrent { current =>
      ToyRuntime
        .git(
          materialized.repository,
          Seq(
            "diff",
            "--no-ext-diff",
            "--no-textconv",
            "--binary",
            "HEAD",
            "--",
            "src/main/java",
            "src/test/java"
          )
        )
        .map(result => ToyWorkspace.decodeUtf8(result.output) -> current)
    }

  private[quickstart] def applyPatch(
      patch: String,
      reverse: Boolean,
      expectedRevision: Long,
      expectedFingerprint: String
  ): IO[BatError, ToyWorkspaceToken] =
    mutate(expectedRevision, expectedFingerprint) {
      ToyWorkspace.validatePatch(patch) *>
        ToyRuntime
          .git(
            materialized.repository,
            Seq("apply") ++
              Option.when(reverse)("--reverse").toSeq ++
              Seq("--index", "--whitespace=error-all", "-"),
            Some(patch)
          )
          .unit
    }

  private[quickstart] def runTests(
      suite: String,
      expectedRevision: Long,
      expectedFingerprint: String
  ): IO[BatError, ToyTestResult] =
    mutex.withPermit {
      for
        current <- requireCurrent(expectedRevision, expectedFingerprint)
        result <- ToyWorkspace.runPublicSuite(
          materialized.repository,
          suite
        )
        _ <- invocations.update(_ + 1L)
        after <- ToyWorkspace.fingerprint(materialized.repository)
        _ <- ZIO
          .fail(ToyWorkspace.failure("toy_workspace_changed"))
          .unless(after == current.fingerprint)
      yield result.copy(workspace = current)
    }

  private[quickstart] def gitCommit(
      message: String,
      expectedRevision: Long,
      expectedFingerprint: String
  ): IO[BatError, (String, ToyWorkspaceToken)] =
    for
      _ <- ToyWorkspace.validateCommitMessage(message)
      next <- mutate(expectedRevision, expectedFingerprint) {
        ToyRuntime
          .git(
            materialized.repository,
            Seq(
              "commit",
              "--no-verify",
              "--no-gpg-sign",
              "-m",
              message
            ),
            environment = Map(
              "GIT_AUTHOR_NAME" -> "BAT Quickstart",
              "GIT_AUTHOR_EMAIL" -> "bat@invalid.local",
              "GIT_AUTHOR_DATE" -> "2000-01-01T00:00:02Z",
              "GIT_COMMITTER_NAME" -> "BAT Quickstart",
              "GIT_COMMITTER_EMAIL" -> "bat@invalid.local",
              "GIT_COMMITTER_DATE" -> "2000-01-01T00:00:02Z"
            )
          )
          .unit
      }
      head <- lockedCurrent { current =>
        ZIO
          .fail(ToyWorkspace.failure("stale_toy_workspace"))
          .unless(current == next) *>
          ToyRuntime.head(materialized.repository)
      }
    yield head -> next

  private def lockedCurrent[A](
      use: ToyWorkspaceToken => IO[BatError, A]
  ): IO[BatError, A] =
    mutex.withPermit {
      for
        current <- state.get
        actual <- ToyWorkspace.fingerprint(materialized.repository)
        _ <- ZIO
          .fail(ToyWorkspace.failure("toy_workspace_changed"))
          .unless(actual == current.fingerprint)
        result <- use(current)
      yield result
    }

  private def requireCurrent(
      expectedRevision: Long,
      expectedFingerprint: String
  ): IO[BatError, ToyWorkspaceToken] =
    for
      current <- state.get
      actual <- ToyWorkspace.fingerprint(materialized.repository)
      _ <- ZIO
        .fail(ToyWorkspace.failure("toy_workspace_changed"))
        .unless(actual == current.fingerprint)
      _ <- ZIO
        .fail(ToyWorkspace.failure("stale_toy_workspace"))
        .unless(
          expectedRevision == current.revision &&
            expectedFingerprint == current.fingerprint
        )
    yield current

  private def mutate(
      expectedRevision: Long,
      expectedFingerprint: String
  )(
      operation: IO[BatError, Unit]
  ): IO[BatError, ToyWorkspaceToken] =
    mutex.withPermit {
      for
        current <- requireCurrent(expectedRevision, expectedFingerprint)
        _ <- operation
        fingerprint <- ToyWorkspace.fingerprint(materialized.repository)
        _ <- ZIO
          .fail(ToyWorkspace.failure("unchanged_toy_workspace"))
          .when(fingerprint == current.fingerprint)
        next = ToyWorkspaceToken(current.revision + 1L, fingerprint)
        _ <- state.set(next)
      yield next
    }

private[quickstart] object ToyWorkspace:
  private val MaxVisibleFileBytes = 512L * 1024
  private val MaxSearchBytes = 16L * 1024 * 1024
  private val MaxSearchMatches = 200
  private val MaxWorkspaceFiles = 10000
  private val MaxWorkspaceBytes = 64L * 1024 * 1024
  private val MaxPatchBytes = 1024 * 1024
  private val PublicMain =
    "dev.bat.examples.ingress.IngressGatewayPublicTest"
  private val Suites = Set("baseline", "expose", "saturate", "falsify", "final")
  private val ActorForbidden = Set(".git", ".bdr", "oracle", "reference")

  def open(materialized: MaterializedToy): IO[BatError, ToyWorkspace] =
    for
      repository <- validateMaterialized(materialized)
      fingerprint <- fingerprint(repository)
      state <- Ref.make(ToyWorkspaceToken(0L, fingerprint))
      invocations <- Ref.make(0L)
      mutex <- Semaphore.make(1L)
    yield new ToyWorkspace(
      materialized.copy(repository = repository),
      state,
      invocations,
      mutex
    )

  private def validateMaterialized(
      toy: MaterializedToy
  ): IO[BatError, Path] =
    for
      repository <- ZIO
        .attemptBlocking {
          if toy == null || toy.repository == null then throw ToyFailure()
          val path = toy.repository.toAbsolutePath.normalize
          if Files.isSymbolicLink(path) ||
            !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
          then throw ToyFailure()
          Seq("oracle", "reference").foreach { name =>
            if Files.exists(path.resolve(name), LinkOption.NOFOLLOW_LINKS) then
              throw ToyFailure()
          }
          path
        }
        .mapError(_ => failure("invalid_toy_workspace"))
      head <- ToyRuntime.head(repository)
      _ <- ZIO
        .fail(failure("invalid_toy_workspace"))
        .unless(head == toy.headSha)
      remote <- ToyRuntime.git(repository, Seq("remote"))
      _ <- ZIO
        .fail(failure("invalid_toy_workspace"))
        .unless(decodeUtf8(remote.output).trim.isEmpty)
    yield repository

  private[quickstart] def fingerprint(
      repository: Path
  ): IO[BatError, String] =
    for
      head <- ToyRuntime.head(repository)
      digest <- ZIO
        .attemptBlocking {
          val hash = MessageDigest.getInstance("SHA-256")
          ToyRuntime.updateDigest(hash, "bat-toy-workspace-v1")
          ToyRuntime.updateDigest(hash, head)
          updateControlFile(hash, repository.resolve(".git/index"), "index")
          updateControlFile(hash, repository.resolve(".git/config"), "config")
          val stream = Files.walk(repository)
          val paths =
            try
              stream
                .iterator()
                .asScala
                .filter(_ != repository)
                .filter(path =>
                  val relative = repository.relativize(path)
                  relative.getNameCount > 0 &&
                  !Set(".git", ".bdr").contains(
                    relative.getName(0).toString
                  )
                )
                .toList
                .sortBy(path => portable(repository.relativize(path)))
            finally stream.close()
          if paths.size > MaxWorkspaceFiles then throw ToyFailure()
          var total = 0L
          paths.foreach { path =>
            if Files.isSymbolicLink(path) then throw ToyFailure()
            val relative = repository.relativize(path)
            rejectProtected(relative)
            val name = portable(relative)
            if Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
              ToyRuntime.updateDigest(hash, s"directory:$name")
            else if Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
              val size = Files.size(path)
              total = Math.addExact(total, size)
              if total > MaxWorkspaceBytes then throw ToyFailure()
              ToyRuntime.updateDigest(hash, s"file:$name")
              ToyRuntime.updateDigest(hash, size)
              hash.update(Files.readAllBytes(path))
            else throw ToyFailure()
          }
          ToyRuntime.hex(hash.digest())
        }
        .mapError(_ => failure("toy_workspace_fingerprint_failed"))
    yield digest

  private def updateControlFile(
      hash: MessageDigest,
      path: Path,
      label: String
  ): Unit =
    if Files.isSymbolicLink(path) ||
      !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
      Files.size(path) > MaxWorkspaceBytes
    then throw ToyFailure()
    val bytes = Files.readAllBytes(path)
    ToyRuntime.updateDigest(hash, s"git-control:$label")
    ToyRuntime.updateDigest(hash, bytes.length.toLong)
    hash.update(bytes)

  private def readVisibleFile(
      repository: Path,
      raw: String
  ): IO[BatError, String] =
    ZIO
      .attemptBlocking {
        val path = visiblePath(repository, raw)
        if Files.isSymbolicLink(path) ||
          !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
          Files.size(path) > MaxVisibleFileBytes
        then throw ToyFailure()
        decodeUtf8(Files.readAllBytes(path))
      }
      .mapError(_ => failure("invalid_toy_read"))

  private def searchVisibleFiles(
      repository: Path,
      rawQuery: String
  ): IO[BatError, Chunk[ToySearchMatch]] =
    ZIO
      .attemptBlocking {
        val query = Option(rawQuery)
          .filter(value =>
            value.nonEmpty && value.length <= 256 && !value.contains('\u0000')
          )
          .getOrElse(throw ToyFailure())
        val stream = Files.walk(repository)
        val files =
          try
            stream
              .iterator()
              .asScala
              .filter(path =>
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                  !Files.isSymbolicLink(path) &&
                  !Set(".git", ".bdr").contains(
                    repository.relativize(path).getName(0).toString
                  )
              )
              .toList
              .sortBy(path => portable(repository.relativize(path)))
          finally stream.close()
        var bytes = 0L
        var matches = 0
        val result = ChunkBuilder.make[ToySearchMatch]()
        files.foreach { path =>
          val relative = repository.relativize(path)
          rejectProtected(relative)
          val size = Files.size(path)
          bytes = Math.addExact(bytes, size)
          if bytes > MaxSearchBytes then throw ToyFailure()
          if size <= MaxVisibleFileBytes && matches < MaxSearchMatches then
            val lines = decodeUtf8(Files.readAllBytes(path)).linesIterator.toSeq
            lines.zipWithIndex.foreach { case (line, index) =>
              var from = 0
              var at = line.indexOf(query, from)
              while at >= 0 && matches < MaxSearchMatches do
                result += ToySearchMatch(
                  portable(relative),
                  index + 1,
                  at + 1,
                  line.take(512)
                )
                matches += 1
                from = at + math.max(query.length, 1)
                at = line.indexOf(query, from)
            }
        }
        result.result()
      }
      .mapError(_ => failure("invalid_toy_search"))

  private def validatePatch(patch: String): IO[BatError, Unit] =
    ZIO
      .attempt {
        val value = Option(patch).getOrElse(throw ToyFailure())
        val bytes = value.getBytes(StandardCharsets.UTF_8)
        if value.isBlank || value.contains('\u0000') ||
          bytes.length > MaxPatchBytes
        then throw ToyFailure()
        val lines = value.linesIterator.toSeq
        if lines.exists(line =>
            line.startsWith("rename from ") ||
              line.startsWith("rename to ") ||
              line.startsWith("copy from ") ||
              line.startsWith("copy to ") ||
              line == "GIT binary patch" ||
              line.startsWith("Binary files ") ||
              line.startsWith("deleted file mode ") ||
              line.startsWith("old mode ") ||
              line.startsWith("new mode ") ||
              (line.startsWith("new file mode ") &&
                line != "new file mode 100644")
          )
        then throw ToyFailure()
        val diffs = lines.filter(_.startsWith("diff --git "))
        if diffs.isEmpty then throw ToyFailure()
        diffs.foreach { line =>
          val fields = line.stripPrefix("diff --git ").split(" ", -1)
          if fields.length != 2 || !fields(0).startsWith("a/") ||
            !fields(1).startsWith("b/")
          then throw ToyFailure()
          validatePatchPath(fields(0).drop(2))
          validatePatchPath(fields(1).drop(2))
        }
        lines.foreach { line =>
          if line.startsWith("--- ") || line.startsWith("+++ ") then
            val raw = line.drop(4)
            if raw != "/dev/null" then
              if raw.startsWith("a/") || raw.startsWith("b/") then
                validatePatchPath(raw.drop(2))
              else throw ToyFailure()
        }
      }
      .mapError(_ => failure("invalid_toy_patch"))

  private def validatePatchPath(raw: String): Unit =
    if raw.isEmpty || raw.contains('\\') || raw.contains('\u0000') ||
      raw.startsWith("/")
    then throw ToyFailure()
    val path = Path.of(raw).normalize
    if path.isAbsolute || path.toString != raw ||
      !(raw.startsWith("src/main/java/") ||
        raw.startsWith("src/test/java/"))
    then throw ToyFailure()
    rejectProtected(path)

  private def validateCommitMessage(message: String): IO[BatError, Unit] =
    ZIO
      .attempt {
        val value = Option(message).getOrElse(throw ToyFailure())
        if value.isBlank || value.length > 256 || value.contains('\u0000') ||
          value.contains('\r') || value.contains('\n')
        then throw ToyFailure()
      }
      .mapError(_ => failure("invalid_toy_commit_message"))

  private def runPublicSuite(
      repository: Path,
      suite: String
  ): IO[BatError, ToyTestResult] =
    ZIO.scoped {
      for
        _ <- ZIO
          .fail(failure("invalid_toy_suite"))
          .unless(Suites.contains(suite))
        classes <- temporaryDirectory("bat-toy-public-")
        sources <- javaSources(repository)
        compile <- ToyRuntime.command(
          Seq(
            ToyRuntime.Javac.toString,
            "--release",
            "17",
            "-d",
            classes.toString
          ) ++ sources.map(_.toString),
          repository
        )
        run <-
          if compile.exitCode == 0 then
            ToyRuntime.command(
              Seq(
                ToyRuntime.Java.toString,
                "-cp",
                classes.toString,
                PublicMain
              ),
              classes
            )
          else
            ZIO.succeed(
              ToyCommandResult(compile.exitCode, Array.emptyByteArray)
            )
        output = compile.combinedOutput ++ run.combinedOutput
        digestBytes =
          s"bat-toy-public-v1\n$suite\n${compile.exitCode}\n${run.exitCode}\n"
            .getBytes(StandardCharsets.UTF_8) ++ output
        failureFingerprint = assertionFingerprint(output)
      yield ToyTestResult(
        exitCode =
          if compile.exitCode == 0 then run.exitCode else compile.exitCode,
        assertionFailed = failureFingerprint.nonEmpty,
        failureFingerprint = failureFingerprint,
        outputDigest = s"sha256:${ToyRuntime.sha256(digestBytes)}",
        suite = suite,
        workspace = ToyWorkspaceToken(-1L, "pending")
      )
    }

  private def javaSources(repository: Path): IO[BatError, Seq[Path]] =
    ZIO
      .attemptBlocking {
        val roots = Seq(
          repository.resolve("src/main/java"),
          repository.resolve("src/test/java")
        )
        val sources = roots
          .flatMap { root =>
            if Files.isSymbolicLink(root) ||
              !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
            then throw ToyFailure()
            val stream = Files.walk(root)
            try
              stream
                .iterator()
                .asScala
                .filter(path =>
                  Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(path) &&
                    path.getFileName.toString.endsWith(".java")
                )
                .toSeq
            finally stream.close()
          }
          .sortBy(_.toString)
        if sources.isEmpty then throw ToyFailure()
        sources
      }
      .mapError(_ => failure("invalid_toy_sources"))

  private def assertionFingerprint(output: Array[Byte]): Option[String] =
    val marker = "java.lang.AssertionError:"
    decodeUtf8(output).linesIterator.collectFirst {
      case line if line.contains(marker) =>
        val message = line.drop(line.indexOf(marker) + marker.length).trim
        s"sha256:${ToyRuntime.sha256(message.getBytes(StandardCharsets.UTF_8))}"
    }

  private def temporaryDirectory(
      prefix: String
  ): ZIO[Scope, BatError, Path] =
    ZIO.acquireRelease(
      ZIO
        .attemptBlocking(Files.createTempDirectory(prefix))
        .mapError(_ => failure("toy_test_workspace_failed"))
    )(deleteRecursively)

  private def deleteRecursively(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
        val stream = Files.walk(path)
        try
          stream
            .iterator()
            .asScala
            .toSeq
            .sortBy(_.getNameCount)
            .reverse
            .foreach(candidate =>
              val _ = Files.deleteIfExists(candidate)
            )
        finally stream.close()
    }.ignore

  private def visiblePath(repository: Path, raw: String): Path =
    val value = Option(raw).filter(_.nonEmpty).getOrElse(throw ToyFailure())
    val relative = Path.of(value)
    if relative.isAbsolute || relative.normalize != relative then
      throw ToyFailure()
    rejectProtected(relative)
    val resolved = repository.resolve(relative).normalize
    if !resolved.startsWith(repository) then throw ToyFailure()
    resolved

  private def rejectProtected(relative: Path): Unit =
    if relative.getNameCount == 0 ||
      ActorForbidden.contains(relative.getName(0).toString.toLowerCase)
    then throw ToyFailure()

  private def portable(path: Path): String =
    path.iterator().asScala.map(_.toString).mkString("/")

  private[quickstart] def decodeUtf8(bytes: Array[Byte]): String =
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString

  private final case class ToyFailure() extends RuntimeException

  private[quickstart] def failure(code: String): BatError =
    BatError.BackendFailure(code, "toy workspace operation failed", false)

private[quickstart] final case class ToySearchMatch(
    path: String,
    line: Int,
    column: Int,
    preview: String
)

private[quickstart] final case class ToyTestResult(
    exitCode: Int,
    assertionFailed: Boolean,
    failureFingerprint: Option[String],
    outputDigest: String,
    suite: String,
    workspace: ToyWorkspaceToken
)

/** Closed strict tool definitions for the executable Java toy. */
private[quickstart] object ToyTools:
  def all(workspace: ToyWorkspace): Chunk[Tool] =
    Chunk(
      WorkspaceStateTool(workspace),
      ReadFileTool(workspace),
      SearchTool(workspace),
      GitDiffTool(workspace),
      ApplyPatchTool(workspace),
      RunTestsTool(workspace),
      GitCommitTool(workspace)
    )

  private final case class WorkspaceStateTool(workspace: ToyWorkspace)
      extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.WorkspaceState

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      tool(
        workspace.workspaceState.map(token => obj("workspace" -> state(token)))
      )

  private final case class ReadFileTool(workspace: ToyWorkspace) extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.ReadFile

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      tool {
        workspace.readFile(string(invocation, "path")).map {
          case (content, token) =>
            obj(
              "path" -> Json.Str(string(invocation, "path")),
              "content" -> Json.Str(content),
              "workspace" -> state(token)
            )
        }
      }

  private final case class SearchTool(workspace: ToyWorkspace) extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.Search

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      tool {
        workspace.search(string(invocation, "query")).map {
          case (matches, token) =>
            obj(
              "matches" -> Json.Arr(
                matches.map(result =>
                  obj(
                    "path" -> Json.Str(result.path),
                    "line" -> number(result.line.toLong),
                    "column" -> number(result.column.toLong),
                    "preview" -> Json.Str(result.preview)
                  )
                )
              ),
              "workspace" -> state(token)
            )
        }
      }

  private final case class GitDiffTool(workspace: ToyWorkspace) extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.GitDiff

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      tool(workspace.gitDiff.map { case (patch, token) =>
        obj("patch" -> Json.Str(patch), "workspace" -> state(token))
      })

  private final case class ApplyPatchTool(workspace: ToyWorkspace) extends Tool:
    val definition: ToolDefinition = Definitions.ApplyPatch

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      tool {
        workspace
          .applyPatch(
            string(invocation, "patch"),
            boolean(invocation, "reverse"),
            integer(invocation, "expected_revision"),
            string(invocation, "expected_fingerprint")
          )
          .map(token => obj("workspace" -> state(token)))
      }

  private final case class RunTestsTool(workspace: ToyWorkspace) extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.RunTests

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      tool {
        workspace
          .runTests(
            string(invocation, "suite"),
            integer(invocation, "expected_revision"),
            string(invocation, "expected_fingerprint")
          )
          .map(result =>
            obj(
              "exit_code" -> number(result.exitCode.toLong),
              "assertion_failed" -> Json.Bool(result.assertionFailed),
              "failure_fingerprint" -> result.failureFingerprint.fold[Json](
                Json.Null
              )(Json.Str.apply),
              "output_digest" -> Json.Str(result.outputDigest),
              "suite" -> Json.Str(result.suite),
              "workspace" -> state(result.workspace)
            )
          )
      }

  private final case class GitCommitTool(workspace: ToyWorkspace) extends Tool:
    val definition: ToolDefinition = Definitions.GitCommit

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      tool {
        workspace
          .gitCommit(
            string(invocation, "message"),
            integer(invocation, "expected_revision"),
            string(invocation, "expected_fingerprint")
          )
          .map { case (head, token) =>
            obj(
              "head_sha" -> Json.Str(head),
              "workspace" -> state(token)
            )
          }
      }

  private def tool[A <: Json](effect: IO[BatError, A]): IO[ToolError, Json] =
    effect
      .map(value => value: Json)
      .mapError(error =>
        ToolError
          .make(error.code)
          .getOrElse(Errors.Failed)
      )

  private def string(invocation: ToolInvocation, name: String): String =
    invocation.arguments.fields
      .collectFirst { case (`name`, Json.Str(value)) =>
        value
      }
      .getOrElse(throw IllegalStateException(s"validated field missing: $name"))

  private def boolean(invocation: ToolInvocation, name: String): Boolean =
    invocation.arguments.fields
      .collectFirst { case (`name`, Json.Bool(value)) =>
        value
      }
      .getOrElse(throw IllegalStateException(s"validated field missing: $name"))

  private def integer(invocation: ToolInvocation, name: String): Long =
    invocation.arguments.fields
      .collectFirst { case (`name`, Json.Num(value)) =>
        Try(value.longValueExact()).toOption
      }
      .flatten
      .getOrElse(
        throw IllegalStateException(s"validated integer missing: $name")
      )

  private def state(token: ToyWorkspaceToken): Json.Obj =
    obj(
      "revision" -> number(token.revision),
      "fingerprint" -> Json.Str(token.fingerprint)
    )

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))

  private object Errors:
    val Failed: ToolError =
      ToolError
        .make("toy_tool_failed")
        .fold(error => throw IllegalStateException(error.safeMessage), identity)

  private object Definitions:
    val WorkspaceState: ToolDefinition = definition(
      "toy_workspace_state",
      "Read the exact revision and fingerprint of the isolated toy workspace.",
      objectSchema()
    )
    val ReadFile: ToolDefinition = definition(
      "toy_read_file",
      "Read one bounded actor-visible UTF-8 file.",
      objectSchema("path" -> stringSchema)
    )
    val Search: ToolDefinition = definition(
      "toy_search",
      "Run a deterministic bounded fixed-string search over actor-visible files.",
      objectSchema("query" -> stringSchema)
    )
    val GitDiff: ToolDefinition = definition(
      "toy_git_diff",
      "Read the actor-visible Java diff from the target commit.",
      objectSchema()
    )
    val ApplyPatch: ToolDefinition = definition(
      "toy_apply_patch",
      "Apply or reverse one Java-only patch at an exact workspace state.",
      mutationSchema(
        "patch" -> stringSchema,
        "reverse" -> booleanSchema
      )
    )
    val RunTests: ToolDefinition = definition(
      "toy_run_tests",
      "Compile Java 17 and execute the dependency-free public assertion main.",
      mutationSchema(
        "suite" -> enumSchema(
          "baseline",
          "expose",
          "saturate",
          "falsify",
          "final"
        )
      )
    )
    val GitCommit: ToolDefinition = definition(
      "toy_git_commit",
      "Create one deterministic local commit without hooks, signing, or push.",
      mutationSchema("message" -> stringSchema)
    )

    private def definition(
        name: String,
        description: String,
        parameters: Json.Obj
    ): ToolDefinition =
      ToolDefinition
        .make(name, description, parameters)
        .fold(error => throw IllegalStateException(error.safeMessage), identity)

    private def mutationSchema(fields: (String, Json.Obj)*): Json.Obj =
      objectSchema(
        (Chunk.fromIterable(fields) ++ Chunk(
          "expected_revision" -> integerSchema,
          "expected_fingerprint" -> stringSchema
        ))*
      )

    private def objectSchema(fields: (String, Json.Obj)*): Json.Obj =
      val properties = Chunk.fromIterable(fields)
      Json.Obj(
        Chunk(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(properties),
          "required" -> Json.Arr(
            properties.map { case (name, _) => Json.Str(name) }
          ),
          "additionalProperties" -> Json.Bool(false)
        )
      )

    private lazy val stringSchema =
      Json.Obj(Chunk("type" -> Json.Str("string")))
    private lazy val integerSchema =
      Json.Obj(Chunk("type" -> Json.Str("integer")))
    private lazy val booleanSchema =
      Json.Obj(Chunk("type" -> Json.Str("boolean")))
    private def enumSchema(values: String*): Json.Obj =
      Json.Obj(
        Chunk(
          "type" -> Json.Str("string"),
          "enum" -> Json.Arr(Chunk.fromIterable(values).map(Json.Str(_)))
        )
      )
