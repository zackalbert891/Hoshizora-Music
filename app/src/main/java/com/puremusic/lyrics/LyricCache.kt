package com.puremusic.lyrics

import android.content.Context
import android.util.LruCache
import com.puremusic.model.Lyrics
import com.puremusic.model.Song
import com.puremusic.source.autoswitch.AutoSwitchSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 歌词两级缓存：内存 LruCache + 磁盘 cacheDir/lyrics（按 源:真实id 分文件）。
 *
 * 磁盘格式：首行版本标记；正文行 L\t<timeMs>\t<text>（原文行）/ T\t<timeMs>\t<text>（翻译行）。
 * key 使用 源id:真实平台id（跨源换源后仍能命中同一首歌的缓存）。
 */
@Singleton
class LyricCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val memory = object : LruCache<String, Lyrics>(MAX_MEMORY_ENTRIES) {}

    private val dir: File
        get() = File(context.cacheDir, "lyrics").apply { if (!exists()) mkdirs() }

    fun get(key: String): Lyrics? {
        memory.get(key)?.let { return it }
        val file = fileOf(key)
        if (!file.exists()) return null
        val lyrics = runCatching { decode(file.readText()) }.getOrNull()
        if (lyrics != null) memory.put(key, lyrics)
        return lyrics
    }

    fun put(key: String, lyrics: Lyrics) {
        memory.put(key, lyrics)
        runCatching {
            fileOf(key).apply { parentFile?.mkdirs() }.writeText(encode(lyrics))
        }
    }

    fun clearKey(key: String) {
        memory.remove(key)
        runCatching { fileOf(key).delete() }
    }

    private fun fileOf(key: String): File =
        File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".lrc")

    private fun encode(lyrics: Lyrics): String = buildString {
        appendLine("#PUREMUSIC-LYRIC v1")
        lyrics.lines.forEach {
            appendLine("L\t${it.timeMs}\t${it.text.replace("\n", "\\n")}")
        }
        lyrics.translation.forEach {
            appendLine("T\t${it.timeMs}\t${it.text.replace("\n", "\\n")}")
        }
    }

    private fun decode(text: String): Lyrics? {
        val raw = text.lines().drop(1)
        val original = mutableListOf<com.puremusic.model.LyricLine>()
        val translated = mutableListOf<com.puremusic.model.LyricLine>()
        for (line in raw) {
            val parts = line.split("\t", limit = 3)
            if (parts.size != 3) continue
            val time = parts[1].toLongOrNull() ?: continue
            val lyric = parts[2].replace("\\n", "\n")
            when (parts[0]) {
                "L" -> original += com.puremusic.model.LyricLine(time, lyric)
                "T" -> translated += com.puremusic.model.LyricLine(time, lyric)
            }
        }
        return Lyrics(lines = original, translation = translated)
    }

    companion object {
        private const val MAX_MEMORY_ENTRIES = 64

        /** 缓存 key：源id + 真实平台 id（跨源换源后的歌曲 extra["srcId"]）。 */
        fun keyOf(song: Song): String =
            "${song.source.id}:${song.extra[AutoSwitchSource.SRC_ID_KEY] ?: song.id}"
    }
}
