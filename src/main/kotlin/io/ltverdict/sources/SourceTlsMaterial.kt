package io.ltverdict.sources

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

internal class SourceTlsMaterial(
    val sslContext: SSLContext,
    val clientChain: List<X509Certificate>,
) {
    fun checkClientValidity(clock: () -> Instant = Instant::now) {
        checkClientCertificates(clientChain, clock())
    }
}

internal fun sourceTlsMaterial(
    tls: SourceTls,
    environment: (String) -> String?,
    systemProperty: (String) -> String?,
): SourceTlsMaterial {
    try {
        val bypass = systemProperty("jdk.internal.httpclient.disableHostnameVerification")
        if (bypass != null && (bypass.isEmpty() || bypass.equals("true", ignoreCase = true))) {
            throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
        }
        val keyManagers =
            tls.clientKeystoreFile?.let { path ->
                val name = tls.clientKeystorePasswordEnv ?: throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
                val passwordValue = environment(name)
                if (passwordValue.isNullOrEmpty() ||
                    passwordValue.encodeToByteArray().size > 1024 ||
                    passwordValue.any(Char::isISOControl)
                ) {
                    throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
                }
                val password = passwordValue.toCharArray()
                try {
                    val bytes = readTlsFile(path)
                    if (bytes.isEmpty() || bytes[0] != 0x30.toByte()) throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
                    val store = KeyStore.getInstance("PKCS12")
                    ByteArrayInputStream(bytes).use { store.load(it, password) }
                    val aliases = store.aliases().toList().filter(store::isKeyEntry)
                    if (aliases.size != 1) throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
                    val chain = store.getCertificateChain(aliases.single())
                    if (chain == null || chain.size !in 1..8 || chain.any { it !is X509Certificate }) {
                        throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
                    }
                    val certificates = chain.map { it as X509Certificate }
                    checkClientCertificates(certificates, Instant.now())
                    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    factory.init(store, password)
                    factory.keyManagers to certificates
                } finally {
                    password.fill('\u0000')
                }
            }
        if (tls.clientKeystoreFile == null && tls.clientKeystorePasswordEnv != null) {
            throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        val trustStore =
            tls.caFile?.let { path ->
                val bytes = readTlsFile(path)
                if (!bytes.toString(Charsets.US_ASCII).contains("-----BEGIN CERTIFICATE-----")) {
                    throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
                }
                val certificates = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes))
                if (certificates.size !in 1..32 || certificates.any { it !is X509Certificate }) {
                    throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
                }
                KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    certificates.forEachIndexed { index, certificate -> setCertificateEntry("ca-$index", certificate) }
                }
            }
        trust.init(trustStore)
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers?.first, trust.trustManagers, null)
        return SourceTlsMaterial(context, keyManagers?.second.orEmpty())
    } catch (failure: SourceHttpFailure) {
        throw failure
    } catch (_: Exception) {
        throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
    }
}

private fun checkClientCertificates(
    chain: List<X509Certificate>,
    now: Instant,
) {
    chain.forEach { certificate ->
        if (certificate.notAfter.toInstant().isBefore(now)) throw SourceHttpFailure("SOURCE_TLS_CLIENT_CERT_EXPIRED")
        if (certificate.notBefore.toInstant().isAfter(now)) throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
    }
}

private fun readTlsFile(path: Path): ByteArray {
    if (!Files.isRegularFile(path)) throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_TLS_FILE_BYTES + 1) }
    if (bytes.size > MAX_TLS_FILE_BYTES) throw SourceHttpFailure("SOURCE_TLS_CONFIG_INVALID")
    return bytes
}

private const val MAX_TLS_FILE_BYTES = 1024 * 1024
