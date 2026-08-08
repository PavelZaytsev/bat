package bat.worker

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  SimpleFileVisitor
}

import scala.jdk.CollectionConverters.*
import scala.util.Try

import zio.{Chunk, IO, ZIO}

final class PathGuard private (val root: Path):
  def resolveForRead(path: RepositoryPath): IO[WorkerError, Path] =
    resolve(path, writing = false).flatMap { resolved =>
      ZIO
        .attemptBlocking(
          Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
        )
        .mapError(_ => pathFailure)
        .filterOrFail(identity)(
          WorkerError.ToolFailure(
            "not_regular_file",
            "repository path is not a regular file"
          )
        )
        .as(resolved)
    }

  def resolveForWrite(path: RepositoryPath): IO[WorkerError, Path] =
    resolve(path, writing = true)

  def isProtected(path: RepositoryPath): Boolean =
    val first = path.value.getName(0).toString
    first.equalsIgnoreCase(".git") || first.equalsIgnoreCase(".bdr")

  private def resolve(
      path: RepositoryPath,
      writing: Boolean
  ): IO[WorkerError, Path] =
    ZIO
      .attemptBlocking {
        if writing && isProtected(path) then throw ProtectedPathFailure()
        val resolved = root.resolve(path.value).normalize
        if !resolved.startsWith(root) then throw PathEscapeFailure()
        val parent = Option(resolved.getParent).getOrElse(root)
        requireNoSymlinkComponents(parent)
        if Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) &&
          Files.isSymbolicLink(resolved)
        then throw SymlinkFailure()
        resolved
      }
      .mapError {
        case _: ProtectedPathFailure =>
          WorkerError.ToolFailure(
            "protected_repository_path",
            "model tools cannot write .git or .bdr"
          )
        case _: SymlinkFailure =>
          WorkerError.ToolFailure(
            "symlink_repository_path",
            "repository path crosses a symbolic link"
          )
        case _ => pathFailure
      }

  private def requireNoSymlinkComponents(path: Path): Unit =
    var current = root
    val relative = root.relativize(path)
    relative.iterator().asScala.foreach { part =>
      current = current.resolve(part)
      if Files.isSymbolicLink(current) then throw SymlinkFailure()
    }

  private def pathFailure: WorkerError =
    WorkerError.ToolFailure(
      "invalid_repository_path",
      "repository path could not be resolved safely"
    )

  private final case class ProtectedPathFailure() extends RuntimeException
  private final case class PathEscapeFailure() extends RuntimeException
  private final case class SymlinkFailure() extends RuntimeException

object PathGuard:
  def open(root: Path): IO[WorkerError, PathGuard] =
    ZIO
      .attemptBlocking {
        if root == null then throw new IllegalArgumentException("root required")
        val normalized = root.toAbsolutePath.normalize
        if Files.isSymbolicLink(normalized) ||
          !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
        then throw new IllegalStateException("unsafe root")
        PathGuard(normalized)
      }
      .mapError(_ =>
        WorkerError.ToolFailure(
          "invalid_workspace_root",
          "worker workspace root is not a safe directory"
        )
      )

final case class SearchMatch(
    path: String,
    line: Int,
    column: Int,
    preview: String
)

final class RepositoryReader private (guard: PathGuard):
  def read(path: RepositoryPath, maxBytes: Int): IO[WorkerError, String] =
    if maxBytes <= 0 || maxBytes > RepositoryReader.MaxFileBytes then
      ZIO.fail(
        WorkerError.InvalidInput(
          "invalid_read_limit",
          "read limit must be between 1 byte and 1 MiB"
        )
      )
    else
      guard.resolveForRead(path).flatMap { resolved =>
        ZIO
          .attemptBlocking {
            val size = Files.size(resolved)
            if size > maxBytes then throw RepositoryReader.SizeFailure()
            RepositoryReader.decode(Files.readAllBytes(resolved))
          }
          .mapError {
            case _: RepositoryReader.SizeFailure =>
              WorkerError.ToolFailure(
                "read_limit_exceeded",
                "repository file exceeds the requested read limit"
              )
            case _ =>
              WorkerError.ToolFailure(
                "file_read_failed",
                "repository file could not be read as UTF-8"
              )
          }
      }

  def search(
      needle: String,
      maxMatches: Int = 200,
      maxScannedBytes: Long = 16L * 1024 * 1024
  ): IO[WorkerError, Chunk[SearchMatch]] =
    if needle == null || needle.isEmpty || needle.length > 1024 then
      invalidSearch("search text must contain 1-1024 characters")
    else if maxMatches <= 0 || maxMatches > 1000 then
      invalidSearch("search result limit must be between 1 and 1000")
    else if maxScannedBytes <= 0L || maxScannedBytes > 128L * 1024 * 1024 then
      invalidSearch("search byte limit must be between 1 byte and 128 MiB")
    else
      ZIO
        .attemptBlocking {
          val candidates = RepositoryReader.searchableFiles(guard.root)
          var scanned = 0L
          val matches = Chunk.newBuilder[SearchMatch]
          var count = 0
          val iterator = candidates.iterator
          while iterator.hasNext && count < maxMatches do
            val path = iterator.next()
            val size = Files.size(path)
            if size <= RepositoryReader.MaxFileBytes then
              scanned = Math.addExact(scanned, size)
              if scanned > maxScannedBytes then
                throw RepositoryReader.SearchLimitFailure()
              val text = RepositoryReader.decode(Files.readAllBytes(path))
              text.linesIterator.zipWithIndex.foreach { case (line, index) =>
                var from = 0
                var found = line.indexOf(needle, from)
                while found >= 0 && count < maxMatches do
                  matches += SearchMatch(
                    guard.root.relativize(path).toString,
                    index + 1,
                    found + 1,
                    line.take(500)
                  )
                  count += 1
                  from = found + math.max(needle.length, 1)
                  found = line.indexOf(needle, from)
              }
          matches.result()
        }
        .mapError {
          case _: RepositoryReader.SearchLimitFailure =>
            WorkerError.ToolFailure(
              "search_limit_exceeded",
              "repository search exceeded its byte limit"
            )
          case _ =>
            WorkerError.ToolFailure(
              "search_failed",
              "repository search could not be completed safely"
            )
        }

  private def invalidSearch(message: String): IO[WorkerError, Nothing] =
    ZIO.fail(WorkerError.InvalidInput("invalid_search", message))

object RepositoryReader:
  private val MaxFileBytes = 1024L * 1024
  private val MaxScanPaths = 100000

  private[worker] def searchableFiles(root: Path): List[Path] =
    val candidates = scala.collection.mutable.ListBuffer.empty[Path]
    var visited = 0
    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(
            directory: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult =
          if directory != root && internal(root, directory) then
            FileVisitResult.SKIP_SUBTREE
          else
            countPath()
            FileVisitResult.CONTINUE

        override def visitFile(
            file: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult =
          countPath()
          if attributes.isSymbolicLink then throw SearchLimitFailure()
          if attributes.isRegularFile then candidates += file
          FileVisitResult.CONTINUE

        private def countPath(): Unit =
          visited += 1
          if visited > MaxScanPaths then throw SearchLimitFailure()
    )
    candidates.toList.sortBy(path => root.relativize(path).toString)

  private def internal(root: Path, path: Path): Boolean =
    val relative = root.relativize(path)
    relative.getNameCount > 0 && {
      val first = relative.getName(0).toString
      first.equalsIgnoreCase(".git") || first.equalsIgnoreCase(".bdr")
    }

  def open(root: Path): IO[WorkerError, RepositoryReader] =
    PathGuard.open(root).map(RepositoryReader(_))

  private def decode(bytes: Array[Byte]): String =
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString

  private final case class SizeFailure() extends RuntimeException
  private final case class SearchLimitFailure() extends RuntimeException

object PatchPolicy:
  private val MaxPatchBytes = 2 * 1024 * 1024
  private val ForbiddenMarkers = Chunk(
    "GIT binary patch",
    "Binary files ",
    "new file mode 120000",
    "new file mode 160000",
    "old mode ",
    "new mode ",
    "rename from ",
    "rename to ",
    "copy from ",
    "copy to ",
    "similarity index ",
    "dissimilarity index "
  )

  def validate(patch: String): Either[WorkerError, Chunk[RepositoryPath]] =
    if patch == null || patch.isEmpty then invalid("patch must not be empty")
    else if patch.getBytes(StandardCharsets.UTF_8).length > MaxPatchBytes then
      invalid("patch exceeds the 2 MiB limit")
    else if ForbiddenMarkers.exists(patch.contains) then
      invalid(
        "patch contains an unsupported binary, mode, copy, or rename change"
      )
    else
      val lines = patch.linesIterator.toVector
      val unsupportedDiff = lines.exists(line =>
        line.startsWith("diff --") && !line.startsWith("diff --git ")
      )
      val starts = lines.zipWithIndex.collect {
        case (line, index) if line.startsWith("diff --git ") => index
      }
      if unsupportedDiff || starts.isEmpty then
        invalid("patch has unsupported or missing Git diff headers")
      else
        val boundaries = starts :+ lines.length
        starts
          .zip(boundaries.tail)
          .foldLeft[
            Either[WorkerError, Chunk[RepositoryPath]]
          ](Right(Chunk.empty)) { case (result, (start, end)) =>
            result.flatMap { paths =>
              validateSection(lines.slice(start, end)).map(paths :+ _)
            }
          }
          .map(_.distinct)

  private def validateSection(
      lines: Vector[String]
  ): Either[WorkerError, RepositoryPath] =
    for
      path <- diffPath(lines.headOption.getOrElse(""))
      preamble = lines.takeWhile(line => !line.startsWith("@@"))
      oldMarkers = preamble.filter(_.startsWith("--- "))
      newMarkers = preamble.filter(_.startsWith("+++ "))
      _ <-
        if oldMarkers.size == 1 && newMarkers.size == 1 then Right(())
        else invalid("patch must bind exactly one old and new path per diff")
      oldNull <- markerMatches(oldMarkers.head.drop(4), "a/", path)
      newNull <- markerMatches(newMarkers.head.drop(4), "b/", path)
      _ <-
        if oldNull && newNull then invalid("patch cannot diff two null paths")
        else Right(())
    yield path

  private def diffPath(header: String): Either[WorkerError, RepositoryPath] =
    header.split(" ", -1).toList match
      case "diff" :: "--git" :: left :: right :: Nil
          if left.startsWith("a/") && right.startsWith("b/") &&
            left.drop(2) == right.drop(2) =>
        val raw = left.drop(2)
        Try(Path.of(raw)).toEither.left
          .map(_ => invalidPath)
          .flatMap { candidate =>
            if candidate.toString != raw then Left(invalidPath)
            else RepositoryPath.from(candidate)
          }
          .flatMap { path =>
            val first = path.value.getName(0).toString
            if first.equalsIgnoreCase(".git") ||
              first.equalsIgnoreCase(".bdr")
            then invalid("patch cannot modify .git or .bdr")
            else Right(path)
          }
      case _ => invalid("patch contains an unsupported diff header")

  private def markerMatches(
      marker: String,
      prefix: String,
      expected: RepositoryPath
  ): Either[WorkerError, Boolean] =
    if marker == "/dev/null" then Right(true)
    else if marker == s"$prefix${expected.value}" then Right(false)
    else invalid("patch file markers do not match their protected diff path")

  private def invalidPath: WorkerError =
    WorkerError.InvalidInput(
      "invalid_patch",
      "patch contains an invalid repository path"
    )

  private def invalid(
      message: String
  ): Left[WorkerError, Nothing] =
    Left(WorkerError.InvalidInput("invalid_patch", message))
