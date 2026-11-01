package com.signalless.relay.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalless.relay.ui.compose.RelayColors

/** Failure banner with the technical detail and a retry path. */
@Composable
fun ErrorCard(
    message: String,
    onRetry: (() -> Unit)?,
    onOpenLog: (() -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RelayColors.Error.copy(alpha = 0.08f))
            .border(1.dp, RelayColors.Error.copy(alpha = 0.35f), shape)
            .padding(if (compact) 8.dp else 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(RelayColors.Error),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Relay needs attention",
                color = RelayColors.Error,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.size(if (compact) 3.dp else 5.dp))

        SelectionContainer {
            Text(
                text = message,
                fontFamily = FontFamily.Monospace,
                fontSize = if (compact) 10.sp else 11.sp,
                lineHeight = if (compact) 13.sp else 15.sp,
                color = RelayColors.Text,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (onRetry != null || onOpenLog != null) {
            Spacer(Modifier.size(if (compact) 5.dp else 9.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onOpenLog != null) {
                    OutlinedButton(
                        onClick = onOpenLog,
                        modifier = Modifier.height(if (compact) 28.dp else 30.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("Open log", fontSize = if (compact) 10.sp else 11.sp)
                    }
                }
                if (onRetry != null) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.height(if (compact) 28.dp else 30.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RelayColors.Error,
                            contentColor = RelayColors.Background,
                        ),
                    ) {
                        Text("Try again", fontSize = if (compact) 10.sp else 11.sp)
                    }
                }
            }
        }
    }
}
