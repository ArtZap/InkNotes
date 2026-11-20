package dev.inknotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.data.tr
import dev.inknotes.search.Spelling

/**
 * A word underlined as maybe misspelt: what the spell checker suggests. The ink stays as
 * written (rewrite it by hand); «Это верно» stops marking the word, here and later.
 */
@Composable
fun TypoDialog(c: EditorController, typo: Spelling.Typo) {
    AlertDialog(
        onDismissRequest = { c.tappedTypo = null },
        title = { Text("«${typo.word}»") },
        text = {
            Column {
                Text(tr("Возможно, ошибка или слово прочитано неверно.", "Maybe a mistake, or the word was read wrong."), fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                if (typo.suggestions.isEmpty()) {
                    Text(tr("Вариантов нет", "No suggestions"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(tr("Может быть:", "Perhaps:"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    for (s in typo.suggestions) Text(s, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { c.tappedTypo = null }) { Text(tr("Закрыть", "Close")) } },
        dismissButton = { TextButton(onClick = { c.ignoreTypo(typo.word) }) { Text(tr("Это верно", "It is right")) } },
    )
}
