package moe.gbxin.saltreader.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SaltLoading(
    modifier: Modifier = Modifier,
    sizeDp: Int = 36,
    color: Color = LocalContentColor.current,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(sizeDp.dp), color = color)
    }
}
