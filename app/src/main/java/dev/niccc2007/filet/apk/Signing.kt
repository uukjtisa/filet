package dev.niccc2007.filet.apk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import dev.niccc2007.filet.data.Prefs
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** A key Filet can sign with, plus the certificate chain that goes out with it. */
data class SigningKey(
    val alias: String,
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
    val source: String,
) {
    val subject: String get() = certificates.firstOrNull()?.subjectX500Principal?.name ?: alias
    val sha256: String
        get() = certificates.firstOrNull()?.let { cert ->
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }
        } ?: ""
}

/**
 * Signing keys, and signing.
 *
 * Three ways to get a key, because all three exist in the wild:
 *
 * 1. **Generated on device** into the Android keystore. The private key never leaves secure
 *    hardware where the device has it - which also means it cannot be backed up, so the UI
 *    says so rather than letting someone discover it later.
 * 2. **A keystore file** (PKCS#12 or BKS), the normal Android release key.
 * 3. **`pk8` + `pem`**, which is what AOSP platform keys and most command-line tooling use.
 *
 * The signing itself is apksig - the same library the Android build uses, so v1 through v4
 * are the real thing and not an approximation.
 */
class SigningKeys(private val context: Context, private val prefs: Prefs) {

    /**
     * Generate a self-signed key in the Android keystore.
     *
     * `KeyGenParameterSpec` produces the self-signed certificate for us, which is the reason
     * this needs no BouncyCastle: building an X.509 certificate by hand would mean hand-rolled
     * ASN.1, and hand-rolled ASN.1 in a signing path is a bad trade.
     */
    fun generate(alias: String, commonName: String, years: Int = 30): SigningKey {
        val notBefore = Calendar.getInstance()
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, years) }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        gen.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=$commonName"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(notBefore.time)
                .setCertificateNotAfter(notAfter.time)
                .build()
        )
        gen.generateKeyPair()
        return loadFromAndroidKeystore(alias) ?: error("key generated but not readable back")
    }

    fun loadFromAndroidKeystore(alias: String): SigningKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = ks.getKey(alias, null) as? PrivateKey ?: return null
        val chain = ks.getCertificateChain(alias)?.mapNotNull { it as? X509Certificate } ?: emptyList()
        SigningKey(alias, key, chain, "on-device key")
    }.getOrNull()

    fun androidKeystoreAliases(): List<String> = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.aliases().toList().filter { it.startsWith(PREFIX) }
    }.getOrElse { emptyList() }

    fun deleteAndroidKey(alias: String) = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    /**
     * Load from a keystore file.
     *
     * PKCS#12 first because that is what `keytool` produces now, then JKS for older files.
     * Android's JCA has no JKS provider on every version, so a failure here is reported as
     * "this file is not a keystore Android can read" rather than swallowed.
     */
    fun loadKeystore(stream: InputStream, storePassword: String, alias: String?, keyPassword: String): SigningKey {
        val bytes = stream.use { it.readBytes() }
        var lastError: Throwable? = null
        for (type in listOf("PKCS12", "BKS", "JKS")) {
            val result = runCatching {
                val ks = KeyStore.getInstance(type)
                ks.load(bytes.inputStream(), storePassword.toCharArray())
                val useAlias = alias?.takeIf { it.isNotBlank() }
                    ?: ks.aliases().toList().firstOrNull()
                    ?: error("the keystore has no entries")
                val key = ks.getKey(useAlias, keyPassword.toCharArray()) as? PrivateKey
                    ?: error("'$useAlias' is not a private key")
                val chain = ks.getCertificateChain(useAlias)?.mapNotNull { it as? X509Certificate }.orEmpty()
                if (chain.isEmpty()) error("'$useAlias' has no certificate")
                SigningKey(useAlias, key, chain, "$type keystore")
            }
            result.getOrNull()?.let { return it }
            lastError = result.exceptionOrNull()
        }
        throw IllegalArgumentException(
            lastError?.message ?: "Not a keystore Android can read (tried PKCS12, BKS, JKS)."
        )
    }

    /** AOSP platform keys and most command-line tooling ship as a pk8 + pem pair. */
    fun loadPk8Pem(pk8: InputStream, pem: InputStream, alias: String = "pk8"): SigningKey {
        val keyBytes = pk8.use { it.readBytes() }
        val key = java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val certs = CertificateFactory.getInstance("X.509")
            .generateCertificates(pem).mapNotNull { it as? X509Certificate }
        require(certs.isNotEmpty()) { "the certificate file contains no certificate" }
        return SigningKey(alias, key, certs, "pk8 + pem")
    }

    /**
     * Sign [input] into [output].
     *
     * v1 through v3 by default: v1 is what pre-Nougat installers check, v2/v3 are what modern
     * Android verifies, and dropping v1 makes an APK that installs on new devices and fails
     * silently on old ones. v4 needs a separate `.idsig` file and a streaming installer, so it
     * is opt-in.
     */
    fun sign(
        key: SigningKey,
        input: File,
        output: File,
        minSdk: Int,
        v1: Boolean = true,
        v2: Boolean = true,
        v3: Boolean = true,
        v4: Boolean = false,
    ) {
        val config = ApkSigner.SignerConfig.Builder(key.alias, key.privateKey, key.certificates).build()
        val builder = ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk.coerceAtLeast(1))
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(v2)
            .setV3SigningEnabled(v3)
            .setV4SigningEnabled(v4)
            .setCreatedBy("Filet")
        if (v4) builder.setV4SignatureOutputFile(File(output.absolutePath + ".idsig"))
        builder.build().sign()
    }

    /** The alias the "Sign" button uses unless told otherwise. */
    var defaultAlias: String?
        get() = prefs.getString(KEY_DEFAULT)
        set(v) = prefs.putString(KEY_DEFAULT, v)

    fun newAlias(): String = PREFIX + System.currentTimeMillis()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val PREFIX = "filet-signing-"
        const val KEY_DEFAULT = "signing.defaultAlias"
    }
}
