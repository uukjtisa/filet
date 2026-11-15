package dev.niccc2007.filet.locker

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint unlock for the Locker.
 *
 * The Locker's file key is sealed a second time under a key the Android keystore holds and will
 * use only after a fingerprint, and that sealed copy is what [LockerVault.saveBio] keeps. The
 * fingerprint therefore never stands in for the password inside the vault: it unwraps the same
 * file key the password does, and the password keeps working whatever happens to it.
 *
 * Decision: the platform `BiometricPrompt` (API 28) rather than the androidx one, because the
 * androidx prompt wants a FragmentActivity and this app has none. Below 28 the switch is not
 * offered at all.
 *
 * The keystore key is made with `setInvalidatedByBiometricEnrollment`, so a fingerprint added to
 * the phone later cannot open the Locker: the key stops working, [unlock] says so, and the
 * sealed copy is dropped until the switch is turned on again.
 */
object LockerBio {
    private const val ALIAS = "filet-locker-bio"
    private const val GCM_BITS = 128

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** Whether the unlock form should offer the fingerprint. */
    fun ready(context: Context, vault: LockerVault): Boolean =
        supported() && vault.bio() != null && enrolled(context)

    private fun enrolled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bm = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return false
            return bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bm = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return false
            @Suppress("DEPRECATION")
            return bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
        }
        // 28 has no BiometricManager; the prompt itself reports a phone with no fingerprint.
        return true
    }

    /**
     * Turns fingerprint unlock on: asks for a finger, then seals the open Locker's file key under
     * the keystore key. Needs the Locker open, since that is the only time the file key exists.
     */
    fun enable(context: Context, vault: LockerVault, onDone: () -> Unit, onError: (String) -> Unit) {
        if (!supported()) return onError("Fingerprint unlock needs Android 9 or later.")
        val fileKey = vault.fileKey() ?: return onError("Open the Locker first.")
        val cipher = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, freshKey()) }
        }.getOrElse {
            fileKey.fill(0)
            return onError("This phone's keystore would not make a fingerprint key.")
        }
        prompt(context, "Turn on fingerprint unlock", cipher, onError = { fileKey.fill(0); onError(it) }) { c ->
            val sealed = runCatching { c.doFinal(fileKey) }
            fileKey.fill(0)
            sealed.onSuccess { ct ->
                val iv = c.iv
                vault.saveBio(byteArrayOf(iv.size.toByte()) + iv + ct)
                onDone()
            }.onFailure { onError("The fingerprint key could not seal the Locker's key.") }
        }
    }

    /** Asks for a finger and hands the file key it unseals to [onKey]. */
    fun unlock(context: Context, vault: LockerVault, onKey: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (!supported()) return
        val blob = vault.bio() ?: return onError("Fingerprint unlock is off.")
        val cipher = runCatching {
            val ivLen = blob[0].toInt()
            val iv = blob.copyOfRange(1, 1 + ivLen)
            val key = keystore().getKey(ALIAS, null) as? SecretKey ?: error("no key")
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_BITS, iv)) }
        }.getOrElse { e ->
            // The fingerprints on the phone changed, or the key went: the sealed copy can never
            // be opened again, so it goes, and the password is the way in.
            disable(vault)
            return onError(
                if (e is KeyPermanentlyInvalidatedException) "The phone's fingerprints changed. Use the password, then turn fingerprint unlock on again."
                else "Fingerprint unlock stopped working. Use the password.",
            )
        }
        val ivLen = blob[0].toInt()
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        prompt(context, "Open the Locker", cipher, onError) { c ->
            runCatching { c.doFinal(ct) }
                .onSuccess(onKey)
                .onFailure { onError("The fingerprint key did not open the Locker. Use the password.") }
        }
    }

    /** Fingerprint unlock off: the sealed copy and the keystore key both go. */
    fun disable(vault: LockerVault) {
        vault.clearBio()
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun freshKey(): SecretKey {
        runCatching { keystore().deleteEntry(ALIAS) }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun prompt(
        context: Context,
        title: String,
        cipher: Cipher,
        onError: (String) -> Unit,
        onCipher: (Cipher) -> Unit,
    ) {
        val main = context.mainExecutor
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setNegativeButton("Use password", main) { _, _ -> }
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            main,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onCipher) ?: onError("The fingerprint was not tied to the Locker's key.")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled by the person, by the negative button or by leaving: not an error
                    // worth a message.
                    if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    ) return
                    onError(errString.toString())
                }
            },
        )
    }
}
