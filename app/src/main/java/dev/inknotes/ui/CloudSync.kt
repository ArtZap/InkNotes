package dev.inknotes.ui

import dev.inknotes.data.tr
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.inknotes.BuildConfig
import dev.inknotes.data.DriveClient
import dev.inknotes.data.DriveSync
import dev.inknotes.data.GoogleAuth
import dev.inknotes.data.NotebookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Google Drive for the whole app: signing in (in the browser), and syncing the library with
 * the Drive's "InkNotes" folder, by hand or when the app is opened and left.
 */
class CloudSync(private val context: Context, private val repo: NotebookRepository, private val prefs: AppPrefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientId = BuildConfig.GOOGLE_CLIENT_ID
    private val secret = BuildConfig.GOOGLE_CLIENT_SECRET

    /** The app was built with a Google client (local.properties): sync can be offered. */
    val configured get() = clientId.isNotBlank()

    var email by mutableStateOf(prefs.driveEmail)
        private set
    var signedIn by mutableStateOf(prefs.driveRefreshToken != null)
        private set
    var running by mutableStateOf(false)
        private set
    var signingIn by mutableStateOf(false)
        private set

    /** The last outcome, for the dialog: what was done, or what went wrong. */
    var status by mutableStateOf(prefs.driveLastResult)
        private set
    var lastSync by mutableStateOf(prefs.driveLastSync)
        private set

    private var loopback: DriveClient.Companion.Loopback? = null

    /** Counts sign-ins: a sign-in given up for a newer one changes nothing when it ends. */
    private var attempt = 0

    /**
     * Notebooks deleted on the Drive and not put in the trash here, because so many went at once
     * (id and title): the dialog asks what to do with them.
     */
    var held by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    /** Opens Google's page in the browser; the answer comes back to the app by itself. */
    fun signIn(from: Context) {
        if (!configured) return
        // Pressed again while waiting (the browser closed, the page lost): that sign-in is given
        // up and a new one starts, instead of waiting out the old one's minutes.
        loopback?.close()
        val mine = ++attempt
        val server = DriveClient.Companion.Loopback().also { loopback = it }
        val verifier = GoogleAuth.verifier()
        val state = GoogleAuth.state()
        val url = GoogleAuth.authUrl(clientId, server.redirect, GoogleAuth.challenge(verifier), state)
        val opened = runCatching { from.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!opened) {
            server.close()
            status = tr("Нет браузера, чтобы войти в Google", "No browser to sign in to Google")
            return
        }
        signingIn = true
        status = tr("Войдите в Google в браузере и вернитесь сюда", "Sign in to Google in the browser and come back here")
        scope.launch {
            val result = runCatching {
                when (val reply = server.await(state)) {
                    is GoogleAuth.Reply.Code -> DriveClient.exchange(clientId, secret, reply.code, server.redirect, verifier)
                    is GoogleAuth.Reply.Error -> error(if (reply.error == "access_denied") tr("Доступ не разрешён", "Access was not allowed") else tr("Google ответил: ${reply.error}", "Google replied: ${reply.error}"))
                    GoogleAuth.Reply.Other -> error(tr("Вход не выполнен", "Sign-in failed"))
                }
            }
            server.close()
            main {
                if (mine != attempt) return@main
                signingIn = false
                result.onSuccess { t ->
                    val refresh = t.refresh
                    if (refresh == null) {
                        status = tr("Google не дал постоянного ключа. Отзовите доступ InkNotes в аккаунте Google и войдите снова", "Google did not give a lasting key. Remove InkNotes' access in your Google account and sign in again")
                    } else {
                        // Another account: what was synced with the old one does not count.
                        // What was synced is kept per account: the same account again compares as
                        // before (no copies), another one starts from its own. An account whose
                        // address Google did not give can't be told apart: it starts afresh.
                        if (t.email == null) stateFile(null).delete()
                        // The old one-file state goes to the account it belongs to, not this one.
                        prefs.driveEmail?.let { stateFile(it) }
                        prefs.driveRefreshToken = refresh
                        prefs.driveEmail = t.email
                        email = t.email
                        signedIn = true
                        status = tr("Вход выполнен", "Signed in") + (t.email?.let { ": $it" } ?: "")
                    }
                }.onFailure { status = signInError(it) }
            }
            if (mine == attempt && result.isSuccess && prefs.driveRefreshToken != null) sync()
        }
    }

    private fun signInError(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> tr("Время входа вышло. Нажмите «Войти через Google» ещё раз", "Sign-in timed out. Press “Sign in with Google” again")
        is java.net.SocketException -> tr("Вход прерван", "Sign-in interrupted")
        else -> e.message?.takeIf { it.isNotBlank() } ?: tr("Вход не выполнен", "Sign-in failed")
    }

    fun signOut() {
        attempt++
        loopback?.close()
        signingIn = false
        held = emptyList()
        prefs.driveRefreshToken = null
        // What was synced stays (per account): signing in again does not turn changes into copies.
        stateFile()
        prefs.driveEmail = null
        signedIn = false
        email = null
        status = tr("Вы вышли. Тетради на Диске остались", "Signed out. Notebooks on the Drive stay there")
    }

    /**
     * What was synced with the Drive of [email] (a file per account). The one file of versions
     * before 0.39 belongs to the account signed in when it is first needed.
     */
    private fun stateFile(email: String? = prefs.driveEmail): File {
        val legacy = File(context.filesDir, "drive-sync.json")
        if (email == null) return legacy
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(email.trim().lowercase().toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val own = File(context.filesDir, "drive-sync-$hash.json")
        if (!own.exists() && legacy.isFile) legacy.renameTo(own)
        return own
    }

    private fun unmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Who has notebooks open in an editor now (the app, a quick note): asked by the sync before
     * every step that would replace or remove one. Weakly held: a closed screen is not kept
     * in memory by this process-wide object.
     */
    private val openers = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<() -> Set<String>>>()

    /** [key]'s open notebooks are [ids]; registered again with the same key replaces it. */
    fun watchOpen(key: String, ids: () -> Set<String>) {
        openers[key] = java.lang.ref.WeakReference(ids)
    }

    fun unwatchOpen(key: String) {
        openers.remove(key)
    }

    fun openNow(): Set<String> = openers.values.flatMap { ref -> ref.get()?.let { runCatching { it() }.getOrNull() }.orEmpty() }.toSet()

    /**
     * When the app is opened or left: a sync if it is on, and not more often than every couple
     * of minutes. [busy]: notebooks open in the editor (already saved).
     */
    fun autoSync(busy: Set<String>) {
        if (!configured || !signedIn || !prefs.driveAuto || running) return
        if (System.currentTimeMillis() - prefs.driveLastSync < AUTO_EVERY_MS) return
        if (!online() || (prefs.driveWifiOnly && !unmetered())) return
        sync(busy)
    }

    /**
     * Notebooks [held] back go to the trash here after all (the user deleted them on purpose),
     * or stay here and go up to the Drive again ([trash] false).
     */
    fun resolveHeld(trash: Boolean) {
        val ids = held.map { it.first }
        held = emptyList()
        if (trash) {
            sync(trashMany = true)
        } else {
            DriveSync(repo, NoDrive, stateFile(), context.cacheDir).keepHere(ids)
            sync()
        }
    }

    /** For [DriveSync.keepHere], which only edits what is remembered. */
    private object NoDrive : dev.inknotes.data.DriveApi {
        override fun folder() = error("offline")
        override fun exists(folderId: String) = error("offline")
        override fun list(folderId: String) = error("offline")
        override fun upload(folderId: String, fileId: String?, name: String, props: Map<String, String>, from: File) = error("offline")
        override fun download(fileId: String, to: File) = error("offline")
        override fun trash(fileId: String) = error("offline")
    }

    /** [busy]: notebooks open now besides those registered with [watchOpen]. */
    fun sync(busy: Set<String> = emptySet(), trashMany: Boolean = false) {
        if (!configured || !signedIn) return
        if (!DriveSync.running.compareAndSet(false, true)) return
        running = true
        status = tr("Синхронизация…", "Syncing…")
        scope.launch {
            val result = runCatching {
                if (!online()) error(tr("Нет интернета", "No internet"))
                val refresh = prefs.driveRefreshToken ?: error(tr("Войдите в Google", "Sign in to Google"))
                val token = { DriveClient.refresh(clientId, secret, refresh).access }
                val api = DriveClient(token, token)
                DriveSync(repo, api, stateFile(), context.cacheDir).run({ busy + openNow() }, trashMany)
            }
            DriveSync.running.set(false)
            val now = System.currentTimeMillis()
            val text = result.fold(
                { tr("Синхронизировано: $it", "Synced: $it") },
                { e ->
                    val m = e.message.orEmpty()
                    when {
                        // The sign-in was taken back (or ran out): sign in again.
                        m.contains("invalid_grant") -> tr("Вход в Google больше не действует — войдите снова", "The Google sign-in has expired — sign in again")
                        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException -> tr("Нет связи с Google Диском", "Cannot reach Google Drive")
                        e is dev.inknotes.data.DriveError -> e.friendly()
                        else -> tr("Не удалось: ${m.take(200)}", "Failed: ${m.take(200)}")
                    }
                },
            )
            if (result.isSuccess) prefs.driveLastSync = now
            prefs.driveLastResult = text
            if (result.exceptionOrNull()?.message?.contains("invalid_grant") == true) {
                prefs.driveRefreshToken = null
            }
            main {
                running = false
                status = text
                result.getOrNull()?.let { held = it.held }
                lastSync = prefs.driveLastSync
                signedIn = prefs.driveRefreshToken != null
            }
            result.getOrNull()?.let { if (!it.nothing) repo.refreshLibrary() }
        }
    }

    companion object {
        const val AUTO_EVERY_MS = 2 * 60 * 1000L
    }
}
