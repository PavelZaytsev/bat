package bat.worker

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest
import java.util.Locale
import java.util.Arrays

/** Reads only the semantic portion of a standalone Git index.
  *
  * Git is allowed to rewrite the index's cached stat data while answering a
  * read-only query such as `git status`. Those bytes are deliberately absent
  * from this digest. Staged paths, object IDs, modes, merge stages, and
  * behavior-changing entry flags remain authenticated.
  *
  * The parser is intentionally narrower than Git. The trusted pre-seal
  * `write-tree` may leave one authenticated TREE cache; every other extension
  * is rejected. BAT exposes no stage-only, merge, split-index, sparse-index,
  * untracked-cache, or fsmonitor operation after sealing, so accelerator state
  * cannot silently churn underneath the workspace identity.
  */
private[worker] object GitIndexSnapshot:
  private val MaxIndexBytes = 256L * 1024 * 1024
  private val MaxConfigBytes = 1024L * 1024
  private val MaxEntries = 100000L
  private val HeaderBytes = 12
  private val Signature = "DIRC".getBytes(StandardCharsets.US_ASCII)
  private val TreeExtension = "TREE".getBytes(StandardCharsets.US_ASCII)
  private val SemanticFlagsMask = 0x8000 | 0x3000
  private val ExtendedFlag = 0x4000
  private val SemanticExtendedFlagsMask = 0x6000
  private val InvalidExtendedFlagsMask = 0x9fff
  private val NameLengthMask = 0x0fff

  def semanticDigest(gitDirectory: Path, detachedHead: String): Array[Byte] =
    val objectFormat = readObjectFormat(gitDirectory, detachedHead)
    val index = gitDirectory.resolve("index")
    if Files.isSymbolicLink(index) ||
      !Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS) ||
      Files.size(index) > MaxIndexBytes
    then throw IndexFailure("workspace index is unsafe")

    val bytes = Files.readAllBytes(index)
    if bytes.length.toLong > MaxIndexBytes ||
      bytes.length < HeaderBytes + objectFormat.objectIdBytes
    then throw IndexFailure("workspace index has an invalid size")

    val contentEnd = bytes.length - objectFormat.objectIdBytes
    verifyChecksum(bytes, contentEnd, objectFormat)
    val cursor = Cursor(bytes, contentEnd)
    if !Arrays.equals(cursor.readBytes(Signature.length), Signature) then
      throw IndexFailure("workspace index has an invalid signature")
    val version = cursor.readInt()
    if version < 2 || version > 4 then
      throw IndexFailure("workspace index has an unsupported version")
    val entryCount = cursor.readUnsignedInt()
    if entryCount > MaxEntries then
      throw IndexFailure("workspace index has too many entries")

    val digest = MessageDigest.getInstance("SHA-256")
    update(digest, "bat-semantic-git-index-v1")
    update(digest, objectFormat.wire)
    updateLong(digest, entryCount)
    var previousPath = Array.emptyByteArray
    var previousStage = -1
    var entry = 0L
    while entry < entryCount do
      val entryStart = cursor.position
      cursor.skip(24) // ctime, mtime, device, and inode stat cache
      val mode = cursor.readInt()
      requireMode(mode)
      cursor.skip(12) // uid, gid, and file-size stat cache
      val objectId = cursor.readBytes(objectFormat.objectIdBytes)
      val flags = cursor.readUnsignedShort()
      val extendedFlags =
        if (flags & ExtendedFlag) == 0 then 0
        else
          if version == 2 then
            throw IndexFailure("version 2 index has extended flags")
          val value = cursor.readUnsignedShort()
          if (value & InvalidExtendedFlagsMask) != 0 then
            throw IndexFailure("workspace index has unknown entry flags")
          value
      val path =
        if version == 4 then
          val remove = readOffsetEncoding(cursor)
          if remove > previousPath.length then
            throw IndexFailure("workspace index path compression is invalid")
          val suffix = cursor.readNulTerminated()
          previousPath.take(previousPath.length - remove) ++ suffix
        else
          val value = cursor.readNulTerminated()
          val consumed = cursor.position - entryStart
          val padding = (8 - (consumed & 7)) & 7
          cursor.readBytes(padding).foreach { byte =>
            if byte != 0 then
              throw IndexFailure("workspace index entry padding is invalid")
          }
          value

      requirePath(path)
      val declaredLength = flags & NameLengthMask
      if declaredLength != math.min(path.length, NameLengthMask) then
        throw IndexFailure("workspace index path length is invalid")
      val stage = (flags & 0x3000) >>> 12
      if entry > 0 then
        val order = compareUnsigned(previousPath, path)
        if order > 0 || (order == 0 && previousStage >= stage) then
          throw IndexFailure("workspace index entries are not sorted")

      updateInt(digest, mode)
      updateInt(digest, flags & SemanticFlagsMask)
      updateInt(digest, extendedFlags & SemanticExtendedFlagsMask)
      updateBytes(digest, objectId)
      updateBytes(digest, path)
      previousPath = path
      previousStage = stage
      entry += 1L

    var sawTreeExtension = false
    while cursor.remaining > 0 do
      if cursor.remaining < 8 then
        throw IndexFailure("workspace index extension is truncated")
      val signature = cursor.readBytes(4)
      val extensionBytes = cursor.readUnsignedInt()
      if extensionBytes > cursor.remaining.toLong then
        throw IndexFailure("workspace index extension is invalid")
      val optional = signature.head >= 'A'.toByte &&
        signature.head <= 'Z'.toByte
      if !optional then
        throw IndexFailure("workspace index uses an unsupported extension")
      if !Arrays.equals(signature, TreeExtension) || sawTreeExtension then
        throw IndexFailure("workspace index uses an unsupported extension")
      val extension = cursor.readBytes(extensionBytes.toInt)
      updateBytes(digest, signature)
      updateBytes(digest, extension)
      sawTreeExtension = true

    digest.digest()

  /** Resolve the index object width from the repository format, rather than
    * guessing solely from a mutable HEAD file. BAT-created SHA-1 repositories
    * use format 0. Git's SHA-256 repositories use format 1 plus
    * extensions.objectFormat=sha256.
    */
  private def readObjectFormat(
      gitDirectory: Path,
      detachedHead: String
  ): ObjectFormat =
    val config = gitDirectory.resolve("config")
    if Files.isSymbolicLink(gitDirectory) ||
      !Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS) ||
      Files.isSymbolicLink(config) ||
      !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) ||
      Files.size(config) > MaxConfigBytes
    then throw IndexFailure("workspace Git format is unsafe")

    val text = Files.readString(config, StandardCharsets.UTF_8)
    var section = ""
    var repositoryVersion = Option.empty[Int]
    var configuredFormat = Option.empty[String]
    text.linesIterator.zipWithIndex.foreach { (rawLine, lineNumber) =>
      val line = rawLine.trim
      if line.nonEmpty && !line.startsWith("#") && !line.startsWith(";") then
        if line.startsWith("[") then section = parseSection(line)
        else
          val (key, value) = parseAssignment(line)
          (section, key.toLowerCase(Locale.ROOT)) match
            case ("core", "repositoryformatversion") =>
              if repositoryVersion.nonEmpty then
                throw IndexFailure("workspace Git format is ambiguous")
              repositoryVersion = Some(
                parseRepositoryVersion(value, lineNumber + 1)
              )
            case ("extensions", "objectformat") =>
              if configuredFormat.nonEmpty then
                throw IndexFailure("workspace Git format is ambiguous")
              configuredFormat = Some(
                scalarValue(value).toLowerCase(Locale.ROOT)
              )
            case _ => ()
    }

    val version = repositoryVersion.getOrElse(
      throw IndexFailure("workspace Git format is missing")
    )
    val format = (version, configuredFormat) match
      case (0, None)           => ObjectFormat.Sha1
      case (1, Some("sha1"))   => ObjectFormat.Sha1
      case (1, Some("sha256")) => ObjectFormat.Sha256
      case (0, Some(_))        =>
        throw IndexFailure("format 0 repository has extensions")
      case _ =>
        throw IndexFailure("workspace Git format is unsupported")

    if detachedHead.length != format.hexObjectIdLength then
      throw IndexFailure("workspace HEAD does not match its Git format")
    format

  private def parseSection(line: String): String =
    val close = line.indexOf(']')
    if close < 2 || !commentOnly(line.substring(close + 1)) then
      throw IndexFailure("workspace Git config is malformed")
    val value = line.substring(1, close).trim
    if !value.matches("[A-Za-z][A-Za-z0-9.-]*") then
      "" // A valid subsection cannot be one of the sections inspected here.
    else value.toLowerCase(Locale.ROOT)

  private def parseAssignment(line: String): (String, String) =
    val equals = line.indexOf('=')
    val (rawKey, rawValue) =
      if equals < 0 then line -> "true"
      else line.substring(0, equals) -> line.substring(equals + 1)
    val key = rawKey.trim
    if !key.matches("[A-Za-z][A-Za-z0-9-]*") then
      throw IndexFailure("workspace Git config is malformed")
    key -> rawValue

  private def parseRepositoryVersion(value: String, lineNumber: Int): Int =
    scalarValue(value) match
      case "0" => 0
      case "1" => 1
      case _   =>
        throw IndexFailure(
          s"workspace Git format is invalid at line $lineNumber"
        )

  private def scalarValue(raw: String): String =
    val value = stripComment(raw).trim
    if value.isEmpty || value.exists(character =>
        character == '"' || character == '\\'
      )
    then throw IndexFailure("workspace Git format value is malformed")
    value

  private def stripComment(value: String): String =
    val comment =
      value.indexWhere(character => character == '#' || character == ';')
    if comment < 0 then value else value.substring(0, comment)

  private def commentOnly(value: String): Boolean =
    val trailing = value.trim
    trailing.isEmpty || trailing.startsWith("#") || trailing.startsWith(";")

  private def verifyChecksum(
      bytes: Array[Byte],
      contentEnd: Int,
      objectFormat: ObjectFormat
  ): Unit =
    val digest = MessageDigest.getInstance(objectFormat.checksumAlgorithm)
    digest.update(bytes, 0, contentEnd)
    val expected = Arrays.copyOfRange(bytes, contentEnd, bytes.length)
    if !MessageDigest.isEqual(digest.digest(), expected) then
      throw IndexFailure("workspace index checksum is invalid")

  private def requireMode(mode: Int): Unit =
    val valid = mode == 0x81a4 || // 100644 regular file
      mode == 0x81ed || // 100755 executable file
      mode == 0xa000 || // 120000 symbolic link
      mode == 0xe000 // 160000 gitlink
    if !valid then throw IndexFailure("workspace index mode is invalid")

  private def requirePath(path: Array[Byte]): Unit =
    if path.isEmpty || path.head == '/'.toByte || path.last == '/'.toByte
    then throw IndexFailure("workspace index path is invalid")
    val logicalEnd = path.length
    var componentStart = 0
    var index = 0
    while index <= logicalEnd do
      if index == logicalEnd || path(index) == '/'.toByte then
        val length = index - componentStart
        if length == 0 ||
          asciiEquals(path, componentStart, length, ".") ||
          asciiEquals(path, componentStart, length, "..") ||
          asciiEqualsIgnoreCase(path, componentStart, length, ".git")
        then throw IndexFailure("workspace index path is invalid")
        componentStart = index + 1
      index += 1

  private def asciiEquals(
      bytes: Array[Byte],
      start: Int,
      length: Int,
      value: String
  ): Boolean =
    if length != value.length then false
    else
      var index = 0
      while index < length &&
        bytes(start + index) == value.charAt(index).toByte
      do index += 1
      index == length

  private def asciiEqualsIgnoreCase(
      bytes: Array[Byte],
      start: Int,
      length: Int,
      value: String
  ): Boolean =
    if length != value.length then false
    else
      var index = 0
      while index < length &&
        asciiLower(bytes(start + index)) == value.charAt(index).toByte
      do index += 1
      index == length

  private def asciiLower(value: Byte): Byte =
    if value >= 'A'.toByte && value <= 'Z'.toByte then
      (value + ('a'.toByte - 'A'.toByte)).toByte
    else value

  private def compareUnsigned(left: Array[Byte], right: Array[Byte]): Int =
    val length = math.min(left.length, right.length)
    var index = 0
    while index < length do
      val compared = java.lang.Integer.compare(
        left(index) & 0xff,
        right(index) & 0xff
      )
      if compared != 0 then return compared
      index += 1
    java.lang.Integer.compare(left.length, right.length)

  private def readOffsetEncoding(cursor: Cursor): Int =
    var current = cursor.readUnsignedByte()
    var value = (current & 0x7f).toLong
    while (current & 0x80) != 0 do
      if value > ((Int.MaxValue.toLong - 0x7f) >>> 7) - 1L then
        throw IndexFailure("workspace index path compression is too large")
      current = cursor.readUnsignedByte()
      value = ((value + 1L) << 7) | (current & 0x7f).toLong
    value.toInt

  private def update(digest: MessageDigest, value: String): Unit =
    updateBytes(digest, value.getBytes(StandardCharsets.UTF_8))

  private def updateBytes(digest: MessageDigest, bytes: Array[Byte]): Unit =
    updateLong(digest, bytes.length.toLong)
    digest.update(bytes)

  private def updateInt(digest: MessageDigest, value: Int): Unit =
    digest.update(ByteBuffer.allocate(4).putInt(value).array())

  private def updateLong(digest: MessageDigest, value: Long): Unit =
    digest.update(ByteBuffer.allocate(8).putLong(value).array())

  private final class Cursor private (
      bytes: Array[Byte],
      limit: Int,
      private var offset: Int
  ):
    def position: Int = offset
    def remaining: Int = limit - offset

    def skip(count: Int): Unit =
      requireAvailable(count)
      offset += count

    def readUnsignedByte(): Int =
      requireAvailable(1)
      val value = bytes(offset) & 0xff
      offset += 1
      value

    def readUnsignedShort(): Int =
      (readUnsignedByte() << 8) | readUnsignedByte()

    def readInt(): Int =
      (readUnsignedByte() << 24) |
        (readUnsignedByte() << 16) |
        (readUnsignedByte() << 8) |
        readUnsignedByte()

    def readUnsignedInt(): Long =
      java.lang.Integer.toUnsignedLong(readInt())

    def readBytes(count: Int): Array[Byte] =
      requireAvailable(count)
      val result = Arrays.copyOfRange(bytes, offset, offset + count)
      offset += count
      result

    def readNulTerminated(): Array[Byte] =
      val start = offset
      while offset < limit && bytes(offset) != 0 do offset += 1
      if offset == limit then
        throw IndexFailure("workspace index path is unterminated")
      val result = Arrays.copyOfRange(bytes, start, offset)
      offset += 1
      result

    private def requireAvailable(count: Int): Unit =
      if count < 0 || count > remaining then
        throw IndexFailure("workspace index is truncated")

  private object Cursor:
    def apply(bytes: Array[Byte], limit: Int): Cursor =
      new Cursor(bytes, limit, 0)

  private enum ObjectFormat(
      val wire: String,
      val objectIdBytes: Int,
      val checksumAlgorithm: String
  ):
    case Sha1 extends ObjectFormat("sha1", 20, "SHA-1")
    case Sha256 extends ObjectFormat("sha256", 32, "SHA-256")

    def hexObjectIdLength: Int = objectIdBytes * 2

  private final case class IndexFailure(message: String)
      extends RuntimeException(message)
