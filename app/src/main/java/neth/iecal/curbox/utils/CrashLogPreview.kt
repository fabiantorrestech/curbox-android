package neth.iecal.curbox.utils

import java.io.File
import java.io.RandomAccessFile

object CrashLogPreview {
    private const val MAX_DISPLAY_CHARS = 50_000
    private const val MAX_READ_BYTES = MAX_DISPLAY_CHARS * 4

    /** Reads only the end of the log, so a large file cannot fill the app's heap. */
    fun read(file: File): String? = RandomAccessFile(file, "r").use { log ->
        val length = log.length()
        if (length == 0L) return null

        val start = (length - MAX_READ_BYTES).coerceAtLeast(0L)
        val bytes = ByteArray((length - start).toInt())
        log.seek(start)
        log.readFully(bytes)

        val text = String(bytes, Charsets.UTF_8)
        val shortened = start > 0L || text.length > MAX_DISPLAY_CHARS
        val preview = text.takeLast(MAX_DISPLAY_CHARS)
        if (preview.isBlank()) null else if (shortened) "...$preview" else preview
    }
}
