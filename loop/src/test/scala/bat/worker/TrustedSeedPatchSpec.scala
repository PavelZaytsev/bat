package bat.worker

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import zio.test.*

object TrustedSeedPatchSpec extends ZIOSpecDefault:
  private val Patch =
    """diff --git a/src/Main.java b/src/Main.java
      |--- a/src/Main.java
      |+++ b/src/Main.java
      |@@ -1 +1 @@
      |-before
      |+after
      |""".stripMargin

  def spec =
    suite("trusted seed patch")(
      test("admits only an operator-bound validated UTF-8 patch") {
        val bytes = Patch.getBytes(StandardCharsets.UTF_8)
        val result = TrustedSeedPatch.fromBytes(bytes, sha256(bytes))
        assertTrue(
          result.exists(_.text == Patch),
          result.exists(_.byteLength == bytes.length),
          result.exists(_.sha256.value == sha256(bytes)),
          result.exists(_.toString.contains("payload=<redacted>")),
          !result.exists(_.toString.contains(Patch))
        )
      },
      test("rejects a digest mismatch before admitting the payload") {
        val bytes = Patch.getBytes(StandardCharsets.UTF_8)
        val result = TrustedSeedPatch.fromBytes(bytes, "0" * 64)
        assertTrue(
          result.left.exists(_.code == "seed_patch_digest_mismatch")
        )
      },
      test("rejects malformed UTF-8 and invalid patch grammar") {
        val malformed = Array(0xc3.toByte, 0x28.toByte)
        val encoding = TrustedSeedPatch.fromBytes(
          malformed,
          sha256(malformed)
        )
        val invalid = "not a patch\n".getBytes(StandardCharsets.UTF_8)
        val grammar = TrustedSeedPatch.fromBytes(invalid, sha256(invalid))
        assertTrue(
          encoding.left.exists(_.code == "invalid_seed_patch_encoding"),
          grammar.left.exists(_.code == "invalid_patch")
        )
      },
      test("enforces the two MiB byte limit") {
        val oversized = Array.fill(TrustedSeedPatch.MaxBytes + 1)('x'.toByte)
        val result = TrustedSeedPatch.fromBytes(oversized, sha256(oversized))
        assertTrue(result.left.exists(_.code == "invalid_seed_patch"))
      }
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
