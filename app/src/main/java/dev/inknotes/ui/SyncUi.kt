package dev.inknotes.ui

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
        title = { Text("Google Диск") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !cloud.configured -> {
                        Text("Эта сборка сделана без ключа Google.", fontWeight = FontWeight.Medium)
                        Text(
                            "Чтобы включить синхронизацию, впишите в local.properties проекта google.clientId и google.clientSecret " +
                                "(клиент OAuth «Приложение для ПК» в Google Cloud, с включённым Google Drive API) и соберите приложение заново. " +
                                "Подробно — в README, раздел «Google Диск».",
                            fontSize = 13.sp,
                            color = muted,
                        )
                    }
                    !cloud.signedIn -> {
                        Text(
                            "Каждая тетрадь хранится на Диске файлом в папке InkNotes. Изменения идут в обе стороны: " +
                                "на другом планшете с тем же аккаунтом будут те же тетради. Приложение видит на Диске только свои файлы.",
                            fontSize = 13.sp,
                            color = muted,
                        )
                        Text("Вход откроется в браузере; Google-сервисы на устройстве не нужны.", fontSize = 13.sp, color = muted)
                    }
                    else -> {
                        Text("Аккаунт: ${cloud.email ?: "Google"}", fontWeight = FontWeight.Medium)
                        if (cloud.lastSync > 0) {
                            Text(
                                "Последняя синхронизация: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(cloud.lastSync)),
                                fontSize = 13.sp,
                                color = muted,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Сама")
                                Text("При открытии и сворачивании приложения", fontSize = 12.sp, color = muted)
                            }
                            Switch(checked = auto, onCheckedChange = { auto = it; prefs.driveAuto = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Только по Wi-Fi")
                                Text("Сама — только без мобильного трафика", fontSize = 12.sp, color = muted)
                            }
                            Switch(checked = wifi, onCheckedChange = { wifi = it; prefs.driveWifiOnly = it })
                        }
                        HorizontalDivider()
                        Text(
                            "Тетрадь, изменённая в двух местах, сохраняется дважды — ничего не теряется. " +
                                "Удалённая на одном устройстве уходит в корзину на другом. Папки библиотеки не синхронизируются.",
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
            }
        },
        confirmButton = {
            when {
                !cloud.configured -> TextButton(onClick = onDismiss) { Text("Понятно") }
                !cloud.signedIn -> Button(enabled = !cloud.signingIn, onClick = { cloud.signIn(context) }) { Text("Войти через Google") }
                else -> Button(enabled = !cloud.running, onClick = { cloud.sync(busy()) }) { Text("Синхронизировать") }
            }
        },
        dismissButton = {
            Row {
                if (cloud.signedIn) OutlinedButton(onClick = cloud::signOut) { Text("Выйти") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}
