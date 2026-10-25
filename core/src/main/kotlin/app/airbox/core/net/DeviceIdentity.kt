package app.airbox.core.net

import app.airbox.core.security.sha256
import app.airbox.core.security.toHex
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * A device's long-term TLS identity: an ECDSA P-256 key and a self-signed certificate.
 * Peers recognise each other by the certificate fingerprint, not by name. The subject
 * deliberately contains no device name or user data.
 *
 * On Android, prefer generating the key inside AndroidKeyStore (which issues the
 * self-signed certificate itself); on iOS, store it in the Keychain.
 */
class DeviceIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: String = fingerprintOf(certificate)

    fun toKeyStore(password: CharArray): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, privateKey, password, arrayOf(certificate))
        }

    fun save(file: Path, password: CharArray) {
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { toKeyStore(password).store(it, password) }
    }

    companion object {
        private const val ALIAS = "airbox-device"

        fun fingerprintOf(cert: X509Certificate): String = sha256(cert.encoded).toHex()

        fun generate(): DeviceIdentity {
            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val now = Instant.now()
            val subject = X500Name("CN=airbox")
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(64, java.security.SecureRandom()).abs().add(BigInteger.ONE),
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(3650, ChronoUnit.DAYS)),
                subject,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return DeviceIdentity(keyPair.private, cert)
        }

        fun loadOrCreate(file: Path, password: CharArray): DeviceIdentity {
            if (Files.exists(file)) {
                val ks = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, password) } }
                return DeviceIdentity(ks.getKey(ALIAS, password) as PrivateKey, ks.getCertificate(ALIAS) as X509Certificate)
            }
            return generate().also { it.save(file, password) }
        }
    }
}
