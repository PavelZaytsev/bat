package bat.worker

import bat.worker.oci.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object WorkspaceProvisionerSpec extends ZIOSpecDefault:
  private val Pins = unsafe(
    PullRequestPins.make(
      "R_base",
      "R_head",
      "PR_42",
      "refs/heads/main",
      "1" * 40,
      "refs/pull/42/head",
      "2" * 40
    )
  )
  private val Limits = unsafeOci(
    OciLimits.make(
      10.seconds,
      1024,
      1024,
      8192L,
      128,
      1024L * 1024 * 1024,
      BigDecimal(2),
      64L * 1024 * 1024,
      16L * 1024 * 1024
    )
  )
  private val StorageLimits = unsafe(
    WorkerStorageLimits.make(
      maxSourceBytes = 1024L * 1024,
      maxSourcePaths = 1000L,
      maxCheckoutBytes = 1024L * 1024,
      maxCheckoutPaths = 1000L,
      maxTreeMetadataBytes = 8192
    )
  )

  def spec =
    suite("isolated workspace provisioner")(
      test(
        "initializes, fetches only exact PR refs, and checks out exact head"
      ) {
        ZIO.scoped {
          for
            fixture <- paths
            (source, control, runDirectory) = fixture
            allocation = RunWorkspace.Allocation(
              runId("run-provision"),
              control,
              runDirectory,
              runDirectory.resolve("repository"),
              Pins
            )
            sandbox <- RecordingSandbox.make(provisionSuccess)
            result <- WorkspaceProvisioner
              .provision(
                sandbox,
                source,
                allocation,
                Limits,
                StorageLimits
              )
              .either
            requests <- sandbox.requests
            inspection = requests.head
            init = requests(1)
            fetch = requests(2)
            checkout = requests(3)
          yield assertTrue(
            result == Right(()),
            requests.size == 4,
            inspection.operationId == WorkspaceProvisioner
              .provisionOperationId(allocation.runId, "inspect-tree"),
            inspection.argv == Chunk(
              "/usr/bin/git",
              "-c",
              "core.hooksPath=/dev/null",
              "-c",
              "filter.lfs.smudge=",
              "-c",
              "filter.lfs.required=false",
              "-C",
              "/bat/source",
              "ls-tree",
              "-rlz",
              "--full-tree",
              Pins.headCommit.value
            ),
            inspection.mounts.map(mount =>
              (
                mount.source,
                mount.destination.value,
                mount.access
              )
            ) == Chunk(
              (
                source.toAbsolutePath.normalize,
                "/bat/source",
                MountAccess.ReadOnly
              )
            ),
            inspection.workingDirectory.value == "/bat/source",
            inspection.limits.stdoutPreviewBytes == StorageLimits.maxTreeMetadataBytes,
            init.operationId == WorkspaceProvisioner.provisionOperationId(
              allocation.runId,
              "init"
            ),
            init.argv == Chunk(
              "/usr/bin/git",
              "-c",
              "core.hooksPath=/dev/null",
              "-c",
              "filter.lfs.smudge=",
              "-c",
              "filter.lfs.required=false",
              "init",
              "--initial-branch=bat-empty",
              "/bat/work/repository"
            ),
            fetch.operationId == WorkspaceProvisioner.provisionOperationId(
              allocation.runId,
              "fetch"
            ),
            fetch.argv == Chunk(
              "/usr/bin/git",
              "-c",
              "core.hooksPath=/dev/null",
              "-c",
              "filter.lfs.smudge=",
              "-c",
              "filter.lfs.required=false",
              "-C",
              "/bat/work/repository",
              "fetch",
              "--no-tags",
              "--no-write-fetch-head",
              "/bat/source",
              s"+${Pins.baseRef.value}:refs/bat/base",
              s"+${Pins.headRef.value}:refs/bat/head"
            ),
            checkout.operationId == WorkspaceProvisioner.provisionOperationId(
              allocation.runId,
              "checkout"
            ),
            checkout.argv == Chunk(
              "/usr/bin/git",
              "-c",
              "core.hooksPath=/dev/null",
              "-c",
              "filter.lfs.smudge=",
              "-c",
              "filter.lfs.required=false",
              "-C",
              "/bat/work/repository",
              "checkout",
              "--detach",
              "--no-recurse-submodules",
              Pins.headCommit.value
            ),
            init.workingDirectory.value == "/bat/work",
            fetch.workingDirectory.value == "/bat/work",
            checkout.workingDirectory.value == "/bat/work",
            init.mounts == fetch.mounts,
            fetch.mounts == checkout.mounts,
            init.mounts.map(mount =>
              (
                mount.source,
                mount.destination.value,
                mount.access
              )
            ) == Chunk(
              (
                source.toAbsolutePath.normalize,
                "/bat/source",
                MountAccess.ReadOnly
              ),
              (
                runDirectory,
                "/bat/work",
                MountAccess.ReadWrite
              )
            )
          )
        }
      },
      test("stops after a failed init and reports the exact phase") {
        ZIO.scoped {
          for
            fixture <- paths
            (source, control, runDirectory) = fixture
            allocation = RunWorkspace.Allocation(
              runId("run-init-failure"),
              control,
              runDirectory,
              runDirectory.resolve("repository"),
              Pins
            )
            sandbox <- RecordingSandbox.make(request =>
              if isInspection(request) then treeResult(request, 7L)
              else exited(17)
            )
            result <- WorkspaceProvisioner
              .provision(
                sandbox,
                source,
                allocation,
                Limits,
                StorageLimits
              )
              .either
            requests <- sandbox.requests
          yield assertTrue(
            errorCode(result).contains("workspace_init_failed"),
            requests.map(_.operationId) == Chunk(
              WorkspaceProvisioner.provisionOperationId(
                allocation.runId,
                "inspect-tree"
              ),
              WorkspaceProvisioner.provisionOperationId(
                allocation.runId,
                "init"
              )
            )
          )
        }
      },
      test("stops after an exact-ref fetch failure") {
        ZIO.scoped {
          for
            fixture <- paths
            (source, control, runDirectory) = fixture
            allocation = RunWorkspace.Allocation(
              runId("run-fetch-failure"),
              control,
              runDirectory,
              runDirectory.resolve("repository"),
              Pins
            )
            sandbox <- RecordingSandbox.make(request =>
              if isInspection(request) then treeResult(request, 7L)
              else if request.argv.contains("fetch") then exited(23)
              else success
            )
            result <- WorkspaceProvisioner
              .provision(
                sandbox,
                source,
                allocation,
                Limits,
                StorageLimits
              )
              .either
            requests <- sandbox.requests
          yield assertTrue(
            errorCode(result).contains("workspace_fetch_failed"),
            requests.map(_.operationId) == Chunk(
              WorkspaceProvisioner.provisionOperationId(
                allocation.runId,
                "inspect-tree"
              ),
              WorkspaceProvisioner.provisionOperationId(
                allocation.runId,
                "init"
              ),
              WorkspaceProvisioner.provisionOperationId(
                allocation.runId,
                "fetch"
              )
            )
          )
        }
      },
      test(
        "rejects a tiny compressed source object with oversized checkout expansion"
      ) {
        ZIO.scoped {
          for
            fixture <- paths
            (source, control, runDirectory) = fixture
            compressed <- ZIO.attemptBlocking(
              writeCompressedBlob(source, expandedBytes = 4096)
            )
            (objectId, physicalObjectBytes) = compressed
            allocation = RunWorkspace.Allocation(
              runId("run-checkout-expansion"),
              control,
              runDirectory,
              runDirectory.resolve("repository"),
              Pins
            )
            sandbox <- RecordingSandbox.make(request =>
              if isInspection(request) then
                treeResult(request, 4096L, objectId = objectId)
              else success
            )
            tight = unsafe(
              WorkerStorageLimits.make(
                maxSourceBytes = 1024L,
                maxSourcePaths = 100L,
                maxCheckoutBytes = 1024L,
                maxCheckoutPaths = 100L,
                maxTreeMetadataBytes = 4096
              )
            )
            result <- WorkspaceProvisioner
              .provision(sandbox, source, allocation, Limits, tight)
              .either
            requests <- sandbox.requests
          yield assertTrue(
            errorCode(result).contains("checkout_byte_limit_exceeded"),
            physicalObjectBytes < 1024L,
            requests.size == 1,
            isInspection(requests.head),
            !requests.exists(_.argv.contains("init")),
            !requests.exists(_.argv.contains("fetch")),
            !requests.exists(_.argv.contains("checkout"))
          )
        }
      },
      test("counts expanded checkout directories as bounded paths") {
        ZIO.scoped {
          for
            fixture <- paths
            (source, control, runDirectory) = fixture
            allocation = RunWorkspace.Allocation(
              runId("run-checkout-path-expansion"),
              control,
              runDirectory,
              runDirectory.resolve("repository"),
              Pins
            )
            sandbox <- RecordingSandbox.make(request =>
              if isInspection(request) then
                treeResult(request, 1L, "one/two/File.java")
              else success
            )
            tight = unsafe(
              WorkerStorageLimits.make(
                maxSourceBytes = 1024L,
                maxSourcePaths = 100L,
                maxCheckoutBytes = 1024L,
                maxCheckoutPaths = 2L,
                maxTreeMetadataBytes = 4096
              )
            )
            result <- WorkspaceProvisioner
              .provision(sandbox, source, allocation, Limits, tight)
              .either
            requests <- sandbox.requests
          yield assertTrue(
            errorCode(result).contains("checkout_path_limit_exceeded"),
            requests.size == 1,
            isInspection(requests.head)
          )
        }
      },
      test("rejects case-aliased Git and BDR control paths") {
        ZIO.scoped {
          for
            fixture <- paths
            (source, control, runDirectory) = fixture
            allocation = RunWorkspace.Allocation(
              runId("run-case-aliased-control"),
              control,
              runDirectory,
              runDirectory.resolve("repository"),
              Pins
            )
            sandbox <- RecordingSandbox.make(request =>
              if isInspection(request) then
                treeResult(request, 1L, ".BDR/progress.yaml")
              else success
            )
            result <- WorkspaceProvisioner
              .provision(
                sandbox,
                source,
                allocation,
                Limits,
                StorageLimits
              )
              .either
            requests <- sandbox.requests
          yield assertTrue(
            errorCode(result).contains("invalid_source_tree_metadata"),
            requests.size == 1,
            isInspection(requests.head)
          )
        }
      },
      test("fails closed on malformed or truncated tree metadata") {
        ZIO.scoped {
          for
            malformedFixture <- paths
            (malformedSource, malformedControl, malformedRun) =
              malformedFixture
            malformedAllocation = RunWorkspace.Allocation(
              runId("run-tree-malformed"),
              malformedControl,
              malformedRun,
              malformedRun.resolve("repository"),
              Pins
            )
            malformedSandbox <- RecordingSandbox.make(request =>
              if isInspection(request) then
                outputResult(
                  request.operationId,
                  "not-a-tree-record\u0000".getBytes(StandardCharsets.UTF_8)
                )
              else success
            )
            malformed <- WorkspaceProvisioner
              .provision(
                malformedSandbox,
                malformedSource,
                malformedAllocation,
                Limits,
                StorageLimits
              )
              .either
            malformedRequests <- malformedSandbox.requests
            truncatedFixture <- paths
            (truncatedSource, truncatedControl, truncatedRun) =
              truncatedFixture
            truncatedAllocation = RunWorkspace.Allocation(
              runId("run-tree-truncated"),
              truncatedControl,
              truncatedRun,
              truncatedRun.resolve("repository"),
              Pins
            )
            truncatedSandbox <- RecordingSandbox.make(request =>
              if isInspection(request) then truncatedTreeResult(request)
              else success
            )
            truncated <- WorkspaceProvisioner
              .provision(
                truncatedSandbox,
                truncatedSource,
                truncatedAllocation,
                Limits,
                StorageLimits
              )
              .either
            truncatedRequests <- truncatedSandbox.requests
          yield assertTrue(
            errorCode(malformed).contains("invalid_source_tree_metadata"),
            malformedRequests.size == 1,
            errorCode(truncated).contains("source_tree_metadata_truncated"),
            truncatedRequests.size == 1
          )
        }
      },
      test("fails closed on a nonzero tree inspection") {
        ZIO.scoped {
          for
            fixture <- paths
            (source, control, runDirectory) = fixture
            allocation = RunWorkspace.Allocation(
              runId("run-tree-nonzero"),
              control,
              runDirectory,
              runDirectory.resolve("repository"),
              Pins
            )
            sandbox <- RecordingSandbox.make(request =>
              if isInspection(request) then exited(request.operationId, 19)
              else success
            )
            result <- WorkspaceProvisioner
              .provision(
                sandbox,
                source,
                allocation,
                Limits,
                StorageLimits
              )
              .either
            requests <- sandbox.requests
          yield assertTrue(
            errorCode(result).contains("source_tree_inspection_failed"),
            requests.size == 1,
            isInspection(requests.head)
          )
        }
      },
      test("rejects oversized source Git storage before sandbox invocation") {
        ZIO.scoped {
          for
            fixture <- paths
            (source, control, runDirectory) = fixture
            _ <- ZIO.attemptBlocking {
              val objects = Files.createDirectories(
                source.resolve(".git").resolve("objects").resolve("pack")
              )
              val _ = Files.write(
                objects.resolve("pack-source.pack"),
                Array.fill[Byte](17)(1)
              )
            }
            allocation = RunWorkspace.Allocation(
              runId("run-source-bytes"),
              control,
              runDirectory,
              runDirectory.resolve("repository"),
              Pins
            )
            sandbox <- RecordingSandbox.make(_ => success)
            tight = unsafe(
              WorkerStorageLimits.make(
                maxSourceBytes = 16L,
                maxSourcePaths = 100L,
                maxCheckoutBytes = 1024L,
                maxCheckoutPaths = 100L,
                maxTreeMetadataBytes = 4096
              )
            )
            result <- WorkspaceProvisioner
              .provision(sandbox, source, allocation, Limits, tight)
              .either
            requests <- sandbox.requests
          yield assertTrue(
            errorCode(result).contains("source_byte_limit_exceeded"),
            requests.isEmpty
          )
        }
      },
      test("rejects too many source paths before sandbox invocation") {
        ZIO.scoped {
          for
            fixture <- paths
            (source, control, runDirectory) = fixture
            _ <- ZIO.attemptBlocking {
              (1 to 3).foreach { index =>
                val _ = Files.write(
                  source.resolve(s"source-$index"),
                  Array(index.toByte)
                )
              }
            }
            allocation = RunWorkspace.Allocation(
              runId("run-source-paths"),
              control,
              runDirectory,
              runDirectory.resolve("repository"),
              Pins
            )
            sandbox <- RecordingSandbox.make(_ => success)
            tight = unsafe(
              WorkerStorageLimits.make(
                maxSourceBytes = 1024L,
                maxSourcePaths = 2L,
                maxCheckoutBytes = 1024L,
                maxCheckoutPaths = 100L,
                maxTreeMetadataBytes = 4096
              )
            )
            result <- WorkspaceProvisioner
              .provision(sandbox, source, allocation, Limits, tight)
              .either
            requests <- sandbox.requests
          yield assertTrue(
            errorCode(result).contains("source_path_limit_exceeded"),
            requests.isEmpty
          )
        }
      },
      test("rejects source symlinks before sandbox invocation") {
        ZIO.scoped {
          for
            fixture <- paths
            (source, control, runDirectory) = fixture
            _ <- ZIO.attemptBlocking {
              val _ = Files.createSymbolicLink(
                source.resolve("source-link"),
                Path.of("missing-target")
              )
            }
            allocation = RunWorkspace.Allocation(
              runId("run-source-symlink"),
              control,
              runDirectory,
              runDirectory.resolve("repository"),
              Pins
            )
            sandbox <- RecordingSandbox.make(_ => success)
            result <- WorkspaceProvisioner
              .provision(
                sandbox,
                source,
                allocation,
                Limits,
                StorageLimits
              )
              .either
            requests <- sandbox.requests
          yield assertTrue(
            errorCode(result).contains("unsafe_source_storage"),
            requests.isEmpty
          )
        }
      }
    ) @@ TestAspect.sequential

  private def paths: ZIO[Scope, Throwable, (Path, Path, Path)] =
    for
      source <- temporaryDirectory("bat-provision-source-")
      control <- temporaryDirectory("bat-provision-control-")
      runDirectory <- temporaryDirectory("bat-provision-work-")
    yield (source, control, runDirectory)

  private def success: OciRunResult = exited(0)

  private def provisionSuccess(request: OciRunRequest): OciRunResult =
    if isInspection(request) then treeResult(request, 7L)
    else success

  private def isInspection(request: OciRunRequest): Boolean =
    request.argv.contains("ls-tree")

  private def treeResult(
      request: OciRunRequest,
      expandedBytes: Long,
      path: String = "src/Main.java",
      objectId: String = "a" * 40
  ): OciRunResult =
    val record =
      s"100644 blob $objectId $expandedBytes\t$path\u0000"
        .getBytes(StandardCharsets.UTF_8)
    outputResult(request.operationId, record)

  private def outputResult(
      operationId: String,
      bytes: Array[Byte]
  ): OciRunResult =
    OciRunResult(
      operationId,
      OciRunOutcome.Exited(0),
      OciStreamReceipt(
        bytes.length.toLong,
        sha256(bytes),
        Chunk.fromArray(bytes),
        previewTruncated = false
      ),
      emptyStream,
      1L
    )

  private def truncatedTreeResult(request: OciRunRequest): OciRunResult =
    val bytes =
      s"100644 blob ${"a" * 40} 7\tsrc/Main.java"
        .getBytes(StandardCharsets.UTF_8)
    OciRunResult(
      request.operationId,
      OciRunOutcome.Exited(0),
      OciStreamReceipt(
        bytes.length.toLong + 1L,
        sha256(bytes),
        Chunk.fromArray(bytes),
        previewTruncated = true
      ),
      emptyStream,
      1L
    )

  private def exited(code: Int): OciRunResult =
    exited("ignored-by-fake", code)

  private def exited(operationId: String, code: Int): OciRunResult =
    OciRunResult(
      operationId,
      OciRunOutcome.Exited(code),
      emptyStream,
      emptyStream,
      1L
    )

  private def emptyStream: OciStreamReceipt =
    OciStreamReceipt(
      0L,
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      Chunk.empty,
      previewTruncated = false
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def writeCompressedBlob(
      source: Path,
      expandedBytes: Int
  ): (String, Long) =
    val content = Array.fill[Byte](expandedBytes)('x'.toByte)
    val header =
      s"blob $expandedBytes\u0000".getBytes(StandardCharsets.US_ASCII)
    val looseObject = header ++ content
    val objectId = MessageDigest
      .getInstance("SHA-1")
      .digest(looseObject)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
    val compressed = ByteArrayOutputStream()
    val deflater = DeflaterOutputStream(compressed)
    try deflater.write(looseObject)
    finally deflater.close()
    val objectDirectory = Files.createDirectories(
      source.resolve(".git").resolve("objects").resolve(objectId.take(2))
    )
    val path = objectDirectory.resolve(objectId.drop(2))
    val _ = Files.write(path, compressed.toByteArray)
    objectId -> Files.size(path)

  private def runId(value: String): RunId = unsafe(RunId.from(value))

  private def errorCode[A](result: Either[WorkerError, A]): Option[String] =
    result.left.toOption.map(_.code)

  private def unsafe[A](result: Either[WorkerError, A]): A =
    result.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def unsafeOci[A](result: Either[OciFailure, A]): A =
    result.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def temporaryDirectory(prefix: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix))
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

  private final class RecordingSandbox private (
      result: OciRunRequest => OciRunResult,
      ref: Ref[Chunk[OciRunRequest]]
  ) extends OciSandbox:
    val image: PinnedImage = unsafeOci(
      PinnedImage.from("ghcr.io/bat/java-worker@sha256:" + ("a" * 64))
    )

    def requests: UIO[Chunk[OciRunRequest]] = ref.get

    def cleanup(operationId: String): IO[OciFailure, Unit] = ZIO.unit

    def run(request: OciRunRequest): IO[OciFailure, OciRunResult] =
      ref.update(_ :+ request).as(result(request))

  private object RecordingSandbox:
    def make(
        result: OciRunRequest => OciRunResult
    ): UIO[RecordingSandbox] =
      Ref
        .make(Chunk.empty[OciRunRequest])
        .map(
          new RecordingSandbox(result, _)
        )
