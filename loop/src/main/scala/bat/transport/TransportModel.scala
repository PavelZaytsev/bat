package bat.transport

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

import scala.util.control.NonFatal

import zio.{Chunk, Duration}
import zio.stream.ZStream

/** Stable failures at the HTTP boundary. No constructor accepts provider data,
  * throwable messages, request bodies, or credentials.
  */
sealed abstract class TransportError(
    val code: String,
    val safeMessage: String,
    val retryable: Boolean
) extends Serializable:
  final override def toString: String =
    s"TransportError(code=$code, safeMessage=$safeMessage, retryable=$retryable)"

object TransportError:
  case object InvalidEndpoint
      extends TransportError(
        "invalid_endpoint",
        "HTTP endpoint configuration is invalid",
        false
      )

  case object InvalidTimeout
      extends TransportError(
        "invalid_timeout",
        "HTTP transport timeouts must be finite and positive",
        false
      )

  case object InvalidSizeLimit
      extends TransportError(
        "invalid_size_limit",
        "HTTP transport size limits must be positive",
        false
      )

  case object InvalidTarget
      extends TransportError(
        "invalid_target",
        "HTTP request target is invalid",
        false
      )

  case object InvalidHeader
      extends TransportError(
        "invalid_header",
        "HTTP request header is invalid",
        false
      )

  case object InvalidSecret
      extends TransportError(
        "invalid_secret",
        "HTTP credential is invalid",
        false
      )

  case object InvalidRequestBody
      extends TransportError(
        "invalid_request_body",
        "HTTP request body is invalid",
        false
      )

  case object RequestBodyTooLarge
      extends TransportError(
        "request_body_too_large",
        "HTTP request body exceeds the configured limit",
        false
      )

  case object InvalidRequest
      extends TransportError(
        "invalid_request",
        "HTTP request is invalid",
        false
      )

  case object OpenFailed
      extends TransportError(
        "open_failed",
        "HTTP request failed before a response was opened",
        false
      )

  case object OpenTimedOut
      extends TransportError(
        "open_timed_out",
        "HTTP request timed out before a response was opened",
        false
      )

  case object InvalidResponse
      extends TransportError(
        "invalid_response",
        "HTTP response metadata is invalid",
        false
      )

  case object BodyFailed
      extends TransportError(
        "body_failed",
        "HTTP response body stream failed",
        false
      )

  case object BodyTimedOut
      extends TransportError(
        "body_timed_out",
        "HTTP response body stream timed out",
        false
      )

enum HttpMethod:
  case Get, Head, Post, Put, Patch, Delete

/** A credential wrapper whose product representation is always redacted. */
final class Secret private (private[transport] val reveal: String):
  override def toString: String = "Secret(<redacted>)"

object Secret:
  private val MaxSecretCharacters = 16 * 1024

  def from(value: String): Either[TransportError, Secret] =
    if value == null || value.isEmpty || value.length > MaxSecretCharacters ||
      containsUnsafeCharacters(value)
    then Left(TransportError.InvalidSecret)
    else Right(new Secret(value))

  private def containsUnsafeCharacters(value: String): Boolean =
    value.exists(character =>
      character == '\u0000' || character == '\r' || character == '\n' ||
        character.isControl
    )

/** A base endpoint and transport-owned operational bounds. The endpoint is
  * deliberately absent from `toString`: paths in private deployments can be
  * sensitive even when query strings and user-info are forbidden.
  */
final class TransportConfig private (
    private[transport] val baseUri: URI,
    val openTimeout: Duration,
    val bodyIdleTimeout: Duration,
    val maxRequestBytes: Long,
    val maxResponseHeaderBytes: Long
):
  private[transport] def resolve(
      target: RequestTarget
  ): Either[TransportError, URI] =
    try
      val base = baseUri.toASCIIString.stripSuffix("/")
      Right(new URI(base + target.value))
    catch case NonFatal(_) => Left(TransportError.InvalidTarget)

  override def toString: String =
    s"TransportConfig(endpoint=<redacted>, openTimeout=$openTimeout, bodyIdleTimeout=$bodyIdleTimeout, maxRequestBytes=$maxRequestBytes, maxResponseHeaderBytes=$maxResponseHeaderBytes)"

object TransportConfig:
  val DefaultMaxRequestBytes: Long = 16L * 1024 * 1024
  val DefaultMaxResponseHeaderBytes: Long = 64L * 1024

  def make(
      baseUrl: String,
      openTimeout: Duration,
      bodyIdleTimeout: Duration,
      maxRequestBytes: Long = DefaultMaxRequestBytes,
      maxResponseHeaderBytes: Long = DefaultMaxResponseHeaderBytes
  ): Either[TransportError, TransportConfig] =
    for
      uri <- parseBaseUri(baseUrl)
      _ <- Either.cond(
        validDuration(openTimeout) && validDuration(bodyIdleTimeout),
        (),
        TransportError.InvalidTimeout
      )
      _ <- Either.cond(
        maxRequestBytes > 0 && maxResponseHeaderBytes > 0,
        (),
        TransportError.InvalidSizeLimit
      )
    yield new TransportConfig(
      uri,
      openTimeout,
      bodyIdleTimeout,
      maxRequestBytes,
      maxResponseHeaderBytes
    )

  private def parseBaseUri(value: String): Either[TransportError, URI] =
    try
      if value == null || value.isEmpty || value.length > 4096 ||
        value.exists(character => character.isWhitespace || character.isControl)
      then Left(TransportError.InvalidEndpoint)
      else
        val uri = new URI(value)
        val scheme = Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT))
        val decodedSegments = Option(uri.getPath)
          .getOrElse("")
          .split("/", -1)
        val hasTraversal =
          decodedSegments.exists(segment => segment == "." || segment == "..")
        val port = uri.getPort
        val valid =
          uri.isAbsolute &&
            scheme.exists(candidate =>
              candidate == "http" || candidate == "https"
            ) &&
            Option(uri.getHost).exists(_.nonEmpty) &&
            uri.getRawUserInfo == null &&
            uri.getRawQuery == null &&
            uri.getRawFragment == null &&
            (port == -1 || (port >= 1 && port <= 65535)) &&
            !value.contains('\\') &&
            !hasTraversal &&
            uri.normalize() == uri
        if valid then Right(uri)
        else Left(TransportError.InvalidEndpoint)
    catch case NonFatal(_) => Left(TransportError.InvalidEndpoint)

  private def validDuration(value: Duration): Boolean =
    try
      value != null && value != Duration.Infinity && value.isPositive &&
        value.toNanos > 0 && value.toMillis >= 0
    catch case NonFatal(_) => false

final class RequestTarget private (private[transport] val value: String):
  override def toString: String = "RequestTarget(<redacted>)"

object RequestTarget:
  def from(value: String): Either[TransportError, RequestTarget] =
    try
      if value == null || value.isEmpty || value.length > 4096 ||
        !value.startsWith("/") || value.startsWith("//") ||
        value.exists(character =>
          character.isWhitespace || character.isControl
        ) ||
        value.contains('\\')
      then Left(TransportError.InvalidTarget)
      else
        val uri = new URI(value)
        val decodedSegments = Option(uri.getPath)
          .getOrElse("")
          .split("/", -1)
        val valid =
          !uri.isAbsolute &&
            uri.getRawAuthority == null &&
            uri.getRawQuery == null &&
            uri.getRawFragment == null &&
            uri.getRawPath == value &&
            !decodedSegments.exists(segment =>
              segment == "." || segment == ".."
            )
        if valid then Right(new RequestTarget(value))
        else Left(TransportError.InvalidTarget)
    catch case NonFatal(_) => Left(TransportError.InvalidTarget)

final class RequestHeader private (
    val name: String,
    private[transport] val rawValue: String
):
  override def toString: String =
    "RequestHeader(name=<redacted>, value=<redacted>)"

object RequestHeader:
  private val Name = "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$".r
  private val Forbidden = Set("content-length", "host", "transfer-encoding")
  private val MaxHeaderCharacters = 16 * 1024

  def make(
      name: String,
      value: String
  ): Either[TransportError, RequestHeader] =
    validate(name, value).map((safeName, safeValue) =>
      new RequestHeader(safeName, safeValue)
    )

  def secret(
      name: String,
      value: Secret
  ): Either[TransportError, RequestHeader] =
    if value == null then Left(TransportError.InvalidHeader)
    else make(name, value.reveal)

  def bearer(value: Secret): Either[TransportError, RequestHeader] =
    if value == null then Left(TransportError.InvalidHeader)
    else make("authorization", s"Bearer ${value.reveal}")

  private[transport] def validateResponse(
      name: String,
      value: String
  ): Either[TransportError, (String, String)] =
    validateCommon(name, value, forbidTransportHeaders = false).left
      .map(_ => TransportError.InvalidResponse)

  private def validate(
      name: String,
      value: String
  ): Either[TransportError, (String, String)] =
    validateCommon(name, value, forbidTransportHeaders = true)

  private def validateCommon(
      name: String,
      value: String,
      forbidTransportHeaders: Boolean
  ): Either[TransportError, (String, String)] =
    val normalizedName = Option(name)
      .map(_.toLowerCase(Locale.ROOT))
      .getOrElse("")
    val invalidValue =
      value == null || value.length > MaxHeaderCharacters ||
        value.exists(character =>
          character == '\u0000' || character == '\r' || character == '\n'
        )
    val invalidName =
      name == null || !Name.matches(name) ||
        (forbidTransportHeaders && Forbidden.contains(normalizedName))
    if invalidName || invalidValue then Left(TransportError.InvalidHeader)
    else Right((normalizedName, value))

final class RequestBody private (private[transport] val bytes: Chunk[Byte]):
  val sizeBytes: Int = bytes.length

  override def toString: String =
    s"RequestBody(sizeBytes=$sizeBytes, content=<redacted>)"

object RequestBody:
  val empty: RequestBody = new RequestBody(Chunk.empty)

  def fromChunk(value: Chunk[Byte]): Either[TransportError, RequestBody] =
    if value == null then Left(TransportError.InvalidRequestBody)
    else Right(new RequestBody(Chunk.fromArray(value.toArray)))

  def utf8(value: String): Either[TransportError, RequestBody] =
    if value == null then Left(TransportError.InvalidRequestBody)
    else fromChunk(Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8)))

final class StreamingRequest private (
    val method: HttpMethod,
    val target: RequestTarget,
    val headers: Chunk[RequestHeader],
    val body: RequestBody
):
  override def toString: String =
    s"StreamingRequest(method=$method, target=$target, headers=$headers, body=$body)"

object StreamingRequest:
  def make(
      method: HttpMethod,
      target: RequestTarget,
      headers: Chunk[RequestHeader],
      body: RequestBody
  ): Either[TransportError, StreamingRequest] =
    val nullHeader =
      headers == null || headers.exists(header => header == null)
    val duplicateHeader =
      if headers == null then false
      else
        val names = headers.map(_.name.toLowerCase(Locale.ROOT))
        names.distinct.length != names.length
    val bodyForbidden =
      body != null && body.sizeBytes > 0 &&
        (method == HttpMethod.Get || method == HttpMethod.Head)
    if method == null || target == null || body == null || nullHeader ||
      duplicateHeader || bodyForbidden
    then Left(TransportError.InvalidRequest)
    else Right(new StreamingRequest(method, target, headers, body))

final class ResponseStatus private (val code: Int):
  def isSuccess: Boolean = code >= 200 && code < 300

  override def toString: String = s"ResponseStatus($code)"

object ResponseStatus:
  def from(code: Int): Either[TransportError, ResponseStatus] =
    if code >= 100 && code <= 599 then Right(new ResponseStatus(code))
    else Left(TransportError.InvalidResponse)

final class ResponseHeaders private (
    private val entries: Map[String, Chunk[String]]
):
  def first(name: String): Option[String] =
    Option(name).flatMap(candidate =>
      entries.get(candidate.toLowerCase(Locale.ROOT)).flatMap(_.headOption)
    )

  def all(name: String): Chunk[String] =
    Option(name)
      .flatMap(candidate => entries.get(candidate.toLowerCase(Locale.ROOT)))
      .getOrElse(Chunk.empty)

  val names: Set[String] = entries.keySet

  override def toString: String =
    s"ResponseHeaders(count=${entries.size}, names=<redacted>, values=<redacted>)"

object ResponseHeaders:
  val DefaultMaxAggregateBytes: Long = 64L * 1024
  val empty: ResponseHeaders = new ResponseHeaders(Map.empty)

  def from(
      values: Iterable[(String, String)],
      maxAggregateBytes: Long = DefaultMaxAggregateBytes
  ): Either[TransportError, ResponseHeaders] =
    if values == null || maxAggregateBytes <= 0 then
      Left(TransportError.InvalidResponse)
    else
      values
        .foldLeft[
          Either[TransportError, (Map[String, Chunk[String]], Long)]
        ](
          Right((Map.empty, 0L))
        ) { (result, entry) =>
          if entry == null then Left(TransportError.InvalidResponse)
          else
            val (name, value) = entry
            for
              (accumulated, totalBytes) <- result
              validated <- RequestHeader.validateResponse(name, value)
              (safeName, safeValue) = validated
              entryBytes <- utf8Length(safeName, safeValue)
              nextTotal <- checkedAdd(totalBytes, entryBytes)
              _ <- Either.cond(
                nextTotal <= maxAggregateBytes,
                (),
                TransportError.InvalidResponse
              )
            yield (
              accumulated.updatedWith(safeName) {
                case Some(existing) => Some(existing :+ safeValue)
                case None           => Some(Chunk(safeValue))
              },
              nextTotal
            )
        }
        .map((entries, _) => new ResponseHeaders(entries))

  private def utf8Length(
      name: String,
      value: String
  ): Either[TransportError, Long] =
    try
      checkedAdd(
        name.getBytes(StandardCharsets.UTF_8).length.toLong,
        value.getBytes(StandardCharsets.UTF_8).length.toLong + 4L
      )
    catch case NonFatal(_) => Left(TransportError.InvalidResponse)

  private def checkedAdd(
      left: Long,
      right: Long
  ): Either[TransportError, Long] =
    try Right(Math.addExact(left, right))
    catch case _: ArithmeticException => Left(TransportError.InvalidResponse)

final class StreamingResponse private (
    val status: ResponseStatus,
    val headers: ResponseHeaders,
    val body: ZStream[Any, TransportError, Byte]
):
  override def toString: String =
    s"StreamingResponse(status=$status, headers=$headers, body=<stream>)"

object StreamingResponse:
  def make(
      status: ResponseStatus,
      headers: ResponseHeaders,
      body: ZStream[Any, TransportError, Byte]
  ): Either[TransportError, StreamingResponse] =
    if status == null || headers == null || body == null then
      Left(TransportError.InvalidResponse)
    else Right(new StreamingResponse(status, headers, body))
