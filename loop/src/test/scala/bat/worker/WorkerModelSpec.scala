package bat.worker

import zio.test.*

object WorkerModelSpec extends ZIOSpecDefault:
  private val Base = "1" * 40
  private val Head = "2" * 40

  def spec =
    suite("worker model")(
      test("accepts same-named refs from distinct fork repositories") {
        val pins = PullRequestPins.make(
          baseRepository = "R_base_123",
          headRepository = "R_fork_456",
          pullRequestId = "PR_99",
          baseRef = "refs/heads/main",
          baseCommit = Base,
          headRef = "refs/heads/main",
          headCommit = Head
        )

        assertTrue(
          pins.isRight,
          pins.toOption.exists(_.baseRepository.value == "R_base_123"),
          pins.toOption.exists(_.headRepository.value == "R_fork_456"),
          pins.toOption.exists(_.pullRequestId.value == "PR_99")
        )
      },
      test("rejects abbreviated objects and revision-like refs") {
        val abbreviated = PullRequestPins.make(
          "R_base",
          "R_head",
          "PR_1",
          "refs/heads/main",
          Base.take(12),
          "refs/pull/1/head",
          Head
        )
        val revisionExpression = PullRequestPins.make(
          "R_base",
          "R_head",
          "PR_1",
          "refs/heads/main~1",
          Base,
          "refs/pull/1/head",
          Head
        )
        val shorthand = PullRequestPins.make(
          "R_base",
          "R_head",
          "PR_1",
          "main",
          Base,
          "refs/pull/1/head",
          Head
        )

        assertTrue(
          errorCode(abbreviated).contains("invalid_base_commit"),
          errorCode(revisionExpression).contains("invalid_base_ref"),
          errorCode(shorthand).contains("invalid_base_ref")
        )
      },
      test("rejects unbounded or path-shaped provider identities") {
        val badRepository = PullRequestPins.make(
          "../../repo",
          "R_head",
          "PR_1",
          "refs/heads/main",
          Base,
          "refs/pull/1/head",
          Head
        )
        val badPullRequest = PullRequestPins.make(
          "R_base",
          "R_head",
          "PR/1",
          "refs/heads/main",
          Base,
          "refs/pull/1/head",
          Head
        )

        assertTrue(
          errorCode(badRepository).contains("invalid_base_repository_id"),
          errorCode(badPullRequest).contains("invalid_pull_request_id")
        )
      },
      test(
        "derives stable domain-separated operation IDs from provider calls"
      ) {
        val runOne = unsafe(RunId.from("run-one"))
        val runTwo = unsafe(RunId.from("run-two"))
        val first = OperationId.derive(runOne, "call_ABC:42", "apply_patch")
        val replay = OperationId.derive(runOne, "call_ABC:42", "apply_patch")
        val otherRun = OperationId.derive(runTwo, "call_ABC:42", "apply_patch")
        val otherTool = OperationId.derive(runOne, "call_ABC:42", "git_commit")

        assertTrue(
          first == replay,
          first != otherRun,
          first != otherTool,
          first.value.matches("[0-9a-f]{64}"),
          OperationId.from(first.value).contains(first)
        )
      },
      test("operation binding validates policy and optional image digests") {
        val operationId = unsafe(OperationId.from("operation-1"))
        val workspace = WorkspacePrecondition(
          unsafe(WorkspaceRevision.from(0L)),
          unsafe(WorkspaceFingerprint.from("a" * 64))
        )
        val valid = WorkerOperation.make(
          operationId,
          WorkerOperationKind.MavenTest,
          "b" * 64,
          "java-build-v1:maven_test:full",
          workspace,
          "java-tests-v1",
          Some("c" * 64)
        )
        val floatingImage = WorkerOperation.make(
          operationId,
          WorkerOperationKind.MavenTest,
          "b" * 64,
          "java-build-v1:maven_test:full",
          workspace,
          "java-tests-v1",
          Some("latest")
        )
        val unsafePolicy = WorkerOperation.make(
          operationId,
          WorkerOperationKind.MavenTest,
          "b" * 64,
          "java-build-v1:maven_test:full",
          workspace,
          "../../policy"
        )
        val unsafeIdentity = WorkerOperation.make(
          operationId,
          WorkerOperationKind.MavenTest,
          "b" * 64,
          "java-build-v1|maven_test|full",
          workspace,
          "java-tests-v1"
        )
        val oversizedIdentity = WorkerOperation.make(
          operationId,
          WorkerOperationKind.MavenTest,
          "b" * 64,
          "x" * 1025,
          workspace,
          "java-tests-v1"
        )

        assertTrue(
          valid.isRight,
          errorCode(floatingImage).contains("invalid_image_digest"),
          errorCode(unsafePolicy).contains("invalid_policy_id"),
          errorCode(unsafeIdentity).contains("invalid_request_identity"),
          errorCode(oversizedIdentity).contains("invalid_request_identity")
        )
      }
    )

  private def errorCode[A](value: Either[WorkerError, A]): Option[String] =
    value.left.toOption.map(_.code)

  private def unsafe[A](value: Either[WorkerError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )
