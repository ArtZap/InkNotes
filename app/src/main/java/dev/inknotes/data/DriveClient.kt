package dev.inknotes.data

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder

/** A file of the cloud folder: [props] are the app's own properties (notebook id, its time). */
data class DriveFile(val id: String, val name: String, val version: Long, val props: Map<String, String>)

/** The cloud folder, as the sync needs it (a fake one in the tests). */
interface DriveApi {
    /** The app's folder ("InkNotes" at the top of the Drive), made the first time. */
    fun folder(): String

    /** Folder [folderId] is still there (not deleted, not in the trash), wherever it was moved. */
    fun exists(folderId: String): Boolean

    fun list(folderId: String): List<DriveFile>

    /** [fileId] null: a new file in [folderId]; else that file gets new contents. */
    fun upload(folderId: String, fileId: String?, name: String, props: Map<String, String>, from: File): DriveFile

    fun download(fileId: String, to: File)

    /** To the Drive's trash (it can be brought back there for 30 days). */
    fun trash(fileId: String)
}

class DriveError(message: String, val code: Int = 0) : IOException(message) {
    /** What went wrong in words for the user, not Google's answer. */
    fun friendly(): String {
        val m = message.orEmpty()
        return when {
            m.contains("storageQuotaExceeded") || m.contains("quotaExceeded") && code == 403 -> tr("На Google Диске закончилось место", "Your Google Drive is full")
            code == 429 || m.contains("rateLimitExceeded") -> tr("Google Диск просит подождать: синхронизация продолжится позже", "Google Drive asks to wait: syncing will continue later")
            code in 500..599 -> tr("Google Диск сейчас недоступен, синхронизация продолжится позже", "Google Drive is unavailable right now, syncing will continue later")
            code == 401 || code == 403 -> tr("Нет доступа к Google Диску — войдите снова", "No access to Google Drive — sign in again")
            code == 404 -> tr("Файл на Google Диске не найден — синхронизация продолжится в следующий раз", "A file on Google Drive was not found — syncing will continue next time")
            code != 0 -> tr("Ошибка Google Диска (код $code)", "Google Drive error (code $code)")
            else -> m.take(200)
        }
    }
}

/**
 * Google Drive over its REST API (plain HTTPS, no Google services needed). [token] gives an
 * access token; [refresh] is asked for a new one when Google says the old one expired.
 */
class DriveClient(private val token: () -> String, private val refresh: () -> String) : DriveApi {
    private var access: String? = null

    private fun <T> authorized(call: (String) -> T): T = retrying {
        val t = access ?: token().also { access = it }
        try {
            call(t)
        } catch (e: DriveError) {
            if (e.code != 401) throw e
            val fresh = refresh().also { access = it }
            call(fresh)
        }
    }

    /**
     * [call] again after a pause when Google asks to slow down (429), stumbles (5xx) or the
     * connection breaks: up to [TRIES] times, waiting 1, 2, 4 s. Anything else fails at once.
     */
    private fun <T> retrying(call: () -> T): T {
        var wait = 1000L
        repeat(TRIES - 1) {
            try {
                return call()
            } catch (e: DriveError) {
                if (e.code != 429 && e.code !in 500..599) throw e
            } catch (e: java.net.SocketTimeoutException) {
                // retried
            } catch (e: java.net.SocketException) {
                // retried
            }
            Thread.sleep(wait)
            wait *= 2
        }
        return call()
    }

    private fun open(url: String, method: String, auth: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (method == "PATCH") "POST" else method
            // HttpURLConnection knows no PATCH: Google takes it in a header.
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            setRequestProperty("Authorization", "Bearer $auth")
            connectTimeout = 20_000
            readTimeout = 60_000
        }

    private fun HttpURLConnection.answer(): String {
        val code = responseCode
        val body = (if (code in 200..299) inputStream else errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        if (code !in 200..299) throw DriveError("Google Drive: $code ${body.take(300)}", code)
        return body
    }

    private fun json(conn: HttpURLConnection, body: JSONObject) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    override fun folder(): String = authorized { t ->
        val query = "name = '$FOLDER' and mimeType = '$FOLDER_MIME' and trashed = false and 'root' in parents"
        val found = JSONObject(open("$API/files?q=${q(query)}&fields=files(id)&spaces=drive", "GET", t).answer()).optJSONArray("files")
        found?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() } ?: run {
            val conn = open("$API/files?fields=id", "POST", t)
            json(conn, JSONObject().put("name", FOLDER).put("mimeType", FOLDER_MIME))
            JSONObject(conn.answer()).getString("id")
        }
    }

    override fun exists(folderId: String): Boolean = authorized { t ->
        try {
            val o = JSONObject(open("$API/files/${q(folderId)}?fields=${q("id,trashed,mimeType")}", "GET", t).answer())
            !o.optBoolean("trashed") && o.optString("mimeType") == FOLDER_MIME
        } catch (e: DriveError) {
            // Gone for good (a 403 may be a rate limit: not taken for gone).
            if (e.code == 404) false else throw e
        }
    }

    override fun list(folderId: String): List<DriveFile> = authorized { t ->
        val out = ArrayList<DriveFile>()
        var page: String? = null
        do {
            val query = "'$folderId' in parents and trashed = false"
            val url = "$API/files?q=${q(query)}&fields=${q("nextPageToken,files(id,name,version,appProperties)")}&pageSize=1000&spaces=drive" +
                (page?.let { "&pageToken=${q(it)}" } ?: "")
            val json = JSONObject(open(url, "GET", t).answer())
            val files = json.optJSONArray("files")
            for (i in 0 until (files?.length() ?: 0)) out += file(files!!.getJSONObject(i))
            page = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null)
        out
    }

    private fun file(o: JSONObject): DriveFile {
        val props = o.optJSONObject("appProperties")
        return DriveFile(
            o.getString("id"),
            o.optString("name"),
            o.optString("version").toLongOrNull() ?: 0L,
            props?.keys()?.asSequence()?.associateWith { props.optString(it) }.orEmpty(),
        )
    }

    /** A resumable upload: any size, and nothing changes on the Drive until all of it is there. */
    override fun upload(folderId: String, fileId: String?, name: String, props: Map<String, String>, from: File): DriveFile = authorized { t ->
        val meta = JSONObject().put("name", name).put("appProperties", JSONObject(props))
        val start = if (fileId == null) {
            meta.put("parents", org.json.JSONArray().put(folderId))
            open("$UPLOAD/files?uploadType=resumable&fields=${q(FIELDS)}", "POST", t)
        } else {
            open("$UPLOAD/files/$fileId?uploadType=resumable&fields=${q(FIELDS)}", "PATCH", t)
        }
        start.setRequestProperty("X-Upload-Content-Type", MIME)
        start.setRequestProperty("X-Upload-Content-Length", from.length().toString())
        json(start, meta)
        start.answer()
        val session = start.getHeaderField("Location") ?: throw DriveError("Google Drive: no upload address")
        val put = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setFixedLengthStreamingMode(from.length())
            setRequestProperty("Content-Type", MIME)
            connectTimeout = 20_000
            readTimeout = 120_000
        }
        put.outputStream.use { out -> from.inputStream().use { it.copyTo(out) } }
        file(JSONObject(put.answer()))
    }

    override fun download(fileId: String, to: File): Unit = authorized { t ->
        val conn = open("$API/files/$fileId?alt=media", "GET", t)
        if (conn.responseCode !in 200..299) conn.answer()
        conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        Unit
    }

    override fun trash(fileId: String): Unit = authorized { t ->
        val conn = open("$API/files/$fileId?fields=id", "PATCH", t)
        json(conn, JSONObject().put("trashed", true))
        conn.answer()
        Unit
    }

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER = "InkNotes"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val MIME = "application/octet-stream"
        const val FIELDS = "id,name,version,appProperties"
        private const val TRIES = 4

        /** Tokens of a sign-in: [refresh] lasts, [access] for about an hour. */
        class Tokens(val access: String, val refresh: String?, val email: String?, val expiresIn: Long)

        private fun post(url: String, params: Map<String, String>): JSONObject {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(GoogleAuth.form(params).toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw DriveError("Google: $code ${body.take(300)}", code)
            return JSONObject(body)
        }

        private fun tokens(o: JSONObject) = Tokens(
            o.getString("access_token"),
            o.optString("refresh_token").ifEmpty { null },
            GoogleAuth.emailOf(o.optString("id_token").ifEmpty { null }),
            o.optLong("expires_in", 3600),
        )

        fun exchange(clientId: String, secret: String, code: String, redirect: String, verifier: String) = tokens(
            post(
                GoogleAuth.TOKEN_URL,
                mapOf("code" to code, "client_id" to clientId, "client_secret" to secret, "redirect_uri" to redirect, "grant_type" to "authorization_code", "code_verifier" to verifier),
            ),
        )

        fun refresh(clientId: String, secret: String, refreshToken: String) = tokens(
            post(GoogleAuth.TOKEN_URL, mapOf("refresh_token" to refreshToken, "client_id" to clientId, "client_secret" to secret, "grant_type" to "refresh_token")),
        )

        /**
         * The loopback server for one sign-in: [redirect] goes to Google; [await] waits (off the
         * main thread) until the browser comes back with the answer, and tells the browser to
         * go back to the app.
         */
        class Loopback : AutoCloseable {
            private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5 * 60 * 1000 }
            val redirect = "http://127.0.0.1:${server.localPort}"

            fun await(state: String): GoogleAuth.Reply {
                while (true) {
                    val socket = server.accept()
                    socket.use { s ->
                        // Browsers open spare connections ahead and may send nothing on them:
                        // such a one is dropped after a few seconds instead of blocking the
                        // one that brings the answer.
                        s.soTimeout = 5_000
                        val line = try {
                            s.getInputStream().bufferedReader().readLine().orEmpty()
                        } catch (e: java.net.SocketTimeoutException) {
                            return@use
                        }
                        val reply = GoogleAuth.parse(line, state)
                        val ok = reply is GoogleAuth.Reply.Code
                        val page = if (reply == GoogleAuth.Reply.Other) "" else
                            "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
                                "<body style='font:18px sans-serif;padding:32px;text-align:center'>" +
                                (if (ok) tr("<h2>Готово</h2><p>InkNotes подключён к Google Диску.</p>", "<h2>Done</h2><p>InkNotes is connected to Google Drive.</p>") else tr("<h2>Вход не выполнен</h2>", "<h2>Sign-in failed</h2>")) +
                                tr("<p><a href='inknotes://sync' style='font-size:20px'>Вернуться в InkNotes</a></p></body>", "<p><a href='inknotes://sync' style='font-size:20px'>Back to InkNotes</a></p></body>")
                        val status = if (reply == GoogleAuth.Reply.Other) "404 Not Found" else "200 OK"
                        val bytes = page.toByteArray()
                        s.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                        if (reply != GoogleAuth.Reply.Other) return reply
                    }
                }
            }

            override fun close() = runCatching { server.close() }.let { }
        }
    }
}
