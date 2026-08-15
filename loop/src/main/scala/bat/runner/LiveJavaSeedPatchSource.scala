package bat.runner

import bat.protocol.BatError
import bat.worker.{Sha256Digest, TrustedSeedPatch}

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}

import scala.jdk.CollectionConverters.*

import zio.{IO, ZIO}

/** Private host-side location and operator digest for an optional recovery
  * patch. Neither value crosses the actor tool boundary.
  */
private[runner] final class LiveJavaSeedPatchSource private (
    private[runner] val path: Path,
    val sha256: Sha256Digest
):
  override def toString: String =
    s"LiveJavaSeedPatchSource(sha256=${sha256.value}, path=<redacted>)"

  def load(
      privateRoot: Path,
      outputDirectory: Path
  ): IO[BatError, TrustedSeedPatch] =
    ZIO
      .attemptBlocking(readPrivate(privateRoot, outputDirectory))
      .mapError(_ =>
        BatError.ProtocolViolation("live Java seed patch source is unsafe")
      )
      .flatMap(bytes =>
        ZIO
          .fromEither(TrustedSeedPatch.fromBytes(bytes, sha256.value))
          .mapError(error =>
            BatError.ProtocolViolation(
              s"live Java seed patch is invalid (${error.code})"
            )
          )
      )

  private def readPrivate(
      privateRoot: Path,
      outputDirectory: Path
  ): Array[Byte] =
    val root = privateRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
    val output = outputDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS)
    val candidate = path.toAbsolutePath.normalize
    if !candidate.startsWith(root) || candidate.startsWith(output) then
      throw IllegalArgumentException("seed boundary")
    requireNoSymlinkComponents(root, candidate)
    if Files.isSymbolicLink(candidate) ||
      !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
    then throw IllegalArgumentException("seed type")
    if Files.getOwner(candidate, LinkOption.NOFOLLOW_LINKS) !=
        Files.getOwner(root, LinkOption.NOFOLLOW_LINKS)
    then throw IllegalArgumentException("seed owner")
    if !privatePermissions(candidate) then
      throw IllegalArgumentException("seed permissions")

    val channel = FileChannel.open(
      candidate,
      StandardOpenOption.READ,
      LinkOption.NOFOLLOW_LINKS
    )
    try
      val size = channel.size()
      if size <= 0L || size > TrustedSeedPatch.MaxBytes.toLong then
        throw IllegalArgumentException("seed size")
      val buffer = ByteBuffer.allocate(size.toInt)
      while buffer.hasRemaining do
        if channel.read(buffer) < 0 then
          throw IllegalArgumentException("seed changed while reading")
      val trailing = ByteBuffer.allocate(1)
      if channel.read(trailing) >= 0 then
        throw IllegalArgumentException("seed changed while reading")
      buffer.array()
    finally channel.close()

  private def requireNoSymlinkComponents(root: Path, candidate: Path): Unit =
    var current = root
    root.relativize(candidate).iterator().asScala.foreach { part =>
      current = current.resolve(part)
      if Files.isSymbolicLink(current) then
        throw IllegalArgumentException("seed symlink")
    }

  private def privatePermissions(path: Path): Boolean =
    val forbidden = Set(
      PosixFilePermission.GROUP_READ,
      PosixFilePermission.GROUP_WRITE,
      PosixFilePermission.GROUP_EXECUTE,
      PosixFilePermission.OTHERS_READ,
      PosixFilePermission.OTHERS_WRITE,
      PosixFilePermission.OTHERS_EXECUTE
    )
    Files
      .getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
      .asScala
      .intersect(forbidden)
      .isEmpty

private[runner] object LiveJavaSeedPatchSource:
  def make(
      path: Path,
      sha256: String
  ): Either[BatError, LiveJavaSeedPatchSource] =
    for
      candidate <- Option(path)
        .filter(_.isAbsolute)
        .map(_.normalize)
        .toRight(
          BatError.ProtocolViolation("invalid live seed patch path")
        )
      digest <- Sha256Digest
        .from(sha256, "seed_patch_sha256")
        .left
        .map(_ => BatError.ProtocolViolation("invalid live seed patch SHA-256"))
    yield new LiveJavaSeedPatchSource(candidate, digest)
