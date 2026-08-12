package bat.probe

import bat.protocol.*
import bat.telemetry.*
import bat.trace.*

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest
import java.util.HexFormat

import scala.jdk.CollectionConverters.*
import scala.util.Try

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

final case class CommittedProbeValidationFailure(
    code: String,
    location: String,
    safeMessage: String
):
  override def toString: String =
    s"CommittedProbeValidationFailure(code=$code, location=$location, payload=<redacted>)"

final case class CommittedProbeValidationSummary(
    runs: Int,
    runIds: Set[String]
)

/** Validates the immutable deployment records committed under
  * `benchmarks/probes`.
  *
  * This is intentionally independent from the live artifact writer. A record is
  * decoded from disk, reconstructed through the same domain validators that
  * produced it, and required to regenerate byte-for-byte. The directory index
  * is the inventory authority, so an extra directory, file, or repeated run ID
  * fails closed.
  */
object CommittedProbeValidator:
  val IndexSchema = "bat.dev/gpt-oss-probe-index"
  val IndexVersion = 1

  private val IndexFile = "index.json"
  private val RootReadme = "README.md"
  private val RunFiles = Set(
    "README.md",
    "result.json",
    "safe-trace.json",
    "telemetry.json"
  )
  private val DirectoryName = "^[a-z0-9][a-z0-9._-]{0,127}$".r
  private val Digest = "^[0-9a-f]{64}$".r
  private val MachineCode = "^[a-z][a-z0-9_-]{0,63}$".r
  private val MaxJsonBytes = 8L * 1024L * 1024L
  private val MaxReadmeBytes = 256L * 1024L
  private val MaxJsonDepth = 48
  private val MaxJsonNodes = 200000
  private val MaxJsonStringCharacters = 8192
  private val MaxJsonKeyCharacters = 128
  private val MaxTraceEvents = 512

  private val ForbiddenKeys = Set(
    "api_key",
    "authorization",
    "call_id",
    "content",
    "credential",
    "developer_text",
    "endpoint",
    "hostname",
    "prompt",
    "prompt_text",
    "provider_body",
    "raw_reasoning",
    "reasoning_content",
    "request_body",
    "response_body",
    "token",
    "user_text"
  )
  private val RedactedPayloadKeys = Set(
    "arguments",
    "message",
    "output",
    "payload"
  )
  private val CredentialFragments = List(
    "bearer ",
    "github_pat_",
    "ghp_",
    "xoxb-",
    "xoxp-",
    "akia"
  )
  private val Hostname =
    "^(?:localhost|(?:[a-z0-9-]+\\.)+[a-z]{2,63})(?::[0-9]{1,5})?(?:/.*)?$".r
  private val Ipv4 =
    "^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?::[0-9]{1,5})?(?:/.*)?$".r

  def validate(
      root: Path
  ): Either[CommittedProbeValidationFailure, CommittedProbeValidationSummary] =
    guard("invalid_probe_inventory", "benchmarks/probes") {
      val normalized = Option(root)
        .map(_.toAbsolutePath.normalize)
        .getOrElse(throw Invalid("probe root is required"))
      requireSafeTreeRoot(normalized)
      val rootEntries = list(normalized)
      val expectedRootFiles = Set(RootReadme, IndexFile)
      val files = rootEntries.filter(isRegular).map(fileName).toSet
      val directories = rootEntries.filter(isDirectory).map(fileName).toSet
      if files != expectedRootFiles ||
        rootEntries.size != files.size + directories.size
      then throw Invalid("probe root inventory is not exact")
      val _ = readUtf8(normalized.resolve(RootReadme), MaxReadmeBytes)
      val index = decodeIndex(
        readJson(normalized.resolve(IndexFile), "index.json")
      )
      val indexedDirectories = index.map(_.directory).toSet
      if directories != indexedDirectories then
        throw Invalid("probe index and directory inventory disagree")
      index.foreach(entry =>
        if !DirectoryName.matches(entry.directory) then
          throw Invalid("probe directory name is invalid")
      )
      val results = index.map(entry => validateRun(normalized, entry))
      val ids = results.map(_.runId)
      if ids.distinct.size != ids.size then
        throw Failure(
          "duplicate_probe_run_id",
          "index.json",
          "probe run IDs must be unique"
        )
      CommittedProbeValidationSummary(results.size, ids.toSet)
    }

  private final case class IndexEntry(directory: String, runId: String)
  private final case class ValidatedRun(runId: String)

  private def decodeIndex(value: Json.Obj): Vector[IndexEntry] =
    if requiredString(value, "schema") != IndexSchema ||
      requiredLong(value, "version") != IndexVersion.toLong
    then throw Invalid("probe index schema or version is invalid")
    val entries = requiredArray(value, "runs").map { child =>
      val item = requiredObject(child, "probe index entry")
      IndexEntry(
        requiredString(item, "directory"),
        requiredString(item, "run_id")
      )
    }.toVector
    if entries.isEmpty then throw Invalid("probe index must not be empty")
    if entries.map(_.directory).distinct.size != entries.size ||
      entries.map(_.runId).distinct.size != entries.size
    then throw Invalid("probe index contains duplicate entries")
    if entries.map(_.directory) != entries.map(_.directory).sorted then
      throw Invalid("probe index entries must be sorted by directory")
    entries.foreach(entry =>
      TelemetryRunId
        .from(entry.runId)
        .fold(
          _ => throw Invalid("probe index run ID is invalid"),
          _ => ()
        )
    )
    val expected = Json.Obj(
      Chunk(
        "schema" -> Json.Str(IndexSchema),
        "version" -> Json.Num(BigDecimal(IndexVersion)),
        "runs" -> Json.Arr(Chunk.fromIterable(entries.map { entry =>
          Json.Obj(
            Chunk(
              "directory" -> Json.Str(entry.directory),
              "run_id" -> Json.Str(entry.runId)
            )
          )
        }))
      )
    )
    requireCanonical(value, expected, "index.json")
    entries

  private def validateRun(root: Path, entry: IndexEntry): ValidatedRun =
    val location = entry.directory
    guardRun(location) {
      val directory = root.resolve(entry.directory)
      if !isDirectory(directory) || Files.isSymbolicLink(directory) then
        throw Invalid("probe run directory is unsafe")
      val children = list(directory)
      val names = children.map(fileName).toSet
      if names != RunFiles || children.exists(path => !isRegular(path)) then
        throw Invalid("probe run inventory is not exact")
      val _ = readUtf8(directory.resolve("README.md"), MaxReadmeBytes)

      val resultText = readUtf8(directory.resolve("result.json"), MaxJsonBytes)
      val traceText = readUtf8(
        directory.resolve("safe-trace.json"),
        MaxJsonBytes
      )
      val telemetryText = readUtf8(
        directory.resolve("telemetry.json"),
        MaxJsonBytes
      )
      val resultJson = parseObject(resultText, "result.json")
      val traceJson = parse(traceText, "safe-trace.json")
      val telemetryJson = parseObject(telemetryText, "telemetry.json")
      scanJson(resultJson, "result.json")
      scanJson(traceJson, "safe-trace.json")
      scanJson(telemetryJson, "telemetry.json")

      val trace = decodeTrace(traceJson, traceText)
      val telemetry = PersistedTelemetry
        .decode(telemetryJson)
        .fold(
          _ =>
            throw Failure(
              "invalid_probe_telemetry",
              location,
              "probe telemetry failed domain validation"
            ),
          identity
        )
      val canonicalTelemetry = telemetry.canonicalJson.fold(
        _ => throw Invalid("probe telemetry could not be regenerated"),
        identity
      )
      if telemetryText != canonicalTelemetry then
        throw Failure(
          "noncanonical_probe_telemetry",
          location,
          "telemetry.json is not the exact canonical document"
        )

      validateStandaloneBindings(resultJson, traceJson, telemetryJson)
      validateDigests(resultJson, traceText, telemetryText)
      val verdict = decodeVerdict(resultJson)
      val reason = decodeReason(resultJson)
      val commit = ProbeBatCommit
        .from(requiredString(resultJson, "bat_commit"))
        .fold(_ => throw Invalid("probe BAT commit is invalid"), identity)
      val artifact = ProbeResultArtifact
        .make(
          verdict,
          reason,
          commit,
          telemetry.deployment,
          trace,
          telemetry
        )
        .fold(
          _ =>
            throw Failure(
              "invalid_probe_result_semantics",
              location,
              "probe verdict contradicts its terminal documents"
            ),
          identity
        )
      if resultText != artifact.canonicalJson then
        throw Failure(
          "noncanonical_probe_result",
          location,
          "result.json does not regenerate from its bound documents"
        )

      val runId = requiredString(resultJson, "run_id")
      if runId != entry.runId || runId != telemetry.runId.value then
        throw Failure(
          "probe_run_id_mismatch",
          location,
          "probe run IDs are inconsistent"
        )
      validateTraceTelemetry(trace, telemetry, verdict, commit.value, location)
      ValidatedRun(runId)
    }

  private def decodeTrace(
      value: Json,
      text: String
  ): Option[SafeTraceDocument] = value match
    case Json.Null =>
      if text != "null" then throw Invalid("null safe trace is not canonical")
      None
    case _: Json.Obj =>
      val document = text
        .fromJson[SafeTraceDocument]
        .fold(
          _ => throw Invalid("safe trace does not match its typed schema"),
          identity
        )
      if document.schema != "bat.dev/conformance-trace" ||
        document.version != 2 || document.toJson != text
      then throw Invalid("safe trace schema, version, or encoding is invalid")
      validateTrace(document)
      Some(document)
    case _ => throw Invalid("safe trace must be an object or null")

  private def validateTrace(document: SafeTraceDocument): Unit =
    val events = document.events
    if events.isEmpty || events.size > MaxTraceEvents then
      throw Invalid("safe trace event count is invalid")
    val starts = events.collect { case value: SafeTraceEvent.RunStart => value }
    val completes = events.collect { case value: SafeTraceEvent.RunComplete =>
      value
    }
    if starts.size != 1 || events.head != starts.head || completes.size > 1 ||
      completes.headOption.exists(_ != events.last)
    then throw Invalid("safe trace envelope is invalid")
    val developers = events.count(
      _.isInstanceOf[SafeTraceEvent.DeveloperInput]
    )
    val users = events.count(_.isInstanceOf[SafeTraceEvent.UserInput])
    if developers != 1 || users != 1 then
      throw Invalid("safe trace input inventory is invalid")
    events.foreach {
      case SafeTraceEvent.DeveloperInput(characters) if characters < 0 =>
        throw Invalid("safe trace developer length is invalid")
      case SafeTraceEvent.UserInput(characters) if characters < 0 =>
        throw Invalid("safe trace user length is invalid")
      case SafeTraceEvent.FinalOutput(characters) if characters < 0 =>
        throw Invalid("safe trace final-output length is invalid")
      case value: SafeTraceEvent.Usage =>
        val valid = Usage.make(
          value.totalTokens,
          value.inputTokens,
          value.cachedInputTokens,
          value.outputTokens,
          value.reasoningTokens
        )
        if valid.isLeft then throw Invalid("safe trace usage is invalid")
      case value: SafeTraceEvent.RunStart =>
        val mode = RunMode.values.exists(_.wire == value.mode)
        val capabilities = Capability.values.map(_.wire).toSet
        val required = value.capabilities.required
        val enabled = value.capabilities.enabled
        val budgets = BudgetLimits.make(
          value.budgets.maxIterations,
          value.budgets.maxToolCalls,
          zio.Duration.fromMillis(value.budgets.maxWallMillis),
          value.budgets.maxTotalTokens
        )
        if !mode || required.distinct.size != required.size ||
          enabled.distinct.size != enabled.size ||
          !required.toSet.subsetOf(enabled.toSet) ||
          !(required.toSet ++ enabled.toSet).subsetOf(capabilities) ||
          budgets.isLeft
        then throw Invalid("safe trace run start is invalid")
        validateTraceBdr(value.bdr)
      case value: SafeTraceEvent.RunComplete =>
        if !RunOutcome.values.exists(_.wire == value.outcome) ||
          value.iterations < 0 || value.toolCalls < 0 ||
          value.totalTokens < 0
        then throw Invalid("safe trace completion is invalid")
        validateTraceBdr(value.bdr)
      case value: SafeTraceEvent.ProviderError =>
        if !MachineCode.matches(value.code) then
          throw Invalid("safe trace provider code is invalid")
      case value: SafeTraceEvent.FunctionCall =>
        if !value.name.matches("^[A-Za-z][A-Za-z0-9_.:-]{0,127}$") then
          throw Invalid("safe trace tool name is invalid")
      case value: SafeTraceEvent.ReasoningContext =>
        if !ContinuationMode.values.exists(_.wire == value.mode) then
          throw Invalid("safe trace continuation mode is invalid")
      case _ => ()
    }
    val iterations = events.collect { case SafeTraceEvent.Iteration(number) =>
      number
    }
    if iterations != Chunk.fromIterable(1 to iterations.size) then
      throw Invalid("safe trace iterations are not contiguous")
    val calls = events.collect { case value: SafeTraceEvent.FunctionCall =>
      value
    }
    val outputs = events.collect { case value: SafeTraceEvent.FunctionOutput =>
      value
    }
    if calls.map(_.callId).distinct.size != calls.size ||
      outputs.map(_.callId).distinct.size != outputs.size ||
      outputs.exists(output => !calls.exists(_.callId == output.callId))
    then throw Invalid("safe trace call correlation is invalid")
    completes.headOption.foreach { completed =>
      val usages = events.collect { case value: SafeTraceEvent.Usage => value }
      if completed.iterations != iterations.size ||
        completed.toolCalls != calls.size ||
        completed.totalTokens != usages.map(_.totalTokens).sum
      then throw Invalid("safe trace completion totals do not reconcile")
    }

  private def validateTraceBdr(value: SafeBdrCheckpoint): Unit =
    if value.revision < 0 || !MachineCode.matches(value.runState) ||
      !Digest.matches(value.stateDigest)
    then throw Invalid("safe trace BDR checkpoint is invalid")

  private def validateStandaloneBindings(
      result: Json.Obj,
      trace: Json,
      telemetry: Json.Obj
  ): Unit =
    if requiredField(result, "safe_trace") != trace ||
      requiredField(result, "telemetry") != telemetry ||
      requiredField(result, "deployment") != requiredField(
        telemetry,
        "deployment"
      )
    then
      throw Failure(
        "probe_document_mismatch",
        "result.json",
        "embedded and standalone probe documents disagree"
      )

  private def validateDigests(
      result: Json.Obj,
      traceText: String,
      telemetryText: String
  ): Unit =
    val expectedTrace = requiredString(result, "safe_trace_sha256")
    val expectedTelemetry = requiredString(result, "telemetry_sha256")
    if !Digest.matches(expectedTrace) || !Digest.matches(expectedTelemetry) ||
      expectedTrace != sha256(traceText) ||
      expectedTelemetry != sha256(telemetryText)
    then
      throw Failure(
        "probe_digest_mismatch",
        "result.json",
        "probe document digest does not match exact standalone bytes"
      )

  private def validateTraceTelemetry(
      trace: Option[SafeTraceDocument],
      telemetry: TelemetryDocument,
      verdict: ProbeVerdict,
      batCommit: String,
      location: String
  ): Unit =
    val terminal = telemetry.records.last.event
    verdict match
      case ProbeVerdict.Compatible =>
        val document = trace.getOrElse(
          throw Invalid("compatible probe requires a safe trace")
        )
        val start = document.events.head.asInstanceOf[SafeTraceEvent.RunStart]
        val complete = document.events.last match
          case value: SafeTraceEvent.RunComplete => value
          case _ => throw Invalid("compatible trace must complete")
        val calls = document.events.collect {
          case value: SafeTraceEvent.FunctionCall => value.name
        }
        if calls != Chunk("bdr_audit_summary", "bdr_apply") ||
          complete.outcome != "ready_for_review" ||
          complete.iterations != 3 || complete.toolCalls != 2 ||
          complete.bdr.runState != "ready_for_review"
        then
          throw Failure(
            "invalid_compatible_probe_trace",
            location,
            "compatible probe does not contain the fixed golden scenario"
          )
        terminal match
          case value: TelemetryEvent.RunCompleted =>
            if value.outcome.wire != complete.outcome ||
              value.iterations != complete.iterations ||
              value.toolCalls != complete.toolCalls ||
              value.totalTokens != complete.totalTokens ||
              value.finalBdr.revision != complete.bdr.revision ||
              value.finalBdr.runState != complete.bdr.runState ||
              value.finalBdr.stateDigest != complete.bdr.stateDigest
            then throw Invalid("trace and telemetry terminals disagree")
          case _ => throw Invalid("compatible telemetry must complete")
        validateStartBinding(start, telemetry, batCommit)
      case ProbeVerdict.Incompatible | ProbeVerdict.Blocked =>
        if terminal.isInstanceOf[TelemetryEvent.RunCompleted] then
          throw Invalid("failed probe verdict cannot have completed telemetry")
        trace.foreach(document =>
          validateStartBinding(
            document.events.head.asInstanceOf[SafeTraceEvent.RunStart],
            telemetry,
            batCommit
          )
        )
      case ProbeVerdict.Nonconformant =>
        trace.foreach(document =>
          validateStartBinding(
            document.events.head.asInstanceOf[SafeTraceEvent.RunStart],
            telemetry,
            batCommit
          )
        )

  private def validateStartBinding(
      start: SafeTraceEvent.RunStart,
      telemetry: TelemetryDocument,
      batCommit: String
  ): Unit =
    val deployment = telemetry.deployment
    val pins = start.pins
    if pins.backend != deployment.identity.backend ||
      pins.modelId != deployment.identity.modelId ||
      pins.modelRevision != deployment.identity.modelRevision ||
      pins.bdrCommit != batCommit
    then throw Invalid("trace pins do not match result deployment")
    val runPins = RunPins
      .make(
        pins.backend,
        pins.modelId,
        pins.modelRevision,
        pins.reasoningEffort,
        pins.promptVersion,
        pins.bdrCommit
      )
      .fold(_ => throw Invalid("trace run pins are invalid"), identity)
    val expected = TelemetryRunPins.capture(runPins)
    val actualStart = telemetry.records.head.event match
      case value: TelemetryEvent.RunStarted => value
      case _ => throw Invalid("telemetry run start is missing")
    if actualStart.pins.identityDigest.value != expected.identityDigest.value ||
      actualStart.pins.reasoningEffortDigest.value !=
        expected.reasoningEffortDigest.value ||
        actualStart.pins.promptVersionDigest.value !=
        expected.promptVersionDigest.value ||
        actualStart.pins.bdrCommit != pins.bdrCommit ||
        actualStart.mode.wire != start.mode ||
        actualStart.budgets.maxIterations != start.budgets.maxIterations ||
        actualStart.budgets.maxToolCalls != start.budgets.maxToolCalls ||
        actualStart.budgets.maxWallTime.toMillis !=
        start.budgets.maxWallMillis ||
        actualStart.budgets.maxTotalTokens != start.budgets.maxTotalTokens
    then throw Invalid("trace and telemetry run-start pins disagree")

  private def decodeVerdict(value: Json.Obj): ProbeVerdict =
    val wire = requiredString(value, "verdict")
    ProbeVerdict.values
      .find(_.wire == wire)
      .getOrElse(throw Invalid("probe verdict is invalid"))

  private def decodeReason(
      value: Json.Obj
  ): Option[ProbeReasonCode] = requiredField(value, "reason_code") match
    case Json.Null      => None
    case Json.Str(text) =>
      Some(
        ProbeReasonCode
          .from(text)
          .fold(_ => throw Invalid("probe reason code is invalid"), identity)
      )
    case _ => throw Invalid("probe reason code must be a string or null")

  private def readJson(path: Path, location: String): Json.Obj =
    parseObject(readUtf8(path, MaxJsonBytes), location)

  private def parseObject(text: String, location: String): Json.Obj =
    parse(text, location) match
      case value: Json.Obj => value
      case _               => throw Invalid(s"$location must be an object")

  private def parse(text: String, location: String): Json =
    StrictJson
      .parse(text, location)
      .fold(_ => throw Invalid(s"$location is not strict JSON"), identity)

  private def requireCanonical(
      supplied: Json,
      expected: Json,
      location: String
  ): Unit =
    val suppliedText = StrictJson
      .canonical(supplied, location)
      .fold(_ => throw Invalid(s"$location is invalid"), identity)
    val expectedText = StrictJson
      .canonical(expected, location)
      .fold(_ => throw Invalid(s"$location schema is invalid"), identity)
    if suppliedText != expectedText then
      throw Invalid(s"$location does not match its exact schema")

  private def scanJson(value: Json, location: String): Unit =
    var nodes = 0
    def visit(child: Json, depth: Int, path: String): Unit =
      nodes += 1
      if nodes > MaxJsonNodes || depth > MaxJsonDepth then
        throw Failure(
          "probe_json_bound_exceeded",
          location,
          "probe JSON exceeds structural bounds"
        )
      child match
        case Json.Obj(fields) =>
          fields.foreach { case (name, nested) =>
            if name.length > MaxJsonKeyCharacters ||
              ForbiddenKeys.contains(name.toLowerCase)
            then unsafe(location)
            if RedactedPayloadKeys.contains(name) then
              nested match
                case Json.Str(text) if text != "<redacted>" => unsafe(location)
                case _                                      => ()
            visit(nested, depth + 1, s"$path.$name")
          }
        case Json.Arr(values) =>
          values.zipWithIndex.foreach { case (nested, index) =>
            visit(nested, depth + 1, s"$path[$index]")
          }
        case Json.Str(text) => validatePersistedText(text, location)
        case _              => ()
    visit(value, 0, "$")

  private def validatePersistedText(text: String, location: String): Unit =
    val lower = Option(text).fold("")(_.toLowerCase)
    val windowsAbsolute = "^[a-zA-Z]:[\\\\/].*".r
    val absolute =
      text == null || text.length > MaxJsonStringCharacters ||
        text.exists(_.isControl) || text.startsWith("/") ||
        text.startsWith("~/") || text.startsWith("\\\\") ||
        windowsAbsolute.matches(text) || lower.contains("://") ||
        lower.startsWith("file:") ||
        (Hostname.matches(lower) && !lower.startsWith("bat.dev/")) ||
        Ipv4.matches(lower) ||
        CredentialFragments.exists(lower.contains)
    if absolute then unsafe(location)

  private def unsafe(location: String): Nothing =
    throw Failure(
      "unsafe_probe_artifact_value",
      location,
      "probe artifact contains a forbidden path, payload, or secret shape"
    )

  private def requireSafeTreeRoot(root: Path): Unit =
    if Files.isSymbolicLink(root) ||
      !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
    then throw Invalid("probe root is unsafe")
    val stream = Files.walk(root)
    try
      stream.iterator().asScala.foreach { path =>
        if Files.isSymbolicLink(path) ||
          !(isDirectory(path) || isRegular(path))
        then
          throw Failure(
            "unsafe_probe_inventory_entry",
            "benchmarks/probes",
            "probe inventory contains a symbolic link or special file"
          )
      }
    finally stream.close()

  private def readUtf8(path: Path, maxBytes: Long): String =
    if Files.isSymbolicLink(path) ||
      !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    then throw Invalid("probe artifact is not a regular file")
    val size = Files.size(path)
    if size <= 0L || size > maxBytes then
      throw Invalid("probe artifact size is invalid")
    val bytes = Files.readAllBytes(path)
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    Try(decoder.decode(ByteBuffer.wrap(bytes)).toString).getOrElse(
      throw Invalid("probe artifact is not valid UTF-8")
    )

  private def list(path: Path): Vector[Path] =
    val stream = Files.list(path)
    try stream.iterator().asScala.toVector
    finally stream.close()

  private def isRegular(path: Path): Boolean =
    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

  private def isDirectory(path: Path): Boolean =
    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

  private def fileName(path: Path): String = path.getFileName.toString

  private def requiredField(value: Json.Obj, name: String): Json =
    value.fields
      .collectFirst { case (`name`, child) => child }
      .getOrElse(
        throw Invalid(s"required field $name is missing")
      )

  private def requiredObject(value: Json, label: String): Json.Obj =
    value match
      case result: Json.Obj => result
      case _                => throw Invalid(s"$label must be an object")

  private def requiredString(value: Json.Obj, name: String): String =
    requiredField(value, name) match
      case Json.Str(result) => result
      case _                => throw Invalid(s"field $name must be a string")

  private def requiredLong(value: Json.Obj, name: String): Long =
    requiredField(value, name) match
      case number: Json.Num =>
        Try(number.value.longValueExact()).getOrElse(
          throw Invalid(s"field $name must be an integer")
        )
      case _ => throw Invalid(s"field $name must be an integer")

  private def requiredArray(value: Json.Obj, name: String): Chunk[Json] =
    requiredField(value, name) match
      case Json.Arr(result) => result
      case _                => throw Invalid(s"field $name must be an array")

  private def sha256(value: String): String =
    HexFormat
      .of()
      .formatHex(
        MessageDigest
          .getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8))
      )

  private def guard[A](
      code: String,
      location: String
  )(effect: => A): Either[CommittedProbeValidationFailure, A] =
    try Right(effect)
    catch
      case value: Failure => Left(value.failure)
      case _: Invalid     =>
        Left(
          CommittedProbeValidationFailure(
            code,
            location,
            "committed probe evidence is invalid"
          )
        )
      case _: Exception =>
        Left(
          CommittedProbeValidationFailure(
            code,
            location,
            "committed probe evidence could not be inspected safely"
          )
        )

  private def guardRun[A](location: String)(effect: => A): A =
    try effect
    catch
      case value: Failure => throw value
      case _: Invalid     =>
        throw Failure(
          "invalid_committed_probe",
          location,
          "committed probe run is invalid"
        )

  private final case class Invalid(message: String) extends RuntimeException

  private final case class Failure(
      code: String,
      location: String,
      message: String
  ) extends RuntimeException:
    val failure = CommittedProbeValidationFailure(code, location, message)

object CommittedProbeValidatorApp:
  def main(arguments: Array[String]): Unit =
    val root = arguments.toList match
      case Nil          => Path.of("benchmarks", "probes")
      case value :: Nil => Path.of(value)
      case _            =>
        System.err.println(
          "usage: CommittedProbeValidatorApp [benchmarks/probes]"
        )
        sys.exit(64)
    CommittedProbeValidator.validate(root) match
      case Right(summary) =>
        println(s"validated ${summary.runs} committed probe run(s)")
      case Left(failure) =>
        System.err.println(
          s"${failure.code}: ${failure.location}: ${failure.safeMessage}"
        )
        sys.exit(1)
