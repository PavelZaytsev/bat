package bat.protocol

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Strict JSON parsing plus deterministic rendering for call replay digests. */
object StrictJson:
  def parse(text: String, label: String = "JSON"): Either[BatError, Json] =
    Option(text)
      .toRight(BatError.ProtocolViolation(s"$label must not be null"))
      .flatMap(
        _.fromJson[Json].left.map { detail =>
          BatError.ProtocolViolation(s"$label is not strict JSON: $detail")
        }
      )
      .flatMap(value => validate(value, label).map(_ => value))

  def parseObject(
      text: String,
      label: String = "JSON"
  ): Either[BatError, Json.Obj] =
    parse(text, label).flatMap {
      case value: Json.Obj => Right(value)
      case _               =>
        Left(BatError.ProtocolViolation(s"$label must be a JSON object"))
    }

  def validate(value: Json, label: String = "JSON"): Either[BatError, Unit] =
    validateAt(value, label, "$")

  def canonical(value: Json, label: String = "JSON"): Either[BatError, String] =
    validate(value, label).map(_ => sorted(value).toJson)

  def sha256(value: Json, label: String = "JSON"): Either[BatError, String] =
    canonical(value, label).map { encoded =>
      val digest = MessageDigest
        .getInstance("SHA-256")
        .digest(encoded.getBytes(StandardCharsets.UTF_8))
      digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
    }

  private def validateAt(
      value: Json,
      label: String,
      path: String
  ): Either[BatError, Unit] =
    value match
      case Json.Obj(fields) =>
        val unique =
          fields.foldLeft[Either[BatError, Set[String]]](Right(Set.empty)) {
            case (result, (name, _)) =>
              result.flatMap { names =>
                if names.contains(name) then
                  Left(
                    BatError.ProtocolViolation(
                      s"$label contains duplicate key '$name' at $path"
                    )
                  )
                else Right(names + name)
              }
          }
        unique.flatMap { _ =>
          fields.foldLeft[Either[BatError, Unit]](Right(())) {
            case (result, (name, child)) =>
              result.flatMap(_ =>
                validateAt(child, label, s"$path.${escapePath(name)}")
              )
          }
        }
      case Json.Arr(elements) =>
        elements.zipWithIndex.foldLeft[Either[BatError, Unit]](Right(())) {
          case (result, (child, index)) =>
            result.flatMap(_ => validateAt(child, label, s"$path[$index]"))
        }
      case _ => Right(())

  private def sorted(value: Json): Json =
    value match
      case Json.Obj(fields) =>
        Json.Obj(
          fields
            .map { case (name, child) => name -> sorted(child) }
            .sortBy(_._1)
        )
      case Json.Arr(elements) => Json.Arr(elements.map(sorted))
      case scalar             => scalar

  private def escapePath(value: String): String =
    if value.matches("[A-Za-z_][A-Za-z0-9_]*") then value
    else value.toJson
