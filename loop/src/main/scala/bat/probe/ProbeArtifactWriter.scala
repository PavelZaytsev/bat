package bat.probe

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.util.UUID

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import bat.protocol.StrictJson

import zio.{Chunk, IO, Ref, Scope, ZIO}

/** A prepared, private staging directory for one probe evidence set.
  *
  * The final directory does not exist until all three canonical documents have
  * been validated, written with owner-only permissions, and atomically moved
  * into place. Neither path is exposed through the value rendering.
  */
final class PreparedProbeOutput private[probe] (
    private[probe] val staging: Path,
    private val destination: Path,
    private val published: Ref[Boolean]
):
  override def toString: String =
    "PreparedProbeOutput(path=<redacted>, state=<redacted>)"

  def publish(
      artifact: ProbeResultArtifact,
      forbiddenValues: Chunk[String]
  ): IO[ProbeError, Unit] =
    for
      alreadyPublished <- published.get
      _ <- fail(
        !alreadyPublished,
        "probe_artifacts_already_published",
        "probe artifacts may be published only once"
      )
      _ <- fail(
        artifact != null && forbiddenValues != null &&
          !forbiddenValues.exists(_ == null),
        "invalid_probe_artifacts",
        "probe artifact publication input is invalid"
      )
      documents = Chunk(
        "result.json" -> artifact.canonicalJson,
        "safe-trace.json" -> artifact.safeTraceJson,
        "telemetry.json" -> artifact.telemetryJson
      )
      _ <- validateDocuments(documents)
      _ <- rejectLeaks(documents, forbiddenValues)
      _ <- ZIO
        .attemptBlocking {
          documents.foreach { case (name, contents) =>
            writePrivate(staging.resolve(name), contents)
          }
        }
        .mapError(_ =>
          ProbeError.make(
            "probe_artifact_publication_failed",
            "probe artifacts could not be written privately"
          )
        )
      _ <- (ZIO
        .attemptBlocking {
          if Files.exists(destination, LinkOption.NOFOLLOW_LINKS) then
            throw IllegalStateException("destination appeared during run")
          // Staging and destination are siblings. Without REPLACE_EXISTING the
          // NIO contract fails if a racing writer creates the destination; the
          // Linux/macOS providers implement this same-filesystem rename as one
          // publication step.
          val _ = Files.move(staging, destination)
        }
        .mapError(_ =>
          ProbeError.make(
            "probe_artifact_publication_failed",
            "probe artifacts could not be published without overwrite"
          )
        ) *> published.set(true)).uninterruptible
    yield ()

  private def validateDocuments(
      documents: Chunk[(String, String)]
  ): IO[ProbeError, Unit] =
    ZIO.foreachDiscard(documents) { case (_, contents) =>
      ZIO.fromEither(
        StrictJson
          .parse(contents, "probe artifact")
          .left
          .map(_ =>
            ProbeError.make(
              "probe_artifact_encoding_invalid",
              "probe artifact encoding is invalid"
            )
          )
      )
    }

  private def rejectLeaks(
      documents: Chunk[(String, String)],
      forbiddenValues: Chunk[String]
  ): IO[ProbeError, Unit] =
    val payload = documents.map(_._2).mkString("\n")
    val leaked = forbiddenValues.filter(_.nonEmpty).exists(payload.contains)
    fail(
      !leaked,
      "probe_artifact_leak_detected",
      "probe artifact publication failed the redaction check"
    )

  private def writePrivate(path: Path, contents: String): Unit =
    val _ = Files.writeString(
      path,
      contents,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE
    )
    val _ =
      Files.setPosixFilePermissions(path, ProbeArtifactWriter.FilePermissions)

  private def fail(
      condition: Boolean,
      code: String,
      message: String
  ): IO[ProbeError, Unit] =
    ZIO.fail(ProbeError.make(code, message)).unless(condition).unit

object ProbeArtifactWriter:
  private val DirectoryPermissions =
    PosixFilePermissions.fromString("rwx------")
  private[probe] val FilePermissions =
    PosixFilePermissions.fromString("rw-------")

  /** Prepare the output boundary before a live HTTP client is constructed. The
    * destination must be absent, outside the BAT checkout, and have a
    * pre-existing real parent with no symbolic-link component.
    */
  def prepare(
      output: ProbeOutputDirectory,
      projectRoot: Path
  ): ZIO[Scope, ProbeError, PreparedProbeOutput] =
    ZIO.acquireRelease(create(output, projectRoot))(prepared =>
      cleanup(prepared)
    )

  private def create(
      output: ProbeOutputDirectory,
      projectRoot: Path
  ): IO[ProbeError, PreparedProbeOutput] =
    for
      _ <- fail(
        output != null && projectRoot != null,
        "invalid_probe_output_boundary",
        "probe output boundary is invalid"
      )
      paths <- ZIO
        .attemptBlocking {
          val requested = output.path
          val parent = requested.getParent
          if parent == null || !Files.isDirectory(
              parent,
              LinkOption.NOFOLLOW_LINKS
            )
          then throw IllegalArgumentException("invalid parent")
          rejectSymbolicLinkComponents(parent)
          val realParent = parent.toRealPath()
          val realProject = projectRoot.toRealPath()
          val parentPermissions = Files.getPosixFilePermissions(realParent)
          val parentOwner = Files.getOwner(realParent)
          val projectOwner = Files.getOwner(realProject)
          val destination = realParent.resolve(requested.getFileName)
          if !Files.isWritable(realParent) ||
            parentOwner != projectOwner ||
            parentPermissions.contains(PosixFilePermission.GROUP_WRITE) ||
            parentPermissions.contains(PosixFilePermission.OTHERS_WRITE) ||
            Files.exists(destination, LinkOption.NOFOLLOW_LINKS) ||
            destination.startsWith(realProject) ||
            realProject.startsWith(destination)
          then throw IllegalArgumentException("unsafe destination")
          (realParent, destination)
        }
        .mapError(_ =>
          ProbeError.make(
            "unsafe_probe_output_directory",
            "probe output directory is unsafe or unavailable"
          )
        )
      (realParent, destination) = paths
      staging <- ZIO
        .attemptBlocking {
          val name = s".bat-probe-staging-${UUID.randomUUID().toString}"
          val path = realParent.resolve(name)
          val _ = Files.createDirectory(
            path,
            PosixFilePermissions.asFileAttribute(DirectoryPermissions)
          )
          if path.toRealPath().getParent != realParent then
            throw IllegalStateException("staging parent changed")
          path
        }
        .mapError(_ =>
          ProbeError.make(
            "probe_artifact_staging_failed",
            "probe artifact staging could not be prepared"
          )
        )
      state <- Ref.make(false)
    yield new PreparedProbeOutput(staging, destination, state)

  private def cleanup(prepared: PreparedProbeOutput): IO[Nothing, Unit] =
    ZIO.attemptBlocking(deleteTree(prepared.staging)).ignore

  private def deleteTree(root: Path): Unit =
    if Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)(using Ordering.Int.reverse)
          .foreach(path => Files.deleteIfExists(path))
      finally stream.close()

  private def rejectSymbolicLinkComponents(path: Path): Unit =
    val absolute = path.toAbsolutePath.normalize()
    var current = absolute.getRoot
    absolute.iterator().asScala.foreach { component =>
      current = current.resolve(component)
      if Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
        Files.isSymbolicLink(current)
      then throw IllegalArgumentException("symbolic link component")
    }

  private def fail(
      condition: Boolean,
      code: String,
      message: String
  ): IO[ProbeError, Unit] =
    ZIO.fail(ProbeError.make(code, message)).unless(condition).unit
