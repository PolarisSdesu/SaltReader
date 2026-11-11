package moe.gbxin.saltreader.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import moe.gbxin.saltreader.R

/** Quiet battery read-out for the lower-left reader corner. */
@Composable
internal fun BatteryIndicator(
    textSizeSp: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val filter = remember { IntentFilter(Intent.ACTION_BATTERY_CHANGED) }
    var status by remember {
        mutableStateOf(batteryStatus(context.registerReceiver(null, filter)) ?: BatteryStatus(0, false))
    }
    DisposableEffect(context, filter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                status = batteryStatus(intent) ?: status
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val icon = when {
        status.charging -> R.drawable.battery_android_frame_bolt
        status.percent >= 100 -> R.drawable.battery_android_full
        else -> when (((status.percent * 6 + 99) / 100).coerceIn(1, 6)) {
            1 -> R.drawable.battery_android_1
            2 -> R.drawable.battery_android_2
            3 -> R.drawable.battery_android_3
            4 -> R.drawable.battery_android_4
            5 -> R.drawable.battery_android_5
            else -> R.drawable.battery_android_6
        }
    }
    val iconSize = with(LocalDensity.current) { textSizeSp.sp.toDp() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(iconSize),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            stringResource(R.string.reader_percent, status.percent),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = textSizeSp.sp,
                fontFeatureSettings = "tnum",
            ),
            color = color,
        )
    }
}

private data class BatteryStatus(val percent: Int, val charging: Boolean)

private fun batteryStatus(intent: Intent?): BatteryStatus? {
    if (intent == null) return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val chargeState = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    return BatteryStatus(
        percent = (level * 100 / scale).coerceIn(0, 100),
        charging = chargeState == BatteryManager.BATTERY_STATUS_CHARGING ||
            chargeState == BatteryManager.BATTERY_STATUS_FULL,
    )
}
