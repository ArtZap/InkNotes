package dev.inknotes.ui

import dev.inknotes.data.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/** Google Drive: sign in, sync now, by itself or not, sign out. */
@Composable
fun SyncDialog(cloud: CloudSync, prefs: AppPrefs, busy: () -> Set<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var auto by remember { mutableStateOf(prefs.driveAuto) }
    var wifi by remember { mutableStateOf(prefs.driveWifiOnly) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(InkIcons.Cloud, null) },
        title = { Text(tr("Google Диск", "Google Drive")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !cloud.configured -> {
                        Text(tr("Эта сборка сделана без ключа Google.", "This build was made without a Google key."), fontWeight = FontWeight.Medium)
                        Text(
                            tr("Чтобы включить синхронизацию, впишите в local.properties проекта google.clientId и google.clientSecret ", "To turn on syncing, put google.clientId and google.clientSecret in the project's local.properties ") +
                                tr("(клиент OAuth «Приложение для ПК» в Google Cloud, с включённым Google Drive API) и соберите приложение заново. ", "(an OAuth “Desktop app” client in Google Cloud, with the Google Drive API enabled) and build the app again. ") +
                                tr("Подробно — в README, раздел «Google Диск».", "Details are in the README, section “Google Drive”."),
                            fontSize = 13.sp,
                            color = muted,
                        )
                    }
                    !cloud.signedIn -> {
                        Text(
                            tr("Каждая тетрадь хранится на Диске файлом в папке InkNotes. Изменения идут в обе стороны: ", "Each notebook is kept on the Drive as a file in the InkNotes folder. Changes go both ways: ") +
                                tr("на другом планшете с тем же аккаунтом будут те же тетради. Приложение видит на Диске только свои файлы.", "another tablet with the same account has the same notebooks. The app sees only its own files on the Drive."),
                            fontSize = 13.sp,
                            color = muted,
                        )
                        Text(tr("Вход откроется в браузере; Google-сервисы на устройстве не нужны.", "Sign-in opens in the browser; Google services are not needed on the device."), fontSize = 13.sp, color = muted)
                    }
                    else -> {
                        Text(tr("Аккаунт: ${cloud.email ?: "Google"}", "Account: ${cloud.email ?: "Google"}"), fontWeight = FontWeight.Medium)
                        if (cloud.lastSync > 0) {
                            Text(
                                tr("Последняя синхронизация: ", "Last sync: ") + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(cloud.lastSync)),
                                fontSize = 13.sp,
                                color = muted,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("Сама", "Automatic"))
                                Text(tr("При открытии и сворачивании приложения", "When the app is opened and put away"), fontSize = 12.sp, color = muted)
                            }
                            Switch(checked = auto, onCheckedChange = { auto = it; prefs.driveAuto = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("Только по Wi-Fi", "Wi-Fi only"))
                                Text(tr("Сама — только без мобильного трафика", "Automatic — only without mobile data"), fontSize = 12.sp, color = muted)
                            }
                            Switch(checked = wifi, onCheckedChange = { wifi = it; prefs.driveWifiOnly = it })
                        }
                        HorizontalDivider()
                        Text(
                            tr("Тетрадь, изменённая в двух местах, сохраняется дважды — ничего не теряется. ", "A notebook changed in two places is kept twice — nothing is lost. ") +
                                tr("Удалённая на одном устройстве уходит в корзину на другом. Папки библиотеки не синхронизируются.", "One deleted on one device goes to the trash on the other. Library folders are not synced."),
                            fontSize = 12.sp,
                            color = muted,
                        )
                    }
                }
                cloud.status?.let { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (cloud.running || cloud.signingIn) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(s, fontSize = 13.sp)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(cloud.held.isNotEmpty() && !cloud.running) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text(tr("Пропали сразу ${cloud.held.size} тетрадей", "${cloud.held.size} notebooks vanished at once"), fontWeight = FontWeight.Medium)
                    Text(
                        cloud.held.take(5).joinToString(", ") { "«${it.second}»" } + (if (cloud.held.size > 5) tr(" и другие", " and others") else "") +
                            tr(". Их нет на одной стороне — на Диске или на этом планшете. Так бывает, если папку InkNotes удалили или подменили ", ". They are missing on one side — on the Drive or on this tablet. This happens if the InkNotes folder was deleted or replaced ") +
                            tr("или память планшета не прочиталась. Пока ничего не удалено.", "or the tablet's storage could not be read. Nothing has been deleted yet."),
                        fontSize = 13.sp,
                        color = muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { cloud.resolveHeld(trash = false) }) { Text(tr("Оставить везде", "Keep everywhere")) }
                        OutlinedButton(onClick = { cloud.resolveHeld(trash = true) }) { Text(tr("Удалить везде", "Delete everywhere")) }
                    }
                } }
            }
        },
        confirmButton = {
            when {
                !cloud.configured -> TextButton(onClick = onDismiss) { Text(tr("Понятно", "Got it")) }
                !cloud.signedIn -> Button(enabled = !cloud.signingIn, onClick = { cloud.signIn(context) }) { Text(tr("Войти через Google", "Sign in with Google")) }
                else -> Button(enabled = !cloud.running, onClick = { cloud.sync(busy()) }) { Text(tr("Синхронизировать", "Sync now")) }
            }
        },
        dismissButton = {
            Row {
                if (cloud.signedIn) OutlinedButton(onClick = cloud::signOut) { Text(tr("Выйти", "Sign out")) }
                if (cloud.configured) TextButton(onClick = onDismiss) { Text(tr("Закрыть", "Close")) }
            }
        },
    )
}
