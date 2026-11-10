package moe.gbxin.saltreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CustomColorPickerDialog(
    initialColor: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val initial = Color(initialColor)
    var red by remember { mutableIntStateOf((initial.red * 255f).roundToInt()) }
    var green by remember { mutableIntStateOf((initial.green * 255f).roundToInt()) }
    var blue by remember { mutableIntStateOf((initial.blue * 255f).roundToInt()) }
    var hex by remember { mutableStateOf(rgbHex(red, green, blue)) }

    // Only a complete #RRGGBB input is accepted; anything else is shown as an error and cannot
    // be saved, instead of silently keeping the previous color.
    val parsedHex = parseHex(hex)
    val hexValid = parsedHex != null
    val preview = Color(red, green, blue)
    val hexSupporting: (@Composable () -> Unit)? = if (hexValid) {
        null
    } else {
        { Text("请输入 #RRGGBB 格式的颜色") }
    }

    fun applyValidHex() {
        val parsed = parseHex(hex) ?: return
        red = (parsed shr 16) and 0xFF
        green = (parsed shr 8) and 0xFF
        blue = parsed and 0xFF
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义背景") },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(preview),
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = hex,
                    onValueChange = { raw ->
                        hex = raw.uppercase(Locale.ROOT).take(7)
                        applyValidHex()
                    },
                    label = { Text("十六进制") },
                    placeholder = { Text("#RRGGBB") },
                    singleLine = true,
                    isError = !hexValid,
                    supportingText = hexSupporting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                RgbSlider("R", red) {
                    red = it
                    hex = rgbHex(red, green, blue)
                }
                RgbSlider("G", green) {
                    green = it
                    hex = rgbHex(red, green, blue)
                }
                RgbSlider("B", blue) {
                    blue = it
                    hex = rgbHex(red, green, blue)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = hexValid,
                onClick = {
                    val argb = parsedHex ?: ((0xFF shl 24) or (red shl 16) or (green shl 8) or blue)
                    onConfirm(argb.toLong() and 0xFFFFFFFFL)
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RgbSlider(channel: String, value: Int, onChange: (Int) -> Unit) {
    Text("$channel  $value", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // Material3 1.5 state-based Slider: state owns the thumb; follow external value changes
    // (hex input updates R/G/B) unless the user is dragging.
    val sliderState = rememberSliderState(value = value.toFloat(), trackRange = 0f..255f)
    LaunchedEffect(value) {
        if (!sliderState.isDragging) sliderState.value = value.toFloat()
    }
    Slider(
        state = sliderState,
        onValueChange = { raw ->
            // With an onValueChange callback the state is not updated automatically.
            sliderState.value = raw
            onChange(raw.roundToInt().coerceIn(0, 255))
        },
    )
}

/** Parses "#RRGGBB" / "RRGGBB" (case insensitive) into an RGB int, or null when invalid. */
private fun parseHex(input: String): Int? {
    val normalized = input.trim().removePrefix("#")
    if (normalized.length != 6) return null
    return normalized.toIntOrNull(16)
}

private fun rgbHex(red: Int, green: Int, blue: Int): String =
    String.format(Locale.ROOT, "#%02X%02X%02X", red, green, blue)
