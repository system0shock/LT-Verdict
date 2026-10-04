package io.ltverdict.sources

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsExchange
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceRole
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

internal object TestPki {
    private data class Fileset(
        val directory: Path,
        val ca1Pem: Path,
        val ca2Pem: Path,
        val server: Path,
        val wrongSan: Path,
        val client1: Path,
        val client2: Path,
        val clientOther: Path,
        val clientExpired: Path,
        val clientNotYet: Path,
        val jks: Path,
        val empty: Path,
        val garbage: Path,
        val noKey: Path,
        val password: String,
    )

    private val files by lazy(::generateOrCleanup)

    val directory: Path get() = files.directory
    val ca1Pem: Path get() = files.ca1Pem
    val ca2Pem: Path get() = files.ca2Pem
    val serverKeystore: Path get() = files.server
    val wrongSanKeystore: Path get() = files.wrongSan
    val client1: Path get() = files.client1
    val client2: Path get() = files.client2
    val clientOther: Path get() = files.clientOther
    val clientExpired: Path get() = files.clientExpired
    val clientNotYet: Path get() = files.clientNotYet
    val jks: Path get() = files.jks
    val empty: Path get() = files.empty
    val garbage: Path get() = files.garbage
    val noKey: Path get() = files.noKey
    val password: String get() = files.password

    fun profile(
        port: Int,
        tls: SourceTls? = null,
        transport: SourceTransport = SourceTransport.DIRECT,
        id: String = "tls-profile",
    ): SourceProfile =
        SourceProfile(
            id = id,
            sourceKind = SourceKind.PROMETHEUS,
            transport = transport,
            baseUrl = URI("https://127.0.0.1:$port"),
            datasourceUid = if (transport == SourceTransport.GRAFANA_PROXY) "prom" else null,
            governor = SourceGovernor(requestsPerSecond = 1_000.0, timeoutMillis = 5_000, maxAttempts = 1),
            queries =
                listOf(
                    SourceQuery(
                        "q",
                        "rate(x[${'$'}__interval])",
                        "x",
                        "ratio",
                        "entity",
                        ResourceRole.SYSTEM,
                        ResourceAggregation.INTERVAL_RATE,
                        emptyMap(),
                    ),
                ),
            tls = tls,
        )

    fun server(
        keystore: Path = serverKeystore,
        trustCa2: Boolean = false,
    ): Server {
        val keyStore = KeyStore.getInstance("PKCS12")
        Files.newInputStream(keystore).use { keyStore.load(it, password.toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(keyStore, password.toCharArray())
        val trustStore = KeyStore.getInstance("PKCS12")
        trustStore.load(null, null)
        val certificates = CertificateFactory.getInstance("X.509")
        Files.newInputStream(ca1Pem).use { trustStore.setCertificateEntry("ca1", certificates.generateCertificate(it)) }
        if (trustCa2) {
            Files.newInputStream(ca2Pem).use { trustStore.setCertificateEntry("ca2", certificates.generateCertificate(it)) }
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(trustStore)
        val context = SSLContext.getInstance("TLS")
        context.init(keys.keyManagers, trust.trustManagers, null)
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val subjects = CopyOnWriteArrayList<String>()
        val executor = Executors.newCachedThreadPool { task -> Thread(task, "source-tls-test").apply { isDaemon = true } }
        server.httpsConfigurator =
            object : HttpsConfigurator(context) {
                override fun configure(params: HttpsParameters) {
                    params.needClientAuth = true
                    params.setSSLParameters(context.defaultSSLParameters.apply { needClientAuth = true })
                }
            }
        server.createContext("/") { exchange ->
            val certificate = (exchange as HttpsExchange).sslSession.peerCertificates[0] as X509Certificate
            subjects += certificate.subjectX500Principal.name
            val body = RESPONSE.encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.executor = executor
        server.start()
        return Server(server, executor, subjects)
    }

    internal class Server(
        private val server: HttpsServer,
        private val executor: java.util.concurrent.ExecutorService,
        val subjects: CopyOnWriteArrayList<String>,
    ) : AutoCloseable {
        val port: Int get() = server.address.port

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun generateOrCleanup(): Fileset {
        val directory = Files.createTempDirectory("ltv-test-pki-")
        val cleanup = Thread { deleteTree(directory) }
        Runtime.getRuntime().addShutdownHook(cleanup)
        try {
            return generate(directory)
        } catch (failure: Throwable) {
            // Do not leave key material behind when generation fails halfway.
            deleteTree(directory)
            Runtime.getRuntime().removeShutdownHook(cleanup)
            throw failure
        }
    }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun generate(directory: Path): Fileset {
        val password = UUID.randomUUID().toString() + UUID.randomUUID()
        val keytool =
            Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool",
            )

        fun run(vararg args: String) {
            val process = ProcessBuilder(keytool.toString(), *args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() != 0) error("keytool failed: ${output.replace(password, "[redacted]")}")
        }

        fun pair(
            path: Path,
            alias: String,
            cn: String,
            type: String = "PKCS12",
            vararg extra: String,
        ) {
            run(
                "-genkeypair",
                "-alias",
                alias,
                "-dname",
                "CN=$cn",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "3650",
                "-keystore",
                path.toString(),
                "-storetype",
                type,
                "-storepass",
                password,
                "-keypass",
                password,
                "-noprompt",
                *extra,
            )
        }

        fun ca(name: String): Pair<Path, Path> {
            val store = directory.resolve("$name.p12")
            val pem = directory.resolve("$name.pem")
            pair(store, name, name, "PKCS12", "-ext", "bc=ca:true", "-ext", "ku=keyCertSign,cRLSign")
            run("-exportcert", "-alias", name, "-keystore", store.toString(), "-storepass", password, "-rfc", "-file", pem.toString())
            return store to pem
        }

        fun signed(
            name: String,
            cn: String,
            caStore: Path,
            caPem: Path,
            caAlias: String,
            vararg certificateOptions: String,
        ): Path {
            val store = directory.resolve("$name.p12")
            val request = directory.resolve("$name.csr")
            val signed = directory.resolve("$name.crt")
            pair(store, name, cn)
            run("-certreq", "-alias", name, "-keystore", store.toString(), "-storepass", password, "-file", request.toString())
            run(
                "-gencert",
                "-alias",
                caAlias,
                "-keystore",
                caStore.toString(),
                "-storepass",
                password,
                "-infile",
                request.toString(),
                "-outfile",
                signed.toString(),
                "-rfc",
                *certificateOptions,
            )
            run(
                "-importcert",
                "-alias",
                caAlias,
                "-keystore",
                store.toString(),
                "-storepass",
                password,
                "-file",
                caPem.toString(),
                "-noprompt",
            )
            run(
                "-importcert",
                "-alias",
                name,
                "-keystore",
                store.toString(),
                "-storepass",
                password,
                "-file",
                signed.toString(),
                "-noprompt",
            )
            return store
        }

        val (ca1, ca1Pem) = ca("ca1")
        val (ca2, ca2Pem) = ca("ca2")
        val server = signed("server", "localhost", ca1, ca1Pem, "ca1", "-ext", "san=ip:127.0.0.1,dns:localhost")
        val wrongSan = signed("wrong-san", "wrong.example", ca1, ca1Pem, "ca1", "-ext", "san=dns:wrong.example")
        val client1 = signed("client-one", "client-one", ca1, ca1Pem, "ca1")
        val client2 = signed("client-two", "client-two", ca1, ca1Pem, "ca1")
        val clientOther = signed("client-other", "client-other", ca2, ca2Pem, "ca2")
        val clientExpired = signed("client-expired", "client-expired", ca1, ca1Pem, "ca1", "-startdate", "-10d", "-validity", "1")
        val clientNotYet = signed("client-notyet", "client-notyet", ca1, ca1Pem, "ca1", "-startdate", "+10d")
        val jks = directory.resolve("client.jks")
        pair(jks, "client-jks", "client-jks", "JKS")
        val empty = Files.createFile(directory.resolve("empty"))
        val garbage = Files.writeString(directory.resolve("garbage"), "not a keystore or certificate")
        val noKey = directory.resolve("no-key.p12")
        run(
            "-importcert",
            "-alias",
            "ca1",
            "-keystore",
            noKey.toString(),
            "-storetype",
            "PKCS12",
            "-storepass",
            password,
            "-file",
            ca1Pem.toString(),
            "-noprompt",
        )
        return Fileset(
            directory,
            ca1Pem,
            ca2Pem,
            server,
            wrongSan,
            client1,
            client2,
            clientOther,
            clientExpired,
            clientNotYet,
            jks,
            empty,
            garbage,
            noKey,
            password,
        )
    }

    const val RESPONSE = """{"status":"success","data":{"resultType":"matrix","result":[]}}"""
}
