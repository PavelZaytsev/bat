package bat.worker

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object PathGuardSpec extends ZIOSpecDefault:
  def spec =
    suite("repository path, read, search, and patch policy")(
      test("rejects absolute paths and traversal before filesystem access") {
        val absolute = RepositoryPath.from(Path.of("/etc/passwd"))
        val parent = RepositoryPath.from(Path.of("..", "outside.txt"))
        val nestedParent = RepositoryPath.from(
          Path.of("src", "..", "outside.txt")
        )

        assertTrue(
          errorCode(absolute).contains("invalid_repository_path"),
          errorCode(parent).contains("invalid_repository_path"),
          errorCode(nestedParent).contains("invalid_repository_path")
        )
      },
      test("rejects final and intermediate symlinks for reads and writes") {
        ZIO.scoped {
          for
            fixture <- workspace
            (root, outside) = fixture
            _ <- ZIO.attemptBlocking {
              val _ = Files.writeString(outside.resolve("secret.txt"), "canary")
              val _ = Files.createSymbolicLink(
                root.resolve("linked-file"),
                outside.resolve("secret.txt")
              )
              val _ = Files.createSymbolicLink(
                root.resolve("linked-dir"),
                outside
              )
            }
            guard <- PathGuard.open(root)
            finalLink <- guard
              .resolveForRead(path("linked-file"))
              .either
            intermediate <- guard
              .resolveForRead(path("linked-dir/secret.txt"))
              .either
            writeThrough <- guard
              .resolveForWrite(path("linked-dir/new.txt"))
              .either
          yield assertTrue(
            errorCode(finalLink).contains("symlink_repository_path"),
            errorCode(intermediate).contains("symlink_repository_path"),
            errorCode(writeThrough).contains("symlink_repository_path")
          )
        }
      },
      test("protects Git and BDR state from model writes") {
        ZIO.scoped {
          for
            fixture <- workspace
            (root, _) = fixture
            guard <- PathGuard.open(root)
            git <- guard.resolveForWrite(path(".git/config")).either
            bdr <- guard.resolveForWrite(path(".bdr/progress.yaml")).either
            source <- guard.resolveForWrite(path("src/Main.java")).either
          yield assertTrue(
            errorCode(git).contains("protected_repository_path"),
            errorCode(bdr).contains("protected_repository_path"),
            source.toOption.contains(root.resolve("src/Main.java"))
          )
        }
      },
      test("bounded reader rejects oversized and malformed UTF-8 files") {
        ZIO.scoped {
          for
            fixture <- workspace
            (root, _) = fixture
            _ <- ZIO.attemptBlocking {
              val _ = Files.writeString(root.resolve("large.txt"), "abcdef")
              val _ = Files.write(
                root.resolve("binary.dat"),
                Array[Byte](0xc3.toByte, 0x28.toByte)
              )
            }
            reader <- RepositoryReader.open(root)
            oversized <- reader.read(path("large.txt"), 3).either
            malformed <- reader.read(path("binary.dat"), 32).either
            valid <- reader.read(path("large.txt"), 6).either
          yield assertTrue(
            errorCode(oversized).contains("read_limit_exceeded"),
            errorCode(malformed).contains("file_read_failed"),
            valid.contains("abcdef")
          )
        }
      },
      test(
        "search is literal, bounded, deterministic, and ignores Git internals"
      ) {
        ZIO.scoped {
          for
            fixture <- workspace
            (root, _) = fixture
            _ <- ZIO.attemptBlocking {
              val src = Files.createDirectories(root.resolve("src"))
              val git = Files.createDirectories(root.resolve(".git"))
              val _ =
                Files.writeString(src.resolve("B.java"), "needle needle\n")
              val _ =
                Files.writeString(src.resolve("A.java"), "needle.*literal\n")
              val _ = Files.writeString(git.resolve("config"), "needle\n")
            }
            reader <- RepositoryReader.open(root)
            literal <- reader.search("needle.*literal")
            ordered <- reader.search("needle", maxMatches = 2)
            bounded <- reader
              .search("needle", maxMatches = 10, maxScannedBytes = 2L)
              .either
          yield assertTrue(
            literal.map(_.path) == Chunk("src/A.java"),
            ordered.map(match_ => match_.path -> match_.column) == Chunk(
              "src/A.java" -> 1,
              "src/B.java" -> 1
            ),
            errorCode(bounded).contains("search_limit_exceeded")
          )
        }
      },
      test("patch policy accepts plain in-repository text edits") {
        val patch =
          """diff --git a/src/Main.java b/src/Main.java
            |--- a/src/Main.java
            |+++ b/src/Main.java
            |@@ -1 +1 @@
            |-class Main {}
            |+final class Main {}
            |""".stripMargin

        val result = PatchPolicy.validate(patch)

        assertTrue(
          result.toOption.exists(
            _.map(_.value.toString) == Chunk("src/Main.java")
          )
        )
      },
      test(
        "patch policy rejects traversal, protected, symlink, and rename edits"
      ) {
        val traversal = PatchPolicy.validate(
          "diff --git a/../secret b/../secret\n--- a/../secret\n+++ b/../secret\n"
        )
        val absolute = PatchPolicy.validate(
          "diff --git a//etc/passwd b//etc/passwd\n--- a//etc/passwd\n+++ b//etc/passwd\n"
        )
        val protectedState = PatchPolicy.validate(
          "diff --git a/.bdr/progress.yaml b/.bdr/progress.yaml\n--- a/.bdr/progress.yaml\n+++ b/.bdr/progress.yaml\n"
        )
        val symlink = PatchPolicy.validate(
          "diff --git a/link b/link\nnew file mode 120000\n"
        )
        val rename = PatchPolicy.validate(
          "diff --git a/Old.java b/New.java\nrename from Old.java\nrename to New.java\n"
        )
        val disguisedProtectedState = PatchPolicy.validate(
          "diff --git a/src/Main.java b/src/Main.java\n--- a/.bdr/progress.yaml\n+++ b/.bdr/progress.yaml\n"
        )
        val missingMarkers = PatchPolicy.validate(
          "diff --git a/src/Main.java b/src/Main.java\n@@ -1 +1 @@\n-a\n+b\n"
        )

        assertTrue(
          errorCode(traversal).contains("invalid_repository_path"),
          errorCode(absolute).contains("invalid_repository_path"),
          Seq(
            protectedState,
            disguisedProtectedState,
            missingMarkers,
            symlink,
            rename
          )
            .forall(errorCode(_).contains("invalid_patch"))
        )
      }
    ) @@ TestAspect.sequential

  private def workspace: ZIO[Scope, Throwable, (Path, Path)] =
    for
      root <- temporaryDirectory("bat-path-root-")
      outside <- temporaryDirectory("bat-path-outside-")
    yield root -> outside

  private def temporaryDirectory(prefix: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix))
    )(deleteRecursively)

  private def path(value: String): RepositoryPath =
    unsafe(RepositoryPath.from(Path.of(value)))

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
