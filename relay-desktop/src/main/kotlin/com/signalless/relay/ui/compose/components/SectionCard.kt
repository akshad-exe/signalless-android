package com.signalless.relay.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.signalless.relay.ui.compose.RelayColors

/** Rounded, bordered surface used by the Links, Traffic, and Events sections. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(RelayColors.Surface)
            .border(1.dp, RelayColors.Border, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (compact) 9.dp else 12.dp,
                    bottom = if (compact) 5.dp else 8.dp,
                ),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = RelayColors.Muted,
            )
            Spacer(Modifier.weight(1f))
            trailing()
        }
        content()
    }
}
