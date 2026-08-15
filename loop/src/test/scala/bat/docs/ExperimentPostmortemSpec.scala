package bat.docs

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.annotation.tailrec
import scala.util.matching.Regex

import zio.*
import zio.test.*

object ExperimentPostmortemSpec extends ZIOSpecDefault:
  private val RelativeDocument =
    Path.of("docs", "experiments", "java-six-phase-120b-20260814.md")

  private final case class Attempt(
      state: String,
      chargedIterations: Int,
      modelTurns: Int,
      providerAttempts: Int,
      retries: Int,
      tools: Int,
      tokens: Long,
      activeMillis: Long,
      terminal: String
  )

  private final case class TokenCounts(
      input: Long,
      cachedInput: Long,
      output: Long,
      reasoning: Long,
      total: Long
  ):
    def +(other: TokenCounts): TokenCounts =
      TokenCounts(
        input + other.input,
        cachedInput + other.cachedInput,
        output + other.output,
        reasoning + other.reasoning,
        total + other.total
      )

  private val ExpectedAttempts = Map(
    "001" -> Attempt(
      "published failed",
      14,
      14,
      14,
      0,
      13,
      108678L,
      390354L,
      "`harmony_chat_protocol_violation` at sequences 55–57"
    ),
    "002" -> Attempt(
      "in progress",
      15,
      14,
      20,
      6,
      14,
      125560L,
      1731523L,
      "sixth retry recorded at sequence 70"
    ),
    "003" -> Attempt(
      "in progress",
      1,
      0,
      6,
      6,
      0,
      0L,
      333928L,
      "sixth retry recorded at sequence 14"
    ),
    "004" -> Attempt(
      "published failed",
      19,
      19,
      19,
      0,
      18,
      242819L,
      1289429L,
      "`harmony_chat_chat_error` at sequences 75–77"
    ),
    "005" -> Attempt(
      "published failed",
      12,
      12,
      12,
      0,
      11,
      75782L,
      276251L,
      "`harmony_chat_protocol_violation` at sequences 47–49"
    ),
    "006" -> Attempt(
      "published failed",
      26,
      26,
      26,
      0,
      25,
      337726L,
      1304585L,
      "`workspace_fingerprint_mismatch` at sequence 105"
    )
  )

  private val ExpectedTokens = Map(
    "001" -> TokenCounts(107301L, 42315L, 1377L, 790L, 108678L),
    "002" -> TokenCounts(124007L, 49140L, 1553L, 992L, 125560L),
    "003" -> TokenCounts(0L, 0L, 0L, 0L, 0L),
    "004" -> TokenCounts(230830L, 66885L, 11989L, 9961L, 242819L),
    "005" -> TokenCounts(74330L, 30030L, 1452L, 1100L, 75782L),
    "006" -> TokenCounts(325150L, 126945L, 12576L, 9883L, 337726L)
  )

  private val ExpectedTools = Map(
    "worker_search" -> 28,
    "worker_read_file" -> 28,
    "worker_workspace" -> 5,
    "worker_target_diff" -> 5,
    "bdr_audit_summary" -> 5,
    "worker_apply_patch" -> 4,
    "worker_java_build" -> 2,
    "bdr_apply" -> 4
  )

  private val SafeSha256 = Set(
    "0327d2c2b6bf101e52f9d3b870bc24344a19281c2b5ee2b41148e514ec98fa3c",
    "d51942b41690bd51f558d3285dfb44c3188ab65c53c8db984e051adfc8d18633",
    "b06161286bca6ea50bb308e120a454c5d05004681c284f6ba39b7356687be675",
    "816221f8cd30c919efc08885e3028913f81f4df68a4d59f664736619da1b835a",
    "86d380981bd8dc9ad9314c47380ebbd8bd39075d9d10c2c93bc3c1d49c0625fd",
    "b84042b2dd3ba3e015023602fdbe37eb11c90cf32f7fa3db8543f69f8a386a7e",
    "351694465722e6684240c5a447057e8d3dcce34496de22adfda0aec08e1708a3",
    "11e101f682260d4797b04c30f33bd646ae451f1a303749c976aaa323a9fb1f2c"
  )

  private val ForbiddenPatterns = Map(
    "URL" -> raw"(?i)https?://".r,
    "IPv4 address" -> raw"(?<!\d)(?:\d{1,3}\.){3}\d{1,3}(?!\d)".r,
    "host-like name" ->
      raw"(?i)\b(?:[A-Za-z0-9-]+\.)+(?:local|internal|lan)\b".r,
    "private absolute path" ->
      raw"(?i)(?<![A-Za-z0-9.])/(?:Users|home|private|tmp|var)/".r,
    "Windows absolute path" -> raw"(?i)\b[A-Za-z]:\\".r,
    "raw prompt marker" -> raw"(?i)<bat_turn_context\b".r,
    "provider reasoning field" -> raw"(?i)\breasoning_content\b".r,
    "serialized message body" -> raw"(?i)\"messages\"\s*:".r,
    "serialized tool arguments" -> raw"(?i)\"arguments\"\s*:".r,
    "serialized payload" -> raw"(?i)\"payload\"\s*:".r,
    "private receipt authority filename" -> raw"(?i)receipt[.]key".r,
    "fenced payload block" -> "```".r
  )

  private val AttemptRow =
    raw"""^\| (00[1-6]) \| ([^|]+?) \| ([\d,]+) \| ([\d,]+) \| ([\d,]+) \| ([\d,]+) \| ([\d,]+) \| ([\d,]+) \| ([\d,]+) \| ([^|]+?) \|$$""".r
  private val TokenRow =
    raw"""^\| (00[1-6]) \| ([\d,]+) \| ([\d,]+) \| ([\d,]+) \| ([\d,]+) \| ([\d,]+) \|$$""".r
  private val ToolRow = raw"""^\| `([a-z_]+)` \| ([\d,]+) \|$$""".r
  private val Hex40 = raw"(?i)(?<![0-9a-f])[0-9a-f]{40}(?![0-9a-f])".r
  private val Hex64 = raw"(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])".r

  private val RequiredStatements = Set(
    "There are 85 `model_turn` events: 82 valid tool-calling turns and three backend-failed turns.",
    "Telemetry records 97 provider attempts in all: 82 completed, 15 failed, and 12 retry events.",
    "The lineage counter is 6,758,945 ms",
    "The 1,432,875 ms difference is the five restart/operator gaps",
    "There is no sequence-105 `tool_execution` event.",
    "input context grew from 4,011 tokens on the first turn to 27,814 on the last",
    "**2.692915000**",
    "973,949 ms (16m 13.949s) to the first accepted patch",
    "1,212,207 ms (20m 12.207s) to the authenticated red build",
    "The workspace manifest version advances from v1 to v2"
  )

  def spec: Spec[TestEnvironment, Any] =
    suite("sanitized issue #33 experiment postmortem")(
      test("admits only reviewed publication-safe identifiers") {
        readDocument.map { text =>
          val violations = ForbiddenPatterns.collect {
            case (label, pattern) if pattern.findFirstIn(text).nonEmpty => label
          }
          val hashes = Hex64.findAllIn(text).toSet
          val hasControlCharacters = text.exists(character =>
            character < ' ' &&
              character != '\n' &&
              character != '\r' &&
              character != '\t'
          )

          assertTrue(
            violations.isEmpty,
            Hex40.findFirstIn(text).isEmpty,
            hashes == SafeSha256,
            !hasControlCharacters
          )
        }
      },
      test("reconciles attempt, token, tool, and event totals") {
        for
          text <- readDocument
          attemptSection <- section(text, "issue-33-attempts")
          tokenSection <- section(text, "issue-33-tokens")
          toolSection <- section(text, "issue-33-tools")
        yield
          val attemptRows = parseAttempts(attemptSection)
          val tokenRows = parseTokens(tokenSection)
          val toolRows = parseTools(toolSection)
          val attempts = attemptRows.toMap
          val tokens = tokenRows.toMap
          val tools = toolRows.toMap
          val tokenTotals =
            tokens.values.foldLeft(TokenCounts(0L, 0L, 0L, 0L, 0L))(_ + _)
          val normalized = text.split(raw"\s+").mkString(" ")

          assertTrue(
            attemptRows.size == ExpectedAttempts.size,
            attempts == ExpectedAttempts,
            attempts.values.map(_.chargedIterations).sum == 87,
            attempts.values.map(_.modelTurns).sum == 85,
            attempts.values.map(_.providerAttempts).sum == 97,
            attempts.values.map(_.retries).sum == 12,
            attempts.values.map(_.tools).sum == 81,
            attempts.values.map(_.tokens).sum == 890565L,
            attempts.values.map(_.activeMillis).sum == 5326070L,
            tokenRows.size == ExpectedTokens.size,
            tokens == ExpectedTokens,
            tokenTotals ==
              TokenCounts(861618L, 315315L, 28947L, 22726L, 890565L),
            tokens.forall { case (attempt, counts) =>
              attempts.get(attempt).exists(_.tokens == counts.total) &&
              counts.input + counts.output == counts.total &&
              counts.cachedInput <= counts.input &&
              counts.reasoning <= counts.output
            },
            toolRows.size == ExpectedTools.size,
            tools == ExpectedTools,
            tools.values.sum == 81,
            RequiredStatements.forall(normalized.contains)
          )
      }
    ) @@ TestAspect.timeout(10.seconds)

  private def parseAttempts(section: String): Vector[(String, Attempt)] =
    section.linesIterator.collect {
      case AttemptRow(
            attempt,
            state,
            charged,
            modelTurns,
            providerAttempts,
            retries,
            tools,
            tokens,
            activeMillis,
            terminal
          ) =>
        attempt -> Attempt(
          state,
          integer(charged).toInt,
          integer(modelTurns).toInt,
          integer(providerAttempts).toInt,
          integer(retries).toInt,
          integer(tools).toInt,
          integer(tokens),
          integer(activeMillis),
          terminal
        )
    }.toVector

  private def parseTokens(section: String): Vector[(String, TokenCounts)] =
    section.linesIterator.collect {
      case TokenRow(
            attempt,
            input,
            cachedInput,
            output,
            reasoning,
            total
          ) =>
        attempt -> TokenCounts(
          integer(input),
          integer(cachedInput),
          integer(output),
          integer(reasoning),
          integer(total)
        )
    }.toVector

  private def parseTools(section: String): Vector[(String, Int)] =
    section.linesIterator.collect { case ToolRow(tool, executions) =>
      tool -> integer(executions).toInt
    }.toVector

  private def integer(value: String): Long =
    value.filterNot(_ == ',').toLong

  private def section(text: String, name: String): Task[String] =
    val start = s"<!-- $name:start -->"
    val end = s"<!-- $name:end -->"
    val startAt = text.indexOf(start)
    val endAt = text.indexOf(end)
    ZIO
      .fromEither(
        Either.cond(
          startAt >= 0 &&
            startAt == text.lastIndexOf(start) &&
            endAt > startAt &&
            endAt == text.lastIndexOf(end),
          text.substring(startAt + start.length, endAt),
          s"expected exactly one ordered $name marker pair"
        )
      )
      .mapError(new IllegalArgumentException(_))

  private def readDocument: Task[String] =
    ZIO.attemptBlocking(
      Files.readString(documentPath, StandardCharsets.UTF_8)
    )

  private def documentPath: Path =
    @tailrec
    def find(directory: Path): Path =
      val candidate = directory.resolve(RelativeDocument)
      if Files.isRegularFile(candidate) then candidate
      else
        val parent = directory.getParent
        if parent == null then
          throw new IllegalStateException(
            s"experiment postmortem not found: $RelativeDocument"
          )
        find(parent)

    find(Path.of("").toAbsolutePath.normalize)
