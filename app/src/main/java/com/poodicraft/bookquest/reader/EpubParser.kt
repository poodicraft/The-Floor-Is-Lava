package com.poodicraft.bookquest.reader

import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Result of flattening an EPUB into one long HTML document. */
data class EpubContent(val title: String, val html: String)

/**
 * A deliberately small EPUB reader: it walks the spine and concatenates the
 * chapter bodies. Images and stylesheets are dropped so nothing has to be
 * resolved from disk while reading.
 */
object EpubParser {

    private const val MAX_CHARS = 3_000_000

    fun parse(file: File): EpubContent? {
        return try {
            ZipFile(file).use { zip ->
                val names = zip.entries().toList().associateBy { it.name }
                val containerEntry = names["META-INF/container.xml"]
                    ?: names.values.firstOrNull { it.name.endsWith("container.xml") }
                    ?: return null
                val containerXml = readEntry(zip, containerEntry)
                val opfPath = Regex("full-path=\"([^\"]+)\"")
                    .find(containerXml)?.groupValues?.get(1)
                    ?: names.keys.firstOrNull { it.endsWith(".opf") }
                    ?: return null

                val opfEntry = names[opfPath] ?: return null
                val opf = readEntry(zip, opfEntry)
                val base = opfPath.substringBeforeLast('/', "")

                val title = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL)
                    .find(opf)?.groupValues?.get(1)?.let { stripTags(it).trim() }
                    .orEmpty()

                val manifest = HashMap<String, String>()
                Regex("<item\\b[^>]*/?>").findAll(opf).forEach { match ->
                    val tag = match.value
                    val id = attribute(tag, "id")
                    val href = attribute(tag, "href")
                    if (id != null && href != null) manifest[id] = href
                }

                val spine = Regex("<itemref\\b[^>]*>").findAll(opf)
                    .mapNotNull { attribute(it.value, "idref") }
                    .toList()

                val order = if (spine.isNotEmpty()) spine.mapNotNull { manifest[it] }
                else manifest.values.filter { it.endsWith(".xhtml") || it.endsWith(".html") }

                val builder = StringBuilder()
                for (href in order) {
                    if (builder.length > MAX_CHARS) break
                    val path = resolve(base, URLDecoder.decode(href, "UTF-8"))
                    val entry = names[path] ?: names[href] ?: continue
                    val chapter = readEntry(zip, entry)
                    builder.append(bodyOf(chapter))
                    builder.append("\n<hr class=\"chapter-break\"/>\n")
                }
                EpubContent(title, builder.toString())
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Cleans a standalone HTML file the same way chapters are cleaned. */
    fun cleanHtml(html: String): String = bodyOf(html)

    private fun readEntry(zip: ZipFile, entry: ZipEntry): String =
        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun attribute(tag: String, name: String): String? =
        Regex("$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            ?: Regex("$name\\s*=\\s*'([^']*)'").find(tag)?.groupValues?.get(1)

    private fun resolve(base: String, href: String): String {
        if (base.isEmpty()) return normalize(href)
        return normalize("$base/$href")
    }

    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun bodyOf(document: String): String {
        val body = Regex("<body[^>]*>(.*?)</body>", RegexOption.DOT_MATCHES_ALL)
            .find(document)?.groupValues?.get(1) ?: document
        return body
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<svg[^>]*>.*?</svg>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<image\\b[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE), "")
    }

    private fun stripTags(value: String): String = value.replace(Regex("<[^>]*>"), "")
}
