package dev.niccc2007.filet.apk

import android.content.Context
import android.content.pm.PackageManager
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliFormatter
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Everything the inspector shows about an APK, gathered once. */
data class ApkInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int?,
    val debuggable: Boolean,
    val permissions: List<String>,
    val activities: List<String>,
    val dexEntries: List<String>,
    val classCount: Int,
    val nativeAbis: List<String>,
    val signerSubject: String?,
    val signerSha256: String?,
    val sizeBytes: Long,
    val warnings: List<String>,
)

/**
 * Reading, decompiling, rebuilding and signing an APK, on the device.
 *
 * Two parsers on purpose:
 *
 * - `PackageManager.getPackageArchiveInfo` for the summary, because it is the *operating
 *   system's own* parser and will agree with what actually installs.
 * - **ARSCLib** for anything that has to be edited and written back. It reads and writes
 *   binary XML and `resources.arsc` directly, with no aapt2 and no NDK - which is the only
 *   reason manifest editing is possible in a plain Kotlin app.
 *
 * APKEditor is deliberately NOT used for the heavy path: it loads every dex at once and OOMs
 * on large apps. Dexes are streamed one at a time here instead.
 */
class ApkTools(
    private val context: Context,
    private val vfs: Vfs,
    private val ledger: JobLedger,
) {

    suspend fun inspect(apk: VPath): ApkInfo = withContext(Dispatchers.IO) {
        val os = vfs.osPath(apk) ?: throw IllegalArgumentException("Inspect APKs from local storage.")
        val file = File(os)
        val warnings = ArrayList<String>()

        val pm = context.packageManager
        val pkgInfo = runCatching {
            pm.getPackageArchiveInfo(
                os,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }.getOrNull()
        if (pkgInfo == null) warnings += "Android's own parser could not read this APK."

        val manifest = runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return@use null
                zip.getInputStream(entry).use { AndroidManifestBlock.load(it) }
            }
        }.getOrNull()
        if (manifest == null) warnings += "AndroidManifest.xml could not be decoded."

        val dexEntries = ArrayList<String>()
        val abis = LinkedHashSet<String>()
        var classes = 0
        ZipFile(file).use { zip ->
            for (e in zip.entries()) {
                val n = e.name
                if (n.endsWith(".dex") && !n.contains('/')) dexEntries += n
                if (n.startsWith("lib/")) n.split('/').getOrNull(1)?.let { abis += it }
            }
            for (dexName in dexEntries) {
                currentCoroutineContext().ensureActive()
                runCatching {
                    val bytes = zip.getInputStream(zip.getEntry(dexName)).use { it.readBytes() }
                    classes += DexBackedDexFile(Opcodes.forApi(READ_API_LEVEL), bytes).classes.size
                }
            }
        }

        val signer = pkgInfo?.signingInfo?.apkContentsSigners?.firstOrNull()
        val cert = signer?.let {
            runCatching {
                java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(it.toByteArray().inputStream()) as java.security.cert.X509Certificate
            }.getOrNull()
        }

        ApkInfo(
            label = pkgInfo?.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
                ?: manifest?.applicationLabelString
                ?: file.nameWithoutExtension,
            packageName = pkgInfo?.packageName ?: manifest?.packageName ?: "unknown",
            versionName = pkgInfo?.versionName ?: manifest?.versionName ?: "",
            versionCode = pkgInfo?.longVersionCode ?: (manifest?.versionCode?.toLong() ?: 0L),
            minSdk = pkgInfo?.applicationInfo?.minSdkVersion ?: manifest?.minSdkVersion ?: 0,
            targetSdk = pkgInfo?.applicationInfo?.targetSdkVersion ?: manifest?.targetSdkVersion ?: 0,
            compileSdk = manifest?.compileSdkVersion,
            debuggable = manifest?.isDebuggable == true,
            permissions = (pkgInfo?.requestedPermissions?.toList() ?: manifest?.usesPermissions ?: emptyList()).sorted(),
            activities = pkgInfo?.activities?.mapNotNull { it.name }?.sorted() ?: emptyList(),
            dexEntries = dexEntries,
            classCount = classes,
            nativeAbis = abis.toList(),
            signerSubject = cert?.subjectX500Principal?.name,
            signerSha256 = cert?.let {
                java.security.MessageDigest.getInstance("SHA-256").digest(it.encoded)
                    .joinToString(":") { b -> "%02X".format(b) }
            },
            sizeBytes = file.length(),
            warnings = warnings,
        )
    }

    /**
     * Write a working copy: every dex as a `smali_classesN` tree, everything else verbatim.
     *
     * Dexes are read one at a time. Loading all of them at once is exactly what makes the
     * popular on-device tools fall over on a large app.
     */
    suspend fun decompile(apk: VPath, workDir: File, onProgress: (String) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val os = vfs.osPath(apk) ?: throw IllegalArgumentException("Decompile APKs from local storage.")
            val file = File(os)
            workDir.deleteRecursively()
            workDir.mkdirs()

            ZipFile(file).use { zip ->
                for (e in zip.entries()) {
                    currentCoroutineContext().ensureActive()
                    if (e.isDirectory) continue
                    val name = e.name
                    if (name.endsWith(".dex") && !name.contains('/')) continue   // handled below
                    val out = File(workDir, "original/$name")
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }

                val dexNames = zip.entries().asSequence()
                    .map { it.name }.filter { it.endsWith(".dex") && !it.contains('/') }.toList()

                for (dexName in dexNames) {
                    currentCoroutineContext().ensureActive()
                    onProgress(dexName)
                    val bytes = zip.getInputStream(zip.getEntry(dexName)).use { it.readBytes() }
                    val dex = DexBackedDexFile(Opcodes.forApi(READ_API_LEVEL), bytes)
                    val root = File(workDir, smaliDirFor(dexName))
                    root.mkdirs()
                    val options = BaksmaliOptions().apply {
                        apiLevel = READ_API_LEVEL
                        parameterRegisters = true
                        localsDirective = true
                        sequentialLabels = true
                        debugInfo = true
                    }
                    for (classDef in dex.classes) {
                        currentCoroutineContext().ensureActive()
                        val type = classDef.type
                        if (!type.startsWith("L") || !type.endsWith(";")) continue
                        val rel = type.substring(1, type.length - 1) + ".smali"
                        val target = File(root, rel)
                        target.parentFile?.mkdirs()
                        target.bufferedWriter().use { w ->
                            BaksmaliFormatter().getWriter(w).use { writer ->
                                ClassDefinition(options, classDef).writeTo(writer)
                            }
                        }
                    }
                }
            }
            workDir
        }

    /**
     * Assemble the smali trees back to dex, repack, and sign.
     *
     * @return the signed APK.
     *
     * The original APK is the source for every non-dex entry, so resources, assets and native
     * libraries are byte-identical to what came in. Only the dexes are rebuilt, which keeps
     * the blast radius of a rebuild to the thing that was actually edited.
     */
    suspend fun rebuild(
        workDir: File,
        originalApk: VPath,
        outUnsigned: File,
        /**
         * The APK's own `minSdkVersion`, and the thing that decides the **dex format
         * version** smali writes.
         *
         * This is not a detail. smali picks the newest dex format the given API level allows,
         * so assembling at [READ_API_LEVEL] emits DEX 041 - which no runtime below API 35
         * can load. The rebuilt APK then installs happily and dies the moment a class is
         * touched, on every device the app actually supports. Assembling at the app's own
         * minimum keeps the output loadable everywhere the original was.
         */
        minSdk: Int,
        onProgress: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val os = vfs.osPath(originalApk) ?: throw IllegalArgumentException("Rebuild from local storage.")
        val original = File(os)
        val smaliDirs = workDir.listFiles { f -> f.isDirectory && f.name.startsWith("smali") }
            ?.sortedBy { it.name } ?: emptyList()
        require(smaliDirs.isNotEmpty()) { "No smali folders in the working copy." }

        val dexOut = LinkedHashMap<String, File>()
        for (dir in smaliDirs) {
            currentCoroutineContext().ensureActive()
            onProgress("assembling ${dir.name}")
            val dexName = dexNameFor(dir.name)
            val target = File(workDir, "build/$dexName")
            target.parentFile?.mkdirs()
            val options = SmaliOptions().apply {
                apiLevel = minSdk.coerceIn(MIN_WRITE_API_LEVEL, READ_API_LEVEL)
                outputDexFile = target.absolutePath
                jobs = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
            }
            val ok = Smali.assemble(options, listOf(dir.absolutePath))
            require(ok) { "smali failed to assemble ${dir.name}" }
            dexOut[dexName] = target
        }

        onProgress("repacking")
        outUnsigned.parentFile?.mkdirs()
        ZipOutputStream(outUnsigned.outputStream().buffered()).use { zos ->
            ZipFile(original).use { zip ->
                for (e in zip.entries()) {
                    currentCoroutineContext().ensureActive()
                    if (e.isDirectory) continue
                    val name = e.name
                    // The signature of the ORIGINAL is worthless once a dex changes, and
                    // leaving it in produces an APK that fails verification rather than one
                    // that is unsigned.
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") ||
                            name == "META-INF/MANIFEST.MF")
                    ) continue
                    if (dexOut.containsKey(name)) continue

                    val edited = File(workDir, "original/$name")
                    val entry = ZipEntry(name)
                    if (e.method == ZipEntry.STORED) {
                        entry.method = ZipEntry.STORED
                        entry.size = e.size
                        entry.compressedSize = e.compressedSize
                        entry.crc = e.crc
                    }
                    zos.putNextEntry(entry)
                    if (edited.isFile) edited.inputStream().use { it.copyTo(zos) }
                    else zip.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            for ((name, f) in dexOut) {
                zos.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        outUnsigned
    }

    /**
     * Rebuild from the working copy and sign the result.
     *
     * The whole pipeline lives here rather than in the ViewModel because every step needs a
     * real `java.io.File` - apksig, smali and ARSCLib all refuse streams - and R3 keeps that
     * confined to this package.
     *
     * @return the signed APK, beside the original.
     */
    suspend fun rebuildAndSign(
        original: VPath,
        workDir: File,
        keys: SigningKeys,
        key: SigningKey,
        /**
         * Leave null to read it out of the APK, which is almost always what is wanted.
         *
         * It decides both the dex format smali writes and which signature schemes apksig
         * emits, so a caller guessing it wrong produces an APK that installs and then fails -
         * and a caller has no reason to know it better than the manifest does.
         */
        minSdk: Int? = null,
        onProgress: (String) -> Unit = {},
    ): VPath = withContext(Dispatchers.IO) {
        val sdk = minSdk ?: runCatching { inspect(original).minSdk }.getOrDefault(MIN_WRITE_API_LEVEL)
        val unsigned = File(workDir, "build/unsigned.apk")
        rebuild(workDir, original, unsigned, sdk, onProgress)
        onProgress("signing")
        val parent = original.parent ?: error("nowhere to put the result")
        val out = File(parent.path, original.name.removeSuffix(".apk") + "-filet.apk")
        keys.sign(key, unsigned, out, sdk)
        VPath.of("local", out.absolutePath.replace(File.separatorChar, '/'))
    }

    /** Re-sign an APK without changing anything in it. */
    suspend fun signOnly(
        apk: VPath,
        keys: SigningKeys,
        key: SigningKey,
        /** Null reads it from the APK - see [rebuildAndSign]. */
        minSdk: Int? = null,
    ): VPath = withContext(Dispatchers.IO) {
        val os = vfs.osPath(apk) ?: error("Sign APKs from local storage.")
        val sdk = minSdk ?: runCatching { inspect(apk).minSdk }.getOrDefault(MIN_WRITE_API_LEVEL)
        val parent = apk.parent ?: error("nowhere to put the result")
        val out = File(parent.path, apk.name.removeSuffix(".apk") + "-signed.apk")
        keys.sign(key, File(os), out, sdk)
        VPath.of("local", out.absolutePath.replace(File.separatorChar, '/'))
    }

    /** Read the binary manifest out of an APK for editing. */
    suspend fun readManifest(apk: VPath): AndroidManifestBlock = withContext(Dispatchers.IO) {
        val os = vfs.osPath(apk) ?: throw IllegalArgumentException("Read APKs from local storage.")
        ZipFile(File(os)).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw IllegalStateException("No AndroidManifest.xml in this APK.")
            zip.getInputStream(entry).use { AndroidManifestBlock.load(it) }
        }
    }

    /**
     * Write an edited manifest into the working copy.
     *
     * The manifest lands in `original/`, where [rebuild] prefers it over the entry from the
     * source APK. Editing it in place inside the APK is not an option: a zip is not a
     * random-access format once entry sizes change.
     */
    suspend fun writeManifest(workDir: File, manifest: AndroidManifestBlock) = withContext(Dispatchers.IO) {
        manifest.refresh()
        val out = File(workDir, "original/AndroidManifest.xml")
        out.parentFile?.mkdirs()
        out.outputStream().use { manifest.writeBytes(it) }
    }

    private fun smaliDirFor(dexName: String): String = when (dexName) {
        "classes.dex" -> "smali"
        else -> "smali_" + dexName.removeSuffix(".dex")
    }

    private fun dexNameFor(dirName: String): String = when (dirName) {
        "smali" -> "classes.dex"
        else -> dirName.removePrefix("smali_") + ".dex"
    }

    companion object {
        /**
         * What dexlib2 and baksmali are told when READING.
         *
         * Reading is forgiving - a high level simply means every opcode has a name - so this
         * is deliberately generous and can move forward with new Android releases.
         */
        const val READ_API_LEVEL = 35

        /**
         * The floor used when WRITING, so a broken or absent `minSdkVersion` cannot produce a
         * dex older than Filet's own minimum.
         */
        const val MIN_WRITE_API_LEVEL = 26
    }
}
