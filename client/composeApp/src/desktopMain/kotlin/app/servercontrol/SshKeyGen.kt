package app.servercontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Provider
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

// NOTE: identical file in androidMain and desktopMain.
// Key pairs are generated with the JCA KeyPairGenerator like ConnectBot does
// (GeneratePubkeyViewModel.generateKeyPair); the result is written in the standard
// OpenSSH formats so JSch, OpenSSH and other clients can use it.

/** SSH wire format writer (RFC 4251). */
private class SshBuf {
    private val out = ByteArrayOutputStream()
    fun u32(v: Int) = apply { out.write(v ushr 24 and 255); out.write(v ushr 16 and 255); out.write(v ushr 8 and 255); out.write(v and 255) }
    fun bytes(b: ByteArray) = apply { u32(b.size); out.write(b) }
    fun str(s: String) = bytes(s.toByteArray(Charsets.US_ASCII))
    fun mpint(v: BigInteger) = bytes(if (v.signum() == 0) ByteArray(0) else v.toByteArray())
    fun raw(b: ByteArray) = apply { out.write(b) }
    fun size() = out.size()
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Minimal DER reader: returns (contentStart, contentLength) of the element at [pos]. */
private fun der(b: ByteArray, pos: Int): Pair<Int, Int> {
    var p = pos + 1
    var len = b[p++].toInt() and 0xff
    if (len and 0x80 != 0) {
        val n = len and 0x7f
        len = 0
        repeat(n) { len = (len shl 8) or (b[p++].toInt() and 0xff) }
    }
    return p to len
}

/** 32-byte Ed25519 seed from a PKCS#8 (v1 or v2) encoding. */
private fun ed25519Seed(pkcs8: ByteArray): ByteArray {
    var (p, _) = der(pkcs8, 0)                 // PrivateKeyInfo SEQUENCE
    der(pkcs8, p).let { (s, l) -> p = s + l } // version
    der(pkcs8, p).let { (s, l) -> p = s + l } // AlgorithmIdentifier
    val (s, l) = der(pkcs8, p)                 // privateKey OCTET STRING
    val inner = pkcs8.copyOfRange(s, s + l)    // CurvePrivateKey ::= OCTET STRING (32 bytes)
    val (s2, l2) = der(inner, 0)
    require(l2 == 32) { "unexpected Ed25519 key encoding" }
    return inner.copyOfRange(s2, s2 + l2)
}

/** Unsigned big-endian integer padded to [len] bytes. */
private fun fixed(v: BigInteger, len: Int): ByteArray {
    val b = v.toByteArray().let { if (it.size > len && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    return ByteArray(len - b.size) + b
}

private fun keyPairGenerator(algorithm: String): KeyPairGenerator = try {
    KeyPairGenerator.getInstance(algorithm)
} catch (e: Exception) {
    // older Android versions have no Ed25519 in the platform providers – use Bouncy Castle (bundled on Android)
    val bc = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider").getDeclaredConstructor().newInstance() as Provider
    KeyPairGenerator.getInstance(algorithm, bc)
}

actual suspend fun generateSshKey(algo: SshKeyAlgo, bits: Int, comment: String): GeneratedSshKey = withContext(Dispatchers.Default) {
    val random = SecureRandom()
    val kp: KeyPair = when (algo) {
        SshKeyAlgo.ED25519 -> keyPairGenerator("Ed25519").generateKeyPair()
        SshKeyAlgo.ECDSA -> keyPairGenerator("EC").apply {
            initialize(ECGenParameterSpec(when (bits) { 384 -> "secp384r1"; 521 -> "secp521r1"; else -> "secp256r1" }), random)
        }.generateKeyPair()
        SshKeyAlgo.RSA -> keyPairGenerator("RSA").apply { initialize(bits.coerceIn(2048, 8192), random) }.generateKeyPair()
    }

    val type: String
    val pubBlob: ByteArray
    val privFields = SshBuf()
    when (algo) {
        SshKeyAlgo.ED25519 -> {
            type = "ssh-ed25519"
            val x509 = kp.public.encoded
            val pub = x509.copyOfRange(x509.size - 32, x509.size)
            val seed = ed25519Seed(kp.private.encoded)
            pubBlob = SshBuf().str(type).bytes(pub).toByteArray()
            privFields.str(type).bytes(pub).bytes(seed + pub)
        }
        SshKeyAlgo.ECDSA -> {
            val pub = kp.public as ECPublicKey
            val priv = kp.private as ECPrivateKey
            val fieldBytes = (pub.params.curve.field.fieldSize + 7) / 8
            val curve = when (fieldBytes) { 48 -> "nistp384"; 66 -> "nistp521"; else -> "nistp256" }
            type = "ecdsa-sha2-$curve"
            val q = byteArrayOf(4) + fixed(pub.w.affineX, fieldBytes) + fixed(pub.w.affineY, fieldBytes)
            pubBlob = SshBuf().str(type).str(curve).bytes(q).toByteArray()
            privFields.str(type).str(curve).bytes(q).mpint(priv.s)
        }
        SshKeyAlgo.RSA -> {
            type = "ssh-rsa"
            val pub = kp.public as RSAPublicKey
            val priv = kp.private as RSAPrivateCrtKey
            pubBlob = SshBuf().str(type).mpint(pub.publicExponent).mpint(pub.modulus).toByteArray()
            privFields.str(type).mpint(priv.modulus).mpint(priv.publicExponent).mpint(priv.privateExponent)
                .mpint(priv.crtCoefficient).mpint(priv.primeP).mpint(priv.primeQ)
        }
    }

    // openssh-key-v1, cipher "none" (the app stores it inside its encrypted vault)
    val check = random.nextInt()
    val priv = SshBuf().u32(check).u32(check).raw(privFields.toByteArray()).str(comment)
    var pad = 1
    while (priv.size() % 8 != 0) priv.raw(byteArrayOf((pad++).toByte()))
    val blob = SshBuf().raw("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))
        .str("none").str("none").bytes(ByteArray(0)).u32(1).bytes(pubBlob).bytes(priv.toByteArray())
        .toByteArray()
    val b64 = Base64.getEncoder().encodeToString(blob).chunked(70).joinToString("\n")
    val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"

    val pubLine = "$type ${Base64.getEncoder().encodeToString(pubBlob)}" + (if (comment.isNotBlank()) " ${comment.trim()}" else "")
    val fp = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(pubBlob))
    GeneratedSshKey(pem, pubLine, fp)
}

/** Public key line ("type base64 comment") and SHA256 fingerprint of an OpenSSH/PEM private key. */
actual fun sshPublicKeyOf(privateKey: String, passphrase: String?): Pair<String, String>? = runCatching {
    val kp = com.jcraft.jsch.KeyPair.load(com.jcraft.jsch.JSch(), privateKey.toByteArray(), null)
    if (kp.isEncrypted && (passphrase.isNullOrEmpty() || !kp.decrypt(passphrase))) return null
    val blob = kp.publicKeyBlob ?: return null
    val type = String(blob, 4, ((blob[0].toInt() and 255) shl 24) or ((blob[1].toInt() and 255) shl 16) or ((blob[2].toInt() and 255) shl 8) or (blob[3].toInt() and 255), Charsets.US_ASCII)
    val fp = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    val line = "$type ${Base64.getEncoder().encodeToString(blob)}" + (kp.publicKeyComment?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
    kp.dispose()
    line to fp
}.getOrNull()
