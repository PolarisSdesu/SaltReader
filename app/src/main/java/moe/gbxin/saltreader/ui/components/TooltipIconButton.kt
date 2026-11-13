package moe.gbxin.saltreader.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.withTimeoutOrNull

/** Icon button with Material 3 plain tooltip on long press + long-press haptic. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 顶部栏按钮设为 true：tooltip 向下展开，避让状态栏安全区。 */
    anchorBelow: Boolean = false,
) {
    val hostView = LocalView.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        TooltipBox(
            positionProvider = if (anchorBelow) {
                TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Below,
                )
            } else {
                TooltipDefaults.rememberPlainTooltipPositionProvider()
            },
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.pointerInput(label) {
                    // 长按震动：只监听不消费事件，tooltip/点击保持官方行为。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var released = false
                        val timeout = viewConfiguration.longPressTimeoutMillis
                        val timedOut = withTimeoutOrNull(timeout) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    released = true
                                    break
                                }
                                if ((change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    moved = true
                                    break
                                }
                            }
                            null
                        }
                        if (timedOut == null && !moved && !released) {
                            hostView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    }
                },
            ) {
                Icon(icon, contentDescription = label)
            }
        }
    }
}
