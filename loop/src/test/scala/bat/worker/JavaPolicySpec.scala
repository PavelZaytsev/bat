package bat.worker

import zio.Chunk
import zio.test.*

object JavaPolicySpec extends ZIOSpecDefault:
  private val Maven = "/opt/bat/bin/mvn"
  private val Gradle = "/opt/bat/bin/gradle"
  private val Policy = unsafe(
    JavaBuildPolicy.make("java-v1", Maven, Gradle)
  )

  def spec =
    suite("Java build command policy")(
      test("plans an offline focused Bazel test against the mounted caches") {
        val request = unsafe(
          JavaBuildRequest.make(
            JavaBuildAction.BazelTest,
            Some("//core/datastore-manager:TopologyChangeManagerTest")
          )
        )
        val plan = Policy.plan(request)
        val argv = plan.argv
        assertTrue(
          plan.kind == WorkerOperationKind.BazelTest,
          !plan.kind.mutating,
          // The worker has no network, so fetching is refused outright rather
          // than attempted and failing slowly.
          argv.contains("--nofetch"),
          argv.contains("--repository_cache=/bat/run/cache/bazel/repo"),
          argv.contains("--disk_cache=/bat/run/cache/bazel/disk"),
          argv.contains("--output_user_root=/bat/run/cache/bazel/root"),
          argv.last == "//core/datastore-manager:TopologyChangeManagerTest",
          plan.requestIdentity.startsWith("java-build-v1:bazel_test:selector=")
        )
      },
      test("rejects a Bazel request that is not one focused absolute label") {
        assertTrue(
          // Bazel cannot run "everything" implicitly, and an unfocused run is
          // not the focused selection BDR asked for.
          JavaBuildRequest.make(JavaBuildAction.BazelTest, None).isLeft,
          JavaBuildRequest
            .make(JavaBuildAction.BazelTest, Some("core/datastore-manager:X"))
            .isLeft,
          JavaBuildRequest
            .make(JavaBuildAction.BazelTest, Some("//..."))
            .isLeft,
          JavaBuildRequest
            .make(JavaBuildAction.BazelTest, Some("//pkg:t --config=evil"))
            .isLeft,
          JavaBuildRequest
            .make(JavaBuildAction.BazelTest, Some("//core/dsm/..."))
            .isRight
        )
      },
      test("renders exact offline Maven test argv with one literal selector") {
        val request = unsafe(
          JavaBuildRequest.make(
            JavaBuildAction.MavenTest,
            Some("com.acme.CacheTest#evictsEntry")
          )
        )
        val plan = Policy.plan(request)

        assertTrue(
          plan.kind == WorkerOperationKind.MavenTest,
          plan.policyId == "java-v1",
          plan.requestIdentity ==
            "java-build-v1:maven_test:selector=com.acme.CacheTest#evictsEntry",
          plan.argv == Chunk(
            Maven,
            "--batch-mode",
            "--offline",
            "--no-transfer-progress",
            "-Dstyle.color=never",
            "-Dmaven.repo.local=/bat/run/cache/maven",
            "-Dtest=com.acme.CacheTest#evictsEntry",
            "test"
          )
        )
      },
      test("renders exact offline Gradle test argv without a shell") {
        val request = unsafe(
          JavaBuildRequest.make(
            JavaBuildAction.GradleTest,
            Some("com.acme.CacheTest")
          )
        )
        val plan = Policy.plan(request)

        assertTrue(
          plan.kind == WorkerOperationKind.GradleTest,
          plan.requestIdentity ==
            "java-build-v1:gradle_test:selector=com.acme.CacheTest",
          plan.argv == Chunk(
            Gradle,
            "--offline",
            "--no-daemon",
            "--console=plain",
            "--gradle-user-home",
            "/bat/run/cache/gradle",
            "test",
            "--tests",
            "com.acme.CacheTest"
          ),
          !plan.argv.contains("/bin/sh"),
          !plan.argv.contains("-c")
        )
      },
      test("limits broad verification to fixed verify and check actions") {
        val maven = Policy.plan(
          unsafe(JavaBuildRequest.make(JavaBuildAction.MavenVerify, None))
        )
        val gradle = Policy.plan(
          unsafe(JavaBuildRequest.make(JavaBuildAction.GradleCheck, None))
        )

        assertTrue(
          maven.argv.last == "verify",
          maven.argv.contains("--offline"),
          maven.kind == WorkerOperationKind.MavenVerify,
          maven.requestIdentity == "java-build-v1:maven_verify:full",
          gradle.argv.last == "check",
          gradle.argv.contains("--offline"),
          gradle.kind == WorkerOperationKind.GradleCheck,
          gradle.requestIdentity == "java-build-v1:gradle_check:full",
          JavaBuildAction.values.map(_.wire).toSet == Set(
            "maven_test",
            "maven_verify",
            "gradle_test",
            "gradle_check",
            "bazel_test"
          )
        )
      },
      test("rejects deploy-like and argument-shaped test selectors") {
        val unsafeSelectors = Seq(
          "CacheTest;deploy",
          "CacheTest --tests Evil",
          "../../CacheTest",
          "CacheTest#method#again",
          "CacheTest*",
          "-DskipTests",
          "A" * 513
        )
        val results = unsafeSelectors.map(selector =>
          JavaBuildRequest.make(JavaBuildAction.MavenTest, Some(selector))
        )
        val verifySelector = JavaBuildRequest.make(
          JavaBuildAction.MavenVerify,
          Some("CacheTest")
        )
        val checkSelector = JavaBuildRequest.make(
          JavaBuildAction.GradleCheck,
          Some("CacheTest")
        )

        assertTrue(
          results.forall(
            errorCode(_).contains("invalid_java_build_request")
          ),
          errorCode(verifySelector).contains("invalid_java_build_request"),
          errorCode(checkSelector).contains("invalid_java_build_request")
        )
      },
      test("rejects host, relative, and traversal-shaped executables") {
        val relative = JavaBuildPolicy.make("java-v1", "mvn", Gradle)
        val traversal = JavaBuildPolicy.make(
          "java-v1",
          "/opt/bat/../bin/mvn",
          Gradle
        )
        val nul = JavaBuildPolicy.make(
          "java-v1",
          "/opt/bat/bin/mvn\u0000--evil",
          Gradle
        )

        assertTrue(
          errorCode(relative).contains("invalid_maven_executable"),
          errorCode(traversal).contains("invalid_maven_executable"),
          errorCode(nul).contains("invalid_maven_executable")
        )
      }
    )

  private def errorCode[A](result: Either[WorkerError, A]): Option[String] =
    result.left.toOption.map(_.code)

  private def unsafe[A](result: Either[WorkerError, A]): A =
    result.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )
