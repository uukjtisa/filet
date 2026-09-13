package dev.niccc2007.filet.apk

import dev.niccc2007.filet.index.FactExtractor
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.util.zip.ZipFile

/**
 * What is inside an APK, as searchable facts.
 *
 * > `pkg:` `label:` `perm:` via ARSCLib; `class:` as deduped package prefixes via dexlib2
 * > (~2 KB per APK, not 2 MB). **Nothing else on Android does this.** (FEATURES.md F59)
 *
 * The size discipline is the whole design. A large app has a hundred thousand classes, and
 * storing them all would make the index bigger than the apps it describes. Class names are
 * reduced to their **package prefixes** (`com.squareup.okhttp3`), deduped and capped - which
 * is what anyone actually searches for. Nobody types a full descriptor.
 *
 * Package, label and permissions come from [ApkTools.inspect], not a second parser, so a file
 * that inspects one way cannot search another.
 */
class ApkFacts(private val vfs: Vfs, private val tools: ApkTools) : FactExtractor {

    override val extensions: Set<String> = setOf("apk")

    override suspend fun facts(node: VNode): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val os = vfs.osPath(node.path) ?: return@withContext emptyMap()
        val out = LinkedHashMap<String, List<String>>()

        runCatching { tools.inspect(node.path) }.getOrNull()?.let { info ->
            if (info.packageName.isNotEmpty()) out["pkg"] = listOf(info.packageName)
            if (info.label.isNotEmpty()) out["label"] = listOf(info.label)
            if (info.permissions.isNotEmpty()) {
                // Stored short (`CAMERA`) as well as fully qualified, because both are how
                // people search for one.
                out["perm"] = (info.permissions + info.permissions.map { it.substringAfterLast('.') })
                    .distinct()
                    .take(MAX_PERMISSIONS)
            }
        }

        runCatching { out["class"] = classPrefixes(File(os)) }

        out.filterValues { it.isNotEmpty() }
    }

    private fun classPrefixes(file: File): List<String> {
        val prefixes = LinkedHashSet<String>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (!name.endsWith(".dex") || name.contains('/')) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val dex = DexBackedDexFile(Opcodes.forApi(ApkTools.READ_API_LEVEL), bytes)
                for (classDef in dex.classes) {
                    val type = classDef.type
                    if (!type.startsWith("L") || !type.endsWith(";")) continue
                    prefixes += packagePrefixOf(type.substring(1, type.length - 1).replace('/', '.'))
                    if (prefixes.size >= MAX_PREFIXES) return prefixes.toList()
                }
            }
        }
        return prefixes.toList()
    }

    /**
     * `com.squareup.okhttp3.internal.Util` becomes `com.squareup.okhttp3`.
     *
     * Three segments is the level a library is named at. Deeper and the set explodes;
     * shallower and every app on the device collapses into `com`.
     */
    private fun packagePrefixOf(fqn: String): String {
        val parts = fqn.split('.')
        return when {
            parts.size <= 1 -> fqn
            parts.size <= 3 -> parts.dropLast(1).joinToString(".")
            else -> parts.take(3).joinToString(".")
        }
    }

    private companion object {
        const val MAX_PREFIXES = 400
        const val MAX_PERMISSIONS = 120
    }
}
