package dev.inknotes.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A WebDAV server (Yandex Disk, Nextcloud, ownCloud, one's own) as the cloud folder of the
 * sync: the same [DriveSync] and [SyncPlan] as Google Drive, over plain HTTPS. WebDAV keeps
 * no properties of its own on files, so a notebook's id and time are in its file's name
 * ([WebDavNames]); a file that is not the app's (another name) is left alone.
 */
object WebDavNames {
    private val RE = Regex("""^(.*) \[([0-9a-fA-F-]{8,64})~(\d+)]\.inknote$""")

    /** The file name of notebook [id] titled [title], changed at [time]. */
    fun name(title: String, id: String, time: Long): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\[\\]~]"), "_").trim().take(80).ifEmpty { "InkNotes" }
        return "$safe [$id~$time].inknote"
    }

    /** The notebook id and time of a file named [name], or null for a file not the app's. */
    fun parse(name: String): Pair<String, Long>? {
        val m = RE.matchEntire(name) ?: return null
        return m.groupValues[2] to (m.groupValues[3].toLongOrNull() ?: return null)
    }

    /** The title part of [name]. */
    fun title(name: String): String = RE.matchEntire(name)?.groupValues?.get(1) ?: name.removeSuffix(".inknote")
}

/** What a WebDAV server answered to PROPFIND, one entry per file or folder. */
object WebDavListing {
    /** An entry: its [href] (path as the server gives it), a folder or not. */
    class Entry(val href: String, val folder: Boolean)

    /** The entries of a 207 Multi-Status answer [xml]. */
    fun parse(xml: String): List<Entry> {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = f.newDocumentBuilder().parse(xml.byteInputStream())
        val out = ArrayList<Entry>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.getElementsByTagNameNS(DAV, "href").item(0)?.textContent?.trim() ?: continue
            val folder = r.getElementsByTagNameNS(DAV, "collection").length > 0
            out += Entry(href, folder)
        }
        return out
    }

    /** The last part of [href], decoded: the file's name. */
    fun nameOf(href: String): String = URLDecoder.decode(href.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")

    /** [href] (absolute, or a path on the server of [base]) as a full URL. */
    fun absolute(base: String, href: String): String = if (href.startsWith("http://") || href.startsWith("https://")) href else URI(base).resolve(href).toString()

    /** [name] as one part of a URL path. */
    fun encode(name: String): String = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private const val DAV = "DAV:"
}

/** Where a WebDAV folder is and who signs in. [folder]: the app's folder under [url]. */
data class WebDavAccount(val url: String, val user: String, val password: String, val folder: String = "InkNotes") {
    /** The server's address with one slash at the end. */
    val base get() = url.trim().trimEnd('/') + "/"

    /**
     * The password would go in the open: an address without https, outside the home network.
     * A server at home (192.168.…, 10.…, 172.16–31.…, localhost, ….local) may use http.
     */
    val insecure: Boolean
        get() {
            val u = url.trim().lowercase()
            if (u.startsWith("https://")) return false
            val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            val private = host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") ||
                parts.size == 4 && (parts[0] == 10 || parts[0] == 127 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31)
            return !private
        }

    /** Yandex Disk's address, Nextcloud's form of it: what to suggest in the form. */
    companion object {
        const val YANDEX = "https://webdav.yandex.ru"
        const val NEXTCLOUD_HINT = "https://cloud.example.com/remote.php/dav/files/USER"
    }
}
