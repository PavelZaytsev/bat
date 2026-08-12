package bat.worker

import java.nio.file.Path

import zio.Chunk

enum JavaBuildAction(val wire: String):
  case MavenTest extends JavaBuildAction("maven_test")
  case MavenVerify extends JavaBuildAction("maven_verify")
  case GradleTest extends JavaBuildAction("gradle_test")
  case GradleCheck extends JavaBuildAction("gradle_check")

final case class JavaBuildRequest private (
    action: JavaBuildAction,
    testSelector: Option[String]
)

object JavaBuildRequest:
  private val Selector =
    "^[A-Za-z_$][A-Za-z0-9_.$]*(?:#[A-Za-z_$][A-Za-z0-9_$]*)?$".r
  private val MaxSelectorCharacters = 512

  def make(
      action: JavaBuildAction,
      testSelector: Option[String]
  ): Either[WorkerError, JavaBuildRequest] =
    if action == null then invalid("build action must be explicit")
    else if testSelector.exists(value =>
        value.length > MaxSelectorCharacters || !Selector.matches(value)
      )
    then invalid("test selector must be one Java class or class#method name")
    else if testSelector.nonEmpty &&
      action != JavaBuildAction.MavenTest &&
      action != JavaBuildAction.GradleTest
    then invalid("test selectors are allowed only for focused test actions")
    else Right(JavaBuildRequest(action, testSelector))

  private def invalid(message: String): Left[WorkerError, Nothing] =
    Left(WorkerError.InvalidInput("invalid_java_build_request", message))

final case class JavaCommandPlan(
    kind: WorkerOperationKind,
    policyId: String,
    argv: Chunk[String],
    requestIdentity: String
)

final case class JavaBuildPolicy private (
    policyId: String,
    mavenExecutable: String,
    gradleExecutable: String
):
  def plan(request: JavaBuildRequest): JavaCommandPlan =
    request.action match
      case JavaBuildAction.MavenTest =>
        JavaCommandPlan(
          WorkerOperationKind.MavenTest,
          policyId,
          Chunk(
            mavenExecutable,
            "--batch-mode",
            "--offline",
            "--no-transfer-progress",
            "-Dstyle.color=never",
            "-Dmaven.repo.local=/bat/run/cache/maven"
          ) ++ request.testSelector.map(value => s"-Dtest=$value") ++
            Chunk("test"),
          buildIdentity(request)
        )
      case JavaBuildAction.MavenVerify =>
        JavaCommandPlan(
          WorkerOperationKind.MavenVerify,
          policyId,
          Chunk(
            mavenExecutable,
            "--batch-mode",
            "--offline",
            "--no-transfer-progress",
            "-Dstyle.color=never",
            "-Dmaven.repo.local=/bat/run/cache/maven",
            "verify"
          ),
          buildIdentity(request)
        )
      case JavaBuildAction.GradleTest =>
        JavaCommandPlan(
          WorkerOperationKind.GradleTest,
          policyId,
          Chunk(
            gradleExecutable,
            "--offline",
            "--no-daemon",
            "--console=plain",
            "--gradle-user-home",
            "/bat/run/cache/gradle",
            "test"
          ) ++ request.testSelector.fold(Chunk.empty[String])(value =>
            Chunk("--tests", value)
          ),
          buildIdentity(request)
        )
      case JavaBuildAction.GradleCheck =>
        JavaCommandPlan(
          WorkerOperationKind.GradleCheck,
          policyId,
          Chunk(
            gradleExecutable,
            "--offline",
            "--no-daemon",
            "--console=plain",
            "--gradle-user-home",
            "/bat/run/cache/gradle",
            "check"
          ),
          buildIdentity(request)
        )

  private def buildIdentity(request: JavaBuildRequest): String =
    val scope =
      request.testSelector.fold("full")(selector => s"selector=$selector")
    s"java-build-v1:${request.action.wire}:$scope"

object JavaBuildPolicy:
  def make(
      policyId: String,
      mavenExecutable: String,
      gradleExecutable: String
  ): Either[WorkerError, JavaBuildPolicy] =
    for
      _ <- validatePolicyId(policyId)
      maven <- validateExecutable(mavenExecutable, "maven")
      gradle <- validateExecutable(gradleExecutable, "gradle")
    yield JavaBuildPolicy(policyId, maven, gradle)

  private def validatePolicyId(value: String): Either[WorkerError, Unit] =
    OperationId.from(value).map(_ => ())

  private def validateExecutable(
      value: String,
      label: String
  ): Either[WorkerError, String] =
    if value == null || !value.startsWith("/") || value.contains('\u0000') ||
      value.split("/", -1).exists(part => part == "." || part == "..")
    then
      Left(
        WorkerError.InvalidInput(
          s"invalid_${label}_executable",
          s"$label executable must be a normalized absolute container path"
        )
      )
    else Right(Path.of(value).normalize.toString)

object GitCommandPolicy:
  val PolicyId = "git-v1"
  val Executable = "/usr/bin/git"

  private val Prefix = Chunk(
    Executable,
    "-c",
    "core.hooksPath=/dev/null",
    "-c",
    "core.fsmonitor=false",
    "-c",
    "diff.external=",
    "-c",
    "commit.gpgSign=false"
  )

  val status: JavaCommandPlan = JavaCommandPlan(
    WorkerOperationKind.GitStatus,
    PolicyId,
    Prefix ++ Chunk(
      "status",
      "--porcelain=v2",
      "--untracked-files=all",
      "--ignore-submodules=all"
    ),
    "git-v1:status"
  )

  val diff: JavaCommandPlan = JavaCommandPlan(
    WorkerOperationKind.GitDiff,
    PolicyId,
    Prefix ++ Chunk(
      "diff",
      "--no-ext-diff",
      "--no-textconv",
      "--binary",
      "HEAD"
    ),
    "git-v1:diff"
  )

  def targetDiff(pins: PullRequestPins): JavaCommandPlan =
    JavaCommandPlan(
      WorkerOperationKind.TargetDiff,
      PolicyId,
      Prefix ++ Chunk(
        "diff",
        "--no-ext-diff",
        "--no-textconv",
        "--binary",
        pins.baseCommit.value,
        pins.headCommit.value
      ),
      s"git-v1:target-diff:${pins.baseCommit.value}:${pins.headCommit.value}"
    )

  def targetPaths(pins: PullRequestPins): JavaCommandPlan =
    JavaCommandPlan(
      WorkerOperationKind.TargetPaths,
      PolicyId,
      Prefix ++ Chunk(
        "diff",
        "--name-only",
        "-z",
        "--no-renames",
        pins.baseCommit.value,
        pins.headCommit.value
      ),
      s"git-v1:target-paths:${pins.baseCommit.value}:${pins.headCommit.value}"
    )

  val applyPatch: JavaCommandPlan = JavaCommandPlan(
    WorkerOperationKind.Patch,
    PolicyId,
    Prefix ++ Chunk(
      "apply",
      "--index",
      "--whitespace=error-all",
      "/bat/input/change.patch"
    ),
    "git-v1:apply-patch"
  )

  def commit(message: String): Either[WorkerError, JavaCommandPlan] =
    if message == null || message.trim.isEmpty || message.length > 2000 ||
      message.indexOf('\u0000') >= 0
    then
      Left(
        WorkerError.InvalidInput(
          "invalid_commit_message",
          "commit message must contain 1-2000 characters without NUL bytes"
        )
      )
    else
      Right(
        JavaCommandPlan(
          WorkerOperationKind.GitCommit,
          PolicyId,
          Prefix ++ Chunk(
            "-c",
            "user.name=BAT",
            "-c",
            "user.email=bat@invalid.local",
            "commit",
            "--no-verify",
            "--no-gpg-sign",
            "--message",
            message
          ),
          "git-v1:commit"
        )
      )
