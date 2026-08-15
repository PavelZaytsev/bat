package bat.runner

import bat.protocol.{BatError, BudgetKind, BudgetLimits, StrictJson}
import bat.telemetry.*
import bat.worker.AttemptId

import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
import java.nio.file.{
  Files,
  LinkOption,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*
import scala.util.Try

import zio.json.ast.Json
import zio.{Chunk, Clock, Duration, IO, Ref, UIO, ZIO}

/** Cumulative controller accounting across fresh model attempts in one logical
  * Java run. These are controller budgets, not provider-attempt counters:
  * retries never double-count a completed logical model turn.
  */
private[runner] final case class LiveJavaUsage private (
    iterations: Int,
    toolCalls: Int,
    totalTokens: Long,
    wallMillis: Long
):
  def add(current: LiveJavaUsage): Either[BatError, LiveJavaUsage] =
    LiveJavaUsage.make(
      iterations.toLong + current.iterations.toLong,
      toolCalls.toLong + current.toolCalls.toLong,
      addExact(totalTokens, current.totalTokens),
      addExact(wallMillis, current.wallMillis)
    )

  def remaining(original: BudgetLimits): Either[BatError, BudgetLimits] =
    val remainingIterations = original.maxIterations.toLong - iterations
    val remainingTools = original.maxToolCalls.toLong - toolCalls
    val remainingTokens = original.maxTotalTokens - totalTokens
    val remainingWall = original.maxWallTime.toMillis - wallMillis
    if remainingIterations <= 0 then
      Left(BatError.BudgetExceeded(BudgetKind.Iterations))
    else if remainingTools <= 0 then
      Left(BatError.BudgetExceeded(BudgetKind.ToolCalls))
    else if remainingTokens <= 0 then
      Left(BatError.BudgetExceeded(BudgetKind.TotalTokens))
    else if remainingWall <= 0 then
      Left(BatError.BudgetExceeded(BudgetKind.WallTime))
    else
      BudgetLimits.make(
        remainingIterations.toInt,
        remainingTools.toInt,
        Duration.fromMillis(remainingWall),
        remainingTokens
      )

  private def addExact(left: Long, right: Long): Long =
    try Math.addExact(left, right)
    catch case _: ArithmeticException => Long.MaxValue

private[runner] object LiveJavaUsage:
  val Zero: LiveJavaUsage = LiveJavaUsage(0, 0, 0L, 0L)

  def make(
      iterations: Long,
      toolCalls: Long,
      totalTokens: Long,
      wallMillis: Long
  ): Either[BatError, LiveJavaUsage] =
    if iterations < 0 || iterations > Int.MaxValue ||
      toolCalls < 0 || toolCalls > Int.MaxValue ||
      totalTokens < 0 || wallMillis < 0
    then invalid("live attempt usage is invalid")
    else
      Right(
        LiveJavaUsage(
          iterations.toInt,
          toolCalls.toInt,
          totalTokens,
          wallMillis
        )
      )

  def from(records: Chunk[TelemetryRecord], wallMillis: Long): LiveJavaUsage =
    val iterations = records.foldLeft(0) { (maximum, record) =>
      Math.max(maximum, attribution(record.event).fold(0)(_.iteration))
    }
    val tools = records.count {
      case TelemetryRecord(
            _,
            TelemetryEvent.ToolExecution(
              _,
              _,
              _,
              outcome,
              _,
              _
            )
          ) =>
        outcome != ToolExecutionOutcome.Replayed
      case _ => false
    }
    val tokens = records.foldLeft(0L) {
      case (total, TelemetryRecord(_, event: TelemetryEvent.ModelTurn)) =>
        event.tokens.total match
          case Measurement.Observed(value) => safeAdd(total, value)
          case Measurement.Unavailable(_)  => total
      case (total, _) => total
    }
    val terminalWall = records.foldLeft(0L) {
      case (maximum, TelemetryRecord(_, value: TelemetryEvent.RunCompleted)) =>
        Math.max(maximum, value.wallMillis)
      case (maximum, TelemetryRecord(_, value: TelemetryEvent.RunFailed)) =>
        Math.max(maximum, value.wallMillis)
      case (maximum, _) => maximum
    }
    LiveJavaUsage(
      iterations,
      tools,
      tokens,
      Math.max(Math.max(0L, wallMillis), terminalWall)
    )

  def latestBdr(records: Chunk[TelemetryRecord]): Option[BdrAttribution] =
    records.reverseIterator
      .flatMap(record => attribution(record.event))
      .nextOption()

  private def attribution(event: TelemetryEvent): Option[BdrAttribution] =
    event match
      case TelemetryEvent.BdrCheckpoint(value)   => Some(value)
      case value: TelemetryEvent.ModelTurn       => Some(value.attribution)
      case value: TelemetryEvent.ProviderAttempt => Some(value.attribution)
      case value: TelemetryEvent.Retry           => Some(value.attribution)
      case value: TelemetryEvent.ToolExecution   =>
        value.after match
          case Measurement.Observed(after) => Some(after)
          case Measurement.Unavailable(_)  => Some(value.before)
      case value: TelemetryEvent.RunCompleted => Some(value.finalBdr)
      case _                                  => None

  private def safeAdd(left: Long, right: Long): Long =
    try Math.addExact(left, right)
    catch case _: ArithmeticException => Long.MaxValue

  private def invalid(message: String): Left[BatError, Nothing] =
    Left(BatError.ProtocolViolation(message))

/** Private in-progress directory for one controller attempt. It receives a
  * canonical checkpoint after every telemetry event (therefore immediately
  * after every completed tool execution) and becomes visible under its final
  * attempt name only after all final documents have been written.
  */
private[runner] final class LiveJavaAttemptStore private (
    val attemptId: AttemptId,
    val priorUsage: LiveJavaUsage,
    private val runId: TelemetryRunId,
    private val parentAttempt: Option[AttemptId],
    private val bindingSha256: String,
    private val attemptStartedEpochMillis: Long,
    private val staging: Path,
    private val destination: Path,
    private val startedNanos: Long,
    private val published: Ref[Boolean]
):
  override def toString: String =
    "LiveJavaAttemptStore(path=<redacted>, payload=<redacted>)"

  def remaining(original: BudgetLimits): Either[BatError, BudgetLimits] =
    priorUsage.remaining(original)

  def telemetry: UIO[InMemoryTelemetry] =
    InMemoryTelemetry.makeObserved(records =>
      observe(records).orDieWith(_ => LiveJavaCheckpointDefect())
    )

  def publish(
      decision: String,
      records: Chunk[TelemetryRecord],
      documents: Chunk[(String, String)],
      forbiddenValues: Chunk[String]
  ): IO[BatError, Unit] =
    publishWithBeforeCommit(
      decision,
      records,
      documents,
      forbiddenValues,
      ZIO.unit
    )

  /** Test seam at the publication commit point. Production always supplies a
    * successful hook; deterministic tests use it to model a process loss after
    * every final byte is durable but before the directory rename commits the
    * attempt.
    */
  private[runner] def publishWithBeforeCommit(
      decision: String,
      records: Chunk[TelemetryRecord],
      documents: Chunk[(String, String)],
      forbiddenValues: Chunk[String],
      beforeCommit: IO[BatError, Unit]
  ): IO[BatError, Unit] =
    for
      _ <- fail(
        LiveJavaAttemptStore.TerminalDecisions.contains(decision),
        "live attempt decision is invalid"
      )
      _ <- fail(
        records.nonEmpty && documents.nonEmpty &&
          documents.map(_._1).toSet.size == documents.size,
        "live attempt publication is invalid"
      )
      _ <- fail(
        documents.forall { case (name, _) =>
          LiveJavaAttemptStore.FileName.matches(name)
        },
        "live attempt document name is invalid"
      )
      parsedDocuments <- ZIO.foreach(documents) { case (_, contents) =>
        ZIO.fromEither(StrictJson.parse(contents, "live attempt document"))
      }
      payload = documents.map(_._2).mkString("\n")
      forbidden = forbiddenValues.filter(value =>
        value != null && value.nonEmpty
      )
      encodedForbidden <- ZIO.foreach(forbidden)(value =>
        ZIO.fromEither(
          StrictJson
            .canonical(Json.Str(value), "live attempt forbidden value")
            .map(encoded => encoded.substring(1, encoded.length - 1))
        )
      )
      _ <- fail(
        !forbidden.exists(payload.contains) &&
          !encodedForbidden.exists(payload.contains) &&
          !parsedDocuments.exists(
            LiveJavaAttemptStore.containsForbidden(_, forbidden)
          ),
        "live attempt publication failed the redaction check"
      )
      documentDigests = documents.map { case (name, contents) =>
        name -> LiveJavaAttemptStore.sha256Utf8(contents)
      }
      elapsed <- elapsedMillis
      current = LiveJavaUsage.from(records, elapsed)
      cumulative <- ZIO.fromEither(priorUsage.add(current))
      checkpoint <- ZIO.fromEither(
        LiveJavaAttemptStore.checkpointJson(
          runId,
          attemptId,
          parentAttempt,
          bindingSha256,
          attemptStartedEpochMillis,
          decision,
          records,
          current,
          cumulative,
          LiveJavaUsage.latestBdr(records),
          documentDigests
        )
      )
      already <- published.get
      _ <- fail(!already, "live attempt was already published")
      _ <- ZIO
        .attemptBlocking {
          documents.foreach { case (name, contents) =>
            LiveJavaAttemptStore.writePrivate(staging.resolve(name), contents)
          }
          LiveJavaAttemptStore.replaceCheckpoint(staging, checkpoint)
        }
        .mapError(_ => publicationFailed)
      _ <- beforeCommit
      _ <- (ZIO
        .attemptBlocking {
          val _ = Files.move(staging, destination)
          LiveJavaAttemptStore.syncDirectory(destination.getParent)
        }
        .mapError(_ => publicationFailed) *> published.set(
        true
      )).uninterruptible
    yield ()

  private def observe(records: Chunk[TelemetryRecord]): IO[BatError, Unit] =
    for
      elapsed <- elapsedMillis
      current = LiveJavaUsage.from(records, elapsed)
      cumulative <- ZIO.fromEither(priorUsage.add(current))
      checkpoint <- ZIO.fromEither(
        LiveJavaAttemptStore.checkpointJson(
          runId,
          attemptId,
          parentAttempt,
          bindingSha256,
          attemptStartedEpochMillis,
          "running",
          records,
          current,
          cumulative,
          LiveJavaUsage.latestBdr(records)
        )
      )
      _ <- ZIO
        .attemptBlocking(
          LiveJavaAttemptStore.replaceCheckpoint(staging, checkpoint)
        )
        .mapError(_ => publicationFailed)
    yield ()

  private def elapsedMillis: UIO[Long] =
    Clock.nanoTime.map(now =>
      Duration.fromNanos(Math.max(0L, now - startedNanos)).toMillis
    )

  private def publicationFailed: BatError =
    BatError.ProtocolViolation("live attempt persistence failed")

  private def fail(condition: Boolean, message: String): IO[BatError, Unit] =
    ZIO.fail(BatError.ProtocolViolation(message)).unless(condition).unit

private final case class LiveJavaCheckpointDefect()
    extends RuntimeException("live attempt checkpoint failed")

private[runner] object LiveJavaAttemptStore:
  private final case class PreparedAttempt(
      priorUsage: LiveJavaUsage,
      staging: Path,
      destination: Path
  )

  private final case class PublicationCheckpoint(
      status: String,
      documentsSha256: Map[String, String]
  )

  private val LineageMonitor = new Object
  private val DirectoryPermissions =
    PosixFilePermissions.fromString("rwx------")
  private val FilePermissions = PosixFilePermissions.fromString("rw-------")
  private val FileName = "^[a-z][a-z0-9-]{0,63}\\.json$".r
  private val TerminalDecisions = Set("ready", "rejected", "terminal", "failed")
  private val ResumableStates = Set("running", "failed")
  private val Schema = "bat.dev/live-java-checkpoint"
  private val Version = 2L
  private val LineageSchema = "bat.dev/live-java-lineage"
  private val LineageVersion = 1L
  private val LineageLock = ".live-java-lineage.lock"

  def prepare(
      outputRoot: Path,
      projectRoot: Path,
      runId: TelemetryRunId,
      attemptId: AttemptId,
      bindingSha256: String,
      previousAttempt: Option[AttemptId]
  ): IO[BatError, LiveJavaAttemptStore] =
    for
      _ <- fail(
        outputRoot != null && projectRoot != null &&
          runId.asInstanceOf[AnyRef] != null &&
          attemptId.asInstanceOf[AnyRef] != null &&
          Option(bindingSha256).exists(_.matches("[0-9a-f]{64}")),
        "live attempt output boundary is invalid"
      )
      root <- secureRoot(outputRoot, projectRoot)
      _ <- fail(
        previousAttempt.forall(_.value != attemptId.value),
        "live attempt identity must advance on resume"
      )
      started <- Clock.nanoTime
      startedEpoch <- Clock.currentTime(TimeUnit.MILLISECONDS)
      prepared <- prepareSerialized(
        root,
        runId,
        attemptId,
        bindingSha256,
        previousAttempt,
        startedEpoch
      )
      published <- Ref.make(false)
    yield new LiveJavaAttemptStore(
      attemptId,
      prepared.priorUsage,
      runId,
      previousAttempt,
      bindingSha256,
      startedEpoch,
      prepared.staging,
      prepared.destination,
      started,
      published
    )

  private def secureRoot(
      outputRoot: Path,
      projectRoot: Path
  ): IO[BatError, Path] =
    ZIO
      .attemptBlocking {
        val root = outputRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val project = projectRoot.toRealPath()
        if !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
          Files.isSymbolicLink(root) || root.startsWith(project) ||
          project.startsWith(root) || !Files.isWritable(root)
        then throw IllegalArgumentException("unsafe root")
        val permissions = Files.getPosixFilePermissions(root)
        if permissions.contains(PosixFilePermission.GROUP_WRITE) ||
          permissions.contains(PosixFilePermission.OTHERS_WRITE) ||
          Files.getOwner(root) != Files.getOwner(project)
        then throw IllegalArgumentException("unsafe permissions")
        root
      }
      .mapError(_ =>
        BatError.ProtocolViolation("live attempt output root is unsafe")
      )

  private def prepareSerialized(
      root: Path,
      runId: TelemetryRunId,
      attemptId: AttemptId,
      bindingSha256: String,
      previousAttempt: Option[AttemptId],
      startedEpochMillis: Long
  ): IO[BatError, PreparedAttempt] =
    ZIO
      .attemptBlocking {
        LineageMonitor.synchronized {
          withLineageLock(root) {
            prepareUnderLock(
              root,
              runId,
              attemptId,
              bindingSha256,
              previousAttempt,
              startedEpochMillis
            )
          }
        }
      }
      .mapError(_ =>
        BatError.ProtocolViolation("live attempt persistence failed")
      )
      .flatMap(ZIO.fromEither)

  private def prepareUnderLock(
      root: Path,
      runId: TelemetryRunId,
      attemptId: AttemptId,
      bindingSha256: String,
      previousAttempt: Option[AttemptId],
      startedEpochMillis: Long
  ): Either[BatError, PreparedAttempt] =
    val staging = root.resolve(s".${attemptId.value}.in-progress")
    val destination = root.resolve(attemptId.value)
    for
      _ <- require(
        startedEpochMillis >= 0,
        "live attempt start time is invalid"
      )
      tip <- loadLineage(root, runId, bindingSha256)
      _ <- require(
        tip == previousAttempt,
        "previous live attempt is not the logical run tip"
      )
      _ <- tip match
        case Some(value) =>
          recoverCommittedPublication(root, runId, value, bindingSha256)
        case None => Right(())
      prior <- previousAttempt match
        case None        => Right(LiveJavaUsage.Zero)
        case Some(value) =>
          loadPrior(
            root,
            runId,
            value,
            bindingSha256,
            startedEpochMillis
          )
      checkpoint <- checkpointJson(
        runId,
        attemptId,
        previousAttempt,
        bindingSha256,
        startedEpochMillis,
        "running",
        Chunk.empty,
        LiveJavaUsage.Zero,
        prior,
        None
      )
      lineage <- lineageJson(runId, bindingSha256, attemptId)
      _ <- fileStep("live attempt output already exists") {
        if Files.exists(staging, LinkOption.NOFOLLOW_LINKS) ||
          Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
        then throw IllegalStateException("attempt exists")
        val _ = Files.createDirectory(
          staging,
          PosixFilePermissions.asFileAttribute(DirectoryPermissions)
        )
        syncDirectory(root)
      }
      _ <- fileStep("live attempt initial checkpoint failed") {
        replaceCheckpoint(staging, checkpoint)
      }
      _ <- fileStep("live attempt lineage update failed") {
        replaceLineage(root, runId, lineage)
      }
    yield PreparedAttempt(prior, staging, destination)

  /** Finish an attempt whose complete terminal bundle reached durable staging
    * before the process died at the final rename. This recovery never repairs
    * or guesses a partial bundle: a running checkpoint is left resumable, and a
    * terminal checkpoint is published only when its exact closed file set is
    * present as ordinary files.
    */
  private def recoverCommittedPublication(
      root: Path,
      runId: TelemetryRunId,
      attemptId: AttemptId,
      bindingSha256: String
  ): Either[BatError, Unit] =
    val staging = root.resolve(s".${attemptId.value}.in-progress")
    val destination = root.resolve(attemptId.value)
    val stagingExists = Files.exists(staging, LinkOption.NOFOLLOW_LINKS)
    val destinationExists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
    if stagingExists && destinationExists then
      invalid("previous live attempt publication collided with output")
    else if !stagingExists || destinationExists then Right(())
    else
      fileStep("previous live attempt publication is unavailable") {
        val checkpoint = staging.resolve("checkpoint.json")
        if Files.isSymbolicLink(staging) ||
          !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS) ||
          Files.isSymbolicLink(checkpoint) ||
          !Files.isRegularFile(checkpoint, LinkOption.NOFOLLOW_LINKS)
        then throw IllegalStateException("unsafe staged publication")
        Files.readString(checkpoint, StandardCharsets.UTF_8)
      }.flatMap(text =>
        publicationStatus(text, runId, attemptId, bindingSha256).flatMap {
          case publication if TerminalDecisions.contains(publication.status) =>
            val expected =
              if publication.status == "failed" then
                Set("checkpoint.json", "result.json", "telemetry.json")
              else
                Set(
                  "checkpoint.json",
                  "result.json",
                  "evidence.json",
                  "telemetry.json"
                )
            fileStep("previous live attempt publication is incomplete") {
              val stream = Files.list(staging)
              val entries =
                try stream.toArray.map(_.asInstanceOf[Path]).toVector
                finally stream.close()
              if entries.map(_.getFileName.toString).toSet != expected ||
                entries.exists(path =>
                  Files.isSymbolicLink(path) ||
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                )
                || publication.documentsSha256.keySet !=
                  (expected - "checkpoint.json")
                  || publication.documentsSha256.exists { case (name, digest) =>
                    sha256File(staging.resolve(name)) != digest
                  }
              then throw IllegalStateException("incomplete staged publication")
              syncDirectory(staging)
              val _ = Files.move(staging, destination)
              syncDirectory(destination.getParent)
            }
          case _ => Right(())
        }
      )

  private def publicationStatus(
      text: String,
      runId: TelemetryRunId,
      attemptId: AttemptId,
      bindingSha256: String
  ): Either[BatError, PublicationCheckpoint] =
    for
      value <- StrictJson.parseObject(text.trim, "live attempt checkpoint")
      canonical <- StrictJson.canonical(value, "live attempt checkpoint")
      _ <- require(
        canonical == text.trim,
        "previous checkpoint is not canonical"
      )
      _ <- literal(value, "schema", Schema)
      _ <- number(value, "version").flatMap(result =>
        require(result == Version, "previous checkpoint version is invalid")
      )
      _ <- literal(value, "run_id", runId.value)
      _ <- literal(value, "attempt_id", attemptId.value)
      _ <- literal(value, "binding_sha256", bindingSha256)
      status <- string(value, "status")
      _ <- require(
        status == "running" || TerminalDecisions.contains(status),
        "previous checkpoint status is invalid"
      )
      digests <- objectField(value, "publication_sha256").flatMap(
        decodePublicationDigests
      )
      _ <- require(
        status != "running" || digests.isEmpty,
        "running checkpoint publication is invalid"
      )
    yield PublicationCheckpoint(status, digests)

  private def withLineageLock[A](root: Path)(effect: => A): A =
    val lockPath = root.resolve(LineageLock)
    val channel = FileChannel.open(
      lockPath,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      LinkOption.NOFOLLOW_LINKS
    )
    try
      val _ = Files.setPosixFilePermissions(lockPath, FilePermissions)
      val lock = channel.lock()
      try effect
      finally lock.release()
    finally channel.close()

  private def loadLineage(
      root: Path,
      runId: TelemetryRunId,
      bindingSha256: String
  ): Either[BatError, Option[AttemptId]] =
    val path = lineagePath(root, runId)
    if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then Right(None)
    else
      fileStep("live attempt lineage is unavailable") {
        if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
          Files.isSymbolicLink(path)
        then throw IllegalStateException("unsafe lineage")
        Files.readString(path, StandardCharsets.UTF_8)
      }.flatMap(text => decodeLineage(text, runId, bindingSha256).map(Some(_)))

  private def loadPrior(
      root: Path,
      runId: TelemetryRunId,
      attemptId: AttemptId,
      bindingSha256: String,
      resumedEpochMillis: Long
  ): Either[BatError, LiveJavaUsage] =
    val published = root.resolve(s"${attemptId.value}/checkpoint.json")
    val interrupted =
      root.resolve(s".${attemptId.value}.in-progress/checkpoint.json")
    fileStep("previous live attempt is unavailable") {
      val candidates = Vector(published, interrupted).filter(path =>
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
      )
      if candidates.size != 1 then
        throw IllegalStateException("prior checkpoint unavailable")
      Files.readString(candidates.head, StandardCharsets.UTF_8)
    }.flatMap(text =>
      decodePrior(
        text,
        runId,
        attemptId,
        bindingSha256,
        resumedEpochMillis
      )
    )

  private def decodePrior(
      text: String,
      runId: TelemetryRunId,
      attemptId: AttemptId,
      bindingSha256: String,
      resumedEpochMillis: Long
  ): Either[BatError, LiveJavaUsage] =
    for
      value <- StrictJson.parseObject(text.trim, "live attempt checkpoint")
      canonical <- StrictJson.canonical(value, "live attempt checkpoint")
      _ <- require(
        canonical == text.trim,
        "previous checkpoint is not canonical"
      )
      _ <- literal(value, "schema", Schema)
      _ <- number(value, "version").flatMap(result =>
        require(result == Version, "previous checkpoint version is invalid")
      )
      _ <- literal(value, "run_id", runId.value)
      _ <- literal(value, "attempt_id", attemptId.value)
      _ <- literal(value, "binding_sha256", bindingSha256)
      parent <- parentAttempt(value)
      _ <- require(
        parent.forall(_.value != attemptId.value),
        "previous checkpoint parent is invalid"
      )
      startedEpochMillis <- number(value, "attempt_started_epoch_millis")
      status <- string(value, "status")
      _ <- require(
        ResumableStates.contains(status),
        "previous live attempt is not resumable"
      )
      current <- objectField(value, "current_usage").flatMap(decodeUsage)
      cumulative <- objectField(value, "cumulative_usage").flatMap(
        decodeUsage
      )
      usage <- chargeResumeWall(
        current,
        cumulative,
        startedEpochMillis,
        resumedEpochMillis
      )
    yield usage

  private def checkpointJson(
      runId: TelemetryRunId,
      attemptId: AttemptId,
      parentAttempt: Option[AttemptId],
      bindingSha256: String,
      attemptStartedEpochMillis: Long,
      status: String,
      records: Chunk[TelemetryRecord],
      current: LiveJavaUsage,
      cumulative: LiveJavaUsage,
      bdr: Option[BdrAttribution],
      documentDigests: Chunk[(String, String)] = Chunk.empty
  ): Either[BatError, String] =
    StrictJson.canonical(
      Json.Obj(
        Chunk(
          "schema" -> Json.Str(Schema),
          "version" -> Json.Num(Version),
          "run_id" -> Json.Str(runId.value),
          "attempt_id" -> Json.Str(attemptId.value),
          "parent_attempt_id" -> parentAttempt.fold[Json](Json.Null)(value =>
            Json.Str(value.value)
          ),
          "binding_sha256" -> Json.Str(bindingSha256),
          "attempt_started_epoch_millis" -> Json.Num(
            attemptStartedEpochMillis
          ),
          "status" -> Json.Str(status),
          "telemetry_record_count" -> Json.Num(records.size.toLong),
          "telemetry_records" -> Json.Arr(records.map(TelemetryRecord.json)),
          "current_usage" -> usageJson(current),
          "cumulative_usage" -> usageJson(cumulative),
          "bdr" -> bdr.fold[Json](Json.Null)(bdrJson),
          "publication_sha256" -> Json.Obj(
            documentDigests
              .sortBy(_._1)
              .map { case (name, digest) => name -> Json.Str(digest) }
          )
        )
      ),
      "live attempt checkpoint"
    )

  private def lineageJson(
      runId: TelemetryRunId,
      bindingSha256: String,
      tip: AttemptId
  ): Either[BatError, String] =
    StrictJson.canonical(
      Json.Obj(
        Chunk(
          "schema" -> Json.Str(LineageSchema),
          "version" -> Json.Num(LineageVersion),
          "run_id" -> Json.Str(runId.value),
          "binding_sha256" -> Json.Str(bindingSha256),
          "tip_attempt_id" -> Json.Str(tip.value)
        )
      ),
      "live attempt lineage"
    )

  private def decodeLineage(
      text: String,
      runId: TelemetryRunId,
      bindingSha256: String
  ): Either[BatError, AttemptId] =
    for
      value <- StrictJson.parseObject(text.trim, "live attempt lineage")
      canonical <- StrictJson.canonical(value, "live attempt lineage")
      _ <- require(
        canonical == text.trim,
        "live attempt lineage is not canonical"
      )
      _ <- literal(value, "schema", LineageSchema)
      _ <- number(value, "version").flatMap(result =>
        require(
          result == LineageVersion,
          "live attempt lineage version is invalid"
        )
      )
      _ <- literal(value, "run_id", runId.value)
      _ <- literal(value, "binding_sha256", bindingSha256)
      rawTip <- string(value, "tip_attempt_id")
      tip <- AttemptId
        .from(rawTip)
        .left
        .map(_ =>
          BatError.ProtocolViolation("live attempt lineage tip is invalid")
        )
    yield tip

  private def chargeResumeWall(
      current: LiveJavaUsage,
      cumulative: LiveJavaUsage,
      startedEpochMillis: Long,
      resumedEpochMillis: Long
  ): Either[BatError, LiveJavaUsage] =
    val usageIsMonotonic =
      cumulative.iterations >= current.iterations &&
        cumulative.toolCalls >= current.toolCalls &&
        cumulative.totalTokens >= current.totalTokens &&
        cumulative.wallMillis >= current.wallMillis
    if startedEpochMillis < 0 || resumedEpochMillis < 0 || !usageIsMonotonic
    then invalid("previous checkpoint usage is invalid")
    else
      val elapsedEpochMillis =
        if resumedEpochMillis >= startedEpochMillis then
          resumedEpochMillis - startedEpochMillis
        else 0L
      val priorWall = cumulative.wallMillis - current.wallMillis
      val chargedCurrentWall = Math.max(current.wallMillis, elapsedEpochMillis)
      LiveJavaUsage.make(
        cumulative.iterations.toLong,
        cumulative.toolCalls.toLong,
        cumulative.totalTokens,
        safeAdd(priorWall, chargedCurrentWall)
      )

  private def usageJson(value: LiveJavaUsage): Json.Obj =
    Json.Obj(
      Chunk(
        "iterations" -> Json.Num(value.iterations.toLong),
        "tool_calls" -> Json.Num(value.toolCalls.toLong),
        "total_tokens" -> Json.Num(value.totalTokens),
        "wall_millis" -> Json.Num(value.wallMillis)
      )
    )

  private def bdrJson(value: BdrAttribution): Json.Obj =
    Json.Obj(
      Chunk(
        "iteration" -> Json.Num(value.iteration.toLong),
        "revision" -> Json.Num(value.revision),
        "run_state" -> Json.Str(value.runState),
        "state_digest" -> Json.Str(value.stateDigest)
      )
    )

  private def replaceCheckpoint(staging: Path, contents: String): Unit =
    val checkpoint = staging.resolve("checkpoint.json")
    val temporary = staging.resolve(".checkpoint.tmp")
    val _ = Files.deleteIfExists(temporary)
    writePrivate(temporary, contents)
    val _ = Files.move(
      temporary,
      checkpoint,
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING
    )
    syncDirectory(staging)

  private def replaceLineage(
      root: Path,
      runId: TelemetryRunId,
      contents: String
  ): Unit =
    val lineage = lineagePath(root, runId)
    val temporary = root.resolve(s".${runId.value}.lineage.tmp")
    val _ = Files.deleteIfExists(temporary)
    writePrivate(temporary, contents)
    val _ = Files.move(
      temporary,
      lineage,
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING
    )
    syncDirectory(root)

  private def lineagePath(root: Path, runId: TelemetryRunId): Path =
    root.resolve(s".${runId.value}.lineage.json")

  private def writePrivate(path: Path, contents: String): Unit =
    val _ = Files.writeString(
      path,
      contents,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE,
      StandardOpenOption.SYNC
    )
    val _ = Files.setPosixFilePermissions(path, FilePermissions)
    syncFile(path)

  private def syncFile(path: Path): Unit =
    val channel = FileChannel.open(path, StandardOpenOption.WRITE)
    try channel.force(true)
    finally channel.close()

  private def syncDirectory(path: Path): Unit =
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    try channel.force(true)
    finally channel.close()

  private def sha256Utf8(contents: String): String =
    sha256(contents.getBytes(StandardCharsets.UTF_8))

  private def sha256File(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path, StandardOpenOption.READ)
    try
      val buffer = Array.ofDim[Byte](8192)
      var read = input.read(buffer)
      while read >= 0 do
        if read > 0 then digest.update(buffer, 0, read)
        read = input.read(buffer)
    finally input.close()
    hex(digest.digest())

  private def sha256(bytes: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def hex(bytes: Array[Byte]): String =
    bytes.iterator.map(byte => f"${byte & 0xff}%02x").mkString

  private def containsForbidden(
      value: Json,
      forbidden: Chunk[String]
  ): Boolean =
    value match
      case Json.Str(text)   => forbidden.exists(text.contains)
      case Json.Obj(fields) =>
        fields.exists { case (name, child) =>
          forbidden.exists(name.contains) || containsForbidden(child, forbidden)
        }
      case Json.Arr(elements) =>
        elements.exists(containsForbidden(_, forbidden))
      case _ => false

  private def decodePublicationDigests(
      value: Json.Obj
  ): Either[BatError, Map[String, String]] =
    value.fields.foldLeft[Either[BatError, Map[String, String]]](
      Right(Map.empty)
    ) {
      case (result, (name, Json.Str(digest))) =>
        result.flatMap { values =>
          require(
            FileName.matches(name) && digest.matches("[0-9a-f]{64}"),
            "previous publication digest is invalid"
          ).map(_ => values.updated(name, digest))
        }
      case _ =>
        invalid("previous publication digest is invalid")
    }

  private def decodeUsage(value: Json.Obj): Either[BatError, LiveJavaUsage] =
    for
      iterations <- number(value, "iterations")
      tools <- number(value, "tool_calls")
      tokens <- number(value, "total_tokens")
      wall <- number(value, "wall_millis")
      usage <- LiveJavaUsage.make(iterations, tools, tokens, wall)
    yield usage

  private def parentAttempt(
      value: Json.Obj
  ): Either[BatError, Option[AttemptId]] =
    field(value, "parent_attempt_id") match
      case Some(Json.Null)     => Right(None)
      case Some(Json.Str(raw)) =>
        AttemptId
          .from(raw)
          .left
          .map(_ =>
            BatError.ProtocolViolation(
              "live checkpoint parent_attempt_id is invalid"
            )
          )
          .map(Some(_))
      case _ => invalid("live checkpoint parent_attempt_id is invalid")

  private def objectField(
      value: Json.Obj,
      name: String
  ): Either[BatError, Json.Obj] =
    field(value, name) match
      case Some(result: Json.Obj) => Right(result)
      case _ => invalid(s"live checkpoint $name is invalid")

  private def string(value: Json.Obj, name: String): Either[BatError, String] =
    field(value, name) match
      case Some(Json.Str(result)) => Right(result)
      case _ => invalid(s"live checkpoint $name is invalid")

  private def literal(
      value: Json.Obj,
      name: String,
      expected: String
  ): Either[BatError, Unit] =
    string(value, name).flatMap(result =>
      require(result == expected, s"live checkpoint $name does not match")
    )

  private def number(value: Json.Obj, name: String): Either[BatError, Long] =
    field(value, name) match
      case Some(number: Json.Num) =>
        Try(number.value.longValueExact()).toEither.left.map(_ =>
          BatError.ProtocolViolation(s"live checkpoint $name is invalid")
        )
      case _ => invalid(s"live checkpoint $name is invalid")

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def safeAdd(left: Long, right: Long): Long =
    try Math.addExact(left, right)
    catch case _: ArithmeticException => Long.MaxValue

  private def fileStep[A](
      message: String
  )(effect: => A): Either[BatError, A] =
    Try(effect).toEither.left.map(_ => BatError.ProtocolViolation(message))

  private def require(
      condition: Boolean,
      message: String
  ): Either[BatError, Unit] =
    Either.cond(condition, (), BatError.ProtocolViolation(message))

  private def invalid(message: String): Left[BatError, Nothing] =
    Left(BatError.ProtocolViolation(message))

  private def fail(condition: Boolean, message: String): IO[BatError, Unit] =
    ZIO.fail(BatError.ProtocolViolation(message)).unless(condition).unit
