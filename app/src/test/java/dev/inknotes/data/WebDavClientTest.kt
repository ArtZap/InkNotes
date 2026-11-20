package dev.inknotes.data

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64

/**
 * The WebDAV client against a small WebDAV server in memory: a folder made once, notebooks
 * sent under names that carry them, the old file removed after the new one is there, files
 * listed, fetched and deleted; a wrong password answered in words.
 */
class WebDavClientTest {
    private lateinit var server: HttpServer
    private val files = LinkedHashMap<String, ByteArray>()
    private val folders = HashSet<String>()
    private val password = "secret"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = URLDecoder.decode(ex.requestURI.rawPath.replace("+", "%2B"), "UTF-8")
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            val ok = auth == "Basic " + Base64.getEncoder().encodeToString("me:$password".toByteArray())
            fun reply(code: Int, body: ByteArray = ByteArray(0)) {
                ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
            }
            if (!ok) return@createContext reply(401)
            when (ex.requestMethod) {
                "PROPFIND" -> {
                    val dir = path.trimEnd('/') + "/"
                    if (dir !in folders) return@createContext reply(404)
                    val depth = ex.requestHeaders.getFirst("Depth")
                    val rows = StringBuilder("<d:response><d:href>$dir</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>")
                    if (depth == "1") for (f in files.keys.filter { it.startsWith(dir) }) {
                        val href = dir + java.net.URLEncoder.encode(f.removePrefix(dir), "UTF-8").replace("+", "%20")
                        rows.append("<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>")
                    }
                    reply(207, "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">$rows</d:multistatus>".toByteArray())
                }
                "MKCOL" -> {
                    folders += path.trimEnd('/') + "/"
                    reply(201)
                }
                "PUT" -> {
                    files[path] = ex.requestBody.readBytes()
                    reply(201)
                }
                "GET" -> files[path]?.let { reply(200, it) } ?: reply(404)
                "DELETE" -> if (files.remove(path) != null) reply(204) else reply(404)
                else -> reply(405)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun account(pass: String = password) = WebDavAccount("http://127.0.0.1:${server.address.port}/dav", "me", pass)

    @Test
    fun aNotebookGoesUpComesDownAndGoes() {
        val c = WebDavClient(account())
        val folder = c.folder()
        assertTrue(folder.endsWith("/dav/InkNotes/"))
        assertEquals("made once", folder, c.folder())
        assertTrue(c.exists(folder))
        val id = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
        val tmp = File.createTempFile("note", ".inknote").apply { writeText("first") }
        val up = c.upload(folder, null, "Матан.inknote", mapOf(DriveSync.PROP_ID to id, DriveSync.PROP_TIME to "100"), tmp)
        assertEquals(1, c.list(folder).size)
        // Changed: up under a new name, the old file gone.
        tmp.writeText("second")
        val again = c.upload(folder, up.id, "Матан.inknote", mapOf(DriveSync.PROP_ID to id, DriveSync.PROP_TIME to "200"), tmp)
        val listed = c.list(folder)
        assertEquals(1, listed.size)
        assertEquals(again.id, listed[0].id)
        assertEquals(200L, listed[0].version)
        assertEquals(id, listed[0].props[DriveSync.PROP_ID])
        val down = File.createTempFile("down", ".inknote")
        c.download(listed[0].id, down)
        assertEquals("second", down.readText())
        c.trash(listed[0].id)
        assertTrue(c.list(folder).isEmpty())
        // Not the app's: left alone.
        files["/dav/InkNotes/photo.jpg"] = ByteArray(3)
        assertTrue(c.list(folder).isEmpty())
    }

    @Test
    fun aWrongPasswordIsSaidInWords() {
        val e = runCatching { WebDavClient(account("wrong")).folder() }.exceptionOrNull()
        assertTrue(e is DriveError && e.code == 401)
        assertTrue((e as DriveError).friendly().contains("парол") || e.friendly().contains("password"))
        assertFalse(WebDavClient.check(account("wrong")).isNullOrEmpty())
        assertEquals(null, WebDavClient.check(account()))
    }
}
