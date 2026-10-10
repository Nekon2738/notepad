package com.example.notepad

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun newId(): String = UUID.randomUUID().toString()

sealed class Block {
    abstract val id: String

    data class Text(
        override val id: String = newId(),
        val text: String = "",
        val heading: Boolean = false
    ) : Block()

    data class Image(
        override val id: String = newId(),
        val file: String
    ) : Block()

    data class Audio(
        override val id: String = newId(),
        val file: String,
        val name: String
    ) : Block()
}

data class Note(
    val id: String = newId(),
    val title: String = "",
    val blocks: List<Block> = listOf(Block.Text()),
    val updated: Long = System.currentTimeMillis()
)

fun Note.isEmpty(): Boolean =
    title.isBlank() && blocks.all { it is Block.Text && it.text.isBlank() }

fun Note.toJson(): JSONObject {
    val arr = JSONArray()
    for (b in blocks) {
        val o = JSONObject()
        when (b) {
            is Block.Text -> o.put("t", "text").put("text", b.text).put("h", b.heading)
            is Block.Image -> o.put("t", "img").put("file", b.file)
            is Block.Audio -> o.put("t", "aud").put("file", b.file).put("name", b.name)
        }
        arr.put(o)
    }
    return JSONObject().put("id", id).put("title", title).put("updated", updated).put("blocks", arr)
}

fun noteFromJson(o: JSONObject): Note {
    val a = o.getJSONArray("blocks")
    val list = ArrayList<Block>()
    for (i in 0 until a.length()) {
        val j = a.getJSONObject(i)
        list.add(
            when (j.getString("t")) {
                "img" -> Block.Image(file = j.getString("file"))
                "aud" -> Block.Audio(file = j.getString("file"), name = j.optString("name"))
                else -> Block.Text(text = j.optString("text"), heading = j.optBoolean("h"))
            }
        )
    }
    if (list.isEmpty()) list.add(Block.Text())
    return Note(o.getString("id"), o.optString("title"), list, o.optLong("updated"))
}

// ---------- export ----------

fun htmlEsc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

fun Note.toPlain(): String {
    val parts = ArrayList<String>()
    parts.add(title)
    for (b in blocks) {
        parts.add(
            when (b) {
                is Block.Text -> b.text
                is Block.Image -> "[фото]"
                is Block.Audio -> "[аудио: ${b.name}]"
            }
        )
    }
    return parts.filter { it.isNotBlank() }.joinToString("\n\n")
}

fun Note.toMarkdown(): String {
    val body = blocks.joinToString("\n\n") {
        when (it) {
            is Block.Text -> if (it.heading) "## " + it.text else it.text
            is Block.Image -> "[фото]"
            is Block.Audio -> "[аудио: ${it.name}]"
        }
    }
    return "# " + title + "\n\n" + body + "\n"
}

fun Note.toHtml(media: File): String {
    val sb = StringBuilder()
    for (b in blocks) {
        when (b) {
            is Block.Text -> {
                val t = htmlEsc(b.text).replace("\n", "<br>")
                sb.append(if (b.heading) "<h2>$t</h2>\n" else "<p>$t</p>\n")
            }
            is Block.Image -> {
                val f = File(media, b.file)
                if (f.exists()) {
                    val data = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                    sb.append("<img style=\"max-width:100%\" src=\"data:image/jpeg;base64,$data\">\n")
                }
            }
            is Block.Audio -> sb.append("<p>[аудио: ${htmlEsc(b.name)}]</p>\n")
        }
    }
    return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + htmlEsc(title) +
        "</title></head><body style=\"font-family:sans-serif;max-width:720px;margin:2em auto;line-height:1.7\"><h1>" +
        htmlEsc(title) + "</h1>\n" + sb.toString() + "</body></html>"
}

fun docxBytes(text: String): ByteArray {
    val paras = text.split("\n").joinToString("") {
        "<w:p><w:r><w:t xml:space=\"preserve\">" + htmlEsc(it) + "</w:t></w:r></w:p>"
    }
    val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    val bo = ByteArrayOutputStream()
    ZipOutputStream(bo).use { z ->
        fun put(n: String, s: String) {
            z.putNextEntry(ZipEntry(n))
            z.write(s.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        put(
            "[Content_Types].xml",
            head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"
        )
        put(
            "_rels/.rels",
            head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
        )
        put(
            "word/document.xml",
            head + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                paras + "</w:body></w:document>"
        )
    }
    return bo.toByteArray()
}

// ---------- storage ----------

class Repo(private val ctx: Context) {
    val notesDir = File(ctx.filesDir, "notes").apply { mkdirs() }
    val mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }
    val fontsDir = File(ctx.filesDir, "fonts").apply { mkdirs() }

    fun loadAll(): List<Note> =
        (notesDir.listFiles() ?: emptyArray())
            .mapNotNull { f -> runCatching { noteFromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.updated }

    fun save(n: Note) {
        File(notesDir, n.id + ".json").writeText(n.toJson().toString())
    }

    fun delete(n: Note) {
        File(notesDir, n.id + ".json").delete()
        for (b in n.blocks) {
            when (b) {
                is Block.Image -> File(mediaDir, b.file).delete()
                is Block.Audio -> File(mediaDir, b.file).delete()
                else -> {}
            }
        }
    }

    fun displayName(uri: Uri): String {
        val c = ctx.contentResolver.query(uri, null, null, null, null)
        c?.use {
            val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && i >= 0) return it.getString(i) ?: "file"
        }
        return "file"
    }

    fun copyMedia(uri: Uri, ext: String): String {
        val e = ext.filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
        val name = newId() + "." + e
        ctx.contentResolver.openInputStream(uri)?.use { i ->
            File(mediaDir, name).outputStream().use { o -> i.copyTo(o) }
        }
        return name
    }

    // ----- import (txt, md, html, docx) -----

    private fun readString(uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    private fun htmlText(h: String): String =
        h.replace(Regex("(?is)<(script|style).*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h\\d>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&")
            .trim()

    private fun docxText(uri: Uri): String {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val z = ZipInputStream(ins)
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    val xml = z.readBytes().toString(Charsets.UTF_8)
                    return xml.replace("</w:p>", "\n").replace(Regex("<[^>]+>"), "")
                        .replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&amp;", "&").trim()
                }
                e = z.nextEntry
            }
        }
        return ""
    }

    /** returns title to text, or null when the format is not supported */
    fun readImported(uri: Uri): Pair<String, String>? {
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = name.substringBeforeLast('.')
        val text = when (ext) {
            "docx" -> docxText(uri)
            "html", "htm" -> htmlText(readString(uri))
            "txt", "md", "markdown", "csv", "log", "json" -> readString(uri)
            else -> return null
        }
        return base to text
    }

    // ----- fonts -----

    fun fonts(): List<File> =
        (fontsDir.listFiles() ?: emptyArray())
            .filter { it.extension.lowercase() == "ttf" || it.extension.lowercase() == "otf" }
            .sortedBy { it.name }

    fun fontFile(key: String): File? = File(fontsDir, key).takeIf { it.exists() }

    fun saveFontStream(name: String, ins: InputStream): Int {
        val ext = name.substringAfterLast('.', "").lowercase()
        var n = 0
        if (ext == "zip") {
            val z = ZipInputStream(ins)
            var e = z.nextEntry
            while (e != null) {
                val en = e.name.substringAfterLast('/')
                val ex = en.substringAfterLast('.', "").lowercase()
                if (!e.isDirectory && (ex == "ttf" || ex == "otf") && !en.contains("Italic")) {
                    File(fontsDir, en).outputStream().use { z.copyTo(it) }
                    n++
                }
                e = z.nextEntry
            }
        } else if (ext == "ttf" || ext == "otf") {
            File(fontsDir, name).outputStream().use { ins.copyTo(it) }
            n = 1
        }
        return n
    }

    fun addFontFromUri(uri: Uri): Int {
        val name = displayName(uri)
        return ctx.contentResolver.openInputStream(uri)?.use { saveFontStream(name, it) } ?: 0
    }
}
