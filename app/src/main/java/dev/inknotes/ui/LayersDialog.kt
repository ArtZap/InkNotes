package dev.inknotes.ui

import dev.inknotes.data.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.inknotes.data.Layers

/**
 * Layers of the notebook: which one new writing goes onto, which are shown, which are locked
 * (not erased, selected or moved), and moving the selection onto one.
 */
@Composable
fun LayersDialog(c: EditorController, onDismiss: () -> Unit) {
    var naming by remember { mutableStateOf<Int?>(null) }
    val layers = c.notebook.layerList()
    val selection = c.selectionCount > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Слои", "Layers")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (selection) tr("Коснитесь «Сюда», чтобы перенести выделенное на слой.", "Tap “Here” to move the selection to a layer.")
                    else tr("Новое пишется на отмеченный слой. Скрытые и закреплённые слои не стираются и не выделяются.", "New writing goes onto the marked layer. Hidden and locked layers are not erased or selected."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                for ((i, layer) in layers.withIndex()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = i == c.notebook.activeLayer, onClick = { c.setActiveLayer(i) })
                        Text(
                            layer.name.ifEmpty { tr("Слой ${i + 1}", "Layer ${i + 1}") },
                            Modifier.weight(1f).clickable { naming = i }.padding(vertical = 8.dp),
                            fontWeight = if (i == c.notebook.activeLayer) FontWeight.Medium else FontWeight.Normal,
                        )
                        FilterChip(selected = layer.visible, onClick = { c.toggleLayerVisible(i) }, label = { Text(if (layer.visible) tr("Виден", "Shown") else tr("Скрыт", "Hidden")) })
                        FilterChip(selected = layer.locked, onClick = { c.toggleLayerLocked(i) }, label = { Text(tr("Закреплён", "Locked")) }, modifier = Modifier.padding(start = 4.dp))
                        if (selection) TextButton(onClick = { c.moveSelectionToLayer(i); onDismiss() }) { Text(tr("Сюда", "Here")) }
                        if (i > 0) TextButton(onClick = { c.deleteLayer(i) }) { Text("✕") }
                    }
                    HorizontalDivider()
                }
                Text(
                    tr("Удалённый слой не стирает записи: они переходят на основной слой.", "Deleting a layer does not erase its writing: it moves to the main layer."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(enabled = layers.size < Layers.MAX, onClick = { naming = -1 }) { Text(tr("Новый слой", "New layer")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Закрыть", "Close")) } },
    )
    naming?.let { i ->
        var name by remember(i) { mutableStateOf(if (i >= 0) layers.getOrNull(i)?.name.orEmpty() else tr("Слой ${layers.size + 1}", "Layer ${layers.size + 1}")) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (i >= 0) tr("Название слоя", "Layer name") else tr("Новый слой", "New layer")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                Button(onClick = {
                    if (i >= 0) c.renameLayer(i, name) else c.addLayer(name)
                    naming = null
                }) { Text(tr("Готово", "Done")) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}
