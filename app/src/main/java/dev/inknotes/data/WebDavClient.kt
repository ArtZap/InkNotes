package dev.inknotes.data

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The sync's cloud folder on a WebDAV server: the folder [WebDavAccount.folder] under the
 * account's address, made the first time. File ids are the files' full URLs. A notebook goes
 * up under a new name (its time is in it) and the old file is then removed, so an upload cut
 * off halfway leaves the old one whole; two files of one notebook are sorted out by the sync
 * (the latest counts). Removing a file there is a DELETE: Yandex Disk and Nextcloud keep it
 * in their own trash.
 */
class WebDavClient(private val account: WebDavAccount, private val http: OkHttpClient = DEFAULT) : DriveApi {

    private val auth = Credentials.basic(account.user, account.password, Charsets.UTF_8)

    private fun request(url: String) = Request.Builder().url(url).header("Authorization", auth)

    private fun call(r: Request): Response = http.newCall(r).execute()

    private fun fail(what: String, code: Int): Nothing = throw DriveError("webdav $what: HTTP $code", code, webdav = true)

    private val folderUrl get() = account.base + WebDavListing.encode(account.folder) + "/"

    override fun folder(): String {
        val url = folderUrl
        if (exists(url)) return url
        call(request(url).method("MKCOL", null).build()).use { r ->
            // 405: already there (made in the meantime).
            if (!r.isSuccessful && r.code != 405) fail("MKCOL", r.code)
        }
        return url
    }

    override fun exists(folderId: String): Boolean =
        call(propfind(folderId, depth = 0)).use { r ->
            when {
                r.code == 207 || r.isSuccessful -> true
                r.code == 404 -> false
                else -> fail("PROPFIND", r.code)
            }
        }

    override fun list(folderId: String): List<DriveFile> {
        val xml = call(propfind(folderId, depth = 1)).use { r ->
            if (r.code != 207 && !r.isSuccessful) fail("PROPFIND", r.code)
            r.body?.string().orEmpty()
        }
        // Someone else's file with an address that does not parse is passed over, not a failed sync.
        return WebDavListing.parse(xml).filter { !it.folder }.mapNotNull { e ->
            val name = runCatching { WebDavListing.nameOf(e.href) }.getOrNull() ?: return@mapNotNull null
            val (id, time) = WebDavNames.parse(name) ?: return@mapNotNull null
            // Only a file on this very server: the password is sent with every request.
            val url = runCatching { WebDavListing.absolute(folderId, e.href) }.getOrNull() ?: return@mapNotNull null
            DriveFile(url, name, time, mapOf(DriveSync.PROP_ID to id, DriveSync.PROP_TIME to time.toString()))
        }
    }

    override fun upload(folderId: String, fileId: String?, name: String, props: Map<String, String>, from: File): DriveFile {
        val id = props[DriveSync.PROP_ID] ?: error("no notebook id")
        val time = props[DriveSync.PROP_TIME]?.toLongOrNull() ?: System.currentTimeMillis()
        val fileName = WebDavNames.name(name.removeSuffix(".inknote"), id, time)
        val url = folderId + WebDavListing.encode(fileName)
        call(request(url).put(from.asRequestBody(OCTET)).build()).use { r -> if (!r.isSuccessful) fail("PUT", r.code) }
        // The old file of this notebook goes only once the new one is there. Not removed (the
        // server failed): the new one is up all the same; two files of one notebook are sorted
        // out by the sync, the latest counts, and the old one goes next time.
        if (fileId != null && fileId != url) runCatching { trash(fileId) }
        return DriveFile(url, fileName, time, props)
    }

    override fun download(fileId: String, to: File) {
        call(request(fileId).get().build()).use { r ->
            if (!r.isSuccessful) fail("GET", r.code)
            val body = r.body ?: fail("GET", r.code)
            to.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    override fun trash(fileId: String) {
        call(request(fileId).delete().build()).use { r ->
            // Already gone: nothing to do.
            if (!r.isSuccessful && r.code != 404) fail("DELETE", r.code)
        }
    }

    private fun propfind(url: String, depth: Int): Request =
        request(url).header("Depth", depth.toString()).method("PROPFIND", PROPS.toRequestBody(XML)).build()

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val PROPS = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getlastmodified/></d:prop></d:propfind>"""

        val DEFAULT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        /** For the form: signs in and makes (or finds) the folder; the error in words if not. */
        fun check(account: WebDavAccount): String? = runCatching { WebDavClient(account).folder() }.fold({ null }, { e ->
            when (e) {
                is DriveError -> e.friendly()
                is java.net.UnknownHostException -> tr("Сервер не найден — проверьте адрес", "Server not found — check the address")
                is IllegalArgumentException -> tr("Неверный адрес", "Invalid address")
                else -> e.message?.take(200) ?: tr("Не удалось подключиться", "Could not connect")
            }
        })
    }
}
