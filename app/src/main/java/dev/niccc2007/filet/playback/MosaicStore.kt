package dev.niccc2007.filet.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The last All Music wall as it is kept on disk: the newest-track key it was built from, its
 * tint, and each tile's encoded picture.
 *
 * Bug identified here: the wall lived in memory only, so after a cold start All Music's banner
 * and the Playlists home sat blank while every cover was read and judged again - for a wall
 * that, nine times in ten, was the one drawn last time. Kept, it draws at once, and the key says
 * whether it is still the right one.
 */
class MosaicRecord(val key: String, val tint: Int?, val tiles: List<ByteArray>)

/** One record to bytes and back. Pure, so what counts as a readable record can be tested. */
object MosaicCodec {

    private const val MAGIC = 0x46574C4C // "FWLL"
    private const val VERSION = 1

    /**
     * Ceilings a length read from a damaged file cannot talk past. A tile is a 128px JPEG of a
     * few kilobytes and the key is two hundred paths; a header claiming more is not a record, and
     * believing it would be an allocation the size of whatever the garbage said.
     */
    private const val MAX_TILE_BYTES = 512 * 1024
    private const val MAX_KEY_BYTES = 1 shl 20

    fun encode(r: MosaicRecord): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(VERSION)
            val key = r.key.toByteArray(Charsets.UTF_8)
            o.writeInt(key.size)
            o.write(key)
            o.writeBoolean(r.tint != null)
            o.writeInt(r.tint ?: 0)
            o.writeInt(r.tiles.size)
            for (t in r.tiles) {
                o.writeInt(t.size)
                o.write(t)
            }
        }
        return bytes.toByteArray()
    }

    /**
     * The record, or null for anything that is not a whole one this build wrote: no file, a
     * file cut short, another version, trailing bytes, garbage. Null is today's behaviour - the
     * wall is built from the covers - so a bad file costs one slow start and nothing else.
     */
    fun decode(bytes: ByteArray?): MosaicRecord? = runCatching {
        if (bytes == null) return null
        val i = DataInputStream(ByteArrayInputStream(bytes))
        if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
        val keyLen = i.readInt()
        if (keyLen !in 0..MAX_KEY_BYTES) return null
        val key = ByteArray(keyLen).also { i.readFully(it) }.toString(Charsets.UTF_8)
        val hasTint = i.readBoolean()
        val tint = i.readInt()
        val n = i.readInt()
        if (n !in 0..CoverMosaic.MAX_TILES) return null
        val tiles = ArrayList<ByteArray>(n)
        repeat(n) {
            val len = i.readInt()
            if (len !in 1..MAX_TILE_BYTES) return null
            tiles += ByteArray(len).also { i.readFully(it) }
        }
        if (i.read() != -1) return null
        MosaicRecord(key, tint.takeIf { hasTint }, tiles)
    }.getOrNull()

    /** Whether [stored] was built from the newest tracks [key] names, and so needs no rebuild. */
    fun current(stored: String?, key: String?): Boolean = stored != null && key != null && stored == key
}

/**
 * Where the record lives, and the reads and writes that need Android.
 *
 * One file, written over each time, in the disposable Playback area under Filet's cache, which
 * the storage screen's Clear empties. Its path comes from [dev.niccc2007.filet.data.AppFiles]
 * and every read and write goes through the [Vfs], like any other file the app touches.
 */
internal object MosaicDisk {

    private const val NAME = "all-music-wall.bin"

    /** Kept small: the wall is drawn at under half opacity, and a tile is 128px. */
    private const val QUALITY = 85

    private fun path(context: Context): VPath {
        val files = dev.niccc2007.filet.data.AppFiles(context.applicationContext)
        return files.vpath(files.playCache(NAME))
    }

    /** The stored wall and the key it was built from, or null when there is none worth drawing. */
    suspend fun load(context: Context, vfs: Vfs): Pair<String, Mosaic>? = withContext(Dispatchers.IO) {
        val bytes = runCatching { vfs.openRead(path(context)).use { it.readBytes() } }.getOrNull()
        val record = MosaicCodec.decode(bytes) ?: return@withContext null
        val tiles = record.tiles.mapNotNull { t ->
            runCatching { BitmapFactory.decodeByteArray(t, 0, t.size) }.getOrNull()?.asImageBitmap()
        }
        // A tile that will not decode is a damaged file, and a wall missing tiles from the middle
        // repeats the wrong covers. All of it or none.
        if (tiles.size != record.tiles.size) return@withContext null
        record.key to Mosaic(tiles, record.tint?.let { Color(it) })
    }

    /**
     * Keep [mosaic] as the wall built from [key].
     *
     * Written beside and then renamed into place, so a process killed mid-write leaves the last
     * whole record or none - never half of one that would have to be detected as damaged.
     */
    suspend fun save(context: Context, vfs: Vfs, key: String, mosaic: Mosaic) = withContext(Dispatchers.IO) {
        runCatching {
            val tiles = mosaic.tiles.map { tile ->
                ByteArrayOutputStream().also { tile.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                    .toByteArray()
            }
            val bytes = MosaicCodec.encode(MosaicRecord(key, mosaic.tint?.toArgb(), tiles))
            val target = path(context)
            val beside = target.parent?.child("$NAME.part") ?: return@runCatching
            vfs.openWrite(beside).use { it.write(bytes) }
            runCatching { vfs.delete(target) }
            vfs.rename(beside, NAME)
        }.onFailure {
            dev.niccc2007.filet.log.FiletLog.w("mosaic", "wall not kept: " + it.javaClass.simpleName)
        }
    }
}
