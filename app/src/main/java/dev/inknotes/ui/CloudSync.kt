package dev.inknotes.ui

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

    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    /** Opens Google's page in the browser; the answer comes back to the app by itself. */
    fun signIn(from: Context) {
        if (!configured || signingIn) return
        loopback?.close()
        val server = DriveClient.Companion.Loopback().also { loopback = it }
        val verifier = GoogleAuth.verifier()
        val state = GoogleAuth.state()
        val url = GoogleAuth.authUrl(clientId, server.redirect, GoogleAuth.challenge(verifier), state)
        val opened = runCatching { from.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!opened) {
            server.close()
            status = "Нет браузера, чтобы войти в Google"
            return
        }
        signingIn = true
        status = "Войдите в Google в браузере и вернитесь сюда"
        scope.launch {
            val result = runCatching {
                when (val reply = server.await(state)) {
                    is GoogleAuth.Reply.Code -> DriveClient.exchange(clientId, secret, reply.code, server.redirect, verifier)
                    is GoogleAuth.Reply.Error -> error(if (reply.error == "access_denied") "Доступ не разрешён" else "Google ответил: ${reply.error}")
                    GoogleAuth.Reply.Other -> error("Вход не выполнен")
                }
            }
            server.close()
            main {
                signingIn = false
                result.onSuccess { t ->
                    val refresh = t.refresh
                    if (refresh == null) {
                        status = "Google не дал постоянного ключа. Отзовите доступ InkNotes в аккаунте Google и войдите снова"
                    } else {
                        // Another account: what was synced with the old one does not count.
                        if (prefs.driveEmail != null && prefs.driveEmail != t.email) stateFile().delete()
                        prefs.driveRefreshToken = refresh
                        prefs.driveEmail = t.email
                        email = t.email
                        signedIn = true
                        status = "Вход выполнен" + (t.email?.let { ": $it" } ?: "")
                    }
                }.onFailure { status = it.message ?: "Вход не выполнен" }
            }
            if (result.isSuccess && prefs.driveRefreshToken != null) sync(emptySet())
        }
    }

    fun signOut() {
        prefs.driveRefreshToken = null
        prefs.driveEmail = null
        stateFile().delete()
        signedIn = false
        email = null
        status = "Вы вышли. Тетради на Диске остались"
    }

    private fun stateFile() = File(context.filesDir, "drive-sync.json")

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
     * When the app is opened or left: a sync if it is on, and not more often than every couple
     * of minutes. [busy]: notebooks open in the editor (already saved).
     */
    /** Notebooks open in the editor now (set by the activity): not replaced under it. */
    var openNow: () -> Set<String> = { emptySet() }

    fun autoSync(busy: Set<String>) {
        if (!configured || !signedIn || !prefs.driveAuto || running) return
        if (System.currentTimeMillis() - prefs.driveLastSync < AUTO_EVERY_MS) return
        if (!online() || (prefs.driveWifiOnly && !unmetered())) return
        sync(busy)
    }

    fun sync(busy: Set<String>) {
        if (!configured || !signedIn) return
        if (!DriveSync.running.compareAndSet(false, true)) return
        running = true
        status = "Синхронизация…"
        scope.launch {
            val result = runCatching {
                if (!online()) error("Нет интернета")
                val refresh = prefs.driveRefreshToken ?: error("Войдите в Google")
                val token = { DriveClient.refresh(clientId, secret, refresh).access }
                val api = DriveClient(token, token)
                DriveSync(repo, api, stateFile(), context.cacheDir).run(busy)
            }
            DriveSync.running.set(false)
            val now = System.currentTimeMillis()
            val text = result.fold(
                { "Синхронизировано: $it" },
                { e ->
                    val m = e.message.orEmpty()
                    when {
                        // The sign-in was taken back (or ran out): sign in again.
                        m.contains("invalid_grant") -> "Вход в Google больше не действует — войдите снова"
                        else -> "Не удалось: ${m.take(200)}"
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
